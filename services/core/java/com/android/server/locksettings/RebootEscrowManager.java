/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.locksettings;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.rebootescrow.IRebootEscrow;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserManager;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.widget.RebootEscrowListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

class RebootEscrowManager {
    private static final String TAG = "RebootEscrowManager";

    /**
     * Used to track when the reboot escrow is wanted. Should stay true once escrow is requested
     * unless clearRebootEscrow is called. This will allow all the active users to be unlocked
     * after reboot.
     */
    private boolean mRebootEscrowWanted;

    /** Used to track when reboot escrow is ready. */
    private boolean mRebootEscrowReady;

    /** Notified when mRebootEscrowReady changes. */
    private RebootEscrowListener mRebootEscrowListener;

    /**
     * Hold this lock when checking or generating the reboot escrow key.
     */
    private final Object mKeyGenerationLock = new Object();

    /**
     * Stores the reboot escrow data between when it's supplied and when
     * {@link #armRebootEscrowIfNeeded()} is called.
     */
    @GuardedBy("mKeyGenerationLock")
    private RebootEscrowKey mPendingRebootEscrowKey;

    private final UserManager mUserManager;

    private final Injector mInjector;

    private final LockSettingsStorage mStorage;

    private final Callbacks mCallbacks;

    interface Callbacks {
        boolean isUserSecure(int userId);
        void onRebootEscrowRestored(byte spVersion, byte[] syntheticPassword, int userId);
    }

    static class Injector {
        protected Context mContext;

        Injector(Context context) {
            mContext = context;
        }

        public Context getContext() {
            return mContext;
        }

        public UserManager getUserManager() {
            return (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        }

        public @Nullable IRebootEscrow getRebootEscrow() {
            try {
                return IRebootEscrow.Stub.asInterface(ServiceManager.getService(
                        "android.hardware.rebootescrow.IRebootEscrow/default"));
            } catch (NoSuchElementException e) {
                Slog.i(TAG, "Device doesn't implement RebootEscrow HAL");
            }
            return null;
        }
    }

    RebootEscrowManager(Context context, Callbacks callbacks, LockSettingsStorage storage) {
        this(new Injector(context), callbacks, storage);
    }

    @VisibleForTesting
    RebootEscrowManager(Injector injector, Callbacks callbacks,
            LockSettingsStorage storage) {
        mInjector = injector;
        mCallbacks = callbacks;
        mStorage = storage;
        mUserManager = injector.getUserManager();
    }

    void loadRebootEscrowDataIfAvailable() {
        List<UserInfo> users = mUserManager.getUsers();
        List<UserInfo> rebootEscrowUsers = new ArrayList<>();
        for (UserInfo user : users) {
            if (mCallbacks.isUserSecure(user.id) && mStorage.hasRebootEscrow(user.id)) {
                rebootEscrowUsers.add(user);
            }
        }

        if (rebootEscrowUsers.isEmpty()) {
            return;
        }

        RebootEscrowKey escrowKey = getAndClearRebootEscrowKey();
        if (escrowKey == null) {
            Slog.w(TAG, "Had reboot escrow data for users, but no key; removing escrow storage.");
            for (UserInfo user : users) {
                mStorage.removeRebootEscrow(user.id);
            }
            FrameworkStatsLog.write(FrameworkStatsLog.REBOOT_ESCROW_RECOVERY_REPORTED, false);
            return;
        }

        boolean allUsersUnlocked = true;
        for (UserInfo user : rebootEscrowUsers) {
            allUsersUnlocked &= restoreRebootEscrowForUser(user.id, escrowKey);
        }
        FrameworkStatsLog.write(FrameworkStatsLog.REBOOT_ESCROW_RECOVERY_REPORTED,
                allUsersUnlocked);
    }

    private RebootEscrowKey getAndClearRebootEscrowKey() {
        IRebootEscrow rebootEscrow = mInjector.getRebootEscrow();
        if (rebootEscrow == null) {
            Slog.w(TAG, "Had reboot escrow data for users, but RebootEscrow HAL is unavailable");
            return null;
        }

        try {
            byte[] escrowKeyBytes = rebootEscrow.retrieveKey();
            if (escrowKeyBytes == null) {
                Slog.w(TAG, "Had reboot escrow data for users, but could not retrieve key");
                return null;
            } else if (escrowKeyBytes.length != 32) {
                Slog.e(TAG, "IRebootEscrow returned key of incorrect size "
                        + escrowKeyBytes.length);
                return null;
            }

            // Make sure we didn't get the null key.
            int zero = 0;
            for (int i = 0; i < escrowKeyBytes.length; i++) {
                zero |= escrowKeyBytes[i];
            }
            if (zero == 0) {
                Slog.w(TAG, "IRebootEscrow returned an all-zeroes key");
                return null;
            }

            // Overwrite the existing key with the null key
            rebootEscrow.storeKey(new byte[32]);

            return RebootEscrowKey.fromKeyBytes(escrowKeyBytes);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not retrieve escrow data");
            return null;
        }
    }

