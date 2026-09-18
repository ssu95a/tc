package ru.inversion.tc.jdbc.internal.db;

import ru.inversion.tc.jdbc.event.EventType;
import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.internal.trace.JdbcServerOutputTracer;
import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

import java.sql.Connection;
import java.util.function.Predicate;

/**<h5>Особые фичи СУБД которые доступны при работе с ней</h5> */
public interface JdbcDatabaseSupport {

   /** */
   JdbcTransactionPolicy transactionPolicy();

   /** */
   JdbcServerOutputTracer createServerOutputTracer(
           Connection connection,
           JdbcEventBus eventBus,
           Predicate<EventType> enabled
   );
}
