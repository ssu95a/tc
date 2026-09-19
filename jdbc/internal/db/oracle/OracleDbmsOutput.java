package ru.inversion.tc.jdbc.internal.db.oracle;

import ru.inversion.db.JInvDbException;
import ru.inversion.tc.jdbc.internal.trace.JdbcDbmsOutput;
import ru.inversion.utils.Checks;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;

final class OracleDbmsOutput implements JdbcDbmsOutput
{
   private static final int READ_BATCH_SIZE = 100;

   /*
    * Сохраняем существующую семантику Oracle:
    * null = unlimited buffer.
    */
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

   private final Connection connection;

   private boolean enabled;


   /** */
   OracleDbmsOutput( Connection connection )
   {
      this.connection = Checks.Require.object(connection,"connection");
   }


   /** */
   @Override
   public boolean isEnabled()
   {
      return enabled;
   }


   @Override
   public void enable()
   {
      if( enabled )
         return;

      try( CallableStatement call = connection.prepareCall(ENABLE_SQL) )
      {
         call.execute();
         enabled = true;
      }
      catch( SQLException ex ) {
         throw new JInvDbException( ex, ENABLE_SQL );
      }
   }


   @Override
   public void disable()
   {
      if( !enabled )
         return;

      try( CallableStatement call = connection.prepareCall(DISABLE_SQL) )
      {
         call.execute();
         enabled = false;
      }
      catch( SQLException ex ) {
         throw new JInvDbException( ex, DISABLE_SQL );
      }
   }


   @Override
   public String read()
   {
      if( !enabled )
         return "";

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

            Array array = call.getArray(2);

            try
            {
               if( array == null )
                  continue;

               Object[] lines = (Object[]) array.getArray();

               for( Object line : lines )
               {
                  if( line != null )
                      text.append(line).append('\n');
               }
            }
            finally {
               if( array != null )
                   array.free();
            }
         }
         while( linesRead == READ_BATCH_SIZE );

         return text.toString();
      }
      catch( SQLException ex ) {
         throw new JInvDbException( ex, READ_SQL );
      }
   }
}