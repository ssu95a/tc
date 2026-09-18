package ru.inversion.tc.jdbc.internal.proxy;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** */
final class JdbcSqlTraceInfo {
   private static final String HIDE_MARKER = "\n--lhv:";

   private final String sql;

   private final Set<Integer> hiddenParameters;

   /** */
   private JdbcSqlTraceInfo(String sql, Set<Integer> hiddenParameters) {
      this.sql = sql;
      this.hiddenParameters = hiddenParameters;
   }

   static JdbcSqlTraceInfo parse( String sql )
   {
      if( sql == null || sql.isEmpty() )
         return new JdbcSqlTraceInfo(sql, Collections.emptySet());

      int pos = sql.lastIndexOf(HIDE_MARKER);

      if( pos < 0 )
          return new JdbcSqlTraceInfo( sql, Collections.emptySet() );

      Set<Integer> hidden = new TreeSet<>();

      String values = sql.substring(pos + HIDE_MARKER.length());

      for( String value : values.split(",") )
      {
         try
         {
            int index = Integer.parseInt(value.trim());

            if( index > 0 )
                hidden.add(index);
         }
         catch( NumberFormatException ignored )
         { }
      }

      return new JdbcSqlTraceInfo( sql.substring(0, pos), hidden );
   }

   String sql() {
      return sql;
   }

   Set<Integer> hiddenParameters() {
      return hiddenParameters;
   }

}
