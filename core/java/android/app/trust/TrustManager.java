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

import android.Manifest;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.hardware.biometrics.BiometricSourceType;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;

import java.util.ArrayList;
import java.util.List;

/**
 * See {@link com.android.server.trust.TrustManagerService}
 * @hide
 */
@SystemService(Context.TRUST_SERVICE)
public class TrustManager {

    private static final int MSG_TRUST_CHANGED = 1;
    private static final int MSG_TRUST_MANAGED_CHANGED = 2;
    private static final int MSG_TRUST_ERROR = 3;

    private static final String TAG = "TrustManager";
    private static final String DATA_FLAGS = "initiatedByUser";
    private static final String DATA_MESSAGE = "message";
    private static final String DATA_GRANTED_MESSAGES = "grantedMessages";

    private final ITrustManager mService;
    private final ArrayMap<TrustListener, ITrustListener> mTrustListeners;

    public TrustManager(IBinder b) {
        mService = ITrustManager.Stub.asInterface(b);
        mTrustListeners = new ArrayMap<TrustListener, ITrustListener>();
    }

    /**
     * Changes the lock status for the given user. This is only applicable to Managed Profiles,
     * other users should be handled by Keyguard.
     *
     * @param userId The id for the user to be locked/unlocked.
     * @param locked The value for that user's locked state.
     */
    @RequiresPermission(Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE)
    public void setDeviceLockedForUser(int userId, boolean locked) {
        try {
            mService.setDeviceLockedForUser(userId, locked);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports that user {@param userId} has tried to unlock the device.
     *
     * @param successful if true, the unlock attempt was successful.
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     */
    @UnsupportedAppUsage
    public void reportUnlockAttempt(boolean successful, int userId) {
        try {
            mService.reportUnlockAttempt(successful, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports that the user {@code userId} is likely interested in unlocking the device.
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     *
     * @param dismissKeyguard whether the user wants to dismiss keyguard
     */
    public void reportUserRequestedUnlock(int userId, boolean dismissKeyguard) {
        try {
            mService.reportUserRequestedUnlock(userId, dismissKeyguard);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports that the user {@code userId} may want to unlock the device soon.
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     */
    public void reportUserMayRequestUnlock(int userId) {
        try {
            mService.reportUserMayRequestUnlock(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports that user {@param userId} has entered a temporary device lockout.
     *
     * This generally occurs when  the user has unsuccessfully tried to unlock the device too many
     * times. The user will then be unable to unlock the device until a set amount of time has
     * elapsed.
     *
     * @param timeout The amount of time that needs to elapse, in milliseconds, until the user may
     *    attempt to unlock the device again.
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     */
    public void reportUnlockLockout(int timeoutMs, int userId) {
        try {
            mService.reportUnlockLockout(timeoutMs, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
            throw e.rethrowFromSystemServer();
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
                public void onTrustChanged(boolean enabled, int userId, int flags,
                        List<String> trustGrantedMessages) {
                    Message m = mHandler.obtainMessage(MSG_TRUST_CHANGED, (enabled ? 1 : 0), userId,
                            trustListener);
                    if (flags != 0) {
                        m.getData().putInt(DATA_FLAGS, flags);
                    }
                    m.getData().putCharSequenceArrayList(
                            DATA_GRANTED_MESSAGES, (ArrayList) trustGrantedMessages);
                    m.sendToTarget();
                }

                @Override
                public void onTrustManagedChanged(boolean managed, int userId) {
                    mHandler.obtainMessage(MSG_TRUST_MANAGED_CHANGED, (managed ? 1 : 0), userId,
                            trustListener).sendToTarget();
                }

                @Override
                public void onTrustError(CharSequence message) {
                    Message m = mHandler.obtainMessage(MSG_TRUST_ERROR, trustListener);
                    m.getData().putCharSequence(DATA_MESSAGE, message);
                    m.sendToTarget();
                }
            };
            mService.registerTrustListener(iTrustListener);
            mTrustListeners.put(trustListener, iTrustListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @return whether {@param userId} has enabled and configured trust agents. Ignores short-term
     * unavailability of trust due to {@link LockPatternUtils.StrongAuthTracker}.
     */
    @RequiresPermission(android.Manifest.permission.TRUST_LISTENER)
    public boolean isTrustUsuallyManaged(int userId) {
        try {
            return mService.isTrustUsuallyManaged(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the trust state for the user due to the user unlocking via a biometric sensor.
     * Should only be called if user authenticated via fingerprint, face, or iris and bouncer
     * can be skipped.
     *
     * @param userId
     */
    @RequiresPermission(Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE)
    public void unlockedByBiometricForUser(int userId, BiometricSourceType source) {
        try {
            mService.unlockedByBiometricForUser(userId, source);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clears authentication by the specified biometric type for all users.
     */
    @RequiresPermission(Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE)
    public void clearAllBiometricRecognized(BiometricSourceType source, int unlockedUser) {
        try {
            mService.clearAllBiometricRecognized(source, unlockedUser);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case MSG_TRUST_CHANGED:
                    int flags = msg.peekData() != null ? msg.peekData().getInt(DATA_FLAGS) : 0;
                    ((TrustListener) msg.obj).onTrustChanged(msg.arg1 != 0, msg.arg2, flags,
                            msg.getData().getStringArrayList(DATA_GRANTED_MESSAGES));
                    break;
                case MSG_TRUST_MANAGED_CHANGED:
                    ((TrustListener)msg.obj).onTrustManagedChanged(msg.arg1 != 0, msg.arg2);
                    break;
                case MSG_TRUST_ERROR:
                    final CharSequence message = msg.peekData().getCharSequence(DATA_MESSAGE);
                    ((TrustListener) msg.obj).onTrustError(message);
            }
        }
    };

    public interface TrustListener {

        /**
         * Reports that the trust state has changed.
         * @param enabled If true, the system believes the environment to be trusted.
         * @param userId The user, for which the trust changed.
         * @param flags Flags specified by the trust agent when granting trust. See
         *     {@link android.service.trust.TrustAgentService#grantTrust(CharSequence, long, int)
         *                 TrustAgentService.grantTrust(CharSequence, long, int)}.
         * @param trustGrantedMessages Messages to display to the user when trust has been granted
         *        by one or more trust agents.
         */
        void onTrustChanged(boolean enabled, int userId, int flags,
                List<String> trustGrantedMessages);

        /**
         * Reports that whether trust is managed has changed
         * @param enabled If true, at least one trust agent is managing trust.
         * @param userId The user, for which the state changed.
         */
        void onTrustManagedChanged(boolean enabled, int userId);

        /**
         * Reports that an error happened on a TrustAgentService.
         * @param message A message that should be displayed on the UI.
         */
        void onTrustError(CharSequence message);
    }
}
