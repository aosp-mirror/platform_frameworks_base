/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.stats;

import static android.app.StatsManager.ACTION_STATSD_STARTED;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;

/**
 * Provides helper methods for the Statsd APEX
 *
 * @hide
 **/
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public final class StatsHelper {
    private StatsHelper() {}

    /**
     * Send statsd ready broadcast
     *
     **/
    public static void sendStatsdReadyBroadcast(@NonNull final Context context) {
        context.sendBroadcastAsUser(
                new Intent(ACTION_STATSD_STARTED).addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND),
                UserHandle.SYSTEM, android.Manifest.permission.DUMP);
    }
}
