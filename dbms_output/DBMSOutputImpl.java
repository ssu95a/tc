package ru.inversion.tc.dbms_output;

import ru.inversion.db.JInvDbException;
import ru.inversion.db.StoredProc;

import java.io.IOException;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.sql.*;
import java.util.List;

/**
 *
 * @author ssu @
 */
public class DBMSOutputImpl implements IDBMSOutput {

	private final Connection connection;
	
	private boolean enable = false;
			
	public DBMSOutputImpl( Connection connection ) {
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
	public void enable( ) {
		enable( -1 );
	}
	/** */
	@Override
	public void enable( int buffer_size ) {

		if( isEnable( ) )
			return;
		
//		final String sp_name = "dbms_output.enable";
//
//		try {
//			StoredProc sp = new StoredProc( sp_name );
//			sp.addParameter	( buffer_size == -1 ? null : buffer_size );
//			sp.execute		( getConnection( ) );
//
//			enable = true;
//
//		}
//		catch( Throwable ex ) {
//			handleException( ex, sp_name );
//		}

		final String sp_name = "{call dbms_output.enable(" + ( buffer_size == -1 ? "null" : buffer_size ) + ")}";

		try( CallableStatement st = connection.prepareCall(sp_name) ) {
			 st.execute();
			 enable = true;
		}
		catch( Throwable ex ) {
			handleException( ex, sp_name );
		}

	}
	/** */
	@Override
	public void disable( ) {
		
        if( !isEnable() )
            return;
        
		final String sp_name = "dbms_output.disable";
		
		try {
			StoredProc sp = new StoredProc( sp_name );
			sp.execute		( getConnection( ) );
			
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
		
		final String sp_name = "dbms_output.get_line";
		
		try {
			StoredProc sp = new StoredProc( sp_name );
			sp.registerOutParameter( Types.VARCHAR );
			sp.registerOutParameter( Types.INTEGER );
			sp.execute		( getConnection( ) );
			
			if( (Integer)sp.getOutParameter(2) == 1 )
				return (String)sp.getOutParameter( 1 );
			return null;
		}
		catch( Throwable ex ) {
			handleException( ex, sp_name );
		}
		return null;
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

	final static private String get_Lines_SQL = "declare l_num integer; begin l_num :=?; dbms_output.get_lines(?, l_num); ? := l_num; end;";

	public String get_lines( int numLines ) {

		if( !isEnable( ) )
			return null;

		final int default_num_lines = 100;

		boolean checkNumLines = numLines > 0;
		boolean readDone	  = false;

		StringBuilder sb = new StringBuilder();

		try( CallableStatement call = getConnection().prepareCall( get_Lines_SQL ) )
		{
			call.registerOutParameter( 2, Types.ARRAY, "DBMSOUTPUT_LINESARRAY");
			call.registerOutParameter( 3, Types.INTEGER );

			do {

				int linesToRead = checkNumLines ? Math.min( default_num_lines, numLines ) : default_num_lines;

				call.setInt ( 1, linesToRead );
				call.execute( );

				Array array = null;

				try {

					array = call.getArray(2);

					if( array != null )
						for( Object o : (Object[])array.getArray() ) {
							 if( o != null )
								 sb.append( o ).append('\n');
						}
				}
				finally {
					if( array != null )
						array.free();
				}

				readDone = linesToRead - call.getInt( 3 ) > 0;

			} while( !readDone );

			return sb.toString();
		}
		catch( Throwable ex ) {
			handleException( ex, get_Lines_SQL );
		}
		return null;
	}

	/** */
	public String get_lines1( int numLines ) {
		
		if( !isEnable( ) )
			 return null;
		
		final int default_num_lines = 100;
		
		final String strSQL = 
		 "declare " +
          "    l_line	varchar2(32767); " +
          "    l_done	integer; " +
          "    l_index	integer := 0; " +
          "    l_buffer long; " +
          "begin " +
          "  loop " +
          "		exit when l_index > ? OR l_done = 1; " +
          "		dbms_output.get_line( l_line, l_done ); " +
		  "		if l_line is not null then " +		
          "			l_buffer := l_buffer || l_line || chr(10); " +
          "			l_index  := l_index + 1; " +
          "		end if; " +
          "  end loop; " +
          " ? := l_done; " +
          " ? := l_buffer; " +
          "end;";
		
		
		boolean checkNumLines = numLines > 0;
		boolean readDone	  = false;
		
		StringBuilder sb = new StringBuilder();
		
		try( CallableStatement ps = getConnection().prepareCall(strSQL) ){
			
			ps.registerOutParameter( 2, Types.INTEGER );
			ps.registerOutParameter( 3, Types.VARCHAR );
			
			do {

				int linesToRead = checkNumLines ? Math.min( default_num_lines, numLines ) : default_num_lines;
				
				ps.setInt( 1, linesToRead );
				
				ps.execute( );

				readDone = ps.getInt( 2 ) == 1;
			
				String s = ps.getString(3);
				if( s != null )
					sb.append( s );
				
				if( !readDone ) {

					if( checkNumLines ) {
						
						numLines -= linesToRead;
						
						if( numLines <= 0 )
							readDone = true;
					}//end if
				}// end if
			} while( !readDone );
			
			return sb.toString();
		}
		catch( Throwable ex ) {
			handleException( ex, strSQL );
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
