package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * ANQP Element to hold a generic (UTF-8 decoded) character string
 */
public class GenericStringElement extends ANQPElement {
    private final String mText;

    public GenericStringElement(Constants.ANQPElementType infoID, ByteBuffer payload) throws ProtocolException {
        super(infoID);
        mText = Constants.getString(payload, payload.remaining(), StandardCharsets.UTF_8);
    }

    public String getM_text() {
        return mText;
    }

    @Override
    public String toString() {
        return "Element ID " + getID() + ": '" + mText + "'";
    }
}
