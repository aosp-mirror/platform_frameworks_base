/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.tv;

import android.annotation.NonNull;
import android.media.tv.interactive.TvInteractiveAppServiceInfo;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * AIT (Application Information Table) info.
 */
public final class AitInfo implements Parcelable {
    static final String TAG = "AitInfo";

    public static final @NonNull Creator<AitInfo> CREATOR = new Creator<AitInfo>() {
        @Override
        public AitInfo createFromParcel(Parcel in) {
            return new AitInfo(in);
        }

        @Override
        public AitInfo[] newArray(int size) {
            return new AitInfo[size];
        }
    };

    private final int mType;
    private final int mVersion;

    private AitInfo(Parcel in) {
        mType = in.readInt();
        mVersion = in.readInt();
    }

    /**
     * Constructs AIT info.
     */
    public AitInfo(@TvInteractiveAppServiceInfo.InteractiveAppType int type, int version) {
        mType = type;
        mVersion = version;
    }

    /**
     * Gets interactive app type.
     */
    @TvInteractiveAppServiceInfo.InteractiveAppType
    public int getType() {
        return mType;
    }

    /**
     * Gets version.
     */
    public int getVersion() {
        return mVersion;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mVersion);
    }

    @Override
    public String toString() {
        return "type=" + mType + ";version=" + mVersion;
    }
}
