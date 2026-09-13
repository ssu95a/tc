package ru.inversion.tc.jdbc.internal.transaction;

import java.sql.Connection;
import java.sql.SQLException;


/** */
public interface JdbcTransactionPolicy
{
   /** Допустим ли автоматический COMMIT idle-транзакции. */
   boolean canCommitIdleTransaction(Connection connection ) throws SQLException;
}