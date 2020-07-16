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

import static android.os.UserHandle.USER_SYSTEM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.hardware.rebootescrow.IRebootEscrow;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.widget.RebootEscrowListener;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;

class RebootEscrowManager {
    private static final String TAG = "RebootEscrowManager";

    /**
     * Used in the database storage to indicate the boot count at which the reboot escrow was
     * previously armed.
     */
    @VisibleForTesting
    public static final String REBOOT_ESCROW_ARMED_KEY = "reboot_escrow_armed_count";

    /**
     * Number of boots until we consider the escrow data to be stale for the purposes of metrics.
     * <p>
     * If the delta between the current boot number and the boot number stored when the mechanism
     * was armed is under this number and the escrow mechanism fails, we report it as a failure of
     * the mechanism.
     * <p>
     * If the delta over this number and escrow fails, we will not report the metric as failed
     * since there most likely was some other issue if the device rebooted several times before
     * getting to the escrow restore code.
     */
    private static final int BOOT_COUNT_TOLERANCE = 5;

    /**
     * Logs events for later debugging in bugreports.
     */
    private final RebootEscrowEventLog mEventLog;

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

        @Nullable
        public IRebootEscrow getRebootEscrow() {
            try {
                return IRebootEscrow.Stub.asInterface(ServiceManager.getService(
                        "android.hardware.rebootescrow.IRebootEscrow/default"));
            } catch (NoSuchElementException e) {
                Slog.i(TAG, "Device doesn't implement RebootEscrow HAL");
            }
            return null;
        }

