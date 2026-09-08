package ru.inversion.tc.jdbc.internal.transaction;

import java.sql.Connection;
import java.sql.SQLException;


/** */
public interface JdbcTransactionPolicy
{
   /** Можем ли завершить транзакцию */
   boolean canFinishReadTransaction( Connection connection ) throws SQLException;
}