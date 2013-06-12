/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
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
 * <STRONG>Request Codes</STRONG> There are four different request codes that
 * are in this class. 0x02 is a PUT request that signals that the request is not
 * complete and requires an additional OBEX packet. 0x82 is a PUT request that
 * says that request is complete. In this case, the server can begin sending the
 * response. The 0x03 is a GET request that signals that the request is not
 * finished. When the server receives a 0x83, the client is signaling the server
 * that it is done with its request. TODO: Extend the ClientOperation and reuse
 * the methods defined TODO: in that class.
 * @hide
 */
public final class ServerOperation implements Operation, BaseStream {

    public boolean isAborted;

    public HeaderSet requestHeader;

    public HeaderSet replyHeader;

    public boolean finalBitSet;

    private InputStream mInput;

    private ServerSession mParent;

    private int mMaxPacketLength;

    private int mResponseSize;

    private boolean mClosed;

    private boolean mGetOperation;

    private PrivateInputStream mPrivateInput;

    private PrivateOutputStream mPrivateOutput;

    private boolean mPrivateOutputOpen;

    private String mExceptionString;

    private ServerRequestHandler mListener;

    private boolean mRequestFinished;

    private boolean mHasBody;

    private boolean mSendBodyHeader = true;

    private boolean mEndofBody = true;
    /**
     * Creates new ServerOperation
     * @param p the parent that created this object
     * @param in the input stream to read from
     * @param out the output stream to write to
     * @param request the initial request that was received from the client
     * @param maxSize the max packet size that the client will accept
     * @param listen the listener that is responding to the request
     * @throws IOException if an IO error occurs
     */
    public ServerOperation(ServerSession p, InputStream in, int request, int maxSize,
            ServerRequestHandler listen) throws IOException {

        isAborted = false;
        mParent = p;
        mInput = in;
        mMaxPacketLength = maxSize;
        mClosed = false;
        requestHeader = new HeaderSet();
        replyHeader = new HeaderSet();
        mPrivateInput = new PrivateInputStream(this);
        mResponseSize = 3;
        mListener = listen;
        mRequestFinished = false;
        mPrivateOutputOpen = false;
        mHasBody = false;
        int bytesReceived;

        /*
         * Determine if this is a PUT request
         */
        if ((request == 0x02) || (request == 0x82)) {
            /*
             * It is a PUT request.
             */
            mGetOperation = false;

            /*
             * Determine if the final bit is set
             */
            if ((request & 0x80) == 0) {
                finalBitSet = false;
            } else {
                finalBitSet = true;
                mRequestFinished = true;
            }
        } else if ((request == 0x03) || (request == 0x83)) {
            /*
             * It is a GET request.
             */
            mGetOperation = true;

            // For Get request, final bit set is decided by server side logic
            finalBitSet = false;

            if (request == 0x83) {
                mRequestFinished = true;
            }
        } else {
            throw new IOException("ServerOperation can not handle such request");
        }

        int length = in.read();
        length = (length << 8) + in.read();

        /*
         * Determine if the packet length is larger than this device can receive
         */
        if (length > ObexHelper.MAX_PACKET_SIZE_INT) {
            mParent.sendResponse(ResponseCodes.OBEX_HTTP_REQ_TOO_LARGE, null);
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

            byte[] body = ObexHelper.updateHeaderSet(requestHeader, data);

            if (body != null) {
                mHasBody = true;
            }

            if (mListener.getConnectionId() != -1 && requestHeader.mConnectionID != null) {
                mListener.setConnectionId(ObexHelper.convertToLong(requestHeader.mConnectionID));
            } else {
                mListener.setConnectionId(1);
            }

            if (requestHeader.mAuthResp != null) {
                if (!mParent.handleAuthResp(requestHeader.mAuthResp)) {
                    mExceptionString = "Authentication Failed";
                    mParent.sendResponse(ResponseCodes.OBEX_HTTP_UNAUTHORIZED, null);
                    mClosed = true;
                    requestHeader.mAuthResp = null;
                    return;
                }
            }

            if (requestHeader.mAuthChall != null) {
                mParent.handleAuthChall(requestHeader);
                // send the  authResp to the client
                replyHeader.mAuthResp = new byte[requestHeader.mAuthResp.length];
                System.arraycopy(requestHeader.mAuthResp, 0, replyHeader.mAuthResp, 0,
                        replyHeader.mAuthResp.length);
                requestHeader.mAuthResp = null;
                requestHeader.mAuthChall = null;

            }

            if (body != null) {
                mPrivateInput.writeBytes(body, 1);
            } else {
                while ((!mGetOperation) && (!finalBitSet)) {
                    sendReply(ResponseCodes.OBEX_HTTP_CONTINUE);
                    if (mPrivateInput.available() > 0) {
                        break;
                    }
                }
            }
        }

        while ((!mGetOperation) && (!finalBitSet) && (mPrivateInput.available() == 0)) {
            sendReply(ResponseCodes.OBEX_HTTP_CONTINUE);
            if (mPrivateInput.available() > 0) {
                break;
            }
        }

        // wait for get request finished !!!!
        while (mGetOperation && !mRequestFinished) {
            sendReply(ResponseCodes.OBEX_HTTP_CONTINUE);
        }
    }

