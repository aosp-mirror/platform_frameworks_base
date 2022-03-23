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
 * This class holds user name and password combinations.
 * @hide
 */
public final class PasswordAuthentication {

    private byte[] mUserName;

    private final byte[] mPassword;

    /**
     * Creates a new <code>PasswordAuthentication</code> with the user name and
     * password provided.
     * @param userName the user name to include; this may be <code>null</code>
     * @param password the password to include in the response
     * @throws NullPointerException if <code>password</code> is
     *         <code>null</code>
     */
    public PasswordAuthentication(final byte[] userName, final byte[] password) {
        if (userName != null) {
            mUserName = new byte[userName.length];
            System.arraycopy(userName, 0, mUserName, 0, userName.length);
        }

        mPassword = new byte[password.length];
        System.arraycopy(password, 0, mPassword, 0, password.length);
    }

    /**
     * Retrieves the user name that was specified in the constructor. The user
     * name may be <code>null</code>.
     * @return the user name
     */
    public byte[] getUserName() {
        return mUserName;
    }

    /**
     * Retrieves the password.
     * @return the password
     */
    public byte[] getPassword() {
        return mPassword;
    }
}
