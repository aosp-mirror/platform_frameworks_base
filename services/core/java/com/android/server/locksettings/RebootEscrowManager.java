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

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.widget.RebootEscrowListener;

import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.crypto.spec.SecretKeySpec;

class RebootEscrowManager {
    private static final String TAG = "RebootEscrowManager";

    /**
     * Used to track when the reboot escrow is wanted. Set to false when mRebootEscrowReady is
     * true.
     */
    private final AtomicBoolean mRebootEscrowWanted = new AtomicBoolean(false);

    /** Used to track when reboot escrow is ready. */
    private boolean mRebootEscrowReady;

    /** Notified when mRebootEscrowReady changes. */
    private RebootEscrowListener mRebootEscrowListener;

    /**
     * Stores the reboot escrow data between when it's supplied and when
     * {@link #armRebootEscrowIfNeeded()} is called.
     */
    private RebootEscrowData mPendingRebootEscrowData;

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
        IRebootEscrow rebootEscrow = mInjector.getRebootEscrow();
        if (rebootEscrow == null) {
            return;
        }

        final SecretKeySpec escrowKey;
        try {
            byte[] escrowKeyBytes = rebootEscrow.retrieveKey();
            if (escrowKeyBytes == null) {
                return;
            } else if (escrowKeyBytes.length != 32) {
                Slog.e(TAG, "IRebootEscrow returned key of incorrect size "
                        + escrowKeyBytes.length);
                return;
            }

            // Make sure we didn't get the null key.
            int zero = 0;
            for (int i = 0; i < escrowKeyBytes.length; i++) {
                zero |= escrowKeyBytes[i];
            }
            if (zero == 0) {
                Slog.w(TAG, "IRebootEscrow returned an all-zeroes key");
                return;
            }

            // Overwrite the existing key with the null key
            rebootEscrow.storeKey(new byte[32]);

            escrowKey = RebootEscrowData.fromKeyBytes(escrowKeyBytes);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not retrieve escrow data");
            return;
        }

        List<UserInfo> users = mUserManager.getUsers();
        for (UserInfo user : users) {
            if (mCallbacks.isUserSecure(user.id)) {
                restoreRebootEscrowForUser(user.id, escrowKey);
            }
        }
    }

    private void restoreRebootEscrowForUser(@UserIdInt int userId, SecretKeySpec escrowKey) {
        if (!mStorage.hasRebootEscrow(userId)) {
            return;
        }

        try {
            byte[] blob = mStorage.readRebootEscrow(userId);
            mStorage.removeRebootEscrow(userId);

            RebootEscrowData escrowData = RebootEscrowData.fromEncryptedData(escrowKey, blob);

            mCallbacks.onRebootEscrowRestored(escrowData.getSpVersion(),
                    escrowData.getSyntheticPassword(), userId);
        } catch (IOException e) {
            Slog.w(TAG, "Could not load reboot escrow data for user " + userId, e);
        }
    }

    void callToRebootEscrowIfNeeded(@UserIdInt int userId, byte spVersion,
            byte[] syntheticPassword) {
        if (!mRebootEscrowWanted.compareAndSet(true, false)) {
            return;
        }

        IRebootEscrow rebootEscrow = mInjector.getRebootEscrow();
        if (rebootEscrow == null) {
            setRebootEscrowReady(false);
            return;
        }

        final RebootEscrowData escrowData;
        try {
            escrowData = RebootEscrowData.fromSyntheticPassword(spVersion, syntheticPassword);
        } catch (IOException e) {
            setRebootEscrowReady(false);
            Slog.w(TAG, "Could not escrow reboot data", e);
            return;
        }

        mPendingRebootEscrowData = escrowData;
        mStorage.writeRebootEscrow(userId, escrowData.getBlob());

        setRebootEscrowReady(true);
    }

    private void clearRebootEscrowIfNeeded() {
        mRebootEscrowWanted.set(false);
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
            return false;
        }

        RebootEscrowData escrowData = mPendingRebootEscrowData;
        if (escrowData == null) {
            return false;
        }

        boolean armedRebootEscrow = false;
        try {
            rebootEscrow.storeKey(escrowData.getKey());
            armedRebootEscrow = true;
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed escrow secret to RebootEscrow HAL", e);
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
        mRebootEscrowWanted.set(true);
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
