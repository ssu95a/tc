package ru.inversion.tc.jdbc.internal.trace;

import ru.inversion.tc.dbms_output.IDBMSOutput;
import ru.inversion.tc.jdbc.event.*;
import ru.inversion.utils.Checks;

import java.sql.CallableStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;


/**
 * Server-side DB output -> JDBC trace.
 *
 * DBMS_OUTPUT:
 *   Oracle / PostgreSQL через IDBMSOutput.
 *
 * NOTICE:
 *   PostgreSQL RAISE DEBUG / NOTICE через SQLWarning.
 *
 * JDBC/core specifics и raw Connection здесь отсутствуют.
 */
public final class JdbcServerOutputTracer implements JdbcEventListener<JdbcStatementEvent>, AutoCloseable
{
   private final JdbcEventBus eventBus;

   /*
    * Oracle DBMS_OUTPUT или PostgreSQL dbms_output extension.
    */
   private final IDBMSOutput dbmsOutput;

   /*
    * PostgreSQL-specific callback:
    *
    * true  -> включить server RAISE DEBUG/NOTICE
    * false -> выключить
    *
    * null для СУБД, где эта возможность отсутствует.
    */
   private final Consumer<Boolean> raiseNoticeState;

   /*
    * Фактическое состояние server-side RAISE output.
    */
   private boolean raiseNoticeEnabled;

   private boolean closed;

   private final Predicate<EventType> enabled;

   /** */
   public JdbcServerOutputTracer(
      JdbcEventBus eventBus,
      IDBMSOutput dbmsOutput,
      Predicate<EventType> enabled,
      Consumer<Boolean> raiseNoticeState
   )
   {
      this.eventBus   = Checks.Require.object( eventBus, "eventBus" );
      this.dbmsOutput = Checks.Require.object( dbmsOutput, "dbmsOutput" );
      this.enabled    = Checks.Require.object( enabled, "enabled" );
      this.raiseNoticeState = raiseNoticeState;

      eventBus.addListener( JdbcStatementEvent.class, this );
   }

   /** */
   private boolean isEnabled( EventType type )
   {
      return enabled != null && enabled.test(type);
   }

   /** */
   @Override
   public void onJdbcEvent(
           JdbcStatementEvent event
   )
   {
      if( closed || event == null )
         return;

      if( event.type() != EventType.STATEMENT_EXECUTE )
         return;

      switch( event.phase() )
      {
         case BEFORE:
            beforeExecute(event);
            break;

         case AFTER:
         case ERROR:
            afterExecute(event);
            break;

         default:
            break;
      }
   }


   /**
    * Настройка server output должна происходить
    * до выполнения пользовательского SQL.
    */
   private void beforeExecute( JdbcStatementEvent event )
   {
      /*
       * PostgreSQL RAISE DEBUG / NOTICE.
       * Состояние должно быть установлено до execute().
       */
      syncRaiseNoticeState();

      /*
       * DBMS_OUTPUT нужен только для CallableStatement.
       */
      if( !(event.getSource() instanceof CallableStatement) )
         return;

      if( !isEnabled(EventType.DBMS_OUTPUT) )
         return;

      if( !dbmsOutput.isEnable() )
         dbmsOutput.enable();
   }

   /**
    * Выполняется как после успешного execute,
    * так и после ERROR.
    */
   private void afterExecute( JdbcStatementEvent event )
   {
      /*
       * SQLWarning chain используется только там,
       * где есть raiseNoticeState, т.е. PostgreSQL.
       */
      if( raiseNoticeState != null )
         traceWarnings(event);

      /*
       * DBMS_OUTPUT читаем только после CallableStatement.
       */
      if( event.getSource() instanceof CallableStatement )
         traceDbmsOutput(event);
   }


   /**
    * PostgreSQL RAISE DEBUG / NOTICE,
    * пришедшие через JDBC SQLWarning chain.
    */
   private void traceWarnings(
           JdbcStatementEvent event
   )
   {
      if( !isEnabled(EventType.NOTICE) )
         return;

      Object source =
              event.getSource();

      if( !(source instanceof Statement) )
         return;

      try
      {
         SQLWarning warning =
                 ((Statement) source).getWarnings();

         StringBuilder text =
                 null;

         while( warning != null )
         {
            String message =
                    warning.getMessage();

            if( message != null
                    && !message.isEmpty() )
            {
               if( text == null )
                  text = new StringBuilder(message);
               else
                  text.append('\n').append(message);
            }

            warning =
                    warning.getNextWarning();
         }

         if( text != null )
         {
            eventBus.fireSafely(
                    JdbcMessageEvent.notice(
                            source,
                            text.toString()
                    )
            );
         }
      }
      catch( SQLException ignored )
      {
         /*
          * Diagnostics не должны влиять
          * на application JDBC.
          */
      }
   }


   /**
    * Oracle DBMS_OUTPUT /
    * PostgreSQL dbms_output extension.
    */
   private void traceDbmsOutput(
           JdbcStatementEvent event
   )
   {
      if( !isEnabled(EventType.DBMS_OUTPUT) )
         return;

      String text =
              dbmsOutput.get_lines();

      if( text == null || text.isEmpty() )
         return;

      eventBus.fireSafely(
              JdbcMessageEvent.dbmsOutput(
                      event.getSource(),
                      text
              )
      );
   }

   /**
    * Синхронизирует состояние server-side
    * RAISE output с настройками JdbcTracer.
    */
   private void syncRaiseNoticeState()
   {
      if( raiseNoticeState == null )
         return;

      boolean enable = isEnabled(EventType.NOTICE);

      if( enable == raiseNoticeEnabled )
         return;

      raiseNoticeState.accept(enable);

      /*
       * Меняем локальное состояние только
       * после успешного DB-specific callback.
       */
      raiseNoticeEnabled = enable;
   }


   /** */
   @Override
   public synchronized void close()
   {
      if( closed )
         return;

      closed = true;

      /*
       * Сначала перестаём получать JDBC events.
       */
      eventBus.removeListener(
              JdbcStatementEvent.class,
              this
      );

      Throwable failure =
              null;

      /*
       * Выключаем PostgreSQL RAISE output,
       * если включали его.
       */
      if( raiseNoticeState != null
              && raiseNoticeEnabled )
      {
         try
         {
            raiseNoticeState.accept(false);
         }
         catch( Throwable ex )
         {
            failure = ex;
         }
         finally
         {
            raiseNoticeEnabled = false;
         }
      }

      /*
       * disable DBMS_OUTPUT.
       *
       * IDBMSOutput внутри использует raw Connection,
       * переданный ему при создании.
       */
      try
      {
         dbmsOutput.close();
      }
      catch( Throwable ex )
      {
         if( failure == null )
            failure = ex;
         else
            failure.addSuppressed(ex);
      }

      if( failure != null )
      {
         if( failure instanceof ThreadDeath )
            throw (ThreadDeath) failure;

         if( failure instanceof VirtualMachineError )
            throw (VirtualMachineError) failure;

         if( failure instanceof RuntimeException )
            throw (RuntimeException) failure;

         throw new RuntimeException(failure);
      }
   }
}