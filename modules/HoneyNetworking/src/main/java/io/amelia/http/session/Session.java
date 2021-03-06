/**
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 * <p>
 * Copyright (c) 2019 Amelia Sara Greene <barelyaprincess@gmail.com>
 * Copyright (c) 2019 Penoaks Publishing LLC <development@penoaks.com>
 * <p>
 * All Rights Reserved.
 */
package io.amelia.http.session;

import com.google.common.base.Joiner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import io.amelia.data.parcel.Parcel;
import io.amelia.events.Events;
import io.amelia.foundation.ConfigRegistry;
import io.amelia.http.HoneyCookie;
import io.amelia.http.Nonce;
import io.amelia.http.events.SessionDestroyEvent;
import io.amelia.http.webroot.Webroot;
import io.amelia.http.webroot.WebrootRegistry;
import io.amelia.lang.ParcelableException;
import io.amelia.lang.SessionException;
import io.amelia.scripting.ScriptingSession;
import io.amelia.support.DateAndTime;
import io.amelia.support.EnumColor;
import io.amelia.support.HttpCookie;
import io.amelia.support.Objs;
import io.amelia.support.Strs;
import io.amelia.support.WeakReferenceList;
import io.amelia.users.Kickable;
import io.amelia.users.UserContext;
import io.amelia.users.UserException;
import io.amelia.users.UserPermissible;
import io.amelia.users.UserResult;
import io.amelia.users.auth.UserAuthenticator;

/**
 * This class is used to carry data that is to be persistent from request to request.
 * If you need to sync data across requests then we recommend using Session Vars for Security.
 */
public final class Session extends UserPermissible implements Kickable, ScriptingSession
{
	/**
	 * The underlying data for this session<br>
	 * Preserves access to the datastore and it's methods {@link SessionData#save()}, {@link SessionData#reload()}, {@link SessionData#destroy()}
	 */
	final SessionData data;
	/**
	 * Global session variables<br>
	 * Globals will not live outside of the session's life
	 */
	final Map<String, Object> globals = new LinkedHashMap<>();
	/**
	 * History of changes made to the variables since last {@link #save()}
	 */
	private final Set<String> dataChangeHistory = new HashSet<>();
	/**
	 * Holds a set of known IP Addresses
	 */
	private final Set<String> knownIps = new HashSet<>();
	/**
	 * The sessionId of this session
	 */
	private final String sessionId;
	/**
	 * Reference to each wrapper that is utilizing this session<br>
	 * We use a WeakReference so they can still be reclaimed by the GC
	 */
	private final WeakReferenceList<SessionWrapper> wrappers = new WeakReferenceList<>();
	// Indicates if the Session has been unloaded or destroyed!
	boolean isInvalidated = false;
	boolean newSession = false;
	private Nonce nonce = null;
	/**
	 * Number of times this session has been requested<br>
	 * More requests mean longer TTL
	 */
	private int requestCnt = 0;
	/**
	 * The Session Cookie
	 */
	private HoneyCookie sessionCookie;
	/**
	 * Tracks session sessionCookies
	 */
	private Set<HoneyCookie> sessionCookies = new HashSet<>();
	/**
	 * The sessionKey of this session
	 */
	private String sessionKey;
	/**
	 * The epoch for when this session is to be destroyed
	 */
	private long timeout = 0;
	/**
	 * The wenroot this session is bound to
	 */
	private Webroot webroot;

