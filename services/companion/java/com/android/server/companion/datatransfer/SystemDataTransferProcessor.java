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

package com.android.server.companion.datatransfer;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.companion.CompanionDeviceManager.COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME;
import static android.content.ComponentName.createRelative;

import static com.android.server.companion.Utils.prepareForIpc;
import static com.android.server.companion.transport.Transport.MESSAGE_REQUEST_PERMISSION_RESTORE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.companion.AssociationInfo;
import android.companion.DeviceNotAssociatedException;
import android.companion.IOnMessageReceivedListener;
import android.companion.ISystemDataTransferCallback;
import android.companion.datatransfer.PermissionSyncRequest;
import android.companion.datatransfer.SystemDataTransferRequest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.permission.PermissionControllerManager;
import android.util.Slog;

import com.android.server.companion.AssociationStore;
import com.android.server.companion.CompanionDeviceManagerService;
import com.android.server.companion.PermissionsUtils;
import com.android.server.companion.transport.CompanionTransportManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * This processor builds user consent intent for a
 * {@link SystemDataTransferRequest} and processes the request once a channel is
 * established between the local and remote companion device.
 */
public class SystemDataTransferProcessor {

    private static final String LOG_TAG = "CDM_SystemDataTransferProcessor";

    // Values from UI to SystemDataTransferProcessor via ResultReceiver
    private static final int RESULT_CODE_SYSTEM_DATA_TRANSFER_ALLOWED = 0;
    private static final int RESULT_CODE_SYSTEM_DATA_TRANSFER_DISALLOWED = 1;
    private static final String EXTRA_PERMISSION_SYNC_REQUEST = "permission_sync_request";
    private static final String EXTRA_COMPANION_DEVICE_NAME = "companion_device_name";
    private static final String EXTRA_SYSTEM_DATA_TRANSFER_RESULT_RECEIVER =
            "system_data_transfer_result_receiver";
    private static final ComponentName SYSTEM_DATA_TRANSFER_REQUEST_APPROVAL_ACTIVITY =
            createRelative(COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME,
                    ".CompanionDeviceDataTransferActivity");

    private final Context mContext;
    private final AssociationStore mAssociationStore;
    private final SystemDataTransferRequestStore mSystemDataTransferRequestStore;
    private final CompanionTransportManager mTransportManager;
    private final PermissionControllerManager mPermissionControllerManager;
    private final ExecutorService mExecutor;

