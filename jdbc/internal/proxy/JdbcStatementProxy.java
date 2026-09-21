package ru.inversion.tc.jdbc.internal.proxy;

import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.event.JdbcStatementEvent;
import ru.inversion.tc.jdbc.internal.lifecycle.JdbcLifecycleManager;
import ru.inversion.utils.Checks;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Statement;
import java.sql.Types;

import java.util.*;


/**
 * <h5>JDBC Statement / PreparedStatement / CallableStatement proxy.</h5>
 * <p>
 * Отслеживаем только связанные со Statement cursor ResultSet.
 */
public final class JdbcStatementProxy extends JdbcObjectProxy
{
   private static final String HIDDEN_VALUE     = "***";
   private static final String REF_CURSOR_VALUE = "<REF_CURSOR>";


   /**
    * Гнездо cursor-а.
    *
    * Обычный ResultSet:
    *
    *    parameterIndex == null
    *    resultSet      != null
    *    proxy          != null
    *
    * Pending OUT REF_CURSOR:
    *
    *    parameterIndex != null
    *    resultSet      == null
    *    proxy          == null
    *
    * Materialized OUT REF_CURSOR:
    *
    *    parameterIndex != null
    *    resultSet      != null
    *    proxy          != null
    */
   private static final class CursorSlot
   {
      private final Integer parameterIndex;

      private ResultSet resultSet;

      private JdbcResultSetProxy proxy;

      private CursorSlot( Integer parameterIndex ) {
         this.parameterIndex = parameterIndex;
      }

      /** */
      private boolean isPending()
      {
         return parameterIndex != null && resultSet == null;
      }
   }

   private final Statement statement;

   /* Proxy Connection, raw никогда не возвращаем. */
   private final JdbcConnectionProxy connection;

   /*
    * Исходный SQL текст:
    *
    * PreparedStatement / CallableStatement -> SQL
    * Statement                             -> null
    */
   private final String sql;

   private final boolean prepared;
   private final boolean callable;

   private final boolean traceIgnored;

   private boolean closingResultSets;

   /*
    * Последние успешно установленные positional IN parameters.
    */
   private final Map<Integer, Object> inParameters = new TreeMap<>();

   /*
    * OUT parameters CallableStatement.
    *
    * key   -> parameter index
    * value -> java.sql.Types value
    */
   private final Map<Integer, Integer> outParameters = new TreeMap<>();

   /* Все Statement-owned cursor ResultSet. */
   private final List<CursorSlot> cursorSlots =new ArrayList<>();
   /*
    * OUT REF_CURSOR slots текущего execute.
    *
    * До getObject(index) slot пустой.
    *
    * После getObject(index) тот же slot содержит
    * ResultSet + JdbcResultSetProxy.
    */
   private final Map<Integer, CursorSlot> outCursorSlots =
           new TreeMap<>();

   /* */
   private Statement proxy;

   /* */
   private boolean closed;

   /* */
   private boolean getMoreResultsMode;

   private final Set<Integer> hiddenParameters;


   /** */
   private JdbcStatementProxy(
           Statement statement,
           JdbcConnectionProxy connection,
           String sql,
           Set<Integer> hiddenParameters,
           JdbcLifecycleManager lifecycle,
           JdbcEventBus eventBus,
           boolean traceIgnored
   )
   {
      super( lifecycle, eventBus );

      this.statement = Checks.Require.object( statement, "statement" );
      this.connection= Checks.Require.object( connection, "connection" );

      this.sql = sql;

      this.prepared =
              statement instanceof PreparedStatement;

      this.callable =
              statement instanceof CallableStatement;

      this.hiddenParameters =
              hiddenParameters == null
                      || hiddenParameters.isEmpty()
                      ? Collections.emptySet()
                      : Collections.unmodifiableSet(
                      new TreeSet<>(hiddenParameters)
              );

      this.traceIgnored =
              traceIgnored;
   }


   /** */
   boolean isTraceIgnored()
   {
      return traceIgnored;
   }


