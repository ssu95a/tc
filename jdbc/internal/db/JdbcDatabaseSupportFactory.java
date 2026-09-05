package ru.inversion.tc.jdbc.internal.db;

import ru.inversion.tc.jdbc.internal.db.oracle.OracleDatabaseSupport;
import ru.inversion.tc.jdbc.internal.db.postgresql.PostgreSqlDatabaseSupport;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

public final class JdbcDatabaseSupportFactory
{
   private JdbcDatabaseSupportFactory()
   { }


   /** */
   public static JdbcDatabaseSupport create ( Connection connection )
   {
      try
      {
         String productName = connection.getMetaData().getDatabaseProductName();

         if( productName != null )
         {
            String name = productName.trim();

            if( "PostgreSQL".equalsIgnoreCase(name) )
                return new PostgreSqlDatabaseSupport();

            if( name.toLowerCase(Locale.ROOT).contains("oracle") )
                return new OracleDatabaseSupport();
         }
      }
      catch( SQLException ignored )
      {
         /*
          * DB-specific optimizations disabled.
          */
      }
      return new DefaultDatabaseSupport();
   }
}