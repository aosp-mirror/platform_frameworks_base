/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.Nullable;

import java.util.Objects;

/**
 * Immutable class holding information about where the request to install or update an app
 * came from.
 */
final class InstallSource {
    /**
     * An instance of InstallSource representing an absence of knowledge of the source of
     * a package. Used in preference to null.
     */
    static final InstallSource EMPTY = new InstallSource(null, null, null, false);

    /** The package that requested the installation, if known. */
    @Nullable
    final String initiatingPackageName;

    /**
     * The package on behalf of which the initiating package requested the installation, if any.
     * For example if a downloaded APK is installed via the Package Installer this could be the
     * app that performed the download. This value is provided by the initiating package and not
     * verified by the framework.
     */
    @Nullable
    final String originatingPackageName;

    /**
     * Package name of the app that installed this package (the installer of record). Note that
     * this may be modified.
     */
    @Nullable
    final String installerPackageName;

    /** Indicates if the package that was the installerPackageName has been uninstalled. */
    final boolean isOrphaned;

    static InstallSource create(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            boolean isOrphaned) {

        if (initiatingPackageName == null && originatingPackageName == null
                && installerPackageName == null && !isOrphaned) {
            return EMPTY;
        }
        return new InstallSource(
                initiatingPackageName == null ? null : initiatingPackageName.intern(),
                originatingPackageName == null ? null : originatingPackageName.intern(),
                installerPackageName == null ? null : installerPackageName.intern(),
                isOrphaned);
    }

    private InstallSource(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            boolean isOrphaned) {
        this.initiatingPackageName = initiatingPackageName;
        this.originatingPackageName = originatingPackageName;
        this.installerPackageName = installerPackageName;
        this.isOrphaned = isOrphaned;
    }

    /**
     * Return an InstallSource the same as this one except with the specified installerPackageName.
     */
    InstallSource setInstallerPackage(String installerPackageName) {
        if (Objects.equals(installerPackageName, this.installerPackageName)) {
            return this;
        }
        return create(initiatingPackageName, originatingPackageName, installerPackageName,
                isOrphaned);
    }

    /**
     * Return an InstallSource the same as this one except with the specified value for isOrphaned.
     */
    InstallSource setIsOrphaned(boolean isOrphaned) {
        if (isOrphaned == this.isOrphaned) {
            return this;
        }
        return create(initiatingPackageName, originatingPackageName, installerPackageName,
                isOrphaned);
    }

    /**
     * Return an InstallSource the same as this one except it does not refer to the specified
     * installer package name (which is being uninstalled).
     */
    InstallSource removeInstallerPackage(String packageName) {
        if (packageName == null) {
            return this;
        }

        boolean modified = false;
        String initiatingPackageName = this.initiatingPackageName;
        String originatingPackageName = this.originatingPackageName;
        String installerPackageName = this.installerPackageName;
        boolean isOrphaned = this.isOrphaned;

        if (packageName.equals(initiatingPackageName)) {
            initiatingPackageName = null;
            modified = true;
        }
        if (packageName.equals(originatingPackageName)) {
            originatingPackageName = null;
            modified = true;
        }
        if (packageName.equals(installerPackageName)) {
            installerPackageName = null;
            isOrphaned = true;
            modified = true;
        }

        if (!modified) {
            return this;
        }
        return create(initiatingPackageName, originatingPackageName, installerPackageName,
                isOrphaned);
    }
}
