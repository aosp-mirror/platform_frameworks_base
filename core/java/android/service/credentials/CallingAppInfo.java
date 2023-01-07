/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.credentials;

import android.annotation.NonNull;
import android.content.pm.SigningInfo;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Information pertaining to the calling application, including the package name and a list of
 * app signatures.
 */
public final class CallingAppInfo implements Parcelable {
    @NonNull private final String mPackageName;
    @NonNull private final SigningInfo mSigningInfo;

    /**
     * Constructs a new instance.
     *
     * @throws IllegalArgumentException If {@code packageName} is null or empty.
     * @throws NullPointerException If {@code signingInfo} is null.
     */
    public CallingAppInfo(@NonNull String packageName,
            @NonNull SigningInfo signingInfo) {
        mPackageName = packageName;
        mSigningInfo = signingInfo;
    }

    private CallingAppInfo(@NonNull Parcel in) {
        mPackageName = in.readString8();
        mSigningInfo = in.readTypedObject(SigningInfo.CREATOR);
    }

    public static final @NonNull Creator<CallingAppInfo> CREATOR = new Creator<CallingAppInfo>() {
        @Override
        public CallingAppInfo createFromParcel(Parcel in) {
            return new CallingAppInfo(in);
        }

        @Override
        public CallingAppInfo[] newArray(int size) {
            return new CallingAppInfo[size];
        }
    };

    /** Returns the package name of the source of this info. */
    @NonNull public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the SigningInfo object that contains an array of
     * {@link android.content.pm.Signature} belonging to the app.
     */
    @NonNull public SigningInfo getSigningInfo() {
        return mSigningInfo;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mPackageName);
        dest.writeTypedObject(mSigningInfo, flags);
    }

    @Override
    public String toString() {
        StringBuilder builder =  new StringBuilder("CallingAppInfo {"
                + "packageName= " + mPackageName);
        if (mSigningInfo != null) {
            builder.append(", mSigningInfo : No. of signatures: " + mSigningInfo
                    .getApkContentsSigners().length);
        } else {
            builder.append(", mSigningInfo: null");
        }
        builder.append(" }");
        return builder.toString();
    }
}