	Session( @Nonnull SessionData data ) throws SessionException.Error
	{
		this.data = data;

		sessionId = data.sessionId;

		sessionKey = data.sessionName;
		timeout = data.timeout;
		Strs.split( data.ipAddress, "|" ).forEach( knownIps::add );
		webroot = WebrootRegistry.getWebrootById( data.webroot ).orElseGet( WebrootRegistry::getDefaultWebroot );
		if ( !webroot.getWebrootId().equals( data.webroot ) )
			data.webroot = webroot.getWebrootId(); // Updates changed and invalid webroot ids

		timeout = data.timeout;

		if ( timeout > 0 && timeout < DateAndTime.epoch() )
			throw SessionException.error( String.format( "The session '%s' expired at epoch '%s', might have expired while offline or this is a bug!", sessionId, timeout ) );

		/*
		 * TODO Figure out how to track if a particular wrapper's IP changes
		 * Maybe check the original IP the wrapper was authenticated with
		 * TCP IP: ?
		 * HTTP IP: ?
		 *
		 * String origIpAddress = lastIpAddress;
		 *
		 * // Possible Session Hijacking! nullify!!!
		 * if ( lastIpAddress != null && !lastIpAddress.equals( origIpAddress ) && !Loader.getConfig().getBoolean( "sessions.allowIPChange" ) )
		 * {
		 * sessionCookie = null;
		 * lastIpAddress = origIpAddress;
		 * }
		 */

		// XXX New Session, Requested Session, Loaded Session

		if ( SessionRegistry.isDebug() )
			SessionRegistry.L.info( EnumColor.DARK_AQUA + "Session " + ( data.stale ? "Loaded" : "Created" ) + " `" + this + "`" );

		initialized();
	}

	public String changeSessionId()
	{
		throw new RuntimeException( "Not Implemented" );
	}

	public boolean changesMade()
	{
		return !isInvalidated && dataChangeHistory.size() > 0;
	}

	public void destroy() throws SessionException.Error
	{
		destroy( SessionRegistry.MANUAL );
	}

	public void destroy( int reasonCode ) throws SessionException.Error
	{
		if ( SessionRegistry.isDebug() )
			SessionRegistry.L.info( EnumColor.DARK_AQUA + "Session Destroyed `" + this + "`" );

		Events.getInstance().callEvent( new SessionDestroyEvent( this, reasonCode ) );

		// Account Auth Section
		if ( "token".equals( getVariable( "auth" ) ) )
		{
			Objs.notNull( getVariable( "uuid" ) );
			Objs.notNull( getVariable( "token" ) );

			UserAuthenticator.TOKEN.deleteToken( getVariable( "uuid" ), getVariable( "token" ) );
		}

		SessionRegistry.sessions.remove( this );

		for ( SessionWrapper wrap : wrappers )
		{
			wrap.finish();
			unregisterAttachment( wrap );
		}
		wrappers.clear();

		timeout = DateAndTime.epoch();
		data.timeout = DateAndTime.epoch();

		if ( sessionCookie != null )
			sessionCookie.setMaxAge( 0 );

		data.destroy();
		isInvalidated = true;
	}

	@Override
	public void destroyNonce()
	{
		nonce = null;
	}

	@Override
	protected void failedLogin( UserResult result )
	{

	}

	/**
	 * Get the present data change history
	 *
	 * @return A unmodifiable copy of dataChangeHistory.
	 */
	public Set<String> getChangeHistory()
	{
		return Collections.unmodifiableSet( new HashSet<String>( dataChangeHistory ) );
	}

	/**
	 * Returns a sessionCookie if existent in the session.
	 *
	 * @param key
	 *
	 * @return The cookie matching the requested key
	 */
	@Override
	public HoneyCookie getCookie( String key )
	{
		return sessionCookies.stream().filter( sessionCookie -> key.equals( sessionCookie.getName() ) ).findAny().orElseGet( () -> HoneyCookie.empty( key ) );
	}

	@Override
	public Stream<HoneyCookie> getCookies()
	{
		return sessionCookies.stream();
	}

	@Override
	public Parcel getDataMap()
	{
		return data.data;
	}

	@Override
	public Object getGlobal( String key )
	{
		return globals.get( key );
	}

	@Override
	public Map<String, Object> getGlobals()
	{
		return globals;
	}

	@Override
	public List<String> getIpAddresses()
	{
		List<String> ips = new ArrayList<>();
		for ( SessionWrapper sp : wrappers )
			if ( sp.hasSession() && sp.getSession() == this )
			{
				String ipAddress = sp.getIpAddress();
				if ( ipAddress != null && !ipAddress.isEmpty() && !ips.contains( ipAddress ) )
					ips.add( ipAddress );
			}
		return ips;
	}

	@Override
	public String getName()
	{
		return sessionKey;
	}

	@Override
	public Nonce getNonce()
	{
		if ( nonce == null )
			initNonce();
		return nonce;
	}

	@Override
	public HoneyCookie getSessionCookie()
	{
		return sessionCookie;
	}

	@Override
	public String getSessionId()
	{
		return sessionId;
	}

