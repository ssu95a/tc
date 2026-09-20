package ru.inversion.tc.jdbc.internal.trace;

import ru.inversion.tc.jdbc.event.*;
import ru.inversion.utils.Checks;
import ru.inversion.utils.S;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
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
   private final JdbcDbmsOutput dbmsOutput;
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

   /** */
   private final Predicate<EventType> enabled;

   /** */
   public JdbcServerOutputTracer(
      JdbcEventBus eventBus,
      JdbcDbmsOutput dbmsOutput,
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
   public void onJdbcEvent( JdbcStatementEvent event )
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
    * Настройка server output должна происходить до выполнения пользовательского SQL.
    */
   private void beforeExecute( JdbcStatementEvent event )
   {
      syncRaiseNoticeState();
      syncDbmsOutputState();
   }


   /** */
   private void syncDbmsOutputState()
   {
      boolean enable = isEnabled(EventType.DBMS_OUTPUT);

      if( enable == dbmsOutput.isEnabled() )
          return;

      if( enable )
          dbmsOutput.enable();
      else
          dbmsOutput.disable();
   }

   /**
    * Выполняется как после успешного execute, так и после ERROR.
    */
   private void afterExecute( JdbcStatementEvent event )
   {
      if( raiseNoticeState != null )
          traceWarnings(event);

      traceDbmsOutput(event);
   }


   /**
    * PostgreSQL RAISE DEBUG / NOTICE, пришедшие через JDBC SQLWarning chain.
    */
   private void traceWarnings( JdbcStatementEvent event )
   {
      if( !isEnabled(EventType.NOTICE) )
          return;

      Object source = event.getSource();

      if( !(source instanceof Statement) )
         return;

      try
      {
         SQLWarning warning = ((Statement) source).getWarnings();

         StringBuilder text = null;

         while( warning != null )
         {
            String message = warning.getMessage();

            if( !S.isNullOrEmpty(message) )
            {
               if( text == null )
                   text = new StringBuilder(message);
               else
                   text.append('\n').append(message);
            }

            warning = warning.getNextWarning();
         }

         if( event.isTraceIgnored() )
            return;

         if( text != null )
             eventBus.fire( JdbcMessageEvent.notice( source, text.toString() ) );
      }
      catch( SQLException ignored ) {
         /* Diagnostics не должны влиять на application JDBC. */
      }
   }


   /**
    * Oracle DBMS_OUTPUT / PostgreSQL dbms_output extension.
    */
   private void traceDbmsOutput(
           JdbcStatementEvent event
   )
   {
      if( !isEnabled(EventType.DBMS_OUTPUT) )
         return;

      if( !dbmsOutput.isEnabled() )
         return;

      String text =
              dbmsOutput.read(); // читаем ВСЕГДА

      if( event.isTraceIgnored() )
         return;               // но наружу не отдаём

      if( S.isNullOrEmpty(text) )
         return;

      eventBus.fire(
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
      close(true);
   }


   /**
    * Connection уже физически закрыт/abort.
    *
    * Никаких SQL cleanup операций.
    */
   public synchronized void closedByConnection()
   {
      close(false);
   }


   private void close( boolean cleanupServerState )
   {
      if( closed )
         return;

      closed = true;

      eventBus.removeListener(
              JdbcStatementEvent.class,
              this
      );

      if( !cleanupServerState )
         return;

      /*
       * Observation cleanup не должен мешать
       * закрытию JDBC connection.
       */
      if( raiseNoticeState != null
              && raiseNoticeEnabled )
      {
         try
         {
            raiseNoticeState.accept(false);
         }
         catch( ThreadDeath | VirtualMachineError fatal )
         {
            throw fatal;
         }
         catch( Throwable ignored )
         {
         }
         finally
         {
            raiseNoticeEnabled = false;
         }
      }

      try
      {
         dbmsOutput.close();
      }
      catch( ThreadDeath | VirtualMachineError fatal )
      {
         throw fatal;
      }
      catch( Throwable ignored )
      {
      }
   }
}