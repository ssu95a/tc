package ru.inversion.tc.jdbc.internal.trace;

/**
 * Доступ к server-side DBMS_OUTPUT.
 */
public interface JdbcDbmsOutput extends AutoCloseable
{
   /** Включить накопление server output. */
   void enable();

   /** Выключить накопление server output. */
   void disable();

   /** Текущее состояние, известное клиенту. */
   boolean isEnabled();

   /**
    * Вычитать весь накопленный output.
    */
   String read();

   @Override
   default void close()
   {
      disable();
   }
}