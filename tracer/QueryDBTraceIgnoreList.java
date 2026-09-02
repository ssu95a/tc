package ru.inversion.tc.tracer;

/**
 * Списки исключения выражений, вокруг которых не нужно делать InvocationHandler
 * @author ssu
 */
public class QueryDBTraceIgnoreList {
    
    static final public String exprList[] = {
        "{?=call UTIL_TRACE.ReadNextChunk()}",
        "select OBJECT_ID from all_objects where owner = 'PUBLIC' and object_name = 'XXI_UPGRADE' and OBJECT_TYPE = 'SYNONYM'",
        "insert into XXI_FX_PLSQL_MON (JAR_NAME, SRC_NAME, BLK_NAME) select ?,?,? from dual where not exists ( select null from XXI_FX_PLSQL_MON where SRC_NAME = ? and BLK_NAME = ? and run_date = trunc(LOCALTIMESTAMP) )"
    };
    
    public static boolean isIgnore( String expr ) {
        
        for( String s : exprList ) {
             if( s.equalsIgnoreCase(expr) )
                 return true;
        }
        return false;
    }
}
