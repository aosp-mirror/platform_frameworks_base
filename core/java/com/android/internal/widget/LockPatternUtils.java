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

import android.Manifest;
import android.app.ActivityManagerNative;
import android.app.AlarmManager;
import android.app.admin.DevicePolicyManager;
import android.app.trust.TrustManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IMountService;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.IWindowManager;
import android.view.View;
import android.widget.Button;

import com.android.internal.R;
import com.google.android.collect.Lists;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Utilities for the lock pattern and its settings.
 */
public class LockPatternUtils {

    private static final String TAG = "LockPatternUtils";
    private static final boolean DEBUG = false;

    /**
     * The maximum number of incorrect attempts before the user is prevented
     * from trying again for {@link #FAILED_ATTEMPT_TIMEOUT_MS}.
     */
    public static final int FAILED_ATTEMPTS_BEFORE_TIMEOUT = 5;

    /**
     * The number of incorrect attempts before which we fall back on an alternative
     * method of verifying the user, and resetting their lock pattern.
     */
    public static final int FAILED_ATTEMPTS_BEFORE_RESET = 20;

    /**
     * How long the user is prevented from trying again after entering the
     * wrong pattern too many times.
     */
    public static final long FAILED_ATTEMPT_TIMEOUT_MS = 30000L;

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
     * The minimum number of dots the user must include in a wrong pattern
     * attempt for it to be counted against the counts that affect
     * {@link #FAILED_ATTEMPTS_BEFORE_TIMEOUT} and {@link #FAILED_ATTEMPTS_BEFORE_RESET}
     */
    public static final int MIN_PATTERN_REGISTER_FAIL = MIN_LOCK_PATTERN_SIZE;

    /**
     * Tells the keyguard to show the user switcher when the keyguard is created.
     */
    public static final String KEYGUARD_SHOW_USER_SWITCHER = "showuserswitcher";

    /**
     * Tells the keyguard to show the security challenge when the keyguard is created.
     */
    public static final String KEYGUARD_SHOW_SECURITY_CHALLENGE = "showsecuritychallenge";

    /**
     * Tells the keyguard to show the widget with the specified id when the keyguard is created.
     */
    public static final String KEYGUARD_SHOW_APPWIDGET = "showappwidget";

    /**
     * The bit in LOCK_BIOMETRIC_WEAK_FLAGS to be used to indicate whether liveliness should
     * be used
     */
    public static final int FLAG_BIOMETRIC_WEAK_LIVELINESS = 0x1;

    /**
     * Pseudo-appwidget id we use to represent the default clock status widget
     */
    public static final int ID_DEFAULT_STATUS_WIDGET = -2;

    public final static String LOCKOUT_PERMANENT_KEY = "lockscreen.lockedoutpermanently";
    public final static String LOCKOUT_ATTEMPT_DEADLINE = "lockscreen.lockoutattemptdeadline";
    public final static String PATTERN_EVER_CHOSEN_KEY = "lockscreen.patterneverchosen";
    public final static String PASSWORD_TYPE_KEY = "lockscreen.password_type";
    public static final String PASSWORD_TYPE_ALTERNATE_KEY = "lockscreen.password_type_alternate";
    public final static String LOCK_PASSWORD_SALT_KEY = "lockscreen.password_salt";
    public final static String DISABLE_LOCKSCREEN_KEY = "lockscreen.disabled";
    public final static String LOCKSCREEN_OPTIONS = "lockscreen.options";
    public final static String LOCKSCREEN_BIOMETRIC_WEAK_FALLBACK
            = "lockscreen.biometric_weak_fallback";
    public final static String BIOMETRIC_WEAK_EVER_CHOSEN_KEY
            = "lockscreen.biometricweakeverchosen";
    public final static String LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS
            = "lockscreen.power_button_instantly_locks";
    public final static String LOCKSCREEN_WIDGETS_ENABLED = "lockscreen.widgets_enabled";

    public final static String PASSWORD_HISTORY_KEY = "lockscreen.passwordhistory";

    private static final String LOCK_SCREEN_OWNER_INFO = Settings.Secure.LOCK_SCREEN_OWNER_INFO;
    private static final String LOCK_SCREEN_OWNER_INFO_ENABLED =
            Settings.Secure.LOCK_SCREEN_OWNER_INFO_ENABLED;

    private static final String ENABLED_TRUST_AGENTS = "lockscreen.enabledtrustagents";

    // Maximum allowed number of repeated or ordered characters in a sequence before we'll
    // consider it a complex PIN/password.
    public static final int MAX_ALLOWED_SEQUENCE = 3;

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private DevicePolicyManager mDevicePolicyManager;
    private ILockSettings mLockSettingsService;

    private final boolean mMultiUserMode;

    // The current user is set by KeyguardViewMediator and shared by all LockPatternUtils.
    private static volatile int sCurrentUserId = UserHandle.USER_NULL;

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

        // If this is being called by the system or by an application like keyguard that
        // has permision INTERACT_ACROSS_USERS, then LockPatternUtils will operate in multi-user
        // mode where calls are for the current user rather than the user of the calling process.
        mMultiUserMode = context.checkCallingOrSelfPermission(
            Manifest.permission.INTERACT_ACROSS_USERS_FULL) == PackageManager.PERMISSION_GRANTED;
    }

    private ILockSettings getLockSettings() {
        if (mLockSettingsService == null) {
            ILockSettings service = ILockSettings.Stub.asInterface(
                    ServiceManager.getService("lock_settings"));
            mLockSettingsService = service;
        }
        return mLockSettingsService;
    }

    public int getRequestedMinimumPasswordLength() {
        return getDevicePolicyManager().getPasswordMinimumLength(null, getCurrentOrCallingUserId());
    }

    /**
     * Gets the device policy password mode. If the mode is non-specific, returns
     * MODE_PATTERN which allows the user to choose anything.
     */
    public int getRequestedPasswordQuality() {
        return getDevicePolicyManager().getPasswordQuality(null, getCurrentOrCallingUserId());
    }

    public int getRequestedPasswordHistoryLength() {
        return getDevicePolicyManager().getPasswordHistoryLength(null, getCurrentOrCallingUserId());
    }

    public int getRequestedPasswordMinimumLetters() {
        return getDevicePolicyManager().getPasswordMinimumLetters(null,
                getCurrentOrCallingUserId());
    }

