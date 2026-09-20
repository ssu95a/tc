package ru.inversion.tc.jdbc.internal.proxy;

import ru.inversion.tc.jdbc.event.EventPhase;
import ru.inversion.tc.jdbc.event.EventType;
import ru.inversion.tc.jdbc.event.JdbcEvent;
import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.internal.db.JdbcDatabaseSupport;
import ru.inversion.tc.jdbc.internal.db.JdbcDatabaseSupportFactory;
import ru.inversion.tc.jdbc.internal.lifecycle.JdbcLifecycleManager;
import ru.inversion.tc.jdbc.internal.trace.JdbcServerOutputTracer;
import ru.inversion.tc.jdbc.internal.transaction.JdbcSavepointManager;
import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionManager;
import ru.inversion.utils.Checks;


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

import java.util.*;
import java.util.function.Predicate;


/**
 * <h5>JDBC Connection proxy.</h5>
 * <p>
 * Один JdbcConnectionProxy владеет:
 * <ul>
 * <li>одним JdbcLifecycleManager
 * <li>одним набором StatementProxy
 * <li>одним JDBC Connection proxy
 * </ul>
 * Все Statement, созданные через Connection,
 * обязательно проходят через JdbcStatementProxy.
 */
public final class JdbcConnectionProxy extends JdbcObjectProxy
{
   /** real JDBC connection*/
   private final Connection connection;

   /*
    * Statement'ы, созданные данным Connection!
    */
   private final Map<Statement, JdbcStatementProxy> statements = new IdentityHashMap<>();

   private Connection proxy;

   /* состояние */
   private volatile boolean closed;

   /* Флаг, который защищает от зацикливания */
   private int autoFinishLevel;

   /** Правитель транзакциЙ */
   private final JdbcTransactionManager transactionManager;

   /** Точки и тире */
   private final JdbcSavepointManager savepoints = new JdbcSavepointManager();

   /** Ловим то что выводит вдруг сервер */
   private final JdbcServerOutputTracer serverOutputTracer;

   /** */
   private JdbcConnectionProxy( Connection connection, JdbcEventBus eventBus, Predicate<EventType> traceEnabled )
   {
      super( new JdbcLifecycleManager(), eventBus );

      this.connection = Checks.Require.object(connection,"connection");

      /* Что умеет подключаемая СУБД */
      JdbcDatabaseSupport support = JdbcDatabaseSupportFactory.create( connection );

      transactionManager = new JdbcTransactionManager(
        connection,     // JDBC
        lifecycle,      // цикл
        savepoints,
        support.transactionPolicy() // политика работы с транзакцией
      );

      serverOutputTracer = support.createServerOutputTracer( connection, eventBus, traceEnabled );
   }

   /** */
   private synchronized void suspendAutoFinish()
   {
      autoFinishLevel++;
   }

   /** */
   private synchronized void resumeAutoFinish()
   {
      if( autoFinishLevel <= 0 )
          throw new IllegalStateException( "Auto-finish is not suspended" );
      autoFinishLevel--;
   }

   /** */
   private synchronized boolean isAutoFinishSuspended()
   {
      return autoFinishLevel != 0;
   }

