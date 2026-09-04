package ru.inversion.tc.jdbc.event;

import ru.inversion.tc.jdbc.internal.JdbcObjectId;

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


   /** */
   private JdbcStatementEvent(
           Object source,

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
      super(
              source,
              statementId,
              type,
              phase,
              throwable
      );

      if( !JdbcObjectId.isStatement(statementId) )
         throw new IllegalArgumentException(
                 "Invalid statementId: "
                         + JdbcObjectId.toString(statementId)
         );

      this.methodName    = methodName;
      this.sql           = sql;
      this.inParameters  = snapshot(inParameters);
      this.outParameters = snapshot(outParameters);
      this.durationNanos = durationNanos;
   }


   public long statementId()
   {
      return objectId();
   }


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


   /** */
   public static JdbcStatementEvent open(
           Object source,
           long statementId,
           String sql
   )
   {
      return new JdbcStatementEvent(
              source,
              statementId,

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


   /** */
   public static JdbcStatementEvent beforeExecute(
           Object source,
           long statementId,
           String methodName,
           String sql,
           Map<Integer, Object> inParameters
   )
   {
      return new JdbcStatementEvent(
              source,
              statementId,

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


   /** */
   public static JdbcStatementEvent afterExecute(
           Object source,
           long statementId,
           String methodName,
           String sql,
           Map<Integer, Object> inParameters,
           Map<Integer, Object> outParameters,
           long durationNanos
   )
   {
      return new JdbcStatementEvent(
              source,
              statementId,

              EventType.STATEMENT_EXECUTE,
              EventPhase.AFTER,

              methodName,
              sql,

              inParameters,
              outParameters,

              durationNanos,
              null
      );
   }


   /** */
   public static JdbcStatementEvent executeError(
           Object source,
           long statementId,
           String methodName,
           String sql,
           Map<Integer, Object> inParameters,
           long durationNanos,
           Throwable throwable
   )
   {
      return new JdbcStatementEvent(
              source,
              statementId,

              EventType.STATEMENT_EXECUTE,
              EventPhase.ERROR,

              methodName,
              sql,

              inParameters,
              null,

              durationNanos,
              throwable
      );
   }


   /** */
   public static JdbcStatementEvent close(
           Object source,
           long statementId,
           String sql
   )
   {
      return new JdbcStatementEvent(
              source,
              statementId,

              EventType.STATEMENT_CLOSE,
              EventPhase.ON,

              null,
              sql,

              null,
              null,

              0L,
              null
      );
   }


   /** */
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