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


import com.android.internal.net.DomainNameValidator;

import org.apache.harmony.xnet.provider.jsse.SSLParameters;

import java.io.IOException;

import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Date;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Class responsible for all server certificate validation functionality
 * 
 * {@hide}
 */
class CertificateChainValidator {

    /**
     * The singleton instance of the certificate chain validator
     */
    private static final CertificateChainValidator sInstance
            = new CertificateChainValidator();

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
        X509Certificate[] serverCertificates = null;

        // start handshake, close the socket if we fail
        try {
            sslSocket.setUseClientMode(true);
            sslSocket.startHandshake();
        } catch (IOException e) {
            closeSocketThrowException(
                sslSocket, e.getMessage(),
                "failed to perform SSL handshake");
        }

        // retrieve the chain of the server peer certificates
        Certificate[] peerCertificates =
            sslSocket.getSession().getPeerCertificates();

        if (peerCertificates == null || peerCertificates.length <= 0) {
            closeSocketThrowException(
                sslSocket, "failed to retrieve peer certificates");
        } else {
            serverCertificates =
                new X509Certificate[peerCertificates.length];
            for (int i = 0; i < peerCertificates.length; ++i) {
                serverCertificates[i] =
                    (X509Certificate)(peerCertificates[i]);
            }

            // update the SSL certificate associated with the connection
            if (connection != null) {
                if (serverCertificates[0] != null) {
                    connection.setCertificate(
                        new SslCertificate(serverCertificates[0]));
                }
            }
        }

        // check if the first certificate in the chain is for this site
        X509Certificate currCertificate = serverCertificates[0];
        if (currCertificate == null) {
            closeSocketThrowException(
                sslSocket, "certificate for this site is null");
        } else {
            if (!DomainNameValidator.match(currCertificate, domain)) {
                String errorMessage = "certificate not for this host: " + domain;

                if (HttpLog.LOGV) {
                    HttpLog.v(errorMessage);
                }

                sslSocket.getSession().invalidate();
                return new SslError(
                    SslError.SSL_IDMISMATCH, currCertificate);
            }
        }

        // Clean up the certificates chain and build a new one.
        // Theoretically, we shouldn't have to do this, but various web servers
        // in practice are mis-configured to have out-of-order certificates or
        // expired self-issued root certificate.
        int chainLength = serverCertificates.length;
        if (serverCertificates.length > 1) {
          // 1. we clean the received certificates chain.
          // We start from the end-entity certificate, tracing down by matching
          // the "issuer" field and "subject" field until we can't continue.
          // This helps when the certificates are out of order or
          // some certificates are not related to the site.
          int currIndex;
          for (currIndex = 0; currIndex < serverCertificates.length; ++currIndex) {
            boolean foundNext = false;
            for (int nextIndex = currIndex + 1;
                 nextIndex < serverCertificates.length;
                 ++nextIndex) {
              if (serverCertificates[currIndex].getIssuerDN().equals(
                  serverCertificates[nextIndex].getSubjectDN())) {
                foundNext = true;
                // Exchange certificates so that 0 through currIndex + 1 are in proper order
                if (nextIndex != currIndex + 1) {
                  X509Certificate tempCertificate = serverCertificates[nextIndex];
                  serverCertificates[nextIndex] = serverCertificates[currIndex + 1];
                  serverCertificates[currIndex + 1] = tempCertificate;
                }
                break;
              }
            }
            if (!foundNext) break;
          }

          // 2. we exam if the last traced certificate is self issued and it is expired.
          // If so, we drop it and pass the rest to checkServerTrusted(), hoping we might
          // have a similar but unexpired trusted root.
          chainLength = currIndex + 1;
          X509Certificate lastCertificate = serverCertificates[chainLength - 1];
          Date now = new Date();
          if (lastCertificate.getSubjectDN().equals(lastCertificate.getIssuerDN())
              && now.after(lastCertificate.getNotAfter())) {
            --chainLength;
          }
        }

        // 3. Now we copy the newly built chain into an appropriately sized array.
        X509Certificate[] newServerCertificates = null;
        newServerCertificates = new X509Certificate[chainLength];
        for (int i = 0; i < chainLength; ++i) {
          newServerCertificates[i] = serverCertificates[i];
        }

        // first, we validate the new chain using the standard validation
        // solution; if we do not find any errors, we are done; if we
        // fail the standard validation, we re-validate again below,
        // this time trying to retrieve any individual errors we can
        // report back to the user.
        //
        try {
            SSLParameters.getDefaultTrustManager().checkServerTrusted(
                newServerCertificates, "RSA");

            // no errors!!!
            return null;
        } catch (CertificateException e) {
            sslSocket.getSession().invalidate();

            if (HttpLog.LOGV) {
                HttpLog.v(
                    "failed to pre-validate the certificate chain, error: " +
                    e.getMessage());
            }
            return new SslError(
                SslError.SSL_UNTRUSTED, currCertificate);
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
