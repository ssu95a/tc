package ru.inversion.tc.jdbc.event;

/**
 * <h5>Текстовое сообщение от JDBC/server-side infrastructure.</h5>
 * <p>
 *  Используется в логике работы с dbms_output, raise debug - notice
 */
public final class JdbcMessageEvent extends JdbcEvent
{
   // Текст сообщения
   private final String text;

   /** */
   private JdbcMessageEvent( Object source, EventType type, String text )
   {
      super( source, type, EventPhase.ON );
      this.text = text;
   }

   /** */
   public String text()
   {
      return text;
   }

   /** */
   public static JdbcMessageEvent notice( Object source, String text )
   {
      return new JdbcMessageEvent( source, EventType.NOTICE, text );
   }

   /** */
   public static JdbcMessageEvent dbmsOutput( Object source, String text )
   {
      return new JdbcMessageEvent( source, EventType.DBMS_OUTPUT, text );
   }
}