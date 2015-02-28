/*
 * Copyright (C) 2012 The Android Open Source Project
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

import com.android.org.conscrypt.TrustManagerImpl;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.X509TrustManager;

/**
 * X509TrustManager wrapper exposing Android-added features.
 * <p>
 * The checkServerTrusted method allows callers to perform additional
 * verification of certificate chains after they have been successfully verified
 * by the platform.
 * </p>
 */
public class X509TrustManagerExtensions {

    final TrustManagerImpl mDelegate;

    /**
     * Constructs a new X509TrustManagerExtensions wrapper.
     *
     * @param tm A {@link X509TrustManager} as returned by TrustManagerFactory.getInstance();
     * @throws IllegalArgumentException If tm is an unsupported TrustManager type.
     */
    public X509TrustManagerExtensions(X509TrustManager tm) throws IllegalArgumentException {
        if (tm instanceof TrustManagerImpl) {
            mDelegate = (TrustManagerImpl) tm;
        } else {
            mDelegate = null;
            throw new IllegalArgumentException("tm is an instance of " + tm.getClass().getName() +
                    " which is not a supported type of X509TrustManager");
        }
    }

    /**
     * Verifies the given certificate chain.
     *
     * <p>See {@link X509TrustManager#checkServerTrusted(X509Certificate[], String)} for a
     * description of the chain and authType parameters. The final parameter, host, should be the
     * hostname of the server.</p>
     *
     * @throws CertificateException if the chain does not verify correctly.
     * @return the properly ordered chain used for verification as a list of X509Certificates.
     */
    public List<X509Certificate> checkServerTrusted(X509Certificate[] chain, String authType,
                                                    String host) throws CertificateException {
        return mDelegate.checkServerTrusted(chain, authType, host);
    }

    /**
     * Checks whether a CA certificate is added by an user.
     *
     * <p>Since {@link X509TrustManager#checkServerTrusted} allows its parameter {@code chain} to
     * chain up to user-added CA certificates, this method can be used to perform additional
     * policies for user-added CA certificates.
     *
     * @return {@code true} to indicate that the certificate was added by the user, {@code false}
     * otherwise.
     */
    public boolean isUserAddedCertificate(X509Certificate cert) {
        return mDelegate.isUserAddedCertificate(cert);
    }
}
