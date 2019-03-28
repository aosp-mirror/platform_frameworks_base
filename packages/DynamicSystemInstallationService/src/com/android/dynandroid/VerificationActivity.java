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

import static android.os.image.DynamicSystemClient.KEY_SYSTEM_SIZE;
import static android.os.image.DynamicSystemClient.KEY_USERDATA_SIZE;

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
 * This Activity starts KeyguardManager and ask the user to confirm
 * before any installation request. If the device is not protected by
 * a password, it approves the request by default.
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
            String title = getString(R.string.keyguard_title);
            String description = getString(R.string.keyguard_description);
            Intent intent = km.createConfirmDeviceCredentialIntent(title, description);

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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK) {
            startInstallationService();
        }

        finish();
    }

    private void startInstallationService() {
        // retrieve data from calling intent
        Intent callingIntent = getIntent();

        Uri url = callingIntent.getData();
        long systemSize = callingIntent.getLongExtra(KEY_SYSTEM_SIZE, 0);
        long userdataSize = callingIntent.getLongExtra(KEY_USERDATA_SIZE, 0);

        sVerifiedUrl = url.toString();

        // start service
        Intent intent = new Intent(this, DynamicSystemInstallationService.class);
        intent.setData(url);
        intent.setAction(DynamicSystemClient.ACTION_START_INSTALL);
        intent.putExtra(KEY_SYSTEM_SIZE, systemSize);
        intent.putExtra(KEY_USERDATA_SIZE, userdataSize);

        Log.d(TAG, "Starting Installation Service");
        startServiceAsUser(intent, UserHandle.SYSTEM);
    }

    static boolean isVerified(String url) {
        return sVerifiedUrl != null && sVerifiedUrl.equals(url);
    }
}
