package ru.inversion.tc.jdbc.trace;

import ru.inversion.tc.jdbc.event.JdbcEvent;
import ru.inversion.utils.Checks;

import java.io.Writer;
import java.util.Collections;
import java.util.EventObject;
import java.util.LinkedHashMap;
import java.util.Map;


/**
 * <h5>Событие tracing layer.</h5>
 * Может:
 * <ol>
 * <li>Оборачивать low-level JdbcEvent;
 * <li>Быть самостоятельным trace-событием, не связанным с JDBC объектом.
 * </ol>
 * Для JDBC-origin события данные JdbcEvent не копируются
 */
public final class JdbcTraceEvent extends EventObject
{
   /*
    * Исходное JDBC событие, null для custom trace events.
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
   private JdbcTraceEvent (
      Object source,
      JdbcEvent jdbcEvent,
      JdbcTraceType type,
      String text,
      Map<String, Object> properties
   )
   {
      super( source );

      Checks.Require.object( type,"type" );

      this.jdbcEvent = jdbcEvent;
      this.type      = type;
      this.text      = text;

      this.timestampNanos = jdbcEvent != null ? jdbcEvent.timestampNanos() : System.nanoTime();

      this.properties= snapshot(properties);
   }


   /**
    * Исходное JDBC событие.
    * <p>
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
   public <T> T property( String name )
   {
      if( name == null )
         return null;
      return (T) properties.get(name);
   }


   /**
    * Wrap low-level JDBC event.
    */
   public static JdbcTraceEvent jdbc( JdbcEvent event )
   {
      return jdbc(event, null);
   }


   /**
    * Wrap low-level JDBC event, properties ver
    */
   public static JdbcTraceEvent jdbc( JdbcEvent event, Map<String, Object> properties )
   {
      Checks.Require.object( event,"event" );
      return new JdbcTraceEvent( event.getSource(), event, JdbcTraceType.JDBC, null, properties );
   }


   /**
    * Самостоятельное trace-событие, не имеющее JdbcEvent origin.
    */
   public static JdbcTraceEvent custom( Object source, JdbcTraceType type, String text )
   {
      return custom( source, type, text, null);
   }


   /**
    * Самостоятельное trace-событие с properties.
    */
   public static JdbcTraceEvent custom( Object source, JdbcTraceType type, String text, Map<String, Object> properties )
   {
      return new JdbcTraceEvent( source, null, type, text, properties );
   }


   /** Копия параметров */
   private static Map<String, Object> snapshot( Map<String, Object> source )
   {
      if( source == null || source.isEmpty() )
          return Collections.emptyMap();

      return Collections.unmodifiableMap( new LinkedHashMap<>(source) );
   }

   /** Вывод в оут */
   public void print( Writer writer )
   {
      JdbcTraceEventWriter.write( this, writer );
   }
}