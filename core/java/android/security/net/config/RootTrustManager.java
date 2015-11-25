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

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.X509TrustManager;

/**
 * {@link X509TrustManager} based on an {@link ApplicationConfig}.
 *
 * <p>This {@code X509TrustManager} delegates to the specific trust manager for the hostname
 * being used for the connection (See {@link ApplicationConfig#getConfigForHostname(String)} and
 * {@link NetworkSecurityTrustManager}).</p>
 *
 * Note that if the {@code ApplicationConfig} has per-domain configurations the hostname aware
 * {@link #checkServerTrusted(X509Certificate[], String String)} must be used instead of the normal
 * non-aware call.
 * @hide */
public class RootTrustManager implements X509TrustManager {
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
    public List<X509Certificate> checkServerTrusted(X509Certificate[] certs, String authType,
            String hostname) throws CertificateException {
        NetworkSecurityConfig config = mConfig.getConfigForHostname(hostname);
        return config.getTrustManager().checkServerTrusted(certs, authType, hostname);
    }

    /**
     * Check if the provided certificate is a user added certificate authority.
     * This is required by android.net.http.X509TrustManagerExtensions.
     */
    public boolean isUserAddedCertificate(X509Certificate cert) {
        // TODO: Figure out the right way to handle this, and if it is still even used.
        return false;
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        // getAcceptedIssuers is meant to be used to determine which trust anchors the server will
        // accept when verifying clients. Domain specific configs are only for use in checking
        // server trust not client trust so use the default config.
        NetworkSecurityConfig config = mConfig.getConfigForHostname("");
        return config.getTrustManager().getAcceptedIssuers();
    }
}
