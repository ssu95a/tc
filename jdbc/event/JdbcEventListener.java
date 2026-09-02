package ru.inversion.tc.jdbc.event;

import java.util.EventListener;

@FunctionalInterface
public interface JdbcEventListener<E extends JdbcEvent> extends EventListener {
   void onJdbcEvent(E event);
}