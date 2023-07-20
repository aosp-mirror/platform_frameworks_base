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

package com.android.server.timezone;

import com.android.server.EventLogTags;

import android.app.timezone.RulesUpdaterContract;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PatternMatcher;
import android.os.UserHandle;
import android.util.Slog;

/**
 * The bona fide implementation of {@link PackageTrackerIntentHelper}.
 */
final class PackageTrackerIntentHelperImpl implements PackageTrackerIntentHelper {

    private final static String TAG = "timezone.PackageTrackerIntentHelperImpl";

    private final Context mContext;
    private String mUpdaterAppPackageName;

    PackageTrackerIntentHelperImpl(Context context) {
        mContext = context;
    }

    @Override
    public void initialize(String updaterAppPackageName, String dataAppPackageName,
            PackageTracker packageTracker) {
        mUpdaterAppPackageName = updaterAppPackageName;

        // Register for events of interest.

        // The intent filter that triggers when package update events happen that indicate there may
        // be work to do.
        IntentFilter packageIntentFilter = new IntentFilter();

        packageIntentFilter.addDataScheme("package");
        packageIntentFilter.addDataSchemeSpecificPart(
                updaterAppPackageName, PatternMatcher.PATTERN_LITERAL);
        packageIntentFilter.addDataSchemeSpecificPart(
                dataAppPackageName, PatternMatcher.PATTERN_LITERAL);

        // ACTION_PACKAGE_ADDED is fired when a package is upgraded or downgraded (in addition to
        // ACTION_PACKAGE_REMOVED and ACTION_PACKAGE_REPLACED). A system/priv-app can never be
        // removed entirely so we do not need to trigger on ACTION_PACKAGE_REMOVED or
        // ACTION_PACKAGE_FULLY_REMOVED.
        packageIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);

        // ACTION_PACKAGE_CHANGED is used when a package is disabled / re-enabled. It is not
        // strictly necessary to trigger on this but it won't hurt anything and may catch some cases
        // where a package has changed while disabled.
        // Note: ACTION_PACKAGE_CHANGED is not fired when updating a suspended app, but
        // ACTION_PACKAGE_ADDED, ACTION_PACKAGE_REMOVED and ACTION_PACKAGE_REPLACED are (and the app
        // is left in an unsuspended state after this).
        packageIntentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);

        // We do not register for ACTION_PACKAGE_RESTARTED because it doesn't imply an update.
        // We do not register for ACTION_PACKAGE_DATA_CLEARED because the updater / data apps are
        // not expected to need local data.

        Receiver packageUpdateReceiver = new Receiver(packageTracker);
        mContext.registerReceiverAsUser(
                packageUpdateReceiver, UserHandle.SYSTEM, packageIntentFilter,
                null /* broadcastPermission */, null /* default handler */);
    }

    /** Sends an intent to trigger an update check. */
    @Override
    public void sendTriggerUpdateCheck(CheckToken checkToken) {
        RulesUpdaterContract.sendBroadcast(
                mContext, mUpdaterAppPackageName, checkToken.toByteArray());
        EventLogTags.writeTimezoneTriggerCheck(checkToken.toString());
    }

    @Override
    public synchronized void scheduleReliabilityTrigger(long minimumDelayMillis) {
        TimeZoneUpdateIdler.schedule(mContext, minimumDelayMillis);
    }

    @Override
    public synchronized void unscheduleReliabilityTrigger() {
        TimeZoneUpdateIdler.unschedule(mContext);
    }

    private static class Receiver extends BroadcastReceiver {
        private final PackageTracker mPackageTracker;

        private Receiver(PackageTracker packageTracker) {
            mPackageTracker = packageTracker;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Slog.d(TAG, "Received intent: " + intent.toString());
            mPackageTracker.triggerUpdateIfNeeded(true /* packageChanged */);
        }
    }
}
