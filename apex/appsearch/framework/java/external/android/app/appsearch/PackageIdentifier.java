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

import java.util.Arrays;
import java.util.Objects;

/**
 * This class represents a uniquely identifiable package.
 *
 * @hide
 */
public class PackageIdentifier {
    public final String packageName;
    public final byte[] certificate;

    /**
     * Creates a unique identifier for a package.
     *
     * @param packageName Name of the package.
     * @param certificate SHA256 certificate digest of the package.
     */
    public PackageIdentifier(@NonNull String packageName, @NonNull byte[] certificate) {
        this.packageName = packageName;
        this.certificate = certificate;
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
        return this.packageName.equals(other.packageName)
                && Arrays.equals(this.certificate, other.certificate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName, Arrays.hashCode(certificate));
    }
}
