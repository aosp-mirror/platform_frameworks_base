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

package com.android.server.companion;

import static android.os.UserHandle.getCallingUserId;

import static com.android.server.companion.CompanionDeviceManagerService.PerUserAssociationSet;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;
import android.companion.Flags;
import android.companion.datatransfer.SystemDataTransferRequest;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManagerInternal;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.CollectionUtils;
import com.android.server.companion.datatransfer.SystemDataTransferRequestStore;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

@SuppressLint("LongLogTag")
class BackupRestoreProcessor {
    static final String TAG = "CDM_BackupRestoreProcessor";
    private static final int BACKUP_AND_RESTORE_VERSION = 0;

    @NonNull
    private final CompanionDeviceManagerService mService;
    @NonNull
    private final PackageManagerInternal mPackageManager;
    @NonNull
    private final AssociationStoreImpl mAssociationStore;
    @NonNull
    private final PersistentDataStore mPersistentStore;
    @NonNull
    private final SystemDataTransferRequestStore mSystemDataTransferRequestStore;
    @NonNull
    private final AssociationRequestsProcessor mAssociationRequestsProcessor;

    /**
     * A structure that consists of a set of restored associations that are pending corresponding
     * companion app to be installed.
     */
    @GuardedBy("mAssociationsPendingAppInstall")
    private final PerUserAssociationSet mAssociationsPendingAppInstall =
            new PerUserAssociationSet();

    BackupRestoreProcessor(@NonNull CompanionDeviceManagerService service,
                           @NonNull AssociationStoreImpl associationStore,
                           @NonNull PersistentDataStore persistentStore,
                           @NonNull SystemDataTransferRequestStore systemDataTransferRequestStore,
                           @NonNull AssociationRequestsProcessor associationRequestsProcessor) {
        mService = service;
        mPackageManager = service.mPackageManagerInternal;
        mAssociationStore = associationStore;
        mPersistentStore = persistentStore;
        mSystemDataTransferRequestStore = systemDataTransferRequestStore;
        mAssociationRequestsProcessor = associationRequestsProcessor;
    }

    /**
     * Generate CDM state payload to be backed up.
     * Backup payload is formatted as following:
     * | (4) payload version | (4) AssociationInfo length | AssociationInfo XML
     * | (4) SystemDataTransferRequest length | SystemDataTransferRequest XML (without userId)|
     */
    byte[] getBackupPayload(int userId) {
        // Persist state first to generate an up-to-date XML file
        mService.persistStateForUser(userId);
        byte[] associationsPayload = mPersistentStore.getBackupPayload(userId);
        int associationsPayloadLength = associationsPayload.length;

        // System data transfer requests are persisted up-to-date already
        byte[] requestsPayload = mSystemDataTransferRequestStore.getBackupPayload(userId);
        int requestsPayloadLength = requestsPayload.length;

        int payloadSize = /* 3 integers */ 12
                + associationsPayloadLength
                + requestsPayloadLength;

        return ByteBuffer.allocate(payloadSize)
                .putInt(BACKUP_AND_RESTORE_VERSION)
                .putInt(associationsPayloadLength)
                .put(associationsPayload)
                .putInt(requestsPayloadLength)
                .put(requestsPayload)
                .array();
    }

