/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.companion.association;

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
import static android.companion.AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION;

import static com.android.internal.util.CollectionUtils.any;
import static com.android.server.companion.utils.RolesUtils.removeRoleHolderForAssociation;

import static java.util.concurrent.TimeUnit.DAYS;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.companion.AssociationInfo;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.companion.datatransfer.SystemDataTransferRequestStore;
import com.android.server.companion.devicepresence.CompanionAppBinder;
import com.android.server.companion.devicepresence.DevicePresenceProcessor;
import com.android.server.companion.transport.CompanionTransportManager;

/**
 * This class responsible for disassociation.
 */
@SuppressLint("LongLogTag")
public class DisassociationProcessor {

    private static final String TAG = "CDM_DisassociationProcessor";

    private static final String SYS_PROP_DEBUG_REMOVAL_TIME_WINDOW =
            "debug.cdm.cdmservice.removal_time_window";
    private static final long ASSOCIATION_REMOVAL_TIME_WINDOW_DEFAULT = DAYS.toMillis(90);

    @NonNull
    private final Context mContext;
    @NonNull
    private final AssociationStore mAssociationStore;
    @NonNull
    private final PackageManagerInternal mPackageManagerInternal;
    @NonNull
    private final DevicePresenceProcessor mDevicePresenceMonitor;
    @NonNull
    private final SystemDataTransferRequestStore mSystemDataTransferRequestStore;
    @NonNull
    private final CompanionAppBinder mCompanionAppController;
    @NonNull
    private final CompanionTransportManager mTransportManager;
    private final OnPackageVisibilityChangeListener mOnPackageVisibilityChangeListener;
    private final ActivityManager mActivityManager;

    public DisassociationProcessor(@NonNull Context context,
            @NonNull ActivityManager activityManager,
            @NonNull AssociationStore associationStore,
            @NonNull PackageManagerInternal packageManager,
            @NonNull DevicePresenceProcessor devicePresenceMonitor,
            @NonNull CompanionAppBinder applicationController,
            @NonNull SystemDataTransferRequestStore systemDataTransferRequestStore,
            @NonNull CompanionTransportManager companionTransportManager) {
        mContext = context;
        mActivityManager = activityManager;
        mAssociationStore = associationStore;
        mPackageManagerInternal = packageManager;
        mOnPackageVisibilityChangeListener =
                new OnPackageVisibilityChangeListener();
        mDevicePresenceMonitor = devicePresenceMonitor;
        mCompanionAppController = applicationController;
        mSystemDataTransferRequestStore = systemDataTransferRequestStore;
        mTransportManager = companionTransportManager;
    }

    /**
     * Disassociate an association by id.
     */
    // TODO: also revoke notification access
    public void disassociate(int id) {
        Slog.i(TAG, "Disassociating id=[" + id + "]...");

        final AssociationInfo association = mAssociationStore.getAssociationWithCallerChecks(id);
        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        final String deviceProfile = association.getDeviceProfile();

        final boolean isRoleInUseByOtherAssociations = deviceProfile != null
                && any(mAssociationStore.getActiveAssociationsByPackage(userId, packageName),
                    it -> deviceProfile.equals(it.getDeviceProfile()) && id != it.getId());

        final int packageProcessImportance = getPackageProcessImportance(userId, packageName);
        if (packageProcessImportance <= IMPORTANCE_VISIBLE && deviceProfile != null
                && !isRoleInUseByOtherAssociations) {
            // Need to remove the app from the list of role holders, but the process is visible
            // to the user at the moment, so we'll need to do it later.
            Slog.i(TAG, "Cannot disassociate id=[" + id + "] now - process is visible. "
                    + "Start listening to package importance...");

            AssociationInfo revokedAssociation = (new AssociationInfo.Builder(
                    association)).setRevoked(true).build();
            mAssociationStore.updateAssociation(revokedAssociation);
            startListening();
            return;
        }

        // Detach transport if exists
        mTransportManager.detachSystemDataTransport(id);

        // Association cleanup.
        mSystemDataTransferRequestStore.removeRequestsByAssociationId(userId, id);
        mAssociationStore.removeAssociation(association.getId());

        // If role is not in use by other associations, revoke the role.
        // Do not need to remove the system role since it was pre-granted by the system.
        if (!isRoleInUseByOtherAssociations && deviceProfile != null && !deviceProfile.equals(
                DEVICE_PROFILE_AUTOMOTIVE_PROJECTION)) {
            removeRoleHolderForAssociation(mContext, association.getUserId(),
                    association.getPackageName(), association.getDeviceProfile());
        }

        // Unbind the app if needed.
        final boolean wasPresent = mDevicePresenceMonitor.isDevicePresent(id);
        if (!wasPresent || !association.isNotifyOnDeviceNearby()) {
            return;
        }
        final boolean shouldStayBound = any(
                mAssociationStore.getActiveAssociationsByPackage(userId, packageName),
                it -> it.isNotifyOnDeviceNearby()
                        && mDevicePresenceMonitor.isDevicePresent(it.getId()));
        if (!shouldStayBound) {
            mCompanionAppController.unbindCompanionApp(userId, packageName);
        }
    }

