package ru.inversion.tc.jdbc.internal.trace;

import ru.inversion.db.JInvDbException;
import ru.inversion.utils.Checks;

import java.sql.Array;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Consumer;

/** Общая реализация server-side DBMS_OUTPUT. */
public abstract class AbstractDbmsOutput implements JdbcDbmsOutput
{
   protected static final int READ_BATCH_SIZE = 100;

   protected final Connection connection;

   private final String enableSql;
   private final String disableSql;
   private final String readSql;

   private boolean enabled;


   /** */
   protected AbstractDbmsOutput( Connection connection, String enableSql, String disableSql, String readSql )
   {
      this.connection = Checks.Require.object( connection, "connection");
      this.enableSql  = Checks.Require.text  ( enableSql,  "enableSql" );
      this.disableSql = Checks.Require.text  ( disableSql, "disableSql");
      this.readSql    = Checks.Require.text  ( readSql,    "readSql"   );
   }


   /** */
   @Override
   public final boolean isEnabled()
   {
      return enabled;
   }


   /** */
   @Override
   public final void enable()
   {
      if( enabled )
          return;

      try {
         executeServerCommand(enableSql);
         enabled = true;
      }
      catch( SQLException ex ) {
         throw new JInvDbException( ex, enableSql );
      }
   }


   /** */
   @Override
   public final void disable()
   {
      if( !enabled )
         return;

      try {
         executeServerCommand(disableSql);
         enabled = false;
      }
      catch( SQLException ex ) {
         throw new JInvDbException( ex, disableSql );
      }
   }


   /** */
   @Override
   public final void read( Consumer<String> consumer )
   {
      Checks.Require.object( consumer, "consumer" );

      if( !enabled )
          return;

      try {
         readOutput(consumer);
      }
      catch( SQLException ex ) {
         throw new JInvDbException( ex, readSql );
      }
   }

   /** */
   protected final void consumeLines( Array array, StringBuilder text, Consumer<String> consumer ) throws SQLException
   {
      if( array == null )
         return;

      try
      {
         Object[] lines = (Object[]) array.getArray();

         for( Object line : lines )
         {
            if( line != null )
                text.append(line).append('\n');
         }

         if( text.length() != 0 )
         {
            consumer.accept( text.toString() );
            text.setLength(0);
         }
      }
      finally {
         array.free();
      }
   }

   /** */
   protected abstract void executeServerCommand( String sql ) throws SQLException;

   /** */
   protected abstract void readOutput( Consumer<String> consumer ) throws SQLException;

}
