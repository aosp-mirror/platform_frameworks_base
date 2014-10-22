package android.net.dhcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.ShortBuffer;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines basic data and operations needed to build and use packets for the
 * DHCP protocol.  Subclasses create the specific packets used at each
 * stage of the negotiation.
 */
abstract class DhcpPacket {
    protected static final String TAG = "DhcpPacket";

    /**
     * Packet encapsulations.
     */
    public static final int ENCAP_L2 = 0;    // EthernetII header included
    public static final int ENCAP_L3 = 1;    // IP/UDP header included
    public static final int ENCAP_BOOTP = 2; // BOOTP contents only

    /**
     * IP layer definitions.
     */
    private static final byte IP_TYPE_UDP = (byte) 0x11;

    /**
     * IP: Version 4, Header Length 20 bytes
     */
    private static final byte IP_VERSION_HEADER_LEN = (byte) 0x45;

    /**
     * IP: Flags 0, Fragment Offset 0, Don't Fragment
     */
    private static final short IP_FLAGS_OFFSET = (short) 0x4000;

    /**
     * IP: TOS
     */
    private static final byte IP_TOS_LOWDELAY = (byte) 0x10;

    /**
     * IP: TTL -- use default 64 from RFC1340
     */
    private static final byte IP_TTL = (byte) 0x40;

    /**
     * The client DHCP port.
     */
    static final short DHCP_CLIENT = (short) 68;

    /**
     * The server DHCP port.
     */
    static final short DHCP_SERVER = (short) 67;

    /**
     * The message op code indicating a request from a client.
     */
    protected static final byte DHCP_BOOTREQUEST = (byte) 1;

    /**
     * The message op code indicating a response from the server.
     */
    protected static final byte DHCP_BOOTREPLY = (byte) 2;

    /**
     * The code type used to identify an Ethernet MAC address in the
     * Client-ID field.
     */
    protected static final byte CLIENT_ID_ETHER = (byte) 1;

    /**
     * The maximum length of a packet that can be constructed.
     */
    protected static final int MAX_LENGTH = 1500;

    /**
     * DHCP Optional Type: DHCP Subnet Mask
     */
    protected static final byte DHCP_SUBNET_MASK = 1;
    protected InetAddress mSubnetMask;

    /**
     * DHCP Optional Type: DHCP Router
     */
    protected static final byte DHCP_ROUTER = 3;
    protected InetAddress mGateway;

    /**
     * DHCP Optional Type: DHCP DNS Server
     */
    protected static final byte DHCP_DNS_SERVER = 6;
    protected List<InetAddress> mDnsServers;

    /**
     * DHCP Optional Type: DHCP Host Name
     */
    protected static final byte DHCP_HOST_NAME = 12;
    protected String mHostName;

    /**
     * DHCP Optional Type: DHCP DOMAIN NAME
     */
    protected static final byte DHCP_DOMAIN_NAME = 15;
    protected String mDomainName;

    /**
     * DHCP Optional Type: DHCP BROADCAST ADDRESS
     */
    protected static final byte DHCP_BROADCAST_ADDRESS = 28;
    protected InetAddress mBroadcastAddress;

    /**
     * DHCP Optional Type: DHCP Requested IP Address
     */
    protected static final byte DHCP_REQUESTED_IP = 50;
    protected InetAddress mRequestedIp;

    /**
     * DHCP Optional Type: DHCP Lease Time
     */
    protected static final byte DHCP_LEASE_TIME = 51;
    protected Integer mLeaseTime;

    /**
     * DHCP Optional Type: DHCP Message Type
     */
    protected static final byte DHCP_MESSAGE_TYPE = 53;
    // the actual type values
    protected static final byte DHCP_MESSAGE_TYPE_DISCOVER = 1;
    protected static final byte DHCP_MESSAGE_TYPE_OFFER = 2;
    protected static final byte DHCP_MESSAGE_TYPE_REQUEST = 3;
    protected static final byte DHCP_MESSAGE_TYPE_DECLINE = 4;
    protected static final byte DHCP_MESSAGE_TYPE_ACK = 5;
    protected static final byte DHCP_MESSAGE_TYPE_NAK = 6;
    protected static final byte DHCP_MESSAGE_TYPE_INFORM = 8;

