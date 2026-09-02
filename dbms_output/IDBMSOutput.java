package ru.inversion.tc.dbms_output;

import java.util.List;

/**
 * Управление Oracle DBMS_OUTPUT
 * @author ssu @
 */
public interface IDBMSOutput extends AutoCloseable {
	
	final static int DEFAULT_BUFFER_SIZE = 1000000;
	/** */
	void enable( );
	/** */
	void enable( int buffer_size );
	/** */
	void disable( );
	/** */
	String get_line( );
	/** */
	int get_lines( List<String> lines, int numLines );
	/** */
	int get_lines( List<String> lines );
	/** */
	String get_lines( int numLines );
	/** */
	String get_lines( );
	/** */
	void put( String str );
	/** */
	void put_line( String str );
	/** */
	void new_line( );
	/** */
	boolean isEnable( );
}
