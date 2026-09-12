package ru.inversion.tc.jdbc.trace;

import ru.inversion.tc.jdbc.event.EventType;
import ru.inversion.tc.jdbc.event.JdbcEvent;
import ru.inversion.tc.jdbc.event.JdbcStatementEvent;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.lang.reflect.Array;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.TimeUnit;


/**
 * Текстовое представление JdbcTraceEvent.
 *
 * Класс отвечает только за форматирование.
 * Куда направляется вывод — Writer, log, System.out и т.п. —
 * решает вызывающий код.
 */
public final class JdbcTraceEventWriter
{
   private JdbcTraceEventWriter()
   { }


   /** */
   public static void write( JdbcTraceEvent event, Writer writer )
   {
      if( event == null )
         return;

      if( writer == null )
          throw new IllegalArgumentException( "writer is null" );

      try
      {
         if( event.hasJdbcEvent() )
            writeJdbcEvent(event, writer);
         else
            writeCustomEvent(event, writer);

         writer.flush();
      }
      catch( IOException ex )
      {
         throw new UncheckedIOException(ex);
      }
   }


   /** */
   private static void writeJdbcEvent(
           JdbcTraceEvent traceEvent,
           Writer writer
   )
           throws IOException
   {
      JdbcEvent jdbcEvent =
              traceEvent.jdbcEvent();

      if( jdbcEvent instanceof JdbcStatementEvent )
      {
         writeStatementEvent(
                 traceEvent,
                 (JdbcStatementEvent) jdbcEvent,
                 writer
         );

         return;
      }

      writeGenericJdbcEvent(
              traceEvent,
              jdbcEvent,
              writer
      );
   }


   /** */
   private static void writeStatementEvent(
           JdbcTraceEvent traceEvent,
           JdbcStatementEvent event,
           Writer writer
   )
           throws IOException
   {
      if( event.type() != EventType.STATEMENT_EXECUTE )
      {
         writeGenericJdbcEvent(
                 traceEvent,
                 event,
                 writer
         );

         return;
      }

      switch( event.phase() )
      {
         case BEFORE:
            writeBeforeExecute(
                    traceEvent,
                    event,
                    writer
            );
            break;

         case AFTER:
            writeAfterExecute(
                    traceEvent,
                    event,
                    writer
            );
            break;

         case ERROR:
            writeExecuteError(
                    traceEvent,
                    event,
                    writer
            );
            break;

         default:
            writeGenericJdbcEvent(
                    traceEvent,
                    event,
                    writer
            );
            break;
      }
   }


   /** */
   private static void writeBeforeExecute(
           JdbcTraceEvent traceEvent,
           JdbcStatementEvent event,
           Writer writer
   )
           throws IOException
   {
      writer.append("SQL");

      writeSession(
              traceEvent,
              writer
      );

      writeMethod(
              event,
              writer
      );

      writer.append('\n');

      if( event.sqlStatement() != null )
      {
         writer.append(
                 event.sqlStatement()
         );

         writer.append('\n');
      }

      writeParameters(
              "IN",
              event.inParameters(),
              writer
      );
   }


   /** */
   private static void writeAfterExecute(
           JdbcTraceEvent traceEvent,
           JdbcStatementEvent event,
           Writer writer
   )
           throws IOException
   {
      writer.append("RESULT");

      writeSession(
              traceEvent,
              writer
      );

      writeMethod(
              event,
              writer
      );

      writer.append('\n');

      writer.append("time: ");
      writer.append(
              formatDuration(
                      event.durationNanos()
              )
      );
      writer.append('\n');

      writeParameters(
              "OUT",
              event.outParameters(),
              writer
      );
   }


   /** */
   private static void writeExecuteError(
           JdbcTraceEvent traceEvent,
           JdbcStatementEvent event,
           Writer writer
   )
           throws IOException
   {
      writer.append("ERROR");

      writeSession(
              traceEvent,
              writer
      );

      writeMethod(
              event,
              writer
      );

      writer.append('\n');

      if( event.sqlStatement() != null )
      {
         writer.append(
                 event.sqlStatement()
         );

         writer.append('\n');
      }

      writeParameters(
              "IN",
              event.inParameters(),
              writer
      );

      if( event.durationNanos() > 0L )
      {
         writer.append("time: ");
         writer.append(
                 formatDuration(
                         event.durationNanos()
                 )
         );
         writer.append('\n');
      }

      writeThrowable(
              event.throwable(),
              writer
      );
   }


