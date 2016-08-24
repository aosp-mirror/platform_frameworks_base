package com.android.hotspot2.utils;

import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class HTTPRequest implements HTTPMessage {
    private static final Charset HeaderCharset = StandardCharsets.US_ASCII;
    private static final int HTTPS_PORT = 443;

    private final String mMethodLine;
    private final Map<String, String> mHeaderFields;
    private final byte[] mBody;

    public HTTPRequest(Method method, URL url) {
        this(null, null, method, url, null, false);
    }

    public HTTPRequest(String payload, Charset charset, Method method, URL url, String contentType,
                       boolean base64) {
        mBody = payload != null ? payload.getBytes(charset) : null;

        mHeaderFields = new LinkedHashMap<>();
        mHeaderFields.put(AgentHeader, AgentName);
        if (url.getPort() != HTTPS_PORT) {
            mHeaderFields.put(HostHeader, url.getHost() + ':' + url.getPort());
        } else {
            mHeaderFields.put(HostHeader, url.getHost());
        }
        mHeaderFields.put(AcceptHeader, "*/*");
        if (payload != null) {
            if (base64) {
                mHeaderFields.put(ContentTypeHeader, contentType);
                mHeaderFields.put(ContentEncodingHeader, "base64");
            } else {
                mHeaderFields.put(ContentTypeHeader, contentType + "; charset=" +
                        charset.displayName().toLowerCase());
            }
            mHeaderFields.put(ContentLengthHeader, Integer.toString(mBody.length));
        }

        mMethodLine = method.name() + ' ' + url.getPath() + ' ' + HTTPVersion + CRLF;
    }

    public void doAuthenticate(HTTPResponse httpResponse, String userName, byte[] password,
                               URL url, int sequence) throws IOException, GeneralSecurityException {
        mHeaderFields.put(HTTPMessage.AuthorizationHeader,
                generateAuthAnswer(httpResponse, userName, password, url, sequence));
    }

    private static String generateAuthAnswer(HTTPResponse httpResponse, String userName,
                                             byte[] password, URL url, int sequence)
            throws IOException, GeneralSecurityException {

        String authRequestLine = httpResponse.getHeader(HTTPMessage.AuthHeader);
        if (authRequestLine == null) {
            throw new IOException("Missing auth line");
        }
        String[] tokens = authRequestLine.split("[ ,]+");
        //System.out.println("Tokens: " + Arrays.toString(tokens));
        if (tokens.length < 3 || !tokens[0].equalsIgnoreCase("digest")) {
            throw new IOException("Bad " + HTTPMessage.AuthHeader + ": '" + authRequestLine + "'");
        }

        Map<String, String> itemMap = new HashMap<>();
        for (int n = 1; n < tokens.length; n++) {
            String s = tokens[n];
            int split = s.indexOf('=');
            if (split < 0) {
                continue;
            }
            itemMap.put(s.substring(0, split).trim().toLowerCase(),
                    unquote(s.substring(split + 1).trim()));
        }

        Set<String> qops = splitValue(itemMap.remove("qop"));
        if (!qops.contains("auth")) {
            throw new IOException("Unsupported quality of protection value(s): '" + qops + "'");
        }
        String algorithm = itemMap.remove("algorithm");
        if (algorithm != null && !algorithm.equalsIgnoreCase("md5")) {
            throw new IOException("Unsupported algorithm: '" + algorithm + "'");
        }
        String realm = itemMap.remove("realm");
        String nonceText = itemMap.remove("nonce");
        if (realm == null || nonceText == null) {
            throw new IOException("realm and/or nonce missing: '" + authRequestLine + "'");
        }
        //System.out.println("Remaining tokens: " + itemMap);

        byte[] cnonce = new byte[16];
        SecureRandom prng = new SecureRandom();
        prng.nextBytes(cnonce);

        /*
         * H(data) = MD5(data)
         * KD(secret, data) = H(concat(secret, ":", data))
         *
         * A1 = unq(username-value) ":" unq(realm-value) ":" passwd
         * A2 = Method ":" digest-uri-value
         *
         * response = KD ( H(A1), unq(nonce-value) ":" nc-value ":" unq(cnonce-value) ":"
          * unq(qop-value) ":" H(A2) )
         */

        String nc = String.format("%08d", sequence);

        /*
         * This bears witness to the ingenuity of the emerging "web generation" and the authors of
         * RFC-2617: Strings are treated as a sequence of octets in blind ignorance of character
         * encoding, whereas octets strings apparently aren't "good enough" and expanded to
         * "hex strings"...
         * As a wild guess I apply UTF-8 below.
         */
        String passwordString = new String(password, StandardCharsets.UTF_8);
        String cNonceString = bytesToHex(cnonce);

        byte[] a1 = hash(userName, realm, passwordString);
        byte[] a2 = hash("POST", url.getPath());
        byte[] response = hash(a1, nonceText, nc, cNonceString, "auth", a2);

        StringBuilder authLine = new StringBuilder();
        authLine.append("Digest ")
                .append("username=\"").append(userName).append("\", ")
                .append("realm=\"").append(realm).append("\", ")
                .append("nonce=\"").append(nonceText).append("\", ")
                .append("uri=\"").append(url.getPath()).append("\", ")
                .append("qop=\"auth\", ")
                .append("nc=").append(nc).append(", ")
                .append("cnonce=\"").append(cNonceString).append("\", ")
                .append("response=\"").append(bytesToHex(response)).append('"');
        String opaque = itemMap.get("opaque");
        if (opaque != null) {
            authLine.append(", \"").append(opaque).append('"');
        }

        return authLine.toString();
    }

    private static Set<String> splitValue(String value) {
        Set<String> result = new HashSet<>();
        if (value != null) {
            for (String s : value.split(",")) {
                result.add(s.trim());
            }
        }
        return result;
    }

    private static byte[] hash(Object... objects) throws GeneralSecurityException {
        MessageDigest hash = MessageDigest.getInstance("MD5");

        //System.out.println("<Hash>");
        boolean first = true;
        for (Object object : objects) {
            byte[] octets;
            if (object.getClass() == String.class) {
                //System.out.println("+= '" + object + "'");
                octets = ((String) object).getBytes(StandardCharsets.UTF_8);
            } else {
                octets = bytesToHexBytes((byte[]) object);
                //System.out.println("+= " + new String(octets, StandardCharsets.ISO_8859_1));
            }
            if (first) {
                first = false;
            } else {
                hash.update((byte) ':');
            }
            hash.update(octets);
        }
        //System.out.println("</Hash>");
        return hash.digest();
    }

    private static String unquote(String s) {
        return s.startsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private static byte[] bytesToHexBytes(byte[] octets) {
        return bytesToHex(octets).getBytes(StandardCharsets.ISO_8859_1);
    }

    private static String bytesToHex(byte[] octets) {
        StringBuilder sb = new StringBuilder(octets.length * 2);
        for (byte b : octets) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private byte[] buildHeader() {
        StringBuilder header = new StringBuilder();
        header.append(mMethodLine);
        for (Map.Entry<String, String> entry : mHeaderFields.entrySet()) {
            header.append(entry.getKey()).append(": ").append(entry.getValue()).append(CRLF);
        }
        header.append(CRLF);

        //System.out.println("HTTP Request:");
        StringBuilder sb2 = new StringBuilder();
        sb2.append(header);
        if (mBody != null) {
            sb2.append(new String(mBody, StandardCharsets.ISO_8859_1));
        }
        //System.out.println(sb2);
        //System.out.println("End HTTP Request.");

        return header.toString().getBytes(HeaderCharset);
    }

    public void send(OutputStream out) throws IOException {
        out.write(buildHeader());
        if (mBody != null) {
            out.write(mBody);
        }
        out.flush();
    }

    @Override
    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(mHeaderFields);
    }

    @Override
    public InputStream getPayloadStream() {
        return mBody != null ? new ByteArrayInputStream(mBody) : null;
    }

    @Override
    public ByteBuffer getPayload() {
        return mBody != null ? ByteBuffer.wrap(mBody) : null;
    }

    @Override
    public ByteBuffer getBinaryPayload() {
        byte[] binary = Base64.decode(mBody, Base64.DEFAULT);
        return ByteBuffer.wrap(binary);
    }

    public static void main(String[] args) throws GeneralSecurityException {
        test("Mufasa", "testrealm@host.com", "Circle Of Life", "GET", "/dir/index.html",
                "dcd98b7102dd2f0e8b11d0f600bfb0c093", "0a4f113b", "00000001", "auth",
                "6629fae49393a05397450978507c4ef1");

        // WWW-Authenticate: Digest realm="wi-fi.org", qop="auth",
        // nonce="MTQzMTg1MTIxMzUyNzo0OGFhNGU5ZTg4Y2M4YmFhYzM2MzAwZDg5MGNiYTJlNw=="
        // Authorization: Digest
        //  username="1c7e1582-604d-4c00-b411-bb73735cbcb0"
        //  realm="wi-fi.org"
        //  nonce="MTQzMTg1MTIxMzUyNzo0OGFhNGU5ZTg4Y2M4YmFhYzM2MzAwZDg5MGNiYTJlNw=="
        //  uri="/.well-known/est/simpleenroll"
        //  cnonce="NzA3NDk0"
        //  nc=00000001
        //  qop="auth"
        //  response="2c485d24076452e712b77f4e70776463"

        String nonce = "MTQzMTg1MTIxMzUyNzo0OGFhNGU5ZTg4Y2M4YmFhYzM2MzAwZDg5MGNiYTJlNw==";
        String cnonce = "NzA3NDk0";
        test("1c7e1582-604d-4c00-b411-bb73735cbcb0", "wi-fi.org", "ruckus1234", "POST",
                "/.well-known/est/simpleenroll",
                /*new String(Base64.getDecoder().decode(nonce), StandardCharsets.ISO_8859_1)*/
                nonce,
                /*new String(Base64.getDecoder().decode(cnonce), StandardCharsets.ISO_8859_1)*/
                cnonce, "00000001", "auth", "2c485d24076452e712b77f4e70776463");
    }

    private static void test(String user, String realm, String password, String method, String path,
                             String nonce, String cnonce, String nc, String qop, String expect)
            throws GeneralSecurityException {
        byte[] a1 = hash(user, realm, password);
        System.out.println("HA1: " + bytesToHex(a1));
        byte[] a2 = hash(method, path);
        System.out.println("HA2: " + bytesToHex(a2));
        byte[] response = hash(a1, nonce, nc, cnonce, qop, a2);

        StringBuilder authLine = new StringBuilder();
        String responseString = bytesToHex(response);
        authLine.append("Digest ")
                .append("username=\"").append(user).append("\", ")
                .append("realm=\"").append(realm).append("\", ")
                .append("nonce=\"").append(nonce).append("\", ")
                .append("uri=\"").append(path).append("\", ")
                .append("qop=\"").append(qop).append("\", ")
                .append("nc=").append(nc).append(", ")
                .append("cnonce=\"").append(cnonce).append("\", ")
                .append("response=\"").append(responseString).append('"');

        System.out.println(authLine);
        System.out.println("Success: " + responseString.equals(expect));
    }
}