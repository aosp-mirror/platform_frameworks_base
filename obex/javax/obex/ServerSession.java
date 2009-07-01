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

import java.io.*;

/**
 * This class in an implementation of the ServerSession interface.
 * 
 * @version 0.3 November 28, 2008
 */
public class ServerSession implements Runnable, ObexSession {

    private ObexTransport client;

    // private Socket client ;
    private InputStream input;

    private OutputStream output;

    private ServerRequestHandler listener;

    private Thread processThread;

    private int maxPacketLength;

    private Authenticator authenticator;

    byte[] challengeDigest;

    public boolean isClosed;

    private static final String TAG = "ServerSession";

    /**
     * Creates new ServerSession.
     *
     * @param conn
     *            the connection to the client
     *
     * @param handler
     *            the event listener that will process requests
     *
     * @param auth
     *            the authenticator to use with this connection
     *
     * @exception IOException
     *                if an error occurred while opening the input and output
     *                streams
     */
    public ServerSession(ObexTransport conn, ServerRequestHandler handler, Authenticator auth)
            throws IOException {
        authenticator = auth;
        client = conn;
        input = client.openInputStream();
        output = client.openOutputStream();
        listener = handler;
        maxPacketLength = 256;

        isClosed = false;
        processThread = new Thread(this);
        processThread.start();
    }

    /* removed as they're provided to the API user. Not used internally. */
    /*
     public boolean isCreatedServer() {
        if (client instanceof BTConnection)
            return ((BTConnection)client).isServerCreated();
        else
            return false;
    }

    public boolean isClosed() {
        if (client instanceof BTConnection)
            return ((BTConnection)client).isClosed();
        else
            return false;
    }

    public int getConnectionHandle() {
        if (client instanceof BTConnection)
            return ((BTConnection)client).getConnectionHandle();
        else
            return -1;
    }

    public RemoteDevice getRemoteDevice() {
        if (client instanceof BTConnection)
            return ((BTConnection)client).getRemoteDevice();
        else
            return null;
    }*/

    /**
     * Processes requests made to the server and forwards them to the
     * appropriate event listener.
     */
    public void run() {
        try {

            boolean done = false;
            while (!done && !isClosed) {
                int requestType = input.read();
                switch (requestType) {
                    case 0x80:
                        handleConnectRequest();
                        break;

                    case 0x81:
                        handleDisconnectRequest();
                        done = true;
                        break;

                    case 0x03:
                    case 0x83:
                        handleGetRequest(requestType);
                        break;

                    case 0x02:
                    case 0x82:
                        handlePutRequest(requestType);
                        break;

                    case 0x85:
                        handleSetPathRequest();
                        break;

                    case -1:
                        done = true;
                        break;

                    default:

                        /*
                         * Received a request type that is not recognized so I am
                         * just going to read the packet and send a not implemented
                         * to the client
                         */
                        int length = input.read();
                        length = (length << 8) + input.read();
                        for (int i = 3; i < length; i++) {
                            input.read();
                        }
                        sendResponse(ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED, null);

                        // done = true;
                }
            }

        } catch (NullPointerException e) {
        } catch (Exception e) {
        }
        close();
    }

    /**
     * Handles a PUT request from a client. This method will provide a
     * <code>ServerOperation</code> object to the request handler. The
     * <code>ServerOperation</code> object will handle the rest of the request.
     * It will also send replies and receive requests until the final reply
     * should be sent. When the final reply should be sent, this method will get
     * the response code to use and send the reply. The
     * <code>ServerOperation</code> object will always reply with a
     * OBEX_HTTP_CONTINUE reply. It will only reply if further information is
     * needed.
     *
     * @param type
     *            the type of request received; either 0x02 or 0x82
     *
     * @exception IOException
     *                if an error occurred at the transport layer
     */
    private void handlePutRequest(int type) throws IOException {
        ServerOperation client = new ServerOperation(this, input, type, maxPacketLength, listener);
        try {
            int response = -1;

            if ((client.finalBitSet) && !client.isValidBody()) {
                response = validateResponseCode(listener.onDelete(client.requestHeaders,
                        client.replyHeaders));
            } else {
                response = validateResponseCode(listener.onPut(client));
            }
            if (response != ResponseCodes.OBEX_HTTP_OK) {
                client.sendReply(response);
            } else if (!client.isAborted) {
                // wait for the final bit
                while (!client.finalBitSet) {
                    client.sendReply(OBEXConstants.OBEX_HTTP_CONTINUE);
                }
                client.sendReply(response);
            }
        } catch (Exception e) {
            sendResponse(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR, null);
        }
    }

