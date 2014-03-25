/*
 * Copyright 2014 The Android Open Source Project
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

import java.io.IOException;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * This is used when only a {@code hostname} is available for
 * {@link X509ExtendedTrustManager#checkServerTrusted(java.security.cert.X509Certificate[], String, Socket)}
 * but we want to use the new API that requires a {@link SSLSocket}.
 */
class DelegatingSocketWrapper extends SSLSocket {
    private String hostname;

    public DelegatingSocketWrapper(String hostname) {
        this.hostname = hostname;
    }

    @Override
    public String[] getSupportedCipherSuites() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getEnabledCipherSuites() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEnabledCipherSuites(String[] suites) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getSupportedProtocols() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getEnabledProtocols() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEnabledProtocols(String[] protocols) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SSLSession getSession() {
        return new DelegatingSSLSession.HostnameWrap(hostname);
    }

    @Override
    public void addHandshakeCompletedListener(HandshakeCompletedListener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void startHandshake() throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setUseClientMode(boolean mode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean getUseClientMode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setNeedClientAuth(boolean need) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setWantClientAuth(boolean want) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean getNeedClientAuth() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean getWantClientAuth() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEnableSessionCreation(boolean flag) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean getEnableSessionCreation() {
        throw new UnsupportedOperationException();
    }
}