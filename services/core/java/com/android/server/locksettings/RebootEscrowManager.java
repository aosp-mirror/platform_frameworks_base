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

import static com.android.internal.widget.LockSettingsInternal.ARM_REBOOT_ERROR_ESCROW_NOT_READY;
import static com.android.internal.widget.LockSettingsInternal.ARM_REBOOT_ERROR_KEYSTORE_FAILURE;
import static com.android.internal.widget.LockSettingsInternal.ARM_REBOOT_ERROR_NONE;
import static com.android.internal.widget.LockSettingsInternal.ARM_REBOOT_ERROR_NO_ESCROW_KEY;
import static com.android.internal.widget.LockSettingsInternal.ARM_REBOOT_ERROR_NO_PROVIDER;
import static com.android.internal.widget.LockSettingsInternal.ARM_REBOOT_ERROR_PROVIDER_MISMATCH;
import static com.android.internal.widget.LockSettingsInternal.ARM_REBOOT_ERROR_STORE_ESCROW_KEY;
import static com.android.internal.widget.LockSettingsInternal.ArmRebootEscrowErrorCode;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
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
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

    static final String REBOOT_ESCROW_KEY_ARMED_TIMESTAMP = "reboot_escrow_key_stored_timestamp";
    static final String REBOOT_ESCROW_KEY_PROVIDER = "reboot_escrow_key_provider";

    /**
     * The verified boot 2.0 vbmeta digest of the current slot, the property value is always
     * available after boot.
     */
    static final String VBMETA_DIGEST_PROP_NAME = "ro.boot.vbmeta.digest";
    /**
     * The system prop contains vbmeta digest of the inactive slot. The build property is set after
     * an OTA update. RebootEscrowManager will store it in disk before the OTA reboot, so the value
     * is available for vbmeta digest verification after the device reboots.
     */
    static final String OTHER_VBMETA_DIGEST_PROP_NAME = "ota.other.vbmeta_digest";
    static final String REBOOT_ESCROW_KEY_VBMETA_DIGEST = "reboot_escrow_key_vbmeta_digest";
    static final String REBOOT_ESCROW_KEY_OTHER_VBMETA_DIGEST =
            "reboot_escrow_key_other_vbmeta_digest";

    /**
     * Number of boots until we consider the escrow data to be stale for the purposes of metrics.
     *
     * <p>If the delta between the current boot number and the boot number stored when the mechanism
     * was armed is under this number and the escrow mechanism fails, we report it as a failure of
     * the mechanism.
     *
     * <p>If the delta over this number and escrow fails, we will not report the metric as failed
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

    // 3 minutes. It's enough for the default 3 retries with 30 seconds interval
    private static final int DEFAULT_LOAD_ESCROW_BASE_TIMEOUT_MILLIS = 180_000;
    // 5 seconds. An extension of the overall RoR timeout to account for overhead.
    private static final int DEFAULT_LOAD_ESCROW_TIMEOUT_EXTENSION_MILLIS = 5000;

    @IntDef(prefix = {"ERROR_"}, value = {
            ERROR_NONE,
            ERROR_UNKNOWN,
            ERROR_NO_PROVIDER,
            ERROR_LOAD_ESCROW_KEY,
            ERROR_RETRY_COUNT_EXHAUSTED,
            ERROR_UNLOCK_ALL_USERS,
            ERROR_PROVIDER_MISMATCH,
            ERROR_KEYSTORE_FAILURE,
            ERROR_NO_NETWORK,
            ERROR_TIMEOUT_EXHAUSTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface RebootEscrowErrorCode {
    }

    static final int ERROR_NONE = 0;
    static final int ERROR_UNKNOWN = 1;
    static final int ERROR_NO_PROVIDER = 2;
    static final int ERROR_LOAD_ESCROW_KEY = 3;
    static final int ERROR_RETRY_COUNT_EXHAUSTED = 4;
    static final int ERROR_UNLOCK_ALL_USERS = 5;
    static final int ERROR_PROVIDER_MISMATCH = 6;
    static final int ERROR_KEYSTORE_FAILURE = 7;
    static final int ERROR_NO_NETWORK = 8;
    static final int ERROR_TIMEOUT_EXHAUSTED = 9;

    private @RebootEscrowErrorCode int mLoadEscrowDataErrorCode = ERROR_NONE;

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

    /** Set when unlocking reboot escrow times out. */
    private boolean mRebootEscrowTimedOut = false;

    /**
     * Set when {@link #loadRebootEscrowDataWithRetry} is called to ensure the function is only
     * called once.
     */
    private boolean mLoadEscrowDataWithRetry = false;

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

    PowerManager.WakeLock mWakeLock;

    private ConnectivityManager.NetworkCallback mNetworkCallback;

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
            if (serverBasedResumeOnReboot()) {
                Slog.i(TAG, "Using server based resume on reboot");
                rebootEscrowProvider = new RebootEscrowProviderServerBasedImpl(mContext, mStorage);
            } else {
                Slog.i(TAG, "Using HAL based resume on reboot");
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

        public boolean serverBasedResumeOnReboot() {
            // Always use the server based RoR if the HAL isn't installed on device.
            if (!mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_REBOOT_ESCROW)) {
                return true;
            }

            return DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_OTA,
                    "server_based_ror_enabled", false);
        }

        public boolean waitForInternet() {
            return DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_OTA, "wait_for_internet_ror", false);
        }

        public boolean isNetworkConnected() {
            final ConnectivityManager connectivityManager =
                    mContext.getSystemService(ConnectivityManager.class);
            if (connectivityManager == null) {
                return false;
            }

            Network activeNetwork = connectivityManager.getActiveNetwork();
            NetworkCapabilities networkCapabilities =
                    connectivityManager.getNetworkCapabilities(activeNetwork);
            return networkCapabilities != null
                    && networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && networkCapabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }

        /**
         * Request network with internet connectivity with timeout.
         *
         * @param networkCallback callback to be executed if connectivity manager exists.
         * @return true if success
         */
        public boolean requestNetworkWithInternet(
                ConnectivityManager.NetworkCallback networkCallback) {
            final ConnectivityManager connectivityManager =
                    mContext.getSystemService(ConnectivityManager.class);
            if (connectivityManager == null) {
                return false;
            }
            NetworkRequest request =
                    new NetworkRequest.Builder()
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .build();

            connectivityManager.requestNetwork(
                    request, networkCallback, getLoadEscrowTimeoutMillis());
            return true;
        }

        public void stopRequestingNetwork(ConnectivityManager.NetworkCallback networkCallback) {
            final ConnectivityManager connectivityManager =
                    mContext.getSystemService(ConnectivityManager.class);
            if (connectivityManager == null) {
                return;
            }
            connectivityManager.unregisterNetworkCallback(networkCallback);
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

        public RebootEscrowProviderInterface createRebootEscrowProviderIfNeeded() {
            // Initialize for the provider lazily. Because the device_config and service
            // implementation apps may change when system server is running.
            if (mRebootEscrowProvider == null) {
                mRebootEscrowProvider = createRebootEscrowProvider();
            }

            return mRebootEscrowProvider;
        }

        PowerManager.WakeLock getWakeLock() {
            final PowerManager pm = mContext.getSystemService(PowerManager.class);
            return pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RebootEscrowManager");
        }

        public RebootEscrowProviderInterface getRebootEscrowProvider() {
            return mRebootEscrowProvider;
        }

        public void clearRebootEscrowProvider() {
            mRebootEscrowProvider = null;
        }

        public int getBootCount() {
            return Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.BOOT_COUNT,
                    0);
        }

        public long getCurrentTimeMillis() {
            return System.currentTimeMillis();
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

        @VisibleForTesting
        public int getLoadEscrowTimeoutMillis() {
            return DEFAULT_LOAD_ESCROW_BASE_TIMEOUT_MILLIS;
        }

        @VisibleForTesting
        public int getWakeLockTimeoutMillis() {
            return getLoadEscrowTimeoutMillis() + DEFAULT_LOAD_ESCROW_TIMEOUT_EXTENSION_MILLIS;
        }

        public void reportMetric(boolean success, int errorCode, int serviceType, int attemptCount,
                int escrowDurationInSeconds, int vbmetaDigestStatus,
                int durationSinceBootCompleteInSeconds) {
            FrameworkStatsLog.write(FrameworkStatsLog.REBOOT_ESCROW_RECOVERY_REPORTED, success,
                    errorCode, serviceType, attemptCount, escrowDurationInSeconds,
                    vbmetaDigestStatus, durationSinceBootCompleteInSeconds);
        }

        public RebootEscrowEventLog getEventLog() {
            return new RebootEscrowEventLog();
        }

        public String getVbmetaDigest(boolean other) {
            return other ? SystemProperties.get(OTHER_VBMETA_DIGEST_PROP_NAME)
                    : SystemProperties.get(VBMETA_DIGEST_PROP_NAME);
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

    /** Wrapper function to set error code serialized through handler, */
    private void setLoadEscrowDataErrorCode(@RebootEscrowErrorCode int value, Handler handler) {
        if (mInjector.waitForInternet()) {
            mInjector.post(
                    handler,
                    () -> {
                        mLoadEscrowDataErrorCode = value;
                    });
        } else {
            mLoadEscrowDataErrorCode = value;
        }
    }

    /** Wrapper function to compare and set error code serialized through handler. */
    private void compareAndSetLoadEscrowDataErrorCode(
            @RebootEscrowErrorCode int expectedValue,
            @RebootEscrowErrorCode int newValue,
            Handler handler) {
        if (expectedValue == mLoadEscrowDataErrorCode) {
            setLoadEscrowDataErrorCode(newValue, handler);
        }
    }

    private void onGetRebootEscrowKeyFailed(
            List<UserInfo> users, int attemptCount, Handler retryHandler) {
        Slog.w(TAG, "Had reboot escrow data for users, but no key; removing escrow storage.");
        for (UserInfo user : users) {
            mStorage.removeRebootEscrow(user.id);
        }

        onEscrowRestoreComplete(false, attemptCount, retryHandler);
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
            Slog.i(TAG, "No reboot escrow data found for users,"
                    + " skipping loading escrow data");
            clearMetricsStorage();
            return;
        }

        // Acquire the wake lock to make sure our scheduled task will run.
        mWakeLock = mInjector.getWakeLock();
        if (mWakeLock != null) {
            mWakeLock.setReferenceCounted(false);
            mWakeLock.acquire(mInjector.getWakeLockTimeoutMillis());
        }

        if (mInjector.waitForInternet()) {
            // Timeout to stop retrying same as the wake lock timeout.
            mInjector.postDelayed(
                    retryHandler,
                    () -> {
                        mRebootEscrowTimedOut = true;
                    },
                    mInjector.getLoadEscrowTimeoutMillis());

            mInjector.post(
                    retryHandler,
                    () -> loadRebootEscrowDataOnInternet(retryHandler, users, rebootEscrowUsers));
            return;
        }

        mInjector.post(retryHandler, () -> loadRebootEscrowDataWithRetry(
                retryHandler, 0, users, rebootEscrowUsers));
    }

    void scheduleLoadRebootEscrowDataOrFail(
            Handler retryHandler,
            int attemptNumber,
            List<UserInfo> users,
            List<UserInfo> rebootEscrowUsers) {
        Objects.requireNonNull(retryHandler);

        final int retryLimit = mInjector.getLoadEscrowDataRetryLimit();
        final int retryIntervalInSeconds = mInjector.getLoadEscrowDataRetryIntervalSeconds();

        if (attemptNumber < retryLimit && !mRebootEscrowTimedOut) {
            Slog.i(TAG, "Scheduling loadRebootEscrowData retry number: " + attemptNumber);
            mInjector.postDelayed(retryHandler, () -> loadRebootEscrowDataWithRetry(
                            retryHandler, attemptNumber, users, rebootEscrowUsers),
                    retryIntervalInSeconds * 1000);
            return;
        }

        if (mInjector.waitForInternet()) {
            if (mRebootEscrowTimedOut) {
                Slog.w(TAG, "Failed to load reboot escrow data within timeout");
                compareAndSetLoadEscrowDataErrorCode(
                        ERROR_NONE, ERROR_TIMEOUT_EXHAUSTED, retryHandler);
            } else {
                Slog.w(
                        TAG,
                        "Failed to load reboot escrow data after " + attemptNumber + " attempts");
                compareAndSetLoadEscrowDataErrorCode(
                        ERROR_NONE, ERROR_RETRY_COUNT_EXHAUSTED, retryHandler);
            }
            onGetRebootEscrowKeyFailed(users, attemptNumber, retryHandler);
            return;
        }

        Slog.w(TAG, "Failed to load reboot escrow data after " + attemptNumber + " attempts");
        if (mInjector.serverBasedResumeOnReboot() && !mInjector.isNetworkConnected()) {
            mLoadEscrowDataErrorCode = ERROR_NO_NETWORK;
        } else {
            mLoadEscrowDataErrorCode = ERROR_RETRY_COUNT_EXHAUSTED;
        }
        onGetRebootEscrowKeyFailed(users, attemptNumber, retryHandler);
    }

    void loadRebootEscrowDataOnInternet(
            Handler retryHandler, List<UserInfo> users, List<UserInfo> rebootEscrowUsers) {

        // HAL-Based RoR does not require network connectivity.
        if (!mInjector.serverBasedResumeOnReboot()) {
            loadRebootEscrowDataWithRetry(
                    retryHandler, /* attemptNumber = */ 0, users, rebootEscrowUsers);
            return;
        }

        mNetworkCallback =
                new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(Network network) {
                        compareAndSetLoadEscrowDataErrorCode(
                                ERROR_NO_NETWORK, ERROR_NONE, retryHandler);

                        if (!mLoadEscrowDataWithRetry) {
                            mLoadEscrowDataWithRetry = true;
                            // Only kickoff retry mechanism on first onAvailable call.
                            loadRebootEscrowDataWithRetry(
                                    retryHandler,
                                    /* attemptNumber = */ 0,
                                    users,
                                    rebootEscrowUsers);
                        }
                    }

                    @Override
                    public void onUnavailable() {
                        Slog.w(TAG, "Failed to connect to network within timeout");
                        compareAndSetLoadEscrowDataErrorCode(
                                ERROR_NONE, ERROR_NO_NETWORK, retryHandler);
                        onGetRebootEscrowKeyFailed(users, /* attemptCount= */ 0, retryHandler);
                    }

                    @Override
                    public void onLost(Network lostNetwork) {
                        // TODO(b/231660348): If network is lost, wait for network to become
                        // available again.
                        Slog.w(TAG, "Network lost, still attempting to load escrow key.");
                        compareAndSetLoadEscrowDataErrorCode(
                                ERROR_NONE, ERROR_NO_NETWORK, retryHandler);
                    }
                };

        // Fallback to retrying without waiting for internet on failure.
        boolean success = mInjector.requestNetworkWithInternet(mNetworkCallback);
        if (!success) {
            loadRebootEscrowDataWithRetry(
                    retryHandler, /* attemptNumber = */ 0, users, rebootEscrowUsers);
        }
    }

    void loadRebootEscrowDataWithRetry(
            Handler retryHandler,
            int attemptNumber,
            List<UserInfo> users,
            List<UserInfo> rebootEscrowUsers) {
        // Fetch the key from keystore to decrypt the escrow data & escrow key; this key is
        // generated before reboot. Note that we will clear the escrow key even if the keystore key
        // is null.
        SecretKey kk = mKeyStoreManager.getKeyStoreEncryptionKey();
        if (kk == null) {
            Slog.i(TAG, "Failed to load the key for resume on reboot from key store.");
        }

        RebootEscrowKey escrowKey;
        try {
            escrowKey = getAndClearRebootEscrowKey(kk, retryHandler);
        } catch (IOException e) {
            Slog.i(TAG, "Failed to load escrow key, scheduling retry.", e);
            scheduleLoadRebootEscrowDataOrFail(retryHandler, attemptNumber + 1, users,
                    rebootEscrowUsers);
            return;
        }

        if (escrowKey == null) {
            if (mLoadEscrowDataErrorCode == ERROR_NONE) {
                // Specifically check if the RoR provider has changed after reboot.
                int providerType = mInjector.serverBasedResumeOnReboot()
                        ? RebootEscrowProviderInterface.TYPE_SERVER_BASED
                        : RebootEscrowProviderInterface.TYPE_HAL;
                if (providerType != mStorage.getInt(REBOOT_ESCROW_KEY_PROVIDER, -1, USER_SYSTEM)) {
                    setLoadEscrowDataErrorCode(ERROR_PROVIDER_MISMATCH, retryHandler);
                } else {
                    setLoadEscrowDataErrorCode(ERROR_LOAD_ESCROW_KEY, retryHandler);
                }
            }
            onGetRebootEscrowKeyFailed(users, attemptNumber + 1, retryHandler);
            return;
        }

        mEventLog.addEntry(RebootEscrowEvent.FOUND_ESCROW_DATA);

        boolean allUsersUnlocked = true;
        for (UserInfo user : rebootEscrowUsers) {
            allUsersUnlocked &= restoreRebootEscrowForUser(user.id, escrowKey, kk);
        }

        if (!allUsersUnlocked) {
            compareAndSetLoadEscrowDataErrorCode(ERROR_NONE, ERROR_UNLOCK_ALL_USERS, retryHandler);
        }
        onEscrowRestoreComplete(allUsersUnlocked, attemptNumber + 1, retryHandler);
    }

    private void clearMetricsStorage() {
        mStorage.removeKey(REBOOT_ESCROW_ARMED_KEY, USER_SYSTEM);
        mStorage.removeKey(REBOOT_ESCROW_KEY_ARMED_TIMESTAMP, USER_SYSTEM);
        mStorage.removeKey(REBOOT_ESCROW_KEY_VBMETA_DIGEST, USER_SYSTEM);
        mStorage.removeKey(REBOOT_ESCROW_KEY_OTHER_VBMETA_DIGEST, USER_SYSTEM);
        mStorage.removeKey(REBOOT_ESCROW_KEY_PROVIDER, USER_SYSTEM);
    }

    private int getVbmetaDigestStatusOnRestoreComplete() {
        String currentVbmetaDigest = mInjector.getVbmetaDigest(false);
        String vbmetaDigestStored = mStorage.getString(REBOOT_ESCROW_KEY_VBMETA_DIGEST,
                "", USER_SYSTEM);
        String vbmetaDigestOtherStored = mStorage.getString(REBOOT_ESCROW_KEY_OTHER_VBMETA_DIGEST,
                "", USER_SYSTEM);

        // The other vbmeta digest is never set, assume no slot switch is attempted.
        if (vbmetaDigestOtherStored.isEmpty()) {
            if (currentVbmetaDigest.equals(vbmetaDigestStored)) {
                return FrameworkStatsLog
                        .REBOOT_ESCROW_RECOVERY_REPORTED__VBMETA_DIGEST_STATUS__MATCH_EXPECTED_SLOT;
            }
            return FrameworkStatsLog
                    .REBOOT_ESCROW_RECOVERY_REPORTED__VBMETA_DIGEST_STATUS__MISMATCH;
        }

        // The other vbmeta digest is set, we expect to boot into the new slot.
        if (currentVbmetaDigest.equals(vbmetaDigestOtherStored)) {
            return FrameworkStatsLog
                    .REBOOT_ESCROW_RECOVERY_REPORTED__VBMETA_DIGEST_STATUS__MATCH_EXPECTED_SLOT;
        } else if (currentVbmetaDigest.equals(vbmetaDigestStored)) {
            return FrameworkStatsLog
                    .REBOOT_ESCROW_RECOVERY_REPORTED__VBMETA_DIGEST_STATUS__MATCH_FALLBACK_SLOT;
        }
        return FrameworkStatsLog
                .REBOOT_ESCROW_RECOVERY_REPORTED__VBMETA_DIGEST_STATUS__MISMATCH;
    }

    private void reportMetricOnRestoreComplete(
            boolean success, int attemptCount, Handler retryHandler) {
        int serviceType = mInjector.serverBasedResumeOnReboot()
                ? FrameworkStatsLog.REBOOT_ESCROW_RECOVERY_REPORTED__TYPE__SERVER_BASED
                : FrameworkStatsLog.REBOOT_ESCROW_RECOVERY_REPORTED__TYPE__HAL;

        long armedTimestamp = mStorage.getLong(REBOOT_ESCROW_KEY_ARMED_TIMESTAMP, -1,
                USER_SYSTEM);
        int escrowDurationInSeconds = -1;
        long currentTimeStamp = mInjector.getCurrentTimeMillis();
        if (armedTimestamp != -1 && currentTimeStamp > armedTimestamp) {
            escrowDurationInSeconds = (int) (currentTimeStamp - armedTimestamp) / 1000;
        }

        int vbmetaDigestStatus = getVbmetaDigestStatusOnRestoreComplete();
        if (!success) {
            compareAndSetLoadEscrowDataErrorCode(ERROR_NONE, ERROR_UNKNOWN, retryHandler);
        }

        Slog.i(
                TAG,
                "Reporting RoR recovery metrics, success: "
                        + success
                        + ", service type: "
                        + serviceType
                        + ", error code: "
                        + mLoadEscrowDataErrorCode);
        // TODO(179105110) report the duration since boot complete.
        mInjector.reportMetric(
                success,
                mLoadEscrowDataErrorCode,
                serviceType,
                attemptCount,
                escrowDurationInSeconds,
                vbmetaDigestStatus,
                -1);

        setLoadEscrowDataErrorCode(ERROR_NONE, retryHandler);
    }

    private void onEscrowRestoreComplete(boolean success, int attemptCount, Handler retryHandler) {
        int previousBootCount = mStorage.getInt(REBOOT_ESCROW_ARMED_KEY, -1, USER_SYSTEM);

        int bootCountDelta = mInjector.getBootCount() - previousBootCount;
        if (success || (previousBootCount != -1 && bootCountDelta <= BOOT_COUNT_TOLERANCE)) {
            reportMetricOnRestoreComplete(success, attemptCount, retryHandler);
        }
        // Clear the old key in keystore. A new key will be generated by new RoR requests.
        mKeyStoreManager.clearKeyStoreEncryptionKey();
        // Clear the saved reboot escrow provider
        mInjector.clearRebootEscrowProvider();
        clearMetricsStorage();

        if (mNetworkCallback != null) {
            mInjector.stopRequestingNetwork(mNetworkCallback);
        }

        if (mWakeLock != null) {
            mWakeLock.release();
        }
    }

    private RebootEscrowKey getAndClearRebootEscrowKey(SecretKey kk, Handler retryHandler)
            throws IOException {
        RebootEscrowProviderInterface rebootEscrowProvider =
                mInjector.createRebootEscrowProviderIfNeeded();
        if (rebootEscrowProvider == null) {
            Slog.w(
                    TAG,
                    "Had reboot escrow data for users, but RebootEscrowProvider is unavailable");
            setLoadEscrowDataErrorCode(ERROR_NO_PROVIDER, retryHandler);
            return null;
        }

        // Server based RoR always need the decryption key from keystore.
        if (rebootEscrowProvider.getType() == RebootEscrowProviderInterface.TYPE_SERVER_BASED
                && kk == null) {
            setLoadEscrowDataErrorCode(ERROR_KEYSTORE_FAILURE, retryHandler);
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

        if (mInjector.createRebootEscrowProviderIfNeeded() == null) {
            Slog.w(TAG, "Not storing escrow data, RebootEscrowProvider is unavailable");
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

        // We want to clear the internal data inside the provider, so always try to create the
        // provider.
        RebootEscrowProviderInterface rebootEscrowProvider =
                mInjector.createRebootEscrowProviderIfNeeded();
        if (rebootEscrowProvider == null) {
            Slog.w(TAG, "RebootEscrowProvider is unavailable for clear request");
        } else {
            rebootEscrowProvider.clearRebootEscrowKey();
        }

        mInjector.clearRebootEscrowProvider();
        clearMetricsStorage();

        List<UserInfo> users = mUserManager.getUsers();
        for (UserInfo user : users) {
            mStorage.removeRebootEscrow(user.id);
        }

        mEventLog.addEntry(RebootEscrowEvent.CLEARED_LSKF_REQUEST);
    }

    @ArmRebootEscrowErrorCode int armRebootEscrowIfNeeded() {
        if (!mRebootEscrowReady) {
            return ARM_REBOOT_ERROR_ESCROW_NOT_READY;
        }

        RebootEscrowProviderInterface rebootEscrowProvider = mInjector.getRebootEscrowProvider();
        if (rebootEscrowProvider == null) {
            Slog.w(TAG, "Not storing escrow key, RebootEscrowProvider is unavailable");
            clearRebootEscrowIfNeeded();
            return ARM_REBOOT_ERROR_NO_PROVIDER;
        }

        int expectedProviderType = mInjector.serverBasedResumeOnReboot()
                ? RebootEscrowProviderInterface.TYPE_SERVER_BASED
                : RebootEscrowProviderInterface.TYPE_HAL;
        int actualProviderType = rebootEscrowProvider.getType();
        if (expectedProviderType != actualProviderType) {
            Slog.w(TAG, "Expect reboot escrow provider " + expectedProviderType
                    + ", but the RoR is prepared with " + actualProviderType
                    + ". Please prepare the RoR again.");
            clearRebootEscrowIfNeeded();
            return ARM_REBOOT_ERROR_PROVIDER_MISMATCH;
        }

        RebootEscrowKey escrowKey;
        synchronized (mKeyGenerationLock) {
            escrowKey = mPendingRebootEscrowKey;
        }

        if (escrowKey == null) {
            Slog.e(TAG, "Escrow key is null, but escrow was marked as ready");
            clearRebootEscrowIfNeeded();
            return ARM_REBOOT_ERROR_NO_ESCROW_KEY;
        }

        // We will use the same key from keystore to encrypt the escrow key and escrow data blob.
        SecretKey kk = mKeyStoreManager.getKeyStoreEncryptionKey();
        if (kk == null) {
            Slog.e(TAG, "Failed to get encryption key from keystore.");
            clearRebootEscrowIfNeeded();
            return ARM_REBOOT_ERROR_KEYSTORE_FAILURE;
        }

        // TODO(b/183140900) design detailed errors for store escrow key errors.
        // We don't clear rebootEscrow here, because some errors may be recoverable, e.g. network
        // unavailable for server based provider.
        boolean armedRebootEscrow = rebootEscrowProvider.storeRebootEscrowKey(escrowKey, kk);
        if (!armedRebootEscrow) {
            return ARM_REBOOT_ERROR_STORE_ESCROW_KEY;
        }

        mStorage.setInt(REBOOT_ESCROW_ARMED_KEY, mInjector.getBootCount(), USER_SYSTEM);
        mStorage.setLong(REBOOT_ESCROW_KEY_ARMED_TIMESTAMP, mInjector.getCurrentTimeMillis(),
                USER_SYSTEM);
        // Store the vbmeta digest of both slots.
        mStorage.setString(REBOOT_ESCROW_KEY_VBMETA_DIGEST, mInjector.getVbmetaDigest(false),
                USER_SYSTEM);
        mStorage.setString(REBOOT_ESCROW_KEY_OTHER_VBMETA_DIGEST,
                mInjector.getVbmetaDigest(true), USER_SYSTEM);
        mStorage.setInt(REBOOT_ESCROW_KEY_PROVIDER, actualProviderType, USER_SYSTEM);
        mEventLog.addEntry(RebootEscrowEvent.SET_ARMED_STATUS);

        return ARM_REBOOT_ERROR_NONE;
    }

    private void setRebootEscrowReady(boolean ready) {
        if (mRebootEscrowReady != ready) {
            mRebootEscrowListener.onPreparedForReboot(ready);
        }
        mRebootEscrowReady = ready;
    }

    boolean prepareRebootEscrow() {
        clearRebootEscrowIfNeeded();
        if (mInjector.createRebootEscrowProviderIfNeeded() == null) {
            Slog.w(TAG, "No reboot escrow provider, skipping resume on reboot preparation.");
            return false;
        }

        mRebootEscrowWanted = true;
        mEventLog.addEntry(RebootEscrowEvent.REQUESTED_LSKF);
        return true;
    }

    boolean clearRebootEscrow() {
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

        pw.print("mLoadEscrowDataErrorCode=");
        pw.println(mLoadEscrowDataErrorCode);

        boolean keySet;
        synchronized (mKeyGenerationLock) {
            keySet = mPendingRebootEscrowKey != null;
        }

        pw.print("mPendingRebootEscrowKey is ");
        pw.println(keySet ? "set" : "not set");

        RebootEscrowProviderInterface provider = mInjector.getRebootEscrowProvider();
        String providerType = provider == null ? "null" : String.valueOf(provider.getType());
        pw.print("RebootEscrowProvider type is " + providerType);

        pw.println();
        pw.println("Event log:");
        pw.increaseIndent();
        mEventLog.dump(pw);
        pw.println();
        pw.decreaseIndent();
    }
}
