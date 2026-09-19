package ru.inversion.tc.jdbc.event;

/** Поддерживаемые типы событий */
public enum EventType
{
   CONNECTION_OPEN      (Kind.JDBC),
   CONNECTION_CLOSE     (Kind.JDBC),

   STATEMENT_OPEN       (Kind.JDBC),
   STATEMENT_EXECUTE    (Kind.JDBC),
   STATEMENT_CLOSE      (Kind.JDBC),

   RESULT_SET_OPEN      (Kind.JDBC),
   RESULT_SET_CLOSE     (Kind.JDBC),

   TRANSACTION_COMMIT   (Kind.JDBC),
   TRANSACTION_ROLLBACK (Kind.JDBC),

   SAVEPOINT_SET        (Kind.JDBC),
   SAVEPOINT_RELEASE    (Kind.JDBC),
   SAVEPOINT_ROLLBACK   (Kind.JDBC),

   NOTICE               (Kind.SERVER_OUTPUT),
   DBMS_OUTPUT          (Kind.SERVER_OUTPUT);

   public enum Kind
   {
      JDBC,
      SERVER_OUTPUT
   }

   private final Kind kind;

   EventType( Kind kind )
   {
      this.kind = kind;
   }

   public Kind kind()
   {
      return kind;
   }

   public boolean isJdbc()
   {
      return kind == Kind.JDBC;
   }

   public boolean isServerOutput()
   {
      return kind == Kind.SERVER_OUTPUT;
   }
}