    /**
     * DHCP Optional Type: DHCP Server Identifier
     */
    protected static final byte DHCP_SERVER_IDENTIFIER = 54;
    protected InetAddress mServerIdentifier;

    /**
     * DHCP Optional Type: DHCP Parameter List
     */
    protected static final byte DHCP_PARAMETER_LIST = 55;
    protected byte[] mRequestedParams;

    /**
     * DHCP Optional Type: DHCP MESSAGE
     */
    protected static final byte DHCP_MESSAGE = 56;
    protected String mMessage;

    /**
     * DHCP Optional Type: DHCP Renewal Time Value
     */
    protected static final byte DHCP_RENEWAL_TIME = 58;

    /**
     * DHCP Optional Type: Vendor Class Identifier
     */
    protected static final byte DHCP_VENDOR_CLASS_ID = 60;

    /**
     * DHCP Optional Type: DHCP Client Identifier
     */
    protected static final byte DHCP_CLIENT_IDENTIFIER = 61;

    /**
     * The transaction identifier used in this particular DHCP negotiation
     */
    protected final int mTransId;

    /**
     * The IP address of the client host.  This address is typically
     * proposed by the client (from an earlier DHCP negotiation) or
     * supplied by the server.
     */
    protected final InetAddress mClientIp;
    protected final InetAddress mYourIp;
    private final InetAddress mNextIp;
    private final InetAddress mRelayIp;

    /**
     * Does the client request a broadcast response?
     */
    protected boolean mBroadcast;

    /**
     * The six-octet MAC of the client.
     */
    protected final byte[] mClientMac;

    /**
     * Asks the packet object to signal the next operation in the DHCP
     * protocol.  The available actions are methods defined in the
     * DhcpStateMachine interface.
     */
    public abstract void doNextOp(DhcpStateMachine stateMachine);

    /**
     * Asks the packet object to create a ByteBuffer serialization of
     * the packet for transmission.
     */
    public abstract ByteBuffer buildPacket(int encap, short destUdp,
        short srcUdp);

    /**
     * Allows the concrete class to fill in packet-type-specific details,
     * typically optional parameters at the end of the packet.
     */
    abstract void finishPacket(ByteBuffer buffer);

    protected DhcpPacket(int transId, InetAddress clientIp, InetAddress yourIp,
                         InetAddress nextIp, InetAddress relayIp,
                         byte[] clientMac, boolean broadcast) {
        mTransId = transId;
        mClientIp = clientIp;
        mYourIp = yourIp;
        mNextIp = nextIp;
        mRelayIp = relayIp;
        mClientMac = clientMac;
        mBroadcast = broadcast;
    }

    /**
     * Returns the transaction ID.
     */
    public int getTransactionId() {
        return mTransId;
    }

