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
import android.util.Log;
import android.widget.Toast;

public class SampleTrustAgent extends TrustAgentService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    /**
     * If true, allows anyone to control this trust agent, e.g. using adb:
     * <pre>
     * $ adb shell am broadcast -a action.sample_trust_agent.grant_trust\
     *  -e extra.message SampleTrust\
     *  --el extra.duration 1000 --ez extra.init_by_user false
     * </pre>
     */
    private static final boolean ALLOW_EXTERNAL_BROADCASTS = false;

    LocalBroadcastManager mLocalBroadcastManager;

    private static final String ACTION_GRANT_TRUST = "action.sample_trust_agent.grant_trust";
    private static final String ACTION_REVOKE_TRUST = "action.sample_trust_agent.revoke_trust";

    private static final String EXTRA_MESSAGE = "extra.message";
    private static final String EXTRA_DURATION = "extra.duration";
    private static final String EXTRA_INITIATED_BY_USER = "extra.init_by_user";

    private static final String PREFERENCE_REPORT_UNLOCK_ATTEMPTS
            = "preference.report_unlock_attempts";
    private static final String PREFERENCE_MANAGING_TRUST
            = "preference.managing_trust";

    private static final String TAG = "SampleTrustAgent";

    @Override
    public void onCreate() {
        super.onCreate();
        mLocalBroadcastManager = LocalBroadcastManager.getInstance(this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_GRANT_TRUST);
        filter.addAction(ACTION_REVOKE_TRUST);
        mLocalBroadcastManager.registerReceiver(mReceiver, filter);
        if (ALLOW_EXTERNAL_BROADCASTS) {
            registerReceiver(mReceiver, filter);
        }

        setManagingTrust(getIsManagingTrust(this));
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onUnlockAttempt(boolean successful) {
        if (getReportUnlockAttempts(this)) {
            Toast.makeText(this, "onUnlockAttempt(successful=" + successful + ")",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onSetTrustAgentFeaturesEnabled(Bundle options) {
        Log.v(TAG, "Policy options received: " + options.getStringArrayList(KEY_FEATURES));
        // TODO: Handle options
        return true; // inform DPM that we support it
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mLocalBroadcastManager.unregisterReceiver(mReceiver);
        if (ALLOW_EXTERNAL_BROADCASTS) {
            unregisterReceiver(mReceiver);
        }
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(this);
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_GRANT_TRUST.equals(action)) {
                try {
                    grantTrust(intent.getStringExtra(EXTRA_MESSAGE),
                            intent.getLongExtra(EXTRA_DURATION, 0),
                            intent.getBooleanExtra(EXTRA_INITIATED_BY_USER, false));
                } catch (IllegalStateException e) {
                    Toast.makeText(context,
                            "IllegalStateException: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            } else if (ACTION_REVOKE_TRUST.equals(action)) {
                revokeTrust();
            }
        }
    };

    public static void sendGrantTrust(Context context,
            String message, long durationMs, boolean initiatedByUser) {
        Intent intent = new Intent(ACTION_GRANT_TRUST);
        intent.putExtra(EXTRA_MESSAGE, message);
        intent.putExtra(EXTRA_DURATION, durationMs);
        intent.putExtra(EXTRA_INITIATED_BY_USER, initiatedByUser);
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

    public static void setIsManagingTrust(Context context, boolean enabled) {
        SharedPreferences sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        sharedPreferences.edit().putBoolean(PREFERENCE_MANAGING_TRUST, enabled).apply();
    }

    public static boolean getIsManagingTrust(Context context) {
        SharedPreferences sharedPreferences = PreferenceManager
                .getDefaultSharedPreferences(context);
        return sharedPreferences.getBoolean(PREFERENCE_MANAGING_TRUST, false);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (PREFERENCE_MANAGING_TRUST.equals(key)) {
            setManagingTrust(getIsManagingTrust(this));
        }
    }
}