    /**
     * Handles a GET request from a client. This method will provide a
     * <code>ServerOperation</code> object to the request handler. The
     * <code>ServerOperation</code> object will handle the rest of the request.
     * It will also send replies and receive requests until the final reply
     * should be sent. When the final reply should be sent, this method will get
     * the response code to use and send the reply. The
     * <code>ServerOperation</code> object will always reply with a
     * OBEX_HTTP_CONTINUE reply. It will only reply if further information is
     * needed.
     *
     * @param type
     *            the type of request received; either 0x03 or 0x83
     *
     * @exception IOException
     *                if an error occurred at the transport layer
     */
    private void handleGetRequest(int type) throws IOException {
        ServerOperation client = new ServerOperation(this, input, type, maxPacketLength, listener);
        try {
            int response = validateResponseCode(listener.onGet(client));

            if (!client.isAborted) {
                client.sendReply(response);
            }
        } catch (Exception e) {
            sendResponse(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR, null);
        }
    }

    /**
     * Send standard response.
     *
     * @param code
     *            the response code to send
     *
     * @param header
     *            the headers to include in the response
     *
     * @exception IOException
     *                if an IO error occurs
     */
    protected void sendResponse(int code, byte[] header) throws IOException {
        int totalLength = 3;
        byte[] data = null;

        if (header != null) {
            totalLength += header.length;
            data = new byte[totalLength];
            data[0] = (byte)code;
            data[1] = (byte)(totalLength >> 8);
            data[2] = (byte)totalLength;
            System.arraycopy(header, 0, data, 3, header.length);
        } else {
            data = new byte[totalLength];
            data[0] = (byte)code;
            data[1] = (byte)0x00;
            data[2] = (byte)totalLength;
        }
        output.write(data);
        output.flush();
    }

