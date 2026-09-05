package ru.inversion.tc.jdbc.internal.db;

import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

public final class DefaultDatabaseSupport
        implements JdbcDatabaseSupport
{
   private final JdbcTransactionPolicy transactionPolicy =
           new DefaultTransactionPolicy();

   @Override
   public JdbcTransactionPolicy transactionPolicy()
   {
      return transactionPolicy;
   }
}