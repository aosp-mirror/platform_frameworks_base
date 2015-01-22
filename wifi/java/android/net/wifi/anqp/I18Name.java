package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static android.net.wifi.anqp.Constants.BYTE_MASK;

/**
 * A generic Internationalized name used in ANQP elements as specified in 802.11-2012 and
 * "Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00"
 */
public class I18Name {
    private static final int LANG_CODE_LENGTH = 3;

    private final Locale mLocale;
    private final String mText;

    public I18Name(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() < 4) {
            throw new ProtocolException("Truncated I18Name: " + payload.remaining());
        }
        int nameLength = payload.get() & BYTE_MASK;
        if (nameLength < 3) {
            throw new ProtocolException("Runt I18Name: " + nameLength);
        }
        String language = Constants.getString(payload, LANG_CODE_LENGTH, StandardCharsets.US_ASCII);
        mLocale = Locale.forLanguageTag(language);
        mText = Constants.getString(payload, nameLength - LANG_CODE_LENGTH, StandardCharsets.UTF_8);
    }

    public Locale getLocale() {
        return mLocale;
    }

    public String getText() {
        return mText;
    }

    @Override
    public String toString() {
        return mText + ':' + mLocale.getLanguage();
    }
}
