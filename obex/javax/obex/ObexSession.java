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

import android.util.Log;

/**
 * The <code>ObexSession</code> interface characterizes the term
 * "OBEX Connection" as defined in the IrDA Object Exchange Protocol v1.2, which
 * could be the server-side view of an OBEX connection, or the client-side view
 * of the same connection, which is established by server's accepting of a
 * client issued "CONNECT".
 * <P>
 * This interface serves as the common super class for
 * <CODE>ClientSession</CODE> and <CODE>ServerSession</CODE>.
 * @hide
 */
public class ObexSession {

    private static final String TAG = "ObexSession";
    private static final boolean V = ObexHelper.VDBG;

    protected Authenticator mAuthenticator;

    protected byte[] mChallengeDigest;

    /**
     * Called when the server received an authentication challenge header. This
     * will cause the authenticator to handle the authentication challenge.
     * @param header the header with the authentication challenge
     * @return <code>true</code> if the last request should be resent;
     *         <code>false</code> if the last request should not be resent
     * @throws IOException
     */
    public boolean handleAuthChall(HeaderSet header) throws IOException {
        if (mAuthenticator == null) {
            return false;
        }

        /*
         * An authentication challenge is made up of one required and two
         * optional tag length value triplets. The tag 0x00 is required to be in
         * the authentication challenge and it represents the challenge digest
         * that was received. The tag 0x01 is the options tag. This tag tracks
         * if user ID is required and if full access will be granted. The tag
         * 0x02 is the realm, which provides a description of which user name
         * and password to use.
         */
        byte[] challenge = ObexHelper.getTagValue((byte)0x00, header.mAuthChall);
        byte[] option = ObexHelper.getTagValue((byte)0x01, header.mAuthChall);
        byte[] description = ObexHelper.getTagValue((byte)0x02, header.mAuthChall);

        String realm = null;
        if (description != null) {
            byte[] realmString = new byte[description.length - 1];
            System.arraycopy(description, 1, realmString, 0, realmString.length);

            switch (description[0] & 0xFF) {

                case ObexHelper.OBEX_AUTH_REALM_CHARSET_ASCII:
                    // ASCII encoding
                    // Fall through
                case ObexHelper.OBEX_AUTH_REALM_CHARSET_ISO_8859_1:
                    // ISO-8859-1 encoding
                    try {
                        realm = new String(realmString, "ISO8859_1");
                    } catch (Exception e) {
                        throw new IOException("Unsupported Encoding Scheme");
                    }
                    break;

                case ObexHelper.OBEX_AUTH_REALM_CHARSET_UNICODE:
                    // UNICODE Encoding
                    realm = ObexHelper.convertToUnicode(realmString, false);
                    break;

                default:
                    throw new IOException("Unsupported Encoding Scheme");
            }
        }

        boolean isUserIDRequired = false;
        boolean isFullAccess = true;
        if (option != null) {
            if ((option[0] & 0x01) != 0) {
                isUserIDRequired = true;
            }

            if ((option[0] & 0x02) != 0) {
                isFullAccess = false;
            }
        }

        PasswordAuthentication result = null;
        header.mAuthChall = null;

        try {
            result = mAuthenticator
                    .onAuthenticationChallenge(realm, isUserIDRequired, isFullAccess);
        } catch (Exception e) {
            if (V) Log.d(TAG, "Exception occured - returning false", e);
            return false;
        }

        /*
         * If no password is provided then we not resent the request
         */
        if (result == null) {
            return false;
        }

        byte[] password = result.getPassword();
        if (password == null) {
            return false;
        }

        byte[] userName = result.getUserName();

        /*
         * Create the authentication response header. It includes 1 required and
         * 2 option tag length value triples. The required triple has a tag of
         * 0x00 and is the response digest. The first optional tag is 0x01 and
         * represents the user ID. If no user ID is provided, then no user ID
         * will be sent. The second optional tag is 0x02 and is the challenge
         * that was received. This will always be sent
         */
        if (userName != null) {
            header.mAuthResp = new byte[38 + userName.length];
            header.mAuthResp[36] = (byte)0x01;
            header.mAuthResp[37] = (byte)userName.length;
            System.arraycopy(userName, 0, header.mAuthResp, 38, userName.length);
        } else {
            header.mAuthResp = new byte[36];
        }

        // Create the secret String
        byte[] digest = new byte[challenge.length + password.length + 1];
        System.arraycopy(challenge, 0, digest, 0, challenge.length);
        // Insert colon between challenge and password
        digest[challenge.length] = (byte)0x3A;
        System.arraycopy(password, 0, digest, challenge.length + 1, password.length);

        // Add the Response Digest
        header.mAuthResp[0] = (byte)0x00;
        header.mAuthResp[1] = (byte)0x10;

        System.arraycopy(ObexHelper.computeMd5Hash(digest), 0, header.mAuthResp, 2, 16);

        // Add the challenge
        header.mAuthResp[18] = (byte)0x02;
        header.mAuthResp[19] = (byte)0x10;
        System.arraycopy(challenge, 0, header.mAuthResp, 20, 16);

        return true;
    }

    /**
     * Called when the server received an authentication response header. This
     * will cause the authenticator to handle the authentication response.
     * @param authResp the authentication response
     * @return <code>true</code> if the response passed; <code>false</code> if
     *         the response failed
     */
    public boolean handleAuthResp(byte[] authResp) {
        if (mAuthenticator == null) {
            return false;
        }
        // get the correct password from the application
        byte[] correctPassword = mAuthenticator.onAuthenticationResponse(ObexHelper.getTagValue(
                (byte)0x01, authResp));
        if (correctPassword == null) {
            return false;
        }

        byte[] temp = new byte[correctPassword.length + 16];

        System.arraycopy(mChallengeDigest, 0, temp, 0, 16);
        System.arraycopy(correctPassword, 0, temp, 16, correctPassword.length);

        byte[] correctResponse = ObexHelper.computeMd5Hash(temp);
        byte[] actualResponse = ObexHelper.getTagValue((byte)0x00, authResp);

        // compare the MD5 hash array .
        for (int i = 0; i < 16; i++) {
            if (correctResponse[i] != actualResponse[i]) {
                return false;
            }
        }

        return true;
    }
}
