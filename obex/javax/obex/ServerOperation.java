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

import java.io.IOException;
import java.io.InputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;

/**
 * This class implements the Operation interface for server side connections.
 * <P>
 * <STRONG>Request Codes</STRONG>
 * There are four different request codes that are in this class.  0x02 is a
 * PUT request that signals that the request is not complete and requires an
 * additional OBEX packet.  0x82 is a PUT request that says that request is
 * complete.  In this case, the server can begin sending the response.  The
 * 0x03 is a GET request that signals that the request is not finished.  When
 * the server receives a 0x83, the client is signalling the server that it is
 * done with its request.
 *
 * OPTIMIZATION: Extend the ClientOperation and reuse the methods defined
 * OPTIMIZATION: in that class.
 *
 * @hide
 */
public class ServerOperation implements Operation, BaseStream {

    private InputStream socketInput;

    private ServerSession parent;

    private int maxPacketLength;

    private int responseSize;

    private boolean isClosed;

    boolean finalBitSet;

    // This variable defines when the end of body
    // header has been received.  When this header
    // is received, no further body data will be
    // received from the client
    private boolean endOfBody;

    private boolean isGet;

    boolean isAborted;

    HeaderSet requestHeaders;

    HeaderSet replyHeaders;

    PrivateInputStream privateInput;

    private PrivateOutputStream privateOutput;

    private String exceptionString;

    private ServerRequestHandler listener;

    private boolean outputStreamOpened;

    private boolean requestFinished;

    private static final int BASE_PACKET_LENGTH = 3;

    private boolean isHasBody;

    /**
     * Creates new PutServerOperation
     *
     * @param p the parent that created this object
     *
     * @param in the input stream to read from
     *
     * @param out the output stream to write to
     *
     * @param request the initial request that was received from the client
     *
     * @param maxSize the max packet size that the client will accept
     *
     * @param listen the listener that is responding to the request
     *
     * @throws IOException if an IO error occurs
     */
    public ServerOperation(ServerSession p, InputStream in, int request, int maxSize,
            ServerRequestHandler listen) throws IOException {

        isAborted = false;
        parent = p;
        socketInput = in;
        maxPacketLength = maxSize;
        isClosed = false;
        requestHeaders = new HeaderSet();
        replyHeaders = new HeaderSet();
        privateInput = new PrivateInputStream(this);
        endOfBody = false;
        responseSize = 3;
        listener = listen;
        requestFinished = false;
        outputStreamOpened = false;
        isHasBody = false;
        int bytesReceived;

        /*
         * Determine if this is a PUT request
         */
        if ((request == 0x02) || (request == 0x82)) {
            /*
             * It is a PUT request.
             */
            isGet = false;
        } else {
            /*
             * It is a GET request.
             */
            isGet = true;
        }

        /*
         * Determine if the final bit is set
         */
        if ((request & 0x80) == 0) {
            finalBitSet = false;
        } else {
            finalBitSet = true;
            requestFinished = true;
        }

        int length = in.read();
        length = (length << 8) + in.read();

        /*
         * Determine if the packet length is larger than this device can receive
         */
        if (length > ObexHelper.MAX_PACKET_SIZE_INT) {
            parent.sendResponse(ResponseCodes.OBEX_HTTP_REQ_TOO_LARGE, null);
            throw new IOException("Packet received was too large");
        }

        /*
         * Determine if any headers were sent in the initial request
         */
        if (length > 3) {
            byte[] data = new byte[length - 3];
            bytesReceived = in.read(data);

            while (bytesReceived != data.length) {
                bytesReceived += in.read(data, bytesReceived, data.length - bytesReceived);
            }

            byte[] body = ObexHelper.updateHeaderSet(requestHeaders, data);

            if (body != null) {
                isHasBody = true;
            }

            if (requestHeaders.connectionID != null) {
                listener.setConnectionID(ObexHelper.convertToLong(requestHeaders.connectionID));
            } else {
                listener.setConnectionID(0);
            }

            if (requestHeaders.authResp != null) {
                if (!parent.handleAuthResp(requestHeaders.authResp)) {
                    exceptionString = "Authentication Failed";
                    parent.sendResponse(ResponseCodes.OBEX_HTTP_UNAUTHORIZED, null);
                    isClosed = true;
                    requestHeaders.authResp = null;
                    return;
                }
            }

            if (requestHeaders.authChall != null) {
                parent.handleAuthChall(requestHeaders);
                // send the  authResp to the client
                replyHeaders.authResp = new byte[requestHeaders.authResp.length];
                System.arraycopy(requestHeaders.authResp, 0, replyHeaders.authResp, 0,
                        replyHeaders.authResp.length);
                requestHeaders.authResp = null;
                requestHeaders.authChall = null;

            }

            if (body != null) {
                /*
                 * 0x49 is the end of body header.  This signifies that no more
                 * body data will be sent from the client
                 */
                if (body[0] == 0x49) {
                    endOfBody = true;
                }
                //privateInput.writeBytes(body, body.length);
                //byte [] body_tmp = new byte[body.length-1];
                //System.arraycopy(body,1,body_tmp,0,body.length-1);
                //privateInput.writeBytes(body_tmp, body.length-1);
                privateInput.writeBytes(body, 1);
            } else {
                while ((!isGet) && (!finalBitSet)) {
                    sendReply(ObexHelper.OBEX_HTTP_CONTINUE);
                    if (privateInput.available() > 0) {
                        break;
                    }
                }
            }//  if (body != null) 

        }// if (length > 3)

        while ((!isGet) && (!finalBitSet) && (privateInput.available() == 0)) {
            sendReply(ObexHelper.OBEX_HTTP_CONTINUE);
            if (privateInput.available() > 0) {
                break;
            }
        }

        // wait for get request finished !!!!
        while (isGet && !finalBitSet) {
            sendReply(ObexHelper.OBEX_HTTP_CONTINUE);
        }
        if (finalBitSet && isGet) {
            requestFinished = true;
        }
    }