    /**
     * @deprecated Use {@link #disassociate(int)} instead.
     */
    @Deprecated
    public void disassociate(int userId, String packageName, String macAddress) {
        AssociationInfo association = mAssociationStore.getFirstAssociationByAddress(userId,
                packageName, macAddress);

        if (association == null) {
            throw new IllegalArgumentException(
                    "Association for mac address=[" + macAddress + "] doesn't exist");
        }

        mAssociationStore.getAssociationWithCallerChecks(association.getId());

        disassociate(association.getId());
    }

    @SuppressLint("MissingPermission")
    private int getPackageProcessImportance(@UserIdInt int userId, @NonNull String packageName) {
        return Binder.withCleanCallingIdentity(() -> {
            final int uid =
                    mPackageManagerInternal.getPackageUid(packageName, /* flags */0, userId);
            return mActivityManager.getUidImportance(uid);
        });
    }

    private void startListening() {
        Slog.i(TAG, "Start listening to uid importance changes...");
        try {
            Binder.withCleanCallingIdentity(
                    () -> mActivityManager.addOnUidImportanceListener(
                            mOnPackageVisibilityChangeListener,
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE));
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Failed to start listening to uid importance changes.");
        }
    }

    private void stopListening() {
        Slog.i(TAG, "Stop listening to uid importance changes.");
        try {
            Binder.withCleanCallingIdentity(() -> mActivityManager.removeOnUidImportanceListener(
                    mOnPackageVisibilityChangeListener));
        } catch (IllegalArgumentException e) {
            Slog.e(TAG, "Failed to stop listening to uid importance changes.");
        }
    }

    /**
     * Remove idle self-managed associations.
     */
    public void removeIdleSelfManagedAssociations() {
        Slog.i(TAG, "Removing idle self-managed associations.");

        final long currentTime = System.currentTimeMillis();
        long removalWindow = SystemProperties.getLong(SYS_PROP_DEBUG_REMOVAL_TIME_WINDOW, -1);
        if (removalWindow <= 0) {
            // 0 or negative values indicate that the sysprop was never set or should be ignored.
            removalWindow = ASSOCIATION_REMOVAL_TIME_WINDOW_DEFAULT;
        }

        for (AssociationInfo association : mAssociationStore.getAssociations()) {
            if (!association.isSelfManaged()) continue;

            final boolean isInactive =
                    currentTime - association.getLastTimeConnectedMs() >= removalWindow;
            if (!isInactive) continue;

            final int id = association.getId();

            Slog.i(TAG, "Removing inactive self-managed association=[" + association.toShortString()
                    + "].");
            disassociate(id);
        }
    }

    /**
     * An OnUidImportanceListener class which watches the importance of the packages.
     * In this class, we ONLY interested in the importance of the running process is greater than
     * {@link ActivityManager.RunningAppProcessInfo#IMPORTANCE_VISIBLE}.
     *
     * Lastly remove the role holder for the revoked associations for the same packages.
     *
     * @see #disassociate(int)
     */
    private class OnPackageVisibilityChangeListener implements
            ActivityManager.OnUidImportanceListener {

        @Override
        public void onUidImportance(int uid, int importance) {
            if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                // The lower the importance value the more "important" the process is.
                // We are only interested when the process ceases to be visible.
                return;
            }

            final String packageName = mPackageManagerInternal.getNameForUid(uid);
            if (packageName == null) {
                // Not interested in this uid.
                return;
            }

            int userId = UserHandle.getUserId(uid);
            for (AssociationInfo association : mAssociationStore.getRevokedAssociations(userId,
                    packageName)) {
                disassociate(association.getId());
            }

            if (mAssociationStore.getRevokedAssociations().isEmpty()) {
                stopListening();
            }
        }
    }
}
