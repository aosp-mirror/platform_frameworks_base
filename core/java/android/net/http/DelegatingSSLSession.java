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

import java.security.Principal;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;

/**
 * This is only used when a {@code certificate} is available but usage
 * requires a {@link SSLSession}.
 *
 * @hide
 */
public class DelegatingSSLSession implements SSLSession {
    protected DelegatingSSLSession() {
    }

    public static class CertificateWrap extends DelegatingSSLSession {
        private final Certificate mCertificate;

        public CertificateWrap(Certificate certificate) {
            mCertificate = certificate;
        }

        @Override
        public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
            return new Certificate[] { mCertificate };
        }
    }


    @Override
    public int getApplicationBufferSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getCipherSuite() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getCreationTime() {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getId() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getLastAccessedTime() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Certificate[] getLocalCertificates() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Principal getLocalPrincipal() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getPacketBufferSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public javax.security.cert.X509Certificate[] getPeerCertificateChain()
            throws SSLPeerUnverifiedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Certificate[] getPeerCertificates() throws SSLPeerUnverifiedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getPeerHost() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getPeerPort() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Principal getPeerPrincipal() throws SSLPeerUnverifiedException {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getProtocol() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SSLSessionContext getSessionContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getValue(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getValueNames() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void invalidate() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isValid() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putValue(String name, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void removeValue(String name) {
        throw new UnsupportedOperationException();
    }
}
