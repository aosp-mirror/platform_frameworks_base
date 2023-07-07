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

import static android.os.Process.INVALID_UID;

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
    static final InstallSource EMPTY = new InstallSource(null /* initiatingPackageName */,
            null /* originatingPackageName */, null /* installerPackageName */, INVALID_UID,
            null /* updateOwnerPackageName */, null /* installerAttributionTag */,
            false /* isOrphaned */, false /* isInitiatingPackageUninstalled */,
            null /* initiatingPackageSignatures */, PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);

    /** We also memoize this case because it is common - all un-updated system apps. */
    private static final InstallSource EMPTY_ORPHANED = new InstallSource(
            null /* initiatingPackageName */, null /* originatingPackageName */,
            null /* installerPackageName */, INVALID_UID, null /* updateOwnerPackageName */,
            null /* installerAttributionTag */, true /* isOrphaned */,
            false /* isInitiatingPackageUninstalled */, null /* initiatingPackageSignatures */,
            PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED);

    /**
     * The package that requested the installation, if known. May not correspond to a currently
     * installed package if {@link #mIsInitiatingPackageUninstalled} is true.
     */
    @Nullable
    final String mInitiatingPackageName;

    /**
     * The signing details of the initiating package, if known. Always null if
     * {@link #mInitiatingPackageName} is null.
     */
    @Nullable
    final PackageSignatures mInitiatingPackageSignatures;

    /**
     * The package on behalf of which the initiating package requested the installation, if any.
     * For example if a downloaded APK is installed via the Package Installer this could be the
     * app that performed the download. This value is provided by the initiating package and not
     * verified by the framework.
     */
    @Nullable
    final String mOriginatingPackageName;

    /**
     * Package name of the app that installed this package (the installer of record). Note that
     * this may be modified.
     */
    @Nullable
    final String mInstallerPackageName;

    /**
     * Package name of the app that requested the installer ownership. Note that this may be
     * modified.
     */
    @Nullable
    final String mUpdateOwnerPackageName;

    /**
     * UID of the installer package, corresponding to the {@link #mInstallerPackageName}.
     */
    final int mInstallerPackageUid;

    /**
     * {@link android.content.Context#getAttributionTag()} of installing context.
     */
    @Nullable
    final String mInstallerAttributionTag;

    /** Indicates if the package that was the installerPackageName has been uninstalled. */
    final boolean mIsOrphaned;

    /**
     * Indicates if the package in initiatingPackageName has been uninstalled. Always false if
     * {@link #mInitiatingPackageName} is null.
     */
    final boolean mIsInitiatingPackageUninstalled;

    final int mPackageSource;

    static InstallSource create(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            int installerPackageUid, @Nullable String updateOwnerPackageName,
            @Nullable String installerAttributionTag, boolean isOrphaned,
            boolean isInitiatingPackageUninstalled) {
        return create(initiatingPackageName, originatingPackageName, installerPackageName,
                installerPackageUid, updateOwnerPackageName, installerAttributionTag,
                PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED, isOrphaned,
                isInitiatingPackageUninstalled);
    }

    static InstallSource create(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            int installerPackageUid, @Nullable String updateOwnerPackageName,
            @Nullable String installerAttributionTag, int packageSource) {
        return create(initiatingPackageName, originatingPackageName, installerPackageName,
                installerPackageUid, updateOwnerPackageName, installerAttributionTag,
                packageSource, false /* isOrphaned */, false /* isInitiatingPackageUninstalled */);
    }

    static InstallSource create(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            int installerPackageUid, @Nullable String updateOwnerPackageName,
            @Nullable String installerAttributionTag, int packageSource, boolean isOrphaned,
            boolean isInitiatingPackageUninstalled) {
        return createInternal(
                intern(initiatingPackageName),
                intern(originatingPackageName),
                intern(installerPackageName),
                installerPackageUid,
                intern(updateOwnerPackageName),
                installerAttributionTag,
                packageSource,
                isOrphaned, isInitiatingPackageUninstalled,
                null /* initiatingPackageSignatures */);
    }

    private static InstallSource createInternal(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            int installerPackageUid, @Nullable String updateOwnerPackageName,
            @Nullable String installerAttributionTag, int packageSource, boolean isOrphaned,
            boolean isInitiatingPackageUninstalled,
            @Nullable PackageSignatures initiatingPackageSignatures) {
        if (initiatingPackageName == null && originatingPackageName == null
                && installerPackageName == null && updateOwnerPackageName == null
                && initiatingPackageSignatures == null
                && !isInitiatingPackageUninstalled
                && packageSource == PackageInstaller.PACKAGE_SOURCE_UNSPECIFIED) {
            return isOrphaned ? EMPTY_ORPHANED : EMPTY;
        }
        return new InstallSource(initiatingPackageName, originatingPackageName,
                installerPackageName, installerPackageUid, updateOwnerPackageName,
                installerAttributionTag, isOrphaned, isInitiatingPackageUninstalled,
                initiatingPackageSignatures, packageSource
        );
    }

    private InstallSource(@Nullable String initiatingPackageName,
            @Nullable String originatingPackageName, @Nullable String installerPackageName,
            int installerPackageUid, @Nullable String updateOwnerPackageName,
            @Nullable String installerAttributionTag, boolean isOrphaned,
            boolean isInitiatingPackageUninstalled,
            @Nullable PackageSignatures initiatingPackageSignatures,
            int packageSource) {
        if (initiatingPackageName == null) {
            Preconditions.checkArgument(initiatingPackageSignatures == null);
            Preconditions.checkArgument(!isInitiatingPackageUninstalled);
        }
        mInitiatingPackageName = initiatingPackageName;
        mOriginatingPackageName = originatingPackageName;
        mInstallerPackageName = installerPackageName;
        mInstallerPackageUid = installerPackageUid;
        mUpdateOwnerPackageName = updateOwnerPackageName;
        mInstallerAttributionTag = installerAttributionTag;
        mIsOrphaned = isOrphaned;
        mIsInitiatingPackageUninstalled = isInitiatingPackageUninstalled;
        mInitiatingPackageSignatures = initiatingPackageSignatures;
        mPackageSource = packageSource;
    }

    /**
     * Return an InstallSource the same as this one except with the specified
     * {@link #mInstallerPackageName}.
     */
    InstallSource setInstallerPackage(@Nullable String installerPackageName,
            int installerPackageUid) {
        if (Objects.equals(installerPackageName, mInstallerPackageName)) {
            return this;
        }
        return createInternal(mInitiatingPackageName, mOriginatingPackageName,
                intern(installerPackageName), installerPackageUid, mUpdateOwnerPackageName,
                mInstallerAttributionTag, mPackageSource, mIsOrphaned,
                mIsInitiatingPackageUninstalled, mInitiatingPackageSignatures);
    }

    /**
     * Return an InstallSource the same as this one except with the specified
     * {@link #mUpdateOwnerPackageName}.
     */
    InstallSource setUpdateOwnerPackageName(@Nullable String updateOwnerPackageName) {
        if (Objects.equals(updateOwnerPackageName, mUpdateOwnerPackageName)) {
            return this;
        }
        return createInternal(mInitiatingPackageName, mOriginatingPackageName,
                mInstallerPackageName, mInstallerPackageUid, intern(updateOwnerPackageName),
                mInstallerAttributionTag, mPackageSource, mIsOrphaned,
                mIsInitiatingPackageUninstalled, mInitiatingPackageSignatures);
    }

    /**
     * Return an InstallSource the same as this one except with the specified value for
     * {@link #mIsOrphaned}.
     */
    InstallSource setIsOrphaned(boolean isOrphaned) {
        if (isOrphaned == mIsOrphaned) {
            return this;
        }
        return createInternal(mInitiatingPackageName, mOriginatingPackageName,
                mInstallerPackageName, mInstallerPackageUid, mUpdateOwnerPackageName,
                mInstallerAttributionTag, mPackageSource, isOrphaned,
                mIsInitiatingPackageUninstalled, mInitiatingPackageSignatures);
    }

    /**
     * Return an InstallSource the same as this one except with the specified
     * {@link #mInitiatingPackageSignatures}.
     */
    InstallSource setInitiatingPackageSignatures(@Nullable PackageSignatures signatures) {
        if (signatures == mInitiatingPackageSignatures) {
            return this;
        }
        return createInternal(mInitiatingPackageName, mOriginatingPackageName,
                mInstallerPackageName, mInstallerPackageUid, mUpdateOwnerPackageName,
                mInstallerAttributionTag, mPackageSource, mIsOrphaned,
                mIsInitiatingPackageUninstalled, signatures);
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
        boolean isInitiatingPackageUninstalled = mIsInitiatingPackageUninstalled;
        String originatingPackageName = mOriginatingPackageName;
        String installerPackageName = mInstallerPackageName;
        String updateOwnerPackageName = mUpdateOwnerPackageName;
        int installerPackageUid = mInstallerPackageUid;
        boolean isOrphaned = mIsOrphaned;

        if (packageName.equals(mInitiatingPackageName)) {
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
            installerPackageUid = INVALID_UID;
            isOrphaned = true;
            modified = true;
        }
        if (packageName.equals(updateOwnerPackageName)) {
            updateOwnerPackageName = null;
            modified = true;
        }

        if (!modified) {
            return this;
        }

        return createInternal(mInitiatingPackageName, originatingPackageName, installerPackageName,
                installerPackageUid, updateOwnerPackageName,
                null /* installerAttributionTag */, mPackageSource, isOrphaned,
                isInitiatingPackageUninstalled, mInitiatingPackageSignatures);
    }

    @Nullable
    private static String intern(@Nullable String packageName) {
        return packageName == null ? null : packageName.intern();
    }
}
