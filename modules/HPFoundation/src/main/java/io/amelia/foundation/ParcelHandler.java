package io.amelia.foundation;

import java.util.function.Consumer;

/**
 * A {@link ParcelHandler} allows you to send and receive objects through
 * a thread's {@link Looper}. There is only one per {@link Looper}.
 * <p>
 * The main use for this class is to enqueue an action to be performed on a different thread.
 * <p>
 * Sending messages is accomplished with the {@link #sendEmptyInternalMessage}, {@link #sendInternalMessage},
 * {@link #sendInternalMessageAtTime}, and {@link #sendInternalMessageDelayed} methods.
 * The <em>post</em> versions allow you to enqueue Runnable objects to be called by the message queue when
 * they are received; the <em>sendInternalMessage</em> versions allow you to enqueue
 * a {@link ParcelCarrier} object containing a bundle of data that will be
 * processed by the Handler's {@link #handleInternalMessage} method (requiring that
 * you implement a subclass of Handler).
 * <p>
 * When posting or sending to a Handler, you can either
 * allow the item to be processed as soon as the message queue is ready
 * to do so, or specify a delay before it gets processed or absolute time for
 * it to be processed.  The latter two allow you to implement timeouts,
 * ticks, and other timing-based behavior.
 * <p>
 * When a process is created for your application, its main thread is dedicated to
 * running a message queue that takes care of managing the top-level
 * application tasks and messages.  You can create your own threads,
 * and communicate back with the main application thread through a Handler.
 */
public class ParcelHandler
{
	final boolean async;
	final Consumer<ParcelCarrier> callback;
	final Looper looper;

	/**
	 * Default constructor associates this handler with the {@link Looper} for the
	 * current thread.
	 * <p>
	 * If this thread does not have a looper, this handler won't be able to receive messages
	 * so an exception is thrown.
	 */
	public ParcelHandler()
	{
		this( null, false );
	}

	/**
	 * Constructor associates this handler with the {@link Looper} for the
	 * current thread and takes a callback interface in which you can handle
	 * messages.
	 * <p>
	 * If this thread does not have a looper, this handler won't be able to receive messages
	 * so an exception is thrown.
	 *
	 * @param callback The callback interface in which to handle messages, or null.
	 */
	public ParcelHandler( Consumer<ParcelCarrier> callback )
	{
		this( callback, false );
	}

	/**
	 * Use the provided {@link Looper} instead of the default one.
	 *
	 * @param looper The looper, must not be null.
	 */
	public ParcelHandler( Looper looper )
	{
		this( looper, null, false );
	}

	/**
	 * Use the provided {@link Looper} instead of the default one and take a callback
	 * interface in which to handle messages.
	 *
	 * @param looper   The looper, must not be null.
	 * @param callback The callback interface in which to handle messages, or null.
	 */
	public ParcelHandler( Looper looper, Consumer<ParcelCarrier> callback )
	{
		this( looper, callback, false );
	}

	/**
	 * Use the {@link Looper} for the current thread
	 * and set whether the handler should be asynchronous.
	 * <p>
	 * Handlers are synchronous by default unless this constructor is used to make
	 * one that is strictly asynchronous.
	 * <p>
	 * Asynchronous messages represent interrupts or events that do not require global ordering
	 * with respect to synchronous messages.  Asynchronous messages are not subject to
	 * the synchronization barriers introduced by {@link LooperQueue#postTaskBarrier(long)}.
	 *
	 * @param async If true, the handler calls {@link ParcelCarrier#setAsync(boolean)} for
	 *              each {@link ParcelCarrier} that is sent to it or {@link Runnable} that is posted to it.
	 *
	 * @hide
	 */
	public ParcelHandler( boolean async )
	{
		this( null, async );
	}

	/**
	 * Use the {@link Looper} for the current thread with the specified callback interface
	 * and set whether the handler should be asynchronous.
	 * <p>
	 * Handlers are synchronous by default unless this constructor is used to make
	 * one that is strictly asynchronous.
	 * <p>
	 * Asynchronous messages represent interrupts or events that do not require global ordering
	 * with respect to synchronous messages.  Asynchronous messages are not subject to
	 * the synchronization barriers introduced by {@link LooperQueue#postTaskBarrier(long)}.
	 *
	 * @param callback The callback interface in which to handle messages, or null.
	 * @param async    If true, the handler calls {@link ParcelCarrier#setAsync(boolean)} for
	 *                 each {@link ParcelCarrier} that is sent to it or {@link Runnable} that is posted to it.
	 *
	 * @hide
	 */
	public ParcelHandler( Consumer<ParcelCarrier> callback, boolean async )
	{
		looper = Looper.Factory.obtain();
		if ( looper == null )
			throw new RuntimeException( "Can't create handler inside thread that has not called Looper.prepare()" );
		looper.queue = looper.getQueue();
		this.callback = callback;
		this.async = async;
	}

