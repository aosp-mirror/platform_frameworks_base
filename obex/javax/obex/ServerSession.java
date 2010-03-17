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

import android.util.Log;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * This class in an implementation of the OBEX ServerSession.
 * @hide
 */
public final class ServerSession extends ObexSession implements Runnable {

    private static final String TAG = "Obex ServerSession";

    private ObexTransport mTransport;

    private InputStream mInput;

    private OutputStream mOutput;

    private ServerRequestHandler mListener;

    private Thread mProcessThread;

    private int mMaxPacketLength;

    private boolean mClosed;

    /**
     * Creates new ServerSession.
     * @param trans the connection to the client
     * @param handler the event listener that will process requests
     * @param auth the authenticator to use with this connection
     * @throws IOException if an error occurred while opening the input and
     *         output streams
     */
    public ServerSession(ObexTransport trans, ServerRequestHandler handler, Authenticator auth)
            throws IOException {
        mAuthenticator = auth;
        mTransport = trans;
        mInput = mTransport.openInputStream();
        mOutput = mTransport.openOutputStream();
        mListener = handler;
        mMaxPacketLength = 256;

        mClosed = false;
        mProcessThread = new Thread(this);
        mProcessThread.start();
    }

    /**
     * Processes requests made to the server and forwards them to the
     * appropriate event listener.
     */
    public void run() {
        try {

            boolean done = false;
            while (!done && !mClosed) {
                int requestType = mInput.read();
                switch (requestType) {
                    case ObexHelper.OBEX_OPCODE_CONNECT:
                        handleConnectRequest();
                        break;

                    case ObexHelper.OBEX_OPCODE_DISCONNECT:
                        handleDisconnectRequest();
                        done = true;
                        break;

                    case ObexHelper.OBEX_OPCODE_GET:
                    case ObexHelper.OBEX_OPCODE_GET_FINAL:
                        handleGetRequest(requestType);
                        break;

                    case ObexHelper.OBEX_OPCODE_PUT:
                    case ObexHelper.OBEX_OPCODE_PUT_FINAL:
                        handlePutRequest(requestType);
                        break;

                    case ObexHelper.OBEX_OPCODE_SETPATH:
                        handleSetPathRequest();
                        break;
                    case ObexHelper.OBEX_OPCODE_ABORT:
                        handleAbortRequest();
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
                        int length = mInput.read();
                        length = (length << 8) + mInput.read();
                        for (int i = 3; i < length; i++) {
                            mInput.read();
                        }
                        sendResponse(ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED, null);
                }
            }

        } catch (NullPointerException e) {
            Log.d(TAG, e.toString());
        } catch (Exception e) {
            Log.d(TAG, e.toString());
        }
        close();
    }

