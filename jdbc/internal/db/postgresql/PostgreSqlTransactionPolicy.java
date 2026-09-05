package ru.inversion.tc.jdbc.internal.db.postgresql;

import ru.inversion.tc.jdbc.internal.transaction.JdbcTransactionPolicy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** */
public final class PostgreSqlTransactionPolicy implements JdbcTransactionPolicy
{
   private static final String SQL_XACT_ID = "select pg_current_xact_id_if_assigned()";


   @Override
   public boolean canFinishReadTransaction( Connection connection )
           throws SQLException
   {
      if( connection.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED )
      {
         return false;
      }

      return !hasAssignedTransactionId( connection );
   }


   /** */
   private boolean hasAssignedTransactionId( Connection connection ) throws SQLException
   {
      try( Statement statement = connection.createStatement() )
      {
         statement.setFetchSize(0);

         try( ResultSet resultSet = statement.executeQuery( SQL_XACT_ID ) )
         {
            if( !resultSet.next() )
                 return true;

            return resultSet.getObject(1) != null;
         }
      }
   }
}