    public SystemDataTransferProcessor(CompanionDeviceManagerService service,
            AssociationStore associationStore,
            SystemDataTransferRequestStore systemDataTransferRequestStore,
            CompanionTransportManager transportManager) {
        mContext = service.getContext();
        mAssociationStore = associationStore;
        mSystemDataTransferRequestStore = systemDataTransferRequestStore;
        mTransportManager = transportManager;
        IOnMessageReceivedListener messageListener = new IOnMessageReceivedListener() {
            @Override
            public void onMessageReceived(int associationId, byte[] data) throws RemoteException {
                onReceivePermissionRestore(data);
            }

            @Override
            public IBinder asBinder() {
                return null;
            }
        };
        mTransportManager.addListener(MESSAGE_REQUEST_PERMISSION_RESTORE, messageListener);
        mPermissionControllerManager = mContext.getSystemService(PermissionControllerManager.class);
        mExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * Resolve the requested association, throwing if the caller doesn't have
     * adequate permissions.
     */
    private @NonNull AssociationInfo resolveAssociation(String packageName, int userId,
            int associationId) {
        AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        association = PermissionsUtils.sanitizeWithCallerChecks(mContext, association);
        if (association == null) {
            throw new DeviceNotAssociatedException("Association "
                    + associationId + " is not associated with the app " + packageName
                    + " for user " + userId);
        }
        return association;
    }

    /**
     * Build a PendingIntent of permission sync user consent dialog
     */
    public PendingIntent buildPermissionTransferUserConsentIntent(String packageName,
            @UserIdInt int userId, int associationId) {
        final AssociationInfo association = resolveAssociation(packageName, userId, associationId);

        Slog.i(LOG_TAG, "Creating permission sync intent for userId [" + userId
                + "] associationId [" + associationId + "]");

        // Create an internal intent to launch the user consent dialog
        final Bundle extras = new Bundle();
        PermissionSyncRequest request = new PermissionSyncRequest(associationId);
        request.setUserId(userId);
        extras.putParcelable(EXTRA_PERMISSION_SYNC_REQUEST, request);
        extras.putCharSequence(EXTRA_COMPANION_DEVICE_NAME, association.getDisplayName());
        extras.putParcelable(EXTRA_SYSTEM_DATA_TRANSFER_RESULT_RECEIVER,
                prepareForIpc(mOnSystemDataTransferRequestConfirmationReceiver));

        final Intent intent = new Intent();
        intent.setComponent(SYSTEM_DATA_TRANSFER_REQUEST_APPROVAL_ACTIVITY);
        intent.putExtras(extras);

        // Create a PendingIntent
        final long token = Binder.clearCallingIdentity();
        try {
            return PendingIntent.getActivityAsUser(mContext, /*requestCode */ associationId, intent,
                    FLAG_ONE_SHOT | FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE, /* options= */ null,
                    UserHandle.CURRENT);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Start system data transfer. It should first try to establish a secure channel and then sync
     * system data.
     *
     * TODO: execute callback when the transfer finishes successfully or with errors.
     */
    public void startSystemDataTransfer(String packageName, int userId, int associationId,
            ISystemDataTransferCallback callback) {
        Slog.i(LOG_TAG, "Start system data transfer for package [" + packageName
                + "] userId [" + userId + "] associationId [" + associationId + "]");

        final AssociationInfo association = resolveAssociation(packageName, userId, associationId);

        // Check if the request has been consented by the user.
        List<SystemDataTransferRequest> storedRequests =
                mSystemDataTransferRequestStore.readRequestsByAssociationId(userId,
                        associationId);
        boolean hasConsented = false;
        for (SystemDataTransferRequest storedRequest : storedRequests) {
            if (storedRequest instanceof PermissionSyncRequest && storedRequest.isUserConsented()) {
                hasConsented = true;
                break;
            }
        }
        if (!hasConsented) {
            String message = "User " + userId + " hasn't consented permission sync.";
            Slog.e(LOG_TAG, message);
            try {
                callback.onError(message);
            } catch (RemoteException ignored) { }
            return;
        }

        // Start permission sync
        final long callingIdentityToken = Binder.clearCallingIdentity();
        try {
            // TODO: refactor to work with streams of data
            mPermissionControllerManager.getRuntimePermissionBackup(UserHandle.of(userId),
                    mExecutor, backup -> {
                        Future<?> future = mTransportManager
                                .requestPermissionRestore(associationId, backup);
                        translateFutureToCallback(future, callback);
                    });
        } finally {
            Binder.restoreCallingIdentity(callingIdentityToken);
        }
    }

    private void onReceivePermissionRestore(byte[] message) {
        Slog.i(LOG_TAG, "Applying permissions.");
        // Start applying permissions
        UserHandle user = mContext.getUser();
        final long callingIdentityToken = Binder.clearCallingIdentity();
        try {
            // TODO: refactor to work with streams of data
            mPermissionControllerManager.stageAndApplyRuntimePermissionsBackup(
                    message, user);
        } finally {
            Binder.restoreCallingIdentity(callingIdentityToken);
        }
    }

    private static void translateFutureToCallback(@NonNull Future<?> future,
            @Nullable ISystemDataTransferCallback callback) {
        try {
            future.get(15, TimeUnit.SECONDS);
            try {
                if (callback != null) {
                    callback.onResult();
                }
            } catch (RemoteException ignored) {
            }
        } catch (Exception e) {
            try {
                if (callback != null) {
                    callback.onError(e.getMessage());
                }
            } catch (RemoteException ignored) {
            }
        }
    }

    private final ResultReceiver mOnSystemDataTransferRequestConfirmationReceiver =
            new ResultReceiver(Handler.getMain()) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle data) {
                    Slog.d(LOG_TAG, "onReceiveResult() code=" + resultCode + ", "
                            + "data=" + data);

                    if (resultCode == RESULT_CODE_SYSTEM_DATA_TRANSFER_ALLOWED
                            || resultCode == RESULT_CODE_SYSTEM_DATA_TRANSFER_DISALLOWED) {
                        final PermissionSyncRequest request =
                                data.getParcelable(EXTRA_PERMISSION_SYNC_REQUEST,
                                        PermissionSyncRequest.class);
                        if (request != null) {
                            request.setUserConsented(
                                    resultCode == RESULT_CODE_SYSTEM_DATA_TRANSFER_ALLOWED);
                            Slog.i(LOG_TAG, "Recording request: " + request);
                            mSystemDataTransferRequestStore.writeRequest(request.getUserId(),
                                    request);
                        }

                        return;
                    }

                    Slog.e(LOG_TAG, "Unknown result code:" + resultCode);
                }
            };
}
