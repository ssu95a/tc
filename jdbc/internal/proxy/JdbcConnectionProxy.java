package ru.inversion.tc.jdbc.internal.proxy;

import ru.inversion.tc.jdbc.event.EventPhase;
import ru.inversion.tc.jdbc.event.EventType;
import ru.inversion.tc.jdbc.event.JdbcEvent;
import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.internal.lifecycle.JdbcLifecycleManager;
import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionManager;
import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;


/**
 * Mandatory JDBC Connection proxy.
 * <p>
 * Один JdbcConnectionProxy владеет:
 *
 * - одним JdbcLifecycleManager
 * - одним набором StatementProxy
 * - одним JDBC Connection proxy
 *
 * Все Statement, созданные через Connection,
 * обязательно проходят через JdbcStatementProxy.
 */
public final class JdbcConnectionProxy
        implements InvocationHandler
{
   private final Connection connection;

   private final JdbcLifecycleManager lifecycle = new JdbcLifecycleManager();

   private final JdbcEventBus eventBus;

   /*
    * Только Statement, созданные данным Connection.
    *
    * Identity semantics принципиальны.
    */
   private final Map<Statement, JdbcStatementProxy> statements = new IdentityHashMap<>();

   private Connection proxy;

   /*
    * Lifecycle state нашего proxy.
    */
   private volatile boolean closed;


   private final JdbcTransactionManager transactionManager;


   /** */
   private JdbcConnectionProxy( Connection connection, JdbcEventBus eventBus )
   {
      if( connection == null )
          throw new IllegalArgumentException( "connection is null" );

      this.connection = connection;
      this.eventBus = eventBus;

      JdbcTransactionPolicy policy = JdbcTransactionPolicyFactory.create( connection );

      transactionManager =
              new JdbcTransactionManager(
                      connection,     // RAW
                      lifecycle,
                      policy
              );

   }


   /**
    * Создаёт handler + JDBC Connection proxy.
    */
   public static JdbcConnectionProxy create(
           Connection connection,
           JdbcEventBus eventBus
   )
   {
      JdbcConnectionProxy handler =
              new JdbcConnectionProxy(
                      connection,
                      eventBus
              );

      handler.proxy =
              (Connection) Proxy.newProxyInstance(
                      JdbcConnectionProxy.class.getClassLoader(),
                      new Class<?>[] { Connection.class },
                      handler
              );

      handler.fireConnectionOpen();

      return handler;
   }


   /**
    * JDBC proxy Connection.
    */
   public Connection proxy()
   {
      return proxy;
   }


   /**
    * Raw connection.
    *
    * Не public намеренно.
    *
    * В будущем именно этот connection должен
    * использовать lifecycle для PostgreSQL
    * pg_current_xact_id_if_assigned() и auto-finish
    * read transaction, минуя proxy/events.
    */
   Connection raw()
   {
      return connection;
   }


   /**
    * Lifecycle manager данного Connection.
    */
   JdbcLifecycleManager lifecycle()
   {
      return lifecycle;
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
       * от driver equals/hashCode.
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
       * Connection.close()
       */
      if( "close".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         close();

         return null;
      }

      /*
       * Connection.abort(Executor)
       */
      if( "abort".equals(methodName) )
      {
         return abort(
                 method,
                 args
         );
      }

      /*
       * Connection.isClosed()
       */
      if( "isClosed".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         return isClosed();
      }

      /*
       * createStatement(...)
       *
       * Все overload-ы.
       */
      if( "createStatement".equals(methodName) )
      {
         Statement statement =
                 (Statement) invokeRaw(
                         method,
                         args
                 );

         return wrapStatement(
                 statement,
                 null
         );
      }

      /*
       * prepareStatement(...)
       *
       * SQL всегда первый argument.
       */
      if( "prepareStatement".equals(methodName) )
      {
         PreparedStatement statement =
                 (PreparedStatement) invokeRaw(
                         method,
                         args
                 );

         return wrapStatement(
                 statement,
                 sql(args)
         );
      }

      /*
       * prepareCall(...)
       */
      if( "prepareCall".equals(methodName) )
      {
         CallableStatement statement =
                 (CallableStatement) invokeRaw(
                         method,
                         args
                 );

         return wrapStatement(
                 statement,
                 sql(args)
         );
      }

      /*
       * Явный COMMIT.
       */
      if( "commit".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         return commit(
                 method,
                 args
         );
      }

      /*
       * rollback()
       *
       * rollback(Savepoint) обрабатывается отдельно.
       */
      if( "rollback".equals(methodName) )
      {
         if( args != null
                 && args.length == 1
                 && args[0] instanceof Savepoint )
         {
            return rollbackSavepoint(
                    method,
                    args
            );
         }

         return rollback(
                 method,
                 args
         );
      }

      /*
       * setSavepoint()
       * setSavepoint(String)
       */
      if( "setSavepoint".equals(methodName) )
      {
         return setSavepoint(
                 method,
                 args
         );
      }

      /*
       * releaseSavepoint(Savepoint)
       */
      if( "releaseSavepoint".equals(methodName) )
      {
         return releaseSavepoint(
                 method,
                 args
         );
      }

      /*
       * setAutoCommit(true) может завершить
       * текущую transaction и тем самым изменить
       * состояние cursor ResultSet.
       *
       * Поэтому после успешного вызова
       * синхронизируем Statement-ы.
       */
      if( "setAutoCommit".equals(methodName) ) {
         boolean autoCommit =
                 (Boolean) args[0];

         try {
            Object value =
                    invokeRaw(
                            method,
                            args
                    );

            if (autoCommit)
               lifecycle.transactionFinished();

            return value;
         } finally {
            syncStatements();
         }
      }
      /*
       * unwrap(Connection.class) должен оставить
       * клиента внутри proxy.
       *
       * Vendor-specific unwrap разрешаем driver-у.
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
       * Остальной Connection API:
       *
       * getMetaData()
       * nativeSQL()
       * createArrayOf()
       * createBlob()
       * createClob()
       * setTransactionIsolation()
       * setReadOnly()
       * setSchema()
       * network timeout
       * clientInfo
       * ...
       */
      return invokeRaw(
              method,
              args
      );
   }


   /**
    * Wrap Statement.
    *
    * ВАЖНО:
    *
    * порядок:
    *
    * raw Statement created
    *      |
    * StatementProxy created
    *      |
    * REGISTER owner
    *      |
    * STATEMENT_OPEN
    *
    * То есть listener STATEMENT_OPEN уже видит
    * полностью зарегистрированный Statement.
    */
   private Statement wrapStatement(
           Statement statement,
           String sql
   )
           throws Throwable
   {
      if( statement == null )
         return null;

      /*
       * Теоретическая защита от driver-а,
       * возвращающего тот же Statement object.
       */
      JdbcStatementProxy current =
              registeredStatement(
                      statement
              );

      if( current != null )
         return current.proxy();

      JdbcStatementProxy handler = null;

      try
      {
         handler =
                 JdbcStatementProxy.create(
                         statement,
                         this,
                         sql,
                         lifecycle,
                         eventBus
                 );

         registerStatement(
                 statement,
                 handler
         );

         /*
          * Event только ПОСЛЕ owner registration.
          */
         handler.fireOpen();

         return handler.proxy();
      }
      catch( Throwable throwable )
      {
         /*
          * Если proxy construction/registration
          * не удались, raw Statement наружу
          * выпускать нельзя.
          */
         if( handler != null )
         {
            statementClosed(
                    handler
            );
         }

         closeRawStatement(
                 statement
         );

         throw throwable;
      }
   }


   /**
    * Statement handler зарегистрирован
    * в данном Connection.
    */
   private synchronized void registerStatement(
           Statement statement,
           JdbcStatementProxy handler
   )
           throws SQLException
   {
      if( closed )
      {
         throw new SQLException(
                 "Connection is closed"
         );
      }

      JdbcStatementProxy current =
              statements.get(statement);

      if( current != null
              && current != handler )
      {
         throw new IllegalStateException(
                 "Statement already registered"
         );
      }

      statements.put(
              statement,
              handler
      );
   }


   /**
    * Получить handler по raw Statement identity.
    */
   private synchronized JdbcStatementProxy registeredStatement(
           Statement statement
   )
   {
      return statements.get(
              statement
      );
   }


   /**
    * Callback от JdbcStatementProxy.
    *
    * Stale handler не может удалить
    * новый Statement registration.
    */
   void statementClosed(
           JdbcStatementProxy statement
   )
   {
      synchronized( this )
      {
         JdbcStatementProxy current =
                 statements.get(
                         statement.raw()
                 );

         if( current == statement )
         {
            statements.remove(
                    statement.raw()
            );
         }
      }
   }


   /**
    * Явный Connection.commit().
    */
   private Object commit(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      try
      {
         Object value =
                 invokeRaw(
                         method,
                         args
                 );

         lifecycle.transactionFinished();
         /*
          * COMMIT мог закрыть server cursor ResultSet.
          */
         syncStatements();

         fire(
                 EventType.TRANSACTION_COMMIT,
                 EventPhase.AFTER,
                 null
         );

         return value;
      }
      catch( Throwable throwable )
      {
         /*
          * Driver мог частично изменить JDBC state.
          */
         syncStatements();

         fire(
                 EventType.TRANSACTION_COMMIT,
                 EventPhase.ERROR,
                 throwable
         );

         throw throwable;
      }
   }


   /**
    * Явный Connection.rollback().
    */
   private Object rollback(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      try
      {
         Object value =
                 invokeRaw(
                         method,
                         args
                 );

         lifecycle.transactionFinished();
         /*
          * ROLLBACK закрывает/инвалидирует cursor state.
          */
         syncStatements();

         fire(
                 EventType.TRANSACTION_ROLLBACK,
                 EventPhase.AFTER,
                 null
         );

         return value;
      }
      catch( Throwable throwable )
      {
         syncStatements();

         fire(
                 EventType.TRANSACTION_ROLLBACK,
                 EventPhase.ERROR,
                 throwable
         );

         throw throwable;
      }
   }


   /**
    * Connection.rollback(Savepoint).
    */
   private Object rollbackSavepoint(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      try
      {
         Object value =
                 invokeRaw(
                         method,
                         args
                 );

         /*
          * Rollback-to-savepoint также способен
          * изменить состояние ResultSet/portal.
          */
         syncStatements();

         fire(
                 EventType.SAVEPOINT_ROLLBACK,
                 EventPhase.AFTER,
                 null
         );

         return value;
      }
      catch( Throwable throwable )
      {
         syncStatements();

         fire(
                 EventType.SAVEPOINT_ROLLBACK,
                 EventPhase.ERROR,
                 throwable
         );

         throw throwable;
      }
   }


   /**
    * Connection.setSavepoint(...).
    */
   private Object setSavepoint(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      try
      {
         Savepoint savepoint =
                 (Savepoint) invokeRaw(
                         method,
                         args
                 );

         /*
          * Mandatory correctness state.
          */
         lifecycle.savepointSet(
                 savepoint
         );

         /*
          * Observation.
          */
         fire(
                 EventType.SAVEPOINT_SET,
                 EventPhase.AFTER,
                 null
         );

         return savepoint;
      }
      catch( Throwable throwable )
      {
         fire(
                 EventType.SAVEPOINT_SET,
                 EventPhase.ERROR,
                 throwable
         );

         throw throwable;
      }
   }


   /**
    * Connection.releaseSavepoint(...).
    */
   private Object releaseSavepoint(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      Savepoint savepoint =
              (Savepoint) args[0];

      try
      {
         Object value =
                 invokeRaw(
                         method,
                         args
                 );

         lifecycle.savepointReleased(
                 savepoint
         );

         fire(
                 EventType.SAVEPOINT_RELEASE,
                 EventPhase.AFTER,
                 null
         );

         return value;
      }
      catch( Throwable throwable )
      {
         fire(
                 EventType.SAVEPOINT_RELEASE,
                 EventPhase.ERROR,
                 throwable
         );

         throw throwable;
      }
   }

   /**
    * Явный Connection.close().
    */
   private void close()
           throws Throwable
   {
      if( closed )
         return;

      try
      {
         connection.close();
      }
      catch( Throwable throwable )
      {
         /*
          * Даже failed close мог закрыть Statement/RS.
          */
         syncStatements();

         if( isRawConnectionClosed() )
            connectionClosed();

         throw throwable;
      }

      connectionClosed();
   }


   /**
    * Connection.abort(Executor).
    *
    * Успешный abort означает physical close.
    */
   private Object abort(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      if( closed )
         return null;

      try
      {
         Object value =
                 invokeRaw(
                         method,
                         args
                 );

         connectionClosed();

         return value;
      }
      catch( Throwable throwable )
      {
         syncStatements();

         if( isRawConnectionClosed() )
            connectionClosed();

         throw throwable;
      }
   }


   /**
    * Единственная точка завершения
    * нашего Connection lifecycle.
    */
   private void connectionClosed()
   {
      List<JdbcStatementProxy> snapshot;

      synchronized( this )
      {
         if( closed )
            return;

         closed = true;

         snapshot =
                 new ArrayList<>(
                         statements.values()
                 );

         /*
          * Сначала owner registry.
          *
          * Callback от StatementProxy далее
          * станет безопасным no-op.
          */
         statements.clear();
      }

      /*
       * Raw Connection уже закрыт.
       *
       * Statement.close() повторно НЕ вызываем.
       * Только синхронизируем наши lifecycle states.
       */
      for( JdbcStatementProxy statement : snapshot )
      {
         statement.closedByConnection();
      }

      /*
       * Порядок событий:
       *
       * RESULT_SET_CLOSE
       * STATEMENT_CLOSE
       * CONNECTION_CLOSE
       */
      fireConnectionClose();
   }


   /**
    * Connection.isClosed().
    */
   private boolean isClosed()
           throws SQLException
   {
      if( closed )
         return true;

      boolean rawClosed =
              connection.isClosed();

      if( rawClosed )
         connectionClosed();

      return rawClosed;
   }


   /**
    * Синхронизировать все принадлежащие
    * Connection Statement после transaction boundary
    * или другой Connection operation.
    */
   private void syncStatements()
   {
      List<JdbcStatementProxy> snapshot =
              statementSnapshot();

      for( JdbcStatementProxy statement : snapshot )
      {
         statement.syncConnectionState();
      }
   }


   /**
    * Snapshot Statement handlers.
    */
   private synchronized List<JdbcStatementProxy> statementSnapshot()
   {
      if( statements.isEmpty() )
         return new ArrayList<>(0);

      return new ArrayList<>(
              statements.values()
      );
   }


   /**
    * SQL является первым argument
    * prepareStatement()/prepareCall().
    */
   private static String sql(
           Object[] args
   )
   {
      if( args == null
              || args.length == 0
              || !(args[0] instanceof String) )
      {
         return null;
      }

      return (String) args[0];
   }


   /**
    * Raw invocation.
    */
   private Object invokeRaw(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      try
      {
         return method.invoke(
                 connection,
                 args
         );
      }
      catch( InvocationTargetException ex )
      {
         throw ex.getCause();
      }
   }


   /**
    * Raw Statement cleanup при ошибке
    * построения proxy.
    */
   private static void closeRawStatement(
           Statement statement
   )
   {
      if( statement == null )
         return;

      try
      {
         statement.close();
      }
      catch( SQLException ignored )
      {
      }
   }


   /**
    * Проверка physical Connection state.
    */
   private boolean isRawConnectionClosed()
   {
      try
      {
         return connection.isClosed();
      }
      catch( SQLException ignored )
      {
         /*
          * Консервативно считаем Connection
          * ещё открытым.
          */
         return false;
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
         return "JdbcConnectionProxy@" + Integer.toHexString( System.identityHashCode(proxy) );
      }

      throw new IllegalStateException(
              "Unsupported Object method: "
                      + methodName
      );
   }


   /**
    * CONNECTION_OPEN.
    */
   private void fireConnectionOpen()
   {
      fire(
              EventType.CONNECTION_OPEN,
              EventPhase.ON,
              null
      );
   }


   /**
    * CONNECTION_CLOSE.
    */
   private void fireConnectionClose()
   {
      fire(
              EventType.CONNECTION_CLOSE,
              EventPhase.ON,
              null
      );
   }


   /**
    * Пока специализированного JdbcConnectionEvent
    * нет, Connection/transaction/savepoint события
    * представлены базовым JdbcEvent.
    */
   private void fire(
           EventType type,
           EventPhase phase,
           Throwable throwable
   )
   {
      if( eventBus == null )
         return;

      /*
       * JdbcEvent instance получают только
       * listeners на JdbcEvent.class.
       */
      if( !eventBus.hasListeners(
              JdbcEvent.class
      ) )
      {
         return;
      }

      safeFire(
              new JdbcEvent(
                      proxy,
                      type,
                      phase,
                      throwable
              )
      );
   }


   /**
    * Event observation не влияет
    * на JDBC correctness.
    *
    * Error не перехватываем.
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


}