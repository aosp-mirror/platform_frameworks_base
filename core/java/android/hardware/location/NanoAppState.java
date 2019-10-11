/*
 * Copyright 2017 The Android Open Source Project
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
package android.hardware.location;

import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A class describing the nanoapp state information resulting from a query to a Context Hub.
 *
 * @hide
 */
@SystemApi
public final class NanoAppState implements Parcelable {
    private long mNanoAppId;
    private int mNanoAppVersion;
    private boolean mIsEnabled;

    public NanoAppState(long nanoAppId, int appVersion, boolean enabled) {
        mNanoAppId = nanoAppId;
        mNanoAppVersion = appVersion;
        mIsEnabled = enabled;
    }

    /**
     * @return the NanoAppInfo for this app
     */
    public long getNanoAppId() {
        return mNanoAppId;
    }

    /**
     * @return the app version
     */
    public long getNanoAppVersion() {
        return mNanoAppVersion;
    }

    /**
     * @return {@code true} if the app is enabled at the Context Hub, {@code false} otherwise
     */
    public boolean isEnabled() {
        return mIsEnabled;
    }

    private NanoAppState(Parcel in) {
        mNanoAppId = in.readLong();
        mNanoAppVersion = in.readInt();
        mIsEnabled = (in.readInt() == 1);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeLong(mNanoAppId);
        out.writeInt(mNanoAppVersion);
        out.writeInt(mIsEnabled ? 1 : 0);
    }

    public static final @android.annotation.NonNull Creator<NanoAppState> CREATOR =
            new Creator<NanoAppState>() {
                @Override
                public NanoAppState createFromParcel(Parcel in) {
                    return new NanoAppState(in);
                }

                @Override
                public NanoAppState[] newArray(int size) {
                    return new NanoAppState[size];
                }
            };
}
