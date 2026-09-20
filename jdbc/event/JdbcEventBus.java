package ru.inversion.tc.jdbc.event;

import ru.inversion.utils.lstn.IListenerManConsumer;
import ru.inversion.utils.lstn.ListenerManFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h5>Служба доставки событий</h5>
 * <p>
 * Ведение подписки на события Jdbc слоя
 */
public final class JdbcEventBus
{
   private final Map<Class<? extends JdbcEvent>, IListenerManConsumer<JdbcEventListener<?>>> listenerMap = new ConcurrentHashMap<>();

  /** Добавление слушателя событий
    * <p>
    * @param <E> тип события
    */
   public synchronized <E extends JdbcEvent> void addListener( Class<E> eventClass, JdbcEventListener<? super E> listener )
   {
      if( eventClass == null || listener == null )
          return;

      IListenerManConsumer<JdbcEventListener<?>> man = listenerMap.computeIfAbsent( eventClass,k -> ListenerManFactory.createListenerManConsumer() );

      man.addListener(listener);
   }


   /** Удаление слушателя событий
    * <p>
    * @param <E> тип события
    */
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


   /** */
   public boolean hasListeners( Class<? extends JdbcEvent> eventClass )
   {
      return listenerMap.containsKey(eventClass) || listenerMap.containsKey(JdbcEvent.class);
   }


   /**
    * Уведомление слушателей
    * <p>
    * Ошибка listener-а не должна влиять на JDBC/application.
    */
   public <E extends JdbcEvent> void fire( E event )
   {
      if( event == null )
          return;

      fireForClass( event, event.getClass() );

      /* Слушатели JdbcEvent.class получают все события независимо от типа. */
      if( event.getClass() != JdbcEvent.class )
          fireForClass( event, JdbcEvent.class );
   }


   /** */
   private void fireForClass( JdbcEvent event, Class<? extends JdbcEvent> eventClass )
   {
      IListenerManConsumer<JdbcEventListener<?>> man = listenerMap.get(eventClass);

      if( man == null )
          return;

      try {
         man.fire( listener -> fireListener( listener, event ) );
      }
      catch( ThreadDeath | VirtualMachineError fatal ) {
         throw fatal;
      }
      catch( Throwable ignored ) {
         /*
          * Ошибка listener'а не должна влиять на JDBC/application.
          * TODO diagnostics/logging.
          */
      }
   }


   @SuppressWarnings({ "rawtypes", "unchecked" })
   private static void fireListener( JdbcEventListener<?> listener, JdbcEvent event )
   {
      try {
         ((JdbcEventListener) listener).onJdbcEvent(event);
      }
      catch( ThreadDeath | VirtualMachineError fatal ) {
         throw fatal;
      }
      catch( Throwable ignored ) {
         /*
          * Один сломанный listener не мешает остальным.
          *
          * TODO diagnostics/logging.
          */
      }
   }
}