package ru.inversion.tc.jdbc.trace;

import ru.inversion.tc.jdbc.event.EventPhase;
import ru.inversion.tc.jdbc.event.JdbcEvent;
import ru.inversion.utils.Checks;

import java.io.Writer;
import java.util.Collections;
import java.util.EventObject;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * <h5>Пользовательское trace-событие.</h5>
 * <p>
 * Может:
 * <ol>
 * <li>оборачивать low-level JdbcEvent;
 * <li>быть самостоятельным trace-событием,
 *     не связанным с JDBC object.
 * </ol>
 * <p>
 * Для JDBC-origin события данные JdbcEvent
 * не копируются.
 * <p>
 * JdbcEvent сохраняет точное техническое событие,
 * JdbcTraceType представляет его friendly-категорию
 * для пользователя.
 */
public final class JdbcTraceEvent extends EventObject
{
   /*
    * Исходное low-level JDBC событие.
    *
    * null для custom trace event.
    */
   private final JdbcEvent jdbcEvent;

   /*
    * Пользовательская категория trace.
    */
   private final JdbcTraceType type;

   /*
    * Текст custom trace-события.
    *
    * Для JDBC-origin события обычно null:
    * данные находятся в jdbcEvent.
    */
   private final String text;

   /*
    * Время события.
    *
    * Для JDBC-origin сохраняем timestamp
    * исходного JdbcEvent.
    */
   private final long timestampNanos;

   /*
    * Дополнительные trace properties.
    */
   private final Map<String, Object> properties;


   /** */
   private JdbcTraceEvent(
      Object source,
      JdbcEvent jdbcEvent,
      JdbcTraceType type,
      String text,
      Map<String, Object> properties
   )
   {
      super(source);
      Checks.Require.object( type, "type" );

      this.jdbcEvent = jdbcEvent;
      this.type      = type;
      this.text      = text;

      this.timestampNanos = jdbcEvent != null ? jdbcEvent.timestampNanos() : System.nanoTime();
      this.properties     = snapshot(properties);
   }


   /**
    * Исходное low-level JDBC событие.
    *
    * @return JdbcEvent или null для custom trace event
    */
   public JdbcEvent jdbcEvent()
   {
      return jdbcEvent;
   }


   /** */
   public boolean hasJdbcEvent()
   {
      return jdbcEvent != null;
   }


   /**
    * Friendly trace-категория.
    */
   public JdbcTraceType type()
   {
      return type;
   }


   /**
    * Текст custom trace-события.
    */
   public String text()
   {
      return text;
   }


   /** */
   public long timestampNanos()
   {
      return timestampNanos;
   }


   /** */
   public Map<String, Object> properties()
   {
      return properties;
   }


   /**
    * Получить event property.
    */
   @SuppressWarnings("unchecked")
   public <T> T property( String name )
   {
      if( name == null )
          return null;

      return (T) properties.get(name);
   }


   /**
    * Оборачивает low-level JdbcEvent
    * в пользовательское trace-событие.
    */
   public static JdbcTraceEvent jdbc( JdbcEvent event )
   {
      return jdbc( event, null );
   }


   /**
    * Оборачивает low-level JdbcEvent
    * в пользовательское trace-событие
    * с дополнительными properties.
    */
   public static JdbcTraceEvent jdbc(
           JdbcEvent event,
           Map<String, Object> properties
   )
   {
      Checks.Require.object(
              event,
              "event"
      );

      return new JdbcTraceEvent(
              event.getSource(),
              event,
              traceType(event),
              null,
              properties
      );
   }


   /**
    * Преобразует точное low-level JDBC событие
    * в пользовательскую trace-категорию.
    */
   private static JdbcTraceType traceType(
           JdbcEvent event
   )
   {
      /*
       * Ошибка любого JDBC operation полезна пользователю,
       * даже если исходное событие относится к TECH.
       */
      if( event.phase() == EventPhase.ERROR )
         return JdbcTraceType.ERROR;

      switch( event.type() )
      {
         case STATEMENT_EXECUTE:

            switch( event.phase() )
            {
               case BEFORE:
                  return JdbcTraceType.SQL;

               case AFTER:
                  return JdbcTraceType.RESULT;

               default:
                  return JdbcTraceType.TECH;
            }

         case NOTICE:
            return JdbcTraceType.NOTICE;

         case DBMS_OUTPUT:
            return JdbcTraceType.DBMS_OUTPUT;

         default:
            /*
             * CONNECTION_OPEN/CLOSE
             * STATEMENT_OPEN/CLOSE
             * RESULT_SET_OPEN/CLOSE
             * TRANSACTION_*
             * SAVEPOINT_*
             *
             * Это внутренние диагностические события.
             */
            return JdbcTraceType.TECH;
      }
   }


   /**
    * Самостоятельное trace-событие,
    * не имеющее JdbcEvent.
    */
   public static JdbcTraceEvent custom(
           Object source,
           JdbcTraceType type,
           String text
   )
   {
      return custom(
              source,
              type,
              text,
              null
      );
   }


   /**
    * Самостоятельное trace-событие
    * с дополнительными properties.
    */
   public static JdbcTraceEvent custom(
           Object source,
           JdbcTraceType type,
           String text,
           Map<String, Object> properties
   )
   {
      return new JdbcTraceEvent(
              source,
              null,
              type,
              text,
              properties
      );
   }


   /**
    * Создаёт immutable snapshot properties.
    */
   private static Map<String, Object> snapshot(
           Map<String, Object> source
   )
   {
      if( source == null || source.isEmpty() )
         return Collections.emptyMap();

      return Collections.unmodifiableMap(
              new LinkedHashMap<>(source)
      );
   }


   /**
    * Форматированный вывод события.
    */
   public void print(
           Writer writer
   )
   {
      JdbcTraceEventWriter.write(
              this,
              writer
      );
   }
}
