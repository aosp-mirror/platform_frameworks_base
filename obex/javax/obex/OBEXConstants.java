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
 * This class implements the <code>Operation</code> interface.  It will read
 * and write data via puts and gets.
 *
 * @version 0.3 November 28, 2008
 */
public class OBEXConstants {
    /**
     * Defines the OBEX CONTINUE response code.
     * <P>
     * The value of <code>OBEX_HTTP_CONTINUE</code> is 0x90 (144).
     */
    public static final int OBEX_HTTP_CONTINUE = 0x90;

    /**
     * The maximum packet size for OBEX packets that this client can handle.
     * At present, this must be changed for each port.
     *
     * OPTIMIZATION: The max packet size should be the Max incoming MTU minus
     * OPTIMIZATION: L2CAP package headers and RFCOMM package headers.
     *
     * OPTIMIZATION: Retrieve the max incoming MTU from
     * OPTIMIZATION: LocalDevice.getProperty().
     */
    /** android note
     *  set as 0xFFFE to match remote MPS
     */
    //public static final byte[] MAX_PACKET_SIZE = {0x01, 0x00};  // To be removed
    //public static final int MAX_PACKET_SIZE_INT = 667*6;//0x0100; 
    public static final int MAX_PACKET_SIZE_INT = 0xFFFE;

    /**
     * The number of server parser threads that may be active at one time.
     * This should be changed for each port.
     *
     * OPTIMIZATION: Retrieve this value by a native call to the KOSI layer.
     */
    public static final int MAX_PARSER_THREADS = 5;

}