    /**
     * Handles a SETPATH request from a client. This method will read the rest
     * of the request from the client. Assuming the request is valid, it will
     * create a <code>HeaderSet</code> object to pass to the
     * <code>ServerRequestHandler</code> object. After the handler processes the
     * request, this method will create a reply message to send to the server
     * with the response code provided.
     *
     * @exception IOException
     *                if an error occurred at the transport layer
     */
    private void handleSetPathRequest() throws IOException {
        int length;
        int flags;
        int constants;
        int totalLength = 3;
        byte[] head = null;
        int code = -1;
        int bytesReceived;
        HeaderSet request = new HeaderSet();
        HeaderSet reply = new HeaderSet();

        length = input.read();
        length = (length << 8) + input.read();
        flags = input.read();
        constants = input.read();

        if (length > OBEXConstants.MAX_PACKET_SIZE_INT) {
            code = ResponseCodes.OBEX_HTTP_REQ_TOO_LARGE;
            totalLength = 3;
        } else {
            if (length > 5) {
                byte[] headers = new byte[length - 5];
                bytesReceived = input.read(headers);

                while (bytesReceived != headers.length) {
                    bytesReceived += input.read(headers, bytesReceived, headers.length
                            - bytesReceived);
                }

                OBEXHelper.updateHeaderSet(request, headers);

                if (request.connectionID != null) {
                    listener.setConnectionID(OBEXHelper.convertToLong(request.connectionID));
                } else {
                    listener.setConnectionID(-1);
                }
                // the Auth chan is initiated by the server.
                // client sent back the authResp .
                if (request.authResp != null) {
                    if (!handleAuthResp(request.authResp)) {
                        code = ResponseCodes.OBEX_HTTP_UNAUTHORIZED;
                        listener.onAuthenticationFailure(OBEXHelper.getTagValue((byte)0x01,
                                request.authResp));
                    }
                    request.authResp = null;
                }
            }

            if (code != ResponseCodes.OBEX_HTTP_UNAUTHORIZED) {
                // the Auth chan is initiated by the client
                // the server will send back the authResp to the client
                if (request.authChall != null) {
                    handleAuthChall(request);
                    reply.authResp = new byte[request.authResp.length];
                    System.arraycopy(request.authResp, 0, reply.authResp, 0, reply.authResp.length);
                    request.authChall = null;
                    request.authResp = null;
                }
                boolean backup = false;
                boolean create = true;
                if (!((flags & 1) == 0)) {
                    backup = true;
                }
                if ((flags & 2) == 0) {
                    create = false;
                }

                try {
                    code = listener.onSetPath(request, reply, backup, create);
                } catch (Exception e) {
                    sendResponse(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR, null);
                    return;
                }

                code = validateResponseCode(code);

                if (reply.nonce != null) {
                    challengeDigest = new byte[16];
                    System.arraycopy(reply.nonce, 0, challengeDigest, 0, 16);
                } else {
                    challengeDigest = null;
                }

                long id = listener.getConnectionID();
                if (id == -1) {
                    reply.connectionID = null;
                } else {
                    reply.connectionID = OBEXHelper.convertToByteArray(id);
                }

                head = OBEXHelper.createHeader(reply, false);
                totalLength += head.length;

                if (totalLength > maxPacketLength) {
                    totalLength = 3;
                    head = null;
                    code = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                }
            }
        }

        // Compute Length of OBEX SETPATH packet
        byte[] replyData = new byte[totalLength];
        replyData[0] = (byte)code;
        replyData[1] = (byte)(totalLength >> 8);
        replyData[2] = (byte)totalLength;
        if (head != null) {
            System.arraycopy(head, 0, replyData, 3, head.length);
        }
        /*
         * Write the OBEX SETPATH packet to the server. Byte 0: response code
         * Byte 1&2: Connect Packet Length Byte 3 to n: headers
         */
        output.write(replyData);
        output.flush();
    }

