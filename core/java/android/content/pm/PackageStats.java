/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * implementation of PackageStats associated with a
 * application package.
 */
public class PackageStats implements Parcelable {
    public String packageName;
    public long codeSize;
    public long dataSize;
    public long cacheSize;
    
    public static final Parcelable.Creator<PackageStats> CREATOR
    = new Parcelable.Creator<PackageStats>() {
        public PackageStats createFromParcel(Parcel in) {
            return new PackageStats(in);
        }

        public PackageStats[] newArray(int size) {
            return new PackageStats[size];
        }
    };
    
    public String toString() {
        return "PackageStats{"
        + Integer.toHexString(System.identityHashCode(this))
        + " " + packageName + "}";
    }
    
    public PackageStats(String pkgName) {
        packageName = pkgName;
    }
    
    public PackageStats(Parcel source) {
        packageName = source.readString();
        codeSize = source.readLong();
        dataSize = source.readLong();
        cacheSize = source.readLong();
    }
    
    public PackageStats(PackageStats pStats) {
        packageName = pStats.packageName;
        codeSize = pStats.codeSize;
        dataSize = pStats.dataSize;
        cacheSize = pStats.cacheSize;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags){
        dest.writeString(packageName);
        dest.writeLong(codeSize);
        dest.writeLong(dataSize);
        dest.writeLong(cacheSize);
    }
}