   /** */
   static JdbcStatementProxy create(
           Statement statement,
           JdbcConnectionProxy connection,
           String sql,
           Set<Integer> hiddenParameters,
           boolean traceIgnored,
           JdbcLifecycleManager lifecycle,
           JdbcEventBus eventBus
   )
   {
      JdbcStatementProxy handler =
              new JdbcStatementProxy(
                      statement,
                      connection,
                      sql,
                      hiddenParameters,
                      lifecycle,
                      eventBus,
                      traceIgnored
              );

      Class<?> jdbcInterface;

      if( statement instanceof CallableStatement )
      {
         jdbcInterface =
                 CallableStatement.class;
      }
      else if( statement instanceof PreparedStatement )
      {
         jdbcInterface =
                 PreparedStatement.class;
      }
      else
      {
         jdbcInterface =
                 Statement.class;
      }

      handler.proxy =
              (Statement) Proxy.newProxyInstance(
                      JdbcStatementProxy.class.getClassLoader(),
                      new Class<?>[] { jdbcInterface },
                      handler
              );

      /*
       * fireOpen() здесь НЕ вызываем.
       *
       * Сначала ConnectionProxy должен
       * зарегистрировать Statement у себя.
       */
      return handler;
   }


   /** */
   Statement proxy()
   {
      return proxy;
   }


   /** */
   Statement raw()
   {
      return statement;
   }


   /** */
   boolean isLifecycleClosed()
   {
      return closed;
   }


   /**
    * Есть OUT REF_CURSOR, который ещё не был
    * получен application через getObject(index).
    *
    * JdbcConnectionProxy использует это вместе с
    * lifecycle.hasOpenCursors() перед auto-finish.
    */
   boolean hasPendingOutCursors()
   {
      for( CursorSlot slot : cursorSlots )
      {
         if( slot.isPending() )
            return true;
      }

      return false;
   }

   /** */
   @Override
   public Object invoke(
           Object proxy,
           Method method,
           Object[] args
   )
           throws Throwable
   {
      String methodName =
              method.getName();

      /*
       * Object methods.
       */
      if( Object.class.equals(
              method.getDeclaringClass()
      ))
      {
         return invokeObjectMethod(
                 proxy,
                 methodName,
                 args
         );
      }

      /*
       * Statement.close().
       */
      if( "close".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         close();

         return null;
      }

      /*
       * Statement.isClosed().
       */
      if( "isClosed".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         return isClosed();
      }

      /*
       * Никогда не выпускаем raw Connection.
       */
      if( "getConnection".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         return connection.proxy();
      }

      /*
       * Current Statement ResultSet.
       */
      if( "getResultSet".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         ResultSet raw =
                 (ResultSet) invokeRaw(
                         method,
                         args
                 );

         return wrapCursorResultSet(raw);
      }

      /*
       * Generated keys НЕ считаем cursor lifecycle.
       */
      if( "getGeneratedKeys".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         return invokeRaw(
                 method,
                 args
         );
      }

      /*
       * getMoreResults()
       * getMoreResults(int)
       */
      if( "getMoreResults".equals(methodName) )
      {
         return getMoreResults(
                 method,
                 args
         );
      }

      /*
       * unwrap(proxy-compatible interface)
       * оставляет клиента внутри proxy.
       */
      if( "unwrap".equals(methodName)
              && args != null
              && args.length == 1 )
      {
         Class<?> clazz =
                 (Class<?>) args[0];

         if( clazz.isInstance(proxy) )
            return clazz.cast(proxy);
      }

      if( "isWrapperFor".equals(methodName)
              && args != null
              && args.length == 1 )
      {
         Class<?> clazz =
                 (Class<?>) args[0];

         if( clazz.isInstance(proxy) )
            return true;
      }

      /*
       * PreparedStatement.setXXX(int,...)
       */
      if( isParameterSetter(
              method,
              args
      ))
      {
         return setParameter(
                 method,
                 args
         );
      }

      /*
       * PreparedStatement.clearParameters().
       */
      if( prepared
              && "clearParameters".equals(methodName)
              && method.getParameterTypes().length == 0 )
      {
         Object value =
                 invokeRaw(
                         method,
                         args
                 );

         inParameters.clear();

         /*
          * OUT registrations clearParameters()
          * не снимает.
          */
         return value;
      }

      /*
       * CallableStatement.registerOutParameter(int,...)
       *
       * Named OUT parameters пока просто делегируются.
       */
      if( callable
              && "registerOutParameter".equals(methodName)
              && args != null
              && args.length > 0
              && args[0] instanceof Integer )
      {
         Object value =
                 invokeRaw(
                         method,
                         args
                 );

         int index =
                 (Integer) args[0];

         outParameters.put(
                 index,
                 outSqlType(args)
         );

         return value;
      }

      /*
       * CallableStatement.getObject(int,...)
       * только для зарегистрированного REF_CURSOR.
       */
      if( isOutCursorGetter(
              method,
              args
      ))
      {
         return getOutCursor(
                 method,
                 args
         );
      }

      /*
       * execute()
       * executeQuery()
       * executeUpdate()
       * executeBatch()
       * executeLargeUpdate()
       * executeLargeBatch()
       * ...
       */
      if( methodName.startsWith("execute") )
      {
         return execute(
                 method,
                 args
         );
      }

      return invokeRaw(
              method,
              args
      );
   }


