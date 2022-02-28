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

import static java.util.Objects.requireNonNull;

import android.annotation.UserIdInt;
import android.app.PendingIntent;
import android.companion.AssociationInfo;
import android.companion.DeviceNotAssociatedException;
import android.companion.SystemDataTransferRequest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
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
    private static final String EXTRA_SYSTEM_DATA_TRANSFER_REQUEST = "system_data_transfer_request";
    private static final String EXTRA_SYSTEM_DATA_TRANSFER_RESULT_RECEIVER =
            "system_data_transfer_result_receiver";
    private static final ComponentName SYSTEM_DATA_TRANSFER_REQUEST_APPROVAL_ACTIVITY =
            createRelative(COMPANION_DEVICE_DISCOVERY_PACKAGE_NAME,
                    ".CompanionDeviceDataTransferActivity");

    private final Context mContext;
    private final AssociationStore mAssociationStore;
    private final SystemDataTransferRequestStore mSystemDataTransferRequestStore;

    SystemDataTransferProcessor(CompanionDeviceManagerService service,
            AssociationStore associationStore,
            SystemDataTransferRequestStore systemDataTransferRequestStore) {
        mContext = service.getContext();
        mAssociationStore = associationStore;
        mSystemDataTransferRequestStore = systemDataTransferRequestStore;
    }

    /**
     * Build a PendingIntent of user consent dialog
     */
    public PendingIntent buildSystemDataTransferConfirmationIntent(@UserIdInt int userId,
            SystemDataTransferRequest request) throws DeviceNotAssociatedException {
        // The association must exist and either belong to the calling package,
        // or the calling package must hold REQUEST_SYSTEM_DATA_TRANSFER permission.
        int associationId = request.getAssociationId();
        AssociationInfo association = mAssociationStore.getAssociationById(associationId);
        if (association == null) {
            throw new DeviceNotAssociatedException(
                    "Association id: " + associationId + " doesn't exist.");
        } else {
            // If the package is not the companion app which owns the association,
            // it must hold REQUEST_SYSTEM_DATA_TRANSFER permission.
            // TODO(b/204593788): uncomment the following with the API changes
//            if (!association.getPackageName()
//                    .equals(mContext.getPackageManager().getNameForUid(Binder.getCallingUid()))) {
//                mContext.enforceCallingOrSelfPermission(
//                        Manifest.permission.REQUEST_COMPANION_DEVICE_SYSTEM_DATA_TRANSFER,
//                        "requestSystemDataTransfer requires REQUEST_SYSTEM_DATA_TRANSFER "
//                                + "permission if the package doesn't own the association.");
//            }

            // Check if the request's data type has been requested before.
            List<SystemDataTransferRequest> storedRequests =
                    mSystemDataTransferRequestStore.readRequestsByAssociationId(userId,
                            associationId);
            for (SystemDataTransferRequest storedRequest : storedRequests) {
                if (request.hasSameDataType(storedRequest)) {
                    Slog.e(LOG_TAG, "The request has been sent before, you can not send "
                            + "the same request type again.");
                    return null;
                }
            }
        }

        Slog.i(LOG_TAG, "Creating PendingIntent for associationId: " + associationId + ", request: "
                + request);

        // Create an internal intent to launch the user consent dialog
        final Bundle extras = new Bundle();
        request.setUserId(userId);
        extras.putParcelable(EXTRA_SYSTEM_DATA_TRANSFER_REQUEST, request);
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
                        final SystemDataTransferRequest request =
                                data.getParcelable(EXTRA_SYSTEM_DATA_TRANSFER_REQUEST);
                        requireNonNull(request);

                        request.setUserConsented(
                                resultCode == RESULT_CODE_SYSTEM_DATA_TRANSFER_ALLOWED);
                        Slog.i(LOG_TAG, "Recording request: " + request);
                        mSystemDataTransferRequestStore.writeRequest(request.getUserId(), request);

                        return;
                    }

                    Slog.e(LOG_TAG, "Unknown result code:" + resultCode);
                }
            };
}
