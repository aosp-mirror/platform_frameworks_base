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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The <code>ObexTransport</code> interface defines the underlying transport
 * connection which carries the OBEX protocol( such as TCP, RFCOMM device file
 * exposed by Bluetooth or USB in kernel, RFCOMM socket emulated in Android
 * platform, Irda). This interface provides an abstract layer to be used by the
 * <code>ObexConnection</code>. Each kind of medium shall have its own
 * implementation to wrap and follow the same interface.
 * <P>
 * See section 1.2.2 of IrDA Object Exchange Protocol specification.
 * <P>
 * Different kind of medium may have different construction - for example, the
 * RFCOMM device file medium may be constructed from a file descriptor or simply
 * a string while the TCP medium usually from a socket.
 * @hide
 */
public interface ObexTransport {

    void create() throws IOException;

    void listen() throws IOException;

    void close() throws IOException;

    void connect() throws IOException;

    void disconnect() throws IOException;

    InputStream openInputStream() throws IOException;

    OutputStream openOutputStream() throws IOException;

    DataInputStream openDataInputStream() throws IOException;

    DataOutputStream openDataOutputStream() throws IOException;

    /**
     * Must return the maximum allowed OBEX packet that can be sent over
     * the transport. For L2CAP this will be the Max SDU reported by the
     * peer device.
     * The returned value will be used to set the outgoing OBEX packet
     * size. Therefore this value shall not change.
     * For RFCOMM or other transport types where the OBEX packets size
     * is unrelated to the transport packet size, return -1;
     * @return the maximum allowed OBEX packet that can be send over
     *         the transport. Or -1 in case of don't care.
     */
    int getMaxTransmitPacketSize();

    /**
     * Must return the maximum allowed OBEX packet that can be received over
     * the transport. For L2CAP this will be the Max SDU configured for the
     * L2CAP channel.
     * The returned value will be used to validate the incoming packet size
     * values.
     * For RFCOMM or other transport types where the OBEX packets size
     * is unrelated to the transport packet size, return -1;
     * @return the maximum allowed OBEX packet that can be send over
     *         the transport. Or -1 in case of don't care.
     */
    int getMaxReceivePacketSize();

    /**
     * Shall return true if the transport in use supports SRM.
     * @return
     *        <code>true</code> if SRM operation is supported, and is to be enabled.
     *        <code>false</code> if SRM operations are not supported, or should not be used.
     */
    boolean isSrmSupported();


}
