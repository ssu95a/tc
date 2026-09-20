package ru.inversion.tc.jdbc.event;

import ru.inversion.utils.Checks;

/** <h5>Событие связанное с ResultSet-Курсором</h5> */
public final class JdbcResultSetEvent extends JdbcEvent
{
   // Кол-во открытых на данный момент курсоров
   private final int openResultSetCount;


   /** */
   private JdbcResultSetEvent( Object source, EventType type, int openResultSetCount,  boolean traceIgnored )
   {
      super( source, type, EventPhase.ON, traceIgnored );

      if( openResultSetCount < 0 )
         throw new IllegalArgumentException(
                 "openResultSetCount < 0"
         );
      this.openResultSetCount = openResultSetCount;
   }


   /** Кол-во открытых курсоров в данный момент, в рамках connection */
   public int openResultSetCount()
   {
      return openResultSetCount;
   }


   /** Создание события открытия курсора */
   public static JdbcResultSetEvent open( Object source, int openResultSetCount, boolean traceIgnored )
   {
      return new JdbcResultSetEvent( source, EventType.RESULT_SET_OPEN, openResultSetCount, traceIgnored );
   }


   /** Создание события закрытия курсора */
   public static JdbcResultSetEvent close( Object source, int openResultSetCount, boolean traceIgnored )
   {
      return new JdbcResultSetEvent( source, EventType.RESULT_SET_CLOSE, openResultSetCount, traceIgnored );
   }
}