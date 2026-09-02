package ru.inversion.tc.jdbc.internal;

import java.util.concurrent.atomic.AtomicLong;

/** */
public final class JdbcObjectId
{
   private static final int TYPE_SHIFT = 56;

   private static final long SEQUENCE_MASK  = 0x00FFFFFFFFFFFFFFL;

   private static final int TYPE_CONNECTION = 1;
   private static final int TYPE_STATEMENT  = 2;
   private static final int TYPE_RESULT_SET = 3;

   /** */
   private JdbcObjectId()
   { }

   /** */
   public static long getSequence( long id )
   {
      return id & SEQUENCE_MASK;
   }

   /** */
   public static boolean isConnection( long id )
   {
      return getType(id) == TYPE_CONNECTION;
   }

   /** */
   public static boolean isStatement( long id )
   {
      return getType(id) == TYPE_STATEMENT;
   }

   /** */
   public static boolean isResultSet( long id )
   {
      return getType(id) == TYPE_RESULT_SET;
   }

   /** */
   public static String toString( long id )
   {
      long sequence = getSequence(id);

      switch( getType(id) )
      {
         case TYPE_CONNECTION:
            return "C#" + sequence;

         case TYPE_STATEMENT:
            return "S#" + sequence;

         case TYPE_RESULT_SET:
            return "RS#" + sequence;

         default:
            return "?#" + sequence;
      }
   }

   /** */
   private static long create( int type, long sequence )
   {
      if( sequence <= 0 || sequence > SEQUENCE_MASK )
         throw new IllegalArgumentException( "Invalid JDBC object sequence: " + sequence );

      return ((long) type << TYPE_SHIFT) | sequence;
   }

   /** */
   private static int getType( long id )
   {
      return (int) ((id >>> TYPE_SHIFT) & 0xFF);
   }


   /** */
   public static final class Generator
   {
      private static final AtomicLong CONNECTION_SEQUENCE =
              new AtomicLong();

      private final AtomicLong objectSequence =
              new AtomicLong();

      private final long connectionId;


      public Generator()
      {
         connectionId = create(
                 TYPE_CONNECTION,
                 CONNECTION_SEQUENCE.incrementAndGet()
         );
      }


      public long getConnectionId()
      {
         return connectionId;
      }


      public long nextStatementId()
      {
         return create(
                 TYPE_STATEMENT,
                 objectSequence.incrementAndGet()
         );
      }


      public long nextResultSetId()
      {
         return create(
                 TYPE_RESULT_SET,
                 objectSequence.incrementAndGet()
         );
      }
   }
}