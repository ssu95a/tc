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
 * Получает точные low-level JdbcEvent из JdbcEventBus,
 * преобразует их в friendly JdbcTraceEvent и доставляет
 * JdbcTraceListener-ам.
 * <p>
 * Low-level слой:
 * <ul>
 * <li>EventType;
 * <li>EventPhase;
 * <li>JdbcEvent и его наследники.
 * </ul>
 * <p>
 * Пользовательский trace-слой:
 * <ul>
 * <li>SQL;
 * <li>RESULT;
 * <li>ERROR;
 * <li>NOTICE;
 * <li>DBMS_OUTPUT;
 * <li>INFO;
 * <li>WARNING;
 * <li>TECH.
 * </ul>
 * <p>
 * Также позволяет публиковать custom trace-события,
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
    * Friendly trace-категории, включённые по умолчанию.
    *
    * TECH выключен:
    * low-level lifecycle не должен шуметь в пользовательской форме.
    *
    * DBMS_OUTPUT выключен:
    * его включение меняет состояние server session и может
    * создавать большой объём данных.
    *
    * Deprecated JDBC выключен:
    * оставлен только как compatibility umbrella.
    */
   private final EnumSet<JdbcTraceType> enabledTypes =
           EnumSet.of(
             JdbcTraceType.SQL,
             JdbcTraceType.RESULT,
             JdbcTraceType.ERROR,
             JdbcTraceType.INFO,
             JdbcTraceType.WARNING
           );

   /*
    * Дополнительный low-level фильтр.
    *
    * По умолчанию JDBC core события разрешены все.
    * Friendly filtering выполняется отдельно через enabledTypes.
    */
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
       * Default trace consumer.
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

      /*
       * --lti скрывает пользовательский trace.
       *
       * Внутренние JDBC events при этом уже были созданы
       * и могли быть обработаны другими internal listeners.
       */
      if( event.isTraceIgnored() )
          return;

      trace( JdbcTraceEvent.jdbc(event) );
   }


   /**
    * Публикация готового trace event.
    */
   public void trace( JdbcTraceEvent event )
   {
      if( event == null )
          return;

      if( !isTraceEnabled(event) )
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
    * Включение или выключение friendly trace-категории.
    */
   public synchronized void setEnabled( JdbcTraceType type, boolean enabled )
   {
      if( type == null )
          return;

      if( enabled )
          enabledTypes.add(type);
      else
          enabledTypes.remove(type);
   }


   /**
    * Включена ли friendly trace-категория.
    */
   public synchronized boolean isEnabled( JdbcTraceType type )
   {
      if( type == null )
          return false;

      return enabledTypes.contains(type);
   }


   /**
    * Проверка trace event.
    */
   private boolean isTraceEnabled( JdbcTraceType type )
   {
      if( !enabled )
         return false;

      return isEnabled(type);
   }


   /**
    * Общий + friendly + low-level фильтр.
    */
   private boolean isTraceEnabled( JdbcTraceEvent event )
   {
      if( !enabled )
         return false;

      JdbcEvent jdbcEvent = event.jdbcEvent();

      /*
       * Custom trace event:
       * фильтруется только по friendly category.
       */
      if( jdbcEvent == null )
          return isEnabled(event.type());

      /*
       * JDBC-origin event:
       * сначала проверяем точный low-level EventType.
       */
      if( !isJdbcEventEnabled(jdbcEvent.type()) )
          return false;

      /*
       * Основной friendly filter.
       */
      if( isEnabled(event.type()) )
          return true;

      /*
       * Compatibility со старым JdbcTraceType.JDBC.
       *
       * Раньше JDBC означал обычные JDBC events,
       * но ERROR, NOTICE и DBMS_OUTPUT уже имели
       * отдельные категории.
       */
      if( !isEnabled(JdbcTraceType.JDBC) )
         return false;

      switch( event.type() )
      {
         case SQL:
         case RESULT:
         case TECH:
            return true;

         default:
            return false;
      }
   }


   /**
    * Включение или выключение точного low-level JDBC EventType.
    */
   public synchronized void setJdbcEventEnabled( EventType type, boolean enabled )
   {
      if( type == null )
          return;

      if( enabled )
          enabledJdbcEventTypes.add(type);
      else
          enabledJdbcEventTypes.remove(type);
   }


   /**
    * Разрешён ли точный low-level JDBC EventType.
    */
   public synchronized boolean isJdbcEventEnabled( EventType type )
   {
      if( type == null )
          return false;

      return enabledJdbcEventTypes.contains(type);
   }


   /**
    * Используется server-output infrastructure.
    * <p>
    * Метод получает только EventType, поэтому для
    * STATEMENT_EXECUTE не может различить BEFORE,
    * AFTER и ERROR.
    * <p>
    * Он отвечает на более общий вопрос:
    * может ли событие такого low-level типа
    * понадобиться включённым friendly-категориям.
    */
   public boolean isJdbcEventTraceEnabled( EventType type )
   {
      if( !enabled || type == null )
          return false;

      if( !isJdbcEventEnabled(type) )
         return false;

      switch( type )
      {
         case NOTICE:
            return isEnabled( JdbcTraceType.NOTICE );
         case DBMS_OUTPUT:
            return isEnabled( JdbcTraceType.DBMS_OUTPUT );
         case STATEMENT_EXECUTE:
            /*
             * Один low-level EventType порождает:
             *
             * BEFORE -> SQL
             * AFTER  -> RESULT
             * ERROR  -> ERROR
             */
            return isEnabled(JdbcTraceType.SQL) || isEnabled(JdbcTraceType.RESULT) || isEnabled(JdbcTraceType.ERROR) || isEnabled(JdbcTraceType.JDBC);

         default:
            /*
             * Успешное lifecycle событие -> TECH.
             * Ошибка lifecycle operation -> ERROR.
             */
            return isEnabled(JdbcTraceType.TECH) || isEnabled(JdbcTraceType.ERROR) || isEnabled(JdbcTraceType.JDBC);
      }
   }


   /**
    * Observation-only dispatch.
    * <p>
    * Каждый listener изолирован отдельно:
    * один broken listener не мешает остальным.
    */
   private void fire( JdbcTraceEvent event )
   {
      try {
         listeners.fire( listener ->fireListener( listener, event ) );
      }
      catch( ThreadDeath | VirtualMachineError fatal ) {
         throw fatal;
      }
      catch( Throwable ignored )
      {
         /*
          * Ошибка listener не должна влиять
          * на JDBC/application.
          *
          * TODO diagnostics/logging.
          */
      }
   }


   /** */
   private static void fireListener( JdbcTraceListener listener, JdbcTraceEvent event )
   {
      try {
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
