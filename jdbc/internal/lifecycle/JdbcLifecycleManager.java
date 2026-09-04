package ru.inversion.tc.jdbc.internal.lifecycle;

import ru.inversion.tc.jdbc.internal.JdbcObjectId;

import java.sql.ResultSet;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Mandatory JDBC lifecycle state for one Connection.
 */
public final class JdbcLifecycleManager
{

   private final JdbcObjectId.Generator ids = new JdbcObjectId.Generator();

   /*
    * Только реально зарегистрированные открытые ResultSet.
    *
    * Identity semantics принципиальны.
    */
   private final Map<ResultSet, Long> resultSets = new IdentityHashMap<>();


   /** */
   public long connectionId()
   {
      return ids.connectionId();
   }


   /** */
   public long nextStatementId()
   {
      return ids.nextStatementId();
   }


   /**
    * Регистрирует ResultSet.
    * <p>
    * Повторная регистрация того же raw ResultSet идемпотентна.
    */
   public synchronized long registerResultSet( ResultSet resultSet, long statementId )
   {
      if( resultSet == null )
          throw new IllegalArgumentException( "resultSet is null" );

      if( !JdbcObjectId.isStatement(statementId) )
          throw new IllegalArgumentException( "Invalid statementId: " + JdbcObjectId.toString(statementId) );

      if( JdbcObjectId.connectionId(statementId) != connectionId() )
          throw new IllegalArgumentException( "Statement belongs to another connection: " + JdbcObjectId.toString(statementId) );

      Long currentId = resultSets.get( resultSet );

      if( currentId != null )
      {
         /*
          * Один raw ResultSet не может внезапно
          * поменять Statement-владельца.
          */
         if( JdbcObjectId.statementId(currentId) != statementId )
            throw new IllegalStateException( "ResultSet already registered for another statement" );

         return currentId;
      }

      long resultSetId = ids.nextResultSetId(statementId);

      resultSets.put( resultSet, resultSetId );

      return resultSetId;
   }


   /**
    * Снимает конкретную регистрацию.
    * <p>
    * <code>expectedResultSetId</code> здесь принципиален.
    */
   public synchronized boolean unregisterResultSet( ResultSet resultSet, long expectedResultSetId )
   {
      if( resultSet == null )
          return false;

      if( !JdbcObjectId.isResultSet(expectedResultSetId) )
          throw new IllegalArgumentException( "Invalid resultSetId: " + JdbcObjectId.toString(expectedResultSetId) );

      Long currentId = resultSets.get(resultSet);

      if( currentId == null )
          return false;

      /*
       * Защита от stale proxy.
       *
       * R#1 уже мог быть освобождён и выдан
       * другому ResultSet.
       */
      if( currentId.longValue() != expectedResultSetId )
          return false;

      resultSets.remove(resultSet);

      ids.releaseResultSetId( expectedResultSetId );

      return true;
   }


   /** */
   public synchronized int openResultSetCount()
   {
      return resultSets.size();
   }


   /** */
   public synchronized boolean hasOpenResultSets()
   {
      return !resultSets.isEmpty();
   }


   /**
    * Проверка конкретной регистрации.
    */
   public synchronized boolean isRegistered( ResultSet resultSet, long resultSetId )
   {
      if( resultSet == null )
         return false;

      Long currentId = resultSets.get(resultSet);

      return currentId != null && currentId == resultSetId;
   }
}