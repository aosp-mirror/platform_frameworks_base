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
import android.content.pm.Signature;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import com.android.internal.util.Preconditions;

import java.util.Objects;
import java.util.Set;

/**
 * Information pertaining to the calling application, including the package name and a list of
 * app signatures.
 */
public final class CallingAppInfo implements Parcelable {
    @NonNull private final String mPackageName;
    @NonNull private final Set<Signature> mSignatures;

    /**
     * Constructs a new instance.
     *
     * @throws IllegalArgumentException If {@code packageName} is null or empty.
     * @throws NullPointerException If {@code signatures} is null.
     */
    public CallingAppInfo(@NonNull String packageName,
            @NonNull Set<Signature> signatures) {
        mPackageName = Preconditions.checkStringNotEmpty(packageName,
                "packageName must not be null or empty");
        mSignatures = Objects.requireNonNull(signatures);
    }

    private CallingAppInfo(@NonNull Parcel in) {
        final ClassLoader boot = Object.class.getClassLoader();
        mPackageName = in.readString8();
        ArraySet<Signature> signatures = (ArraySet<Signature>) in.readArraySet(boot);
        mSignatures = signatures == null ? new ArraySet<>() : signatures;
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

    /** Returns the Set of signatures belonging to the app */
    @NonNull public Set<Signature> getSignatures() {
        return mSignatures;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mPackageName);
        dest.writeArraySet(new ArraySet<>(mSignatures));
    }

    @Override
    public String toString() {
        return "CallingAppInfo {"
                + "packageName= " + mPackageName
                + ", No. of signatures: " + mSignatures.size()
                + " }";
    }
}
