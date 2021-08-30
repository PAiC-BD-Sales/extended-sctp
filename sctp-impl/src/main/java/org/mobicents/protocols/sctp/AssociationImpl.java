/*
 * TeleStax, Open Source Cloud Communications  Copyright 2012. 
 * and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.protocols.sctp;

import com.sun.nio.sctp.SctpMultiChannel;
import com.sun.nio.sctp.SctpStandardSocketOptions;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import javolution.util.FastList;
import javolution.xml.XMLFormat;
import javolution.xml.stream.XMLStreamException;

import org.apache.log4j.Logger;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationListener;
import org.mobicents.protocols.api.AssociationType;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.ManagementEventListener;
import org.mobicents.protocols.api.PayloadData;

import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;

/**
 * @author amit bhayani
 *
 */
public class AssociationImpl implements Association {

    protected static final Logger logger = Logger.getLogger(AssociationImpl.class.getName());

    private static final String NAME = "name";
    private static final String SERVER_NAME = "serverName";
    private static final String HOST_ADDRESS = "hostAddress";
    private static final String HOST_PORT = "hostPort";

    private static final String PEER_ADDRESS = "peerAddress";
    private static final String PEER_PORT = "peerPort";

    private static final String ASSOCIATION_TYPE = "associationType";
    private static final String IP_CHANNEL_TYPE = "ipChannelType";
    private static final String EXTRA_HOST_ADDRESS = "extraHostAddress";
    private static final String EXTRA_HOST_ADDRESSES_SIZE = "extraHostAddressesSize";

    private String hostAddress;
    private int hostPort;
    private String peerAddress;
    private int peerPort;
    private String serverName;
    private String name;
    private IpChannelType ipChannelType;
    private String[] extraHostAddresses;
    private ServerImpl server; // this is filled only for anonymous Associations

    private AssociationType type;

    private AssociationListener associationListener = null;

    protected final AssociationHandler associationHandler = new AssociationHandler();

    /**
     * This is used only for SCTP This is the socket address for peer which will
     * be null initially. If the Association has multihome support and if peer
     * address changes, this variable is set to new value so new messages are
     * now sent to changed peer address
     */
    protected volatile SocketAddress peerSocketAddress = null;

    // Is the Association been started by management?
    private volatile boolean started = false;
    // Is the Association up (connection is established)
    protected volatile boolean up = false;

    private int workerThreadTable[] = null;

    private ConcurrentLinkedQueue<PayloadData> txQueue = new ConcurrentLinkedQueue<PayloadData>();

    private ManagementImpl management;

    private SctpChannel socketChannelSctp;
    private SocketChannel socketChannelTcp;

    // The buffer into which we'll read data when it's available
    private ByteBuffer rxBuffer;

    private volatile MessageInfo msgInfo;

    /**
     * Count of number of IO Errors occurred. If this exceeds the maxIOErrors set
     * in Management, socket will be closed and request to reopen the socket
     * will be initiated
     */
    private volatile int ioErrors = 0;

    public AssociationImpl() {
        super();
    }

    protected void initChannels() {
        rxBuffer = ByteBuffer.allocateDirect(management.getBufferSize());
        // clean receiver buffer
        rxBuffer.clear();
        rxBuffer.rewind();
        rxBuffer.flip();
    }

    /**
     * Creating a CLIENT Association
     *
     * @param hostAddress
     * @param hostPort
     * @param peerAddress
     * @param peerPort
     * @param associationName
     * @param ipChannelType
     * @param extraHostAddresses
     * @throws IOException
     */
    public AssociationImpl(String hostAddress, int hostPort, String peerAddress, int peerPort, String associationName,
                           IpChannelType ipChannelType, String[] extraHostAddresses) throws IOException {
        this();
        this.hostAddress = hostAddress;
        this.hostPort = hostPort;
        this.peerAddress = peerAddress;
        this.peerPort = peerPort;
        this.name = associationName;
        this.ipChannelType = ipChannelType;
        this.extraHostAddresses = extraHostAddresses;

        this.type = AssociationType.CLIENT;
    }

