/*
    Calimero 2 - A library for KNX network access
    Copyright (c) 2010, 2017 B. Malinowsky

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
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import tuwien.auto.calimero.CloseEvent;
import tuwien.auto.calimero.IndividualAddress;
import tuwien.auto.calimero.KNXFormatException;
import tuwien.auto.calimero.KNXIllegalArgumentException;
import tuwien.auto.calimero.KNXTimeoutException;
import tuwien.auto.calimero.cemi.CEMI;
import tuwien.auto.calimero.cemi.CEMIBusMon;
import tuwien.auto.calimero.cemi.CEMIDevMgmt;
import tuwien.auto.calimero.cemi.CEMIFactory;
import tuwien.auto.calimero.cemi.CEMILData;
import tuwien.auto.calimero.knxnetip.ConnectionBase;
import tuwien.auto.calimero.knxnetip.KNXConnectionClosedException;
import tuwien.auto.calimero.knxnetip.KNXnetIPConnection;
import tuwien.auto.calimero.knxnetip.servicetype.ConnectionstateRequest;
import tuwien.auto.calimero.knxnetip.servicetype.ConnectionstateResponse;
import tuwien.auto.calimero.knxnetip.servicetype.ErrorCodes;
import tuwien.auto.calimero.knxnetip.servicetype.KNXnetIPHeader;
import tuwien.auto.calimero.knxnetip.servicetype.PacketHelper;
import tuwien.auto.calimero.knxnetip.servicetype.ServiceAck;
import tuwien.auto.calimero.knxnetip.servicetype.ServiceRequest;
import tuwien.auto.calimero.knxnetip.util.HPAI;
import tuwien.auto.calimero.log.LogService;
import tuwien.auto.calimero.log.LogService.LogLevel;

/**
 * Server-side implementation of KNXnet/IP tunneling and device management protocol.
 *
 * @author B. Malinowsky
 */
final class DataEndpointServiceHandler extends ConnectionBase
{
	// sender SHALL wait 1 second for the acknowledgment response
	// to a tunneling request
	private static final int TUNNELING_REQ_TIMEOUT = 1;
	// sender SHALL wait 10 seconds for the acknowledgment response
	// to a device configuration request
	private static final int CONFIGURATION_REQ_TIMEOUT = 10;

	private final BiConsumer<DataEndpointServiceHandler, IndividualAddress> connectionClosed;
	private final Consumer<DataEndpointServiceHandler> resetRequest;

	private final IndividualAddress device;
	private final boolean tunnel;
	private final boolean monitor;

	private volatile boolean shutdown;

	// updated on every correctly received message
	private long lastMsgTimestamp;

	DataEndpointServiceHandler(final DatagramSocket localCtrlEndpt, final DatagramSocket localDataEndpt,
		final InetSocketAddress remoteCtrlEndpt, final InetSocketAddress remoteDataEndpt, final int channelId,
		final IndividualAddress assigned, final boolean tunneling, final boolean busmonitor, final boolean useNAT,
		final BiConsumer<DataEndpointServiceHandler, IndividualAddress> connectionClosed,
		final Consumer<DataEndpointServiceHandler> resetRequest)
	{
		super(tunneling ? KNXnetIPHeader.TUNNELING_REQ : KNXnetIPHeader.DEVICE_CONFIGURATION_REQ,
				tunneling ? KNXnetIPHeader.TUNNELING_ACK : KNXnetIPHeader.DEVICE_CONFIGURATION_ACK,
				tunneling ? 2 : 4, tunneling ? TUNNELING_REQ_TIMEOUT : CONFIGURATION_REQ_TIMEOUT);
		device = assigned;
		tunnel = tunneling;
		monitor = busmonitor;
		this.channelId = channelId;

		ctrlSocket = localCtrlEndpt;
		socket = localDataEndpt;

		ctrlEndpt = remoteCtrlEndpt;
		dataEndpt = remoteDataEndpt;

		useNat = useNAT;
		this.connectionClosed = connectionClosed;
		this.resetRequest = resetRequest;

		logger = LogService.getLogger("calimero.server.knxnetip." + getName());
		setState(OK);
	}

	@Override
	public void send(final CEMI frame, final BlockingMode mode)
		throws KNXTimeoutException, KNXConnectionClosedException, InterruptedException
	{
		checkFrameType(frame);
		super.send(frame, mode);
	}

	@Override
	public String getName()
	{
		if (tunnel && monitor)
			return "KNXnet/IP Monitor " + super.getName();
		if (tunnel)
			return "KNXnet/IP Tunneling " + super.getName();
		return "KNXnet/IP DevMgmt " + super.getName();
	}

	@Override
	public String toString()
	{
		return getName() + " (channel " + getChannelId() + ")";
	}

	@Override
	protected void close(final int initiator, final String reason, final LogLevel level, final Throwable t)
	{
		super.close(initiator, reason, level, t);
	}

