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

package android.content.pm;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;

import java.util.Arrays;
import java.util.Objects;

/**
 * A data class representing a package and (SHA-256 hash of) a signing certificate.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@FlaggedApi(android.permission.flags.Flags.FLAG_ENHANCED_CONFIRMATION_MODE_APIS_ENABLED)
public class SignedPackage {
    @NonNull
    private final SignedPackageParcel mData;

    /** @hide */
    public SignedPackage(@NonNull String packageName, @NonNull byte[] certificateDigest) {
        SignedPackageParcel data = new SignedPackageParcel();
        data.packageName = packageName;
        data.certificateDigest = certificateDigest;
        mData = data;
    }

    /** @hide */
    public SignedPackage(@NonNull SignedPackageParcel data) {
        mData = data;
    }

    /** @hide */
    public final @NonNull SignedPackageParcel getData() {
        return mData;
    }

    public @NonNull String getPackageName() {
        return mData.packageName;
    }

    public @NonNull byte[] getCertificateDigest() {
        return mData.certificateDigest;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SignedPackage that)) return false;
        return mData.packageName.equals(that.mData.packageName) && Arrays.equals(
                mData.certificateDigest, that.mData.certificateDigest);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mData.packageName, Arrays.hashCode(mData.certificateDigest));
    }
}
