package ru.inversion.tc.jdbc.internal.proxy;

import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.event.JdbcResultSetEvent;
import ru.inversion.tc.jdbc.internal.lifecycle.JdbcLifecycleManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** */
public final class JdbcResultSetProxy implements InvocationHandler
{

   private final ResultSet resultSet;

   /*
    * Именно proxy Statement.
    * getStatement() не должен выпускать наружу raw Statement.
    */
   private final Statement statement;

   private final JdbcLifecycleManager lifecycle;

   private final JdbcEventBus eventBus;

   private final long resultSetId;

   private ResultSet proxy;

   /*
    * Наш lifecycle state.
    * Не обязательно совпадает с состоянием raw ResultSet,
    * пока StatementProxy не сообщил о неявном close.
    */
   private boolean closed;


   /** */
   private JdbcResultSetProxy (
      ResultSet resultSet,
      Statement statement,
      long statementId,
      JdbcLifecycleManager lifecycle,
      JdbcEventBus eventBus
   )
   {
      if( resultSet == null )
         throw new IllegalArgumentException("resultSet is null");

      if( statement == null )
          throw new IllegalArgumentException("statement is null");

      if( lifecycle == null )
          throw new IllegalArgumentException("lifecycle is null");

      this.resultSet = resultSet;
      this.statement = statement;
      this.lifecycle = lifecycle;
      this.eventBus  = eventBus;

      this.resultSetId = lifecycle.registerResultSet( resultSet, statementId );
   }


   /**
    * Создаёт handler + proxy.
    * <p>
    * StatementProxy должен сохранить возвращённый handler, а наружу отдать proxy().
    */
   public static JdbcResultSetProxy create (
      ResultSet resultSet,
      Statement statement,
      long statementId,
      JdbcLifecycleManager lifecycle,
      JdbcEventBus eventBus
   )
   {
      final JdbcResultSetProxy handler = new JdbcResultSetProxy( resultSet, statement, statementId, lifecycle, eventBus );
      handler.proxy = (ResultSet) Proxy.newProxyInstance( JdbcResultSetProxy.class.getClassLoader(), new Class<?>[] { ResultSet.class }, handler );

      handler.fireOpen();

      return handler;
   }


   /** */
   public ResultSet proxy()
   {
      return proxy;
   }


   /** */
   ResultSet raw()
   {
      return resultSet;
   }


   /** */
   public long resultSetId()
   {
      return resultSetId;
   }


   /** */
   public boolean isClosed()
   {
      return closed;
   }


   @Override
   public Object invoke( Object proxy, Method method, Object[] args ) throws Throwable
   {

      final String methodName = method.getName();

      /*
       * Object methods не делегируем raw объекту.
       */
      if( Object.class.equals(method.getDeclaringClass()) )
          return invokeObjectMethod( proxy, methodName, args );

      if( "close".equals(methodName) )
      {
         close();
         return null;
      }

      /*
       * Нельзя отдавать raw Statement наружу.
       */
      if( "getStatement".equals(methodName) && method.getParameterTypes().length == 0 )
      {
         return statement;
      }

      /*
       * unwrap(ResultSet.class) должен оставить клиента
       * внутри нашего proxy.
       */
      if( "unwrap".equals(methodName)
              && args != null
              && args.length == 1 )
      {
         Class<?> clazz = (Class<?>) args[0];

         if( clazz.isInstance(proxy) )
            return clazz.cast(proxy);
      }

      if( "isWrapperFor".equals(methodName)
              && args != null
              && args.length == 1 )
      {
         Class<?> clazz = (Class<?>) args[0];

         if( clazz.isInstance(proxy) )
            return true;
      }

      return invokeRaw(method, args);
   }


   /**
    * Явный ResultSet.close().
    */
   private synchronized void close()
           throws Throwable
   {
      if( closed )
         return;

      boolean rawClosed = false;

      try
      {
         resultSet.close();
         rawClosed = true;
      }
      catch( Throwable throwable )
      {
         /*
          * close() мог физически закрыть объект и всё же
          * вернуть ошибку. Проверяем состояние консервативно.
          */
         rawClosed = isRawClosed();

         if( rawClosed )
            lifecycleClosed();

         throw unwrapThrowable(throwable);
      }

      if( rawClosed )
         lifecycleClosed();
   }


   /**
    * Statement.close() / повторный execute*() закрыл raw ResultSet
    * без вызова ResultSetProxy.close().
    *
    * Raw close здесь НЕ выполняем.
    */
   synchronized void closedByStatement()
   {
      if( closed )
         return;

      lifecycleClosed();
   }


   /**
    * Единственная точка изменения нашего lifecycle state.
    */
   private void lifecycleClosed()
   {
      if( closed )
         return;

      boolean removed =
              lifecycle.unregisterResultSet(
                      resultSet,
                      resultSetId
              );

      if( !removed )
      {
         /*
          * Уже был unregister с другой стороны.
          * Для данного proxy ресурс считается закрытым.
          */
         closed = true;
         return;
      }

      closed = true;

      fireClose();
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
         return "JdbcResultSetProxy["
                 + resultSetId
                 + "]";

      throw new IllegalStateException(
              "Unsupported Object method: " + methodName
      );
   }


   /** */
   private void fireOpen()
   {
      if( eventBus == null )
         return;

      if( !eventBus.hasListeners(JdbcResultSetEvent.class) )
         return;

      eventBus.fire(
              JdbcResultSetEvent.open(
                      proxy,
                      resultSetId,
                      lifecycle.openResultSetCount()
              )
      );
   }


   /** */
   private void fireClose()
   {
      if( eventBus == null )
         return;

      if( !eventBus.hasListeners( JdbcResultSetEvent.class ) )
           return;

      eventBus.fire( JdbcResultSetEvent.close( proxy, resultSetId, lifecycle.openResultSetCount() ) );
   }


   /** */
   private static Throwable unwrapThrowable( Throwable throwable )
   {
      if( throwable instanceof InvocationTargetException )
      {
         Throwable cause = ((InvocationTargetException) throwable).getCause();
         if( cause != null )
             return cause;
      }
      return throwable;
   }
}