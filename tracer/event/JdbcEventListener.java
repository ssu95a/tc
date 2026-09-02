package ru.inversion.tc.tracer.event;

import java.util.EventListener;

/**
 * Слушатель событий JDBC Connection.
 * Отслеживает все операции, выполняемые через Connection.
 */
@FunctionalInterface
public interface JdbcEventListener extends EventListener {

    /**
     * Обработка события JDBC Connection
     * @param event событие соединения
     */
    void onJdbcEvent(JdbcEvent event);

}