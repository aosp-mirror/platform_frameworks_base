/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.widget;

import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_MANAGED;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PropertyInvalidatedCache;
import android.app.admin.DevicePolicyManager;
import android.app.admin.PasswordMetrics;
import android.app.trust.IStrongAuthTracker;
import android.app.trust.TrustManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import com.google.android.collect.Lists;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/**
 * Utilities for the lock pattern and its settings.
 */
public class LockPatternUtils {
    private static final String TAG = "LockPatternUtils";
    private static final boolean FRP_CREDENTIAL_ENABLED = true;

    /**
     * The key to identify when the lock pattern enabled flag is being accessed for legacy reasons.
     */
    public static final String LEGACY_LOCK_PATTERN_ENABLED = "legacy_lock_pattern_enabled";

    /**
     * The interval of the countdown for showing progress of the lockout.
     */
    public static final long FAILED_ATTEMPT_COUNTDOWN_INTERVAL_MS = 1000L;

    /**
     * This dictates when we start telling the user that continued failed attempts will wipe
     * their device.
     */
    public static final int FAILED_ATTEMPTS_BEFORE_WIPE_GRACE = 5;

    /**
     * The minimum number of dots in a valid pattern.
     */
    public static final int MIN_LOCK_PATTERN_SIZE = 4;

    /**
     * The minimum size of a valid password.
     */
    public static final int MIN_LOCK_PASSWORD_SIZE = 4;

    /**
     * The minimum number of dots the user must include in a wrong pattern attempt for it to be
     * counted.
     */
    public static final int MIN_PATTERN_REGISTER_FAIL = MIN_LOCK_PATTERN_SIZE;

