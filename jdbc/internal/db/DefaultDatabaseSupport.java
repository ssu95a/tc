package ru.inversion.tc.jdbc.internal.db;

import ru.inversion.tc.jdbc.event.EventType;
import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.internal.trace.JdbcServerOutputTracer;
import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

import java.sql.Connection;
import java.util.function.Predicate;

public final class DefaultDatabaseSupport
        implements JdbcDatabaseSupport
{
   private final JdbcTransactionPolicy transactionPolicy = new DefaultTransactionPolicy();

   @Override
   public JdbcTransactionPolicy transactionPolicy()
   {
      return transactionPolicy;
   }

   @Override
   public JdbcServerOutputTracer createServerOutputTracer(Connection connection, JdbcEventBus eventBus, Predicate<EventType> enabled) {
      return null;
   }
}