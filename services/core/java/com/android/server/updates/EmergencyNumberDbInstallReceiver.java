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

package com.android.server.updates;

import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Slog;

/**
 * Emergency Number Database Install Receiver.
 */
public class EmergencyNumberDbInstallReceiver extends ConfigUpdateInstallReceiver {

    private static final String TAG = "EmergencyNumberDbInstallReceiver";

    public EmergencyNumberDbInstallReceiver() {
        super("/data/misc/emergencynumberdb", "emergency_number_db", "metadata/", "version");
    }

    @Override
    protected void postInstall(Context context, Intent intent) {
        Slog.i(TAG, "Emergency number database is updated in file partition");

        // Notify EmergencyNumberTracker for emergency number installation complete.
        TelephonyManager telephonyManager = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        telephonyManager.notifyOtaEmergencyNumberDbInstalled();
    }
}
