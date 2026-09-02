package ru.inversion.tc.tracer;

/** */
public enum QueryDBTraceTypeEnum {

    TECH,

    DBMS_OUTPUT, 
    
    BEFORE_EXECUTE, 
    
    AFTER_EXECUTE,
    
    INFO,
    
    ERROR,

    RAISE_DEBUG;

    @Override
    public String toString() {
        switch( this ) {
            case BEFORE_EXECUTE:
                return "SQL before execute";
            case AFTER_EXECUTE:
                return "Return after execute";
            case DBMS_OUTPUT:
                return "DbmsOutput";
            case ERROR:
                return "Error";
            case INFO:
                return "Info";
            case RAISE_DEBUG:
                return "RaiseDebug";
            default:
                return this.name();
        }
    }
}
