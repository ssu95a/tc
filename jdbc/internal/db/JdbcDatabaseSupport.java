package ru.inversion.tc.jdbc.internal.db;

import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

/** */
public interface JdbcDatabaseSupport {

   JdbcTransactionPolicy transactionPolicy();
}
