package ru.inversion.tc.tracer;

import ru.inversion.dataset.IParameters;
import ru.inversion.utils.lstn.IListenerManEvent;
import ru.inversion.utils.lstn.ListenerManFactory;

import java.util.Arrays;

import static ru.inversion.tc.tracer.QueryDBTraceTypeEnum.*;

/**
 *
 * @author ssu
 */
public class QueryDBTracer implements IQueryDBTracer {
    
    /** */
    private final boolean[] enableFlags = new boolean[QueryDBTraceTypeEnum.values().length];
    private boolean enable = true;

    final private IListenerManEvent<IQueryDBTraceListener,QueryDBTraceEvent> listeners = 
                                    ListenerManFactory.<IQueryDBTraceListener,QueryDBTraceEvent>createListenerManEvent( IQueryDBTraceListener::trace );
    
    //final private String sessionInfo;
    final private IParameters callbackParameters;
    /** */
    public QueryDBTracer( IParameters callbackParameters ) {

        this.callbackParameters = callbackParameters;
        
//        if( "enable".equalsIgnoreCase( System.getProperty("ru.inversion.QueryDBTracer.System.out"))  )
//            addListener( SystemOutTraceListener.instance() );
        
        addListener( LoggerTraceListener.instance() );

        Arrays.fill( enableFlags, true );
        
        enableFlags[DBMS_OUTPUT.ordinal()] = false;
        enableFlags[RAISE_DEBUG.ordinal()] = false;
    }

    /** */
    @Override
    public void setEnableTrace( QueryDBTraceTypeEnum objectType, boolean enable) {
        enableFlags[objectType.ordinal()] = enable;
        if( objectType == RAISE_DEBUG)
            listeners.fire( QueryDBTraceEvent.tech( this, RAISE_DEBUG.name(), enable ? "enable" : "disable" ) );
    }

    /** */
    @Override
    public boolean isEnableTrace(QueryDBTraceTypeEnum objectType) {
        return enableFlags[objectType.ordinal()];
    }
    
    /** */
    @Override
    public void addListener( IQueryDBTraceListener listener ) {
        
        listeners.addListener( listener );
        
        if( listener != null )
            listener.trace( QueryDBTraceEvent.info( this, null, (String)callbackParameters.getParameter("sessionInfo") ) );
    }

    /** */
    @Override
    public void removeListener(IQueryDBTraceListener listener) {
        listeners.removeListener(listener);
    }

    /** */
    @Override
    public boolean isEnable( ) {
        return enable && !listeners.isEmpty();
    }

    /** */
    @Override
    public void setEnable( boolean enable ) {
        this.enable = enable;
    }

    /** */
    @Override
    synchronized public void trace( QueryDBTraceEvent event ) {
        
        if( isEnable() ) {
            event.setProperty("sessionId", callbackParameters.getParameter("sessionId") );
            listeners.fire(event);
        }
    }
}
