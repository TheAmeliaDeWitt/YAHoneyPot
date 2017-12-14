package io.amelia.foundation;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.amelia.lang.ApplicationException;
import io.amelia.logcompat.LogBuilder;
import io.amelia.logcompat.Logger;
import io.amelia.support.Objs;

/**
 * The Looper is intended to be interfaced by the thread that intends to execute tasks or oversee the process.
 */
public class Looper
{
	public static final Logger L = LogBuilder.get( Looper.class );

	/**
	 * Used to increment globally used unique numbers
	 */
	private static volatile AtomicLong UNIQUE = new AtomicLong( 0L );

	/**
	 * Stores the Loopers
	 */
	private static volatile NavigableSet<Looper> loopers = new TreeSet<>();

	public static long getGloballyUniqueId()
	{
		long id = UNIQUE.getAndIncrement();

		/*
		 * Just as a safety check in case the app is running for like a millennia.
		 * Should probably also check that we don't encounter in use numbers but that's probably even more rare.
		 */
		if ( Long.MAX_VALUE - id == 0 )
			UNIQUE.set( 0L );

		return id;
	}

	/**
	 * The Looper Queue
	 */
	final LooperQueue queue = new LooperQueue( this );
	/**
	 * List of threads that were spawned by this Looper.
	 * Used to obtain() this Looper from a async thread.
	 */
	private final List<WeakReference<Thread>> aliasThreads = new ArrayList<>();
	/**
	 * The Looper Flags
	 */
	private final EnumSet<Flag> flags = EnumSet.noneOf( Flag.class );
	/**
	 * Idle Handlers that run when the queue is empty
	 */
	private final Map<Long, Predicate<Looper>> idleHandlers = new HashMap<>();
	/**
	 * Used to synchronize certain methods with the loop, so to avoid concurrent and/or race issues
	 */
	private final ReentrantLock lock = new ReentrantLock();
	/**
	 * The Looper state
	 */
	private final EnumSet<State> states = EnumSet.noneOf( State.class );
	/**
	 * States the average millis between iterations.
	 */
	private long averagePolledMillis = 0L;
	/**
	 * Indicates the Looper is overloaded.
	 */
	private boolean isOverloaded = false;
	/**
	 * Stores the amount of time that has past between iterations.
	 */
	private long lastPolledMillis = 0L;
	/**
	 * Reference to the thread running this Looper.
	 * Remains null until {@link #joinLoop()} is called.
	 */
	private Thread thread = null;

	Looper( Flag... flags )
	{
		this.flags.addAll( Arrays.asList( flags ) );
	}

	void addChildThread( Thread thread )
	{
		synchronized ( aliasThreads )
		{
			for ( WeakReference<Thread> reference : aliasThreads )
				if ( reference.get() == null )
					aliasThreads.remove( reference );

			aliasThreads.add( new WeakReference<>( thread ) );
			thread.setName( getThread().getName() + "-" + aliasThreads.size() );
		}
	}

	public void addFlag( Flag flag )
	{
		if ( isRunning() )
			throw ApplicationException.ignorable( "You can't modify Looper flags while it's running." );
		if ( flag == Flag.SYSTEM )
			throw ApplicationException.runtime( "System Loopers are the domain of the application Kernel." );
		if ( flag == Flag.PLUGIN )
			throw ApplicationException.runtime( "Plugin Loopers are the domain of the PluginManager." );

		flags.add( flag );
	}

	void addState( State state )
	{
		states.add( state );
	}

	public long getAveragePolledMillis()
	{
		return averagePolledMillis;
	}

	public long getLastPolledMillis()
	{
		return lastPolledMillis;
	}

	public String getName()
	{
		return "Looper " + getThread().getName();
	}

	/**
	 * Get the TaskQueue associated with this Looper
	 */
	public LooperQueue getQueue()
	{
		return queue;
	}

	/**
	 * Gets the Thread running this Looper.
	 */
	public Thread getThread()
	{
		return thread;
	}

