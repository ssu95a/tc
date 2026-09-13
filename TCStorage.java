package ru.inversion.tc;

import ru.inversion.utils.lstn.IListenerManConsumer;
import ru.inversion.utils.lstn.ListenerManFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Predicate;


/**
 * Хранилище активных TaskContext.
 * <p>
 * TCStorage владеет зарегистрированными TaskContext до их явного удаления
 * либо до закрытия самого TCStorage.
 *
 * @author ssu
 */
public class TCStorage implements AutoCloseable
{
    private static final TCStorage instance = new TCStorage();

    private final Lock lock = new ReentrantLock(true);

    /*
     * экземпляры TaskContext'ов.
     */
    private final Set<TaskContext> contexts = Collections.newSetFromMap( new IdentityHashMap<>() );

    private final IListenerManConsumer<BiConsumer<TaskContext, Boolean>> listeners = ListenerManFactory.createListenerManConsumer();

    private boolean closing;
    private boolean closed;


    /** */
    private TCStorage()
    { }


    /** */
    public static TCStorage INSTANCE()
    {
        return instance;
    }


    /** */
    public void addListener( BiConsumer<TaskContext, Boolean> listener )
    {
        listeners.addListener(listener);
    }


    /** */
    public void removeListener( BiConsumer<TaskContext, Boolean> listener )
    {
        listeners.removeListener(listener);
    }


    /** */
    protected void fireOnCreate( TaskContext tc )
    {
        if(!listeners.isEmpty() )
            listeners.fire(listener -> listener.accept(tc, true) );
    }


    /** */
    protected void fireOnClose( TaskContext tc )
    {
        if( !listeners.isEmpty() )
            listeners.fire(listener -> listener.accept(tc, false) );
    }


    /**
     * Ищет TaskContext по произвольному условию.
     */
    public TaskContext getBy( Predicate<TaskContext> finder )
    {
        if( finder == null )
            return null;

        for( TaskContext tc : snapshot() )
        {
            if( finder.test(tc) )
                return tc;
        }

        return null;
    }


    /**
     * Ищет TaskContext по JDBC Connection.
     */
    public TaskContext getByConnection( Connection connection )
    {
        if( connection == null )
            return null;

        for( TaskContext tc : snapshot() )
        {
            try
            {
                Connection tcConnection = tc.getConnection();

                if( tcConnection == connection )
                    return tc;

                if( tcConnection.unwrap(Connection.class) == connection )
                    return tc;
            }
            catch( SQLException | IllegalStateException ignored )
            { }
        }

        return null;
    }


    /**
     * Регистрирует TaskContext.
     */
    void add( TaskContext tc )
    {
        if( tc == null )
            throw new IllegalArgumentException("TaskContext is null");

        boolean added;

        lock.lock();

        try
        {
            if( closing || closed )
                throw new IllegalStateException("TCStorage is closed");

            added = contexts.add(tc);
        }
        finally {
            lock.unlock();
        }

        /* Listener вызываем после изменения внутреннего набора и вне lock. */
        if( added )
            fireOnCreate(tc);
    }


    /**
     * Удаляет TaskContext.
     */
    void remove( TaskContext tc )
    {
        if( tc == null )
            return;

        boolean removed;

        lock.lock();

        try
        {
            removed = contexts.remove(tc);
        }
        finally
        {
            lock.unlock();
        }

        /*
         * В том числе работает во время TCStorage.close().
         */
        if( removed )
            fireOnClose(tc);
    }


    /**
     * Возвращает список активных TaskContext.
     */
    public List<TaskContext> getList()
    {
        return Collections.unmodifiableList( snapshot() );
    }


    /**
     * Закрывает все зарегистрированные TaskContext.
     */
    @Override
    public void close()
    {
        List<TaskContext> snapshot;

        lock.lock();

        try
        {
            if( closing || closed )
                return;

            closing = true;

            snapshot = new ArrayList<>(contexts);
        }
        finally
        {
            lock.unlock();
        }

        try
        {
            for( TaskContext tc : snapshot )
            {
                try
                {
                    tc.close();
                }
                catch( ThreadDeath | VirtualMachineError fatal )
                {
                    throw fatal;
                }
                catch( Throwable ignored )
                {
                    /*
                     * Закрытие одного TaskContext не должно мешать
                     * закрытию остальных.
                     *
                     * TODO diagnostics.
                     */
                }
            }
        }
        finally
        {
            lock.lock();

            try
            {
                /*
                 * В нормальном случае contexts уже пуст:
                 * TaskContext.close() -> TCStorage.remove().
                 *
                 * clear() оставлен как страховка на случай ошибки
                 * внутри конкретного TaskContext.close().
                 */
                contexts.clear();

                closing = false;
                closed = true;
            }
            finally
            {
                lock.unlock();
            }
        }
    }


    /**
     * Делает snapshot внутреннего списка.
     */
    private List<TaskContext> snapshot()
    {
        lock.lock();

        try {
            return new ArrayList<>(contexts);
        }
        finally {
            lock.unlock();
        }
    }
}