   /**
    * execute* operation.
    */
   private Object execute(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      String methodName =
              method.getName();

      String executedSql =
              sql(args);

      fireBeforeExecute(
              methodName,
              executedSql
      );

      /*
       * До raw execute создаём пустые гнёзда OUT REF_CURSOR.
       *
       * Именно наличие pending slot запрещает
       * auto-finish transaction.
       *
       * Batch OUT параметры не возвращает.
       */
      if( callable
              && !isBatchExecute(methodName) )
      {
         prepareOutCursorSlots();
      }

      long started =
              System.nanoTime();

      Object value;

      try
      {
         value =
                 invokeRaw(
                         method,
                         args
                 );
      }
      catch( Throwable throwable )
      {
         long duration =
                 System.nanoTime() - started;

         /*
          * PostgreSQL SQL ERROR переводит transaction
          * в aborted state.
          *
          * Recovery должен произойти ДО ERROR event,
          * иначе server-output listeners сами могут
          * получить 25P02.
          *
          * Для Oracle/default policy это no-op.
          */
         connection.statementExecutionFailed(
                 throwable
         );

         /*
          * Driver мог закрыть предыдущий current cursor
          * даже при ошибке execute.
          */
         syncCursorResultSets();

         /*
          * Statement также мог оказаться закрыт.
          */
         syncClosedState();

         fireExecuteError(
                 methodName,
                 executedSql,
                 duration,
                 throwable
         );

         throw throwable;
      }

      long duration =
              System.nanoTime() - started;

      /*
       * Новый обычный cursor регистрируем
       * ДО reconcile старых.
       *
       * OUT REF_CURSOR здесь не читаем!
       */
      CursorSlot newCursor = null;

      if( value instanceof ResultSet )
      {
         /*
          * executeQuery().
          */
         newCursor =
                 registerCursorResultSet(
                         (ResultSet) value
                 );
      }
      else if( Boolean.TRUE.equals(value) )
      {
         /*
          * Statement.execute() == true.
          */
         ResultSet current =
                 statement.getResultSet();

         newCursor =
                 registerCursorResultSet(
                         current
                 );
      }

      /*
       * Теперь безопасно снять закрытые старые cursors.
       */
      syncCursorResultSets();

      /*
       * Синхронизируем факт закрытия Statement.
       */
      syncClosedState();

      /*
       * ВАЖНО:
       * outParameterValues() REF_CURSOR НЕ читает.
       */
      fireAfterExecute(
              methodName,
              executedSql,
              duration
      );

      if( newCursor != null )
      {
         newCursor.proxy.fireOpen();
      }
      else if( !"execute".equals(methodName)
              || (Boolean.FALSE.equals(value)
              && statement.getUpdateCount() == -1) )
      {
         /*
          * Если есть pending REF_CURSOR,
          * ConnectionProxy увидит его через
          * hasPendingOutCursors()
          * и auto-commit не произойдёт.
          */
         connection.transactionStateChanged();
      }

      /*
       * executeQuery() наружу возвращает proxy.
       */
      if( value instanceof ResultSet
              && newCursor != null )
      {
         return newCursor.proxy.proxy();
      }

      return value;
   }


