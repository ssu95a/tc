package ru.inversion.tc.jdbc.trace;

import ru.inversion.tc.jdbc.event.JdbcEvent;
import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.event.JdbcEventListener;

import ru.inversion.utils.lstn.IListenerManConsumer;
import ru.inversion.utils.lstn.ListenerManFactory;

import java.util.EnumSet;
import java.util.Map;


/**
 * JDBC tracing hub.
 *
 * Получает low-level JdbcEvent из JdbcEventBus,
 * преобразует их в JdbcTraceEvent и доставляет
 * JdbcTraceListener-ам.
 *
 * Также позволяет публиковать trace-события,
 * которые не имеют JdbcEvent origin.
 *
 * Tracing является observation-only:
 * ошибка JdbcTraceListener не должна влиять
 * на JDBC operation и остальных listeners.
 */
public final class JdbcTracer implements JdbcEventListener<JdbcEvent>, AutoCloseable
{
   private final JdbcEventBus eventBus;

   private final IListenerManConsumer<JdbcTraceListener> listeners = ListenerManFactory.createListenerManConsumer();

   /*
    * Общий выключатель tracing.
    */
   private volatile boolean enabled = true;

   /*
    * Включённые категории trace.
    */
   private final EnumSet<JdbcTraceType> enabledTypes = EnumSet.allOf(JdbcTraceType.class);

   private boolean closed;

   /** */
   public JdbcTracer( JdbcEventBus eventBus )
   {
      if( eventBus == null )
          throw new IllegalArgumentException( "eventBus is null" );

      this.eventBus = eventBus;

      /*
       * Одна глобальная подписка на весь JDBC core.
       */
      eventBus.addListener( JdbcEvent.class, this );
   }


   /**
    * Получение low-level JDBC event.
    */
   @Override
   public void onJdbcEvent(
           JdbcEvent event
   )
   {
      if( event == null )
         return;

      if( !isTraceEnabled(
              JdbcTraceType.JDBC
      ) )
      {
         return;
      }

      fire(
              JdbcTraceEvent.jdbc(event)
      );
   }


   /**
    * Публикация готового trace event.
    *
    * Используется в том числе для событий,
    * которые не имеют JdbcEvent origin.
    */
   public void trace(
           JdbcTraceEvent event
   )
   {
      if( event == null )
         return;

      if( !isTraceEnabled(
              event.type()
      ) )
      {
         return;
      }

      fire(event);
   }


   /**
    * Custom trace event.
    */
   public void trace(
           Object source,
           JdbcTraceType type,
           String text
   )
   {
      if( !isTraceEnabled(type) )
         return;

      fire(
              JdbcTraceEvent.custom(
                      source,
                      type,
                      text
              )
      );
   }


   /**
    * Custom trace event с properties.
    */
   public void trace(
           Object source,
           JdbcTraceType type,
           String text,
           Map<String, Object> properties
   )
   {
      if( !isTraceEnabled(type) )
         return;

      fire(
              JdbcTraceEvent.custom(
                      source,
                      type,
                      text,
                      properties
              )
      );
   }


   /** */
   public synchronized void addListener(
           JdbcTraceListener listener
   )
   {
      if( listener == null )
         return;

      if( closed )
         throw new IllegalStateException(
                 "JdbcTracer is closed"
         );

      listeners.addListener(listener);
   }


   /** */
   public synchronized void removeListener(
           JdbcTraceListener listener
   )
   {
      if( listener == null )
         return;

      listeners.removeListener(listener);
   }


   /** */
   public boolean isEnabled()
   {
      return enabled;
   }


   /** */
   public void setEnabled(
           boolean enabled
   )
   {
      this.enabled = enabled;
   }


   /**
    * Включение/выключение отдельной
    * trace category.
    */
   public synchronized void setEnabled(
           JdbcTraceType type,
           boolean enabled
   )
   {
      if( type == null )
         throw new IllegalArgumentException(
                 "type is null"
         );

      if( enabled )
         enabledTypes.add(type);
      else
         enabledTypes.remove(type);
   }


   /** */
   public synchronized boolean isEnabled(
           JdbcTraceType type
   )
   {
      if( type == null )
         return false;

      return enabledTypes.contains(type);
   }


   /**
    * Общий + category-level switch.
    */
   private boolean isTraceEnabled(
           JdbcTraceType type
   )
   {
      if( !enabled )
         return false;

      return isEnabled(type);
   }


   /**
    * Observation-only dispatch.
    *
    * Каждый listener изолирован отдельно:
    * один broken listener не мешает остальным.
    */
   private void fire(
           JdbcTraceEvent event
   )
   {
      try
      {
         listeners.fire(
                 listener ->
                         fireListener(
                                 listener,
                                 event
                         )
         );
      }
      catch( ThreadDeath | VirtualMachineError fatal )
      {
         throw fatal;
      }
      catch( Throwable ignored )
      {
         /*
          * Ошибка listener manager также
          * не должна влиять на JDBC/application logic.
          *
          * TODO diagnostics/logging.
          */
      }
   }


   /** */
   private static void fireListener(
           JdbcTraceListener listener,
           JdbcTraceEvent event
   )
   {
      try
      {
         listener.onJdbcTrace(event);
      }
      catch( ThreadDeath | VirtualMachineError fatal )
      {
         throw fatal;
      }
      catch( Throwable ignored )
      {
         /*
          * Trace listener observation-only.
          *
          * TODO diagnostics/logging.
          */
      }
   }


   /**
    * Отписка от JDBC event layer.
    *
    * Повторный close безопасен.
    */
   @Override
   public synchronized void close()
   {
      if( closed )
         return;

      closed = true;

      eventBus.removeListener(
              JdbcEvent.class,
              this
      );
   }
}