        public int getBootCount() {
            return Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.BOOT_COUNT,
                    0);
        }

        public void reportMetric(boolean success) {
            FrameworkStatsLog.write(FrameworkStatsLog.REBOOT_ESCROW_RECOVERY_REPORTED, success);
        }

        public RebootEscrowEventLog getEventLog() {
            return new RebootEscrowEventLog();
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
        mEventLog = injector.getEventLog();
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
            onEscrowRestoreComplete(false);
            return;
        }

        mEventLog.addEntry(RebootEscrowEvent.FOUND_ESCROW_DATA);

        boolean allUsersUnlocked = true;
        for (UserInfo user : rebootEscrowUsers) {
            allUsersUnlocked &= restoreRebootEscrowForUser(user.id, escrowKey);
        }
        onEscrowRestoreComplete(allUsersUnlocked);
    }

    private void onEscrowRestoreComplete(boolean success) {
        int previousBootCount = mStorage.getInt(REBOOT_ESCROW_ARMED_KEY, -1, USER_SYSTEM);
        mStorage.removeKey(REBOOT_ESCROW_ARMED_KEY, USER_SYSTEM);

        int bootCountDelta = mInjector.getBootCount() - previousBootCount;
        if (success || (previousBootCount != -1 && bootCountDelta <= BOOT_COUNT_TOLERANCE)) {
            mInjector.reportMetric(success);
        }
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

            mEventLog.addEntry(RebootEscrowEvent.RETRIEVED_STORED_KEK);
            return RebootEscrowKey.fromKeyBytes(escrowKeyBytes);
        } catch (RemoteException e) {
            Slog.w(TAG, "Could not retrieve escrow data");
            return null;
        } catch (ServiceSpecificException e) {
            Slog.w(TAG, "Got service-specific exception: " + e.errorCode);
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
            mEventLog.addEntry(RebootEscrowEvent.RETRIEVED_LSKF_FOR_USER, userId);
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
        mEventLog.addEntry(RebootEscrowEvent.STORED_LSKF_FOR_USER, userId);

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

        mStorage.removeKey(REBOOT_ESCROW_ARMED_KEY, USER_SYSTEM);

        try {
            rebootEscrow.storeKey(new byte[32]);
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.w(TAG, "Could not call RebootEscrow HAL to shred key");
        }

        List<UserInfo> users = mUserManager.getUsers();
        for (UserInfo user : users) {
            mStorage.removeRebootEscrow(user.id);
        }

        mEventLog.addEntry(RebootEscrowEvent.CLEARED_LSKF_REQUEST);
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
        } catch (RemoteException | ServiceSpecificException e) {
            Slog.e(TAG, "Failed escrow secret to RebootEscrow HAL", e);
        }

        if (armedRebootEscrow) {
            mStorage.setInt(REBOOT_ESCROW_ARMED_KEY, mInjector.getBootCount(), USER_SYSTEM);
            mEventLog.addEntry(RebootEscrowEvent.SET_ARMED_STATUS);
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
        mEventLog.addEntry(RebootEscrowEvent.REQUESTED_LSKF);
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

    @VisibleForTesting
    public static class RebootEscrowEvent {
        static final int FOUND_ESCROW_DATA = 1;
        static final int SET_ARMED_STATUS = 2;
        static final int CLEARED_LSKF_REQUEST = 3;
        static final int RETRIEVED_STORED_KEK = 4;
        static final int REQUESTED_LSKF = 5;
        static final int STORED_LSKF_FOR_USER = 6;
        static final int RETRIEVED_LSKF_FOR_USER = 7;

        final int mEventId;
        final Integer mUserId;
        final long mWallTime;
        final long mTimestamp;

        RebootEscrowEvent(int eventId) {
            this(eventId, null);
        }

        RebootEscrowEvent(int eventId, Integer userId) {
            mEventId = eventId;
            mUserId = userId;
            mTimestamp = SystemClock.uptimeMillis();
            mWallTime = System.currentTimeMillis();
        }

        String getEventDescription() {
            switch (mEventId) {
                case FOUND_ESCROW_DATA:
                    return "Found escrow data";
                case SET_ARMED_STATUS:
                    return "Set armed status";
                case CLEARED_LSKF_REQUEST:
                    return "Cleared request for LSKF";
                case RETRIEVED_STORED_KEK:
                    return "Retrieved stored KEK";
                case REQUESTED_LSKF:
                    return "Requested LSKF";
                case STORED_LSKF_FOR_USER:
                    return "Stored LSKF for user";
                case RETRIEVED_LSKF_FOR_USER:
                    return "Retrieved LSKF for user";
                default:
                    return "Unknown event ID " + mEventId;
            }
        }
    }

    @VisibleForTesting
    public static class RebootEscrowEventLog {
        private RebootEscrowEvent[] mEntries = new RebootEscrowEvent[16];
        private int mNextIndex = 0;

        void addEntry(int eventId) {
            addEntryInternal(new RebootEscrowEvent(eventId));
        }

        void addEntry(int eventId, int userId) {
            addEntryInternal(new RebootEscrowEvent(eventId, userId));
        }

        private void addEntryInternal(RebootEscrowEvent event) {
            final int index = mNextIndex;
            mEntries[index] = event;
            mNextIndex = (mNextIndex + 1) % mEntries.length;
        }

        void dump(@NonNull IndentingPrintWriter pw) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

            for (int i = 0; i < mEntries.length; ++i) {
                RebootEscrowEvent event = mEntries[(i + mNextIndex) % mEntries.length];
                if (event == null) {
                    continue;
                }

                pw.print("Event #");
                pw.println(i);

                pw.println(" time=" + sdf.format(new Date(event.mWallTime))
                        + " (timestamp=" + event.mTimestamp + ")");

                pw.print(" event=");
                pw.println(event.getEventDescription());

                if (event.mUserId != null) {
                    pw.print(" user=");
                    pw.println(event.mUserId);
                }
            }
        }
    }

    void dump(@NonNull IndentingPrintWriter pw) {
        pw.print("mRebootEscrowWanted=");
        pw.println(mRebootEscrowWanted);

        pw.print("mRebootEscrowReady=");
        pw.println(mRebootEscrowReady);

        pw.print("mRebootEscrowListener=");
        pw.println(mRebootEscrowListener);

        boolean keySet;
        synchronized (mKeyGenerationLock) {
            keySet = mPendingRebootEscrowKey != null;
        }

        pw.print("mPendingRebootEscrowKey is ");
        pw.println(keySet ? "set" : "not set");

        pw.println();
        pw.println("Event log:");
        pw.increaseIndent();
        mEventLog.dump(pw);
        pw.println();
        pw.decreaseIndent();
    }
}
