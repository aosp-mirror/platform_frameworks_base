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

import android.os.SystemProperties;
import android.util.Config;
import android.util.Log;

import com.android.common.DomainNameValidator;

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

import org.apache.harmony.xnet.provider.jsse.OpenSSLSocketImpl;
import org.apache.harmony.xnet.provider.jsse.SSLClientSessionCache;
import org.apache.harmony.xnet.provider.jsse.SSLContextImpl;
import org.apache.harmony.xnet.provider.jsse.SSLParameters;

/**
 * SSLSocketFactory implementation with several extra features:
 * <ul>
 * <li>Timeout specification for SSL handshake operations
 * <li>Optional SSL session caching with {@link SSLSessionCache}
 * <li>On development devices, "setprop socket.relaxsslcheck yes" bypasses all
 * SSL certificate checks, for testing with development servers
 * </ul>
 * Note that the handshake timeout does not apply to actual connection.
 * If you want a connection timeout as well, use {@link #createSocket()} and
 * {@link Socket#connect(SocketAddress, int)}.
 */
public class SSLCertificateSocketFactory extends SSLSocketFactory {
    private static final String TAG = "SSLCertificateSocketFactory";

    private static final TrustManager[] INSECURE_TRUST_MANAGER = new TrustManager[] {
        new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return null; }
            public void checkClientTrusted(X509Certificate[] certs, String authType) { }
            public void checkServerTrusted(X509Certificate[] certs, String authType) { }
        }
    };

    private SSLSocketFactory mInsecureFactory = null;
    private SSLSocketFactory mSecureFactory = null;

    private final int mHandshakeTimeoutMillis;
    private final SSLClientSessionCache mSessionCache;

    /** @deprecated Use {@link #getDefault(int)} instead. */
    public SSLCertificateSocketFactory(int handshakeTimeoutMillis) {
        this(handshakeTimeoutMillis, null /* cache */);
    }

    private SSLCertificateSocketFactory(int handshakeTimeoutMillis, SSLSessionCache cache) {
        mHandshakeTimeoutMillis = handshakeTimeoutMillis;
        mSessionCache = cache == null ? null : cache.mSessionCache;
    }

    /**
     * Returns a new instance of a socket factory using the specified socket read
     * timeout while connecting with the server/negotiating an ssl session.
     *
     * @param handshakeTimeoutMillis to use for SSL connection handshake, or 0
     *         for none.  The socket timeout is reset to 0 after the handshake.
     * @return a new SocketFactory with the specified parameters
     */
    public static SocketFactory getDefault(int handshakeTimeoutMillis) {
        return getDefault(handshakeTimeoutMillis, null /* cache */);
    }

    /**
     * Returns a new instance of a socket factory using the specified socket
     * read timeout while connecting with the server/negotiating an ssl session
     * Persists ssl sessions using the provided {@link SSLClientSessionCache}.
     *
     * @param handshakeTimeoutMillis to use for SSL connection handshake, or 0
     *         for none.  The socket timeout is reset to 0 after the handshake.
     * @param cache The {@link SSLClientSessionCache} to use, or null for no cache.
     * @return a new SocketFactory with the specified parameters
     */
    public static SocketFactory getDefault(int handshakeTimeoutMillis, SSLSessionCache cache) {
        return new SSLCertificateSocketFactory(handshakeTimeoutMillis, cache);
    }

    /**
     * Returns a socket factory (also named SSLSocketFactory, but in a different
     * namespace) for use with the Apache HTTP stack.
     *
     * @param handshakeTimeoutMillis to use for SSL connection handshake, or 0
     *         for none.  The socket timeout is reset to 0 after the handshake.
     * @param cache The {@link SSLClientSessionCache} to use, or null for no cache.
     * @return a new SocketFactory with the specified parameters
     */
    public static org.apache.http.conn.ssl.SSLSocketFactory getHttpSocketFactory(
            int handshakeTimeoutMillis,
            SSLSessionCache cache) {
        return new org.apache.http.conn.ssl.SSLSocketFactory(
                new SSLCertificateSocketFactory(handshakeTimeoutMillis, cache));
    }

    private SSLSocketFactory makeSocketFactory(TrustManager[] trustManagers) {
        try {
            SSLContextImpl sslContext = new SSLContextImpl();
            sslContext.engineInit(null, trustManagers, null, mSessionCache, null);
            return sslContext.engineGetSocketFactory();
        } catch (KeyManagementException e) {
            Log.wtf(TAG, e);
            return (SSLSocketFactory) SSLSocketFactory.getDefault();  // Fallback
        }
    }

    private synchronized SSLSocketFactory getDelegate() {
        // only allow relaxing the ssl check on non-secure builds where the relaxation is
        // specifically requested.
        if ("0".equals(SystemProperties.get("ro.secure")) &&
            "yes".equals(SystemProperties.get("socket.relaxsslcheck"))) {
            if (mInsecureFactory == null) {
                Log.w(TAG, "*** BYPASSING SSL SECURITY CHECKS (socket.relaxsslcheck=yes) ***");
                mInsecureFactory = makeSocketFactory(INSECURE_TRUST_MANAGER);
            }
            return mInsecureFactory;
        } else {
            if (mSecureFactory == null) {
                mSecureFactory = makeSocketFactory(null);
            }
            return mSecureFactory;
        }
    }

    @Override
    public Socket createSocket(Socket k, String host, int port, boolean close) throws IOException {
        OpenSSLSocketImpl s = (OpenSSLSocketImpl) getDelegate().createSocket(k, host, port, close);
        s.setHandshakeTimeout(mHandshakeTimeoutMillis);
        return s;
    }

    @Override
    public Socket createSocket() throws IOException {
        OpenSSLSocketImpl s = (OpenSSLSocketImpl) getDelegate().createSocket();
        s.setHandshakeTimeout(mHandshakeTimeoutMillis);
        return s;
    }

    @Override
    public Socket createSocket(InetAddress addr, int port, InetAddress localAddr, int localPort)
            throws IOException {
        OpenSSLSocketImpl s = (OpenSSLSocketImpl) getDelegate().createSocket(
                addr, port, localAddr, localPort);
        s.setHandshakeTimeout(mHandshakeTimeoutMillis);
        return s;
    }

    @Override
    public Socket createSocket(InetAddress addr, int port) throws IOException {
        OpenSSLSocketImpl s = (OpenSSLSocketImpl) getDelegate().createSocket(addr, port);
        s.setHandshakeTimeout(mHandshakeTimeoutMillis);
        return s;
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localAddr, int localPort)
            throws IOException {
        OpenSSLSocketImpl s = (OpenSSLSocketImpl) getDelegate().createSocket(
                host, port, localAddr, localPort);
        s.setHandshakeTimeout(mHandshakeTimeoutMillis);
        return s;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        OpenSSLSocketImpl s = (OpenSSLSocketImpl) getDelegate().createSocket(host, port);
        s.setHandshakeTimeout(mHandshakeTimeoutMillis);
        return s;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return getDelegate().getSupportedCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return getDelegate().getSupportedCipherSuites();
    }
}