    // NOTE: When modifying this, make sure credential sufficiency validation logic is intact.
    public static final int CREDENTIAL_TYPE_NONE = -1;
    public static final int CREDENTIAL_TYPE_PATTERN = 1;
    // This is the legacy value persisted on disk. Never return it to clients, but internally
    // we still need it to handle upgrade cases.
    public static final int CREDENTIAL_TYPE_PASSWORD_OR_PIN = 2;
    public static final int CREDENTIAL_TYPE_PIN = 3;
    public static final int CREDENTIAL_TYPE_PASSWORD = 4;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"CREDENTIAL_TYPE_"}, value = {
            CREDENTIAL_TYPE_NONE,
            CREDENTIAL_TYPE_PATTERN,
            CREDENTIAL_TYPE_PASSWORD,
            CREDENTIAL_TYPE_PIN,
            // CREDENTIAL_TYPE_PASSWORD_OR_PIN is missing on purpose.
    })
    public @interface CredentialType {}

    /**
     * Flag provided to {@link #verifyCredential(LockscreenCredential, int, int)} . If set, the
     * method will return a handle to the Gatekeeper Password in the
     * {@link VerifyCredentialResponse}.
     */
    public static final int VERIFY_FLAG_REQUEST_GK_PW_HANDLE = 1 << 0;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            VERIFY_FLAG_REQUEST_GK_PW_HANDLE
    })
    public @interface VerifyFlag {}

    /**
     * Special user id for triggering the FRP verification flow.
     */
    public static final int USER_FRP = UserHandle.USER_NULL + 1;

    @Deprecated
    public final static String LOCKOUT_PERMANENT_KEY = "lockscreen.lockedoutpermanently";
    public final static String PATTERN_EVER_CHOSEN_KEY = "lockscreen.patterneverchosen";
    public final static String PASSWORD_TYPE_KEY = "lockscreen.password_type";
    @Deprecated
    public final static String PASSWORD_TYPE_ALTERNATE_KEY = "lockscreen.password_type_alternate";
    public final static String LOCK_PASSWORD_SALT_KEY = "lockscreen.password_salt";
    public final static String DISABLE_LOCKSCREEN_KEY = "lockscreen.disabled";
    public final static String LOCKSCREEN_OPTIONS = "lockscreen.options";
    @Deprecated
    public final static String LOCKSCREEN_BIOMETRIC_WEAK_FALLBACK
            = "lockscreen.biometric_weak_fallback";
    @Deprecated
    public final static String BIOMETRIC_WEAK_EVER_CHOSEN_KEY
            = "lockscreen.biometricweakeverchosen";
    public final static String LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS
            = "lockscreen.power_button_instantly_locks";
    @Deprecated
    public final static String LOCKSCREEN_WIDGETS_ENABLED = "lockscreen.widgets_enabled";

    public final static String PASSWORD_HISTORY_KEY = "lockscreen.passwordhistory";

    private static final String LOCK_SCREEN_OWNER_INFO = Settings.Secure.LOCK_SCREEN_OWNER_INFO;
    private static final String LOCK_SCREEN_OWNER_INFO_ENABLED =
            Settings.Secure.LOCK_SCREEN_OWNER_INFO_ENABLED;

    private static final String LOCK_SCREEN_DEVICE_OWNER_INFO = "lockscreen.device_owner_info";

    private static final String ENABLED_TRUST_AGENTS = "lockscreen.enabledtrustagents";
    private static final String KNOWN_TRUST_AGENTS = "lockscreen.knowntrustagents";
    private static final String IS_TRUST_USUALLY_MANAGED = "lockscreen.istrustusuallymanaged";

    public static final String PROFILE_KEY_NAME_ENCRYPT = "profile_key_name_encrypt_";
    public static final String PROFILE_KEY_NAME_DECRYPT = "profile_key_name_decrypt_";
    public static final String SYNTHETIC_PASSWORD_KEY_PREFIX = "synthetic_password_";

    public static final String SYNTHETIC_PASSWORD_HANDLE_KEY = "sp-handle";
    public static final String SYNTHETIC_PASSWORD_ENABLED_KEY = "enable-sp";
    public static final int SYNTHETIC_PASSWORD_ENABLED_BY_DEFAULT = 1;
    public static final String PASSWORD_HISTORY_DELIMITER = ",";

    @UnsupportedAppUsage
    private final Context mContext;
    @UnsupportedAppUsage
    private final ContentResolver mContentResolver;
    private DevicePolicyManager mDevicePolicyManager;
    private ILockSettings mLockSettingsService;
    private UserManager mUserManager;
    private final Handler mHandler;
    private final SparseLongArray mLockoutDeadlines = new SparseLongArray();
    private Boolean mHasSecureLockScreen;

    private HashMap<UserHandle, UserManager> mUserManagerCache = new HashMap<>();

    /**
     * Use {@link TrustManager#isTrustUsuallyManaged(int)}.
     *
     * This returns the lazily-peristed value and should only be used by TrustManagerService.
     */
    public boolean isTrustUsuallyManaged(int userId) {
        if (!(mLockSettingsService instanceof ILockSettings.Stub)) {
            throw new IllegalStateException("May only be called by TrustManagerService. "
                    + "Use TrustManager.isTrustUsuallyManaged()");
        }
        try {
            return getLockSettings().getBoolean(IS_TRUST_USUALLY_MANAGED, false, userId);
        } catch (RemoteException e) {
            return false;
        }
    }

    public void setTrustUsuallyManaged(boolean managed, int userId) {
        try {
            getLockSettings().setBoolean(IS_TRUST_USUALLY_MANAGED, managed, userId);
        } catch (RemoteException e) {
            // System dead.
        }
    }

    public void userPresent(int userId) {
        try {
            getLockSettings().userPresent(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public static final class RequestThrottledException extends Exception {
        private int mTimeoutMs;
        @UnsupportedAppUsage
        public RequestThrottledException(int timeoutMs) {
            mTimeoutMs = timeoutMs;
        }

        /**
         * @return The amount of time in ms before another request may
         * be executed
         */
        @UnsupportedAppUsage
        public int getTimeoutMs() {
            return mTimeoutMs;
        }

    }

    @UnsupportedAppUsage
    public DevicePolicyManager getDevicePolicyManager() {
        if (mDevicePolicyManager == null) {
            mDevicePolicyManager =
                (DevicePolicyManager)mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (mDevicePolicyManager == null) {
                Log.e(TAG, "Can't get DevicePolicyManagerService: is it running?",
                        new IllegalStateException("Stack trace:"));
            }
        }
        return mDevicePolicyManager;
    }

    private UserManager getUserManager() {
        if (mUserManager == null) {
            mUserManager = UserManager.get(mContext);
        }
        return mUserManager;
    }

    private UserManager getUserManager(int userId) {
        UserHandle userHandle = UserHandle.of(userId);
        if (mUserManagerCache.containsKey(userHandle)) {
            return mUserManagerCache.get(userHandle);
        }

        try {
            Context userContext = mContext.createPackageContextAsUser("system", 0, userHandle);
            UserManager userManager = userContext.getSystemService(UserManager.class);
            mUserManagerCache.put(userHandle, userManager);
            return userManager;
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Failed to create context for user " + userHandle, e);
        }
    }

    private TrustManager getTrustManager() {
        TrustManager trust = (TrustManager) mContext.getSystemService(Context.TRUST_SERVICE);
        if (trust == null) {
            Log.e(TAG, "Can't get TrustManagerService: is it running?",
                    new IllegalStateException("Stack trace:"));
        }
        return trust;
    }

    @UnsupportedAppUsage
    public LockPatternUtils(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();

        Looper looper = Looper.myLooper();
        mHandler = looper != null ? new Handler(looper) : null;
    }

    @UnsupportedAppUsage
    @VisibleForTesting
    public ILockSettings getLockSettings() {
        if (mLockSettingsService == null) {
            ILockSettings service = ILockSettings.Stub.asInterface(
                    ServiceManager.getService("lock_settings"));
            mLockSettingsService = service;
        }
        return mLockSettingsService;
    }

    public int getRequestedMinimumPasswordLength(int userId) {
        return getDevicePolicyManager().getPasswordMinimumLength(null, userId);
    }

    public int getMaximumPasswordLength(int quality) {
        return getDevicePolicyManager().getPasswordMaximumLength(quality);
    }

    /**
     * Returns aggregated (legacy) password quality requirement on the target user from all admins.
     */
    public PasswordMetrics getRequestedPasswordMetrics(int userId) {
        return getRequestedPasswordMetrics(userId, false);
    }

    /**
     * Returns aggregated (legacy) password quality requirement on the target user from all admins,
     * optioanlly disregarding policies set on the managed profile as if the  profile had separate
     * work challenge.
     */
    public PasswordMetrics getRequestedPasswordMetrics(int userId, boolean deviceWideOnly) {
        return getDevicePolicyManager().getPasswordMinimumMetrics(userId, deviceWideOnly);
    }

    private int getRequestedPasswordHistoryLength(int userId) {
        return getDevicePolicyManager().getPasswordHistoryLength(null, userId);
    }

    /**
     * Returns the effective complexity for the user.
     * @param userId  The user to return the complexity for.
     * @return complexity level for the user.
     */
    public @DevicePolicyManager.PasswordComplexity int getRequestedPasswordComplexity(int userId) {
        return getRequestedPasswordComplexity(userId, false);
    }

    /**
     * Returns the effective complexity for the user, optioanlly disregarding complexity set on the
     * managed profile as if the  profile had separate work challenge.

     * @param userId  The user to return the complexity for.
     * @param deviceWideOnly  whether to ignore complexity set on the managed profile.
     * @return complexity level for the user.
     */
    public @DevicePolicyManager.PasswordComplexity int getRequestedPasswordComplexity(int userId,
            boolean deviceWideOnly) {
        return getDevicePolicyManager().getAggregatedPasswordComplexityForUser(userId,
                deviceWideOnly);
    }

    @UnsupportedAppUsage
    public void reportFailedPasswordAttempt(int userId) {
        if (userId == USER_FRP && frpCredentialEnabled(mContext)) {
            return;
        }
        getDevicePolicyManager().reportFailedPasswordAttempt(userId);
        getTrustManager().reportUnlockAttempt(false /* authenticated */, userId);
    }

    @UnsupportedAppUsage
    public void reportSuccessfulPasswordAttempt(int userId) {
        if (userId == USER_FRP && frpCredentialEnabled(mContext)) {
            return;
        }
        getDevicePolicyManager().reportSuccessfulPasswordAttempt(userId);
        getTrustManager().reportUnlockAttempt(true /* authenticated */, userId);
    }

    public void reportPasswordLockout(int timeoutMs, int userId) {
        if (userId == USER_FRP && frpCredentialEnabled(mContext)) {
            return;
        }
        getTrustManager().reportUnlockLockout(timeoutMs, userId);
    }

    public int getCurrentFailedPasswordAttempts(int userId) {
        if (userId == USER_FRP && frpCredentialEnabled(mContext)) {
            return 0;
        }
        return getDevicePolicyManager().getCurrentFailedPasswordAttempts(userId);
    }

    public int getMaximumFailedPasswordsForWipe(int userId) {
        if (userId == USER_FRP && frpCredentialEnabled(mContext)) {
            return 0;
        }
        return getDevicePolicyManager().getMaximumFailedPasswordsForWipe(
                null /* componentName */, userId);
    }

    /**
     * Check to see if a credential matches the saved one.
     * If credential matches, return an opaque attestation that the challenge was verified.
     *
     * @param credential The credential to check.
     * @param userId The user whose credential is being verified
     * @param flags See {@link VerifyFlag}
     * @throws IllegalStateException if called on the main thread.
     */
    @NonNull
    public VerifyCredentialResponse verifyCredential(@NonNull LockscreenCredential credential,
            int userId, @VerifyFlag int flags) {
        throwIfCalledOnMainThread();
        try {
            final VerifyCredentialResponse response = getLockSettings().verifyCredential(
                    credential, userId, flags);
            if (response == null) {
                return VerifyCredentialResponse.ERROR;
            } else {
                return response;
            }
        } catch (RemoteException re) {
            Log.e(TAG, "failed to verify credential", re);
            return VerifyCredentialResponse.ERROR;
        }
    }

    /**
     * With the Gatekeeper Password Handle returned via {@link #verifyCredential(
     * LockscreenCredential, int, int)}, request Gatekeeper to create a HardwareAuthToken wrapping
     * the given challenge.
     */
    @NonNull
    public VerifyCredentialResponse verifyGatekeeperPasswordHandle(long gatekeeperPasswordHandle,
            long challenge, int userId) {
        try {
            final VerifyCredentialResponse response = getLockSettings()
                    .verifyGatekeeperPasswordHandle(gatekeeperPasswordHandle, challenge, userId);
            if (response == null) {
                return VerifyCredentialResponse.ERROR;
            }
            return response;
        } catch (RemoteException e) {
            Log.e(TAG, "failed to verify gatekeeper password", e);
            return VerifyCredentialResponse.ERROR;
        }
    }

    public void removeGatekeeperPasswordHandle(long gatekeeperPasswordHandle) {
        try {
            getLockSettings().removeGatekeeperPasswordHandle(gatekeeperPasswordHandle);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to remove gatekeeper password handle", e);
        }
    }

    /**
     * Check to see if a credential matches the saved one.
     *
     * @param credential The credential to check.
     * @param userId The user whose credential is being checked
     * @param progressCallback callback to deliver early signal that the credential matches
     * @return {@code true} if credential matches, {@code false} otherwise
     * @throws RequestThrottledException if credential verification is being throttled due to
     *         to many incorrect attempts.
     * @throws IllegalStateException if called on the main thread.
     */
    public boolean checkCredential(@NonNull LockscreenCredential credential, int userId,
            @Nullable CheckCredentialProgressCallback progressCallback)
            throws RequestThrottledException {
        throwIfCalledOnMainThread();
        try {
            VerifyCredentialResponse response = getLockSettings().checkCredential(
                    credential, userId, wrapCallback(progressCallback));
            if (response == null) {
                return false;
            } else if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
                return true;
            } else if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
                throw new RequestThrottledException(response.getTimeout());
            } else {
                return false;
            }
        } catch (RemoteException re) {
            Log.e(TAG, "failed to check credential", re);
            return false;
        }
    }

    /**
     * Check if the credential of a managed profile with unified challenge matches. In this context,
     * The credential should be the parent user's lockscreen password. If credential matches,
     * return an opaque attestation associated with the managed profile that the challenge was
     * verified.
     *
     * @param credential The parent user's credential to check.
     * @return the attestation that the challenge was verified, or null
     * @param userId The managed profile user id
     * @param flags See {@link VerifyFlag}
     * @throws IllegalStateException if called on the main thread.
     */
    @NonNull
    public VerifyCredentialResponse verifyTiedProfileChallenge(
            @NonNull LockscreenCredential credential, int userId, @VerifyFlag int flags) {
        throwIfCalledOnMainThread();
        try {
            final VerifyCredentialResponse response = getLockSettings()
                    .verifyTiedProfileChallenge(credential, userId, flags);
            if (response == null) {
                return VerifyCredentialResponse.ERROR;
            } else {
                return response;
            }
        } catch (RemoteException re) {
            Log.e(TAG, "failed to verify tied profile credential", re);
            return VerifyCredentialResponse.ERROR;
        }
    }

    /**
     * Returns the password history hash factor, needed to check new password against password
     * history with {@link #checkPasswordHistory(byte[], byte[], int)}
     */
    public byte[] getPasswordHistoryHashFactor(@NonNull LockscreenCredential currentPassword,
            int userId) {
        try {
            return getLockSettings().getHashFactor(currentPassword, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to get hash factor", e);
            return null;
        }
    }

    /**
     * Check to see if a password matches any of the passwords stored in the
     * password history.
     *
     * @param passwordToCheck The password to check.
     * @param hashFactor Hash factor of the current user returned from
     *        {@link ILockSettings#getHashFactor}
     * @return Whether the password matches any in the history.
     */
    public boolean checkPasswordHistory(byte[] passwordToCheck, byte[] hashFactor, int userId) {
        if (passwordToCheck == null || passwordToCheck.length == 0) {
            Log.e(TAG, "checkPasswordHistory: empty password");
            return false;
        }
        String passwordHistory = getString(PASSWORD_HISTORY_KEY, userId);
        if (TextUtils.isEmpty(passwordHistory)) {
            return false;
        }
        int passwordHistoryLength = getRequestedPasswordHistoryLength(userId);
        if(passwordHistoryLength == 0) {
            return false;
        }
        byte[] salt = getSalt(userId).getBytes();
        String legacyHash = LockscreenCredential.legacyPasswordToHash(passwordToCheck, salt);
        String passwordHash = LockscreenCredential.passwordToHistoryHash(
                passwordToCheck, salt, hashFactor);
        String[] history = passwordHistory.split(PASSWORD_HISTORY_DELIMITER);
        // Password History may be too long...
        for (int i = 0; i < Math.min(passwordHistoryLength, history.length); i++) {
            if (history[i].equals(legacyHash) || history[i].equals(passwordHash)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return true if the user has ever chosen a pattern.  This is true even if the pattern is
     * currently cleared.
     *
     * @return True if the user has ever chosen a pattern.
     */
    public boolean isPatternEverChosen(int userId) {
        return getBoolean(PATTERN_EVER_CHOSEN_KEY, false, userId);
    }

    /**
     * Records that the user has chosen a pattern at some time, even if the pattern is
     * currently cleared.
     */
    public void reportPatternWasChosen(int userId) {
        setBoolean(PATTERN_EVER_CHOSEN_KEY, true, userId);
    }

    /**
     * Used by device policy manager to validate the current password
     * information it has.
     * @Deprecated use {@link #getKeyguardStoredPasswordQuality}
     */
    @UnsupportedAppUsage
    public int getActivePasswordQuality(int userId) {
        return getKeyguardStoredPasswordQuality(userId);
    }

    /**
     * Use it to reset keystore without wiping work profile
     */
    public void resetKeyStore(int userId) {
        try {
            getLockSettings().resetKeyStore(userId);
        } catch (RemoteException e) {
            // It should not happen
            Log.e(TAG, "Couldn't reset keystore " + e);
        }
    }

    /**
     * Disable showing lock screen at all for a given user.
     * This is only meaningful if pattern, pin or password are not set.
     *
     * @param disable Disables lock screen when true
     * @param userId User ID of the user this has effect on
     */
    public void setLockScreenDisabled(boolean disable, int userId) {
        setBoolean(DISABLE_LOCKSCREEN_KEY, disable, userId);
    }

    /**
     * Determine if LockScreen is disabled for the current user. This is used to decide whether
     * LockScreen is shown after reboot or after screen timeout / short press on power.
     *
     * @return true if lock screen is disabled
     */
    @UnsupportedAppUsage
    public boolean isLockScreenDisabled(int userId) {
        if (isSecure(userId)) {
            return false;
        }
        boolean disabledByDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_disableLockscreenByDefault);
        boolean isSystemUser = UserManager.isSplitSystemUser() && userId == UserHandle.USER_SYSTEM;
        UserInfo userInfo = getUserManager().getUserInfo(userId);
        boolean isDemoUser = UserManager.isDeviceInDemoMode(mContext) && userInfo != null
                && userInfo.isDemo();
        return getBoolean(DISABLE_LOCKSCREEN_KEY, false, userId)
                || (disabledByDefault && !isSystemUser)
                || isDemoUser;
    }

    /** Returns if the given quality maps to an alphabetic password */
    public static boolean isQualityAlphabeticPassword(int quality) {
        return quality >= PASSWORD_QUALITY_ALPHABETIC;
    }

    /** Returns if the given quality maps to an numeric pin */
    public static boolean isQualityNumericPin(int quality) {
        return quality == PASSWORD_QUALITY_NUMERIC || quality == PASSWORD_QUALITY_NUMERIC_COMPLEX;
    }

    /** Returns the canonical password quality corresponding to the given credential type. */
    public static int credentialTypeToPasswordQuality(int credentialType) {
        switch (credentialType) {
            case CREDENTIAL_TYPE_NONE:
                return PASSWORD_QUALITY_UNSPECIFIED;
            case CREDENTIAL_TYPE_PATTERN:
                return PASSWORD_QUALITY_SOMETHING;
            case CREDENTIAL_TYPE_PIN:
                return PASSWORD_QUALITY_NUMERIC;
            case CREDENTIAL_TYPE_PASSWORD:
                return PASSWORD_QUALITY_ALPHABETIC;
            default:
                throw new IllegalStateException("Unknown type: " + credentialType);
        }
    }

    /**
     * Save a new lockscreen credential.
     *
     * <p> This method will fail (returning {@code false}) if the previously saved credential
     * provided is incorrect, or if the lockscreen verification is still being throttled.
     *
     * @param newCredential The new credential to save
     * @param savedCredential The current credential
     * @param userHandle the user whose lockscreen credential is to be changed
     *
     * @return whether this method saved the new password successfully or not. This flow will fail
     * and return false if the given credential is wrong.
     * @throws RuntimeException if password change encountered an unrecoverable error.
     * @throws UnsupportedOperationException secure lockscreen is not supported on this device.
     * @throws IllegalArgumentException if new credential is too short.
     */
    public boolean setLockCredential(@NonNull LockscreenCredential newCredential,
            @NonNull LockscreenCredential savedCredential, int userHandle) {
        if (!hasSecureLockScreen() && newCredential.getType() != CREDENTIAL_TYPE_NONE) {
            throw new UnsupportedOperationException(
                    "This operation requires the lock screen feature.");
        }
        newCredential.checkLength();

        try {
            if (!getLockSettings().setLockCredential(newCredential, savedCredential, userHandle)) {
                return false;
            }
        } catch (RemoteException e) {
            throw new RuntimeException("Unable to save lock password", e);
        }
        return true;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setOwnerInfo(String info, int userId) {
        setString(LOCK_SCREEN_OWNER_INFO, info, userId);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setOwnerInfoEnabled(boolean enabled, int userId) {
        setBoolean(LOCK_SCREEN_OWNER_INFO_ENABLED, enabled, userId);
    }

    @UnsupportedAppUsage
    public String getOwnerInfo(int userId) {
        return getString(LOCK_SCREEN_OWNER_INFO, userId);
    }

    public boolean isOwnerInfoEnabled(int userId) {
        return getBoolean(LOCK_SCREEN_OWNER_INFO_ENABLED, false, userId);
    }

    /**
     * Sets the device owner information. If the information is {@code null} or empty then the
     * device owner info is cleared.
     *
     * @param info Device owner information which will be displayed instead of the user
     * owner info.
     */
    public void setDeviceOwnerInfo(String info) {
        if (info != null && info.isEmpty()) {
            info = null;
        }

        setString(LOCK_SCREEN_DEVICE_OWNER_INFO, info, UserHandle.USER_SYSTEM);
    }

    public String getDeviceOwnerInfo() {
        return getString(LOCK_SCREEN_DEVICE_OWNER_INFO, UserHandle.USER_SYSTEM);
    }

    public boolean isDeviceOwnerInfoEnabled() {
        return getDeviceOwnerInfo() != null;
    }

    /**
     * Determine if the device supports encryption, even if it's set to default. This
     * differs from isDeviceEncrypted() in that it returns true even if the device is
     * encrypted with the default password.
     * @return true if device encryption is enabled
     */
    @UnsupportedAppUsage
    public static boolean isDeviceEncryptionEnabled() {
        return StorageManager.isEncrypted();
    }

    /**
     * Determine if the device is file encrypted
     * @return true if device is file encrypted
     */
    public static boolean isFileEncryptionEnabled() {
        return StorageManager.isFileEncryptedNativeOrEmulated();
    }

    /**
     * Retrieves the quality mode for {@code userHandle}.
     * @see DevicePolicyManager#getPasswordQuality(android.content.ComponentName)
     *
     * @return stored password quality
     * @deprecated use {@link #getCredentialTypeForUser(int)} instead
     */
    @UnsupportedAppUsage
    @Deprecated
    public int getKeyguardStoredPasswordQuality(int userHandle) {
        return credentialTypeToPasswordQuality(getCredentialTypeForUser(userHandle));
    }

    /**
     * Enables/disables the Separate Profile Challenge for this {@code userHandle}. This is a no-op
     * for user handles that do not belong to a profile that shares credential with parent.
     * (managed profile and clone profile share lock credential with parent).
     *
     * @param userHandle Managed profile user id
     * @param enabled True if separate challenge is enabled
     * @param profilePassword Managed/Clone profile previous password. Null when {@code enabled} is
     *            true
     */
    public void setSeparateProfileChallengeEnabled(int userHandle, boolean enabled,
            LockscreenCredential profilePassword) {
        if (!isCredentialSharableWithParent(userHandle)) {
            return;
        }
        try {
            getLockSettings().setSeparateProfileChallengeEnabled(userHandle, enabled,
                    profilePassword);
            reportEnabledTrustAgentsChanged(userHandle);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't update work profile challenge enabled");
        }
    }

    /**
     * Returns true if {@code userHandle} is a managed profile with separate challenge.
     */
    public boolean isSeparateProfileChallengeEnabled(int userHandle) {
        return isCredentialSharableWithParent(userHandle) && hasSeparateChallenge(userHandle);
    }

    /**
     * Returns true if {@code userHandle} is a managed profile with unified challenge.
     */
    public boolean isManagedProfileWithUnifiedChallenge(int userHandle) {
        return isManagedProfile(userHandle) && !hasSeparateChallenge(userHandle);
    }

    private boolean hasSeparateChallenge(int userHandle) {
        try {
            return getLockSettings().getSeparateProfileChallengeEnabled(userHandle);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't get separate profile challenge enabled");
            // Default value is false
            return false;
        }
    }

    private boolean isManagedProfile(int userHandle) {
        final UserInfo info = getUserManager().getUserInfo(userHandle);
        return info != null && info.isManagedProfile();
    }

    private boolean isCredentialSharableWithParent(int userHandle) {
        return getUserManager(userHandle).isCredentialSharableWithParent();
    }

    /**
     * Deserialize a pattern.
     * @param  bytes The pattern serialized with {@link #patternToByteArray}
     * @return The pattern.
     */
    public static List<LockPatternView.Cell> byteArrayToPattern(byte[] bytes) {
        if (bytes == null) {
            return null;
        }

        List<LockPatternView.Cell> result = Lists.newArrayList();

        for (int i = 0; i < bytes.length; i++) {
            byte b = (byte) (bytes[i] - '1');
            result.add(LockPatternView.Cell.of(b / 3, b % 3));
        }
        return result;
    }

    /**
     * Serialize a pattern.
     * @param pattern The pattern.
     * @return The pattern in byte array form.
     */
    public static byte[] patternToByteArray(List<LockPatternView.Cell> pattern) {
        if (pattern == null) {
            return new byte[0];
        }
        final int patternSize = pattern.size();

        byte[] res = new byte[patternSize];
        for (int i = 0; i < patternSize; i++) {
            LockPatternView.Cell cell = pattern.get(i);
            res[i] = (byte) (cell.getRow() * 3 + cell.getColumn() + '1');
        }
        return res;
    }

    private String getSalt(int userId) {
        long salt = getLong(LOCK_PASSWORD_SALT_KEY, 0, userId);
        if (salt == 0) {
            try {
                salt = SecureRandom.getInstance("SHA1PRNG").nextLong();
                setLong(LOCK_PASSWORD_SALT_KEY, salt, userId);
                Log.v(TAG, "Initialized lock password salt for user: " + userId);
            } catch (NoSuchAlgorithmException e) {
                // Throw an exception rather than storing a password we'll never be able to recover
                throw new IllegalStateException("Couldn't get SecureRandom number", e);
            }
        }
        return Long.toHexString(salt);
    }

    /**
     * Retrieve the credential type of a user.
     */
    private final PropertyInvalidatedCache.QueryHandler<Integer, Integer> mCredentialTypeQuery =
            new PropertyInvalidatedCache.QueryHandler<>() {
                @Override
                public Integer apply(Integer userHandle) {
                    try {
                        return getLockSettings().getCredentialType(userHandle);
                    } catch (RemoteException re) {
                        Log.e(TAG, "failed to get credential type", re);
                        return CREDENTIAL_TYPE_NONE;
                    }
                }
                @Override
                public boolean shouldBypassCache(Integer userHandle) {
                    return userHandle == USER_FRP;
                }
            };

    /**
     * The API that is cached.
     */
    private final static String CREDENTIAL_TYPE_API = "getCredentialType";

    /**
     * Cache the credential type of a user.
     */
    private final PropertyInvalidatedCache<Integer, Integer> mCredentialTypeCache =
            new PropertyInvalidatedCache<>(4, PropertyInvalidatedCache.MODULE_SYSTEM,
                    CREDENTIAL_TYPE_API, CREDENTIAL_TYPE_API, mCredentialTypeQuery);

    /**
     * Invalidate the credential cache
     * @hide
     */
    public final static void invalidateCredentialTypeCache() {
        PropertyInvalidatedCache.invalidateCache(PropertyInvalidatedCache.MODULE_SYSTEM,
                CREDENTIAL_TYPE_API);
    }

    /**
     * Returns the credential type of the user, can be one of {@link #CREDENTIAL_TYPE_NONE},
     * {@link #CREDENTIAL_TYPE_PATTERN}, {@link #CREDENTIAL_TYPE_PIN} and
     * {@link #CREDENTIAL_TYPE_PASSWORD}
     */
    public @CredentialType int getCredentialTypeForUser(int userHandle) {
        return mCredentialTypeCache.query(userHandle);
    }

    /**
     * @param userId the user for which to report the value
     * @return Whether the lock screen is secured.
     */
    @UnsupportedAppUsage
    public boolean isSecure(int userId) {
        int type = getCredentialTypeForUser(userId);
        return type != CREDENTIAL_TYPE_NONE;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isLockPasswordEnabled(int userId) {
        int type = getCredentialTypeForUser(userId);
        return type == CREDENTIAL_TYPE_PASSWORD || type == CREDENTIAL_TYPE_PIN;
    }

    /**
     * @return Whether the lock pattern is enabled
     */
    @UnsupportedAppUsage
    public boolean isLockPatternEnabled(int userId) {
        int type = getCredentialTypeForUser(userId);
        return type == CREDENTIAL_TYPE_PATTERN;
    }

    @Deprecated
    public boolean isLegacyLockPatternEnabled(int userId) {
        // Note: this value should default to {@code true} to avoid any reset that might result.
        // We must use a special key to read this value, since it will by default return the value
        // based on the new logic.
        return getBoolean(LEGACY_LOCK_PATTERN_ENABLED, true, userId);
    }

    @Deprecated
    public void setLegacyLockPatternEnabled(int userId) {
        setBoolean(Settings.Secure.LOCK_PATTERN_ENABLED, true, userId);
    }

    /**
     * @return Whether the visible pattern is enabled.
     */
    @UnsupportedAppUsage
    public boolean isVisiblePatternEnabled(int userId) {
        return getBoolean(Settings.Secure.LOCK_PATTERN_VISIBLE, false, userId);
    }

    /**
     * Set whether the visible pattern is enabled.
     */
    public void setVisiblePatternEnabled(boolean enabled, int userId) {
        setBoolean(Settings.Secure.LOCK_PATTERN_VISIBLE, enabled, userId);
    }

    public boolean isVisiblePatternEverChosen(int userId) {
        return getString(Settings.Secure.LOCK_PATTERN_VISIBLE, userId) != null;
    }

    /**
     * Set whether the visible password is enabled for cryptkeeper screen.
     */
    public void setVisiblePasswordEnabled(boolean enabled, int userId) {
        // No longer does anything.
    }

    /**
     * Set and store the lockout deadline, meaning the user can't attempt their unlock
     * pattern until the deadline has passed.
     * @return the chosen deadline.
     */
    @UnsupportedAppUsage
    public long setLockoutAttemptDeadline(int userId, int timeoutMs) {
        final long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        if (userId == USER_FRP) {
            // For secure password storage (that is required for FRP), the underlying storage also
            // enforces the deadline. Since we cannot store settings for the FRP user, don't.
            return deadline;
        }
        mLockoutDeadlines.put(userId, deadline);
        return deadline;
    }

    /**
     * @return The elapsed time in millis in the future when the user is allowed to
     *   attempt to enter their lock pattern, or 0 if the user is welcome to
     *   enter a pattern.
     */
    public long getLockoutAttemptDeadline(int userId) {
        final long deadline = mLockoutDeadlines.get(userId, 0L);
        final long now = SystemClock.elapsedRealtime();
        if (deadline < now && deadline != 0) {
            // timeout expired
            mLockoutDeadlines.put(userId, 0);
            return 0L;
        }
        return deadline;
    }

    private boolean getBoolean(String secureSettingKey, boolean defaultValue, int userId) {
        try {
            return getLockSettings().getBoolean(secureSettingKey, defaultValue, userId);
        } catch (RemoteException re) {
            return defaultValue;
        }
    }

    private void setBoolean(String secureSettingKey, boolean enabled, int userId) {
        try {
            getLockSettings().setBoolean(secureSettingKey, enabled, userId);
        } catch (RemoteException re) {
            // What can we do?
            Log.e(TAG, "Couldn't write boolean " + secureSettingKey + re);
        }
    }

    private long getLong(String secureSettingKey, long defaultValue, int userHandle) {
        try {
            return getLockSettings().getLong(secureSettingKey, defaultValue, userHandle);
        } catch (RemoteException re) {
            return defaultValue;
        }
    }

    @UnsupportedAppUsage
    private void setLong(String secureSettingKey, long value, int userHandle) {
        try {
            getLockSettings().setLong(secureSettingKey, value, userHandle);
        } catch (RemoteException re) {
            // What can we do?
            Log.e(TAG, "Couldn't write long " + secureSettingKey + re);
        }
    }

    @UnsupportedAppUsage
    private String getString(String secureSettingKey, int userHandle) {
        try {
            return getLockSettings().getString(secureSettingKey, null, userHandle);
        } catch (RemoteException re) {
            return null;
        }
    }

    @UnsupportedAppUsage
    private void setString(String secureSettingKey, String value, int userHandle) {
        try {
            getLockSettings().setString(secureSettingKey, value, userHandle);
        } catch (RemoteException re) {
            // What can we do?
            Log.e(TAG, "Couldn't write string " + secureSettingKey + re);
        }
    }

    public void setPowerButtonInstantlyLocks(boolean enabled, int userId) {
        setBoolean(LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS, enabled, userId);
    }

    @UnsupportedAppUsage
    public boolean getPowerButtonInstantlyLocks(int userId) {
        return getBoolean(LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS, true, userId);
    }

    public boolean isPowerButtonInstantlyLocksEverChosen(int userId) {
        return getString(LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS, userId) != null;
    }

    /** Updates the list of enabled trust agent in LockSettings storage for the given user. */
    public void setEnabledTrustAgents(Collection<ComponentName> activeTrustAgents, int userId) {
        setString(ENABLED_TRUST_AGENTS, serializeTrustAgents(activeTrustAgents), userId);
        getTrustManager().reportEnabledTrustAgentsChanged(userId);
    }

    /** Returns the list of enabled trust agent in LockSettings storage for the given user. */
    public List<ComponentName> getEnabledTrustAgents(int userId) {
        return deserializeTrustAgents(getString(ENABLED_TRUST_AGENTS, userId));
    }

    /** Updates the list of known trust agent in LockSettings storage for the given user. */
    public void setKnownTrustAgents(Collection<ComponentName> knownTrustAgents, int userId) {
        setString(KNOWN_TRUST_AGENTS, serializeTrustAgents(knownTrustAgents), userId);
    }

    /** Returns the list of known trust agent in LockSettings storage for the given user. */
    public List<ComponentName> getKnownTrustAgents(int userId) {
        return deserializeTrustAgents(getString(KNOWN_TRUST_AGENTS, userId));
    }

    private String serializeTrustAgents(Collection<ComponentName> trustAgents) {
        StringBuilder sb = new StringBuilder();
        for (ComponentName cn : trustAgents) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(cn.flattenToShortString());
        }
        return sb.toString();
    }

    private List<ComponentName> deserializeTrustAgents(String serializedTrustAgents) {
        if (TextUtils.isEmpty(serializedTrustAgents)) {
            return new ArrayList<>();
        }
        String[] split = serializedTrustAgents.split(",");
        ArrayList<ComponentName> trustAgents = new ArrayList<>(split.length);
        for (String s : split) {
            if (!TextUtils.isEmpty(s)) {
                trustAgents.add(ComponentName.unflattenFromString(s));
            }
        }
        return trustAgents;
    }

    /**
     * Disable trust until credentials have been entered for user {@code userId}.
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     *
     * @param userId either an explicit user id or {@link android.os.UserHandle#USER_ALL}
     */
    public void requireCredentialEntry(int userId) {
        requireStrongAuth(StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST, userId);
    }

    /**
     * Requests strong authentication for user {@code userId}.
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     *
     * @param strongAuthReason a combination of {@link StrongAuthTracker.StrongAuthFlags} indicating
     *                         the reason for and the strength of the requested authentication.
     * @param userId either an explicit user id or {@link android.os.UserHandle#USER_ALL}
     */
    public void requireStrongAuth(@StrongAuthTracker.StrongAuthFlags int strongAuthReason,
            int userId) {
        try {
            getLockSettings().requireStrongAuth(strongAuthReason, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error while requesting strong auth: " + e);
        }
    }

    private void reportEnabledTrustAgentsChanged(int userHandle) {
        getTrustManager().reportEnabledTrustAgentsChanged(userHandle);
    }

    public boolean isCredentialRequiredToDecrypt(boolean defaultValue) {
        final int value = Settings.Global.getInt(mContentResolver,
                Settings.Global.REQUIRE_PASSWORD_TO_DECRYPT, -1);
        return value == -1 ? defaultValue : (value != 0);
    }

    public void setCredentialRequiredToDecrypt(boolean required) {
        if (!(getUserManager().isSystemUser() || getUserManager().isPrimaryUser())) {
            throw new IllegalStateException(
                    "Only the system or primary user may call setCredentialRequiredForDecrypt()");
        }

        if (isDeviceEncryptionEnabled()){
            Settings.Global.putInt(mContext.getContentResolver(),
               Settings.Global.REQUIRE_PASSWORD_TO_DECRYPT, required ? 1 : 0);
        }
    }

    private void throwIfCalledOnMainThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            throw new IllegalStateException("should not be called from the main thread.");
        }
    }

    public void registerStrongAuthTracker(final StrongAuthTracker strongAuthTracker) {
        try {
            getLockSettings().registerStrongAuthTracker(strongAuthTracker.getStub());
        } catch (RemoteException e) {
            throw new RuntimeException("Could not register StrongAuthTracker");
        }
    }

    public void unregisterStrongAuthTracker(final StrongAuthTracker strongAuthTracker) {
        try {
            getLockSettings().unregisterStrongAuthTracker(strongAuthTracker.getStub());
        } catch (RemoteException e) {
            Log.e(TAG, "Could not unregister StrongAuthTracker", e);
        }
    }

    /** Register the given WeakEscrowTokenRemovedListener. */
    public boolean registerWeakEscrowTokenRemovedListener(
            @NonNull final IWeakEscrowTokenRemovedListener listener) {
        try {
            return getLockSettings().registerWeakEscrowTokenRemovedListener(listener);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not register WeakEscrowTokenRemovedListener.");
            throw e.rethrowFromSystemServer();
        }
    }

    /** Unregister the given WeakEscrowTokenRemovedListener. */
    public boolean unregisterWeakEscrowTokenRemovedListener(
            @NonNull final IWeakEscrowTokenRemovedListener listener) {
        try {
            return getLockSettings().unregisterWeakEscrowTokenRemovedListener(listener);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not register WeakEscrowTokenRemovedListener.");
            throw e.rethrowFromSystemServer();
        }
    }

    public void reportSuccessfulBiometricUnlock(boolean isStrongBiometric, int userId) {
        try {
            getLockSettings().reportSuccessfulBiometricUnlock(isStrongBiometric, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not report successful biometric unlock", e);
        }
    }

    public void scheduleNonStrongBiometricIdleTimeout(int userId) {
        try {
            getLockSettings().scheduleNonStrongBiometricIdleTimeout(userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not schedule non-strong biometric idle timeout", e);
        }
    }

    /**
     * @see StrongAuthTracker#getStrongAuthForUser
     */
    public int getStrongAuthForUser(int userId) {
        try {
            return getLockSettings().getStrongAuthForUser(userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not get StrongAuth", e);
            return StrongAuthTracker.getDefaultFlags(mContext);
        }
    }

    /**
     * Whether the user is not allowed to set any credentials via PASSWORD_QUALITY_MANAGED.
     */
    public boolean isCredentialsDisabledForUser(int userId) {
        return getDevicePolicyManager().getPasswordQuality(/* admin= */ null, userId)
                == PASSWORD_QUALITY_MANAGED;
    }

    /**
     * @see StrongAuthTracker#isTrustAllowedForUser
     */
    public boolean isTrustAllowedForUser(int userId) {
        return getStrongAuthForUser(userId) == StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED;
    }

    /**
     * @see StrongAuthTracker#isBiometricAllowedForUser(int)
     */
    public boolean isBiometricAllowedForUser(int userId) {
        return (getStrongAuthForUser(userId) & ~StrongAuthTracker.ALLOWING_BIOMETRIC) == 0;
    }

    public boolean isUserInLockdown(int userId) {
        return getStrongAuthForUser(userId)
                == StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN;
    }

    private static class WrappedCallback extends ICheckCredentialProgressCallback.Stub {

        private Handler mHandler;
        private CheckCredentialProgressCallback mCallback;

        WrappedCallback(Handler handler, CheckCredentialProgressCallback callback) {
            mHandler = handler;
            mCallback = callback;
        }

        @Override
        public void onCredentialVerified() throws RemoteException {
            if (mHandler == null) {
                Log.e(TAG, "Handler is null during callback");
            }
            // Kill reference immediately to allow early GC at client side independent of
            // when system_server decides to lose its reference to the
            // ICheckCredentialProgressCallback binder object.
            mHandler.post(() -> {
                mCallback.onEarlyMatched();
                mCallback = null;
            });
            mHandler = null;
        }
    }

    private ICheckCredentialProgressCallback wrapCallback(
            final CheckCredentialProgressCallback callback) {
        if (callback == null) {
            return null;
        } else {
            if (mHandler == null) {
                throw new IllegalStateException("Must construct LockPatternUtils on a looper thread"
                        + " to use progress callbacks.");
            }
            return new WrappedCallback(mHandler, callback);
        }
    }

    private LockSettingsInternal getLockSettingsInternal() {
        LockSettingsInternal service = LocalServices.getService(LockSettingsInternal.class);
        if (service == null) {
            throw new SecurityException("Only available to system server itself");
        }
        return service;
    }
    /**
     * Create an escrow token for the current user, which can later be used to unlock FBE
     * or change user password.
     *
     * After adding, if the user currently has lockscreen password, they will need to perform a
     * confirm credential operation in order to activate the token for future use. If the user
     * has no secure lockscreen, then the token is activated immediately.
     *
     * <p>This method is only available to code running in the system server process itself.
     *
     * @return a unique 64-bit token handle which is needed to refer to this token later.
     */
    public long addEscrowToken(byte[] token, int userId,
            @Nullable EscrowTokenStateChangeCallback callback) {
        return getLockSettingsInternal().addEscrowToken(token, userId, callback);
    }

    /**
     * Create a weak escrow token for the current user, which can later be used to unlock FBE
     * or change user password.
     *
     * After adding, if the user currently has lockscreen password, they will need to perform a
     * confirm credential operation in order to activate the token for future use. If the user
     * has no secure lockscreen, then the token is activated immediately.
     *
     * If the user changes or removes lockscreen password, activated weak escrow tokens will be
     * removed.
     *
     * @return a unique 64-bit token handle which is needed to refer to this token later.
     */
    public long addWeakEscrowToken(byte[] token, int userId,
            @NonNull IWeakEscrowTokenActivatedListener callback) {
        try {
            return getLockSettings().addWeakEscrowToken(token, userId, callback);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not add weak token.");
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Callback interface to notify when an added escrow token has been activated.
     */
    public interface EscrowTokenStateChangeCallback {
        /**
         * The method to be called when the token is activated.
         * @param handle 64 bit handle corresponding to the escrow token
         * @param userId user for whom the escrow token has been added
         */
        void onEscrowTokenActivated(long handle, int userId);
    }

    /**
     * Remove an escrow token.
     *
     * <p>This method is only available to code running in the system server process itself.
     *
     * @return true if the given handle refers to a valid token previously returned from
     * {@link #addEscrowToken}, whether it's active or not. return false otherwise.
     */
    public boolean removeEscrowToken(long handle, int userId) {
        return getLockSettingsInternal().removeEscrowToken(handle, userId);
    }

    /**
     * Remove a weak escrow token.
     *
     * @return true if the given handle refers to a valid weak token previously returned from
     * {@link #addWeakEscrowToken}, whether it's active or not. return false otherwise.
     */
    public boolean removeWeakEscrowToken(long handle, int userId) {
        try {
            return getLockSettings().removeWeakEscrowToken(handle, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not remove the weak token.");
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if the given escrow token is active or not. Only active token can be used to call
     * {@link #setLockCredentialWithToken} and {@link #unlockUserWithToken}
     *
     * <p>This method is only available to code running in the system server process itself.
     */
    public boolean isEscrowTokenActive(long handle, int userId) {
        return getLockSettingsInternal().isEscrowTokenActive(handle, userId);
    }

    /**
     * Check if the given weak escrow token is active or not. Only active token can be used to call
     * {@link #setLockCredentialWithToken} and {@link #unlockUserWithToken}
     */
    public boolean isWeakEscrowTokenActive(long handle, int userId) {
        try {
            return getLockSettings().isWeakEscrowTokenActive(handle, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not check the weak token.");
            throw e.rethrowFromSystemServer();
        }
    }

    /** Check if the given weak escrow token is valid. */
    public boolean isWeakEscrowTokenValid(long handle, byte[] token, int userId) {
        try {
            return getLockSettings().isWeakEscrowTokenValid(handle, token, userId);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not validate the weak token.");
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Change a user's lock credential with a pre-configured escrow token.
     *
     * <p>This method is only available to code running in the system server process itself.
     *
     * @param credential The new credential to be set
     * @param tokenHandle Handle of the escrow token
     * @param token Escrow token
     * @param userHandle The user who's lock credential to be changed
     * @return {@code true} if the operation is successful.
     */
    public boolean setLockCredentialWithToken(@NonNull LockscreenCredential credential,
            long tokenHandle, byte[] token, int userHandle) {
        if (!hasSecureLockScreen() && credential.getType() != CREDENTIAL_TYPE_NONE) {
            throw new UnsupportedOperationException(
                    "This operation requires the lock screen feature.");
        }
        credential.checkLength();
        LockSettingsInternal localService = getLockSettingsInternal();

        return localService.setLockCredentialWithToken(credential, tokenHandle, token, userHandle);
    }

    /**
     * Unlock the specified user by an pre-activated escrow token. This should have the same effect
     * on device encryption as the user entering their lockscreen credentials for the first time after
     * boot, this includes unlocking the user's credential-encrypted storage as well as the keystore
     *
     * <p>This method is only available to code running in the system server process itself.
     *
     * @return {@code true} if the supplied token is valid and unlock succeeds,
     *         {@code false} otherwise.
     */
    public boolean unlockUserWithToken(long tokenHandle, byte[] token, int userId) {
        return getLockSettingsInternal().unlockUserWithToken(tokenHandle, token, userId);
    }


    /**
     * Callback to be notified about progress when checking credentials.
     */
    public interface CheckCredentialProgressCallback {

        /**
         * Called as soon as possible when we know that the credentials match but the user hasn't
         * been fully unlocked.
         */
        void onEarlyMatched();
    }

    /**
     * Tracks the global strong authentication state.
     */
    public static class StrongAuthTracker {

        @IntDef(flag = true,
                value = { STRONG_AUTH_NOT_REQUIRED,
                        STRONG_AUTH_REQUIRED_AFTER_BOOT,
                        STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW,
                        SOME_AUTH_REQUIRED_AFTER_USER_REQUEST,
                        STRONG_AUTH_REQUIRED_AFTER_LOCKOUT,
                        STRONG_AUTH_REQUIRED_AFTER_TIMEOUT,
                        STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN,
                        STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT,
                        SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED})
        @Retention(RetentionPolicy.SOURCE)
        public @interface StrongAuthFlags {}

        /**
         * Strong authentication is not required.
         */
        public static final int STRONG_AUTH_NOT_REQUIRED = 0x0;

        /**
         * Strong authentication is required because the user has not authenticated since boot.
         */
        public static final int STRONG_AUTH_REQUIRED_AFTER_BOOT = 0x1;

        /**
         * Strong authentication is required because a device admin has requested it.
         */
        public static final int STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW = 0x2;

        /**
         * Some authentication is required because the user has temporarily disabled trust.
         */
        public static final int SOME_AUTH_REQUIRED_AFTER_USER_REQUEST = 0x4;

        /**
         * Strong authentication is required because the user has been locked out after too many
         * attempts.
         */
        public static final int STRONG_AUTH_REQUIRED_AFTER_LOCKOUT = 0x8;

        /**
         * Strong authentication is required because it hasn't been used for a time required by
         * a device admin.
         */
        public static final int STRONG_AUTH_REQUIRED_AFTER_TIMEOUT = 0x10;

        /**
         * Strong authentication is required because the user has triggered lockdown.
         */
        public static final int STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN = 0x20;

        /**
         * Strong authentication is required to prepare for unattended upgrade.
         */
        public static final int STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE = 0x40;

        /**
         * Strong authentication is required because it hasn't been used for a time after a
         * non-strong biometric (i.e. weak or convenience biometric) is used to unlock device.
         */
        public static final int STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT = 0x80;

        /**
         * Some authentication is required because the trustagent either timed out or was disabled
         * manually.
         */
        public static final int SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED = 0x100;

        /**
         * Strong auth flags that do not prevent biometric methods from being accepted as auth.
         * If any other flags are set, biometric authentication is disabled.
         */
        private static final int ALLOWING_BIOMETRIC = STRONG_AUTH_NOT_REQUIRED
                | SOME_AUTH_REQUIRED_AFTER_USER_REQUEST
                | SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED;

        private final SparseIntArray mStrongAuthRequiredForUser = new SparseIntArray();
        private final H mHandler;
        private final int mDefaultStrongAuthFlags;

        private final SparseBooleanArray mIsNonStrongBiometricAllowedForUser =
                new SparseBooleanArray();
        private final boolean mDefaultIsNonStrongBiometricAllowed = true;

        public StrongAuthTracker(Context context) {
            this(context, Looper.myLooper());
        }

        /**
         * @param looper the looper on whose thread calls to {@link #onStrongAuthRequiredChanged}
         *               will be scheduled.
         * @param context the current {@link Context}
         */
        public StrongAuthTracker(Context context, Looper looper) {
            mHandler = new H(looper);
            mDefaultStrongAuthFlags = getDefaultFlags(context);
        }

        public static @StrongAuthFlags int getDefaultFlags(Context context) {
            boolean strongAuthRequired = context.getResources().getBoolean(
                    com.android.internal.R.bool.config_strongAuthRequiredOnBoot);
            return strongAuthRequired ? STRONG_AUTH_REQUIRED_AFTER_BOOT : STRONG_AUTH_NOT_REQUIRED;
        }

        /**
         * Returns {@link #STRONG_AUTH_NOT_REQUIRED} if strong authentication is not required,
         * otherwise returns a combination of {@link StrongAuthFlags} indicating why strong
         * authentication is required.
         *
         * @param userId the user for whom the state is queried.
         */
        public @StrongAuthFlags int getStrongAuthForUser(int userId) {
            return mStrongAuthRequiredForUser.get(userId, mDefaultStrongAuthFlags);
        }

        /**
         * @return true if unlocking with trust alone is allowed for {@code userId} by the current
         * strong authentication requirements.
         */
        public boolean isTrustAllowedForUser(int userId) {
            return getStrongAuthForUser(userId) == STRONG_AUTH_NOT_REQUIRED;
        }

        /**
         * @return true if unlocking with a biometric method alone is allowed for {@code userId}
         * by the current strong authentication requirements.
         */
        public boolean isBiometricAllowedForUser(boolean isStrongBiometric, int userId) {
            boolean allowed = ((getStrongAuthForUser(userId) & ~ALLOWING_BIOMETRIC) == 0);
            if (!isStrongBiometric) {
                allowed &= isNonStrongBiometricAllowedAfterIdleTimeout(userId);
            }
            return allowed;
        }

        /**
         * @return true if unlocking with a non-strong (i.e. weak or convenience) biometric method
         * alone is allowed for {@code userId}, otherwise returns false.
         */
        public boolean isNonStrongBiometricAllowedAfterIdleTimeout(int userId) {
            return mIsNonStrongBiometricAllowedForUser.get(userId,
                    mDefaultIsNonStrongBiometricAllowed);
        }

        /**
         * Called when the strong authentication requirements for {@code userId} changed.
         */
        public void onStrongAuthRequiredChanged(int userId) {
        }

        /**
         * Called when whether non-strong biometric is allowed for {@code userId} changed.
         */
        public void onIsNonStrongBiometricAllowedChanged(int userId) {
        }

        protected void handleStrongAuthRequiredChanged(@StrongAuthFlags int strongAuthFlags,
                int userId) {
            int oldValue = getStrongAuthForUser(userId);
            if (strongAuthFlags != oldValue) {
                if (strongAuthFlags == mDefaultStrongAuthFlags) {
                    mStrongAuthRequiredForUser.delete(userId);
                } else {
                    mStrongAuthRequiredForUser.put(userId, strongAuthFlags);
                }
                onStrongAuthRequiredChanged(userId);
            }
        }

        protected void handleIsNonStrongBiometricAllowedChanged(boolean allowed,
                int userId) {
            boolean oldValue = isNonStrongBiometricAllowedAfterIdleTimeout(userId);
            if (allowed != oldValue) {
                if (allowed == mDefaultIsNonStrongBiometricAllowed) {
                    mIsNonStrongBiometricAllowedForUser.delete(userId);
                } else {
                    mIsNonStrongBiometricAllowedForUser.put(userId, allowed);
                }
                onIsNonStrongBiometricAllowedChanged(userId);
            }
        }

        private final IStrongAuthTracker.Stub mStub = new IStrongAuthTracker.Stub() {
            @Override
            public void onStrongAuthRequiredChanged(@StrongAuthFlags int strongAuthFlags,
                    int userId) {
                mHandler.obtainMessage(H.MSG_ON_STRONG_AUTH_REQUIRED_CHANGED,
                        strongAuthFlags, userId).sendToTarget();
            }

            @Override
            public void onIsNonStrongBiometricAllowedChanged(boolean allowed, int userId) {
                mHandler.obtainMessage(H.MSG_ON_IS_NON_STRONG_BIOMETRIC_ALLOWED_CHANGED,
                        allowed ? 1 : 0, userId).sendToTarget();
            }
        };

        public IStrongAuthTracker.Stub getStub() {
            return mStub;
        }

        private class H extends Handler {
            static final int MSG_ON_STRONG_AUTH_REQUIRED_CHANGED = 1;
            static final int MSG_ON_IS_NON_STRONG_BIOMETRIC_ALLOWED_CHANGED = 2;

            public H(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_ON_STRONG_AUTH_REQUIRED_CHANGED:
                        handleStrongAuthRequiredChanged(msg.arg1, msg.arg2);
                        break;
                    case MSG_ON_IS_NON_STRONG_BIOMETRIC_ALLOWED_CHANGED:
                        handleIsNonStrongBiometricAllowedChanged(msg.arg1 == 1 /* allowed */,
                                msg.arg2);
                        break;
                }
            }
        }
    }

    public void enableSyntheticPassword() {
        setLong(SYNTHETIC_PASSWORD_ENABLED_KEY, 1L, UserHandle.USER_SYSTEM);
    }

    public void disableSyntheticPassword() {
        setLong(SYNTHETIC_PASSWORD_ENABLED_KEY, 0L, UserHandle.USER_SYSTEM);
    }

    public boolean isSyntheticPasswordEnabled() {
        return getLong(SYNTHETIC_PASSWORD_ENABLED_KEY, SYNTHETIC_PASSWORD_ENABLED_BY_DEFAULT,
                UserHandle.USER_SYSTEM) != 0;
    }

    /**
     * Returns whether the given user has pending escrow tokens
     */
    public boolean hasPendingEscrowToken(int userId) {
        try {
            return getLockSettings().hasPendingEscrowToken(userId);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Return true if the device supports the lock screen feature, false otherwise.
     */
    public boolean hasSecureLockScreen() {
        if (mHasSecureLockScreen == null) {
            try {
                mHasSecureLockScreen = Boolean.valueOf(getLockSettings().hasSecureLockScreen());
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
        return mHasSecureLockScreen.booleanValue();
    }

    public static boolean userOwnsFrpCredential(Context context, UserInfo info) {
        return info != null && info.isPrimary() && info.isAdmin() && frpCredentialEnabled(context);
    }

    public static boolean frpCredentialEnabled(Context context) {
        return FRP_CREDENTIAL_ENABLED && context.getResources().getBoolean(
                com.android.internal.R.bool.config_enableCredentialFactoryResetProtection);
    }

    /**
     * Attempt to rederive the unified work challenge for the specified profile user and unlock the
     * user. If successful, this would allow the user to leave quiet mode automatically without
     * additional user authentication.
     *
     * This is made possible by the framework storing an encrypted copy of the unified challenge
     * auth-bound to the primary user's lockscreen. As long as the primery user has unlocked
     * recently (7 days), the framework will be able to decrypt it and plug the secret into the
     * unlock flow.
     *
     * @return {@code true} if automatic unlocking is successful, {@code false} otherwise.
     */
    public boolean tryUnlockWithCachedUnifiedChallenge(int userId) {
        try {
            return getLockSettings().tryUnlockWithCachedUnifiedChallenge(userId);
        } catch (RemoteException re) {
            return false;
        }
    }

    /** Remove cached unified profile challenge, for testing and CTS usage. */
    public void removeCachedUnifiedChallenge(int userId) {
        try {
            getLockSettings().removeCachedUnifiedChallenge(userId);
        } catch (RemoteException re) {
            re.rethrowFromSystemServer();
        }
    }
}
