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

package com.android.server.credentials;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.service.credentials.CreateCredentialRequest;
import android.service.credentials.CredentialProviderService;
import android.util.Log;

/**
 * Class that invokes providers' pending intents and listens to the responses.
 */
@SuppressLint("LongLogTag")
public class ProviderIntentController {
    private static final String TAG = "ProviderIntentController";
    /**
     * Interface to be implemented by any class that wishes to get callbacks from the UI.
     */
    public interface ProviderIntentControllerCallback {
        /** Called when the user makes a selection. */
        void onProviderIntentResult(Bundle resultData);
        /** Called when the user cancels the UI. */
        void onProviderIntentCancelled();
    }

    private final int mUserId;
    private final Context mContext;
    private final ProviderIntentControllerCallback mCallback;
    private final ResultReceiver mResultReceiver = new ResultReceiver(
            new Handler(Looper.getMainLooper())) {
        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            Log.i(TAG, "onReceiveResult in providerIntentController");

            if (resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "onReceiveResult - ACTIVITYOK");
                mCallback.onProviderIntentResult(resultData);
            } else if (resultCode == Activity.RESULT_CANCELED) {
                Log.i(TAG, "onReceiveResult - RESULTCANCELED");
                mCallback.onProviderIntentCancelled();
            }
            // Drop unknown result
        }
    };

    public ProviderIntentController(@UserIdInt int userId,
            Context context,
            ProviderIntentControllerCallback callback) {
        mUserId = userId;
        mContext = context;
        mCallback = callback;
    }

    /** Sets up the request data and invokes the given pending intent. */
    public void setupAndInvokePendingIntent(@NonNull PendingIntent pendingIntent,
            CreateCredentialRequest request) {
        Log.i(TAG, "in invokePendingIntent");
        setupIntent(pendingIntent, request);
        Log.i(TAG, "in invokePendingIntent receiver set up");
        Log.i(TAG, "creator package: " + pendingIntent.getIntentSender()
                .getCreatorPackage());

        try {
            mContext.startIntentSender(pendingIntent.getIntentSender(),
                    null, 0, 0, 0);
        } catch (IntentSender.SendIntentException e) {
            Log.i(TAG, "Error while invoking pending intent");
        }

    }

    private void setupIntent(PendingIntent pendingIntent, CreateCredentialRequest request) {
        pendingIntent.getIntent().putExtra(Intent.EXTRA_RESULT_RECEIVER,
                toIpcFriendlyResultReceiver(mResultReceiver));
        pendingIntent.getIntent().putExtra(
                CredentialProviderService.EXTRA_CREATE_CREDENTIAL_REQUEST_PARAMS,
                request.getData());
    }

    private <T extends ResultReceiver> ResultReceiver toIpcFriendlyResultReceiver(
            T resultReceiver) {
        final Parcel parcel = Parcel.obtain();
        resultReceiver.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        final ResultReceiver ipcFriendly = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        return ipcFriendly;
    }
}
