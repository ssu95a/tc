package ru.inversion.tc.tracer.event;


public class JdbcConnectionEvent extends JdbcEvent {

    public JdbcConnectionEvent( Object source, EventType type, EventPhase phase ) {
        super( source, type, phase);
    }
}
