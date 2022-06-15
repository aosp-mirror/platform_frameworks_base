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
 * This interface defines the methods needed by a parent that uses the
 * PrivateInputStream and PrivateOutputStream objects defined in this package.
 * @hide
 */
public interface BaseStream {

    /**
     * Verifies that this object is still open.
     * @throws IOException if the object is closed
     */
    void ensureOpen() throws IOException;

    /**
     * Verifies that additional information may be sent. In other words, the
     * operation is not done.
     * @throws IOException if the operation is completed
     */
    void ensureNotDone() throws IOException;

    /**
     * Continues the operation since there is no data to read.
     * @param sendEmpty <code>true</code> if the operation should send an empty
     *        packet or not send anything if there is no data to send
     * @param inStream <code>true</code> if the stream is input stream or is
     *        output stream
     * @return <code>true</code> if the operation was completed;
     *         <code>false</code> if no operation took place
     * @throws IOException if an IO error occurs
     */
    boolean continueOperation(boolean sendEmpty, boolean inStream) throws IOException;

    /**
     * Called when the output or input stream is closed.
     * @param inStream <code>true</code> if the input stream is closed;
     *        <code>false</code> if the output stream is closed
     * @throws IOException if an IO error occurs
     */
    void streamClosed(boolean inStream) throws IOException;
}