    /**
     * Creates a new L3 packet (including IP header) containing the
     * DHCP udp packet.  This method relies upon the delegated method
     * finishPacket() to insert the per-packet contents.
     */
    protected void fillInPacket(int encap, InetAddress destIp,
        InetAddress srcIp, short destUdp, short srcUdp, ByteBuffer buf,
        byte requestCode, boolean broadcast) {
        byte[] destIpArray = destIp.getAddress();
        byte[] srcIpArray = srcIp.getAddress();
        int ipLengthOffset = 0;
        int ipChecksumOffset = 0;
        int endIpHeader = 0;
        int udpHeaderOffset = 0;
        int udpLengthOffset = 0;
        int udpChecksumOffset = 0;

        buf.clear();
        buf.order(ByteOrder.BIG_ENDIAN);

        // if a full IP packet needs to be generated, put the IP & UDP
        // headers in place, and pre-populate with artificial values
        // needed to seed the IP checksum.
        if (encap == ENCAP_L3) {
            // fake IP header, used in the IP-header checksum
            buf.put(IP_VERSION_HEADER_LEN);
            buf.put(IP_TOS_LOWDELAY);    // tos: IPTOS_LOWDELAY
            ipLengthOffset = buf.position();
            buf.putShort((short)0);  // length
            buf.putShort((short)0);  // id
            buf.putShort(IP_FLAGS_OFFSET); // ip offset: don't fragment
            buf.put(IP_TTL);    // TTL: use default 64 from RFC1340
            buf.put(IP_TYPE_UDP);
            ipChecksumOffset = buf.position();
            buf.putShort((short) 0); // checksum

            buf.put(srcIpArray);
            buf.put(destIpArray);
            endIpHeader = buf.position();

            // UDP header
            udpHeaderOffset = buf.position();
            buf.putShort(srcUdp);
            buf.putShort(destUdp);
            udpLengthOffset = buf.position();
            buf.putShort((short) 0); // length
            udpChecksumOffset = buf.position();
            buf.putShort((short) 0); // UDP checksum -- initially zero
        }

        // DHCP payload
        buf.put(requestCode);
        buf.put((byte) 1); // Hardware Type: Ethernet
        buf.put((byte) mClientMac.length); // Hardware Address Length
        buf.put((byte) 0); // Hop Count
        buf.putInt(mTransId);  // Transaction ID
        buf.putShort((short) 0); // Elapsed Seconds

        if (broadcast) {
            buf.putShort((short) 0x8000); // Flags
        } else {
            buf.putShort((short) 0x0000); // Flags
        }

        buf.put(mClientIp.getAddress());
        buf.put(mYourIp.getAddress());
        buf.put(mNextIp.getAddress());
        buf.put(mRelayIp.getAddress());
        buf.put(mClientMac);
        buf.position(buf.position() +
                     (16 - mClientMac.length) // pad addr to 16 bytes
                     + 64     // empty server host name (64 bytes)
                     + 128);  // empty boot file name (128 bytes)
        buf.putInt(0x63825363); // magic number
        finishPacket(buf);

        // round up to an even number of octets
        if ((buf.position() & 1) == 1) {
            buf.put((byte) 0);
        }

        // If an IP packet is being built, the IP & UDP checksums must be
        // computed.
        if (encap == ENCAP_L3) {
            // fix UDP header: insert length
            short udpLen = (short)(buf.position() - udpHeaderOffset);
            buf.putShort(udpLengthOffset, udpLen);
            // fix UDP header: checksum
            // checksum for UDP at udpChecksumOffset
            int udpSeed = 0;

            // apply IPv4 pseudo-header.  Read IP address src and destination
            // values from the IP header and accumulate checksum.
            udpSeed += intAbs(buf.getShort(ipChecksumOffset + 2));
            udpSeed += intAbs(buf.getShort(ipChecksumOffset + 4));
            udpSeed += intAbs(buf.getShort(ipChecksumOffset + 6));
            udpSeed += intAbs(buf.getShort(ipChecksumOffset + 8));

            // accumulate extra data for the pseudo-header
            udpSeed += IP_TYPE_UDP;
            udpSeed += udpLen;
            // and compute UDP checksum
            buf.putShort(udpChecksumOffset, (short) checksum(buf, udpSeed,
                                                             udpHeaderOffset,
                                                             buf.position()));
            // fix IP header: insert length
            buf.putShort(ipLengthOffset, (short)buf.position());
            // fixup IP-header checksum
            buf.putShort(ipChecksumOffset,
                         (short) checksum(buf, 0, 0, endIpHeader));
        }
    }

    /**
     * Converts a signed short value to an unsigned int value.  Needed
     * because Java does not have unsigned types.
     */
    private int intAbs(short v) {
        if (v < 0) {
            int r = v + 65536;
            return r;
        } else {
            return(v);
        }
    }

    /**
     * Performs an IP checksum (used in IP header and across UDP
     * payload) on the specified portion of a ByteBuffer.  The seed
     * allows the checksum to commence with a specified value.
     */
    private int checksum(ByteBuffer buf, int seed, int start, int end) {
        int sum = seed;
        int bufPosition = buf.position();

        // set position of original ByteBuffer, so that the ShortBuffer
        // will be correctly initialized
        buf.position(start);
        ShortBuffer shortBuf = buf.asShortBuffer();

        // re-set ByteBuffer position
        buf.position(bufPosition);

        short[] shortArray = new short[(end - start) / 2];
        shortBuf.get(shortArray);

        for (short s : shortArray) {
            sum += intAbs(s);
        }

        start += shortArray.length * 2;

        // see if a singleton byte remains
        if (end != start) {
            short b = buf.get(start);

            // make it unsigned
            if (b < 0) {
                b += 256;
            }

            sum += b * 256;
        }

        sum = ((sum >> 16) & 0xFFFF) + (sum & 0xFFFF);
        sum = ((sum + ((sum >> 16) & 0xFFFF)) & 0xFFFF);
        int negated = ~sum;
        return intAbs((short) negated);
    }

