package ru.inversion.tc.jdbc.internal.proxy;

import ru.inversion.tc.jdbc.event.JdbcEvent;
import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.event.JdbcStatementEvent;
import ru.inversion.tc.jdbc.internal.JdbcObjectId;
import ru.inversion.tc.jdbc.internal.lifecycle.JdbcLifecycleManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;


/**
 * JDBC Statement / PreparedStatement / CallableStatement proxy.
 *
 * Tracks only Statement-owned cursor ResultSet.
 *
 * Arbitrary JDBC ResultSet such as generated keys are not
 * registered in JdbcLifecycleManager.
 */
public final class JdbcStatementProxy
        implements InvocationHandler
{
   private final Statement statement;

   /*
    * Именно proxy Connection.
    *
    * Statement.getConnection() никогда
    * не должен возвращать raw Connection.
    */
   private final JdbcConnectionProxy  connection;

   private final JdbcLifecycleManager lifecycle;
   private final JdbcEventBus eventBus;

   private final long statementId;

   /*
    * Для PreparedStatement / CallableStatement.
    *
    * Для обычного Statement == null.
    */
   private final String sql;

   private final boolean prepared;
   private final boolean callable;

   /*
    * Последние успешно установленные
    * positional IN parameters.
    */
   private final Map<Integer, Object> inParameters =
           new TreeMap<>();

   /*
    * Positional OUT parameters CallableStatement.
    */
   private final Set<Integer> outParameters =
           new TreeSet<>();

   /*
    * Только Statement-owned cursor ResultSet.
    *
    * Generated keys сюда НЕ входят.
    *
    * Identity semantics обязательны.
    */
   private final Map<ResultSet, JdbcResultSetProxy> cursorResultSets = new IdentityHashMap<>();

   private Statement proxy;

   /*
    * Lifecycle state proxy-а.
    */
   private boolean closed;


   /** */
   private JdbcStatementProxy(
           Statement statement,
           JdbcConnectionProxy connection,
           String sql,
           JdbcLifecycleManager lifecycle,
           JdbcEventBus eventBus
   )
   {
      if( statement == null )
      {
         throw new IllegalArgumentException(
                 "statement is null"
         );
      }

      if( connection == null )
      {
         throw new IllegalArgumentException(
                 "connection is null"
         );
      }

      if( lifecycle == null )
      {
         throw new IllegalArgumentException(
                 "lifecycle is null"
         );
      }

      this.statement = statement;
      this.connection = connection;
      this.lifecycle = lifecycle;
      this.eventBus = eventBus;
      this.sql = sql;

      prepared =
              statement instanceof PreparedStatement;

      callable =
              statement instanceof CallableStatement;

      statementId =
              lifecycle.nextStatementId();
   }


   /** */
   static JdbcStatementProxy create(
           Statement statement,
           JdbcConnectionProxy connection,
           String sql,
           JdbcLifecycleManager lifecycle,
           JdbcEventBus eventBus
   )
   {
      JdbcStatementProxy handler =
              new JdbcStatementProxy(
                      statement,
                      connection,
                      sql,
                      lifecycle,
                      eventBus
              );

      Class<?> jdbcInterface;

      if( statement instanceof CallableStatement )
         jdbcInterface = CallableStatement.class;
      else if( statement instanceof PreparedStatement )
         jdbcInterface = PreparedStatement.class;
      else
         jdbcInterface = Statement.class;

      handler.proxy =
              (Statement) Proxy.newProxyInstance(
                      JdbcStatementProxy.class.getClassLoader(),
                      new Class<?>[] { jdbcInterface },
                      handler
              );

      /*
       * fireOpen() здесь НЕ вызываем.
       *
       * Сначала ConnectionProxy должен
       * зарегистрировать owner.
       */
      return handler;
   }

   /** */
   public long statementId()
   {
      return statementId;
   }


   /** */
   Statement proxy()
   {
      return proxy;
   }


   /** */
   Statement raw()
   {
      return statement;
   }


   /**
    * Локальный lifecycle state.
    */
   boolean isLifecycleClosed()
   {
      return closed;
   }


   @Override
   public Object invoke(
           Object proxy,
           Method method,
           Object[] args
   )
           throws Throwable
   {
      String methodName =
              method.getName();

      /*
       * Object identity proxy-а не зависит
       * от equals/hashCode JDBC driver-а.
       */
      if( Object.class.equals(
              method.getDeclaringClass()
      ) )
      {
         return invokeObjectMethod(
                 proxy,
                 methodName,
                 args
         );
      }

      /*
       * Statement.close()
       */
      if( "close".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         close();

         return null;
      }

      /*
       * Statement.isClosed()
       */
      if( "isClosed".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         return isClosed();
      }

      /*
       * Никогда не выпускаем raw Connection.
       */
      if( "getConnection".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         return connection.proxy();
      }

      /*
       * Current Statement ResultSet.
       *
       * Это cursor candidate -> track.
       */
      if( "getResultSet".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         ResultSet raw =
                 (ResultSet) invokeRaw(
                         method,
                         args
                 );

         return wrapCursorResultSet(
                 raw
         );
      }

      /*
       * Generated keys являются JDBC ResultSet,
       * но НЕ входят в наш cursor lifecycle.
       *
       * Возвращаем raw ResultSet.
       */
      if( "getGeneratedKeys".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         return invokeRaw(
                 method,
                 args
         );
      }

      /*
       * getMoreResults()
       * getMoreResults(int)
       */
      if( "getMoreResults".equals(methodName) )
      {
         return getMoreResults(
                 method,
                 args
         );
      }

      /*
       * unwrap(proxy-compatible interface)
       * оставляет клиента внутри proxy.
       *
       * Vendor-specific unwrap делегируем driver-у.
       */
      if( "unwrap".equals(methodName)
              && args != null
              && args.length == 1 )
      {
         Class<?> clazz =
                 (Class<?>) args[0];

         if( clazz.isInstance(proxy) )
            return clazz.cast(proxy);
      }

      if( "isWrapperFor".equals(methodName)
              && args != null
              && args.length == 1 )
      {
         Class<?> clazz =
                 (Class<?>) args[0];

         if( clazz.isInstance(proxy) )
            return true;
      }

      /*
       * PreparedStatement.setXXX(int,...)
       */
      if( isParameterSetter(
              method,
              args
      ) )
      {
         return setParameter(
                 method,
                 args
         );
      }

      /*
       * PreparedStatement.clearParameters()
       */
      if( prepared
              && "clearParameters".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         Object value =
                 invokeRaw(
                         method,
                         args
                 );

         inParameters.clear();

         /*
          * OUT registrations здесь не чистим.
          */
         return value;
      }

      /*
       * CallableStatement.registerOutParameter(int,...)
       *
       * Named OUT parameters пока только делегируются.
       */
      if( callable
              && "registerOutParameter".equals(methodName)
              && args != null
              && args.length > 0
              && args[0] instanceof Integer )
      {
         Object value =
                 invokeRaw(
                         method,
                         args
                 );

         outParameters.add(
                 (Integer) args[0]
         );

         return value;
      }

      /*
       * execute()
       * executeQuery()
       * executeUpdate()
       * executeBatch()
       * executeLargeUpdate()
       * executeLargeBatch()
       * ...
       */
      if( methodName.startsWith("execute") )
      {
         return execute(
                 method,
                 args
         );
      }

      /*
       * В том числе сюда естественно попадают:
       *
       * closeOnCompletion()
       * isCloseOnCompletion()
       *
       * Мы не моделируем closeOnCompletion самостоятельно.
       */
      return invokeRaw(
              method,
              args
      );
   }


   /**
    * execute* operation.
    */
   private Object execute(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      String methodName =
              method.getName();

      String executedSql =
              sql(args);

      fireBeforeExecute(
              methodName,
              executedSql
      );

      long started =
              System.nanoTime();

      Object value;

      try
      {
         value =
                 invokeRaw(
                         method,
                         args
                 );
      }
      catch( Throwable throwable )
      {
         long duration =
                 System.nanoTime() - started;

         /*
          * Driver мог закрыть предыдущий current cursor
          * даже при ошибке execute.
          */
         reconcileCursorResultSets();

         /*
          * Statement также мог оказаться закрыт
          * по любой driver/JDBC причине.
          */
         syncClosedState();

         fireExecuteError(
                 methodName,
                 executedSql,
                 duration,
                 throwable
         );

         /*
          * НИКАКОГО rollback здесь.
          */
         throw throwable;
      }

      long duration =
              System.nanoTime() - started;

      /*
       * Новый cursor регистрируем ДО reconcile старых.
       *
       * Иначе может возникнуть ложное:
       *
       * openCursorCount == 0
       *
       * между старым и новым ResultSet.
       */
      JdbcResultSetProxy newCursor = null;

      if( value instanceof ResultSet )
      {
         /*
          * executeQuery()
          */
         newCursor =
                 registerCursorResultSet(
                         (ResultSet) value
                 );
      }
      else if( Boolean.TRUE.equals(value) )
      {
         /*
          * Statement.execute() == true.
          *
          * Регистрируем current ResultSet сразу,
          * даже если пользователь ещё не вызвал
          * getResultSet().
          */
         ResultSet current =
                 statement.getResultSet();

         newCursor =
                 registerCursorResultSet(
                         current
                 );
      }

      /*
       * Теперь безопасно снять закрытые старые cursor-ы.
       */
      reconcileCursorResultSets();

      /*
       * Не моделируем причину закрытия Statement.
       * Просто синхронизируем факт.
       */
      syncClosedState();

      /*
       * Lifecycle registration нового cursor-а
       * уже существует.
       *
       * Event OPEN отправляем после AFTER execute.
       */
      fireAfterExecute(
              methodName,
              executedSql,
              duration
      );

      if( newCursor != null )
         newCursor.fireOpen();

      /*
       * executeQuery() наружу должен вернуть proxy.
       */
      if( value instanceof ResultSet
              && newCursor != null )
      {
         return newCursor.proxy();
      }

      return value;
   }


   /**
    * Statement.getMoreResults()
    * Statement.getMoreResults(int)
    */
   private Object getMoreResults(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      Object value;

      try
      {
         value =
                 invokeRaw(
                         method,
                         args
                 );
      }
      catch( Throwable throwable )
      {
         /*
          * Даже failed transition мог изменить
          * состояние предыдущих ResultSet.
          */
         reconcileCursorResultSets();

         syncClosedState();

         throw throwable;
      }

      JdbcResultSetProxy newCursor = null;

      /*
       * При наличии нового ResultSet сначала
       * регистрируем его.
       *
       * Это также корректно работает с
       * KEEP_CURRENT_RESULT:
       *
       * предыдущий ResultSet остаётся raw isClosed()==false
       * и reconcile его не снимет.
       */
      if( Boolean.TRUE.equals(value) )
      {
         ResultSet current =
                 statement.getResultSet();

         newCursor =
                 registerCursorResultSet(
                         current
                 );
      }

      reconcileCursorResultSets();

      syncClosedState();

      if( newCursor != null )
         newCursor.fireOpen();

      return value;
   }


   /**
    * PreparedStatement.setXXX(int,...).
    */
   private Object setParameter(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      /*
       * Сначала driver.
       *
       * Если setXXX выбросил SQLException,
       * наш parameter state не меняется.
       */
      Object value =
              invokeRaw(
                      method,
                      args
              );

      int index =
              (Integer) args[0];

      if( "setNull".equals(method.getName()) )
      {
         inParameters.put(
                 index,
                 null
         );
      }
      else
      {
         inParameters.put(
                 index,
                 args.length > 1
                         ? args[1]
                         : null
         );
      }

      return value;
   }


   /**
    * Определяет positional PreparedStatement setter
    * без ручного списка SET_METHODS.
    */
   private static boolean isParameterSetter(
           Method method,
           Object[] args
   )
   {
      if( args == null
              || args.length == 0 )
      {
         return false;
      }

      if( !(args[0] instanceof Integer) )
         return false;

      if( !method.getName().startsWith("set") )
         return false;

      /*
       * Statement.setFetchSize(),
       * Statement.setMaxRows(),
       * Statement.setQueryTimeout()
       *
       * сюда не проходят.
       */
      return PreparedStatement.class.isAssignableFrom(
              method.getDeclaringClass()
      );
   }


   /**
    * Пользовательский Statement.getResultSet().
    */
   private ResultSet wrapCursorResultSet(
           ResultSet resultSet
   )
   {
      if( resultSet == null )
         return null;

      JdbcResultSetProxy handler =
              registerCursorResultSet(
                      resultSet
              );

      /*
       * Если driver уже считает ResultSet закрытым,
       * cursor lifecycle не создаётся.
       */
      if( handler == null )
         return resultSet;

      handler.fireOpen();

      return handler.proxy();
   }


   /**
    * Регистрирует только Statement-owned cursor ResultSet.
    *
    * OPEN event здесь НЕ отправляется.
    */
   private JdbcResultSetProxy registerCursorResultSet(
           ResultSet resultSet
   )
   {
      if( resultSet == null )
         return null;

      JdbcResultSetProxy current =
              cursorResultSets.get(
                      resultSet
              );

      if( current != null )
      {
         if( !current.isLifecycleClosed() )
            return current;

         cursorResultSets.remove(
                 resultSet
         );
      }

      /*
       * Уже закрытый ResultSet lifecycle
       * не регистрируем.
       */
      if( isRawResultSetClosed(resultSet) )
         return null;

      JdbcResultSetProxy handler =
              JdbcResultSetProxy.create(
                      resultSet,
                      this,
                      statementId,
                      lifecycle,
                      eventBus
              );

      cursorResultSets.put(
              resultSet,
              handler
      );

      return handler;
   }


   /**
    * ResultSetProxy сообщает owner-у,
    * что его lifecycle закончился.
    *
    * Identity handler-а защищает от stale proxy.
    */
   void cursorResultSetClosed(
           JdbcResultSetProxy resultSet
   )
   {
      JdbcResultSetProxy current =
              cursorResultSets.get(
                      resultSet.raw()
              );

      if( current == resultSet )
      {
         cursorResultSets.remove(
                 resultSet.raw()
         );
      }
   }


   /**
    * Синхронизирует ResultSet, которые driver
    * закрыл без вызова нашего ResultSetProxy.close().
    */
   private void reconcileCursorResultSets()
   {
      if( cursorResultSets.isEmpty() )
         return;

      ArrayList<JdbcResultSetProxy> snapshot =
              new ArrayList<>(
                      cursorResultSets.values()
              );

      for( JdbcResultSetProxy resultSet : snapshot )
      {
         if( resultSet.isLifecycleClosed() )
         {
            cursorResultSetClosed(
                    resultSet
            );

            continue;
         }

         if( isRawResultSetClosed(
                 resultSet.raw()
         ) )
         {
            /*
             * closedByStatement() выполняет:
             *
             * unregister lifecycle
             * release ResultSet ID slot
             * owner callback
             * RESULT_SET_CLOSE event
             * Statement closed-state sync
             */
            resultSet.closedByStatement();
         }
      }
   }


   /**
    * Statement уже физически закрыт driver-ом.
    *
    * Синхронизируем все tracked ResultSet.
    */
   private void closeCursorResultSetsByStatement()
   {
      if( cursorResultSets.isEmpty() )
         return;

      ArrayList<JdbcResultSetProxy> snapshot =
              new ArrayList<>(
                      cursorResultSets.values()
              );

      /*
       * Сначала очищаем owner cache.
       *
       * Callback из ResultSetProxy после этого
       * становится безопасным no-op.
       */
      cursorResultSets.clear();

      for( JdbcResultSetProxy resultSet : snapshot )
      {
         resultSet.closedByStatement();
      }
   }


   /**
    * Явный Statement.close().
    */
   private void close()
           throws Throwable
   {
      if( closed )
         return;

      try
      {
         statement.close();
      }
      catch( Throwable throwable )
      {
         /*
          * close() мог закрыть часть ResultSet
          * и затем выбросить SQLException.
          */
         reconcileCursorResultSets();

         /*
          * Сам Statement также мог уже
          * физически закрыться.
          */
         if( isRawStatementClosed() )
            statementClosed();

         throw throwable;
      }

      statementClosed();
   }


   /**
    * Единственная точка завершения
    * Statement lifecycle нашего proxy.
    */
   private void statementClosed()
   {
      if( closed )
         return;

      closed = true;

      closeCursorResultSetsByStatement();

      /*
       * Mandatory owner state.
       */
      connection.statementClosed(this);

      /*
       * Observation.
       */
      fireClose();
   }

   /**
    * Statement.isClosed().
    */
   private boolean isClosed()
           throws SQLException
   {
      if( closed )
         return true;

      boolean rawClosed =
              statement.isClosed();

      if( rawClosed )
         statementClosed();

      return rawClosed;
   }


   /**
    * Универсальная синхронизация фактического
    * состояния raw Statement.
    *
    * Нам неважно, ПОЧЕМУ driver закрыл Statement:
    *
    * - closeOnCompletion()
    * - Connection.close()
    * - driver-specific behaviour
    * - другая JDBC причина
    *
    * Proxy моделирует только факт закрытия.
    */
   void syncClosedState()
   {
      if( closed )
         return;

      if( isRawStatementClosed() )
         statementClosed();
   }


   /** */
   private boolean isRawStatementClosed()
   {
      try
      {
         return statement.isClosed();
      }
      catch( SQLException ignored )
      {
         /*
          * Консервативно считаем Statement
          * ещё открытым.
          */
         return false;
      }
   }


   /** */
   private static boolean isRawResultSetClosed(
           ResultSet resultSet
   )
   {
      try
      {
         return resultSet.isClosed();
      }
      catch( SQLException ignored )
      {
         /*
          * Если определить состояние нельзя,
          * считаем cursor открытым.
          */
         return false;
      }
   }


   /**
    * SQL конкретного execute*().
    */
   private String sql(
           Object[] args
   )
   {
      /*
       * Statement.execute*(String,...)
       */
      if( args != null
              && args.length > 0
              && args[0] instanceof String )
      {
         return (String) args[0];
      }

      /*
       * PreparedStatement / CallableStatement.
       */
      return sql;
   }


   /**
    * OUT values собираются только при наличии
    * listener-а на JdbcStatementEvent.
    */
   private Map<Integer, Object> outParameterValues()
   {
      if( !callable
              || outParameters.isEmpty() )
      {
         return Collections.emptyMap();
      }

      Map<Integer, Object> result =
              new TreeMap<>();

      CallableStatement callableStatement =
              (CallableStatement) statement;

      for( Integer index : outParameters )
      {
         try
         {
            result.put(
                    index,
                    callableStatement.getObject(index)
            );
         }
         catch( SQLException ignored )
         {
            /*
             * Diagnostics не должны ломать
             * успешный execute.
             */
         }
      }

      return result;
   }


   /** */
   private Object invokeRaw(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      try
      {
         return method.invoke(
                 statement,
                 args
         );
      }
      catch( InvocationTargetException ex )
      {
         throw ex.getCause();
      }
   }


   /** */
   private Object invokeObjectMethod(
           Object proxy,
           String methodName,
           Object[] args
   )
   {
      if( "equals".equals(methodName) )
         return proxy == args[0];

      if( "hashCode".equals(methodName) )
         return System.identityHashCode(proxy);

      if( "toString".equals(methodName) )
      {
         return "JdbcStatementProxy["
                 + JdbcObjectId.toString(statementId)
                 + "]";
      }

      throw new IllegalStateException(
              "Unsupported Object method: "
                      + methodName
      );
   }


   /** */
   private boolean hasStatementListeners()
   {
      return eventBus != null
              && eventBus.hasListeners(
              JdbcStatementEvent.class
      );
   }


   /** */
   void fireOpen()
   {
      if( !hasStatementListeners() )
         return;

      safeFire(
              JdbcStatementEvent.open(
                      proxy,
                      statementId,
                      sql
              )
      );
   }


   /** */
   private void fireBeforeExecute(
           String methodName,
           String sql
   )
   {
      if( !hasStatementListeners() )
         return;

      safeFire(
              JdbcStatementEvent.beforeExecute(
                      proxy,
                      statementId,
                      methodName,
                      sql,
                      inParameters
              )
      );
   }


   /** */
   private void fireAfterExecute(
           String methodName,
           String sql,
           long durationNanos
   )
   {
      if( !hasStatementListeners() )
         return;

      Map<Integer, Object> out =
              outParameterValues();

      safeFire(
              JdbcStatementEvent.afterExecute(
                      proxy,
                      statementId,
                      methodName,
                      sql,
                      inParameters,
                      out,
                      durationNanos
              )
      );
   }


   /** */
   private void fireExecuteError(
           String methodName,
           String sql,
           long durationNanos,
           Throwable throwable
   )
   {
      if( !hasStatementListeners() )
         return;

      safeFire(
              JdbcStatementEvent.executeError(
                      proxy,
                      statementId,
                      methodName,
                      sql,
                      inParameters,
                      durationNanos,
                      throwable
              )
      );
   }


   /** */
   private void fireClose()
   {
      if( !hasStatementListeners() )
         return;

      safeFire(
              JdbcStatementEvent.close(
                      proxy,
                      statementId,
                      sql
              )
      );
   }


   /**
    * Event listeners являются observation-only.
    *
    * RuntimeException listener-а не должна
    * изменять результат JDBC operation.
    *
    * Error намеренно не перехватываем.
    */
   private void safeFire(
           JdbcEvent event
   )
   {
      try
      {
         eventBus.fire(event);
      }
      catch( RuntimeException ignored )
      {
         /*
          * TODO logging/diagnostics
          * лучше централизовать в JdbcEventBus.
          */
      }
   }

   void syncConnectionState()
   {
      if( closed )
         return;

      reconcileCursorResultSets();

      syncClosedState();
   }

   void closedByConnection()
   {
      if( closed )
         return;

      statementClosed();
   }
}