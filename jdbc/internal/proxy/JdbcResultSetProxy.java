package ru.inversion.tc.jdbc.internal.proxy;

import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.event.JdbcResultSetEvent;
import ru.inversion.tc.jdbc.internal.lifecycle.JdbcLifecycleManager;
import ru.inversion.utils.Checks;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;


/**
 * <h5>Proxy только для Statement cursor ResultSet.</h5>
 * <p>
 * Не учитываются:
 * - Statement.getGeneratedKeys()
 * - Array.getResultSet()
 * - DatabaseMetaData ResultSet
 * - прочих служебных JDBC ResultSet
 */
public final class JdbcResultSetProxy extends JdbcObjectProxy
{
   private final ResultSet resultSet;

   /*
    * Владелец - Statement handler.
    *
    * Нужен:
    * - для getStatement() -> proxy Statement
    * - для удаления себя из owner cache
    * - для closeOnCompletion synchronization
    */
   private final JdbcStatementProxy statement;

   private ResultSet proxy;

   /*
    * state proxy.
    */
   private boolean closed;

   /*
    * OPEN event должен быть отправлен ровно один раз.
    *
    * Создание proxy двухфазное:
    *
    * 1. register cursor в lifecycle
    * 2. позже fireOpen()
    *
    * Это позволяет StatementProxy сначала зарегистрировать
    * новый cursor, затем закрыть старый, не создавая
    * промежуточного openCursorCount == 0.
    */
   private boolean openEventFired;

   private final JdbcLifecycleManager.CursorToken registration;


   /** */
   private JdbcResultSetProxy( ResultSet resultSet, JdbcStatementProxy statement, JdbcLifecycleManager lifecycle, JdbcEventBus eventBus )
   {
      super(lifecycle, eventBus);

      if( resultSet == null )
          throw new IllegalArgumentException( "resultSet is null" );

      if( statement == null )
          throw new IllegalArgumentException("statement is null" );

      this.resultSet = Checks.Require.object(resultSet, "resultSet");
      this.statement = Checks.Require.object(statement, "statement");

      this.registration = lifecycle.registerCursor(resultSet);
   }


   /**
    * Создаёт handler и JDBC proxy.
    * <p>
    * OPEN event здесь специально НЕ отправляется.
    * Его вызывает owner StatementProxy после того,
    * как завершена необходимая lifecycle-синхронизация.
    */
   static JdbcResultSetProxy create(
      ResultSet resultSet,
      JdbcStatementProxy statement,
      JdbcLifecycleManager lifecycle,
      JdbcEventBus eventBus
   )
   {
      JdbcResultSetProxy handler = new JdbcResultSetProxy( resultSet, statement, lifecycle, eventBus );

      try
      {
         handler.proxy = (ResultSet) Proxy.newProxyInstance( JdbcResultSetProxy.class.getClassLoader(), new Class<?>[] { ResultSet.class }, handler );
         return handler;
      }
      catch( RuntimeException | Error ex )
      {
         lifecycle.unregisterCursor( handler.registration );
         throw ex;
      }
   }


   /** */
   ResultSet proxy()
   {
      return proxy;
   }


   /** */
   ResultSet raw()
   {
      return resultSet;
   }


   /**
    * Только локальный lifecycle state.
    * <p>
    * Driver здесь не опрашиваем.
    */
   boolean isLifecycleClosed()
   {
      return closed;
   }


   /** */
   @Override
   public Object invoke( Object proxy, Method method, Object[] args ) throws Throwable
   {
      final String methodName = method.getName();

      if( Object.class.equals( method.getDeclaringClass() ) )
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
       * Не выпускаем jdbc-raw Statement, возвращаем proxy
       */
      if( "getStatement".equals(methodName) && method.getParameterTypes().length == 0 )
      {
         return statement.proxy();
      }

      /*
       * unwrap(ResultSet.class) должен вернуть proxy.
       * <p>
       * Если запрос на Vendor-specific unwrap, то isWrapperFor вернет false и вызов делегируется driver-у.
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

      return invokeRaw( method, args );
   }


   /**
    * Явный ResultSet.close().
    */
   private synchronized void close() throws Throwable
   {
      if( closed )
          return;
      try
      {
         resultSet.close();
      }
      catch( Throwable throwable )
      {
         /*
          * Driver мог физически закрыть RS
          * и одновременно вернуть ошибку.
          */
         if( isRawClosed() )
             lifecycleClosed();

         throw unwrapThrowable( throwable );
      }

      lifecycleClosed();
   }


   /**
    * ResultSet был закрыт самим Statement/driver-ом:
    *
    * - Statement.close()
    * - повторный execute*
    * - getMoreResults()
    * - CLOSE_CURRENT_RESULT
    * - CLOSE_ALL_RESULTS
    *
    * Raw close здесь повторно НЕ вызываем.
    */
   synchronized void closedByStatement()
   {
      if( closed )
          return;

      lifecycleClosed();
   }


   /**
    * Точка завершения cursor lifecycle.
    */
   private void lifecycleClosed()
   {
      if( closed )
         return;

      boolean removed = lifecycle.unregisterCursor( registration );

      closed = true;

      statement.cursorResultSetClosed(this);

      if( removed )
          fireClose();

      statement.syncClosedState();

      if( removed && !statement.isLifecycleClosed() )
          statement.cursorStateChanged();
   }


   /**
    * isClosed() синхронизируется, если driver уже закрыл ResultSet где-то.
    */
   private synchronized boolean isClosed() throws SQLException
   {
      if( closed )
          return true;

      boolean rawClosed = resultSet.isClosed();

      if( rawClosed )
          lifecycleClosed();

      return rawClosed;
   }


   /** */
   private boolean isRawClosed()
   {
      try {
         return resultSet.isClosed();
      }
      catch( SQLException ignored )
      {
         /*
          * считаем открытым.
          */
         return false;
      }
   }


   /** */
   private Object invokeRaw( Method method, Object[] args )
           throws Throwable
   {
      try
      {
         return method.invoke( resultSet, args );
      }
      catch( InvocationTargetException ex )
      {
         throw ex.getCause();
      }
   }


   /**
    * OPEN event вызывается StatementProxy - владельцем данного ResultSet.
    */
   synchronized void fireOpen()
   {
      if( openEventFired )
          return;

      openEventFired = true;

      if( eventBus == null )
          return;

      if( !eventBus.hasListeners( JdbcResultSetEvent.class ) )
      {
         return;
      }

      eventBus.fire( JdbcResultSetEvent.open( proxy, lifecycle.openCursorCount(), statement.isTraceIgnored() ) );
   }


   /**
    * CLOSE event вызываем сами, когда приходит конец жизненного цикла
    */
   private void fireClose()
   {
      if( eventBus == null )
          return;

      if(!eventBus.hasListeners( JdbcResultSetEvent.class ) )
      {
         return;
      }

      eventBus.fire( JdbcResultSetEvent.close( proxy, lifecycle.openCursorCount(), statement.isTraceIgnored() ) );
   }


   /** */
   private static Throwable unwrapThrowable( Throwable throwable )
   {
      if( throwable instanceof InvocationTargetException )
      {
         Throwable cause = throwable.getCause();
         if( cause != null )
            return cause;
      }

      return throwable;
   }
}