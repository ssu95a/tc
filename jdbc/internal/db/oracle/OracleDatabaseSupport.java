package ru.inversion.tc.jdbc.internal.db.oracle;

import ru.inversion.tc.jdbc.internal.db.JdbcDatabaseSupport;
import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

public final class OracleDatabaseSupport implements JdbcDatabaseSupport
{
   private final JdbcTransactionPolicy transactionPolicy = new OracleTransactionPolicy();

   @Override
   public JdbcTransactionPolicy transactionPolicy()
   {
      return transactionPolicy;
   }
}