	@Override
	protected void cleanup(final int initiator, final String reason, final LogLevel level, final Throwable t)
	{
		// we want close/shutdown be called only once
		synchronized (this) {
			if (shutdown)
				return;
			shutdown = true;
		}

		LogService.log(logger, level, "close connection for channel " + channelId + " - " + reason, t);
		connectionClosed.accept(this, device);
		super.cleanup(initiator, reason, level, t);
	}

	void init(final DatagramSocket localCtrlEndpt, final DatagramSocket localDataEndpt)
	{
		ctrlSocket = localCtrlEndpt;
		socket = localDataEndpt;
		setState(OK);
	}

	void setSocket(final DatagramSocket socket) {
		this.socket = socket;
	}

	boolean handleDataServiceType(final KNXnetIPHeader h, final byte[] data, final int offset)
		throws KNXFormatException, IOException
	{
		final int svc = h.getServiceType();
		final String type = tunnel ? "tunneling" : "device configuration";

		final boolean configReq = svc == KNXnetIPHeader.DEVICE_CONFIGURATION_REQ;
		final boolean configAck = svc == KNXnetIPHeader.DEVICE_CONFIGURATION_ACK;
		if (tunnel && (configReq || configAck)) {
			final int recvChannelId = configReq ? getServiceRequest(h, data, offset).getChannelID()
					: new ServiceAck(svc, data, offset).getChannelID();
			if (recvChannelId == channelId)
				return false;
			final int localPort = socket.getLocalPort();
			logger.error("ETS 5 sends configuration requests for channel {} to wrong UDP port {} (channel {}), "
					+ "try to find correct connection", recvChannelId, localPort, channelId);
			final LooperThread looper = ControlEndpointService.findConnectionLooper(recvChannelId);
			if (looper == null)
				return false;
			final DataEndpointService dataEndpointService = (DataEndpointService) looper.getLooper();
			dataEndpointService.rebindSocket(localPort);
			dataEndpointService.svcHandler.handleDataServiceType(h, data, offset);
			return true;
		}

		if (svc == serviceRequest) {
			final ServiceRequest req = getServiceRequest(h, data, offset);
			if (!checkChannelId(req.getChannelID(), "request"))
				return true;
			final int seq = req.getSequenceNumber();
			if (seq == getSeqRcv() || (tunnel && ((seq + 1) & 0xFF) == getSeqRcv())) {
				final int status = checkVersion(h) ? ErrorCodes.NO_ERROR : ErrorCodes.VERSION_NOT_SUPPORTED;
				final byte[] buf = PacketHelper.toPacket(new ServiceAck(serviceAck, channelId, seq, status));
				final DatagramPacket p = new DatagramPacket(buf, buf.length, dataEndpt);
				socket.send(p);
				if (status == ErrorCodes.VERSION_NOT_SUPPORTED) {
					close(CloseEvent.INTERNAL, "protocol version changed", LogLevel.ERROR, null);
					return true;
				}
			}
			else
				logger.warn(type + " request with invalid receive sequence " + seq + ", expected " + getSeqRcv()
						+ " - ignored");

			if (seq == getSeqRcv()) {
				incSeqRcv();
				updateLastMsgTimestamp();

				final CEMI cemi = req.getCEMI();
				// leave if we are working with an empty (broken) service request
				if (cemi == null)
					return true;
				if (tunnel)
					checkNotifyTunnelingCEMI(cemi);
				else
					checkNotifyConfigurationCEMI(cemi);
			}
		}
		else if (svc == serviceAck) {
			final ServiceAck res = new ServiceAck(svc, data, offset);
			if (!checkChannelId(res.getChannelID(), "acknowledgment"))
				return true;

			if (res.getSequenceNumber() != getSeqSend())
				logger.warn("received " + type + " acknowledgment with wrong send-sequence "
						+ res.getSequenceNumber() + ", expected " + getSeqSend() + " - ignored");
			else {
				if (!checkVersion(h)) {
					close(CloseEvent.INTERNAL, "protocol version changed", LogLevel.ERROR, null);
					return true;
				}
				incSeqSend();
				updateLastMsgTimestamp();

				// update state and notify our lock
				setStateNotify(res.getStatus() == ErrorCodes.NO_ERROR ? OK : ACK_ERROR);
				if (logger.isTraceEnabled())
					logger.trace("received service ack {} from " + ctrlEndpt
							+ " (channel " + channelId + ")", res.getSequenceNumber());
				if (internalState == ACK_ERROR)
					logger.warn("received service acknowledgment status " + res.getStatusString());
			}
		}
		else if (svc == KNXnetIPHeader.CONNECTIONSTATE_REQ) {
			// For some weird reason (or no reason, because it's not in the spec), ETS sends a
			// connection-state.req from its data endpoint to our data endpoint immediately after
			// a new connection was established.
			// It expects it to be answered by the control endpoint (!), so do that here.
			// If we ignore that connection-state.req, the ETS connection establishment
			// gets delayed by the connection-state.res timeout.
			final ConnectionstateRequest csr = new ConnectionstateRequest(data, offset);
			int status = checkVersion(h) ? ErrorCodes.NO_ERROR : ErrorCodes.VERSION_NOT_SUPPORTED;
			if (status == ErrorCodes.NO_ERROR && csr.getControlEndpoint().getHostProtocol() != HPAI.IPV4_UDP)
				status = ErrorCodes.HOST_PROTOCOL_TYPE;

			if (status == ErrorCodes.NO_ERROR) {
				logger.trace("data endpoint received connection state request from " + dataEndpt + " for channel "
						+ csr.getChannelID());
				updateLastMsgTimestamp();
			}
			else
				logger.warn("received invalid connection state request: " + ErrorCodes.getErrorMessage(status));

			final byte[] buf = PacketHelper.toPacket(new ConnectionstateResponse(csr.getChannelID(), status));
			final DatagramPacket p = new DatagramPacket(buf, buf.length, ctrlEndpt);
			ctrlSocket.send(p);
		}
		else
			return false;
		return true;
	}

