package ru.inversion.tc.jdbc.internal.proxy;

import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.event.JdbcStatementEvent;
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

/** */
public final class JdbcStatementProxy implements InvocationHandler
{
   private final Statement statement;
   /*
    * Proxy Connection.
    * Statement.getConnection() не должен возвращать raw connection.
    */
   private final Connection connection;

   private final JdbcLifecycleManager lifecycle;
   private final JdbcEventBus eventBus;

   private final long statementId;

   /*
    * Для PreparedStatement / CallableStatement.
    * Для обычного Statement == null.
    */
   private final String sql;

   private final boolean prepared;
   private final boolean callable;

   /*
    * Последние установленные IN параметры.
    */
   private final Map<Integer, Object> inParameters = new TreeMap<>();

   /*
    * Зарегистрированные OUT параметры CallableStatement.
    */
   private final Set<Integer> outParameters = new TreeSet<>();

   /*
    * Один raw ResultSet должен иметь ровно один proxy.
    */
   private final Map< ResultSet, JdbcResultSetProxy > resultSets = new IdentityHashMap<>();

   /** */
   private Statement proxy;

   /** */
   private boolean closed;

   /** */
   private JdbcStatementProxy (
      Statement statement,
      Connection connection,
      String sql,
      JdbcLifecycleManager lifecycle,
      JdbcEventBus eventBus
   )
   {
      if( statement == null )
          throw new IllegalArgumentException("statement is null");

      if( connection == null )
          throw new IllegalArgumentException("connection is null");

      if( lifecycle == null )
          throw new IllegalArgumentException("lifecycle is null");

      this.statement = statement;
      this.connection = connection;

      this.lifecycle = lifecycle;
      this.eventBus = eventBus;

      this.sql = sql;

      prepared = statement instanceof PreparedStatement;

      callable = statement instanceof CallableStatement;

      statementId
               = lifecycle.nextStatementId();
   }

