package ru.inversion.tc.jdbc.event;

import ru.inversion.utils.lstn.IListenerManConsumer;
import ru.inversion.utils.lstn.ListenerManFactory;


import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** */
public final class JdbcEventBus
{
   private final Map< Class<? extends JdbcEvent>, IListenerManConsumer<JdbcEventListener<?>>> listenerMap = new ConcurrentHashMap<>();

   public synchronized <E extends JdbcEvent> void addListener( Class<E> eventClass, JdbcEventListener<? super E> listener )
   {
      if( eventClass == null || listener == null )
          return;

      IListenerManConsumer<JdbcEventListener<?>> man = listenerMap.get(eventClass);

      if( man == null )
      {
         man = ListenerManFactory.createListenerManConsumer();
         listenerMap.put(eventClass, man);
      }

      man.addListener(listener);
   }


   public synchronized <E extends JdbcEvent> void removeListener( Class<E> eventClass, JdbcEventListener<? super E> listener )
   {
      if( eventClass == null || listener == null )
         return;

      IListenerManConsumer<JdbcEventListener<?>> man = listenerMap.get(eventClass);

      if( man == null )
          return;

      man.removeListener(listener);

      if( man.isEmpty() )
          listenerMap.remove(eventClass);
   }


   public boolean isEmpty()
   {
      return listenerMap.isEmpty();
   }


   public void fire( JdbcEvent event )
   {
      if( event == null )
          return;

      fireForClass( event, event.getClass());

      /*
       * Listener на JdbcEvent.class получает все события.
       */
      if( event.getClass() != JdbcEvent.class )
          fireForClass( event, JdbcEvent.class);
   }


   /** */
   private void fireForClass( JdbcEvent event, Class<? extends JdbcEvent> eventClass )
   {
      IListenerManConsumer<JdbcEventListener<?>> man = listenerMap.get(eventClass);
      if( man == null )
         return;

      fire(man, event);
   }


   /** */
   @SuppressWarnings({ "rawtypes", "unchecked" })
   private static void fire( IListenerManConsumer<JdbcEventListener<?>> man, JdbcEvent event )
   {
      man.fire( listener -> ((JdbcEventListener) listener).onJdbcEvent(event));
   }
}