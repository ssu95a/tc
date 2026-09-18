package ru.inversion.tc.jdbc.internal.db.oracle;

import ru.inversion.tc.dbms_output.DBMSOutputImpl;
import ru.inversion.tc.jdbc.event.EventType;
import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.internal.db.JdbcDatabaseSupport;
import ru.inversion.tc.jdbc.internal.trace.JdbcServerOutputTracer;
import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

import java.sql.Connection;
import java.util.function.Predicate;

/** */
public final class OracleDatabaseSupport implements JdbcDatabaseSupport
{
   private final JdbcTransactionPolicy transactionPolicy = new OracleTransactionPolicy();

   @Override
   public JdbcTransactionPolicy transactionPolicy()
   {
      return transactionPolicy;
   }

   @Override
   public JdbcServerOutputTracer createServerOutputTracer( Connection connection, JdbcEventBus eventBus, Predicate<EventType> enabled )
   {
      return new JdbcServerOutputTracer( eventBus, new DBMSOutputImpl(connection), enabled, null );
   }
}