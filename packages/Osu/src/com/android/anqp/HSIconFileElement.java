package com.android.anqp;

import android.os.Parcel;

import com.android.hotspot2.Utils;

import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static com.android.anqp.Constants.BYTE_MASK;
import static com.android.anqp.Constants.SHORT_MASK;

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
        mType = Constants.getPrefixedString(payload, 1, StandardCharsets.US_ASCII);

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
    public boolean equals(Object thatObject) {
        if (thatObject == this) {
            return true;
        } else if (thatObject.getClass() != HSIconFileElement.class) {
            return false;
        }
        HSIconFileElement that = (HSIconFileElement) thatObject;
        if (getStatusCode() != that.getStatusCode() || getStatusCode() != StatusCode.Success) {
            return false;
        }
        return getType().equals(that.getType()) && Arrays.equals(getIconData(), that.getIconData());
    }

    @Override
    public String toString() {
        return "HSIconFile{" +
                "statusCode=" + mStatusCode +
                ", type='" + mType + '\'' +
                ", iconData=" + mIconData.length + " bytes }";
    }

    public HSIconFileElement(Parcel in) {
        super(Constants.ANQPElementType.HSIconFile);
        mStatusCode = Utils.mapEnum(in.readInt(), StatusCode.class);
        mType = in.readString();
        mIconData = in.readBlob();
    }

    public void writeParcel(Parcel out) {
        out.writeInt(mStatusCode.ordinal());
        out.writeString(mType);
        out.writeBlob(mIconData);
    }
}
