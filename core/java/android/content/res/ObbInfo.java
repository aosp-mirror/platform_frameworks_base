/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.content.res;

import android.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Basic information about a Opaque Binary Blob (OBB) that reflects the info
 * from the footer on the OBB file. This information may be manipulated by a
 * developer with the <code>obbtool</code> program in the Android SDK.
 */
public class ObbInfo implements Parcelable {
    /** Flag noting that this OBB is an overlay patch for a base OBB. */
    public static final int OBB_OVERLAY = 1 << 0;

    /**
     * The canonical filename of the OBB.
     */
    public String filename;

    /**
     * The name of the package to which the OBB file belongs.
     */
    public String packageName;

    /**
     * The version of the package to which the OBB file belongs.
     */
    public int version;

    /**
     * The flags relating to the OBB.
     */
    public int flags;

    /**
     * The salt for the encryption algorithm.
     * 
     * @hide
     */
    @UnsupportedAppUsage
    public byte[] salt;

    // Only allow things in this package to instantiate.
    /* package */ ObbInfo() {
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("ObbInfo{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(" packageName=");
        sb.append(packageName);
        sb.append(",version=");
        sb.append(version);
        sb.append(",flags=");
        sb.append(flags);
        sb.append('}');
        return sb.toString();
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        // Keep this in sync with writeToParcel() in ObbInfo.cpp
        dest.writeString(filename);
        dest.writeString(packageName);
        dest.writeInt(version);
        dest.writeInt(flags);
        dest.writeByteArray(salt);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ObbInfo> CREATOR
            = new Parcelable.Creator<ObbInfo>() {
        public ObbInfo createFromParcel(Parcel source) {
            return new ObbInfo(source);
        }

        public ObbInfo[] newArray(int size) {
            return new ObbInfo[size];
        }
    };

    private ObbInfo(Parcel source) {
        filename = source.readString();
        packageName = source.readString();
        version = source.readInt();
        flags = source.readInt();
        salt = source.createByteArray();
    }
}
