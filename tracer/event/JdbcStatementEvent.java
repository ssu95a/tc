package ru.inversion.tc.tracer.event;

public class JdbcStatementEvent extends JdbcEvent{
    /**
     * Constructs a prototypical Event.
     *
     * @param source   The object on which the Event initially occurred.
     * @param type
     * @param fireMode
     * @throws IllegalArgumentException if source is null.
     */
    public JdbcStatementEvent(Object source, EventType type, EventPhase fireMode) {
        super(source, type, fireMode);
    }
}
