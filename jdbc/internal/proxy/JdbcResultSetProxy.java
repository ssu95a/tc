package ru.inversion.tc.jdbc.internal.proxy;

import ru.inversion.tc.jdbc.event.JdbcEvent;
import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.event.JdbcResultSetEvent;
import ru.inversion.tc.jdbc.internal.lifecycle.JdbcLifecycleManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;


/**
 * Proxy только для Statement-owned cursor ResultSet.
 * <p>
 * Не предназначен для:
 * - Statement.getGeneratedKeys()
 * - Array.getResultSet()
 * - DatabaseMetaData ResultSet
 * - прочих служебных JDBC ResultSet
 */
public final class JdbcResultSetProxy implements InvocationHandler
{
   private final ResultSet resultSet;

   /*
    * Owner Statement handler.
    *
    * Нужен:
    * - для getStatement() -> proxy Statement
    * - для удаления себя из owner cache
    * - для closeOnCompletion synchronization
    */
   private final JdbcStatementProxy statement;

   private final JdbcLifecycleManager lifecycle;
   private final JdbcEventBus eventBus;

   private ResultSet proxy;

   /*
    * Lifecycle state нашего proxy.
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

   private final JdbcLifecycleManager.CursorRegistration registration;


   /** */
   private JdbcResultSetProxy( ResultSet resultSet, JdbcStatementProxy statement, JdbcLifecycleManager lifecycle, JdbcEventBus eventBus )
   {
      if( resultSet == null )
          throw new IllegalArgumentException( "resultSet is null" );

      if( statement == null )
          throw new IllegalArgumentException("statement is null" );

      if( lifecycle == null )
          throw new IllegalArgumentException( "lifecycle is null" );

      this.resultSet = resultSet;
      this.statement = statement;
      this.lifecycle = lifecycle;
      this.eventBus  = eventBus;

      registration   = lifecycle.registerCursor(resultSet);
   }


   /**
    * Создаёт handler и JDBC proxy.
    *
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
    *
    * Driver здесь не опрашиваем.
    */
   boolean isLifecycleClosed()
   {
      return closed;
   }


   @Override
   public Object invoke( Object proxy, Method method, Object[] args ) throws Throwable
   {
      final String methodName = method.getName();

      /*
       * Object identity нашего proxy никак
       * не зависит от equals/hashCode driver-а.
       */
      if( Object.class.equals( method.getDeclaringClass() ) )
      {
         return invokeObjectMethod( proxy, methodName, args );
      }

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
       * Никогда не выпускаем raw Statement
       * через tracked cursor ResultSet.
       */
      if( "getStatement".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         return statement.proxy();
      }

      /*
       * unwrap(ResultSet.class) должен оставить
       * пользователя внутри proxy.
       *
       * Vendor-specific unwrap делегируется driver-у.
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

      return invokeRaw(
              method,
              args
      );
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
    * Единственная точка завершения cursor lifecycle.
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

      if( removed )
          statement.cursorStateChanged();
   }

   /**
    * isClosed() синхронизирует lifecycle, если driver
    * уже закрыл ResultSet не через наш proxy.
    */
   private synchronized boolean isClosed()
           throws SQLException
   {
      if( closed )
         return true;

      boolean rawClosed =
              resultSet.isClosed();

      if( rawClosed )
         lifecycleClosed();

      return rawClosed;
   }


   /** */
   private boolean isRawClosed()
   {
      try
      {
         return resultSet.isClosed();
      }
      catch( SQLException ignored )
      {
         /*
          * Консервативно считаем ресурс ещё открытым.
          */
         return false;
      }
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
                 resultSet,
                 args
         );
      }
      catch( InvocationTargetException ex )
      {
         throw ex.getCause();
      }
   }


   /** */
   private Object invokeObjectMethod( Object proxy, String methodName, Object[] args )
   {
      if( "equals".equals(methodName) )
          return proxy == args[0];

      if( "hashCode".equals(methodName) )
           return System.identityHashCode(proxy);

      if( "toString".equals(methodName) )
      {
         return "JdbcResultSetProxy[" + "]";
      }

      throw new IllegalStateException( "Unsupported Object method: " + methodName );
   }


   /**
    * OPEN event вызывается owner StatementProxy.
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

      safeFire( JdbcResultSetEvent.open( proxy, lifecycle.openCursorCount() ) );
   }


   /** */
   private void fireClose()
   {
      if( eventBus == null )
          return;

      if(!eventBus.hasListeners( JdbcResultSetEvent.class ) )
      {
         return;
      }

      safeFire( JdbcResultSetEvent.close( proxy, lifecycle.openCursorCount() ) );
   }


   /**
    * Events являются observation-only.
    * <p>
    * Ошибка listener-а не должна превращать
    * успешный JDBC operation в ошибку приложения.
    */
   private void safeFire( JdbcEvent event )
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
          * TODO diagnostics/logging на уровне JdbcEventBus.
          */
      }
   }


   /** */
   private static Throwable unwrapThrowable(
           Throwable throwable
   )
   {
      if( throwable instanceof InvocationTargetException )
      {
         Throwable cause =
                 ((InvocationTargetException) throwable)
                         .getCause();

         if( cause != null )
            return cause;
      }

      return throwable;
   }
}