    /**
     * Adds an optional parameter containing a single byte value.
     */
    protected void addTlv(ByteBuffer buf, byte type, byte value) {
        buf.put(type);
        buf.put((byte) 1);
        buf.put(value);
    }

    /**
     * Adds an optional parameter containing an array of bytes.
     */
    protected void addTlv(ByteBuffer buf, byte type, byte[] payload) {
        if (payload != null) {
            buf.put(type);
            buf.put((byte) payload.length);
            buf.put(payload);
        }
    }

    /**
     * Adds an optional parameter containing an IP address.
     */
    protected void addTlv(ByteBuffer buf, byte type, InetAddress addr) {
        if (addr != null) {
            addTlv(buf, type, addr.getAddress());
        }
    }

    /**
     * Adds an optional parameter containing a list of IP addresses.
     */
    protected void addTlv(ByteBuffer buf, byte type, List<InetAddress> addrs) {
        if (addrs != null && addrs.size() > 0) {
            buf.put(type);
            buf.put((byte)(4 * addrs.size()));

            for (InetAddress addr : addrs) {
                buf.put(addr.getAddress());
            }
        }
    }

    /**
     * Adds an optional parameter containing a simple integer
     */
    protected void addTlv(ByteBuffer buf, byte type, Integer value) {
        if (value != null) {
            buf.put(type);
            buf.put((byte) 4);
            buf.putInt(value.intValue());
        }
    }

    /**
     * Adds an optional parameter containing and ASCII string.
     */
    protected void addTlv(ByteBuffer buf, byte type, String str) {
        if (str != null) {
            buf.put(type);
            buf.put((byte) str.length());

            for (int i = 0; i < str.length(); i++) {
                buf.put((byte) str.charAt(i));
            }
        }
    }

    /**
     * Adds the special end-of-optional-parameters indicator.
     */
    protected void addTlvEnd(ByteBuffer buf) {
        buf.put((byte) 0xFF);
    }

    /**
     * Converts a MAC from an array of octets to an ASCII string.
     */
    public static String macToString(byte[] mac) {
        String macAddr = "";

        for (int i = 0; i < mac.length; i++) {
            String hexString = "0" + Integer.toHexString(mac[i]);

            // substring operation grabs the last 2 digits: this
            // allows signed bytes to be converted correctly.
            macAddr += hexString.substring(hexString.length() - 2);

            if (i != (mac.length - 1)) {
                macAddr += ":";
            }
        }

        return macAddr;
    }

    public String toString() {
        String macAddr = macToString(mClientMac);

        return macAddr;
    }

    /**
     * Reads a four-octet value from a ByteBuffer and construct
     * an IPv4 address from that value.
     */
    private static InetAddress readIpAddress(ByteBuffer packet) {
        InetAddress result = null;
        byte[] ipAddr = new byte[4];
        packet.get(ipAddr);

        try {
            result = InetAddress.getByAddress(ipAddr);
        } catch (UnknownHostException ex) {
            // ipAddr is numeric, so this should not be
            // triggered.  However, if it is, just nullify
            result = null;
        }

        return result;
    }

    /**
     * Reads a string of specified length from the buffer.
     */
    private static String readAsciiString(ByteBuffer buf, int byteCount) {
        byte[] bytes = new byte[byteCount];
        buf.get(bytes);
        return new String(bytes, 0, bytes.length, StandardCharsets.US_ASCII);
    }

