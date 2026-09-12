package ru.inversion.tc.jdbc.trace;


/**
 * Listener tracing layer.
 *
 * Не является listener-ом JDBC core.
 *
 * JdbcTracer получает low-level JdbcEvent,
 * преобразует его в JdbcTraceEvent
 * и отправляет JdbcTraceListener-ам.
 */
@FunctionalInterface
public interface JdbcTraceListener
{
   void onJdbcTrace(
           JdbcTraceEvent event
   );
}