    /**
     * Creating a SERVER Association
     *
     * @param peerAddress
     * @param peerPort
     * @param serverName
     * @param associationName
     * @param ipChannelType
     */
    public AssociationImpl(String peerAddress, int peerPort, String serverName, String associationName, IpChannelType ipChannelType) {
        this();
        this.peerAddress = peerAddress;
        this.peerPort = peerPort;
        this.serverName = serverName;
        this.name = associationName;
        this.ipChannelType = ipChannelType;

        this.type = AssociationType.SERVER;
    }

	/**
	 * Creating an ANONYMOUS_SERVER Association
     *
     * @param peerAddress
     * @param peerPort
     * @param serverName
     * @param ipChannelType
     * @param server
	 */
	protected AssociationImpl(String peerAddress, int peerPort, String serverName, IpChannelType ipChannelType, ServerImpl server) {
		this();
		this.peerAddress = peerAddress;
		this.peerPort = peerPort;
		this.serverName = serverName;
		this.ipChannelType = ipChannelType;
		this.server = server;

        this.type = AssociationType.ANONYMOUS_SERVER;
    }

    protected void start() throws Exception {
        if (this.associationListener == null) {
            throw new NullPointerException(String.format("AssociationListener is null for Association=%s", getAssociationName()));
        }

        if (this.type == AssociationType.CLIENT) {
            this.scheduleConnect();
        }

        this.started = true;

        if (logger.isInfoEnabled()) {
            if (this.type != AssociationType.ANONYMOUS_SERVER) {
                logger.info(String.format("Started Association=%s", this));
            }
        }

        for (ManagementEventListener managementEventListener : this.management.getManagementEventListeners()) {
            try {
                managementEventListener.onAssociationStarted(this);
            } catch (Throwable ee) {
                logger.error("Exception while invoking onAssociationStarted", ee);
            }
        }
    }

    /**
     * Stops this Association. If the underlying SctpChannel is open, marks the
     * channel for close
     */
    protected void stop() throws Exception {
        this.started = false;
        for (ManagementEventListener managementEventListener : this.management.getManagementEventListeners()) {
            try {
                managementEventListener.onAssociationStopped(this);
            } catch (Throwable ee) {
                logger.error("Exception while invoking onAssociationStopped", ee);
            }
        }

        if (this.getSocketChannel() != null && this.getSocketChannel().isOpen()) {
            FastList<ChangeRequest> pendingChanges = this.management.getPendingChanges();
            synchronized (pendingChanges) {
                // Indicate we want the interest ops set changed
                pendingChanges.add(new ChangeRequest(getSocketChannel(), this, ChangeRequest.CLOSE, -1));
            }

            // Finally, wake up our selecting thread so it can make the required
            // changes
            this.management.getSocketSelector().wakeup();
        }
    }

    public void acceptAnonymousAssociation(AssociationListener associationListener) throws Exception {
        this.associationListener = associationListener;

        if (this.getAssociationType() != AssociationType.ANONYMOUS_SERVER) {
            throw new UnsupportedOperationException(
                "Association.acceptAnonymousAssociation() can be applied only for anonymous associations");
        }

        this.start();
    }

    public void rejectAnonymousAssociation() {
    }

    public void stopAnonymousAssociation() throws Exception {

        if (this.getAssociationType() != AssociationType.ANONYMOUS_SERVER) {
            throw new UnsupportedOperationException(
                "Association.stopAnonymousAssociation() can be applied only for anonymous associations");
        }

        this.stop();
    }

    public IpChannelType getIpChannelType() {
        return this.ipChannelType;
    }

    public void setIpChannelType(IpChannelType ipChannelType) {
        this.ipChannelType = ipChannelType;
    }

    /**
     * @return the associationListener
     */
    public AssociationListener getAssociationListener() {
        return associationListener;
    }

	/**
	 * @param associationListener the associationListener to set
	 */
	public void setAssociationListener(AssociationListener associationListener) {
		this.associationListener = associationListener;
	}

    /**
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * @return the associationName
     */
    public String getAssociationName() {
        return this.type == AssociationType.ANONYMOUS_SERVER ? server.getName() : name;
    }

    /**
     * @return the associationType
     */
    public AssociationType getAssociationType() {
        return type;
    }