    /**
     * Creates a concrete DhcpPacket from the supplied ByteBuffer.  The
     * buffer may have an L2 encapsulation (which is the full EthernetII
     * format starting with the source-address MAC) or an L3 encapsulation
     * (which starts with the IP header).
     * <br>
     * A subset of the optional parameters are parsed and are stored
     * in object fields.
     */
    public static DhcpPacket decodeFullPacket(ByteBuffer packet, int pktType)
    {
        // bootp parameters
        int transactionId;
        InetAddress clientIp;
        InetAddress yourIp;
        InetAddress nextIp;
        InetAddress relayIp;
        byte[] clientMac;
        List<InetAddress> dnsServers = new ArrayList<InetAddress>();
        InetAddress gateway = null; // aka router
        Integer leaseTime = null;
        InetAddress serverIdentifier = null;
        InetAddress netMask = null;
        String message = null;
        String vendorId = null;
        byte[] expectedParams = null;
        String hostName = null;
        String domainName = null;
        InetAddress ipSrc = null;
        InetAddress ipDst = null;
        InetAddress bcAddr = null;
        InetAddress requestedIp = null;

        // dhcp options
        byte dhcpType = (byte) 0xFF;

        packet.order(ByteOrder.BIG_ENDIAN);

        // check to see if we need to parse L2, IP, and UDP encaps
        if (pktType == ENCAP_L2) {
            // System.out.println("buffer len " + packet.limit());
            byte[] l2dst = new byte[6];
            byte[] l2src = new byte[6];

            packet.get(l2dst);
            packet.get(l2src);

            short l2type = packet.getShort();

            if (l2type != 0x0800)
                return null;
        }

        if ((pktType == ENCAP_L2) || (pktType == ENCAP_L3)) {
            // assume l2type is 0x0800, i.e. IP
            byte ipType = packet.get();
            // System.out.println("ipType is " + ipType);
            byte ipDiffServicesField = packet.get();
            short ipTotalLength = packet.getShort();
            short ipIdentification = packet.getShort();
            byte ipFlags = packet.get();
            byte ipFragOffset = packet.get();
            byte ipTTL = packet.get();
            byte ipProto = packet.get();
            short ipChksm = packet.getShort();

            ipSrc = readIpAddress(packet);
            ipDst = readIpAddress(packet);

            if (ipProto != IP_TYPE_UDP) // UDP
                return null;

            // assume UDP
            short udpSrcPort = packet.getShort();
            short udpDstPort = packet.getShort();
            short udpLen = packet.getShort();
            short udpChkSum = packet.getShort();

            if ((udpSrcPort != DHCP_SERVER) && (udpSrcPort != DHCP_CLIENT))
                return null;
        }

        // assume bootp
        byte type = packet.get();
        byte hwType = packet.get();
        byte addrLen = packet.get();
        byte hops = packet.get();
        transactionId = packet.getInt();
        short elapsed = packet.getShort();
        short bootpFlags = packet.getShort();
        boolean broadcast = (bootpFlags & 0x8000) != 0;
        byte[] ipv4addr = new byte[4];

        try {
            packet.get(ipv4addr);
            clientIp = InetAddress.getByAddress(ipv4addr);
            packet.get(ipv4addr);
            yourIp = InetAddress.getByAddress(ipv4addr);
            packet.get(ipv4addr);
            nextIp = InetAddress.getByAddress(ipv4addr);
            packet.get(ipv4addr);
            relayIp = InetAddress.getByAddress(ipv4addr);
        } catch (UnknownHostException ex) {
            return null;
        }

        clientMac = new byte[addrLen];
        packet.get(clientMac);

        // skip over address padding (16 octets allocated)
        packet.position(packet.position() + (16 - addrLen)
                        + 64    // skip server host name (64 chars)
                        + 128); // skip boot file name (128 chars)

        int dhcpMagicCookie = packet.getInt();

        if (dhcpMagicCookie !=  0x63825363)
            return null;

        // parse options
        boolean notFinishedOptions = true;

        while ((packet.position() < packet.limit()) && notFinishedOptions) {
            byte optionType = packet.get();

            if (optionType == (byte) 0xFF) {
                notFinishedOptions = false;
            } else {
                byte optionLen = packet.get();
                int expectedLen = 0;

                switch(optionType) {
                    case DHCP_SUBNET_MASK:
                        netMask = readIpAddress(packet);
                        expectedLen = 4;
                        break;
                    case DHCP_ROUTER:
                        gateway = readIpAddress(packet);
                        expectedLen = 4;
                        break;
                    case DHCP_DNS_SERVER:
                        expectedLen = 0;

                        for (expectedLen = 0; expectedLen < optionLen;
                             expectedLen += 4) {
                            dnsServers.add(readIpAddress(packet));
                        }
                        break;
                    case DHCP_HOST_NAME:
                        expectedLen = optionLen;
                        hostName = readAsciiString(packet, optionLen);
                        break;
                    case DHCP_DOMAIN_NAME:
                        expectedLen = optionLen;
                        domainName = readAsciiString(packet, optionLen);
                        break;
                    case DHCP_BROADCAST_ADDRESS:
                        bcAddr = readIpAddress(packet);
                        expectedLen = 4;
                        break;
                    case DHCP_REQUESTED_IP:
                        requestedIp = readIpAddress(packet);
                        expectedLen = 4;
                        break;
                    case DHCP_LEASE_TIME:
                        leaseTime = Integer.valueOf(packet.getInt());
                        expectedLen = 4;
                        break;
                    case DHCP_MESSAGE_TYPE:
                        dhcpType = packet.get();
                        expectedLen = 1;
                        break;
                    case DHCP_SERVER_IDENTIFIER:
                        serverIdentifier = readIpAddress(packet);
                        expectedLen = 4;
                        break;
                    case DHCP_PARAMETER_LIST:
                        expectedParams = new byte[optionLen];
                        packet.get(expectedParams);
                        expectedLen = optionLen;
                        break;
                    case DHCP_MESSAGE:
                        expectedLen = optionLen;
                        message = readAsciiString(packet, optionLen);
                        break;
                    case DHCP_VENDOR_CLASS_ID:
                        expectedLen = optionLen;
                        vendorId = readAsciiString(packet, optionLen);
                        break;
                    case DHCP_CLIENT_IDENTIFIER: { // Client identifier
                        byte[] id = new byte[optionLen];
                        packet.get(id);
                        expectedLen = optionLen;
                    } break;
                    default:
                        // ignore any other parameters
                        for (int i = 0; i < optionLen; i++) {
                            expectedLen++;
                            byte throwaway = packet.get();
                        }
                }

                if (expectedLen != optionLen) {
                    return null;
                }
            }
        }

        DhcpPacket newPacket;

        switch(dhcpType) {
            case -1: return null;
            case DHCP_MESSAGE_TYPE_DISCOVER:
                newPacket = new DhcpDiscoverPacket(
                    transactionId, clientMac, broadcast);
                break;
            case DHCP_MESSAGE_TYPE_OFFER:
                newPacket = new DhcpOfferPacket(
                    transactionId, broadcast, ipSrc, yourIp, clientMac);
                break;
            case DHCP_MESSAGE_TYPE_REQUEST:
                newPacket = new DhcpRequestPacket(
                    transactionId, clientIp, clientMac, broadcast);
                break;
            case DHCP_MESSAGE_TYPE_DECLINE:
                newPacket = new DhcpDeclinePacket(
                    transactionId, clientIp, yourIp, nextIp, relayIp,
                    clientMac);
                break;
            case DHCP_MESSAGE_TYPE_ACK:
                newPacket = new DhcpAckPacket(
                    transactionId, broadcast, ipSrc, yourIp, clientMac);
                break;
            case DHCP_MESSAGE_TYPE_NAK:
                newPacket = new DhcpNakPacket(
                    transactionId, clientIp, yourIp, nextIp, relayIp,
                    clientMac);
                break;
            case DHCP_MESSAGE_TYPE_INFORM:
                newPacket = new DhcpInformPacket(
                    transactionId, clientIp, yourIp, nextIp, relayIp,
                    clientMac);
                break;
            default:
                System.out.println("Unimplemented type: " + dhcpType);
                return null;
        }

        newPacket.mBroadcastAddress = bcAddr;
        newPacket.mDnsServers = dnsServers;
        newPacket.mDomainName = domainName;
        newPacket.mGateway = gateway;
        newPacket.mHostName = hostName;
        newPacket.mLeaseTime = leaseTime;
        newPacket.mMessage = message;
        newPacket.mRequestedIp = requestedIp;
        newPacket.mRequestedParams = expectedParams;
        newPacket.mServerIdentifier = serverIdentifier;
        newPacket.mSubnetMask = netMask;
        return newPacket;
    }

