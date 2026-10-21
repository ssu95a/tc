package ru.inversion.tc.jdbc.internal.transaction;

import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** */
public final class JdbcSavepointManager
{
   private final List<SavepointReg> savepoints = new ArrayList<>();

   private static final class SavepointReg
   {
      private final Savepoint savepoint;
      private final String name;

      private SavepointReg( Savepoint savepoint, String name )
      {
         this.savepoint = savepoint;
         this.name = name;
      }
   }

   public synchronized void set( Savepoint savepoint, String name )
   {
      if( savepoint == null )
          throw new IllegalArgumentException( "savepoint is null" );

      savepoints.add( new SavepointReg( savepoint, name ) );
   }


   public synchronized boolean hasSavepoints()
   {
      return !savepoints.isEmpty();
   }


   /** */
   public synchronized Savepoint find( String name )
   {
      for( int i = savepoints.size() - 1; i >= 0; i-- )
      {
         SavepointReg reg = savepoints.get(i);

         if( Objects.equals( name, reg.name ) )
             return reg.savepoint;
      }

      return null;
   }


   /** */
   public synchronized void released( Savepoint savepoint )
   {
      int index = indexOf(savepoint);

      if( index >= 0 )
          removeFrom( index );
   }


   /** */
   public synchronized void rollbackTo( Savepoint savepoint )
   {
      int index = indexOf(savepoint);

      if( index >= 0 )
         removeFrom(index + 1);
   }


   /** */
   public synchronized void onTransactionCompleted()
   {
      savepoints.clear();
   }


   /** */
   private int indexOf( Savepoint savepoint )
   {
      for( int i = savepoints.size() - 1; i >= 0; i-- )
      {
         if( savepoints.get(i).savepoint == savepoint )
             return i;
      }

      return -1;
   }


   /** */
   private void removeFrom( int index )
   {
      if( index < savepoints.size() )
         savepoints.subList( index, savepoints.size() ).clear();
   }
}