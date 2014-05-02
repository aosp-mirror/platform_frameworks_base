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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;
import android.service.trust.ITrustAgentService;
import android.service.trust.ITrustAgentServiceCallback;

/**
 * A wrapper around a TrustAgentService interface. Coordinates communication between
 * TrustManager and the actual TrustAgent.
 */
public class TrustAgentWrapper {
    private static final boolean DEBUG = false;
    private static final String TAG = "TrustAgentWrapper";

    private static final int MSG_GRANT_TRUST = 1;
    private static final int MSG_REVOKE_TRUST = 2;
    private static final int MSG_TRUST_TIMEOUT = 3;

    /**
     * Long extra for {@link #MSG_GRANT_TRUST}
     */
    private static final String DATA_DURATION = "duration";

    private final TrustManagerService mTrustManagerService;
    private final int mUserId;
    private final Context mContext;
    private final ComponentName mName;

    private ITrustAgentService mTrustAgentService;

    // Trust state
    private boolean mTrusted;
    private CharSequence mMessage;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_GRANT_TRUST:
                    mTrusted = true;
                    mMessage = (CharSequence) msg.obj;
                    boolean initiatedByUser = msg.arg1 != 0;
                    // TODO: Handle initiatedByUser.
                    long durationMs = msg.getData().getLong(DATA_DURATION);
                    if (durationMs > 0) {
                        mHandler.removeMessages(MSG_TRUST_TIMEOUT);
                        mHandler.sendEmptyMessageDelayed(MSG_TRUST_TIMEOUT, durationMs);
                    }
                    mTrustManagerService.mArchive.logGrantTrust(mUserId, mName,
                            (mMessage != null ? mMessage.toString() : null),
                            durationMs, initiatedByUser);
                    mTrustManagerService.updateTrust(mUserId);
                    break;
                case MSG_TRUST_TIMEOUT:
                    if (DEBUG) Slog.v(TAG, "Trust timed out : " + mName.flattenToShortString());
                    mTrustManagerService.mArchive.logTrustTimeout(mUserId, mName);
                    // Fall through.
                case MSG_REVOKE_TRUST:
                    mTrusted = false;
                    mMessage = null;
                    mHandler.removeMessages(MSG_TRUST_TIMEOUT);
                    if (msg.what == MSG_REVOKE_TRUST) {
                        mTrustManagerService.mArchive.logRevokeTrust(mUserId, mName);
                    }
                    mTrustManagerService.updateTrust(mUserId);
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
    };

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Log.v(TAG, "TrustAgent started : " + name.flattenToString());
            mTrustAgentService = ITrustAgentService.Stub.asInterface(service);
            setCallback(mCallback);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Log.v(TAG, "TrustAgent disconnected : " + name.flattenToShortString());
            mTrustAgentService = null;
            mTrustManagerService.mArchive.logAgentDied(mUserId, name);
            mHandler.sendEmptyMessage(MSG_REVOKE_TRUST);
        }
    };


    public TrustAgentWrapper(Context context, TrustManagerService trustManagerService,
            Intent intent, UserHandle user) {
        mContext = context;
        mTrustManagerService = trustManagerService;
        mUserId = user.getIdentifier();
        mName = intent.getComponent();
        if (!context.bindServiceAsUser(intent, mConnection, Context.BIND_AUTO_CREATE, user)) {
            if (DEBUG) Log.v(TAG, "can't bind to TrustAgent " + mName.flattenToShortString());
            // TODO: retry somehow?
        }
    }

    private void onError(Exception e) {
        Slog.w(TAG , "Remote Exception", e);
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

    public boolean isTrusted() {
        return mTrusted;
    }

    public CharSequence getMessage() {
        return mMessage;
    }

    public void unbind() {
        if (DEBUG) Log.v(TAG, "TrustAgent unbound : " + mName.flattenToShortString());
        mContext.unbindService(mConnection);
    }

    public boolean isConnected() {
        return mTrustAgentService != null;
    }
}
