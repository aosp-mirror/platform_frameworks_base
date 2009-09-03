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

package android.net;

import android.net.http.DomainNameChecker;
import android.os.SystemProperties;
import android.util.Config;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.apache.harmony.xnet.provider.jsse.SSLClientSessionCache;
import org.apache.harmony.xnet.provider.jsse.SSLContextImpl;
import org.apache.harmony.xnet.provider.jsse.SSLParameters;

/**
 * SSLSocketFactory that provides optional (on debug devices, only) skipping of ssl certificfate
 * chain validation and custom read timeouts used just when connecting to the server/negotiating
 * an ssl session.
 *
 * You can skip the ssl certificate checking at runtime by setting socket.relaxsslcheck=yes on
 * devices that do not have have ro.secure set.
 */
public class SSLCertificateSocketFactory extends SSLSocketFactory {

    private static final String LOG_TAG = "SSLCertificateSocketFactory";

    private static final TrustManager[] TRUST_MANAGER = new TrustManager[] {
        new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            public void checkClientTrusted(X509Certificate[] certs,
                    String authType) { }

            public void checkServerTrusted(X509Certificate[] certs,
                    String authType) { }
        }
    };

    private final SSLSocketFactory mFactory;

    private final int mSocketReadTimeoutForSslHandshake;

    /**
     * Do not use this constructor (will be deprecated).  Use {@link #getDefault(int)} instead.
     */
    public SSLCertificateSocketFactory(int socketReadTimeoutForSslHandshake)
            throws NoSuchAlgorithmException, KeyManagementException {
        this(socketReadTimeoutForSslHandshake, null /* cache */);
    }

    private SSLCertificateSocketFactory(int socketReadTimeoutForSslHandshake,
            SSLClientSessionCache cache) throws NoSuchAlgorithmException, KeyManagementException {
        SSLContextImpl sslContext = new SSLContextImpl();
        sslContext.engineInit(null /* kms */,
            TRUST_MANAGER, new java.security.SecureRandom(),
            cache /* client cache */, null /* server cache */);
        this.mFactory = sslContext.engineGetSocketFactory();
        this.mSocketReadTimeoutForSslHandshake = socketReadTimeoutForSslHandshake;
    }

    /**
     * Returns a new instance of a socket factory using the specified socket read
     * timeout while connecting with the server/negotiating an ssl session.
     *
     * @param socketReadTimeoutForSslHandshake the socket read timeout used for performing
     *        ssl handshake. The socket read timeout is set back to 0 after the handshake.
     * @return a new SocketFactory, or null on error
     */
    public static SocketFactory getDefault(int socketReadTimeoutForSslHandshake) {
        return getDefault(socketReadTimeoutForSslHandshake, null /* cache */);
    }

    /**
     * Returns a new instance of a socket factory using the specified socket read
     * timeout while connecting with the server/negotiating an ssl session.
     * Persists ssl sessions using the provided {@link SSLClientSessionCache}.
     *
     * @param socketReadTimeoutForSslHandshake the socket read timeout used for performing
     *        ssl handshake. The socket read timeout is set back to 0 after the handshake.
     * @param cache The {@link SSLClientSessionCache} to use, if any.
     * @return a new SocketFactory, or null on error
     *
     * @hide
    */
    public static SocketFactory getDefault(int socketReadTimeoutForSslHandshake,
            SSLClientSessionCache cache) {
        try {
            return new SSLCertificateSocketFactory(socketReadTimeoutForSslHandshake, cache);
        } catch (NoSuchAlgorithmException e) {
            Log.e(LOG_TAG, 
                    "SSLCertifcateSocketFactory.getDefault" +
                    " NoSuchAlgorithmException " , e);
            return null;
        } catch (KeyManagementException e) {
            Log.e(LOG_TAG, 
                    "SSLCertifcateSocketFactory.getDefault" +
                    " KeyManagementException " , e);
            return null; 
        }
    }

    private boolean hasValidCertificateChain(Certificate[] certs) 
            throws IOException {
        boolean trusted = (certs != null && (certs.length > 0));

        if (trusted) {
            try {
                // the authtype we pass in doesn't actually matter
                SSLParameters.getDefaultTrustManager()
                        .checkServerTrusted((X509Certificate[]) certs, "RSA");
            } catch (GeneralSecurityException e) { 
                String exceptionMessage = e != null ? e.getMessage() : "none";
                if (Config.LOGD) {
                    Log.d(LOG_TAG,"hasValidCertificateChain(): sec. exception: "
                         + exceptionMessage);
                }
                trusted = false;
            }
        }

        return trusted;
    }

    private void validateSocket(SSLSocket sslSock, String destHost) 
            throws IOException
    {
        if (Config.LOGV) {
            Log.v(LOG_TAG,"validateSocket() to host "+destHost);
        }

        String relaxSslCheck = SystemProperties.get("socket.relaxsslcheck");
        String secure = SystemProperties.get("ro.secure");

        // only allow relaxing the ssl check on non-secure builds where the relaxation is
        // specifically requested.
        if ("0".equals(secure) && "yes".equals(relaxSslCheck)) {
            if (Config.LOGD) {
                Log.d(LOG_TAG,"sys prop socket.relaxsslcheck is set," +
                        " ignoring invalid certs");
            }
            return;
        }

        Certificate[] certs = null;
        sslSock.setUseClientMode(true);
        sslSock.startHandshake();
        certs = sslSock.getSession().getPeerCertificates();

        // check that the root certificate in the chain belongs to
        // a CA we trust
        if (certs == null) {
            Log.e(LOG_TAG, 
                    "[SSLCertificateSocketFactory] no trusted root CA");
            throw new IOException("no trusted root CA");
        }

        if (Config.LOGV) {
            Log.v(LOG_TAG,"validateSocket # certs = " +certs.length);
        }

        if (!hasValidCertificateChain(certs)) {
            if (Config.LOGD) {
                Log.d(LOG_TAG,"validateSocket(): certificate untrusted!");
            }
            throw new IOException("Certificate untrusted");
        }

        X509Certificate lastChainCert = (X509Certificate) certs[0];

        if (!DomainNameChecker.match(lastChainCert, destHost)) {
            if (Config.LOGD) {
                Log.d(LOG_TAG,"validateSocket(): domain name check failed");
            }
            throw new IOException("Domain Name check failed");
        }
    }

    public Socket createSocket(Socket socket, String s, int i, boolean flag)
            throws IOException
    {
        throw new IOException("Cannot validate certification without a hostname");       
    }

    public Socket createSocket(InetAddress inaddr, int i, InetAddress inaddr2, int j)
            throws IOException
    {
        throw new IOException("Cannot validate certification without a hostname");       
    }

    public Socket createSocket(InetAddress inaddr, int i) throws IOException {
        throw new IOException("Cannot validate certification without a hostname");       
    }

    public Socket createSocket(String s, int i, InetAddress inaddr, int j) throws IOException {
        SSLSocket sslSock = (SSLSocket) mFactory.createSocket(s, i, inaddr, j);

        if (mSocketReadTimeoutForSslHandshake >= 0) {
            sslSock.setSoTimeout(mSocketReadTimeoutForSslHandshake);
        }

        validateSocket(sslSock,s);
        sslSock.setSoTimeout(0);
        
        return sslSock;
    }

    public Socket createSocket(String s, int i) throws IOException {
        SSLSocket sslSock = (SSLSocket) mFactory.createSocket(s, i);

        if (mSocketReadTimeoutForSslHandshake >= 0) {
            sslSock.setSoTimeout(mSocketReadTimeoutForSslHandshake);
        }
        
        validateSocket(sslSock,s);
        sslSock.setSoTimeout(0);

        return sslSock;
    }

    public String[] getDefaultCipherSuites() {
        return mFactory.getSupportedCipherSuites();
    }

    public String[] getSupportedCipherSuites() {
        return mFactory.getSupportedCipherSuites();
    }
}


