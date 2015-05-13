/*
 * $HeadURL: http://svn.apache.org/repos/asf/httpcomponents/httpclient/trunk/module-client/src/main/java/org/apache/http/conn/ssl/SSLSocketFactory.java $
 * $Revision: 659194 $
 * $Date: 2008-05-22 11:33:47 -0700 (Thu, 22 May 2008) $
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.conn.ssl;

import org.apache.http.conn.scheme.HostNameResolver;
import org.apache.http.conn.scheme.LayeredSocketFactory;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;

/**
 * Layered socket factory for TLS/SSL connections, based on JSSE.
 *.
 * <p>
 * SSLSocketFactory can be used to validate the identity of the HTTPS 
 * server against a list of trusted certificates and to authenticate to
 * the HTTPS server using a private key. 
 * </p>
 * 
 * <p>
 * SSLSocketFactory will enable server authentication when supplied with
 * a {@link KeyStore truststore} file containg one or several trusted
 * certificates. The client secure socket will reject the connection during
 * the SSL session handshake if the target HTTPS server attempts to
 * authenticate itself with a non-trusted certificate.
 * </p>
 * 
 * <p>
 * Use JDK keytool utility to import a trusted certificate and generate a truststore file:    
 *    <pre>
 *     keytool -import -alias "my server cert" -file server.crt -keystore my.truststore
 *    </pre>
 * </p>
 * 
 * <p>
 * SSLSocketFactory will enable client authentication when supplied with
 * a {@link KeyStore keystore} file containg a private key/public certificate
 * pair. The client secure socket will use the private key to authenticate
 * itself to the target HTTPS server during the SSL session handshake if
 * requested to do so by the server.
 * The target HTTPS server will in its turn verify the certificate presented
 * by the client in order to establish client's authenticity
 * </p>
 * 
 * <p>
 * Use the following sequence of actions to generate a keystore file
 * </p>
 *   <ul>
 *     <li>
 *      <p>
 *      Use JDK keytool utility to generate a new key
 *      <pre>keytool -genkey -v -alias "my client key" -validity 365 -keystore my.keystore</pre>
 *      For simplicity use the same password for the key as that of the keystore
 *      </p>
 *     </li>
 *     <li>
 *      <p>
 *      Issue a certificate signing request (CSR)
 *      <pre>keytool -certreq -alias "my client key" -file mycertreq.csr -keystore my.keystore</pre>
 *     </p>
 *     </li>
 *     <li>
 *      <p>
 *      Send the certificate request to the trusted Certificate Authority for signature. 
 *      One may choose to act as her own CA and sign the certificate request using a PKI 
 *      tool, such as OpenSSL.
 *      </p>
 *     </li>
 *     <li>
 *      <p>
 *       Import the trusted CA root certificate
 *       <pre>keytool -import -alias "my trusted ca" -file caroot.crt -keystore my.keystore</pre> 
 *      </p>
 *     </li>
 *     <li>
 *      <p>
 *       Import the PKCS#7 file containg the complete certificate chain
 *       <pre>keytool -import -alias "my client key" -file mycert.p7 -keystore my.keystore</pre> 
 *      </p>
 *     </li>
 *     <li>
 *      <p>
 *       Verify the content the resultant keystore file
 *       <pre>keytool -list -v -keystore my.keystore</pre> 
 *      </p>
 *     </li>
 *   </ul>
 * @author <a href="mailto:oleg at ural.ru">Oleg Kalnichevski</a>
 * @author Julius Davies
 *
 * @deprecated Please use {@link java.net.URL#openConnection} instead.
 *     Please visit <a href="http://android-developers.blogspot.com/2011/09/androids-http-clients.html">this webpage</a>
 *     for further details.
 */
@Deprecated
public class SSLSocketFactory implements LayeredSocketFactory {

    public static final String TLS   = "TLS";
    public static final String SSL   = "SSL";
    public static final String SSLV2 = "SSLv2";
    
    public static final X509HostnameVerifier ALLOW_ALL_HOSTNAME_VERIFIER 
        = new AllowAllHostnameVerifier();
    
