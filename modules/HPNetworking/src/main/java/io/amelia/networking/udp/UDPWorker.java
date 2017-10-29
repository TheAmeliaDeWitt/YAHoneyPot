/**
 * This software may be modified and distributed under the terms
 * of the MIT license.  See the LICENSE file for details.
 * <p>
 * Copyright (c) 2017 Joel Greene <joel.greene@penoaks.com>
 * Copyright (c) 2017 Penoaks Publishing LLC <development@penoaks.com>
 * <p>
 * All Rights Reserved.
 */
package io.amelia.networking.udp;

import io.amelia.config.ConfigMap;
import io.amelia.config.ConfigRegistry;
import io.amelia.foundation.Kernel;
import io.amelia.lang.NetworkException;
import io.amelia.lang.PacketValidationException;
import io.amelia.lang.StartupException;
import io.amelia.networking.NetworkLoader;
import io.amelia.networking.NetworkWorker;
import io.amelia.networking.packets.PacketRequest;
import io.amelia.support.LibIO;
import io.amelia.support.Sys;
import io.amelia.support.Timings;
import io.amelia.tasks.TaskDispatcher;
import io.amelia.support.NIO;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.ObjectInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.KeyPair;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

import static io.amelia.networking.NetworkLoader.L;

public class UDPWorker implements NetworkWorker
{
	private final List<PacketContainer> awaiting = new CopyOnWriteArrayList<>();
	private NioDatagramChannel channel = null;

	@Override
	public void dispose()
	{

	}

	public InetSocketAddress getBroadcast()
	{
		return broadcast;
	}

	@Override
	public String getId()
	{
		return "udp";
	}

	@Override
	public void heartbeat()
	{
		for ( PacketContainer packetContainer : awaiting )
			if ( packetContainer.expired() )
				awaiting.remove( packetContainer );
	}

	@Override
	public boolean isStarted()
	{
		return channel != null && channel.isConnected();
	}

	public UDPWorker start() throws NetworkException
	{
		ConfigMap config = getConfig();
		String dest = config.getString( "broadcast" ).orElse( "239.255.255.255" );
		int port = config.getInteger( "port" ).orElse( 4855 );
		String ifs = config.getString( "interface" ).orElse( null );

		if ( config.getBoolean( "disable" ).orElse( false ) || port <= 0 )
			throw new StartupException( "UDP Service is disabled!" );

		L.info( "Starting UDP Service!" );

		InetSocketAddress broadcast = new InetSocketAddress( dest, port );

		if ( Sys.isPrivilegedPort( port ) )
		{
			L.warning( "It would seem that you are trying to start the UDP Service on a privileged port without root access." );
			L.warning( "We will attempt to still start the service but we can't guarantee it's success. http://www.w3.org/Daemon/User/Installation/PrivilegedPorts.html" );
		}

		NetworkInterface iface;

		if ( ifs == null )
		{
			try
			{
				NetworkInterface lb = null;
				NetworkInterface ii = null;
				Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
				while ( ifaces.hasMoreElements() )
				{
					NetworkInterface i = ifaces.nextElement();
					if ( i.isLoopback() )
						lb = i;
					if ( i.getInterfaceAddresses().size() > 0 )
						ii = i;
				}

				iface = lb == null ? ii : lb;
			}
			catch ( SocketException e )
			{
				throw new StartupException( e );
			}

			if ( iface == null )
				throw new StartupException( "The UDP interface is unset and we were unable to autodetect an acceptable interface. Please manually specify an interface." );
		}
		else
			try
			{
				iface = NetworkInterface.getByName( ifs );
				if ( iface == null )
					throw new StartupException( "The interface \"" + ifs + "\" does not exist." );
			}
			catch ( SocketException e )
			{
				throw new StartupException( "Binding UDP to interface \"" + ifs + "\" was unsuccessful.", e );
			}

		InetAddress localAddr = NIO.firstAddr( iface );

		try
		{
			Bootstrap b = new Bootstrap();
			b.group( NetworkLoader.IO_LOOP_GROUP ).channelFactory( () -> new NioDatagramChannel( InternetProtocolFamily.IPv4 ) ).localAddress( localAddr, broadcast.getPort() ).option( ChannelOption.IP_MULTICAST_IF, iface ).option( ChannelOption.SO_REUSEADDR, true ).handler( new ChannelInitializer<NioDatagramChannel>()
			{
				final UDPEncryptCodec udpEncryptCodec = new UDPEncryptCodec();
				final UDPPacketCodec udpPacketCodec = new UDPPacketCodec();

				@Override
				protected void initChannel( NioDatagramChannel ch ) throws Exception
				{
					ChannelPipeline p = ch.pipeline();

					p.addLast( udpEncryptCodec );
					p.addLast( udpPacketCodec );
					p.addLast( "handler", new UDPHandler( broadcast ) );
				}
			} );

			channel = ( NioDatagramChannel ) b.bind( broadcast.getPort() ).sync().channel();

			Kernel.registerRunnable( () ->
			{
				try
				{
					channel.joinGroup( broadcast, iface ).sync();
					channel.closeFuture().await();
				}
				catch ( InterruptedException e )
				{
					e.printStackTrace();
				}

				L.info( "The UDP service has shutdown!" );
			} );
		}
		catch ( NullPointerException e )
		{
			throw new StartupException( "There was a problem starting the UDP service.", e );
		}
		catch ( Throwable e )
		{
			throw e instanceof StartupException ? ( StartupException ) e : new StartupException( e );
		}

		/* TaskDispatcher.scheduleSyncRepeatingTask( Kernel.getApplication(), 1000L, 1000L, () ->
		{
			LogBuilder.get().debug( "Sending UDP: " + Timings.epoch() );
			channel.writeAndFlush( Unpooled.buffer().writeLong( Timings.epoch() ) );
		} ); */

		return this;
	}

