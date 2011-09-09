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

import com.android.internal.R;
import com.android.internal.telephony.ITelephony;
import com.google.android.collect.Lists;

import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.FileObserver;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.storage.IMountService;
import android.provider.Settings;
import android.security.KeyStore;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Utilities for the lock pattern and its settings.
 */
public class LockPatternUtils {

    private static final String TAG = "LockPatternUtils";

    private static final String SYSTEM_DIRECTORY = "/system/";
    private static final String LOCK_PATTERN_FILE = "gesture.key";
    private static final String LOCK_PASSWORD_FILE = "password.key";

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

    private final static String LOCKOUT_PERMANENT_KEY = "lockscreen.lockedoutpermanently";
    private final static String LOCKOUT_ATTEMPT_DEADLINE = "lockscreen.lockoutattemptdeadline";
    private final static String PATTERN_EVER_CHOSEN_KEY = "lockscreen.patterneverchosen";
    public final static String PASSWORD_TYPE_KEY = "lockscreen.password_type";
    public static final String PASSWORD_TYPE_ALTERNATE_KEY = "lockscreen.password_type_alternate";
    private final static String LOCK_PASSWORD_SALT_KEY = "lockscreen.password_salt";
    private final static String DISABLE_LOCKSCREEN_KEY = "lockscreen.disabled";
    public final static String LOCKSCREEN_BIOMETRIC_WEAK_FALLBACK
            = "lockscreen.biometric_weak_fallback";

    private final static String PASSWORD_HISTORY_KEY = "lockscreen.passwordhistory";

    private final Context mContext;
    private final ContentResolver mContentResolver;
    private DevicePolicyManager mDevicePolicyManager;
    private static String sLockPatternFilename;
    private static String sLockPasswordFilename;

    private static final AtomicBoolean sHaveNonZeroPatternFile = new AtomicBoolean(false);
    private static final AtomicBoolean sHaveNonZeroPasswordFile = new AtomicBoolean(false);

    private static FileObserver sPasswordObserver;

    private static class PasswordFileObserver extends FileObserver {
        public PasswordFileObserver(String path, int mask) {
            super(path, mask);
        }

