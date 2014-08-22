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
 * limitations under the License.
 */

package com.android.server.trust;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.service.trust.ITrustAgentService;
import android.service.trust.ITrustAgentServiceCallback;
import android.service.trust.TrustAgentService;

import java.util.ArrayList;
import java.util.List;

/**
 * A wrapper around a TrustAgentService interface. Coordinates communication between
 * TrustManager and the actual TrustAgent.
 */
public class TrustAgentWrapper {
    private static final String EXTRA_COMPONENT_NAME = "componentName";
    private static final String TRUST_EXPIRED_ACTION = "android.server.trust.TRUST_EXPIRED_ACTION";
    private static final String PERMISSION = "android.permission.PROVIDE_TRUST_AGENT";
    private static final boolean DEBUG = false;
    private static final String TAG = "TrustAgentWrapper";

    private static final int MSG_GRANT_TRUST = 1;
    private static final int MSG_REVOKE_TRUST = 2;
    private static final int MSG_TRUST_TIMEOUT = 3;
    private static final int MSG_RESTART_TIMEOUT = 4;
    private static final int MSG_SET_TRUST_AGENT_FEATURES_COMPLETED = 5;
    private static final int MSG_MANAGING_TRUST = 6;

    /**
     * Time in uptime millis that we wait for the service connection, both when starting
     * and when the service disconnects.
     */
    private static final long RESTART_TIMEOUT_MILLIS = 5 * 60000;

    /**
     * Long extra for {@link #MSG_GRANT_TRUST}
     */
    private static final String DATA_DURATION = "duration";

    private final TrustManagerService mTrustManagerService;
    private final int mUserId;
    private final Context mContext;
    private final ComponentName mName;

    private ITrustAgentService mTrustAgentService;
    private boolean mBound;
    private long mScheduledRestartUptimeMillis;

