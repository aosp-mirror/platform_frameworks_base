/*
 * Copyright (c) 2008-2009, Motorola, Inc.
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * - Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 *
 * - Neither the name of the Motorola, Inc. nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package javax.obex;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * This class implements the <code>Operation</code> interface.  It will read
 * and write data via puts and gets.
 *
 * @hide
 */
public class ClientSession implements ObexSession {
    protected Authenticator authenticator;

    protected boolean connectionOpen = false;

    // Determines if an OBEX layer connection has been established
    protected boolean isConnected;

    private byte[] connectionID = null;

    byte[] challengeDigest = null;

    protected InputStream input = null;

    protected OutputStream output = null;

    protected ObexTransport trans = null;

    /*
    * The max Packet size must be at least 256 according to the OBEX
    * specification.
    */
    private int maxPacketSize = 256;

    protected boolean isActive;

    /* public ClientSession() {
              connectionOpen = false;
    }*/

    public ClientSession(ObexTransport trans) {
        try {
            this.trans = trans;
            input = trans.openInputStream();
            output = trans.openOutputStream();
            connectionOpen = true;
        } catch (IOException ioe) {
        }

    }

    public HeaderSet connect(HeaderSet header) throws java.io.IOException {
        ensureOpen();
        if (isConnected) {
            throw new IOException("Already connected to server");
        }
        synchronized (this) {
            if (isActive) {
                throw new IOException("OBEX request is already being performed");
            }
            isActive = true;
        }
        int totalLength = 4;
        byte[] head = null;

        // Determine the header byte array
        if (header != null) {
            if ((header).nonce != null) {
                challengeDigest = new byte[16];
                System.arraycopy((header).nonce, 0, challengeDigest, 0, 16);
            }
            head = ObexHelper.createHeader(header, false);
            totalLength += head.length;
        }
        /*
        * Write the OBEX CONNECT packet to the server.
        * Byte 0: 0x80
        * Byte 1&2: Connect Packet Length
        * Byte 3: OBEX Version Number (Presently, 0x10)
        * Byte 4: Flags (For TCP 0x00)
        * Byte 5&6: Max OBEX Packet Length (Defined in MAX_PACKET_SIZE)
        * Byte 7 to n: headers
        */
        byte[] requestPacket = new byte[totalLength];
        // We just need to start at  byte 3 since the sendRequest() method will
        // handle the length and 0x80.
        requestPacket[0] = (byte)0x10;
        requestPacket[1] = (byte)0x00;
        requestPacket[2] = (byte)(ObexHelper.MAX_PACKET_SIZE_INT >> 8);
        requestPacket[3] = (byte)(ObexHelper.MAX_PACKET_SIZE_INT & 0xFF);
        if (head != null) {
            System.arraycopy(head, 0, requestPacket, 4, head.length);
        }

        // check with local max packet size
        if ((requestPacket.length + 3) > ObexHelper.MAX_PACKET_SIZE_INT) {
            throw new IOException("Packet size exceeds max packet size");
        }

        HeaderSet returnHeaderSet = new HeaderSet();
        sendRequest(0x80, requestPacket, returnHeaderSet, null);

        /*
        * Read the response from the OBEX server.
        * Byte 0: Response Code (If successful then OBEX_HTTP_OK)
        * Byte 1&2: Packet Length
        * Byte 3: OBEX Version Number
        * Byte 4: Flags3
        * Byte 5&6: Max OBEX packet Length
        * Byte 7 to n: Optional HeaderSet
        */
        if (returnHeaderSet.responseCode == ResponseCodes.OBEX_HTTP_OK) {
            isConnected = true;
        }
        synchronized (this) {
            isActive = false;
        }

        return returnHeaderSet;
    }

