package ru.inversion.tc.tracer;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author ssu
 */
public class LoggerTraceListener implements IQueryDBTraceListener {

    /** */
    final private static LoggerTraceListener instance = new LoggerTraceListener();

    public static LoggerTraceListener instance( ) { return instance; }
    
    static final private Logger logger = LoggerFactory.getLogger("ru.inversion.sql");
    
    @Override
    public void trace( QueryDBTraceEvent event ) {
        
        if( event.getType() == QueryDBTraceTypeEnum.ERROR ) {
            
            if( logger.isErrorEnabled() )
                logger.error(eventToString(event) ); 
        }
        else {
            
            if( logger.isDebugEnabled() )
                logger.debug(eventToString(event) ); 
        }
    }
    
    /** */
    private String eventToString( QueryDBTraceEvent event ) {
        StringWriter sw = new StringWriter();
        event.print( new PrintWriter( sw ) );
        return sw.toString();
    }
}
