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
import static com.android.server.companion.utils.MetricUtils.logRemoveAssociation;
import static com.android.server.companion.utils.RolesUtils.removeRoleHolderForAssociation;
import static com.android.server.companion.CompanionDeviceManagerService.PerUserAssociationSet;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.companion.AssociationInfo;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.companion.CompanionApplicationController;
import com.android.server.companion.CompanionDeviceManagerService;
import com.android.server.companion.datatransfer.SystemDataTransferRequestStore;
import com.android.server.companion.presence.CompanionDevicePresenceMonitor;
import com.android.server.companion.transport.CompanionTransportManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A class response for Association removal.
 */
@SuppressLint("LongLogTag")
public class AssociationRevokeProcessor {

    private static final String TAG = "CDM_AssociationRevokeProcessor";
    private static final boolean DEBUG = false;
    private final @NonNull Context mContext;
    private final @NonNull CompanionDeviceManagerService mService;
    private final @NonNull AssociationStore mAssociationStore;
    private final @NonNull PackageManagerInternal mPackageManagerInternal;
    private final @NonNull CompanionDevicePresenceMonitor mDevicePresenceMonitor;
    private final @NonNull SystemDataTransferRequestStore mSystemDataTransferRequestStore;
    private final @NonNull CompanionApplicationController mCompanionAppController;
    private final @NonNull CompanionTransportManager mTransportManager;
    private final OnPackageVisibilityChangeListener mOnPackageVisibilityChangeListener;
    private final ActivityManager mActivityManager;

    /**
     * A structure that consists of a set of revoked associations that pending for role holder
     * removal per each user.
     *
     * @see #maybeRemoveRoleHolderForAssociation(AssociationInfo)
     * @see #addToPendingRoleHolderRemoval(AssociationInfo)
     * @see #removeFromPendingRoleHolderRemoval(AssociationInfo)
     * @see #getPendingRoleHolderRemovalAssociationsForUser(int)
     */
    @GuardedBy("mRevokedAssociationsPendingRoleHolderRemoval")
    private final PerUserAssociationSet mRevokedAssociationsPendingRoleHolderRemoval =
            new PerUserAssociationSet();
    /**
     * Contains uid-s of packages pending to be removed from the role holder list (after
     * revocation of an association), which will happen one the package is no longer visible to the
     * user.
     * For quicker uid -> (userId, packageName) look-up this is not a {@code Set<Integer>} but
     * a {@code Map<Integer, String>} which maps uid-s to packageName-s (userId-s can be derived
     * from uid-s using {@link UserHandle#getUserId(int)}).
     *
     * @see #maybeRemoveRoleHolderForAssociation(AssociationInfo)
     * @see #addToPendingRoleHolderRemoval(AssociationInfo)
     * @see #removeFromPendingRoleHolderRemoval(AssociationInfo)
     */
    @GuardedBy("mRevokedAssociationsPendingRoleHolderRemoval")
    private final Map<Integer, String> mUidsPendingRoleHolderRemoval = new HashMap<>();

    public AssociationRevokeProcessor(@NonNull CompanionDeviceManagerService service,
            @NonNull AssociationStore associationStore,
            @NonNull PackageManagerInternal packageManager,
            @NonNull CompanionDevicePresenceMonitor devicePresenceMonitor,
            @NonNull CompanionApplicationController applicationController,
            @NonNull SystemDataTransferRequestStore systemDataTransferRequestStore,
            @NonNull CompanionTransportManager companionTransportManager) {
        mService = service;
        mContext = service.getContext();
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mAssociationStore = associationStore;
        mPackageManagerInternal = packageManager;
        mOnPackageVisibilityChangeListener =
                new OnPackageVisibilityChangeListener(mActivityManager);
        mDevicePresenceMonitor = devicePresenceMonitor;
        mCompanionAppController = applicationController;
        mSystemDataTransferRequestStore = systemDataTransferRequestStore;
        mTransportManager = companionTransportManager;
    }

    /**
     * Disassociate an association
     */
    // TODO: also revoke notification access
    public void disassociateInternal(int associationId) {
        final AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        final String deviceProfile = association.getDeviceProfile();

        // Detach transport if exists
        mTransportManager.detachSystemDataTransport(packageName, userId, associationId);

        if (!maybeRemoveRoleHolderForAssociation(association)) {
            // Need to remove the app from list of the role holders, but will have to do it later
            // (the app is in foreground at the moment).
            addToPendingRoleHolderRemoval(association);
        }

        // Need to check if device still present now because CompanionDevicePresenceMonitor will
        // remove current connected device after mAssociationStore.removeAssociation
        final boolean wasPresent = mDevicePresenceMonitor.isDevicePresent(associationId);

        // Removing the association.
        mAssociationStore.removeAssociation(associationId);
        // Do not need to persistUserState since CompanionDeviceManagerService will get callback
        // from #onAssociationChanged, and it will handle the persistUserState which including
        // active and revoked association.
        logRemoveAssociation(deviceProfile);

        // Remove all the system data transfer requests for the association.
        mSystemDataTransferRequestStore.removeRequestsByAssociationId(userId, associationId);

        if (!wasPresent || !association.isNotifyOnDeviceNearby()) return;
        // The device was connected and the app was notified: check if we need to unbind the app
        // now.
        final boolean shouldStayBound = any(
                mAssociationStore.getAssociationsForPackage(userId, packageName),
                it -> it.isNotifyOnDeviceNearby()
                        && mDevicePresenceMonitor.isDevicePresent(it.getId()));
        if (shouldStayBound) return;
        mCompanionAppController.unbindCompanionApplication(userId, packageName);
    }