	public boolean hasFlag( Flag flag )
	{
		return flags.contains( flag );
	}

	/**
	 * Returns true if this Looper belongs to this thread.
	 */
	public boolean isCurrentThread()
	{
		if ( thread == null )
			return false;
		Thread currentThread = Thread.currentThread();
		if ( thread == currentThread )
			return true;
		synchronized ( aliasThreads )
		{
			for ( WeakReference<Thread> threadReference : aliasThreads )
				if ( threadReference.get() == null )
					aliasThreads.remove( threadReference );
				else if ( threadReference.get() == currentThread )
					return true;
		}
		return false;
	}

	/**
	 * Returns true if this Looper has an average millis of over 100ms with each iteration.
	 */
	public boolean isOverloaded()
	{
		return isOverloaded;
	}

	/**
	 * Returns true if this Looper is currently working on quitting. No more tasks will be accepted by the Queue.
	 */
	public boolean isQuitting()
	{
		return states.contains( State.QUITTING );
	}

	/**
	 * Returns true if this Looper is currently running, i.e., a thread is actively calling the {@link #joinLoop()} method.
	 */
	public boolean isRunning()
	{
		return thread != null;
	}

	/**
	 * Joins the thread to this looper until it quits.
	 */
	public void joinLoop()
	{
		thread = Thread.currentThread();

		// Attempt to acquire the lock on the Looper, as to force outside calls to only process while Looper is asleep.
		lock.lock();

		try
		{
			// Stores the last time the overload warning was displayed as to not flood the console.
			long lastWarningMillis = 0L;

			// Stores the last time the overload wait was called as to not delay the system all the more.
			long lastOverloadMillis = 0;

			for ( ; ; )
			{
				// Stores when the loop started.
				long loopStartMillis = System.currentTimeMillis();

				// Call the actual loop logic.
				LooperQueue.ActiveState result = queue.next( loopStartMillis );

				if ( result.resultCode == LooperQueue.RESULT_OK )
				{
					result.entry.markFinalized();

					if ( result.entry.isAsync() )
						runAsync( result.entry.getRunnable() );
					else
						result.entry.getRunnable().run();

					result.entry.recycle();
				}

				// Update the time taken during this iteration.
				lastPolledMillis = System.currentTimeMillis() - loopStartMillis;

				// Prevent negative numbers and warn
				if ( lastPolledMillis < 0L )
				{
					L.warning( "[" + getName() + "] Time ran backwards! Did the system time change?" );
					lastPolledMillis = 0L;
				}

				// Update the average millis once we know the lastPolledMillis from this last iteration
				averagePolledMillis = ( Math.min( lastPolledMillis, averagePolledMillis ) - Math.max( lastPolledMillis, averagePolledMillis ) ) / 2;

				// Are we on average taking more than 100ms per iteration and has it been more than 5 seconds since last overload warning?
				if ( averagePolledMillis > 100L )
				{
					if ( loopStartMillis - lastWarningMillis >= 15000L && ConfigRegistry.config.isTrue( ConfigRegistry.ConfigKeys.WARN_ON_OVERLOAD ) )
					{
						L.warning( "[" + getName() + "] Can't keep up! Did the system time change, or is it overloaded?" );
						lastWarningMillis = loopStartMillis;
					}
					isOverloaded = true;
				}
				else
					isOverloaded = false;

				// Delay was under the 50 millis cap, so we wait with timeout so the Looper can breath
				if ( lastPolledMillis < 50L )
					lock.newCondition().await( 50 - lastPolledMillis, TimeUnit.MILLISECONDS );

				// Are we overloaded and it has been more than 1 second since the last time we forced a call on wait()
				if ( isOverloaded && loopStartMillis - lastOverloadMillis > 1000L )
				{
					lock.newCondition().await( 20, TimeUnit.MILLISECONDS );
					lastOverloadMillis = loopStartMillis;
				}

				// Process the quit message now that all pending messages have been handled.
				if ( isQuitting() ) // TODO
					break;

				// Otherwise the delay was longer, so we need to go immediately to the next iteration
			}
		}
		catch ( Throwable t )
		{
			Kernel.handleExceptions( t );
		}
		finally
		{
			lock.unlock();
			thread = null;
		}
	}

