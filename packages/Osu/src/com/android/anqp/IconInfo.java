package com.android.anqp;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static com.android.anqp.Constants.SHORT_MASK;

/**
 * The Icons available OSU Providers sub field, as specified in
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.8.1.4
 */
public class IconInfo {
    private final int mWidth;
    private final int mHeight;
    private final String mLanguage;
    private final String mIconType;
    private final String mFileName;

    public IconInfo(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() < 9) {
            throw new ProtocolException("Truncated icon meta data");
        }

        mWidth = payload.getShort() & SHORT_MASK;
        mHeight = payload.getShort() & SHORT_MASK;
        mLanguage = Constants.getTrimmedString(payload,
                Constants.LANG_CODE_LENGTH, StandardCharsets.US_ASCII);
        mIconType = Constants.getPrefixedString(payload, 1, StandardCharsets.US_ASCII);
        mFileName = Constants.getPrefixedString(payload, 1, StandardCharsets.UTF_8);
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public String getLanguage() {
        return mLanguage;
    }

    public String getIconType() {
        return mIconType;
    }

    public String getFileName() {
        return mFileName;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (thatObject == null || getClass() != thatObject.getClass()) {
            return false;
        }

        IconInfo that = (IconInfo) thatObject;
        return mHeight == that.mHeight &&
                mWidth == that.mWidth &&
                mFileName.equals(that.mFileName) &&
                mIconType.equals(that.mIconType) &&
                mLanguage.equals(that.mLanguage);
    }

    @Override
    public int hashCode() {
        int result = mWidth;
        result = 31 * result + mHeight;
        result = 31 * result + mLanguage.hashCode();
        result = 31 * result + mIconType.hashCode();
        result = 31 * result + mFileName.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "IconInfo{" +
                "Width=" + mWidth +
                ", Height=" + mHeight +
                ", Language=" + mLanguage +
                ", IconType='" + mIconType + '\'' +
                ", FileName='" + mFileName + '\'' +
                '}';
    }
}
