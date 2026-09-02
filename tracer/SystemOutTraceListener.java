package ru.inversion.tc.tracer;

import java.io.PrintWriter;

/**
 *
 * @author ssu
 */
public class SystemOutTraceListener implements IQueryDBTraceListener {
    /** */
    final private static SystemOutTraceListener instance = new SystemOutTraceListener();

    public static SystemOutTraceListener instance( ) { return instance; }
    
    private SystemOutTraceListener( ) {
    
    }
    
    @Override
    public void trace( QueryDBTraceEvent event ) {
        event.print( new PrintWriter( System.out ) );
    }
}