    public Operation get(HeaderSet header) throws java.io.IOException {

        if (!isConnected) {
            throw new IOException("Not connected to the server");
        }
        synchronized (this) {
            if (isActive) {
                throw new IOException("OBEX request is already being performed");
            }
            isActive = true;
        }
        ensureOpen();

        if (header == null) {
            header = new HeaderSet();
        } else {
            if ((header).nonce != null) {
                challengeDigest = new byte[16];
                System.arraycopy((header).nonce, 0, challengeDigest, 0, 16);
            }
        }
        // Add the connection ID if one exists
        if (connectionID != null) {
            (header).connectionID = new byte[4];
            System.arraycopy(connectionID, 0, (header).connectionID, 0, 4);
        }

        return new ClientOperation(input, maxPacketSize, this, header, true);
    }

    /**
    *  0xCB Connection Id an identifier used for OBEX connection multiplexing
    */
    public void setConnectionID(long id) {
        if ((id < 0) || (id > 0xFFFFFFFFL)) {
            throw new IllegalArgumentException("Connection ID is not in a valid range");
        }
        connectionID = ObexHelper.convertToByteArray(id);
    }

    public HeaderSet createHeaderSet() {
        return new HeaderSet();
    }

    public HeaderSet delete(HeaderSet headers) throws java.io.IOException {

        Operation op = put(headers);
        op.getResponseCode();
        HeaderSet returnValue = op.getReceivedHeaders();
        op.close();

        return returnValue;
    }

    public HeaderSet disconnect(HeaderSet header) throws java.io.IOException {
        if (!isConnected) {
            throw new IOException("Not connected to the server");
        }
        synchronized (this) {
            if (isActive) {
                throw new IOException("OBEX request is already being performed");
            }
            isActive = true;
        }
        ensureOpen();
        // Determine the header byte array
        byte[] head = null;
        if (header != null) {
            if ((header).nonce != null) {
                challengeDigest = new byte[16];
                System.arraycopy((header).nonce, 0, challengeDigest, 0, 16);
            }
            // Add the connection ID if one exists
            if (connectionID != null) {
                (header).connectionID = new byte[4];
                System.arraycopy(connectionID, 0, (header).connectionID, 0, 4);
            }
            head = ObexHelper.createHeader(header, false);

            if ((head.length + 3) > maxPacketSize) {
                throw new IOException("Packet size exceeds max packet size");
            }
        } else {
            // Add the connection ID if one exists
            if (connectionID != null) {
                head = new byte[5];
                head[0] = (byte)0xCB;
                System.arraycopy(connectionID, 0, head, 1, 4);
            }
        }

        HeaderSet returnHeaderSet = new HeaderSet();
        sendRequest(0x81, head, returnHeaderSet, null);

        /*
        * An OBEX DISCONNECT reply from the server:
        * Byte 1: Response code
        * Bytes 2 & 3: packet size
        * Bytes 4 & up: headers
        */

        /* response code , and header are ignored
         * */

        synchronized (this) {
            isConnected = false;
            isActive = false;
        }

        return returnHeaderSet;
    }

    public long getConnectionID() {

        if (connectionID == null) {
            return -1;
        }
        return ObexHelper.convertToLong(connectionID);
    }

    public Operation put(HeaderSet header) throws java.io.IOException {
        if (!isConnected) {
            throw new IOException("Not connected to the server");
        }
        synchronized (this) {
            if (isActive) {
                throw new IOException("OBEX request is already being performed");
            }
            isActive = true;
        }

        ensureOpen();

        if (header == null) {
            header = new HeaderSet();
        } else {
            // when auth is initated by client ,save the digest 
            if ((header).nonce != null) {
                challengeDigest = new byte[16];
                System.arraycopy((header).nonce, 0, challengeDigest, 0, 16);
            }
        }

        // Add the connection ID if one exists
        if (connectionID != null) {

            (header).connectionID = new byte[4];
            System.arraycopy(connectionID, 0, (header).connectionID, 0, 4);
        }

        return new ClientOperation(input, maxPacketSize, this, header, false);
    }

    public void setAuthenticator(Authenticator auth) {
        if (auth == null) {
            throw new NullPointerException("Authenticator may not be null");
        }
        authenticator = auth;
    }