    public int getRequestedPasswordMinimumUpperCase() {
        return getDevicePolicyManager().getPasswordMinimumUpperCase(null,
                getCurrentOrCallingUserId());
    }

    public int getRequestedPasswordMinimumLowerCase() {
        return getDevicePolicyManager().getPasswordMinimumLowerCase(null,
                getCurrentOrCallingUserId());
    }

    public int getRequestedPasswordMinimumNumeric() {
        return getDevicePolicyManager().getPasswordMinimumNumeric(null,
                getCurrentOrCallingUserId());
    }

    public int getRequestedPasswordMinimumSymbols() {
        return getDevicePolicyManager().getPasswordMinimumSymbols(null,
                getCurrentOrCallingUserId());
    }

    public int getRequestedPasswordMinimumNonLetter() {
        return getDevicePolicyManager().getPasswordMinimumNonLetter(null,
                getCurrentOrCallingUserId());
    }

    public void reportFailedPasswordAttempt() {
        int userId = getCurrentOrCallingUserId();
        getDevicePolicyManager().reportFailedPasswordAttempt(userId);
        getTrustManager().reportUnlockAttempt(false /* authenticated */, userId);
        getTrustManager().reportRequireCredentialEntry(userId);
    }

    public void reportSuccessfulPasswordAttempt() {
        getDevicePolicyManager().reportSuccessfulPasswordAttempt(getCurrentOrCallingUserId());
        getTrustManager().reportUnlockAttempt(true /* authenticated */,
                getCurrentOrCallingUserId());
    }

    public void setCurrentUser(int userId) {
        sCurrentUserId = userId;
    }

    public int getCurrentUser() {
        if (sCurrentUserId != UserHandle.USER_NULL) {
            // Someone is regularly updating using setCurrentUser() use that value.
            return sCurrentUserId;
        }
        try {
            return ActivityManagerNative.getDefault().getCurrentUser().id;
        } catch (RemoteException re) {
            return UserHandle.USER_OWNER;
        }
    }

    public void removeUser(int userId) {
        try {
            getLockSettings().removeUser(userId);
        } catch (RemoteException re) {
            Log.e(TAG, "Couldn't remove lock settings for user " + userId);
        }
    }

    private int getCurrentOrCallingUserId() {
        if (mMultiUserMode) {
            // TODO: This is a little inefficient. See if all users of this are able to
            // handle USER_CURRENT and pass that instead.
            return getCurrentUser();
        } else {
            return UserHandle.getCallingUserId();
        }
    }

    /**
     * Check to see if a pattern matches the saved pattern.  If no pattern exists,
     * always returns true.
     * @param pattern The pattern to check.
     * @return Whether the pattern matches the stored one.
     */
    public boolean checkPattern(List<LockPatternView.Cell> pattern) {
        final int userId = getCurrentOrCallingUserId();
        try {
            return getLockSettings().checkPattern(patternToString(pattern), userId);
        } catch (RemoteException re) {
            return true;
        }
    }

    /**
     * Check to see if a password matches the saved password.  If no password exists,
     * always returns true.
     * @param password The password to check.
     * @return Whether the password matches the stored one.
     */
    public boolean checkPassword(String password) {
        final int userId = getCurrentOrCallingUserId();
        try {
            return getLockSettings().checkPassword(password, userId);
        } catch (RemoteException re) {
            return true;
        }
    }

    /**
     * Check to see if vold already has the password.
     * Note that this also clears vold's copy of the password.
     * @return Whether the vold password matches or not.
     */
    public boolean checkVoldPassword() {
        final int userId = getCurrentOrCallingUserId();
        try {
            return getLockSettings().checkVoldPassword(userId);
        } catch (RemoteException re) {
            return false;
        }
    }

    /**
     * Check to see if a password matches any of the passwords stored in the
     * password history.
     *
     * @param password The password to check.
     * @return Whether the password matches any in the history.
     */
    public boolean checkPasswordHistory(String password) {
        String passwordHashString = new String(
                passwordToHash(password, getCurrentOrCallingUserId()));
        String passwordHistory = getString(PASSWORD_HISTORY_KEY);
        if (passwordHistory == null) {
            return false;
        }
        // Password History may be too long...
        int passwordHashLength = passwordHashString.length();
        int passwordHistoryLength = getRequestedPasswordHistoryLength();
        if(passwordHistoryLength == 0) {
            return false;
        }
        int neededPasswordHistoryLength = passwordHashLength * passwordHistoryLength
                + passwordHistoryLength - 1;
        if (passwordHistory.length() > neededPasswordHistoryLength) {
            passwordHistory = passwordHistory.substring(0, neededPasswordHistoryLength);
        }
        return passwordHistory.contains(passwordHashString);
    }

    /**
     * Check to see if the user has stored a lock pattern.
     * @return Whether a saved pattern exists.
     */
    public boolean savedPatternExists() {
        return savedPatternExists(getCurrentOrCallingUserId());
    }

    /**
     * Check to see if the user has stored a lock pattern.
     * @return Whether a saved pattern exists.
     */
    public boolean savedPatternExists(int userId) {
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
    public boolean savedPasswordExists() {
        return savedPasswordExists(getCurrentOrCallingUserId());
    }

     /**
     * Check to see if the user has stored a lock pattern.
     * @return Whether a saved pattern exists.
     */
    public boolean savedPasswordExists(int userId) {
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
    public boolean isPatternEverChosen() {
        return getBoolean(PATTERN_EVER_CHOSEN_KEY, false);
    }

    /**
     * Return true if the user has ever chosen biometric weak.  This is true even if biometric
     * weak is not current set.
     *
     * @return True if the user has ever chosen biometric weak.
     */
    public boolean isBiometricWeakEverChosen() {
        return getBoolean(BIOMETRIC_WEAK_EVER_CHOSEN_KEY, false);
    }

    /**
     * Used by device policy manager to validate the current password
     * information it has.
     */
    public int getActivePasswordQuality() {
        int activePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
        // Note we don't want to use getKeyguardStoredPasswordQuality() because we want this to
        // return biometric_weak if that is being used instead of the backup
        int quality =
                (int) getLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);
        switch (quality) {
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                if (isLockPatternEnabled()) {
                    activePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
                }
                break;
            case DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK:
                if (isBiometricWeakInstalled()) {
                    activePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK;
                }
                break;
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
                if (isLockPasswordEnabled()) {
                    activePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
                }
                break;
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
                if (isLockPasswordEnabled()) {
                    activePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
                }
                break;
            case DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC:
                if (isLockPasswordEnabled()) {
                    activePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
                }
                break;
            case DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC:
                if (isLockPasswordEnabled()) {
                    activePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
                }
                break;
            case DevicePolicyManager.PASSWORD_QUALITY_COMPLEX:
                if (isLockPasswordEnabled()) {
                    activePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
                }
                break;
        }

        return activePasswordQuality;
    }

    public void clearLock(boolean isFallback) {
        clearLock(isFallback, getCurrentOrCallingUserId());
    }

    /**
     * Clear any lock pattern or password.
     */
    public void clearLock(boolean isFallback, int userHandle) {
        if(!isFallback) deleteGallery(userHandle);
        saveLockPassword(null, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING, isFallback,
                userHandle);
        setLockPatternEnabled(false, userHandle);
        saveLockPattern(null, isFallback, userHandle);
        setLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userHandle);
        setLong(PASSWORD_TYPE_ALTERNATE_KEY, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
                userHandle);
        onAfterChangingPassword(userHandle);
    }

