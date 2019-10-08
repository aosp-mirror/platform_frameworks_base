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

package com.android.server.integrity.model;

import static com.android.internal.util.Preconditions.checkArgument;
import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.Nullable;

/**
 * The app install metadata.
 *
 * <p>The integrity component retrieves metadata for app installs from package manager, passing it
 * to the rule evaluation engine to evaluate the metadata against the rules.
 *
 * <p>Instances of this class are immutable.
 */
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

    public String getPackageName() {
        return mPackageName;
    }

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

    /**
     * @see AppInstallMetadata.Builder#setVersionCode(int)
     */
    public int getVersionCode() {
        return mVersionCode;
    }

    /**
     * @see AppInstallMetadata.Builder#setIsPreInstalled(boolean)
     */
    public boolean isPreInstalled() {
        return mIsPreInstalled;
    }

    /**
     * Builder class for constructing {@link AppInstallMetadata} objects.
     */
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
        public Builder setPackageName(String packageName) {
            this.mPackageName = checkNotNull(packageName);
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
        public Builder setAppCertificate(String appCertificate) {
            this.mAppCertificate = checkNotNull(appCertificate);
            return this;
        }

        /**
         * Set name of the installer installing the app.
         *
         * @see AppInstallMetadata#getInstallerName()
         */
        public Builder setInstallerName(String installerName) {
            this.mInstallerName = checkNotNull(installerName);
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
        public Builder setInstallerCertificate(String installerCertificate) {
            this.mInstallerCertificate = checkNotNull(installerCertificate);
            return this;
        }

        /**
         * Set version code of the app to be installed.
         *
         * @see AppInstallMetadata#getVersionCode()
         */
        public Builder setVersionCode(int versionCode) {
            this.mVersionCode = versionCode;
            return this;
        }

        /**
         * Set whether the app is pre-installed on the device or not.
         *
         * @see AppInstallMetadata#isPreInstalled()
         */
        public Builder setIsPreInstalled(boolean isPreInstalled) {
            this.mIsPreInstalled = isPreInstalled;
            return this;
        }

        /**
         * Build {@link AppInstallMetadata}.
         */
        public AppInstallMetadata build() {
            checkArgument(mPackageName != null);
            checkArgument(mAppCertificate != null);
            return new AppInstallMetadata(this);
        }
    }
}
