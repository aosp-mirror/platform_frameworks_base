/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.pm.dex;

import static com.android.server.pm.PackageManagerServiceCompilerMapping.getCompilerFilterForReason;

import static dalvik.system.DexFile.isProfileGuidedCompilerFilter;

import android.annotation.Nullable;

import com.android.server.art.ReasonMapping;
import com.android.server.art.model.ArtFlags;
import com.android.server.art.model.DexoptParams;
import com.android.server.pm.DexOptHelper;
import com.android.server.pm.PackageManagerService;

/**
 * Options used for dexopt invocations.
 */
public final class DexoptOptions {
    // When set, the profiles will be checked for updates before calling dexopt. If
    // the apps profiles didn't update in a meaningful way (decided by the compiler), dexopt
    // will be skipped.
    // Currently this only affects the optimization of primary apks. Secondary dex files
    // will always check the profiles for updates.
    public static final int DEXOPT_CHECK_FOR_PROFILES_UPDATES = 1 << 0;

    // When set, dexopt will execute unconditionally (even if not needed).
    public static final int DEXOPT_FORCE = 1 << 1;

    // Whether or not the invocation of dexopt is done after the boot is completed. This is used
    // in order to adjust the priority of the compilation thread.
    public static final int DEXOPT_BOOT_COMPLETE = 1 << 2;

    // When set, the dexopt invocation will optimize only the secondary dex files. If false, dexopt
    // will only consider the primary apk.
    public static final int DEXOPT_ONLY_SECONDARY_DEX = 1 << 3;

    // When set, dexopt will attempt to scale down the optimizations previously applied in order
    // save disk space.
    public static final int DEXOPT_DOWNGRADE = 1 << 5;

    // When set, dexopt will compile the dex file as a shared library even if it is not actually
    // used by other apps. This is used to force the compilation or shared libraries declared
    // with in the manifest with ''uses-library' before we have a chance to detect they are
    // actually shared at runtime.
    public static final int DEXOPT_AS_SHARED_LIBRARY = 1 << 6;

    // When set, indicates that dexopt is invoked from the background service.
    public static final int DEXOPT_IDLE_BACKGROUND_JOB = 1 << 9;

    // When set, indicates that dexopt is invoked from the install time flow and
    // should get the dex metdata file if present.
    public static final int DEXOPT_INSTALL_WITH_DEX_METADATA_FILE = 1 << 10;

    // When set, indicates that dexopt is being invoked from the install flow during device restore
    // or device setup and should be scheduled appropriately.
    public static final int DEXOPT_FOR_RESTORE = 1 << 11; // TODO(b/135202722): remove

    /**
     * A value indicating that dexopt shouldn't be run.  This string is only used when loading
     * filters from the `pm.dexopt.install*` properties and is not propagated to dex2oat.
     */
    public static final String COMPILER_FILTER_NOOP = "skip";

    // The name of package to optimize.
    private final String mPackageName;

    // The intended target compiler filter. Note that dexopt might adjust the filter before the
    // execution based on factors like: vmSafeMode and packageUsedByOtherApps.
    private final String mCompilerFilter;

    // The set of flags for the dexopt options. It's a mix of the DEXOPT_* flags.
    private final int mFlags;

    // When not null, dexopt will optimize only the split identified by this name.
    // It only applies for primary apk and it's always null if mOnlySecondaryDex is true.
    private final String mSplitName;

    // The reason for invoking dexopt (see PackageManagerService.REASON_* constants).
    // A -1 value denotes an unknown reason.
    private final int mCompilationReason;

    public DexoptOptions(String packageName, String compilerFilter, int flags) {
        this(packageName, /*compilationReason*/ -1, compilerFilter, /*splitName*/ null, flags);
    }

    public DexoptOptions(String packageName, int compilationReason, int flags) {
        this(packageName, compilationReason, getCompilerFilterForReason(compilationReason),
                /*splitName*/ null, flags);
    }