    /**
     * Handles a disconnect request from a client. This method will read the
     * rest of the request from the client. Assuming the request is valid, it
     * will create a <code>HeaderSet</code> object to pass to the
     * <code>ServerRequestHandler</code> object. After the handler processes the
     * request, this method will create a reply message to send to the server.
     *
     * @exception IOException
     *                if an error occurred at the transport layer
     */
    private void handleDisconnectRequest() throws IOException {
        int length;
        int code = ResponseCodes.OBEX_HTTP_OK;
        int totalLength = 3;
        byte[] head = null;
        int bytesReceived;
        HeaderSet request = new HeaderSet();
        HeaderSet reply = new HeaderSet();

        length = input.read();
        length = (length << 8) + input.read();

        if (length > OBEXConstants.MAX_PACKET_SIZE_INT) {
            code = ResponseCodes.OBEX_HTTP_REQ_TOO_LARGE;
            totalLength = 3;
        } else {
            if (length > 3) {
                byte[] headers = new byte[length - 3];
                bytesReceived = input.read(headers);

                while (bytesReceived != headers.length) {
                    bytesReceived += input.read(headers, bytesReceived, headers.length
                            - bytesReceived);
                }

                OBEXHelper.updateHeaderSet(request, headers);
            }

            if (request.connectionID != null) {
                listener.setConnectionID(OBEXHelper.convertToLong(request.connectionID));
            } else {
                listener.setConnectionID(1);
            }

            if (request.authResp != null) {
                if (!handleAuthResp(request.authResp)) {
                    code = ResponseCodes.OBEX_HTTP_UNAUTHORIZED;
                    listener.onAuthenticationFailure(OBEXHelper.getTagValue((byte)0x01,
                            request.authResp));
                }
                request.authResp = null;
            }

            if (code != ResponseCodes.OBEX_HTTP_UNAUTHORIZED) {

                if (request.authChall != null) {
                    handleAuthChall(request);
                    request.authChall = null;
                }

                try {
                    listener.onDisconnect(request, reply);
                } catch (Exception e) {
                    sendResponse(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR, null);
                    return;
                }

                /*
                 * Since a client will never response to an authentication
                 * challenge on a DISCONNECT, there is no reason to keep track
                 * of the challenge.
                 *
                 * if (reply.nonce != null) { challengeDigest = new byte[16];
                 * System.arraycopy(reply.nonce, 0, challengeDigest, 0, 16); }
                 * else { challengeDigest = null; }
                 */

                long id = listener.getConnectionID();
                if (id == -1) {
                    reply.connectionID = null;
                } else {
                    reply.connectionID = OBEXHelper.convertToByteArray(id);
                }

                head = OBEXHelper.createHeader(reply, false);
                totalLength += head.length;

                if (totalLength > maxPacketLength) {
                    totalLength = 3;
                    head = null;
                    code = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                }
            }
        }

        // Compute Length of OBEX CONNECT packet
        byte[] replyData;
        if (head != null) {
            replyData = new byte[3 + head.length];
        } else {
            replyData = new byte[3];
        }
        replyData[0] = (byte)code;
        replyData[1] = (byte)(totalLength >> 8);
        replyData[2] = (byte)totalLength;
        if (head != null) {
            System.arraycopy(head, 0, replyData, 3, head.length);
        }
        /*
         * Write the OBEX DISCONNECT packet to the server. Byte 0: response code
         * Byte 1&2: Connect Packet Length Byte 3 to n: headers
         */
        output.write(replyData);
        output.flush();
    }

