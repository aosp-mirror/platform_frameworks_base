/* Copyright (c) 2012-2013, The Linux Foundation. All rights reserved.
 *
 * Not a Contribution, Apache license notifications and license are retained
 * for attribution purposes only.
 *
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.location;

import android.app.PendingIntent;
import android.os.Binder;
import android.os.Parcel;
import android.os.Parcelable;
import java.io.PrintWriter;

/**
 * GeoFenceParams for internal use
 * {@hide}
 */
public class GeoFenceParams implements Parcelable {
    public static final int ENTERING = 1;
    public static final int LEAVING = 2;
    public final int mUid;
    public final double mLatitude;
    public final double mLongitude;
    public final float mRadius;
    public final long mExpiration;
    public final PendingIntent mIntent;
    public final String mPackageName;

    public static final Parcelable.Creator<GeoFenceParams> CREATOR = new Parcelable.Creator<GeoFenceParams>() {
        public GeoFenceParams createFromParcel(Parcel in) {
            return new GeoFenceParams(in);
        }

        @Override
        public GeoFenceParams[] newArray(int size) {
            return new GeoFenceParams[size];
        }
    };

    public GeoFenceParams(double lat, double lon, float r,
                          long expire, PendingIntent intent, String packageName) {
        this(Binder.getCallingUid(), lat, lon, r, expire, intent, packageName);
    }

    public GeoFenceParams(int uid, double lat, double lon, float r,
                          long expire, PendingIntent intent, String packageName) {
        mUid = uid;
        mLatitude = lat;
        mLongitude = lon;
        mRadius = r;
        mExpiration = expire;
        mIntent = intent;
        mPackageName = packageName;
    }

    private GeoFenceParams(Parcel in) {
        mUid = in.readInt();
        mLatitude = in.readDouble();
        mLongitude = in.readDouble();
        mRadius = in.readFloat();
        mExpiration = in.readLong();
        mIntent = in.readParcelable(null);
        mPackageName = in.readString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUid);
        dest.writeDouble(mLatitude);
        dest.writeDouble(mLongitude);
        dest.writeFloat(mRadius);
        dest.writeLong(mExpiration);
        dest.writeParcelable(mIntent, 0);
        dest.writeString(mPackageName);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("GeoFenceParams:\n\tmUid - ");
        sb.append(mUid);
        sb.append("\n\tmLatitide - ");
        sb.append(mLatitude);
        sb.append("\n\tmLongitude - ");
        sb.append(mLongitude);
        sb.append("\n\tmRadius - ");
        sb.append(mRadius);
        sb.append("\n\tmExpiration - ");
        sb.append(mExpiration);
        sb.append("\n\tmIntent - ");
        sb.append(mIntent);
        return sb.toString();
    }

    public long getExpiration() {
        return mExpiration;
    }

    public PendingIntent getIntent() {
        return mIntent;
    }

    public int getCallerUid() {
        return mUid;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + this);
        pw.println(prefix + "mLatitude=" + mLatitude + " mLongitude=" + mLongitude);
        pw.println(prefix + "mRadius=" + mRadius + " mExpiration=" + mExpiration);
    }
}
