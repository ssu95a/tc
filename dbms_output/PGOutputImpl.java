package ru.inversion.tc.dbms_output;

import ru.inversion.db.JInvDbException;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.sql.*;
import java.util.List;

/**
 *
 * @author ssu @
 */
public class PGOutputImpl implements IDBMSOutput {

	private final Connection connection;

	private boolean enable = false;

	public PGOutputImpl( Connection connection ) {
		this.connection = connection;
	}

	/** */
	public Connection getConnection( ) {
		return connection;
	}

	/** */
	@Override
	public boolean isEnable( ) {
		return enable;
	}

	/** */
	@Override
	public void enable( int buffer_size ) {

		if( isEnable( ) )
			return;

		final String sp_name = "select dbms_output.enable(?)";

		try( PreparedStatement st = connection.prepareStatement(sp_name) ) {
			st.setInt (1,buffer_size);
			st.execute();
			enable = true;
		}
		catch( Throwable ex ) {
			handleException( ex, sp_name );
		}
	}

	/** */
	@Override
	public void enable( ) {

		if( isEnable( ) )
			return;

		final String sp_name = "select dbms_output.enable(1000000)";

		try( Statement st = connection.createStatement() ) {
			 st.execute(sp_name);
			 enable = true;
		}
		catch( Throwable ex ) {
			handleException( ex, sp_name );
		}
	}

	/** */
	@Override
	public void disable( )
	{
		
        if( !isEnable() )
            return;
        
		final String sp_name = "select dbms_output.disable()";

		try(Statement st = connection.createStatement() ) {
			st.execute(sp_name);
			enable = false;
		}
		catch( Throwable ex ) {
			handleException( ex, sp_name );
		}
	}
	/** */
	@Override
	public String get_line( ) {
		
		if( !isEnable( ) )
			 return null;

		throw new UnsupportedOperationException("'get_line' Not supported yet.");
	}
	/** */
	private int parseNLString( String str, List<String> lines ) throws IOException {
		
		if( str == null )
			return 0;
		
		LineNumberReader lnr = new LineNumberReader( new StringReader(str) );
		String line = null;
		while( (line = lnr.readLine() ) != null  ) {
			lines.add(line);
		}
		return lnr.getLineNumber();
	}
	/** */
	@Override
	public int get_lines( List<String> lines ) {
		return get_lines( lines, -1 );
	}
	/** */
	@Override
	public int get_lines( List<String> lines, int numLines ) {

		if( !isEnable( ) )
			 return 0;
		try {
			return parseNLString( get_lines( numLines ), lines );
		}
		catch( IOException ex ) {
			throw new RuntimeException("DbmsOutput: An error occurred while reading rows", ex );
		}
	}

	final static private String get_Lines_SQL = "select lines, numlines from dbms_output.get_lines(?) sel";

	public String get_lines( int numLines ) {

		if( !isEnable( ) )
			return null;

		final int default_num_lines = 100;

		boolean checkNumLines = numLines > 0;
		boolean readDone	  = false;

		StringBuilder sb = new StringBuilder();

		try( PreparedStatement st = getConnection().prepareStatement( get_Lines_SQL ) )
		{
			do {

				int linesToRead = checkNumLines ? Math.min( default_num_lines, numLines ) : default_num_lines;

				st.setInt ( 1, linesToRead );

				Array array = null;

				try( ResultSet rs = st.executeQuery() )
				{
					readDone = !rs.next();
					if( !readDone )
					{
						array = rs.getArray(1);

						if( array != null )
							for( Object o : (Object[])array.getArray() ) {
								 if( o != null )
									 sb.append( o ).append('\n');
							}

						readDone = linesToRead - rs.getInt( 2 ) > 0;
					}
				}
				finally {
					if( array != null )
						array.free();
				}

			} while( !readDone );

			return sb.toString();
		}
		catch( Throwable ex ) {
			handleException( ex, get_Lines_SQL );
		}
		return null;
	}

	/** */
    @Override
	public String get_lines( ) {
		return get_lines(-1);
	}
	/** */
	@Override
	public void put(String str) {
		throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
	}
	/** */
	@Override
	public void put_line(String str) {
		throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
	}
	/** */
	@Override
	public void new_line() {
		throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
	}
	/** */
	protected void handleException( Throwable e, String sqlText ) {
		if( e instanceof JInvDbException )
			throw (JInvDbException)e;
		throw new JInvDbException( e, sqlText );
	}

    @Override
    public void close() throws Exception {
        disable( );
    }
}