	void updateLastMsgTimestamp()
	{
		lastMsgTimestamp = System.currentTimeMillis();
	}

	long getLastMsgTimestamp()
	{
		return lastMsgTimestamp;
	}

	int getChannelId()
	{
		return channelId;
	}

	SocketAddress getCtrlSocketAddress()
	{
		return ctrlSocket.getLocalSocketAddress();
	}

	boolean isDeviceMgmt()
	{
		return !tunnel;
	}

	boolean isMonitor()
	{
		return monitor;
	}

	private boolean checkVersion(final KNXnetIPHeader h)
	{
		final boolean ok = h.getVersion() == KNXnetIPConnection.KNXNETIP_VERSION_10;
		if (!ok)
			logger.warn("KNXnet/IP " + (h.getVersion() >> 4) + "." + (h.getVersion() & 0xf) + " "
					+ ErrorCodes.getErrorMessage(ErrorCodes.VERSION_NOT_SUPPORTED));
		return ok;
	}

	private void checkNotifyTunnelingCEMI(final CEMI cemi)
	{
		final int mc = cemi.getMessageCode();
		if (monitor)
			logger.warn("client is not allowed to send cEMI messages in busmonitor mode - ignored");
		else if (mc == CEMILData.MC_LDATA_REQ) {
			CEMILData ldata = (CEMILData) cemi;
			if (ldata.getSource().equals(new IndividualAddress(0)))
				ldata = CEMIFactory.create(device, ldata.getDestination(), ldata, false);
			fireFrameReceived(ldata);
		}
		else if (mc == CEMIDevMgmt.MC_RESET_REQ) {
			fireFrameReceived(cemi);
			resetRequest.accept(this);
		}
		else {
			switch (mc) {
			case CEMILData.MC_LDATA_CON:
				logger.warn("received L-Data confirmation - ignored");
				break;
			case CEMILData.MC_LDATA_IND:
				logger.warn("received L-Data indication - ignored");
				break;
			case CEMIBusMon.MC_BUSMON_IND:
				logger.warn("received L-Busmon indication - ignored");
				break;
			default:
				logger.warn("unsupported cEMI message code " + mc + " - ignored");
			}
		}
	}

	private void checkNotifyConfigurationCEMI(final CEMI cemi)
	{
		if (cemi.getMessageCode() == CEMIDevMgmt.MC_PROPREAD_REQ
				|| cemi.getMessageCode() == CEMIDevMgmt.MC_PROPWRITE_REQ
				|| cemi.getMessageCode() == CEMIDevMgmt.MC_RESET_REQ) {
			fireFrameReceived(cemi);
			if (cemi.getMessageCode() == CEMIDevMgmt.MC_RESET_REQ)
				resetRequest.accept(this);
		}
		else {
			switch (cemi.getMessageCode()) {
			case CEMIDevMgmt.MC_PROPREAD_CON:
				logger.warn("received property read confirmation - ignored");
				break;
			case CEMIDevMgmt.MC_PROPWRITE_CON:
				logger.warn("received property write confirmation - ignored");
				break;
			case CEMIDevMgmt.MC_PROPINFO_IND:
				logger.warn("received property info indication - ignored");
				break;
			case CEMIDevMgmt.MC_RESET_IND:
				logger.warn("received reset indication - ignored");
				break;
			default:
				logger.warn("unsupported cEMI message code " + cemi.getMessageCode() + " - ignored");
			}
		}
	}

	private void checkFrameType(final CEMI frame)
	{
		if (tunnel) {
			if (monitor) {
				if (!(frame instanceof CEMIBusMon))
					throw new KNXIllegalArgumentException("bus monitor requires cEMI bus monitor frame type");
			}
			else if (!(frame instanceof CEMILData))
				throw new KNXIllegalArgumentException("link layer requires cEMI L-Data frame type");
		}
		else if (!(frame instanceof CEMIDevMgmt))
			throw new KNXIllegalArgumentException("expect cEMI device management frame type");
	}
}
