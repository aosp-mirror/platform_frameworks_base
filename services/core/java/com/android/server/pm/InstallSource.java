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
import android.content.pm.PackageInstaller;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Immutable class holding information about where the request to install or update an app
 * came from.
 */
public final class InstallSource {
    /**
     * An instance of InstallSource representing an absence of knowledge of the source of
     * a package. Used in preference to null.
     */
    static final InstallSource EMPTY = new InstallSource(null, null, null, null, false, false,
            null, PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);

    /** We also memoize this case because it is common - all un-updated system apps. */
    private static final InstallSource EMPTY_ORPHANED = new InstallSource(
            null, null, null, null, true, false, null,
            PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);

    /**
     * The package that requested the installation, if known. May not correspond to a currently
     * installed package if {@link #isInitiatingPackageUninstalled} is true.
     */
    @Nullable
    final String initiatingPackageName;

    /**
     * The signing details of the initiating package, if known. Always null if
     * {@link #initiatingPackageName} is null.
     */
    @Nullable
    final PackageSignatures initiatingPackageSignatures;

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


    /**
     * {@link android.content.Context#getAttributionTag()} of installing context.
     */
    @Nullable
    final String installerAttributionTag;

    /** Indicates if the package that was the installerPackageName has been uninstalled. */
    final boolean isOrphaned;

    /**
     * Indicates if the package in initiatingPackageName has been uninstalled. Always false if
     * {@link #initiatingPackageName} is null.
     */
    final boolean isInitiatingPackageUninstalled;

    final int packageSource;

    static InstallSource create(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            @Nullable String installerAttributionTag) {
        return create(initiatingPackageName, originatingPackageName, installerPackageName,
                installerAttributionTag, PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);
    }

    static InstallSource create(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            @Nullable String installerAttributionTag, boolean isOrphaned,
            boolean isInitiatingPackageUninstalled) {
        return create(initiatingPackageName, originatingPackageName, installerPackageName,
                installerAttributionTag, PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED, isOrphaned,
                isInitiatingPackageUninstalled);
    }

    static InstallSource create(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            @Nullable String installerAttributionTag, int packageSource) {
        return create(initiatingPackageName, originatingPackageName, installerPackageName,
                installerAttributionTag, packageSource, false, false);
    }

    static InstallSource create(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            @Nullable String installerAttributionTag, int packageSource, boolean isOrphaned,
            boolean isInitiatingPackageUninstalled) {
        return createInternal(
                intern(initiatingPackageName),
                intern(originatingPackageName),
                intern(installerPackageName),
                installerAttributionTag,
                packageSource,
                isOrphaned, isInitiatingPackageUninstalled, null);
    }

    private static InstallSource createInternal(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            @Nullable String installerAttributionTag, int packageSource, boolean isOrphaned,
            boolean isInitiatingPackageUninstalled,
            @Nullable PackageSignatures initiatingPackageSignatures) {
        if (initiatingPackageName == null && originatingPackageName == null
                && installerPackageName == null && initiatingPackageSignatures == null
                && !isInitiatingPackageUninstalled) {
            return isOrphaned ? EMPTY_ORPHANED : EMPTY;
        }
        return new InstallSource(initiatingPackageName, originatingPackageName,
                installerPackageName, installerAttributionTag, isOrphaned,
                isInitiatingPackageUninstalled, initiatingPackageSignatures, packageSource
        );
    }

    private InstallSource(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            @Nullable String installerAttributionTag, boolean isOrphaned,
            boolean isInitiatingPackageUninstalled,
            @Nullable PackageSignatures initiatingPackageSignatures,
            int packageSource) {
        if (initiatingPackageName == null) {
            Preconditions.checkArgument(initiatingPackageSignatures == null);
            Preconditions.checkArgument(!isInitiatingPackageUninstalled);
        }
        this.initiatingPackageName = initiatingPackageName;
        this.originatingPackageName = originatingPackageName;
        this.installerPackageName = installerPackageName;
        this.installerAttributionTag = installerAttributionTag;
        this.isOrphaned = isOrphaned;
        this.isInitiatingPackageUninstalled = isInitiatingPackageUninstalled;
        this.initiatingPackageSignatures = initiatingPackageSignatures;
        this.packageSource = packageSource;
    }

    /**
     * Return an InstallSource the same as this one except with the specified
     * {@link #installerPackageName}.
     */
    InstallSource setInstallerPackage(@Nullable String installerPackageName) {
        if (Objects.equals(installerPackageName, this.installerPackageName)) {
            return this;
        }
        return createInternal(initiatingPackageName, originatingPackageName,
                intern(installerPackageName), installerAttributionTag, packageSource, isOrphaned,
                isInitiatingPackageUninstalled, initiatingPackageSignatures);
    }

    /**
     * Return an InstallSource the same as this one except with the specified value for
     * {@link #isOrphaned}.
     */
    InstallSource setIsOrphaned(boolean isOrphaned) {
        if (isOrphaned == this.isOrphaned) {
            return this;
        }
        return createInternal(initiatingPackageName, originatingPackageName, installerPackageName,
                installerAttributionTag, packageSource, isOrphaned, isInitiatingPackageUninstalled,
                initiatingPackageSignatures);
    }

    /**
     * Return an InstallSource the same as this one except with the specified
     * {@link #initiatingPackageSignatures}.
     */
    InstallSource setInitiatingPackageSignatures(@Nullable PackageSignatures signatures) {
        if (signatures == initiatingPackageSignatures) {
            return this;
        }
        return createInternal(initiatingPackageName, originatingPackageName, installerPackageName,
                installerAttributionTag, packageSource, isOrphaned,
                isInitiatingPackageUninstalled, signatures);
    }

    /**
     * Return an InstallSource the same as this one updated to reflect that the specified installer
     * package name has been uninstalled.
     */
    InstallSource removeInstallerPackage(@Nullable String packageName) {
        if (packageName == null) {
            return this;
        }

        boolean modified = false;
        boolean isInitiatingPackageUninstalled = this.isInitiatingPackageUninstalled;
        String originatingPackageName = this.originatingPackageName;
        String installerPackageName = this.installerPackageName;
        boolean isOrphaned = this.isOrphaned;

        if (packageName.equals(this.initiatingPackageName)) {
            if (!isInitiatingPackageUninstalled) {
                // In this case we deliberately do not clear the package name (and signatures).
                // We allow an app to retrieve details of its own install initiator even after
                // it has been uninstalled.
                isInitiatingPackageUninstalled = true;
                modified = true;
            }
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

        return createInternal(initiatingPackageName, originatingPackageName, installerPackageName,
                null, packageSource, isOrphaned,
                isInitiatingPackageUninstalled, initiatingPackageSignatures);
    }

    @Nullable
    private static String intern(@Nullable String packageName) {
        return packageName == null ? null : packageName.intern();
    }
}
