/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.notification;

import android.net.Uri;
import android.service.notification.ZenModeConfig;

import java.io.PrintWriter;

/**
 * Condition provider used for custom manual rules (i.e. user-created rules without an automatic
 * trigger).
 */
public class CustomManualConditionProvider extends SystemConditionProviderService {

    private static final String SIMPLE_NAME = CustomManualConditionProvider.class.getSimpleName();

    @Override
    public boolean isValidConditionId(Uri id) {
        return ZenModeConfig.isValidCustomManualConditionId(id);
    }

    @Override
    public void onBootComplete() {
        // Nothing to do.
    }

    @Override
    public void onConnected() {
        // No need to keep subscriptions because we won't ever call notifyConditions
    }

    @Override
    public void onSubscribe(Uri conditionId) {
        // No need to keep subscriptions because we won't ever call notifyConditions
    }

    @Override
    public void onUnsubscribe(Uri conditionId) {
        // No need to keep subscriptions because we won't ever call notifyConditions
    }

    @Override
    public void dump(PrintWriter pw, NotificationManagerService.DumpFilter filter) {
        pw.print("    "); pw.print(SIMPLE_NAME); pw.println(": ENABLED");
    }
}