	/**
	 * @return A set of active SessionProviders for this session.
	 */
	public Stream<SessionWrapper> getSessionWrappers()
	{
		return wrappers.stream();
	}

	@Override
	public long getTimeout()
	{
		return timeout;
	}

	@Override
	public UserContext getUser()
	{
		return getEntity().getContext();
	}

	@Override
	public String getVariable( String key )
	{
		return getVariable( key, null );
	}

	@Override
	public String getVariable( String key, String def )
	{
		SessionRegistry.L.fine( String.format( "%sGetting variable key `%s` with value '%s' and default value '%s'", EnumColor.GRAY, key, data.data.getString( key ), def ) );

		return data.data.getString( key ).orElse( def );
	}

	@Override
	public Webroot getWebroot()
	{
		if ( webroot == null )
			return WebrootRegistry.getDefaultWebroot();
		else
			return webroot;
	}

	/**
	 * Replaces current nonce instance with a new instance.
	 * Can be called multiple times.
	 */
	@Override
	public void initNonce()
	{
		nonce = new Nonce( this );
	}

	@Override
	public boolean isInvalidated()
	{
		return isInvalidated;
	}

	@Override
	public boolean isNew()
	{
		return newSession;
	}

	@Override
	public boolean isSet( String key )
	{
		return data.data.hasValue( key );
	}

