/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.integrity;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/**
 * The app install metadata.
 *
 * <p>The integrity component retrieves metadata for app installs from package manager, passing it
 * to the rule evaluation engine to evaluate the metadata against the rules.
 *
 * <p>Instances of this class are immutable.
 *
 * @hide
 */
@SystemApi
@VisibleForTesting
public final class AppInstallMetadata {
    private final String mPackageName;
    // Raw string encoding for the SHA-256 hash of the certificate of the app.
    private final String mAppCertificate;
    private final String mInstallerName;
    // Raw string encoding for the SHA-256 hash of the certificate of the installer.
    private final String mInstallerCertificate;
    private final int mVersionCode;
    private final boolean mIsPreInstalled;

    private AppInstallMetadata(Builder builder) {
        this.mPackageName = builder.mPackageName;
        this.mAppCertificate = builder.mAppCertificate;
        this.mInstallerName = builder.mInstallerName;
        this.mInstallerCertificate = builder.mInstallerCertificate;
        this.mVersionCode = builder.mVersionCode;
        this.mIsPreInstalled = builder.mIsPreInstalled;
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    @NonNull
    public String getAppCertificate() {
        return mAppCertificate;
    }

    @Nullable
    public String getInstallerName() {
        return mInstallerName;
    }

    @Nullable
    public String getInstallerCertificate() {
        return mInstallerCertificate;
    }

    /** @see AppInstallMetadata.Builder#setVersionCode(int) */
    public int getVersionCode() {
        return mVersionCode;
    }

    /** @see AppInstallMetadata.Builder#setIsPreInstalled(boolean) */
    public boolean isPreInstalled() {
        return mIsPreInstalled;
    }

    @Override
    public String toString() {
        return String.format(
                "AppInstallMetadata { PackageName = %s, AppCert = %s, InstallerName = %s,"
                    + " InstallerCert = %s, VersionCode = %d, PreInstalled = %b }",
                mPackageName,
                mAppCertificate,
                mInstallerName == null ? "null" : mInstallerName,
                mInstallerCertificate == null ? "null" : mInstallerCertificate,
                mVersionCode,
                mIsPreInstalled);
    }

    /** Builder class for constructing {@link AppInstallMetadata} objects. */
    public static final class Builder {
        private String mPackageName;
        private String mAppCertificate;
        private String mInstallerName;
        private String mInstallerCertificate;
        private int mVersionCode;
        private boolean mIsPreInstalled;

        /**
         * Set package name of the app to be installed.
         *
         * @see AppInstallMetadata#getPackageName()
         */
        @NonNull
        public Builder setPackageName(@NonNull String packageName) {
            this.mPackageName = Objects.requireNonNull(packageName);
            return this;
        }

        /**
         * Set certificate of the app to be installed.
         *
         * <p>It is represented as the raw string encoding for the SHA-256 hash of the certificate
         * of the app.
         *
         * @see AppInstallMetadata#getAppCertificate()
         */
        @NonNull
        public Builder setAppCertificate(@NonNull String appCertificate) {
            this.mAppCertificate = Objects.requireNonNull(appCertificate);
            return this;
        }

        /**
         * Set name of the installer installing the app.
         *
         * @see AppInstallMetadata#getInstallerName()
         */
        @NonNull
        public Builder setInstallerName(@NonNull String installerName) {
            this.mInstallerName = Objects.requireNonNull(installerName);
            return this;
        }

        /**
         * Set certificate of the installer installing the app.
         *
         * <p>It is represented as the raw string encoding for the SHA-256 hash of the certificate
         * of the installer.
         *
         * @see AppInstallMetadata#getInstallerCertificate()
         */
        @NonNull
        public Builder setInstallerCertificate(@NonNull String installerCertificate) {
            this.mInstallerCertificate = Objects.requireNonNull(installerCertificate);
            return this;
        }

        /**
         * Set version code of the app to be installed.
         *
         * @see AppInstallMetadata#getVersionCode()
         */
        @NonNull
        public Builder setVersionCode(int versionCode) {
            this.mVersionCode = versionCode;
            return this;
        }

        /**
         * Set whether the app is pre-installed on the device or not.
         *
         * @see AppInstallMetadata#isPreInstalled()
         */
        @NonNull
        public Builder setIsPreInstalled(boolean isPreInstalled) {
            this.mIsPreInstalled = isPreInstalled;
            return this;
        }

        /**
         * Build {@link AppInstallMetadata}.
         *
         * @throws IllegalArgumentException if package name or app certificate is null
         */
        @NonNull
        public AppInstallMetadata build() {
            Objects.requireNonNull(mPackageName);
            Objects.requireNonNull(mAppCertificate);
            return new AppInstallMetadata(this);
        }
    }
}
