package ru.inversion.tc.jdbc.internal;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;

/** */
public final class JdbcObjectId
{
   private static final int TYPE_SHIFT = 56;

   private static final long SEQUENCE_MASK  = 0x00FFFFFFFFFFFFFFL;

   private enum ObjectType {

      CONNECTION,
      STATEMENT,
      RESULT_SET,
      APPLICATION,
      JDBC;

      public int typeCode()
      {
         switch ( this ) {
            case CONNECTION:
               return 1;
            case STATEMENT:
               return 2;
            case RESULT_SET:
               return 3;
            case APPLICATION:
               return 4;
            case JDBC:
               return 5;
         }
         return 0;
      }

      /** */
      public static ObjectType fromTypeCode(int code )
      {
         switch ( code ) {
            case 1:
               return CONNECTION;
            case 2:
               return STATEMENT;
            case 3:
               return RESULT_SET;
            case 4:
               return APPLICATION;
            case 5:
               return JDBC;
         }
         throw new NoSuchElementException("No elem 'ObjectType' with typeCode " + code);
      }
   }

   /** */
   private JdbcObjectId()
   { }

   /** */
   public static long getSequence( long id )
   {
      return id & SEQUENCE_MASK;
   }

   /** */
   public static boolean isConnection( long id ) { return getType(id) == ObjectType.CONNECTION; }

   /** */
   public static boolean isStatement( long id )
   {
      return getType(id) == ObjectType.STATEMENT;
   }

   /** */
   public static boolean isResultSet( long id )
   {
      return getType(id) == ObjectType.RESULT_SET;
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
   private static long create( ObjectType obj, long sequence )
   {
      if( sequence <= 0 || sequence > SEQUENCE_MASK )
          throw new IllegalArgumentException( "Invalid JDBC object sequence: " + sequence );

      return ((long) obj.typeCode() << TYPE_SHIFT ) | sequence;
   }

   /** */
   private static ObjectType getType( long id )
   {
      return ObjectType.fromTypeCode((int) ((id >>> TYPE_SHIFT) & 0xFF));
   }

   /** */
   public static final class Generator
   {
      private static final AtomicLong CONNECTION_SEQUENCE = new AtomicLong();

      private final AtomicLong objectSequence = new AtomicLong();

      private final long connectionId;

      public Generator()
      {
         connectionId = create( ObjectType.CONNECTION, CONNECTION_SEQUENCE.incrementAndGet() );
      }

      public long connectionId()
      {
         return connectionId;
      }

      public long nextStatementId()
      {
         return create( ObjectType.STATEMENT, objectSequence.incrementAndGet() );
      }

      public long nextResultSetId()
      {
         return create( ObjectType.RESULT_SET, objectSequence.incrementAndGet() );
      }
   }
}