    /**
     * @return the started status
     */
    @Override
    public boolean isStarted() {
        return started;
    }

    /**
     * @return the connected status
     */
    @Override
    public boolean isConnected() {
        return started && up;
    }

    /**
     * @return the up status
     */
    @Override
    public boolean isUp() {
        return up;
    }

    protected void markAssociationUp() {
        if (this.server != null) {
            synchronized (this.server.anonymAssociations) {
                this.server.anonymAssociations.add(this);
            }
        }

        this.up = true;
        for (ManagementEventListener managementEventListener : this.management.getManagementEventListeners()) {
            try {
                managementEventListener.onAssociationUp(this);
            } catch (Throwable ee) {
                logger.error("Exception while invoking onAssociationUp", ee);
            }
        }
    }

    protected void markAssociationDown() {
        this.up = false;
        for (ManagementEventListener managementEventListener : this.management.getManagementEventListeners()) {
            try {
                managementEventListener.onAssociationDown(this);
            } catch (Throwable ee) {
                logger.error("Exception while invoking onAssociationDown", ee);
            }
        }

        if (this.server != null) {
            synchronized (this.server.anonymAssociations) {
                this.server.anonymAssociations.remove(this);
            }
        }
    }

    /**
     * @return the hostAddress
     */
    public String getHostAddress() {
        return hostAddress;
    }

    /**
     * @set the hostAddress
     */
    public void setHostAddress(String hostAddress) {
        this.hostAddress = hostAddress;
    }

    /**
     * @return the hostPort
     */
    public int getHostPort() {
        return hostPort;
    }

    /**
     * @set the hostPort
     */
    public void setHostPort(int hostPort) {
        this.hostPort = hostPort;
    }

    /**
     * @return the peerAddress
     */
    public String getPeerAddress() {
        return peerAddress;
    }

    /**
     * @set the peerAddress
     */
    public void setPeerAddress(String peerAddress) {
        this.peerAddress = peerAddress;
    }

    /**
     * @return the peerPort
     */
    public int getPeerPort() {
        return peerPort;
    }

    /**
     * @set the peerPort
     */
    public void setPeerPort(int peerPort) {
        this.peerPort = peerPort;
    }

    /**
     * @return the serverName
     */
    public String getServerName() {
        return serverName;
    }

    /**
     * @set the serverName
     */
    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    @Override
    public String[] getExtraHostAddresses() {
        return extraHostAddresses;
    }

    public void setExtraHostAddresses(String[] extraHostAddresses) {
        this.extraHostAddresses = extraHostAddresses;
    }

    /**
     * @param management the management to set
     */
    protected void setManagement(ManagementImpl management) {
		this.management = management;
		this.initChannels();
    }

    private AbstractSelectableChannel getSocketChannel() {
        if (this.ipChannelType == IpChannelType.SCTP)
            return this.socketChannelSctp;
        else
            return this.socketChannelTcp;
    }

    /**
     * @param socketChannel the socketChannel to set
     */
    protected void setSocketChannel(AbstractSelectableChannel socketChannel) {
        if (this.ipChannelType == IpChannelType.SCTP)
            this.socketChannelSctp = (SctpChannel) socketChannel;
        else
            this.socketChannelTcp = (SocketChannel) socketChannel;
    }

    public void send(PayloadData payloadData) throws Exception {
        this.checkSocketIsOpen();

        FastList<ChangeRequest> pendingChanges = this.management.getPendingChanges();
        synchronized (pendingChanges) {

			// Indicate we want the interest ops set changed
			pendingChanges.add(new ChangeRequest(this.getSocketChannel(), this, ChangeRequest.CHANGEOPS,
				SelectionKey.OP_WRITE));

            // And queue the data we want written
            // TODO Do we need to synchronize ConcurrentLinkedQueue ?
            // synchronized (this.txQueue) {
            this.txQueue.add(payloadData);
        }

        // Finally, wake up our selecting thread so it can make the required
        // changes
        this.management.getSocketSelector().wakeup();
    }