	void quit( boolean removePendingMessages )
	{
		// TODO

		if ( hasFlag( Flag.SYSTEM ) && !Foundation.isPrimaryThread() )
			throw new IllegalStateException( "SYSTEM queues are not allowed to quit." );
		if ( isQuitting() )
			return;

		lock.lock();
		try
		{
			addState( State.QUITTING );

			final long now = Kernel.uptime();
			synchronized ( messages )
			{
				messages.removeIf( message -> {
					if ( removePendingMessages || message.getWhen() > now )
					{
						message.recycle();
						return true;
					}
					else
						return false;
				} );
			}

			// TODO Wake Queue
		}
		finally
		{
			lock.unlock();
		}
	}

	/**
	 * Quits the looper.
	 * <p>
	 * Causes the {@link #joinLoop()} method to terminate without processing any more messages in the queue.
	 * <p>
	 * Using this method may be unsafe because some messages may not be delivered
	 * before the looper terminates.  Consider using {@link #quitSafely} instead to ensure
	 * that all pending work is completed in an orderly manner.
	 *
	 * @see #quitSafely
	 */
	void quitAndDestroy()
	{
		quit( true );

		// TODO
		lock.lock();
		try
		{
			if ( thread != null )
				throw ApplicationException.ignorable( "Looper can't be destroyed while running." );

			addState( State.QUITTING );
			loopers.remove( this );
		}
		finally
		{
			lock.unlock();
		}
	}

	/**
	 * Quits the looper safely.
	 * <p>
	 * Causes the {@link #joinLoop()} method to terminate as soon as all remaining messages
	 * in the queue that are already due to be delivered have been handled.
	 * However pending delayed messages with due times in the future will not be
	 * delivered before the loop terminates.
	 */
	public void quitSafely()
	{
		quit( false );
	}

	void removeChildThread( Thread thread )
	{
		synchronized ( aliasThreads )
		{
			for ( WeakReference<Thread> threadReference : aliasThreads )
				if ( threadReference.get() == null || threadReference.get() == thread )
					aliasThreads.remove( threadReference );
		}
	}

	public void removeFlag( Flag flag )
	{
		if ( isRunning() )
			throw ApplicationException.ignorable( "You can't modify Looper flags while it's running." );
		if ( flag == Flag.SYSTEM )
			throw ApplicationException.runtime( "System Loopers are the domain of the application Kernel." );
		if ( flag == Flag.PLUGIN )
			throw ApplicationException.runtime( "Plugin Loopers are the domain of the PluginManager." );

		flags.remove( flag );
	}

	void removeState( State state )
	{
		states.remove( state );
	}

	/**
	 * Executes {@link Runnable} on a new thread, i.e., async.
	 * <p>
	 * We also add the new thread to the aliases, such that calls to
	 * the {@link Looper.Factory#obtain()} returns this {@link Looper}.
	 * <p>
	 * This also prevents a async task from creating a new Looper by accident.
	 */
	void runAsync( Runnable task )
	{
		Kernel.getExecutorParallel().execute( () -> {
			Thread thread = Thread.currentThread();
			addChildThread( thread );
			task.run();
			removeChildThread( thread );
		} );
	}

	@Override
	public String toString()
	{
		return "Looper (" + thread.getName() + ", threadId " + thread.getId() + ") {" + Integer.toHexString( System.identityHashCode( this ) ) + "}";
	}

	/**
	 * Wakes the Looper to resume task checking.
	 */
	void wake()
	{
		lock.newCondition().signalAll();
	}