    public static final X509HostnameVerifier BROWSER_COMPATIBLE_HOSTNAME_VERIFIER 
        = new BrowserCompatHostnameVerifier();
    
    public static final X509HostnameVerifier STRICT_HOSTNAME_VERIFIER 
        = new StrictHostnameVerifier();

    /*
     * Put defaults into holder class to avoid class preloading creating an
     * instance of the classes referenced.
     */
    private static class NoPreloadHolder {
        /**
         * The factory using the default JVM settings for secure connections.
         */
        private static final SSLSocketFactory DEFAULT_FACTORY = new SSLSocketFactory();
    }

    /**
     * Gets an singleton instance of the SSLProtocolSocketFactory.
     * @return a SSLProtocolSocketFactory
     */
    public static SSLSocketFactory getSocketFactory() {
        return NoPreloadHolder.DEFAULT_FACTORY;
    }

    private final SSLContext sslcontext;
    private final javax.net.ssl.SSLSocketFactory socketfactory;
    private final HostNameResolver nameResolver;
    private X509HostnameVerifier hostnameVerifier = BROWSER_COMPATIBLE_HOSTNAME_VERIFIER;

    public SSLSocketFactory(
        String algorithm, 
        final KeyStore keystore, 
        final String keystorePassword, 
        final KeyStore truststore,
        final SecureRandom random,
        final HostNameResolver nameResolver) 
        throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException
    {
        super();
        if (algorithm == null) {
            algorithm = TLS;
        }
        KeyManager[] keymanagers = null;
        if (keystore != null) {
            keymanagers = createKeyManagers(keystore, keystorePassword);
        }
        TrustManager[] trustmanagers = null;
        if (truststore != null) {
            trustmanagers = createTrustManagers(truststore);
        }
        this.sslcontext = SSLContext.getInstance(algorithm);
        this.sslcontext.init(keymanagers, trustmanagers, random);
        this.socketfactory = this.sslcontext.getSocketFactory();
        this.nameResolver = nameResolver;
    }

    public SSLSocketFactory(
            final KeyStore keystore, 
            final String keystorePassword, 
            final KeyStore truststore) 
            throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException
    {
        this(TLS, keystore, keystorePassword, truststore, null, null);
    }

    public SSLSocketFactory(final KeyStore keystore, final String keystorePassword) 
            throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException
    {
        this(TLS, keystore, keystorePassword, null, null, null);
    }

    public SSLSocketFactory(final KeyStore truststore) 
            throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException, UnrecoverableKeyException
    {
        this(TLS, null, null, truststore, null, null);
    }

    /**
     * Constructs an HttpClient SSLSocketFactory backed by the given JSSE
     * SSLSocketFactory.
     *
     * @hide
     */
    public SSLSocketFactory(javax.net.ssl.SSLSocketFactory socketfactory) {
        super();
        this.sslcontext = null;
        this.socketfactory = socketfactory;
        this.nameResolver = null;
    }

    /**
     * Creates the default SSL socket factory.
     * This constructor is used exclusively to instantiate the factory for
     * {@link #getSocketFactory getSocketFactory}.
     */
    private SSLSocketFactory() {
        super();
        this.sslcontext = null;
        this.socketfactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        this.nameResolver = null;
    }

