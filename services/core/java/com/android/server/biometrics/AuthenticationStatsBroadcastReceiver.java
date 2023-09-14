/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.biometrics;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.biometrics.BiometricAuthenticator;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.biometrics.sensors.BiometricNotificationImpl;

import java.util.function.Consumer;

/**
 * Receives broadcast to initialize AuthenticationStatsCollector.
 */
public class AuthenticationStatsBroadcastReceiver extends BroadcastReceiver {

    private static final String TAG = "AuthenticationStatsBroadcastReceiver";

    @NonNull
    private final Consumer<AuthenticationStatsCollector> mCollectorConsumer;
    @BiometricAuthenticator.Modality
    private final int mModality;

    public AuthenticationStatsBroadcastReceiver(@NonNull Context context,
            @BiometricAuthenticator.Modality int modality,
            @NonNull Consumer<AuthenticationStatsCollector> callback) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_UNLOCKED);
        context.registerReceiver(this, intentFilter);

        mCollectorConsumer = callback;
        mModality = modality;
    }

    @Override
    public void onReceive(@NonNull Context context, @NonNull Intent intent) {
        final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);

        if (userId != UserHandle.USER_NULL
                && Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
            Slog.d(TAG, "Received: " + intent.getAction());

            mCollectorConsumer.accept(
                    new AuthenticationStatsCollector(context, mModality,
                            new BiometricNotificationImpl()));

            context.unregisterReceiver(this);
        }
    }
}
