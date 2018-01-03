package io.amelia.looper.queue;

import java.util.EnumSet;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import io.amelia.support.TriEnum;

public abstract class AbstractQueue
{
	/**
	 * Queue flags - Blocking is default.
	 */
	private final EnumSet<Flag> flags;
	private AbstractEntry activeEntry = null;
	private Result activeResult = Result.NONE;
	private boolean isBlocking = false;
	private boolean isPolling = false;
	private ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private Condition blockingCondition = lock.writeLock().newCondition();

	public AbstractQueue( TriEnum<Flag> flags )
	{
		if ( flags.isUnset( Flag.BLOCKING ) )
			flags.allow( Flag.BLOCKING );

		this.flags = flags.toEnumSet();
	}

	public final void clearState()
	{
		lock.writeLock().lock();
		try
		{
			activeResult = Result.NONE;
			activeEntry = null;
		}
		finally
		{
			lock.writeLock().unlock();
		}
	}

	public AbstractEntry getActiveEntry()
	{
		return activeEntry;
	}

	public Result getActiveResult()
	{
		return activeResult;
	}

	public abstract long getEarliestEntry();

	public abstract long getLatestEntry();

	private boolean hasFlag( Flag flag )
	{
		return flags.contains( flag );
	}

	public abstract boolean hasPendingEntries();

	public final boolean isBlocking()
	{
		return isBlocking;
	}

	public boolean isEmpty()
	{
		return activeResult == Result.EMPTY;
	}

	/**
	 * Returns true if the looper has no pending entries which are due to be processed.
	 * <p>
	 * This method is safe to call from any thread.
	 *
	 * @return True if the looper is idle.
	 */
	public boolean isIdle()
	{
		return activeResult == Result.EMPTY || activeResult == Result.WAITING;
	}

	/**
	 * Returns whether this looper queue is currently polling for more work to do.
	 * This is a good signal that the loop is still alive rather than being stuck
	 * handling a callback.
	 *
	 * @return True if the looper is currently polling for entries.
	 */
	public final boolean isPolling()
	{
		return !isQuitting() && isPolling;
	}

	public abstract boolean isQuitting();

	public boolean isStalled()
	{
		return activeResult == Result.STALLED;
	}

	public boolean isWaiting()
	{
		return activeResult == Result.WAITING;
	}

	public final boolean isWriteLockedByCurrentThread()
	{
		return lock.isWriteLockedByCurrentThread();
	}

	public Result next( long now )
	{
		isPolling = true;
		try
		{
			for ( ; ; )
			{
				Lock writeLock = lock.writeLock();
				writeLock.lock();
				try
				{
					// If the current entry is null, poll for the next upcoming entry.
					if ( activeEntry == null )
						activeEntry = pollNext();

					// If it is still null assume the queue is effectively empty.
					if ( activeEntry == null )
					{
						activeResult = Result.EMPTY;

						if ( hasFlag( Flag.BLOCKING ) )
						{
							try
							{
								isBlocking = true;
								try
								{
									blockingCondition.await();
								}
								catch ( InterruptedException ignore )
								{
									// Ignore
								}
								return next( now );
							}
							finally
							{
								isBlocking = false;
							}
						}
					}
					else
					{
						activeResult = processEntry( activeEntry, now );

						if ( activeResult == null )
						{
							activeEntry = null;
							continue;
						}
					}

					return activeResult;
				}
				finally
				{
					writeLock.unlock();
				}
			}
		}
		finally
		{
			isPolling = false;
		}
	}

	protected abstract AbstractEntry pollNext();

	public final <T extends AbstractEntry> T postEntry( T entry )
	{
		if ( isQuitting() )
			throw new IllegalStateException( "The LooperQueue is quitting!" );

		lock.writeLock().lock();

		try
		{
			postEntry0( entry );
			return entry;
		}
		finally
		{
			lock.writeLock().unlock();
		}
	}

	/**
	 * Posts an entry to the implemented entry queue.
	 *
	 * @param entry The Entry to be posted
	 */
	protected abstract void postEntry0( AbstractEntry entry );

	/**
	 * Process the active entry.
	 *
	 * @param activeEntry
	 * @param now
	 *
	 * @return The Result associated with the provided entry, returning null indicates an entry type that's internally handled.
	 */
	protected abstract Result processEntry( AbstractEntry activeEntry, long now );

	abstract void quit( boolean removePendingMessages );

	protected final Lock readLock()
	{
		return lock.readLock();
	}

	public void wake()
	{
		// We only need to try signaling if we are legitimately blocking.
		if ( !isBlocking )
			return;

		lock.writeLock().lock();
		try
		{
			// IllegalMonitorStateException thrown if we don't acquire the write lock;
			blockingCondition.signalAll();
		}
		finally
		{
			lock.writeLock().unlock();
		}
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
		 * Indicates the {@link #next(long)} can and will block while the queue is empty.
		 * This flag is default on any non-system queue as to save CPU time.
		 */
		BLOCKING
	}

	enum Result
	{
		/**
		 * Indicates the queue is new or something has changed since the last time next() was called.
		 */
		NONE,
		/**
		 * Indicates an empty queue.
		 */
		EMPTY,
		/**
		 * Indicates the queue has encountered a stall barrier, we are waiting for it removal.
		 */
		STALLED,
		/**
		 * Indicates the next enqueued task has been returned by next().
		 */
		SUCCESS,
		/**
		 * Indicates the next enqueued task is still in the future, so we wait.
		 */
		WAITING
	}
}