/*
 * Copyright (C) 2009 The Android Open Source Project
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
import android.os.PatternMatcher;

/**
 * Description of permissions needed to access a particular path
 * in a {@link ProviderInfo}.
 */
public class PathPermission extends PatternMatcher {
    private final String mReadPermission;
    private final String mWritePermission;
    
    public PathPermission(String pattern, int type, String readPermission,
            String writePermission) {
        super(pattern, type);
        mReadPermission = readPermission;
        mWritePermission = writePermission;
    }
    
    public String getReadPermission() {
        return mReadPermission;
    }
    
    public String getWritePermission() {
        return mWritePermission;
    }
    
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mReadPermission);
        dest.writeString(mWritePermission);
    }
    
    public PathPermission(Parcel src) {
        super(src);
        mReadPermission = src.readString();
        mWritePermission = src.readString();
    }
    
    public static final Parcelable.Creator<PathPermission> CREATOR
            = new Parcelable.Creator<PathPermission>() {
        public PathPermission createFromParcel(Parcel source) {
            return new PathPermission(source);
        }

        public PathPermission[] newArray(int size) {
            return new PathPermission[size];
        }
    };
}