    public DexoptOptions(String packageName, int compilationReason, String compilerFilter,
                String splitName, int flags) {
        int validityMask =
                DEXOPT_CHECK_FOR_PROFILES_UPDATES |
                DEXOPT_FORCE |
                DEXOPT_BOOT_COMPLETE |
                DEXOPT_ONLY_SECONDARY_DEX |
                DEXOPT_DOWNGRADE |
                DEXOPT_AS_SHARED_LIBRARY |
                DEXOPT_IDLE_BACKGROUND_JOB |
                DEXOPT_INSTALL_WITH_DEX_METADATA_FILE |
                DEXOPT_FOR_RESTORE;
        if ((flags & (~validityMask)) != 0) {
            throw new IllegalArgumentException("Invalid flags : " + Integer.toHexString(flags));
        }

        mPackageName = packageName;
        mCompilerFilter = compilerFilter;
        mFlags = flags;
        mSplitName = splitName;
        mCompilationReason = compilationReason;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public boolean isCheckForProfileUpdates() {
        return (mFlags & DEXOPT_CHECK_FOR_PROFILES_UPDATES) != 0;
    }

    public String getCompilerFilter() {
        return mCompilerFilter;
    }

    public boolean isForce() {
        return (mFlags & DEXOPT_FORCE) != 0;
    }

    public boolean isBootComplete() {
        return (mFlags & DEXOPT_BOOT_COMPLETE) != 0;
    }

    public boolean isDexoptOnlySecondaryDex() {
        return (mFlags & DEXOPT_ONLY_SECONDARY_DEX) != 0;
    }

    public boolean isDowngrade() {
        return (mFlags & DEXOPT_DOWNGRADE) != 0;
    }

    public boolean isDexoptAsSharedLibrary() {
        return (mFlags & DEXOPT_AS_SHARED_LIBRARY) != 0;
    }

    public boolean isDexoptIdleBackgroundJob() {
        return (mFlags & DEXOPT_IDLE_BACKGROUND_JOB) != 0;
    }

    public boolean isDexoptInstallWithDexMetadata() {
        return (mFlags & DEXOPT_INSTALL_WITH_DEX_METADATA_FILE) != 0;
    }

    public boolean isDexoptInstallForRestore() {
        return (mFlags & DEXOPT_FOR_RESTORE) != 0;
    }

    public String getSplitName() {
        return mSplitName;
    }

    public int getFlags() {
        return mFlags;
    }

    public int getCompilationReason() {
        return mCompilationReason;
    }

    public boolean isCompilationEnabled() {
        return !mCompilerFilter.equals(COMPILER_FILTER_NOOP);
    }

    /**
     * Creates a new set of DexoptOptions which are the same with the exception of the compiler
     * filter (set to the given value).
     */
    public DexoptOptions overrideCompilerFilter(String newCompilerFilter) {
        return new DexoptOptions(
                mPackageName,
                mCompilationReason,
                newCompilerFilter,
                mSplitName,
                mFlags);
    }

    /**
     * Returns an {@link DexoptParams} instance corresponding to this object, for use with
     * {@link com.android.server.art.ArtManagerLocal}.
     *
     * @param extraFlags extra {@link ArtFlags#DexoptFlags} to set in the returned
     *     {@code DexoptParams} beyond those converted from this object
     * @return null if the settings cannot be accurately represented, and hence the old
     *     PackageManager/installd code paths need to be used.
     */
    public @Nullable DexoptParams convertToDexoptParams(/*@DexoptFlags*/ int extraFlags) {
        if (mSplitName != null) {
            DexOptHelper.reportArtManagerFallback(
                    mPackageName, "Request to optimize only split " + mSplitName);
            return null;
        }

        /*@DexoptFlags*/ int flags = extraFlags;
        if ((mFlags & DEXOPT_CHECK_FOR_PROFILES_UPDATES) == 0
                && isProfileGuidedCompilerFilter(mCompilerFilter)) {
            // ART Service doesn't support bypassing the profile update check when profiles are
            // used, so not setting this flag is not supported.
            throw new IllegalArgumentException(
                    "DEXOPT_CHECK_FOR_PROFILES_UPDATES must be set with profile guided filter");
        }
        if ((mFlags & DEXOPT_FORCE) != 0) {
            flags |= ArtFlags.FLAG_FORCE;
        }
        if ((mFlags & DEXOPT_ONLY_SECONDARY_DEX) != 0) {
            flags |= ArtFlags.FLAG_FOR_SECONDARY_DEX;
        } else {
            flags |= ArtFlags.FLAG_FOR_PRIMARY_DEX;
        }
        if ((mFlags & DEXOPT_DOWNGRADE) != 0) {
            flags |= ArtFlags.FLAG_SHOULD_DOWNGRADE;
        }
        if ((mFlags & DEXOPT_INSTALL_WITH_DEX_METADATA_FILE) == 0) {
            // ART Service cannot be instructed to ignore a DM file if present, so not setting this
            // flag is not supported.
            DexOptHelper.reportArtManagerFallback(
                    mPackageName, "DEXOPT_INSTALL_WITH_DEX_METADATA_FILE not set");
            return null;
        }

        /*@PriorityClassApi*/ int priority;
        // Replicates logic in RunDex2Oat::PrepareCompilerRuntimeAndPerfConfigFlags in installd.
        if ((mFlags & DEXOPT_BOOT_COMPLETE) != 0) {
            if ((mFlags & DEXOPT_FOR_RESTORE) != 0) {
                priority = ArtFlags.PRIORITY_INTERACTIVE_FAST;
            } else {
                // TODO(b/251903639): Repurpose DEXOPT_IDLE_BACKGROUND_JOB to choose new
                // dalvik.vm.background-dex2oat-* properties.
                priority = ArtFlags.PRIORITY_INTERACTIVE;
            }
        } else {
            priority = ArtFlags.PRIORITY_BOOT;
        }

        // The following flags in mFlags are ignored:
        //
        // -  DEXOPT_AS_SHARED_LIBRARY: It's implicit with ART Service since it always looks at
        //    <uses-library> rather than actual dependencies.
        //
        //    We don't require it to be set either. It's safe when switching between old and new
        //    code paths since the only effect is that some packages may be unnecessarily compiled
        //    without user profiles.
        //
        // -  DEXOPT_IDLE_BACKGROUND_JOB: Its only effect is to allow the debug variant dex2oatd to
        //    be used, but ART Service never uses that (cf. Artd::GetDex2Oat in artd.cc).

        String reason;
        switch (mCompilationReason) {
            case PackageManagerService.REASON_FIRST_BOOT:
                reason = ReasonMapping.REASON_FIRST_BOOT;
                break;
            case PackageManagerService.REASON_BOOT_AFTER_OTA:
                reason = ReasonMapping.REASON_BOOT_AFTER_OTA;
                break;
            case PackageManagerService.REASON_POST_BOOT:
                // This reason will go away with the legacy dexopt code.
                DexOptHelper.reportArtManagerFallback(
                        mPackageName, "Unsupported compilation reason REASON_POST_BOOT");
                return null;
            case PackageManagerService.REASON_INSTALL:
                reason = ReasonMapping.REASON_INSTALL;
                break;
            case PackageManagerService.REASON_INSTALL_FAST:
                reason = ReasonMapping.REASON_INSTALL_FAST;
                break;
            case PackageManagerService.REASON_INSTALL_BULK:
                reason = ReasonMapping.REASON_INSTALL_BULK;
                break;
            case PackageManagerService.REASON_INSTALL_BULK_SECONDARY:
                reason = ReasonMapping.REASON_INSTALL_BULK_SECONDARY;
                break;
            case PackageManagerService.REASON_INSTALL_BULK_DOWNGRADED:
                reason = ReasonMapping.REASON_INSTALL_BULK_DOWNGRADED;
                break;
            case PackageManagerService.REASON_INSTALL_BULK_SECONDARY_DOWNGRADED:
                reason = ReasonMapping.REASON_INSTALL_BULK_SECONDARY_DOWNGRADED;
                break;
            case PackageManagerService.REASON_BACKGROUND_DEXOPT:
                reason = ReasonMapping.REASON_BG_DEXOPT;
                break;
            case PackageManagerService.REASON_INACTIVE_PACKAGE_DOWNGRADE:
                reason = ReasonMapping.REASON_INACTIVE;
                break;
            case PackageManagerService.REASON_CMDLINE:
                reason = ReasonMapping.REASON_CMDLINE;
                break;
            case PackageManagerService.REASON_SHARED:
            case PackageManagerService.REASON_AB_OTA:
                // REASON_SHARED shouldn't go into this code path - it's only used at lower levels
                // in PackageDexOptimizer.
                // TODO(b/251921228): OTA isn't supported, so REASON_AB_OTA shouldn't come this way
                // either.
                throw new UnsupportedOperationException(
                        "ART Service unsupported compilation reason " + mCompilationReason);
            default:
                throw new IllegalArgumentException(
                        "Invalid compilation reason " + mCompilationReason);
        }

        return new DexoptParams.Builder(reason, flags)
                .setCompilerFilter(mCompilerFilter)
                .setPriorityClass(priority)
                .build();
    }
}