    /**
     * Handles a connect request from a client. This method will read the rest
     * of the request from the client. Assuming the request is valid, it will
     * create a <code>HeaderSet</code> object to pass to the
     * <code>ServerRequestHandler</code> object. After the handler processes the
     * request, this method will create a reply message to send to the server
     * with the response code provided.
     *
     * @exception IOException
     *                if an error occurred at the transport layer
     */
    private void handleConnectRequest() throws IOException {
        int packetLength;
        int version;
        int flags;
        int totalLength = 7;
        byte[] head = null;
        int code = -1;
        HeaderSet request = new HeaderSet();
        HeaderSet reply = new HeaderSet();
        int bytesReceived;

        /*
         * Read in the length of the OBEX packet, OBEX version, flags, and max
         * packet length
         */
        packetLength = input.read();
        packetLength = (packetLength << 8) + input.read();
        version = input.read();
        flags = input.read();
        maxPacketLength = input.read();
        maxPacketLength = (maxPacketLength << 8) + input.read();

        // should we check it?
        if (maxPacketLength > OBEXConstants.MAX_PACKET_SIZE_INT) {
            maxPacketLength = OBEXConstants.MAX_PACKET_SIZE_INT;
        }

        if (packetLength > OBEXConstants.MAX_PACKET_SIZE_INT) {
            code = ResponseCodes.OBEX_HTTP_REQ_TOO_LARGE;
            totalLength = 7;
        } else {
            if (packetLength > 7) {
                byte[] headers = new byte[packetLength - 7];
                bytesReceived = input.read(headers);

                while (bytesReceived != headers.length) {
                    bytesReceived += input.read(headers, bytesReceived, headers.length
                            - bytesReceived);
                }

                OBEXHelper.updateHeaderSet(request, headers);
            }

            if (request.connectionID != null) {
                listener.setConnectionID(OBEXHelper.convertToLong(request.connectionID));
            } else {
                listener.setConnectionID(1);
            }

            if (request.authResp != null) {
                if (!handleAuthResp(request.authResp)) {
                    code = ResponseCodes.OBEX_HTTP_UNAUTHORIZED;
                    listener.onAuthenticationFailure(OBEXHelper.getTagValue((byte)0x01,
                            request.authResp));
                }
                request.authResp = null;
            }

            if (code != ResponseCodes.OBEX_HTTP_UNAUTHORIZED) {
                if (request.authChall != null) {
                    handleAuthChall(request);
                    reply.authResp = new byte[request.authResp.length];
                    System.arraycopy(request.authResp, 0, reply.authResp, 0, reply.authResp.length);
                    request.authChall = null;
                    request.authResp = null;
                }

                try {
                    code = listener.onConnect(request, reply);
                    code = validateResponseCode(code);

                    if (reply.nonce != null) {
                        challengeDigest = new byte[16];
                        System.arraycopy(reply.nonce, 0, challengeDigest, 0, 16);
                    } else {
                        challengeDigest = null;
                    }
                    long id = listener.getConnectionID();
                    if (id == -1) {
                        reply.connectionID = null;
                    } else {
                        reply.connectionID = OBEXHelper.convertToByteArray(id);
                    }

                    head = OBEXHelper.createHeader(reply, false);
                    totalLength += head.length;

                    if (totalLength > maxPacketLength) {
                        totalLength = 7;
                        head = null;
                        code = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    totalLength = 7;
                    head = null;
                    code = ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
                }

            }
        }

        // Compute Length of OBEX CONNECT packet
        byte[] length = OBEXHelper.convertToByteArray(totalLength);

        /*
         * Write the OBEX CONNECT packet to the server. Byte 0: response code
         * Byte 1&2: Connect Packet Length Byte 3: OBEX Version Number
         * (Presently, 0x10) Byte 4: Flags (For TCP 0x00) Byte 5&6: Max OBEX
         * Packet Length (Defined in MAX_PACKET_SIZE) Byte 7 to n: headers
         */
        byte[] sendData = new byte[totalLength];
        sendData[0] = (byte)code;
        sendData[1] = length[2];
        sendData[2] = length[3];
        sendData[3] = (byte)0x10;
        sendData[4] = (byte)0x00;
        sendData[5] = (byte)(OBEXConstants.MAX_PACKET_SIZE_INT >> 8);
        sendData[6] = (byte)(OBEXConstants.MAX_PACKET_SIZE_INT & 0xFF);

        if (head != null) {
            System.arraycopy(head, 0, sendData, 7, head.length);
        }

        output.write(sendData);
        output.flush();
    }

    /**
     * Closes the server session - in detail close I/O streams and the
     * underlying transport layer. Internal flag is also set so that later
     * attempt to read/write will throw an exception.
     */
    public synchronized void close() {
        if (listener != null) {
            listener.onClose();
        }
        try {
            input.close();
            output.close();
            client.close();
            isClosed = true;
        } catch (Exception e) {
        }
        client = null;
        input = null;
        output = null;
        listener = null;
    }

    /**
     * Verifies that the response code is valid. If it is not valid, it will
     * return the <code>OBEX_HTTP_INTERNAL_ERROR</code> response code.
     *
     * @param code
     *            the response code to check
     *
     * @return the valid response code or <code>OBEX_HTTP_INTERNAL_ERROR</code>
     *         if <code>code</code> is not valid
     */
    private int validateResponseCode(int code) {

        if ((code >= ResponseCodes.OBEX_HTTP_OK) && (code <= ResponseCodes.OBEX_HTTP_PARTIAL)) {
            return code;
        }
        if ((code >= ResponseCodes.OBEX_HTTP_MULT_CHOICE)
                && (code <= ResponseCodes.OBEX_HTTP_USE_PROXY)) {
            return code;
        }
        if ((code >= ResponseCodes.OBEX_HTTP_BAD_REQUEST)
                && (code <= ResponseCodes.OBEX_HTTP_UNSUPPORTED_TYPE)) {
            return code;
        }
        if ((code >= ResponseCodes.OBEX_HTTP_INTERNAL_ERROR)
                && (code <= ResponseCodes.OBEX_HTTP_VERSION)) {
            return code;
        }
        if ((code >= ResponseCodes.OBEX_DATABASE_FULL)
                && (code <= ResponseCodes.OBEX_DATABASE_LOCKED)) {
            return code;
        }
        return ResponseCodes.OBEX_HTTP_INTERNAL_ERROR;
    }

    /**
     * Called when the server received an authentication challenge header. This
     * will cause the authenticator to handle the authentication challenge.
     *
     * @param header
     *            the header with the authentication challenge
     *
     * @return <code>true</code> if the last request should be resent;
     *         <code>false</code> if the last request should not be resent
     */
    protected boolean handleAuthChall(HeaderSet header) {
        if (authenticator == null) {
            return false;
        }

        /*
         * An authentication challenge is made up of one required and two
         * optional tag length value triplets. The tag 0x00 is required to be in
         * the authentication challenge and it represents the challenge digest
         * that was received. The tag 0x01 is the options tag. This tag tracks
         * if user ID is required and if full access will be granted. The tag
         * 0x02 is the realm, which provides a description of which user name
         * and password to use.
         */
        byte[] challenge = OBEXHelper.getTagValue((byte)0x00, header.authChall);
        byte[] option = OBEXHelper.getTagValue((byte)0x01, header.authChall);
        byte[] description = OBEXHelper.getTagValue((byte)0x02, header.authChall);

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
                    realm = OBEXHelper.convertToUnicode(realmString, false);
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
         * If no password is provided then we not resent the request
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
         * Create the authentication response header. It includes 1 required and
         * 2 option tag length value triples. The required triple has a tag of
         * 0x00 and is the response digest. The first optional tag is 0x01 and
         * represents the user ID. If no user ID is provided, then no user ID
         * will be sent. The second optional tag is 0x02 and is the challenge
         * that was received. This will always be sent
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
        byte[] digest = new byte[challenge.length + password.length + 1];
        System.arraycopy(challenge, 0, digest, 0, challenge.length);
        // Insert colon between challenge and password
        digest[challenge.length] = (byte)0x3A;
        System.arraycopy(password, 0, digest, challenge.length + 1, password.length);

        // Add the Response Digest
        header.authResp[0] = (byte)0x00;
        header.authResp[1] = (byte)0x10;

        System.arraycopy(OBEXHelper.computeMD5Hash(digest), 0, header.authResp, 2, 16);

        // Add the challenge
        header.authResp[18] = (byte)0x02;
        header.authResp[19] = (byte)0x10;
        System.arraycopy(challenge, 0, header.authResp, 20, 16);

        return true;
    }

    /**
     * Called when the server received an authentication response header. This
     * will cause the authenticator to handle the authentication response.
     *
     * @param authResp
     *            the authentication response
     *
     * @return <code>true</code> if the response passed; <code>false</code> if
     *         the response failed
     */
    protected boolean handleAuthResp(byte[] authResp) {
        if (authenticator == null) {
            return false;
        }
        // get the correct password from the application
        byte[] correctPassword = authenticator.onAuthenticationResponse(OBEXHelper.getTagValue(
                (byte)0x01, authResp));
        if (correctPassword == null) {
            return false;
        }

        byte[] temp = new byte[correctPassword.length + 16];

        System.arraycopy(challengeDigest, 0, temp, 0, 16);
        System.arraycopy(correctPassword, 0, temp, 16, correctPassword.length);

        byte[] correctResponse = OBEXHelper.computeMD5Hash(temp);
        byte[] actualResponse = OBEXHelper.getTagValue((byte)0x00, authResp);

        // compare the MD5 hash array .
        for (int i = 0; i < 16; i++) {
            if (correctResponse[i] != actualResponse[i]) {
                return false;
            }
        }

        return true;
    }
}
