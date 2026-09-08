package ru.inversion.tc.jdbc.internal.lifecycle;

import java.sql.ResultSet;
import java.sql.Savepoint;
import java.util.IdentityHashMap;
import java.util.Map;


/**
 * Mandatory JDBC lifecycle state for one Connection.
 *
 * Tracks only Statement-owned cursor ResultSet.
 */
public final class JdbcLifecycleManager
{
   private final Map<ResultSet, CursorReg>  cursors = new IdentityHashMap<>();

   private final Map<Savepoint, Boolean> savepoints = new IdentityHashMap<>();

   /**
    * Registration identity используется для защиты
    * от stale JdbcResultSetProxy.
    */
   public static final class CursorReg
   {
      private final ResultSet resultSet;

      private CursorReg(ResultSet resultSet)
      {
         this.resultSet = resultSet;
      }
   }


   /**
    * Регистрирует cursor.
    * <p>
    * Повторная регистрация того же raw ResultSet
    * возвращает существующую registration.
    */
   public synchronized CursorReg registerCursor( ResultSet resultSet )
   {
      if( resultSet == null )
         throw new IllegalArgumentException( "resultSet is null" );

      CursorReg current = cursors.get(resultSet);

      if( current != null )
          return current;

      CursorReg registration = new CursorReg(resultSet);

      cursors.put( resultSet, registration );

      return registration;
   }


   /**
    * Снимает именно эту registration.
    * <p>
    * Stale registration безопасно вернёт false.
    */
   public synchronized boolean unregisterCursor( CursorReg registration )
   {
      if( registration == null )
          return false;

      ResultSet resultSet = registration.resultSet;

      CursorReg current = cursors.get(resultSet);

      if( current != registration )
          return false;

      cursors.remove(resultSet);

      return true;
   }


   /** */
   public synchronized int openCursorCount()
   {
      return cursors.size();
   }


   /** */
   public synchronized boolean hasOpenCursors()
   {
      return !cursors.isEmpty();
   }


   /** */
   public synchronized boolean isRegistered( CursorReg registration )
   {
      if( registration == null )
         return false;

      return cursors.get( registration.resultSet ) == registration;
   }


   /** */
   public synchronized void savepointSet( Savepoint savepoint )
   {
      if( savepoint != null )
          savepoints.put(savepoint, Boolean.TRUE);
   }


   /** */
   public synchronized void savepointReleased( Savepoint savepoint )
   {
      if( savepoint != null )
          savepoints.remove(savepoint);
   }

   /** */
   public synchronized boolean hasSavepoints()
   {
      return !savepoints.isEmpty();
   }

   /** */
   public synchronized void transactionFinished()
   {
      savepoints.clear();
   }
}