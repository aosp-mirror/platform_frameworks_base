/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.trustagent.test;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.service.trust.TrustAgentService;
import android.support.v4.content.LocalBroadcastManager;
import android.widget.Toast;

public class SampleTrustAgent extends TrustAgentService {

    LocalBroadcastManager mLocalBroadcastManager;

    private static final String ACTION_GRANT_TRUST = "action.sample_trust_agent.grant_trust";
    private static final String ACTION_REVOKE_TRUST = "action.sample_trust_agent.revoke_trust";

    private static final String EXTRA_MESSAGE = "extra.message";
    private static final String EXTRA_DURATION = "extra.duration";
    private static final String EXTRA_EXTRA = "extra.extra";

    private static final String PREFERENCE_REPORT_UNLOCK_ATTEMPTS
            = "preference.report_unlock_attempts";

    @Override
    public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_GRANT_TRUST);
        filter.addAction(ACTION_REVOKE_TRUST);
        mLocalBroadcastManager = LocalBroadcastManager.getInstance(this);
        mLocalBroadcastManager.registerReceiver(mReceiver, filter);
    }

    @Override
    public void onUnlockAttempt(boolean successful) {
        if (getReportUnlockAttempts(this)) {
            Toast.makeText(this, "onUnlockAttempt(successful=" + successful + ")",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mLocalBroadcastManager.unregisterReceiver(mReceiver);
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_GRANT_TRUST.equals(action)) {
                grantTrust(intent.getStringExtra(EXTRA_MESSAGE),
                        intent.getLongExtra(EXTRA_DURATION, 0),
                        false /* initiatedByUser */);
            } else if (ACTION_REVOKE_TRUST.equals(action)) {
                revokeTrust();
            }
        }
    };

    public static void sendGrantTrust(Context context,
            String message, long durationMs, Bundle extra) {
        Intent intent = new Intent(ACTION_GRANT_TRUST);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_DURATION, durationMs);
        intent.putExtra(EXTRA_EXTRA, extra);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    public static void sendRevokeTrust(Context context) {
        Intent intent = new Intent(ACTION_REVOKE_TRUST);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    public static void setReportUnlockAttempts(Context context, boolean enabled) {
        SharedPreferences sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        sharedPreferences.edit().putBoolean(PREFERENCE_REPORT_UNLOCK_ATTEMPTS, enabled).apply();
    }

    public static boolean getReportUnlockAttempts(Context context) {
        SharedPreferences sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        return sharedPreferences.getBoolean(PREFERENCE_REPORT_UNLOCK_ATTEMPTS, false);
    }
}
