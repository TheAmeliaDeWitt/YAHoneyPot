package io.amelia.foundation;

import io.amelia.foundation.tasks.Tasks;
import io.amelia.lang.ApplicationException;
import io.amelia.logcompat.LogBuilder;
import io.amelia.support.Runlevel;

/**
 * Implements a basic application environment with modules Config, Tasks, Events.
 */
public abstract class DefaultApplication extends ApplicationInterface
{
	private static String stopReason = null;

	static
	{
		System.setProperty( "file.encoding", "utf-8" );
	}

	public DefaultApplication()
	{
		// CommandDispatch.handleCommands();
	}

	@Override
	public String getName()
	{
		return getClass().getSimpleName();
	}

	@Override
	public void onRunlevelChange( Runlevel previousRunlevel, Runlevel currentRunlevel ) throws ApplicationException.Error
	{
		if ( currentRunlevel == Runlevel.MAINLOOP )
			getLooper().postTaskRepeatingLater( () -> Tasks.heartbeat( Foundation.getLooper().getLastPolledMillis() ), 50L, 50L );
		if ( currentRunlevel == Runlevel.SHUTDOWN )
		{
			LogBuilder.get().info( "Shutting Down Task Manager..." );
			Tasks.shutdown();

			LogBuilder.get().info( "Saving Configuration..." );
			ConfigRegistry.save();

			try
			{
				Foundation.L.info( "Clearing Excess Cache..." );
				long keepHistory = ConfigRegistry.config.getLong( "advanced.execute.keepHistory" ).orElse( 30L );
				ConfigRegistry.clearCache( keepHistory );
			}
			catch ( IllegalArgumentException e )
			{
				Foundation.L.warning( "Cache directory is invalid!" );
			}
		}
	}
}
