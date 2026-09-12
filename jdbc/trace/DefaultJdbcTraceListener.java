package ru.inversion.tc.jdbc.trace;

import ru.inversion.tc.jdbc.event.EventPhase;
import ru.inversion.tc.jdbc.event.JdbcEvent;
import ru.inversion.tc.jdbc.event.JdbcStatementEvent;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Map;


/**
 * Стандартный listener для текстового представления JdbcTraceEvent.
 *
 * Observation-only.
 *
 * Не выполняет никаких JDBC операций и не влияет
 * на lifecycle/transaction state.
 */
public class DefaultJdbcTraceListener implements JdbcTraceListener
{
   private final Writer writer;

   public DefaultJdbcTraceListener( Writer writer )
   {
      if( writer == null )
         throw new IllegalArgumentException( "writer is null" );

      this.writer = writer;
   }


   @Override
   public synchronized void onJdbcTrace( JdbcTraceEvent event )
   {
      if( event == null )
          return;

      try
      {
         if( event.hasJdbcEvent() )
            writeJdbcEvent(event);
         else
            writeCustomEvent(event);

         writer.flush();
      }
      catch( IOException ex )
      {
         throw new UncheckedIOException(ex);
      }
   }


   /**
    * JDBC-origin trace event.
    */
   protected void writeJdbcEvent(
           JdbcTraceEvent traceEvent
   )
           throws IOException
   {
      JdbcEvent event =
              traceEvent.jdbcEvent();

      if( event instanceof JdbcStatementEvent )
      {
         writeStatementEvent(
                 traceEvent,
                 (JdbcStatementEvent) event
         );

         return;
      }

      writeGenericJdbcEvent(
              traceEvent,
              event
      );
   }


   /**
    * Statement events имеют наиболее полезное
    * для trace содержимое: SQL, параметры, duration.
    */
   protected void writeStatementEvent(
           JdbcTraceEvent traceEvent,
           JdbcStatementEvent event
   )
           throws IOException
   {
      if( event.type()
              != ru.inversion.tc.jdbc.event.EventType.STATEMENT_EXECUTE )
      {
         writeGenericJdbcEvent(
                 traceEvent,
                 event
         );

         return;
      }

      switch( event.phase() )
      {
         case BEFORE:
            writeBeforeExecute(event);
            break;

         case AFTER:
            writeAfterExecute(event);
            break;

         case ERROR:
            writeExecuteError(event);
            break;

         default:
            writeGenericJdbcEvent(
                    traceEvent,
                    event
            );
            break;
      }
   }


   protected void writeBeforeExecute(
           JdbcStatementEvent event
   )
           throws IOException
   {
      writer.append("SQL");

      writeMethodName(event);

      writer.append('\n');

      if( event.sqlStatement() != null )
      {
         writer.append(event.sqlStatement());
         writer.append('\n');
      }

      writeParameters(
              "IN",
              event.inParameters()
      );
   }


   protected void writeAfterExecute(
           JdbcStatementEvent event
   )
           throws IOException
   {
      writer.append("RESULT");

      writeMethodName(event);

      writer.append('\n');

      writer.append("time: ");
      writer.append(
              formatDuration(event.durationNanos())
      );
      writer.append('\n');

      writeParameters(
              "OUT",
              event.outParameters()
      );
   }


   protected void writeExecuteError(
           JdbcStatementEvent event
   )
           throws IOException
   {
      writer.append("ERROR");

      writeMethodName(event);

      writer.append('\n');

      if( event.sqlStatement() != null )
      {
         writer.append(event.sqlStatement());
         writer.append('\n');
      }

      writeParameters(
              "IN",
              event.inParameters()
      );

      writeThrowable(
              event.throwable()
      );
   }


   /**
    * Остальные JdbcEvent пока выводятся универсально.
    *
    * Позже здесь можно отдельно обработать:
    *
    * RESULT_SET_OPEN/CLOSE,
    * TRANSACTION_*,
    * WARNING,
    * NOTICE,
    * DBMS_OUTPUT.
    */
   protected void writeGenericJdbcEvent(
           JdbcTraceEvent traceEvent,
           JdbcEvent event
   )
           throws IOException
   {
      writer.append("JDBC: ");
      writer.append(event.type().name());
      writer.append('/');
      writer.append(event.phase().name());
      writer.append('\n');

      writeThrowable(
              event.throwable()
      );
   }


   /**
    * Trace event без JdbcEvent origin.
    */
   protected void writeCustomEvent(
           JdbcTraceEvent event
   )
           throws IOException
   {
      writer.append(
              event.type().name()
      );
      writer.append(":");

      if( event.text() != null )
      {
         writer.append(' ');
         writer.append(event.text());
      }

      writer.append('\n');

      writeProperties(
              event.properties()
      );
   }


   protected void writeMethodName(
           JdbcStatementEvent event
   )
           throws IOException
   {
      if( event.methodName() == null )
         return;

      writer.append(" [");
      writer.append(event.methodName());
      writer.append(']');
   }


   protected void writeParameters(
           String title,
           Map<Integer, Object> parameters
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
         writer.append('\t');
         writer.append(
                 String.valueOf(entry.getKey())
         );
         writer.append(": ");

         writeValue(
                 entry.getValue()
         );

         writer.append('\n');
      }
   }


   protected void writeProperties(
           Map<String, Object> properties
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
         writer.append('\t');
         writer.append(entry.getKey());
         writer.append(": ");

         writeValue(
                 entry.getValue()
         );

         writer.append('\n');
      }
   }


   protected void writeValue(
           Object value
   )
           throws IOException
   {
      if( value == null )
      {
         writer.append("<null>");
         return;
      }

      if( value instanceof String )
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

      writer.append(" (");
      writer.append(
              value.getClass().getSimpleName()
      );
      writer.append(')');
   }


   protected void writeThrowable(
           Throwable throwable
   )
           throws IOException
   {
      if( throwable == null )
         return;

      StringWriter sw =
              new StringWriter();

      PrintWriter pw =
              new PrintWriter(sw);

      throwable.printStackTrace(pw);
      pw.flush();

      writer.append(
              sw.toString()
      );
   }


   protected String formatDuration(
           long durationNanos
   )
   {
      long millis =
              durationNanos / 1_000_000L;

      long hours =
              millis / 3_600_000L;

      millis %= 3_600_000L;

      long minutes =
              millis / 60_000L;

      millis %= 60_000L;

      long seconds =
              millis / 1_000L;

      millis %= 1_000L;

      return String.format(
              "%02d.%02d.%02d:%03d",
              hours,
              minutes,
              seconds,
              millis
      );
   }
}