   /**
    * Statement.getMoreResults()
    * Statement.getMoreResults(int)
    */
   private Object getMoreResults(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      getMoreResultsMode = true;

      try
      {
         Object value =
                 invokeRaw(
                         method,
                         args
                 );

         CursorSlot newCursor = null;

         if( Boolean.TRUE.equals(value) )
         {
            ResultSet current =
                    statement.getResultSet();

            newCursor =
                    registerCursorResultSet(
                            current
                    );
         }

         syncCursorResultSets();

         syncClosedState();

         if( newCursor != null )
            newCursor.proxy.fireOpen();

         /*
          * false + updateCount == -1
          * означает конец result chain.
          */
         if( !Boolean.TRUE.equals(value)
                 && statement.getUpdateCount() == -1 )
         {
            getMoreResultsMode = false;

            connection.transactionStateChanged();
         }

         return value;
      }
      finally
      {
         getMoreResultsMode = false;
      }
   }


   /**
    * PreparedStatement.setXXX(int,...).
    */
   private Object setParameter(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      /*
       * Сначала driver.
       *
       * Если setXXX выбросил SQLException,
       * parameter state не меняем.
       */
      Object value =
              invokeRaw(
                      method,
                      args
              );

      int index =
              (Integer) args[0];

      if( "setNull".equals(method.getName()) )
      {
         inParameters.put(
                 index,
                 null
         );
      }
      else if( hiddenParameters.contains(index) )
      {
         inParameters.put(
                 index,
                 HIDDEN_VALUE
         );
      }
      else
      {
         inParameters.put(
                 index,
                 args.length > 1
                         ? args[1]
                         : null
         );
      }

      return value;
   }


   /**
    * Определяет positional PreparedStatement setter
    * без ручного списка SET_METHODS.
    */
   private static boolean isParameterSetter( Method method, Object[] args )
   {
      if( args == null || args.length == 0 )
         return false;

      if( !(args[0] instanceof Integer) )
         return false;

      if( !method.getName().startsWith("set") )
         return false;

      /*
       * Statement.setFetchSize(),
       * Statement.setMaxRows(),
       * Statement.setQueryTimeout()
       *
       * сюда не проходят.
       */
      return PreparedStatement.class.isAssignableFrom( method.getDeclaringClass() );
   }


   /**
    * SQL type из registerOutParameter(int,...).
    */
   private static Integer outSqlType(
           Object[] args
   )
   {
      if( args == null
              || args.length < 2 )
      {
         return null;
      }

      Object type =
              args[1];

      if( type instanceof Integer )
         return (Integer) type;

      /*
       * JDBC 4.2 overload.
       */
      if( type instanceof SQLType )
      {
         return ((SQLType) type)
                 .getVendorTypeNumber();
      }

      return null;
   }


   /** */
   private static boolean isRefCursorType(
           Integer sqlType
   )
   {
      return sqlType != null
              && sqlType == Types.REF_CURSOR;
   }


   /**
    * getObject(int)
    * getObject(int, Class)
    *
    * но только если positional OUT parameter
    * зарегистрирован как REF_CURSOR.
    */
   private boolean isOutCursorGetter(
           Method method,
           Object[] args
   )
   {
      if( !callable )
         return false;

      if( !"getObject".equals(method.getName()) )
         return false;

      if( args == null
              || args.length == 0
              || !(args[0] instanceof Integer) )
      {
         return false;
      }

      Integer sqlType =
              outParameters.get(
                      (Integer) args[0]
              );

      return isRefCursorType(
              sqlType
      );
   }


