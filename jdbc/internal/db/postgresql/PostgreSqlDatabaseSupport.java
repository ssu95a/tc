package ru.inversion.tc.jdbc.internal.db.postgresql;

import ru.inversion.tc.jdbc.internal.db.JdbcDatabaseSupport;
import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

public class PostgreSqlDatabaseSupport implements JdbcDatabaseSupport {
   @Override
   public JdbcTransactionPolicy transactionPolicy() {
      return new PostgreSqlTransactionPolicy();
   }
}
