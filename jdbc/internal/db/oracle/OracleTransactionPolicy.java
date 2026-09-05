package ru.inversion.tc.jdbc.internal.db.oracle;

import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

import java.sql.Connection;
import java.sql.SQLException;


public final class OracleTransactionPolicy
        implements JdbcTransactionPolicy
{
   @Override
   public boolean canFinishReadTransaction(
           Connection connection
   )
           throws SQLException
   {
      /*
       * Пока Oracle-specific механизм безопасного
       * определения read-only transaction не реализован.
       *
       * Никогда автоматически не commit.
       */
      return false;
   }
}