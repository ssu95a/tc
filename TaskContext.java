package ru.inversion.tc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.inversion.dataset.ParametersByName;
import ru.inversion.db.dialect.SqlDialect;
import ru.inversion.db.dialect.SqlDialectFactory;
import ru.inversion.db.session.SessionEnvironment;
import ru.inversion.tc.jdbc.event.JdbcEventBus;
import ru.inversion.tc.jdbc.trace.DefaultJdbcTraceListener;
import ru.inversion.tc.jdbc.trace.JdbcTracer;
import ru.inversion.tc.tracer.IQueryDBTracer;
import ru.inversion.tc.tracer.QueryDBTracer;
import ru.inversion.tc.tracer.impl.QueryDBTracerConnection;
import ru.inversion.utils.ConnectionStringFormatEnum;
import ru.inversion.utils.S;
import ru.inversion.utils.Tags;
import ru.inversion.utils.U;

import java.sql.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import ru.inversion.tc.jdbc.internal.proxy.JdbcConnectionProxy;

/**
 * Одно соединение к БД
 * @author sulimoff
 */
public class TaskContext implements AutoCloseable {
    
    private Connection          connection;
    private final Long          sessionId;
    private final QueryDBTracer queryDBTracer;

    static final private Logger logger = LoggerFactory.getLogger("ru.inversion.sql");

    private Map<String,Object>  properties;

    // savePoints support
    private static final AtomicInteger SAVEPOINT_ID_GENERATOR = new AtomicInteger();

    /**  */
    public TaskContext( ) {
        this( null, null, null );
    }

    /**  */
    public TaskContext( String login, String password, String url ) {

        logger.debug("new TaskContext ...");

        Connection c = null;

        try {

            c = createConnection( login, password, url );

            sessionId = initSessionID( c );
            
            final String sessionInfo = String.format (
                    "DB connection \n\tUSER     : %s\n\tURL      : %s\n\tSESSIONID: %s",
                    c.getMetaData().getUserName(), 
                    c.getMetaData().getURL(),
                    sessionId
            );
            
            queryDBTracer = new QueryDBTracer( new ParametersByName() {
                @Override
                public Object getParameter( String name ) {
                    switch(name) {
                        case "sessionInfo":
                            return sessionInfo;
                        case "sessionId":
                            return sessionId;
                    }
                    return null;
                }
            });

//            Connection jdbcConnection = JdbcConnectionProxy.create( c, null ).proxy();
//
//            connection = QueryDBTracerConnection.newInstance( jdbcConnection, queryDBTracer );

            JdbcEventBus eventBus =
                    new JdbcEventBus();

            JdbcTracer jdbcTracer =
                    new JdbcTracer(eventBus);


            jdbcTracer.addListener(
                    new DefaultJdbcTraceListener(System.out.)
            );

            Connection jdbcConnection =
                    JdbcConnectionProxy
                            .create(
                                    c,
                                    eventBus
                            )
                            .proxy();

            connection =
                    QueryDBTracerConnection.newInstance(
                            jdbcConnection,
                            queryDBTracer
                    );

            TCStorage.INSTANCE().add(this);

            logger.debug("TaskContext successfully created. session ID: {}", sessionId);
        }
		  catch( Throwable ex ) {
            
            try {

                if( c != null && !c.isClosed() ) {
                    c.close();
                }
            }

            catch( SQLException ignored ) {
            }
            
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error on create TaskContext", ex );
        }
    }

    /** */
    private static Connection createConnection( String login, String password, String url ) throws SQLException {

        Connection c = null;

        if( U.containsNotNull( login,password, url) )
            c = SessionEnvironment.getInstance().getConnectionProvider().getConnection( login, password, url );
        else
            c = SessionEnvironment.getInstance().getConnectionProvider().getConnection( );

        return c;
    }

    /** */
    public String getDataBaseProductName()
    {
        try {
            return getConnection().getMetaData().getDatabaseProductName();
        } catch(SQLException e) {
            return e.getMessage();
        }
    }

