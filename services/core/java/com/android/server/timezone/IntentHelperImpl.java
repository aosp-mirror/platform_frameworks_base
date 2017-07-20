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
import android.util.Slog;

/**
 * The bona fide implementation of {@link IntentHelper}.
 */
final class IntentHelperImpl implements IntentHelper {

    private final static String TAG = "timezone.IntentHelperImpl";

    private final Context mContext;
    private String mUpdaterAppPackageName;

    private boolean mReliabilityReceiverEnabled;
    private Receiver mReliabilityReceiver;

    IntentHelperImpl(Context context) {
        mContext = context;
    }

    @Override
    public void initialize(
            String updaterAppPackageName, String dataAppPackageName, Listener listener) {
        mUpdaterAppPackageName = updaterAppPackageName;

        // Register for events of interest.

        // The intent filter that triggers when package update events happen that indicate there may
        // be work to do.
        IntentFilter packageIntentFilter = new IntentFilter();
        // Either of these mean a downgrade?
        packageIntentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageIntentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageIntentFilter.addDataScheme("package");
        packageIntentFilter.addDataSchemeSpecificPart(
                updaterAppPackageName, PatternMatcher.PATTERN_LITERAL);
        packageIntentFilter.addDataSchemeSpecificPart(
                dataAppPackageName, PatternMatcher.PATTERN_LITERAL);
        Receiver packageUpdateReceiver = new Receiver(listener, true /* packageUpdated */);
        mContext.registerReceiver(packageUpdateReceiver, packageIntentFilter);

        // TODO(nfuller): Add more exotic intents as needed. e.g.
        // packageIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        // Also, disabled...?
        mReliabilityReceiver = new Receiver(listener, false /* packageUpdated */);
    }

    /** Sends an intent to trigger an update check. */
    @Override
    public void sendTriggerUpdateCheck(CheckToken checkToken) {
        RulesUpdaterContract.sendBroadcast(
                mContext, mUpdaterAppPackageName, checkToken.toByteArray());
        EventLogTags.writeTimezoneTriggerCheck(checkToken.toString());
    }

    @Override
    public synchronized void enableReliabilityTriggering() {
        if (!mReliabilityReceiverEnabled) {
            // The intent filter that exists to make updates reliable in the event of failures /
            // reboots.
            IntentFilter reliabilityIntentFilter = new IntentFilter();
            reliabilityIntentFilter.addAction(Intent.ACTION_IDLE_MAINTENANCE_START);
            mContext.registerReceiver(mReliabilityReceiver, reliabilityIntentFilter);
            mReliabilityReceiverEnabled = true;
        }
    }

    @Override
    public synchronized void disableReliabilityTriggering() {
        if (mReliabilityReceiverEnabled) {
            mContext.unregisterReceiver(mReliabilityReceiver);
            mReliabilityReceiverEnabled = false;
        }
    }

    private static class Receiver extends BroadcastReceiver {
        private final Listener mListener;
        private final boolean mPackageUpdated;

        private Receiver(Listener listener, boolean packageUpdated) {
            mListener = listener;
            mPackageUpdated = packageUpdated;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Slog.d(TAG, "Received intent: " + intent.toString());
            mListener.triggerUpdateIfNeeded(mPackageUpdated);
        }
    }

}
