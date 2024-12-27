/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.security.advancedprotection;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.security.Flags;

/**
 * An advanced protection feature providing protections.
 * @hide
 */
@FlaggedApi(Flags.FLAG_AAPM_API)
@SystemApi
public final class AdvancedProtectionFeature implements Parcelable {
    private final String mId;

    /**
     * Create an object identifying an Advanced Protection feature for AdvancedProtectionManager
     * @param id A unique ID to identify this feature. It is used by Settings screens to display
     *           information about this feature.
     */
    public AdvancedProtectionFeature(@NonNull String id) {
        mId = id;
    }

    private AdvancedProtectionFeature(Parcel in) {
        mId = in.readString8();
    }

    /**
     * @return the unique ID representing this feature
     */
    @NonNull
    public String getId() {
        return mId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mId);
    }

    @NonNull
    public static final Parcelable.Creator<AdvancedProtectionFeature> CREATOR =
            new Parcelable.Creator<>() {
                public AdvancedProtectionFeature createFromParcel(Parcel in) {
                    return new AdvancedProtectionFeature(in);
                }

                public AdvancedProtectionFeature[] newArray(int size) {
                    return new AdvancedProtectionFeature[size];
                }
            };
}
