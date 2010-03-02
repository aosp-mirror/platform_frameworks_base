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

/**
 * The <code>ServerRequestHandler</code> class defines an event listener that
 * will respond to OBEX requests made to the server.
 * <P>
 * The <code>onConnect()</code>, <code>onSetPath()</code>,
 * <code>onDelete()</code>, <code>onGet()</code>, and <code>onPut()</code>
 * methods may return any response code defined in the
 * <code>ResponseCodes</code> class except for <code>OBEX_HTTP_CONTINUE</code>.
 * If <code>OBEX_HTTP_CONTINUE</code> or a value not defined in the
 * <code>ResponseCodes</code> class is returned, the server implementation will
 * send an <code>OBEX_HTTP_INTERNAL_ERROR</code> response to the client.
 * <P>
 * <STRONG>Connection ID and Target Headers</STRONG>
 * <P>
 * According to the IrOBEX specification, a packet may not contain a Connection
 * ID and Target header. Since the Connection ID header is managed by the
 * implementation, it will not send a Connection ID header, if a Connection ID
 * was specified, in a packet that has a Target header. In other words, if an
 * application adds a Target header to a <code>HeaderSet</code> object used in
 * an OBEX operation and a Connection ID was specified, no Connection ID will be
 * sent in the packet containing the Target header.
 * <P>
 * <STRONG>CREATE-EMPTY Requests</STRONG>
 * <P>
 * A CREATE-EMPTY request allows clients to create empty objects on the server.
 * When a CREATE-EMPTY request is received, the <code>onPut()</code> method will
 * be called by the implementation. To differentiate between a normal PUT
 * request and a CREATE-EMPTY request, an application must open the
 * <code>InputStream</code> from the <code>Operation</code> object passed to the
 * <code>onPut()</code> method. For a PUT request, the application will be able
 * to read Body data from this <code>InputStream</code>. For a CREATE-EMPTY
 * request, there will be no Body data to read. Therefore, a call to
 * <code>InputStream.read()</code> will return -1.
 * @hide
 */
public class ServerRequestHandler {

    private long mConnectionId;

    /**
     * Creates a <code>ServerRequestHandler</code>.
     */
    protected ServerRequestHandler() {
        /*
         * A connection ID of -1 implies there is no conenction ID
         */
        mConnectionId = -1;
    }

    /**
     * Sets the connection ID header to include in the reply packets.
     * @param connectionId the connection ID to use; -1 if no connection ID
     *        should be sent
     * @throws IllegalArgumentException if <code>id</code> is not in the range
     *         -1 to 2<sup>32</sup>-1
     */
    public void setConnectionId(final long connectionId) {
        if ((connectionId < -1) || (connectionId > 0xFFFFFFFFL)) {
            throw new IllegalArgumentException("Illegal Connection ID");
        }
        mConnectionId = connectionId;
    }

    /**
     * Retrieves the connection ID that is being used in the present connection.
     * This method will return -1 if no connection ID is being used.
     * @return the connection id being used or -1 if no connection ID is being
     *         used
     */
    public long getConnectionId() {
        return mConnectionId;
    }

    /**
     * Called when a CONNECT request is received.
     * <P>
     * If this method is not implemented by the class that extends this class,
     * <code>onConnect()</code> will always return an <code>OBEX_HTTP_OK</code>
     * response code.
     * <P>
     * The headers received in the request can be retrieved from the
     * <code>request</code> argument. The headers that should be sent in the
     * reply must be specified in the <code>reply</code> argument.
     * @param request contains the headers sent by the client;
     *        <code>request</code> will never be <code>null</code>
     * @param reply the headers that should be sent in the reply;
     *        <code>reply</code> will never be <code>null</code>
     * @return a response code defined in <code>ResponseCodes</code> that will
     *         be returned to the client; if an invalid response code is
     *         provided, the <code>OBEX_HTTP_INTERNAL_ERROR</code> response code
     *         will be used
     */
    public int onConnect(HeaderSet request, HeaderSet reply) {
        return ResponseCodes.OBEX_HTTP_OK;
    }

    /**
     * Called when a DISCONNECT request is received.
     * <P>
     * The headers received in the request can be retrieved from the
     * <code>request</code> argument. The headers that should be sent in the
     * reply must be specified in the <code>reply</code> argument.
     * @param request contains the headers sent by the client;
     *        <code>request</code> will never be <code>null</code>
     * @param reply the headers that should be sent in the reply;
     *        <code>reply</code> will never be <code>null</code>
     */
    public void onDisconnect(HeaderSet request, HeaderSet reply) {
    }

