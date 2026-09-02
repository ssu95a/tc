package ru.inversion.tc.tracer;

import ru.inversion.tc.tracer.impl.QueryDBTraceEventWriter;

import java.io.Writer;
import java.time.Duration;
import java.util.EventObject;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author sulimoff
 */
public class QueryDBTraceEvent extends EventObject {

    /** */
    final private QueryDBTraceTypeEnum type;

    /** */
    final private String    text, 
                            methodName;

    /** */
    final private Map       parameters;

    /** */
    final private Throwable throwable;

    /** */
    final private Duration  duration;

    private Map<String,Object> properties;

    private QueryDBTraceEvent( Object source, String methodName, QueryDBTraceTypeEnum type, String text, Map parameters, Throwable throwable, Duration duration ) {
        super(source);
        this.type       = type;
        this.text       = text;
        this.parameters = parameters;
        this.throwable  = throwable;
        this.duration   = duration;
        this.methodName = methodName;
    }

    /** */
    public QueryDBTraceTypeEnum getType() {
        return type;
    }

    /** */
    public String getText( ) {
        return text;
    }

    /** */
    public String getMethodName( ) {
        return methodName;
    }

    /** */
    public Map getParameters( ) {
        return parameters;
    }

    /** */
    public Throwable getThrowable() {
        return throwable;
    }

    /** */
    public Duration getDuration() {
        return duration;
    }

    /** */
    public void print( Writer writer ) {
        QueryDBTraceEventWriter.write(this, writer);
    }

    public <T> T getProperty(String propertyName) {
        return properties == null ? null : (T)properties.get(propertyName);
    }

    /** */
    public void setProperty(String propertyName, Object value ) {
        if( properties == null )
            properties = new HashMap<>();
        properties.put( propertyName, value );
    }

    /** */
    public static QueryDBTraceEvent dbmsOutput( Object source, String methodName, String text ) {
        return new QueryDBTraceEvent( source, methodName, QueryDBTraceTypeEnum.DBMS_OUTPUT, text, null, null, null );
    }

    /** */
    public static QueryDBTraceEvent raiseNotice( Object source, String methodName, String text ) {
        return new QueryDBTraceEvent( source, methodName, QueryDBTraceTypeEnum.RAISE_DEBUG, text, null, null, null );
    }

    /** */
    public static QueryDBTraceEvent beforeExecute( Object source, String methodName, String text, Map<Integer,Object> parameters ) {
        return new QueryDBTraceEvent( source, methodName, QueryDBTraceTypeEnum.BEFORE_EXECUTE, text, parameters, null, null );
    }
    
    /** */
    public static QueryDBTraceEvent afterExecute( Object source, String methodName, Map<Integer,Object> parameters, Duration duration ) {
        return new QueryDBTraceEvent( source, methodName, QueryDBTraceTypeEnum.AFTER_EXECUTE, null, parameters, null, duration );
    }

    /** */
    public static QueryDBTraceEvent onThrowable( Object source, String methodName, Throwable th, String text, Map<Integer,Object> parameters ) {
        return new QueryDBTraceEvent( source, methodName, QueryDBTraceTypeEnum.ERROR, text, parameters, th, null );
    }

    /** */
    public static QueryDBTraceEvent onThrowable( Object source, String methodName, Throwable th ) {
        return new QueryDBTraceEvent( source, methodName, QueryDBTraceTypeEnum.ERROR, null, null, th, null );
    }

    /** */
    public static QueryDBTraceEvent info( Object source, String methodName, String text ) {
        return new QueryDBTraceEvent( source, methodName, QueryDBTraceTypeEnum.INFO, text, null, null, null );
    }

    public static QueryDBTraceEvent tech( Object source, String type, String param ) {
        return new QueryDBTraceEvent( source, param, QueryDBTraceTypeEnum.TECH, type, null, null, null );
    }

}
