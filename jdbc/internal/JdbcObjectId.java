package ru.inversion.tc.jdbc.internal;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** */
public final class JdbcObjectId
{
   /*
    * 63                       24 23                    4 3       0
    * +-------------------------+------------------------+---------+
    * | connectionNo : 39 bit   | statementNo : 20 bit   | RS : 4  |
    * +-------------------------+------------------------+---------+
    *
    * Connection : [C][0][0]
    * Statement  : [C][S][0]
    * ResultSet  : [C][S][R]
    */

   private static final int RESULT_SET_BITS = 4;
   private static final int STATEMENT_BITS  = 20;
   private static final int CONNECTION_BITS = 39;

   private static final int STATEMENT_SHIFT = RESULT_SET_BITS;

   private static final int CONNECTION_SHIFT = RESULT_SET_BITS + STATEMENT_BITS;


   private static final long RESULT_SET_MASK = (1L << RESULT_SET_BITS) - 1L;

   private static final long STATEMENT_MASK = (1L << STATEMENT_BITS) - 1L;

   private static final long CONNECTION_MASK = (1L << CONNECTION_BITS) - 1L;


   private JdbcObjectId()
   { }


   /** */
   public static long connectionNo( long id )
   {
      return (id >>> CONNECTION_SHIFT) & CONNECTION_MASK;
   }


   /** */
   public static int statementNo( long id )
   {
      return (int) ( (id >>> STATEMENT_SHIFT) & STATEMENT_MASK );
   }


   /** */
   public static int resultSetNo( long id )
   {
      return (int) (id & RESULT_SET_MASK);
   }


   /** */
   public static long connectionId( long id )
   {
      return id & (CONNECTION_MASK << CONNECTION_SHIFT);
   }


   /** */
   public static long statementId( long id )
   {
      return id & ~RESULT_SET_MASK;
   }


   /** */
   public static boolean isConnection( long id )
   {
      return connectionNo(id) != 0
              && statementNo(id) == 0
              && resultSetNo(id) == 0;
   }


   /** */
   public static boolean isStatement( long id )
   {
      return connectionNo(id) != 0
              && statementNo(id) != 0
              && resultSetNo(id) == 0;
   }


   /** */
   public static boolean isResultSet( long id )
   {
      return connectionNo(id) != 0
              && statementNo(id) != 0
              && resultSetNo(id) != 0;
   }


   /** */
   public static boolean isValid( long id )
   {
      if( id <= 0 )
         return false;

      long connectionNo = connectionNo(id);
      int statementNo = statementNo(id);
      int resultSetNo = resultSetNo(id);

      if( connectionNo == 0 )
         return false;

      /*
       * [C][0][R] невозможен.
       */
      return statementNo != 0 || resultSetNo == 0;
   }


   /** */
   public static String toString( long id )
   {
      long c = connectionNo(id);
      int s = statementNo(id);
      int r = resultSetNo(id);

      if( c == 0 )
         return "#INVALID";

      if( r != 0 )
         return "C#" + c + "/S#" + s + "/R#" + r;

      if( s != 0 )
         return "C#" + c + "/S#" + s;

      return "C#" + c;
   }


   /** */
   private static long connection( long connectionNo )
   {
      check(
              connectionNo,
              CONNECTION_MASK,
              "connectionNo"
      );

      return connectionNo << CONNECTION_SHIFT;
   }


   /** */
   private static long statement(
           long connectionId,
           int statementNo
   )
   {
      if( !isConnection(connectionId) )
         throw new IllegalArgumentException(
                 "Invalid connectionId: "
                         + JdbcObjectId.toString(connectionId)
         );

      check(
              statementNo,
              STATEMENT_MASK,
              "statementNo"
      );

      return connectionId
              | ((long) statementNo << STATEMENT_SHIFT);
   }


   /** */
   private static long resultSet(
           long statementId,
           int resultSetNo
   )
   {
      if( !isStatement(statementId) )
         throw new IllegalArgumentException(
                 "Invalid statementId: "
                         + JdbcObjectId.toString(statementId)
         );

      check(
              resultSetNo,
              RESULT_SET_MASK,
              "resultSetNo"
      );

      return statementId | resultSetNo;
   }


