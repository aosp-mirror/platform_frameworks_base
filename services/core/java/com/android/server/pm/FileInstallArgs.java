/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.content.pm.PackageManager.INSTALL_STAGED;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.os.incremental.IncrementalManager.isIncrementalPath;

import static com.android.internal.content.NativeLibraryHelper.LIB_DIR_NAME;
import static com.android.server.pm.InstructionSets.getDexCodeInstructionSets;
import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.TAG;
import static com.android.server.pm.PackageManagerServiceUtils.makeDirRecursive;

import android.content.pm.DataLoaderType;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.os.Environment;
import android.os.FileUtils;
import android.os.SELinux;
import android.os.Trace;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Slog;

import com.android.internal.content.NativeLibraryHelper;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * Logic to handle installation of new applications, including copying
 * and renaming logic.
 */
class FileInstallArgs extends InstallArgs {
    private File mCodeFile;

    // Example topology:
    // /data/app/com.example/base.apk
    // /data/app/com.example/split_foo.apk
    // /data/app/com.example/lib/arm/libfoo.so
    // /data/app/com.example/lib/arm64/libfoo.so
    // /data/app/com.example/dalvik/arm/base.apk@classes.dex

    /** New install */
    FileInstallArgs(InstallParams params) {
        super(params);
    }

    /** Existing install */
    FileInstallArgs(String codePath, String[] instructionSets, PackageManagerService pm) {
        super(OriginInfo.fromNothing(), null, null, 0, InstallSource.EMPTY,
                null, null, instructionSets, null, null, null, MODE_DEFAULT, null, 0,
                SigningDetails.UNKNOWN,
                PackageManager.INSTALL_REASON_UNKNOWN, PackageManager.INSTALL_SCENARIO_DEFAULT,
                false, DataLoaderType.NONE, pm);
        mCodeFile = (codePath != null) ? new File(codePath) : null;
    }