    /**
     */
    private long initSessionID(Connection c) {

        try( PreparedStatement ps = c.prepareStatement("SELECT to_number(USERENV ('SESSIONID')) session_id FROM dual"); ResultSet rs = ps.executeQuery() ) {
            rs.next( );
            return rs.getLong(1);
        }
        catch( Throwable th ) {
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error getting session ID", th );
        }
    }
    
    /** 
     */
    private void checkForClose( ) {

        if( connection == null )
            throw new IllegalStateException( Tags.PRODUCT_LABEL + "TaskContext is already closed. session ID: " + sessionId);
    }

    /** */
    public boolean isClosed()
    {
        return connection == null;
    }

	/** 
     */
	public Connection getConnection() {
		checkForClose( );
        return connection;
	}
        
	/** 
     */
    private void doClose() {

        TCStorage.INSTANCE( ).remove( this );

        try {

            if( connection != null )
            {
                if(!connection.isClosed())
                {
                    if(!connection.getAutoCommit()  )
                        connection.rollback();
                    connection.close();
                    connection = null;
                }
            }
        } catch( SQLException ex ) {
            ex.printStackTrace();
        }

        logger.debug( "TaskContext was closed. session ID: " + sessionId);
    }

    /** */
    @Override
    public void close() {
        synchronized(this) {
            doClose( );
        }
    }

    /**
     * @return  */
    public IQueryDBTracer getQueryDBTracer( ) {
        return queryDBTracer;
    }
    
    /**
     * @return  */
    public Long getSessionID( ) {
        return sessionId;
    }
    
    /** */
    public String getUserName( ) {
        try {
            return getConnection().getMetaData().getUserName();
        } catch (SQLException ex) {
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error getting UserName session", ex );
        }
    }
    
    /** */
    public String getConnectionString( ConnectionStringFormatEnum format ) {

        try {

            String s = ( format == ConnectionStringFormatEnum.JDBC )
                        ?
                        getConnection().getMetaData().getURL()
                        :
                        SessionEnvironment.getInstance().getConnectionProvider().getConnectionString(format);
            
            if( format == ConnectionStringFormatEnum.SQL_INFO )
                s += ( " : " + getSessionID() );

            return s;
            
        } catch (SQLException ex) {
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error getting connectionString session", ex );
        }
    }
    
    /** */
    public void commit() {
        try {

            clearSavePoints();

            if( !isAutoCommit() )
                 connection.commit();

        } catch ( SQLException ex ) {
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error on call 'commit'", ex );
        }
    }

    /** */
    public void rollback() {

        try {

            clearSavePoints();

            if( !isAutoCommit() )
                 connection.rollback();

        } catch ( SQLException ex ) {
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error on call 'rollback'", ex );
        }
    }

    /** */
    public boolean isAutoCommit() {
        try {
            return getConnection().getAutoCommit();
        } catch(SQLException ex ) {
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error on call connection 'getAutoCommit'", ex );
        }
    }

    /** */
    public void setAutoCommit( boolean autoCommit )
    {
        try {
            getConnection().setAutoCommit(autoCommit);
        } catch(SQLException ex ) {
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error on call connection 'autoCommit'", ex );
        }
    }

    /** */
    private void clearSavePoints()
    {
        if( properties != null )
            properties.remove("$sp_map$");
    }

    /** */
    private Map<String,Savepoint> savePointsMap() {
        return (Map<String,Savepoint>)properties().computeIfAbsent("$sp_map$", s -> new HashMap<String,Savepoint>());
    }

    /** */
    public String setSavepoint( ) {
        final String name = String.join( "_", "SP_INV", Integer.toString( SAVEPOINT_ID_GENERATOR.addAndGet(1) ) );
        setSavepoint( name );
        return name;
    }

    /** */
    public void setSavepoint( String name ) {
        try {
            savePointsMap().put( name, getConnection().setSavepoint(name) );
        } catch(SQLException ex ) {
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error on call 'setSavepoint(name)'", ex );
        }
    }

