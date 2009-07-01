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
import java.io.OutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayOutputStream;

/**
 * This class implements the <code>Operation</code> interface.  It will read
 * and write data via puts and gets.
 *
 * @hide
 */
public final class ClientOperation implements Operation, BaseStream {

    /**
     * Defines the basic packet length used by OBEX.  Event OBEX packet has the
     * same basic format:<BR>
     * Byte 0: Request or Response Code
     * Byte 1&2: Length of the packet.
     */
    private static final int BASE_PACKET_LENGTH = 3;

    private ClientSession parent;

    private InputStream socketInput;

    private PrivateInputStream privateInput;

    private PrivateOutputStream privateOutput;

    private boolean isClosed;

    private String exceptionMessage;

    private int maxPacketSize;

    private boolean isDone;

    private boolean isGet;

    private HeaderSet requestHeaders;

    private HeaderSet replyHeaders;

    private boolean isEndOfBodySent;

    private boolean inputStreamOpened;

    private boolean outputStreamOpened;

    private boolean isValidateConnected;

    /** 
     * Creates new OperationImpl to read and write data to a server
     *
     * @param in the input stream to read from
     *
     * @param maxSize the maximum packet size
     *
     * @param p the parent to this object
     *
     * @param headers the headers to set in the initial request
     *
     * @param type <code>true</code> if this is a get request;
     * <code>false</code. if this is a put request
     *
     * @throws IOExcpetion if the an IO error occured
     */
    public ClientOperation(InputStream in, int maxSize, ClientSession p, HeaderSet header,
            boolean type) throws IOException {

        parent = p;
        isEndOfBodySent = false;
        socketInput = in;
        isClosed = false;
        isDone = false;
        maxPacketSize = maxSize;
        isGet = type;

        inputStreamOpened = false;
        outputStreamOpened = false;
        isValidateConnected = false;

        privateInput = null;
        privateOutput = null;

        replyHeaders = new HeaderSet();

        requestHeaders = new HeaderSet();

        int[] headerList = header.getHeaderList();

        if (headerList != null) {

            for (int i = 0; i < headerList.length; i++) {
                requestHeaders.setHeader(headerList[i], header.getHeader(headerList[i]));
            }
        }

        if ((header).authChall != null) {
            requestHeaders.authChall = new byte[(header).authChall.length];
            System.arraycopy((header).authChall, 0, requestHeaders.authChall, 0,
                    (header).authChall.length);
        }

        if ((header).authResp != null) {
            requestHeaders.authResp = new byte[(header).authResp.length];
            System.arraycopy((header).authResp, 0, requestHeaders.authResp, 0,
                    (header).authResp.length);

        }
        //        requestHeaders = (HeaderSet)header;
    }

