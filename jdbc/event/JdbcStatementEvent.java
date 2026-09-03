package ru.inversion.tc.jdbc.event;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** */
public final class JdbcStatementEvent extends JdbcEvent
{
   private final String methodName;
   private final String sql;

   private final Map<Integer, Object> inParameters;
   private final Map<Integer, Object> outParameters;

   private final long durationNanos;

   private final long statementId;

   private JdbcStatementEvent (
      Object source,

      long connectionId,
      long statementId,

      EventType type,
      EventPhase phase,

      String methodName,
      String sql,

      Map<Integer, Object> inParameters,
      Map<Integer, Object> outParameters,

      long durationNanos,

      Throwable throwable
   )
   {
      super(source, connectionId, type, phase, throwable);

      this.methodName    = methodName;
      this.sql           = sql;
      this.inParameters  = snapshot(inParameters);
      this.outParameters = snapshot(outParameters);
      this.durationNanos = durationNanos;
      this.statementId   = statementId;
   }

   public long statementId() { return statementId;}

   public String methodName()
   {
      return methodName;
   }

   public String sqlStatement()
   {
      return sql;
   }

   public Map<Integer, Object> inParameters()
   {
      return inParameters;
   }

   public Map<Integer, Object> outParameters()
   {
      return outParameters;
   }

   public long durationNanos()
   {
      return durationNanos;
   }

   /*
    * Statement создан.
    * <p>
    * sql == null для обычного createStatement().
    */
   public static JdbcStatementEvent open(
      Object source,
      long connectionId,
      long statementId,
      String sql
   )
   {
      return new JdbcStatementEvent( source, connectionId, statementId, EventType.STATEMENT_OPEN, EventPhase.ON, null, sql, null, null, 0L, null );
   }

   /*
    * Начало execute*().
    */
   public static JdbcStatementEvent beforeExecute (
      Object source,
        long connectionId,
        long statementId,
      String methodName,
      String sql,
      Map<Integer, Object> inParameters
   )
   {
      return new JdbcStatementEvent(
              source,
              connectionId, statementId,
              EventType.STATEMENT_EXECUTE,
              EventPhase.BEFORE,
              methodName,
              sql,
              inParameters,
              null,
              0L,
              null
      );
   }


   /*
    * Успешное завершение execute*().
    */
   public static JdbcStatementEvent afterExecute(
      Object source,
      long connectionId,
      long statementId,
      String methodName,
      String sql,
      Map<Integer, Object> inParameters,
      Map<Integer, Object> outParameters,
      long duration
   )
   {
      return new JdbcStatementEvent(
              source,
              connectionId, statementId,
              EventType.STATEMENT_EXECUTE,
              EventPhase.AFTER,
              methodName,
              sql,
              inParameters,
              outParameters,
              duration,
              null
      );
   }


   /*
    * Ошибка execute*().
    */
   public static JdbcStatementEvent executeError(
      Object source,
      long connectionId,
      long statementId,
      String methodName,
      String sql,
      Map<Integer, Object> inParameters,
      long duration,
      Throwable throwable
   )
   {
      return new JdbcStatementEvent(
           source,
           connectionId, statementId,
           EventType.STATEMENT_EXECUTE,
           EventPhase.ERROR,
           methodName,
           sql,
           inParameters,
           null,
           duration,
           throwable
      );
   }


   /*
    * Statement закрыт.
    */
   public static JdbcStatementEvent close(
      Object source,
      long connectionId,
      long statementId,
      String sql
   )
   {
      return new JdbcStatementEvent(
              source,
              connectionId, statementId,
              EventType.STATEMENT_CLOSE,
              EventPhase.ON,
              null,
              sql,
              null,
              null,
              0,
              null
      );
   }

   /** */
   private static Map<Integer, Object> snapshot( Map<Integer, Object> source )
   {
      if( source == null || source.isEmpty() )
          return Collections.emptyMap();

      return Collections.unmodifiableMap( new TreeMap<>(source) );
   }
}