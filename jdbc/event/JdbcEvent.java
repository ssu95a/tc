package ru.inversion.tc.jdbc.event;

import java.util.EventObject;

public class JdbcEvent extends EventObject {

   private final EventType type;
   private final EventPhase phase;

   private final long timestampNanos;

   private final Throwable throwable;

   private final long connectionId;

   /** */
   public JdbcEvent( Object source, long connectionId, EventType type, EventPhase phase )
   {
      this( source, connectionId, type, phase, null );
   }

   /** */
   public JdbcEvent( Object source, long connectionId, EventType type, EventPhase phase, Throwable throwable )
   {
      super(source);

      if( connectionId <= 0 )
         throw new IllegalArgumentException("connectionId <= 0");

      if( type == null )
         throw new IllegalArgumentException("type is null");

      if( phase == null )
          throw new IllegalArgumentException("phase is null");

      this.type         = type;
      this.phase        = phase;
      this.throwable    = throwable;
      this.connectionId = connectionId;

      this.timestampNanos = System.nanoTime();
   }

   /** */
   public long connectionId( )
   {
      return connectionId;
   }

   /** */
   public EventType type() {
      return type;
   }

   /** */
   public EventPhase phase() {
      return phase;
   }

   /** */
   public long timestampNanos() {
      return timestampNanos;
   }

   /** */
   public Throwable throwable() {
      return throwable;
   }
}