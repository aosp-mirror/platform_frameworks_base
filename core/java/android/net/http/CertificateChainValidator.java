/*
 * Copyright (C) 2008 The Android Open Source Project
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
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.DefaultHostnameVerifier;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509TrustManager;
import org.apache.harmony.security.provider.cert.X509CertImpl;
import org.apache.harmony.xnet.provider.jsse.SSLParametersImpl;
import org.apache.harmony.xnet.provider.jsse.TrustManagerImpl;

/**
 * Class responsible for all server certificate validation functionality
 *
 * {@hide}
 */
public class CertificateChainValidator {

    /**
     * The singleton instance of the certificate chain validator
     */
    private static final CertificateChainValidator sInstance
            = new CertificateChainValidator();

    private static final DefaultHostnameVerifier sVerifier
            = new DefaultHostnameVerifier();

    /**
     * @return The singleton instance of the certificates chain validator
     */
    public static CertificateChainValidator getInstance() {
        return sInstance;
    }

    /**
     * Creates a new certificate chain validator. This is a private constructor.
     * If you need a Certificate chain validator, call getInstance().
     */
    private CertificateChainValidator() {}

    /**
     * Performs the handshake and server certificates validation
     * Notice a new chain will be rebuilt by tracing the issuer and subject
     * before calling checkServerTrusted().
     * And if the last traced certificate is self issued and it is expired, it
     * will be dropped.
     * @param sslSocket The secure connection socket
     * @param domain The website domain
     * @return An SSL error object if there is an error and null otherwise
     */
    public SslError doHandshakeAndValidateServerCertificates(
            HttpsConnection connection, SSLSocket sslSocket, String domain)
            throws IOException {
        // get a valid SSLSession, close the socket if we fail
        SSLSession sslSession = sslSocket.getSession();
        if (!sslSession.isValid()) {
            closeSocketThrowException(sslSocket, "failed to perform SSL handshake");
        }

        // retrieve the chain of the server peer certificates
        Certificate[] peerCertificates =
            sslSocket.getSession().getPeerCertificates();

        if (peerCertificates == null || peerCertificates.length == 0) {
            closeSocketThrowException(
                sslSocket, "failed to retrieve peer certificates");
        } else {
            // update the SSL certificate associated with the connection
            if (connection != null) {
                if (peerCertificates[0] != null) {
                    connection.setCertificate(
                        new SslCertificate((X509Certificate)peerCertificates[0]));
                }
            }
        }

        return verifyServerDomainAndCertificates((X509Certificate[]) peerCertificates, domain, "RSA");
    }

    /**
     * Similar to doHandshakeAndValidateServerCertificates but exposed to JNI for use
     * by Chromium HTTPS stack to validate the cert chain.
     * @param certChain The bytes for certificates in ASN.1 DER encoded certificates format.
     * @param domain The full website hostname and domain
     * @param authType The authentication type for the cert chain
     * @return An SSL error object if there is an error and null otherwise
     */
    public static SslError verifyServerCertificates(
        byte[][] certChain, String domain, String authType)
        throws IOException {

        if (certChain == null || certChain.length == 0) {
            throw new IllegalArgumentException("bad certificate chain");
        }

        X509Certificate[] serverCertificates = new X509Certificate[certChain.length];

        for (int i = 0; i < certChain.length; ++i) {
            serverCertificates[i] = new X509CertImpl(certChain[i]);
        }

        return verifyServerDomainAndCertificates(serverCertificates, domain, authType);
    }

    /**
     * Handles updates to credential storage.
     */
    public static void handleTrustStorageUpdate() {

        try {
            X509TrustManager x509TrustManager = SSLParametersImpl.getDefaultTrustManager();
            if( x509TrustManager instanceof TrustManagerImpl ) {
                TrustManagerImpl trustManager = (TrustManagerImpl) x509TrustManager;
                trustManager.handleTrustStorageUpdate();
            }
        } catch (KeyManagementException ignored) {
        }
    }

    /**
     * Common code of doHandshakeAndValidateServerCertificates and verifyServerCertificates.
     * Calls DomainNamevalidator to verify the domain, and TrustManager to verify the certs.
     * @param chain the cert chain in X509 cert format.
     * @param domain The full website hostname and domain
     * @param authType The authentication type for the cert chain
     * @return An SSL error object if there is an error and null otherwise
     */
    private static SslError verifyServerDomainAndCertificates(
            X509Certificate[] chain, String domain, String authType)
            throws IOException {
        // check if the first certificate in the chain is for this site
        X509Certificate currCertificate = chain[0];
        if (currCertificate == null) {
            throw new IllegalArgumentException("certificate for this site is null");
        }

        boolean valid = domain != null
                && !domain.isEmpty()
                && sVerifier.verify(domain, currCertificate);
        if (!valid) {
            if (HttpLog.LOGV) {
                HttpLog.v("certificate not for this host: " + domain);
            }
            return new SslError(SslError.SSL_IDMISMATCH, currCertificate);
        }

        try {
            SSLParametersImpl.getDefaultTrustManager().checkServerTrusted(chain, authType);
            return null;  // No errors.
        } catch (GeneralSecurityException e) {
            if (HttpLog.LOGV) {
                HttpLog.v("failed to validate the certificate chain, error: " +
                    e.getMessage());
            }
            return new SslError(SslError.SSL_UNTRUSTED, currCertificate);
        }
    }


    private void closeSocketThrowException(
            SSLSocket socket, String errorMessage, String defaultErrorMessage)
            throws IOException {
        closeSocketThrowException(
            socket, errorMessage != null ? errorMessage : defaultErrorMessage);
    }

    private void closeSocketThrowException(SSLSocket socket,
            String errorMessage) throws IOException {
        if (HttpLog.LOGV) {
            HttpLog.v("validation error: " + errorMessage);
        }

        if (socket != null) {
            SSLSession session = socket.getSession();
            if (session != null) {
                session.invalidate();
            }

            socket.close();
        }

        throw new SSLHandshakeException(errorMessage);
    }
}