	/**
	 * Use the provided {@link Looper} instead of the default one and take a callback
	 * interface in which to handle messages.  Also set whether the handler
	 * should be asynchronous.
	 * <p>
	 * Handlers are synchronous by default unless this constructor is used to make
	 * one that is strictly asynchronous.
	 * <p>
	 * Asynchronous messages represent interrupts or events that do not require global ordering
	 * with respect to synchronous messages.  Asynchronous messages are not subject to
	 * the synchronization barriers introduced by {@link LooperQueue#postTaskBarrier(long)}.
	 *
	 * @param looper   The looper, must not be null.
	 * @param callback The callback interface in which to handle messages, or null.
	 * @param async    If true, the handler calls {@link ParcelCarrier#setAsync(boolean)} for
	 *                 each {@link ParcelCarrier} that is sent to it or {@link Runnable} that is posted to it.
	 *
	 * @hide
	 */
	public ParcelHandler( Looper looper, Consumer<ParcelCarrier> callback, boolean async )
	{
		this.looper = looper;
		looper.queue = looper.getQueue();
		this.callback = callback;
		this.async = async;
	}

	/**
	 * Handle system messages here.
	 */
	public void dispatchInternalMessage( ParcelCarrier msg )
	{
		if ( msg.callback != null )
		{
			msg.callback.accept( msg );
		}
		else
		{
			if ( callback != null )
			{
				if ( callback.handleMessage( msg ) )
				{
					return;
				}
			}
			handleInternalMessage( msg );
		}
	}

	private boolean enqueueInternalMessage( LooperQueue queue, ParcelCarrier msg, long uptimeMillis )
	{
		msg.target = this;
		if ( async )
			msg.setAsync( true );
		return queue.postMessage( msg, uptimeMillis );
	}

	/**
	 * Returns a string representing the name of the specified message.
	 * The default implementation will either return the class name of the
	 * message callback if any, or the hexadecimal representation of the
	 * message "what" field.
	 *
	 * @param message The message whose name is being queried
	 */
	public String getInternalMessageName( ParcelCarrier message )
	{
		if ( message.callback != null )
		{
			return message.callback.getClass().getName();
		}
		return "0x" + Integer.toHexString( message.what );
	}

	// if we can get rid of this method, the handler need not remember its loop
	// we could instead export a getLooperQueue() method...
	public final Looper getLooper()
	{
		return looper;
	}

	/**
	 * {@hide}
	 */
	public String getTraceName( ParcelCarrier message )
	{
		final StringBuilder sb = new StringBuilder();
		sb.append( getClass().getName() ).append( ": " );
		if ( message.callback != null )
		{
			sb.append( message.callback.getClass().getName() );
		}
		else
		{
			sb.append( "#" ).append( message.what );
		}
		return sb.toString();
	}

	/**
	 * Subclasses must implement this to receive messages.
	 */
	public void handleInternalMessage( ParcelCarrier msg )
	{
	}

	/**
	 * Returns a new {@link ParcelCarrier} from the global message pool. More efficient than
	 * creating and allocating new instances. The retrieved message has its handler set to this instance (InternalMessage.target == this).
	 * If you don't want that facility, just call InternalMessage.obtain() instead.
	 */
	public final ParcelCarrier obtainInternalMessage()
	{
		return ParcelCarrier.obtain( this );
	}

	/**
	 * Sends a InternalMessage containing only the what value.
	 *
	 * @return Returns true if the message was successfully placed in to the
	 * message queue.  Returns false on failure, usually because the
	 * looper processing the message queue is exiting.
	 */
	public final boolean sendEmptyInternalMessage( int what )
	{
		return sendEmptyInternalMessageDelayed( what, 0 );
	}

	/**
	 * Sends a InternalMessage containing only the what value, to be delivered
	 * at a specific time.
	 *
	 * @return Returns true if the message was successfully placed in to the
	 * message queue.  Returns false on failure, usually because the
	 * looper processing the message queue is exiting.
	 *
	 * @see #sendInternalMessageAtTime(ParcelCarrier, long)
	 */

