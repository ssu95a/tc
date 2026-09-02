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

   private JdbcStatementEvent(
           Object source,
           EventType type,
           EventPhase phase,
           String methodName,
           String sql,
           Map<Integer, Object> inParameters,
           Map<Integer, Object> outParameters,
           long duration,
           Throwable throwable
   )
   {
      super(source, type, phase, throwable);

      this.methodName    = methodName;
      this.sql           = sql;
      this.inParameters  = snapshot(inParameters);
      this.outParameters = snapshot(outParameters);
      this.durationNanos = duration;
   }


   public String getMethodName()
   {
      return methodName;
   }


   public String getSql()
   {
      return sql;
   }


   public Map<Integer, Object> getInParameters()
   {
      return inParameters;
   }


   public Map<Integer, Object> getOutParameters()
   {
      return outParameters;
   }


   public long getDuration()
   {
      return durationNanos;
   }


   /*
    * Statement создан.
    *
    * sql == null для обычного createStatement().
    */
   public static JdbcStatementEvent open(
           Object source,
           String sql
   )
   {
      return new JdbcStatementEvent(
              source,
              EventType.STATEMENT_OPEN,
              EventPhase.ON,
              null,
              sql,
              null,
              null,
              0L,
              null
      );
   }


   /*
    * Начало execute*().
    */
   public static JdbcStatementEvent beforeExecute(
           Object source,
           String methodName,
           String sql,
           Map<Integer, Object> inParameters
   )
   {
      return new JdbcStatementEvent(
              source,
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
           String methodName,
           String sql,
           Map<Integer, Object> inParameters,
           Map<Integer, Object> outParameters,
           long duration
   )
   {
      return new JdbcStatementEvent(
              source,
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
           String methodName,
           String sql,
           Map<Integer, Object> inParameters,
           long duration,
           Throwable throwable
   )
   {
      return new JdbcStatementEvent(
              source,
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
           String sql
   )
   {
      return new JdbcStatementEvent(
              source,
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


   private static Map<Integer, Object> snapshot(
           Map<Integer, Object> source
   )
   {
      if( source == null || source.isEmpty() )
         return Collections.emptyMap();

      return Collections.unmodifiableMap(
              new TreeMap<>(source)
      );
   }
}