    /**
     * Sends an ABORT message to the server.  By calling this method, the
     * corresponding input and output streams will be closed along with this
     * object.
     *
     * @throws IOException if the transaction has already ended or if an
     * OBEX server called this method
     */
    public synchronized void abort() throws IOException {
        ensureOpen();
        // need check again .
        //	if(isDone) {
        //	     throw new IOException("Operation has already ended");
        //	}

        //no compatible with sun-ri
        if ((isDone) && (replyHeaders.responseCode != ObexHelper.OBEX_HTTP_CONTINUE)) {
            throw new IOException("Operation has already ended");
        }

        exceptionMessage = "Operation aborted";
        if ((!isDone) && (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE)) {
            isDone = true;
            /*
             * Since we are not sending any headers or returning any headers then
             * we just need to write and read the same bytes
             */
            parent.sendRequest(0xFF, null, replyHeaders, null);

            if (replyHeaders.responseCode != ResponseCodes.OBEX_HTTP_OK) {
                throw new IOException("Invalid response code from server");
            }

            exceptionMessage = null;
        }

        close();
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
     * <code>ClientSession</code> object
     */
    public synchronized int getResponseCode() throws IOException {
        //avoid dup validateConnection
        if ((replyHeaders.responseCode == -1)
                || (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE)) {
            validateConnection();
        }

        return replyHeaders.responseCode;
    }

    /**
     * This method will always return <code>null</code>
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
            return (String)replyHeaders.getHeader(HeaderSet.TYPE);
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
            Long temp = (Long)replyHeaders.getHeader(HeaderSet.LENGTH);

            if (temp == null) {
                return -1;
            } else {
                return temp.longValue();
            }
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Open and return an input stream for a connection.
     *
     * @return an input stream
     *
     * @throws IOException if an I/O error occurs
     */
    public InputStream openInputStream() throws IOException {
        // TODO: this mode is not set yet.
        // if ((parent.mode & Connector.READ) == 0)
        // throw new IOException("write-only connection");

        ensureOpen();

        if (inputStreamOpened)
            throw new IOException("no more input streams available");
        if (isGet) {
            // send the GET request here
            validateConnection();
            isValidateConnected = true;
        } else {
            if (privateInput == null) {
                privateInput = new PrivateInputStream(this);
            }
        }

        inputStreamOpened = true;

        return privateInput;
    }

    /**8
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
        // TODO: this mode is not set yet.
        //    	if ((parent.mode & Connector.WRITE) == 0)
        //    		throw new IOException("read-only connection");
        ensureOpen();
        ensureNotDone();

        if (outputStreamOpened)
            throw new IOException("no more output streams available");

        if (privateOutput == null) {
            // there are 3 bytes operation headers and 3 bytes body headers //
            privateOutput = new PrivateOutputStream(this, maxPacketSize - 6);
        }

        outputStreamOpened = true;

        return privateOutput;
    }

    public int getMaxPacketSize() {
        return maxPacketSize - 6;
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
        isClosed = true;
        inputStreamOpened = false;
        outputStreamOpened = false;
        parent.setRequestInactive();
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

        return replyHeaders;
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
     *
     * @throws NullPointerException if <code>headers</code> is <code>null</code>
     */
    public void sendHeaders(HeaderSet headers) throws IOException {
        ensureOpen();
        if (isDone) {
            throw new IOException("Operation has already exchanged all data");
        }

        if (headers == null) {
            throw new NullPointerException("Headers may not be null");
        }

        int[] headerList = headers.getHeaderList();
        if (headerList != null) {
            for (int i = 0; i < headerList.length; i++) {
                requestHeaders.setHeader(headerList[i], headers.getHeader(headerList[i]));
            }
        }
    }

    /**
     * Reads a response from the server.  It will populate the appropriate body
     * and headers.
     *
     * @return <code>true</code> if the transaction should end;
     * <code>false</code> if the transaction should not end
     *
     * @throws IOException if an IO error occurred
     */
    private boolean readResponse() throws IOException {
        replyHeaders.responseCode = socketInput.read();
        int packetLength = socketInput.read();
        packetLength = (packetLength << 8) + socketInput.read();

        if (packetLength > ObexHelper.MAX_PACKET_SIZE_INT) {
            if (exceptionMessage != null) {
                abort();
            }
            throw new IOException("Received a packet that was too big");
        }

        if (packetLength > BASE_PACKET_LENGTH) {
            int dataLength = packetLength - BASE_PACKET_LENGTH;
            byte[] data = new byte[dataLength];
            int readLength = socketInput.read(data);
            if (readLength != dataLength) {
                throw new IOException("Received a packet without data as decalred length");
            }
            byte[] body = ObexHelper.updateHeaderSet(replyHeaders, data);

            if (body != null) {
                privateInput.writeBytes(body, 1);

                /*
                 * Determine if a body (0x48) header or an end of body (0x49)
                 * was received.  If we received an end of body and
                 * a response code of OBEX_HTTP_OK, then the operation should
                 * end.
                 */
                if ((body[0] == 0x49) && (replyHeaders.responseCode == ResponseCodes.OBEX_HTTP_OK)) {
                    return false;
                }
            }
        }

        if (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Verifies that additional information may be sent.  In other words, the
     * operation is not done.
     *
     * @throws IOException if the operation is completed
     */
    public void ensureNotDone() throws IOException {
        if (isDone) {
            throw new IOException("Operation has completed");
        }
    }

    /**
     * Verifies that the connection is open and no exceptions should be thrown.
     *
     * @throws IOException if an exception needs to be thrown
     */
    public void ensureOpen() throws IOException {
        parent.ensureOpen();

        if (exceptionMessage != null) {
            throw new IOException(exceptionMessage);
        }
        if (isClosed) {
            throw new IOException("Operation has already ended");
        }
    }

    /**
     * Verifies that the connection is open and the proper data has been read.
     *
     * @throws IOException if an IO error occurs
     */
    private void validateConnection() throws IOException {
        ensureOpen();

        // to sure only one privateInput object exist.
        if (privateInput == null) {
            startProcessing();
        }
    }

    /**
     * Sends a request to the client of the specified type
     *
     * @param response the response code to send back to the client
     *
     * @return <code>true</code> if there is more data to send;
     * <code>false</code> if there is no more data to send
     *
     * @throws IOException if an IO error occurs
     */
    protected boolean sendRequest(int type) throws IOException {
        boolean returnValue = false;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int bodyLength = -1;
        byte[] headerArray = ObexHelper.createHeader(requestHeaders, true);
        if (privateOutput != null) {
            bodyLength = privateOutput.size();
        }

        /*
         * Determine if there is space to add a body request.  At present
         * this method checks to see if there is room for at least a 17
         * byte body header.  This number needs to be at least 6 so that
         * there is room for the header ID and length and the reply ID and
         * length, but it is a waste of resources if we can't send much of
         * the body.
         */
        if ((BASE_PACKET_LENGTH + headerArray.length) > maxPacketSize) {
            int end = 0;
            int start = 0;
            // split & send the headerArray in multiple packets.

            while (end != headerArray.length) {
                //split the headerArray
                end = ObexHelper.findHeaderEnd(headerArray, start, maxPacketSize
                        - BASE_PACKET_LENGTH);
                // can not split 
                if (end == -1) {
                    isDone = true;
                    abort();
                    // isDone = true;
                    exceptionMessage = "Header larger then can be sent in a packet";
                    isClosed = true;

                    if (privateInput != null) {
                        privateInput.close();
                    }

                    if (privateOutput != null) {
                        privateOutput.close();
                    }
                    throw new IOException("OBEX Packet exceeds max packet size");
                }

                byte[] sendHeader = new byte[end - start];
                System.arraycopy(headerArray, start, sendHeader, 0, sendHeader.length);
                if (!parent.sendRequest(type, sendHeader, replyHeaders, privateInput)) {
                    return false;
                }

                if (replyHeaders.responseCode != ObexHelper.OBEX_HTTP_CONTINUE) {
                    return false;
                }

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

        if (bodyLength > 0) {
            /*
             * Determine if I can send the whole body or just part of
             * the body.  Remember that there is the 3 bytes for the
             * response message and 3 bytes for the header ID and length
             */
            if (bodyLength > (maxPacketSize - headerArray.length - 6)) {
                returnValue = true;

                bodyLength = maxPacketSize - headerArray.length - 6;
            }

            byte[] body = privateOutput.readBytes(bodyLength);

            /*
             * Since this is a put request if the final bit is set or
             * the output stream is closed we need to send the 0x49
             * (End of Body) otherwise, we need to send 0x48 (Body)
             */
            if ((privateOutput.isClosed()) && (!returnValue) && (!isEndOfBodySent)
                    && ((type & 0x80) != 0)) {
                out.write(0x49);
                isEndOfBodySent = true;
            } else {
                out.write(0x48);
            }

            bodyLength += 3;
            out.write((byte)(bodyLength >> 8));
            out.write((byte)bodyLength);

            if (body != null) {
                out.write(body);
            }
        }

        if (outputStreamOpened && bodyLength <= 0 && !isEndOfBodySent) {
            // only 0x82 or 0x83 can send 0x49
            if ((type & 0x80) == 0) {
                out.write(0x48);
            } else {
                out.write(0x49);
                isEndOfBodySent = true;

            }

            bodyLength = 3;
            out.write((byte)(bodyLength >> 8));
            out.write((byte)bodyLength);
        }

        if (out.size() == 0) {
            if (!parent.sendRequest(type, null, replyHeaders, privateInput)) {
                return false;
            }
            return returnValue;
        }
        if ((out.size() > 0)
                && (!parent.sendRequest(type, out.toByteArray(), replyHeaders, privateInput))) {
            return false;
        }

        // send all of the output data in 0x48, 
        // send 0x49 with empty body
        if ((privateOutput != null) && (privateOutput.size() > 0))
            returnValue = true;

        return returnValue;
    }

    /**
     * This method starts the processing thread results.  It will send the
     * initial request.  If the response takes more then one packet, a thread
     * will be started to handle additional requests
     *
     * @throws IOException if an IO error occurs
     */
    private synchronized void startProcessing() throws IOException {

        if (privateInput == null) {
            privateInput = new PrivateInputStream(this);
        }
        boolean more = true;

        if (isGet) {
            if (!isDone) {
                replyHeaders.responseCode = ObexHelper.OBEX_HTTP_CONTINUE;
                while ((more) && (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE)) {
                    more = sendRequest(0x03);
                }

                if (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE) {
                    parent.sendRequest(0x83, null, replyHeaders, privateInput);
                }
                if (replyHeaders.responseCode != ObexHelper.OBEX_HTTP_CONTINUE) {
                    isDone = true;
                }
            }
        } else {

            if (!isDone) {
                replyHeaders.responseCode = ObexHelper.OBEX_HTTP_CONTINUE;
                while ((more) && (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE)) {
                    more = sendRequest(0x02);

                }
            }

            if (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE) {
                parent.sendRequest(0x82, null, replyHeaders, privateInput);
            }

            if (replyHeaders.responseCode != ObexHelper.OBEX_HTTP_CONTINUE) {
                isDone = true;
            }
        }
    }

    /**
     * Continues the operation since there is no data to read.
     *
     * @param sendEmpty <code>true</code> if the operation should send an
     * empty packet or not send anything if there is no data to send
     * @param inStream  <code>true</code> if the stream is input stream or
     * is output stream
     * @throws IOException if an IO error occurs
     */
    public synchronized boolean continueOperation(boolean sendEmpty, boolean inStream)
            throws IOException {

        if (isGet) {
            if ((inStream) && (!isDone)) {
                // to deal with inputstream in get operation
                parent.sendRequest(0x83, null, replyHeaders, privateInput);
                /*
                  * Determine if that was not the last packet in the operation
                  */
                if (replyHeaders.responseCode != ObexHelper.OBEX_HTTP_CONTINUE) {
                    isDone = true;
                }

                return true;

            } else if ((!inStream) && (!isDone)) {
                // to deal with outputstream in get operation

                if (privateInput == null) {
                    privateInput = new PrivateInputStream(this);
                }
                sendRequest(0x03);
                return true;

            } else if (isDone) {
                return false;
            }

        } else {
            if ((!inStream) && (!isDone)) {
                // to deal with outputstream in put operation
                if (replyHeaders.responseCode == -1) {
                    replyHeaders.responseCode = ObexHelper.OBEX_HTTP_CONTINUE;
                }
                sendRequest(0x02);
                return true;
            } else if ((inStream) && (!isDone)) {
                // How to deal with inputstream  in put operation ?
                return false;

            } else if (isDone) {
                return false;
            }

        }
        return false;
    }

    /**
     * Called when the output or input stream is closed.
     *
     * @param inStream <code>true</code> if the input stream is closed;
     * <code>false</code> if the output stream is closed
     *
     * @throws IOException if an IO error occurs
     */
    public void streamClosed(boolean inStream) throws IOException {
        if (!isGet) {
            if ((!inStream) && (!isDone)) {
                // to deal with outputstream in put operation

                boolean more = true;

                if ((privateOutput != null) && (privateOutput.size() <= 0)) {
                    byte[] headerArray = ObexHelper.createHeader(requestHeaders, false);
                    if (headerArray.length <= 0)
                        more = false;
                }
                // If have not sent any data so send  all now
                if (replyHeaders.responseCode == -1) {
                    replyHeaders.responseCode = ObexHelper.OBEX_HTTP_CONTINUE;
                }

                while ((more) && (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE)) {
                    more = sendRequest(0x02);
                }

                /*
                 * According to the IrOBEX specification, after the final put, you
                 * only have a single reply to send.  so we don't need the while
                 * loop.
                 */
                while (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE) {

                    sendRequest(0x82);
                }
                isDone = true;
            } else if ((inStream) && (isDone)) {
                // how to deal with input stream in put stream ?
                isDone = true;
            }
        } else {
            isValidateConnected = false;
            if ((inStream) && (!isDone)) {

                // to deal with inputstream in get operation
                // Have not sent any data so send it all now

                if (replyHeaders.responseCode == -1) {
                    replyHeaders.responseCode = ObexHelper.OBEX_HTTP_CONTINUE;
                }

                while (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE) {
                    if (!sendRequest(0x83)) {
                        break;
                    }
                }
                while (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE) {
                    parent.sendRequest(0x83, null, replyHeaders, privateInput);
                }
                isDone = true;
            } else if ((!inStream) && (!isDone)) {
                // to deal with outputstream in get operation
                // part of the data may have been sent in continueOperation.

                boolean more = true;

                if ((privateOutput != null) && (privateOutput.size() <= 0)) {
                    byte[] headerArray = ObexHelper.createHeader(requestHeaders, false);
                    if (headerArray.length <= 0)
                        more = false;
                }

                if (privateInput == null) {
                    privateInput = new PrivateInputStream(this);
                }
                if ((privateOutput != null) && (privateOutput.size() <= 0))
                    more = false;

                replyHeaders.responseCode = ObexHelper.OBEX_HTTP_CONTINUE;
                while ((more) && (replyHeaders.responseCode == ObexHelper.OBEX_HTTP_CONTINUE)) {
                    more = sendRequest(0x03);
                }
                sendRequest(0x83);
                //                parent.sendRequest(0x83, null, replyHeaders, privateInput);
                if (replyHeaders.responseCode != ObexHelper.OBEX_HTTP_CONTINUE) {
                    isDone = true;
                }

            }
        }
    }
}
