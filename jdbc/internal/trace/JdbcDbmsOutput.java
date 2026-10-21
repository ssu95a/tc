package ru.inversion.tc.jdbc.internal.trace;

import java.util.function.Consumer;

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
    * Вычитать весь накопленный output, отдавая его порциями.
    */
   void read( Consumer<String> consumer );

   /** */
   @Override
   default void close()
   {
      disable();
   }
}
