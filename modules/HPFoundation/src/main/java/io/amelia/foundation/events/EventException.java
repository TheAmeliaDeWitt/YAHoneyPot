/**
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 * <p>
 * Copyright (c) 2017 Joel Greene <joel.greene@penoaks.com>
 * Copyright (c) 2017 Penoaks Publishing LLC <development@penoaks.com>
 * <p>
 * All Rights Reserved.
 */
package io.amelia.foundation.events;

import io.amelia.lang.ApplicationException;

public class EventException extends ApplicationException.Error
{
	private static final long serialVersionUID = 3532808232324183999L;

	/**
	 * Constructs a new EventException
	 */
	public EventException()
	{
		super();
	}

	/**
	 * Constructs a new EventException with the given message
	 *
	 * @param message The message
	 */
	public EventException( String message )
	{
		super( message );
	}

	/**
	 * Constructs a new EventException with the given message
	 *
	 * @param cause   The exception that caused this
	 * @param message The message
	 */
	public EventException( String message, Throwable cause )
	{
		super( message, cause );
	}

	/**
	 * Constructs a new EventException based on the given Exception
	 *
	 * @param cause Exception that triggered this Exception
	 */
	public EventException( Throwable cause )
	{
		super( cause );
	}
}