package ru.inversion.tc.jdbc.event;


/** */
public final class JdbcResultSetEvent extends JdbcEvent
{
   private final int openResultSetCount;

   /** */
   private JdbcResultSetEvent(
           Object source,
           EventType type,
           int openResultSetCount
   )
   {
      super(
              source,
              type,
              EventPhase.ON
      );


      if( openResultSetCount < 0 )
         throw new IllegalArgumentException(
                 "openResultSetCount < 0"
         );

      this.openResultSetCount =
              openResultSetCount;
   }




   public int openResultSetCount()
   {
      return openResultSetCount;
   }


   /** */
   public static JdbcResultSetEvent open(
           Object source,
           long resultSetId,
           int openResultSetCount
   )
   {
      return new JdbcResultSetEvent(
              source,
              EventType.RESULT_SET_OPEN,
              openResultSetCount
      );
   }


   /** */
   public static JdbcResultSetEvent close(
           Object source,
           long resultSetId,
           int openResultSetCount
   )
   {
      return new JdbcResultSetEvent(
              source,
              EventType.RESULT_SET_CLOSE,
              openResultSetCount
      );
   }
}