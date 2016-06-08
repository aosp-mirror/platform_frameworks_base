package com.android.anqp;

import android.os.Parcel;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static com.android.anqp.Constants.BYTE_MASK;

/**
 * A generic Internationalized name used in ANQP elements as specified in 802.11-2012 and
 * "Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00"
 */
public class I18Name {
    private final String mLanguage;
    private final Locale mLocale;
    private final String mText;

    public I18Name(ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() < Constants.LANG_CODE_LENGTH + 1) {
            throw new ProtocolException("Truncated I18Name: " + payload.remaining());
        }
        int nameLength = payload.get() & BYTE_MASK;
        if (nameLength < Constants.LANG_CODE_LENGTH) {
            throw new ProtocolException("Runt I18Name: " + nameLength);
        }
        mLanguage = Constants.getTrimmedString(payload,
                Constants.LANG_CODE_LENGTH, StandardCharsets.US_ASCII);
        mLocale = Locale.forLanguageTag(mLanguage);
        mText = Constants.getString(payload, nameLength -
                Constants.LANG_CODE_LENGTH, StandardCharsets.UTF_8);
    }

    public I18Name(String compoundString) throws IOException {
        if (compoundString.length() < Constants.LANG_CODE_LENGTH) {
            throw new IOException("I18String too short: '" + compoundString + "'");
        }
        mLanguage = compoundString.substring(0, Constants.LANG_CODE_LENGTH);
        mText = compoundString.substring(Constants.LANG_CODE_LENGTH);
        mLocale = Locale.forLanguageTag(mLanguage);
    }

    public String getLanguage() {
        return mLanguage;
    }

    public Locale getLocale() {
        return mLocale;
    }

    public String getText() {
        return mText;
    }

    @Override
    public boolean equals(Object thatObject) {
        if (this == thatObject) {
            return true;
        }
        if (thatObject == null || getClass() != thatObject.getClass()) {
            return false;
        }

        I18Name that = (I18Name) thatObject;
        return mLanguage.equals(that.mLanguage) && mText.equals(that.mText);
    }

    @Override
    public int hashCode() {
        int result = mLanguage.hashCode();
        result = 31 * result + mText.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return mText + ':' + mLocale.getLanguage();
    }

    public I18Name(Parcel in) throws IOException {
        mLanguage = in.readString();
        mText = in.readString();
        mLocale = Locale.forLanguageTag(mLanguage);
    }

    public void writeParcel(Parcel out) {
        out.writeString(mLanguage);
        out.writeString(mText);
    }
}
