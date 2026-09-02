package ru.inversion.tc.tracer.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.inversion.tc.dbms_output.IDBMSOutput;
import ru.inversion.tc.tracer.IQueryDBTracer;
import ru.inversion.tc.tracer.QueryDBTraceEvent;
import ru.inversion.tc.tracer.QueryDBTraceTypeEnum;
import ru.inversion.utils.S;
import ru.inversion.utils.Tags;
import ru.inversion.utils.U;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Duration;
import java.util.*;

/**
 *
 * @author ssu
 */
public class QueryDBTracerPreparedStatement extends AbstractBaseQueryDBTracer {

    final static public String STUB_VALUE = "*********";

    final static private Logger logger = LoggerFactory.getLogger("ru.inversion.sql");


//    private static final Set<String> EXECUTE_METHODS = buildExecuteMethods();
    private static final Set<String> SET_METHODS = buildSetMethods();
    
//    private static Set<String> buildExecuteMethods( ) {
//        Set<String> exec = new HashSet<>();
//        exec.add("execute");
//        exec.add("executeUpdate");
//        exec.add("executeQuery" );
//        exec.add("addBatch");
//        return Collections.unmodifiableSet(exec);
//    }


    private static Set<String> buildSetMethods( ) {
        Set<String> set = new HashSet<>();
        set.add("setString");
        set.add("setNString");
        set.add("setInt");
        set.add("setByte");
        set.add("setShort");
        set.add("setLong");
        set.add("setDouble");
        set.add("setFloat");
        set.add("setTimestamp");
        set.add("setDate");
        set.add("setTime");
        set.add("setArray");
        set.add("setBigDecimal");
        set.add("setAsciiStream");
        set.add("setBinaryStream");
        set.add("setBlob");
        set.add("setBoolean");
        set.add("setBytes");
        set.add("setCharacterStream");
        set.add("setNCharacterStream");
        set.add("setClob");
        set.add("setNClob");
        set.add("setObject");
        set.add("setNull");
        return Collections.unmodifiableSet(set);
    }
    
    /** */
    final private PreparedStatement     statement; 
    /** */
    final private String                sqlQuery;
    /** */
    final private IQueryDBTracer        tracer;
    /** */
    final private Map <Integer,Object>   inParameters = new TreeMap<>();
    final private List<Integer>         outParameters = new ArrayList<>();
    /** */
    final boolean                       isCallable;
    /** */
    final private IDBMSOutput           dbmsOutput;

    final private Set<Integer>          hideParams;



    /** */
    public QueryDBTracerPreparedStatement( PreparedStatement preparedStatement, String sqlQuery,
                                           IQueryDBTracer tracer, IDBMSOutput dbmsOutput, Map<String,Object> parameters )
    {
        this.statement = preparedStatement;
        this.sqlQuery  = sqlQuery;
        this.hideParams= (Set<Integer>)parameters.get("hideParams");
        this.tracer    = tracer;
        this.dbmsOutput= dbmsOutput;
        
        this.isCallable= preparedStatement instanceof CallableStatement;
    }
    
    /** */
    private boolean isEnableTrace( ) {
        return this.tracer != null && tracer.isEnable( );
    }
    
    /** */
    @Override
    public Object invoke( Object proxy, Method method, Object[] args ) throws Throwable {
        
        try {
            
            if( Object.class.equals( method.getDeclaringClass()) ) {
                return method.invoke( this, args);
            }
            
            if( !isEnableTrace() )
                return method.invoke( statement, args );
            
            String methodName = method.getName( );

            if( methodName.startsWith("execute") ) 
            {
                return execute( method, args );
            } 
            else if( SET_METHODS.contains( methodName ) ) 
            {
                if ("setNull".equals( methodName) ) 
                    inParameters.put( (int)args[0], null);
                else {

                    if( hideParams != null && hideParams.contains((int)args[0]) )
                        inParameters.put( (int)args[0], STUB_VALUE );
                    else
                        inParameters.put( (int)args[0], args[1] );
                }
                return method.invoke( statement, args );
            } 
            else if( "clearParameters".equals(methodName) ) {
                
                 inParameters.clear();
                outParameters.clear();
                
                return method.invoke( statement, args );
            }
            else if( this.isCallable ) {
                    
                if( "registerOutParameter".equals(methodName) )
                    outParameters.add( (int)args[0] );
                    
                return method.invoke( statement, args );
            }
            else 
            {
                return method.invoke( statement, args );
            }
        } catch( Throwable t ) {
            statement.clearWarnings();
            throw unwrapThrowable(t);
        }
    }
    
