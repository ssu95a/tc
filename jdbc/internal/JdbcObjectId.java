package ru.inversion.tc.jdbc.internal;

import ru.inversion.tc.jdbc.event.EventObj;

import java.util.concurrent.atomic.AtomicLong;

/** */
public final class JdbcObjectId
{
   private static final int TYPE_SHIFT = 56;

   private static final long SEQUENCE_MASK  = 0x00FFFFFFFFFFFFFFL;

//   private static final int TYPE_CONNECTION = 1;
//   private static final int TYPE_STATEMENT  = 2;
//   private static final int TYPE_RESULT_SET = 3;

   /** */
   private JdbcObjectId()
   { }

   /** */
   public static long getSequence( long id )
   {
      return id & SEQUENCE_MASK;
   }

   /** */
   public static boolean isConnection( long id ) { return getType(id) == EventObj.CONNECTION; }

   /** */
   public static boolean isStatement( long id )
   {
      return getType(id) == EventObj.STATEMENT;
   }

   /** */
   public static boolean isResultSet( long id )
   {
      return getType(id) == EventObj.RESULT_SET;
   }

   /** */
   public static boolean isApplication( long id )
   {
      return getType(id) == EventObj.APPLICATION;
   }

   /** */
   public static boolean isJdbc( long id )
   {
      return getType(id) == EventObj.JDBC;
   }

   /** */
   public static String toString( long id )
   {
      long sequence = getSequence(id);

      switch( getType(id) )
      {
         case CONNECTION:
            return "C#" + sequence;

         case STATEMENT:
            return "S#" + sequence;

         case RESULT_SET:
            return "R#" + sequence;

         case APPLICATION:
            return "A#" + sequence;

         case JDBC:
            return "J#" + sequence;

         default:
            return "?#" + sequence;
      }
   }

   /** */
   private static long create( EventObj obj, long sequence )
   {
      if( sequence <= 0 || sequence > SEQUENCE_MASK )
          throw new IllegalArgumentException( "Invalid JDBC object sequence: " + sequence );

      return ((long) obj.typeCode() << TYPE_SHIFT ) | sequence;
   }

   /** */
   private static EventObj getType( long id )
   {
      return EventObj.fromTypeCode((int) ((id >>> TYPE_SHIFT) & 0xFF));
   }

   /** */
   public static final class Generator
   {
      private static final AtomicLong CONNECTION_SEQUENCE = new AtomicLong();
      private final AtomicLong objectSequence = new AtomicLong();

      private final long connectionId;

      public Generator()
      {
         connectionId = create( EventObj.CONNECTION, CONNECTION_SEQUENCE.incrementAndGet() );
      }

      public long getConnectionId()
      {
         return connectionId;
      }

      public long nextStatementId()
      {
         return create( EventObj.STATEMENT, objectSequence.incrementAndGet() );
      }

      public long nextResultSetId()
      {
         return create( EventObj.RESULT_SET, objectSequence.incrementAndGet() );
      }
   }
}