   /** */
   private static boolean isBatchExecute(
           String methodName
   )
   {
      return "executeBatch".equals(methodName)
              || "executeLargeBatch".equals(methodName);
   }


   /**
    * Перед новым execute создаём пустые
    * slots для зарегистрированных OUT REF_CURSOR.
    *
    * Старые materialized cursor ResultSet продолжают
    * жить в cursorResultSets независимо от нового execute.
    */
   private void prepareOutCursorSlots()
   {
      removePendingOutCursorSlots();

      for( Map.Entry<Integer, Integer> entry
              : outParameters.entrySet() )
      {
         if( isRefCursorType(entry.getValue()) )
         {
            cursorSlots.add(
                    new CursorSlot(entry.getKey())
            );
         }
      }
   }

   private CursorSlot findOutCursorSlot( int index )
   {
      for( CursorSlot slot : cursorSlots )
      {
         if( slot.parameterIndex != null
                 && slot.parameterIndex == index
                 && slot.isPending() )
         {
            return slot;
         }
      }

      return null;
   }

   private CursorSlot findCursorSlot(
           ResultSet resultSet
   )
   {
      for( CursorSlot slot : cursorSlots )
      {
         if( slot.resultSet == resultSet )
            return slot;
      }

      return null;
   }


   /**
    * Execute закончился ошибкой.
    *
    * Все новые OUT slots ещё пустые,
    * поэтому просто забываем их.
    */
   private void discardPendingOutCursorSlots()
   {
      if( outCursorSlots.isEmpty() )
         return;

      ArrayList<Integer> remove =
              new ArrayList<>();

      for( Map.Entry<Integer, CursorSlot> entry
              : outCursorSlots.entrySet() )
      {
         if( entry.getValue().isPending() )
            remove.add(entry.getKey());
      }

      for( Integer index : remove )
         outCursorSlots.remove(index);
   }


   /**
    * CallableStatement.getObject(index)
    * для OUT REF_CURSOR.
    */
   private Object getOutCursor(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      int index =
              (Integer) args[0];

      /*
       * Только application вызывает реальный getObject().
       *
       * Tracer REF_CURSOR никогда не читает.
       */
      Object value =
              invokeRaw(
                      method,
                      args
              );

      CursorSlot slot =
              outCursorSlots.get(index);

      /*
       * Slot отсутствует:
       *
       * - getObject до execute;
       * - повторный getObject;
       * - другой нестандартный JDBC сценарий.
       *
       * Если driver всё же вернул ResultSet,
       * ведём его как обычный cursor.
       */
      if( slot == null )
      {
         if( value instanceof ResultSet )
         {
            return wrapCursorResultSet(
                    (ResultSet) value
            );
         }

         return value;
      }

      /*
       * Slot уже материализован.
       *
       * wrapCursorResultSet() по identity найдёт
       * существующий proxy, если driver вернул
       * тот же raw ResultSet.
       */
      if( !slot.isPending() )
      {
         if( value instanceof ResultSet )
         {
            return wrapCursorResultSet(
                    (ResultSet) value
            );
         }

         return value;
      }

      /*
       * REF_CURSOR реально вернулся.
       */
      if( value instanceof ResultSet )
      {
         ResultSet resultSet = (ResultSet) value;

         /*
          * Теоретически raw ResultSet уже мог быть зарегистрирован другим JDBC path.
          */
         CursorSlot current = findCursorSlot( resultSet );

         if( current != null && current.proxy != null && !current.proxy.isLifecycleClosed() )
         {
            outCursorSlots.remove(index);

            current.proxy.fireOpen();

            return current.proxy.proxy();
         }

         CursorSlot materialized = fillCursorSlot( slot, resultSet );

         outCursorSlots.remove(index);

         if( materialized == null )
         {
            connection.transactionStateChanged();
            return value;
         }

         materialized.proxy.fireOpen();

         return materialized.proxy.proxy();
      }

      /*
       * Зарегистрированный REF_CURSOR реально
       * вернул null / не ResultSet.
       *
       * Pending slot больше не должен держать
       * transaction открытой.
       */
      if( outCursorSlots.get(index) == slot )
         outCursorSlots.remove(index);

      connection.transactionStateChanged();

      return value;
   }


