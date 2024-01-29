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

package android.media.projection;

import android.app.ActivityOptions.LaunchCookie;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

import java.util.Objects;

/** @hide */
public final class MediaProjectionInfo implements Parcelable {
    private final String mPackageName;
    private final UserHandle mUserHandle;
    private final LaunchCookie mLaunchCookie;

    public MediaProjectionInfo(String packageName, UserHandle handle, LaunchCookie launchCookie) {
        mPackageName = packageName;
        mUserHandle = handle;
        mLaunchCookie = launchCookie;
    }

    public MediaProjectionInfo(Parcel in) {
        mPackageName = in.readString();
        mUserHandle = UserHandle.readFromParcel(in);
        mLaunchCookie = LaunchCookie.readFromParcel(in);
    }

    public String getPackageName() {
        return mPackageName;
    }

    public UserHandle getUserHandle() {
        return mUserHandle;
    }

    public LaunchCookie getLaunchCookie() {
        return mLaunchCookie;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof MediaProjectionInfo other) {
            return Objects.equals(other.mPackageName, mPackageName)
                    && Objects.equals(other.mUserHandle, mUserHandle)
                    && Objects.equals(other.mLaunchCookie, mLaunchCookie);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPackageName, mUserHandle);
    }

    @Override
    public String toString() {
        return "MediaProjectionInfo{mPackageName="
            + mPackageName + ", mUserHandle="
            + mUserHandle + ", mLaunchCookie="
            + mLaunchCookie + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(mPackageName);
        UserHandle.writeToParcel(mUserHandle, out);
        LaunchCookie.writeToParcel(mLaunchCookie, out);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<MediaProjectionInfo> CREATOR =
            new Parcelable.Creator<MediaProjectionInfo>() {
        @Override
        public MediaProjectionInfo createFromParcel(Parcel in) {
            return new MediaProjectionInfo (in);
        }

        @Override
        public MediaProjectionInfo[] newArray(int size) {
            return new MediaProjectionInfo[size];
        }
    };
}