    /**
     * Create new associations and system data transfer request consents using backed up payload.
     */
    void applyRestoredPayload(byte[] payload, int userId) {
        ByteBuffer buffer = ByteBuffer.wrap(payload);

        // Make sure that payload version matches current version to ensure proper deserialization
        int version = buffer.getInt();
        if (version != BACKUP_AND_RESTORE_VERSION) {
            Slog.e(TAG, "Unsupported backup payload version");
            return;
        }

        // Read the bytes containing backed-up associations
        byte[] associationsPayload = new byte[buffer.getInt()];
        buffer.get(associationsPayload);
        final Set<AssociationInfo> restoredAssociations = new HashSet<>();
        mPersistentStore.readStateFromPayload(associationsPayload, userId,
                restoredAssociations, new HashMap<>());

        // Read the bytes containing backed-up system data transfer requests user consent
        byte[] requestsPayload = new byte[buffer.getInt()];
        buffer.get(requestsPayload);
        List<SystemDataTransferRequest> restoredRequestsForUser =
                mSystemDataTransferRequestStore.readRequestsFromPayload(requestsPayload);

        // Get a list of installed packages ahead of time.
        List<ApplicationInfo> installedApps = mPackageManager.getInstalledApplications(
                0, userId, getCallingUserId());

        // Restored device may have a different user ID than the backed-up user's user-ID. Since
        // association ID is dependent on the user ID, restored associations must account for
        // this potential difference on their association IDs.
        for (AssociationInfo restored : restoredAssociations) {
            // Don't restore a revoked association. Since they weren't added to the device being
            // restored in the first place, there is no need to worry about revoking a role that
            // was never granted either.
            if (restored.isRevoked()) {
                continue;
            }

            // Filter restored requests for those that belong to the restored association.
            List<SystemDataTransferRequest> restoredRequests = CollectionUtils.filter(
                    restoredRequestsForUser, it -> it.getAssociationId() == restored.getId());

            // Handle collision: If a local association belonging to the same package already exists
            // and their tags match, then keep the local one in favor of creating a new association.
            if (handleCollision(userId, restored, restoredRequests)) {
                continue;
            }

            // Create a new association reassigned to this user and a valid association ID
            final String packageName = restored.getPackageName();
            final int newId = mService.getNewAssociationIdForPackage(userId, packageName);
            AssociationInfo newAssociation =
                    new AssociationInfo.Builder(newId, userId, packageName, restored)
                            .build();

            // Check if the companion app for this association is already installed, then do one
            // of the following:
            // (1) If the app is already installed, then go ahead and add this association and grant
            // the role attached to this association to the app.
            // (2) If the app isn't yet installed, then add this association to the list of pending
            // associations to be added when the package is installed in the future.
            boolean isPackageInstalled = installedApps.stream()
                    .anyMatch(app -> packageName.equals(app.packageName));
            if (isPackageInstalled) {
                mAssociationRequestsProcessor.maybeGrantRoleAndStoreAssociation(newAssociation,
                        null, null);
            } else {
                addToPendingAppInstall(newAssociation);
            }

            // Re-map restored system data transfer requests to newly created associations
            for (SystemDataTransferRequest restoredRequest : restoredRequests) {
                SystemDataTransferRequest newRequest = restoredRequest.copyWithNewId(newId);
                newRequest.setUserId(userId);
                mSystemDataTransferRequestStore.writeRequest(userId, newRequest);
            }
        }

        // Persist restored state.
        mService.persistStateForUser(userId);
    }

    void addToPendingAppInstall(@NonNull AssociationInfo association) {
        association = (new AssociationInfo.Builder(association))
                .setPending(true)
                .build();

        synchronized (mAssociationsPendingAppInstall) {
            mAssociationsPendingAppInstall.forUser(association.getUserId()).add(association);
        }
    }

    void removeFromPendingAppInstall(@NonNull AssociationInfo association) {
        synchronized (mAssociationsPendingAppInstall) {
            mAssociationsPendingAppInstall.forUser(association.getUserId()).remove(association);
        }
    }

    @NonNull
    Set<AssociationInfo> getAssociationsPendingAppInstallForUser(@UserIdInt int userId) {
        synchronized (mAssociationsPendingAppInstall) {
            // Return a copy.
            return new ArraySet<>(mAssociationsPendingAppInstall.forUser(userId));
        }
    }

    /**
     * Detects and handles collision between restored association and local association. Returns
     * true if there has been a collision and false otherwise.
     */
    private boolean handleCollision(@UserIdInt int userId,
            AssociationInfo restored,
            List<SystemDataTransferRequest> restoredRequests) {
        List<AssociationInfo> localAssociations = mAssociationStore.getAssociationsForPackage(
                restored.getUserId(), restored.getPackageName());
        Predicate<AssociationInfo> isSameDevice = associationInfo -> {
            boolean matchesMacAddress = Objects.equals(
                    associationInfo.getDeviceMacAddress(),
                    restored.getDeviceMacAddress());
            boolean matchesTag = !Flags.associationTag()
                    || Objects.equals(associationInfo.getTag(), restored.getTag());
            return matchesMacAddress && matchesTag;
        };
        AssociationInfo local = CollectionUtils.find(localAssociations, isSameDevice);

        // No collision detected
        if (local == null) {
            return false;
        }

        Log.d(TAG, "Conflict detected with association id=" + local.getId()
                + " while restoring CDM backup. Keeping local association.");

        List<SystemDataTransferRequest> localRequests = mSystemDataTransferRequestStore
                .readRequestsByAssociationId(userId, local.getId());

        // If local association doesn't have any existing system data transfer request of same type
        // attached, then restore corresponding request onto the local association. Otherwise, keep
        // the locally stored request.
        for (SystemDataTransferRequest restoredRequest : restoredRequests) {
            boolean requestTypeExists = CollectionUtils.any(localRequests, request ->
                    request.getDataType() == restoredRequest.getDataType());

            // This type of request consent already exists for the association.
            if (requestTypeExists) {
                continue;
            }

            Log.d(TAG, "Restoring " + restoredRequest.getClass().getSimpleName()
                    + " to an existing association id=" + local.getId() + ".");

            SystemDataTransferRequest newRequest =
                    restoredRequest.copyWithNewId(local.getId());
            newRequest.setUserId(userId);
            mSystemDataTransferRequestStore.writeRequest(userId, newRequest);
        }

        return true;
    }
}
