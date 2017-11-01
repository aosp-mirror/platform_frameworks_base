/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.osu;

import android.app.Activity;
import android.content.Intent;
import android.net.wifi.hotspot2.OsuProvider;
import android.os.Bundle;
import android.util.Log;

/**
 * Main entry point for the OSU (Online Sign-Up) app.
 */
public class MainActivity extends Activity {
    private static final String TAG = "OSU_MainActivity";
    private OsuService mService;

    @Override
    protected void onCreate(Bundle saveInstanceState) {
        super.onCreate(saveInstanceState);

        Intent intent = getIntent();
        if (intent == null) {
            Log.e(TAG, "Intent not provided");
            finish();
        }

        if (!intent.hasExtra(Constants.INTENT_EXTRA_COMMAND)) {
            Log.e(TAG, "Command not provided");
            finish();
        }

        String command = intent.getStringExtra(Constants.INTENT_EXTRA_COMMAND);
        switch (command) {
            case Constants.COMMAND_PROVISION:
                if (!startProvisionService(intent.getParcelableExtra(
                        Constants.INTENT_EXTRA_OSU_PROVIDER))) {
                    finish();
                }
                break;
            default:
                Log.e(TAG, "Unknown command: '" + command + "'");
                finish();
                break;
        }
    }

    /**
     * Start the {@link ProvisionService} to perform provisioning tasks.
     *
     * @return true if service is started
     */
    private boolean startProvisionService(OsuProvider provider) {
        if (provider == null) {
            Log.e(TAG, "OSU Provider not provided");
            return false;
        }
        mService = new ProvisionService(this, provider);
        mService.start();
        return true;
    }
}
