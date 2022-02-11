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
    // Raw string encoding for the SHA-256 hash of the certificate lineage/history of the app.
    private final List<String> mAppCertificateLineage;
    private final String mInstallerName;
    // Raw string encoding for the SHA-256 hash of the certificate of the installer.
    private final List<String> mInstallerCertificates;
    private final long mVersionCode;
    private final boolean mIsPreInstalled;
    private final boolean mIsStampPresent;
    private final boolean mIsStampVerified;
    private final boolean mIsStampTrusted;
    // Raw string encoding for the SHA-256 hash of the certificate of the stamp.
    private final String mStampCertificateHash;
    private final Map<String, String> mAllowedInstallersAndCertificates;

    private AppInstallMetadata(Builder builder) {
        this.mPackageName = builder.mPackageName;
        this.mAppCertificates = builder.mAppCertificates;
        this.mAppCertificateLineage = builder.mAppCertificateLineage;
        this.mInstallerName = builder.mInstallerName;
        this.mInstallerCertificates = builder.mInstallerCertificates;
        this.mVersionCode = builder.mVersionCode;
        this.mIsPreInstalled = builder.mIsPreInstalled;
        this.mIsStampPresent = builder.mIsStampPresent;
        this.mIsStampVerified = builder.mIsStampVerified;
        this.mIsStampTrusted = builder.mIsStampTrusted;
        this.mStampCertificateHash = builder.mStampCertificateHash;
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
    public List<String> getAppCertificateLineage() {
        return mAppCertificateLineage;
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

    /** @see AppInstallMetadata.Builder#setIsStampPresent(boolean) */
    public boolean isStampPresent() {
        return mIsStampPresent;
    }

    /** @see AppInstallMetadata.Builder#setIsStampVerified(boolean) */
    public boolean isStampVerified() {
        return mIsStampVerified;
    }

    /** @see AppInstallMetadata.Builder#setIsStampTrusted(boolean) */
    public boolean isStampTrusted() {
        return mIsStampTrusted;
    }

    /** @see AppInstallMetadata.Builder#setStampCertificateHash(String) */
    public String getStampCertificateHash() {
        return mStampCertificateHash;
    }

    /** Get the allowed installers and their corresponding cert. */
    public Map<String, String> getAllowedInstallersAndCertificates() {
        return mAllowedInstallersAndCertificates;
    }

    @Override
    public String toString() {
        return String.format(
                "AppInstallMetadata { PackageName = %s, AppCerts = %s, InstallerName = %s,"
                    + " InstallerCerts = %s, VersionCode = %d, PreInstalled = %b, StampPresent ="
                    + " %b, StampVerified = %b, StampTrusted = %b, StampCert = %s }",
                mPackageName,
                mAppCertificates,
                mAppCertificateLineage,
                mInstallerName == null ? "null" : mInstallerName,
                mInstallerCertificates == null ? "null" : mInstallerCertificates,
                mVersionCode,
                mIsPreInstalled,
                mIsStampPresent,
                mIsStampVerified,
                mIsStampTrusted,
                mStampCertificateHash == null ? "null" : mStampCertificateHash);
    }

    /** Builder class for constructing {@link AppInstallMetadata} objects. */
    public static final class Builder {
        private String mPackageName;
        private List<String> mAppCertificates;
        private List<String> mAppCertificateLineage;
        private String mInstallerName;
        private List<String> mInstallerCertificates;
        private long mVersionCode;
        private boolean mIsPreInstalled;
        private boolean mIsStampPresent;
        private boolean mIsStampVerified;
        private boolean mIsStampTrusted;
        private String mStampCertificateHash;
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
         * Set the list of (old and new) certificates used for signing the app to be installed.
         *
         * <p>It is represented as the raw string encoding for the SHA-256 hash of the certificate
         * lineage/history of the app.
         *
         * @see AppInstallMetadata#getAppCertificateLineage()
         */
        @NonNull
        public Builder setAppCertificateLineage(@NonNull List<String> appCertificateLineage) {
            this.mAppCertificateLineage = Objects.requireNonNull(appCertificateLineage);
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
         * Set whether the stamp embedded in the APK is present or not.
         *
         * @see AppInstallMetadata#isStampPresent()
         */
        @NonNull
        public Builder setIsStampPresent(boolean isStampPresent) {
            this.mIsStampPresent = isStampPresent;
            return this;
        }

        /**
         * Set whether the stamp embedded in the APK is verified or not.
         *
         * @see AppInstallMetadata#isStampVerified()
         */
        @NonNull
        public Builder setIsStampVerified(boolean isStampVerified) {
            this.mIsStampVerified = isStampVerified;
            return this;
        }

        /**
         * Set whether the stamp embedded in the APK is trusted or not.
         *
         * @see AppInstallMetadata#isStampTrusted()
         */
        @NonNull
        public Builder setIsStampTrusted(boolean isStampTrusted) {
            this.mIsStampTrusted = isStampTrusted;
            return this;
        }

        /**
         * Set certificate hash of the stamp embedded in the APK.
         *
         * <p>It is represented as the raw string encoding for the SHA-256 hash of the certificate
         * of the stamp.
         *
         * @see AppInstallMetadata#getStampCertificateHash()
         */
        @NonNull
        public Builder setStampCertificateHash(@NonNull String stampCertificateHash) {
            this.mStampCertificateHash = Objects.requireNonNull(stampCertificateHash);
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
            Objects.requireNonNull(mAppCertificateLineage);
            return new AppInstallMetadata(this);
        }
    }
}
