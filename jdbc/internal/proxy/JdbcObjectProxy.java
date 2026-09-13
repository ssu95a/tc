package ru.inversion.tc.jdbc.internal.proxy;

import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.internal.lifecycle.JdbcLifecycleManager;

import java.lang.reflect.InvocationHandler;
import java.util.concurrent.atomic.AtomicInteger;

/** */
abstract class JdbcObjectProxy implements InvocationHandler {

   static final private AtomicInteger idGenerator = new AtomicInteger(0);

   final private int objectId = idGenerator.incrementAndGet();

   final protected JdbcLifecycleManager lifecycle;

   /** Полчатель-доставщик событий */
   final protected JdbcEventBus eventBus;

   /** */
   JdbcObjectProxy( JdbcLifecycleManager lifecycle, JdbcEventBus eventBus ) {

      if( lifecycle == null )
         throw new IllegalArgumentException( "lifecycle is null" );

      this.lifecycle = lifecycle;
      this.eventBus  = eventBus;
   }

   /** */
   protected Object invokeObjectMethod ( Object proxy, String methodName, Object[] args )
   {
      if( "equals".equals(methodName) )
          return proxy == args[0];

      if( "hashCode".equals(methodName) )
          return System.identityHashCode(proxy);

      if( "toString".equals(methodName) )
          return proxy.getClass().getSimpleName() + "@" + objectId;

      throw new IllegalStateException( "Unsupported Object method: " + methodName );
   }

}