    /**
     * Called when a SETPATH request is received.
     * <P>
     * If this method is not implemented by the class that extends this class,
     * <code>onSetPath()</code> will always return an
     * <code>OBEX_HTTP_NOT_IMPLEMENTED</code> response code.
     * <P>
     * The headers received in the request can be retrieved from the
     * <code>request</code> argument. The headers that should be sent in the
     * reply must be specified in the <code>reply</code> argument.
     * @param request contains the headers sent by the client;
     *        <code>request</code> will never be <code>null</code>
     * @param reply the headers that should be sent in the reply;
     *        <code>reply</code> will never be <code>null</code>
     * @param backup <code>true</code> if the client requests that the server
     *        back up one directory before changing to the path described by
     *        <code>name</code>; <code>false</code> to apply the request to the
     *        present path
     * @param create <code>true</code> if the path should be created if it does
     *        not already exist; <code>false</code> if the path should not be
     *        created if it does not exist and an error code should be returned
     * @return a response code defined in <code>ResponseCodes</code> that will
     *         be returned to the client; if an invalid response code is
     *         provided, the <code>OBEX_HTTP_INTERNAL_ERROR</code> response code
     *         will be used
     */
    public int onSetPath(HeaderSet request, HeaderSet reply, boolean backup, boolean create) {

        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    /**
     * Called when a DELETE request is received.
     * <P>
     * If this method is not implemented by the class that extends this class,
     * <code>onDelete()</code> will always return an
     * <code>OBEX_HTTP_NOT_IMPLEMENTED</code> response code.
     * <P>
     * The headers received in the request can be retrieved from the
     * <code>request</code> argument. The headers that should be sent in the
     * reply must be specified in the <code>reply</code> argument.
     * @param request contains the headers sent by the client;
     *        <code>request</code> will never be <code>null</code>
     * @param reply the headers that should be sent in the reply;
     *        <code>reply</code> will never be <code>null</code>
     * @return a response code defined in <code>ResponseCodes</code> that will
     *         be returned to the client; if an invalid response code is
     *         provided, the <code>OBEX_HTTP_INTERNAL_ERROR</code> response code
     *         will be used
     */
    public int onDelete(HeaderSet request, HeaderSet reply) {
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    /**
     * Called when a ABORT request is received.
     */
    public int onAbort(HeaderSet request, HeaderSet reply) {
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    /**
     * Called when a PUT request is received.
     * <P>
     * If this method is not implemented by the class that extends this class,
     * <code>onPut()</code> will always return an
     * <code>OBEX_HTTP_NOT_IMPLEMENTED</code> response code.
     * <P>
     * If an ABORT request is received during the processing of a PUT request,
     * <code>op</code> will be closed by the implementation.
     * @param operation contains the headers sent by the client and allows new
     *        headers to be sent in the reply; <code>op</code> will never be
     *        <code>null</code>
     * @return a response code defined in <code>ResponseCodes</code> that will
     *         be returned to the client; if an invalid response code is
     *         provided, the <code>OBEX_HTTP_INTERNAL_ERROR</code> response code
     *         will be used
     */
    public int onPut(Operation operation) {
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    /**
     * Called when a GET request is received.
     * <P>
     * If this method is not implemented by the class that extends this class,
     * <code>onGet()</code> will always return an
     * <code>OBEX_HTTP_NOT_IMPLEMENTED</code> response code.
     * <P>
     * If an ABORT request is received during the processing of a GET request,
     * <code>op</code> will be closed by the implementation.
     * @param operation contains the headers sent by the client and allows new
     *        headers to be sent in the reply; <code>op</code> will never be
     *        <code>null</code>
     * @return a response code defined in <code>ResponseCodes</code> that will
     *         be returned to the client; if an invalid response code is
     *         provided, the <code>OBEX_HTTP_INTERNAL_ERROR</code> response code
     *         will be used
     */
    public int onGet(Operation operation) {
        return ResponseCodes.OBEX_HTTP_NOT_IMPLEMENTED;
    }

    /**
     * Called when this object attempts to authenticate a client and the
     * authentication request fails because the response digest in the
     * authentication response header was wrong.
     * <P>
     * If this method is not implemented by the class that extends this class,
     * this method will do nothing.
     * @param userName the user name returned in the authentication response;
     *        <code>null</code> if no user name was provided in the response
     */
    public void onAuthenticationFailure(byte[] userName) {
    }

    /**
     * Called by ServerSession to update the status of current transaction
     * <P>
     * If this method is not implemented by the class that extends this class,
     * this method will do nothing.
     */
    public void updateStatus(String message) {
    }

    /**
     * Called when session is closed.
     * <P>
     * If this method is not implemented by the class that extends this class,
     * this method will do nothing.
     */
    public void onClose() {
    }
}