    /**
     * Handles a ABORT request from a client. This method will read the rest of
     * the request from the client. Assuming the request is valid, it will
     * create a <code>HeaderSet</code> object to pass to the
     * <code>ServerRequestHandler</code> object. After the handler processes the
     * request, this method will create a reply message to send to the server.
     *
     * @throws IOException if an error occurred at the transport layer
     */
    private void handleAbortRequest() throws IOException {
        int code = ResponseCodes.OBEX_HTTP_OK;
        HeaderSet request = new HeaderSet();
        HeaderSet reply = new HeaderSet();

        int length = mInput.read();
        length = (length << 8) + mInput.read();
        if (length > ObexHelper.MAX_PACKET_SIZE_INT) {
            code = ResponseCodes.OBEX_HTTP_REQ_TOO_LARGE;
        } else {
            for (int i = 3; i < length; i++) {
                mInput.read();
            }
            code = mListener.onAbort(request, reply);
            Log.v(TAG, "onAbort request handler return value- " + code);
            code = validateResponseCode(code);
        }
        sendResponse(code, null);
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
     * @param type the type of request received; either 0x02 or 0x82
     * @throws IOException if an error occurred at the transport layer
     */
    private void handlePutRequest(int type) throws IOException {
        ServerOperation op = new ServerOperation(this, mInput, type, mMaxPacketLength, mListener);
        try {
            int response = -1;

            if ((op.finalBitSet) && !op.isValidBody()) {
                response = validateResponseCode(mListener
                        .onDelete(op.requestHeader, op.replyHeader));
            } else {
                response = validateResponseCode(mListener.onPut(op));
            }
            if (response != ResponseCodes.OBEX_HTTP_OK && !op.isAborted) {
                op.sendReply(response);
            } else if (!op.isAborted) {
                // wait for the final bit
                while (!op.finalBitSet) {
                    op.sendReply(ResponseCodes.OBEX_HTTP_CONTINUE);
                }
                op.sendReply(response);
            }
        } catch (Exception e) {
            /*To fix bugs in aborted cases,
             *(client abort file transfer prior to the last packet which has the end of body header,
             *internal error should not be sent because server has already replied with
             *OK response in "sendReply")
             */
            if (!op.isAborted) {
                sendResponse(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR, null);
            }
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
     * @param type the type of request received; either 0x03 or 0x83
     * @throws IOException if an error occurred at the transport layer
     */
    private void handleGetRequest(int type) throws IOException {
        ServerOperation op = new ServerOperation(this, mInput, type, mMaxPacketLength, mListener);
        try {
            int response = validateResponseCode(mListener.onGet(op));

            if (!op.isAborted) {
                op.sendReply(response);
            }
        } catch (Exception e) {
            sendResponse(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR, null);
        }
    }

    /**
     * Send standard response.
     * @param code the response code to send
     * @param header the headers to include in the response
     * @throws IOException if an IO error occurs
     */
    public void sendResponse(int code, byte[] header) throws IOException {
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
        mOutput.write(data);
        mOutput.flush();
    }

    /**
     * Handles a SETPATH request from a client. This method will read the rest
     * of the request from the client. Assuming the request is valid, it will
     * create a <code>HeaderSet</code> object to pass to the
     * <code>ServerRequestHandler</code> object. After the handler processes the
     * request, this method will create a reply message to send to the server
     * with the response code provided.
     * @throws IOException if an error occurred at the transport layer
     */
    private void handleSetPathRequest() throws IOException {
        int length;
        int flags;
        @SuppressWarnings("unused")
        int constants;
        int totalLength = 3;
        byte[] head = null;
        int code = -1;
        int bytesReceived;
        HeaderSet request = new HeaderSet();
        HeaderSet reply = new HeaderSet();

        length = mInput.read();
        length = (length << 8) + mInput.read();
        flags = mInput.read();
        constants = mInput.read();

        if (length > ObexHelper.MAX_PACKET_SIZE_INT) {
            code = ResponseCodes.OBEX_HTTP_REQ_TOO_LARGE;
            totalLength = 3;
        } else {
            if (length > 5) {
                byte[] headers = new byte[length - 5];
                bytesReceived = mInput.read(headers);

                while (bytesReceived != headers.length) {
                    bytesReceived += mInput.read(headers, bytesReceived, headers.length
                            - bytesReceived);
                }

                ObexHelper.updateHeaderSet(request, headers);

                if (mListener.getConnectionId() != -1 && request.mConnectionID != null) {
                    mListener.setConnectionId(ObexHelper.convertToLong(request.mConnectionID));
                } else {
                    mListener.setConnectionId(1);
                }
                // the Auth chan is initiated by the server, client sent back the authResp .
                if (request.mAuthResp != null) {
                    if (!handleAuthResp(request.mAuthResp)) {
                        code = ResponseCodes.OBEX_HTTP_UNAUTHORIZED;
                        mListener.onAuthenticationFailure(ObexHelper.getTagValue((byte)0x01,
                                request.mAuthResp));
                    }
                    request.mAuthResp = null;
                }
            }

            if (code != ResponseCodes.OBEX_HTTP_UNAUTHORIZED) {
                // the Auth challenge is initiated by the client
                // the server will send back the authResp to the client
                if (request.mAuthChall != null) {
                    handleAuthChall(request);
                    reply.mAuthResp = new byte[request.mAuthResp.length];
                    System.arraycopy(request.mAuthResp, 0, reply.mAuthResp, 0,
                            reply.mAuthResp.length);
                    request.mAuthChall = null;
                    request.mAuthResp = null;
                }
                boolean backup = false;
                boolean create = true;
                if (!((flags & 1) == 0)) {
                    backup = true;
                }
                if (!((flags & 2) == 0)) {
                    create = false;
                }

                try {
                    code = mListener.onSetPath(request, reply, backup, create);
                } catch (Exception e) {
                    sendResponse(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR, null);
                    return;
                }

                code = validateResponseCode(code);

                if (reply.nonce != null) {
                    mChallengeDigest = new byte[16];
                    System.arraycopy(reply.nonce, 0, mChallengeDigest, 0, 16);
                } else {
                    mChallengeDigest = null;
                }

                long id = mListener.getConnectionId();
                if (id == -1) {
                    reply.mConnectionID = null;
                } else {
                    reply.mConnectionID = ObexHelper.convertToByteArray(id);
                }

                head = ObexHelper.createHeader(reply, false);
                totalLength += head.length;

                if (totalLength > mMaxPacketLength) {
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
        mOutput.write(replyData);
        mOutput.flush();
    }

    /**
     * Handles a disconnect request from a client. This method will read the
     * rest of the request from the client. Assuming the request is valid, it
     * will create a <code>HeaderSet</code> object to pass to the
     * <code>ServerRequestHandler</code> object. After the handler processes the
     * request, this method will create a reply message to send to the server.
     * @throws IOException if an error occurred at the transport layer
     */
    private void handleDisconnectRequest() throws IOException {
        int length;
        int code = ResponseCodes.OBEX_HTTP_OK;
        int totalLength = 3;
        byte[] head = null;
        int bytesReceived;
        HeaderSet request = new HeaderSet();
        HeaderSet reply = new HeaderSet();

        length = mInput.read();
        length = (length << 8) + mInput.read();

        if (length > ObexHelper.MAX_PACKET_SIZE_INT) {
            code = ResponseCodes.OBEX_HTTP_REQ_TOO_LARGE;
            totalLength = 3;
        } else {
            if (length > 3) {
                byte[] headers = new byte[length - 3];
                bytesReceived = mInput.read(headers);

                while (bytesReceived != headers.length) {
                    bytesReceived += mInput.read(headers, bytesReceived, headers.length
                            - bytesReceived);
                }

                ObexHelper.updateHeaderSet(request, headers);
            }

            if (mListener.getConnectionId() != -1 && request.mConnectionID != null) {
                mListener.setConnectionId(ObexHelper.convertToLong(request.mConnectionID));
            } else {
                mListener.setConnectionId(1);
            }

            if (request.mAuthResp != null) {
                if (!handleAuthResp(request.mAuthResp)) {
                    code = ResponseCodes.OBEX_HTTP_UNAUTHORIZED;
                    mListener.onAuthenticationFailure(ObexHelper.getTagValue((byte)0x01,
                            request.mAuthResp));
                }
                request.mAuthResp = null;
            }

            if (code != ResponseCodes.OBEX_HTTP_UNAUTHORIZED) {

                if (request.mAuthChall != null) {
                    handleAuthChall(request);
                    request.mAuthChall = null;
                }

                try {
                    mListener.onDisconnect(request, reply);
                } catch (Exception e) {
                    sendResponse(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR, null);
                    return;
                }

                long id = mListener.getConnectionId();
                if (id == -1) {
                    reply.mConnectionID = null;
                } else {
                    reply.mConnectionID = ObexHelper.convertToByteArray(id);
                }

                head = ObexHelper.createHeader(reply, false);
                totalLength += head.length;

                if (totalLength > mMaxPacketLength) {
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
        mOutput.write(replyData);
        mOutput.flush();
    }

    /**
     * Handles a connect request from a client. This method will read the rest
     * of the request from the client. Assuming the request is valid, it will
     * create a <code>HeaderSet</code> object to pass to the
     * <code>ServerRequestHandler</code> object. After the handler processes the
     * request, this method will create a reply message to send to the server
     * with the response code provided.
     * @throws IOException if an error occurred at the transport layer
     */
    private void handleConnectRequest() throws IOException {
        int packetLength;
        @SuppressWarnings("unused")
        int version;
        @SuppressWarnings("unused")
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
        packetLength = mInput.read();
        packetLength = (packetLength << 8) + mInput.read();
        version = mInput.read();
        flags = mInput.read();
        mMaxPacketLength = mInput.read();
        mMaxPacketLength = (mMaxPacketLength << 8) + mInput.read();

        // should we check it?
        if (mMaxPacketLength > ObexHelper.MAX_PACKET_SIZE_INT) {
            mMaxPacketLength = ObexHelper.MAX_PACKET_SIZE_INT;
        }

        if (packetLength > ObexHelper.MAX_PACKET_SIZE_INT) {
            code = ResponseCodes.OBEX_HTTP_REQ_TOO_LARGE;
            totalLength = 7;
        } else {
            if (packetLength > 7) {
                byte[] headers = new byte[packetLength - 7];
                bytesReceived = mInput.read(headers);

                while (bytesReceived != headers.length) {
                    bytesReceived += mInput.read(headers, bytesReceived, headers.length
                            - bytesReceived);
                }

                ObexHelper.updateHeaderSet(request, headers);
            }

            if (mListener.getConnectionId() != -1 && request.mConnectionID != null) {
                mListener.setConnectionId(ObexHelper.convertToLong(request.mConnectionID));
            } else {
                mListener.setConnectionId(1);
            }

            if (request.mAuthResp != null) {
                if (!handleAuthResp(request.mAuthResp)) {
                    code = ResponseCodes.OBEX_HTTP_UNAUTHORIZED;
                    mListener.onAuthenticationFailure(ObexHelper.getTagValue((byte)0x01,
                            request.mAuthResp));
                }
                request.mAuthResp = null;
            }

            if (code != ResponseCodes.OBEX_HTTP_UNAUTHORIZED) {
                if (request.mAuthChall != null) {
                    handleAuthChall(request);
                    reply.mAuthResp = new byte[request.mAuthResp.length];
                    System.arraycopy(request.mAuthResp, 0, reply.mAuthResp, 0,
                            reply.mAuthResp.length);
                    request.mAuthChall = null;
                    request.mAuthResp = null;
                }

                try {
                    code = mListener.onConnect(request, reply);
                    code = validateResponseCode(code);

                    if (reply.nonce != null) {
                        mChallengeDigest = new byte[16];
                        System.arraycopy(reply.nonce, 0, mChallengeDigest, 0, 16);
                    } else {
                        mChallengeDigest = null;
                    }
                    long id = mListener.getConnectionId();
                    if (id == -1) {
                        reply.mConnectionID = null;
                    } else {
                        reply.mConnectionID = ObexHelper.convertToByteArray(id);
                    }

                    head = ObexHelper.createHeader(reply, false);
                    totalLength += head.length;

                    if (totalLength > mMaxPacketLength) {
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
        byte[] length = ObexHelper.convertToByteArray(totalLength);

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
        sendData[5] = (byte)(ObexHelper.MAX_PACKET_SIZE_INT >> 8);
        sendData[6] = (byte)(ObexHelper.MAX_PACKET_SIZE_INT & 0xFF);

        if (head != null) {
            System.arraycopy(head, 0, sendData, 7, head.length);
        }

        mOutput.write(sendData);
        mOutput.flush();
    }

    /**
     * Closes the server session - in detail close I/O streams and the
     * underlying transport layer. Internal flag is also set so that later
     * attempt to read/write will throw an exception.
     */
    public synchronized void close() {
        if (mListener != null) {
            mListener.onClose();
        }
        try {
            mInput.close();
            mOutput.close();
            mTransport.close();
            mClosed = true;
        } catch (Exception e) {
        }
        mTransport = null;
        mInput = null;
        mOutput = null;
        mListener = null;
    }

    /**
     * Verifies that the response code is valid. If it is not valid, it will
     * return the <code>OBEX_HTTP_INTERNAL_ERROR</code> response code.
     * @param code the response code to check
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

}