   /** */
   public static Statement create (
      Statement statement,
      Connection connection,
      String sql,
      JdbcLifecycleManager lifecycle,
      JdbcEventBus eventBus
   )
   {
      JdbcStatementProxy handler = new JdbcStatementProxy( statement, connection, sql, lifecycle, eventBus );

      Class<?> jdbcInterface;

      if( statement instanceof CallableStatement )
         jdbcInterface = CallableStatement.class;
      else if( statement instanceof PreparedStatement )
         jdbcInterface = PreparedStatement.class;
      else
         jdbcInterface = Statement.class;

      handler.proxy = (Statement) Proxy.newProxyInstance( JdbcStatementProxy.class.getClassLoader(), new Class<?>[] { jdbcInterface }, handler );
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

   /** */
   @Override
   public Object invoke( Object proxy, Method method, Object[] args ) throws Throwable
   {
      String methodName = method.getName();

      /*
       * Object identity proxy-а не связываем
       * с equals/hashCode JDBC driver-а.
       */
      if( Object.class.equals(method.getDeclaringClass()) )
         return invokeObjectMethod( proxy, methodName, args );

      if( "close".equals(methodName) && method.getParameterTypes().length == 0 )
      {
         close();
         return null;
      }

      if( "isClosed".equals(methodName) && method.getParameterTypes().length == 0 )
      {
         return isClosed();
      }

      /*
       * Не выпускаем raw Connection.
       */
      if( "getConnection".equals(methodName) && method.getParameterTypes().length == 0 )
      {
         return connection;
      }

      /*
       * ResultSet-producing methods.
       */
      if( "getResultSet".equals(methodName) && method.getParameterTypes().length == 0 )
      {
         return wrapResultSet( (ResultSet) invokeRaw(method, args) );
      }

      /* */
      if( "getGeneratedKeys".equals(methodName) && method.getParameterTypes().length == 0 )
      {
         return wrapResultSet( (ResultSet) invokeRaw(method, args) );
      }

      /*
       * Следующий result может неявно закрыть предыдущий.
       */
      if( "getMoreResults".equals(methodName) )
      {
         Object value;

         try
         {
            value = invokeRaw(method, args);
         }
         finally
         {
            reconcileResultSets();
         }

         return value;
      }

      /*
       * unwrap(proxy-compatible interface) оставляем
       * внутри нашей proxy цепочки.
       *
       * Vendor unwrap делегируем driver-у.
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
       * PreparedStatement parameter setters.
       */
      if( isParameterSetter(method, args) )
         return setParameter(
                 method,
                 args
         );

      if( prepared
              && "clearParameters".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         Object value =
                 invokeRaw(method, args);

         inParameters.clear();

         /*
          * OUT registrations здесь специально НЕ чистим.
          * clearParameters() очищает parameter values,
          * а не нашу информацию о registerOutParameter().
          */

         return value;
      }

      /*
       * CallableStatement.registerOutParameter(int,...)
       */
      if( callable
              && "registerOutParameter".equals(methodName)
              && args != null
              && args.length > 0
              && args[0] instanceof Integer )
      {
         Object value =
                 invokeRaw(method, args);

         outParameters.add(
                 (Integer) args[0]
         );

         return value;
      }

      /*
       * execute / executeQuery / executeUpdate /
       * executeBatch / executeLargeUpdate ...
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


   /** */
   private Object execute(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      String methodName =
              method.getName();

      String executedSql =
              sql(method, args);

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
          * Re-execute мог уже закрыть предыдущий ResultSet,
          * даже если сам execute завершился ошибкой.
          */
         reconcileResultSets();

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
       * JDBC re-execute автоматически закрывает старый
       * current ResultSet. Сверяемся с driver state.
       */
      reconcileResultSets();

      fireAfterExecute(
              methodName,
              executedSql,
              duration
      );

      /*
       * executeQuery() возвращает ResultSet напрямую.
       *
       * AFTER execute логически идёт раньше RESULT_SET_OPEN.
       */
      if( value instanceof ResultSet )
         return wrapResultSet(
                 (ResultSet) value
         );

      return value;
   }


   /** */
   private Object setParameter(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      /*
       * Сначала driver.
       * Если setXXX упал, state proxy менять нельзя.
       */
      Object value =
              invokeRaw(
                      method,
                      args
              );

      int index =
              (Integer) args[0];

      if( "setNull".equals(method.getName()) )
         inParameters.put(
                 index,
                 null
         );
      else
         inParameters.put(
                 index,
                 args.length > 1
                         ? args[1]
                         : null
         );

      return value;
   }


   /**
    * PreparedStatement.setXXX(int,...)
    *
    * Здесь не нужен огромный SET_METHODS как в старом tracer.
    */
   private static boolean isParameterSetter(
           Method method,
           Object[] args
   )
   {
      if( args == null || args.length == 0 )
         return false;

      if( !(args[0] instanceof Integer) )
         return false;

      if( !method.getName().startsWith("set") )
         return false;

      /*
       * Отсекает Statement.setFetchSize(),
       * setMaxRows(), setQueryTimeout() и т.п.
       */
      return PreparedStatement.class.isAssignableFrom(
              method.getDeclaringClass()
      );
   }


   /** */
   private ResultSet wrapResultSet(
           ResultSet resultSet
   )
           throws SQLException
   {
      if( resultSet == null )
         return null;

      JdbcResultSetProxy current =
              resultSets.get(resultSet);

      if( current != null )
      {
         if( !current.isClosed() )
            return current.proxy();

         resultSets.remove(resultSet);
      }

      /*
       * Driver иногда способен вернуть уже закрытый RS.
       * Такой объект lifecycle-регистрировать не надо.
       */
      if( isClosed(resultSet) )
         return resultSet;

      JdbcResultSetProxy handler =
              JdbcResultSetProxy.create(
                      resultSet,
                      proxy,
                      statementId,
                      lifecycle,
                      eventBus
              );

      resultSets.put(
              resultSet,
              handler
      );

      return handler.proxy();
   }


   /**
    * Убирает ResultSet-ы, которые driver закрыл
    * не через ResultSetProxy.close().
    */
   private void reconcileResultSets()
   {
      if( resultSets.isEmpty() )
         return;

      /*
       * Snapshot нужен: closedByStatement() меняет lifecycle,
       * а позднее ResultSetProxy сможет уведомлять owner.
       */
      ArrayList<JdbcResultSetProxy> snapshot =
              new ArrayList<>(
                      resultSets.values()
              );

      for( JdbcResultSetProxy resultSet : snapshot )
      {
         if( resultSet.isClosed() )
         {
            resultSets.remove(
                    resultSet.raw()
            );

            continue;
         }

         if( isClosed(resultSet.raw()) )
         {
            resultSet.closedByStatement();

            resultSets.remove(
                    resultSet.raw()
            );
         }
      }
   }


   /**
    * Statement.close() по JDBC закрывает принадлежащие ему ResultSet.
    */
   private void closeResultSetsByStatement()
   {
      if( resultSets.isEmpty() )
          return;

      ArrayList<JdbcResultSetProxy> snapshot = new ArrayList<>( resultSets.values() );

      resultSets.clear();

      for( JdbcResultSetProxy resultSet : snapshot )
           resultSet.closedByStatement();
   }


   /** */
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
          * Некоторые driver-ы могут физически закрыть
          * Statement и всё же выбросить исключение.
          */
         if( isRawStatementClosed() )
            statementClosed();

         throw throwable;
      }

      statementClosed();
   }


   /** */
   private void statementClosed()
   {
      if( closed )
         return;

      closed = true;

      /*
       * Сначала mandatory lifecycle.
       */
      closeResultSetsByStatement();

      /*
       * Потом observation.
       */
      fireClose();
   }


   /** */
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
   private static boolean isClosed(
           ResultSet resultSet
   )
   {
      try
      {
         return resultSet.isClosed();
      }
      catch( SQLException ignored )
      {
         return false;
      }
   }


   /** */
   private String sql(
           Method method,
           Object[] args
   )
   {
      /*
       * Statement.execute*(String sql,...)
       */
      if( args != null
              && args.length > 0
              && args[0] instanceof String )
      {
         return (String) args[0];
      }

      /*
       * PreparedStatement.execute*()
       */
      return sql;
   }


   /** */
   private Map<Integer, Object> outParameterValues()
   {
      if( !callable || outParameters.isEmpty() )
         return Collections.emptyMap();

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
             * Сбор diagnostics не должен ломать
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
         return "JdbcStatementProxy["
                 + statementId
                 + "]";

      throw new IllegalStateException(
              "Unsupported Object method: "
                      + methodName
      );
   }


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

      eventBus.fire(
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

      eventBus.fire(
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

      eventBus.fire(
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

      eventBus.fire(
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

      eventBus.fire(
              JdbcStatementEvent.close(
                      proxy,
                      statementId,
                      sql
              )
      );
   }
}