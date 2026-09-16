package ru.inversion.tc.jdbc.trace;


/**
 * <h5>Слушатель Trace</h5>
 * <p>
 * Не является listener-ом JDBC core.
 * <p>
 * JdbcTracer получает JdbcEvent, преобразует его в JdbcTraceEvent и отправляет JdbcTraceListener-ам.
 */
@FunctionalInterface
public interface JdbcTraceListener {
   void onJdbcTrace( JdbcTraceEvent event );
}