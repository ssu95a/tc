package ru.inversion.tc.jdbc.event;

/** */
public final class JdbcResultSetEvent extends JdbcEvent
{
   private final long resultSetId;
   private final long statementId;

   private final int openResultSetCount;


   private JdbcResultSetEvent(
           Object source,
           EventType type,
           long resultSetId,
           long statementId,
           int openResultSetCount
   )
   {
      super(source, type, EventPhase.ON);

      if( resultSetId <= 0 )
         throw new IllegalArgumentException("resultSetId <= 0");

      if( statementId <= 0 )
         throw new IllegalArgumentException("statementId <= 0");

      if( openResultSetCount < 0 )
         throw new IllegalArgumentException("openResultSetCount < 0");

      this.resultSetId       = resultSetId;
      this.statementId       = statementId;
      this.openResultSetCount = openResultSetCount;
   }


   public long getResultSetId()
   {
      return resultSetId;
   }


   public long getStatementId()
   {
      return statementId;
   }


   public int getOpenResultSetCount()
   {
      return openResultSetCount;
   }


   /** */
   public static JdbcResultSetEvent open( Object source, long resultSetId, long statementId, int openResultSetCount )
   {
      return new JdbcResultSetEvent( source, EventType.RESULT_SET_OPEN, resultSetId, statementId, openResultSetCount );
   }


   /** */
   public static JdbcResultSetEvent close(
           Object source,
           long resultSetId,
           long statementId,
           int openResultSetCount
   )
   {
      return new JdbcResultSetEvent(
              source,
              EventType.RESULT_SET_CLOSE,
              resultSetId,
              statementId,
              openResultSetCount
      );
   }
}