    /**
     * Disable showing lock screen at all when the DevicePolicyManager allows it.
     * This is only meaningful if pattern, pin or password are not set.
     *
     * @param disable Disables lock screen when true
     */
    public void setLockScreenDisabled(boolean disable) {
        setLong(DISABLE_LOCKSCREEN_KEY, disable ? 1 : 0);
    }

    /**
     * Determine if LockScreen can be disabled. This is used, for example, to tell if we should
     * show LockScreen or go straight to the home screen.
     *
     * @return true if lock screen is can be disabled
     */
    public boolean isLockScreenDisabled() {
        if (!isSecure() && getLong(DISABLE_LOCKSCREEN_KEY, 0) != 0) {
            // Check if the number of switchable users forces the lockscreen.
            final List<UserInfo> users = UserManager.get(mContext).getUsers(true);
            final int userCount = users.size();
            int switchableUsers = 0;
            for (int i = 0; i < userCount; i++) {
                if (users.get(i).supportsSwitchTo()) {
                    switchableUsers++;
                }
            }
            return switchableUsers < 2;
        }
        return false;
    }

    /**
     * Calls back SetupFaceLock to delete the temporary gallery file
     */
    public void deleteTempGallery() {
        Intent intent = new Intent().setAction("com.android.facelock.DELETE_GALLERY");
        intent.putExtra("deleteTempGallery", true);
        mContext.sendBroadcast(intent);
    }

    /**
     * Calls back SetupFaceLock to delete the gallery file when the lock type is changed
    */
    void deleteGallery(int userId) {
        if(usingBiometricWeak(userId)) {
            Intent intent = new Intent().setAction("com.android.facelock.DELETE_GALLERY");
            intent.putExtra("deleteGallery", true);
            mContext.sendBroadcastAsUser(intent, new UserHandle(userId));
        }
    }

    /**
     * Save a lock pattern.
     * @param pattern The new pattern to save.
     */
    public void saveLockPattern(List<LockPatternView.Cell> pattern) {
        this.saveLockPattern(pattern, false);
    }

    /**
     * Save a lock pattern.
     * @param pattern The new pattern to save.
     */
    public void saveLockPattern(List<LockPatternView.Cell> pattern, boolean isFallback) {
        this.saveLockPattern(pattern, isFallback, getCurrentOrCallingUserId());
    }

