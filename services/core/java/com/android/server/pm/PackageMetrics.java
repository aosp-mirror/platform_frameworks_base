/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.IntDef;
import android.app.ActivityManager;
import android.app.admin.SecurityLog;
import android.content.pm.PackageManager;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.os.UserHandle;
import android.util.Pair;
import android.util.SparseArray;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.LocalServices;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Metrics class for reporting stats to logging infrastructures like Westworld
 */
final class PackageMetrics {
    public static final int STEP_PREPARE = 1;
    public static final int STEP_SCAN = 2;
    public static final int STEP_RECONCILE = 3;
    public static final int STEP_COMMIT = 4;

    @IntDef(prefix = {"STEP_"}, value = {
            STEP_PREPARE,
            STEP_SCAN,
            STEP_RECONCILE,
            STEP_COMMIT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StepInt {
    }

    private final long mInstallStartTimestampMillis;
    private final SparseArray<InstallStep> mInstallSteps = new SparseArray<>();
    private final InstallRequest mInstallRequest;

    PackageMetrics(InstallRequest installRequest) {
        // New instance is used for tracking installation metrics only.
        // Other metrics should use static methods of this class.
        mInstallStartTimestampMillis = System.currentTimeMillis();
        mInstallRequest = installRequest;
    }

    public void onInstallSucceed() {
        reportInstallationToSecurityLog(mInstallRequest.getUserId());
        reportInstallationStats(true /* success */);
    }

    public void onInstallFailed() {
        reportInstallationStats(false /* success */);
    }

    private void reportInstallationStats(boolean success) {
        final UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        if (userManagerInternal == null) {
            // UserManagerService is not available. Skip metrics reporting.
            return;
        }

        final long installDurationMillis =
                System.currentTimeMillis() - mInstallStartTimestampMillis;
        // write to stats
        final Pair<int[], long[]> stepDurations = getInstallStepDurations();
        final int[] newUsers = mInstallRequest.getNewUsers();
        final int[] originalUsers = mInstallRequest.getOriginUsers();
        final String packageName;
        // only reporting package name for failed non-adb installations
        if (success || mInstallRequest.isInstallFromAdb()) {
            packageName = null;
        } else {
            packageName = mInstallRequest.getName();
        }

        final int installerPackageUid = mInstallRequest.getInstallerPackageUid();

        long versionCode = 0, apksSize = 0;
        if (success) {
            final PackageSetting ps = mInstallRequest.getScannedPackageSetting();
            if (ps != null) {
                versionCode = ps.getVersionCode();
                apksSize = getApksSize(ps.getPath());
            }
        }


        FrameworkStatsLog.write(FrameworkStatsLog.PACKAGE_INSTALLATION_SESSION_REPORTED,
                mInstallRequest.getSessionId() /* session_id */,
                packageName /* package_name */,
                getUid(mInstallRequest.getAppId(), mInstallRequest.getUserId()) /* uid */,
                newUsers /* user_ids */,
                userManagerInternal.getUserTypesForStatsd(newUsers) /* user_types */,
                originalUsers /* original_user_ids */,
                userManagerInternal.getUserTypesForStatsd(originalUsers) /* original_user_types */,
                mInstallRequest.getReturnCode() /* public_return_code */,
                0 /* internal_error_code */,
                apksSize /* apks_size_bytes */,
                versionCode /* version_code */,
                stepDurations.first /* install_steps */,
                stepDurations.second /* step_duration_millis */,
                installDurationMillis /* total_duration_millis */,
                mInstallRequest.getInstallFlags() /* install_flags */,
                installerPackageUid /* installer_package_uid */,
                -1 /* original_installer_package_uid */,
                mInstallRequest.getDataLoaderType() /* data_loader_type */,
                mInstallRequest.getRequireUserAction() /* user_action_required_type */,
                mInstallRequest.isInstantInstall() /* is_instant */,
                mInstallRequest.isInstallReplace() /* is_replace */,
                mInstallRequest.isInstallSystem() /* is_system */,
                mInstallRequest.isInstallInherit() /* is_inherit */,
                mInstallRequest.isInstallForUsers() /* is_installing_existing_as_user */,
                mInstallRequest.isInstallMove() /* is_move_install */,
                false /* is_staged */
        );
    }

    private static int getUid(int appId, int userId) {
        if (userId == UserHandle.USER_ALL) {
            userId = ActivityManager.getCurrentUser();
        }
        return UserHandle.getUid(userId, appId);
    }

    private long getApksSize(File apkDir) {
        // TODO(b/249294752): also count apk sizes for failed installs
        final AtomicLong apksSize = new AtomicLong();
        try (Stream<Path> walkStream = Files.walk(apkDir.toPath())) {
            walkStream.filter(p -> p.toFile().isFile()
                    && ApkLiteParseUtils.isApkFile(p.toFile())).forEach(
                            f -> apksSize.addAndGet(f.toFile().length()));
        } catch (IOException e) {
            // ignore
        }
        return apksSize.get();
    }

    public void onStepStarted(@StepInt int step) {
        mInstallSteps.put(step, new InstallStep());
    }

    public void onStepFinished(@StepInt int step) {
        final InstallStep installStep = mInstallSteps.get(step);
        if (installStep != null) {
            // Only valid if the start timestamp is set; otherwise no-op
            installStep.finish();
        }
    }

    // List of steps (e.g., 1, 2, 3) and corresponding list of durations (e.g., 200ms, 100ms, 150ms)
    private Pair<int[], long[]> getInstallStepDurations() {
        ArrayList<Integer> steps = new ArrayList<>();
        ArrayList<Long> durations = new ArrayList<>();
        for (int i = 0; i < mInstallSteps.size(); i++) {
            final long duration = mInstallSteps.valueAt(i).getDurationMillis();
            if (duration >= 0) {
                steps.add(mInstallSteps.keyAt(i));
                durations.add(mInstallSteps.valueAt(i).getDurationMillis());
            }
        }
        int[] stepsArray = new int[steps.size()];
        long[] durationsArray = new long[durations.size()];
        for (int i = 0; i < stepsArray.length; i++) {
            stepsArray[i] = steps.get(i);
            durationsArray[i] = durations.get(i);
        }
        return new Pair<>(stepsArray, durationsArray);
    }

    private static class InstallStep {
        private final long mStartTimestampMillis;
        private long mDurationMillis = -1;

        InstallStep() {
            mStartTimestampMillis = System.currentTimeMillis();
        }

        void finish() {
            mDurationMillis = System.currentTimeMillis() - mStartTimestampMillis;
        }

        long getDurationMillis() {
            return mDurationMillis;
        }
    }

    public static void onUninstallSucceeded(PackageRemovedInfo info, int deleteFlags, int userId) {
        if (info.mIsUpdate) {
            // Not logging uninstalls caused by app updates
            return;
        }
        final UserManagerInternal userManagerInternal =
                LocalServices.getService(UserManagerInternal.class);
        if (userManagerInternal == null) {
            // UserManagerService is not available. Skip metrics reporting.
            return;
        }
        final int[] removedUsers = info.mRemovedUsers;
        final int[] removedUserTypes = userManagerInternal.getUserTypesForStatsd(removedUsers);
        final int[] originalUsers = info.mOrigUsers;
        final int[] originalUserTypes = userManagerInternal.getUserTypesForStatsd(originalUsers);
        FrameworkStatsLog.write(FrameworkStatsLog.PACKAGE_UNINSTALLATION_REPORTED,
                getUid(info.mUid, userId), removedUsers, removedUserTypes, originalUsers,
                originalUserTypes, deleteFlags, PackageManager.DELETE_SUCCEEDED,
                info.mIsRemovedPackageSystemUpdate, !info.mRemovedForAllUsers);
        final String packageName = info.mRemovedPackage;
        final long versionCode = info.mRemovedPackageVersionCode;
        reportUninstallationToSecurityLog(packageName, versionCode, userId);
    }

    public static void onVerificationFailed(VerifyingSession verifyingSession) {
        FrameworkStatsLog.write(FrameworkStatsLog.PACKAGE_INSTALLATION_SESSION_REPORTED,
                verifyingSession.getSessionId() /* session_id */,
                null /* package_name */,
                INVALID_UID /* uid */,
                null /* user_ids */,
                null /* user_types */,
                null /* original_user_ids */,
                null /* original_user_types */,
                verifyingSession.getRet() /* public_return_code */,
                0 /* internal_error_code */,
                0 /* apks_size_bytes */,
                0 /* version_code */,
                null /* install_steps */,
                null /* step_duration_millis */,
                0 /* total_duration_millis */,
                0 /* install_flags */,
                verifyingSession.getInstallerPackageUid() /* installer_package_uid */,
                INVALID_UID /* original_installer_package_uid */,
                verifyingSession.getDataLoaderType() /* data_loader_type */,
                verifyingSession.getUserActionRequiredType() /* user_action_required_type */,
                verifyingSession.isInstant() /* is_instant */,
                false /* is_replace */,
                false /* is_system */,
                verifyingSession.isInherit() /* is_inherit */,
                false /* is_installing_existing_as_user */,
                false /* is_move_install */,
                verifyingSession.isStaged() /* is_staged */
        );
    }

    private void reportInstallationToSecurityLog(int userId) {
        if (!SecurityLog.isLoggingEnabled()) {
            return;
        }
        final PackageSetting ps = mInstallRequest.getScannedPackageSetting();
        if (ps == null) {
            return;
        }
        final String packageName = ps.getPackageName();
        final long versionCode = ps.getVersionCode();
        if (!mInstallRequest.isInstallReplace()) {
            SecurityLog.writeEvent(SecurityLog.TAG_PACKAGE_INSTALLED, packageName, versionCode,
                    userId);
        } else {
            SecurityLog.writeEvent(SecurityLog.TAG_PACKAGE_UPDATED, packageName, versionCode,
                    userId);
        }
    }

    private static void reportUninstallationToSecurityLog(String packageName, long versionCode,
            int userId) {
        if (!SecurityLog.isLoggingEnabled()) {
            return;
        }
        SecurityLog.writeEvent(SecurityLog.TAG_PACKAGE_UNINSTALLED, packageName, versionCode,
                userId);
    }
}