   /** */
   private static void check(
           long value,
           long maxValue,
           String name
   )
   {
      if( value <= 0 || value > maxValue )
         throw new IllegalArgumentException(
                 "Invalid " + name + ": " + value
         );
   }


   /**
    * Генератор идентификаторов для одного JDBC Connection.
    */
   public static final class Generator
   {
      /*
       * connectionNo уникален за время жизни JVM.
       */
      private static final AtomicLong CONNECTION_SEQUENCE =
              new AtomicLong();


      private final long connectionId;

      /*
       * statementNo монотонен внутри данного connection.
       */
      private int statementSequence;

      /*
       * Для каждого Statement храним занятые ResultSet slots.
       *
       * bit 0 -> resultSetNo 1
       * ...
       * bit 14 -> resultSetNo 15
       */
      private final Map<Integer, Integer> resultSetSlots =
              new HashMap<>();


      public Generator()
      {
         long connectionNo =
                 CONNECTION_SEQUENCE.incrementAndGet();

         connectionId =
                 JdbcObjectId.connection(connectionNo);
      }


      /** */
      public long connectionId()
      {
         return connectionId;
      }


      /** */
      public synchronized long nextStatementId()
      {
         if( statementSequence >= STATEMENT_MASK )
            throw new IllegalStateException(
                    "JDBC statement id sequence exhausted for "
                            + JdbcObjectId.toString(connectionId)
            );

         ++statementSequence;

         return JdbcObjectId.statement(
                 connectionId,
                 statementSequence
         );
      }


      /**
       * Занять свободный ResultSet slot внутри Statement.
       */
      public synchronized long nextResultSetId(
              long statementId
      )
      {
         checkStatement(statementId);

         int statementNo =
                 JdbcObjectId.statementNo(statementId);

         Integer value =
                 resultSetSlots.get(statementNo);

         int usedSlots =
                 value == null ? 0 : value;

         for( int resultSetNo = 1;
              resultSetNo <= RESULT_SET_MASK;
              resultSetNo++ )
         {
            int bit = 1 << (resultSetNo - 1);

            if( (usedSlots & bit) != 0 )
               continue;

            resultSetSlots.put(
                    statementNo,
                    usedSlots | bit
            );

            return JdbcObjectId.resultSet(
                    statementId,
                    resultSetNo
            );
         }

         throw new IllegalStateException(
                 "No free ResultSet slots for "
                         + JdbcObjectId.toString(statementId)
         );
      }


      /**
       * Освободить ResultSet slot.
       *
       * Вызывать только при реальном unregister ResultSet
       * из lifecycle registry.
       */
      public synchronized void releaseResultSetId(
              long resultSetId
      )
      {
         if( !JdbcObjectId.isResultSet(resultSetId) )
            throw new IllegalArgumentException(
                    "Invalid resultSetId: "
                            + JdbcObjectId.toString(resultSetId)
            );

         if( JdbcObjectId.connectionId(resultSetId)
                 != connectionId )
         {
            throw new IllegalArgumentException(
                    "ResultSet belongs to another connection: "
                            + JdbcObjectId.toString(resultSetId)
            );
         }

         int statementNo =
                 JdbcObjectId.statementNo(resultSetId);

         int resultSetNo =
                 JdbcObjectId.resultSetNo(resultSetId);

         Integer value =
                 resultSetSlots.get(statementNo);

         if( value == null )
            return;

         int usedSlots = value;

         int bit =
                 1 << (resultSetNo - 1);

         usedSlots &= ~bit;

         if( usedSlots == 0 )
            resultSetSlots.remove(statementNo);
         else
            resultSetSlots.put(
                    statementNo,
                    usedSlots
            );
      }


      /** */
      private void checkStatement(
              long statementId
      )
      {
         if( !JdbcObjectId.isStatement(statementId) )
            throw new IllegalArgumentException(
                    "Invalid statementId: "
                            + JdbcObjectId.toString(statementId)
            );

         if( JdbcObjectId.connectionId(statementId)
                 != connectionId )
         {
            throw new IllegalArgumentException(
                    "Statement belongs to another connection: "
                            + JdbcObjectId.toString(statementId)
            );
         }
      }
   }
}