    private void checkSocketIsOpen() throws Exception {
        if (this.ipChannelType == IpChannelType.SCTP) {
            if (!this.started || this.socketChannelSctp == null || !this.socketChannelSctp.isOpen()
                || this.socketChannelSctp.association() == null)
                throw new Exception(String.format(
                    "Underlying SCTP channel doesn't open or doesn't have an association for Association=%s",
                    getAssociationName()));
        } else {
            if (!this.started || this.socketChannelTcp == null || !this.socketChannelTcp.isOpen()
                || !this.socketChannelTcp.isConnected())
                throw new Exception(String.format("Underlying TCP channel doesn't open for Association=%s", getAssociationName()));
        }
    }

    protected void read() {

        try {
            PayloadData payload;
            if (this.ipChannelType == IpChannelType.SCTP)
                payload = this.doReadSctp();
            else
                payload = this.doReadTcp();
            if (payload == null)
                return;

            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Rx : Ass=%s %s", getAssociationName(), payload));
            }

			if (this.management.isSingleThread()) {
				// If single thread model the listener should be called in the
				// selector thread itself
				try {
					this.associationListener.onPayload(this, payload);
				} catch (Exception e) {
					logger.error(String.format("Error while calling Listener for Association=%s.Payload=%s",
                            getAssociationName(), payload), e);
				}
			} else {
				Worker worker = new Worker(this, this.associationListener, payload);

//				System.out.println("payload.getStreamNumber()=" + payload.getStreamNumber()
//						+ " this.workerThreadTable[payload.getStreamNumber()]"
//						+ this.workerThreadTable[payload.getStreamNumber()]);

                ExecutorService executorService = this.management.getExecutorService(this.workerThreadTable[payload
                    .getStreamNumber()]);
                try {
                    executorService.execute(worker);
                } catch (RejectedExecutionException e) {
                    logger.error(String.format("Rejected %s as Executor is shutdown", payload), e);
                } catch (NullPointerException e) {
                    logger.error(String.format("NullPointerException while submitting %s", payload), e);
                } catch (Exception e) {
                    logger.error(String.format("Exception while submitting %s", payload), e);
                }
            }
        } catch (IOException e) {
            this.ioErrors++;
            logger.error(String.format(
                "IOException while trying to read from underlying socket for Association=%s IOError count=%d",
                getAssociationName(), this.ioErrors), e);

            if (this.ioErrors > this.management.getMaxIOErrors()) {
                // Close this socket
                this.close();

                // retry to connect after delay
                this.scheduleConnect();
            }
        }
    }

    private PayloadData doReadSctp() throws IOException {

        rxBuffer.clear();
        MessageInfo messageInfo = this.socketChannelSctp.receive(rxBuffer, this, this.associationHandler);

        if (messageInfo == null) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format(" messageInfo is null for Association=%s", getAssociationName()));
            }
            return null;
        }

        int len = messageInfo.bytes();
        if (len == -1) {
            logger.error(String.format("Rx -1 while trying to read from underlying socket for Association=%s ",
                getAssociationName()));
            this.close();
            this.scheduleConnect();
            return null;
        }

        rxBuffer.flip();
        ByteBuf byteBuf = Unpooled.copiedBuffer(rxBuffer);
        rxBuffer.clear();

        PayloadData payload = new PayloadData(len, byteBuf, messageInfo.isComplete(), messageInfo.isUnordered(),
            messageInfo.payloadProtocolID(), messageInfo.streamNumber());

        return payload;
    }

    private PayloadData doReadTcp() throws IOException {

        rxBuffer.clear();
        int len = this.socketChannelTcp.read(rxBuffer);
        if (len == -1) {
            logger.warn(String.format("Rx -1 while trying to read from underlying socket for Association=%s ",
                getAssociationName()));
            this.close();
            this.scheduleConnect();
            return null;
        }

        rxBuffer.flip();
        ByteBuf byteBuf = Unpooled.copiedBuffer(rxBuffer);
        rxBuffer.clear();

        PayloadData payload = new PayloadData(len, byteBuf, true, false, 0, 0);

        return payload;
    }

    protected void write(SelectionKey key) {

        try {
            // TODO Do we need to synchronize ConcurrentLinkedQueue?
            // synchronized (this.txQueue) {
            if (!txQueue.isEmpty()) {
                while (!txQueue.isEmpty()) {
                    // Lets read all the messages in txQueue and send
                    PayloadData payloadData = txQueue.poll();
                    logger.debug(String.format("Tx : Ass=%s %s", getAssociationName(), payloadData));

                    if (this.ipChannelType == IpChannelType.SCTP) {
                        int seqControl = payloadData.getStreamNumber();
                        if (seqControl < 0 || seqControl >= this.associationHandler.getMaxOutboundStreams()) {
                            try {
                                // TODO : calling in same Thread. Is this ok? or
                                // dangerous?
                                this.associationListener.inValidStreamId(payloadData);
                            } catch (Exception e) {
                            }
                            logger.error(String.format(
                                "Cannot send through stream '%d' over association '%s', message will be lost!",
                                seqControl, getAssociationName()));
                            continue;
                        }
                        msgInfo = MessageInfo.createOutgoing(this.peerSocketAddress, seqControl);
                        msgInfo.payloadProtocolID(payloadData.getPayloadProtocolId());
                        msgInfo.complete(payloadData.isComplete());
                        msgInfo.unordered(payloadData.isUnordered());
                    }

                    int bytesSent = this.doSend(payloadData.getByteBuf());
                    if (bytesSent == 0) {
                        logger.warn(String.format(
                            "Unable to send '%d' bytes over association '%s', retried '%d' times, will retry again.",
                            payloadData.getDataLength(), getAssociationName(), payloadData.getRetryCount()));
                        // txQueue.add(payloadData.retry());
                        send(payloadData.retry());
                    } else if (bytesSent != payloadData.getDataLength()) {
                        logger.error(String.format(
                            "Sent '%d' bytes out of '%d', giving up for this packet on association '%s'",
                            bytesSent, payloadData.getDataLength(), getAssociationName()));
                    } else {
                        // dissect diameter header (e2e & hbh)
                        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payloadData.getData()));
                        in.readInt(); in.readInt(); in.readInt();
                        long hopByHopId = ((long) in.readInt() << 32) >>> 32;
                        long endToEndId = ((long) in.readInt() << 32) >>> 32;

                        logger.debug(String.format(
                            "Sent '%d' bytes, association '%s', retry count '%d', hbh 0x%x, e2e 0x%x.",
                            payloadData.getDataLength(), getAssociationName(), payloadData.getRetryCount(),
                            hopByHopId, endToEndId));
                    }

                }
            }
            if (txQueue.isEmpty()) {
                // We wrote away all data, so we're no longer interested
                // in writing on this socket. Switch back to waiting for
                // data.ﬁ
                key.interestOps(SelectionKey.OP_READ);
            }
        } catch (Exception e) {
            this.ioErrors++;
            logger.error(String.format(
                "IOException while trying to write to underlying socket for Association=%s IOError count=%d",
                getAssociationName(), this.ioErrors), e);
            if (this.ioErrors > this.management.getMaxIOErrors()) {
                // Close this socket
                this.close();

                // retry to connect after delay
                this.scheduleConnect();
            }
        }// try-catch
    }

    private int doSend(ByteBuf buffer) throws IOException {
        if (this.ipChannelType == IpChannelType.SCTP)
            return this.doSendSctp(buffer);
        else
            return this.doSendTcp(buffer);
    }

    private int doSendSctp(ByteBuf buffer) throws IOException {
        return this.socketChannelSctp.send(buffer.nioBuffer(), msgInfo);
    }

    private int doSendTcp(ByteBuf buffer) throws IOException {
        return this.socketChannelTcp.write(buffer.nioBuffer());
    }

	@Override
	public ByteBufAllocator getByteBufAllocator() {
		return null;
	}

	@Override
	public int getCongestionLevel() {
		return 0;
	}

    protected void close() {
        if (this.getSocketChannel() != null) {
            try {
                this.getSocketChannel().close();
            } catch (Exception e) {
                logger.error(String.format("Exception while closing the SctpSocket for Association=%s",
                    getAssociationName()), e);
            }
        }

        try {
            this.markAssociationDown();
            this.associationListener.onCommunicationShutdown(this);
        } catch (Exception e) {
            logger.error(String.format(
                "Exception while calling onCommunicationShutdown on AssociationListener for Association=%s",
                getAssociationName()), e);
        }

        // Finally clear the txQueue
        if (this.txQueue.size() > 0) {
            logger.warn(String.format("Clearing txQueue for Association=%s. %d messages still pending will be cleared",
                getAssociationName(), this.txQueue.size()));
        }
        this.txQueue.clear();
    }

    protected void scheduleConnect() {
        if (this.getAssociationType() == AssociationType.CLIENT) {
            // If Association is of Client type, restart the connection procedure
            FastList<ChangeRequest> pendingChanges = this.management.getPendingChanges();
            synchronized (pendingChanges) {
                pendingChanges.add(new ChangeRequest(this, ChangeRequest.CONNECT, System.currentTimeMillis()
                    + this.management.getConnectDelay()));
            }
        }
    }

    protected void initiateConnection() throws IOException {

        // If Association is stopped, don't try to initiate connect
        if (!this.started) {
            return;
        }

        if (this.getSocketChannel() != null) {
            try {
                this.getSocketChannel().close();
            } catch (Exception e) {
                logger.error(
                    String.format(
                        "Exception while trying to close existing SCTP socket and initiate new socket for Association=%s",
                        getAssociationName()), e);
            }
        }

        try {
            if (this.ipChannelType == IpChannelType.SCTP)
                this.doInitiateConnectionSctp();
            else
                this.doInitiateConnectionTcp();
        } catch (Exception e) {
            logger.error("Error while initiating a connection", e);
            this.scheduleConnect();
            return;
        }

        // reset the ioErrors
        this.ioErrors = 0;

        // Queue a channel registration since the caller is not the
        // selecting thread. As part of the registration we'll register
        // an interest in connection events. These are raised when a channel
        // is ready to complete connection establishment.
        FastList<ChangeRequest> pendingChanges = this.management.getPendingChanges();
        synchronized (pendingChanges) {
            pendingChanges.add(new ChangeRequest(this.getSocketChannel(), this, ChangeRequest.REGISTER,
                SelectionKey.OP_CONNECT));
        }

        // Finally, wake up our selecting thread so it can make the required
        // changes
        this.management.getSocketSelector().wakeup();

    }

    private void doInitiateConnectionSctp() throws IOException {
        // Create a non-blocking socket channel
        this.socketChannelSctp = SctpChannel.open();
        this.socketChannelSctp.configureBlocking(false);

        logger.info(String.format("Initial receive buffer SO_RCVBUF: %s and initial send buffer SO_SNDBUF: %s",
            this.socketChannelSctp.getOption(SctpStandardSocketOptions.SO_RCVBUF),
            this.socketChannelSctp.getOption(SctpStandardSocketOptions.SO_SNDBUF)));
        this.socketChannelSctp.setOption(SctpStandardSocketOptions.SO_SNDBUF, new Integer(management.getBufferSize()));
        this.socketChannelSctp.setOption(SctpStandardSocketOptions.SO_RCVBUF, new Integer(management.getBufferSize()));
        logger.info(String.format("Setting receive buffer SO_RCVBUF: %s and setting send buffer SO_SNDBUF: %s",
            this.socketChannelSctp.getOption(SctpStandardSocketOptions.SO_RCVBUF),
            this.socketChannelSctp.getOption(SctpStandardSocketOptions.SO_SNDBUF)));

        // bind to host address:port
        InetSocketAddress isa = new InetSocketAddress(this.hostAddress, this.hostPort);
        try {
			// TODO: is this the right way to set the primary address???
			// this.socketChannelSctp.setOption(SctpStandardSocketOptions.SCTP_PRIMARY_ADDR, isa);
			this.socketChannelSctp.bind(isa);
		} catch (java.nio.channels.AlreadyBoundException abe) {
			logger.warn(String.format("Inet socket address '{}' already bound...", isa));
		} catch (Exception e) {
			throw e;
		}if (this.extraHostAddresses != null) {
            for (String s : extraHostAddresses) {try {
                this.socketChannelSctp.bindAddress(InetAddress.getByName(s));} catch (Exception e) {
					logger.warn(String.format("Unable to bind extra host address '%s'", s));
				}
            }
        }

        // Kick off connection establishment
        this.socketChannelSctp.connect(new InetSocketAddress(this.peerAddress, this.peerPort), 32, 32);

    }

    private void doInitiateConnectionTcp() throws IOException {

        // Create a non-blocking socket channel
        this.socketChannelTcp = SocketChannel.open();
        this.socketChannelTcp.configureBlocking(false);

        // bind to host address:port
        this.socketChannelTcp.bind(new InetSocketAddress(this.hostAddress, this.hostPort));

        // Kick off connection establishment
        this.socketChannelTcp.connect(new InetSocketAddress(this.peerAddress, this.peerPort));
    }

    protected void createworkerThreadTable(int maximumBoundStream) {
        this.workerThreadTable = new int[maximumBoundStream];
        this.management.populateWorkerThread(this.workerThreadTable);
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder();
        sb.append("Association [name=").append(getAssociationName()).append(", associationType=").append(this.type)
            .append(", ipChannelType=").append(this.ipChannelType).append(", hostAddress=")
            .append(this.hostAddress).append(", hostPort=").append(this.hostPort).append(", peerAddress=")
            .append(this.peerAddress).append(", peerPort=").append(this.peerPort).append(", serverName=")
            .append(this.serverName);

        sb.append(", extraHostAddress=[");

        if (this.extraHostAddresses != null) {
            for (int i = 0; i < this.extraHostAddresses.length; i++) {
                String extraHostAddress = this.extraHostAddresses[i];
                sb.append(extraHostAddress);
                sb.append(", ");
            }
        }

        sb.append("]]");

        return sb.toString();
    }

    /**
     * XML Serialization/Deserialization
     */
    protected static final XMLFormat<AssociationImpl> ASSOCIATION_XML = new XMLFormat<AssociationImpl>(
        AssociationImpl.class) {

        @SuppressWarnings("unchecked")
        @Override
        public void read(javolution.xml.XMLFormat.InputElement xml, AssociationImpl association)
            throws XMLStreamException {
            association.name = xml.getAttribute(NAME, "");
            association.type = AssociationType.getAssociationType(xml.getAttribute(ASSOCIATION_TYPE, ""));
            association.hostAddress = xml.getAttribute(HOST_ADDRESS, "");
            association.hostPort = xml.getAttribute(HOST_PORT, 0);

            association.peerAddress = xml.getAttribute(PEER_ADDRESS, "");
            association.peerPort = xml.getAttribute(PEER_PORT, 0);

            association.serverName = xml.getAttribute(SERVER_NAME, "");
            association.ipChannelType = IpChannelType.getInstance(xml.getAttribute(IP_CHANNEL_TYPE,
                IpChannelType.SCTP.getCode()));
            if (association.ipChannelType == null)
                association.ipChannelType = IpChannelType.SCTP;

            int extraHostAddressesSize = xml.getAttribute(EXTRA_HOST_ADDRESSES_SIZE, 0);
            association.extraHostAddresses = new String[extraHostAddressesSize];

            for (int i = 0; i < extraHostAddressesSize; i++) {
                association.extraHostAddresses[i] = xml.get(EXTRA_HOST_ADDRESS, String.class);
            }

        }

        @Override
        public void write(AssociationImpl association, javolution.xml.XMLFormat.OutputElement xml)
            throws XMLStreamException {
            xml.setAttribute(NAME, association.name);
            xml.setAttribute(ASSOCIATION_TYPE, association.type.getType());
            xml.setAttribute(HOST_ADDRESS, association.hostAddress);
            xml.setAttribute(HOST_PORT, association.hostPort);

            xml.setAttribute(PEER_ADDRESS, association.peerAddress);
            xml.setAttribute(PEER_PORT, association.peerPort);

            xml.setAttribute(SERVER_NAME, association.serverName);
            xml.setAttribute(IP_CHANNEL_TYPE, association.ipChannelType.getCode());

            xml.setAttribute(EXTRA_HOST_ADDRESSES_SIZE,
                association.extraHostAddresses != null ? association.extraHostAddresses.length : 0);
            if (association.extraHostAddresses != null) {
                for (String s : association.extraHostAddresses) {
                    xml.add(s, EXTRA_HOST_ADDRESS, String.class);
                }
            }
        }
    };
}
