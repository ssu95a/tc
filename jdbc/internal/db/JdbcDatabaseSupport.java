package ru.inversion.tc.jdbc.internal.db;

import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

/**<h5>Особые фичи СУБД которые доступны при работе с ней</h5> */
public interface JdbcDatabaseSupport {

   /** */
   JdbcTransactionPolicy transactionPolicy();
}