    /**
     * First, checks if the companion application should be removed from the list role holders when
     * upon association's removal, i.e.: association's profile (matches the role) is not null,
     * the application does not have other associations with the same profile, etc.
     *
     * <p>
     * Then, if establishes that the application indeed has to be removed from the list of the role
     * holders, checks if it could be done right now -
     * {@link android.app.role.RoleManager#removeRoleHolderAsUser(String, String, int, UserHandle, java.util.concurrent.Executor, java.util.function.Consumer) RoleManager#removeRoleHolderAsUser()}
     * will kill the application's process, which leads poor user experience if the application was
     * in foreground when this happened, to avoid this CDMS delays invoking
     * {@code RoleManager.removeRoleHolderAsUser()} until the app is no longer in foreground.
     *
     * @return {@code true} if the application does NOT need be removed from the list of the role
     *         holders OR if the application was successfully removed from the list of role holders.
     *         I.e.: from the role-management perspective the association is done with.
     *         {@code false} if the application needs to be removed from the list of role the role
     *         holders, BUT it CDMS would prefer to do it later.
     *         I.e.: application is in the foreground at the moment, but invoking
     *         {@code RoleManager.removeRoleHolderAsUser()} will kill the application's process,
     *         which would lead to the poor UX, hence need to try later.
     */
    public boolean maybeRemoveRoleHolderForAssociation(@NonNull AssociationInfo association) {
        if (DEBUG) Log.d(TAG, "maybeRemoveRoleHolderForAssociation() association=" + association);
        final String deviceProfile = association.getDeviceProfile();

        if (deviceProfile == null) {
            // No role was granted to for this association, there is nothing else we need to here.
            return true;
        }
        // Do not need to remove the system role since it was pre-granted by the system.
        if (deviceProfile.equals(DEVICE_PROFILE_AUTOMOTIVE_PROJECTION)) {
            return true;
        }

        // Check if the applications is associated with another devices with the profile. If so,
        // it should remain the role holder.
        final int id = association.getId();
        final int userId = association.getUserId();
        final String packageName = association.getPackageName();
        final boolean roleStillInUse = any(
                mAssociationStore.getAssociationsForPackage(userId, packageName),
                it -> deviceProfile.equals(it.getDeviceProfile()) && id != it.getId());
        if (roleStillInUse) {
            // Application should remain a role holder, there is nothing else we need to here.
            return true;
        }

        final int packageProcessImportance = getPackageProcessImportance(userId, packageName);
        if (packageProcessImportance <= IMPORTANCE_VISIBLE) {
            // Need to remove the app from the list of role holders, but the process is visible to
            // the user at the moment, so we'll need to it later: log and return false.
            Slog.i(TAG, "Cannot remove role holder for the removed association id=" + id
                    + " now - process is visible.");
            return false;
        }

        removeRoleHolderForAssociation(mContext, association.getUserId(),
                association.getPackageName(), association.getDeviceProfile());
        return true;
    }

    /**
     * Set revoked flag for active association and add the revoked association and the uid into
     * the caches.
     *
     * @see #mRevokedAssociationsPendingRoleHolderRemoval
     * @see #mUidsPendingRoleHolderRemoval
     * @see OnPackageVisibilityChangeListener
     */
    public void addToPendingRoleHolderRemoval(@NonNull AssociationInfo association) {
        // First: set revoked flag
        association = (new AssociationInfo.Builder(association)).setRevoked(true).build();
        final String packageName = association.getPackageName();
        final int userId = association.getUserId();
        final int uid = mPackageManagerInternal.getPackageUid(packageName, /* flags */0, userId);
        // Second: add to the set.
        synchronized (mRevokedAssociationsPendingRoleHolderRemoval) {
            mRevokedAssociationsPendingRoleHolderRemoval.forUser(association.getUserId())
                    .add(association);
            if (!mUidsPendingRoleHolderRemoval.containsKey(uid)) {
                mUidsPendingRoleHolderRemoval.put(uid, packageName);

                if (mUidsPendingRoleHolderRemoval.size() == 1) {
                    // Just added first uid: start the listener
                    mOnPackageVisibilityChangeListener.startListening();
                }
            }
        }
    }