    /** */
    public void releaseSavepoint( String name ) {

        try {

            if( !isPostgreSql() )
                 return;

            if( S.isNullOrEmpty(name) )
                throw new IllegalArgumentException( Tags.PRODUCT_LABEL + "savepoint 'name' is null" );

            final Savepoint savepoint = savePointsMap().remove(name);

            if( savepoint == null )
                ;// ниче не делаем либо Exception - throw new IllegalArgumentException( Tags.PRODUCT_LABEL + "savepoint 'name' not found " );
            else
                getConnection().releaseSavepoint(savepoint);

        } catch( SQLException ex ) {
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error on call 'releaseSavepoint(name)'", ex );
        }
    }

    /** */
    public void rollback( String name ) {
        try {

            if( S.isNullOrEmpty(name) )
                throw new IllegalArgumentException( Tags.PRODUCT_LABEL + "savepoint 'name' is null" );

            final Savepoint savepoint = savePointsMap().remove(name);
            if( savepoint == null )
                ;// ниче не делаем либо Exception - throw new IllegalArgumentException( Tags.PRODUCT_LABEL + "savepoint 'name' not found " );
            else
                getConnection().rollback(savepoint);

        } catch(SQLException ex ) {
            throw new RuntimeException( Tags.PRODUCT_LABEL + "Error on call 'rollback_to'", ex );
        }
    }

    /** */
    public <T> T getProperty(String propertyName) {
        return properties == null ? null : (T)properties.get(propertyName);
    }

    /** */
    public <T> T getProperty(String propertyName, T defaultValue ) {
        return properties == null ? defaultValue : (T)properties.getOrDefault( propertyName, defaultValue );
    }

    /** */
    private Map<String,Object> properties() {
        if( properties == null )
            properties = new HashMap<>();
        return properties;
    }

    /** */
    public void setProperty(String propertyName, Object value) {

        if( S.isNullOrEmpty(propertyName) )
            return;
        properties().put( propertyName, value );
    }

    /** */
    public void assignAppTitle( String title )
    {
        if( S.isNullOrEmpty(title)  )
            return;
        final Connection c = getConnection();
        try( CallableStatement cs = c.prepareCall("{call dbms_application_info.set_module(?,?)}") ) {

            cs.setString( 1, title );
            cs.setString( 2, "initialized" );

            cs.execute();

            c.commit();

        } catch (Throwable ignored) {
        }
    }

    /** */
    public void assignSessionTitle( String title )
    {
        if( S.isNullOrEmpty(title)  )
            return;

         boolean doSet = true;

        LinkedList<String> stackTitles = getProperty("ru.inversion.titles");

        if( stackTitles == null ) {
            stackTitles = new LinkedList<>();
            setProperty( "ru.inversion.titles", stackTitles );
        }
        else
        {
            doSet = !title.equals( stackTitles.getLast() );
        }

        if( doSet )
        {
            final Connection c = getConnection();

            try( CallableStatement cs = c.prepareCall("{call dbms_application_info.set_action(?)}") ) {

                 cs.setString(1, title);
                 cs.execute();

            } catch (Throwable ignored) {
            }
        }

        stackTitles.add(title);
    }

    /** */
    public void restoreSessionTitle( )
    {
        final LinkedList<String> stackTitles = getProperty("ru.inversion.titles");

        if( stackTitles != null )
        {
            stackTitles.pollLast();

            final String title = stackTitles.isEmpty() ? null : stackTitles.getLast();

            try( CallableStatement cs = getConnection().prepareCall("{call dbms_application_info.set_action(?)}") ) {

                 cs.setString(1, title);
                 cs.execute();

            } catch (Throwable ignored) {
            }

            if( stackTitles.isEmpty() )
                properties.remove( "ru.inversion.titles" );
        }
    }

    /** */
    public boolean isOracle() {
        return U.<Integer>nvl( (Integer)properties().computeIfAbsent( "ru.inversion.db_prod_name", s -> getDataBaseProductName().toLowerCase().contains("ora") ? 1 : null ), 0 ) == 1;
    }

    /** */
    public boolean isPostgreSql() {
        return U.<Integer>nvl( (Integer)properties().computeIfAbsent( "ru.inversion.db_prod_name", s -> getDataBaseProductName().toLowerCase().contains("postgres") ? 2 : null ), 0 ) == 2;
    }

    /** */
    public SqlDialect dialect()
    {
        return SqlDialectFactory.from(this);
    }
}
