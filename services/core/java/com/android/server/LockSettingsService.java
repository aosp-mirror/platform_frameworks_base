/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;

import static android.Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE;
import static android.content.Context.USER_SERVICE;
import static android.Manifest.permission.READ_PROFILE;

import android.database.sqlite.SQLiteDatabase;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.storage.IMountService;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.provider.Settings.SettingNotFoundException;
import android.security.KeyStore;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockPatternUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Keeps the lock pattern/password data and related settings for each user.
 * Used by LockPatternUtils. Needs to be a service because Settings app also needs
 * to be able to save lockscreen information for secondary users.
 * @hide
 */
public class LockSettingsService extends ILockSettings.Stub {

    private static final String PERMISSION = ACCESS_KEYGUARD_SECURE_STORAGE;

    private static final String TAG = "LockSettingsService";

    private final Context mContext;

    private final LockSettingsStorage mStorage;

    private LockPatternUtils mLockPatternUtils;
    private boolean mFirstCallToVold;

    public LockSettingsService(Context context) {
        mContext = context;
        // Open the database

        mLockPatternUtils = new LockPatternUtils(context);
        mFirstCallToVold = true;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_STARTING);
        mContext.registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter, null, null);

        mStorage = new LockSettingsStorage(context, new LockSettingsStorage.Callback() {
            @Override
            public void initialize(SQLiteDatabase db) {
                // Get the lockscreen default from a system property, if available
                boolean lockScreenDisable = SystemProperties.getBoolean(
                        "ro.lockscreen.disable.default", false);
                if (lockScreenDisable) {
                    mStorage.writeKeyValue(db, LockPatternUtils.DISABLE_LOCKSCREEN_KEY, "1", 0);
                }
            }
        });
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_ADDED.equals(intent.getAction())) {
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                final int userSysUid = UserHandle.getUid(userHandle, Process.SYSTEM_UID);
                final KeyStore ks = KeyStore.getInstance();

                // Clear up keystore in case anything was left behind by previous users
                ks.resetUid(userSysUid);

                // If this user has a parent, sync with its keystore password
                final UserManager um = (UserManager) mContext.getSystemService(USER_SERVICE);
                final UserInfo parentInfo = um.getProfileParent(userHandle);
                if (parentInfo != null) {
                    final int parentSysUid = UserHandle.getUid(parentInfo.id, Process.SYSTEM_UID);
                    ks.syncUid(parentSysUid, userSysUid);
                }
            } else if (Intent.ACTION_USER_STARTING.equals(intent.getAction())) {
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mStorage.prefetchUser(userHandle);
            }
        }
    };

    public void systemReady() {
        migrateOldData();
        mStorage.prefetchUser(UserHandle.USER_OWNER);
    }

    private void migrateOldData() {
        try {
            // These Settings moved before multi-user was enabled, so we only have to do it for the
            // root user.
            if (getString("migrated", null, 0) == null) {
                final ContentResolver cr = mContext.getContentResolver();
                for (String validSetting : VALID_SETTINGS) {
                    String value = Settings.Secure.getString(cr, validSetting);
                    if (value != null) {
                        setString(validSetting, value, 0);
                    }
                }
                // No need to move the password / pattern files. They're already in the right place.
                setString("migrated", "true", 0);
                Slog.i(TAG, "Migrated lock settings to new location");
            }

            // These Settings changed after multi-user was enabled, hence need to be moved per user.
            if (getString("migrated_user_specific", null, 0) == null) {
                final UserManager um = (UserManager) mContext.getSystemService(USER_SERVICE);
                final ContentResolver cr = mContext.getContentResolver();
                List<UserInfo> users = um.getUsers();
                for (int user = 0; user < users.size(); user++) {
                    // Migrate owner info
                    final int userId = users.get(user).id;
                    final String OWNER_INFO = Secure.LOCK_SCREEN_OWNER_INFO;
                    String ownerInfo = Settings.Secure.getStringForUser(cr, OWNER_INFO, userId);
                    if (ownerInfo != null) {
                        setString(OWNER_INFO, ownerInfo, userId);
                        Settings.Secure.putStringForUser(cr, ownerInfo, "", userId);
                    }

                    // Migrate owner info enabled.  Note there was a bug where older platforms only
                    // stored this value if the checkbox was toggled at least once. The code detects
                    // this case by handling the exception.
                    final String OWNER_INFO_ENABLED = Secure.LOCK_SCREEN_OWNER_INFO_ENABLED;
                    boolean enabled;
                    try {
                        int ivalue = Settings.Secure.getIntForUser(cr, OWNER_INFO_ENABLED, userId);
                        enabled = ivalue != 0;
                        setLong(OWNER_INFO_ENABLED, enabled ? 1 : 0, userId);
                    } catch (SettingNotFoundException e) {
                        // Setting was never stored. Store it if the string is not empty.
                        if (!TextUtils.isEmpty(ownerInfo)) {
                            setLong(OWNER_INFO_ENABLED, 1, userId);
                        }
                    }
                    Settings.Secure.putIntForUser(cr, OWNER_INFO_ENABLED, 0, userId);
                }
                // No need to move the password / pattern files. They're already in the right place.
                setString("migrated_user_specific", "true", 0);
                Slog.i(TAG, "Migrated per-user lock settings to new location");
            }
        } catch (RemoteException re) {
            Slog.e(TAG, "Unable to migrate old data", re);
        }
    }

    private final void checkWritePermission(int userId) {
        mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsWrite");
    }

    private final void checkPasswordReadPermission(int userId) {
        mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsRead");
    }

    private final void checkReadPermission(String requestedKey, int userId) {
        final int callingUid = Binder.getCallingUid();
        for (int i = 0; i < READ_PROFILE_PROTECTED_SETTINGS.length; i++) {
            String key = READ_PROFILE_PROTECTED_SETTINGS[i];
            if (key.equals(requestedKey) && mContext.checkCallingOrSelfPermission(READ_PROFILE)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("uid=" + callingUid
                        + " needs permission " + READ_PROFILE + " to read "
                        + requestedKey + " for user " + userId);
            }
        }
    }

    @Override
    public void setBoolean(String key, boolean value, int userId) throws RemoteException {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, value ? "1" : "0");
    }

    @Override
    public void setLong(String key, long value, int userId) throws RemoteException {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, Long.toString(value));
    }

    @Override
    public void setString(String key, String value, int userId) throws RemoteException {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, value);
    }

    private void setStringUnchecked(String key, int userId, String value) {
        mStorage.writeKeyValue(key, value, userId);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue, int userId) throws RemoteException {
        checkReadPermission(key, userId);

        String value = mStorage.readKeyValue(key, null, userId);
        return TextUtils.isEmpty(value) ?
                defaultValue : (value.equals("1") || value.equals("true"));
    }

    @Override
    public long getLong(String key, long defaultValue, int userId) throws RemoteException {
        checkReadPermission(key, userId);

        String value = mStorage.readKeyValue(key, null, userId);
        return TextUtils.isEmpty(value) ? defaultValue : Long.parseLong(value);
    }

    @Override
    public String getString(String key, String defaultValue, int userId) throws RemoteException {
        checkReadPermission(key, userId);

        return mStorage.readKeyValue(key, defaultValue, userId);
    }

    @Override
    public boolean havePassword(int userId) throws RemoteException {
        // Do we need a permissions check here?

        return mStorage.hasPassword(userId);
    }

    @Override
    public boolean havePattern(int userId) throws RemoteException {
        // Do we need a permissions check here?

        return mStorage.hasPattern(userId);
    }

    private void maybeUpdateKeystore(String password, int userHandle) {
        final UserManager um = (UserManager) mContext.getSystemService(USER_SERVICE);
        final KeyStore ks = KeyStore.getInstance();

        final List<UserInfo> profiles = um.getProfiles(userHandle);
        boolean shouldReset = TextUtils.isEmpty(password);

        // For historical reasons, don't wipe a non-empty keystore if we have a single user with a
        // single profile.
        if (userHandle == UserHandle.USER_OWNER && profiles.size() == 1) {
            if (!ks.isEmpty()) {
                shouldReset = false;
            }
        }

        for (UserInfo pi : profiles) {
            final int profileUid = UserHandle.getUid(pi.id, Process.SYSTEM_UID);
            if (shouldReset) {
                ks.resetUid(profileUid);
            } else {
                ks.passwordUid(password, profileUid);
            }
        }
    }

    @Override
    public void setLockPattern(String pattern, int userId) throws RemoteException {
        checkWritePermission(userId);

        maybeUpdateKeystore(pattern, userId);

        final byte[] hash = LockPatternUtils.patternToHash(
                LockPatternUtils.stringToPattern(pattern));
        mStorage.writePatternHash(hash, userId);
    }

    @Override
    public void setLockPassword(String password, int userId) throws RemoteException {
        checkWritePermission(userId);

        maybeUpdateKeystore(password, userId);

        mStorage.writePasswordHash(mLockPatternUtils.passwordToHash(password, userId), userId);
    }

    @Override
    public boolean checkPattern(String pattern, int userId) throws RemoteException {
        checkPasswordReadPermission(userId);
        byte[] hash = LockPatternUtils.patternToHash(LockPatternUtils.stringToPattern(pattern));
        byte[] storedHash = mStorage.readPatternHash(userId);

        if (storedHash == null) {
            return true;
        }

        boolean matched = Arrays.equals(hash, storedHash);
        if (matched && !TextUtils.isEmpty(pattern)) {
            maybeUpdateKeystore(pattern, userId);
        }
        return matched;
    }

    @Override
    public boolean checkPassword(String password, int userId) throws RemoteException {
        checkPasswordReadPermission(userId);

        byte[] hash = mLockPatternUtils.passwordToHash(password, userId);
        byte[] storedHash = mStorage.readPasswordHash(userId);

        if (storedHash == null) {
            return true;
        }

        boolean matched = Arrays.equals(hash, storedHash);
        if (matched && !TextUtils.isEmpty(password)) {
            maybeUpdateKeystore(password, userId);
        }
        return matched;
    }

    @Override
    public boolean checkVoldPassword(int userId) throws RemoteException {
        if (!mFirstCallToVold) {
            return false;
        }
        mFirstCallToVold = false;

        checkPasswordReadPermission(userId);

        // There's no guarantee that this will safely connect, but if it fails
        // we will simply show the lock screen when we shouldn't, so relatively
        // benign. There is an outside chance something nasty would happen if
        // this service restarted before vold stales out the password in this
        // case. The nastiness is limited to not showing the lock screen when
        // we should, within the first minute of decrypting the phone if this
        // service can't connect to vold, it restarts, and then the new instance
        // does successfully connect.
        final IMountService service = getMountService();
        String password = service.getPassword();
        service.clearPassword();
        if (password == null) {
            return false;
        }

        try {
            if (mLockPatternUtils.isLockPatternEnabled()) {
                if (checkPattern(password, userId)) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        try {
            if (mLockPatternUtils.isLockPasswordEnabled()) {
                if (checkPassword(password, userId)) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        return false;
    }

    @Override
    public void removeUser(int userId) {
        checkWritePermission(userId);

        mStorage.removeUser(userId);

        final KeyStore ks = KeyStore.getInstance();
        final int userUid = UserHandle.getUid(userId, Process.SYSTEM_UID);
        ks.resetUid(userUid);
    }

    private static final String[] VALID_SETTINGS = new String[] {
        LockPatternUtils.LOCKOUT_PERMANENT_KEY,
        LockPatternUtils.LOCKOUT_ATTEMPT_DEADLINE,
        LockPatternUtils.PATTERN_EVER_CHOSEN_KEY,
        LockPatternUtils.PASSWORD_TYPE_KEY,
        LockPatternUtils.PASSWORD_TYPE_ALTERNATE_KEY,
        LockPatternUtils.LOCK_PASSWORD_SALT_KEY,
        LockPatternUtils.DISABLE_LOCKSCREEN_KEY,
        LockPatternUtils.LOCKSCREEN_OPTIONS,
        LockPatternUtils.LOCKSCREEN_BIOMETRIC_WEAK_FALLBACK,
        LockPatternUtils.BIOMETRIC_WEAK_EVER_CHOSEN_KEY,
        LockPatternUtils.LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS,
        LockPatternUtils.PASSWORD_HISTORY_KEY,
        Secure.LOCK_PATTERN_ENABLED,
        Secure.LOCK_BIOMETRIC_WEAK_FLAGS,
        Secure.LOCK_PATTERN_VISIBLE,
        Secure.LOCK_PATTERN_TACTILE_FEEDBACK_ENABLED
    };

    // These are protected with a read permission
    private static final String[] READ_PROFILE_PROTECTED_SETTINGS = new String[] {
        Secure.LOCK_SCREEN_OWNER_INFO_ENABLED,
        Secure.LOCK_SCREEN_OWNER_INFO
    };

    private IMountService getMountService() {
        final IBinder service = ServiceManager.getService("mount");
        if (service != null) {
            return IMountService.Stub.asInterface(service);
        }
        return null;
    }
}
