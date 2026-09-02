package ru.inversion.tc.tracer.event;

import java.sql.Connection;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EventObject;

/** */
public class JdbcEvent extends EventObject {

    /** Типы statement для события Statement_Execute */
    public enum StatementType {
        Statement,           // Обычный Statement
        PreparedStatement,   // PreparedStatement
        CallableStatement,   // CallableStatement (для хранимых процедур)
        Batch                // Batch execution
    }

    /** Тип операции выполнения */
    public enum ExecuteType {
        Execute,           // execute()
        ExecuteQuery,      // executeQuery()
        ExecuteUpdate,     // executeUpdate()
        ExecuteBatch       // executeBatch()
    }

    /** Виды срабатывания */
    public enum EventPhase {
        Before, // Перед действием
        After,  // После Действия
        Error,  // При ошибке во время действия
        On      // Во время действия, там где не поддерживается режим Before и After - DURING
    }

    /*
    public enum EventCategory {
        Connection,
        Statement,
        Transaction,
        Dbms,
        System
    }*/


    public enum EventProperty {

        // === CONNECTION STATE ===
        AUTO_COMMIT,            // boolean
        READ_ONLY,              // boolean
        ISOLATION_LEVEL,        // int
        CATALOG,                // String
        SCHEMA,                 // String
        HOLDABILITY,            // int
        NETWORK_TIMEOUT,        // int (ms)

        // === COMMON ===
        SQL,                    // String
        DURATION,               // Duration
        ERROR,                  // Throwable

        // === STATEMENT ===
        STATEMENT_TYPE,         // StatementType
        EXECUTE_TYPE,           // ExecuteType
        PARAMETERS,             // List<Object> | Map<Integer,Object>
        BATCH_SIZE,             // int
        GENERATED_KEYS,         // boolean

        ROW_COUNT,              // int
        UPDATE_COUNTS,          // int[]
        FETCH_SIZE,             // int

//        // === TRANSACTION ===
//        AUTO_COMMIT,            // boolean
//        ISOLATION_LEVEL,        // int
        SAVEPOINT_NAME,         // String

        // === DBMS ===
        DBMS_LEVEL,             // INFO/WARN/ERROR
        DBMS_MESSAGE,           // String

        // === SYSTEM ===
        MESSAGE                 // String
    }

    /** Типы сообщения */
    public enum EventType {

// ===============================
        // STATEMENT
        // ===============================

        StatementExecute,
        // properties:
        //  - sql
        //  - parameters
        //  - statementType (Statement / Prepared / Callable / Batch)
        //  - executeType   (Execute / ExecuteQuery / ExecuteUpdate / ExecuteBatch)

        // ===============================
        // CONNECTION LIFECYCLE
        // ===============================

        ConnectionCreated,
        ConnectionClosed,
        // properties:
        //  - reason (normal / error / timeout)
        //  - throwable (optional)

        // ===============================
        // TRANSACTIONS
        // ===============================

        TransactionBegin,
        TransactionCommit,
        TransactionRollback,

        SavepointSet,
        SavepointRollbackTo,

        // ===============================
        // CONNECTION STATE
        // ===============================

        ConnectionChangeState,
        // properties:
        //  - autoCommit
        //  - readOnly
        //  - isolation

        // ===============================
        // DB / DIAGNOSTIC OUTPUT
        // ===============================

        DbmsOutput,
        RaiseDebug,

        // ===============================
        // GENERIC
        // ===============================

        Info,
        Tech
    }

    final private EventType type;
    final private EventPhase fireMode;
    final private Throwable throwable;

    private Duration duration;

    private final EnumMap<EventProperty, Object> properties = new EnumMap<>(EventProperty.class);
    /**
     * Constructs a prototypical Event.
     *
     * @param source The object on which the Event initially occurred.
     * @throws IllegalArgumentException if source is null.
     */
    public JdbcEvent(Object source, EventType type, EventPhase fireMode ) {
        super( source );
        this.type     = type;
        this.fireMode = fireMode;
        this.throwable= null;
    }

    /** */
    public EventType type() {
        return type;
    }

    /** */
    public EventPhase phase() {
        return fireMode;
    }


}
