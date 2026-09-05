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
   private final Map<ResultSet, CursorRegistration> cursors =
           new IdentityHashMap<>();

   private final Map<Savepoint, Boolean> savepoints = new IdentityHashMap<>();


   /**
    * Registration identity используется для защиты
    * от stale JdbcResultSetProxy.
    */
   public static final class CursorRegistration
   {
      private final ResultSet resultSet;

      private CursorRegistration(
              ResultSet resultSet
      )
      {
         this.resultSet = resultSet;
      }
   }


   /**
    * Регистрирует cursor.
    *
    * Повторная регистрация того же raw ResultSet
    * возвращает существующую registration.
    */
   public synchronized CursorRegistration registerCursor(
           ResultSet resultSet
   )
   {
      if( resultSet == null )
      {
         throw new IllegalArgumentException(
                 "resultSet is null"
         );
      }

      CursorRegistration current =
              cursors.get(resultSet);

      if( current != null )
         return current;

      CursorRegistration registration =
              new CursorRegistration(resultSet);

      cursors.put(
              resultSet,
              registration
      );

      return registration;
   }


   /**
    * Снимает именно эту registration.
    *
    * Stale registration безопасно вернёт false.
    */
   public synchronized boolean unregisterCursor(
           CursorRegistration registration
   )
   {
      if( registration == null )
         return false;

      ResultSet resultSet =
              registration.resultSet;

      CursorRegistration current =
              cursors.get(resultSet);

      if( current != registration )
         return false;

      cursors.remove(resultSet);

      return true;
   }


   public synchronized int openCursorCount()
   {
      return cursors.size();
   }


   public synchronized boolean hasOpenCursors()
   {
      return !cursors.isEmpty();
   }


   public synchronized boolean isRegistered(
           CursorRegistration registration
   )
   {
      if( registration == null )
         return false;

      return cursors.get(
              registration.resultSet
      ) == registration;
   }

   public synchronized void savepointSet( Savepoint savepoint )
   {
      if( savepoint != null )
          savepoints.put(savepoint, Boolean.TRUE);
   }

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