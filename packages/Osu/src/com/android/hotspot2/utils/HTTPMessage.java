package com.android.hotspot2.utils;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Map;

public interface HTTPMessage {
    public static final String HTTPVersion = "HTTP/1.1";
    public static final String AgentHeader = "User-Agent";
    public static final String AgentName = "Android HS Client";
    public static final String HostHeader = "Host";
    public static final String AcceptHeader = "Accept";
    public static final String LengthHeader = "Content-Length";
    public static final String ContentTypeHeader = "Content-Type";
    public static final String ContentLengthHeader = "Content-Length";
    public static final String ContentEncodingHeader = "Content-Transfer-Encoding";
    public static final String AuthHeader = "WWW-Authenticate";
    public static final String AuthorizationHeader = "Authorization";

    public static final String ContentTypeSOAP = "application/soap+xml";

    public static final int RX_BUFFER = 32768;
    public static final String CRLF = "\r\n";
    public static final int BODY_SEPARATOR = 0x0d0a0d0a;
    public static final int BODY_SEPARATOR_LENGTH = 4;

    public enum Method {GET, PUT, POST}

    public Map<String, String> getHeaders();

    public InputStream getPayloadStream();

    public ByteBuffer getPayload();

    public ByteBuffer getBinaryPayload();
}
