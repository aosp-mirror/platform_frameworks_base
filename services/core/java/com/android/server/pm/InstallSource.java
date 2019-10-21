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

import com.android.internal.util.IndentingPrintWriter;

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
    static final InstallSource EMPTY = new InstallSource(null, null, false);

    /** The package that requested the installation, if known. */
    @Nullable
    final String initiatingPackageName;

    /**
     * Package name of the app that installed this package (the installer of record). Note that
     * this may be modified.
     */
    @Nullable
    final String installerPackageName;

    /** Indicates if the package that was the installerPackageName has been uninstalled. */
    final boolean isOrphaned;

    static InstallSource create(@Nullable String initiatingPackageName,
            @Nullable String installerPackageName) {
        return create(initiatingPackageName, installerPackageName, false);
    }

    static InstallSource create(@Nullable String initiatingPackageName,
            @Nullable String installerPackageName, boolean isOrphaned) {
        if (initiatingPackageName == null && installerPackageName == null && !isOrphaned) {
            return EMPTY;
        }
        return new InstallSource(
                initiatingPackageName == null ? null : initiatingPackageName.intern(),
                installerPackageName == null ? null : installerPackageName.intern(),
                isOrphaned);
    }

    private InstallSource(@Nullable String initiatingPackageName,
            @Nullable String installerPackageName, boolean isOrphaned) {
        this.initiatingPackageName = initiatingPackageName;
        this.isOrphaned = isOrphaned;
        this.installerPackageName = installerPackageName;
    }

    void dump(IndentingPrintWriter pw) {
        pw.printPair("installerPackageName", installerPackageName);
        pw.printPair("installInitiatingPackageName", initiatingPackageName);
    }

    /**
     * Return an InstallSource the same as this one except with the specified installerPackageName.
     */
    InstallSource setInstallerPackage(String installerPackageName) {
        return Objects.equals(installerPackageName, this.installerPackageName) ? this
                : create(initiatingPackageName, installerPackageName, isOrphaned);
    }

    /**
     * Return an InstallSource the same as this one except with the specified value for isOrphaned.
     */
    InstallSource setIsOrphaned(boolean isOrphaned) {
        return isOrphaned == this.isOrphaned ? this
                : create(initiatingPackageName, installerPackageName, isOrphaned);
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
        String installerPackageName = this.installerPackageName;
        boolean isOrphaned = this.isOrphaned;

        if (packageName.equals(initiatingPackageName)) {
            initiatingPackageName = null;
            modified = true;
        }
        if (packageName.equals(installerPackageName)) {
            installerPackageName = null;
            isOrphaned = true;
            modified = true;
        }

        return modified
                ? create(initiatingPackageName, installerPackageName, isOrphaned)
                : this;
    }
}
