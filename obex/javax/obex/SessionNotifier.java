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

/**
 * The <code>SessionNotifier</code> interface defines a connection notifier for
 * server-side OBEX connections. When a <code>SessionNotifier</code> is created
 * and calls <code>acceptAndOpen()</code>, it will begin listening for clients
 * to create a connection at the transport layer. When the transport layer
 * connection is received, the <code>acceptAndOpen()</code> method will return a
 * <code>javax.microedition.io.Connection</code> that is the connection to the
 * client. The <code>acceptAndOpen()</code> method also takes a
 * <code>ServerRequestHandler</code> argument that will process the requests
 * from the client that connects to the server.
 * @hide
 */
public interface SessionNotifier {

    /**
     * Waits for a transport layer connection to be established and specifies
     * the handler to handle the requests from the client. No authenticator is
     * associated with this connection, therefore, it is implementation
     * dependent as to how an authentication challenge and authentication
     * response header will be received and processed.
     * <P>
     * <H4>Additional Note for OBEX over Bluetooth</H4> If this method is called
     * on a <code>SessionNotifier</code> object that does not have a
     * <code>ServiceRecord</code> in the SDDB, the <code>ServiceRecord</code>
     * for this object will be added to the SDDB. This method requests the BCC
     * to put the local device in connectable mode so that it will respond to
     * connection attempts by clients.
     * <P>
     * The following checks are done to verify that the service record provided
     * is valid. If any of these checks fail, then a
     * <code>ServiceRegistrationException</code> is thrown.
     * <UL>
     * <LI>ServiceClassIDList and ProtocolDescriptorList, the mandatory service
     * attributes for a <code>btgoep</code> service record, must be present in
     * the <code>ServiceRecord</code> associated with this notifier.
     * <LI>L2CAP, RFCOMM and OBEX must all be in the ProtocolDescriptorList
     * <LI>The <code>ServiceRecord</code> associated with this notifier must not
     * have changed the RFCOMM server channel number
     * </UL>
     * <P>
     * This method will not ensure that <code>ServiceRecord</code> associated
     * with this notifier is a completely valid service record. It is the
     * responsibility of the application to ensure that the service record
     * follows all of the applicable syntactic and semantic rules for service
     * record correctness.
     * @param handler the request handler that will respond to OBEX requests
     * @return the connection to the client
     * @throws IOException if an error occurs in the transport layer
     * @throws NullPointerException if <code>handler</code> is <code>null</code>
     */
    ObexSession acceptAndOpen(ServerRequestHandler handler) throws IOException;

    /**
     * Waits for a transport layer connection to be established and specifies
     * the handler to handle the requests from the client and the
     * <code>Authenticator</code> to use to respond to authentication challenge
     * and authentication response headers.
     * <P>
     * <H4>Additional Note for OBEX over Bluetooth</H4> If this method is called
     * on a <code>SessionNotifier</code> object that does not have a
     * <code>ServiceRecord</code> in the SDDB, the <code>ServiceRecord</code>
     * for this object will be added to the SDDB. This method requests the BCC
     * to put the local device in connectable mode so that it will respond to
     * connection attempts by clients.
     * <P>
     * The following checks are done to verify that the service record provided
     * is valid. If any of these checks fail, then a
     * <code>ServiceRegistrationException</code> is thrown.
     * <UL>
     * <LI>ServiceClassIDList and ProtocolDescriptorList, the mandatory service
     * attributes for a <code>btgoep</code> service record, must be present in
     * the <code>ServiceRecord</code> associated with this notifier.
     * <LI>L2CAP, RFCOMM and OBEX must all be in the ProtocolDescriptorList
     * <LI>The <code>ServiceRecord</code> associated with this notifier must not
     * have changed the RFCOMM server channel number
     * </UL>
     * <P>
     * This method will not ensure that <code>ServiceRecord</code> associated
     * with this notifier is a completely valid service record. It is the
     * responsibility of the application to ensure that the service record
     * follows all of the applicable syntactic and semantic rules for service
     * record correctness.
     * @param handler the request handler that will respond to OBEX requests
     * @param auth the <code>Authenticator</code> to use with this connection;
     *        if <code>null</code> then no <code>Authenticator</code> will be
     *        used
     * @return the connection to the client
     * @throws IOException if an error occurs in the transport layer
     * @throws NullPointerException if <code>handler</code> is <code>null</code>
     */
    ObexSession acceptAndOpen(ServerRequestHandler handler, Authenticator auth) throws IOException;
}
