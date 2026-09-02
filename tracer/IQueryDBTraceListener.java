package ru.inversion.tc.tracer;

/**
 *
 * @author sulimoff
 */
@FunctionalInterface
public interface IQueryDBTraceListener {
  
    /**
     * 
     * @param event 
     */
    public void trace( QueryDBTraceEvent event );
}
