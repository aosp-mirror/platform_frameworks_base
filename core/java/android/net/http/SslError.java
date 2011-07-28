/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.http;

import java.security.cert.X509Certificate;

/**
 * One or more individual SSL errors and the associated SSL certificate
 */
public class SslError {

    /**
     * Individual SSL errors (in the order from the least to the most severe):
     */

    /**
     * The certificate is not yet valid
     */
    public static final int SSL_NOTYETVALID = 0;
    /**
     * The certificate has expired
     */
    public static final int SSL_EXPIRED = 1;
    /**
     * Hostname mismatch
     */
    public static final int SSL_IDMISMATCH = 2;
    /**
     * The certificate authority is not trusted
     */
    public static final int SSL_UNTRUSTED = 3;
    /**
     * The date of the certificate is invalid
     */
    public static final int SSL_DATE_INVALID = 4;
    /**
     * The certificate is invalid
     */
    public static final int SSL_INVALID = 5;


    /**
     * The number of different SSL errors (update if you add a new SSL error!!!)
     * @deprecated This constant is not necessary for using the SslError API and
     *             can change from release to release.
     */
    @Deprecated
    public static final int SSL_MAX_ERROR = 6;

    /**
     * The SSL error set bitfield (each individual error is an bit index;
     * multiple individual errors can be OR-ed)
     */
    int mErrors;

    /**
     * The SSL certificate associated with the error set
     */
    final SslCertificate mCertificate;

    /**
     * The URL associated with the error set.
     */
    final String mUrl;

    /**
     * Creates a new SSL error set object
     * @param error The SSL error
     * @param certificate The associated SSL certificate
     * @deprecated Use {@link #SslError(int, SslCertificate, String)}
     */
    @Deprecated
    public SslError(int error, SslCertificate certificate) {
        addError(error);
        if (certificate == null) {
            throw new NullPointerException("certificate is null.");
        }
        mCertificate = certificate;
        mUrl = "";
    }

    /**
     * Creates a new SSL error set object
     * @param error The SSL error
     * @param certificate The associated SSL certificate
     * @deprecated Use {@link #SslError(int, X509Certificate, String)}
     */
    @Deprecated
    public SslError(int error, X509Certificate certificate) {
        addError(error);
        if (certificate == null) {
            throw new NullPointerException("certificate is null.");
        }
        mCertificate = new SslCertificate(certificate);
        mUrl = "";
    }

    /**
     * Creates a new SSL error set object
     * @param error The SSL error
     * @param certificate The associated SSL certificate
     * @param url The associated URL.
     */
    public SslError(int error, SslCertificate certificate, String url) {
        addError(error);
        if (certificate == null) {
            throw new NullPointerException("certificate is null.");
        }
        mCertificate = certificate;
        if (url == null) {
            throw new NullPointerException("url is null.");
        }
        mUrl = url;
    }

    /**
     * Creates an SslError object from a chromium error code.
     * @param error The chromium error code
     * @param certificate The associated SSL certificate
     * @param url The associated URL.
     * @hide  chromium error codes only available inside the framework
     */
    public static SslError SslErrorFromChromiumErrorCode(
            int error, SslCertificate cert, String url) {
        // The chromium error codes are in:
        // external/chromium/net/base/net_error_list.h
        if (error > -200 || error < -299) {
            throw new NullPointerException("Not a valid chromium SSL error code.");
        }
        if (error == -200)
            return new SslError(SSL_IDMISMATCH, cert, url);
        if (error == -201)
            return new SslError(SSL_DATE_INVALID, cert, url);
        if (error == -202)
            return new SslError(SSL_UNTRUSTED, cert, url);
        // Map all other errors to SSL_INVALID
        return new SslError(SSL_INVALID, cert, url);
    }

    /**
     * Creates a new SSL error set object
     * @param error The SSL error
     * @param certificate The associated SSL certificate
     * @param url The associated URL.
     */
    public SslError(int error, X509Certificate certificate, String url) {
        addError(error);
        if (certificate == null) {
            throw new NullPointerException("certificate is null.");
        }
        mCertificate = new SslCertificate(certificate);
        if (url == null) {
            throw new NullPointerException("url is null.");
        }
        mUrl = url;
    }

    /**
     * @return The SSL certificate associated with the error set, non-null.
     */
    public SslCertificate getCertificate() {
        return mCertificate;
    }

    /**
     * @return The URL associated with the error set, non-null.
     * "" if one of the deprecated constructors is used.
     */
    public String getUrl() {
        return mUrl;
    }

    /**
     * Adds the SSL error to the error set
     * @param error The SSL error to add
     * @return True iff the error being added is a known SSL error
     */
    public boolean addError(int error) {
        boolean rval = (0 <= error && error < SslError.SSL_MAX_ERROR);
        if (rval) {
            mErrors |= (0x1 << error);
        }

        return rval;
    }

    /**
     * @param error The SSL error to check
     * @return True iff the set includes the error
     */
    public boolean hasError(int error) {
        boolean rval = (0 <= error && error < SslError.SSL_MAX_ERROR);
        if (rval) {
            rval = ((mErrors & (0x1 << error)) != 0);
        }

        return rval;
    }

    /**
     * @return The primary, most severe, SSL error in the set
     */
    public int getPrimaryError() {
        if (mErrors != 0) {
            // go from the most to the least severe errors
            for (int error = SslError.SSL_MAX_ERROR - 1; error >= 0; --error) {
                if ((mErrors & (0x1 << error)) != 0) {
                    return error;
                }
            }
        }

        return 0;
    }

    /**
     * @return A String representation of this SSL error object
     * (used mostly for debugging).
     */
    public String toString() {
        return "primary error: " + getPrimaryError() +
            " certificate: " + getCertificate() +
            "  on URL: " + getUrl();
    }
}