   /**
    * Пользовательский Statement.getResultSet().
    */
   private ResultSet wrapCursorResultSet(
           ResultSet resultSet
   )
   {
      if( resultSet == null )
         return null;

      CursorSlot slot =
              registerCursorResultSet(
                      resultSet
              );

      /*
       * Driver уже считает ResultSet закрытым.
       */
      if( slot == null )
         return resultSet;

      slot.proxy.fireOpen();

      return slot.proxy.proxy();
   }


   /**
    * Регистрирует обычный Statement-owned ResultSet.
    *
    * OPEN event здесь НЕ отправляется.
    */
   private CursorSlot registerCursorResultSet(
           ResultSet resultSet
   )
   {
      CursorSlot current =
              findCursorSlot(resultSet);

      if( current != null )
         return current;

      if( isRawResultSetClosed(resultSet) )
         return null;

      CursorSlot slot = new CursorSlot(null);

      cursorSlots.add(slot);

      return fillCursorSlot( slot, resultSet );
   }

   /**
    * Заполнение CursorSlot.
    * <p>
    * Здесь JdbcResultSetProxy создаётся обычным
    * существующим способом и сам регистрирует
    * ResultSet в JdbcLifecycleManager.
    */
   private CursorSlot fillCursorSlot(CursorSlot slot, ResultSet resultSet )
   {
      if( slot == null )
         throw new IllegalArgumentException( "slot is null" );

      if( resultSet == null )
         throw new IllegalArgumentException("resultSet is null");

      if( !slot.isPending() && slot.parameterIndex != null )
          throw new IllegalStateException( "CursorSlot already materialized" );

      if( isRawResultSetClosed(resultSet) )
         return null;

      JdbcResultSetProxy handler =
              JdbcResultSetProxy.create(
                      resultSet,
                      this,
                      lifecycle,
                      eventBus
              );
      /*
       * JdbcResultSetProxy уже зарегистрировал
       * cursor в lifecycle.
       *
       * Только теперь pending slot становится заполненным
       */
      slot.resultSet = resultSet;
      slot.proxy     = handler;

      cursorResultSets.put(resultSet, slot );

      return slot;
   }


   /**
    * ResultSetProxy сообщает owner-у,
    * что его lifecycle закончился.
    *
    * Identity slot/proxy защищает от stale handler.
    */
   void cursorResultSetClosed(
           JdbcResultSetProxy resultSet
   )
   {
      CursorSlot current = findCursorSlot( resultSet.raw() );

      if( current == null || current.proxy != resultSet )
      {
         return;
      }

      cursorSlots.remove( resultSet.raw() );

      /*
       * Если это OUT REF_CURSOR,
       * убираем также lookup по parameter index.
       */
      if( current.parameterIndex != null )
      {
         CursorSlot byIndex =
                 outCursorSlots.get(
                         current.parameterIndex
                 );

         if( byIndex == current )
         {
            outCursorSlots.remove(
                    current.parameterIndex
            );
         }
      }
   }


