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
import static android.companion.CompanionDeviceManager.MESSAGE_REQUEST_PERMISSION_RESTORE;
import static android.content.ComponentName.createRelative;
import static android.content.pm.PackageManager.FEATURE_WATCH;

import static com.android.server.companion.utils.Utils.prepareForIpc;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.companion.AssociationInfo;
import android.companion.IOnMessageReceivedListener;
import android.companion.ISystemDataTransferCallback;
import android.companion.datatransfer.PermissionSyncRequest;
import android.companion.datatransfer.SystemDataTransferRequest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.permission.PermissionControllerManager;
import android.util.Slog;

import com.android.internal.R;
import com.android.server.companion.CompanionDeviceManagerService;
import com.android.server.companion.association.AssociationStore;
import com.android.server.companion.transport.CompanionTransportManager;
import com.android.server.companion.utils.PackageUtils;

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

    private final Context mContext;
    private final PackageManagerInternal mPackageManager;
    private final AssociationStore mAssociationStore;
    private final SystemDataTransferRequestStore mSystemDataTransferRequestStore;
    private final CompanionTransportManager mTransportManager;
    private final PermissionControllerManager mPermissionControllerManager;
    private final ExecutorService mExecutor;
    private final ComponentName mCompanionDeviceDataTransferActivity;

    public SystemDataTransferProcessor(CompanionDeviceManagerService service,
            PackageManagerInternal packageManager,
            AssociationStore associationStore,
            SystemDataTransferRequestStore systemDataTransferRequestStore,
            CompanionTransportManager transportManager) {
        mContext = service.getContext();
        mPackageManager = packageManager;
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
        mCompanionDeviceDataTransferActivity = createRelative(
                mContext.getString(R.string.config_companionDeviceManagerPackage),
                ".CompanionDeviceDataTransferActivity");
    }

    /**
     * Return whether the user has consented to the permission transfer for the association.
     */
    public boolean isPermissionTransferUserConsented(int associationId) {
        mAssociationStore.getAssociationWithCallerChecks(associationId);

        PermissionSyncRequest request = getPermissionSyncRequest(associationId);
        if (request == null) {
            return false;
        }
        return request.isUserConsented();
    }

    /**
     * Build a PendingIntent of permission sync user consent dialog
     */
    public PendingIntent buildPermissionTransferUserConsentIntent(String packageName,
            @UserIdInt int userId, int associationId) {
        if (PackageUtils.isPermSyncAutoEnabled(mContext, mPackageManager, packageName)) {
            Slog.i(LOG_TAG, "User consent Intent should be skipped. Returning null.");
            // Auto enable perm sync for the allowlisted packages, but don't override user decision
            PermissionSyncRequest request = getPermissionSyncRequest(associationId);
            if (request == null) {
                PermissionSyncRequest newRequest = new PermissionSyncRequest(associationId);
                newRequest.setUserConsented(true);
                mSystemDataTransferRequestStore.writeRequest(userId, newRequest);
            }
            return null;
        }

        final AssociationInfo association = mAssociationStore.getAssociationWithCallerChecks(
                associationId);

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
        intent.setComponent(mCompanionDeviceDataTransferActivity);
        intent.putExtras(extras);

        // Create a PendingIntent
        return Binder.withCleanCallingIdentity(() ->
                PendingIntent.getActivityAsUser(mContext, /*requestCode */ associationId,
                        intent, FLAG_ONE_SHOT | FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE,
                        ActivityOptions.makeBasic()
                                .setPendingIntentCreatorBackgroundActivityStartMode(
                                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                                .toBundle(),
                        UserHandle.CURRENT));
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

        mAssociationStore.getAssociationWithCallerChecks(associationId);

        // Check if the request has been consented by the user.
        PermissionSyncRequest request = getPermissionSyncRequest(associationId);
        if (request == null || !request.isUserConsented()) {
            String message =
                    "User " + userId + " hasn't consented permission sync for associationId ["
                            + associationId + ".";
            Slog.e(LOG_TAG, message);
            try {
                callback.onError(message);
            } catch (RemoteException ignored) {
            }
            return;
        }

        // Start permission sync
        Binder.withCleanCallingIdentity(() -> {
            // TODO: refactor to work with streams of data
            mPermissionControllerManager.getRuntimePermissionBackup(UserHandle.of(userId),
                    mExecutor, backup -> {
                        Future<?> future = mTransportManager
                                .requestPermissionRestore(associationId, backup);
                        translateFutureToCallback(future, callback);
                    });
        });
    }

    /**
     * Enable perm sync for the association
     */
    public void enablePermissionsSync(int associationId) {
        int userId = mAssociationStore.getAssociationWithCallerChecks(associationId).getUserId();
        PermissionSyncRequest request = new PermissionSyncRequest(associationId);
        request.setUserConsented(true);
        mSystemDataTransferRequestStore.writeRequest(userId, request);
    }

    /**
     * Disable perm sync for the association
     */
    public void disablePermissionsSync(int associationId) {
        int userId = mAssociationStore.getAssociationWithCallerChecks(associationId).getUserId();
        PermissionSyncRequest request = new PermissionSyncRequest(associationId);
        request.setUserConsented(false);
        mSystemDataTransferRequestStore.writeRequest(userId, request);
    }

    /**
     * Get perm sync request for the association.
     */
    @Nullable
    public PermissionSyncRequest getPermissionSyncRequest(int associationId) {
        int userId = mAssociationStore.getAssociationWithCallerChecks(associationId)
                .getUserId();
        List<SystemDataTransferRequest> requests =
                mSystemDataTransferRequestStore.readRequestsByAssociationId(userId,
                        associationId);
        for (SystemDataTransferRequest request : requests) {
            if (request instanceof PermissionSyncRequest) {
                return (PermissionSyncRequest) request;
            }
        }
        return null;
    }

    /**
     * Remove perm sync request for the association.
     */
    public void removePermissionSyncRequest(int associationId) {
        Binder.withCleanCallingIdentity(() -> {
            int userId = mAssociationStore.getAssociationById(associationId).getUserId();
            mSystemDataTransferRequestStore.removeRequestsByAssociationId(userId, associationId);
        });
    }

    private void onReceivePermissionRestore(byte[] message) {
        // TODO: Disable Permissions Sync for non-watch devices until we figure out a better UX
        //       model
        if (!Build.isDebuggable() && !mContext.getPackageManager().hasSystemFeature(
                FEATURE_WATCH)) {
            Slog.e(LOG_TAG, "Permissions restore is only available on watch.");
            return;
        }
        Slog.i(LOG_TAG, "Applying permissions.");
        // Start applying permissions
        UserHandle user = mContext.getUser();

        Binder.withCleanCallingIdentity(() -> {
            // TODO: refactor to work with streams of data
            mPermissionControllerManager.stageAndApplyRuntimePermissionsBackup(
                    message, user);
        });
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
