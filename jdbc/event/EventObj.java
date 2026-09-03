package ru.inversion.tc.jdbc.event;

import java.util.NoSuchElementException;

/** */
public enum EventObj {

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
   public static EventObj fromTypeCode( int code )
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
      throw new NoSuchElementException("No elem 'EventObj' with typeCode " + code);
   }
}
