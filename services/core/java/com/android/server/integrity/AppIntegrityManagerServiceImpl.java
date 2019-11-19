/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.integrity;

import static android.content.Intent.ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION;
import static android.content.pm.PackageManager.EXTRA_VERIFICATION_ID;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

/** Implementation of {@link AppIntegrityManagerService}. */
class AppIntegrityManagerServiceImpl {
    private static final String TAG = "AppIntegrityManagerServiceImpl";

    private final Context mContext;
    private final Handler mHandler;
    private final PackageManagerInternal mPackageManagerInternal;

    AppIntegrityManagerServiceImpl(Context context) {
        mContext = context;

        HandlerThread handlerThread = new HandlerThread("AppIntegrityManagerServiceHandler");
        handlerThread.start();
        mHandler = handlerThread.getThreadHandler();

        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);

        IntentFilter integrityVerificationFilter = new IntentFilter();
        integrityVerificationFilter.addAction(ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION);

        mContext.registerReceiver(
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (!ACTION_PACKAGE_NEEDS_INTEGRITY_VERIFICATION.equals(
                                intent.getAction())) {
                            return;
                        }
                        mHandler.post(() -> handleIntegrityVerification(intent));
                    }
                },
                integrityVerificationFilter,
                /* broadcastPermission= */ null,
                mHandler);
    }

    // protected broadcasts cannot be sent in the test.
    @VisibleForTesting
    void handleIntegrityVerification(Intent intent) {
        int verificationId = intent.getIntExtra(EXTRA_VERIFICATION_ID, -1);
        // TODO: implement this method.
        Slog.i(TAG, "Received integrity verification intent " + intent.toString());
        mPackageManagerInternal.setIntegrityVerificationResult(
                verificationId, PackageManager.VERIFICATION_ALLOW);
    }
}
