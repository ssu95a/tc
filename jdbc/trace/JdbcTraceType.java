package ru.inversion.tc.jdbc.trace;


/**
 * Пользовательская категория trace-события.
 * <p>
 * EventType описывает низкоуровневое событие JDBC core.
 * JdbcTraceType описывает смысл события для пользователя.
 */
public enum JdbcTraceType
{
   /** Выполняемый SQL и входные параметры. */
   SQL,

   /**
    * Результат выполнения Statement:
    * duration, OUT parameters и другие execution results.
    */
   RESULT,

   /** Ошибка JDBC или прикладного trace-события. */
   ERROR,

   /** Информационное событие. */
   INFO,

   /** Предупреждение. */
   WARNING,

   /** Сообщение СУБД / server notice. */
   NOTICE,

   /** DBMS_OUTPUT или его аналог. */
   DBMS_OUTPUT,

   /**
    * Низкоуровневая JDBC-телеметрия:
    * <p>
    * connection / statement / result-set lifecycle,
    * transaction, savepoint и прочие внутренние события.
    */
   TECH,

   /**
    * Старый общий тип JDBC trace.
    * <p>
    * Оставлен временно для source compatibility.
    * Новые JDBC-origin события его не используют.
    */
   @Deprecated
   JDBC
}