   /**
    * Создаёт handler + JDBC Connection proxy.
    */
   public static JdbcConnectionProxy create( Connection connection, JdbcEventBus eventBus, Predicate<EventType> traceEnabled )
   {
      JdbcConnectionProxy handler = new JdbcConnectionProxy( connection, eventBus, traceEnabled );

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
    * Raw-jdbc connection.
    */
   Connection raw()
   {
      return connection;
   }


   /**
    * Lifecycle данного Connection.
    */
   JdbcLifecycleManager lifecycle()
   {
      return lifecycle;
   }


   /** InvocationHandler mechanics */
   @Override
   public Object invoke( Object proxy, Method method, Object[] args ) throws Throwable
   {
      String methodName = method.getName();

      /*
       * Object методы
       */
      if( Object.class.equals(method.getDeclaringClass() ) )
          return invokeObjectMethod( proxy, methodName, args );

      /*
       * Connection.close()
       */
      if( "close".equals(methodName) && method.getParameterTypes().length == 0 )
      {
         close();
         return null;
      }

      /*
       * Connection.abort(Executor)
       */
      if( "abort".equals(methodName) )
      {
         return abort( method, args );
      }

      /*
       * Connection.isClosed()
       */
      if( "isClosed".equals(methodName) && method.getParameterTypes().length == 0 )
      {
         return isClosed();
      }

      /*
       * createStatement(...)
       *
       */
      if( "createStatement".equals(methodName) )
      {
         Statement statement = (Statement) invokeRaw( method, args );
         return wrapStatement( statement, null );
      }

      /*
       * prepareStatement(...)
       *
       * SQL всегда первый argument.
       */
      if( "prepareStatement".equals(methodName) )
      {
         JdbcSqlTraceInfo info = JdbcSqlTraceInfo.parse(sql(args));
         args[0] = info.sql();
         PreparedStatement statement = (PreparedStatement) invokeRaw( method, args );

         return wrapStatement( statement, info );
      }

      /*
       * prepareCall(...)
       */
      if( "prepareCall".equals(methodName) )
      {
         JdbcSqlTraceInfo info = JdbcSqlTraceInfo.parse(sql(args));

         args[0] = info.sql();
         CallableStatement statement = (CallableStatement) invokeRaw( method, args);

         return wrapStatement( statement, info );
      }

      /*
       * Явный COMMIT.
       */
      if( "commit".equals(methodName) && method.getParameterTypes().length == 0 )
      {
         return commit( method, args );
      }

      /*
       * rollback() + rollback(Savepoint)
       */
      if( "rollback".equals(methodName) )
      {
         if( args != null && args.length == 1 && args[0] instanceof Savepoint )
             return rollbackSavepoint( method, args );
         else
             return rollback( method, args );
      }

      /*
       * setSavepoint()
       * setSavepoint(String)
       */
      if( "setSavepoint".equals(methodName) )
      {
         return setSavepoint( method, args );
      }

      /*
       * releaseSavepoint(Savepoint)
       */
      if( "releaseSavepoint".equals(methodName) )
      {
         return releaseSavepoint( method, args );
      }

      if( "setAutoCommit".equals(methodName) ) {
          return setAutoCommit(  method, args  );
      }

      /*
       * unwrap(Connection.class) возвращает proxy.
       */
      if( "unwrap".equals(methodName) && args != null && args.length == 1 )
      {
         Class<?> clazz = (Class<?>) args[0];

         if( clazz.isInstance(proxy) )
             return clazz.cast(proxy);
      }

      if( "isWrapperFor".equals(methodName) && args != null && args.length == 1 )
      {
         Class<?> clazz = (Class<?>) args[0];

         if( clazz.isInstance(proxy) )
             return true;
      }

      /*
       * Остальное Connection API:
       */
      return invokeRaw( method, args );
   }


   /**
    * <h5>Wrap Statement.</h5>
    * Фабрика proxy для Statement
    * порядок:
    * <p>
    * raw jdbc Statement created -> StatementProxy created -> REGISTER by connection -> event STATEMENT_OPEN
    * <p>
    * То есть listener STATEMENT_OPEN уже видит полностью зарегистрированный Statement.
    */
   private Statement wrapStatement( Statement statement, JdbcSqlTraceInfo info ) throws Throwable
   {
      if( statement == null )
          return null;

      /* если вдруг JDBC driver вернет тот же Statement object. */
      synchronized (this) {

         JdbcStatementProxy stmnt = statements.get(statement);

         if( stmnt != null )
             return stmnt.proxy();
      }

      JdbcStatementProxy handler = null;

      try
      {
         handler = JdbcStatementProxy.create(
            statement,
            this,
            info != null ? info.sql() : null,
            info != null ? info.hiddenParameters() : null,
            info != null && info.isTraceIgnored(),
            lifecycle,
            eventBus
         );

         registerStatement( statement, handler );

         /* Event только ПОСЛЕ регистрации в connection. */
         handler.fireOpen();

         return handler.proxy();
      }
      catch( Throwable throwable ) {

         /* Если proxy construction/registration упали, jdbc Statement наружу выпускать нельзя, также тушим. */
         if( handler != null )
            statementClosed( handler);

         try {
            statement.close();
         }
         catch( SQLException ignored )
         { }

         throw throwable;
      }
   }


   /**
    * Statement handler зарегистрирован в данном Connection.
    */
   private synchronized void registerStatement( Statement statement, JdbcStatementProxy handler ) throws SQLException
   {
      if( closed )
          throw new SQLException("Connection is closed");

      JdbcStatementProxy current = statements.get(statement);

      if( current != null && current != handler )
         throw new IllegalStateException( "Statement already registered" );

      statements.put( statement, handler );
   }


   /**
    * Получить proxy-handler по raw Statement identity.
    */
   private synchronized JdbcStatementProxy getStatement(Statement statement)
   {
      return statements.get( statement );
   }


   /**
    * Callback от JdbcStatementProxy.
    * <p>
    * Левый handler не может удалить новый Statement registration.
    */
   void statementClosed( JdbcStatementProxy statement )
   {
      synchronized( this )
      {
         JdbcStatementProxy current = statements.get( statement.raw() );

         if( current == statement )
             statements.remove( statement.raw() );
      }
   }


   /**
    * Явный Connection.commit().
    */
   private Object commit( Method method, Object[] args ) throws Throwable
   {
      suspendAutoFinish();

      try
      {
         Object value = invokeRaw( method, args );

         savepoints.onTransactionCompleted();
         /*
          * COMMIT мог закрыть серверный курсор ResultSet.
          */
         syncStatements();

         fire( EventType.TRANSACTION_COMMIT, EventPhase.AFTER, null );

         return value;
      }
      catch( Throwable throwable )
      {
         /* Driver мог частично изменить JDBC state. */
         syncStatements();

         fire( EventType.TRANSACTION_COMMIT, EventPhase.ERROR, throwable );

         throw throwable;
      }
      finally {
         resumeAutoFinish();
      }
   }


   /**
    * Явный Connection.rollback().
    */
   private Object rollback( Method method, Object[] args ) throws Throwable
   {
      suspendAutoFinish();

      try {

         Object value = invokeRaw( method, args );

         savepoints.onTransactionCompleted();
         /*
          * ROLLBACK закрывает/инвалидирует cursor state.
          */
         syncStatements();

         fire( EventType.TRANSACTION_ROLLBACK, EventPhase.AFTER, null );

         return value;
      }
      catch( Throwable throwable ) {

         syncStatements();

         fire( EventType.TRANSACTION_ROLLBACK, EventPhase.ERROR, throwable );

         throw throwable;
      }
      finally {
         resumeAutoFinish();
      }

   }


   /**
    * Connection.rollback(Savepoint).
    */
   private Object rollbackSavepoint( Method method, Object[] args )
           throws Throwable
   {
      suspendAutoFinish();

      try {

         Savepoint savepoint = (Savepoint) args[0];

         Object value = invokeRaw( method, args );

         savepoints.rollbackTo(savepoint);

         /* Rollback-to-savepoint также способен изменить состояние ResultSet. */
         syncStatements();

         fire( EventType.SAVEPOINT_ROLLBACK, EventPhase.AFTER, null );

         return value;
      }
      catch( Throwable throwable ) {

         syncStatements();

         fire( EventType.SAVEPOINT_ROLLBACK, EventPhase.ERROR, throwable );

         throw throwable;
      }
      finally {
         resumeAutoFinish();
      }
   }


   /**
    * Connection.setSavepoint(...).
    */
   private Object setSavepoint( Method method, Object[] args ) throws Throwable
   {
      try
      {
         Savepoint savepoint = (Savepoint) invokeRaw( method, args );

         String name = args != null && args.length == 1 && args[0] instanceof String ? (String) args[0] : null;

         savepoints.set( savepoint, name );

         /* Уведомляем */
         fire( EventType.SAVEPOINT_SET, EventPhase.AFTER, null );

         return savepoint;
      }
      catch( Throwable throwable ) {
         fire( EventType.SAVEPOINT_SET, EventPhase.ERROR, throwable );
         throw throwable;
      }
   }

   /**
    * setAutoCommit(true) может завершить текущую transaction и тем самым изменить
    * состояние cursor ResultSet.
    * <p>
    * Поэтому синхронизируем Statement-ы.
    */
   private Object setAutoCommit( Method method, Object[] args ) throws Throwable
   {
      suspendAutoFinish( );

      boolean autoCommit = (Boolean) args[0];

      try {

         Object value = invokeRaw( method, args );

         if( autoCommit )
            savepoints.onTransactionCompleted();

         return value;
      }
      finally
      {
         try {
            syncStatements();
         }
         finally {
            resumeAutoFinish();
         }
      }

   }


   /**
    * Connection.releaseSavepoint(...).
    */
   private Object releaseSavepoint( Method method, Object[] args ) throws Throwable
   {
      Savepoint savepoint = (Savepoint) args[0];

      try
      {
         Object value = invokeRaw(method, args);

         savepoints.released(savepoint);

         fire( EventType.SAVEPOINT_RELEASE, EventPhase.AFTER, null );

         /*
          * Savepoint мог быть последним препятствием - для commit idle transaction.
          */
         transactionStateChanged();

         return value;
      }
      catch( Throwable throwable )
      {
         fire( EventType.SAVEPOINT_RELEASE, EventPhase.ERROR, throwable );
         throw throwable;
      }
   }


   /**
    * Явный Connection.close().
    */
   private void close() throws Throwable
   {
      if( closed )
         return;

      try
      {
         if( serverOutputTracer != null )
             serverOutputTracer.close();

         connection.close();
      }
      catch( Throwable throwable )
      {
         syncStatements();

         if( isRawConnectionClosed() )
             connectionClosed();

         throw throwable;
      }

      connectionClosed();
   }


   /**
    * Connection.abort(Executor).
    * <p>
    * Успешный abort означает physical close.
    */
   private Object abort( Method method, Object[] args ) throws Throwable
   {
      if( closed )
          return null;

      try {

         Object value = invokeRaw( method, args );

         connectionClosed();

         return value;
      }
      catch( Throwable throwable ) {

         syncStatements();

         if( isRawConnectionClosed() )
             connectionClosed();

         throw throwable;
      }
   }


   /**
    * Точка завершения Connection lifecycle.
    */
   private void connectionClosed()
   {
      // Statement'ы для закрытия, через снимок
      List<JdbcStatementProxy> snapshot;

      synchronized( this )
      {
         if( closed )
            return;

         closed = true;

         snapshot = new ArrayList<>( statements.values() );

         // чистим внутреннюю коллекцию
         // в итоге мы никого не держим
         statements.clear();
      }

      /*
       * SQL здесь уже выполнять нельзя.
       */
      if( serverOutputTracer != null )
          serverOutputTracer.closedByConnection();

      for( JdbcStatementProxy statement : snapshot )
      {
         statement.closedByConnection();
      }

      savepoints.onTransactionCompleted();

      fireConnectionClose();
   }


   /**
    * Connection.isClosed().
    */
   private boolean isClosed() throws SQLException
   {
      if( closed )
          return true;

      boolean rawClosed = connection.isClosed();

      if( rawClosed )
          connectionClosed();

      return rawClosed;
   }


   /**
    * Синхронизировать все, принадлежащие Connection Statement
    * после транзакционной или другой operation.
    */
   private void syncStatements()
   {
      List<JdbcStatementProxy> snapshot;

      synchronized(this) {
         snapshot = statements.isEmpty() ? Collections.emptyList() : new ArrayList<>( statements.values() );
      }

      for( JdbcStatementProxy statement : snapshot )
           statement.syncConnectionState();
   }


   /**
    * SQL является первым argument prepareStatement()/prepareCall().
    */
   private static String sql( Object[] args )
   {
      if( args == null || args.length == 0 || !(args[0] instanceof String) )
          return null;

      return (String) args[0];
   }


   /** */
   private Object invokeRaw( Method method, Object[] args ) throws Throwable
   {
      try
      {
         return method.invoke( connection, args );
      }
      catch( InvocationTargetException ex ) {
         throw ex.getCause();
      }
   }


   /**
    * Проверка состояния raw-jdbc Connection
    */
   private boolean isRawConnectionClosed( )
   {
      try
      {
         return connection.isClosed();
      }
      catch( SQLException ignored )
      {
         /*
          * считаем Connection ещё открытым.
          */
         return false;
      }
   }


   /**
    * Event: CONNECTION_OPEN.
    */
   private void fireConnectionOpen()
   {
      fire( EventType.CONNECTION_OPEN, EventPhase.ON, null );
   }


   /**
    * Event: CONNECTION_CLOSE.
    */
   private void fireConnectionClose()
   {
      fire( EventType.CONNECTION_CLOSE, EventPhase.ON, null );
   }


   /**
    * Специализированного JdbcConnectionEvent нет,
    * события представлены базовым JdbcEvent.
    */
   private void fire ( EventType type, EventPhase phase, Throwable throwable )
   {
      if( eventBus == null )
         return;

      /* JdbcEvent instance получают только listeners на JdbcEvent.class. */
      if( !eventBus.hasListeners( JdbcEvent.class) )
          return;

      eventBus.fire( new JdbcEvent( proxy, type, phase, throwable ) );
   }


   /** Самое оно, чекаем IDLE TRAN */
   void transactionStateChanged()
   {
      if( closed )
         return;

      if( isAutoFinishSuspended() )
         return;

      if( lifecycle.hasOpenCursors() )
          return;

      try
      {
         boolean committed = transactionManager.tryCommitIdleTransaction();

         if( committed )
             fire( EventType.TRANSACTION_COMMIT, EventPhase.AFTER, null );
      }
      catch( SQLException | RuntimeException ex  ) {
         fire( EventType.TRANSACTION_COMMIT, EventPhase.ERROR, ex );
      }
   }


   /** Для поиска Savepoint из TaskContext'а */
   public static Savepoint findSavepoint( Connection connection, String name )
   {
      if( connection == null )
         throw new IllegalArgumentException( "connection is null" );

      if( !Proxy.isProxyClass(connection.getClass()) )
         throw new IllegalStateException( "Connection is not JdbcConnectionProxy" );

      InvocationHandler handler = Proxy.getInvocationHandler(connection);

      if( !(handler instanceof JdbcConnectionProxy) )
         throw new IllegalStateException( "Connection is not JdbcConnectionProxy" );

      return ((JdbcConnectionProxy) handler).savepoints.find(name);
   }
}