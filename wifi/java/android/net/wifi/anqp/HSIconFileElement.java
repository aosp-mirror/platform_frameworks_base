package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static android.net.wifi.anqp.Constants.BYTE_MASK;
import static android.net.wifi.anqp.Constants.SHORT_MASK;

/**
 * The Icon Binary File vendor specific ANQP Element,
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.11
 */
public class HSIconFileElement extends ANQPElement {

    public enum StatusCode {Success, FileNotFound, Unspecified}

    private final StatusCode mStatusCode;
    private final String mType;
    private final byte[] mIconData;

    public HSIconFileElement(Constants.ANQPElementType infoID, ByteBuffer payload)
            throws ProtocolException {
        super(infoID);

        if (payload.remaining() < 4) {
            throw new ProtocolException("Truncated icon file: " + payload.remaining());
        }

        int statusID = payload.get() & BYTE_MASK;
        mStatusCode = statusID < StatusCode.values().length ? StatusCode.values()[statusID] : null;
        mType = Constants.getString(payload, 1, StandardCharsets.US_ASCII);

        int dataLength = payload.getShort() & SHORT_MASK;
        mIconData = new byte[dataLength];
        payload.get(mIconData);
    }

    public StatusCode getStatusCode() {
        return mStatusCode;
    }

    public String getType() {
        return mType;
    }

    public byte[] getIconData() {
        return mIconData;
    }

    @Override
    public String toString() {
        return "HSIconFileElement{" +
                "mStatusCode=" + mStatusCode +
                ", mType='" + mType + '\'' +
                ", mIconData=" + mIconData.length + " bytes }";
    }
}
