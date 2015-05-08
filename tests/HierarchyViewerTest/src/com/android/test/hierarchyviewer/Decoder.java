package com.android.test.hierarchyviewer;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

public class Decoder {
    // Prefixes for simple primitives. These match the JNI definitions.
    public static final byte SIG_BOOLEAN = 'Z';
    public static final byte SIG_BYTE = 'B';
    public static final byte SIG_SHORT = 'S';
    public static final byte SIG_INT = 'I';
    public static final byte SIG_LONG = 'J';
    public static final byte SIG_FLOAT = 'F';
    public static final byte SIG_DOUBLE = 'D';

    // Prefixes for some commonly used objects
    public static final byte SIG_STRING = 'R';

    public static final byte SIG_MAP = 'M'; // a map with an short key
    public static final short SIG_END_MAP = 0;

    private final ByteBuffer mBuf;

    public Decoder(byte[] buf) {
        this(ByteBuffer.wrap(buf));
    }

    public Decoder(ByteBuffer buf) {
        mBuf = buf;
    }

    public boolean hasRemaining() {
        return mBuf.hasRemaining();
    }

    public Object readObject() {
        byte sig = mBuf.get();

        switch (sig) {
            case SIG_BOOLEAN:
                return mBuf.get() == 0 ? Boolean.FALSE : Boolean.TRUE;
            case SIG_BYTE:
                return mBuf.get();
            case SIG_SHORT:
                return mBuf.getShort();
            case SIG_INT:
                return mBuf.getInt();
            case SIG_LONG:
                return mBuf.getLong();
            case SIG_FLOAT:
                return mBuf.getFloat();
            case SIG_DOUBLE:
                return mBuf.getDouble();
            case SIG_STRING:
                return readString();
            case SIG_MAP:
                return readMap();
            default:
                throw new DecoderException(sig, mBuf.position() - 1);
        }
    }

    private String readString() {
        short len = mBuf.getShort();
        byte[] b = new byte[len];
        mBuf.get(b, 0, len);
        return new String(b, Charset.forName("utf-8"));
    }

    private Map<Short, Object> readMap() {
        Map<Short, Object> m = new HashMap<Short, Object>();

        while (true) {
            Object o = readObject();
            if (!(o instanceof Short)) {
                throw new DecoderException("Expected short key, got " + o.getClass());
            }

            Short key = (Short)o;
            if (key == SIG_END_MAP) {
                break;
            }

            m.put(key, readObject());
        }

        return m;
    }

    public static class DecoderException extends RuntimeException {
        public DecoderException(byte seen, int pos) {
            super(String.format("Unexpected byte %c seen at position %d", (char)seen, pos));
        }

        public DecoderException(String msg) {
            super(msg);
        }
    }
}