	public final boolean sendEmptyInternalMessageAtTime( int what, long uptimeMillis )
	{
		ParcelCarrier msg = ParcelCarrier.obtain();
		msg.what = what;
		return sendInternalMessageAtTime( msg, uptimeMillis );
	}

	/**
	 * Sends a InternalMessage containing only the what value, to be delivered
	 * after the specified amount of time elapses.
	 *
	 * @return Returns true if the message was successfully placed in to the
	 * message queue.  Returns false on failure, usually because the
	 * looper processing the message queue is exiting.
	 *
	 * @see #sendInternalMessageDelayed(ParcelCarrier, long)
	 */
	public final boolean sendEmptyInternalMessageDelayed( int what, long delayMillis )
	{
		ParcelCarrier msg = ParcelCarrier.obtain();
		msg.what = what;
		return sendInternalMessageDelayed( msg, delayMillis );
	}

	/**
	 * Pushes a message onto the end of the message queue after all pending messages
	 * before the current time. It will be received in {@link #handleInternalMessage},
	 * in the thread attached to this handler.
	 *
	 * @return Returns true if the message was successfully placed in to the
	 * message queue.  Returns false on failure, usually because the
	 * looper processing the message queue is exiting.
	 */
	public final boolean sendInternalMessage( ParcelCarrier msg )
	{
		return sendInternalMessageDelayed( msg, 0 );
	}

	/**
	 * Enqueue a message at the front of the message queue, to be processed on
	 * the next iteration of the message loop.  You will receive it in
	 * {@link #handleInternalMessage}, in the thread attached to this handler.
	 * <b>This method is only for use in very special circumstances -- it
	 * can easily starve the message queue, cause ordering problems, or have
	 * other unexpected side-effects.</b>
	 *
	 * @return Returns true if the message was successfully placed in to the
	 * message queue.  Returns false on failure, usually because the
	 * looper processing the message queue is exiting.
	 */
	public final boolean sendInternalMessageAtFrontOfQueue( ParcelCarrier msg )
	{
		LooperQueue queue = looper.queue;
		if ( queue == null )
		{
			RuntimeException e = new RuntimeException( this + " sendInternalMessageAtTime() called with no looper.queue" );
			Foundation.L.warning( e.getInternalMessage(), e );
			return false;
		}
		return enqueueInternalMessage( queue, msg, 0 );
	}

	/**
	 * Enqueue a message into the message queue after all pending messages
	 * before the absolute time (in milliseconds) <var>uptimeMillis</var>.
	 * <b>The time-base is {@link Kernel#uptime()}.</b>
	 * Time spent in deep sleep will add an additional delay to execution.
	 * You will receive it in {@link #handleInternalMessage}, in the thread attached
	 * to this handler.
	 *
	 * @param uptimeMillis The absolute time at which the message should be
	 *                     delivered, using the
	 *                     {@link Kernel#uptime()} time-base.
	 *
	 * @return Returns true if the message was successfully placed in to the
	 * message queue.  Returns false on failure, usually because the
	 * looper processing the message queue is exiting.  Note that a
	 * activeState of true does not mean the message will be processed -- if
	 * the looper is quit before the delivery time of the message
	 * occurs then the message will be dropped.
	 */
	public boolean sendInternalMessageAtTime( ParcelCarrier msg, long uptimeMillis )
	{
		LooperQueue queue = looper.queue;
		if ( queue == null )
		{
			RuntimeException e = new RuntimeException( this + " sendInternalMessageAtTime() called with no looper.queue" );
			Looper.L.warning( "Looper", e );
			return false;
		}
		return enqueueInternalMessage( queue, msg, uptimeMillis );
	}

	/**
	 * Enqueue a message into the message queue after all pending messages
	 * before (current time + delayMillis). You will receive it in
	 * {@link #handleInternalMessage}, in the thread attached to this handler.
	 *
	 * @return Returns true if the message was successfully placed in to the
	 * message queue.  Returns false on failure, usually because the
	 * looper processing the message queue is exiting.  Note that a
	 * activeState of true does not mean the message will be processed -- if
	 * the looper is quit before the delivery time of the message
	 * occurs then the message will be dropped.
	 */
	public final boolean sendInternalMessageDelayed( ParcelCarrier msg, long delayMillis )
	{
		if ( delayMillis < 0 )
			delayMillis = 0;
		return sendInternalMessageAtTime( msg, Kernel.uptime() + delayMillis );
	}

	@Override
	public String toString()
	{
		return "Handler (" + getClass().getName() + ") {" + Integer.toHexString( System.identityHashCode( this ) ) + "}";
	}

}