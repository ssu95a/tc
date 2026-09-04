package ru.inversion.tc.jdbc.event;

import java.util.EventObject;

/** */
public class JdbcEvent extends EventObject
{
   private final EventType  type;
   private final EventPhase phase;

   private final long timestampNanos;

   private final Throwable throwable;

   /** */
   public JdbcEvent(
           Object source,
           EventType type,
           EventPhase phase
   )
   {
      this(
              source,
              type,
              phase,
              null
      );
   }


   /** */
   public JdbcEvent(
           Object source,
           EventType type,
           EventPhase phase,
           Throwable throwable
   )
   {
      super(source);

      if( type == null )
         throw new IllegalArgumentException("type is null");

      if( phase == null )
         throw new IllegalArgumentException("phase is null");

      this.type      = type;
      this.phase     = phase;
      this.throwable = throwable;

      this.timestampNanos = System.nanoTime();
   }


   public EventType type()
   {
      return type;
   }


   public EventPhase phase()
   {
      return phase;
   }


   public long timestampNanos()
   {
      return timestampNanos;
   }


   public Throwable throwable()
   {
      return throwable;
   }
}