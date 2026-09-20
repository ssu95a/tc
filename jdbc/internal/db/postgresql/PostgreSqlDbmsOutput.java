package ru.inversion.tc.jdbc.internal.db.postgresql;

import ru.inversion.db.JInvDbException;
import ru.inversion.tc.jdbc.internal.trace.JdbcDbmsOutput;
import ru.inversion.utils.Checks;
import ru.inversion.utils.S;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;

final class PostgreSqlDbmsOutput implements JdbcDbmsOutput
{
   private static final int READ_BATCH_SIZE = 100;

   /* PG логика. */
   private static final String ENABLE_SQL = "select dbms_output.enable(1000000)";

   private static final String DISABLE_SQL= "select dbms_output.disable()";

   private static final String READ_SQL = "select lines, numlines from dbms_output.get_lines(?) sel";

   private final Connection connection;

   private boolean enabled;


   /** */
   PostgreSqlDbmsOutput( Connection connection )
   {
      Checks.Require.object(connection, "connection" );
      this.connection = connection;
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

      try( Statement statement = connection.createStatement() )
      {
         statement.execute(ENABLE_SQL);
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

      try( Statement statement = connection.createStatement() )
      {
         statement.execute(DISABLE_SQL);
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
         return S.EMPTY_STRING;

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
               if( resultSet.next() )
               {
                  linesRead   = resultSet.getInt(2);
                  Array array = resultSet.getArray(1);

                  try
                  {
                     if( array != null )
                     {
                        Object[] lines = (Object[]) array.getArray();

                        for( Object line : lines )
                        {
                           if( line != null )
                               text.append(line).append('\n');
                        }
                     }
                  }
                  finally
                  {
                     if( array != null )
                         array.free();
                  }
               }
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