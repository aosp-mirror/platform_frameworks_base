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
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_MANAGED;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
import static android.app.admin.DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.admin.DevicePolicyManager;
import android.app.admin.PasswordMetrics;
import android.app.trust.IStrongAuthTracker;
import android.app.trust.TrustManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;
import android.util.SparseLongArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;

import com.google.android.collect.Lists;

import libcore.util.HexEncoding;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;

/**
 * Utilities for the lock pattern and its settings.
 */
public class LockPatternUtils {

    private static final String TAG = "LockPatternUtils";
    private static final boolean DEBUG = false;
    private static final boolean FRP_CREDENTIAL_ENABLED = true;

    /**
     * The key to identify when the lock pattern enabled flag is being accessed for legacy reasons.
     */
    public static final String LEGACY_LOCK_PATTERN_ENABLED = "legacy_lock_pattern_enabled";

    /**
     * The number of incorrect attempts before which we fall back on an alternative
     * method of verifying the user, and resetting their lock pattern.
     */
    public static final int FAILED_ATTEMPTS_BEFORE_RESET = 20;

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
     * The minimum number of dots the user must include in a wrong pattern
     * attempt for it to be counted against the counts that affect
     * {@link #FAILED_ATTEMPTS_BEFORE_TIMEOUT} and {@link #FAILED_ATTEMPTS_BEFORE_RESET}
     */
    public static final int MIN_PATTERN_REGISTER_FAIL = MIN_LOCK_PATTERN_SIZE;

    public static final int CREDENTIAL_TYPE_NONE = -1;

    public static final int CREDENTIAL_TYPE_PATTERN = 1;

    public static final int CREDENTIAL_TYPE_PASSWORD = 2;

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
    private static final String IS_TRUST_USUALLY_MANAGED = "lockscreen.istrustusuallymanaged";

    public static final String PROFILE_KEY_NAME_ENCRYPT = "profile_key_name_encrypt_";
    public static final String PROFILE_KEY_NAME_DECRYPT = "profile_key_name_decrypt_";
    public static final String SYNTHETIC_PASSWORD_KEY_PREFIX = "synthetic_password_";

    public static final String SYNTHETIC_PASSWORD_HANDLE_KEY = "sp-handle";
    public static final String SYNTHETIC_PASSWORD_ENABLED_KEY = "enable-sp";
    private static final String HISTORY_DELIMITER = ",";

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private DevicePolicyManager mDevicePolicyManager;
    private ILockSettings mLockSettingsService;
    private UserManager mUserManager;
    private final Handler mHandler;
    private final SparseLongArray mLockoutDeadlines = new SparseLongArray();
    private Boolean mHasSecureLockScreen;

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
        public RequestThrottledException(int timeoutMs) {
            mTimeoutMs = timeoutMs;
        }

        /**
         * @return The amount of time in ms before another request may
         * be executed
         */
        public int getTimeoutMs() {
            return mTimeoutMs;
        }

    }

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

    private TrustManager getTrustManager() {
        TrustManager trust = (TrustManager) mContext.getSystemService(Context.TRUST_SERVICE);
        if (trust == null) {
            Log.e(TAG, "Can't get TrustManagerService: is it running?",
                    new IllegalStateException("Stack trace:"));
        }
        return trust;
    }

    public LockPatternUtils(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();

        Looper looper = Looper.myLooper();
        mHandler = looper != null ? new Handler(looper) : null;
    }

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
     * Gets the device policy password mode. If the mode is non-specific, returns
     * MODE_PATTERN which allows the user to choose anything.
     */
    public int getRequestedPasswordQuality(int userId) {
        return getDevicePolicyManager().getPasswordQuality(null, userId);
    }

    private int getRequestedPasswordHistoryLength(int userId) {
        return getDevicePolicyManager().getPasswordHistoryLength(null, userId);
    }

    public int getRequestedPasswordMinimumLetters(int userId) {
        return getDevicePolicyManager().getPasswordMinimumLetters(null, userId);
    }

    public int getRequestedPasswordMinimumUpperCase(int userId) {
        return getDevicePolicyManager().getPasswordMinimumUpperCase(null, userId);
    }

    public int getRequestedPasswordMinimumLowerCase(int userId) {
        return getDevicePolicyManager().getPasswordMinimumLowerCase(null, userId);
    }

    public int getRequestedPasswordMinimumNumeric(int userId) {
        return getDevicePolicyManager().getPasswordMinimumNumeric(null, userId);
    }

    public int getRequestedPasswordMinimumSymbols(int userId) {
        return getDevicePolicyManager().getPasswordMinimumSymbols(null, userId);
    }

    public int getRequestedPasswordMinimumNonLetter(int userId) {
        return getDevicePolicyManager().getPasswordMinimumNonLetter(null, userId);
    }

    public void reportFailedPasswordAttempt(int userId) {
        if (userId == USER_FRP && frpCredentialEnabled(mContext)) {
            return;
        }
        getDevicePolicyManager().reportFailedPasswordAttempt(userId);
        getTrustManager().reportUnlockAttempt(false /* authenticated */, userId);
    }

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

