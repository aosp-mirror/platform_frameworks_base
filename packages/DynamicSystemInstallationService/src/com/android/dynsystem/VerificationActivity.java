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

package com.android.dynsystem;

import static android.os.image.DynamicSystemClient.ACTION_NOTIFY_KEYGUARD_DISMISSED;
import static android.os.image.DynamicSystemClient.KEY_KEYGUARD_USE_DEFAULT_STRINGS;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.image.DynamicSystemClient;
import android.util.Log;

/**
 * This Activity starts KeyguardManager and ask the user to confirm before any installation request.
 * If the device is not protected by a password, it approves the request by default.
 */
public class VerificationActivity extends Activity {

    private static final String TAG = "VerificationActivity";

    private static final int REQUEST_CODE = 1;

    // For install request verification
    private static String sVerifiedUrl;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        if (km != null) {
            Intent intent = createConfirmDeviceCredentialIntent(km);
            if (intent == null) {
                Log.d(TAG, "This device is not protected by a password/pin");
                startInstallationService();
                finish();
            } else {
                startActivityForResult(intent, REQUEST_CODE);
            }
        } else {
            finish();
        }
    }

    private Intent createConfirmDeviceCredentialIntent(KeyguardManager km) {
        final boolean useDefaultStrings =
                getIntent().getBooleanExtra(KEY_KEYGUARD_USE_DEFAULT_STRINGS, false);
        final String title;
        final String description;
        if (useDefaultStrings) {
            // Use default strings provided by keyguard manager
            title = null;
            description = null;
        } else {
            // Use custom strings provided by DSU
            title = getString(R.string.keyguard_title);
            description = getString(R.string.keyguard_description);
        }
        return km.createConfirmDeviceCredentialIntent(title, description);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            startInstallationService();
        } else {
            notifyKeyguardDismissed();
        }

        finish();
    }

    private void notifyKeyguardDismissed() {
        Intent intent = new Intent(this, DynamicSystemInstallationService.class);
        intent.setAction(ACTION_NOTIFY_KEYGUARD_DISMISSED);
        startServiceAsUser(intent, UserHandle.SYSTEM);
    }

    private void startInstallationService() {
        // retrieve data from calling intent
        Intent callingIntent = getIntent();
        Uri url = callingIntent.getData();

        if (url != null) {
            sVerifiedUrl = url.toString();
        }

        // start service
        Intent intent = new Intent(this, DynamicSystemInstallationService.class);
        if (url != null) {
            intent.setData(url);
        }
        intent.setAction(DynamicSystemClient.ACTION_START_INSTALL);
        intent.putExtras(callingIntent);

        Log.d(TAG, "Starting Installation Service");
        startServiceAsUser(intent, UserHandle.SYSTEM);
    }

    static boolean isVerified(String url) {
        if (url == null) return true;
        return sVerifiedUrl != null && sVerifiedUrl.equals(url);
    }
}
