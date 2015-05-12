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
    private static final int MSG_TRUST_MANAGED_CHANGED = 2;

    private static final String TAG = "TrustManager";
    private static final String DATA_FLAGS = "initiatedByUser";

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
     * Reports that the visibility of the keyguard has changed.
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     */
    public void reportKeyguardShowingChanged() {
        try {
            mService.reportKeyguardShowingChanged();
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
                public void onTrustChanged(boolean enabled, int userId, int flags) {
                    Message m = mHandler.obtainMessage(MSG_TRUST_CHANGED, (enabled ? 1 : 0), userId,
                            trustListener);
                    if (flags != 0) {
                        m.getData().putInt(DATA_FLAGS, flags);
                    }
                    m.sendToTarget();
                }

                @Override
                public void onTrustManagedChanged(boolean managed, int userId) {
                    mHandler.obtainMessage(MSG_TRUST_MANAGED_CHANGED, (managed ? 1 : 0), userId,
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

    /**
     * Checks whether the specified user has been authenticated since the last boot.
     *
     * @param userId the user id of the user to check for
     * @return true if the user has authenticated since boot, false otherwise
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     */
    public boolean hasUserAuthenticatedSinceBoot(int userId) {
        try {
            return mService.hasUserAuthenticatedSinceBoot(userId);
        } catch (RemoteException e) {
            onError(e);
            return false;
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
                    int flags = msg.peekData() != null ? msg.peekData().getInt(DATA_FLAGS) : 0;
                    ((TrustListener)msg.obj).onTrustChanged(msg.arg1 != 0, msg.arg2, flags);
                    break;
                case MSG_TRUST_MANAGED_CHANGED:
                    ((TrustListener)msg.obj).onTrustManagedChanged(msg.arg1 != 0, msg.arg2);
            }
        }
    };

    public interface TrustListener {

        /**
         * Reports that the trust state has changed.
         * @param enabled if true, the system believes the environment to be trusted.
         * @param userId the user, for which the trust changed.
         * @param flags flags specified by the trust agent when granting trust. See
         *     {@link android.service.trust.TrustAgentService#grantTrust(CharSequence, long, int)
         *                 TrustAgentService.grantTrust(CharSequence, long, int)}.
         */
        void onTrustChanged(boolean enabled, int userId, int flags);

        /**
         * Reports that whether trust is managed has changed
         * @param enabled if true, at least one trust agent is managing trust.
         * @param userId the user, for which the state changed.
         */
        void onTrustManagedChanged(boolean enabled, int userId);
    }
}
