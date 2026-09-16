package ru.inversion.tc.jdbc.trace;

import ru.inversion.tc.jdbc.event.EventType;
import ru.inversion.tc.jdbc.event.JdbcEvent;
import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.event.JdbcEventListener;

import ru.inversion.utils.lstn.IListenerManConsumer;
import ru.inversion.utils.lstn.ListenerManFactory;

import java.util.EnumSet;
import java.util.Map;


/**
 * <h5>JDBC tracing hub.</h5>
 * <p>
 * Получает low-level JdbcEvent из JdbcEventBus,
 * преобразует их в JdbcTraceEvent и доставляет
 * JdbcTraceListener-ам.
 * <p>
 * Также позволяет публиковать trace-события,
 * которые не имеют JdbcEvent origin.
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

   private final EnumSet<EventType> enabledJdbcEventTypes = EnumSet.allOf(EventType.class);

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

      /*
       * Default trace
       */
      addListener( JdbcTraceLoggerListener.instance() );
   }


   /**
    * Получение low-level JDBC event.
    */
   @Override
   public void onJdbcEvent( JdbcEvent event )
   {
      if( event == null )
         return;

      trace(JdbcTraceEvent.jdbc(event));
   }

   /**
    * Публикация готового trace event.
    * <p>
    * Используется, в том числе для событий, которые не имеют JdbcEvent.
    */
   public void trace( JdbcTraceEvent event )
   {
      if( event == null )
          return;

      if( !isTraceEnabled( event ) )
         return;

      fire(event);
   }


   /**
    * Custom trace event.
    */
   public void trace( Object source, JdbcTraceType type, String text )
   {
      if( !isTraceEnabled(type) )
         return;

      fire( JdbcTraceEvent.custom( source, type, text ) );
   }


   /**
    * Custom trace event с properties.
    */
   public void trace( Object source, JdbcTraceType type, String text, Map<String, Object> properties )
   {
      if( !isTraceEnabled(type) )
         return;

      fire( JdbcTraceEvent.custom( source, type, text, properties ) );
   }


   /** */
   public synchronized void addListener( JdbcTraceListener listener )
   {
      if( listener == null )
          return;

      if( closed )
          throw new IllegalStateException( "JdbcTracer is closed" );

      listeners.addListener(listener);
   }


   /** */
   public synchronized void removeListener( JdbcTraceListener listener )
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
   public void setEnabled( boolean enabled )
   {
      this.enabled = enabled;
   }


   /**
    * Включение/выключение отдельной trace category.
    */
   public synchronized void setEnabled( JdbcTraceType type, boolean enabled )
   {
      if( type == null )
          //throw new IllegalArgumentException( "type is null" );
         return;

      if( enabled )
         enabledTypes.add(type);
      else
         enabledTypes.remove(type);
   }


   /** */
   public synchronized boolean isEnabled( JdbcTraceType type  )
   {
      if( type == null )
          return false;

      return enabledTypes.contains(type);
   }


   private boolean isTraceEnabled( JdbcTraceType type )
   {
      if( !enabled )
         return false;

      return isEnabled(type);
   }

   /**
    * Общий + category-level switch.
    */
   private boolean isTraceEnabled( JdbcTraceEvent event )
   {
      if( !enabled )
         return false;

      if( !isEnabled(event.type()) )
         return false;

      JdbcEvent jdbcEvent = event.jdbcEvent();

      return jdbcEvent == null || isJdbcEventEnabled(jdbcEvent.type());
   }


   /** включение/выключение jdbc event */
   public synchronized void setJdbcEventEnabled( EventType type, boolean enabled )
   {
      if( type == null )
          return;

      if( enabled )
          enabledJdbcEventTypes.add(type);
      else
          enabledJdbcEventTypes.remove(type);
   }


   /** */
   public synchronized boolean isJdbcEventEnabled( EventType type )
   {
      if( type == null )
          return false;
      return enabledJdbcEventTypes.contains(type);
   }

   /**
    * Observation-only dispatch.
    *
    * Каждый listener изолирован отдельно:
    * один broken listener не мешает остальным.
    */
   private void fire( JdbcTraceEvent event )
   {
      try {
         listeners.fire( listener -> fireListener( listener, event ) );
      }
      catch( ThreadDeath | VirtualMachineError fatal )
      {
         throw fatal;
      }
      catch( Throwable ignored )
      {
         /*
          * Ошибка listener не должна влиять на JDBC/application.
          *
          * TODO diagnostics/logging.
          */
      }
   }


   /** */
   private static void fireListener( JdbcTraceListener listener, JdbcTraceEvent event )
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
      eventBus.removeListener( JdbcEvent.class, this );
   }
}