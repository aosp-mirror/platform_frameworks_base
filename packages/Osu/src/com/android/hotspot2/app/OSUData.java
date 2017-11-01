package com.android.hotspot2.app;

import android.os.Parcel;
import android.os.Parcelable;

import com.android.hotspot2.flow.OSUInfo;
import com.android.hotspot2.osu.OSUManager;

public class OSUData implements Parcelable {
    private final String mName;
    private final String mServiceDescription;
    private final byte[] mIconData;
    private final int mId;

    public OSUData(OSUInfo osuInfo) {
        mName = osuInfo.getName(OSUManager.LOCALE);
        mServiceDescription = osuInfo.getServiceDescription(OSUManager.LOCALE);
        mIconData = osuInfo.getIconFileElement().getIconData();
        mId = osuInfo.getOsuID();
    }

    public String getName() {
        return mName;
    }

    public String getServiceDescription() {
        return mServiceDescription;
    }

    public byte[] getIconData() {
        return mIconData;
    }

    public int getId() {
        return mId;
    }

    private OSUData(Parcel in) {
        mName = in.readString();
        mServiceDescription = in.readString();
        int iconSize = in.readInt();
        mIconData = new byte[iconSize];
        in.readByteArray(mIconData);
        mId = in.readInt();
    }

    public static final Parcelable.Creator<OSUData> CREATOR = new Parcelable.Creator<OSUData>() {
        public OSUData createFromParcel(Parcel in) {
            return new OSUData(in);
        }

        public OSUData[] newArray(int size) {
            return new OSUData[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mName);
        dest.writeString(mServiceDescription);
        dest.writeByteArray(mIconData);
        dest.writeInt(mId);
    }
}
