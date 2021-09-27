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

import static android.os.PowerExemptionManager.REASON_PACKAGE_REPLACED;
import static android.os.PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.annotation.NonNull;
import android.app.ActivityManagerInternal;
import android.app.BroadcastOptions;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerExemptionManager;
import android.util.SparseArray;

import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;

public final class PackageRemovedInfo {
    final PackageSender mPackageSender;
    String mRemovedPackage;
    String mInstallerPackageName;
    int mUid = -1;
    int mRemovedAppId = -1;
    int[] mOrigUsers;
    int[] mRemovedUsers = null;
    int[] mBroadcastUsers = null;
    int[] mInstantUserIds = null;
    SparseArray<Integer> mInstallReasons;
    SparseArray<Integer> mUninstallReasons;
    boolean mIsRemovedPackageSystemUpdate = false;
    boolean mIsUpdate;
    boolean mDataRemoved;
    boolean mRemovedForAllUsers;
    boolean mIsStaticSharedLib;
    // a two dimensional array mapping userId to the set of appIds that can receive notice
    // of package changes
    SparseArray<int[]> mBroadcastAllowList;
    // Clean up resources deleted packages.
    InstallArgs mArgs = null;
    private static final int[] EMPTY_INT_ARRAY = new int[0];

    PackageRemovedInfo(PackageSender packageSender) {
        mPackageSender = packageSender;
    }

    void sendPackageRemovedBroadcasts(boolean killApp, boolean removedBySystem) {
        sendPackageRemovedBroadcastInternal(killApp, removedBySystem);
    }

    void sendSystemPackageUpdatedBroadcasts() {
        if (mIsRemovedPackageSystemUpdate) {
            sendSystemPackageUpdatedBroadcastsInternal();
        }
    }

    private void sendSystemPackageUpdatedBroadcastsInternal() {
        Bundle extras = new Bundle(2);
        extras.putInt(Intent.EXTRA_UID, mRemovedAppId >= 0 ? mRemovedAppId : mUid);
        extras.putBoolean(Intent.EXTRA_REPLACING, true);
        mPackageSender.sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED, mRemovedPackage, extras,
                0, null /*targetPackage*/, null, null, null, mBroadcastAllowList, null);
        mPackageSender.sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED, mRemovedPackage,
                extras, 0, null /*targetPackage*/, null, null, null, mBroadcastAllowList, null);
        mPackageSender.sendPackageBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED, null, null, 0,
                mRemovedPackage, null, null, null, null /* broadcastAllowList */,
                getTemporaryAppAllowlistBroadcastOptions(REASON_PACKAGE_REPLACED).toBundle());
        if (mInstallerPackageName != null) {
            mPackageSender.sendPackageBroadcast(Intent.ACTION_PACKAGE_ADDED,
                    mRemovedPackage, extras, 0 /*flags*/,
                    mInstallerPackageName, null, null, null, null /* broadcastAllowList */,
                    null);
            mPackageSender.sendPackageBroadcast(Intent.ACTION_PACKAGE_REPLACED,
                    mRemovedPackage, extras, 0 /*flags*/,
                    mInstallerPackageName, null, null, null, null /* broadcastAllowList */,
                    null);
        }
    }

    private static @NonNull BroadcastOptions getTemporaryAppAllowlistBroadcastOptions(
            @PowerExemptionManager.ReasonCode int reasonCode) {
        long duration = 10_000;
        final ActivityManagerInternal amInternal =
                LocalServices.getService(ActivityManagerInternal.class);
        if (amInternal != null) {
            duration = amInternal.getBootTimeTempAllowListDuration();
        }
        final BroadcastOptions bOptions = BroadcastOptions.makeBasic();
        bOptions.setTemporaryAppAllowlist(duration,
                TEMPORARY_ALLOW_LIST_TYPE_FOREGROUND_SERVICE_ALLOWED,
                reasonCode, "");
        return bOptions;
    }

    private void sendPackageRemovedBroadcastInternal(boolean killApp, boolean removedBySystem) {
        // Don't send static shared library removal broadcasts as these
        // libs are visible only the apps that depend on them an one
        // cannot remove the library if it has a dependency.
        if (mIsStaticSharedLib) {
            return;
        }
        Bundle extras = new Bundle(2);
        final int removedUid = mRemovedAppId >= 0  ? mRemovedAppId : mUid;
        extras.putInt(Intent.EXTRA_UID, removedUid);
        extras.putBoolean(Intent.EXTRA_DATA_REMOVED, mDataRemoved);
        extras.putBoolean(Intent.EXTRA_DONT_KILL_APP, !killApp);
        extras.putBoolean(Intent.EXTRA_USER_INITIATED, !removedBySystem);
        if (mIsUpdate || mIsRemovedPackageSystemUpdate) {
            extras.putBoolean(Intent.EXTRA_REPLACING, true);
        }
        extras.putBoolean(Intent.EXTRA_REMOVED_FOR_ALL_USERS, mRemovedForAllUsers);
        if (mRemovedPackage != null) {
            mPackageSender.sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED,
                    mRemovedPackage, extras, 0, null /*targetPackage*/, null,
                    mBroadcastUsers, mInstantUserIds, mBroadcastAllowList, null);
            if (mInstallerPackageName != null) {
                mPackageSender.sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED,
                        mRemovedPackage, extras, 0 /*flags*/,
                        mInstallerPackageName, null, mBroadcastUsers, mInstantUserIds, null, null);
            }
            mPackageSender.sendPackageBroadcast(Intent.ACTION_PACKAGE_REMOVED_INTERNAL,
                    mRemovedPackage, extras, 0 /*flags*/, PLATFORM_PACKAGE_NAME,
                    null /*finishedReceiver*/, mBroadcastUsers, mInstantUserIds,
                    mBroadcastAllowList, null /*bOptions*/);
            if (mDataRemoved && !mIsRemovedPackageSystemUpdate) {
                mPackageSender.sendPackageBroadcast(Intent.ACTION_PACKAGE_FULLY_REMOVED,
                        mRemovedPackage, extras, Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND, null,
                        null, mBroadcastUsers, mInstantUserIds, mBroadcastAllowList, null);
                mPackageSender.notifyPackageRemoved(mRemovedPackage, removedUid);
            }
        }
        if (mRemovedAppId >= 0) {
            // If a system app's updates are uninstalled the UID is not actually removed. Some
            // services need to know the package name affected.
            if (extras.getBoolean(Intent.EXTRA_REPLACING, false)) {
                extras.putString(Intent.EXTRA_PACKAGE_NAME, mRemovedPackage);
            }

            mPackageSender.sendPackageBroadcast(Intent.ACTION_UID_REMOVED,
                    null, extras, Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND,
                    null, null, mBroadcastUsers, mInstantUserIds, mBroadcastAllowList, null);
        }
    }

    public void populateUsers(int[] userIds, PackageSetting deletedPackageSetting) {
        mRemovedUsers = userIds;
        if (mRemovedUsers == null) {
            mBroadcastUsers = null;
            return;
        }

        mBroadcastUsers = EMPTY_INT_ARRAY;
        mInstantUserIds = EMPTY_INT_ARRAY;
        for (int i = userIds.length - 1; i >= 0; --i) {
            final int userId = userIds[i];
            if (deletedPackageSetting.getInstantApp(userId)) {
                mInstantUserIds = ArrayUtils.appendInt(mInstantUserIds, userId);
            } else {
                mBroadcastUsers = ArrayUtils.appendInt(mBroadcastUsers, userId);
            }
        }
    }
}