    public synchronized boolean isValidBody() {
        return isHasBody;
    }

    /**
     * Determines if the operation should continue or should wait.  If it
     * should continue, this method will continue the operation.
     *
     * @param sendEmpty if <code>true</code> then this will continue the
     * operation even if no headers will be sent; if <code>false</code> then
     * this method will only continue the operation if there are headers to
     * send
     * @param isStream  if<code>true</code> the stream is input stream or
     * is outputstream
     * @return <code>true</code> if the operation was completed;
     * <code>false</code> if no operation took place
     */
    public synchronized boolean continueOperation(boolean sendEmpty, boolean inStream)
            throws IOException {
        if (!isGet) {
            if (!finalBitSet) {
                if (sendEmpty) {
                    sendReply(ObexHelper.OBEX_HTTP_CONTINUE);
                    return true;
                } else {
                    if ((responseSize > 3) || (privateOutput.size() > 0)) {
                        sendReply(ObexHelper.OBEX_HTTP_CONTINUE);
                        return true;
                    } else {
                        return false;
                    }
                }
            } else {
                return false;
            }
        } else {
            sendReply(ObexHelper.OBEX_HTTP_CONTINUE);
            return true;
        }
    }

    /**
     * Sends a reply to the client.  If the reply is a OBEX_HTTP_CONTINUE, it
     * will wait for a response from the client before ending.
     *
     * @param type the response code to send back to the client
     *
     * @return <code>true</code> if the final bit was not set on the reply;
     * <code>false</code> if no reply was received because the operation ended,
     * an abort was received, or the final bit was set in the reply
     *
     * @throws IOException if an IO error occurs
     */
    protected synchronized boolean sendReply(int type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bytesReceived;

        long id = listener.getConnectionID();
        if (id == -1) {
            replyHeaders.connectionID = null;
        } else {
            replyHeaders.connectionID = ObexHelper.convertToByteArray(id);
        }

        byte[] headerArray = ObexHelper.createHeader(replyHeaders, true);
        int bodyLength = -1;
        int orginalBodyLength = -1;

        if (privateOutput != null) {
            bodyLength = privateOutput.size();
            orginalBodyLength = bodyLength;
        }

        if ((BASE_PACKET_LENGTH + headerArray.length) > maxPacketLength) {

            int end = 0;
            int start = 0;

            while (end != headerArray.length) {
                end = ObexHelper.findHeaderEnd(headerArray, start, maxPacketLength
                        - BASE_PACKET_LENGTH);
                if (end == -1) {

                    isClosed = true;

                    if (privateInput != null) {
                        privateInput.close();
                    }

                    if (privateOutput != null) {
                        privateOutput.close();
                    }
                    parent.sendResponse(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR, null);
                    throw new IOException("OBEX Packet exceeds max packet size");
                }
                byte[] sendHeader = new byte[end - start];
                System.arraycopy(headerArray, start, sendHeader, 0, sendHeader.length);

                parent.sendResponse(type, sendHeader);
                start = end;
            }

            if (bodyLength > 0) {
                return true;
            } else {
                return false;
            }

        } else {
            out.write(headerArray);
        }

        /*
         * Determine if there is space to add a body reply.  First, we need to
         * verify that the client is finished sending the request.  Next, there
         * needs to be enough space to send the headers already defined along
         * with the reply header (3 bytes) and the body header identifier
         * (3 bytes).
         */

        /*        if ((finalBitSet) &&
                    ((bodyLength + 6 + headerArray.length) > maxPacketLength)) {

                    exceptionString = "Header larger then can be sent in a packet";
                    isClosed = true;
                    privateInput.close();
                    if (privateOutput != null) {
                        privateOutput.close();
                    }
                    parent.sendResponse(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR,
                        null);
                    throw new IOException("OBEX Packet exceeds max packet size");
                }
        */
         
        if ((finalBitSet) || (headerArray.length < (maxPacketLength - 20))) {
            if (bodyLength > 0) {
                /*
                 * Determine if I can send the whole body or just part of
                 * the body.  Remember that there is the 3 bytes for the
                 * response message and 3 bytes for the header ID and length
                 */
                if (bodyLength > (maxPacketLength - headerArray.length - 6)) {
                    bodyLength = maxPacketLength - headerArray.length - 6;
                }

                byte[] body = privateOutput.readBytes(bodyLength);

                /*
                 * Since this is a put request if the final bit is set or
                 * the output stream is closed we need to send the 0x49
                 * (End of Body) otherwise, we need to send 0x48 (Body)
                 */
                if ((finalBitSet) || (privateOutput.isClosed())) {
                    out.write(0x49);
                } else {
                    out.write(0x48);
                }

                bodyLength += 3;
                out.write((byte)(bodyLength >> 8));
                out.write((byte)bodyLength);
                out.write(body);
            }
        }

        if ((finalBitSet) && (type == ResponseCodes.OBEX_HTTP_OK) && (orginalBodyLength <= 0)) {
            out.write(0x49);
            orginalBodyLength = 3;
            out.write((byte)(orginalBodyLength >> 8));
            out.write((byte)orginalBodyLength);

        }

        responseSize = 3;
        parent.sendResponse(type, out.toByteArray());

        if (type == ObexHelper.OBEX_HTTP_CONTINUE) {
            int headerID = socketInput.read();
            int length = socketInput.read();
            length = (length << 8) + socketInput.read();
            if ((headerID != 0x02) && (headerID != 0x82) && (headerID != 0x03)
                    && (headerID != 0x83)) {

                if (length > 3) {
                    byte[] temp = new byte[length];
                    bytesReceived = socketInput.read(temp);

                    while (bytesReceived != length) {
                        bytesReceived += socketInput.read(temp, bytesReceived, length
                                - bytesReceived);
                    }
                }

                /*
                 * Determine if an ABORT was sent as the reply
                 */
                if (headerID == 0xFF) {
                    parent.sendResponse(ResponseCodes.OBEX_HTTP_OK, null);
                    isClosed = true;
                    isAborted = true;
                    exceptionString = "Abort Received";
                    throw new IOException("Abort Received");
                } else {
                    parent.sendResponse(ResponseCodes.OBEX_HTTP_BAD_REQUEST, null);
                    isClosed = true;
                    exceptionString = "Bad Request Received";
                    throw new IOException("Bad Request Received");
                }
            } else {

                if ((headerID == 0x82) || (headerID == 0x83)) {
                    finalBitSet = true;
                }

                /*
                 * Determine if the packet length is larger then this device can receive
                 */
                if (length > ObexHelper.MAX_PACKET_SIZE_INT) {
                    parent.sendResponse(ResponseCodes.OBEX_HTTP_REQ_TOO_LARGE, null);
                    throw new IOException("Packet received was too large");
                }

                /*
                 * Determine if any headers were sent in the initial request
                 */
                if (length > 3) {
                    byte[] data = new byte[length - 3];
                    bytesReceived = socketInput.read(data);

                    while (bytesReceived != data.length) {
                        bytesReceived += socketInput.read(data, bytesReceived, data.length
                                - bytesReceived);
                    }
                    byte[] body = ObexHelper.updateHeaderSet(requestHeaders, data);
                    if (body != null) {
                        isHasBody = true;
                    }
                    if (requestHeaders.connectionID != null) {
                        listener.setConnectionID(ObexHelper
                                .convertToLong(requestHeaders.connectionID));
                    } else {
                        listener.setConnectionID(1);
                    }

                    if (requestHeaders.authResp != null) {
                        if (!parent.handleAuthResp(requestHeaders.authResp)) {
                            exceptionString = "Authentication Failed";
                            parent.sendResponse(ResponseCodes.OBEX_HTTP_UNAUTHORIZED, null);
                            isClosed = true;
                            requestHeaders.authResp = null;
                            return false;
                        }
                        requestHeaders.authResp = null;
                    }

                    if (requestHeaders.authChall != null) {
                        parent.handleAuthChall(requestHeaders);
                        // send the auhtResp to the client
                        replyHeaders.authResp = new byte[requestHeaders.authResp.length];
                        System.arraycopy(requestHeaders.authResp, 0, replyHeaders.authResp, 0,
                                replyHeaders.authResp.length);
                        requestHeaders.authResp = null;
                        requestHeaders.authChall = null;
                    }

                    if (body != null) {
                        if (body[0] == 0x49) {
                            endOfBody = true;
                        }

                        /*byte [] body_tmp = new byte[body.length-1];
                        System.arraycopy(body,1,body_tmp,0,body.length-1);
                        privateInput.writeBytes(body_tmp, body.length-1);*/
                        privateInput.writeBytes(body, 1);

                    }
                }
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Sends an ABORT message to the server.  By calling this method, the
     * corresponding input and output streams will be closed along with this
     * object.
     *
     * @throws IOException if the transaction has already ended or if an
     * OBEX server called this method
     */
    public void abort() throws IOException {
        throw new IOException("Called from a server");
    }

    /**
     * Returns the headers that have been received during the operation.
     * Modifying the object returned has no effect on the headers that are
     * sent or retrieved.
     *
     * @return the headers received during this <code>Operation</code>
     *
     * @throws IOException if this <code>Operation</code> has been closed
     */
    public HeaderSet getReceivedHeaders() throws IOException {
        ensureOpen();
        return requestHeaders;
    }

    /**
     * Specifies the headers that should be sent in the next OBEX message that
     * is sent.
     *
     * @param headers the headers to send in the next message
     *
     * @throws IOException  if this <code>Operation</code> has been closed
     * or the transaction has ended and no further messages will be exchanged
     *
     * @throws IllegalArgumentException if <code>headers</code> was not created
     * by a call to <code>ServerRequestHandler.createHeaderSet()</code>
     */
    public void sendHeaders(HeaderSet headers) throws IOException {
        ensureOpen();

        if (headers == null) {
            throw new NullPointerException("Headers may not be null");
        }

        int[] headerList = headers.getHeaderList();
        if (headerList != null) {
            for (int i = 0; i < headerList.length; i++) {
                replyHeaders.setHeader(headerList[i], headers.getHeader(headerList[i]));
            }

        }
    }

    /**
     * Retrieves the response code retrieved from the server.  Response codes
     * are defined in the <code>ResponseCodes</code> interface.
     *
     * @return the response code retrieved from the server
     *
     * @throws IOException if an error occurred in the transport layer during
     * the transaction; if this method is called on a <code>HeaderSet</code>
     * object created by calling <code>createHeaderSet</code> in a
     * <code>ClientSession</code> object; if this is called from a server
     */
    public int getResponseCode() throws IOException {
        throw new IOException("Called from a server");
    }

    /**
     * Always returns <code>null</code>
     *
     * @return <code>null</code>
     */
    public String getEncoding() {
        return null;
    }

    /**
     * Returns the type of content that the resource connected to is providing.
     * E.g. if the connection is via HTTP, then the value of the content-type
     * header field is returned.
     *
     * @return the content type of the resource that the URL references, or
     * <code>null</code> if not known
     */
    public String getType() {
        try {
            return (String)requestHeaders.getHeader(HeaderSet.TYPE);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the length of the content which is being provided. E.g. if the
     * connection is via HTTP, then the value of the content-length header
     * field is returned.
     *
     * @return the content length of the resource that this connection's URL
     * references, or -1 if the content length is not known
     */
    public long getLength() {
        try {
            Long temp = (Long)requestHeaders.getHeader(HeaderSet.LENGTH);

            if (temp == null) {
                return -1;
            } else {
                return temp.longValue();
            }
        } catch (IOException e) {
            return -1;
        }
    }

    public int getMaxPacketSize() {
        return maxPacketLength - 6;
    }

    /**
     * Open and return an input stream for a connection.
     *
     * @return an input stream
     *
     * @throws IOException if an I/O error occurs
     */
    public InputStream openInputStream() throws IOException {
        ensureOpen();
        return privateInput;
    }

    /**
     * Open and return a data input stream for a connection.
     *
     * @return an input stream
     *
     * @throws IOException if an I/O error occurs
     */
    public DataInputStream openDataInputStream() throws IOException {
        return new DataInputStream(openInputStream());
    }

    /**
     * Open and return an output stream for a connection.
     *
     * @return an output stream
     *
     * @throws IOException if an I/O error occurs
     */
    public OutputStream openOutputStream() throws IOException {
        ensureOpen();

        if (outputStreamOpened)
            throw new IOException("no more input streams available, stream already opened");

        if (!requestFinished)
            throw new IOException("no  output streams available ,request not finished");

        if (privateOutput == null) {
            privateOutput = new PrivateOutputStream(this, maxPacketLength - 6);
        }
        outputStreamOpened = true;
        return privateOutput;
    }

    /**
     * Open and return a data output stream for a connection.
     *
     * @return an output stream
     *
     * @throws IOException if an I/O error occurs
     */
    public DataOutputStream openDataOutputStream() throws IOException {
        return new DataOutputStream(openOutputStream());
    }

    /**
     * Closes the connection and ends the transaction
     *
     * @throws IOException if the operation has already ended or is closed
     */
    public void close() throws IOException {
        ensureOpen();
        isClosed = true;
    }

    /**
     * Verifies that the connection is open and no exceptions should be thrown.
     *
     * @throws IOException if an exception needs to be thrown
     */
    public void ensureOpen() throws IOException {
        if (exceptionString != null) {
            throw new IOException(exceptionString);
        }
        if (isClosed) {
            throw new IOException("Operation has already ended");
        }
    }

    /**
     * Verifies that additional information may be sent.  In other words, the
     * operation is not done.
     * <P>
     * Included to implement the BaseStream interface only.  It does not do
     * anything on the server side since the operation of the Operation object
     * is not done until after the handler returns from its method.
     *
     * @throws IOException if the operation is completed
     */
    public void ensureNotDone() throws IOException {
    }

    /**
     * Called when the output or input stream is closed.  It does not do
     * anything on the server side since the operation of the Operation object
     * is not done until after the handler returns from its method.
     *
     * @param inStream <code>true</code> if the input stream is closed;
     * <code>false</code> if the output stream is closed
     *
     * @throws IOException if an IO error occurs
     */
    public void streamClosed(boolean inStream) throws IOException {

    }
}
