package ru.inversion.tc.jdbc.internal.proxy;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;


/**
 * Служебная trace-информация, переданная
 * в trailing SQL comments.
 *
 * Пример:
 *
 * { call pkg.proc(?, ?, ?) }
 * --lhv:2,3
 * --lti
 */
final class JdbcSqlTraceInfo
{
   /*
    * Hide Log Values.
    */
   private static final String HIDE_MARKER =
           "--lhv:";

   /*
    * Log Trace Ignore.
    */
   private static final String IGNORE_MARKER =
           "--lti";


   private final String sql;

   private final Set<Integer> hiddenParameters;

   private final boolean traceIgnored;


   /** */
   private JdbcSqlTraceInfo(
           String sql,
           Set<Integer> hiddenParameters,
           boolean traceIgnored
   )
   {
      this.sql = sql;
      this.hiddenParameters = hiddenParameters;
      this.traceIgnored = traceIgnored;
   }


   /** */
   static JdbcSqlTraceInfo parse(
           String sql
   )
   {
      if( sql == null || sql.isEmpty() )
      {
         return new JdbcSqlTraceInfo(
                 sql,
                 Collections.emptySet(),
                 false
         );
      }

      Set<Integer> hidden =
              new TreeSet<>();

      boolean traceIgnored = false;

      /*
       * Убираем trailing whitespace только для поиска
       * последней логической строки.
       *
       * Сам original SQL пока не меняем.
       */
      int lineEnd = sql.length();

      while( lineEnd > 0 )
      {
         char ch =
                 sql.charAt(lineEnd - 1);

         if( ch != '\r'
                 && ch != '\n'
                 && ch != ' '
                 && ch != '\t' )
            break;

         lineEnd--;
      }

      /*
       * Если metadata block будет найден,
       * отсюда будет отрезан SQL.
       */
      int metadataStart = -1;


      while( lineEnd > 0 )
      {
         int newLine =
                 sql.lastIndexOf(
                         '\n',
                         lineEnd - 1
                 );

         int lineStart =
                 newLine + 1;

         String line =
                 sql.substring(
                         lineStart,
                         lineEnd
                 ).trim();

         /*
          * Идём снизу вверх только пока
          * продолжается trailing comment block.
          */
         if( !line.startsWith("--") )
            break;

         metadataStart =
                 lineStart;

         if( IGNORE_MARKER.equals(line) )
         {
            traceIgnored = true;
         }
         else if( line.startsWith(HIDE_MARKER) )
         {
            parseHiddenParameters(
                    line.substring(
                            HIDE_MARKER.length()
                    ),
                    hidden
            );
         }

         /*
          * Неизвестная --директива не мешает
          * продолжить разбор trailing comment block.
          */

         if( newLine < 0 )
            break;

         /*
          * Следующая итерация заканчивается
          * перед найденным '\n'.
          *
          * CR из CRLF попадёт в substring,
          * но trim() его уберёт.
          */
         lineEnd = newLine;
      }


      String cleanSql =
              metadataStart < 0
                      ? sql
                      : removeTrailingLineBreaks(
                      sql.substring(
                              0,
                              metadataStart
                      )
              );


      return new JdbcSqlTraceInfo(
              cleanSql,
              hidden.isEmpty()
                      ? Collections.emptySet()
                      : Collections.unmodifiableSet(hidden),
              traceIgnored
      );
   }


   /** */
   private static void parseHiddenParameters(
           String values,
           Set<Integer> hidden
   )
   {
      if( values == null || values.isEmpty() )
         return;

      for( String value : values.split(",") )
      {
         try
         {
            int index =
                    Integer.parseInt(
                            value.trim()
                    );

            if( index > 0 )
               hidden.add(index);
         }
         catch( NumberFormatException ignored )
         {
            /*
             * Некорректное значение просто
             * не участвует в masking.
             */
         }
      }
   }


   /** */
   private static String removeTrailingLineBreaks(
           String value
   )
   {
      int end =
              value.length();

      while( end > 0 )
      {
         char ch =
                 value.charAt(end - 1);

         if( ch != '\r' && ch != '\n' )
            break;

         end--;
      }

      return end == value.length()
              ? value
              : value.substring(0, end);
   }


   /** */
   String sql()
   {
      return sql;
   }


   /** */
   Set<Integer> hiddenParameters()
   {
      return hiddenParameters;
   }


   /** */
   boolean isTraceIgnored()
   {
      return traceIgnored;
   }
}