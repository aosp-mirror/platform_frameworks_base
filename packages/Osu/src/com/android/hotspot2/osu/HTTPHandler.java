package com.android.hotspot2.osu;

import android.util.Log;

import com.android.hotspot2.utils.HTTPMessage;
import com.android.hotspot2.utils.HTTPRequest;
import com.android.hotspot2.utils.HTTPResponse;

import com.android.org.conscrypt.OpenSSLSocketImpl;

import org.xml.sax.SAXException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;
import javax.xml.parsers.ParserConfigurationException;

public class HTTPHandler implements AutoCloseable {
    private final Charset mCharset;
    private final OSUSocketFactory mSocketFactory;
    private Socket mSocket;
    private BufferedOutputStream mOut;
    private BufferedInputStream mIn;
    private final String mUser;
    private final byte[] mPassword;
    private boolean mHTTPAuthPerformed;
    private static final AtomicInteger sSequence = new AtomicInteger();

    public HTTPHandler(Charset charset, OSUSocketFactory socketFactory) throws IOException {
        this(charset, socketFactory, null, null);
    }

    public HTTPHandler(Charset charset, OSUSocketFactory socketFactory,
                       String user, byte[] password) throws IOException {
        mCharset = charset;
        mSocketFactory = socketFactory;
        mSocket = mSocketFactory.createSocket();
        mOut = new BufferedOutputStream(mSocket.getOutputStream());
        mIn = new BufferedInputStream(mSocket.getInputStream());
        mUser = user;
        mPassword = password;
    }

    public boolean isHTTPAuthPerformed() {
        return mHTTPAuthPerformed;
    }

    public X509Certificate getOSUCertificate(URL osu) throws GeneralSecurityException {
        return mSocketFactory.getOSUCertificate(osu);
    }

    public void renegotiate(Map<OSUCertType, List<X509Certificate>> certs, PrivateKey key)
            throws IOException {
        if (!(mSocket instanceof SSLSocket)) {
            throw new IOException("Not a TLS connection");
        }
        if (certs != null) {
            mSocketFactory.reloadKeys(certs, key);
        }
        ((SSLSocket) mSocket).startHandshake();
    }

    public byte[] getTLSUnique() throws SSLException {
        if (mSocket instanceof OpenSSLSocketImpl) {
            return ((OpenSSLSocketImpl) mSocket).getChannelId();
        }
        return null;
    }

    public OSUResponse exchangeSOAP(URL url, String message) throws IOException {
        HTTPResponse response = exchangeWithRetry(url, message, HTTPMessage.Method.POST,
                HTTPMessage.ContentTypeSOAP);
        if (response.getStatusCode() >= 300) {
            throw new IOException("Bad HTTP status code " + response.getStatusCode());
        }
        try {
            SOAPParser parser = new SOAPParser(response.getPayloadStream());
            return parser.getResponse();
        } catch (ParserConfigurationException | SAXException e) {
            ByteBuffer x = response.getPayload();
            byte[] b = new byte[x.remaining()];
            x.get(b);
            Log.w("XML", "Bad: '" + new String(b, StandardCharsets.ISO_8859_1));
            throw new IOException(e);
        }
    }

    public ByteBuffer exchangeBinary(URL url, String message, String contentType)
            throws IOException {
        HTTPResponse response =
                exchangeWithRetry(url, message, HTTPMessage.Method.POST, contentType);
        return response.getBinaryPayload();
    }

    public InputStream doGet(URL url) throws IOException {
        HTTPResponse response = exchangeWithRetry(url, null, HTTPMessage.Method.GET, null);
        return response.getPayloadStream();
    }

    public HTTPResponse doGetHTTP(URL url) throws IOException {
        return exchangeWithRetry(url, null, HTTPMessage.Method.GET, null);
    }

    private HTTPResponse exchangeWithRetry(URL url, String message, HTTPMessage.Method method,
                                           String contentType) throws IOException {
        HTTPResponse response = null;
        int retry = 0;
        for (; ; ) {
            try {
                response = httpExchange(url, message, method, contentType);
                break;
            } catch (IOException ioe) {
                close();
                retry++;
                if (retry > 3) {
                    break;
                }
                Log.d(OSUManager.TAG, "Failed HTTP exchange, retry " + retry);
                mSocket = mSocketFactory.createSocket();
                mOut = new BufferedOutputStream(mSocket.getOutputStream());
                mIn = new BufferedInputStream(mSocket.getInputStream());
            }
        }
        if (response == null) {
            throw new IOException("Failed to establish connection to peer");
        }
        return response;
    }

    private HTTPResponse httpExchange(URL url, String message, HTTPMessage.Method method,
                                      String contentType)
            throws IOException {
        HTTPRequest request = new HTTPRequest(message, mCharset, method, url, contentType, false);
        request.send(mOut);
        HTTPResponse response = new HTTPResponse(mIn);
        Log.d(OSUManager.TAG, "HTTP code " + response.getStatusCode() + ", user " + mUser +
                ", pw " + (mPassword != null ? '\'' + new String(mPassword) + '\'' : "-"));
        if (response.getStatusCode() == 401) {
            if (mUser == null) {
                throw new IOException("Missing user name for HTTP authentication");
            }
            try {
                request = new HTTPRequest(message, StandardCharsets.ISO_8859_1, method, url,
                        contentType, true);
                request.doAuthenticate(response, mUser, mPassword, url,
                        sSequence.incrementAndGet());
                request.send(mOut);
                mHTTPAuthPerformed = true;
            } catch (GeneralSecurityException gse) {
                throw new IOException(gse);
            }

            response = new HTTPResponse(mIn);
        }
        return response;
    }

    public void close() throws IOException {
        mSocket.shutdownInput();
        mSocket.shutdownOutput();
        mSocket.close();
        mIn.close();
        mOut.close();
    }
}
