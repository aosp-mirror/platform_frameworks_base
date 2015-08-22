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

import android.app.admin.DevicePolicyManager;
import android.app.backup.BackupManager;
import android.app.trust.IStrongAuthTracker;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import static android.Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE;
import static android.content.Context.USER_SERVICE;
import static android.Manifest.permission.READ_CONTACTS;
import android.database.sqlite.SQLiteDatabase;
import android.os.Binder;
import android.os.IBinder;
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
import android.service.gatekeeper.GateKeeperResponse;
import android.service.gatekeeper.IGateKeeperService;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.LockSettingsStorage.CredentialHash;

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
    private final LockSettingsStrongAuth mStrongAuth = new LockSettingsStrongAuth();

    private LockPatternUtils mLockPatternUtils;
    private boolean mFirstCallToVold;
    private IGateKeeperService mGateKeeperService;

    private interface CredentialUtil {
        void setCredential(String credential, String savedCredential, int userId)
                throws RemoteException;
        byte[] toHash(String credential, int userId);
        String adjustForKeystore(String credential);
    }

    public LockSettingsService(Context context) {
        mContext = context;
        // Open the database

        mLockPatternUtils = new LockPatternUtils(context);
        mFirstCallToVold = true;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_STARTING);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        filter.addAction(Intent.ACTION_USER_PRESENT);
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
                // Notify keystore that a new user was added.
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                final KeyStore ks = KeyStore.getInstance();
                final UserManager um = (UserManager) mContext.getSystemService(USER_SERVICE);
                final UserInfo parentInfo = um.getProfileParent(userHandle);
                final int parentHandle = parentInfo != null ? parentInfo.id : -1;
                ks.onUserAdded(userHandle, parentHandle);
            } else if (Intent.ACTION_USER_STARTING.equals(intent.getAction())) {
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mStorage.prefetchUser(userHandle);
            } else if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                mStrongAuth.reportUnlock(getSendingUserId());
            } else if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (userHandle > 0) {
                    removeUser(userHandle);
                }
            }
        }
    };

    public void systemReady() {
        migrateOldData();
        try {
            getGateKeeperService();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failure retrieving IGateKeeperService", e);
        }
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

            // Migrates biometric weak such that the fallback mechanism becomes the primary.
            if (getString("migrated_biometric_weak", null, 0) == null) {
                final UserManager um = (UserManager) mContext.getSystemService(USER_SERVICE);
                List<UserInfo> users = um.getUsers();
                for (int i = 0; i < users.size(); i++) {
                    int userId = users.get(i).id;
                    long type = getLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
                            userId);
                    long alternateType = getLong(LockPatternUtils.PASSWORD_TYPE_ALTERNATE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
                            userId);
                    if (type == DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK) {
                        setLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                                alternateType,
                                userId);
                    }
                    setLong(LockPatternUtils.PASSWORD_TYPE_ALTERNATE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED,
                            userId);
                }
                setString("migrated_biometric_weak", "true", 0);
                Slog.i(TAG, "Migrated biometric weak to use the fallback instead");
            }

            // Migrates lockscreen.disabled. Prior to M, the flag was ignored when more than one
            // user was present on the system, so if we're upgrading to M and there is more than one
            // user we disable the flag to remain consistent.
            if (getString("migrated_lockscreen_disabled", null, 0) == null) {
                final UserManager um = (UserManager) mContext.getSystemService(USER_SERVICE);

                final List<UserInfo> users = um.getUsers();
                final int userCount = users.size();
                int switchableUsers = 0;
                for (int i = 0; i < userCount; i++) {
                    if (users.get(i).supportsSwitchTo()) {
                        switchableUsers++;
                    }
                }

                if (switchableUsers > 1) {
                    for (int i = 0; i < userCount; i++) {
                        int id = users.get(i).id;

                        if (getBoolean(LockPatternUtils.DISABLE_LOCKSCREEN_KEY, false, id)) {
                            setBoolean(LockPatternUtils.DISABLE_LOCKSCREEN_KEY, false, id);
                        }
                    }
                }

                setString("migrated_lockscreen_disabled", "true", 0);
                Slog.i(TAG, "Migrated lockscreen disabled flag");
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

        for (int i = 0; i < READ_CONTACTS_PROTECTED_SETTINGS.length; i++) {
            String key = READ_CONTACTS_PROTECTED_SETTINGS[i];
            if (key.equals(requestedKey) && mContext.checkCallingOrSelfPermission(READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("uid=" + callingUid
                        + " needs permission " + READ_CONTACTS + " to read "
                        + requestedKey + " for user " + userId);
            }
        }

        for (int i = 0; i < READ_PASSWORD_PROTECTED_SETTINGS.length; i++) {
            String key = READ_PASSWORD_PROTECTED_SETTINGS[i];
            if (key.equals(requestedKey) && mContext.checkCallingOrSelfPermission(PERMISSION)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("uid=" + callingUid
                        + " needs permission " + PERMISSION + " to read "
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
        if (ArrayUtils.contains(SETTINGS_TO_BACKUP, key)) {
            BackupManager.dataChanged("com.android.providers.settings");
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue, int userId) throws RemoteException {
        checkReadPermission(key, userId);
        String value = getStringUnchecked(key, null, userId);
        return TextUtils.isEmpty(value) ?
                defaultValue : (value.equals("1") || value.equals("true"));
    }

    @Override
    public long getLong(String key, long defaultValue, int userId) throws RemoteException {
        checkReadPermission(key, userId);

        String value = getStringUnchecked(key, null, userId);
        return TextUtils.isEmpty(value) ? defaultValue : Long.parseLong(value);
    }

    @Override
    public String getString(String key, String defaultValue, int userId) throws RemoteException {
        checkReadPermission(key, userId);

        return getStringUnchecked(key, defaultValue, userId);
    }

    public String getStringUnchecked(String key, String defaultValue, int userId) {
        if (Settings.Secure.LOCK_PATTERN_ENABLED.equals(key)) {
            long ident = Binder.clearCallingIdentity();
            try {
                return mLockPatternUtils.isLockPatternEnabled(userId) ? "1" : "0";
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

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

    private void setKeystorePassword(String password, int userHandle) {
        final UserManager um = (UserManager) mContext.getSystemService(USER_SERVICE);
        final KeyStore ks = KeyStore.getInstance();

        final List<UserInfo> profiles = um.getProfiles(userHandle);
        for (UserInfo pi : profiles) {
            ks.onUserPasswordChanged(pi.id, password);
        }
    }

    private void unlockKeystore(String password, int userHandle) {
        final UserManager um = (UserManager) mContext.getSystemService(USER_SERVICE);
        final KeyStore ks = KeyStore.getInstance();

        final List<UserInfo> profiles = um.getProfiles(userHandle);
        for (UserInfo pi : profiles) {
            ks.unlock(pi.id, password);
        }
    }


    private byte[] getCurrentHandle(int userId) {
        CredentialHash credential;
        byte[] currentHandle;

        int currentHandleType = mStorage.getStoredCredentialType(userId);
        switch (currentHandleType) {
            case CredentialHash.TYPE_PATTERN:
                credential = mStorage.readPatternHash(userId);
                currentHandle = credential != null
                        ? credential.hash
                        : null;
                break;
            case CredentialHash.TYPE_PASSWORD:
                credential = mStorage.readPasswordHash(userId);
                currentHandle = credential != null
                        ? credential.hash
                        : null;
                break;
            case CredentialHash.TYPE_NONE:
            default:
                currentHandle = null;
                break;
        }

        // sanity check
        if (currentHandleType != CredentialHash.TYPE_NONE && currentHandle == null) {
            Slog.e(TAG, "Stored handle type [" + currentHandleType + "] but no handle available");
        }

        return currentHandle;
    }


    @Override
    public void setLockPattern(String pattern, String savedCredential, int userId)
            throws RemoteException {
        byte[] currentHandle = getCurrentHandle(userId);

        if (pattern == null) {
            getGateKeeperService().clearSecureUserId(userId);
            mStorage.writePatternHash(null, userId);
            setKeystorePassword(null, userId);
            return;
        }

        if (currentHandle == null) {
            if (savedCredential != null) {
                Slog.w(TAG, "Saved credential provided, but none stored");
            }
            savedCredential = null;
        }

        byte[] enrolledHandle = enrollCredential(currentHandle, savedCredential, pattern, userId);
        if (enrolledHandle != null) {
            mStorage.writePatternHash(enrolledHandle, userId);
        } else {
            Slog.e(TAG, "Failed to enroll pattern");
        }
    }


    @Override
    public void setLockPassword(String password, String savedCredential, int userId)
            throws RemoteException {
        byte[] currentHandle = getCurrentHandle(userId);

        if (password == null) {
            getGateKeeperService().clearSecureUserId(userId);
            mStorage.writePasswordHash(null, userId);
            setKeystorePassword(null, userId);
            return;
        }

        if (currentHandle == null) {
            if (savedCredential != null) {
                Slog.w(TAG, "Saved credential provided, but none stored");
            }
            savedCredential = null;
        }

        byte[] enrolledHandle = enrollCredential(currentHandle, savedCredential, password, userId);
        if (enrolledHandle != null) {
            mStorage.writePasswordHash(enrolledHandle, userId);
        } else {
            Slog.e(TAG, "Failed to enroll password");
        }
    }

    private byte[] enrollCredential(byte[] enrolledHandle,
            String enrolledCredential, String toEnroll, int userId)
            throws RemoteException {
        checkWritePermission(userId);
        byte[] enrolledCredentialBytes = enrolledCredential == null
                ? null
                : enrolledCredential.getBytes();
        byte[] toEnrollBytes = toEnroll == null
                ? null
                : toEnroll.getBytes();
        GateKeeperResponse response = getGateKeeperService().enroll(userId, enrolledHandle,
                enrolledCredentialBytes, toEnrollBytes);

        if (response == null) {
            return null;
        }

        byte[] hash = response.getPayload();
        if (hash != null) {
            setKeystorePassword(toEnroll, userId);
        } else {
            // Should not happen
            Slog.e(TAG, "Throttled while enrolling a password");
        }
        return hash;
    }

    @Override
    public VerifyCredentialResponse checkPattern(String pattern, int userId) throws RemoteException {
        return doVerifyPattern(pattern, false, 0, userId);
    }

    @Override
    public VerifyCredentialResponse verifyPattern(String pattern, long challenge, int userId)
            throws RemoteException {
        return doVerifyPattern(pattern, true, challenge, userId);
    }

    private VerifyCredentialResponse doVerifyPattern(String pattern, boolean hasChallenge,
            long challenge, int userId) throws RemoteException {
       checkPasswordReadPermission(userId);
       CredentialHash storedHash = mStorage.readPatternHash(userId);
       boolean shouldReEnrollBaseZero = storedHash != null && storedHash.isBaseZeroPattern;

       String patternToVerify;
       if (shouldReEnrollBaseZero) {
           patternToVerify = LockPatternUtils.patternStringToBaseZero(pattern);
       } else {
           patternToVerify = pattern;
       }

       VerifyCredentialResponse response = verifyCredential(userId, storedHash, patternToVerify,
               hasChallenge, challenge,
               new CredentialUtil() {
                   @Override
                   public void setCredential(String pattern, String oldPattern, int userId)
                           throws RemoteException {
                       setLockPattern(pattern, oldPattern, userId);
                   }

                   @Override
                   public byte[] toHash(String pattern, int userId) {
                       return LockPatternUtils.patternToHash(
                               LockPatternUtils.stringToPattern(pattern));
                   }

                   @Override
                   public String adjustForKeystore(String pattern) {
                       return LockPatternUtils.patternStringToBaseZero(pattern);
                   }
               }
       );

       if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK
               && shouldReEnrollBaseZero) {
           setLockPattern(pattern, patternToVerify, userId);
       }

       return response;

    }

    @Override
    public VerifyCredentialResponse checkPassword(String password, int userId)
            throws RemoteException {
        return doVerifyPassword(password, false, 0, userId);
    }

    @Override
    public VerifyCredentialResponse verifyPassword(String password, long challenge, int userId)
            throws RemoteException {
        return doVerifyPassword(password, true, challenge, userId);
    }

    private VerifyCredentialResponse doVerifyPassword(String password, boolean hasChallenge,
            long challenge, int userId) throws RemoteException {
       checkPasswordReadPermission(userId);
       CredentialHash storedHash = mStorage.readPasswordHash(userId);
       return verifyCredential(userId, storedHash, password, hasChallenge, challenge,
               new CredentialUtil() {
                   @Override
                   public void setCredential(String password, String oldPassword, int userId)
                           throws RemoteException {
                       setLockPassword(password, oldPassword, userId);
                   }

                   @Override
                   public byte[] toHash(String password, int userId) {
                       return mLockPatternUtils.passwordToHash(password, userId);
                   }

                   @Override
                   public String adjustForKeystore(String password) {
                       return password;
                   }
               }
       );
    }

    private VerifyCredentialResponse verifyCredential(int userId, CredentialHash storedHash,
            String credential, boolean hasChallenge, long challenge, CredentialUtil credentialUtil)
                throws RemoteException {
        if ((storedHash == null || storedHash.hash.length == 0) && TextUtils.isEmpty(credential)) {
            // don't need to pass empty credentials to GateKeeper
            return VerifyCredentialResponse.OK;
        }

        if (TextUtils.isEmpty(credential)) {
            return VerifyCredentialResponse.ERROR;
        }

        if (storedHash.version == CredentialHash.VERSION_LEGACY) {
            byte[] hash = credentialUtil.toHash(credential, userId);
            if (Arrays.equals(hash, storedHash.hash)) {
                unlockKeystore(credentialUtil.adjustForKeystore(credential), userId);
                // migrate credential to GateKeeper
                credentialUtil.setCredential(credential, null, userId);
                if (!hasChallenge) {
                    return VerifyCredentialResponse.OK;
                }
                // Fall through to get the auth token. Technically this should never happen,
                // as a user that had a legacy credential would have to unlock their device
                // before getting to a flow with a challenge, but supporting for consistency.
            } else {
                return VerifyCredentialResponse.ERROR;
            }
        }

        VerifyCredentialResponse response;
        boolean shouldReEnroll = false;;
        if (hasChallenge) {
            byte[] token = null;
            GateKeeperResponse gateKeeperResponse = getGateKeeperService()
                    .verifyChallenge(userId, challenge, storedHash.hash, credential.getBytes());
            int responseCode = gateKeeperResponse.getResponseCode();
            if (responseCode == GateKeeperResponse.RESPONSE_RETRY) {
                 response = new VerifyCredentialResponse(gateKeeperResponse.getTimeout());
            } else if (responseCode == GateKeeperResponse.RESPONSE_OK) {
                token = gateKeeperResponse.getPayload();
                if (token == null) {
                    // something's wrong if there's no payload with a challenge
                    Slog.e(TAG, "verifyChallenge response had no associated payload");
                    response = VerifyCredentialResponse.ERROR;
                } else {
                    shouldReEnroll = gateKeeperResponse.getShouldReEnroll();
                    response = new VerifyCredentialResponse(token);
                }
            } else {
                response = VerifyCredentialResponse.ERROR;
            }
        } else {
            GateKeeperResponse gateKeeperResponse = getGateKeeperService().verify(
                    userId, storedHash.hash, credential.getBytes());
            int responseCode = gateKeeperResponse.getResponseCode();
            if (responseCode == GateKeeperResponse.RESPONSE_RETRY) {
                response = new VerifyCredentialResponse(gateKeeperResponse.getTimeout());
            } else if (responseCode == GateKeeperResponse.RESPONSE_OK) {
                shouldReEnroll = gateKeeperResponse.getShouldReEnroll();
                response = VerifyCredentialResponse.OK;
            } else {
                response = VerifyCredentialResponse.ERROR;
            }
        }

        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
            // credential has matched
            unlockKeystore(credential, userId);
            if (shouldReEnroll) {
                credentialUtil.setCredential(credential, credential, userId);
            }
        }

        return response;
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
            if (mLockPatternUtils.isLockPatternEnabled(userId)) {
                if (checkPattern(password, userId).getResponseCode()
                        == GateKeeperResponse.RESPONSE_OK) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        try {
            if (mLockPatternUtils.isLockPasswordEnabled(userId)) {
                if (checkPassword(password, userId).getResponseCode()
                        == GateKeeperResponse.RESPONSE_OK) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        return false;
    }

    private void removeUser(int userId) {
        mStorage.removeUser(userId);
        mStrongAuth.removeUser(userId);

        final KeyStore ks = KeyStore.getInstance();
        ks.onUserRemoved(userId);

        try {
            final IGateKeeperService gk = getGateKeeperService();
            if (gk != null) {
                    gk.clearSecureUserId(userId);
            }
        } catch (RemoteException ex) {
            Slog.w(TAG, "unable to clear GK secure user id");
        }
    }

    @Override
    public void registerStrongAuthTracker(IStrongAuthTracker tracker) {
        checkPasswordReadPermission(UserHandle.USER_ALL);
        mStrongAuth.registerStrongAuthTracker(tracker);
    }

    @Override
    public void unregisterStrongAuthTracker(IStrongAuthTracker tracker) {
        checkPasswordReadPermission(UserHandle.USER_ALL);
        mStrongAuth.unregisterStrongAuthTracker(tracker);
    }

    @Override
    public void requireStrongAuth(int strongAuthReason, int userId) {
        checkWritePermission(userId);
        mStrongAuth.requireStrongAuth(strongAuthReason, userId);
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

    // Reading these settings needs the contacts permission
    private static final String[] READ_CONTACTS_PROTECTED_SETTINGS = new String[] {
        Secure.LOCK_SCREEN_OWNER_INFO_ENABLED,
        Secure.LOCK_SCREEN_OWNER_INFO
    };

    // Reading these settings needs the same permission as checking the password
    private static final String[] READ_PASSWORD_PROTECTED_SETTINGS = new String[] {
            LockPatternUtils.LOCK_PASSWORD_SALT_KEY,
            LockPatternUtils.PASSWORD_HISTORY_KEY,
            LockPatternUtils.PASSWORD_TYPE_KEY,
    };

    private static final String[] SETTINGS_TO_BACKUP = new String[] {
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

    private class GateKeeperDiedRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            mGateKeeperService.asBinder().unlinkToDeath(this, 0);
            mGateKeeperService = null;
        }
    }

    private synchronized IGateKeeperService getGateKeeperService()
            throws RemoteException {
        if (mGateKeeperService != null) {
            return mGateKeeperService;
        }

        final IBinder service =
            ServiceManager.getService("android.service.gatekeeper.IGateKeeperService");
        if (service != null) {
            service.linkToDeath(new GateKeeperDiedRecipient(), 0);
            mGateKeeperService = IGateKeeperService.Stub.asInterface(service);
            return mGateKeeperService;
        }

        Slog.e(TAG, "Unable to acquire GateKeeperService");
        return null;
    }
}