    public HeaderSet setPath(HeaderSet header, boolean backup, boolean create)
            throws java.io.IOException {
        if (!isConnected) {
            throw new IOException("Not connected to the server");
        }
        synchronized (this) {
            if (isActive) {
                throw new IOException("OBEX request is already being performed");
            }
            isActive = true;
        }

        ensureOpen();

        int totalLength = 2;
        byte[] head = null;

        if (header == null) {
            header = new HeaderSet();
        } else {
            if ((header).nonce != null) {
                challengeDigest = new byte[16];
                System.arraycopy((header).nonce, 0, challengeDigest, 0, 16);
            }
        }

        // when auth is initiated by client ,save the digest
        if ((header).nonce != null) {
            challengeDigest = new byte[16];
            System.arraycopy((header).nonce, 0, challengeDigest, 0, 16);
        }

        // Add the connection ID if one exists
        if (connectionID != null) {
            (header).connectionID = new byte[4];
            System.arraycopy(connectionID, 0, (header).connectionID, 0, 4);
        }

        head = ObexHelper.createHeader(header, false);
        totalLength += head.length;

        if (totalLength > maxPacketSize) {
            throw new IOException("Packet size exceeds max packet size");
        }

        int flags = 0;
        /*
         * The backup flag bit is bit 0 so if we add 1, this will set that bit
         */
        if (backup) {
            flags++;
        }
        /*
         * The create bit is bit 1 so if we or with 2 the bit will be set.
         */
        if (!create) {
            flags |= 2;
        }

        /*
         * An OBEX SETPATH packet to the server:
         * Byte 1: 0x85
         * Byte 2 & 3: packet size
         * Byte 4: flags
         * Byte 5: constants
         * Byte 6 & up: headers
         */
        byte[] packet = new byte[totalLength];
        packet[0] = (byte)flags;
        packet[1] = (byte)0x00;
        if (header != null) {
            System.arraycopy(head, 0, packet, 2, head.length);
        }

        HeaderSet returnHeaderSet = new HeaderSet();
        sendRequest(0x85, packet, returnHeaderSet, null);

        /*
         * An OBEX SETPATH reply from the server:
         * Byte 1: Response code
         * Bytes 2 & 3: packet size
         * Bytes 4 & up: headers
         */

        synchronized (this) {
            isActive = false;
        }

        return returnHeaderSet;
    }

    /**
     * Verifies that the connection is open.
     *
     * @throws IOException if the connection is closed
     */
    public synchronized void ensureOpen() throws IOException {
        if (!connectionOpen) {
            throw new IOException("Connection closed");
        }
    }

    /**
     * Sets the active mode to off.  This allows Put and get operation objects
     * to tell this object when they are done.
     */
    public void setInactive() {
        synchronized (this) {
            isActive = false;
        }
    }