   /**
    * Синхронизирует ResultSet, которые driver закрыл
    * без вызова нашего ResultSetProxy.close().
    */
   private void syncCursorResultSets()
   {
      if( cursorResultSets.isEmpty() )
         return;

      ArrayList<CursorSlot> snapshot =
              new ArrayList<>(
                      cursorResultSets.values()
              );

      for( CursorSlot slot : snapshot )
      {
         JdbcResultSetProxy resultSet =
                 slot.proxy;

         if( resultSet == null )
            continue;

         if( resultSet.isLifecycleClosed() )
         {
            cursorResultSetClosed(
                    resultSet
            );

            continue;
         }

         if( isRawResultSetClosed(
                 slot.resultSet
         ))
         {
            resultSet.closedByStatement();
         }
      }
   }


   /**
    * Statement закрывается.
    *
    * Materialized ResultSet проходят обычный
    * JdbcResultSetProxy lifecycle.
    *
    * Pending OUT slots просто исчезают:
    * real ResultSet для них ещё не существовало.
    */
   private void closeCursorSlots()
   {
      ArrayList<CursorSlot> snapshot =
              cursorResultSets.isEmpty()
                      ? new ArrayList<>()
                      : new ArrayList<>(
                      cursorResultSets.values()
              );

      cursorResultSets.clear();

      /*
       * Здесь исчезают и pending slots,
       * и дополнительный index lookup
       * materialized OUT cursor-ов.
       */
      outCursorSlots.clear();

      for( CursorSlot slot : snapshot )
      {
         if( slot.proxy != null )
            slot.proxy.closedByStatement();
      }
   }


   /**
    * Явный Statement.close().
    */
   private void close()
           throws Throwable
   {
      if( closed )
         return;

      try
      {
         statement.close();
      }
      catch( Throwable throwable )
      {
         /*
          * close() мог закрыть часть ResultSet
          * и затем выбросить SQLException.
          */
         syncCursorResultSets();

         /*
          * Statement мог физически закрыться.
          */
         if( isRawStatementClosed() )
            statementClosed();

         throw throwable;
      }

      statementClosed();
   }


   /**
    * Точка завершения Statement lifecycle proxy.
    */
   private void statementClosed()
   {
      if( closed )
         return;

      closed = true;

      closingResultSets = true;

      try
      {
         closeCursorSlots();

         connection.statementClosed(this);

         fireClose();
      }
      finally
      {
         closingResultSets = false;
      }

      connection.transactionStateChanged();
   }


   /**
    * Statement.isClosed().
    */
   private boolean isClosed()
           throws SQLException
   {
      if( closed )
         return true;

      boolean rawClosed =
              statement.isClosed();

      if( rawClosed )
         statementClosed();

      return rawClosed;
   }


   /**
    * Синхронизация фактического состояния
    * raw Statement.
    */
   void syncClosedState()
   {
      if( closed )
         return;

      if( isRawStatementClosed() )
         statementClosed();
   }


   /**
    * Полное завершение transaction:
    *
    * - COMMIT
    * - ROLLBACK
    * - setAutoCommit(true)
    *
    * Pending REF_CURSOR больше не существует.
    *
    * Materialized ResultSet синхронизируем
    * по фактическому состоянию driver-а.
    */
   void transactionCompleted()
   {
      if( closed )
          return;

      removePendingOutCursorSlots();

      syncCursorResultSets();

      syncClosedState();
   }


   private void removePendingOutCursorSlots()
   {
      for( Iterator<CursorSlot> it = cursorSlots.iterator(); it.hasNext(); )
      {
         if( it.next().isPending() )
             it.remove();
      }
   }

   /** */
   private boolean isRawStatementClosed()
   {
      try
      {
         return statement.isClosed();
      }
      catch( SQLException ignored )
      {
         /*
          * Считаем Statement открытым.
          */
         return false;
      }
   }


   /** */
   private static boolean isRawResultSetClosed( ResultSet resultSet )
   {
      try {
         return resultSet.isClosed();
      }
      catch( SQLException ignored ) {
         /*
          * Считаем ResultSet открытым.
          */
         return false;
      }
   }


   /**
    * SQL конкретного execute*().
    */
   private String sql( Object[] args )
   {
      /*
       * Statement.execute*(String,...)
       */
      if( args != null && args.length > 0 && args[0] instanceof String )
      {
         return (String) args[0];
      }

      /*
       * PreparedStatement / CallableStatement.
       */
      return sql;
   }


