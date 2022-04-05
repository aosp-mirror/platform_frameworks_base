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

import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.companion.AssociationInfo;
import android.companion.DeviceNotAssociatedException;
import android.companion.datatransfer.PermissionSyncRequest;
import android.companion.datatransfer.SystemDataTransferRequest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Slog;

import com.android.server.companion.AssociationStore;
import com.android.server.companion.CompanionDeviceManagerService;

import java.util.List;

/**
 * This processor builds user consent intent for a given SystemDataTransferRequest and processes the
 * request when the system is ready (a secure channel is established between the handhold and the
 * companion device).
 */
public class SystemDataTransferProcessor {

    private static final String LOG_TAG = SystemDataTransferProcessor.class.getSimpleName();

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

    public SystemDataTransferProcessor(CompanionDeviceManagerService service,
            AssociationStore associationStore,
            SystemDataTransferRequestStore systemDataTransferRequestStore) {
        mContext = service.getContext();
        mAssociationStore = associationStore;
        mSystemDataTransferRequestStore = systemDataTransferRequestStore;
    }

    /**
     * Build a PendingIntent of permission sync user consent dialog
     */
    public PendingIntent buildPermissionTransferUserConsentIntent(String packageName,
            @UserIdInt int userId, int associationId) throws RemoteException {
        // The association must exist and either belong to the calling package,
        // or the calling package must hold REQUEST_SYSTEM_DATA_TRANSFER permission.
        AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        if (association == null) {
            throw new RemoteException(new DeviceNotAssociatedException(
                    "Association id: " + associationId + " doesn't exist."));
        } else {
            if (!association.getPackageName().equals(packageName)) {
                Slog.e(LOG_TAG, "The calling package doesn't own the association.");
                return null;
            }

            // Check if the request's data type has been requested before.
            List<SystemDataTransferRequest> storedRequests =
                    mSystemDataTransferRequestStore.readRequestsByAssociationId(userId,
                            associationId);
            for (SystemDataTransferRequest storedRequest : storedRequests) {
                if (storedRequest instanceof PermissionSyncRequest) {
                    Slog.e(LOG_TAG, "The request has been sent before, you can not send "
                            + "the same request type again.");
                    return null;
                }
            }
        }

        Slog.i(LOG_TAG, "Creating permission sync intent for userId=" + userId
                + "associationId: " + associationId);

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
            return PendingIntent.getActivity(mContext, /*requestCode */ associationId, intent,
                    FLAG_ONE_SHOT | FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);
        } finally {
            Binder.restoreCallingIdentity(token);
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
