package ru.inversion.tc.jdbc.event;

import ru.inversion.utils.lstn.IListenerManConsumer;
import ru.inversion.utils.lstn.ListenerManFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** */
public final class JdbcEventBus
{
   /** Слушатели разных типов событий, в зависимости от типа - тип, класс события */
   private final Map< Class<? extends JdbcEvent>, IListenerManConsumer<JdbcEventListener<?>>>
      listenerMap = new ConcurrentHashMap<>();

   /** */
   public synchronized <E extends JdbcEvent> void addListener( Class<E> eventClass, JdbcEventListener<? super E> listener )
   {
      if( eventClass == null || listener == null )
          return;

      IListenerManConsumer<JdbcEventListener<?>> man =
              listenerMap.computeIfAbsent( eventClass, (k)->ListenerManFactory.createListenerManConsumer() );
      man.addListener(listener);
   }

   /** */
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


   /** */
   public boolean isEmpty()
   {
      return listenerMap.isEmpty();
   }

   /** */
   public <E extends JdbcEvent> void fire( E event )
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


   /** */
   public boolean hasListeners( Class<? extends JdbcEvent> eventClass )
   {
      return listenerMap.containsKey(eventClass) || listenerMap.containsKey(JdbcEvent.class);
   }


   /** */
   public <E extends JdbcEvent> void fireSafely( E event )
   {
      if( event == null )
          return;

      fireForClassSafely( event, event.getClass() );

      if( event.getClass() != JdbcEvent.class )
          fireForClassSafely( event, JdbcEvent.class );
   }


   /** */
   private void fireForClassSafely( JdbcEvent event, Class<? extends JdbcEvent> eventClass )
   {
      IListenerManConsumer<JdbcEventListener<?>> man = listenerMap.get(eventClass);

      if( man == null )
          return;

      try
      {
         man.fire( listener -> fireListenerSafely( listener, event ) );
      }
      catch( ThreadDeath | VirtualMachineError fatal )
      {
         throw fatal;
      }
      catch( Throwable ignored )
      {
         /*
          * Ошибка самого listener manager.
          *
          * TODO diagnostics/logging.
          */
      }
   }


   @SuppressWarnings({ "rawtypes", "unchecked" })
   private static void fireListenerSafely( JdbcEventListener<?> listener, JdbcEvent event )
   {
      try
      {
         ((JdbcEventListener) listener).onJdbcEvent(event);
      }
      catch( ThreadDeath | VirtualMachineError fatal )
      {
         throw fatal;
      }
      catch( Throwable ignored )
      {
         /*
          * Observation-only.
          *
          * Один broken listener не мешает:
          * - JDBC operation
          * - остальным listeners
          * - global JdbcEvent listener
          *
          * TODO diagnostics/logging.
          */
      }
   }
}