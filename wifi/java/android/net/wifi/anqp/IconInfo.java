package android.net.wifi.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static android.net.wifi.anqp.Constants.SHORT_MASK;

/**
 * The Icons available OSU Providers sub field, as specified in
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.8.1.4
 */
public class IconInfo {
    private final int mWidth;
    private final int mHeight;
    private final Locale mLocale;
    private final String mIconType;
    private final String mFileName;

    public IconInfo(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() < 9) {
            throw new ProtocolException("Truncated icon meta data");
        }

        mWidth = payload.getShort() & SHORT_MASK;
        mHeight = payload.getShort() & SHORT_MASK;
        mLocale = Locale.forLanguageTag(Constants.getString(payload, 3, StandardCharsets.US_ASCII));
        mIconType = Constants.getPrefixedString(payload, 1, StandardCharsets.US_ASCII);
        mFileName = Constants.getPrefixedString(payload, 1, StandardCharsets.UTF_8);
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public Locale getLocale() {
        return mLocale;
    }

    public String getIconType() {
        return mIconType;
    }

    public String getFileName() {
        return mFileName;
    }

    @Override
    public String toString() {
        return "IconInfo{" +
                "mWidth=" + mWidth +
                ", mHeight=" + mHeight +
                ", mLocale=" + mLocale +
                ", mIconType='" + mIconType + '\'' +
                ", mFileName='" + mFileName + '\'' +
                '}';
    }
}
