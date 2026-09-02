package ru.inversion.tc;

import ru.inversion.utils.lstn.IListenerManConsumer;
import ru.inversion.utils.lstn.ListenerManFactory;

import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 *
 * @author ssu
 */
public class TCStorage implements AutoCloseable {

    /** */
    private WeakReference<TaskContext>[] activeTaskContextList = new WeakReference[5];

    final private Lock lock = new ReentrantLock(true);

    private boolean closeMode = false;
    /** */
    final private static TCStorage instance = new TCStorage( );
    /** */
    public static TCStorage INSTANCE() { return instance; }

    /** */
    final private IListenerManConsumer< BiConsumer<TaskContext, Boolean> > listeners = ListenerManFactory.createListenerManConsumer();

    /** */
    private TCStorage( ) {
    }

    /** */
    public void addListener( BiConsumer<TaskContext, Boolean> l ) { listeners.addListener(l);}

    /** */
    public void removeListener( BiConsumer<TaskContext, Boolean> l ) { listeners.removeListener(l); }

    /** */
    protected void fireOnCreate( TaskContext tc )
    {
        if( !listeners.isEmpty() )
             listeners.fire( cns -> cns.accept( tc, true ) );
    }

    /** */
    protected void fireOnClose( TaskContext tc  )
    {
        if( !listeners.isEmpty() )
             listeners.fire( cns -> cns.accept( tc, false ) );
    }

    /** */
    public TaskContext getBy( Predicate<TaskContext> finder ) {

        final WeakReference< TaskContext >[] wtl = activeTaskContextList;

        for( WeakReference<TaskContext> w : wtl )
        {
            if( w == null )
                continue;

            final TaskContext tc = w.get();

            if( tc == null )
                continue;

            if( finder.test(tc) )
                return tc;
        }
        return null;
    }

    /** */
    public TaskContext getByConnection( Connection c ) {

        /*
        try {
            if( c == null || c.isClosed() )
                return null;
        } catch(SQLException e) {
            return null;
        }
        */

        if( c == null )
            return null;

        lock.lock();

        try {

        WeakReference< TaskContext >[] wtl = activeTaskContextList;

        for( WeakReference<TaskContext> w : wtl )
        {
            if( w == null )
                continue;

            final TaskContext tc = w.get();

            if( tc == null )
                continue;

            try {

                if( tc.getConnection() == c || tc.getConnection().unwrap( Connection.class ) == c )
                    return tc;
            }
            catch( SQLException ignored ) {
                ;
            }
        }

        return null;

        } finally {
            lock.unlock();
        }
    }

    /** */
    void add( TaskContext tc ) {

        lock.lock();

        try {

            WeakReference<TaskContext> w;

            for( int i = 0; i < activeTaskContextList.length; i++ )
            {
                w = activeTaskContextList[i];

                if( w == null || w.get() == null ) {
                    activeTaskContextList[i] = new WeakReference<>(tc);

                    fireOnCreate(tc);

                    return;
                }
            }

            activeTaskContextList = Arrays.copyOf( activeTaskContextList, activeTaskContextList.length + 3 );
            activeTaskContextList[activeTaskContextList.length-3] = new WeakReference<>(tc);

            fireOnCreate(tc);

        } finally {
            lock.unlock();
        }
    }

    /** */
    void remove( TaskContext tc ) {

        if( closeMode )
            return;

        lock.lock();

        try {

            WeakReference<TaskContext> w;

            for( int i = 0; i < activeTaskContextList.length; i++ )
            {
                w = activeTaskContextList[i];

                if( w != null && w.get() == tc ) {

                    activeTaskContextList[i] = null;

                    fireOnClose(tc);

                    return;
                }
            }

        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {

        closeMode = true;

        lock.lock();

        int nActive = 0;
        int nTotal  = 0;

        try {

            WeakReference<TaskContext> w;

            for( int i = 0; i < activeTaskContextList.length; i++ )
            {
                nTotal++;

                w = activeTaskContextList[i];

                if( w != null )
                {
                    final TaskContext tc = w.get();

                    if( tc != null ) {
                        tc.close();
                        nActive++;
                    }
                    activeTaskContextList[i] = null;
                }
            }

            activeTaskContextList = null;

            System.out.println("TCStorage close - active TC: " + nActive + " of " + nTotal );

        } finally {
            lock.unlock();
        }

    }

    /** */
    public List<TaskContext> getList( ) {

        final List<TaskContext> tcList = new ArrayList<>();

        lock.lock();

        try {

            WeakReference<TaskContext> w;

            for( int i = 0; i < activeTaskContextList.length; i++ )
            {
                w = activeTaskContextList[i];

                if( w != null )
                {
                    final TaskContext tc = w.get();

                    if( tc != null )
                        tcList.add(tc);
                    else
                        activeTaskContextList[i] = null;
                }
            }

        } finally {
            lock.unlock();
        }

        return Collections.unmodifiableList(tcList);
    }
}