   /** */
   private static void writeGenericJdbcEvent(
           JdbcTraceEvent traceEvent,
           JdbcEvent event,
           Writer writer
   )
           throws IOException
   {
      writer.append("JDBC: ");
      writer.append(
              event.type().name()
      );

      writer.append('/');

      writer.append(
              event.phase().name()
      );

      writeSession(
              traceEvent,
              writer
      );

      writer.append('\n');

      writeProperties(
              traceEvent.properties(),
              writer
      );

      writeThrowable(
              event.throwable(),
              writer
      );
   }


   /** */
   private static void writeCustomEvent(
           JdbcTraceEvent event,
           Writer writer
   )
           throws IOException
   {
      switch( event.type() )
      {
         case DBMS_OUTPUT:
            writeTextEvent(
                    "DBMS_OUTPUT",
                    event,
                    writer
            );
            break;

         case NOTICE:
            writeTextEvent(
                    "NOTICE",
                    event,
                    writer
            );
            break;

         case WARNING:
            writeTextEvent(
                    "WARNING",
                    event,
                    writer
            );
            break;

         case INFO:
            writeTextEvent(
                    "INFO",
                    event,
                    writer
            );
            break;

         case TECH:
            writeTextEvent(
                    "TECH",
                    event,
                    writer
            );
            break;

         case JDBC:
            /*
             * JDBC без JdbcEvent — некорректное состояние,
             * но writer не должен падать из-за trace data.
             */
            writeTextEvent(
                    "JDBC",
                    event,
                    writer
            );
            break;

         default:
            writeTextEvent(
                    event.type().name(),
                    event,
                    writer
            );
            break;
      }
   }


   /** */
   private static void writeTextEvent(
           String title,
           JdbcTraceEvent event,
           Writer writer
   )
           throws IOException
   {
      writer.append(title);

      writeSession(
              event,
              writer
      );

      writer.append(':');

      if( event.text() != null
              && !event.text().isEmpty() )
      {
         writer.append(' ');
         writer.append(
                 event.text()
         );
      }

      writer.append('\n');

      writeProperties(
              event.properties(),
              writer
      );
   }


   /** */
   private static void writeSession(
           JdbcTraceEvent event,
           Writer writer
   )
           throws IOException
   {
      Object sessionId =
              event.property("sessionId");

      if( sessionId == null )
         return;

      writer.append(" /session ");
      writer.append(
              String.valueOf(sessionId)
      );
      writer.append('/');
   }


   /** */
   private static void writeMethod(
           JdbcStatementEvent event,
           Writer writer
   )
           throws IOException
   {
      if( event.methodName() == null
              || event.methodName().isEmpty() )
      {
         return;
      }

      writer.append(" [");
      writer.append(
              event.methodName()
      );
      writer.append(']');
   }


   /** */
   private static void writeParameters(
           String title,
           Map<Integer, Object> parameters,
           Writer writer
   )
           throws IOException
   {
      if( parameters == null
              || parameters.isEmpty() )
      {
         return;
      }

      writer.append(title);
      writer.append(':');
      writer.append('\n');

      for( Map.Entry<Integer, Object> entry
              : parameters.entrySet() )
      {
         writeParameter(
                 entry.getKey(),
                 entry.getValue(),
                 writer
         );
      }
   }


   /** */
   private static void writeParameter(
           Object index,
           Object value,
           Writer writer
   )
           throws IOException
   {
      writer.append('\t');

      writer.append(
              String.valueOf(index)
      );

      writer.append(": ");

      writeValue(
              value,
              writer
      );

      writer.append('\n');
   }


