package ru.inversion.tc.jdbc.event;

/** */
public final class JdbcResultSetEvent extends JdbcEvent
{
   private final long resultSetId;
   private final long statementId;

   private final int openResultSetCount;

   private JdbcResultSetEvent (
        Object source,

        long connectionId,
        long statementId,
        long resultSetId,

        EventType type,

        int openResultSetCount
   )
   {
      super( source, connectionId, type, EventPhase.ON );

      if( statementId <= 0 )
          throw new IllegalArgumentException("statementId <= 0");

      if( resultSetId <= 0 )
          throw new IllegalArgumentException("resultSetId <= 0");

      if( openResultSetCount < 0 )
         throw new IllegalArgumentException("openResultSetCount < 0");

      this.resultSetId = resultSetId;
      this.statementId = statementId;

      this.openResultSetCount
                       = openResultSetCount;
   }

   public long resultSetId()
   {
      return resultSetId;
   }

   public long statementId()
   {
      return statementId;
   }

   public int openResultSetCount()
   {
      return openResultSetCount;
   }

   /** */
   public static JdbcResultSetEvent open( Object source, long connectionId, long statementId, long resultSetId, int openResultSetCount )
   {
      return new JdbcResultSetEvent(
         source,
         connectionId, statementId, resultSetId,
         EventType.RESULT_SET_OPEN,
         openResultSetCount
      );
   }


   /** */
   public static JdbcResultSetEvent close(
           Object source,
           long connectionId,
           long resultSetId,
           long statementId,
           int openResultSetCount
   )
   {
      return new JdbcResultSetEvent(
         source,
         connectionId, statementId, resultSetId,
         EventType.RESULT_SET_CLOSE,
         openResultSetCount
      );
   }
}