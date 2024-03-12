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
import android.annotation.Nullable;
import android.content.pm.SigningInfo;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Information pertaining to the calling application, including the package name and a list of
 * app signatures.
 */
public final class CallingAppInfo implements Parcelable {
    @NonNull private final String mPackageName;
    @NonNull private final SigningInfo mSigningInfo;
    @Nullable
    private final String mOrigin;

    /**
     * Constructs a new instance.
     *
     * @throws IllegalArgumentException If {@code packageName} is null or empty.
     * @throws NullPointerException If {@code signingInfo} is null.
     */
    public CallingAppInfo(@NonNull String packageName,
            @NonNull SigningInfo signingInfo) {
        this(packageName, signingInfo, /*origin=*/ null);
    }

    /**
     * Constructs a new instance.
     *
     * @param packageName - the package name of the calling app
     * @param signingInfo - the signing info on the calling app
     * @param origin - the origin that the calling app wants to use when making request on behalf of
     *               other
     * @throws IllegalArgumentException If {@code packageName} is null or empty.
     * @throws NullPointerException If {@code signingInfo} is null.
     */
    public CallingAppInfo(@NonNull String packageName,
            @NonNull SigningInfo signingInfo, @Nullable String origin) {
        mPackageName = Preconditions.checkStringNotEmpty(packageName, "package name"
                + "must not be null or empty");
        mSigningInfo = Objects.requireNonNull(signingInfo);
        mOrigin = origin;
    }

    private CallingAppInfo(@NonNull Parcel in) {
        mPackageName = in.readString8();
        mSigningInfo = in.readTypedObject(SigningInfo.CREATOR);
        mOrigin = in.readString8();
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

    /**
     * Returns the origin of the calling app if set otherwise returns null.
     * This value is set only if the origin is different than that of the calling app,
     * and should be expected from privileged callers(browsers) only when making request on behalf
     * of other applications.
     *
     * Android system makes sure that only applications that poses the permission
     * {@link android.Manifest.permission#CREDENTIAL_MANAGER_SET_ORIGIN} can set the origin on
     * the incoming {@link android.credentials.GetCredentialRequest} or
     * {@link android.credentials.CreateCredentialRequest}.
     */
    @Nullable
    public String getOrigin() {
        return mOrigin;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mPackageName);
        dest.writeTypedObject(mSigningInfo, flags);
        dest.writeString8(mOrigin);
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
        builder.append(",mOrigin: " + mOrigin);
        builder.append(" }");
        return builder.toString();
    }
}