    /**
     * Sends a standard request to the client.  It will then wait for the reply
     * and update the header set object provided.  If any authentication
     * headers (i.e. authentication challenge or authentication response) are
     * received, they will be processed.
     *
     * @param code the type of request to send to the client
     *
     * @param head the headers to send to the server
     *
     * @param challenge the nonce that was sent in the authentication
     * challenge header located in <code>head</code>; <code>null</code>
     * if no authentication header is included in <code>head</code>
     *
     * @param headers the header object to update with the response
     *
     * @param input the input stream used by the Operation object; null if this
     * is called on a CONNECT, SETPATH or DISCONNECT
     *
     * return <code>true</code> if the operation completed successfully;
     * <code>false</code> if an authentication response failed to pass
     *
     * @throws IOException if an IO error occurs
     */
    public boolean sendRequest(int code, byte[] head, HeaderSet headers,
            PrivateInputStream privateInput) throws IOException {
        //check header length with local max size
        if (head != null) {
            if ((head.length + 3) > ObexHelper.MAX_PACKET_SIZE_INT) {
                throw new IOException("header too large ");
            }
        }
        //byte[] nonce;
        int bytesReceived;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((byte)code);

        // Determine if there are any headers to send
        if (head == null) {
            out.write(0x00);
            out.write(0x03);
        } else {
            out.write((byte)((head.length + 3) >> 8));
            out.write((byte)(head.length + 3));
            out.write(head);
        }

        // Write the request to the output stream and flush the stream
        output.write(out.toByteArray());
        output.flush();

        headers.responseCode = input.read();

        int length = ((input.read() << 8) | (input.read()));

        if (length > ObexHelper.MAX_PACKET_SIZE_INT) {
            throw new IOException("Packet received exceeds packet size limit");
        }
        if (length > 3) {
            byte[] data = null;
            if (code == 0x80) {
                int version = input.read();
                int flags = input.read();
                maxPacketSize = (input.read() << 8) + input.read();

                //check with local max size
                if (maxPacketSize > ObexHelper.MAX_PACKET_SIZE_INT) {
                    maxPacketSize = ObexHelper.MAX_PACKET_SIZE_INT;
                }

                if (length > 7) {
                    data = new byte[length - 7];

                    bytesReceived = input.read(data);
                    while (bytesReceived != (length - 7)) {
                        bytesReceived += input.read(data, bytesReceived, data.length
                                - bytesReceived);
                    }
                } else {
                    return true;
                }
            } else {
                data = new byte[length - 3];
                bytesReceived = input.read(data);

                while (bytesReceived != (length - 3)) {
                    bytesReceived += input.read(data, bytesReceived, data.length - bytesReceived);
                }
                if (code == 0xFF) {
                    return true;
                }
            }

            byte[] body = ObexHelper.updateHeaderSet(headers, data);
            if ((privateInput != null) && (body != null)) {
                privateInput.writeBytes(body, 1);
            }

            if (headers.connectionID != null) {
                connectionID = new byte[4];
                System.arraycopy(headers.connectionID, 0, connectionID, 0, 4);
            }

            if (headers.authResp != null) {
                if (!handleAuthResp(headers.authResp)) {
                    setInactive();
                    throw new IOException("Authentication Failed");
                }
            }

            if ((headers.responseCode == ResponseCodes.OBEX_HTTP_UNAUTHORIZED)
                    && (headers.authChall != null)) {

                if (handleAuthChall(headers)) {
                    out.write((byte)0x4E);
                    out.write((byte)((headers.authResp.length + 3) >> 8));
                    out.write((byte)(headers.authResp.length + 3));
                    out.write(headers.authResp);
                    headers.authChall = null;
                    headers.authResp = null;

                    byte[] sendHeaders = new byte[out.size() - 3];
                    System.arraycopy(out.toByteArray(), 3, sendHeaders, 0, sendHeaders.length);

                    return sendRequest(code, sendHeaders, headers, privateInput);
                }
            }
        }

        return true;
    }

