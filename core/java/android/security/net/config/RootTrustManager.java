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
    private static final X509Certificate[] EMPTY_ISSUERS = new X509Certificate[0];

    public RootTrustManager(ApplicationConfig config) {
        if (config == null) {
            throw new NullPointerException("config must not be null");
        }
        mConfig = config;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        throw new CertificateException("Client authentication not supported");
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

    public void checkServerTrusted(X509Certificate[] certs, String authType, String hostname)
            throws CertificateException {
        NetworkSecurityConfig config = mConfig.getConfigForHostname(hostname);
        config.getTrustManager().checkServerTrusted(certs, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return EMPTY_ISSUERS;
    }
}
