package ru.inversion.tc.jdbc.trace;


/**
 * Тип trace-события.
 * <p>
 * Не дублирует Jdbc EventType:
 * <p>
 * EventType описывает событие JDBC core,
 * JdbcTraceType — категорию события tracing layer.
 */
public enum JdbcTraceType
{
   /**
    * Событие пришло из JDBC event layer.
    *
    * Детали находятся в JdbcTraceEvent.jdbcEvent().
    */
   JDBC,

   /** Информационное trace-событие. */
   INFO,

   /** Техническое/диагностическое событие. */
   TECH,

   /** Предупреждение. */
   WARNING,

   /** Сообщение СУБД / server notice. */
   NOTICE,

   /** DBMS_OUTPUT или его аналог. */
   DBMS_OUTPUT
}