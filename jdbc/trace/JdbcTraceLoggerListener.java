package ru.inversion.tc.jdbc.trace;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringWriter;


public final class JdbcTraceLoggerListener implements JdbcTraceListener
{
   private static final JdbcTraceLoggerListener INSTANCE = new JdbcTraceLoggerListener();

   private static final Logger logger = LoggerFactory.getLogger("ru.inversion.sql");

   private JdbcTraceLoggerListener()
   { }

   public static JdbcTraceLoggerListener instance()
   {
      return INSTANCE;
   }

   @Override
   public void onJdbcTrace(JdbcTraceEvent event)
   {
      if( event == null )
          return;

      if( isError(event) )
      {
         if( logger.isErrorEnabled() )
             logger.error(eventToString(event));
      }
      else
      {
         if( logger.isDebugEnabled() )
            logger.debug(eventToString(event));
      }
   }


   private static boolean isError(JdbcTraceEvent event)
   {
      return event.hasJdbcEvent() && event.jdbcEvent().throwable() != null;
   }


   /** */
   private static String eventToString( JdbcTraceEvent event )
   {
      final StringWriter writer = new StringWriter();
      event.print(writer);
      return writer.toString();
   }
}