/*
 * Copyright (C) 2020 The Android Open Source Project
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
public final class AppInstallMetadata {
    private final String mPackageName;
    // Raw string encoding for the SHA-256 hash of the certificate of the app.
    private final List<String> mAppCertificates;
    private final String mInstallerName;
    // Raw string encoding for the SHA-256 hash of the certificate of the installer.
    private final List<String> mInstallerCertificates;
    private final long mVersionCode;
    private final boolean mIsPreInstalled;
    private final Map<String, String> mAllowedInstallersAndCertificates;

    private AppInstallMetadata(Builder builder) {
        this.mPackageName = builder.mPackageName;
        this.mAppCertificates = builder.mAppCertificates;
        this.mInstallerName = builder.mInstallerName;
        this.mInstallerCertificates = builder.mInstallerCertificates;
        this.mVersionCode = builder.mVersionCode;
        this.mIsPreInstalled = builder.mIsPreInstalled;
        this.mAllowedInstallersAndCertificates = builder.mAllowedInstallersAndCertificates;
    }

    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    @NonNull
    public List<String> getAppCertificates() {
        return mAppCertificates;
    }

    @NonNull
    public String getInstallerName() {
        return mInstallerName;
    }

    @NonNull
    public List<String> getInstallerCertificates() {
        return mInstallerCertificates;
    }

    /** @see AppInstallMetadata.Builder#setVersionCode(long) */
    public long getVersionCode() {
        return mVersionCode;
    }

    /** @see AppInstallMetadata.Builder#setIsPreInstalled(boolean) */
    public boolean isPreInstalled() {
        return mIsPreInstalled;
    }

    /**
     * Get the allowed installers and their corresponding cert.
     */
    public Map<String, String> getAllowedInstallersAndCertificates() {
        return mAllowedInstallersAndCertificates;
    }

    @Override
    public String toString() {
        return String.format(
                "AppInstallMetadata { PackageName = %s, AppCerts = %s, InstallerName = %s,"
                    + " InstallerCerts = %s, VersionCode = %d, PreInstalled = %b }",
                mPackageName,
                mAppCertificates,
                mInstallerName == null ? "null" : mInstallerName,
                mInstallerCertificates == null ? "null" : mInstallerCertificates,
                mVersionCode,
                mIsPreInstalled);
    }

    /** Builder class for constructing {@link AppInstallMetadata} objects. */
    public static final class Builder {
        private String mPackageName;
        private List<String> mAppCertificates;
        private String mInstallerName;
        private List<String> mInstallerCertificates;
        private long mVersionCode;
        private boolean mIsPreInstalled;
        private Map<String, String> mAllowedInstallersAndCertificates;

        public Builder() {
            mAllowedInstallersAndCertificates = new HashMap<>();
        }

        /**
         * Add allowed installers and cert.
         *
         * @see AppInstallMetadata#getAllowedInstallersAndCertificates()
         */
        @NonNull
        public Builder setAllowedInstallersAndCert(
                @NonNull Map<String, String> allowedInstallersAndCertificates) {
            this.mAllowedInstallersAndCertificates = allowedInstallersAndCertificates;
            return this;
        }

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
         * @see AppInstallMetadata#getAppCertificates()
         */
        @NonNull
        public Builder setAppCertificates(@NonNull List<String> appCertificates) {
            this.mAppCertificates = Objects.requireNonNull(appCertificates);
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
         * @see AppInstallMetadata#getInstallerCertificates()
         */
        @NonNull
        public Builder setInstallerCertificates(@NonNull List<String> installerCertificates) {
            this.mInstallerCertificates = Objects.requireNonNull(installerCertificates);
            return this;
        }

        /**
         * Set version code of the app to be installed.
         *
         * @see AppInstallMetadata#getVersionCode()
         */
        @NonNull
        public Builder setVersionCode(long versionCode) {
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
            Objects.requireNonNull(mAppCertificates);
            return new AppInstallMetadata(this);
        }
    }
}
