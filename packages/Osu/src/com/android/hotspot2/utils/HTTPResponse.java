package com.android.hotspot2.utils;

import android.util.Base64;
import android.util.Log;

import com.android.hotspot2.osu.OSUManager;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class HTTPResponse implements HTTPMessage {
    private final int mStatusCode;
    private final Map<String, String> mHeaders = new LinkedHashMap<>();
    private final ByteBuffer mBody;

    private static final String csIndicator = "charset=";

    public HTTPResponse(InputStream in) throws IOException {
        int expected = Integer.MAX_VALUE;
        int offset = 0;
        int body = -1;
        byte[] input = new byte[RX_BUFFER];

        int statusCode = -1;
        int bodyPattern = 0;

        while (offset < expected) {
            int amount = in.read(input, offset, input.length - offset);
            Log.d(OSUManager.TAG, String.format("Reading into %d from %d, amount %d -> %d",
                    input.length, offset, input.length - offset, amount));
            if (amount < 0) {
                throw new EOFException();
            }
            //Log.d("ZXZ", "HTTP response: '"
            // + new String(input, 0, offset + amount, StandardCharsets.ISO_8859_1));

            if (body < 0) {
                for (int n = offset; n < offset + amount; n++) {
                    bodyPattern = (bodyPattern << 8) | (input[n] & 0xff);
                    if (bodyPattern == 0x0d0a0d0a) {
                        body = n + 1;
                        statusCode = parseHeader(input, body, mHeaders);
                        expected = calculateLength(body, mHeaders);
                        if (expected > input.length) {
                            input = Arrays.copyOf(input, expected);
                        }
                        break;
                    }
                }
            }
            offset += amount;
            if (offset < expected && offset == input.length) {
                input = Arrays.copyOf(input, input.length * 2);
            }
        }
        mStatusCode = statusCode;
        mBody = ByteBuffer.wrap(input, body, expected - body);
    }

    private static int parseHeader(byte[] input, int body, Map<String, String> headers)
            throws IOException {
        String headerText = new String(input, 0, body - BODY_SEPARATOR_LENGTH,
                StandardCharsets.ISO_8859_1);
        //System.out.println("Received header: " + headerText);
        Iterator<String> headerLines = Arrays.asList(headerText.split(CRLF)).iterator();
        if (!headerLines.hasNext()) {
            throw new IOException("Bad HTTP Request");
        }

        int statusCode;
        String line0 = headerLines.next();
        String[] status = line0.split(" ");
        if (status.length != 3 || !"HTTP/1.1".equals(status[0])) {
            throw new IOException("Bad HTTP Result: " + line0);
        }
        try {
            statusCode = Integer.parseInt(status[1].trim());
        } catch (NumberFormatException nfe) {
            throw new IOException("Bad HTTP header line: '" + line0 + "'");
        }

        while (headerLines.hasNext()) {
            String line = headerLines.next();
            int keyEnd = line.indexOf(':');
            if (keyEnd < 0) {
                throw new IOException("Bad header line: '" + line + "'");
            }
            String key = line.substring(0, keyEnd).trim();
            String value = line.substring(keyEnd + 1).trim();
            headers.put(key, value);
        }
        return statusCode;
    }

    private static int calculateLength(int body, Map<String, String> headers) throws IOException {
        String contentLength = headers.get(LengthHeader);
        if (contentLength == null) {
            throw new IOException("No " + LengthHeader);
        }
        try {
            return body + Integer.parseInt(contentLength);
        } catch (NumberFormatException nfe) {
            throw new IOException("Bad " + LengthHeader + ": " + contentLength);
        }
    }

    public int getStatusCode() {
        return mStatusCode;
    }

    @Override
    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(mHeaders);
    }

    public String getHeader(String key) {
        return mHeaders.get(key);
    }

    @Override
    public InputStream getPayloadStream() {
        return new ByteArrayInputStream(mBody.array(), mBody.position(),
                mBody.limit() - mBody.position());
    }

    @Override
    public ByteBuffer getPayload() {
        return mBody.duplicate();
    }

    @Override
    public ByteBuffer getBinaryPayload() {
        byte[] data = new byte[mBody.remaining()];
        mBody.duplicate().get(data);
        byte[] binary = Base64.decode(data, Base64.DEFAULT);
        return ByteBuffer.wrap(binary);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Status: ").append(mStatusCode).append(CRLF);
        for (Map.Entry<String, String> entry : mHeaders.entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append(CRLF);
        }
        sb.append(CRLF);
        Charset charset;
        try {
            charset = Charset.forName(getCharset());
        } catch (IllegalArgumentException iae) {
            charset = StandardCharsets.ISO_8859_1;
        }
        sb.append(new String(mBody.array(), mBody.position(),
                mBody.limit() - mBody.position(), charset));
        return sb.toString();
    }

    public String getCharset() {
        String contentType = mHeaders.get(ContentTypeHeader);
        if (contentType == null) {
            return null;
        }
        int csPos = contentType.indexOf(csIndicator);
        return csPos < 0 ? null : contentType.substring(csPos + csIndicator.length()).trim();
    }

    private static boolean equals(byte[] b1, int offset, byte[] pattern) {
        for (int n = 0; n < pattern.length; n++) {
            if (b1[n + offset] != pattern[n]) {
                return false;
            }
        }
        return true;
    }
}
