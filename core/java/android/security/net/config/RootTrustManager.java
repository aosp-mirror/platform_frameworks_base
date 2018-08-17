/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.net.config;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import android.annotation.UnsupportedAppUsage;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * {@link X509ExtendedTrustManager} based on an {@link ApplicationConfig}.
 *
 * <p>This trust manager delegates to the specific trust manager for the hostname being used for
 * the connection (See {@link ApplicationConfig#getConfigForHostname(String)} and
 * {@link NetworkSecurityTrustManager}).</p>
 *
 * Note that if the {@code ApplicationConfig} has per-domain configurations the hostname aware
 * {@link #checkServerTrusted(X509Certificate[], String String)} must be used instead of the normal
 * non-aware call.
 * @hide */
public class RootTrustManager extends X509ExtendedTrustManager {
    private final ApplicationConfig mConfig;

    public RootTrustManager(ApplicationConfig config) {
        if (config == null) {
            throw new NullPointerException("config must not be null");
        }
        mConfig = config;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        // Use the default configuration for all client authentication. Domain specific configs are
        // only for use in checking server trust not client trust.
        NetworkSecurityConfig config = mConfig.getConfigForHostname("");
        config.getTrustManager().checkClientTrusted(chain, authType);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] certs, String authType, Socket socket)
            throws CertificateException {
        // Use the default configuration for all client authentication. Domain specific configs are
        // only for use in checking server trust not client trust.
        NetworkSecurityConfig config = mConfig.getConfigForHostname("");
        config.getTrustManager().checkClientTrusted(certs, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] certs, String authType, SSLEngine engine)
            throws CertificateException {
        // Use the default configuration for all client authentication. Domain specific configs are
        // only for use in checking server trust not client trust.
        NetworkSecurityConfig config = mConfig.getConfigForHostname("");
        config.getTrustManager().checkClientTrusted(certs, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] certs, String authType, Socket socket)
            throws CertificateException {
        if (socket instanceof SSLSocket) {
            SSLSocket sslSocket = (SSLSocket) socket;
            SSLSession session = sslSocket.getHandshakeSession();
            if (session == null) {
                throw new CertificateException("Not in handshake; no session available");
            }
            String host = session.getPeerHost();
            NetworkSecurityConfig config = mConfig.getConfigForHostname(host);
            config.getTrustManager().checkServerTrusted(certs, authType, socket);
        } else {
            // Not an SSLSocket, use the hostname unaware checkServerTrusted.
            checkServerTrusted(certs, authType);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] certs, String authType, SSLEngine engine)
            throws CertificateException {
        SSLSession session = engine.getHandshakeSession();
        if (session == null) {
            throw new CertificateException("Not in handshake; no session available");
        }
        String host = session.getPeerHost();
        NetworkSecurityConfig config = mConfig.getConfigForHostname(host);
        config.getTrustManager().checkServerTrusted(certs, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] certs, String authType)
            throws CertificateException {
        if (mConfig.hasPerDomainConfigs()) {
            throw new CertificateException(
                    "Domain specific configurations require that hostname aware"
                    + " checkServerTrusted(X509Certificate[], String, String) is used");
        }
        NetworkSecurityConfig config = mConfig.getConfigForHostname("");
        config.getTrustManager().checkServerTrusted(certs, authType);
    }

    /**
     * Hostname aware version of {@link #checkServerTrusted(X509Certificate[], String)}.
     * This interface is used by conscrypt and android.net.http.X509TrustManagerExtensions do not
     * modify without modifying those callers.
     */
    @UnsupportedAppUsage
    public List<X509Certificate> checkServerTrusted(X509Certificate[] certs, String authType,
            String hostname) throws CertificateException {
        if (hostname == null && mConfig.hasPerDomainConfigs()) {
            throw new CertificateException(
                    "Domain specific configurations require that the hostname be provided");
        }
        NetworkSecurityConfig config = mConfig.getConfigForHostname(hostname);
        return config.getTrustManager().checkServerTrusted(certs, authType, hostname);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        // getAcceptedIssuers is meant to be used to determine which trust anchors the server will
        // accept when verifying clients. Domain specific configs are only for use in checking
        // server trust not client trust so use the default config.
        NetworkSecurityConfig config = mConfig.getConfigForHostname("");
        return config.getTrustManager().getAcceptedIssuers();
    }

    /**
     * Returns {@code true} if this trust manager uses the same trust configuration for the provided
     * hostnames.
     *
     * <p>This is required by android.net.http.X509TrustManagerExtensions.
     */
    public boolean isSameTrustConfiguration(String hostname1, String hostname2) {
        return mConfig.getConfigForHostname(hostname1)
                .equals(mConfig.getConfigForHostname(hostname2));
    }
}