        @Override
        public void onEvent(int event, String path) {
            if (LOCK_PATTERN_FILE.equals(path)) {
                Log.d(TAG, "lock pattern file changed");
                sHaveNonZeroPatternFile.set(new File(sLockPatternFilename).length() > 0);
            } else if (LOCK_PASSWORD_FILE.equals(path)) {
                Log.d(TAG, "lock password file changed");
                sHaveNonZeroPasswordFile.set(new File(sLockPasswordFilename).length() > 0);
            }
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
    /**
     * @param contentResolver Used to look up and save settings.
     */
    public LockPatternUtils(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();

        // Initialize the location of gesture & PIN lock files
        if (sLockPatternFilename == null) {
            String dataSystemDirectory =
                    android.os.Environment.getDataDirectory().getAbsolutePath() +
                    SYSTEM_DIRECTORY;
            sLockPatternFilename =  dataSystemDirectory + LOCK_PATTERN_FILE;
            sLockPasswordFilename = dataSystemDirectory + LOCK_PASSWORD_FILE;
            sHaveNonZeroPatternFile.set(new File(sLockPatternFilename).length() > 0);
            sHaveNonZeroPasswordFile.set(new File(sLockPasswordFilename).length() > 0);
            int fileObserverMask = FileObserver.CLOSE_WRITE | FileObserver.DELETE |
                    FileObserver.MOVED_TO | FileObserver.CREATE;
            sPasswordObserver = new PasswordFileObserver(dataSystemDirectory, fileObserverMask);
            sPasswordObserver.startWatching();
        }
    }

    public int getRequestedMinimumPasswordLength() {
        return getDevicePolicyManager().getPasswordMinimumLength(null);
    }


    /**
     * Gets the device policy password mode. If the mode is non-specific, returns
     * MODE_PATTERN which allows the user to choose anything.
     */
    public int getRequestedPasswordQuality() {
        return getDevicePolicyManager().getPasswordQuality(null);
    }

    public int getRequestedPasswordHistoryLength() {
        return getDevicePolicyManager().getPasswordHistoryLength(null);
    }

    public int getRequestedPasswordMinimumLetters() {
        return getDevicePolicyManager().getPasswordMinimumLetters(null);
    }

    public int getRequestedPasswordMinimumUpperCase() {
        return getDevicePolicyManager().getPasswordMinimumUpperCase(null);
    }

    public int getRequestedPasswordMinimumLowerCase() {
        return getDevicePolicyManager().getPasswordMinimumLowerCase(null);
    }

    public int getRequestedPasswordMinimumNumeric() {
        return getDevicePolicyManager().getPasswordMinimumNumeric(null);
    }

    public int getRequestedPasswordMinimumSymbols() {
        return getDevicePolicyManager().getPasswordMinimumSymbols(null);
    }

    public int getRequestedPasswordMinimumNonLetter() {
        return getDevicePolicyManager().getPasswordMinimumNonLetter(null);
    }
    /**
     * Returns the actual password mode, as set by keyguard after updating the password.
     *
     * @return
     */
    public void reportFailedPasswordAttempt() {
        getDevicePolicyManager().reportFailedPasswordAttempt();
    }

    public void reportSuccessfulPasswordAttempt() {
        getDevicePolicyManager().reportSuccessfulPasswordAttempt();
    }

    /**
     * Check to see if a pattern matches the saved pattern.  If no pattern exists,
     * always returns true.
     * @param pattern The pattern to check.
     * @return Whether the pattern matches the stored one.
     */
    public boolean checkPattern(List<LockPatternView.Cell> pattern) {
        try {
            // Read all the bytes from the file
            RandomAccessFile raf = new RandomAccessFile(sLockPatternFilename, "r");
            final byte[] stored = new byte[(int) raf.length()];
            int got = raf.read(stored, 0, stored.length);
            raf.close();
            if (got <= 0) {
                return true;
            }
            // Compare the hash from the file with the entered pattern's hash
            return Arrays.equals(stored, LockPatternUtils.patternToHash(pattern));
        } catch (FileNotFoundException fnfe) {
            return true;
        } catch (IOException ioe) {
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
        try {
            // Read all the bytes from the file
            RandomAccessFile raf = new RandomAccessFile(sLockPasswordFilename, "r");
            final byte[] stored = new byte[(int) raf.length()];
            int got = raf.read(stored, 0, stored.length);
            raf.close();
            if (got <= 0) {
                return true;
            }
            // Compare the hash from the file with the entered password's hash
            return Arrays.equals(stored, passwordToHash(password));
        } catch (FileNotFoundException fnfe) {
            return true;
        } catch (IOException ioe) {
            return true;
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
        String passwordHashString = new String(passwordToHash(password));
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
        return sHaveNonZeroPatternFile.get();
    }

    /**
     * Check to see if the user has stored a lock pattern.
     * @return Whether a saved pattern exists.
     */
    public boolean savedPasswordExists() {
        return sHaveNonZeroPasswordFile.get();
    }

    /**
     * Return true if the user has ever chosen a pattern.  This is true even if the pattern is
     * currently cleared.
     *
     * @return True if the user has ever chosen a pattern.
     */
    public boolean isPatternEverChosen() {
        return getBoolean(PATTERN_EVER_CHOSEN_KEY);
    }

    /**
     * Used by device policy manager to validate the current password
     * information it has.
     */
    public int getActivePasswordQuality() {
        int activePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
        switch (getKeyguardStoredPasswordQuality()) {
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
                if (isLockPatternEnabled()) {
                    activePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
                }
                break;
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
                if (isLockPasswordEnabled()) {
                    activePasswordQuality = DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
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

    /**
     * Clear any lock pattern or password.
     */
    public void clearLock() {
        saveLockPassword(null, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);
        setLockPatternEnabled(false);
        saveLockPattern(null);
        setLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);
        setLong(PASSWORD_TYPE_ALTERNATE_KEY, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED);
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
        return !isSecure() && getLong(DISABLE_LOCKSCREEN_KEY, 0) != 0;
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
     * @param isFallback Specifies if this is a fallback to biometric weak
     */
    public void saveLockPattern(List<LockPatternView.Cell> pattern, boolean isFallback) {
        // Compute the hash
        final byte[] hash = LockPatternUtils.patternToHash(pattern);
        try {
            // Write the hash to file
            RandomAccessFile raf = new RandomAccessFile(sLockPatternFilename, "rw");
            // Truncate the file if pattern is null, to clear the lock
            if (pattern == null) {
                raf.setLength(0);
            } else {
                raf.write(hash, 0, hash.length);
            }
            raf.close();
            DevicePolicyManager dpm = getDevicePolicyManager();
            KeyStore keyStore = KeyStore.getInstance();
            if (pattern != null) {
                keyStore.password(patternToString(pattern));
                setBoolean(PATTERN_EVER_CHOSEN_KEY, true);
                if (!isFallback) {
                    setLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);
                } else {
                    setLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK);
                    setLong(PASSWORD_TYPE_ALTERNATE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);
                }
                dpm.setActivePasswordState(DevicePolicyManager.PASSWORD_QUALITY_SOMETHING, pattern
                        .size(), 0, 0, 0, 0, 0, 0);
            } else {
                if (keyStore.isEmpty()) {
                    keyStore.reset();
                }
                dpm.setActivePasswordState(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, 0, 0,
                        0, 0, 0, 0, 0);
            }
        } catch (FileNotFoundException fnfe) {
            // Cant do much, unless we want to fail over to using the settings
            // provider
            Log.e(TAG, "Unable to save lock pattern to " + sLockPatternFilename);
        } catch (IOException ioe) {
            // Cant do much
            Log.e(TAG, "Unable to save lock pattern to " + sLockPatternFilename);
        }
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
            return DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
        }
        return DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED;
    }

    /** Update the encryption password if it is enabled **/
    private void updateEncryptionPassword(String password) {
        DevicePolicyManager dpm = getDevicePolicyManager();
        if (dpm.getStorageEncryptionStatus() != DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE) {
            return;
        }

        IBinder service = ServiceManager.getService("mount");
        if (service == null) {
            Log.e(TAG, "Could not find the mount service to update the encryption password");
            return;
        }

        IMountService mountService = IMountService.Stub.asInterface(service);
        try {
            mountService.changeEncryptionPassword(password);
        } catch (RemoteException e) {
            Log.e(TAG, "Error changing encryption password", e);
        }
    }

    /**
     * Save a lock password.  Does not ensure that the password is as good
     * as the requested mode, but will adjust the mode to be as good as the
     * pattern.
     * @param password The password to save
     * @param quality {@see DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     */
    public void saveLockPassword(String password, int quality) {
        this.saveLockPassword(password, quality, false);
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
        // Compute the hash
        final byte[] hash = passwordToHash(password);
        try {
            // Write the hash to file
            RandomAccessFile raf = new RandomAccessFile(sLockPasswordFilename, "rw");
            // Truncate the file if pattern is null, to clear the lock
            if (password == null) {
                raf.setLength(0);
            } else {
                raf.write(hash, 0, hash.length);
            }
            raf.close();
            DevicePolicyManager dpm = getDevicePolicyManager();
            KeyStore keyStore = KeyStore.getInstance();
            if (password != null) {
                // Update the encryption password.
                updateEncryptionPassword(password);

                // Update the keystore password
                keyStore.password(password);

                int computedQuality = computePasswordQuality(password);
                if (!isFallback) {
                    setLong(PASSWORD_TYPE_KEY, Math.max(quality, computedQuality));
                } else {
                    setLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK);
                    setLong(PASSWORD_TYPE_ALTERNATE_KEY, Math.max(quality, computedQuality));
                }
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
                    dpm.setActivePasswordState(Math.max(quality, computedQuality), password
                            .length(), letters, uppercase, lowercase, numbers, symbols, nonletter);
                } else {
                    // The password is not anything.
                    dpm.setActivePasswordState(
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, 0, 0, 0, 0, 0, 0, 0);
                }
                // Add the password to the password history. We assume all
                // password
                // hashes have the same length for simplicity of implementation.
                String passwordHistory = getString(PASSWORD_HISTORY_KEY);
                if (passwordHistory == null) {
                    passwordHistory = new String();
                }
                int passwordHistoryLength = getRequestedPasswordHistoryLength();
                if (passwordHistoryLength == 0) {
                    passwordHistory = "";
                } else {
                    passwordHistory = new String(hash) + "," + passwordHistory;
                    // Cut it to contain passwordHistoryLength hashes
                    // and passwordHistoryLength -1 commas.
                    passwordHistory = passwordHistory.substring(0, Math.min(hash.length
                            * passwordHistoryLength + passwordHistoryLength - 1, passwordHistory
                            .length()));
                }
                setString(PASSWORD_HISTORY_KEY, passwordHistory);
            } else {
                // Conditionally reset the keystore if empty. If
                // non-empty, we are just switching key guard type
                if (keyStore.isEmpty()) {
                    keyStore.reset();
                }
                dpm.setActivePasswordState(
                        DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, 0, 0, 0, 0, 0, 0, 0);
            }
        } catch (FileNotFoundException fnfe) {
            // Cant do much, unless we want to fail over to using the settings provider
            Log.e(TAG, "Unable to save lock pattern to " + sLockPasswordFilename);
        } catch (IOException ioe) {
            // Cant do much
            Log.e(TAG, "Unable to save lock pattern to " + sLockPasswordFilename);
        }
    }

    /**
     * Retrieves the quality mode we're in.
     * {@see DevicePolicyManager#getPasswordQuality(android.content.ComponentName)}
     *
     * @return stored password quality
     */
    public int getKeyguardStoredPasswordQuality() {
        int quality =
                (int) getLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);
        // If the user has chosen to use weak biometric sensor, then return the backup locking
        // method and treat biometric as a special case.
        if (quality == DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK) {
            quality =
                (int) getLong(PASSWORD_TYPE_ALTERNATE_KEY,
                        DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);
        }
        return quality;
    }

