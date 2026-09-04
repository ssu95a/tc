package ru.inversion.tc.jdbc.internal.lifecycle;

import ru.inversion.tc.jdbc.internal.JdbcObjectId;

import java.sql.ResultSet;
import java.util.IdentityHashMap;
import java.util.Map;


/**
 * Mandatory JDBC lifecycle state for one Connection.
 *
 * Tracks only Statement-owned cursor ResultSet.
 *
 * Does NOT track arbitrary JDBC ResultSet such as:
 * - Statement.getGeneratedKeys()
 * - Array.getResultSet()
 * - DatabaseMetaData result sets
 */
public final class JdbcLifecycleManager
{
   private final JdbcObjectId.Generator ids =
           new JdbcObjectId.Generator();

   /*
    * Только зарегистрированные cursor ResultSet.
    *
    * Identity semantics принципиальны:
    * JDBC driver equals/hashCode здесь не используются.
    */
   private final Map<ResultSet, Long> cursorResultSets =
           new IdentityHashMap<>();


   /** */
   public long connectionId()
   {
      return ids.connectionId();
   }


   /**
    * Выдать следующий Statement ID
    * в рамках данного Connection.
    */
   public long nextStatementId()
   {
      return ids.nextStatementId();
   }


   /**
    * Регистрирует Statement-owned cursor ResultSet.
    *
    * Повторная регистрация того же raw ResultSet
    * для того же Statement идемпотентна.
    */
   public synchronized long registerCursorResultSet(
           ResultSet resultSet,
           long statementId
   )
   {
      if( resultSet == null )
      {
         throw new IllegalArgumentException(
                 "resultSet is null"
         );
      }

      if( !JdbcObjectId.isStatement(statementId) )
      {
         throw new IllegalArgumentException(
                 "Invalid statementId: "
                         + JdbcObjectId.toString(statementId)
         );
      }

      if( JdbcObjectId.connectionId(statementId)
              != connectionId() )
      {
         throw new IllegalArgumentException(
                 "Statement belongs to another connection: "
                         + JdbcObjectId.toString(statementId)
         );
      }

      Long currentId =
              cursorResultSets.get(resultSet);

      if( currentId != null )
      {
         /*
          * Один и тот же raw ResultSet не может
          * одновременно принадлежать разным Statement.
          */
         if( JdbcObjectId.statementId(
                 currentId.longValue()
         ) != statementId )
         {
            throw new IllegalStateException(
                    "Cursor ResultSet already registered "
                            + "for another statement: "
                            + JdbcObjectId.toString(
                            currentId.longValue()
                    )
            );
         }

         return currentId.longValue();
      }

      long resultSetId =
              ids.nextResultSetId(
                      statementId
              );

      cursorResultSets.put(
              resultSet,
              resultSetId
      );

      return resultSetId;
   }


   /**
    * Завершает lifecycle конкретного cursor ResultSet.
    *
    * Возвращает true только если именно эта регистрация
    * была реально удалена.
    *
    * Повторный unregister безопасен и возвращает false.
    */
   public synchronized boolean unregisterCursorResultSet(
           ResultSet resultSet,
           long expectedResultSetId
   )
   {
      if( resultSet == null )
         return false;

      if( !JdbcObjectId.isResultSet(expectedResultSetId) )
      {
         throw new IllegalArgumentException(
                 "Invalid resultSetId: "
                         + JdbcObjectId.toString(
                         expectedResultSetId
                 )
         );
      }

      Long currentId =
              cursorResultSets.get(resultSet);

      if( currentId == null )
         return false;

      /*
       * Удаляем только ожидаемую регистрацию.
       */
      if( currentId.longValue()
              != expectedResultSetId )
      {
         return false;
      }

      cursorResultSets.remove(
              resultSet
      );

      /*
       * ResultSet slot освобождаем только после
       * успешного удаления lifecycle registration.
       */
      ids.releaseResultSetId(
              expectedResultSetId
      );

      return true;
   }


   /**
    * Количество tracked cursor ResultSet,
    * открытых в рамках данного Connection.
    */
   public synchronized int openCursorCount()
   {
      return cursorResultSets.size();
   }


   /**
    * Есть ли хотя бы один tracked cursor.
    */
   public synchronized boolean hasOpenCursors()
   {
      return !cursorResultSets.isEmpty();
   }


   /**
    * Проверяет наличие конкретной lifecycle registration.
    */
   public synchronized boolean isCursorRegistered(
           ResultSet resultSet,
           long resultSetId
   )
   {
      if( resultSet == null )
         return false;

      Long currentId =
              cursorResultSets.get(resultSet);

      return currentId != null
              && currentId.longValue() == resultSetId;
   }
}