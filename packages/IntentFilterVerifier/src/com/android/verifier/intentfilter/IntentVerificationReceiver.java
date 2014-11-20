/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.verifier.intentfilter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Log;
import android.util.Slog;

import java.util.Arrays;
import java.util.List;

public class IntentVerificationReceiver extends BroadcastReceiver {
    static final String TAG = IntentVerificationReceiver.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (Intent.ACTION_INTENT_FILTER_NEEDS_VERIFICATION.equals(action)) {
            Bundle extras = intent.getExtras();
            if (extras != null) {
                int verificationId = extras.getInt(
                        PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_ID);
                String hosts = extras.getString(
                        PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_HOSTS);

                Log.d(TAG, "Received IntentFilter verification broadcast with verificationId: "
                        + verificationId);

                if (canDoVerification(context)) {
                    Intent serviceIntent = new Intent(context, IntentVerificationService.class);
                    serviceIntent.fillIn(intent, 0);
                    serviceIntent.putExtras(intent.getExtras());

                    Slog.d(TAG, "Starting Intent Verification Service.");

                    context.startService(serviceIntent);
                } else {
                    sendVerificationFailure(context, verificationId, hosts);
                }
            }

        } else {
            Log.w(TAG, "Unexpected action: " + action);
        }
    }

    private void sendVerificationFailure(Context context, int verificationId, String hosts) {
        List<String> list = Arrays.asList(hosts.split(" "));
        context.getPackageManager().verifyIntentFilter(
                verificationId, PackageManager.INTENT_FILTER_VERIFICATION_FAILURE, list);

        Log.d(TAG, "No network! Failing IntentFilter verification with verificationId: " +
                verificationId + " and hosts: " + hosts);
    }

    private boolean canDoVerification(Context context) {
        return hasNetwork(context);
    }

    public boolean hasNetwork(Context context) {
        ConnectivityManager cm =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo info = cm.getActiveNetworkInfo();
            return (info != null) && info.isConnected();
        } else {
            return false;
        }
    }
}