    public boolean usingBiometricWeak() {
        int quality =
                (int) getLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING);
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
    private static byte[] patternToHash(List<LockPatternView.Cell> pattern) {
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

    private String getSalt() {
        long salt = getLong(LOCK_PASSWORD_SALT_KEY, 0);
        if (salt == 0) {
            try {
                salt = SecureRandom.getInstance("SHA1PRNG").nextLong();
                setLong(LOCK_PASSWORD_SALT_KEY, salt);
                Log.v(TAG, "Initialized lock password salt");
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
    public byte[] passwordToHash(String password) {
        if (password == null) {
            return null;
        }
        String algo = null;
        byte[] hashed = null;
        try {
            byte[] saltedPassword = (password + getSalt()).getBytes();
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
     * @return Whether the lock password is enabled.
     */
    public boolean isLockPasswordEnabled() {
        long mode = getLong(PASSWORD_TYPE_KEY, 0);
        return savedPasswordExists() &&
                (mode == DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
                        || mode == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                        || mode == DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                        || mode == DevicePolicyManager.PASSWORD_QUALITY_COMPLEX);
    }

    /**
     * @return Whether the lock pattern is enabled.
     */
    public boolean isLockPatternEnabled() {
        return getBoolean(Settings.Secure.LOCK_PATTERN_ENABLED)
                && getLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING)
                        == DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
    }

    /**
     * @return Whether biometric weak lock is enabled.
     */
    public boolean isBiometricEnabled() {
        // TODO: check if it's installed
        return getLong(PASSWORD_TYPE_KEY, DevicePolicyManager.PASSWORD_QUALITY_SOMETHING)
                == DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK;
    }

    /**
     * Set whether the lock pattern is enabled.
     */
    public void setLockPatternEnabled(boolean enabled) {
        setBoolean(Settings.Secure.LOCK_PATTERN_ENABLED, enabled);
    }

    /**
     * @return Whether the visible pattern is enabled.
     */
    public boolean isVisiblePatternEnabled() {
        return getBoolean(Settings.Secure.LOCK_PATTERN_VISIBLE);
    }

    /**
     * Set whether the visible pattern is enabled.
     */
    public void setVisiblePatternEnabled(boolean enabled) {
        setBoolean(Settings.Secure.LOCK_PATTERN_VISIBLE, enabled);
    }

    /**
     * @return Whether tactile feedback for the pattern is enabled.
     */
    public boolean isTactileFeedbackEnabled() {
        return getBoolean(Settings.Secure.LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED);
    }

    /**
     * Set whether tactile feedback for the pattern is enabled.
     */
    public void setTactileFeedbackEnabled(boolean enabled) {
        setBoolean(Settings.Secure.LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED, enabled);
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
        return getBoolean(LOCKOUT_PERMANENT_KEY);
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

    /**
     * @return A formatted string of the next alarm (for showing on the lock screen),
     *   or null if there is no next alarm.
     */
    public String getNextAlarm() {
        String nextAlarm = Settings.System.getString(mContentResolver,
                Settings.System.NEXT_ALARM_FORMATTED);
        if (nextAlarm == null || TextUtils.isEmpty(nextAlarm)) {
            return null;
        }
        return nextAlarm;
    }

    private boolean getBoolean(String secureSettingKey) {
        return 1 ==
                android.provider.Settings.Secure.getInt(mContentResolver, secureSettingKey, 0);
    }

    private void setBoolean(String secureSettingKey, boolean enabled) {
        android.provider.Settings.Secure.putInt(mContentResolver, secureSettingKey,
                                                enabled ? 1 : 0);
    }

    private long getLong(String secureSettingKey, long def) {
        return android.provider.Settings.Secure.getLong(mContentResolver, secureSettingKey, def);
    }

    private void setLong(String secureSettingKey, long value) {
        android.provider.Settings.Secure.putLong(mContentResolver, secureSettingKey, value);
    }

    private String getString(String secureSettingKey) {
        return android.provider.Settings.Secure.getString(mContentResolver, secureSettingKey);
    }

    private void setString(String secureSettingKey, String value) {
        android.provider.Settings.Secure.putString(mContentResolver, secureSettingKey, value);
    }

    public boolean isSecure() {
        long mode = getKeyguardStoredPasswordQuality();
        final boolean isPattern = mode == DevicePolicyManager.PASSWORD_QUALITY_SOMETHING;
        final boolean isPassword = mode == DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                || mode == DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC
                || mode == DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                || mode == DevicePolicyManager.PASSWORD_QUALITY_COMPLEX;
        final boolean secure = isPattern && isLockPatternEnabled() && savedPatternExists()
                || isPassword && savedPasswordExists()
                || usingBiometricWeak() && isBiometricEnabled();
        return secure;
    }

    /**
     * Sets the emergency button visibility based on isEmergencyCallCapable().
     *
     * If the emergency button is visible, sets the text on the emergency button
     * to indicate what action will be taken.
     *
     * If there's currently a call in progress, the button will take them to the call
     * @param button the button to update
     */
    public void updateEmergencyCallButtonState(Button button) {
        if (isEmergencyCallCapable()) {
            button.setVisibility(View.VISIBLE);
        } else {
            button.setVisibility(View.GONE);
            return;
        }

        int newState = TelephonyManager.getDefault().getCallState();
        int textId;
        if (newState == TelephonyManager.CALL_STATE_OFFHOOK) {
            // show "return to call" text and show phone icon
            textId = R.string.lockscreen_return_to_call;
            int phoneCallIcon = R.drawable.stat_sys_phone_call;
            button.setCompoundDrawablesWithIntrinsicBounds(phoneCallIcon, 0, 0, 0);
        } else {
            textId = R.string.lockscreen_emergency_call;
            int emergencyIcon = R.drawable.ic_emergency;
            button.setCompoundDrawablesWithIntrinsicBounds(emergencyIcon, 0, 0, 0);
        }
        button.setText(textId);
    }

    /**
     * Resumes a call in progress. Typically launched from the EmergencyCall button
     * on various lockscreens.
     *
     * @return true if we were able to tell InCallScreen to show.
     */
    public boolean resumeCall() {
        ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
        try {
            if (phone != null && phone.showCallScreen()) {
                return true;
            }
        } catch (RemoteException e) {
            // What can we do?
        }
        return false;
    }

    /**
     * Performs concentenation of PLMN/SPN
     * @param plmn
     * @param spn
     * @return
     */
    public static CharSequence getCarrierString(CharSequence plmn, CharSequence spn) {
        if (plmn != null && spn == null) {
            return plmn;
        } else if (plmn != null && spn != null) {
            return plmn + "|" + spn;
        } else if (plmn == null && spn != null) {
            return spn;
        } else {
            return "";
        }
    }
}
