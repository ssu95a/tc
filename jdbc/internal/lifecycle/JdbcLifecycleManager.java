package ru.inversion.tc.jdbc.internal.lifecycle;

import ru.inversion.utils.Checks;

import java.sql.ResultSet;
import java.util.IdentityHashMap;
import java.util.Map;


/**
 * Жизненный цикл одного соединения
 * <p>
 * Цель:
 * <p>
 *    Отслеживание resultSet-курсоров, открытие, закрытие!
 */
public final class JdbcLifecycleManager
{
   private final Map<ResultSet, CursorToken>  cursors = new IdentityHashMap<>();

   /**
    * Registration identity используется для защиты от удаления "двойника".
    */
   public static final class CursorToken
   {
      private final ResultSet resultSet;
      private CursorToken(ResultSet resultSet)
      {
         this.resultSet = resultSet;
      }
   }


   /**
    * Регистрирует cursor.
    * <p>
    * Регистрация ResultSet
    */
   public synchronized CursorToken registerCursor( ResultSet resultSet )
   {
      Checks.Require.object( resultSet, "resultSet" );

      CursorToken token = new CursorToken(resultSet);

      cursors.put( resultSet, token );

      return token;
   }

   /**
    * Снимает именно эту registration.
    * <p>
    * Если тот же курсор зареган еще раз, где-то, то не удалим!
    */
   public synchronized boolean unregisterCursor( CursorToken registration )
   {
      if( registration == null )
          return false;

      ResultSet resultSet = registration.resultSet;

      CursorToken current = cursors.get(resultSet);

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

}
