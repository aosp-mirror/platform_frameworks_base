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

package android.app.timezone;

import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;

/**
 * Constants related to the contract between the Android system and the privileged time zone updater
 * application.
 *
 * @hide
 */
public final class RulesUpdaterContract {

    /**
     * The system permission possessed by the Android system that allows it to trigger time zone
     * update checks. The updater should be configured to require this permission when registering
     * for {@link #ACTION_TRIGGER_RULES_UPDATE_CHECK} intents.
     */
    public static final String TRIGGER_TIME_ZONE_RULES_CHECK_PERMISSION =
            android.Manifest.permission.TRIGGER_TIME_ZONE_RULES_CHECK;

    /**
     * The system permission possessed by the time zone rules updater app that allows it to update
     * device time zone rules. The Android system requires this permission for calls made to
     * {@link RulesManager}.
     */
    public static final String UPDATE_TIME_ZONE_RULES_PERMISSION =
            android.Manifest.permission.UPDATE_TIME_ZONE_RULES;

    /**
     * The action of the intent that the Android system will broadcast. The intent will be targeted
     * at the configured updater application's package meaning the term "broadcast" only loosely
     * applies.
     */
    public static final String ACTION_TRIGGER_RULES_UPDATE_CHECK =
            "com.android.intent.action.timezone.TRIGGER_RULES_UPDATE_CHECK";

    /**
     * The extra containing the {@code byte[]} that should be passed to
     * {@link RulesManager#requestInstall(ParcelFileDescriptor, byte[], Callback)},
     * {@link RulesManager#requestUninstall(byte[], Callback)} and
     * {@link RulesManager#requestNothing(byte[], boolean)} methods when the
     * {@link #ACTION_TRIGGER_RULES_UPDATE_CHECK} intent has been processed.
     */
    public static final String EXTRA_CHECK_TOKEN =
            "com.android.intent.extra.timezone.CHECK_TOKEN";

    /**
     * Creates an intent that would trigger a time zone rules update check.
     */
    public static Intent createUpdaterIntent(String updaterPackageName) {
        Intent intent = new Intent(RulesUpdaterContract.ACTION_TRIGGER_RULES_UPDATE_CHECK);
        intent.setPackage(updaterPackageName);
        intent.setFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        return intent;
    }

    /**
     * Broadcasts an {@link #ACTION_TRIGGER_RULES_UPDATE_CHECK} intent with the
     * {@link #EXTRA_CHECK_TOKEN} that triggers an update check, including the required receiver
     * permission.
     */
    public static void sendBroadcast(Context context, String updaterAppPackageName,
            byte[] checkTokenBytes) {
        Intent intent = createUpdaterIntent(updaterAppPackageName);
        intent.putExtra(EXTRA_CHECK_TOKEN, checkTokenBytes);
        context.sendBroadcastAsUser(
                intent, UserHandle.SYSTEM,
                RulesUpdaterContract.UPDATE_TIME_ZONE_RULES_PERMISSION);
    }
}
