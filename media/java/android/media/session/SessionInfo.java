/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.media.session;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Information about a media session, including the owner's package name.
 */
public final class SessionInfo implements Parcelable {
    private final String mId;
    private final String mPackageName;

    /**
     * @hide
     */
    public SessionInfo(String id, String packageName) {
        mId = id;
        mPackageName = packageName;
    }

    private SessionInfo(Parcel in) {
        mId = in.readString();
        mPackageName = in.readString();
    }

    /**
     * Get the package name of the owner of this session.
     *
     * @return The owner's package name
     */
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Get the unique id for this session.
     *
     * @return The id for the session.
     */
    public String getId() {
        return mId;
    }

    @Override
    public String toString() {
        return "SessionInfo {id=" + mId + ", pkg=" + mPackageName + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mId);
        dest.writeString(mPackageName);
    }

    public static final Parcelable.Creator<SessionInfo> CREATOR
            = new Parcelable.Creator<SessionInfo>() {
        @Override
        public SessionInfo createFromParcel(Parcel in) {
            return new SessionInfo(in);
        }

        @Override
        public SessionInfo[] newArray(int size) {
            return new SessionInfo[size];
        }
    };
}
