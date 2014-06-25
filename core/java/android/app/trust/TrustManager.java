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

package android.app.trust;

import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

/**
 * See {@link com.android.server.trust.TrustManagerService}
 * @hide
 */
public class TrustManager {

    private static final int MSG_TRUST_CHANGED = 1;

    private static final String TAG = "TrustManager";

    private final ITrustManager mService;
    private final ArrayMap<TrustListener, ITrustListener> mTrustListeners;

    public TrustManager(IBinder b) {
        mService = ITrustManager.Stub.asInterface(b);
        mTrustListeners = new ArrayMap<TrustListener, ITrustListener>();
    }

    /**
     * Reports that user {@param userId} has tried to unlock the device.
     *
     * @param successful if true, the unlock attempt was successful.
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     */
    public void reportUnlockAttempt(boolean successful, int userId) {
        try {
            mService.reportUnlockAttempt(successful, userId);
        } catch (RemoteException e) {
            onError(e);
        }
    }

    /**
     * Reports that the list of enabled trust agents changed for user {@param userId}.
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     */
    public void reportEnabledTrustAgentsChanged(int userId) {
        try {
            mService.reportEnabledTrustAgentsChanged(userId);
        } catch (RemoteException e) {
            onError(e);
        }
    }

    /**
     * Reports that trust is disabled until credentials have been entered for user {@param userId}.
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     *
     * @param userId either an explicit user id or {@link android.os.UserHandle#USER_ALL}
     */
    public void reportRequireCredentialEntry(int userId) {
        try {
            mService.reportRequireCredentialEntry(userId);
        } catch (RemoteException e) {
            onError(e);
        }
    }

    /**
     * Registers a listener for trust events.
     *
     * Requires the {@link android.Manifest.permission#TRUST_LISTENER} permission.
     */
    public void registerTrustListener(final TrustListener trustListener) {
        try {
            ITrustListener.Stub iTrustListener = new ITrustListener.Stub() {
                @Override
                public void onTrustChanged(boolean enabled, int userId) throws RemoteException {
                    mHandler.obtainMessage(MSG_TRUST_CHANGED, (enabled ? 1 : 0), userId,
                            trustListener).sendToTarget();
                }
            };
            mService.registerTrustListener(iTrustListener);
            mTrustListeners.put(trustListener, iTrustListener);
        } catch (RemoteException e) {
            onError(e);
        }
    }

    /**
     * Unregisters a listener for trust events.
     *
     * Requires the {@link android.Manifest.permission#TRUST_LISTENER} permission.
     */
    public void unregisterTrustListener(final TrustListener trustListener) {
        ITrustListener iTrustListener = mTrustListeners.remove(trustListener);
        if (iTrustListener != null) {
            try {
                mService.unregisterTrustListener(iTrustListener);
            } catch (RemoteException e) {
                onError(e);
            }
        }
    }

    private void onError(Exception e) {
        Log.e(TAG, "Error while calling TrustManagerService", e);
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_TRUST_CHANGED:
                    ((TrustListener)msg.obj).onTrustChanged(msg.arg1 != 0, msg.arg2);
                    break;
            }
        }
    };

    public interface TrustListener {

        /**
         * Reports that the trust state has changed.
         * @param enabled if true, the system believes the environment to be trusted.
         * @param userId the user, for which the trust changed.
         */
        void onTrustChanged(boolean enabled, int userId);
    }
}
