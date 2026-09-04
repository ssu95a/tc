package ru.inversion.tc.jdbc.event;

import ru.inversion.tc.jdbc.internal.JdbcObjectId;

/** */
public final class JdbcResultSetEvent extends JdbcEvent
{
   private final int openResultSetCount;

   /** */
   private JdbcResultSetEvent(
           Object source,
           long resultSetId,
           EventType type,
           int openResultSetCount
   )
   {
      super(
              source,
              resultSetId,
              type,
              EventPhase.ON
      );

      if( !JdbcObjectId.isResultSet(resultSetId) )
         throw new IllegalArgumentException(
                 "Invalid resultSetId: "
                         + JdbcObjectId.toString(resultSetId)
         );

      if( openResultSetCount < 0 )
         throw new IllegalArgumentException(
                 "openResultSetCount < 0"
         );

      this.openResultSetCount =
              openResultSetCount;
   }


   public long resultSetId()
   {
      return objectId();
   }


   /*
    * Поле не храним.
    * Родитель содержится внутри resultSetId.
    */
   public long statementId()
   {
      return JdbcObjectId.statementId(
              resultSetId()
      );
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
              resultSetId,
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
              resultSetId,
              EventType.RESULT_SET_CLOSE,
              openResultSetCount
      );
   }
}