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

import static android.os.storage.StorageManager.FLAG_STORAGE_CE;
import static android.os.storage.StorageManager.FLAG_STORAGE_DE;

import static com.android.server.pm.PackageManagerService.DEBUG_INSTALL;
import static com.android.server.pm.PackageManagerService.TAG;

import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Slog;

import com.android.server.pm.parsing.pkg.ParsedPackage;

import java.io.File;

/**
 * Logic to handle movement of existing installed applications.
 */
final class MoveInstallArgs extends InstallArgs {
    private File mCodeFile;

    /** New install */
    MoveInstallArgs(InstallParams params) {
        super(params);
    }

    int copyApk() {
        if (DEBUG_INSTALL) {
            Slog.d(TAG, "Moving " + mMoveInfo.mPackageName + " from "
                    + mMoveInfo.mFromUuid + " to " + mMoveInfo.mToUuid);
        }
        synchronized (mPm.mInstaller) {
            try {
                mPm.mInstaller.moveCompleteApp(mMoveInfo.mFromUuid, mMoveInfo.mToUuid,
                        mMoveInfo.mPackageName, mMoveInfo.mAppId, mMoveInfo.mSeInfo,
                        mMoveInfo.mTargetSdkVersion, mMoveInfo.mFromCodePath);
            } catch (Installer.InstallerException e) {
                Slog.w(TAG, "Failed to move app", e);
                return PackageManager.INSTALL_FAILED_INTERNAL_ERROR;
            }
        }

        final String toPathName = new File(mMoveInfo.mFromCodePath).getName();
        mCodeFile = new File(Environment.getDataAppDirectory(mMoveInfo.mToUuid), toPathName);
        if (DEBUG_INSTALL) Slog.d(TAG, "codeFile after move is " + mCodeFile);

        return PackageManager.INSTALL_SUCCEEDED;
    }

    int doPreInstall(int status) {
        if (status != PackageManager.INSTALL_SUCCEEDED) {
            cleanUp(mMoveInfo.mToUuid);
        }
        return status;
    }

    @Override
    boolean doRename(int status, ParsedPackage parsedPackage) {
        if (status != PackageManager.INSTALL_SUCCEEDED) {
            cleanUp(mMoveInfo.mToUuid);
            return false;
        }

        return true;
    }

    int doPostInstall(int status, int uid) {
        if (status == PackageManager.INSTALL_SUCCEEDED) {
            cleanUp(mMoveInfo.mFromUuid);
        } else {
            cleanUp(mMoveInfo.mToUuid);
        }
        return status;
    }

    @Override
    String getCodePath() {
        return (mCodeFile != null) ? mCodeFile.getAbsolutePath() : null;
    }

    private void cleanUp(String volumeUuid) {
        final String toPathName = new File(mMoveInfo.mFromCodePath).getName();
        final File codeFile = new File(Environment.getDataAppDirectory(volumeUuid),
                toPathName);
        Slog.d(TAG, "Cleaning up " + mMoveInfo.mPackageName + " on " + volumeUuid);
        final int[] userIds = mPm.mUserManager.getUserIds();
        synchronized (mPm.mInstallLock) {
            // Clean up both app data and code
            // All package moves are frozen until finished

            // We purposefully exclude FLAG_STORAGE_EXTERNAL here, since
            // this task was only focused on moving data on internal storage.
            // We don't want ART profiles cleared, because they don't move,
            // so we would be deleting the only copy (b/149200535).
            final int flags = FLAG_STORAGE_DE | FLAG_STORAGE_CE
                    | Installer.FLAG_CLEAR_APP_DATA_KEEP_ART_PROFILES;
            for (int userId : userIds) {
                try {
                    mPm.mInstaller.destroyAppData(volumeUuid, mMoveInfo.mPackageName, userId, flags,
                            0);
                } catch (Installer.InstallerException e) {
                    Slog.w(TAG, String.valueOf(e));
                }
            }
            mRemovePackageHelper.removeCodePathLI(codeFile);
        }
    }

    void cleanUpResourcesLI() {
        throw new UnsupportedOperationException();
    }

    boolean doPostDeleteLI(boolean delete) {
        throw new UnsupportedOperationException();
    }
}
