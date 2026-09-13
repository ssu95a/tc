package ru.inversion.tc.jdbc.event;


/** Событие связанное с ResultSet-Курсором */
public final class JdbcResultSetEvent extends JdbcEvent
{
   private final int openResultSetCount;

   /** */
   private JdbcResultSetEvent( Object source, EventType type, int openResultSetCount )
   {
      super( source, type, EventPhase.ON );

      if( openResultSetCount < 0 )
          throw new IllegalArgumentException( "openResultSetCount < 0" );

      this.openResultSetCount = openResultSetCount;
   }


   /** Кол-во открытых курсоров в данный момент, в рамках connection */
   public int openResultSetCount()
   {
      return openResultSetCount;
   }


   /** Создание события открытия курсора */
   public static JdbcResultSetEvent open( Object source, int openResultSetCount )
   {
      return new JdbcResultSetEvent( source, EventType.RESULT_SET_OPEN, openResultSetCount );
   }


   /** Создание события закрытия курсора */
   public static JdbcResultSetEvent close( Object source, int openResultSetCount )
   {
      return new JdbcResultSetEvent( source, EventType.RESULT_SET_CLOSE, openResultSetCount );
   }
}