package ru.inversion.tc.jdbc.internal.transaction;

import ru.inversion.tc.jdbc.internal.lifecycle.JdbcLifecycleManager;
import ru.inversion.utils.Checks;

import java.sql.Connection;
import java.sql.SQLException;


public final class JdbcTransactionManager
{
   private final Connection connection;
   private final JdbcLifecycleManager lifecycle;
   private final JdbcTransactionPolicy policy;

   public JdbcTransactionManager(
      Connection connection,
      JdbcLifecycleManager lifecycle,
      JdbcTransactionPolicy policy
   )
   {
      Checks.Require.objects( connection, "connection", lifecycle, "lifecycle", policy, "policy" );

      this.connection = connection;
      this.lifecycle  = lifecycle;
      this.policy     = policy;
   }


   /** */
   public synchronized boolean tryFinishReadTransaction( ) throws SQLException
   {
      /*
       * Универсальные JDBC/lifecycle ограничения.
       */
      if( lifecycle.hasOpenCursors() )
          return false;

      if( lifecycle.hasSavepoints() )
          return false;

      if( connection.isClosed() )
          return false;

      if( connection.getAutoCommit() )
          return false;

      /*
       * Всё специфичное для конкретной СУБД —
       * только внутри policy.
       */
      if( !policy.canFinishReadTransaction( connection ) )
      {
         return false;
      }

      /*
       * Повторная обязательная проверка перед commit.
       */
      if( lifecycle.hasOpenCursors() )
         return false;

      if( lifecycle.hasSavepoints() )
         return false;

      connection.commit();

      lifecycle.transactionFinished();

      return true;
   }
}