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

import static com.android.org.conscrypt.flags.Flags.certificateTransparencyCheckservertrustedApi;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.net.platform.flags.Flags;
import android.security.net.config.UserCertificateSource;

import com.android.org.conscrypt.TrustManagerImpl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.X509TrustManager;

/**
 * X509TrustManager wrapper exposing Android-added features.
 * <p>
 * The checkServerTrusted methods allow callers to provide some additional
 * context for the verification. This is particularly useful when an SSLEngine
 * or SSLSocket is not available.
 * </p>
 */
public class X509TrustManagerExtensions {

    private final TrustManagerImpl mDelegate;
    // Methods to use when mDelegate is not a TrustManagerImpl and duck typing is being used.
    private final X509TrustManager mTrustManager;
    private final Method mCheckServerTrusted;
    private final Method mCheckServerTrustedOcspAndTlsData;
    private final Method mIsSameTrustConfiguration;

    /**
     * Constructs a new X509TrustManagerExtensions wrapper.
     *
     * @param tm A {@link X509TrustManager} as returned by TrustManagerFactory.getInstance();
     * @throws IllegalArgumentException If tm is an unsupported TrustManager type.
     */
    public X509TrustManagerExtensions(X509TrustManager tm) throws IllegalArgumentException {
        if (tm instanceof TrustManagerImpl) {
            mDelegate = (TrustManagerImpl) tm;
            mTrustManager = null;
            mCheckServerTrusted = null;
            mCheckServerTrustedOcspAndTlsData = null;
            mIsSameTrustConfiguration = null;
            return;
        }
        // Use duck typing if possible.
        mDelegate = null;
        mTrustManager = tm;
        // Check that the hostname aware checkServerTrusted is present.
        try {
            mCheckServerTrusted = tm.getClass().getMethod("checkServerTrusted",
                    X509Certificate[].class,
                    String.class,
                    String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Required method"
                    + " checkServerTrusted(X509Certificate[], String, String) missing");
        }
        // Check that the OCSP and TlsData aware checkServerTrusted is present.
        Method checkServerTrustedOcspAndTlsData = null;
        try {
            checkServerTrustedOcspAndTlsData = tm.getClass().getMethod("checkServerTrusted",
                    X509Certificate[].class,
                    Byte[].class,
                    Byte[].class,
                    String.class,
                    String.class);
        } catch (ReflectiveOperationException ignored) { }
        mCheckServerTrustedOcspAndTlsData = checkServerTrustedOcspAndTlsData;
        // Get the option isSameTrustConfiguration method.
        Method isSameTrustConfiguration = null;
        try {
            isSameTrustConfiguration = tm.getClass().getMethod("isSameTrustConfiguration",
                    String.class,
                    String.class);
        } catch (ReflectiveOperationException ignored) {
        }
        mIsSameTrustConfiguration = isSameTrustConfiguration;
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
        if (mDelegate != null) {
            return mDelegate.checkServerTrusted(chain, authType, host);
        } else {
            try {
                return (List<X509Certificate>) mCheckServerTrusted.invoke(mTrustManager, chain,
                        authType, host);
            } catch (IllegalAccessException e) {
                throw new CertificateException("Failed to call checkServerTrusted", e);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof CertificateException) {
                    throw (CertificateException) e.getCause();
                }
                if (e.getCause() instanceof RuntimeException) {
                    throw (RuntimeException) e.getCause();
                }
                throw new CertificateException("checkServerTrusted failed", e.getCause());
            }
        }
    }

    /**
     * Verifies the given certificate chain.
     *
     * <p>See {@link X509TrustManager#checkServerTrusted(X509Certificate[], String)} for a
     * description of the chain and authType parameters. The final parameter, host, should be the
     * hostname of the server.</p>
     *
     * <p>ocspData and tlsSctData may be provided to verify any Signed Certificate Timestamp (SCT)
     * attached to the connection. These are ASN.1 octet strings (SignedCertificateTimestampList)
     * as described in RFC 6962, Section 3.3. Note that SCTs embedded in the certificate chain
     * will automatically be processed.
     * </p>
     *
     * @throws CertificateException if the chain does not verify correctly.
     * @throws IllegalArgumentException if the TrustManager is not compatible.
     * @return the properly ordered chain used for verification as a list of X509Certificates.
     */
    @FlaggedApi(Flags.FLAG_X509_EXTENSIONS_CERTIFICATE_TRANSPARENCY)
    @NonNull
    public List<X509Certificate> checkServerTrusted(
            @SuppressLint("ArrayReturn") @NonNull X509Certificate[] chain,
            @Nullable byte[] ocspData,
            @Nullable byte[] tlsSctData,
            @NonNull String authType,
            @NonNull String host) throws CertificateException {
        List<X509Certificate> result;
        if (mDelegate != null) {
            if (certificateTransparencyCheckservertrustedApi()) {
                result = mDelegate.checkServerTrusted(chain, ocspData, tlsSctData, authType, host);
                return result == null ? Collections.emptyList() : result;
            } else {
                // The conscrypt mainline module does not have the required method.
                throw new IllegalArgumentException("Required method"
                    + " checkServerTrusted(X509Certificate[], byte[], byte[], String, String)"
                    + " not available in TrustManagerImpl");
            }
        }
        if (mCheckServerTrustedOcspAndTlsData == null) {
            throw new IllegalArgumentException("Required method"
                    + " checkServerTrusted(X509Certificate[], byte[], byte[], String, String)"
                    + " missing");
        }
        try {
            result = (List<X509Certificate>) mCheckServerTrustedOcspAndTlsData.invoke(mTrustManager,
                    ocspData, tlsSctData, chain, authType, host);
            return result == null ? Collections.emptyList() : result;
        } catch (IllegalAccessException e) {
            throw new CertificateException("Failed to call checkServerTrusted", e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof CertificateException) {
                throw (CertificateException) e.getCause();
            }
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            }
            throw new CertificateException("checkServerTrusted failed", e.getCause());
        }
    }

    /**
     * Checks whether a CA certificate is added by an user.
     *
     * <p>Since {@link X509TrustManager#checkServerTrusted} may allow its parameter {@code chain} to
     * chain up to user-added CA certificates, this method can be used to perform additional
     * policies for user-added CA certificates.
     *
     * @return {@code true} to indicate that the certificate authority exists in the user added
     * certificate store, {@code false} otherwise.
     */
    public boolean isUserAddedCertificate(X509Certificate cert) {
        return UserCertificateSource.getInstance().findBySubjectAndPublicKey(cert) != null;
    }

    /**
     * Returns {@code true} if the TrustManager uses the same trust configuration for the provided
     * hostnames.
     */
    public boolean isSameTrustConfiguration(String hostname1, String hostname2) {
        if (mIsSameTrustConfiguration == null) {
            return true;
        }
        try {
            return (Boolean) mIsSameTrustConfiguration.invoke(mTrustManager, hostname1, hostname2);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to call isSameTrustConfiguration", e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException) {
                throw (RuntimeException) e.getCause();
            } else {
                throw new RuntimeException("isSameTrustConfiguration failed", e.getCause());
            }
        }
    }
}
