package ru.inversion.tc.tracer.event;

import ru.inversion.utils.lstn.IListenerManEvent;
import ru.inversion.utils.lstn.ListenerManFactory;

import java.util.function.Function;

/** */
public class JdbcEventMan {

    final private Function<String,Object> callbackParameters;


    private final boolean[] flagsEnabled = new boolean[JdbcEvent.EventType.values().length];

    private boolean enabled = true;

    final private IListenerManEvent<JdbcEventListener, JdbcEvent> listeners =
                  ListenerManFactory.createListenerManEvent( JdbcEventListener::onJdbcEvent);

    /** */
    public JdbcEventMan( Function<String, Object> callbackParameters ) {
        this.callbackParameters = callbackParameters;
    }

    /** */
    public boolean isEnabled( ) {
        return enabled;
    }

    /** */
    public void setEnabled( boolean v ) {
        this.enabled = v;
    }

    /**
     * Добавление нового listener'а
     */
    public void addListener( JdbcEventListener listener ) {

        listeners.addListener(listener);

        // Информация о сессии
        if( listener != null)
        {
            String sessionInfo = (String) callbackParameters.apply("sessionInfo");
            if( sessionInfo != null )
                ;//listener.onJdbcConnectionEvent( JdbcConnectionEvent.info( this, sessionInfo) );
        }
    }


    /**
     * Удаление listener'а
     */
    public void removeListener(JdbcEventListener listener) {
        listeners.removeListener(listener);
    }


    /**
     * Отправка нового события
     */
    public synchronized void fireEvent(JdbcEvent event) {

        if( !isEnabled() || listeners.isEmpty() )
            return;

//        // Добавляем sessionId в source если нужно
//        if (event.getSource() instanceof JdbcEventDispatcher) {
//            // Можно добавить свойства
//        }

        listeners.fire(event);
    }
}

