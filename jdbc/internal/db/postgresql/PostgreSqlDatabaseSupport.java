package ru.inversion.tc.jdbc.internal.db.postgresql;

import ru.inversion.tc.jdbc.event.EventType;
import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.internal.db.JdbcDatabaseSupport;
import ru.inversion.tc.jdbc.internal.trace.JdbcServerOutputTracer;
import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Predicate;

public class PostgreSqlDatabaseSupport implements JdbcDatabaseSupport {

   /** */
   @Override
   public JdbcTransactionPolicy transactionPolicy() {
      return new PostgreSqlTransactionPolicy();
   }


   /** */
   @Override
   public JdbcServerOutputTracer createServerOutputTracer( Connection connection, JdbcEventBus eventBus, Predicate<EventType> enabled )
   {
      return new JdbcServerOutputTracer( eventBus, new PostgreSqlDbmsOutput(connection), enabled, value -> setRaiseNoticeState( connection, value ) );
   }


   /** */
   private static void setRaiseNoticeState( Connection connection, boolean enable )
   {
      final String sql = "{ call jf_pkg_util.set_raisenotice_state(?) }";

      try( CallableStatement cs = connection.prepareCall(sql) )
      {
         cs.setInt( 1, enable ? 1 : 0 );
         cs.execute();
      }
      catch( SQLException ex ) {
         throw new RuntimeException( "Error on call 'jf_pkg_util.set_raisenotice_state'", ex );
      }
   }
}
