package ru.inversion.tc.tracer.impl;

import ru.inversion.tc.dbms_output.DBMSOutputImpl;
import ru.inversion.tc.dbms_output.IDBMSOutput;
import ru.inversion.tc.dbms_output.PGOutputImpl;
import ru.inversion.tc.tracer.*;
import ru.inversion.utils.AutoCloseableList;
import ru.inversion.utils.S;
import ru.inversion.utils.Tags;
import ru.inversion.utils.U;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import static ru.inversion.tc.tracer.QueryDBTraceTypeEnum.INFO;
import static ru.inversion.tc.tracer.QueryDBTraceTypeEnum.RAISE_DEBUG;

/**
 *
 * @author sulimoff
 */
public class QueryDBTracerConnection extends AbstractBaseQueryDBTracer {
    
    final private Connection     connection;
    final private IQueryDBTracer tracer;
    final private IDBMSOutput    dbmsOutput;
    final private AutoCloseableList
                                 closeableList = new AutoCloseableList();

    /** */
    private static void setRaiseNoteState( Connection c, boolean enable )
    {

        try( CallableStatement cs = c.prepareCall("{ call jf_pkg_util.set_raisenotice_state(?)}") ) {
            cs.setInt( 1, enable ? 1 : 0 );
            cs.execute();
        }
        catch( SQLException ex ) {
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error on call 'jf_pkg_util.set_raisenotice_state'", ex );
        }
    }

    /** */
    private String check4HideParameters(String s, Set<Integer> hideList ) {

        if( S.isNullOrEmpty(s) )
            return s;

        int index = s.lastIndexOf( "\n--lhv:" );

        if( index != -1 )
        {
            String sbs = s.substring(index);

            sbs = sbs.substring( sbs.indexOf(':') + 1 );

            if( !sbs.isEmpty() ) {

                for( String d : U.iterable( U.toIterator( sbs.split(",") ) ) ) {

                    if( S.isNullOrEmpty(d) )
                        continue;

                    try {
                        hideList.add( Integer.parseInt( d.trim() ) );
                    }catch( NumberFormatException e ) {
                    }
                }
            }

            s = s.substring( 0, index );

        }//end if

        return s;
    }

    /** */
    private QueryDBTracerConnection( Connection connection, IQueryDBTracer tracer )
    {
        this.connection = Objects.requireNonNull( connection, "connection is null" );
        this.tracer     = tracer;

        try {

            if( connection.getMetaData().getDatabaseProductName().toLowerCase().contains("postgres") ) {

                this.dbmsOutput = new PGOutputImpl( connection );

                final IQueryDBTraceListener l = new IQueryDBTraceListener() {

                    private boolean enable;

                    @Override
                    public void trace( QueryDBTraceEvent event ) {

                        if( event.getType() == QueryDBTraceTypeEnum.TECH )
                        {
                            if( RAISE_DEBUG.name().equals(event.getText()) )
                            {
                                if("enable".equals(event.getMethodName())) {
                                    if(!enable) {
                                        setRaiseNoteState( connection, true );
                                        enable = true;
                                    }
                                }
                                else
                                {
                                    if(enable) {
                                        setRaiseNoteState( connection, false );
                                        enable = false;
                                    }
                                }
                            }
                        }
                    }
                };
                tracer.addListener( l );
                closeableList.add( () -> tracer.removeListener(l) );
            }
            else
                this.dbmsOutput = new DBMSOutputImpl( connection );

            closeableList.add(this.dbmsOutput);

        } catch(SQLException e) {
            throw new RuntimeException(e);
        }
    }   
    
    /** */
    private boolean isEnableTrace( ) {
        return this.tracer != null && tracer.isEnable( );
    }
    
    /** */
    @Override
    public Object invoke( Object proxy, Method method, Object[] params) throws Throwable {
        
        try {
            
            if( Object.class.equals(method.getDeclaringClass()) ) {
                return method.invoke(this, params);
            }

            if( "createARRAY".equals( method.getName() ) )
            {
                return method.invoke(this, params);
            }

            if( !isEnableTrace() )
                return method.invoke( connection, params );
            
            switch( method.getName() ) {
                
                case "prepareStatement": 
                {
                    Set<Integer> hp = new TreeSet<>();
                    String p = check4HideParameters( (String) params[0], hp );
                    if( hp.isEmpty() )
                        hp = null;
                    else
                        params[0] = p;
                    PreparedStatement stmt = (PreparedStatement) method.invoke(connection, params);
                    return QueryDBTraceIgnoreList.isIgnore(p) ?
                           stmt
                           :
                           QueryDBTracerPreparedStatement.newInstance( PreparedStatement.class, stmt, p, tracer, null, U.toMap("hideParams", hp ) );
                }
                case "prepareCall": {
                    Set<Integer> hp = new TreeSet<>();
                    String p = check4HideParameters( (String) params[0], hp );
                    if( hp.isEmpty() )
                        hp = null;
                    else
                        params[0] = p;
                    PreparedStatement stmt = (PreparedStatement) method.invoke(connection, params);
                    return QueryDBTraceIgnoreList.isIgnore(p) ?
                           stmt
                           :
                           QueryDBTracerPreparedStatement.newInstance( CallableStatement.class, stmt, p, tracer, dbmsOutput, U.toMap("hideParams", hp ) );
                }
                case "unwrap":
                {
                    final Class clazz = (Class)params[0];

                    if( clazz == Connection.class )
                        return connection;

                    return connection.unwrap(clazz);
                }
                case "commit":
                {
                    if( tracer.isEnableTrace(INFO) )
                        tracer.trace( QueryDBTraceEvent.info( this, method.getName(), "COMMIT" ) );

                    return method.invoke(connection, params);
                }
                case "rollback":
                {
                    if( tracer.isEnableTrace(INFO) )
                    {
                        if( params != null && params.length > 0 )
                        tracer.trace(QueryDBTraceEvent.info( this, method.getName(), "ROLLBACK TO " + ((Savepoint)params[0]).getSavepointName() ) );
                        else
                            tracer.trace(QueryDBTraceEvent.info( this, method.getName(), "ROLLBACK" ) );
                    }
                    return method.invoke(connection, params);
                }
                case "close":
                {
                    if( tracer.isEnableTrace(INFO) )
                        tracer.trace( QueryDBTraceEvent.info( this, method.getName(), "CLOSE CONNECTION" ) );

                    //dbmsOutput.close();
                    closeableList.close();

                    return method.invoke(connection, params);
                }
                case "setAutoCommit":
                {
                    if( tracer.isEnableTrace(INFO) )
                        tracer.trace(QueryDBTraceEvent.info( this, method.getName(), "autoCommit: " + params[0] ) );
                    return method.invoke(connection, params);
                }
                case "setSavepoint":
                {
                    if( tracer.isEnableTrace(INFO) )
                        tracer.trace( QueryDBTraceEvent.info( this, method.getName(), "setSavepoint: " + params[0] ) );
                    return method.invoke(connection, params);
                }
                default:
                    return method.invoke(connection, params);
            }
            
        } catch ( Throwable t ) {
            //connection.clearWarnings();
            throw unwrapThrowable(t);
        }
    }
    
    /** */
    public IDBMSOutput getDBMSOutput( ) {
        return dbmsOutput;
    }
    
    /** */
    public static Connection newInstance( Connection connection, IQueryDBTracer tracer ) 
    {
        return (Connection) Proxy.newProxyInstance ( 
                QueryDBTracerConnection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new QueryDBTracerConnection( connection, tracer )
        );
    }
}
