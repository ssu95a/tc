package ru.inversion.tc.jdbc.internal.transaction;

import ru.inversion.tc.jdbc.internal.lifecycle.JdbcLifecycleManager;

import java.sql.Connection;
import java.sql.SQLException;


public interface JdbcTransactionPolicy
{
   boolean canFinishReadTransaction(
           Connection connection
   )
           throws SQLException;
}