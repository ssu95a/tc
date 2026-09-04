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
 */
public final class JdbcStatementProxy
        implements InvocationHandler
{
   private final Statement statement;

   /*
    * Именно proxy Connection.
    *
    * Statement.getConnection() никогда
    * не должен возвращать raw connection.
    */
   private final Connection connection;

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
   private final Map<ResultSet, JdbcResultSetProxy>
           cursorResultSets =
           new IdentityHashMap<>();

   private Statement proxy;

   private boolean closed;

   /*
    * Только hint для проверки driver state.
    *
    * Мы НЕ реализуем closeOnCompletion самостоятельно.
    */
   private boolean closeOnCompletion;


   /** */
   private JdbcStatementProxy(
           Statement statement,
           Connection connection,
           String sql,
           JdbcLifecycleManager lifecycle,
           JdbcEventBus eventBus
   )
   {
      if( statement == null )
         throw new IllegalArgumentException(
                 "statement is null"
         );

      if( connection == null )
         throw new IllegalArgumentException(
                 "connection is null"
         );

      if( lifecycle == null )
         throw new IllegalArgumentException(
                 "lifecycle is null"
         );

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
   public static Statement create(
           Statement statement,
           Connection connection,
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

      handler.fireOpen();

      return handler.proxy;
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
      if( Object.class.equals(method.getDeclaringClass()) )
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
         return connection;
      }

      /*
       * closeOnCompletion принадлежит driver-у.
       *
       * Наш boolean нужен только чтобы не вызывать
       * statement.isClosed() после каждого RS.close().
       */
      if( "closeOnCompletion".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         Object value =
                 invokeRaw(
                         method,
                         args
                 );

         closeOnCompletion = true;

         return value;
      }

      /*
       * Не подменяем JDBC semantics собственным boolean.
       *
       * В частности, закрытый Statement должен вернуть
       * driver SQLException согласно его реализации.
       */
      if( "isCloseOnCompletion".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         return invokeRaw(
                 method,
                 args
         );
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
       * Специально не proxy/wrap/register.
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
       * остаётся внутри proxy.
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
          * OUT registrations специально не чистим.
          *
          * clearParameters() очищает значения parameters,
          * но не является нашим сигналом отмены
          * registerOutParameter().
          */

         return value;
      }

      /*
       * CallableStatement.registerOutParameter(int,...)
       *
       * Named parameters пока только делегируются.
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
       * ...
       */
      if( methodName.startsWith("execute") )
      {
         return execute(
                 method,
                 args
         );
      }

      return invokeRaw(
              method,
              args
      );
   }


   /**
    * Execute operation.
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
              sql(
                      args
              );

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
          * даже при ошибке нового execute.
          */
         reconcileCursorResultSets();

         syncCloseOnCompletion();

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
       * ВАЖНО:
       *
       * новый cursor регистрируем ДО reconcile старого.
       *
       * Иначе:
       *
       * old cursor closed
       * openCursorCount -> 0
       * будущий auto-finish transaction
       * new cursor ещё не registered
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
          * Регистрируем current cursor немедленно,
          * даже если пользователь никогда не вызовет
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
       * Теперь можно unregister закрытые старые cursor-ы.
       */
      reconcileCursorResultSets();

      syncCloseOnCompletion();

      /*
       * Statement AFTER идёт перед RESULT_SET_OPEN event.
       *
       * При этом lifecycle-registration нового cursor-а
       * уже выполнена.
       */
      fireAfterExecute(
              methodName,
              executedSql,
              duration
      );

      if( newCursor != null )
         newCursor.fireOpen();

      /*
       * executeQuery() должен вернуть proxy ResultSet.
       */
      if( value instanceof ResultSet
              && newCursor != null )
      {
         return newCursor.proxy();
      }

      return value;
   }


   /**
    * Statement.getMoreResults().
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

         syncCloseOnCompletion();

         throw throwable;
      }

      JdbcResultSetProxy newCursor = null;

      /*
       * Если появился новый current ResultSet,
       * регистрируем его ДО unregister старых.
       *
       * KEEP_CURRENT_RESULT при этом работает:
       * старый raw ResultSet останется isClosed()==false.
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

      syncCloseOnCompletion();

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
       * Если setXXX упал, proxy state менять нельзя.
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
    * Определяем positional PreparedStatement setter
    * без ручного SET_METHODS списка.
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
       * Statement.setFetchSize()
       * Statement.setMaxRows()
       * Statement.setQueryTimeout()
       *
       * сюда не проходят.
       */
      return PreparedStatement.class.isAssignableFrom(
              method.getDeclaringClass()
      );
   }


   /**
    * Обычный пользовательский getResultSet().
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
       * Уже закрытый driver ResultSet
       * lifecycle не регистрируем.
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
       * Если driver уже считает RS закрытым,
       * cursor lifecycle не создаём.
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
    * что его lifecycle завершён.
    *
    * Identity check защищает от stale proxy.
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
    * Убирает cursor ResultSet, которые driver
    * уже закрыл без ResultSetProxy.close().
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
             * closedByStatement() сам:
             *
             * - unregister lifecycle
             * - release R slot
             * - remove owner cache
             * - fire RESULT_SET_CLOSE
             */
            resultSet.closedByStatement();
         }
      }
   }


   /**
    * Statement.close() физически закрывает
    * все dependent ResultSet.
    *
    * Здесь только синхронизируем наш lifecycle.
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
       * callback от ResultSetProxy после этого
       * станет безопасным no-op.
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
          * Driver мог реально закрыть Statement
          * и одновременно бросить SQLException.
          */
         if( isRawStatementClosed() )
            statementClosed();

         throw throwable;
      }

      statementClosed();
   }


   /**
    * Единственная точка нашего Statement lifecycle close.
    */
   private void statementClosed()
   {
      if( closed )
         return;

      closed = true;

      /*
       * Mandatory lifecycle сначала.
       */
      closeCursorResultSetsByStatement();

      /*
       * Observation потом.
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
    * Проверка автоматического closeOnCompletion.
    *
    * Мы НЕ закрываем Statement сами.
    *
    * Источник истины только JDBC driver.
    */
   void syncCloseOnCompletion()
   {
      if( closed )
         return;

      if( !closeOnCompletion )
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
          * Если состояние определить не удалось,
          * консервативно считаем cursor открытым.
          */
         return false;
      }
   }


   /**
    * SQL конкретного execute.
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
    * Снимаем OUT values только когда реально
    * есть listener на JdbcStatementEvent.
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
   private void fireOpen()
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
    * Event listeners не являются частью
    * JDBC correctness.
    */
   private void safeFire(
           JdbcEvent event
   )
   {
      try
      {
         eventBus.fire(event);
      }
      catch( ThreadDeath | VirtualMachineError fatal )
      {
         throw fatal;
      }
      catch( Throwable ignored )
      {
         /*
          * TODO diagnostics/logging на JdbcEventBus level.
          */
      }
   }
}