   /** */
   private static void writeProperties(
           Map<String, Object> properties,
           Writer writer
   )
           throws IOException
   {
      if( properties == null
              || properties.isEmpty() )
      {
         return;
      }

      for( Map.Entry<String, Object> entry
              : properties.entrySet() )
      {
         /*
          * sessionId уже выводится в заголовке.
          */
         if( "sessionId".equals(
                 entry.getKey()
         ) )
         {
            continue;
         }

         writer.append('\t');
         writer.append(
                 entry.getKey()
         );
         writer.append(": ");

         writeValue(
                 entry.getValue(),
                 writer
         );

         writer.append('\n');
      }
   }


   /** */
   private static void writeValue(
           Object value,
           Writer writer
   )
           throws IOException
   {
      if( value == null )
      {
         writer.append("<null>");
         return;
      }

      if( value instanceof java.sql.Array )
      {
         writeSqlArray(
                 (java.sql.Array) value,
                 writer
         );

         return;
      }

      if( value.getClass().isArray() )
      {
         writeJavaArray(
                 value,
                 writer
         );

         return;
      }

      if( value instanceof String )
      {
         writer.append('"');
         writer.append(
                 (String) value
         );
         writer.append('"');
      }
      else
      {
         writer.append(
                 String.valueOf(value)
         );
      }

      writer.append(" (");
      writer.append(
              value.getClass().getSimpleName()
      );
      writer.append(')');
   }


   /** */
   private static void writeSqlArray(
           java.sql.Array array,
           Writer writer
   )
           throws IOException
   {
      try
      {
         writer.append("ARRAY");

         String baseTypeName =
                 array.getBaseTypeName();

         if( baseTypeName != null )
         {
            writer.append(" (baseType=");
            writer.append(baseTypeName);
            writer.append(')');
         }

         Object values =
                 array.getArray();

         writer.append(": ");

         writeJavaArray(
                 values,
                 writer
         );
      }
      catch( SQLException ex )
      {
         writer.append(
                 "<error reading SQL Array: "
         );

         writer.append(
                 String.valueOf(
                         ex.getLocalizedMessage()
                 )
         );

         writer.append('>');
      }
   }


   /** */
   private static void writeJavaArray(
           Object array,
           Writer writer
   )
           throws IOException
   {
      if( array == null )
      {
         writer.append("<null>");
         return;
      }

      if( !array.getClass().isArray() )
      {
         writeValue(
                 array,
                 writer
         );

         return;
      }

      int length =
              Array.getLength(array);

      writer.append('[');

      for( int i = 0; i < length; i++ )
      {
         if( i > 0 )
            writer.append(", ");

         writer.append(
                 String.valueOf(i)
         );

         writer.append(": ");

         Object value =
                 Array.get(array, i);

         if( value == null )
            writer.append("<null>");
         else if( value.getClass().isArray() )
            writeJavaArray(value, writer);
         else if( value instanceof String )
         {
            writer.append('"');
            writer.append((String) value);
            writer.append('"');
         }
         else
         {
            writer.append(
                    String.valueOf(value)
            );
         }
      }

      writer.append(']');
   }


   /** */
   private static void writeThrowable(
           Throwable throwable,
           Writer writer
   )
   {
      if( throwable == null )
         return;

      PrintWriter printWriter =
              new PrintWriter(writer);

      throwable.printStackTrace(
              printWriter
      );

      printWriter.flush();
   }


   /** */
   private static String formatDuration(
           long durationNanos
   )
   {
      long millis =
              TimeUnit.NANOSECONDS.toMillis(
                      durationNanos
              );

      long hours =
              TimeUnit.MILLISECONDS.toHours(
                      millis
              );

      long minutes =
              TimeUnit.MILLISECONDS.toMinutes(millis)
                      - TimeUnit.HOURS.toMinutes(hours);

      long seconds =
              TimeUnit.MILLISECONDS.toSeconds(millis)
                      - TimeUnit.MINUTES.toSeconds(
                      TimeUnit.MILLISECONDS.toMinutes(millis)
              );

      long milliseconds =
              millis
                      - TimeUnit.SECONDS.toMillis(
                      TimeUnit.MILLISECONDS.toSeconds(millis)
              );

      return String.format(
              "%02d.%02d.%02d:%03d",
              hours,
              minutes,
              seconds,
              milliseconds
      );
   }
}