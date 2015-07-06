package android.net.dhcp;

import android.net.DhcpResults;
import android.net.LinkAddress;
import android.net.NetworkUtils;
import android.os.Build;
import android.os.SystemProperties;
import android.system.OsConstants;

import java.io.UnsupportedEncodingException;
import java.net.Inet4Address;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.ShortBuffer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Defines basic data and operations needed to build and use packets for the
 * DHCP protocol.  Subclasses create the specific packets used at each
 * stage of the negotiation.
 */
abstract class DhcpPacket {
    protected static final String TAG = "DhcpPacket";

    // dhcpcd has a minimum lease of 20 seconds, but DhcpStateMachine would refuse to wake up the
    // CPU for anything shorter than 5 minutes. For sanity's sake, this must be higher than the
    // DHCP client timeout.
    public static final int MINIMUM_LEASE = 60;
    public static final int INFINITE_LEASE = (int) 0xffffffff;

    public static final Inet4Address INADDR_ANY = (Inet4Address) Inet4Address.ANY;
    public static final Inet4Address INADDR_BROADCAST = (Inet4Address) Inet4Address.ALL;
    public static final byte[] ETHER_BROADCAST = new byte[] {
            (byte) 0xff, (byte) 0xff, (byte) 0xff,
            (byte) 0xff, (byte) 0xff, (byte) 0xff,
    };

    /**
     * Packet encapsulations.
     */
    public static final int ENCAP_L2 = 0;    // EthernetII header included
    public static final int ENCAP_L3 = 1;    // IP/UDP header included
    public static final int ENCAP_BOOTP = 2; // BOOTP contents only

    /**
     * Minimum length of a DHCP packet, excluding options, in the above encapsulations.
     */
    public static final int MIN_PACKET_LENGTH_BOOTP = 236;  // See diagram in RFC 2131, section 2.
    public static final int MIN_PACKET_LENGTH_L3 = MIN_PACKET_LENGTH_BOOTP + 20 + 8;
    public static final int MIN_PACKET_LENGTH_L2 = MIN_PACKET_LENGTH_L3 + 14;

    public static final int MAX_OPTION_LEN = 255;
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
    protected Inet4Address mSubnetMask;

    /**
     * DHCP Optional Type: DHCP Router
     */
    protected static final byte DHCP_ROUTER = 3;
    protected Inet4Address mGateway;

    /**
     * DHCP Optional Type: DHCP DNS Server
     */
    protected static final byte DHCP_DNS_SERVER = 6;
    protected List<Inet4Address> mDnsServers;

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
     * DHCP Optional Type: DHCP Interface MTU
     */
    protected static final byte DHCP_MTU = 26;
    protected Short mMtu;

    /**
     * DHCP Optional Type: DHCP BROADCAST ADDRESS
     */
    protected static final byte DHCP_BROADCAST_ADDRESS = 28;
    protected Inet4Address mBroadcastAddress;

    /**
     * DHCP Optional Type: Vendor specific information
     */
    protected static final byte DHCP_VENDOR_INFO = 43;
    protected String mVendorInfo;

    /**
     * DHCP Optional Type: DHCP Requested IP Address
     */
    protected static final byte DHCP_REQUESTED_IP = 50;
    protected Inet4Address mRequestedIp;

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
    protected Inet4Address mServerIdentifier;

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
     * DHCP Optional Type: Maximum DHCP Message Size
     */
    protected static final byte DHCP_MAX_MESSAGE_SIZE = 57;
    protected Short mMaxMessageSize;

    /**
     * DHCP Optional Type: DHCP Renewal Time Value
     */
    protected static final byte DHCP_RENEWAL_TIME = 58;
    protected Integer mT1;

    /**
     * DHCP Optional Type: Rebinding Time Value
     */
    protected static final byte DHCP_REBINDING_TIME = 59;
    protected Integer mT2;

    /**
     * DHCP Optional Type: Vendor Class Identifier
     */
    protected static final byte DHCP_VENDOR_CLASS_ID = 60;
    protected String mVendorId;

    /**
     * DHCP Optional Type: DHCP Client Identifier
     */
    protected static final byte DHCP_CLIENT_IDENTIFIER = 61;

