package ru.inversion.tc.jdbc.internal.db.oracle;

import ru.inversion.tc.jdbc.internal.trace.AbstractDbmsOutput;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.function.Consumer;

/** */
final class OracleDbmsOutput extends AbstractDbmsOutput
{
   private static final String ENABLE_SQL = "{call dbms_output.enable(null)}";

   private static final String DISABLE_SQL= "{call dbms_output.disable()}";

   private static final String READ_SQL =
           "declare " +
                   "  l_num integer; " +
                   "begin " +
                   "  l_num := ?; " +
                   "  dbms_output.get_lines(?, l_num); " +
                   "  ? := l_num; " +
                   "end;";

   /** */
   OracleDbmsOutput( Connection connection )
   {
      super( connection, ENABLE_SQL, DISABLE_SQL, READ_SQL );
   }

   /** */
   @Override
   protected void executeServerCommand(String sql ) throws SQLException
   {
      try( CallableStatement call = connection.prepareCall(sql) )
      {
         call.execute();
      }
   }

   @Override
   protected void readOutput( Consumer<String> consumer ) throws SQLException
   {
      StringBuilder text = new StringBuilder();

      try( CallableStatement call = connection.prepareCall(READ_SQL) )
      {
         call.registerOutParameter( 2, Types.ARRAY, "DBMSOUTPUT_LINESARRAY" );
         call.registerOutParameter( 3, Types.INTEGER );

         int linesRead;

         do
         {
            call.setInt( 1, READ_BATCH_SIZE );
            call.execute();

            linesRead = call.getInt(3);

            consumeLines( call.getArray(2), text, consumer );
         }
         while( linesRead == READ_BATCH_SIZE );
      }
   }
}
