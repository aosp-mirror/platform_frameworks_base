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

import static android.service.trust.TrustAgentService.FLAG_GRANT_TRUST_DISPLAY_MESSAGE;
import static android.service.trust.TrustAgentService.FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE;

import android.annotation.TargetApi;
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
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PatternMatcher;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.trust.GrantTrustResult;
import android.service.trust.ITrustAgentService;
import android.service.trust.ITrustAgentServiceCallback;
import android.service.trust.TrustAgentService;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;
import com.android.server.utils.Slogf;

import java.util.Collections;
import java.util.List;

/**
 * A wrapper around a TrustAgentService interface. Coordinates communication between
 * TrustManager and the actual TrustAgent.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class TrustAgentWrapper {
    private static final String EXTRA_COMPONENT_NAME = "componentName";
    private static final String TRUST_EXPIRED_ACTION = "android.server.trust.TRUST_EXPIRED_ACTION";
    private static final String PERMISSION = android.Manifest.permission.PROVIDE_TRUST_AGENT;
    private static final boolean DEBUG = TrustManagerService.DEBUG;
    private static final String TAG = "TrustAgentWrapper";

    private static final int MSG_GRANT_TRUST = 1;
    private static final int MSG_REVOKE_TRUST = 2;
    private static final int MSG_TRUST_TIMEOUT = 3;
    private static final int MSG_RESTART_TIMEOUT = 4;
    private static final int MSG_SET_TRUST_AGENT_FEATURES_COMPLETED = 5;
    private static final int MSG_MANAGING_TRUST = 6;
    private static final int MSG_ADD_ESCROW_TOKEN = 7;
    private static final int MSG_REMOVE_ESCROW_TOKEN = 8;
    private static final int MSG_ESCROW_TOKEN_STATE = 9;
    private static final int MSG_UNLOCK_USER = 10;
    private static final int MSG_SHOW_KEYGUARD_ERROR_MESSAGE = 11;
    private static final int MSG_LOCK_USER = 12;

    /**
     * Time in uptime millis that we wait for the service connection, both when starting
     * and when the service disconnects.
     */
    private static final long RESTART_TIMEOUT_MILLIS = 5 * 60000;

    /**
     * Long extra for {@link #MSG_GRANT_TRUST}
     */
    private static final String DATA_DURATION = "duration";
    private static final String DATA_ESCROW_TOKEN = "escrow_token";
    private static final String DATA_HANDLE = "handle";
    private static final String DATA_USER_ID = "user_id";
    private static final String DATA_MESSAGE = "message";

    private final TrustManagerService mTrustManagerService;
    private final int mUserId;
    private final Context mContext;
    private final ComponentName mName;

    private ITrustAgentService mTrustAgentService;
    private boolean mBound;
    private long mScheduledRestartUptimeMillis;
    private long mMaximumTimeToLock; // from DevicePolicyManager
    private boolean mPendingSuccessfulUnlock = false;

    // Trust state
    private boolean mTrusted;
    private boolean mWaitingForTrustableDowngrade = false;
    private boolean mWithinSecurityLockdownWindow = false;
    private boolean mTrustable;
    private CharSequence mMessage;
    private boolean mDisplayTrustGrantedMessage;
    private boolean mTrustDisabledByDpm;
    private boolean mManagingTrust;
    private IBinder mSetTrustAgentFeaturesToken;
    private AlarmManager mAlarmManager;
    private final Intent mAlarmIntent;
    private PendingIntent mAlarmPendingIntent;
    private final BroadcastReceiver mTrustableDowngradeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // are these the broadcasts we want to listen to
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                downgradeToTrustable();
            }
        }
    };

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            ComponentName component = intent.getParcelableExtra(EXTRA_COMPONENT_NAME, android.content.ComponentName.class);
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
                    mTrustable = false;
                    Pair<CharSequence, AndroidFuture<GrantTrustResult>> pair = (Pair) msg.obj;
                    mMessage = pair.first;
                    AndroidFuture<GrantTrustResult> resultCallback = pair.second;
                    int flags = msg.arg1;
                    mDisplayTrustGrantedMessage = (flags & FLAG_GRANT_TRUST_DISPLAY_MESSAGE) != 0;
                    if ((flags & FLAG_GRANT_TRUST_TEMPORARY_AND_RENEWABLE) != 0) {
                        mWaitingForTrustableDowngrade = true;
                        resultCallback.thenAccept(result -> {
                            if (result.getStatus() == GrantTrustResult.STATUS_UNLOCKED_BY_GRANT) {
                                // if we are not unlocked by grantTrust, then we don't need to
                                // have the timer for the security window
                                setSecurityWindowTimer();
                            }
                        });
                    } else {
                        mWaitingForTrustableDowngrade = false;
                    }
                    long durationMs = msg.getData().getLong(DATA_DURATION);
                    if (durationMs > 0) {
                        final long duration;
                        if (mMaximumTimeToLock != 0) {
                            // Enforce DevicePolicyManager timeout.  This is here as a safeguard to
                            // ensure trust agents are evaluating trust state at least as often as
                            // the policy dictates. Admins that want more guarantees should be using
                            // DevicePolicyManager#KEYGUARD_DISABLE_TRUST_AGENTS.
                            duration = Math.min(durationMs, mMaximumTimeToLock);
                            if (DEBUG) {
                                Slog.d(TAG, "DPM lock timeout in effect. Timeout adjusted from "
                                    + durationMs + " to " + duration);
                            }
                        } else {
                            duration = durationMs;
                        }
                        long expiration = SystemClock.elapsedRealtime() + duration;
                        mAlarmPendingIntent = PendingIntent.getBroadcast(mContext, 0, mAlarmIntent,
                                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED);
                        mAlarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, expiration,
                                mAlarmPendingIntent);
                    }
                    mTrustManagerService.mArchive.logGrantTrust(mUserId, mName,
                            (mMessage != null ? mMessage.toString() : null),
                            durationMs, flags);
                    mTrustManagerService.updateTrust(mUserId, flags, resultCallback);
                    break;
                case MSG_TRUST_TIMEOUT:
                    if (DEBUG) Slog.d(TAG, "Trust timed out : " + mName.flattenToShortString());
                    mTrustManagerService.mArchive.logTrustTimeout(mUserId, mName);
                    onTrustTimeout();
                    // Fall through.
                case MSG_REVOKE_TRUST:
                    mTrusted = false;
                    mTrustable = false;
                    mWaitingForTrustableDowngrade = false;
                    mDisplayTrustGrantedMessage = false;
                    mMessage = null;
                    mHandler.removeMessages(MSG_TRUST_TIMEOUT);
                    if (msg.what == MSG_REVOKE_TRUST) {
                        mTrustManagerService.mArchive.logRevokeTrust(mUserId, mName);
                    }
                    mTrustManagerService.updateTrust(mUserId, 0);
                    break;
                case MSG_RESTART_TIMEOUT:
                    Slog.w(TAG, "Connection attempt to agent " + mName.flattenToShortString()
                            + " timed out, rebinding");
                    destroy();
                    mTrustManagerService.resetAgent(mName, mUserId);
                    break;
                case MSG_SET_TRUST_AGENT_FEATURES_COMPLETED:
                    IBinder token = (IBinder) msg.obj;
                    boolean result = msg.arg1 != 0;
                    if (mSetTrustAgentFeaturesToken == token) {
                        mSetTrustAgentFeaturesToken = null;
                        if (mTrustDisabledByDpm && result) {
                            if (DEBUG) Slog.d(TAG, "Re-enabling agent because it acknowledged "
                                    + "enabled features: " + mName.flattenToShortString());
                            mTrustDisabledByDpm = false;
                            mTrustManagerService.updateTrust(mUserId, 0);
                        }
                    } else {
                        if (DEBUG) Slog.w(TAG, "Ignoring MSG_SET_TRUST_AGENT_FEATURES_COMPLETED "
                                + "with obsolete token: " + mName.flattenToShortString());
                    }
                    break;
                case MSG_MANAGING_TRUST:
                    mManagingTrust = msg.arg1 != 0;
                    if (!mManagingTrust) {
                        mTrusted = false;
                        mDisplayTrustGrantedMessage = false;
                        mMessage = null;
                    }
                    mTrustManagerService.mArchive.logManagingTrust(mUserId, mName, mManagingTrust);
                    mTrustManagerService.updateTrust(mUserId, 0);
                    break;
                case MSG_ADD_ESCROW_TOKEN: {
                    byte[] eToken = msg.getData().getByteArray(DATA_ESCROW_TOKEN);
                    int userId = msg.getData().getInt(DATA_USER_ID);
                    long handle = mTrustManagerService.addEscrowToken(eToken, userId);
                    boolean resultDeliverred = false;
                    try {
                        if (mTrustAgentService != null) {
                            mTrustAgentService.onEscrowTokenAdded(
                                    eToken, handle, UserHandle.of(userId));
                            resultDeliverred = true;
                        }
                    } catch (RemoteException e) {
                        onError(e);
                    }

                    if (!resultDeliverred) {
                        mTrustManagerService.removeEscrowToken(handle, userId);
                    }
                    break;
                }
                case MSG_ESCROW_TOKEN_STATE: {
                    long handle = msg.getData().getLong(DATA_HANDLE);
                    int userId = msg.getData().getInt(DATA_USER_ID);
                    boolean active = mTrustManagerService.isEscrowTokenActive(handle, userId);
                    try {
                        if (mTrustAgentService != null) {
                            mTrustAgentService.onTokenStateReceived(handle,
                                    active ? TrustAgentService.TOKEN_STATE_ACTIVE
                                            : TrustAgentService.TOKEN_STATE_INACTIVE);
                        }
                    } catch (RemoteException e) {
                        onError(e);
                    }
                    break;
                }
                case MSG_REMOVE_ESCROW_TOKEN: {
                    long handle = msg.getData().getLong(DATA_HANDLE);
                    int userId = msg.getData().getInt(DATA_USER_ID);
                    boolean success = mTrustManagerService.removeEscrowToken(handle, userId);
                    try {
                        if (mTrustAgentService != null) {
                            mTrustAgentService.onEscrowTokenRemoved(handle, success);
                        }
                    } catch (RemoteException e) {
                        onError(e);
                    }
                    break;
                }
                case MSG_UNLOCK_USER: {
                    long handle = msg.getData().getLong(DATA_HANDLE);
                    int userId = msg.getData().getInt(DATA_USER_ID);
                    byte[] eToken = msg.getData().getByteArray(DATA_ESCROW_TOKEN);
                    mTrustManagerService.unlockUserWithToken(handle, eToken, userId);
                    break;
                }
                case MSG_SHOW_KEYGUARD_ERROR_MESSAGE: {
                    CharSequence message = msg.getData().getCharSequence(DATA_MESSAGE);
                    mTrustManagerService.showKeyguardErrorMessage(message);
                    break;
                }
                case MSG_LOCK_USER: {
                    mTrusted = false;
                    mTrustable = false;
                    mTrustManagerService.updateTrust(mUserId, 0 /* flags */);
                    mTrustManagerService.lockUser(mUserId);
                    break;
                }
            }
        }
    };

    private ITrustAgentServiceCallback mCallback = new ITrustAgentServiceCallback.Stub() {

        @Override
        public void grantTrust(
                CharSequence message,
                long durationMs,
                int flags,
                AndroidFuture resultCallback) {
            if (DEBUG) {
                Slogf.d(TAG, "grantTrust(message=\"%s\", durationMs=%d, flags=0x%x)",
                        message, durationMs, flags);
            }

            Message msg = mHandler.obtainMessage(
                    MSG_GRANT_TRUST, flags, 0, Pair.create(message, resultCallback));
            msg.getData().putLong(DATA_DURATION, durationMs);
            msg.sendToTarget();
        }

        @Override
        public void revokeTrust() {
            if (DEBUG) Slog.d(TAG, "revokeTrust()");
            mHandler.sendEmptyMessage(MSG_REVOKE_TRUST);
        }

        @Override
        public void lockUser() {
            if (DEBUG) Slog.d(TAG, "lockUser()");
            mHandler.sendEmptyMessage(MSG_LOCK_USER);
        }

        @Override
        public void setManagingTrust(boolean managingTrust) {
            if (DEBUG) Slogf.d(TAG, "setManagingTrust(%s)", managingTrust);
            mHandler.obtainMessage(MSG_MANAGING_TRUST, managingTrust ? 1 : 0, 0).sendToTarget();
        }

        @Override
        public void onConfigureCompleted(boolean result, IBinder token) {
            if (DEBUG) Slogf.d(TAG, "onConfigureCompleted(result=%s)", result);
            mHandler.obtainMessage(MSG_SET_TRUST_AGENT_FEATURES_COMPLETED,
                    result ? 1 : 0, 0, token).sendToTarget();
        }

        @Override
        public void addEscrowToken(byte[] token, int userId) {
            // 'token' is secret; never log it.
            if (DEBUG) Slogf.d(TAG, "addEscrowToken(userId=%d)", userId);

            if (mContext.getResources()
                    .getBoolean(com.android.internal.R.bool.config_allowEscrowTokenForTrustAgent)) {
                throw new SecurityException("Escrow token API is not allowed.");
            }
            Message msg = mHandler.obtainMessage(MSG_ADD_ESCROW_TOKEN);
            msg.getData().putByteArray(DATA_ESCROW_TOKEN, token);
            msg.getData().putInt(DATA_USER_ID, userId);
            msg.sendToTarget();
        }

        @Override
        public void isEscrowTokenActive(long handle, int userId) {
            if (DEBUG) Slogf.d(TAG, "isEscrowTokenActive(handle=%016x, userId=%d)", handle, userId);

            if (mContext.getResources()
                    .getBoolean(com.android.internal.R.bool.config_allowEscrowTokenForTrustAgent)) {
                throw new SecurityException("Escrow token API is not allowed.");
            }
            Message msg = mHandler.obtainMessage(MSG_ESCROW_TOKEN_STATE);
            msg.getData().putLong(DATA_HANDLE, handle);
            msg.getData().putInt(DATA_USER_ID, userId);
            msg.sendToTarget();
        }

        @Override
        public void removeEscrowToken(long handle, int userId) {
            if (DEBUG) Slogf.d(TAG, "removeEscrowToken(handle=%016x, userId=%d)", handle, userId);

            if (mContext.getResources()
                    .getBoolean(com.android.internal.R.bool.config_allowEscrowTokenForTrustAgent)) {
                throw new SecurityException("Escrow token API is not allowed.");
            }
            Message msg = mHandler.obtainMessage(MSG_REMOVE_ESCROW_TOKEN);
            msg.getData().putLong(DATA_HANDLE, handle);
            msg.getData().putInt(DATA_USER_ID, userId);
            msg.sendToTarget();
        }

        @Override
        public void unlockUserWithToken(long handle, byte[] token, int userId) {
            // 'token' is secret; never log it.
            if (DEBUG) Slogf.d(TAG, "unlockUserWithToken(handle=%016x, userId=%d)", handle, userId);

            if (mContext.getResources()
                    .getBoolean(com.android.internal.R.bool.config_allowEscrowTokenForTrustAgent)) {
                throw new SecurityException("Escrow token API is not allowed.");
            }
            Message msg = mHandler.obtainMessage(MSG_UNLOCK_USER);
            msg.getData().putInt(DATA_USER_ID, userId);
            msg.getData().putLong(DATA_HANDLE, handle);
            msg.getData().putByteArray(DATA_ESCROW_TOKEN, token);
            msg.sendToTarget();
        }

        @Override
        public void showKeyguardErrorMessage(CharSequence message) {
            if (DEBUG) Slogf.d(TAG, "showKeyguardErrorMessage(\"%s\")", message);
            Message msg = mHandler.obtainMessage(MSG_SHOW_KEYGUARD_ERROR_MESSAGE);
            msg.getData().putCharSequence(DATA_MESSAGE, message);
            msg.sendToTarget();
        }
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Slog.d(TAG, "TrustAgent started : " + name.flattenToString());
            mHandler.removeMessages(MSG_RESTART_TIMEOUT);
            mTrustAgentService = ITrustAgentService.Stub.asInterface(service);
            mTrustManagerService.mArchive.logAgentConnected(mUserId, name);
            setCallback(mCallback);
            updateDevicePolicyFeatures();

            if (mPendingSuccessfulUnlock) {
                onUnlockAttempt(true);
                mPendingSuccessfulUnlock = false;
            }

            // It's okay to use the "Inner" version of isDeviceLocked since they differ only for
            // profiles, which cannot be switched to and thus don't support trust agents anyway.
            if (mTrustManagerService.isDeviceLockedInner(mUserId)) {
                onDeviceLocked();
            } else {
                onDeviceUnlocked();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Slog.d(TAG, "TrustAgent disconnected : " + name.flattenToShortString());
            mTrustAgentService = null;
            mManagingTrust = false;
            mSetTrustAgentFeaturesToken = null;
            mTrustManagerService.mArchive.logAgentDied(mUserId, name);
            mHandler.sendEmptyMessage(MSG_REVOKE_TRUST);
            if (mBound) {
                scheduleRestart();
            }
            if (mWithinSecurityLockdownWindow) {
                mTrustManagerService.lockUser(mUserId);
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
        mAlarmIntent.setPackage(context.getPackageName());

        final IntentFilter alarmFilter = new IntentFilter(TRUST_EXPIRED_ACTION);
        alarmFilter.addDataScheme(mAlarmIntent.getScheme());
        final String pathUri = mAlarmIntent.toUri(Intent.URI_INTENT_SCHEME);
        alarmFilter.addDataPath(pathUri, PatternMatcher.PATTERN_LITERAL);

        IntentFilter trustableFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);

        // Schedules a restart for when connecting times out. If the connection succeeds,
        // the restart is canceled in mCallback's onConnected.
        scheduleRestart();
        mBound = context.bindServiceAsUser(intent, mConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE, user);
        if (mBound) {
            mContext.registerReceiver(mBroadcastReceiver, alarmFilter, PERMISSION, null,
                    Context.RECEIVER_EXPORTED);
            mContext.registerReceiver(mTrustableDowngradeReceiver, trustableFilter);
        } else {
            Log.e(TAG, "Can't bind to TrustAgent " + mName.flattenToShortString());
        }
    }

    private void onError(Exception e) {
        Slog.w(TAG , "Exception ", e);
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
            if (mTrustAgentService != null) {
                mTrustAgentService.onUnlockAttempt(successful);
            } else {
                mPendingSuccessfulUnlock = successful;
            }
        } catch (RemoteException e) {
            onError(e);
        }
    }

    /**
     * @see android.service.trust.TrustAgentService#onUserRequestedUnlock(boolean)
     */
    public void onUserRequestedUnlock(boolean dismissKeyguard) {
        try {
            if (mTrustAgentService != null) {
                mTrustAgentService.onUserRequestedUnlock(dismissKeyguard);
            }
        } catch (RemoteException e) {
            onError(e);
        }
    }

    /** @see android.service.trust.TrustAgentService#onUserMayRequestUnlock() */
    public void onUserMayRequestUnlock() {
        try {
            if (mTrustAgentService != null) {
                mTrustAgentService.onUserMayRequestUnlock();
            }
        } catch (RemoteException e) {
            onError(e);
        }
    }

    /**
     * @see android.service.trust.TrustAgentService#onUnlockLockout(int)
     */
    public void onUnlockLockout(int timeoutMs) {
        try {
            if (mTrustAgentService != null) {
                mTrustAgentService.onUnlockLockout(timeoutMs);
            }
        } catch (RemoteException e) {
            onError(e);
        }
    }

    /**
     * @see android.service.trust.TrustAgentService#onDeviceLocked()
     */
    public void onDeviceLocked() {
        mWithinSecurityLockdownWindow = false;
        try {
            if (mTrustAgentService != null) mTrustAgentService.onDeviceLocked();
        } catch (RemoteException e) {
            onError(e);
        }
    }

    /**
     * @see android.service.trust.TrustAgentService#onDeviceUnlocked()
     */
    public void onDeviceUnlocked() {
        try {
            if (mTrustAgentService != null) mTrustAgentService.onDeviceUnlocked();
        } catch (RemoteException e) {
            onError(e);
        }
    }

    /**
     * @see android.service.trust.TrustAgentService#onTokenStateReceived()
     *
     */
    public void onEscrowTokenActivated(long handle, int userId) {
        if (DEBUG) Slog.d(TAG, "onEscrowTokenActivated: " + handle + " user: " + userId);
        if (mTrustAgentService != null) {
            try {
                mTrustAgentService.onTokenStateReceived(handle,
                        TrustAgentService.TOKEN_STATE_ACTIVE);
            } catch (RemoteException e) {
                onError(e);
            }
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
        if (DEBUG) Slog.d(TAG, "updateDevicePolicyFeatures(" + mName + ")");
        try {
            if (mTrustAgentService != null) {
                DevicePolicyManager dpm =
                    (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);

                if ((dpm.getKeyguardDisabledFeatures(null, mUserId)
                        & DevicePolicyManager.KEYGUARD_DISABLE_TRUST_AGENTS) != 0) {
                    List<PersistableBundle> config = dpm.getTrustAgentConfiguration(
                            null, mName, mUserId);
                    trustDisabled = true;
                    if (DEBUG) Slog.d(TAG, "Detected trust agents disabled. Config = " + config);
                    if (config != null && config.size() > 0) {
                        if (DEBUG) {
                            Slog.d(TAG, "TrustAgent " + mName.flattenToShortString()
                                    + " disabled until it acknowledges "+ config);
                        }
                        mSetTrustAgentFeaturesToken = new Binder();
                        mTrustAgentService.onConfigure(config, mSetTrustAgentFeaturesToken);
                    }
                } else {
                    mTrustAgentService.onConfigure(Collections.EMPTY_LIST, null);
                }
                final long maxTimeToLock = dpm.getMaximumTimeToLock(null, mUserId);
                if (maxTimeToLock != mMaximumTimeToLock) {
                    // If the timeout changes, cancel the alarm and send a timeout event to have
                    // the agent re-evaluate trust.
                    mMaximumTimeToLock = maxTimeToLock;
                    if (mAlarmPendingIntent != null) {
                        mAlarmManager.cancel(mAlarmPendingIntent);
                        mAlarmPendingIntent = null;
                        mHandler.sendEmptyMessage(MSG_TRUST_TIMEOUT);
                    }
                }
            }
        } catch (RemoteException e) {
            onError(e);
        }
        if (mTrustDisabledByDpm != trustDisabled) {
            mTrustDisabledByDpm = trustDisabled;
            mTrustManagerService.updateTrust(mUserId, 0);
        }
        return trustDisabled;
    }

    public boolean isTrusted() {
        return mTrusted && mManagingTrust && !mTrustDisabledByDpm;
    }

    public boolean isTrustable() {
        return mTrustable && mManagingTrust && !mTrustDisabledByDpm;
    }

    public boolean isTrustableOrWaitingForDowngrade() {
        return mWaitingForTrustableDowngrade || isTrustable();
    }

    /** Set the trustagent as not trustable */
    public void setUntrustable() {
        mTrustable = false;
    }

    /**
     * Downgrades the trustagent to trustable as a result of a keyguard or screen related event, and
     * then updates the trust state of the phone to reflect the change.
     */
    public void downgradeToTrustable() {
        if (mWaitingForTrustableDowngrade) {
            mWaitingForTrustableDowngrade = false;
            mTrusted = false;
            mTrustable = true;
            mTrustManagerService.updateTrust(mUserId, 0);
        }
    }

    private void setSecurityWindowTimer() {
        mWithinSecurityLockdownWindow = true;
        long expiration = SystemClock.elapsedRealtime() + (15 * 1000); // timer for 15 seconds
        mAlarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                expiration,
                TAG,
                new AlarmManager.OnAlarmListener() {
                    @Override
                    public void onAlarm() {
                        mWithinSecurityLockdownWindow = false;
                    }
                },
                Handler.getMain());
    }

    public boolean isManagingTrust() {
        return mManagingTrust && !mTrustDisabledByDpm;
    }

    public CharSequence getMessage() {
        return mMessage;
    }

    /**
     * Whether the trust agent would like to display {@link #getMessage()} to the user when trust
     * is granted.
     */
    public boolean shouldDisplayTrustGrantedMessage() {
        return mDisplayTrustGrantedMessage;
    }

    public void destroy() {
        mHandler.removeMessages(MSG_RESTART_TIMEOUT);
        if (!mBound) {
            return;
        }
        if (DEBUG) Slog.d(TAG, "TrustAgent unbound : " + mName.flattenToShortString());
        mTrustManagerService.mArchive.logAgentStopped(mUserId, mName);
        mContext.unbindService(mConnection);
        mBound = false;
        mContext.unregisterReceiver(mBroadcastReceiver);
        mContext.unregisterReceiver(mTrustableDowngradeReceiver);
        mTrustAgentService = null;
        mSetTrustAgentFeaturesToken = null;
        mHandler.sendEmptyMessage(MSG_REVOKE_TRUST);
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