    /**
     * Save a lock pattern.
     * @param pattern The new pattern to save.
     * @param isFallback Specifies if this is a fallback to biometric weak
     * @param userId the user whose pattern is to be saved.
     */
    public void saveLockPattern(List<LockPatternView.Cell> pattern, boolean isFallback,
            int userId) {
        try {
            getLockSettings().setLockPattern(patternToString(pattern), userId);
            DevicePolicyManager dpm = getDevicePolicyManager();
            if (pattern != null) {
                // Update the device encryption password.
                if (userId == UserHandle.USER_OWNER
                        && LockPatternUtils.isDeviceEncryptionEnabled()) {
                    final boolean required = isCredentialRequiredToDecrypt(true);
                    if (!required) {
                        clearEncryptionPassword();
                    } else {
                        String stringPattern = patternToString(pattern);
                        updateEncryptionPassword(StorageManager.CRYPT_TYPE_PATTERN, stringPattern);
                    }
                }

                setBoolean(PATTERN_EVER_CHOSEN_KEY, true, userId);
                if (!isFallback) {
                    deleteGallery(userId);
                    setLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING, userId);
                    dpm.setActivePasswordState(DevicePolicyManager.PASSWORD_QUALITY_SOMETHING,
                            pattern.size(), 0, 0, 0, 0, 0, 0, userId);
                } else {
                    setLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK, userId);
                    setLong(PASSWORD_TYPE_ALTERNATE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_SOMETHING, userId);
                    finishBiometricWeak(userId);
                    dpm.setActivePasswordState(DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK,
                            0, 0, 0, 0, 0, 0, 0, userId);
                }
            } else {
                dpm.setActivePasswordState(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, 0, 0,
                        0, 0, 0, 0, 0, userId);
            }
            onAfterChangingPassword(userId);
        } catch (RemoteException re) {
            Log.e(TAG, "Couldn't save lock pattern " + re);
        }
    }

    private void updateCryptoUserInfo() {
        int userId = getCurrentOrCallingUserId();
        if (userId != UserHandle.USER_OWNER) {
            return;
        }

        final String ownerInfo = isOwnerInfoEnabled() ? getOwnerInfo(userId) : "";

        IBinder service = ServiceManager.getService("mount");
        if (service == null) {
            Log.e(TAG, "Could not find the mount service to update the user info");
            return;
        }

        IMountService mountService = IMountService.Stub.asInterface(service);
        try {
            Log.d(TAG, "Setting owner info");
            mountService.setField(StorageManager.OWNER_INFO_KEY, ownerInfo);
        } catch (RemoteException e) {
            Log.e(TAG, "Error changing user info", e);
        }
    }

    public void setOwnerInfo(String info, int userId) {
        setString(LOCK_SCREEN_OWNER_INFO, info, userId);
        updateCryptoUserInfo();
    }

    public void setOwnerInfoEnabled(boolean enabled) {
        setBoolean(LOCK_SCREEN_OWNER_INFO_ENABLED, enabled);
        updateCryptoUserInfo();
    }

    public String getOwnerInfo(int userId) {
        return getString(LOCK_SCREEN_OWNER_INFO);
    }

    public boolean isOwnerInfoEnabled() {
        return getBoolean(LOCK_SCREEN_OWNER_INFO_ENABLED, false);
    }

    /**
     * Compute the password quality from the given password string.
     */
    static public int computePasswordQuality(String password) {
        boolean hasDigit = false;
        boolean hasNonDigit = false;
        final int len = password.length();
        for (int i = 0; i < len; i++) {
            if (Character.isDigit(password.charAt(i))) {
                hasDigit = true;
            } else {
                hasNonDigit = true;
            }
        }

        if (hasNonDigit && hasDigit) {
            return DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
        }
        if (hasNonDigit) {
            return DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
        }
        if (hasDigit) {
            return maxLengthSequence(password) > MAX_ALLOWED_SEQUENCE
                    ? DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                    : DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
        }
        return DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
    }

    private static int categoryChar(char c) {
        if ('a' <= c && c <= 'z') return 0;
        if ('A' <= c && c <= 'Z') return 1;
        if ('0' <= c && c <= '9') return 2;
        return 3;
    }

    private static int maxDiffCategory(int category) {
        if (category == 0 || category == 1) return 1;
        else if (category == 2) return 10;
        return 0;
    }

    /*
     * Returns the maximum length of a sequential characters.  A sequence is defined as
     * monotonically increasing characters with a constant interval or the same character repeated.
     *
     * For example:
     * maxLengthSequence("1234") == 4
     * maxLengthSequence("1234abc") == 4
     * maxLengthSequence("aabc") == 3
     * maxLengthSequence("qwertyuio") == 1
     * maxLengthSequence("@ABC") == 3
     * maxLengthSequence(";;;;") == 4 (anything that repeats)
     * maxLengthSequence(":;<=>") == 1  (ordered, but not composed of alphas or digits)
     *
     * @param string the pass
     * @return the number of sequential letters or digits
     */
    public static int maxLengthSequence(String string) {
        if (string.length() == 0) return 0;
        char previousChar = string.charAt(0);
        int category = categoryChar(previousChar); //current category of the sequence
        int diff = 0; //difference between two consecutive characters
        boolean hasDiff = false; //if we are currently targeting a sequence
        int maxLength = 0; //maximum length of a sequence already found
        int startSequence = 0; //where the current sequence started
        for (int current = 1; current < string.length(); current++) {
            char currentChar = string.charAt(current);
            int categoryCurrent = categoryChar(currentChar);
            int currentDiff = (int) currentChar - (int) previousChar;
            if (categoryCurrent != category || Math.abs(currentDiff) > maxDiffCategory(category)) {
                maxLength = Math.max(maxLength, current - startSequence);
                startSequence = current;
                hasDiff = false;
                category = categoryCurrent;
            }
            else {
                if(hasDiff && currentDiff != diff) {
                    maxLength = Math.max(maxLength, current - startSequence);
                    startSequence = current - 1;
                }
                diff = currentDiff;
                hasDiff = true;
            }
            previousChar = currentChar;
        }
        maxLength = Math.max(maxLength, string.length() - startSequence);
        return maxLength;
    }

    /** Update the encryption password if it is enabled **/
    private void updateEncryptionPassword(final int type, final String password) {
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
                IMountService mountService = IMountService.Stub.asInterface(service);
                try {
                    mountService.changeEncryptionPassword(type, password);
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
     * pattern.
     * @param password The password to save
     * @param quality {@see DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     */
    public void saveLockPassword(String password, int quality) {
        this.saveLockPassword(password, quality, false, getCurrentOrCallingUserId());
    }

    /**
     * Save a lock password.  Does not ensure that the password is as good
     * as the requested mode, but will adjust the mode to be as good as the
     * pattern.
     * @param password The password to save
     * @param quality {@see DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     * @param isFallback Specifies if this is a fallback to biometric weak
     */
    public void saveLockPassword(String password, int quality, boolean isFallback) {
        saveLockPassword(password, quality, isFallback, getCurrentOrCallingUserId());
    }

    /**
     * Save a lock password.  Does not ensure that the password is as good
     * as the requested mode, but will adjust the mode to be as good as the
     * pattern.
     * @param password The password to save
     * @param quality {@see DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     * @param isFallback Specifies if this is a fallback to biometric weak
     * @param userHandle The userId of the user to change the password for
     */
    public void saveLockPassword(String password, int quality, boolean isFallback, int userHandle) {
        try {
            DevicePolicyManager dpm = getDevicePolicyManager();
            if (!TextUtils.isEmpty(password)) {
                getLockSettings().setLockPassword(password, userHandle);
                int computedQuality = computePasswordQuality(password);

                // Update the device encryption password.
                if (userHandle == UserHandle.USER_OWNER
                        && LockPatternUtils.isDeviceEncryptionEnabled()) {
                    if (!isCredentialRequiredToDecrypt(true)) {
                        clearEncryptionPassword();
                    } else {
                        boolean numeric = computedQuality
                                == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
                        boolean numericComplex = computedQuality
                                == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX;
                        int type = numeric || numericComplex ? StorageManager.CRYPT_TYPE_PIN
                                : StorageManager.CRYPT_TYPE_PASSWORD;
                        updateEncryptionPassword(type, password);
                    }
                }

                if (!isFallback) {
                    deleteGallery(userHandle);
                    setLong(PASSWORD_TYPE_KEY, Math.max(quality, computedQuality), userHandle);
                    if (computedQuality != DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED) {
                        int letters = 0;
                        int uppercase = 0;
                        int lowercase = 0;
                        int numbers = 0;
                        int symbols = 0;
                        int nonletter = 0;
                        for (int i = 0; i < password.length(); i++) {
                            char c = password.charAt(i);
                            if (c >= 'A' && c <= 'Z') {
                                letters++;
                                uppercase++;
                            } else if (c >= 'a' && c <= 'z') {
                                letters++;
                                lowercase++;
                            } else if (c >= '0' && c <= '9') {
                                numbers++;
                                nonletter++;
                            } else {
                                symbols++;
                                nonletter++;
                            }
                        }
                        dpm.setActivePasswordState(Math.max(quality, computedQuality),
                                password.length(), letters, uppercase, lowercase,
                                numbers, symbols, nonletter, userHandle);
                    } else {
                        // The password is not anything.
                        dpm.setActivePasswordState(
                                DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
                                0, 0, 0, 0, 0, 0, 0, userHandle);
                    }
                } else {
                    // Case where it's a fallback for biometric weak
                    setLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK,
                            userHandle);
                    setLong(PASSWORD_TYPE_ALTERNATE_KEY, Math.max(quality, computedQuality),
                            userHandle);
                    finishBiometricWeak(userHandle);
                    dpm.setActivePasswordState(DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK,
                            0, 0, 0, 0, 0, 0, 0, userHandle);
                }
                // Add the password to the password history. We assume all
                // password hashes have the same length for simplicity of implementation.
                String passwordHistory = getString(PASSWORD_HISTORY_KEY, userHandle);
                if (passwordHistory == null) {
                    passwordHistory = "";
                }
                int passwordHistoryLength = getRequestedPasswordHistoryLength();
                if (passwordHistoryLength == 0) {
                    passwordHistory = "";
                } else {
                    byte[] hash = passwordToHash(password, userHandle);
                    passwordHistory = new String(hash) + "," + passwordHistory;
                    // Cut it to contain passwordHistoryLength hashes
                    // and passwordHistoryLength -1 commas.
                    passwordHistory = passwordHistory.substring(0, Math.min(hash.length
                            * passwordHistoryLength + passwordHistoryLength - 1, passwordHistory
                            .length()));
                }
                setString(PASSWORD_HISTORY_KEY, passwordHistory, userHandle);
            } else {
                // Empty password
                getLockSettings().setLockPassword(null, userHandle);
                if (userHandle == UserHandle.USER_OWNER) {
                    // Set the encryption password to default.
                    updateEncryptionPassword(StorageManager.CRYPT_TYPE_DEFAULT, null);
                }

                dpm.setActivePasswordState(
                        DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, 0, 0, 0, 0, 0, 0, 0,
                        userHandle);
            }
            onAfterChangingPassword(userHandle);
        } catch (RemoteException re) {
            // Cant do much
            Log.e(TAG, "Unable to save lock password " + re);
        }
    }

    /**
     * Gets whether the device is encrypted.
     *
     * @return Whether the device is encrypted.
     */
    public static boolean isDeviceEncrypted() {
        IMountService mountService = IMountService.Stub.asInterface(
                ServiceManager.getService("mount"));
        try {
            return mountService.getEncryptionState() != IMountService.ENCRYPTION_STATE_NONE
                    && mountService.getPasswordType() != StorageManager.CRYPT_TYPE_DEFAULT;
        } catch (RemoteException re) {
            Log.e(TAG, "Error getting encryption state", re);
        }
        return true;
    }

    /**
     * Determine if the device supports encryption, even if it's set to default. This
     * differs from isDeviceEncrypted() in that it returns true even if the device is
     * encrypted with the default password.
     * @return true if device encryption is enabled
     */
    public static boolean isDeviceEncryptionEnabled() {
        final String status = SystemProperties.get("ro.crypto.state", "unsupported");
        return "encrypted".equalsIgnoreCase(status);
    }

    /**
     * Clears the encryption password.
     */
    public void clearEncryptionPassword() {
        updateEncryptionPassword(StorageManager.CRYPT_TYPE_DEFAULT, null);
    }

    /**
     * Retrieves the quality mode we're in.
     * {@see DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     *
     * @return stored password quality
     */
    public int getKeyguardStoredPasswordQuality() {
        return getKeyguardStoredPasswordQuality(getCurrentOrCallingUserId());
    }

    /**
     * Retrieves the quality mode for {@param userHandle}.
     * {@see DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     *
     * @return stored password quality
     */
    public int getKeyguardStoredPasswordQuality(int userHandle) {
        int quality = (int) getLong(PASSWORD_TYPE_KEY,
                DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userHandle);
        // If the user has chosen to use weak biometric sensor, then return the backup locking
        // method and treat biometric as a special case.
        if (quality == DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK) {
            quality = (int) getLong(PASSWORD_TYPE_ALTERNATE_KEY,
                        DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userHandle);
        }
        return quality;
    }

    /**
     * @return true if the lockscreen method is set to biometric weak
     */
    public boolean usingBiometricWeak() {
        return usingBiometricWeak(getCurrentOrCallingUserId());
    }

    /**
     * @return true if the lockscreen method is set to biometric weak
     */
    public boolean usingBiometricWeak(int userId) {
        int quality = (int) getLong(
                PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userId);
        return quality == DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK;
    }

    /**
     * Deserialize a pattern.
     * @param string The pattern serialized with {@link #patternToString}
     * @return The pattern.
     */
    public static List<LockPatternView.Cell> stringToPattern(String string) {
        List<LockPatternView.Cell> result = Lists.newArrayList();

        final byte[] bytes = string.getBytes();
        for (int i = 0; i < bytes.length; i++) {
            byte b = bytes[i];
            result.add(LockPatternView.Cell.of(b / 3, b % 3));
        }
        return result;
    }

    /**
     * Serialize a pattern.
     * @param pattern The pattern.
     * @return The pattern in string form.
     */
    public static String patternToString(List<LockPatternView.Cell> pattern) {
        if (pattern == null) {
            return "";
        }
        final int patternSize = pattern.size();

        byte[] res = new byte[patternSize];
        for (int i = 0; i < patternSize; i++) {
            LockPatternView.Cell cell = pattern.get(i);
            res[i] = (byte) (cell.getRow() * 3 + cell.getColumn());
        }
        return new String(res);
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

    /*
     * Generate a hash for the given password. To avoid brute force attacks, we use a salted hash.
     * Not the most secure, but it is at least a second level of protection. First level is that
     * the file is in a location only readable by the system process.
     * @param password the gesture pattern.
     * @return the hash of the pattern in a byte array.
     */
    public byte[] passwordToHash(String password, int userId) {
        if (password == null) {
            return null;
        }
        String algo = null;
        byte[] hashed = null;
        try {
            byte[] saltedPassword = (password + getSalt(userId)).getBytes();
            byte[] sha1 = MessageDigest.getInstance(algo = "SHA-1").digest(saltedPassword);
            byte[] md5 = MessageDigest.getInstance(algo = "MD5").digest(saltedPassword);
            hashed = (toHex(sha1) + toHex(md5)).getBytes();
        } catch (NoSuchAlgorithmException e) {
            Log.w(TAG, "Failed to encode string because of missing algorithm: " + algo);
        }
        return hashed;
    }

    private static String toHex(byte[] ary) {
        final String hex = "0123456789ABCDEF";
        String ret = "";
        for (int i = 0; i < ary.length; i++) {
            ret += hex.charAt((ary[i] >> 4) & 0xf);
            ret += hex.charAt(ary[i] & 0xf);
        }
        return ret;
    }

    /**
     * @return Whether the lock password is enabled, or if it is set as a backup for biometric weak
     */
    public boolean isLockPasswordEnabled() {
        long mode = getLong(PASSWORD_TYPE_KEY, 0);
        long backupMode = getLong(PASSWORD_TYPE_ALTERNATE_KEY, 0);
        final boolean passwordEnabled = mode == DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
                || mode == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                || mode == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX
                || mode == DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                || mode == DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
        final boolean backupEnabled = backupMode == DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
                || backupMode == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                || backupMode == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX
                || backupMode == DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                || backupMode == DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;

        return savedPasswordExists() && (passwordEnabled ||
                (usingBiometricWeak() && backupEnabled));
    }

    /**
     * @return Whether the lock pattern is enabled, or if it is set as a backup for biometric weak
     */
    public boolean isLockPatternEnabled() {
        return isLockPatternEnabled(getCurrentOrCallingUserId());
    }

    /**
     * @return Whether the lock pattern is enabled, or if it is set as a backup for biometric weak
     */
    public boolean isLockPatternEnabled(int userId) {
        final boolean backupEnabled =
                getLong(PASSWORD_TYPE_ALTERNATE_KEY,
                        DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userId)
                                == DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;

        return getBoolean(Settings.Secure.LOCK_PATTERN_ENABLED, false, userId)
                && (getLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
                        userId) == DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
                        || (usingBiometricWeak(userId) && backupEnabled));
    }

    /**
     * @return Whether biometric weak lock is installed and that the front facing camera exists
     */
    public boolean isBiometricWeakInstalled() {
        // Check that it's installed
        PackageManager pm = mContext.getPackageManager();
        try {
            pm.getPackageInfo("com.android.facelock", PackageManager.GET_ACTIVITIES);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }

        // Check that the camera is enabled
        if (!pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)) {
            return false;
        }
        if (getDevicePolicyManager().getCameraDisabled(null, getCurrentOrCallingUserId())) {
            return false;
        }

        // TODO: If we decide not to proceed with Face Unlock as a trustlet, this must be changed
        // back to returning true.  If we become certain that Face Unlock will be a trustlet, this
        // entire function and a lot of other code can be removed.
        if (DEBUG) Log.d(TAG, "Forcing isBiometricWeakInstalled() to return false to disable it");
        return false;
    }

    /**
     * Set whether biometric weak liveliness is enabled.
     */
    public void setBiometricWeakLivelinessEnabled(boolean enabled) {
        long currentFlag = getLong(Settings.Secure.LOCK_BIOMETRIC_WEAK_FLAGS, 0L);
        long newFlag;
        if (enabled) {
            newFlag = currentFlag | FLAG_BIOMETRIC_WEAK_LIVELINESS;
        } else {
            newFlag = currentFlag & ~FLAG_BIOMETRIC_WEAK_LIVELINESS;
        }
        setLong(Settings.Secure.LOCK_BIOMETRIC_WEAK_FLAGS, newFlag);
    }

    /**
     * @return Whether the biometric weak liveliness is enabled.
     */
    public boolean isBiometricWeakLivelinessEnabled() {
        long currentFlag = getLong(Settings.Secure.LOCK_BIOMETRIC_WEAK_FLAGS, 0L);
        return ((currentFlag & FLAG_BIOMETRIC_WEAK_LIVELINESS) != 0);
    }

    /**
     * Set whether the lock pattern is enabled.
     */
    public void setLockPatternEnabled(boolean enabled) {
        setLockPatternEnabled(enabled, getCurrentOrCallingUserId());
    }

    /**
     * Set whether the lock pattern is enabled.
     */
    public void setLockPatternEnabled(boolean enabled, int userHandle) {
        setBoolean(Settings.Secure.LOCK_PATTERN_ENABLED, enabled, userHandle);
    }

    /**
     * @return Whether the visible pattern is enabled.
     */
    public boolean isVisiblePatternEnabled() {
        return getBoolean(Settings.Secure.LOCK_PATTERN_VISIBLE, false);
    }

    /**
     * Set whether the visible pattern is enabled.
     */
    public void setVisiblePatternEnabled(boolean enabled) {
        setBoolean(Settings.Secure.LOCK_PATTERN_VISIBLE, enabled);

        // Update for crypto if owner
        int userId = getCurrentOrCallingUserId();
        if (userId != UserHandle.USER_OWNER) {
            return;
        }

        IBinder service = ServiceManager.getService("mount");
        if (service == null) {
            Log.e(TAG, "Could not find the mount service to update the user info");
            return;
        }

        IMountService mountService = IMountService.Stub.asInterface(service);
        try {
            mountService.setField(StorageManager.PATTERN_VISIBLE_KEY, enabled ? "1" : "0");
        } catch (RemoteException e) {
            Log.e(TAG, "Error changing pattern visible state", e);
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
    public long setLockoutAttemptDeadline() {
        final long deadline = SystemClock.elapsedRealtime() + FAILED_ATTEMPT_TIMEOUT_MS;
        setLong(LOCKOUT_ATTEMPT_DEADLINE, deadline);
        return deadline;
    }

    /**
     * @return The elapsed time in millis in the future when the user is allowed to
     *   attempt to enter his/her lock pattern, or 0 if the user is welcome to
     *   enter a pattern.
     */
    public long getLockoutAttemptDeadline() {
        final long deadline = getLong(LOCKOUT_ATTEMPT_DEADLINE, 0L);
        final long now = SystemClock.elapsedRealtime();
        if (deadline < now || deadline > (now + FAILED_ATTEMPT_TIMEOUT_MS)) {
            return 0L;
        }
        return deadline;
    }

    /**
     * @return Whether the user is permanently locked out until they verify their
     *   credentials.  Occurs after {@link #FAILED_ATTEMPTS_BEFORE_RESET} failed
     *   attempts.
     */
    public boolean isPermanentlyLocked() {
        return getBoolean(LOCKOUT_PERMANENT_KEY, false);
    }

    /**
     * Set the state of whether the device is permanently locked, meaning the user
     * must authenticate via other means.
     *
     * @param locked Whether the user is permanently locked out until they verify their
     *   credentials.  Occurs after {@link #FAILED_ATTEMPTS_BEFORE_RESET} failed
     *   attempts.
     */
    public void setPermanentlyLocked(boolean locked) {
        setBoolean(LOCKOUT_PERMANENT_KEY, locked);
    }

    public boolean isEmergencyCallCapable() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);
    }

    public boolean isPukUnlockScreenEnable() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enable_puk_unlock_screen);
    }

    public boolean isEmergencyCallEnabledWhileSimLocked() {
        return mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_enable_emergency_call_while_sim_locked);
    }

    /**
     * @return A formatted string of the next alarm (for showing on the lock screen),
     *   or null if there is no next alarm.
     */
    public AlarmManager.AlarmClockInfo getNextAlarm() {
        AlarmManager alarmManager = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        return alarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
    }

    private boolean getBoolean(String secureSettingKey, boolean defaultValue, int userId) {
        try {
            return getLockSettings().getBoolean(secureSettingKey, defaultValue, userId);
        } catch (RemoteException re) {
            return defaultValue;
        }
    }

    private boolean getBoolean(String secureSettingKey, boolean defaultValue) {
        return getBoolean(secureSettingKey, defaultValue, getCurrentOrCallingUserId());
    }

    private void setBoolean(String secureSettingKey, boolean enabled, int userId) {
        try {
            getLockSettings().setBoolean(secureSettingKey, enabled, userId);
        } catch (RemoteException re) {
            // What can we do?
            Log.e(TAG, "Couldn't write boolean " + secureSettingKey + re);
        }
    }

    private void setBoolean(String secureSettingKey, boolean enabled) {
        setBoolean(secureSettingKey, enabled, getCurrentOrCallingUserId());
    }

    public int[] getAppWidgets() {
        return getAppWidgets(UserHandle.USER_CURRENT);
    }

    private int[] getAppWidgets(int userId) {
        String appWidgetIdString = Settings.Secure.getStringForUser(
                mContentResolver, Settings.Secure.LOCK_SCREEN_APPWIDGET_IDS, userId);
        String delims = ",";
        if (appWidgetIdString != null && appWidgetIdString.length() > 0) {
            String[] appWidgetStringIds = appWidgetIdString.split(delims);
            int[] appWidgetIds = new int[appWidgetStringIds.length];
            for (int i = 0; i < appWidgetStringIds.length; i++) {
                String appWidget = appWidgetStringIds[i];
                try {
                    appWidgetIds[i] = Integer.decode(appWidget);
                } catch (NumberFormatException e) {
                    Log.d(TAG, "Error when parsing widget id " + appWidget);
                    return null;
                }
            }
            return appWidgetIds;
        }
        return new int[0];
    }

    private static String combineStrings(int[] list, String separator) {
        int listLength = list.length;

        switch (listLength) {
            case 0: {
                return "";
            }
            case 1: {
                return Integer.toString(list[0]);
            }
        }

        int strLength = 0;
        int separatorLength = separator.length();

        String[] stringList = new String[list.length];
        for (int i = 0; i < listLength; i++) {
            stringList[i] = Integer.toString(list[i]);
            strLength += stringList[i].length();
            if (i < listLength - 1) {
                strLength += separatorLength;
            }
        }

        StringBuilder sb = new StringBuilder(strLength);

        for (int i = 0; i < listLength; i++) {
            sb.append(list[i]);
            if (i < listLength - 1) {
                sb.append(separator);
            }
        }

        return sb.toString();
    }

    // appwidget used when appwidgets are disabled (we make an exception for
    // default clock widget)
    public void writeFallbackAppWidgetId(int appWidgetId) {
        Settings.Secure.putIntForUser(mContentResolver,
                Settings.Secure.LOCK_SCREEN_FALLBACK_APPWIDGET_ID,
                appWidgetId,
                UserHandle.USER_CURRENT);
    }

    // appwidget used when appwidgets are disabled (we make an exception for
    // default clock widget)
    public int getFallbackAppWidgetId() {
        return Settings.Secure.getIntForUser(
                mContentResolver,
                Settings.Secure.LOCK_SCREEN_FALLBACK_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
                UserHandle.USER_CURRENT);
    }

    private void writeAppWidgets(int[] appWidgetIds) {
        Settings.Secure.putStringForUser(mContentResolver,
                        Settings.Secure.LOCK_SCREEN_APPWIDGET_IDS,
                        combineStrings(appWidgetIds, ","),
                        UserHandle.USER_CURRENT);
    }

    // TODO: log an error if this returns false
    public boolean addAppWidget(int widgetId, int index) {
        int[] widgets = getAppWidgets();
        if (widgets == null) {
            return false;
        }
        if (index < 0 || index > widgets.length) {
            return false;
        }
        int[] newWidgets = new int[widgets.length + 1];
        for (int i = 0, j = 0; i < newWidgets.length; i++) {
            if (index == i) {
                newWidgets[i] = widgetId;
                i++;
            }
            if (i < newWidgets.length) {
                newWidgets[i] = widgets[j];
                j++;
            }
        }
        writeAppWidgets(newWidgets);
        return true;
    }

    public boolean removeAppWidget(int widgetId) {
        int[] widgets = getAppWidgets();

        if (widgets.length == 0) {
            return false;
        }

        int[] newWidgets = new int[widgets.length - 1];
        for (int i = 0, j = 0; i < widgets.length; i++) {
            if (widgets[i] == widgetId) {
                // continue...
            } else if (j >= newWidgets.length) {
                // we couldn't find the widget
                return false;
            } else {
                newWidgets[j] = widgets[i];
                j++;
            }
        }
        writeAppWidgets(newWidgets);
        return true;
    }

    private long getLong(String secureSettingKey, long defaultValue, int userHandle) {
        try {
            return getLockSettings().getLong(secureSettingKey, defaultValue, userHandle);
        } catch (RemoteException re) {
            return defaultValue;
        }
    }

    private long getLong(String secureSettingKey, long defaultValue) {
        try {
            return getLockSettings().getLong(secureSettingKey, defaultValue,
                    getCurrentOrCallingUserId());
        } catch (RemoteException re) {
            return defaultValue;
        }
    }

    private void setLong(String secureSettingKey, long value) {
        setLong(secureSettingKey, value, getCurrentOrCallingUserId());
    }

    private void setLong(String secureSettingKey, long value, int userHandle) {
        try {
            getLockSettings().setLong(secureSettingKey, value, userHandle);
        } catch (RemoteException re) {
            // What can we do?
            Log.e(TAG, "Couldn't write long " + secureSettingKey + re);
        }
    }

    private String getString(String secureSettingKey) {
        return getString(secureSettingKey, getCurrentOrCallingUserId());
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

    public boolean isSecure() {
        return isSecure(getCurrentOrCallingUserId());
    }

    public boolean isSecure(int userId) {
        long mode = getKeyguardStoredPasswordQuality(userId);
        final boolean isPattern = mode == DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
        final boolean isPassword = mode == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                || mode == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX
                || mode == DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
                || mode == DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                || mode == DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
        final boolean secure =
                isPattern && isLockPatternEnabled(userId) && savedPatternExists(userId)
                || isPassword && savedPasswordExists(userId);
        return secure;
    }

    /**
     * Sets the emergency button visibility based on isEmergencyCallCapable().
     *
     * If the emergency button is visible, sets the text on the emergency button
     * to indicate what action will be taken.
     *
     * If there's currently a call in progress, the button will take them to the call
     * @param button The button to update
     * @param shown Indicates whether the given screen wants the emergency button to show at all
     * @param showIcon Indicates whether to show a phone icon for the button.
     */
    public void updateEmergencyCallButtonState(Button button, boolean shown, boolean showIcon) {
        if (isEmergencyCallCapable() && shown) {
            button.setVisibility(View.VISIBLE);
        } else {
            button.setVisibility(View.GONE);
            return;
        }

        int textId;
        if (isInCall()) {
            // show "return to call" text and show phone icon
            textId = R.string.lockscreen_return_to_call;
            int phoneCallIcon = showIcon ? R.drawable.stat_sys_phone_call : 0;
            button.setCompoundDrawablesWithIntrinsicBounds(phoneCallIcon, 0, 0, 0);
        } else {
            textId = R.string.lockscreen_emergency_call;
            int emergencyIcon = showIcon ? R.drawable.ic_emergency : 0;
            button.setCompoundDrawablesWithIntrinsicBounds(emergencyIcon, 0, 0, 0);
        }
        button.setText(textId);
    }

    /**
     * Resumes a call in progress. Typically launched from the EmergencyCall button
     * on various lockscreens.
     */
    public void resumeCall() {
        getTelecommManager().showInCallScreen(false);
    }

    /**
     * @return {@code true} if there is a call currently in progress, {@code false} otherwise.
     */
    public boolean isInCall() {
        return getTelecommManager().isInCall();
    }

    private TelecomManager getTelecommManager() {
        return (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
    }

    private void finishBiometricWeak(int userId) {
        setBoolean(BIOMETRIC_WEAK_EVER_CHOSEN_KEY, true, userId);

        // Launch intent to show final screen, this also
        // moves the temporary gallery to the actual gallery
        Intent intent = new Intent();
        intent.setClassName("com.android.facelock",
                "com.android.facelock.SetupEndScreen");
        mContext.startActivityAsUser(intent, new UserHandle(userId));
    }

    public void setPowerButtonInstantlyLocks(boolean enabled) {
        setBoolean(LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS, enabled);
    }

    public boolean getPowerButtonInstantlyLocks() {
        return getBoolean(LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS, true);
    }

    public static boolean isSafeModeEnabled() {
        try {
            return IWindowManager.Stub.asInterface(
                    ServiceManager.getService("window")).isSafeModeEnabled();
        } catch (RemoteException e) {
            // Shouldn't happen!
        }
        return false;
    }

    /**
     * Determine whether the user has selected any non-system widgets in keyguard
     *
     * @return true if widgets have been selected
     */
    public boolean hasWidgetsEnabledInKeyguard(int userid) {
        int widgets[] = getAppWidgets(userid);
        for (int i = 0; i < widgets.length; i++) {
            if (widgets[i] > 0) {
                return true;
            }
        }
        return false;
    }

    public boolean getWidgetsEnabled() {
        return getWidgetsEnabled(getCurrentOrCallingUserId());
    }

    public boolean getWidgetsEnabled(int userId) {
        return getBoolean(LOCKSCREEN_WIDGETS_ENABLED, false, userId);
    }

    public void setWidgetsEnabled(boolean enabled) {
        setWidgetsEnabled(enabled, getCurrentOrCallingUserId());
    }

    public void setWidgetsEnabled(boolean enabled, int userId) {
        setBoolean(LOCKSCREEN_WIDGETS_ENABLED, enabled, userId);
    }

    public void setEnabledTrustAgents(Collection<ComponentName> activeTrustAgents) {
        setEnabledTrustAgents(activeTrustAgents, getCurrentOrCallingUserId());
    }

    public List<ComponentName> getEnabledTrustAgents() {
        return getEnabledTrustAgents(getCurrentOrCallingUserId());
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
        getTrustManager().reportEnabledTrustAgentsChanged(getCurrentOrCallingUserId());
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
     * @see android.app.trust.TrustManager#reportRequireCredentialEntry(int)
     */
    public void requireCredentialEntry(int userId) {
        getTrustManager().reportRequireCredentialEntry(userId);
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
        if (getCurrentUser() != UserHandle.USER_OWNER) {
            Log.w(TAG, "Only device owner may call setCredentialRequiredForDecrypt()");
            return;
        }
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.REQUIRE_PASSWORD_TO_DECRYPT, required ? 1 : 0);
    }
}