    /**
     * Parse a packet from an array of bytes.
     */
    public static DhcpPacket decodeFullPacket(byte[] packet, int pktType)
    {
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
        return decodeFullPacket(buffer, pktType);
    }

    /**
     * Builds a DHCP-DISCOVER packet from the required specified
     * parameters.
     */
    public static ByteBuffer buildDiscoverPacket(int encap, int transactionId,
        byte[] clientMac, boolean broadcast, byte[] expectedParams) {
        DhcpPacket pkt = new DhcpDiscoverPacket(
            transactionId, clientMac, broadcast);
        pkt.mRequestedParams = expectedParams;
        return pkt.buildPacket(encap, DHCP_SERVER, DHCP_CLIENT);
    }

    /**
     * Builds a DHCP-OFFER packet from the required specified
     * parameters.
     */
    public static ByteBuffer buildOfferPacket(int encap, int transactionId,
        boolean broadcast, InetAddress serverIpAddr, InetAddress clientIpAddr,
        byte[] mac, Integer timeout, InetAddress netMask, InetAddress bcAddr,
        InetAddress gateway, List<InetAddress> dnsServers,
        InetAddress dhcpServerIdentifier, String domainName) {
        DhcpPacket pkt = new DhcpOfferPacket(
            transactionId, broadcast, serverIpAddr, clientIpAddr, mac);
        pkt.mGateway = gateway;
        pkt.mDnsServers = dnsServers;
        pkt.mLeaseTime = timeout;
        pkt.mDomainName = domainName;
        pkt.mServerIdentifier = dhcpServerIdentifier;
        pkt.mSubnetMask = netMask;
        pkt.mBroadcastAddress = bcAddr;
        return pkt.buildPacket(encap, DHCP_CLIENT, DHCP_SERVER);
    }

