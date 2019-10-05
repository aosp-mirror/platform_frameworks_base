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

/**
 * The app install metadata.
 *
 * <p>The integrity component retrieves metadata for app installs from package manager, passing it
 * to the rule evaluation engine to evaluate the metadata against the rules.
 */
public final class AppInstallMetadata {
    final String mPackageName;
    // Raw string encoding for the SHA-256 hash of the certificate of the app.
    final String mAppCertificate;
    final String mInstallerName;
    // Raw string encoding for the SHA-256 hash of the certificate of the installer.
    final String mInstallerCertificate;
    final int mVersionCode;
    final boolean mIsPreInstalled;

    public AppInstallMetadata(String packageName, String appCertificate, String installerName,
            String installerCertificate, int versionCode, boolean isPreInstalled) {
        this.mPackageName = packageName;
        this.mAppCertificate = appCertificate;
        this.mInstallerName = installerName;
        this.mInstallerCertificate = installerCertificate;
        this.mVersionCode = versionCode;
        this.mIsPreInstalled = isPreInstalled;
    }
}
