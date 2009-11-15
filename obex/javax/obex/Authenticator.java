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
 * This interface provides a way to respond to authentication challenge and
 * authentication response headers. When a client or server receives an
 * authentication challenge or authentication response header, the
 * <code>onAuthenticationChallenge()</code> or
 * <code>onAuthenticationResponse()</code> will be called, respectively, by the
 * implementation.
 * <P>
 * For more information on how the authentication procedure works in OBEX,
 * please review the IrOBEX specification at <A
 * HREF="http://www.irda.org">http://www.irda.org</A>.
 * <P>
 * <STRONG>Authentication Challenges</STRONG>
 * <P>
 * When a client or server receives an authentication challenge header, the
 * <code>onAuthenticationChallenge()</code> method will be invoked by the OBEX
 * API implementation. The application will then return the user name (if
 * needed) and password via a <code>PasswordAuthentication</code> object. The
 * password in this object is not sent in the authentication response. Instead,
 * the 16-byte challenge received in the authentication challenge is combined
 * with the password returned from the <code>onAuthenticationChallenge()</code>
 * method and passed through the MD5 hash algorithm. The resulting value is sent
 * in the authentication response along with the user name if it was provided.
 * <P>
 * <STRONG>Authentication Responses</STRONG>
 * <P>
 * When a client or server receives an authentication response header, the
 * <code>onAuthenticationResponse()</code> method is invoked by the API
 * implementation with the user name received in the authentication response
 * header. (The user name will be <code>null</code> if no user name was provided
 * in the authentication response header.) The application must determine the
 * correct password. This value should be returned from the
 * <code>onAuthenticationResponse()</code> method. If the authentication request
 * should fail without the implementation checking the password,
 * <code>null</code> should be returned by the application. (This is needed for
 * reasons like not recognizing the user name, etc.) If the returned value is
 * not <code>null</code>, the OBEX API implementation will combine the password
 * returned from the <code>onAuthenticationResponse()</code> method and
 * challenge sent via the authentication challenge, apply the MD5 hash
 * algorithm, and compare the result to the response hash received in the
 * authentication response header. If the values are not equal, an
 * <code>IOException</code> will be thrown if the client requested
 * authentication. If the server requested authentication, the
 * <code>onAuthenticationFailure()</code> method will be called on the
 * <code>ServerRequestHandler</code> that failed authentication. The connection
 * is <B>not</B> closed if authentication failed.
 * @hide
 */
public interface Authenticator {

    /**
     * Called when a client or a server receives an authentication challenge
     * header. It should respond to the challenge with a
     * <code>PasswordAuthentication</code> that contains the correct user name
     * and password for the challenge.
     * @param description the description of which user name and password should
     *        be used; if no description is provided in the authentication
     *        challenge or the description is encoded in an encoding scheme that
     *        is not supported, an empty string will be provided
     * @param isUserIdRequired <code>true</code> if the user ID is required;
     *        <code>false</code> if the user ID is not required
     * @param isFullAccess <code>true</code> if full access to the server will
     *        be granted; <code>false</code> if read only access will be granted
     * @return a <code>PasswordAuthentication</code> object containing the user
     *         name and password used for authentication
     */
    PasswordAuthentication onAuthenticationChallenge(String description, boolean isUserIdRequired,
            boolean isFullAccess);

    /**
     * Called when a client or server receives an authentication response
     * header. This method will provide the user name and expect the correct
     * password to be returned.
     * @param userName the user name provided in the authentication response; may
     *        be <code>null</code>
     * @return the correct password for the user name provided; if
     *         <code>null</code> is returned then the authentication request
     *         failed
     */
    byte[] onAuthenticationResponse(byte[] userName);
}