    public boolean isValidBody() {
        return mHasBody;
    }

    /**
     * Determines if the operation should continue or should wait. If it should
     * continue, this method will continue the operation.
     * @param sendEmpty if <code>true</code> then this will continue the
     *        operation even if no headers will be sent; if <code>false</code>
     *        then this method will only continue the operation if there are
     *        headers to send
     * @param inStream if<code>true</code> the stream is input stream, otherwise
     *        output stream
     * @return <code>true</code> if the operation was completed;
     *         <code>false</code> if no operation took place
     */
    public synchronized boolean continueOperation(boolean sendEmpty, boolean inStream)
            throws IOException {
        if (!mGetOperation) {
            if (!finalBitSet) {
                if (sendEmpty) {
                    sendReply(ResponseCodes.OBEX_HTTP_CONTINUE);
                    return true;
                } else {
                    if ((mResponseSize > 3) || (mPrivateOutput.size() > 0)) {
                        sendReply(ResponseCodes.OBEX_HTTP_CONTINUE);
                        return true;
                    } else {
                        return false;
                    }
                }
            } else {
                return false;
            }
        } else {
            sendReply(ResponseCodes.OBEX_HTTP_CONTINUE);
            return true;
        }
    }

    /**
     * Sends a reply to the client. If the reply is a OBEX_HTTP_CONTINUE, it
     * will wait for a response from the client before ending.
     * @param type the response code to send back to the client
     * @return <code>true</code> if the final bit was not set on the reply;
     *         <code>false</code> if no reply was received because the operation
     *         ended, an abort was received, or the final bit was set in the
     *         reply
     * @throws IOException if an IO error occurs
     */
    public synchronized boolean sendReply(int type) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bytesReceived;

        long id = mListener.getConnectionId();
        if (id == -1) {
            replyHeader.mConnectionID = null;
        } else {
            replyHeader.mConnectionID = ObexHelper.convertToByteArray(id);
        }

        byte[] headerArray = ObexHelper.createHeader(replyHeader, true);
        int bodyLength = -1;
        int orginalBodyLength = -1;

        if (mPrivateOutput != null) {
            bodyLength = mPrivateOutput.size();
            orginalBodyLength = bodyLength;
        }