    private static KeyManager[] createKeyManagers(final KeyStore keystore, final String password)
        throws KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
        if (keystore == null) {
            throw new IllegalArgumentException("Keystore may not be null");
        }
        KeyManagerFactory kmfactory = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm());
        kmfactory.init(keystore, password != null ? password.toCharArray(): null);
        return kmfactory.getKeyManagers(); 
    }

    private static TrustManager[] createTrustManagers(final KeyStore keystore)
        throws KeyStoreException, NoSuchAlgorithmException { 
        if (keystore == null) {
            throw new IllegalArgumentException("Keystore may not be null");
        }
        TrustManagerFactory tmfactory = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
        tmfactory.init(keystore);
        return tmfactory.getTrustManagers();
    }


    // non-javadoc, see interface org.apache.http.conn.SocketFactory
    public Socket createSocket()
        throws IOException {

        // the cast makes sure that the factory is working as expected
        return (SSLSocket) this.socketfactory.createSocket();
    }


    // non-javadoc, see interface org.apache.http.conn.SocketFactory
    public Socket connectSocket(
        final Socket sock,
        final String host,
        final int port,
        final InetAddress localAddress,
        int localPort,
        final HttpParams params
    ) throws IOException {

        if (host == null) {
            throw new IllegalArgumentException("Target host may not be null.");
        }
        if (params == null) {
            throw new IllegalArgumentException("Parameters may not be null.");
        }

        SSLSocket sslsock = (SSLSocket)
            ((sock != null) ? sock : createSocket());

        if ((localAddress != null) || (localPort > 0)) {

            // we need to bind explicitly
            if (localPort < 0)
                localPort = 0; // indicates "any"

            InetSocketAddress isa =
                new InetSocketAddress(localAddress, localPort);
            sslsock.bind(isa);
        }

        int connTimeout = HttpConnectionParams.getConnectionTimeout(params);
        int soTimeout = HttpConnectionParams.getSoTimeout(params);

        InetSocketAddress remoteAddress;
        if (this.nameResolver != null) {
            remoteAddress = new InetSocketAddress(this.nameResolver.resolve(host), port); 
        } else {
            remoteAddress = new InetSocketAddress(host, port);            
        }
        
        sslsock.connect(remoteAddress, connTimeout);

        sslsock.setSoTimeout(soTimeout);
        try {
            // BEGIN android-added
            /*
             * Make sure we have started the handshake before verifying.
             * Otherwise when we go to the hostname verifier, it directly calls
             * SSLSocket#getSession() which swallows SSL handshake errors.
             */
            sslsock.startHandshake();
            // END android-added
            hostnameVerifier.verify(host, sslsock);
            // verifyHostName() didn't blowup - good!
        } catch (IOException iox) {
            // close the socket before re-throwing the exception
            try { sslsock.close(); } catch (Exception x) { /*ignore*/ }
            throw iox;
        }

        return sslsock;
    }


    /**
     * Checks whether a socket connection is secure.
     * This factory creates TLS/SSL socket connections
     * which, by default, are considered secure.
     * <br/>
     * Derived classes may override this method to perform
     * runtime checks, for example based on the cypher suite.
     *
     * @param sock      the connected socket
     *
     * @return  <code>true</code>
     *
     * @throws IllegalArgumentException if the argument is invalid
     */
    public boolean isSecure(Socket sock)
        throws IllegalArgumentException {

        if (sock == null) {
            throw new IllegalArgumentException("Socket may not be null.");
        }
        // This instanceof check is in line with createSocket() above.
        if (!(sock instanceof SSLSocket)) {
            throw new IllegalArgumentException
                ("Socket not created by this factory.");
        }
        // This check is performed last since it calls the argument object.
        if (sock.isClosed()) {
            throw new IllegalArgumentException("Socket is closed.");
        }

        return true;

    } // isSecure


    // non-javadoc, see interface LayeredSocketFactory
    public Socket createSocket(
        final Socket socket,
        final String host,
        final int port,
        final boolean autoClose
    ) throws IOException, UnknownHostException {
        SSLSocket sslSocket = (SSLSocket) this.socketfactory.createSocket(
              socket,
              host,
              port,
              autoClose
        );
        // BEGIN android-added
        /*
         * Make sure we have started the handshake before verifying.
         * Otherwise when we go to the hostname verifier, it directly calls
         * SSLSocket#getSession() which swallows SSL handshake errors.
         */
        sslSocket.startHandshake();
        // END android-added
        hostnameVerifier.verify(host, sslSocket);
        // verifyHostName() didn't blowup - good!
        return sslSocket;
    }

    public void setHostnameVerifier(X509HostnameVerifier hostnameVerifier) {
        if ( hostnameVerifier == null ) {
            throw new IllegalArgumentException("Hostname verifier may not be null");
        }
        this.hostnameVerifier = hostnameVerifier;
    }

    public X509HostnameVerifier getHostnameVerifier() {
        return hostnameVerifier;
    }

}