    private boolean restoreRebootEscrowForUser(@UserIdInt int userId, RebootEscrowKey key) {
        if (!mStorage.hasRebootEscrow(userId)) {
            return false;
        }

        try {
            byte[] blob = mStorage.readRebootEscrow(userId);
            mStorage.removeRebootEscrow(userId);

            RebootEscrowData escrowData = RebootEscrowData.fromEncryptedData(key, blob);

            mCallbacks.onRebootEscrowRestored(escrowData.getSpVersion(),
                    escrowData.getSyntheticPassword(), userId);
            Slog.i(TAG, "Restored reboot escrow data for user " + userId);
            return true;
        } catch (IOException e) {
            Slog.w(TAG, "Could not load reboot escrow data for user " + userId, e);
            return false;
        }
    }

    void callToRebootEscrowIfNeeded(@UserIdInt int userId, byte spVersion,
            byte[] syntheticPassword) {
        if (!mRebootEscrowWanted) {
            return;
        }

        IRebootEscrow rebootEscrow = mInjector.getRebootEscrow();
        if (rebootEscrow == null) {
            Slog.w(TAG, "Reboot escrow requested, but RebootEscrow HAL is unavailable");
            return;
        }

        RebootEscrowKey escrowKey = generateEscrowKeyIfNeeded();
        if (escrowKey == null) {
            Slog.e(TAG, "Could not generate escrow key");
            return;
        }

        final RebootEscrowData escrowData;
        try {
            escrowData = RebootEscrowData.fromSyntheticPassword(escrowKey, spVersion,
                    syntheticPassword);
        } catch (IOException e) {
            setRebootEscrowReady(false);
            Slog.w(TAG, "Could not escrow reboot data", e);
            return;
        }

        mStorage.writeRebootEscrow(userId, escrowData.getBlob());

        setRebootEscrowReady(true);
    }

    private RebootEscrowKey generateEscrowKeyIfNeeded() {
        synchronized (mKeyGenerationLock) {
            if (mPendingRebootEscrowKey != null) {
                return mPendingRebootEscrowKey;
            }

            RebootEscrowKey key;
            try {
                key = RebootEscrowKey.generate();
            } catch (IOException e) {
                Slog.w(TAG, "Could not generate reboot escrow key");
                return null;
            }

            mPendingRebootEscrowKey = key;
            return key;
        }
    }

    private void clearRebootEscrowIfNeeded() {
        mRebootEscrowWanted = false;
        setRebootEscrowReady(false);

        IRebootEscrow rebootEscrow = mInjector.getRebootEscrow();
        if (rebootEscrow == null) {
            return;
        }

        try {
            rebootEscrow.storeKey(new byte[32]);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not call RebootEscrow HAL to shred key");
        }

        List<UserInfo> users = mUserManager.getUsers();
        for (UserInfo user : users) {
            mStorage.removeRebootEscrow(user.id);
        }
    }

    boolean armRebootEscrowIfNeeded() {
        if (!mRebootEscrowReady) {
            return false;
        }

        IRebootEscrow rebootEscrow = mInjector.getRebootEscrow();
        if (rebootEscrow == null) {
            Slog.w(TAG, "Escrow marked as ready, but RebootEscrow HAL is unavailable");
            return false;
        }

        RebootEscrowKey escrowKey;
        synchronized (mKeyGenerationLock) {
            escrowKey = mPendingRebootEscrowKey;
        }

        if (escrowKey == null) {
            Slog.e(TAG, "Escrow key is null, but escrow was marked as ready");
            return false;
        }

        boolean armedRebootEscrow = false;
        try {
            rebootEscrow.storeKey(escrowKey.getKeyBytes());
            armedRebootEscrow = true;
            Slog.i(TAG, "Reboot escrow key stored with RebootEscrow HAL");
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed escrow secret to RebootEscrow HAL", e);
        }
        return armedRebootEscrow;
    }

    private void setRebootEscrowReady(boolean ready) {
        if (mRebootEscrowReady != ready) {
            mRebootEscrowListener.onPreparedForReboot(ready);
        }
        mRebootEscrowReady = ready;
    }

    boolean prepareRebootEscrow() {
        if (mInjector.getRebootEscrow() == null) {
            return false;
        }

        clearRebootEscrowIfNeeded();
        mRebootEscrowWanted = true;
        return true;
    }

    boolean clearRebootEscrow() {
        if (mInjector.getRebootEscrow() == null) {
            return false;
        }

        clearRebootEscrowIfNeeded();
        return true;
    }

    void setRebootEscrowListener(RebootEscrowListener listener) {
        mRebootEscrowListener = listener;
    }
}
