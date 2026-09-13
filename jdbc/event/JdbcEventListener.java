package ru.inversion.tc.jdbc.event;

import java.util.EventListener;

/** Слушатель события JDBC объектов */
@FunctionalInterface
public interface JdbcEventListener<E extends JdbcEvent> extends EventListener {
   void onJdbcEvent(E event);
}