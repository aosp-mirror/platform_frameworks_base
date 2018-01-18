/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.fingerprint;

import android.content.pm.PackageManager;
import android.hardware.fingerprint.IFingerprintDialogReceiver;
import android.os.Bundle;
import android.util.Log;

import com.android.systemui.SystemUI;
import com.android.systemui.statusbar.CommandQueue;

public class FingerprintDialogImpl extends SystemUI implements CommandQueue.Callbacks {
    private static final String TAG = "FingerprintDialogImpl";
    private static final boolean DEBUG = false;

    @Override
    public void start() {
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
            return;
        }
        getComponent(CommandQueue.class).addCallbacks(this);
    }

    @Override
    public void showFingerprintDialog(Bundle bundle, IFingerprintDialogReceiver receiver) {
        if (DEBUG) Log.d(TAG, "show fingerprint dialog");
    }

    @Override
    public void onFingerprintAuthenticated() {
        if (DEBUG) Log.d(TAG, "onFingerprintAuthenticated");
    }

    @Override
    public void onFingerprintHelp(String message) {
        if (DEBUG) Log.d(TAG, "onFingerprintHelp: " + message);
    }

    @Override
    public void onFingerprintError(String error) {
        if (DEBUG) Log.d(TAG, "onFingerprintError: " + error);
    }

    @Override
    public void hideFingerprintDialog() {
        if (DEBUG) Log.d(TAG, "hideFingerprintDialog");
    }
}
