/**
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 * <p>
 * Copyright (c) 2018 Amelia Sara Greene <barelyaprincess@gmail.com>
 * Copyright (c) 2018 Penoaks Publishing LLC <development@penoaks.com>
 * <p>
 * All Rights Reserved.
 */
package io.amelia.networking;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.security.Security;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import io.amelia.foundation.Kernel;
import io.amelia.foundation.binding.Bindings;
import io.amelia.foundation.binding.FacadePriority;
import io.amelia.foundation.binding.WritableBinding;
import io.amelia.lang.ApplicationException;
import io.amelia.lang.NetworkException;
import io.amelia.networking.packets.RawPacket;
import io.amelia.networking.udp.UDPWorker;
import io.amelia.support.Objs;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

public class NetworkLoader
{
	public static final Kernel.Logger L = Kernel.getLogger( NetworkLoader.class );

	public static final EventLoopGroup IO_LOOP_GROUP = new NioEventLoopGroup( 0, Executors.newCachedThreadPool( new ThreadFactoryBuilder().setNameFormat( "Netty Client IO #%d" ).setDaemon( true ).build() ) );

	private static final Map<Class<? extends NetworkWorker>, NetworkWorker> networkWorkers = new ConcurrentHashMap<>();

	private static final PacketCollection registeredPackets = new PacketCollection();

	static
	{
		if ( Security.getProvider( "BC" ) == null )
			Security.addProvider( new BouncyCastleProvider() );
	}

	/*
	public static HTTPWorker HTTP()
	{
		return getWorker( HTTPWorker.class, HTTPWorker::new )
	}
	*/

	public static UDPWorker UDP()
	{
		return getWorker( UDPWorker.class, UDPWorker::new );
	}

	public static void disposeWorker( Class<? extends NetworkWorker> workerClass ) throws NetworkException.Error
	{
		Objs.ifPresent( networkWorkers.remove( workerClass ), NetworkWorker::stop );
	}

	public static Optional<? extends RawPacket> getPacket( String packetId )
	{
		return registeredPackets.getPacket( packetId );
	}

	public static <T extends RawPacket> Optional<Class<T>> getPacketClass( String classId )
	{
		return registeredPackets.getPacketClass( classId );
	}

	@SuppressWarnings( "unchecked" )
	public static <N extends NetworkWorker> N getWorker( Class<N> workerClass )
	{
		return ( N ) networkWorkers.computeIfAbsent( workerClass, NetworkLoader::initWorker );
	}

	@SuppressWarnings( "unchecked" )
	public static <N extends NetworkWorker> N getWorker( Class<N> workerClass, Supplier<N> workerSupplier )
	{
		return ( N ) networkWorkers.computeIfAbsent( workerClass, c -> workerSupplier.get() );
	}

	public static void heartbeat()
	{
		for ( NetworkWorker worker : networkWorkers.values() )
			if ( worker.isStarted() )
				worker.heartbeat();
	}

	public static void init() throws ApplicationException.Error
	{
		// Initialize networking registration and so forth.
		WritableBinding namespace = Bindings.getSystemNamespace( NetworkLoader.class );

		assert namespace != null;

		namespace.registerFacadeBinding( "facade", NetworkingService.class, NetworkingService::new, FacadePriority.NORMAL );
	}

	public static <N extends NetworkWorker> N initWorker( Class<N> workerClass )
	{
		return Objs.initClass( workerClass );
	}

	public static void registerPacket( RawPacket packet )
	{
		registeredPackets.registerPacket( packet );
	}

	private NetworkLoader()
	{
		// Static Class
	}

	public static class ConfigKeys
	{
		public static final String HTTP_PORT = "networking.http.port";
		public static final String HTTPS_PORT = "networking.https.port";

		private ConfigKeys()
		{
			// Static Access
		}
	}

	/**
	 * Holds each registered Packet
	 * <p>
	 * classId -> packetId -> Packet
	 */
	public static class PacketCollection
	{
		private final Map<String, PacketList> packets = new HashMap<>();

		public Optional<? extends RawPacket> getPacket( String packetId )
		{
			return packets.values().stream().flatMap( Collection::stream ).filter( p -> packetId.equals( p.getPacketId() ) ).findFirst();
		}

		public <T extends RawPacket> Optional<Class<T>> getPacketClass( String classId )
		{
			return packets.entrySet().stream().filter( e -> classId.equals( e.getKey() ) ).map( e -> ( Class<T> ) e.getValue().getPacketClass() ).findFirst();
		}

		public <T extends RawPacket> void registerPacket( T packet )
		{
			packets.computeIfAbsent( packet.getClassId(), k -> new PacketList( packet.getClass() ) ).add( packet );
		}

		private class PacketList extends HashSet<RawPacket>
		{
			private Class<? extends RawPacket> packetClass;

			PacketList( Class<? extends RawPacket> packetClass )
			{
				this.packetClass = packetClass;
			}

			public Class<? extends RawPacket> getPacketClass()
			{
				return packetClass;
			}
		}
	}
}