    /** */
    private void traceDbmsOutput( Method method ) {

        if( tracer.isEnableTrace( QueryDBTraceTypeEnum.RAISE_DEBUG) )
        {
            try {

                StringBuilder sb = null;
                SQLWarning warning = statement.getWarnings();
                while( warning != null )
                {
                    String message = warning.getMessage();
                    if( !S.isNullOrEmpty(message) )
                    {
                        if( sb == null )
                            sb = new StringBuilder(message);
                        else
                            sb.append('\n').append(message);
                    }
                    warning = warning.getNextWarning();
                }

                if( sb != null )
                    this.tracer.trace( QueryDBTraceEvent.raiseNotice( this, method.getName(), sb.toString() ) );

            } catch(SQLException ignored ) {
            }
        }

        if( tracer.isEnableTrace( QueryDBTraceTypeEnum.DBMS_OUTPUT ) )
        {
            if( isCallable && dbmsOutput != null )
            {
                if( tracer.isEnableTrace( QueryDBTraceTypeEnum.DBMS_OUTPUT ) ) {

                    dbmsOutput.enable();

                    String dbmsOutputText = dbmsOutput.get_lines( );

                    if( S.isNotNullOrEmpty(dbmsOutputText) )
                        this.tracer.trace( QueryDBTraceEvent.dbmsOutput( this, method.getName(), dbmsOutputText ) );
                }
                else
                    dbmsOutput.disable( );
            }//end if
        }
    }
    /** */
    private Object execute( Method method, Object[] args ) throws Exception {
        
        //QueryDBTraceEvent beforeExecuteEvent = QueryDBTraceEvent.beforeExecute( this, method.getName(), sqlQuery, inParameters );
        
        if( tracer.isEnableTrace( QueryDBTraceTypeEnum.BEFORE_EXECUTE ) )
            tracer.trace( QueryDBTraceEvent.beforeExecute( this, method.getName(), sqlQuery, inParameters ) );
        
        long start = System.nanoTime();
        
        Object retValue = null;
        
        try {
            
            retValue = method.invoke( statement, args );
            
//            if( retValue != null && retValue instanceof ResultSet ) {
//                checkRS2Restriction( (ResultSet)retValue );
//            }
            
        }
        catch( Throwable th ) {

            if( !statement.getConnection().getAutoCommit() ) {
                try {
                    statement.getConnection().rollback();
                }
                catch( SQLException ignored ) {
                }
            }

            if( !tracer.isEnableTrace( QueryDBTraceTypeEnum.BEFORE_EXECUTE ) ) {
            
                 tracer.trace( QueryDBTraceEvent.onThrowable( this, method.getName(), th, sqlQuery, inParameters));
                 traceDbmsOutput( method );
            }
            else {   
                
                traceDbmsOutput( method );
                tracer.trace( QueryDBTraceEvent.onThrowable( this, method.getName(), th ) );
            }
            
            throw th;
        }
        
        traceDbmsOutput( method );
        
        long finished = System.nanoTime();
        
        Map<Integer,Object> parameters = null;
        
        if( this.isCallable && !outParameters.isEmpty() ) 
        {
            parameters = new TreeMap<>();
            
            CallableStatement cs = (CallableStatement)statement;
            
            for( int index : outParameters )
                if( hideParams != null && hideParams.contains(index) )
                    parameters.put( index, STUB_VALUE );
                else
                    parameters.put( index, cs.getObject(index) );
        }
        
        if( tracer.isEnableTrace( QueryDBTraceTypeEnum.AFTER_EXECUTE ) )
            tracer.trace( QueryDBTraceEvent.afterExecute( this, method.getName(), parameters, Duration.ofNanos(finished - start) ) );
        
        return retValue;
    }
    /** */
    static PreparedStatement newInstance(
            Class<?> clazz,
            PreparedStatement preparedStatement, String sqlQuery, IQueryDBTracer tracer, IDBMSOutput dBMSOutput,
            Map<String,Object> parameters
    )
    {
        return (PreparedStatement) Proxy.newProxyInstance(
                QueryDBTracerPreparedStatement.class.getClassLoader(),
                new Class<?>[]{clazz},
                new QueryDBTracerPreparedStatement( preparedStatement, sqlQuery, tracer, dBMSOutput, parameters ));
    }

    /** */
    private ResultSet checkRS2Restriction( ResultSet rs ) throws SQLException {

        try {
            
            if( rs == null || rs.isClosed() )
                return rs;
            
        } catch( SQLException ex ) {
            return rs;
        }
        
        ResultSetMetaData metaData = rs.getMetaData( );

        int columnCount = metaData.getColumnCount();

        for( int i = 1; i <= columnCount; i++ ) {

            switch( metaData.getColumnType( i ) ) {
                case Types.BLOB:
                case Types.CLOB:
                    logger.warn(Tags.PRODUCT_LABEL + "The BLOB, CLOB data type cannot be used in SQL 'SELECT' expressions. Use a stored procedure."
                                                   + " Column '" + metaData.getColumnName(i) + "', type '" + metaData.getColumnTypeName(i) + "'" );
                    break;
//                    default:
//                        continue;
            }

//                throw new RSException( Tags.PRODUCT_LABEL + "The BLOB, CLOB data type cannot be used in SQL 'SELECT' expressions. Use a stored procedure."
//                                       + " Column '" + metaData.getColumnName(i) + "', type '" + metaData.getColumnTypeName(i) + "'" );
        }//end for
        
        return rs;
    }
    
    
}