	/**
	 * Looper Property Flags
	 */
	public enum Flag
	{
		/**
		 * Forces the Looper the spawn each enqueued task on a new thread, regardless of if it's ASYNC or not.
		 */
		ASYNC,
		/**
		 * Indicates the {@link LooperQueue#next(long)} can and will block while the queue is empty.
		 * This flag is default on any non-system Looper as to save CPU time.
		 */
		BLOCKING,
		/**
		 * Indicates the Looper is used for internal system tasks only, which includes but not limited to,
		 * the Main Loop, User Logins, Permissions, Log Subsystem, Networking, and more.
		 * System Loopers can not be terminated (or auto-quit) and will only shutdown when the entire application does.
		 */
		SYSTEM,
		/**
		 * Indicates the Looper is exclusive to a plugin loaded by the application and handles things such as data mining and analysis.
		 */
		PLUGIN,
		/**
		 * Indicates the Looper will auto-quit once the queue is empty.
		 */
		AUTO_QUIT
	}

	/**
	 * Looper States
	 */
	enum State
	{
		/**
		 * Indicates the Looper is actively trying to poll for new tasks.
		 */
		POLLING,
		/**
		 * Indicates the Looper has gone to sleep.
		 */
		STALLED,
		/**
		 * Indicates the Looper is waiting to quit, which will happen as soon as the queue is empty.
		 */
		QUITTING
	}

	public static final class Factory
	{
		/**
		 * Destroys the Looper associated with the calling Thread.
		 *
		 * @return Was a Looper found and successfully destroyed.
		 */
		public static boolean destroy()
		{
			Looper looper = peek();
			if ( looper != null )
			{
				looper.quitAndDestroy();
				return true;
			}
			else
				return false;
		}

		/**
		 * Returns the Looper associated with the Thread calling this method and that has passed the predicate provided.
		 *
		 * @param supplier  The Supplier used for initiating a new instance of Looper if none was found or it fails the Predicate.
		 * @param predicate The predicate used to evaluate the associated Looper.
		 *
		 * @return The associated Looper that also passed the provided Predicate, otherwise a new instance returned by the Supplier if none was found or it failed the predicate.
		 */
		static Looper obtain( @Nonnull Supplier<Looper> supplier, @Nullable Predicate<Looper> predicate )
		{
			Objs.notNull( supplier );

			Looper looper = peek();
			if ( looper == null )
			{
				looper = supplier.get();
				loopers.add( looper );
			}
			else if ( predicate != null && !predicate.test( looper ) )
			{
				// Looper failed the predicate, so it needs to be replaced.
				loopers.remove( looper );
				looper = supplier.get();
				loopers.add( looper );
			}
			return looper;
		}

		/**
		 * Returns the Looper associated with the Thread calling this method and that has passed the predicate provided.
		 *
		 * @param predicate The predicate used to evaluate the associated Looper.
		 *
		 * @return The associated Looper that also passed the provided Predicate, otherwise new if none was found or it failed the predicate.
		 */
		static Looper obtain( @Nullable Predicate<Looper> predicate )
		{
			return obtain( Looper::new, predicate );
		}

		/**
		 * Returns the Looper associated with the Thread calling this method.
		 *
		 * @return The associated Looper, otherwise a new Looper if none was found.
		 */
		public static Looper obtain()
		{
			return obtain( Looper::new, null );
		}

		/**
		 * Filter all current loopers using the predicate provided.
		 *
		 * @param predicate The predicate used to evaluate each Looper. Looper may or may not be in use elsewhere.
		 *
		 * @return Returns a stream of loopers that matched the predicate provided.
		 */
		static Stream<Looper> peek( @Nonnull Predicate<Looper> predicate )
		{
			return loopers.stream().filter( predicate );
		}

		/**
		 * Returns the Looper associated with the Thread calling this method.
		 *
		 * @return The associated Looper, otherwise {@code null} if none was found.
		 */
		static Looper peek()
		{
			return peek( Looper::isCurrentThread ).findFirst().orElse( null );
		}

		private Factory()
		{
			// Static Access
		}
	}
}