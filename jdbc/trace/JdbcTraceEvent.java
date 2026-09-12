package ru.inversion.tc.jdbc.trace;

import ru.inversion.tc.jdbc.event.JdbcEvent;

import java.util.Collections;
import java.util.EventObject;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Событие tracing layer.
 *
 * Может:
 *
 * 1. оборачивать low-level JdbcEvent;
 * 2. быть самостоятельным trace-событием,
 *    не связанным с JDBC объектом.
 *
 * Для JDBC-origin события данные JdbcEvent
 * не копируются и остаются единственным
 * источником истины.
 */
public final class JdbcTraceEvent extends EventObject
{
   /*
    * Исходное JDBC событие.
    *
    * null для custom trace events.
    */
   private final JdbcEvent jdbcEvent;

   /*
    * Категория tracing layer.
    */
   private final JdbcTraceType type;

   /*
    * Произвольный текст trace-события.
    *
    * Для JDBC событий обычно null:
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
    * Дополнительные trace-level properties.
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
      super(requireSource(source));

      if( type == null )
         throw new IllegalArgumentException(
                 "type is null"
         );

      this.jdbcEvent = jdbcEvent;
      this.type      = type;
      this.text      = text;

      timestampNanos =
              jdbcEvent != null
                      ? jdbcEvent.timestampNanos()
                      : System.nanoTime();

      this.properties =
              snapshot(properties);
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


   /** */
   public JdbcTraceType type()
   {
      return type;
   }


   /** */
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
    * Получить дополнительное trace property.
    */
   @SuppressWarnings("unchecked")
   public <T> T property(
           String name
   )
   {
      if( name == null )
         return null;

      return (T) properties.get(name);
   }


   /**
    * Wrap low-level JDBC event.
    *
    * JdbcEvent остаётся единственным
    * источником JDBC-specific данных.
    */
   public static JdbcTraceEvent jdbc(
           JdbcEvent event
   )
   {
      if( event == null )
         throw new IllegalArgumentException(
                 "event is null"
         );

      return new JdbcTraceEvent(
              event.getSource(),
              event,
              JdbcTraceType.JDBC,
              null,
              null
      );
   }


   /**
    * Wrap low-level JDBC event
    * с дополнительными trace properties.
    */
   public static JdbcTraceEvent jdbc(
           JdbcEvent event,
           Map<String, Object> properties
   )
   {
      if( event == null )
         throw new IllegalArgumentException(
                 "event is null"
         );

      return new JdbcTraceEvent(
              event.getSource(),
              event,
              JdbcTraceType.JDBC,
              null,
              properties
      );
   }


   /**
    * Самостоятельное trace-событие,
    * не имеющее JdbcEvent origin.
    */
   public static JdbcTraceEvent custom(
           Object source,
           JdbcTraceType type,
           String text
   )
   {
      return new JdbcTraceEvent(
              source,
              null,
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


   /** */
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


   /** */
   private static Object requireSource(
           Object source
   )
   {
      if( source == null )
         throw new IllegalArgumentException(
                 "source is null"
         );

      return source;
   }
}