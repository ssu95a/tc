package ru.inversion.tc.jdbc.internal.db.oracle;

import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

import java.sql.Connection;
import java.sql.SQLException;


public final class OracleTransactionPolicy implements JdbcTransactionPolicy
{
   @Override
   public boolean canFinishReadTransaction( Connection connection ) throws SQLException
   {
      /* Для Oracle никогда автоматически не commit.*/
      return false;
   }
}