package ru.inversion.tc.jdbc.event;

/**
 * Текстовое сообщение от JDBC/server-side infrastructure.
 */
public final class JdbcMessageEvent extends JdbcEvent
{
   private final String text;

   /** */
   private JdbcMessageEvent(
           Object source,
           EventType type,
           String text
   )
   {
      super(
              source,
              type,
              EventPhase.ON
      );

      this.text = text;
   }

   /** */
   public String text()
   {
      return text;
   }

   /** */
   public static JdbcMessageEvent notice(
           Object source,
           String text
   )
   {
      return new JdbcMessageEvent(
              source,
              EventType.NOTICE,
              text
      );
   }

   /** */
   public static JdbcMessageEvent dbmsOutput(
           Object source,
           String text
   )
   {
      return new JdbcMessageEvent(
              source,
              EventType.DBMS_OUTPUT,
              text
      );
   }
}