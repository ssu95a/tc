package ru.inversion.tc.jdbc.internal.db;

import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

import java.sql.Connection;

public final class DefaultTransactionPolicy implements JdbcTransactionPolicy
{
   @Override
   public boolean canCommitIdleTransaction(Connection connection )
   {
      return false;
   }
}