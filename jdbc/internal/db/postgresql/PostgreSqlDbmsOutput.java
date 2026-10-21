package ru.inversion.tc.jdbc.internal.db.postgresql;

import ru.inversion.tc.jdbc.internal.trace.AbstractDbmsOutput;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Consumer;

final class PostgreSqlDbmsOutput extends AbstractDbmsOutput
{
   private static final String ENABLE_SQL = "select dbms_output.enable(1000000)";

   private static final String DISABLE_SQL= "select dbms_output.disable()";

   private static final String READ_SQL   = "select lines, numlines  from dbms_output.get_lines(?) sel";

   /** */
   PostgreSqlDbmsOutput( Connection connection )
   {
      super( connection, ENABLE_SQL, DISABLE_SQL, READ_SQL );
   }

   /** */
   @Override
   protected void executeServerCommand( String sql ) throws SQLException
   {
      try( Statement statement = connection.createStatement() )
      {
         statement.execute(sql);
      }
   }

   /** */
   @Override
   protected void readOutput( Consumer<String> consumer ) throws SQLException
   {
      StringBuilder text = new StringBuilder();

      try( PreparedStatement statement = connection.prepareStatement(READ_SQL) )
      {
         int linesRead;

         do
         {
            statement.setInt( 1, READ_BATCH_SIZE );
            linesRead = 0;

            try( ResultSet resultSet = statement.executeQuery() )
            {
               if( resultSet.next() ) {
                  linesRead = resultSet.getInt(2);
                  consumeLines( resultSet.getArray(1), text, consumer );
               }
            }
         }
         while( linesRead == READ_BATCH_SIZE );
      }
   }
}