    /**
     * @return a copy of the revoked associations set (safeguarding against
     *         {@code ConcurrentModificationException}-s).
     */
    @NonNull
    public Set<AssociationInfo> getPendingRoleHolderRemovalAssociationsForUser(
            @UserIdInt int userId) {
        synchronized (mRevokedAssociationsPendingRoleHolderRemoval) {
            // Return a copy.
            return new ArraySet<>(mRevokedAssociationsPendingRoleHolderRemoval.forUser(userId));
        }
    }

    @SuppressLint("MissingPermission")
    private int  getPackageProcessImportance(@UserIdInt int userId, @NonNull String packageName) {
        return Binder.withCleanCallingIdentity(() -> {
            final int uid =
                    mPackageManagerInternal.getPackageUid(packageName, /* flags */0, userId);
            return mActivityManager.getUidImportance(uid);
        });
    }

    /**
     * Remove the revoked association from the cache and also remove the uid from the map if
     * there are other associations with the same package still pending for role holder removal.
     *
     * @see #mRevokedAssociationsPendingRoleHolderRemoval
     * @see #mUidsPendingRoleHolderRemoval
     * @see OnPackageVisibilityChangeListener
     */
    private void removeFromPendingRoleHolderRemoval(@NonNull AssociationInfo association) {
        final String packageName = association.getPackageName();
        final int userId = association.getUserId();
        final int uid = mPackageManagerInternal.getPackageUid(packageName, /* flags */  0, userId);

        synchronized (mRevokedAssociationsPendingRoleHolderRemoval) {
            mRevokedAssociationsPendingRoleHolderRemoval.forUser(userId)
                    .remove(association);

            final boolean shouldKeepUidForRemoval = any(
                    getPendingRoleHolderRemovalAssociationsForUser(userId),
                    ai -> packageName.equals(ai.getPackageName()));
            // Do not remove the uid from the map since other associations with
            // the same packageName still pending for role holder removal.
            if (!shouldKeepUidForRemoval) {
                mUidsPendingRoleHolderRemoval.remove(uid);
            }

            if (mUidsPendingRoleHolderRemoval.isEmpty()) {
                // The set is empty now - can "turn off" the listener.
                mOnPackageVisibilityChangeListener.stopListening();
            }
        }
    }

    private String getPackageNameByUid(int uid) {
        synchronized (mRevokedAssociationsPendingRoleHolderRemoval) {
            return mUidsPendingRoleHolderRemoval.get(uid);
        }
    }

    /**
     * An OnUidImportanceListener class which watches the importance of the packages.
     * In this class, we ONLY interested in the importance of the running process is greater than
     * {@link ActivityManager.RunningAppProcessInfo#IMPORTANCE_VISIBLE} for the uids have been added
     * into the {@link #mUidsPendingRoleHolderRemoval}. Lastly remove the role holder for the
     * revoked associations for the same packages.
     *
     * @see #maybeRemoveRoleHolderForAssociation(AssociationInfo)
     * @see #removeFromPendingRoleHolderRemoval(AssociationInfo)
     * @see #getPendingRoleHolderRemovalAssociationsForUser(int)
     */
    private class OnPackageVisibilityChangeListener implements
            ActivityManager.OnUidImportanceListener {
        final @NonNull ActivityManager mAm;

        OnPackageVisibilityChangeListener(@NonNull ActivityManager am) {
            this.mAm = am;
        }

        @SuppressLint("MissingPermission")
        void startListening() {
            Binder.withCleanCallingIdentity(
                    () -> mAm.addOnUidImportanceListener(
                            /* listener */ OnPackageVisibilityChangeListener.this,
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE));
        }

        @SuppressLint("MissingPermission")
        void stopListening() {
            Binder.withCleanCallingIdentity(
                    () -> mAm.removeOnUidImportanceListener(
                            /* listener */ OnPackageVisibilityChangeListener.this));
        }

        @Override
        public void onUidImportance(int uid, int importance) {
            if (importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                // The lower the importance value the more "important" the process is.
                // We are only interested when the process ceases to be visible.
                return;
            }

            final String packageName = getPackageNameByUid(uid);
            if (packageName == null) {
                // Not interested in this uid.
                return;
            }

            final int userId = UserHandle.getUserId(uid);

            boolean needToPersistStateForUser = false;

            for (AssociationInfo association :
                    getPendingRoleHolderRemovalAssociationsForUser(userId)) {
                if (!packageName.equals(association.getPackageName())) continue;

                if (!maybeRemoveRoleHolderForAssociation(association)) {
                    // Did not remove the role holder, will have to try again later.
                    continue;
                }

                removeFromPendingRoleHolderRemoval(association);
                needToPersistStateForUser = true;
            }

            if (needToPersistStateForUser) {
                mService.postPersistUserState(userId);
            }
        }
    }
}