	@Override
	public UDPWorker stop() throws NetworkException
	{
		NIO.closeQuietly( channel );
		return this;
	}

	/**
	 * Returns the KeyPair per configured in the configuration, e.g., server.udp.rsaSecret and server.udp.rsaKey.
	 *
	 * @return The KeyPair, null if config is unset or null.
	 * @throws NetworkException if there is a failure to initialize the KeyPair
	 */
	public KeyPair getRSA() throws NetworkException
	{
		ObjectInputStream is = null;
		try
		{
			File file = getConfig( "rsaKey" ).getStringAsFile().orElse( null );
			if ( file == null )
				return null;

			PEMParser parser = new PEMParser( new FileReader( file ) );
			Object obj = parser.readObject();
			JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider( "BC" );
			KeyPair kp;

			if ( obj instanceof PEMEncryptedKeyPair )
			{
				PEMEncryptedKeyPair ckp = ( PEMEncryptedKeyPair ) obj;
				PEMDecryptorProvider decProv = new JcePEMDecryptorProviderBuilder().build( ConfigRegistry.getString( "server.udp.rsaSecret" ).orElse( "" ).toCharArray() );
				kp = converter.getKeyPair( ckp.decryptKeyPair( decProv ) );
			}
			else
			{
				PEMKeyPair ukp = ( PEMKeyPair ) obj;
				kp = converter.getKeyPair( ukp );
			}

			return kp;
		}
		catch ( Throwable t )
		{
			throw new NetworkException( "There was problem constructing the RSA file for UDP encryption.", t );
		}
		finally
		{
			LibIO.closeQuietly( is );
		}
	}

	public <S extends PacketRequest, R extends PacketResponse> void sendPacket( S packet, BiConsumer<S, R> received ) throws PacketValidationException
	{
		// Confirm that the UDPWorker has been started.
		if ( !isStarted() )
			throw new IllegalStateException( "The UDPWorker has not been started." );

		// Validate the packet has all the required information
		packet.validate();

		// Instruct the packet to encode the subclass into a ByteBuf payload
		packet.encode();

		// Place the packet into the awaiting response map
		awaiting.add( new PacketContainer( packet, received ) );

		// Schedule a timeout that removes the packet from the awaiting map
		TaskDispatcher.scheduleAsyncDelayedTask( Kernel.getApplication(), packet.getTimeout(), () -> awaiting.remove( packet.getPacketId() ) );



		channel.writeAndFlush( new DatagramPacket( packet.payload ) );
		packet.sentTime = Timings.epoch();
		packet.sent = true;
	}

	private class PacketContainer
	{
		PacketRequest request;

		public <S extends PacketRequest> PacketContainer( PacketRequest request, BiConsumer<S, R> received )
		{
			this.request = request;
		}

		public boolean expired()
		{
			return Timings.epoch() - request.getSentTime() > request.getTimeout();
		}
	}
}
