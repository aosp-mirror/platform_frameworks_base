package com.android.anqp;

import android.os.Parcel;

import com.android.hotspot2.Utils;

import java.io.IOException;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.android.anqp.Constants.BYTE_MASK;
import static com.android.anqp.Constants.SHORT_MASK;

/**
 * An OSU Provider, as specified in
 * Wi-Fi Alliance Hotspot 2.0 (Release 2) Technical Specification - Version 5.00,
 * section 4.8.1
 */
public class OSUProvider {

    public enum OSUMethod {OmaDm, SoapXml}

    private final String mSSID;
    private final List<I18Name> mNames;
    private final String mOSUServer;
    private final List<OSUMethod> mOSUMethods;
    private final List<IconInfo> mIcons;
    private final String mOsuNai;
    private final List<I18Name> mServiceDescriptions;
    private final int mHashCode;

    public OSUProvider(String ssid, ByteBuffer payload) throws ProtocolException {
        if (payload.remaining() < 11) {
            throw new ProtocolException("Truncated OSU provider: " + payload.remaining());
        }

        mSSID = ssid;

        int length = payload.getShort() & SHORT_MASK;
        int namesLength = payload.getShort() & SHORT_MASK;

        ByteBuffer namesBuffer = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        namesBuffer.limit(namesBuffer.position() + namesLength);
        payload.position(payload.position() + namesLength);

        mNames = new ArrayList<>();

        while (namesBuffer.hasRemaining()) {
            mNames.add(new I18Name(namesBuffer));
        }

        mOSUServer = Constants.getPrefixedString(payload, 1, StandardCharsets.UTF_8);
        int methodLength = payload.get() & BYTE_MASK;
        mOSUMethods = new ArrayList<>(methodLength);
        while (methodLength > 0) {
            int methodID = payload.get() & BYTE_MASK;
            mOSUMethods.add(methodID < OSUMethod.values().length ?
                    OSUMethod.values()[methodID] :
                    null);
            methodLength--;
        }

        int iconsLength = payload.getShort() & SHORT_MASK;
        ByteBuffer iconsBuffer = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        iconsBuffer.limit(iconsBuffer.position() + iconsLength);
        payload.position(payload.position() + iconsLength);

        mIcons = new ArrayList<>();

        while (iconsBuffer.hasRemaining()) {
            mIcons.add(new IconInfo(iconsBuffer));
        }

        mOsuNai = Constants.getPrefixedString(payload, 1, StandardCharsets.UTF_8, true);

        int descriptionsLength = payload.getShort() & SHORT_MASK;
        ByteBuffer descriptionsBuffer = payload.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        descriptionsBuffer.limit(descriptionsBuffer.position() + descriptionsLength);
        payload.position(payload.position() + descriptionsLength);

        mServiceDescriptions = new ArrayList<>();

        while (descriptionsBuffer.hasRemaining()) {
            mServiceDescriptions.add(new I18Name(descriptionsBuffer));
        }

        int result = mNames.hashCode();
        result = 31 * result + mSSID.hashCode();
        result = 31 * result + mOSUServer.hashCode();
        result = 31 * result + mOSUMethods.hashCode();
        result = 31 * result + mIcons.hashCode();
        result = 31 * result + (mOsuNai != null ? mOsuNai.hashCode() : 0);
        result = 31 * result + mServiceDescriptions.hashCode();
        mHashCode = result;
    }

    public String getSSID() {
        return mSSID;
    }

    public List<I18Name> getNames() {
        return mNames;
    }

    public String getOSUServer() {
        return mOSUServer;
    }

    public List<OSUMethod> getOSUMethods() {
        return mOSUMethods;
    }

    public List<IconInfo> getIcons() {
        return mIcons;
    }

    public String getOsuNai() {
        return mOsuNai;
    }

    public List<I18Name> getServiceDescriptions() {
        return mServiceDescriptions;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        OSUProvider that = (OSUProvider) o;

        if (!mSSID.equals(that.mSSID)) return false;
        if (!mOSUServer.equals(that.mOSUServer)) return false;
        if (!mNames.equals(that.mNames)) return false;
        if (!mServiceDescriptions.equals(that.mServiceDescriptions)) return false;
        if (!mIcons.equals(that.mIcons)) return false;
        if (!mOSUMethods.equals(that.mOSUMethods)) return false;
        if (mOsuNai != null ? !mOsuNai.equals(that.mOsuNai) : that.mOsuNai != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return mHashCode;
    }

    @Override
    public String toString() {
        return "OSUProvider{" +
                "names=" + mNames +
                ", OSUServer='" + mOSUServer + '\'' +
                ", OSUMethods=" + mOSUMethods +
                ", icons=" + mIcons +
                ", NAI='" + mOsuNai + '\'' +
                ", serviceDescriptions=" + mServiceDescriptions +
                '}';
    }

    public OSUProvider(Parcel in) throws IOException {
        mSSID = in.readString();
        int nameCount = in.readInt();
        mNames = new ArrayList<>(nameCount);
        for (int n = 0; n < nameCount; n++) {
            mNames.add(new I18Name(in));
        }
        mOSUServer = in.readString();
        int methodCount = in.readInt();
        mOSUMethods = new ArrayList<>(methodCount);
        for (int n = 0; n < methodCount; n++) {
            mOSUMethods.add(Utils.mapEnum(in.readInt(), OSUMethod.class));
        }
        int iconCount = in.readInt();
        mIcons = new ArrayList<>(iconCount);
        for (int n = 0; n < iconCount; n++) {
            mIcons.add(new IconInfo(in));
        }
        mOsuNai = in.readString();
        int serviceCount = in.readInt();
        mServiceDescriptions = new ArrayList<>(serviceCount);
        for (int n = 0; n < serviceCount; n++) {
            mServiceDescriptions.add(new I18Name(in));
        }
        mHashCode = in.readInt();
    }

    public void writeParcel(Parcel out) {
        out.writeString(mSSID);
        out.writeInt(mNames.size());
        for (I18Name name : mNames) {
            name.writeParcel(out);
        }
        out.writeString(mOSUServer);
        out.writeInt(mOSUMethods.size());
        for (OSUMethod method : mOSUMethods) {
            out.writeInt(method.ordinal());
        }
        out.writeInt(mIcons.size());
        for (IconInfo iconInfo : mIcons) {
            iconInfo.writeParcel(out);
        }
        out.writeString(mOsuNai);
        out.writeInt(mServiceDescriptions.size());
        for (I18Name serviceDescription : mServiceDescriptions) {
            serviceDescription.writeParcel(out);
        }
        out.writeInt(mHashCode);
    }
}