    int copyApk() {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "copyApk");
        try {
            return doCopyApk();
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    private int doCopyApk() {
        if (mOriginInfo.mStaged) {
            if (DEBUG_INSTALL) Slog.d(TAG, mOriginInfo.mFile + " already staged; skipping copy");
            mCodeFile = mOriginInfo.mFile;
            return PackageManager.INSTALL_SUCCEEDED;
        }

        try {
            final boolean isEphemeral = (mInstallFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
            final File tempDir =
                    mPm.mInstallerService.allocateStageDirLegacy(mVolumeUuid, isEphemeral);
            mCodeFile = tempDir;
        } catch (IOException e) {
            Slog.w(TAG, "Failed to create copy file: " + e);
            return PackageManager.INSTALL_FAILED_INSUFFICIENT_STORAGE;
        }

        int ret = PackageManagerServiceUtils.copyPackage(
                mOriginInfo.mFile.getAbsolutePath(), mCodeFile);
        if (ret != PackageManager.INSTALL_SUCCEEDED) {
            Slog.e(TAG, "Failed to copy package");
            return ret;
        }

        final boolean isIncremental = isIncrementalPath(mCodeFile.getAbsolutePath());
        final File libraryRoot = new File(mCodeFile, LIB_DIR_NAME);
        NativeLibraryHelper.Handle handle = null;
        try {
            handle = NativeLibraryHelper.Handle.create(mCodeFile);
            ret = NativeLibraryHelper.copyNativeBinariesWithOverride(handle, libraryRoot,
                    mAbiOverride, isIncremental);
        } catch (IOException e) {
            Slog.e(TAG, "Copying native libraries failed", e);
            ret = PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
        } finally {
            IoUtils.closeQuietly(handle);
        }

        return ret;
    }

    int doPreInstall(int status) {
        if (status != PackageManager.INSTALL_SUCCEEDED) {
            cleanUp();
        }
        return status;
    }

    @Override
    boolean doRename(int status, ParsedPackage parsedPackage) {
        if (status != PackageManager.INSTALL_SUCCEEDED) {
            cleanUp();
            return false;
        }

        final File targetDir = resolveTargetDir();
        final File beforeCodeFile = mCodeFile;
        final File afterCodeFile = PackageManagerServiceUtils.getNextCodePath(targetDir,
                parsedPackage.getPackageName());

        if (DEBUG_INSTALL) Slog.d(TAG, "Renaming " + beforeCodeFile + " to " + afterCodeFile);
        final boolean onIncremental = mPm.mIncrementalManager != null
                && isIncrementalPath(beforeCodeFile.getAbsolutePath());
        try {
            makeDirRecursive(afterCodeFile.getParentFile(), 0775);
            if (onIncremental) {
                // Just link files here. The stage dir will be removed when the installation
                // session is completed.
                mPm.mIncrementalManager.linkCodePath(beforeCodeFile, afterCodeFile);
            } else {
                Os.rename(beforeCodeFile.getAbsolutePath(), afterCodeFile.getAbsolutePath());
            }
        } catch (IOException | ErrnoException e) {
            Slog.w(TAG, "Failed to rename", e);
            return false;
        }

        if (!onIncremental && !SELinux.restoreconRecursive(afterCodeFile)) {
            Slog.w(TAG, "Failed to restorecon");
            return false;
        }

        // Reflect the rename internally
        mCodeFile = afterCodeFile;

        // Reflect the rename in scanned details
        try {
            parsedPackage.setPath(afterCodeFile.getCanonicalPath());
        } catch (IOException e) {
            Slog.e(TAG, "Failed to get path: " + afterCodeFile, e);
            return false;
        }
        parsedPackage.setBaseApkPath(FileUtils.rewriteAfterRename(beforeCodeFile,
                afterCodeFile, parsedPackage.getBaseApkPath()));
        parsedPackage.setSplitCodePaths(FileUtils.rewriteAfterRename(beforeCodeFile,
                afterCodeFile, parsedPackage.getSplitCodePaths()));

        return true;
    }

    // TODO(b/168126411): Once staged install flow starts using the same folder as non-staged
    //  flow, we won't need this method anymore.
    private File resolveTargetDir() {
        boolean isStagedInstall = (mInstallFlags & INSTALL_STAGED) != 0;
        if (isStagedInstall) {
            return Environment.getDataAppDirectory(null);
        } else {
            return mCodeFile.getParentFile();
        }
    }

    int doPostInstall(int status, int uid) {
        if (status != PackageManager.INSTALL_SUCCEEDED) {
            cleanUp();
        }
        return status;
    }

    @Override
    String getCodePath() {
        return (mCodeFile != null) ? mCodeFile.getAbsolutePath() : null;
    }

    private boolean cleanUp() {
        if (mCodeFile == null || !mCodeFile.exists()) {
            return false;
        }
        mPm.removeCodePathLI(mCodeFile);
        return true;
    }

    void cleanUpResourcesLI() {
        // Try enumerating all code paths before deleting
        List<String> allCodePaths = Collections.EMPTY_LIST;
        if (mCodeFile != null && mCodeFile.exists()) {
            final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
            final ParseResult<PackageLite> result = ApkLiteParseUtils.parsePackageLite(
                    input.reset(), mCodeFile, /* flags */ 0);
            if (result.isSuccess()) {
                // Ignore error; we tried our best
                allCodePaths = result.getResult().getAllApkPaths();
            }
        }

        cleanUp();
        removeDexFiles(allCodePaths, mInstructionSets);
    }

    void removeDexFiles(List<String> allCodePaths, String[] instructionSets) {
        if (!allCodePaths.isEmpty()) {
            if (instructionSets == null) {
                throw new IllegalStateException("instructionSet == null");
            }
            String[] dexCodeInstructionSets = getDexCodeInstructionSets(instructionSets);
            for (String codePath : allCodePaths) {
                for (String dexCodeInstructionSet : dexCodeInstructionSets) {
                    try {
                        mPm.mInstaller.rmdex(codePath, dexCodeInstructionSet);
                    } catch (Installer.InstallerException ignored) {
                    }
                }
            }
        }
    }

    boolean doPostDeleteLI(boolean delete) {
        // XXX err, shouldn't we respect the delete flag?
        cleanUpResourcesLI();
        return true;
    }
}
