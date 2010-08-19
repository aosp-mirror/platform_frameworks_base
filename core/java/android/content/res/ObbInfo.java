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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Basic information about a Opaque Binary Blob (OBB) that reflects
 * the info from the footer on the OBB file.
 * @hide
 */
public class ObbInfo implements Parcelable {
    /** Flag noting that this OBB is an overlay patch for a base OBB. */
    public static final int OBB_OVERLAY = 1 << 0;

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

    public ObbInfo() {
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
        dest.writeString(packageName);
        dest.writeInt(version);
        dest.writeInt(flags);
    }

    public static final Parcelable.Creator<ObbInfo> CREATOR
            = new Parcelable.Creator<ObbInfo>() {
        public ObbInfo createFromParcel(Parcel source) {
            return new ObbInfo(source);
        }

        public ObbInfo[] newArray(int size) {
            return new ObbInfo[size];
        }
    };

    private ObbInfo(Parcel source) {
        packageName = source.readString();
        version = source.readInt();
        flags = source.readInt();
    }
}