        if ((ObexHelper.BASE_PACKET_LENGTH + headerArray.length) > mMaxPacketLength) {

            int end = 0;
            int start = 0;

            while (end != headerArray.length) {
                end = ObexHelper.findHeaderEnd(headerArray, start, mMaxPacketLength
                        - ObexHelper.BASE_PACKET_LENGTH);
                if (end == -1) {

                    mClosed = true;

                    if (mPrivateInput != null) {
                        mPrivateInput.close();
                    }

                    if (mPrivateOutput != null) {
                        mPrivateOutput.close();
                    }
                    mParent.sendResponse(ResponseCodes.OBEX_HTTP_INTERNAL_ERROR, null);
                    throw new IOException("OBEX Packet exceeds max packet size");
                }
                byte[] sendHeader = new byte[end - start];
                System.arraycopy(headerArray, start, sendHeader, 0, sendHeader.length);

                mParent.sendResponse(type, sendHeader);
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

        // For Get operation: if response code is OBEX_HTTP_OK, then this is the
        // last packet; so set finalBitSet to true.
        if (mGetOperation && type == ResponseCodes.OBEX_HTTP_OK) {
            finalBitSet = true;
        }

        if ((finalBitSet) || (headerArray.length < (mMaxPacketLength - 20))) {
            if (bodyLength > 0) {
                /*
                 * Determine if I can send the whole body or just part of
                 * the body.  Remember that there is the 3 bytes for the
                 * response message and 3 bytes for the header ID and length
                 */
                if (bodyLength > (mMaxPacketLength - headerArray.length - 6)) {
                    bodyLength = mMaxPacketLength - headerArray.length - 6;
                }

                byte[] body = mPrivateOutput.readBytes(bodyLength);

                /*
                 * Since this is a put request if the final bit is set or
                 * the output stream is closed we need to send the 0x49
                 * (End of Body) otherwise, we need to send 0x48 (Body)
                 */
                if ((finalBitSet) || (mPrivateOutput.isClosed())) {
                    if (mEndofBody) {
                        out.write(0x49);
                        bodyLength += 3;
                        out.write((byte)(bodyLength >> 8));
                        out.write((byte)bodyLength);
                        out.write(body);
                    }
                } else {
                    if(mSendBodyHeader == true) {
                    out.write(0x48);
                    bodyLength += 3;
                    out.write((byte)(bodyLength >> 8));
                    out.write((byte)bodyLength);
                    out.write(body);
                    }
                }

            }
        }

        if ((finalBitSet) && (type == ResponseCodes.OBEX_HTTP_OK) && (orginalBodyLength <= 0)) {
            if(mSendBodyHeader == true) {
                out.write(0x49);
                orginalBodyLength = 3;
                out.write((byte)(orginalBodyLength >> 8));
                out.write((byte)orginalBodyLength);
            } else if (mEndofBody) {

                out.write(0x49);
                orginalBodyLength = 3;
                out.write((byte)(orginalBodyLength >> 8));
                out.write((byte)orginalBodyLength);
            }
        }

        mResponseSize = 3;
        mParent.sendResponse(type, out.toByteArray());

        if (type == ResponseCodes.OBEX_HTTP_CONTINUE) {
            int headerID = mInput.read();
            int length = mInput.read();
            length = (length << 8) + mInput.read();
            if ((headerID != ObexHelper.OBEX_OPCODE_PUT)
                    && (headerID != ObexHelper.OBEX_OPCODE_PUT_FINAL)
                    && (headerID != ObexHelper.OBEX_OPCODE_GET)
                    && (headerID != ObexHelper.OBEX_OPCODE_GET_FINAL)) {

                if (length > 3) {
                    byte[] temp = new byte[length - 3];
                    // First three bytes already read, compensating for this
                    bytesReceived = mInput.read(temp);

                    while (bytesReceived != temp.length) {
                        bytesReceived += mInput.read(temp, bytesReceived,
                                temp.length - bytesReceived);
                    }
                }

                /*
                 * Determine if an ABORT was sent as the reply
                 */
                if (headerID == ObexHelper.OBEX_OPCODE_ABORT) {
                    mParent.sendResponse(ResponseCodes.OBEX_HTTP_OK, null);
                    mClosed = true;
                    isAborted = true;
                    mExceptionString = "Abort Received";
                    throw new IOException("Abort Received");
                } else {
                    mParent.sendResponse(ResponseCodes.OBEX_HTTP_BAD_REQUEST, null);
                    mClosed = true;
                    mExceptionString = "Bad Request Received";
                    throw new IOException("Bad Request Received");
                }
            } else {

                if ((headerID == ObexHelper.OBEX_OPCODE_PUT_FINAL)) {
                    finalBitSet = true;
                } else if (headerID == ObexHelper.OBEX_OPCODE_GET_FINAL) {
                    mRequestFinished = true;
                }

                /*
                 * Determine if the packet length is larger then this device can receive
                 */
                if (length > ObexHelper.MAX_PACKET_SIZE_INT) {
                    mParent.sendResponse(ResponseCodes.OBEX_HTTP_REQ_TOO_LARGE, null);
                    throw new IOException("Packet received was too large");
                }

                /*
                 * Determine if any headers were sent in the initial request
                 */
                if (length > 3) {
                    byte[] data = new byte[length - 3];
                    bytesReceived = mInput.read(data);

                    while (bytesReceived != data.length) {
                        bytesReceived += mInput.read(data, bytesReceived, data.length
                                - bytesReceived);
                    }
                    byte[] body = ObexHelper.updateHeaderSet(requestHeader, data);
                    if (body != null) {
                        mHasBody = true;
                    }
                    if (mListener.getConnectionId() != -1 && requestHeader.mConnectionID != null) {
                        mListener.setConnectionId(ObexHelper
                                .convertToLong(requestHeader.mConnectionID));
                    } else {
                        mListener.setConnectionId(1);
                    }

                    if (requestHeader.mAuthResp != null) {
                        if (!mParent.handleAuthResp(requestHeader.mAuthResp)) {
                            mExceptionString = "Authentication Failed";
                            mParent.sendResponse(ResponseCodes.OBEX_HTTP_UNAUTHORIZED, null);
                            mClosed = true;
                            requestHeader.mAuthResp = null;
                            return false;
                        }
                        requestHeader.mAuthResp = null;
                    }

                    if (requestHeader.mAuthChall != null) {
                        mParent.handleAuthChall(requestHeader);
                        // send the auhtResp to the client
                        replyHeader.mAuthResp = new byte[requestHeader.mAuthResp.length];
                        System.arraycopy(requestHeader.mAuthResp, 0, replyHeader.mAuthResp, 0,
                                replyHeader.mAuthResp.length);
                        requestHeader.mAuthResp = null;
                        requestHeader.mAuthChall = null;
                    }

                    if (body != null) {
                        mPrivateInput.writeBytes(body, 1);
                    }
                }
            }
            return true;
        } else {
            return false;
        }
    }

    /**
     * Sends an ABORT message to the server. By calling this method, the
     * corresponding input and output streams will be closed along with this
     * object.
     * @throws IOException if the transaction has already ended or if an OBEX
     *         server called this method
     */
    public void abort() throws IOException {
        throw new IOException("Called from a server");
    }

    /**
     * Returns the headers that have been received during the operation.
     * Modifying the object returned has no effect on the headers that are sent
     * or retrieved.
     * @return the headers received during this <code>Operation</code>
     * @throws IOException if this <code>Operation</code> has been closed
     */
    public HeaderSet getReceivedHeader() throws IOException {
        ensureOpen();
        return requestHeader;
    }

    /**
     * Specifies the headers that should be sent in the next OBEX message that
     * is sent.
     * @param headers the headers to send in the next message
     * @throws IOException if this <code>Operation</code> has been closed or the
     *         transaction has ended and no further messages will be exchanged
     * @throws IllegalArgumentException if <code>headers</code> was not created
     *         by a call to <code>ServerRequestHandler.createHeaderSet()</code>
     */
    public void sendHeaders(HeaderSet headers) throws IOException {
        ensureOpen();

        if (headers == null) {
            throw new IOException("Headers may not be null");
        }

        int[] headerList = headers.getHeaderList();
        if (headerList != null) {
            for (int i = 0; i < headerList.length; i++) {
                replyHeader.setHeader(headerList[i], headers.getHeader(headerList[i]));
            }

        }
    }

    /**
     * Retrieves the response code retrieved from the server. Response codes are
     * defined in the <code>ResponseCodes</code> interface.
     * @return the response code retrieved from the server
     * @throws IOException if an error occurred in the transport layer during
     *         the transaction; if this method is called on a
     *         <code>HeaderSet</code> object created by calling
     *         <code>createHeaderSet</code> in a <code>ClientSession</code>
     *         object; if this is called from a server
     */
    public int getResponseCode() throws IOException {
        throw new IOException("Called from a server");
    }

    /**
     * Always returns <code>null</code>
     * @return <code>null</code>
     */
    public String getEncoding() {
        return null;
    }

    /**
     * Returns the type of content that the resource connected to is providing.
     * E.g. if the connection is via HTTP, then the value of the content-type
     * header field is returned.
     * @return the content type of the resource that the URL references, or
     *         <code>null</code> if not known
     */
    public String getType() {
        try {
            return (String)requestHeader.getHeader(HeaderSet.TYPE);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Returns the length of the content which is being provided. E.g. if the
     * connection is via HTTP, then the value of the content-length header field
     * is returned.
     * @return the content length of the resource that this connection's URL
     *         references, or -1 if the content length is not known
     */
    public long getLength() {
        try {
            Long temp = (Long)requestHeader.getHeader(HeaderSet.LENGTH);

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
        return mMaxPacketLength - 6 - getHeaderLength();
    }

    public int getHeaderLength() {
        long id = mListener.getConnectionId();
        if (id == -1) {
            replyHeader.mConnectionID = null;
        } else {
            replyHeader.mConnectionID = ObexHelper.convertToByteArray(id);
        }

        byte[] headerArray = ObexHelper.createHeader(replyHeader, false);

        return headerArray.length;
    }

    /**
     * Open and return an input stream for a connection.
     * @return an input stream
     * @throws IOException if an I/O error occurs
     */
    public InputStream openInputStream() throws IOException {
        ensureOpen();
        return mPrivateInput;
    }

    /**
     * Open and return a data input stream for a connection.
     * @return an input stream
     * @throws IOException if an I/O error occurs
     */
    public DataInputStream openDataInputStream() throws IOException {
        return new DataInputStream(openInputStream());
    }

    /**
     * Open and return an output stream for a connection.
     * @return an output stream
     * @throws IOException if an I/O error occurs
     */
    public OutputStream openOutputStream() throws IOException {
        ensureOpen();

        if (mPrivateOutputOpen) {
            throw new IOException("no more input streams available, stream already opened");
        }

        if (!mRequestFinished) {
            throw new IOException("no  output streams available ,request not finished");
        }

        if (mPrivateOutput == null) {
            mPrivateOutput = new PrivateOutputStream(this, getMaxPacketSize());
        }
        mPrivateOutputOpen = true;
        return mPrivateOutput;
    }

    /**
     * Open and return a data output stream for a connection.
     * @return an output stream
     * @throws IOException if an I/O error occurs
     */
    public DataOutputStream openDataOutputStream() throws IOException {
        return new DataOutputStream(openOutputStream());
    }

    /**
     * Closes the connection and ends the transaction
     * @throws IOException if the operation has already ended or is closed
     */
    public void close() throws IOException {
        ensureOpen();
        mClosed = true;
    }

    /**
     * Verifies that the connection is open and no exceptions should be thrown.
     * @throws IOException if an exception needs to be thrown
     */
    public void ensureOpen() throws IOException {
        if (mExceptionString != null) {
            throw new IOException(mExceptionString);
        }
        if (mClosed) {
            throw new IOException("Operation has already ended");
        }
    }

    /**
     * Verifies that additional information may be sent. In other words, the
     * operation is not done.
     * <P>
     * Included to implement the BaseStream interface only. It does not do
     * anything on the server side since the operation of the Operation object
     * is not done until after the handler returns from its method.
     * @throws IOException if the operation is completed
     */
    public void ensureNotDone() throws IOException {
    }

    /**
     * Called when the output or input stream is closed. It does not do anything
     * on the server side since the operation of the Operation object is not
     * done until after the handler returns from its method.
     * @param inStream <code>true</code> if the input stream is closed;
     *        <code>false</code> if the output stream is closed
     * @throws IOException if an IO error occurs
     */
    public void streamClosed(boolean inStream) throws IOException {

    }

    public void noBodyHeader(){
        mSendBodyHeader = false;
    }
    public void noEndofBody() {
        mEndofBody = false;
    }
}
