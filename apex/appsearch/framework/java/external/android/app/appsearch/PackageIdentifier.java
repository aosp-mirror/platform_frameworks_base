/*
 * Copyright 2020 The Android Open Source Project
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

package android.app.appsearch;

import android.annotation.NonNull;
import android.app.appsearch.util.BundleUtil;
import android.os.Bundle;

import java.util.Objects;

/** This class represents a uniquely identifiable package. */
public class PackageIdentifier {
    private static final String PACKAGE_NAME_FIELD = "packageName";
    private static final String SHA256_CERTIFICATE_FIELD = "sha256Certificate";

    private final Bundle mBundle;

    /**
     * Creates a unique identifier for a package.
     *
     * @param packageName Name of the package.
     * @param sha256Certificate SHA256 certificate digest of the package.
     */
    public PackageIdentifier(@NonNull String packageName, @NonNull byte[] sha256Certificate) {
        mBundle = new Bundle();
        mBundle.putString(PACKAGE_NAME_FIELD, packageName);
        mBundle.putByteArray(SHA256_CERTIFICATE_FIELD, sha256Certificate);
    }

    /** @hide */
    public PackageIdentifier(@NonNull Bundle bundle) {
        mBundle = Objects.requireNonNull(bundle);
    }

    /** @hide */
    @NonNull
    public Bundle getBundle() {
        return mBundle;
    }

    @NonNull
    public String getPackageName() {
        return Objects.requireNonNull(mBundle.getString(PACKAGE_NAME_FIELD));
    }

    @NonNull
    public byte[] getSha256Certificate() {
        return Objects.requireNonNull(mBundle.getByteArray(SHA256_CERTIFICATE_FIELD));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || !(obj instanceof PackageIdentifier)) {
            return false;
        }
        final PackageIdentifier other = (PackageIdentifier) obj;
        return BundleUtil.deepEquals(mBundle, other.mBundle);
    }

    @Override
    public int hashCode() {
        return BundleUtil.deepHashCode(mBundle);
    }
}