	@Override
	public UserResult kick( String reason )
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		return logout();
	}

	/**
	 * Removes the session expiration and prevents the Session Manager from unloading or destroying sessions
	 */
	@Override
	public void noTimeout()
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		timeout = 0;
		data.timeout = 0;
	}

	// TODO Sessions can outlive a login.
	// TODO Sessions can have an expiration in 7 days and a login can have an expiration of 24 hours.
	// TODO Remember me would extend login expiration to the life of the session.

	@Override
	public void processSessionCookie( String domain )
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		// TODO Session Cookies and Session expire at the same time. - Basically, as long as a Session might become called, we keep the session in existence.
		// TODO Unload a session once it has but been used for a while but might still be called upon at anytime.

		/**
		 * Has a Session Cookie been produced yet?
		 * If not we try and create a new one from scratch
		 */
		if ( sessionCookie == null )
		{
			assert sessionId != null && !sessionId.isEmpty();

			sessionKey = getWebroot().getSessionKey();
			sessionCookie = HoneyCookie.empty( getWebroot().getSessionKey() );
			sessionCookie.setValue( sessionId );
			sessionCookie.setDomain( "." + domain );
			sessionCookie.setPath( "/" );
			sessionCookie.setHttpOnly( true );
			rearmTimeout();
		}

		/**
		 * Check if our current session cookie key does not match the present key used by the webroot.
		 * If so, we move the old session to the general cookie array and set it as expired.
		 * This usually forces the browser to delete the old session cookie.
		 */
		if ( !sessionCookie.getName().equals( getWebroot().getSessionKey() ) )
		{
			String oldKey = sessionCookie.getName();
			sessionCookies.add( sessionCookie.setMaxAge( 0 ) );
			HoneyCookie newSessionCookie = HoneyCookie.empty( getWebroot().getSessionKey() );
			newSessionCookie.setValue( sessionCookie.getValue() );
			newSessionCookie.setDomain( sessionCookie.getDomain() );
			newSessionCookie.setHttpOnly( sessionCookie.isHttpOnly() );
			newSessionCookie.setMaxAge( sessionCookie.getMaxAge() );
			newSessionCookie.setPath( sessionCookie.getPath() );
			newSessionCookie.setSecure( sessionCookie.isSecure() );
			newSessionCookie.setWrap( sessionCookie.getWrap() );
			sessionCookie = newSessionCookie;
		}
	}

	@Override
	public void putSessionCookie( String key, HttpCookie cookie )
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );
		sessionCookies.add( ( HoneyCookie ) cookie );
	}

	@Override
	public void rearmTimeout()
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		int defaultTimeout = SessionRegistry.getDefaultTimeout();

		// Grant the timeout an additional 10 minutes per request, capped at one hour or 6 requests.
		requestCnt++;

		// Grant the timeout an additional 2 hours for having a user logged in.
		if ( hasLogin() )
		{
			defaultTimeout = SessionRegistry.getDefaultTimeoutWithLogin();

			if ( Objs.isTrue( getVariable( "remember", "false" ) ) )
				defaultTimeout = SessionRegistry.getDefaultTimeoutWithRememberMe();

			if ( ConfigRegistry.config.getBoolean( "http.session.allowNoSessionTimeoutPermission" ).orElse( true ) && checkPermission( "io.amelia.noSessionTimeout" ).isTrue() )
				defaultTimeout = Integer.MAX_VALUE;
		}

		timeout = DateAndTime.epoch() + defaultTimeout + Math.min( requestCnt, 6 ) * 600;

		data.timeout = timeout;

		if ( sessionCookie != null )
			sessionCookie.setMaxAge( timeout );
	}

	/**
	 * Registers a newly created wrapper with our session
	 *
	 * @param wrapper The newly created wrapper
	 */
	public void registerWrapper( SessionWrapper wrapper )
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		assert wrapper.getSession() == this : "SessionWrapper does not contain proper reference to this Session";

		registerAttachment( wrapper );
		wrappers.add( wrapper );
		knownIps.add( wrapper.getIpAddress() );
	}

	public void reload() throws SessionException.Error
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		data.reload();
	}

	/**
	 * Sets if the user login should be remembered for a longer amount of time
	 *
	 * @param remember Should we?
	 */
	@Override
	public void remember( boolean remember )
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		setVariable( "remember", remember ? "true" : "false" );
		rearmTimeout();
	}

	public void removeWrapper( SessionWrapper wrapper )
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		wrappers.remove( wrapper );
		unregisterAttachment( wrapper );
	}

	public void save() throws SessionException.Error
	{
		save( false );
	}

	public void save( boolean force ) throws SessionException.Error
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		if ( force || changesMade() )
		{
			data.sessionName = sessionKey;
			data.sessionId = sessionId;

			data.ipAddress = Joiner.on( "|" ).join( knownIps );

			data.save();
			dataChangeHistory.clear();
		}
	}

	@Override
	public void saveWithoutException()
	{
		try
		{
			save();
		}
		catch ( SessionException.Error e )
		{
			SessionRegistry.L.severe( "We had a problem saving the current session, changes were not saved to the datastore!", e );
		}
	}

	@Override
	public void setGlobal( String key, Object val )
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		globals.put( key, val );
	}

	@Override
	public void setVariable( String key, String value )
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		try
		{
			SessionRegistry.L.info( String.format( "Setting session variable `%s` with value '%s'", key, value ) );

			if ( value == null )
				data.data.childDestroy( key );

			data.data.setValue( key, value );
			dataChangeHistory.add( key );
		}
		catch ( ParcelableException.Error e )
		{
			e.printStackTrace();
		}
	}

	public void setWebroot( @Nonnull Webroot webroot )
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		this.webroot = webroot;
		data.webroot = webroot.getWebrootId();
	}

	@Override
	public void successfulLogin() throws UserException.Error
	{
		if ( isInvalidated )
			throw new IllegalStateException( "This session has been invalidated" );

		for ( SessionWrapper wrapper : wrappers )
			registerAttachment( wrapper );

		try
		{
			getEntity().getLastUsedCredentials().saveCredentialsToSession( this );
		}
		catch ( UserException.Error e )
		{
			SessionRegistry.L.severe( "We had a problem making the current login resumable!", e );
		}

		rearmTimeout();
		saveWithoutException();
	}

	@Override
	public String toString()
	{
		return "Session{key=" + sessionKey + ",id=" + sessionId + ",ipAddress=" + getIpAddresses() + ",timeout=" + timeout + ",isInvalidated=" + isInvalidated + ",data=" + data + ",requestCount=" + requestCnt + ",webroot=" + webroot + "}";
	}

	public void unload()
	{
		SessionRegistry.L.fine( EnumColor.DARK_AQUA + "Session Unloaded `" + this + "`" );

		SessionRegistry.sessions.remove( this );

		for ( SessionWrapper wrap : wrappers )
		{
			wrap.finish();
			unregisterAttachment( wrap );
		}
		wrappers.clear();

		isInvalidated = true;
	}

	@Override
	public UUID uuid()
	{
		return getEntity().uuid();
	}
}