    // Trust state
    private boolean mTrusted;
    private CharSequence mMessage;
    private boolean mTrustDisabledByDpm;
    private boolean mManagingTrust;
    private IBinder mSetTrustAgentFeaturesToken;
    private AlarmManager mAlarmManager;
    private final Intent mAlarmIntent;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ComponentName component = intent.getParcelableExtra(EXTRA_COMPONENT_NAME);
            if (TRUST_EXPIRED_ACTION.equals(intent.getAction())
                    && mName.equals(component)) {
                mHandler.removeMessages(MSG_TRUST_TIMEOUT);
                mHandler.sendEmptyMessage(MSG_TRUST_TIMEOUT);
            }
        }
    };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_GRANT_TRUST:
                    if (!isConnected()) {
                        Log.w(TAG, "Agent is not connected, cannot grant trust: "
                                + mName.flattenToShortString());
                        return;
                    }
                    mTrusted = true;
                    mMessage = (CharSequence) msg.obj;
                    boolean initiatedByUser = msg.arg1 != 0;
                    long durationMs = msg.getData().getLong(DATA_DURATION);
                    if (durationMs > 0) {
                        long expiration = SystemClock.elapsedRealtime() + durationMs;
                        PendingIntent op = PendingIntent.getBroadcast(mContext, 0, mAlarmIntent,
                                PendingIntent.FLAG_CANCEL_CURRENT);
                        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, expiration, op);
                    }
                    mTrustManagerService.mArchive.logGrantTrust(mUserId, mName,
                            (mMessage != null ? mMessage.toString() : null),
                            durationMs, initiatedByUser);
                    mTrustManagerService.updateTrust(mUserId, initiatedByUser);
                    break;
                case MSG_TRUST_TIMEOUT:
                    if (DEBUG) Slog.v(TAG, "Trust timed out : " + mName.flattenToShortString());
                    mTrustManagerService.mArchive.logTrustTimeout(mUserId, mName);
                    onTrustTimeout();
                    // Fall through.
                case MSG_REVOKE_TRUST:
                    mTrusted = false;
                    mMessage = null;
                    mHandler.removeMessages(MSG_TRUST_TIMEOUT);
                    if (msg.what == MSG_REVOKE_TRUST) {
                        mTrustManagerService.mArchive.logRevokeTrust(mUserId, mName);
                    }
                    mTrustManagerService.updateTrust(mUserId, false);
                    break;
                case MSG_RESTART_TIMEOUT:
                    unbind();
                    mTrustManagerService.resetAgent(mName, mUserId);
                    break;
                case MSG_SET_TRUST_AGENT_FEATURES_COMPLETED:
                    IBinder token = (IBinder) msg.obj;
                    boolean result = msg.arg1 != 0;
                    if (mSetTrustAgentFeaturesToken == token) {
                        mSetTrustAgentFeaturesToken = null;
                        if (mTrustDisabledByDpm && result) {
                            if (DEBUG) Log.v(TAG, "Re-enabling agent because it acknowledged "
                                    + "enabled features: " + mName);
                            mTrustDisabledByDpm = false;
                            mTrustManagerService.updateTrust(mUserId, false);
                        }
                    } else {
                        if (DEBUG) Log.w(TAG, "Ignoring MSG_SET_TRUST_AGENT_FEATURES_COMPLETED "
                                + "with obsolete token: " + mName);
                    }
                    break;
                case MSG_MANAGING_TRUST:
                    mManagingTrust = msg.arg1 != 0;
                    if (!mManagingTrust) {
                        mTrusted = false;
                        mMessage = null;
                    }
                    mTrustManagerService.mArchive.logManagingTrust(mUserId, mName, mManagingTrust);
                    mTrustManagerService.updateTrust(mUserId, false);
                    break;
            }
        }
    };

    private ITrustAgentServiceCallback mCallback = new ITrustAgentServiceCallback.Stub() {

        @Override
        public void grantTrust(CharSequence userMessage, long durationMs, boolean initiatedByUser) {
            if (DEBUG) Slog.v(TAG, "enableTrust(" + userMessage + ", durationMs = " + durationMs
                        + ", initiatedByUser = " + initiatedByUser + ")");

            Message msg = mHandler.obtainMessage(
                    MSG_GRANT_TRUST, initiatedByUser ? 1 : 0, 0, userMessage);
            msg.getData().putLong(DATA_DURATION, durationMs);
            msg.sendToTarget();
        }

        @Override
        public void revokeTrust() {
            if (DEBUG) Slog.v(TAG, "revokeTrust()");
            mHandler.sendEmptyMessage(MSG_REVOKE_TRUST);
        }

        @Override
        public void setManagingTrust(boolean managingTrust) {
            if (DEBUG) Slog.v(TAG, "managingTrust()");
            mHandler.obtainMessage(MSG_MANAGING_TRUST, managingTrust ? 1 : 0, 0).sendToTarget();
        }

        @Override
        public void onSetTrustAgentFeaturesEnabledCompleted(boolean result, IBinder token) {
            if (DEBUG) Slog.v(TAG, "onSetTrustAgentFeaturesEnabledCompleted(result=" + result);
            mHandler.obtainMessage(MSG_SET_TRUST_AGENT_FEATURES_COMPLETED,
                    result ? 1 : 0, 0, token).sendToTarget();
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Log.v(TAG, "TrustAgent started : " + name.flattenToString());
            mHandler.removeMessages(MSG_RESTART_TIMEOUT);
            mTrustAgentService = ITrustAgentService.Stub.asInterface(service);
            mTrustManagerService.mArchive.logAgentConnected(mUserId, name);
            setCallback(mCallback);
            updateDevicePolicyFeatures();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Log.v(TAG, "TrustAgent disconnected : " + name.flattenToShortString());
            mTrustAgentService = null;
            mManagingTrust = false;
            mSetTrustAgentFeaturesToken = null;
            mTrustManagerService.mArchive.logAgentDied(mUserId, name);
            mHandler.sendEmptyMessage(MSG_REVOKE_TRUST);
            if (mBound) {
                scheduleRestart();
            }
            // mTrustDisabledByDpm maintains state
        }
    };

    public TrustAgentWrapper(Context context, TrustManagerService trustManagerService,
            Intent intent, UserHandle user) {
        mContext = context;
        mTrustManagerService = trustManagerService;
        mAlarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        mUserId = user.getIdentifier();
        mName = intent.getComponent();

        mAlarmIntent = new Intent(TRUST_EXPIRED_ACTION).putExtra(EXTRA_COMPONENT_NAME, mName);
        mAlarmIntent.setData(Uri.parse(mAlarmIntent.toUri(Intent.URI_INTENT_SCHEME)));

        final IntentFilter alarmFilter = new IntentFilter(TRUST_EXPIRED_ACTION);
        alarmFilter.addDataScheme(mAlarmIntent.getScheme());
        final String pathUri = mAlarmIntent.toUri(Intent.URI_INTENT_SCHEME);
        alarmFilter.addDataPath(pathUri, PatternMatcher.PATTERN_LITERAL);
        mContext.registerReceiver(mBroadcastReceiver, alarmFilter);

        // Schedules a restart for when connecting times out. If the connection succeeds,
        // the restart is canceled in mCallback's onConnected.
        scheduleRestart();
        mBound = context.bindServiceAsUser(intent, mConnection, Context.BIND_AUTO_CREATE, user);
        if (!mBound) {
            Log.e(TAG, "Can't bind to TrustAgent " + mName.flattenToShortString());
        }
    }

    private void onError(Exception e) {
        Slog.w(TAG , "Remote Exception", e);
    }

    private void onTrustTimeout() {
        try {
            if (mTrustAgentService != null) mTrustAgentService.onTrustTimeout();
        } catch (RemoteException e) {
            onError(e);
        }
    }
    /**
     * @see android.service.trust.TrustAgentService#onUnlockAttempt(boolean)
     */
    public void onUnlockAttempt(boolean successful) {
        try {
            if (mTrustAgentService != null) mTrustAgentService.onUnlockAttempt(successful);
        } catch (RemoteException e) {
            onError(e);
        }
    }

    private void setCallback(ITrustAgentServiceCallback callback) {
        try {
            if (mTrustAgentService != null) {
                mTrustAgentService.setCallback(callback);
            }
        } catch (RemoteException e) {
            onError(e);
        }
    }

    boolean updateDevicePolicyFeatures() {
        boolean trustDisabled = false;
        if (DEBUG) Slog.v(TAG, "updateDevicePolicyFeatures(" + mName + ")");
        try {
            if (mTrustAgentService != null) {
                DevicePolicyManager dpm =
                    (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);

                if ((dpm.getKeyguardDisabledFeatures(null)
                        & DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS) != 0) {
                    List<String> features = dpm.getTrustAgentFeaturesEnabled(null, mName);
                    trustDisabled = true;
                    if (DEBUG) Slog.v(TAG, "Detected trust agents disabled. Features = "
                            + features);
                    if (features != null && features.size() > 0) {
                        Bundle bundle = new Bundle();
                        bundle.putStringArrayList(TrustAgentService.KEY_FEATURES,
                                (ArrayList<String>)features);
                        if (DEBUG) {
                            Slog.v(TAG, "TrustAgent " + mName.flattenToShortString()
                                    + " disabled until it acknowledges "+ features);
                        }
                        mSetTrustAgentFeaturesToken = new Binder();
                        mTrustAgentService.setTrustAgentFeaturesEnabled(bundle,
                                mSetTrustAgentFeaturesToken);
                    }
                }
            }
        } catch (RemoteException e) {
            onError(e);
        }
        if (mTrustDisabledByDpm != trustDisabled) {
            mTrustDisabledByDpm = trustDisabled;
            mTrustManagerService.updateTrust(mUserId, false);
        }
        return trustDisabled;
    }

    public boolean isTrusted() {
        return mTrusted && mManagingTrust && !mTrustDisabledByDpm;
    }

    public boolean isManagingTrust() {
        return mManagingTrust && !mTrustDisabledByDpm;
    }

    public CharSequence getMessage() {
        return mMessage;
    }

    public void unbind() {
        if (!mBound) {
            return;
        }
        if (DEBUG) Log.v(TAG, "TrustAgent unbound : " + mName.flattenToShortString());
        mTrustManagerService.mArchive.logAgentStopped(mUserId, mName);
        mContext.unbindService(mConnection);
        mBound = false;
        mTrustAgentService = null;
        mSetTrustAgentFeaturesToken = null;
        mHandler.sendEmptyMessage(MSG_REVOKE_TRUST);
        mHandler.removeMessages(MSG_RESTART_TIMEOUT);
    }

    public boolean isConnected() {
        return mTrustAgentService != null;
    }

    public boolean isBound() {
        return mBound;
    }

    /**
     * If not connected, returns the time at which the agent is restarted.
     *
     * @return restart time in uptime millis.
     */
    public long getScheduledRestartUptimeMillis() {
        return mScheduledRestartUptimeMillis;
    }

    private void scheduleRestart() {
        mHandler.removeMessages(MSG_RESTART_TIMEOUT);
        mScheduledRestartUptimeMillis = SystemClock.uptimeMillis() + RESTART_TIMEOUT_MILLIS;
        mHandler.sendEmptyMessageAtTime(MSG_RESTART_TIMEOUT, mScheduledRestartUptimeMillis);
    }
}