   /**
    * OUT values собираются только для trace.
    *
    * КРИТИЧНО:
    *
    * REF_CURSOR здесь НИКОГДА не читаем
    * через CallableStatement.getObject().
    */
   private Map<Integer, Object> outParameterValues()
   {
      if( !callable
              || outParameters.isEmpty() )
      {
         return Collections.emptyMap();
      }

      Map<Integer, Object> result =
              new TreeMap<>();

      CallableStatement callableStatement =
              (CallableStatement) statement;

      for( Map.Entry<Integer, Integer> entry
              : outParameters.entrySet() )
      {
         Integer index =
                 entry.getKey();

         if( hiddenParameters.contains(index) )
         {
            result.put(
                    index,
                    HIDDEN_VALUE
            );

            continue;
         }

         if( isRefCursorType(
                 entry.getValue()
         ))
         {
            result.put(
                    index,
                    REF_CURSOR_VALUE
            );

            continue;
         }

         try
         {
            result.put(
                    index,
                    callableStatement.getObject(index)
            );
         }
         catch( SQLException ignored )
         {
            /*
             * Diagnostics не должны ломать
             * успешный execute.
             */
         }
      }

      return result;
   }


   /** */
   private Object invokeRaw(
           Method method,
           Object[] args
   )
           throws Throwable
   {
      try
      {
         return method.invoke(
                 statement,
                 args
         );
      }
      catch( InvocationTargetException ex )
      {
         throw ex.getCause();
      }
   }


   /** */
   private boolean hasStatementListeners()
   {
      return eventBus != null
              && eventBus.hasListeners(
              JdbcStatementEvent.class
      );
   }


   /** */
   void fireOpen()
   {
      if( !hasStatementListeners() )
         return;

      eventBus.fire(
              JdbcStatementEvent.open(
                      proxy,
                      sql,
                      traceIgnored
              )
      );
   }


   /** */
   private void fireBeforeExecute(
           String methodName,
           String sql
   )
   {
      if( !hasStatementListeners() )
         return;

      eventBus.fire(
              JdbcStatementEvent.beforeExecute(
                      proxy,
                      methodName,
                      sql,
                      inParameters,
                      traceIgnored
              )
      );
   }


   /** */
   private void fireAfterExecute(
           String methodName,
           String sql,
           long durationNanos
   )
   {
      if( !hasStatementListeners() )
         return;

      Map<Integer, Object> out =
              outParameterValues();

      eventBus.fire(
              JdbcStatementEvent.afterExecute(
                      proxy,
                      methodName,
                      sql,
                      inParameters,
                      out,
                      durationNanos,
                      traceIgnored
              )
      );
   }


   /** */
   private void fireExecuteError(
           String methodName,
           String sql,
           long durationNanos,
           Throwable throwable
   )
   {
      if( !hasStatementListeners() )
         return;

      eventBus.fire(
              JdbcStatementEvent.executeError(
                      proxy,
                      methodName,
                      sql,
                      inParameters,
                      durationNanos,
                      throwable,
                      traceIgnored
              )
      );
   }


   /** */
   private void fireClose()
   {
      if( !hasStatementListeners() )
         return;

      eventBus.fire(
              JdbcStatementEvent.close(
                      proxy,
                      sql,
                      traceIgnored
              )
      );
   }


   /** */
   void syncConnectionState()
   {
      if( closed )
         return;

      syncCursorResultSets();

      syncClosedState();
   }


   /** */
   void closedByConnection()
   {
      if( closed )
         return;

      statementClosed();
   }


   /** */
   void cursorStateChanged()
   {
      /*
       * Во время Statement.close()
       * ResultSet-ы закрываются пачкой.
       */
      if( closingResultSets
              || getMoreResultsMode )
      {
         return;
      }

      connection.transactionStateChanged();
   }
}