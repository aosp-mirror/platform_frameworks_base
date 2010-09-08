/*
 * Copyright (C) 2010 The Android Open Source Project
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

package coretestutils.http;

import static coretestutils.http.MockWebServer.ASCII;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.util.Log;

/**
 * A scripted response to be replayed by the mock web server.
 */
public class MockResponse {
    private static final byte[] EMPTY_BODY = new byte[0];
    static final String LOG_TAG = "coretestutils.http.MockResponse";

    private String status = "HTTP/1.1 200 OK";
    private Map<String, String> headers = new HashMap<String, String>();
    private byte[] body = EMPTY_BODY;
    private boolean closeConnectionAfter = false;
    private String closeConnectionAfterHeader = null;
    private int closeConnectionAfterXBytes = -1;
    private int pauseConnectionAfterXBytes = -1;
    private File bodyExternalFile = null;

    public MockResponse() {
        addHeader("Content-Length", 0);
    }

    /**
     * Returns the HTTP response line, such as "HTTP/1.1 200 OK".
     */
    public String getStatus() {
        return status;
    }

    public MockResponse setResponseCode(int code) {
        this.status = "HTTP/1.1 " + code + " OK";
        return this;
    }

    /**
     * Returns the HTTP headers, such as "Content-Length: 0".
     */
    public List<String> getHeaders() {
        List<String> headerStrings = new ArrayList<String>();
        for (String header : headers.keySet()) {
            headerStrings.add(header + ": " + headers.get(header));
        }
        return headerStrings;
    }

    public MockResponse addHeader(String header, String value) {
        headers.put(header.toLowerCase(), value);
        return this;
    }

    public MockResponse addHeader(String header, long value) {
        return addHeader(header, Long.toString(value));
    }

    public MockResponse removeHeader(String header) {
        headers.remove(header.toLowerCase());
        return this;
    }

    /**
     * Returns true if the body should come from an external file, false otherwise.
     */
    private boolean bodyIsExternal() {
        return bodyExternalFile != null;
    }

    /**
     * Returns an input stream containing the raw HTTP payload.
     */
    public InputStream getBody() {
        if (bodyIsExternal()) {
            try {
                return new FileInputStream(bodyExternalFile);
            } catch (FileNotFoundException e) {
                Log.e(LOG_TAG, "File not found: " + bodyExternalFile.getAbsolutePath());
            }
        }
        return new ByteArrayInputStream(this.body);
    }

    public MockResponse setBody(File body) {
        addHeader("Content-Length", body.length());
        this.bodyExternalFile = body;
        return this;
    }

    public MockResponse setBody(byte[] body) {
        addHeader("Content-Length", body.length);
        this.body = body;
        return this;
    }

    public MockResponse setBody(String body) {
        try {
            return setBody(body.getBytes(ASCII));
        } catch (UnsupportedEncodingException e) {
            throw new AssertionError();
        }
    }

    /**
     * Sets the body as chunked.
     *
     * Currently chunked body is not supported for external files as bodies.
     */
    public MockResponse setChunkedBody(byte[] body, int maxChunkSize) throws IOException {
        addHeader("Transfer-encoding", "chunked");

        ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
        int pos = 0;
        while (pos < body.length) {
            int chunkSize = Math.min(body.length - pos, maxChunkSize);
            bytesOut.write(Integer.toHexString(chunkSize).getBytes(ASCII));
            bytesOut.write("\r\n".getBytes(ASCII));
            bytesOut.write(body, pos, chunkSize);
            bytesOut.write("\r\n".getBytes(ASCII));
            pos += chunkSize;
        }
        bytesOut.write("0\r\n".getBytes(ASCII));
        this.body = bytesOut.toByteArray();
        return this;
    }

    public MockResponse setChunkedBody(String body, int maxChunkSize) throws IOException {
        return setChunkedBody(body.getBytes(ASCII), maxChunkSize);
    }

    @Override public String toString() {
        return status;
    }

    public boolean shouldCloseConnectionAfter() {
        return closeConnectionAfter;
    }

    public MockResponse setCloseConnectionAfter(boolean closeConnectionAfter) {
        this.closeConnectionAfter = closeConnectionAfter;
        return this;
    }

    /**
     * Sets the header after which sending the server should close the connection.
     */
    public MockResponse setCloseConnectionAfterHeader(String header) {
        closeConnectionAfterHeader = header;
        setCloseConnectionAfter(true);
        return this;
    }

    /**
     * Returns the header after which sending the server should close the connection.
     */
    public String getCloseConnectionAfterHeader() {
        return closeConnectionAfterHeader;
    }

    /**
     * Sets the number of bytes in the body to send before which the server should close the
     * connection. Set to -1 to unset and send the entire body (default).
     */
    public MockResponse setCloseConnectionAfterXBytes(int position) {
        closeConnectionAfterXBytes = position;
        setCloseConnectionAfter(true);
        return this;
    }

    /**
     * Returns the number of bytes in the body to send before which the server should close the
     * connection. Returns -1 if the entire body should be sent (default).
     */
    public int getCloseConnectionAfterXBytes() {
        return closeConnectionAfterXBytes;
    }

    /**
     * Sets the number of bytes in the body to send before which the server should pause the
     * connection (stalls in sending data). Only one pause per response is supported.
     * Set to -1 to unset pausing (default).
     */
    public MockResponse setPauseConnectionAfterXBytes(int position) {
        pauseConnectionAfterXBytes = position;
        return this;
    }

    /**
     * Returns the number of bytes in the body to send before which the server should pause the
     * connection (stalls in sending data). (Returns -1 if it should not pause).
     */
    public int getPauseConnectionAfterXBytes() {
        return pauseConnectionAfterXBytes;
    }

    /**
     * Returns true if this response is flagged to pause the connection mid-stream, false otherwise
     */
    public boolean getShouldPause() {
        return (pauseConnectionAfterXBytes != -1);
    }

    /**
     * Returns true if this response is flagged to close the connection mid-stream, false otherwise
     */
    public boolean getShouldClose() {
        return (closeConnectionAfterXBytes != -1);
    }
}