    private byte[] verifyCredential(byte[] credential, int type, long challenge, int userId)
            throws RequestThrottledException {
        try {
            VerifyCredentialResponse response = getLockSettings().verifyCredential(credential,
                    type, challenge, userId);
            if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
                return response.getPayload();
            } else if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
                throw new RequestThrottledException(response.getTimeout());
            } else {
                return null;
            }
        } catch (RemoteException re) {
            return null;
        }
    }

    private boolean checkCredential(byte[] credential, int type, int userId,
            @Nullable CheckCredentialProgressCallback progressCallback)
            throws RequestThrottledException {
        try {
            VerifyCredentialResponse response = getLockSettings().checkCredential(credential, type,
                    userId, wrapCallback(progressCallback));

            if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
                return true;
            } else if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
                throw new RequestThrottledException(response.getTimeout());
            } else {
                return false;
            }
        } catch (RemoteException re) {
            return false;
        }
    }

    /**
     * Check to see if a pattern matches the saved pattern.
     * If pattern matches, return an opaque attestation that the challenge
     * was verified.
     *
     * @param pattern The pattern to check.
     * @param challenge The challenge to verify against the pattern
     * @return the attestation that the challenge was verified, or null.
     */
    public byte[] verifyPattern(List<LockPatternView.Cell> pattern, long challenge, int userId)
            throws RequestThrottledException {
        throwIfCalledOnMainThread();
        return verifyCredential(patternToByteArray(pattern), CREDENTIAL_TYPE_PATTERN, challenge,
                userId);
    }

    /**
     * Check to see if a pattern matches the saved pattern.  If no pattern exists,
     * always returns true.
     * @param pattern The pattern to check.
     * @return Whether the pattern matches the stored one.
     */
    public boolean checkPattern(List<LockPatternView.Cell> pattern, int userId)
            throws RequestThrottledException {
        return checkPattern(pattern, userId, null /* progressCallback */);
    }

    /**
     * Check to see if a pattern matches the saved pattern.  If no pattern exists,
     * always returns true.
     * @param pattern The pattern to check.
     * @return Whether the pattern matches the stored one.
     */
    public boolean checkPattern(List<LockPatternView.Cell> pattern, int userId,
            @Nullable CheckCredentialProgressCallback progressCallback)
            throws RequestThrottledException {
        throwIfCalledOnMainThread();
        return checkCredential(patternToByteArray(pattern), CREDENTIAL_TYPE_PATTERN, userId,
                progressCallback);
    }

    /**
     * Check to see if a password matches the saved password.
     * If password matches, return an opaque attestation that the challenge
     * was verified.
     *
     * @param password The password to check.
     * @param challenge The challenge to verify against the password
     * @return the attestation that the challenge was verified, or null.
     */
    public byte[] verifyPassword(byte[] password, long challenge, int userId)
            throws RequestThrottledException {
        throwIfCalledOnMainThread();
        return verifyCredential(password, CREDENTIAL_TYPE_PASSWORD, challenge, userId);
    }


    /**
     * Check to see if a password matches the saved password.
     * If password matches, return an opaque attestation that the challenge
     * was verified.
     *
     * @param password The password to check.
     * @param challenge The challenge to verify against the password
     * @return the attestation that the challenge was verified, or null.
     */
    public byte[] verifyTiedProfileChallenge(byte[] password, boolean isPattern, long challenge,
            int userId) throws RequestThrottledException {
        throwIfCalledOnMainThread();
        try {
            VerifyCredentialResponse response =
                    getLockSettings().verifyTiedProfileChallenge(password,
                            isPattern ? CREDENTIAL_TYPE_PATTERN : CREDENTIAL_TYPE_PASSWORD, challenge,
                            userId);

            if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
                return response.getPayload();
            } else if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
                throw new RequestThrottledException(response.getTimeout());
            } else {
                return null;
            }
        } catch (RemoteException re) {
            return null;
        }
    }

    /**
     *
     * Check to see if a password matches the saved password.  If no password exists,
     * always returns true.
     * @param password The password to check.
     * @return Whether the password matches the stored one.
     */
    public boolean checkPassword(String password, int userId) throws RequestThrottledException {
        byte[] passwordBytes = password != null ? password.getBytes() : null;
        return checkPassword(passwordBytes, userId, null /* progressCallback */);
    }


    /**
     *
     * Check to see if a password matches the saved password.  If no password exists,
     * always returns true.
     * @param password The password to check.
     * @return Whether the password matches the stored one.
     */
    public boolean checkPassword(byte[] password, int userId) throws RequestThrottledException {
        return checkPassword(password, userId, null /* progressCallback */);
    }

    // TODO(b/120484642): This method is necessary for vendor/qcom code and is a hidden api
    /* *
     * Check to see if a password matches the saved password.  If no password exists,
     * always returns true.
     * @param password The password to check.
     * @return Whether the password matches the stored one.
     */
    public boolean checkPassword(String password, int userId,
            @Nullable CheckCredentialProgressCallback progressCallback)
            throws RequestThrottledException {
        byte[] passwordBytes = password != null ? password.getBytes() : null;
        throwIfCalledOnMainThread();
        return checkCredential(passwordBytes, CREDENTIAL_TYPE_PASSWORD, userId, progressCallback);

    }

    /**
     * Check to see if a password matches the saved password.  If no password exists,
     * always returns true.
     * @param password The password to check.
     * @return Whether the password matches the stored one.
     */

    public boolean checkPassword(byte[] password, int userId,
            @Nullable CheckCredentialProgressCallback progressCallback)
            throws RequestThrottledException {
        throwIfCalledOnMainThread();
        return checkCredential(password, CREDENTIAL_TYPE_PASSWORD, userId, progressCallback);
    }

    /**
     * Check to see if vold already has the password.
     * Note that this also clears vold's copy of the password.
     * @return Whether the vold password matches or not.
     */
    public boolean checkVoldPassword(int userId) {
        try {
            return getLockSettings().checkVoldPassword(userId);
        } catch (RemoteException re) {
            return false;
        }
    }

    /**
     * Returns the password history hash factor, needed to check new password against password
     * history with {@link #checkPasswordHistory(String, byte[], int)}
     */
    public byte[] getPasswordHistoryHashFactor(byte[] currentPassword, int userId) {
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
        String legacyHash = legacyPasswordToHash(passwordToCheck, userId);
        String passwordHash = passwordToHistoryHash(passwordToCheck, hashFactor, userId);
        String[] history = passwordHistory.split(HISTORY_DELIMITER);
        // Password History may be too long...
        for (int i = 0; i < Math.min(passwordHistoryLength, history.length); i++) {
            if (history[i].equals(legacyHash) || history[i].equals(passwordHash)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check to see if the user has stored a lock pattern.
     * @return Whether a saved pattern exists.
     */
    private boolean savedPatternExists(int userId) {
        try {
            return getLockSettings().havePattern(userId);
        } catch (RemoteException re) {
            return false;
        }
    }

    /**
     * Check to see if the user has stored a lock pattern.
     * @return Whether a saved pattern exists.
     */
    private boolean savedPasswordExists(int userId) {
        try {
            return getLockSettings().havePassword(userId);
        } catch (RemoteException re) {
            return false;
        }
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
     */
    public int getActivePasswordQuality(int userId) {
        int quality = getKeyguardStoredPasswordQuality(userId);

        if (isLockPasswordEnabled(quality, userId)) {
            // Quality is a password and a password exists. Return the quality.
            return quality;
        }

        if (isLockPatternEnabled(quality, userId)) {
            // Quality is a pattern and a pattern exists. Return the quality.
            return quality;
        }

        return PASSWORD_QUALITY_UNSPECIFIED;
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
     * Clear any lock pattern or password.
     */
    public void clearLock(byte[] savedCredential, int userHandle) {
        final int currentQuality = getKeyguardStoredPasswordQuality(userHandle);
        setKeyguardStoredPasswordQuality(PASSWORD_QUALITY_UNSPECIFIED, userHandle);

        try{
            getLockSettings().setLockCredential(null, CREDENTIAL_TYPE_NONE,
                    savedCredential, PASSWORD_QUALITY_UNSPECIFIED, userHandle);
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear lock", e);
            setKeyguardStoredPasswordQuality(currentQuality, userHandle);
            return;
        }

        if (userHandle == UserHandle.USER_SYSTEM) {
            // Set the encryption password to default.
            updateEncryptionPassword(StorageManager.CRYPT_TYPE_DEFAULT, null);
            setCredentialRequiredToDecrypt(false);
        }

        onAfterChangingPassword(userHandle);
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

    /**
     * Save a lock pattern.
     * @param pattern The new pattern to save.
     * @param userId the user whose pattern is to be saved.
     */
    public void saveLockPattern(List<LockPatternView.Cell> pattern, int userId) {
        this.saveLockPattern(pattern, null, userId);
    }
    /**
     * Save a lock pattern.
     * @param pattern The new pattern to save.
     * @param savedPattern The previously saved pattern, converted to byte[] format
     * @param userId the user whose pattern is to be saved.
     */
    public void saveLockPattern(List<LockPatternView.Cell> pattern, byte[] savedPattern,
            int userId) {
        if (!hasSecureLockScreen()) {
            throw new UnsupportedOperationException(
                    "This operation requires the lock screen feature.");
        }
        if (pattern == null || pattern.size() < MIN_LOCK_PATTERN_SIZE) {
            throw new IllegalArgumentException("pattern must not be null and at least "
                    + MIN_LOCK_PATTERN_SIZE + " dots long.");
        }

        final byte[] bytePattern = patternToByteArray(pattern);
        final int currentQuality = getKeyguardStoredPasswordQuality(userId);
        setKeyguardStoredPasswordQuality(PASSWORD_QUALITY_SOMETHING, userId);
        try {
            getLockSettings().setLockCredential(bytePattern, CREDENTIAL_TYPE_PATTERN, savedPattern,
                    PASSWORD_QUALITY_SOMETHING, userId);
        } catch (Exception e) {
            Log.e(TAG, "Couldn't save lock pattern", e);
            setKeyguardStoredPasswordQuality(currentQuality, userId);
            return;
        }
        // Update the device encryption password.
        if (userId == UserHandle.USER_SYSTEM
                && LockPatternUtils.isDeviceEncryptionEnabled()) {
            if (!shouldEncryptWithCredentials(true)) {
                clearEncryptionPassword();
            } else {
                updateEncryptionPassword(StorageManager.CRYPT_TYPE_PATTERN, bytePattern);
            }
        }

        reportPatternWasChosen(userId);
        onAfterChangingPassword(userId);
    }

    private void updateCryptoUserInfo(int userId) {
        if (userId != UserHandle.USER_SYSTEM) {
            return;
        }

        final String ownerInfo = isOwnerInfoEnabled(userId) ? getOwnerInfo(userId) : "";

        IBinder service = ServiceManager.getService("mount");
        if (service == null) {
            Log.e(TAG, "Could not find the mount service to update the user info");
            return;
        }

        IStorageManager storageManager = IStorageManager.Stub.asInterface(service);
        try {
            Log.d(TAG, "Setting owner info");
            storageManager.setField(StorageManager.OWNER_INFO_KEY, ownerInfo);
        } catch (RemoteException e) {
            Log.e(TAG, "Error changing user info", e);
        }
    }

    public void setOwnerInfo(String info, int userId) {
        setString(LOCK_SCREEN_OWNER_INFO, info, userId);
        updateCryptoUserInfo(userId);
    }

    public void setOwnerInfoEnabled(boolean enabled, int userId) {
        setBoolean(LOCK_SCREEN_OWNER_INFO_ENABLED, enabled, userId);
        updateCryptoUserInfo(userId);
    }

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

    /** Update the encryption password if it is enabled **/
    private void updateEncryptionPassword(final int type, final byte[] password) {
        if (!hasSecureLockScreen()) {
            throw new UnsupportedOperationException(
                    "This operation requires the lock screen feature.");
        }
        if (!isDeviceEncryptionEnabled()) {
            return;
        }
        final IBinder service = ServiceManager.getService("mount");
        if (service == null) {
            Log.e(TAG, "Could not find the mount service to update the encryption password");
            return;
        }

        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... dummy) {
                IStorageManager storageManager = IStorageManager.Stub.asInterface(service);
                try {
                    // TODO(b/120484642): This is a location where we still use a String for vold
                    String passwordString = password != null ? new String(password) : null;
                    storageManager.changeEncryptionPassword(type, passwordString);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error changing encryption password", e);
                }
                return null;
            }
        }.execute();
    }

    /**
     * Save a lock password.  Does not ensure that the password is as good
     * as the requested mode, but will adjust the mode to be as good as the
     * password.
     * @param password The password to save
     * @param savedPassword The previously saved lock password, or null if none
     * @param requestedQuality {@see DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     * @param userHandle The userId of the user to change the password for
     *
     * @deprecated Pass password as a byte array
     */
    @Deprecated
    public void saveLockPassword(String password, String savedPassword, int requestedQuality,
            int userHandle) {
        byte[] passwordBytes = password != null ? password.getBytes() : null;
        byte[] savedPasswordBytes = savedPassword != null ? savedPassword.getBytes() : null;
        saveLockPassword(passwordBytes, savedPasswordBytes, requestedQuality, userHandle);
    }

    /**
     * Save a lock password.  Does not ensure that the password is as good
     * as the requested mode, but will adjust the mode to be as good as the
     * password.
     * @param password The password to save
     * @param savedPassword The previously saved lock password, or null if none
     * @param requestedQuality {@see DevicePolicyManager#getPasswordQuality(
     *          android.content.ComponentName)}
     * @param userHandle The userId of the user to change the password for
     */
    public void saveLockPassword(byte[] password, byte[] savedPassword, int requestedQuality,
            int userHandle) {
        if (!hasSecureLockScreen()) {
            throw new UnsupportedOperationException(
                    "This operation requires the lock screen feature.");
        }
        if (password == null || password.length < MIN_LOCK_PASSWORD_SIZE) {
            throw new IllegalArgumentException("password must not be null and at least "
                    + "of length " + MIN_LOCK_PASSWORD_SIZE);
        }

        if (requestedQuality < PASSWORD_QUALITY_NUMERIC) {
            throw new IllegalArgumentException("quality must be at least NUMERIC, but was "
                    + requestedQuality);
        }

        final int currentQuality = getKeyguardStoredPasswordQuality(userHandle);
        setKeyguardStoredPasswordQuality(
                computePasswordQuality(CREDENTIAL_TYPE_PASSWORD, password, requestedQuality),
                userHandle);
        try {
            getLockSettings().setLockCredential(password, CREDENTIAL_TYPE_PASSWORD, savedPassword,
                    requestedQuality, userHandle);
        } catch (Exception e) {
            Log.e(TAG, "Unable to save lock password", e);
            setKeyguardStoredPasswordQuality(currentQuality, userHandle);
            return;
        }

        updateEncryptionPasswordIfNeeded(password,
                PasswordMetrics.computeForPassword(password).quality, userHandle);
        updatePasswordHistory(password, userHandle);
        onAfterChangingPassword(userHandle);
    }

    /**
     * Update device encryption password if calling user is USER_SYSTEM and device supports
     * encryption.
     */
    private void updateEncryptionPasswordIfNeeded(byte[] password, int quality, int userHandle) {
        // Update the device encryption password.
        if (userHandle == UserHandle.USER_SYSTEM
                && LockPatternUtils.isDeviceEncryptionEnabled()) {
            if (!shouldEncryptWithCredentials(true)) {
                clearEncryptionPassword();
            } else {
                boolean numeric = quality == PASSWORD_QUALITY_NUMERIC;
                boolean numericComplex = quality == PASSWORD_QUALITY_NUMERIC_COMPLEX;
                int type = numeric || numericComplex ? StorageManager.CRYPT_TYPE_PIN
                        : StorageManager.CRYPT_TYPE_PASSWORD;
                updateEncryptionPassword(type, password);
            }
        }
    }

    /**
     * Store the hash of the *current* password in the password history list, if device policy
     * enforces password history requirement.
     */
    private void updatePasswordHistory(byte[] password, int userHandle) {
        if (password == null || password.length == 0) {
            Log.e(TAG, "checkPasswordHistory: empty password");
            return;
        }
        // Add the password to the password history. We assume all
        // password hashes have the same length for simplicity of implementation.
        String passwordHistory = getString(PASSWORD_HISTORY_KEY, userHandle);
        if (passwordHistory == null) {
            passwordHistory = "";
        }
        int passwordHistoryLength = getRequestedPasswordHistoryLength(userHandle);
        if (passwordHistoryLength == 0) {
            passwordHistory = "";
        } else {
            final byte[] hashFactor = getPasswordHistoryHashFactor(password, userHandle);
            String hash = passwordToHistoryHash(password, hashFactor, userHandle);
            if (hash == null) {
                Log.e(TAG, "Compute new style password hash failed, fallback to legacy style");
                hash = legacyPasswordToHash(password, userHandle);
            }
            if (TextUtils.isEmpty(passwordHistory)) {
                passwordHistory = hash;
            } else {
                String[] history = passwordHistory.split(HISTORY_DELIMITER);
                StringJoiner joiner = new StringJoiner(HISTORY_DELIMITER);
                joiner.add(hash);
                for (int i = 0; i < passwordHistoryLength - 1 && i < history.length; i++) {
                    joiner.add(history[i]);
                }
                passwordHistory = joiner.toString();
            }
        }
        setString(PASSWORD_HISTORY_KEY, passwordHistory, userHandle);
    }

    /**
     * Determine if the device supports encryption, even if it's set to default. This
     * differs from isDeviceEncrypted() in that it returns true even if the device is
     * encrypted with the default password.
     * @return true if device encryption is enabled
     */
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
     * Clears the encryption password.
     */
    public void clearEncryptionPassword() {
        updateEncryptionPassword(StorageManager.CRYPT_TYPE_DEFAULT, null);
    }

    /**
     * Retrieves the quality mode for {@param userHandle}.
     * {@see DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     *
     * @return stored password quality
     */
    public int getKeyguardStoredPasswordQuality(int userHandle) {
        return (int) getLong(PASSWORD_TYPE_KEY, PASSWORD_QUALITY_UNSPECIFIED, userHandle);
    }

    private void setKeyguardStoredPasswordQuality(int quality, int userHandle) {
        setLong(PASSWORD_TYPE_KEY, quality, userHandle);
    }

    /**
     * Returns the password quality of the given credential, promoting it to a higher level
     * if DevicePolicyManager has a stronger quality requirement. This value will be written
     * to PASSWORD_TYPE_KEY.
     */
    private int computePasswordQuality(int type, byte[] credential, int requestedQuality) {
        final int quality;
        if (type == CREDENTIAL_TYPE_PASSWORD) {
            int computedQuality = PasswordMetrics.computeForPassword(credential).quality;
            quality = Math.max(requestedQuality, computedQuality);
        } else if (type == CREDENTIAL_TYPE_PATTERN)  {
            quality = PASSWORD_QUALITY_SOMETHING;
        } else /* if (type == CREDENTIAL_TYPE_NONE) */ {
            quality = PASSWORD_QUALITY_UNSPECIFIED;
        }
        return quality;
    }

    /**
     * Enables/disables the Separate Profile Challenge for this {@param userHandle}. This is a no-op
     * for user handles that do not belong to a managed profile.
     *
     * @param userHandle Managed profile user id
     * @param enabled True if separate challenge is enabled
     * @param managedUserPassword Managed profile previous password. Null when {@param enabled} is
     *            true
     */
    public void setSeparateProfileChallengeEnabled(int userHandle, boolean enabled,
            byte[] managedUserPassword) {
        if (!isManagedProfile(userHandle)) {
            return;
        }
        try {
            getLockSettings().setSeparateProfileChallengeEnabled(userHandle, enabled,
                    managedUserPassword);
            onAfterChangingPassword(userHandle);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't update work profile challenge enabled");
        }
    }

    /**
     * Returns true if {@param userHandle} is a managed profile with separate challenge.
     */
    public boolean isSeparateProfileChallengeEnabled(int userHandle) {
        return isManagedProfile(userHandle) && hasSeparateChallenge(userHandle);
    }

    /**
     * Returns true if {@param userHandle} is a managed profile with unified challenge.
     */
    public boolean isManagedProfileWithUnifiedChallenge(int userHandle) {
        return isManagedProfile(userHandle) && !hasSeparateChallenge(userHandle);
    }

    /**
     * Retrieves whether the current DPM allows use of the Profile Challenge.
     */
    public boolean isSeparateProfileChallengeAllowed(int userHandle) {
        return isManagedProfile(userHandle)
                && getDevicePolicyManager().isSeparateProfileChallengeAllowed(userHandle);
    }

    /**
     * Retrieves whether the current profile and device locks can be unified.
     * @param userHandle profile user handle.
     */
    public boolean isSeparateProfileChallengeAllowedToUnify(int userHandle) {
        return getDevicePolicyManager().isProfileActivePasswordSufficientForParent(userHandle)
                && !getUserManager().hasUserRestriction(
                        UserManager.DISALLOW_UNIFIED_PASSWORD, UserHandle.of(userHandle));
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

    /**
     * Deserialize a pattern.
     * @param string The pattern serialized with {@link #patternToString}
     * @return The pattern.
     * @deprecated Pass patterns as byte[] and use byteArrayToPattern
     */
    @Deprecated
    public static List<LockPatternView.Cell> stringToPattern(String string) {
        if (string == null) {
            return null;
        }
        return byteArrayToPattern(string.getBytes());
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
     * @return The pattern in string form.
     * @deprecated Use patternToByteArray instead.
     */
    @Deprecated
    public static String patternToString(List<LockPatternView.Cell> pattern) {
        return new String(patternToByteArray(pattern));
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

    /**
     * Transform a pattern byte array to base zero form.
     * @param bytes pattern byte array.
     * @return The pattern in base zero form.
     */
    public static byte[] patternByteArrayToBaseZero(byte[] bytes) {
        if (bytes == null) {
            return new byte[0];
        }
        final int patternSize = bytes.length;
        byte[] res = new byte[patternSize];
        for (int i = 0; i < patternSize; i++) {
            res[i] = (byte) (bytes[i] - '1');
        }
        return res;
    }

    /*
     * Generate an SHA-1 hash for the pattern. Not the most secure, but it is
     * at least a second level of protection. First level is that the file
     * is in a location only readable by the system process.
     * @param pattern the gesture pattern.
     * @return the hash of the pattern in a byte array.
     */
    public static byte[] patternToHash(List<LockPatternView.Cell> pattern) {
        if (pattern == null) {
            return null;
        }

        final int patternSize = pattern.size();
        byte[] res = new byte[patternSize];
        for (int i = 0; i < patternSize; i++) {
            LockPatternView.Cell cell = pattern.get(i);
            res[i] = (byte) (cell.getRow() * 3 + cell.getColumn());
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(res);
            return hash;
        } catch (NoSuchAlgorithmException nsa) {
            return res;
        }
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
     * Generate a hash for the given password. To avoid brute force attacks, we use a salted hash.
     * Not the most secure, but it is at least a second level of protection. First level is that
     * the file is in a location only readable by the system process.
     *
     * @param password the gesture pattern.
     *
     * @return the hash of the pattern in a byte array.
     */
    public String legacyPasswordToHash(byte[] password, int userId) {
        if (password == null || password.length == 0) {
            return null;
        }

        try {
            // Previously the password was passed as a String with the following code:
            // byte[] saltedPassword = (password + getSalt(userId)).getBytes();
            // The code below creates the identical digest preimage using byte arrays:
            byte[] salt = getSalt(userId).getBytes();
            byte[] saltedPassword = Arrays.copyOf(password, password.length + salt.length);
            System.arraycopy(salt, 0, saltedPassword, password.length, salt.length);
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(saltedPassword);
            byte[] md5 = MessageDigest.getInstance("MD5").digest(saltedPassword);

            byte[] combined = new byte[sha1.length + md5.length];
            System.arraycopy(sha1, 0, combined, 0, sha1.length);
            System.arraycopy(md5, 0, combined, sha1.length, md5.length);

            final char[] hexEncoded = HexEncoding.encode(combined);
            Arrays.fill(saltedPassword, (byte) 0);
            return new String(hexEncoded);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Missing digest algorithm: ", e);
        }
    }

    /**
     * Hash the password for password history check purpose.
     */
    private String passwordToHistoryHash(byte[] passwordToHash, byte[] hashFactor, int userId) {
        if (passwordToHash == null || passwordToHash.length == 0 || hashFactor == null) {
            return null;
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(hashFactor);
            byte[] salt = getSalt(userId).getBytes();
            byte[] saltedPassword = Arrays.copyOf(passwordToHash, passwordToHash.length
                    + salt.length);
            System.arraycopy(salt, 0, saltedPassword, passwordToHash.length, salt.length);
            sha256.update(saltedPassword);
            Arrays.fill(saltedPassword, (byte) 0);
            return new String(HexEncoding.encode(sha256.digest()));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("Missing digest algorithm: ", e);
        }
    }

    /**
     * @param userId the user for which to report the value
     * @return Whether the lock screen is secured.
     */
    public boolean isSecure(int userId) {
        int mode = getKeyguardStoredPasswordQuality(userId);
        return isLockPatternEnabled(mode, userId) || isLockPasswordEnabled(mode, userId);
    }

    public boolean isLockPasswordEnabled(int userId) {
        return isLockPasswordEnabled(getKeyguardStoredPasswordQuality(userId), userId);
    }

    private boolean isLockPasswordEnabled(int mode, int userId) {
        final boolean passwordEnabled = mode == PASSWORD_QUALITY_ALPHABETIC
                || mode == PASSWORD_QUALITY_NUMERIC
                || mode == PASSWORD_QUALITY_NUMERIC_COMPLEX
                || mode == PASSWORD_QUALITY_ALPHANUMERIC
                || mode == PASSWORD_QUALITY_COMPLEX
                || mode == PASSWORD_QUALITY_MANAGED;
        return passwordEnabled && savedPasswordExists(userId);
    }

    /**
     * @return Whether the lock pattern is enabled
     */
    public boolean isLockPatternEnabled(int userId) {
        return isLockPatternEnabled(getKeyguardStoredPasswordQuality(userId), userId);
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

    private boolean isLockPatternEnabled(int mode, int userId) {
        return mode == PASSWORD_QUALITY_SOMETHING && savedPatternExists(userId);
    }

    /**
     * @return Whether the visible pattern is enabled.
     */
    public boolean isVisiblePatternEnabled(int userId) {
        return getBoolean(Settings.Secure.LOCK_PATTERN_VISIBLE, false, userId);
    }

    /**
     * Set whether the visible pattern is enabled.
     */
    public void setVisiblePatternEnabled(boolean enabled, int userId) {
        setBoolean(Settings.Secure.LOCK_PATTERN_VISIBLE, enabled, userId);

        // Update for crypto if owner
        if (userId != UserHandle.USER_SYSTEM) {
            return;
        }

        IBinder service = ServiceManager.getService("mount");
        if (service == null) {
            Log.e(TAG, "Could not find the mount service to update the user info");
            return;
        }

        IStorageManager storageManager = IStorageManager.Stub.asInterface(service);
        try {
            storageManager.setField(StorageManager.PATTERN_VISIBLE_KEY, enabled ? "1" : "0");
        } catch (RemoteException e) {
            Log.e(TAG, "Error changing pattern visible state", e);
        }
    }

    public boolean isVisiblePatternEverChosen(int userId) {
        return getString(Settings.Secure.LOCK_PATTERN_VISIBLE, userId) != null;
    }

    /**
     * Set whether the visible password is enabled for cryptkeeper screen.
     */
    public void setVisiblePasswordEnabled(boolean enabled, int userId) {
        // Update for crypto if owner
        if (userId != UserHandle.USER_SYSTEM) {
            return;
        }

        IBinder service = ServiceManager.getService("mount");
        if (service == null) {
            Log.e(TAG, "Could not find the mount service to update the user info");
            return;
        }

        IStorageManager storageManager = IStorageManager.Stub.asInterface(service);
        try {
            storageManager.setField(StorageManager.PASSWORD_VISIBLE_KEY, enabled ? "1" : "0");
        } catch (RemoteException e) {
            Log.e(TAG, "Error changing password visible state", e);
        }
    }

    /**
     * @return Whether tactile feedback for the pattern is enabled.
     */
    public boolean isTactileFeedbackEnabled() {
        return Settings.System.getIntForUser(mContentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED, 1, UserHandle.USER_CURRENT) != 0;
    }

    /**
     * Set and store the lockout deadline, meaning the user can't attempt his/her unlock
     * pattern until the deadline has passed.
     * @return the chosen deadline.
     */
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
     *   attempt to enter his/her lock pattern, or 0 if the user is welcome to
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

    private void setLong(String secureSettingKey, long value, int userHandle) {
        try {
            getLockSettings().setLong(secureSettingKey, value, userHandle);
        } catch (RemoteException re) {
            // What can we do?
            Log.e(TAG, "Couldn't write long " + secureSettingKey + re);
        }
    }

    private String getString(String secureSettingKey, int userHandle) {
        try {
            return getLockSettings().getString(secureSettingKey, null, userHandle);
        } catch (RemoteException re) {
            return null;
        }
    }

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

    public boolean getPowerButtonInstantlyLocks(int userId) {
        return getBoolean(LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS, true, userId);
    }

    public boolean isPowerButtonInstantlyLocksEverChosen(int userId) {
        return getString(LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS, userId) != null;
    }

    public void setEnabledTrustAgents(Collection<ComponentName> activeTrustAgents, int userId) {
        StringBuilder sb = new StringBuilder();
        for (ComponentName cn : activeTrustAgents) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(cn.flattenToShortString());
        }
        setString(ENABLED_TRUST_AGENTS, sb.toString(), userId);
        getTrustManager().reportEnabledTrustAgentsChanged(userId);
    }

    public List<ComponentName> getEnabledTrustAgents(int userId) {
        String serialized = getString(ENABLED_TRUST_AGENTS, userId);
        if (TextUtils.isEmpty(serialized)) {
            return null;
        }
        String[] split = serialized.split(",");
        ArrayList<ComponentName> activeTrustAgents = new ArrayList<ComponentName>(split.length);
        for (String s : split) {
            if (!TextUtils.isEmpty(s)) {
                activeTrustAgents.add(ComponentName.unflattenFromString(s));
            }
        }
        return activeTrustAgents;
    }

    /**
     * Disable trust until credentials have been entered for user {@param userId}.
     *
     * Requires the {@link android.Manifest.permission#ACCESS_KEYGUARD_SECURE_STORAGE} permission.
     *
     * @param userId either an explicit user id or {@link android.os.UserHandle#USER_ALL}
     */
    public void requireCredentialEntry(int userId) {
        requireStrongAuth(StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST, userId);
    }

    /**
     * Requests strong authentication for user {@param userId}.
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

    private void onAfterChangingPassword(int userHandle) {
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

    private boolean isDoNotAskCredentialsOnBootSet() {
        return getDevicePolicyManager().getDoNotAskCredentialsOnBoot();
    }

    private boolean shouldEncryptWithCredentials(boolean defaultValue) {
        return isCredentialRequiredToDecrypt(defaultValue) && !isDoNotAskCredentialsOnBootSet();
    }

    private void throwIfCalledOnMainThread() {
        if (Looper.getMainLooper().isCurrentThread()) {
            throw new IllegalStateException("should not be called from the main thread.");
        }
    }

    public void registerStrongAuthTracker(final StrongAuthTracker strongAuthTracker) {
        try {
            getLockSettings().registerStrongAuthTracker(strongAuthTracker.mStub);
        } catch (RemoteException e) {
            throw new RuntimeException("Could not register StrongAuthTracker");
        }
    }

    public void unregisterStrongAuthTracker(final StrongAuthTracker strongAuthTracker) {
        try {
            getLockSettings().unregisterStrongAuthTracker(strongAuthTracker.mStub);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not unregister StrongAuthTracker", e);
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

    private ICheckCredentialProgressCallback wrapCallback(
            final CheckCredentialProgressCallback callback) {
        if (callback == null) {
            return null;
        } else {
            if (mHandler == null) {
                throw new IllegalStateException("Must construct LockPatternUtils on a looper thread"
                        + " to use progress callbacks.");
            }
            return new ICheckCredentialProgressCallback.Stub() {

                @Override
                public void onCredentialVerified() throws RemoteException {
                    mHandler.post(callback::onEarlyMatched);
                }
            };
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
     * After adding, if the user currently has lockscreen password, he will need to perform a
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
     * Callback interface to notify when an added escrow token has been activated.
     */
    public interface EscrowTokenStateChangeCallback {
        /**
         * The method to be called when the token is activated.
         * @param handle 64 bit handle corresponding to the escrow token
         * @param userid user for whom the escrow token has been added
         */
        void onEscrowTokenActivated(long handle, int userid);
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
     * Check if the given escrow token is active or not. Only active token can be used to call
     * {@link #setLockCredentialWithToken} and {@link #unlockUserWithToken}
     *
     * <p>This method is only available to code running in the system server process itself.
     */
    public boolean isEscrowTokenActive(long handle, int userId) {
        return getLockSettingsInternal().isEscrowTokenActive(handle, userId);
    }

    /**
     * Change a user's lock credential with a pre-configured escrow token.
     *
     * <p>This method is only available to code running in the system server process itself.
     *
     * @param credential The new credential to be set
     * @param type Credential type: password / pattern / none.
     * @param requestedQuality the requested password quality by DevicePolicyManager.
     *        See {@link DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     * @param tokenHandle Handle of the escrow token
     * @param token Escrow token
     * @param userId The user who's lock credential to be changed
     * @return {@code true} if the operation is successful.
     */
    public boolean setLockCredentialWithToken(byte[] credential, int type, int requestedQuality,
            long tokenHandle, byte[] token, int userId) {
        if (!hasSecureLockScreen()) {
            throw new UnsupportedOperationException(
                    "This operation requires the lock screen feature.");
        }
        LockSettingsInternal localService = getLockSettingsInternal();
        if (type != CREDENTIAL_TYPE_NONE) {
            if (credential == null || credential.length < MIN_LOCK_PASSWORD_SIZE) {
                throw new IllegalArgumentException("password must not be null and at least "
                        + "of length " + MIN_LOCK_PASSWORD_SIZE);
            }
            final int quality = computePasswordQuality(type, credential, requestedQuality);
            if (!localService.setLockCredentialWithToken(credential, type, tokenHandle, token,
                    quality, userId)) {
                return false;
            }
            setKeyguardStoredPasswordQuality(quality, userId);

            updateEncryptionPasswordIfNeeded(credential, quality, userId);
            updatePasswordHistory(credential, userId);
            onAfterChangingPassword(userId);
        } else {
            if (!(credential == null || credential.length == 0)) {
                throw new IllegalArgumentException("password must be emtpy for NONE type");
            }
            if (!localService.setLockCredentialWithToken(null, CREDENTIAL_TYPE_NONE, tokenHandle,
                    token, PASSWORD_QUALITY_UNSPECIFIED, userId)) {
                return false;
            }
            setKeyguardStoredPasswordQuality(PASSWORD_QUALITY_UNSPECIFIED, userId);

            if (userId == UserHandle.USER_SYSTEM) {
                // Set the encryption password to default.
                updateEncryptionPassword(StorageManager.CRYPT_TYPE_DEFAULT, null);
                setCredentialRequiredToDecrypt(false);
            }
        }
        onAfterChangingPassword(userId);
        return true;
    }

    /**
     * Unlock the specified user by an pre-activated escrow token. This should have the same effect
     * on device encryption as the user entering his lockscreen credentials for the first time after
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
                        STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN})
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
         * Strong auth flags that do not prevent biometric methods from being accepted as auth.
         * If any other flags are set, biometric authentication is disabled.
         */
        private static final int ALLOWING_BIOMETRIC = STRONG_AUTH_NOT_REQUIRED
                | SOME_AUTH_REQUIRED_AFTER_USER_REQUEST;

        private final SparseIntArray mStrongAuthRequiredForUser = new SparseIntArray();
        private final H mHandler;
        private final int mDefaultStrongAuthFlags;

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
         * @return true if unlocking with trust alone is allowed for {@param userId} by the current
         * strong authentication requirements.
         */
        public boolean isTrustAllowedForUser(int userId) {
            return getStrongAuthForUser(userId) == STRONG_AUTH_NOT_REQUIRED;
        }

        /**
         * @return true if unlocking with a biometric method alone is allowed for {@param userId}
         * by the current strong authentication requirements.
         */
        public boolean isBiometricAllowedForUser(int userId) {
            return (getStrongAuthForUser(userId) & ~ALLOWING_BIOMETRIC) == 0;
        }

        /**
         * Called when the strong authentication requirements for {@param userId} changed.
         */
        public void onStrongAuthRequiredChanged(int userId) {
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


        protected final IStrongAuthTracker.Stub mStub = new IStrongAuthTracker.Stub() {
            @Override
            public void onStrongAuthRequiredChanged(@StrongAuthFlags int strongAuthFlags,
                    int userId) {
                mHandler.obtainMessage(H.MSG_ON_STRONG_AUTH_REQUIRED_CHANGED,
                        strongAuthFlags, userId).sendToTarget();
            }
        };

        private class H extends Handler {
            static final int MSG_ON_STRONG_AUTH_REQUIRED_CHANGED = 1;

            public H(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case MSG_ON_STRONG_AUTH_REQUIRED_CHANGED:
                        handleStrongAuthRequiredChanged(msg.arg1, msg.arg2);
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
        return getLong(SYNTHETIC_PASSWORD_ENABLED_KEY, 0, UserHandle.USER_SYSTEM) != 0;
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
            mHasSecureLockScreen = Boolean.valueOf(mContext.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN));
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
     * Converts a CharSequence to a byte array without requiring a toString(), which creates an
     * additional copy.
     *
     * @param chars The CharSequence to convert
     * @return A byte array representing the input
     */
    public static byte[] charSequenceToByteArray(CharSequence chars) {
        if (chars == null) {
            return null;
        }
        byte[] bytes = new byte[chars.length()];
        for (int i = 0; i < chars.length(); i++) {
            bytes[i] = (byte) chars.charAt(i);
        }
        return bytes;
    }
}
