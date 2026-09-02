package ru.inversion.tc.tracer;

/**
 *
 * @author ssu
 */
public interface IQueryDBTracer extends IQueryDBTraceListener {

    boolean isEnable( );
    
    /** */
    void setEnable( boolean enable );
    
    /** */
    void setEnableTrace( QueryDBTraceTypeEnum objectType, boolean enable );
    
    /** */
    boolean isEnableTrace( QueryDBTraceTypeEnum objectType );
    
    /** */
    void addListener( IQueryDBTraceListener listener );

    /** */
    void removeListener( IQueryDBTraceListener listener );

}