    /**
     * DHCP zero-length option code: pad
     */
    protected static final byte DHCP_OPTION_PAD = 0x00;

    /**
     * DHCP zero-length option code: end of options
     */
    protected static final byte DHCP_OPTION_END = (byte) 0xff;

    /**
     * The transaction identifier used in this particular DHCP negotiation
     */
    protected final int mTransId;

    /**
     * The seconds field in the BOOTP header. Per RFC, should be nonzero in client requests only.
     */
    protected final short mSecs;

    /**
     * The IP address of the client host.  This address is typically
     * proposed by the client (from an earlier DHCP negotiation) or
     * supplied by the server.
     */
    protected final Inet4Address mClientIp;
    protected final Inet4Address mYourIp;
    private final Inet4Address mNextIp;
    private final Inet4Address mRelayIp;

    /**
     * Does the client request a broadcast response?
     */
    protected boolean mBroadcast;

    /**
     * The six-octet MAC of the client.
     */
    protected final byte[] mClientMac;

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

    protected DhcpPacket(int transId, short secs, Inet4Address clientIp, Inet4Address yourIp,
                         Inet4Address nextIp, Inet4Address relayIp,
                         byte[] clientMac, boolean broadcast) {
        mTransId = transId;
        mSecs = secs;
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
     * Returns the client MAC.
     */
    public byte[] getClientMac() {
        return mClientMac;
    }

    /**
     * Returns the client ID. This follows RFC 2132 and is based on the hardware address.
     */
    public byte[] getClientId() {
        byte[] clientId = new byte[mClientMac.length + 1];
        clientId[0] = CLIENT_ID_ETHER;
        System.arraycopy(mClientMac, 0, clientId, 1, mClientMac.length);
        return clientId;
    }

    /**
     * Creates a new L3 packet (including IP header) containing the
     * DHCP udp packet.  This method relies upon the delegated method
     * finishPacket() to insert the per-packet contents.
     */
    protected void fillInPacket(int encap, Inet4Address destIp,
        Inet4Address srcIp, short destUdp, short srcUdp, ByteBuffer buf,
        byte requestCode, boolean broadcast) {
        byte[] destIpArray = destIp.getAddress();
        byte[] srcIpArray = srcIp.getAddress();
        int ipHeaderOffset = 0;
        int ipLengthOffset = 0;
        int ipChecksumOffset = 0;
        int endIpHeader = 0;
        int udpHeaderOffset = 0;
        int udpLengthOffset = 0;
        int udpChecksumOffset = 0;

        buf.clear();
        buf.order(ByteOrder.BIG_ENDIAN);

        if (encap == ENCAP_L2) {
            buf.put(ETHER_BROADCAST);
            buf.put(mClientMac);
            buf.putShort((short) OsConstants.ETH_P_IP);
        }

        // if a full IP packet needs to be generated, put the IP & UDP
        // headers in place, and pre-populate with artificial values
        // needed to seed the IP checksum.
        if (encap <= ENCAP_L3) {
            ipHeaderOffset = buf.position();
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
        buf.putShort(mSecs); // Elapsed Seconds

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
        if (encap <= ENCAP_L3) {
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
            buf.putShort(ipLengthOffset, (short)(buf.position() - ipHeaderOffset));
            // fixup IP-header checksum
            buf.putShort(ipChecksumOffset,
                         (short) checksum(buf, 0, ipHeaderOffset, endIpHeader));
        }
    }

    /**
     * Converts a signed short value to an unsigned int value.  Needed
     * because Java does not have unsigned types.
     */
    private static int intAbs(short v) {
        return v & 0xFFFF;
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
    protected static void addTlv(ByteBuffer buf, byte type, byte value) {
        buf.put(type);
        buf.put((byte) 1);
        buf.put(value);
    }

    /**
     * Adds an optional parameter containing an array of bytes.
     */
    protected static void addTlv(ByteBuffer buf, byte type, byte[] payload) {
        if (payload != null) {
            if (payload.length > MAX_OPTION_LEN) {
                throw new IllegalArgumentException("DHCP option too long: "
                        + payload.length + " vs. " + MAX_OPTION_LEN);
            }
            buf.put(type);
            buf.put((byte) payload.length);
            buf.put(payload);
        }
    }

    /**
     * Adds an optional parameter containing an IP address.
     */
    protected static void addTlv(ByteBuffer buf, byte type, Inet4Address addr) {
        if (addr != null) {
            addTlv(buf, type, addr.getAddress());
        }
    }

    /**
     * Adds an optional parameter containing a list of IP addresses.
     */
    protected static void addTlv(ByteBuffer buf, byte type, List<Inet4Address> addrs) {
        if (addrs == null || addrs.size() == 0) return;

        int optionLen = 4 * addrs.size();
        if (optionLen > MAX_OPTION_LEN) {
            throw new IllegalArgumentException("DHCP option too long: "
                    + optionLen + " vs. " + MAX_OPTION_LEN);
        }

        buf.put(type);
        buf.put((byte)(optionLen));

        for (Inet4Address addr : addrs) {
            buf.put(addr.getAddress());
        }
    }

    /**
     * Adds an optional parameter containing a short integer
     */
    protected static void addTlv(ByteBuffer buf, byte type, Short value) {
        if (value != null) {
            buf.put(type);
            buf.put((byte) 2);
            buf.putShort(value.shortValue());
        }
    }

    /**
     * Adds an optional parameter containing a simple integer
     */
    protected static void addTlv(ByteBuffer buf, byte type, Integer value) {
        if (value != null) {
            buf.put(type);
            buf.put((byte) 4);
            buf.putInt(value.intValue());
        }
    }

    /**
     * Adds an optional parameter containing an ASCII string.
     */
    protected static void addTlv(ByteBuffer buf, byte type, String str) {
        try {
            addTlv(buf, type, str.getBytes("US-ASCII"));
        } catch (UnsupportedEncodingException e) {
           throw new IllegalArgumentException("String is not US-ASCII: " + str);
        }
    }

    /**
     * Adds the special end-of-optional-parameters indicator.
     */
    protected static void addTlvEnd(ByteBuffer buf) {
        buf.put((byte) 0xFF);
    }

    /**
     * Adds common client TLVs.
     *
     * TODO: Does this belong here? The alternative would be to modify all the buildXyzPacket
     * methods to take them.
     */
    protected void addCommonClientTlvs(ByteBuffer buf) {
        addTlv(buf, DHCP_MAX_MESSAGE_SIZE, (short) MAX_LENGTH);
        addTlv(buf, DHCP_VENDOR_CLASS_ID, "android-dhcp-" + Build.VERSION.RELEASE);
        addTlv(buf, DHCP_HOST_NAME, SystemProperties.get("net.hostname"));
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
    private static Inet4Address readIpAddress(ByteBuffer packet) {
        Inet4Address result = null;
        byte[] ipAddr = new byte[4];
        packet.get(ipAddr);

        try {
            result = (Inet4Address) Inet4Address.getByAddress(ipAddr);
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
    private static String readAsciiString(ByteBuffer buf, int byteCount, boolean nullOk) {
        byte[] bytes = new byte[byteCount];
        buf.get(bytes);
        int length = bytes.length;
        if (!nullOk) {
            // Stop at the first null byte. This is because some DHCP options (e.g., the domain
            // name) are passed to netd via FrameworkListener, which refuses arguments containing
            // null bytes. We don't do this by default because vendorInfo is an opaque string which
            // could in theory contain null bytes.
            for (length = 0; length < bytes.length; length++) {
                if (bytes[length] == 0) {
                    break;
                }
            }
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
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
        short secs;
        Inet4Address clientIp;
        Inet4Address yourIp;
        Inet4Address nextIp;
        Inet4Address relayIp;
        byte[] clientMac;
        List<Inet4Address> dnsServers = new ArrayList<Inet4Address>();
        Inet4Address gateway = null; // aka router
        Inet4Address serverIdentifier = null;
        Inet4Address netMask = null;
        String message = null;
        String vendorId = null;
        String vendorInfo = null;
        byte[] expectedParams = null;
        String hostName = null;
        String domainName = null;
        Inet4Address ipSrc = null;
        Inet4Address ipDst = null;
        Inet4Address bcAddr = null;
        Inet4Address requestedIp = null;

        // The following are all unsigned integers. Internally we store them as signed integers of
        // the same length because that way we're guaranteed that they can't be out of the range of
        // the unsigned field in the packet. Callers wanting to pass in an unsigned value will need
        // to cast it.
        Short mtu = null;
        Short maxMessageSize = null;
        Integer leaseTime = null;
        Integer T1 = null;
        Integer T2 = null;

        // dhcp options
        byte dhcpType = (byte) 0xFF;

        packet.order(ByteOrder.BIG_ENDIAN);

        // check to see if we need to parse L2, IP, and UDP encaps
        if (pktType == ENCAP_L2) {
            if (packet.remaining() < MIN_PACKET_LENGTH_L2) {
                return null;
            }

            byte[] l2dst = new byte[6];
            byte[] l2src = new byte[6];

            packet.get(l2dst);
            packet.get(l2src);

            short l2type = packet.getShort();

            if (l2type != OsConstants.ETH_P_IP)
                return null;
        }

        if (pktType <= ENCAP_L3) {
            if (packet.remaining() < MIN_PACKET_LENGTH_L3) {
                return null;
            }

            byte ipTypeAndLength = packet.get();
            int ipVersion = (ipTypeAndLength & 0xf0) >> 4;
            if (ipVersion != 4) {
                return null;
            }

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

            // Skip options. This cannot cause us to read beyond the end of the buffer because the
            // IPv4 header cannot be more than (0x0f * 4) = 60 bytes long, and that is less than
            // MIN_PACKET_LENGTH_L3.
            int optionWords = ((ipTypeAndLength & 0x0f) - 5);
            for (int i = 0; i < optionWords; i++) {
                packet.getInt();
            }

            // assume UDP
            short udpSrcPort = packet.getShort();
            short udpDstPort = packet.getShort();
            short udpLen = packet.getShort();
            short udpChkSum = packet.getShort();

            if ((udpSrcPort != DHCP_SERVER) && (udpSrcPort != DHCP_CLIENT))
                return null;
        }

        // We need to check the length even for ENCAP_L3 because the IPv4 header is variable-length.
        if (pktType > ENCAP_BOOTP || packet.remaining() < MIN_PACKET_LENGTH_BOOTP) {
            return null;
        }

        byte type = packet.get();
        byte hwType = packet.get();
        byte addrLen = packet.get();
        byte hops = packet.get();
        transactionId = packet.getInt();
        secs = packet.getShort();
        short bootpFlags = packet.getShort();
        boolean broadcast = (bootpFlags & 0x8000) != 0;
        byte[] ipv4addr = new byte[4];

        try {
            packet.get(ipv4addr);
            clientIp = (Inet4Address) Inet4Address.getByAddress(ipv4addr);
            packet.get(ipv4addr);
            yourIp = (Inet4Address) Inet4Address.getByAddress(ipv4addr);
            packet.get(ipv4addr);
            nextIp = (Inet4Address) Inet4Address.getByAddress(ipv4addr);
            packet.get(ipv4addr);
            relayIp = (Inet4Address) Inet4Address.getByAddress(ipv4addr);
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
            try {
                byte optionType = packet.get();

                if (optionType == DHCP_OPTION_END) {
                    notFinishedOptions = false;
                } else if (optionType == DHCP_OPTION_PAD) {
                    // The pad option doesn't have a length field. Nothing to do.
                } else {
                    int optionLen = packet.get() & 0xFF;
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
                            for (expectedLen = 0; expectedLen < optionLen; expectedLen += 4) {
                                dnsServers.add(readIpAddress(packet));
                            }
                            break;
                        case DHCP_HOST_NAME:
                            expectedLen = optionLen;
                            hostName = readAsciiString(packet, optionLen, false);
                            break;
                        case DHCP_MTU:
                            expectedLen = 2;
                            mtu = Short.valueOf(packet.getShort());
                            break;
                        case DHCP_DOMAIN_NAME:
                            expectedLen = optionLen;
                            domainName = readAsciiString(packet, optionLen, false);
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
                            message = readAsciiString(packet, optionLen, false);
                            break;
                        case DHCP_MAX_MESSAGE_SIZE:
                            expectedLen = 2;
                            maxMessageSize = Short.valueOf(packet.getShort());
                            break;
                        case DHCP_RENEWAL_TIME:
                            expectedLen = 4;
                            T1 = Integer.valueOf(packet.getInt());
                            break;
                        case DHCP_REBINDING_TIME:
                            expectedLen = 4;
                            T2 = Integer.valueOf(packet.getInt());
                            break;
                        case DHCP_VENDOR_CLASS_ID:
                            expectedLen = optionLen;
                            // Embedded nulls are safe as this does not get passed to netd.
                            vendorId = readAsciiString(packet, optionLen, true);
                            break;
                        case DHCP_CLIENT_IDENTIFIER: { // Client identifier
                            byte[] id = new byte[optionLen];
                            packet.get(id);
                            expectedLen = optionLen;
                        } break;
                        case DHCP_VENDOR_INFO:
                            expectedLen = optionLen;
                            // Embedded nulls are safe as this does not get passed to netd.
                            vendorInfo = readAsciiString(packet, optionLen, true);
                            break;
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
            } catch (BufferUnderflowException e) {
                return null;
            }
        }

        DhcpPacket newPacket;

        switch(dhcpType) {
            case -1: return null;
            case DHCP_MESSAGE_TYPE_DISCOVER:
                newPacket = new DhcpDiscoverPacket(
                    transactionId, secs, clientMac, broadcast);
                break;
            case DHCP_MESSAGE_TYPE_OFFER:
                newPacket = new DhcpOfferPacket(
                    transactionId, secs, broadcast, ipSrc, clientIp, yourIp, clientMac);
                break;
            case DHCP_MESSAGE_TYPE_REQUEST:
                newPacket = new DhcpRequestPacket(
                    transactionId, secs, clientIp, clientMac, broadcast);
                break;
            case DHCP_MESSAGE_TYPE_DECLINE:
                newPacket = new DhcpDeclinePacket(
                    transactionId, secs, clientIp, yourIp, nextIp, relayIp,
                    clientMac);
                break;
            case DHCP_MESSAGE_TYPE_ACK:
                newPacket = new DhcpAckPacket(
                    transactionId, secs, broadcast, ipSrc, clientIp, yourIp, clientMac);
                break;
            case DHCP_MESSAGE_TYPE_NAK:
                newPacket = new DhcpNakPacket(
                    transactionId, secs, clientIp, yourIp, nextIp, relayIp,
                    clientMac);
                break;
            case DHCP_MESSAGE_TYPE_INFORM:
                newPacket = new DhcpInformPacket(
                    transactionId, secs, clientIp, yourIp, nextIp, relayIp,
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
        newPacket.mMtu = mtu;
        newPacket.mRequestedIp = requestedIp;
        newPacket.mRequestedParams = expectedParams;
        newPacket.mServerIdentifier = serverIdentifier;
        newPacket.mSubnetMask = netMask;
        newPacket.mMaxMessageSize = maxMessageSize;
        newPacket.mT1 = T1;
        newPacket.mT2 = T2;
        newPacket.mVendorId = vendorId;
        newPacket.mVendorInfo = vendorInfo;
        return newPacket;
    }

    /**
     * Parse a packet from an array of bytes, stopping at the given length.
     */
    public static DhcpPacket decodeFullPacket(byte[] packet, int length, int pktType)
    {
        ByteBuffer buffer = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.BIG_ENDIAN);
        return decodeFullPacket(buffer, pktType);
    }

    /**
     *  Construct a DhcpResults object from a DHCP reply packet.
     */
    public DhcpResults toDhcpResults() {
        Inet4Address ipAddress = mYourIp;
        if (ipAddress.equals(Inet4Address.ANY)) {
            ipAddress = mClientIp;
            if (ipAddress.equals(Inet4Address.ANY)) {
                return null;
            }
        }

        int prefixLength;
        if (mSubnetMask != null) {
            try {
                prefixLength = NetworkUtils.netmaskToPrefixLength(mSubnetMask);
            } catch (IllegalArgumentException e) {
                // Non-contiguous netmask.
                return null;
            }
        } else {
            prefixLength = NetworkUtils.getImplicitNetmask(ipAddress);
        }

        DhcpResults results = new DhcpResults();
        try {
            results.ipAddress = new LinkAddress(ipAddress, prefixLength);
        } catch (IllegalArgumentException e) {
            return null;
        }
        results.gateway = mGateway;
        results.dnsServers.addAll(mDnsServers);
        results.domains = mDomainName;
        results.serverAddress = mServerIdentifier;
        results.vendorInfo = mVendorInfo;
        results.leaseDuration = (mLeaseTime != null) ? mLeaseTime : INFINITE_LEASE;
        return results;
    }

    /**
     * Returns the parsed lease time, in milliseconds, or 0 for infinite.
     */
    public long getLeaseTimeMillis() {
        // dhcpcd treats the lack of a lease time option as an infinite lease.
        if (mLeaseTime == null || mLeaseTime == INFINITE_LEASE) {
            return 0;
        } else if (0 <= mLeaseTime && mLeaseTime < MINIMUM_LEASE) {
            return MINIMUM_LEASE * 1000;
        } else {
            return (mLeaseTime & 0xffffffffL) * 1000;
        }
    }

    /**
     * Builds a DHCP-DISCOVER packet from the required specified
     * parameters.
     */
    public static ByteBuffer buildDiscoverPacket(int encap, int transactionId,
        short secs, byte[] clientMac, boolean broadcast, byte[] expectedParams) {
        DhcpPacket pkt = new DhcpDiscoverPacket(
            transactionId, secs, clientMac, broadcast);
        pkt.mRequestedParams = expectedParams;
        return pkt.buildPacket(encap, DHCP_SERVER, DHCP_CLIENT);
    }

    /**
     * Builds a DHCP-OFFER packet from the required specified
     * parameters.
     */
    public static ByteBuffer buildOfferPacket(int encap, int transactionId,
        boolean broadcast, Inet4Address serverIpAddr, Inet4Address clientIpAddr,
        byte[] mac, Integer timeout, Inet4Address netMask, Inet4Address bcAddr,
        Inet4Address gateway, List<Inet4Address> dnsServers,
        Inet4Address dhcpServerIdentifier, String domainName) {
        DhcpPacket pkt = new DhcpOfferPacket(
            transactionId, (short) 0, broadcast, serverIpAddr, INADDR_ANY, clientIpAddr, mac);
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
        boolean broadcast, Inet4Address serverIpAddr, Inet4Address clientIpAddr,
        byte[] mac, Integer timeout, Inet4Address netMask, Inet4Address bcAddr,
        Inet4Address gateway, List<Inet4Address> dnsServers,
        Inet4Address dhcpServerIdentifier, String domainName) {
        DhcpPacket pkt = new DhcpAckPacket(
            transactionId, (short) 0, broadcast, serverIpAddr, INADDR_ANY, clientIpAddr, mac);
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
        Inet4Address serverIpAddr, Inet4Address clientIpAddr, byte[] mac) {
        DhcpPacket pkt = new DhcpNakPacket(transactionId, (short) 0, clientIpAddr,
            serverIpAddr, serverIpAddr, serverIpAddr, mac);
        pkt.mMessage = "requested address not available";
        pkt.mRequestedIp = clientIpAddr;
        return pkt.buildPacket(encap, DHCP_CLIENT, DHCP_SERVER);
    }

    /**
     * Builds a DHCP-REQUEST packet from the required specified parameters.
     */
    public static ByteBuffer buildRequestPacket(int encap,
        int transactionId, short secs, Inet4Address clientIp, boolean broadcast,
        byte[] clientMac, Inet4Address requestedIpAddress,
        Inet4Address serverIdentifier, byte[] requestedParams, String hostName) {
        DhcpPacket pkt = new DhcpRequestPacket(transactionId, secs, clientIp,
            clientMac, broadcast);
        pkt.mRequestedIp = requestedIpAddress;
        pkt.mServerIdentifier = serverIdentifier;
        pkt.mHostName = hostName;
        pkt.mRequestedParams = requestedParams;
        ByteBuffer result = pkt.buildPacket(encap, DHCP_SERVER, DHCP_CLIENT);
        return result;
    }
}
