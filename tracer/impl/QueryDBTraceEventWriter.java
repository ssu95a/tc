package ru.inversion.tc.tracer.impl;

import ru.inversion.tc.tracer.QueryDBTraceEvent;
import ru.inversion.utils.S;


import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static ru.inversion.tc.tracer.impl.QueryDBTracerPreparedStatement.STUB_VALUE;

/**
 *
 * @author ssu
 */
public class QueryDBTraceEventWriter {
    
    /** */
    public static void write( QueryDBTraceEvent event, Writer w ) {

        switch( event.getType() ) {
            case BEFORE_EXECUTE:
                writeBeforeExecute( w, event );
            break;
            case AFTER_EXECUTE:
                writeAfterExecute( w, event );
            break;
            case DBMS_OUTPUT:
                writeDbmsOutput( w, event );
            break;
            case RAISE_DEBUG:
                writeRaiseNotice( w, event );
                break;
            case ERROR:
                writeException( w, event );
            break;
            case TECH:
                writeTech( w, event );
                break;
            default:
                writeDefault( w, event );
        }
    }

    /** */
    private static void writeTech( Writer w, QueryDBTraceEvent event ) {
        try {
            w.append( event.getText() ).append(" - ").append(event.getMethodName());
            w.append('\n');
        } catch( IOException unused ) {
            ;
        }
    }

    private static void writeRaiseNotice( Writer w, QueryDBTraceEvent event ) {

        try {

            String text = event.getText();

            if( S.isNotNullOrEmpty(text) ) {

                w.append( "RAISE_DEBUG\n" );

                w.append( text );
                w.append('\n');

            }
            w.flush();

        } catch (Throwable ex) {
            try {
                w.append( ex.getLocalizedMessage() );
            } catch (IOException ex1) {
                ;
            }
        }
    }

    /** */
    private static void writeDefault( Writer w, QueryDBTraceEvent event ) {

        try {
            
            String text = event.getText();

            if( S.isNotNullOrEmpty(text) ) {
                w.append( text );
                w.append('\n');
            }
            writeParameters( w, event );
            w.flush();
            
        } catch (Throwable ex) {
            try {
                w.append( ex.getLocalizedMessage() );
            } catch (IOException ex1) {
                ;
            }
        }
    }
    
    /** */
    private static void writeBeforeExecute( Writer w, QueryDBTraceEvent event ) {

        try {
            
            w.append( "SQL: /session "); w.append( Objects.toString( event.getProperty("sessionId") ) ); w.append("/\n" );

            String text = event.getText();

            if( S.isNotNullOrEmpty(text) ) {
                w.append( text );
                w.append('\n');
            }
            
            writeParameters( w, event );
            w.flush();
            
        } catch (Throwable ex) {
            try {
                w.append( ex.getLocalizedMessage() );
            } catch (IOException ex1) {
                ;
            }
        }
    }

//    private static final DateTimeFormatter dtf = new DateTimeFormatterBuilder()
//            .optionalStart()//second
//            .optionalStart()//minute
//            .optionalStart()//hour
//            .optionalStart()//day
//            .optionalStart()//month
//            .optionalStart()//year
//            .appendValue(ChronoField.YEAR).appendLiteral(" Years ").optionalEnd()
//            .appendValue(ChronoField.MONTH_OF_YEAR).appendLiteral(" Months ").optionalEnd()
//            .appendValue(ChronoField.DAY_OF_MONTH).appendLiteral(" Days ").optionalEnd()
//            .appendValue(ChronoField.HOUR_OF_DAY).appendLiteral(" Hours ").optionalEnd()
//            .appendValue(ChronoField.MINUTE_OF_HOUR).appendLiteral(" Minutes ").optionalEnd()
//            .appendValue(ChronoField.SECOND_OF_MINUTE).appendLiteral(" Seconds").optionalEnd()
//            .toFormatter();

