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
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.DeviceConfig;
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
import java.util.Objects;

import javax.crypto.SecretKey;

/**
 * This class aims to persists the synthetic password(SP) across reboot in a secure way. In
 * particular, it manages the encryption of the sp before reboot, and decryption of the sp after
 * reboot. Here are the meaning of some terms.
 *   SP: synthetic password
 *   K_s: The RebootEscrowKey, i.e. AES-GCM key stored in memory
 *   K_k: AES-GCM key in android keystore
 *   RebootEscrowData: The synthetic password and its encrypted blob. We encrypt SP with K_s first,
 *      then with K_k, i.e. E(K_k, E(K_s, SP))
 */
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
     * The default retry specs for loading reboot escrow data. We will attempt to retry loading
     * escrow data on temporarily errors, e.g. unavailable network.
     */
    private static final int DEFAULT_LOAD_ESCROW_DATA_RETRY_COUNT = 3;
    private static final int DEFAULT_LOAD_ESCROW_DATA_RETRY_INTERVAL_SECONDS = 30;

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

    private final RebootEscrowKeyStoreManager mKeyStoreManager;

    interface Callbacks {
        boolean isUserSecure(int userId);

        void onRebootEscrowRestored(byte spVersion, byte[] syntheticPassword, int userId);
    }

    static class Injector {
        protected Context mContext;
        private final RebootEscrowKeyStoreManager mKeyStoreManager;
        private final LockSettingsStorage mStorage;
        private RebootEscrowProviderInterface mRebootEscrowProvider;

        Injector(Context context, LockSettingsStorage storage) {
            mContext = context;
            mStorage = storage;
            mKeyStoreManager = new RebootEscrowKeyStoreManager();
        }

        private RebootEscrowProviderInterface createRebootEscrowProvider() {
            RebootEscrowProviderInterface rebootEscrowProvider;
            if (DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_OTA,
                    "server_based_ror_enabled", false)) {
                Slog.i(TAG, "Using server based resume on reboot");
                rebootEscrowProvider = new RebootEscrowProviderServerBasedImpl(mContext, mStorage);
            } else {
                rebootEscrowProvider = new RebootEscrowProviderHalImpl();
            }

            if (rebootEscrowProvider.hasRebootEscrowSupport()) {
                return rebootEscrowProvider;
            }
            return null;
        }

        void post(Handler handler, Runnable runnable) {
            handler.post(runnable);
        }

        void postDelayed(Handler handler, Runnable runnable, long delayMillis) {
            handler.postDelayed(runnable, delayMillis);
        }

        public Context getContext() {
            return mContext;
        }

        public UserManager getUserManager() {
            return (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        }

        public RebootEscrowKeyStoreManager getKeyStoreManager() {
            return mKeyStoreManager;
        }

        public RebootEscrowProviderInterface getRebootEscrowProvider() {
            // Initialize for the provider lazily. Because the device_config and service
            // implementation apps may change when system server is running.
            if (mRebootEscrowProvider == null) {
                mRebootEscrowProvider = createRebootEscrowProvider();
            }

            return mRebootEscrowProvider;
        }

        public int getBootCount() {
            return Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.BOOT_COUNT,
                    0);
        }

        public int getLoadEscrowDataRetryLimit() {
            return DeviceConfig.getInt(DeviceConfig.NAMESPACE_OTA,
                    "load_escrow_data_retry_count", DEFAULT_LOAD_ESCROW_DATA_RETRY_COUNT);
        }

        public int getLoadEscrowDataRetryIntervalSeconds() {
            return DeviceConfig.getInt(DeviceConfig.NAMESPACE_OTA,
                    "load_escrow_data_retry_interval_seconds",
                    DEFAULT_LOAD_ESCROW_DATA_RETRY_INTERVAL_SECONDS);
        }

        public void reportMetric(boolean success) {
            // TODO(b/179105110) design error code; and report the true value for other fields.
            FrameworkStatsLog.write(FrameworkStatsLog.REBOOT_ESCROW_RECOVERY_REPORTED, 0, 1, 1,
                    -1, 0);
        }

        public RebootEscrowEventLog getEventLog() {
            return new RebootEscrowEventLog();
        }
    }

    RebootEscrowManager(Context context, Callbacks callbacks, LockSettingsStorage storage) {
        this(new Injector(context, storage), callbacks, storage);
    }

    @VisibleForTesting
    RebootEscrowManager(Injector injector, Callbacks callbacks,
            LockSettingsStorage storage) {
        mInjector = injector;
        mCallbacks = callbacks;
        mStorage = storage;
        mUserManager = injector.getUserManager();
        mEventLog = injector.getEventLog();
        mKeyStoreManager = injector.getKeyStoreManager();
    }

    private void onGetRebootEscrowKeyFailed(List<UserInfo> users) {
        Slog.w(TAG, "Had reboot escrow data for users, but no key; removing escrow storage.");
        for (UserInfo user : users) {
            mStorage.removeRebootEscrow(user.id);
        }

        // Clear the old key in keystore.
        mKeyStoreManager.clearKeyStoreEncryptionKey();
        onEscrowRestoreComplete(false);
    }

    void loadRebootEscrowDataIfAvailable(Handler retryHandler) {
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

        mInjector.post(retryHandler, () -> loadRebootEscrowDataWithRetry(
                retryHandler, 0, users, rebootEscrowUsers));
    }

    void scheduleLoadRebootEscrowDataOrFail(Handler retryHandler, int attemptNumber,
            List<UserInfo> users, List<UserInfo> rebootEscrowUsers) {
        Objects.requireNonNull(retryHandler);

        final int retryLimit = mInjector.getLoadEscrowDataRetryLimit();
        final int retryIntervalInSeconds = mInjector.getLoadEscrowDataRetryIntervalSeconds();

        if (attemptNumber < retryLimit) {
            Slog.i(TAG, "Scheduling loadRebootEscrowData retry number: " + attemptNumber);
            mInjector.postDelayed(retryHandler, () -> loadRebootEscrowDataWithRetry(
                    retryHandler, attemptNumber, users, rebootEscrowUsers),
                    retryIntervalInSeconds * 1000);
            return;
        }

        Slog.w(TAG, "Failed to load reboot escrow data after " + attemptNumber + " attempts");
        onGetRebootEscrowKeyFailed(users);
    }

    void loadRebootEscrowDataWithRetry(Handler retryHandler, int attemptNumber,
            List<UserInfo> users, List<UserInfo> rebootEscrowUsers) {
        // Fetch the key from keystore to decrypt the escrow data & escrow key; this key is
        // generated before reboot. Note that we will clear the escrow key even if the keystore key
        // is null.
        SecretKey kk = mKeyStoreManager.getKeyStoreEncryptionKey();
        if (kk == null) {
            Slog.i(TAG, "Failed to load the key for resume on reboot from key store.");
        }

        RebootEscrowKey escrowKey;
        try {
            escrowKey = getAndClearRebootEscrowKey(kk);
        } catch (IOException e) {
            scheduleLoadRebootEscrowDataOrFail(retryHandler, attemptNumber + 1, users,
                    rebootEscrowUsers);
            return;
        }

        if (escrowKey == null) {
            onGetRebootEscrowKeyFailed(users);
            return;
        }

        mEventLog.addEntry(RebootEscrowEvent.FOUND_ESCROW_DATA);

        boolean allUsersUnlocked = true;
        for (UserInfo user : rebootEscrowUsers) {
            allUsersUnlocked &= restoreRebootEscrowForUser(user.id, escrowKey, kk);
        }

        // Clear the old key in keystore. A new key will be generated by new RoR requests.
        mKeyStoreManager.clearKeyStoreEncryptionKey();

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

    private RebootEscrowKey getAndClearRebootEscrowKey(SecretKey kk) throws IOException {
        RebootEscrowProviderInterface rebootEscrowProvider = mInjector.getRebootEscrowProvider();
        if (rebootEscrowProvider == null) {
            Slog.w(TAG,
                    "Had reboot escrow data for users, but RebootEscrowProvider is unavailable");
            return null;
        }

        // The K_s blob maybe encrypted by K_k as well.
        RebootEscrowKey key = rebootEscrowProvider.getAndClearRebootEscrowKey(kk);
        if (key != null) {
            mEventLog.addEntry(RebootEscrowEvent.RETRIEVED_STORED_KEK);
        }
        return key;
    }

    private boolean restoreRebootEscrowForUser(@UserIdInt int userId, RebootEscrowKey ks,
            SecretKey kk) {
        if (!mStorage.hasRebootEscrow(userId)) {
            return false;
        }

        try {
            byte[] blob = mStorage.readRebootEscrow(userId);
            mStorage.removeRebootEscrow(userId);

            RebootEscrowData escrowData = RebootEscrowData.fromEncryptedData(ks, blob, kk);

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

        if (mInjector.getRebootEscrowProvider() == null) {
            Slog.w(TAG,
                    "Had reboot escrow data for users, but RebootEscrowProvider is unavailable");
            return;
        }

        RebootEscrowKey escrowKey = generateEscrowKeyIfNeeded();
        if (escrowKey == null) {
            Slog.e(TAG, "Could not generate escrow key");
            return;
        }

        SecretKey kk = mKeyStoreManager.generateKeyStoreEncryptionKeyIfNeeded();
        if (kk == null) {
            Slog.e(TAG, "Failed to generate encryption key from keystore.");
            return;
        }

        final RebootEscrowData escrowData;
        try {
            escrowData = RebootEscrowData.fromSyntheticPassword(escrowKey, spVersion,
                    syntheticPassword, kk);
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


        RebootEscrowProviderInterface rebootEscrowProvider = mInjector.getRebootEscrowProvider();
        if (rebootEscrowProvider == null) {
            Slog.w(TAG,
                    "Had reboot escrow data for users, but RebootEscrowProvider is unavailable");
            return;
        }

        mStorage.removeKey(REBOOT_ESCROW_ARMED_KEY, USER_SYSTEM);
        rebootEscrowProvider.clearRebootEscrowKey();

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

        RebootEscrowProviderInterface rebootEscrowProvider = mInjector.getRebootEscrowProvider();
        if (rebootEscrowProvider == null) {
            Slog.w(TAG,
                    "Had reboot escrow data for users, but RebootEscrowProvider is unavailable");
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

        // We will use the same key from keystore to encrypt the escrow key and escrow data blob.
        SecretKey kk = mKeyStoreManager.getKeyStoreEncryptionKey();
        if (kk == null) {
            Slog.e(TAG, "Failed to get encryption key from keystore.");
            return false;
        }
        boolean armedRebootEscrow = rebootEscrowProvider.storeRebootEscrowKey(escrowKey, kk);
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
        if (mInjector.getRebootEscrowProvider() == null) {
            return false;
        }

        clearRebootEscrowIfNeeded();
        mRebootEscrowWanted = true;
        mEventLog.addEntry(RebootEscrowEvent.REQUESTED_LSKF);
        return true;
    }

    boolean clearRebootEscrow() {
        if (mInjector.getRebootEscrowProvider() == null) {
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
