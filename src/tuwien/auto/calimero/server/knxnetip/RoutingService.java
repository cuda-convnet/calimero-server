/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2016, 2017 B. Malinowsky

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

    Linking this library statically or dynamically with other modules is
    making a combined work based on this library. Thus, the terms and
    conditions of the GNU General Public License cover the whole
    combination.

    As a special exception, the copyright holders of this library give you
    permission to link this library with independent modules to produce an
    executable, regardless of the license terms of these independent
    modules, and to copy and distribute the resulting executable under terms
    of your choice, provided that you also meet, for each linked independent
    module, the terms and conditions of the license of that module. An
    independent module is a module which is not derived from or based on
    this library. If you modify this library, you may extend this exception
    to your version of the library, but you are not obligated to do so. If
    you do not wish to do so, delete this exception statement from your
    version.
*/

package tuwien.auto.calimero.server.knxnetip;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;

import tuwien.auto.calimero.KNXException;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.knxnetip.KNXConnectionClosedException;
import tuwien.auto.calimero.knxnetip.KNXnetIPRouting;
import tuwien.auto.calimero.knxnetip.servicetype.KNXnetIPHeader;
import tuwien.auto.calimero.knxnetip.servicetype.PacketHelper;
import tuwien.auto.calimero.knxnetip.servicetype.RoutingLostMessage;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.log.LogService.LogLevel;

final class RoutingService extends ServiceLooper
{
	private boolean closing;

	private final class RoutingServiceHandler extends KNXnetIPRouting
	{
		private RoutingServiceHandler(final String netIf, final InetAddress mcGroup, final boolean enableLoopback)
		{
			super(mcGroup);
			try {
				init(NetworkInterface.getByName(netIf), enableLoopback, false);
				logger = LogService.getLogger("calimero.server.knxnetip." + getName());
			}
			catch (SocketException | KNXException e) {
				throw wrappedException(e);
			}
		}

		// forwarder for RoutingService dispatch, called from handleServiceType
		@Override
		protected boolean handleServiceType(final KNXnetIPHeader h, final byte[] data, final int offset,
			final InetAddress src, final int port) throws KNXFormatException, IOException
		{
			return super.handleServiceType(h, data, offset, src, port);
		}

		@Override
		public String getName()
		{
			return "KNXnet/IP routing service " + ctrlEndpt.getAddress().getHostAddress();
		}

		DatagramSocket getLocalDataSocket()
		{
			return socket;
		}

		public void send(final RoutingLostMessage lost) throws KNXConnectionClosedException
		{
			send(PacketHelper.toPacket(lost));
		}

		@Override
		public String toString()
		{
			return getName();
		}

		@Override
		protected void close(final int initiator, final String reason, final LogLevel level, final Throwable t)
		{
			// quit routing service before, so the UdpSocketLooper has its quit flag set and won't re-throw
			// any I/O or socket exception while exiting
			closing = true;
			RoutingService.this.quit();
			super.close(initiator, reason, level, t);
		}
	}

	final RoutingServiceHandler r;
	private final ServiceContainer svcCont;

	RoutingService(final KNXnetIPServer server, final ServiceContainer sc, final InetAddress mcGroup,
		final boolean enableLoopback)
	{
		super(server, null, false, 512, 0);
		svcCont = sc;
		r = new RoutingServiceHandler(sc.networkInterface(), mcGroup, enableLoopback);
		s = r.getLocalDataSocket();
		fireRoutingServiceStarted(svcCont, r);
	}

	ServiceContainer getServiceContainer()
	{
		return svcCont;
	}

	@Override
	boolean handleServiceType(final KNXnetIPHeader h, final byte[] data, final int offset, final InetAddress src,
		final int port) throws KNXFormatException, IOException
	{
		return r.handleServiceType(h, data, offset, src, port);
	}

	void sendRoutingLostMessage(final int lost, final int state) throws KNXConnectionClosedException
	{
		final RoutingLostMessage msg = new RoutingLostMessage(lost, state);
		r.send(msg);
	}

	@Override
	public void quit()
	{
		super.quit();
		if (!closing)
			r.close();
	}

	private void fireRoutingServiceStarted(final ServiceContainer sc, final KNXnetIPRouting r)
	{
		final ServiceContainerEvent sce = new ServiceContainerEvent(server, ServiceContainerEvent.ROUTING_SVC_STARTED,
				sc, r);
		server.fireOnServiceContainerChange(sce);
	}
}