    /** */
    private static void writeAfterExecute( Writer w, QueryDBTraceEvent event ) {

        try {

            long millis = event.getDuration().toMillis();

            w.append( "RESULT: /session "); w.append( Objects.toString( event.getProperty("sessionId") ) );; w.append("/\n" );

            w.append("time: ").append(String.format("%02d.%02d.%02d:%02d",
                    TimeUnit.MILLISECONDS.toHours(millis),
                    TimeUnit.MILLISECONDS.toMinutes(millis) - TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(millis)),
                    TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis)),
                    TimeUnit.MILLISECONDS.toMillis(millis) - TimeUnit.SECONDS.toMillis(TimeUnit.MILLISECONDS.toSeconds(millis))
            ));
            //w.append( "time: " + LocalTime.ofSecondOfDay( event.getDuration().toMillis() ).toString() ).append('\n');
            w.append('\n');
            writeParameters( w, event );
            w.flush();
            
        } catch (Throwable ex) {
            try {
                w.append( ex.getLocalizedMessage() );
            } catch (IOException ex1) {
                ;
            }
        }
    }
    
    /** */
    private static void writeException( Writer w, QueryDBTraceEvent event ) {

        try {

            w.append( "ERROR: /session "); w.append( Objects.toString( event.getProperty("sessionId") ) ); w.append("/\n" );

            String text = event.getText();

            if( S.isNotNullOrEmpty(text) )
                w.append( text ).append('\n');
            
            writeParameters( w, event );
            
            Throwable th = event.getThrowable();
            if( th != null ) 
                th.printStackTrace( new PrintWriter(w) );
            
            w.append('\n');
            w.flush();
            
        } catch (Throwable ex) {
            try {
                w.append( ex.getLocalizedMessage() );
            } catch (IOException ex1) {
                ;
            }
        }
    }
    
    /** */
    private static void writeDbmsOutput( Writer w, QueryDBTraceEvent event ) {

        try {
        
            String text = event.getText();

            if( S.isNotNullOrEmpty(text) )
            {
                w.append( "DBMS_OUTPUT: /session "); w.append( Objects.toString( event.getProperty("sessionId") ) ); w.append("/\n" );
                w.append( text );
            }
            w.flush();

        } catch (Throwable ex) {
            try {
                w.append( ex.getLocalizedMessage() );
            } catch (IOException ex1) {
                ;
            }
        }
    }

    /** */
    private static String of( Object o ) {

        if( o == null )
            return "<null>";

        StringBuilder w = new StringBuilder();

        if( o == STUB_VALUE )
            w.append( STUB_VALUE );
        else {

            if(o.getClass() == String.class)
                w.append("\"").append(o.toString()).append("\"");
            else
                w.append(o.toString());

            w.append(" (").append(o.getClass().getSimpleName()).append(")");
        }

        return w.toString();
    }

    /** */
    private static void writeParameter( Writer w, Object parameterIndex, Object value ) {
        
        try {
            
            w.append("\t").append( S.nvl(parameterIndex) ).append( ": ");
            
            if( value == null )
                w.append( "<null>" );
            else {
                
                Class clazz = value.getClass();

                if( Array.class.isAssignableFrom( clazz ) ) {

                    try {

                        Array a = (Array)value;

                        List<Object> list = Arrays.asList( (Object[])a.getArray() );

                        w.append("ARRAY ( baseType - '" + a.getBaseTypeName() + "' ), size " + list.size() + ":- [ ");

                        int i = 0;

                        for( Object o : list ) {

                            if( i > 0 )
                                w.append(", " );

                            w.append( i + ": " + S.nvl(o) );

                            i++;
                        }

                        w.append(" ]");

                    }catch( SQLException ex ) {
                        w.append( "Error on write Array data. " + ex.getLocalizedMessage() );
                    }
                }
                else
                {
                    w.append( of(value) );
                }
            }
            
            w.append('\n');
            
        } catch (Throwable ex) {
            try {
                w.append( S.nvl(parameterIndex) ).append(": ").append( ex.getLocalizedMessage() );
            } catch (IOException ex1) {
                ;
            }
        }
    }
    
    /** */
    private static void writeParameters( Writer w, QueryDBTraceEvent event ) {
        
        Map parameters = event.getParameters( );
        
        if( parameters != null && !parameters.isEmpty() ) {
            parameters.forEach((Object t, Object u) -> {
                writeParameter( w, t, u );
            });
        }
    }
    
}