    /**
     * Builds a DHCP-ACK packet from the required specified parameters.
     */
    public static ByteBuffer buildAckPacket(int encap, int transactionId,
        boolean broadcast, InetAddress serverIpAddr, InetAddress clientIpAddr,
        byte[] mac, Integer timeout, InetAddress netMask, InetAddress bcAddr,
        InetAddress gateway, List<InetAddress> dnsServers,
        InetAddress dhcpServerIdentifier, String domainName) {
        DhcpPacket pkt = new DhcpAckPacket(
            transactionId, broadcast, serverIpAddr, clientIpAddr, mac);
        pkt.mGateway = gateway;
        pkt.mDnsServers = dnsServers;
        pkt.mLeaseTime = timeout;
        pkt.mDomainName = domainName;
        pkt.mSubnetMask = netMask;
        pkt.mServerIdentifier = dhcpServerIdentifier;
        pkt.mBroadcastAddress = bcAddr;
        return pkt.buildPacket(encap, DHCP_CLIENT, DHCP_SERVER);
    }

    /**
     * Builds a DHCP-NAK packet from the required specified parameters.
     */
    public static ByteBuffer buildNakPacket(int encap, int transactionId,
        InetAddress serverIpAddr, InetAddress clientIpAddr, byte[] mac) {
        DhcpPacket pkt = new DhcpNakPacket(transactionId, clientIpAddr,
            serverIpAddr, serverIpAddr, serverIpAddr, mac);
        pkt.mMessage = "requested address not available";
        pkt.mRequestedIp = clientIpAddr;
        return pkt.buildPacket(encap, DHCP_CLIENT, DHCP_SERVER);
    }

    /**
     * Builds a DHCP-REQUEST packet from the required specified parameters.
     */
    public static ByteBuffer buildRequestPacket(int encap,
        int transactionId, InetAddress clientIp, boolean broadcast,
        byte[] clientMac, InetAddress requestedIpAddress,
        InetAddress serverIdentifier, byte[] requestedParams, String hostName) {
        DhcpPacket pkt = new DhcpRequestPacket(transactionId, clientIp,
            clientMac, broadcast);
        pkt.mRequestedIp = requestedIpAddress;
        pkt.mServerIdentifier = serverIdentifier;
        pkt.mHostName = hostName;
        pkt.mRequestedParams = requestedParams;
        ByteBuffer result = pkt.buildPacket(encap, DHCP_SERVER, DHCP_CLIENT);
        return result;
    }
}
