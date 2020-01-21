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
package com.android.server.blob;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * Class for representing how a blob can be shared.
 *
 * Note that this class is not thread-safe, callers need to take of synchronizing access.
 */
class BlobAccessMode {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            ACCESS_TYPE_PRIVATE,
            ACCESS_TYPE_PUBLIC,
            ACCESS_TYPE_SAME_SIGNATURE,
            ACCESS_TYPE_WHITELIST,
    })
    @interface AccessType {}
    static final int ACCESS_TYPE_PRIVATE = 1 << 0;
    static final int ACCESS_TYPE_PUBLIC = 1 << 1;
    static final int ACCESS_TYPE_SAME_SIGNATURE = 1 << 2;
    static final int ACCESS_TYPE_WHITELIST = 1 << 3;

    private int mAccessType = ACCESS_TYPE_PRIVATE;

    private final ArraySet<PackageIdentifier> mWhitelistedPackages = new ArraySet<>();

    void allow(BlobAccessMode other) {
        if ((other.mAccessType & ACCESS_TYPE_WHITELIST) != 0) {
            mWhitelistedPackages.addAll(other.mWhitelistedPackages);
        }
        mAccessType |= other.mAccessType;
    }

    void allowPublicAccess() {
        mAccessType |= ACCESS_TYPE_PUBLIC;
    }

    void allowSameSignatureAccess() {
        mAccessType |= ACCESS_TYPE_SAME_SIGNATURE;
    }

    void allowPackageAccess(@NonNull String packageName, @NonNull byte[] certificate) {
        mAccessType |= ACCESS_TYPE_WHITELIST;
        mWhitelistedPackages.add(PackageIdentifier.create(packageName, certificate));
    }

    boolean isPublicAccessAllowed() {
        return (mAccessType & ACCESS_TYPE_PUBLIC) != 0;
    }

    boolean isSameSignatureAccessAllowed() {
        return (mAccessType & ACCESS_TYPE_SAME_SIGNATURE) != 0;
    }

    boolean isPackageAccessAllowed(@NonNull String packageName, @NonNull byte[] certificate) {
        if ((mAccessType & ACCESS_TYPE_WHITELIST) == 0) {
            return false;
        }
        return mWhitelistedPackages.contains(PackageIdentifier.create(packageName, certificate));
    }

    boolean isAccessAllowedForCaller(Context context,
            @NonNull String callingPackage, @NonNull String committerPackage) {
        if ((mAccessType & ACCESS_TYPE_PUBLIC) != 0) {
            return true;
        }

        final PackageManager pm = context.getPackageManager();
        if ((mAccessType & ACCESS_TYPE_SAME_SIGNATURE) != 0) {
            if (pm.checkSignatures(committerPackage, callingPackage)
                    == PackageManager.SIGNATURE_MATCH) {
                return true;
            }
        }

        if ((mAccessType & ACCESS_TYPE_WHITELIST) != 0) {
            for (int i = 0; i < mWhitelistedPackages.size(); ++i) {
                final PackageIdentifier packageIdentifier = mWhitelistedPackages.valueAt(i);
                if (packageIdentifier.packageName.equals(callingPackage)
                        && pm.hasSigningCertificate(callingPackage, packageIdentifier.certificate,
                                PackageManager.CERT_INPUT_SHA256)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static final class PackageIdentifier {
        public final String packageName;
        public final byte[] certificate;

        private PackageIdentifier(@NonNull String packageName, @NonNull byte[] certificate) {
            this.packageName = packageName;
            this.certificate = certificate;
        }

        public static PackageIdentifier create(@NonNull String packageName,
                @NonNull byte[] certificate) {
            return new PackageIdentifier(packageName, certificate);
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
}