    /**
     * Called when the client received an authentication challenge header.  This
     * will cause the authenticator to handle the authentication challenge.
     *
     * @param header the header with the authentication challenge
     *
     * @return <code>true</code> if the last request should be resent;
     * <code>false</code> if the last request should not be resent
     */
    protected boolean handleAuthChall(HeaderSet header) {

        if (authenticator == null) {
            return false;
        }

        /*
         * An authentication challenge is made up of one required and two
         * optional tag length value triplets.  The tag 0x00 is required to be
         * in the authentication challenge and it represents the challenge
         * digest that was received.  The tag 0x01 is the options tag.  This
         * tag tracks if user ID is required and if full access will be
         * granted.  The tag 0x02 is the realm, which provides a description of
         * which user name and password to use.
         */
        byte[] challenge = ObexHelper.getTagValue((byte)0x00, header.authChall);
        byte[] option = ObexHelper.getTagValue((byte)0x01, header.authChall);
        byte[] description = ObexHelper.getTagValue((byte)0x02, header.authChall);

        String realm = "";
        if (description != null) {
            byte[] realmString = new byte[description.length - 1];
            System.arraycopy(description, 1, realmString, 0, realmString.length);

            switch (description[0] & 0xFF) {

                case 0x00:
                    // ASCII encoding
                    // Fall through
                case 0x01:
                    // ISO-8859-1 encoding
                    try {
                        realm = new String(realmString, "ISO8859_1");
                    } catch (Exception e) {
                        throw new RuntimeException("Unsupported Encoding Scheme");
                    }
                    break;

                case 0xFF:
                    // UNICODE Encoding
                    realm = ObexHelper.convertToUnicode(realmString, false);
                    break;

                case 0x02:
                    // ISO-8859-2 encoding
                    // Fall through
                case 0x03:
                    // ISO-8859-3 encoding
                    // Fall through
                case 0x04:
                    // ISO-8859-4 encoding
                    // Fall through
                case 0x05:
                    // ISO-8859-5 encoding
                    // Fall through
                case 0x06:
                    // ISO-8859-6 encoding
                    // Fall through
                case 0x07:
                    // ISO-8859-7 encoding
                    // Fall through
                case 0x08:
                    // ISO-8859-8 encoding
                    // Fall through
                case 0x09:
                    // ISO-8859-9 encoding
                    // Fall through
                default:
                    throw new RuntimeException("Unsupported Encoding Scheme");
            }
        }

        boolean isUserIDRequired = false;
        boolean isFullAccess = true;
        if (option != null) {
            if ((option[0] & 0x01) != 0) {
                isUserIDRequired = true;
            }

            if ((option[0] & 0x02) != 0) {
                isFullAccess = false;
            }
        }

        PasswordAuthentication result = null;
        header.authChall = null;

        try {
            result = authenticator.onAuthenticationChallenge(realm, isUserIDRequired, isFullAccess);
        } catch (Exception e) {
            return false;
        }

        /*
         * If no password was provided then do not resend the request
         */
        if (result == null) {
            return false;
        }

        byte[] password = result.getPassword();
        if (password == null) {
            return false;
        }

        byte[] userName = result.getUserName();

        /*
         * Create the authentication response header.  It includes 1 required
         * and 2 option tag length value triples.  The required triple has a
         * tag of 0x00 and is the response digest.  The first optional tag is
         * 0x01 and represents the user ID.  If no user ID is provided, then
         * no user ID will be sent.  The second optional tag is 0x02 and is the
         * challenge that was received.  This will always be sent
         */
        if (userName != null) {
            header.authResp = new byte[38 + userName.length];
            header.authResp[36] = (byte)0x01;
            header.authResp[37] = (byte)userName.length;
            System.arraycopy(userName, 0, header.authResp, 38, userName.length);
        } else {
            header.authResp = new byte[36];
        }

        // Create the secret String
        byte[] digest = new byte[challenge.length + password.length];
        System.arraycopy(challenge, 0, digest, 0, challenge.length);
        System.arraycopy(password, 0, digest, challenge.length, password.length);

        // Add the Response Digest
        header.authResp[0] = (byte)0x00;
        header.authResp[1] = (byte)0x10;

        byte[] responseDigest = ObexHelper.computeMd5Hash(digest);
        System.arraycopy(responseDigest, 0, header.authResp, 2, 16);

        // Add the challenge
        header.authResp[18] = (byte)0x02;
        header.authResp[19] = (byte)0x10;
        System.arraycopy(challenge, 0, header.authResp, 20, 16);

        return true;
    }

    /**
     * Called when the client received an authentication response header.  This
     * will cause the authenticator to handle the authentication response.
     *
     * @param authResp the authentication response
     *
     * @return <code>true</code> if the response passed; <code>false</code> if
     * the response failed
     */
    protected boolean handleAuthResp(byte[] authResp) {
        if (authenticator == null) {
            return false;
        }

        byte[] correctPassword = authenticator.onAuthenticationResponse(ObexHelper.getTagValue(
                (byte)0x01, authResp));
        if (correctPassword == null) {
            return false;
        }

        byte[] temp = new byte[correctPassword.length + 16];
        System.arraycopy(challengeDigest, 0, temp, 0, 16);
        System.arraycopy(correctPassword, 0, temp, 16, correctPassword.length);

        byte[] correctResponse = ObexHelper.computeMd5Hash(temp);
        byte[] actualResponse = ObexHelper.getTagValue((byte)0x00, authResp);
        for (int i = 0; i < 16; i++) {
            if (correctResponse[i] != actualResponse[i]) {
                return false;
            }
        }

        return true;
    }

    public void close() throws IOException {
        connectionOpen = false;
        input.close();
        output.close();
        //client.close();
    }
}
