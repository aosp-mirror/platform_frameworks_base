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

package com.android.server.locksettings;

import static android.Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE;
import static android.Manifest.permission.READ_CONTACTS;
import static android.content.Context.KEYGUARD_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import static com.android.internal.widget.LockPatternUtils.SYNTHETIC_PASSWORD_ENABLED_KEY;
import static com.android.internal.widget.LockPatternUtils.SYNTHETIC_PASSWORD_HANDLE_KEY;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;
import static com.android.internal.widget.LockPatternUtils.USER_FRP;
import static com.android.internal.widget.LockPatternUtils.frpCredentialEnabled;
import static com.android.internal.widget.LockPatternUtils.userOwnsFrpCredential;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.app.admin.DevicePolicyManagerInternal;
import android.app.admin.PasswordMetrics;
import android.app.backup.BackupManager;
import android.app.trust.IStrongAuthTracker;
import android.app.trust.TrustManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.authsecret.V1_0.IAuthSecret;
import android.hardware.biometrics.BiometricManager;
import android.hardware.face.FaceManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.provider.Settings.SettingNotFoundException;
import android.security.KeyStore;
import android.security.keystore.AndroidKeyStoreProvider;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.security.keystore.UserNotAuthenticatedException;
import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.RecoveryCertPath;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.service.gatekeeper.GateKeeperResponse;
import android.service.gatekeeper.IGateKeeperService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.ICheckCredentialProgressCallback;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsInternal;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.locksettings.LockSettingsStorage.CredentialHash;
import com.android.server.locksettings.LockSettingsStorage.PersistentData;
import com.android.server.locksettings.SyntheticPasswordManager.AuthenticationResult;
import com.android.server.locksettings.SyntheticPasswordManager.AuthenticationToken;
import com.android.server.locksettings.recoverablekeystore.RecoverableKeyStoreManager;
import com.android.server.wm.WindowManagerInternal;

import libcore.util.HexEncoding;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Keeps the lock pattern/password data and related settings for each user. Used by
 * LockPatternUtils. Needs to be a service because Settings app also needs to be able to save
 * lockscreen information for secondary users.
 *
 * @hide
 */
public class LockSettingsService extends ILockSettings.Stub {
    private static final String TAG = "LockSettingsService";
    private static final String PERMISSION = ACCESS_KEYGUARD_SECURE_STORAGE;
    private static final boolean DEBUG = false;

    private static final int PROFILE_KEY_IV_SIZE = 12;
    private static final String SEPARATE_PROFILE_CHALLENGE_KEY = "lockscreen.profilechallenge";
    private static final int SYNTHETIC_PASSWORD_ENABLED_BY_DEFAULT = 1;

    // Order of holding lock: mSeparateChallengeLock -> mSpManager -> this
    // Do not call into ActivityManager while holding mSpManager lock.
    private final Object mSeparateChallengeLock = new Object();

    private final DeviceProvisionedObserver mDeviceProvisionedObserver =
            new DeviceProvisionedObserver();

    private final Injector mInjector;
    private final Context mContext;
    @VisibleForTesting
    protected final Handler mHandler;
    @VisibleForTesting
    protected final LockSettingsStorage mStorage;
    private final LockSettingsStrongAuth mStrongAuth;
    private final SynchronizedStrongAuthTracker mStrongAuthTracker;

    private final LockPatternUtils mLockPatternUtils;
    private final NotificationManager mNotificationManager;
    private final UserManager mUserManager;
    private final IActivityManager mActivityManager;
    private final SyntheticPasswordManager mSpManager;

    private final KeyStore mKeyStore;

    private final RecoverableKeyStoreManager mRecoverableKeyStoreManager;

    private boolean mFirstCallToVold;
    protected IGateKeeperService mGateKeeperService;
    protected IAuthSecret mAuthSecretService;

    private static final String GSI_RUNNING_PROP = "ro.gsid.image_running";

    /**
     * The UIDs that are used for system credential storage in keystore.
     */
    private static final int[] SYSTEM_CREDENTIAL_UIDS = {
            Process.WIFI_UID, Process.VPN_UID,
            Process.ROOT_UID, Process.SYSTEM_UID };

    // This class manages life cycle events for encrypted users on File Based Encryption (FBE)
    // devices. The most basic of these is to show/hide notifications about missing features until
    // the user unlocks the account and credential-encrypted storage is available.
    public static final class Lifecycle extends SystemService {
        private LockSettingsService mLockSettingsService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            AndroidKeyStoreProvider.install();
            mLockSettingsService = new LockSettingsService(getContext());
            publishBinderService("lock_settings", mLockSettingsService);
        }

        @Override
        public void onBootPhase(int phase) {
            super.onBootPhase(phase);
            if (phase == PHASE_ACTIVITY_MANAGER_READY) {
                mLockSettingsService.migrateOldDataAfterSystemReady();
            }
        }

        @Override
        public void onStartUser(int userHandle) {
            mLockSettingsService.onStartUser(userHandle);
        }

        @Override
        public void onUnlockUser(int userHandle) {
            mLockSettingsService.onUnlockUser(userHandle);
        }

        @Override
        public void onCleanupUser(int userHandle) {
            mLockSettingsService.onCleanupUser(userHandle);
        }
    }

    @VisibleForTesting
    protected static class SynchronizedStrongAuthTracker
            extends LockPatternUtils.StrongAuthTracker {
        public SynchronizedStrongAuthTracker(Context context) {
            super(context);
        }

        @Override
        protected void handleStrongAuthRequiredChanged(int strongAuthFlags, int userId) {
            synchronized (this) {
                super.handleStrongAuthRequiredChanged(strongAuthFlags, userId);
            }
        }

        @Override
        public int getStrongAuthForUser(int userId) {
            synchronized (this) {
                return super.getStrongAuthForUser(userId);
            }
        }

        void register(LockSettingsStrongAuth strongAuth) {
            strongAuth.registerStrongAuthTracker(this.mStub);
        }
    }

    /**
     * Tie managed profile to primary profile if it is in unified mode and not tied before.
     *
     * @param managedUserId Managed profile user Id
     * @param managedUserPassword Managed profile original password (when it has separated lock).
     *            NULL when it does not have a separated lock before.
     */
    public void tieManagedProfileLockIfNecessary(int managedUserId, byte[] managedUserPassword) {
        if (DEBUG) Slog.v(TAG, "Check child profile lock for user: " + managedUserId);
        // Only for managed profile
        if (!mUserManager.getUserInfo(managedUserId).isManagedProfile()) {
            return;
        }
        // Do not tie managed profile when work challenge is enabled
        if (mLockPatternUtils.isSeparateProfileChallengeEnabled(managedUserId)) {
            return;
        }
        // Do not tie managed profile to parent when it's done already
        if (mStorage.hasChildProfileLock(managedUserId)) {
            return;
        }
        // Do not tie it to parent when parent does not have a screen lock
        final int parentId = mUserManager.getProfileParent(managedUserId).id;
        if (!isUserSecure(parentId)) {
            if (DEBUG) Slog.v(TAG, "Parent does not have a screen lock");
            return;
        }
        // Do not tie when the parent has no SID (but does have a screen lock).
        // This can only happen during an upgrade path where SID is yet to be
        // generated when the user unlocks for the first time.
        try {
            if (getGateKeeperService().getSecureUserId(parentId) == 0) {
                return;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to talk to GateKeeper service", e);
            return;
        }
        if (DEBUG) Slog.v(TAG, "Tie managed profile to parent now!");
        byte[] randomLockSeed = new byte[] {};
        try {
            randomLockSeed = SecureRandom.getInstance("SHA1PRNG").generateSeed(40);
            char[] newPasswordChars = HexEncoding.encode(randomLockSeed);
            byte[] newPassword = new byte[newPasswordChars.length];
            for (int i = 0; i < newPasswordChars.length; i++) {
                newPassword[i] = (byte) newPasswordChars[i];
            }
            Arrays.fill(newPasswordChars, '\u0000');
            final int quality = DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC;
            setLockCredentialInternal(newPassword, LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                    managedUserPassword, quality, managedUserId);
            // We store a private credential for the managed user that's unlocked by the primary
            // account holder's credential. As such, the user will never be prompted to enter this
            // password directly, so we always store a password.
            setLong(LockPatternUtils.PASSWORD_TYPE_KEY, quality, managedUserId);
            tieProfileLockToParent(managedUserId, newPassword);
            Arrays.fill(newPassword, (byte) 0);
        } catch (NoSuchAlgorithmException | RemoteException e) {
            Slog.e(TAG, "Fail to tie managed profile", e);
            // Nothing client can do to fix this issue, so we do not throw exception out
        }
    }

    static class Injector {

        protected Context mContext;

        public Injector(Context context) {
            mContext = context;
        }

        public Context getContext() {
            return mContext;
        }

        public Handler getHandler() {
            return new Handler();
        }

        public LockSettingsStorage getStorage() {
            final LockSettingsStorage storage = new LockSettingsStorage(mContext);
            storage.setDatabaseOnCreateCallback(new LockSettingsStorage.Callback() {
                @Override
                public void initialize(SQLiteDatabase db) {
                    // Get the lockscreen default from a system property, if available
                    boolean lockScreenDisable = SystemProperties.getBoolean(
                            "ro.lockscreen.disable.default", false);
                    if (lockScreenDisable) {
                        storage.writeKeyValue(db, LockPatternUtils.DISABLE_LOCKSCREEN_KEY, "1", 0);
                    }
                }
            });
            return storage;
        }

        public LockSettingsStrongAuth getStrongAuth() {
            return new LockSettingsStrongAuth(mContext);
        }

        public SynchronizedStrongAuthTracker getStrongAuthTracker() {
            return new SynchronizedStrongAuthTracker(mContext);
        }

        public IActivityManager getActivityManager() {
            return ActivityManager.getService();
        }

        public LockPatternUtils getLockPatternUtils() {
            return new LockPatternUtils(mContext);
        }

        public NotificationManager getNotificationManager() {
            return (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }

        public UserManager getUserManager() {
            return (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        }

        public DevicePolicyManager getDevicePolicyManager() {
            return (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        }

        public KeyStore getKeyStore() {
            return KeyStore.getInstance();
        }

        public RecoverableKeyStoreManager getRecoverableKeyStoreManager(KeyStore keyStore) {
            return RecoverableKeyStoreManager.getInstance(mContext, keyStore);
        }

        public IStorageManager getStorageManager() {
            final IBinder service = ServiceManager.getService("mount");
            if (service != null) {
                return IStorageManager.Stub.asInterface(service);
            }
            return null;
        }

        public SyntheticPasswordManager getSyntheticPasswordManager(LockSettingsStorage storage) {
            return new SyntheticPasswordManager(getContext(), storage, getUserManager(),
                    new PasswordSlotManager());
        }

        public boolean hasBiometrics() {
            return BiometricManager.hasBiometrics(mContext);
        }

        public int binderGetCallingUid() {
            return Binder.getCallingUid();
        }

        public boolean isGsiRunning() {
            return SystemProperties.getInt(GSI_RUNNING_PROP, 0) > 0;
        }
    }

    public LockSettingsService(Context context) {
        this(new Injector(context));
    }

    @VisibleForTesting
    protected LockSettingsService(Injector injector) {
        mInjector = injector;
        mContext = injector.getContext();
        mKeyStore = injector.getKeyStore();
        mRecoverableKeyStoreManager = injector.getRecoverableKeyStoreManager(mKeyStore);
        mHandler = injector.getHandler();
        mStrongAuth = injector.getStrongAuth();
        mActivityManager = injector.getActivityManager();

        mLockPatternUtils = injector.getLockPatternUtils();
        mFirstCallToVold = true;

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_STARTING);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        injector.getContext().registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter,
                null, null);

        mStorage = injector.getStorage();
        mNotificationManager = injector.getNotificationManager();
        mUserManager = injector.getUserManager();
        mStrongAuthTracker = injector.getStrongAuthTracker();
        mStrongAuthTracker.register(mStrongAuth);

        mSpManager = injector.getSyntheticPasswordManager(mStorage);

        LocalServices.addService(LockSettingsInternal.class, new LocalService());
    }

    /**
     * If the account is credential-encrypted, show notification requesting the user to unlock the
     * device.
     */
    private void maybeShowEncryptionNotificationForUser(@UserIdInt int userId) {
        final UserInfo user = mUserManager.getUserInfo(userId);
        if (!user.isManagedProfile()) {
            // When the user is locked, we communicate it loud-and-clear
            // on the lockscreen; we only show a notification below for
            // locked managed profiles.
            return;
        }

        final UserHandle userHandle = user.getUserHandle();
        final boolean isSecure = isUserSecure(userId);
        if (isSecure && !mUserManager.isUserUnlockingOrUnlocked(userHandle)) {
            UserInfo parent = mUserManager.getProfileParent(userId);
            if (parent != null &&
                    mUserManager.isUserUnlockingOrUnlocked(parent.getUserHandle()) &&
                    !mUserManager.isQuietModeEnabled(userHandle)) {
                // Only show notifications for managed profiles once their parent
                // user is unlocked.
                showEncryptionNotificationForProfile(userHandle);
            }
        }
    }

    private void showEncryptionNotificationForProfile(UserHandle user) {
        Resources r = mContext.getResources();
        CharSequence title = r.getText(
                com.android.internal.R.string.user_encrypted_title);
        CharSequence message = r.getText(
                com.android.internal.R.string.profile_encrypted_message);
        CharSequence detail = r.getText(
                com.android.internal.R.string.profile_encrypted_detail);

        final KeyguardManager km = (KeyguardManager) mContext.getSystemService(KEYGUARD_SERVICE);
        final Intent unlockIntent = km.createConfirmDeviceCredentialIntent(null, null,
                user.getIdentifier());
        if (unlockIntent == null) {
            return;
        }
        unlockIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        PendingIntent intent = PendingIntent.getActivity(mContext, 0, unlockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        showEncryptionNotification(user, title, message, detail, intent);
    }

    private void showEncryptionNotification(UserHandle user, CharSequence title,
            CharSequence message, CharSequence detail, PendingIntent intent) {
        if (DEBUG) Slog.v(TAG, "showing encryption notification, user: " + user.getIdentifier());

        // Suppress all notifications on non-FBE devices for now
        if (!StorageManager.isFileEncryptedNativeOrEmulated()) return;

        Notification notification =
                new Notification.Builder(mContext, SystemNotificationChannels.SECURITY)
                        .setSmallIcon(com.android.internal.R.drawable.ic_user_secure)
                        .setWhen(0)
                        .setOngoing(true)
                        .setTicker(title)
                        .setColor(mContext.getColor(
                                com.android.internal.R.color.system_notification_accent_color))
                        .setContentTitle(title)
                        .setContentText(message)
                        .setSubText(detail)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setContentIntent(intent)
                        .build();
        mNotificationManager.notifyAsUser(null, SystemMessage.NOTE_FBE_ENCRYPTED_NOTIFICATION,
            notification, user);
    }

    private void hideEncryptionNotification(UserHandle userHandle) {
        if (DEBUG) Slog.v(TAG, "hide encryption notification, user: " + userHandle.getIdentifier());
        mNotificationManager.cancelAsUser(null, SystemMessage.NOTE_FBE_ENCRYPTED_NOTIFICATION,
            userHandle);
    }

    public void onCleanupUser(int userId) {
        hideEncryptionNotification(new UserHandle(userId));
        // User is stopped with its CE key evicted. Require strong auth next time to be able to
        // unlock the user's storage. Use STRONG_AUTH_REQUIRED_AFTER_BOOT since stopping and
        // restarting a user later is equivalent to rebooting the device.
        requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_BOOT, userId);
    }

    public void onStartUser(final int userId) {
        maybeShowEncryptionNotificationForUser(userId);
    }

    /**
     * Check if profile got unlocked but the keystore is still locked. This happens on full disk
     * encryption devices since the profile may not yet be running when we consider unlocking it
     * during the normal flow. In this case unlock the keystore for the profile.
     */
    private void ensureProfileKeystoreUnlocked(int userId) {
        final KeyStore ks = KeyStore.getInstance();
        if (ks.state(userId) == KeyStore.State.LOCKED
                && tiedManagedProfileReadyToUnlock(mUserManager.getUserInfo(userId))) {
            Slog.i(TAG, "Managed profile got unlocked, will unlock its keystore");
            try {
                // If boot took too long and the password in vold got expired, parent keystore will
                // be still locked, we ignore this case since the user will be prompted to unlock
                // the device after boot.
                unlockChildProfile(userId, true /* ignoreUserNotAuthenticated */);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to unlock child profile");
            }
        }
    }

    public void onUnlockUser(final int userId) {
        // Perform tasks which require locks in LSS on a handler, as we are callbacks from
        // ActivityManager.unlockUser()
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                ensureProfileKeystoreUnlocked(userId);
                // Hide notification first, as tie managed profile lock takes time
                hideEncryptionNotification(new UserHandle(userId));

                // Now we have unlocked the parent user we should show notifications
                // about any profiles that exist.
                List<UserInfo> profiles = mUserManager.getProfiles(userId);
                for (int i = 0; i < profiles.size(); i++) {
                    UserInfo profile = profiles.get(i);
                    final boolean isSecure = isUserSecure(profile.id);
                    if (isSecure && profile.isManagedProfile()) {
                        UserHandle userHandle = profile.getUserHandle();
                        if (!mUserManager.isUserUnlockingOrUnlocked(userHandle) &&
                                !mUserManager.isQuietModeEnabled(userHandle)) {
                            showEncryptionNotificationForProfile(userHandle);
                        }
                    }
                }

                if (mUserManager.getUserInfo(userId).isManagedProfile()) {
                    tieManagedProfileLockIfNecessary(userId, null);
                }

                // If the user doesn't have a credential, try and derive their secret for the
                // AuthSecret HAL. The secret will have been enrolled if the user previously set a
                // credential and still needs to be passed to the HAL once that credential is
                // removed.
                if (mUserManager.getUserInfo(userId).isPrimary() && !isUserSecure(userId)) {
                    tryDeriveAuthTokenForUnsecuredPrimaryUser(userId);
                }
            }
        });
    }

    private void tryDeriveAuthTokenForUnsecuredPrimaryUser(@UserIdInt int userId) {
        synchronized (mSpManager) {
            // Make sure the user has a synthetic password to derive
            if (!isSyntheticPasswordBasedCredentialLocked(userId)) {
                return;
            }

            try {
                final long handle = getSyntheticPasswordHandleLocked(userId);
                final byte[] noCredential = null;
                AuthenticationResult result =
                        mSpManager.unwrapPasswordBasedSyntheticPassword(
                                getGateKeeperService(), handle, noCredential, userId, null);
                if (result.authToken != null) {
                    Slog.i(TAG, "Retrieved auth token for user " + userId);
                    onAuthTokenKnownForUser(userId, result.authToken);
                } else {
                    Slog.e(TAG, "Auth token not available for user " + userId);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failure retrieving auth token", e);
            }
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_ADDED.equals(intent.getAction())) {
                // Notify keystore that a new user was added.
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (userHandle > UserHandle.USER_SYSTEM) {
                    removeUser(userHandle, /* unknownUser= */ true);
                }
                final KeyStore ks = KeyStore.getInstance();
                final UserInfo parentInfo = mUserManager.getProfileParent(userHandle);
                final int parentHandle = parentInfo != null ? parentInfo.id : -1;
                ks.onUserAdded(userHandle, parentHandle);
            } else if (Intent.ACTION_USER_STARTING.equals(intent.getAction())) {
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mStorage.prefetchUser(userHandle);
            } else if (Intent.ACTION_USER_REMOVED.equals(intent.getAction())) {
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                if (userHandle > 0) {
                    removeUser(userHandle, /* unknownUser= */ false);
                }
            }
        }
    };

    @Override // binder interface
    public void systemReady() {
        if (mContext.checkCallingOrSelfPermission(PERMISSION) != PERMISSION_GRANTED) {
            EventLog.writeEvent(0x534e4554, "28251513", getCallingUid(), "");  // SafetyNet
        }
        checkWritePermission(UserHandle.USER_SYSTEM);
        migrateOldData();
        try {
            getGateKeeperService();
            mSpManager.initWeaverService();
        } catch (RemoteException e) {
            Slog.e(TAG, "Failure retrieving IGateKeeperService", e);
        }
        // Find the AuthSecret HAL
        try {
            mAuthSecretService = IAuthSecret.getService();
        } catch (NoSuchElementException e) {
            Slog.i(TAG, "Device doesn't implement AuthSecret HAL");
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to get AuthSecret HAL", e);
        }
        mDeviceProvisionedObserver.onSystemReady();
        // TODO: maybe skip this for split system user mode.
        mStorage.prefetchUser(UserHandle.USER_SYSTEM);
    }

    private void migrateOldData() {
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
            final ContentResolver cr = mContext.getContentResolver();
            List<UserInfo> users = mUserManager.getUsers();
            for (int user = 0; user < users.size(); user++) {
                // Migrate owner info
                final int userId = users.get(user).id;
                final String OWNER_INFO = Secure.LOCK_SCREEN_OWNER_INFO;
                String ownerInfo = Settings.Secure.getStringForUser(cr, OWNER_INFO, userId);
                if (!TextUtils.isEmpty(ownerInfo)) {
                    setString(OWNER_INFO, ownerInfo, userId);
                    Settings.Secure.putStringForUser(cr, OWNER_INFO, "", userId);
                }

                // Migrate owner info enabled. Note there was a bug where older platforms only
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
            List<UserInfo> users = mUserManager.getUsers();
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
            final List<UserInfo> users = mUserManager.getUsers();
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

        final List<UserInfo> users = mUserManager.getUsers();
        for (int i = 0; i < users.size(); i++) {
            final UserInfo userInfo = users.get(i);
            if (userInfo.isManagedProfile() && mStorage.hasChildProfileLock(userInfo.id)) {
                // When managed profile has a unified lock, the password quality stored has 2
                // possibilities only.
                // 1). PASSWORD_QUALITY_UNSPECIFIED, which is upgraded from dp2, and we are
                // going to set it back to PASSWORD_QUALITY_ALPHANUMERIC.
                // 2). PASSWORD_QUALITY_ALPHANUMERIC, which is the actual password quality for
                // unified lock.
                final long quality = getLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                        DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userInfo.id);
                if (quality == DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED) {
                    // Only possible when it's upgraded from nyc dp3
                    Slog.i(TAG, "Migrated tied profile lock type");
                    setLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC, userInfo.id);
                } else if (quality != DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC) {
                    // It should not happen
                    Slog.e(TAG, "Invalid tied profile lock type: " + quality);
                }
            }
            try {
                final String alias = LockPatternUtils.PROFILE_KEY_NAME_ENCRYPT + userInfo.id;
                java.security.KeyStore keyStore =
                        java.security.KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);
                if (keyStore.containsAlias(alias)) {
                    keyStore.deleteEntry(alias);
                }
            } catch (KeyStoreException | NoSuchAlgorithmException |
                    CertificateException | IOException e) {
                Slog.e(TAG, "Unable to remove tied profile key", e);
            }
        }

        boolean isWatch = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WATCH);
        // Wear used to set DISABLE_LOCKSCREEN to 'true', but because Wear now allows accounts
        // and device management the lockscreen must be re-enabled now for users that upgrade.
        if (isWatch && getString("migrated_wear_lockscreen_disabled", null, 0) == null) {
            final int userCount = users.size();
            for (int i = 0; i < userCount; i++) {
                int id = users.get(i).id;
                setBoolean(LockPatternUtils.DISABLE_LOCKSCREEN_KEY, false, id);
            }
            setString("migrated_wear_lockscreen_disabled", "true", 0);
            Slog.i(TAG, "Migrated lockscreen_disabled for Wear devices");
        }
    }

    private void migrateOldDataAfterSystemReady() {
        try {
            // Migrate the FRP credential to the persistent data block
            if (LockPatternUtils.frpCredentialEnabled(mContext)
                    && !getBoolean("migrated_frp", false, 0)) {
                migrateFrpCredential();
                setBoolean("migrated_frp", true, 0);
                Slog.i(TAG, "Migrated migrated_frp.");
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to migrateOldDataAfterSystemReady", e);
        }
    }

    /**
     * Migrate the credential for the FRP credential owner user if the following are satisfied:
     * - the user has a secure credential
     * - the FRP credential is not set up
     * - the credential is based on a synthetic password.
     */
    private void migrateFrpCredential() throws RemoteException {
        if (mStorage.readPersistentDataBlock() != PersistentData.NONE) {
            return;
        }
        for (UserInfo userInfo : mUserManager.getUsers()) {
            if (userOwnsFrpCredential(mContext, userInfo) && isUserSecure(userInfo.id)) {
                synchronized (mSpManager) {
                    if (isSyntheticPasswordBasedCredentialLocked(userInfo.id)) {
                        int actualQuality = (int) getLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                                DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userInfo.id);

                        mSpManager.migrateFrpPasswordLocked(
                                getSyntheticPasswordHandleLocked(userInfo.id),
                                userInfo,
                                redactActualQualityToMostLenientEquivalentQuality(actualQuality));
                    }
                }
                return;
            }
        }
    }

    /**
     * Returns the lowest password quality that still presents the same UI for entering it.
     *
     * For the FRP credential, we do not want to leak the actual quality of the password, only what
     * kind of UI it requires. However, when migrating, we only know the actual quality, not the
     * originally requested quality; since this is only used to determine what input variant to
     * present to the user, we just assume the lowest possible quality was requested.
     */
    private int redactActualQualityToMostLenientEquivalentQuality(int quality) {
        switch (quality) {
            case DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC:
            case DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_COMPLEX:
                return DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC;
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC:
            case DevicePolicyManager.PASSWORD_QUALITY_NUMERIC_COMPLEX:
                return DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
            case DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED:
            case DevicePolicyManager.PASSWORD_QUALITY_SOMETHING:
            case DevicePolicyManager.PASSWORD_QUALITY_MANAGED:
            case DevicePolicyManager.PASSWORD_QUALITY_BIOMETRIC_WEAK:
            default:
                return quality;
        }
    }

    private final void checkWritePermission(int userId) {
        mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsWrite");
    }

    private final void checkPasswordReadPermission(int userId) {
        mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsRead");
    }

    private final void checkPasswordHavePermission(int userId) {
        if (mContext.checkCallingOrSelfPermission(PERMISSION) != PERMISSION_GRANTED) {
            EventLog.writeEvent(0x534e4554, "28251513", getCallingUid(), "");  // SafetyNet
        }
        mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsHave");
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
    public boolean getSeparateProfileChallengeEnabled(int userId) {
        checkReadPermission(SEPARATE_PROFILE_CHALLENGE_KEY, userId);
        synchronized (mSeparateChallengeLock) {
            return getBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, false, userId);
        }
    }

    @Override
    public void setSeparateProfileChallengeEnabled(int userId, boolean enabled,
            byte[] managedUserPassword) {
        checkWritePermission(userId);
        if (!mLockPatternUtils.hasSecureLockScreen()) {
            throw new UnsupportedOperationException(
                    "This operation requires secure lock screen feature.");
        }
        synchronized (mSeparateChallengeLock) {
            setSeparateProfileChallengeEnabledLocked(userId, enabled, managedUserPassword);
        }
        notifySeparateProfileChallengeChanged(userId);
    }

    @GuardedBy("mSeparateChallengeLock")
    private void setSeparateProfileChallengeEnabledLocked(@UserIdInt int userId,
            boolean enabled, byte[] managedUserPassword) {
        final boolean old = getBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, false, userId);
        setBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, enabled, userId);
        try {
            if (enabled) {
                mStorage.removeChildProfileLock(userId);
                removeKeystoreProfileKey(userId);
            } else {
                tieManagedProfileLockIfNecessary(userId, managedUserPassword);
            }
        } catch (IllegalStateException e) {
            setBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, old, userId);
            throw e;
        }
    }

    private void notifySeparateProfileChallengeChanged(int userId) {
        final DevicePolicyManagerInternal dpmi = LocalServices.getService(
                DevicePolicyManagerInternal.class);
        if (dpmi != null) {
            dpmi.reportSeparateProfileChallengeChanged(userId);
        }
    }

    @Override
    public void setBoolean(String key, boolean value, int userId) {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, value ? "1" : "0");
    }

    @Override
    public void setLong(String key, long value, int userId) {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, Long.toString(value));
    }

    @Override
    public void setString(String key, String value, int userId) {
        checkWritePermission(userId);
        setStringUnchecked(key, userId, value);
    }

    private void setStringUnchecked(String key, int userId, String value) {
        Preconditions.checkArgument(userId != USER_FRP, "cannot store lock settings for FRP user");

        mStorage.writeKeyValue(key, value, userId);
        if (ArrayUtils.contains(SETTINGS_TO_BACKUP, key)) {
            BackupManager.dataChanged("com.android.providers.settings");
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue, int userId) {
        checkReadPermission(key, userId);
        String value = getStringUnchecked(key, null, userId);
        return TextUtils.isEmpty(value) ?
                defaultValue : (value.equals("1") || value.equals("true"));
    }

    @Override
    public long getLong(String key, long defaultValue, int userId) {
        checkReadPermission(key, userId);
        String value = getStringUnchecked(key, null, userId);
        return TextUtils.isEmpty(value) ? defaultValue : Long.parseLong(value);
    }

    @Override
    public String getString(String key, String defaultValue, int userId) {
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

        if (userId == USER_FRP) {
            return getFrpStringUnchecked(key);
        }

        if (LockPatternUtils.LEGACY_LOCK_PATTERN_ENABLED.equals(key)) {
            key = Settings.Secure.LOCK_PATTERN_ENABLED;
        }

        return mStorage.readKeyValue(key, defaultValue, userId);
    }

    private String getFrpStringUnchecked(String key) {
        if (LockPatternUtils.PASSWORD_TYPE_KEY.equals(key)) {
            return String.valueOf(readFrpPasswordQuality());
        }
        return null;
    }

    private int readFrpPasswordQuality() {
        return mStorage.readPersistentDataBlock().qualityForUi;
    }

    @Override
    public boolean havePassword(int userId) throws RemoteException {
        checkPasswordHavePermission(userId);
        synchronized (mSpManager) {
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                long handle = getSyntheticPasswordHandleLocked(userId);
                return mSpManager.getCredentialType(handle, userId) ==
                        LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
            }
        }
        // Do we need a permissions check here?
        return mStorage.hasPassword(userId);
    }

    @Override
    public boolean havePattern(int userId) throws RemoteException {
        checkPasswordHavePermission(userId);
        synchronized (mSpManager) {
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                long handle = getSyntheticPasswordHandleLocked(userId);
                return mSpManager.getCredentialType(handle, userId) ==
                        LockPatternUtils.CREDENTIAL_TYPE_PATTERN;
            }
        }
        // Do we need a permissions check here?
        return mStorage.hasPattern(userId);
    }

    private boolean isUserSecure(int userId) {
        synchronized (mSpManager) {
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                long handle = getSyntheticPasswordHandleLocked(userId);
                return mSpManager.getCredentialType(handle, userId) !=
                        LockPatternUtils.CREDENTIAL_TYPE_NONE;
            }
        }
        return mStorage.hasCredential(userId);
    }

    private void setKeystorePassword(byte[] password, int userHandle) {
        final KeyStore ks = KeyStore.getInstance();
        // TODO(b/120484642): Update keystore to accept byte[] passwords
        String passwordString = password == null ? null : new String(password);
        ks.onUserPasswordChanged(userHandle, passwordString);
    }

    private void unlockKeystore(byte[] password, int userHandle) {
        if (DEBUG) Slog.v(TAG, "Unlock keystore for user: " + userHandle);
        // TODO(b/120484642): Update keystore to accept byte[] passwords
        String passwordString = password == null ? null : new String(password);
        final KeyStore ks = KeyStore.getInstance();
        ks.unlock(userHandle, passwordString);
    }

    @VisibleForTesting
    protected byte[] getDecryptedPasswordForTiedProfile(int userId)
            throws KeyStoreException, UnrecoverableKeyException,
            NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException,
            CertificateException, IOException {
        if (DEBUG) Slog.v(TAG, "Get child profile decrypted key");
        byte[] storedData = mStorage.readChildProfileLock(userId);
        if (storedData == null) {
            throw new FileNotFoundException("Child profile lock file not found");
        }
        byte[] iv = Arrays.copyOfRange(storedData, 0, PROFILE_KEY_IV_SIZE);
        byte[] encryptedPassword = Arrays.copyOfRange(storedData, PROFILE_KEY_IV_SIZE,
                storedData.length);
        byte[] decryptionResult;
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey decryptionKey = (SecretKey) keyStore.getKey(
                LockPatternUtils.PROFILE_KEY_NAME_DECRYPT + userId, null);

        Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                + KeyProperties.BLOCK_MODE_GCM + "/" + KeyProperties.ENCRYPTION_PADDING_NONE);

        cipher.init(Cipher.DECRYPT_MODE, decryptionKey, new GCMParameterSpec(128, iv));
        decryptionResult = cipher.doFinal(encryptedPassword);
        return decryptionResult;
    }

    private void unlockChildProfile(int profileHandle, boolean ignoreUserNotAuthenticated)
            throws RemoteException {
        try {
            doVerifyCredential(getDecryptedPasswordForTiedProfile(profileHandle),
                    LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                    false, 0 /* no challenge */, profileHandle, null /* progressCallback */);
        } catch (UnrecoverableKeyException | InvalidKeyException | KeyStoreException
                | NoSuchAlgorithmException | NoSuchPaddingException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException
                | BadPaddingException | CertificateException | IOException e) {
            if (e instanceof FileNotFoundException) {
                Slog.i(TAG, "Child profile key not found");
            } else if (ignoreUserNotAuthenticated && e instanceof UserNotAuthenticatedException) {
                Slog.i(TAG, "Parent keystore seems locked, ignoring");
            } else {
                Slog.e(TAG, "Failed to decrypt child profile key", e);
            }
        }
    }

    private void unlockUser(int userId, byte[] token, byte[] secret) {
        // TODO: make this method fully async so we can update UI with progress strings
        final CountDownLatch latch = new CountDownLatch(1);
        final IProgressListener listener = new IProgressListener.Stub() {
            @Override
            public void onStarted(int id, Bundle extras) throws RemoteException {
                Log.d(TAG, "unlockUser started");
            }

            @Override
            public void onProgress(int id, int progress, Bundle extras) throws RemoteException {
                Log.d(TAG, "unlockUser progress " + progress);
            }

            @Override
            public void onFinished(int id, Bundle extras) throws RemoteException {
                Log.d(TAG, "unlockUser finished");
                latch.countDown();
            }
        };

        try {
            mActivityManager.unlockUser(userId, token, secret, listener);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }

        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            if (!mUserManager.getUserInfo(userId).isManagedProfile()) {
                final List<UserInfo> profiles = mUserManager.getProfiles(userId);
                for (UserInfo pi : profiles) {
                    // Unlock managed profile with unified lock
                    if (tiedManagedProfileReadyToUnlock(pi)) {
                        unlockChildProfile(pi.id, false /* ignoreUserNotAuthenticated */);
                    }
                }
            }
        } catch (RemoteException e) {
            Log.d(TAG, "Failed to unlock child profile", e);
        }
    }

    private boolean tiedManagedProfileReadyToUnlock(UserInfo userInfo) {
        return userInfo.isManagedProfile()
                && !mLockPatternUtils.isSeparateProfileChallengeEnabled(userInfo.id)
                && mStorage.hasChildProfileLock(userInfo.id)
                && mUserManager.isUserRunning(userInfo.id);
    }

    private Map<Integer, byte[]> getDecryptedPasswordsForAllTiedProfiles(int userId) {
        if (mUserManager.getUserInfo(userId).isManagedProfile()) {
            return null;
        }
        Map<Integer, byte[]> result = new ArrayMap<Integer, byte[]>();
        final List<UserInfo> profiles = mUserManager.getProfiles(userId);
        final int size = profiles.size();
        for (int i = 0; i < size; i++) {
            final UserInfo profile = profiles.get(i);
            if (!profile.isManagedProfile()) {
                continue;
            }
            final int managedUserId = profile.id;
            if (mLockPatternUtils.isSeparateProfileChallengeEnabled(managedUserId)) {
                continue;
            }
            try {
                result.put(managedUserId, getDecryptedPasswordForTiedProfile(managedUserId));
            } catch (KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException
                    | NoSuchPaddingException | InvalidKeyException
                    | InvalidAlgorithmParameterException | IllegalBlockSizeException
                    | BadPaddingException | CertificateException | IOException e) {
                Slog.e(TAG, "getDecryptedPasswordsForAllTiedProfiles failed for user " +
                    managedUserId, e);
            }
        }
        return result;
    }

    /**
     * Synchronize all profile's work challenge of the given user if it's unified: tie or clear them
     * depending on the parent user's secure state.
     *
     * When clearing tied work challenges, a pre-computed password table for profiles are required,
     * since changing password for profiles requires existing password, and existing passwords can
     * only be computed before the parent user's password is cleared.
     *
     * Strictly this is a recursive function, since setLockCredentialInternal ends up calling this
     * method again on profiles. However the recursion is guaranteed to terminate as this method
     * terminates when the user is a managed profile.
     */
    private void synchronizeUnifiedWorkChallengeForProfiles(int userId,
            Map<Integer, byte[]> profilePasswordMap) throws RemoteException {
        if (mUserManager.getUserInfo(userId).isManagedProfile()) {
            return;
        }
        final boolean isSecure = isUserSecure(userId);
        final List<UserInfo> profiles = mUserManager.getProfiles(userId);
        final int size = profiles.size();
        for (int i = 0; i < size; i++) {
            final UserInfo profile = profiles.get(i);
            if (profile.isManagedProfile()) {
                final int managedUserId = profile.id;
                if (mLockPatternUtils.isSeparateProfileChallengeEnabled(managedUserId)) {
                    continue;
                }
                if (isSecure) {
                    tieManagedProfileLockIfNecessary(managedUserId, null);
                } else {
                    // We use cached work profile password computed before clearing the parent's
                    // credential, otherwise they get lost
                    if (profilePasswordMap != null && profilePasswordMap.containsKey(managedUserId)) {
                        setLockCredentialInternal(null, LockPatternUtils.CREDENTIAL_TYPE_NONE,
                                profilePasswordMap.get(managedUserId),
                                DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, managedUserId);
                    } else {
                        Slog.wtf(TAG, "clear tied profile challenges, but no password supplied.");
                        // Supplying null here would lead to untrusted credential change
                        setLockCredentialInternal(null, LockPatternUtils.CREDENTIAL_TYPE_NONE, null,
                                DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, managedUserId);
                    }
                    mStorage.removeChildProfileLock(managedUserId);
                    removeKeystoreProfileKey(managedUserId);
                }
            }
        }
    }

    private boolean isManagedProfileWithUnifiedLock(int userId) {
        return mUserManager.getUserInfo(userId).isManagedProfile()
                && !mLockPatternUtils.isSeparateProfileChallengeEnabled(userId);
    }

    private boolean isManagedProfileWithSeparatedLock(int userId) {
        return mUserManager.getUserInfo(userId).isManagedProfile()
                && mLockPatternUtils.isSeparateProfileChallengeEnabled(userId);
    }

    // This method should be called by LockPatternUtil only, all internal methods in this class
    // should call setLockCredentialInternal.
    @Override
    public void setLockCredential(byte[] credential, int type,
            byte[] savedCredential, int requestedQuality, int userId)
            throws RemoteException {

        if (!mLockPatternUtils.hasSecureLockScreen()) {
            throw new UnsupportedOperationException(
                    "This operation requires secure lock screen feature");
        }
        checkWritePermission(userId);
        synchronized (mSeparateChallengeLock) {
            setLockCredentialInternal(credential, type, savedCredential, requestedQuality, userId);
            setSeparateProfileChallengeEnabledLocked(userId, true, null);
            notifyPasswordChanged(userId);
        }
        notifySeparateProfileChallengeChanged(userId);
    }

    private void setLockCredentialInternal(byte[] credential, int credentialType,
            byte[] savedCredential, int requestedQuality, int userId) throws RemoteException {
        // Normalize savedCredential and credential such that empty string is always represented
        // as null.
        if (savedCredential == null || savedCredential.length == 0) {
            savedCredential = null;
        }
        if (credential == null || credential.length == 0) {
            credential = null;
        }
        synchronized (mSpManager) {
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                spBasedSetLockCredentialInternalLocked(credential, credentialType, savedCredential,
                        requestedQuality, userId);
                return;
            }
        }

        if (credentialType == LockPatternUtils.CREDENTIAL_TYPE_NONE) {
            if (credential != null) {
                Slog.wtf(TAG, "CredentialType is none, but credential is non-null.");
            }
            clearUserKeyProtection(userId);
            getGateKeeperService().clearSecureUserId(userId);
            mStorage.writeCredentialHash(CredentialHash.createEmptyHash(), userId);
            setKeystorePassword(null, userId);
            fixateNewestUserKeyAuth(userId);
            synchronizeUnifiedWorkChallengeForProfiles(userId, null);
            notifyActivePasswordMetricsAvailable(null, userId);
            mRecoverableKeyStoreManager.lockScreenSecretChanged(credentialType, credential, userId);
            return;
        }
        if (credential == null) {
            throw new RemoteException("Null credential with mismatched credential type");
        }

        CredentialHash currentHandle = mStorage.readCredentialHash(userId);
        if (isManagedProfileWithUnifiedLock(userId)) {
            // get credential from keystore when managed profile has unified lock
            if (savedCredential == null) {
                try {
                    savedCredential = getDecryptedPasswordForTiedProfile(userId);
                } catch (FileNotFoundException e) {
                    Slog.i(TAG, "Child profile key not found");
                } catch (UnrecoverableKeyException | InvalidKeyException | KeyStoreException
                        | NoSuchAlgorithmException | NoSuchPaddingException
                        | InvalidAlgorithmParameterException | IllegalBlockSizeException
                        | BadPaddingException | CertificateException | IOException e) {
                    Slog.e(TAG, "Failed to decrypt child profile key", e);
                }
            }
        } else {
            if (currentHandle.hash == null) {
                if (savedCredential != null) {
                    Slog.w(TAG, "Saved credential provided, but none stored");
                }
                savedCredential = null;
            }
        }
        synchronized (mSpManager) {
            if (shouldMigrateToSyntheticPasswordLocked(userId)) {
                initializeSyntheticPasswordLocked(currentHandle.hash, savedCredential,
                        currentHandle.type, requestedQuality, userId);
                spBasedSetLockCredentialInternalLocked(credential, credentialType, savedCredential,
                        requestedQuality, userId);
                return;
            }
        }
        if (DEBUG) Slog.d(TAG, "setLockCredentialInternal: user=" + userId);
        byte[] enrolledHandle = enrollCredential(currentHandle.hash, savedCredential, credential,
                userId);
        if (enrolledHandle != null) {
            CredentialHash willStore = CredentialHash.create(enrolledHandle, credentialType);
            mStorage.writeCredentialHash(willStore, userId);
            // push new secret and auth token to vold
            GateKeeperResponse gkResponse = getGateKeeperService()
                    .verifyChallenge(userId, 0, willStore.hash, credential);
            setUserKeyProtection(userId, credential, convertResponse(gkResponse));
            fixateNewestUserKeyAuth(userId);
            // Refresh the auth token
            doVerifyCredential(credential, credentialType, true, 0, userId, null /* progressCallback */);
            synchronizeUnifiedWorkChallengeForProfiles(userId, null);
            mRecoverableKeyStoreManager.lockScreenSecretChanged(credentialType, credential,
                userId);
        } else {
            throw new RemoteException("Failed to enroll " +
                    (credentialType == LockPatternUtils.CREDENTIAL_TYPE_PASSWORD ? "password"
                            : "pattern"));
        }
    }

    private VerifyCredentialResponse convertResponse(GateKeeperResponse gateKeeperResponse) {
        return VerifyCredentialResponse.fromGateKeeperResponse(gateKeeperResponse);
    }

    @VisibleForTesting
    protected void tieProfileLockToParent(int userId, byte[] password) {
        if (DEBUG) Slog.v(TAG, "tieProfileLockToParent for user: " + userId);
        byte[] encryptionResult;
        byte[] iv;
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES);
            keyGenerator.init(new SecureRandom());
            SecretKey secretKey = keyGenerator.generateKey();
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            try {
                keyStore.setEntry(
                        LockPatternUtils.PROFILE_KEY_NAME_ENCRYPT + userId,
                        new java.security.KeyStore.SecretKeyEntry(secretKey),
                        new KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build());
                keyStore.setEntry(
                        LockPatternUtils.PROFILE_KEY_NAME_DECRYPT + userId,
                        new java.security.KeyStore.SecretKeyEntry(secretKey),
                        new KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setUserAuthenticationRequired(true)
                                .setUserAuthenticationValidityDurationSeconds(30)
                                .setCriticalToDeviceEncryption(true)
                                .build());
                // Key imported, obtain a reference to it.
                SecretKey keyStoreEncryptionKey = (SecretKey) keyStore.getKey(
                        LockPatternUtils.PROFILE_KEY_NAME_ENCRYPT + userId, null);
                Cipher cipher = Cipher.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_GCM + "/"
                                + KeyProperties.ENCRYPTION_PADDING_NONE);
                cipher.init(Cipher.ENCRYPT_MODE, keyStoreEncryptionKey);
                encryptionResult = cipher.doFinal(password);
                iv = cipher.getIV();
            } finally {
                // The original key can now be discarded.
                keyStore.deleteEntry(LockPatternUtils.PROFILE_KEY_NAME_ENCRYPT + userId);
            }
        } catch (CertificateException | UnrecoverableKeyException
                | IOException | BadPaddingException | IllegalBlockSizeException | KeyStoreException
                | NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("Failed to encrypt key", e);
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            if (iv.length != PROFILE_KEY_IV_SIZE) {
                throw new RuntimeException("Invalid iv length: " + iv.length);
            }
            outputStream.write(iv);
            outputStream.write(encryptionResult);
        } catch (IOException e) {
            throw new RuntimeException("Failed to concatenate byte arrays", e);
        }
        mStorage.writeChildProfileLock(userId, outputStream.toByteArray());
    }

    private byte[] enrollCredential(byte[] enrolledHandle,
            byte[] enrolledCredential, byte[] toEnroll, int userId)
            throws RemoteException {
        checkWritePermission(userId);
        GateKeeperResponse response = getGateKeeperService().enroll(userId, enrolledHandle,
                enrolledCredential, toEnroll);

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

    private void setAuthlessUserKeyProtection(int userId, byte[] key) throws RemoteException {
        if (DEBUG) Slog.d(TAG, "setAuthlessUserKeyProtectiond: user=" + userId);
        addUserKeyAuth(userId, null, key);
    }

    private void setUserKeyProtection(int userId, byte[] credential, VerifyCredentialResponse vcr)
            throws RemoteException {
        if (DEBUG) Slog.d(TAG, "setUserKeyProtection: user=" + userId);
        if (vcr == null) {
            throw new RemoteException("Null response verifying a credential we just set");
        }
        if (vcr.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
            throw new RemoteException("Non-OK response verifying a credential we just set: "
                    + vcr.getResponseCode());
        }
        byte[] token = vcr.getPayload();
        if (token == null) {
            throw new RemoteException("Empty payload verifying a credential we just set");
        }
        addUserKeyAuth(userId, token, secretFromCredential(credential));
    }

    private void clearUserKeyProtection(int userId) throws RemoteException {
        if (DEBUG) Slog.d(TAG, "clearUserKeyProtection user=" + userId);
        addUserKeyAuth(userId, null, null);
    }

    private static byte[] secretFromCredential(byte[] credential) throws RemoteException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            // Personalize the hash
            byte[] personalization = "Android FBE credential hash".getBytes();
            // Pad it to the block size of the hash function
            personalization = Arrays.copyOf(personalization, 128);
            digest.update(personalization);
            digest.update(credential);
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("NoSuchAlgorithmException for SHA-512");
        }
    }

    private void addUserKeyAuth(int userId, byte[] token, byte[] secret)
            throws RemoteException {
        final UserInfo userInfo = mUserManager.getUserInfo(userId);
        final IStorageManager storageManager = mInjector.getStorageManager();
        final long callingId = Binder.clearCallingIdentity();
        try {
            storageManager.addUserKeyAuth(userId, userInfo.serialNumber, token, secret);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private void fixateNewestUserKeyAuth(int userId)
            throws RemoteException {
        if (DEBUG) Slog.d(TAG, "fixateNewestUserKeyAuth: user=" + userId);
        final IStorageManager storageManager = mInjector.getStorageManager();
        final long callingId = Binder.clearCallingIdentity();
        try {
            storageManager.fixateNewestUserKeyAuth(userId);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void resetKeyStore(int userId) throws RemoteException {
        checkWritePermission(userId);
        if (DEBUG) Slog.v(TAG, "Reset keystore for user: " + userId);
        int managedUserId = -1;
        byte[] managedUserDecryptedPassword = null;
        final List<UserInfo> profiles = mUserManager.getProfiles(userId);
        for (UserInfo pi : profiles) {
            // Unlock managed profile with unified lock
            if (pi.isManagedProfile()
                    && !mLockPatternUtils.isSeparateProfileChallengeEnabled(pi.id)
                    && mStorage.hasChildProfileLock(pi.id)) {
                try {
                    if (managedUserId == -1) {
                        managedUserDecryptedPassword = getDecryptedPasswordForTiedProfile(pi.id);
                        managedUserId = pi.id;
                    } else {
                        // Should not happen
                        Slog.e(TAG, "More than one managed profile, uid1:" + managedUserId
                                + ", uid2:" + pi.id);
                    }
                } catch (UnrecoverableKeyException | InvalidKeyException | KeyStoreException
                        | NoSuchAlgorithmException | NoSuchPaddingException
                        | InvalidAlgorithmParameterException | IllegalBlockSizeException
                        | BadPaddingException | CertificateException | IOException e) {
                    Slog.e(TAG, "Failed to decrypt child profile key", e);
                }
            }
        }
        try {
            // Clear all the users credentials could have been installed in for this user.
            for (int profileId : mUserManager.getProfileIdsWithDisabled(userId)) {
                for (int uid : SYSTEM_CREDENTIAL_UIDS) {
                    mKeyStore.clearUid(UserHandle.getUid(profileId, uid));
                }
            }
        } finally {
            if (managedUserId != -1 && managedUserDecryptedPassword != null) {
                if (DEBUG) Slog.v(TAG, "Restore tied profile lock");
                tieProfileLockToParent(managedUserId, managedUserDecryptedPassword);
            }
        }
        if (managedUserDecryptedPassword != null && managedUserDecryptedPassword.length > 0) {
            Arrays.fill(managedUserDecryptedPassword, (byte) 0);
        }
    }

    @Override
    public VerifyCredentialResponse checkCredential(byte[] credential, int type, int userId,
            ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        checkPasswordReadPermission(userId);
        return doVerifyCredential(credential, type, false, 0, userId, progressCallback);
    }

    @Override
    public VerifyCredentialResponse verifyCredential(byte[] credential, int type, long challenge,
            int userId) throws RemoteException {
        checkPasswordReadPermission(userId);
        return doVerifyCredential(credential, type, true, challenge, userId,
                null /* progressCallback */);
    }

    /**
     * Verify user credential and unlock the user. Fix pattern bug by deprecating the old base zero
     * format.
     */
    private VerifyCredentialResponse doVerifyCredential(byte[] credential, int credentialType,
            boolean hasChallenge, long challenge, int userId,
            ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        if (credential == null || credential.length == 0) {
            throw new IllegalArgumentException("Credential can't be null or empty");
        }
        if (userId == USER_FRP && Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0) {
            Slog.e(TAG, "FRP credential can only be verified prior to provisioning.");
            return VerifyCredentialResponse.ERROR;
        }
        VerifyCredentialResponse response = null;
        response = spBasedDoVerifyCredential(credential, credentialType, hasChallenge, challenge,
                userId, progressCallback);
        // The user employs synthetic password based credential.
        if (response != null) {
            if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
                mRecoverableKeyStoreManager.lockScreenSecretAvailable(credentialType, credential,
                        userId);
            }
            return response;
        }

        if (userId == USER_FRP) {
            Slog.wtf(TAG, "Unexpected FRP credential type, should be SP based.");
            return VerifyCredentialResponse.ERROR;
        }

        final CredentialHash storedHash = mStorage.readCredentialHash(userId);
        if (storedHash.type != credentialType) {
            Slog.wtf(TAG, "doVerifyCredential type mismatch with stored credential??"
                    + " stored: " + storedHash.type + " passed in: " + credentialType);
            return VerifyCredentialResponse.ERROR;
        }

        boolean shouldReEnrollBaseZero = storedHash.type == LockPatternUtils.CREDENTIAL_TYPE_PATTERN
                && storedHash.isBaseZeroPattern;

        byte[] credentialToVerify;
        if (shouldReEnrollBaseZero) {
            credentialToVerify = LockPatternUtils.patternByteArrayToBaseZero(credential);
        } else {
            credentialToVerify = credential;
        }

        response = verifyCredential(userId, storedHash, credentialToVerify,
                hasChallenge, challenge, progressCallback);

        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
            mStrongAuth.reportSuccessfulStrongAuthUnlock(userId);
            if (shouldReEnrollBaseZero) {
                setLockCredentialInternal(credential, storedHash.type, credentialToVerify,
                        DevicePolicyManager.PASSWORD_QUALITY_SOMETHING, userId);
            }
        }

        return response;
    }

    @Override
    public VerifyCredentialResponse verifyTiedProfileChallenge(byte[] credential, int type,
            long challenge, int userId) throws RemoteException {
        checkPasswordReadPermission(userId);
        if (!isManagedProfileWithUnifiedLock(userId)) {
            throw new RemoteException("User id must be managed profile with unified lock");
        }
        final int parentProfileId = mUserManager.getProfileParent(userId).id;
        // Unlock parent by using parent's challenge
        final VerifyCredentialResponse parentResponse = doVerifyCredential(
                credential,
                type,
                true /* hasChallenge */,
                challenge,
                parentProfileId,
                null /* progressCallback */);
        if (parentResponse.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
            // Failed, just return parent's response
            return parentResponse;
        }

        try {
            // Unlock work profile, and work profile with unified lock must use password only
            return doVerifyCredential(getDecryptedPasswordForTiedProfile(userId),
                    LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                    true,
                    challenge,
                    userId, null /* progressCallback */);
        } catch (UnrecoverableKeyException | InvalidKeyException | KeyStoreException
                | NoSuchAlgorithmException | NoSuchPaddingException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException
                | BadPaddingException | CertificateException | IOException e) {
            Slog.e(TAG, "Failed to decrypt child profile key", e);
            throw new RemoteException("Unable to get tied profile token");
        }
    }

    /**
     * Lowest-level credential verification routine that talks to GateKeeper. If verification
     * passes, unlock the corresponding user and keystore. Also handles the migration from legacy
     * hash to GK.
     */
    private VerifyCredentialResponse verifyCredential(int userId, CredentialHash storedHash,
            byte[] credential, boolean hasChallenge, long challenge,
            ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        if ((storedHash == null || storedHash.hash.length == 0)
                    && (credential == null || credential.length == 0)) {
            // don't need to pass empty credentials to GateKeeper
            return VerifyCredentialResponse.OK;
        }

        if (storedHash == null || credential == null || credential.length == 0) {
            return VerifyCredentialResponse.ERROR;
        }

        // We're potentially going to be doing a bunch of disk I/O below as part
        // of unlocking the user, so yell if calling from the main thread.
        StrictMode.noteDiskRead();

        if (storedHash.version == CredentialHash.VERSION_LEGACY) {
            final byte[] hash;
            if (storedHash.type == LockPatternUtils.CREDENTIAL_TYPE_PATTERN) {
                hash = LockPatternUtils.patternToHash(
                        LockPatternUtils.byteArrayToPattern(credential));
            } else {
                hash = mLockPatternUtils.legacyPasswordToHash(credential, userId).getBytes();
            }
            if (Arrays.equals(hash, storedHash.hash)) {
                if (storedHash.type == LockPatternUtils.CREDENTIAL_TYPE_PATTERN) {
                    unlockKeystore(LockPatternUtils.patternByteArrayToBaseZero(credential), userId);
                } else {
                    unlockKeystore(credential, userId);
                }
                // Users with legacy credentials don't have credential-backed
                // FBE keys, so just pass through a fake token/secret
                Slog.i(TAG, "Unlocking user with fake token: " + userId);
                final byte[] fakeToken = String.valueOf(userId).getBytes();
                unlockUser(userId, fakeToken, fakeToken);

                // migrate credential to GateKeeper
                setLockCredentialInternal(credential, storedHash.type, null,
                        storedHash.type == LockPatternUtils.CREDENTIAL_TYPE_PATTERN
                                ? DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
                                : DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                                /* TODO(roosa): keep the same password quality */, userId);
                if (!hasChallenge) {
                    notifyActivePasswordMetricsAvailable(credential, userId);
                    // Use credentials to create recoverable keystore snapshot.
                    mRecoverableKeyStoreManager.lockScreenSecretAvailable(
                            storedHash.type, credential, userId);
                    return VerifyCredentialResponse.OK;
                }
                // Fall through to get the auth token. Technically this should never happen,
                // as a user that had a legacy credential would have to unlock their device
                // before getting to a flow with a challenge, but supporting for consistency.
            } else {
                return VerifyCredentialResponse.ERROR;
            }
        }
        GateKeeperResponse gateKeeperResponse = getGateKeeperService()
                .verifyChallenge(userId, challenge, storedHash.hash, credential);
        VerifyCredentialResponse response = convertResponse(gateKeeperResponse);
        boolean shouldReEnroll = gateKeeperResponse.getShouldReEnroll();

        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {

            // credential has matched

            if (progressCallback != null) {
                progressCallback.onCredentialVerified();
            }
            notifyActivePasswordMetricsAvailable(credential, userId);
            unlockKeystore(credential, userId);

            Slog.i(TAG, "Unlocking user " + userId + " with token length "
                    + response.getPayload().length);
            unlockUser(userId, response.getPayload(), secretFromCredential(credential));

            if (isManagedProfileWithSeparatedLock(userId)) {
                TrustManager trustManager =
                        (TrustManager) mContext.getSystemService(Context.TRUST_SERVICE);
                trustManager.setDeviceLockedForUser(userId, false);
            }
            int reEnrollQuality = storedHash.type == LockPatternUtils.CREDENTIAL_TYPE_PATTERN
                    ? DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
                    : DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC
                    /* TODO(roosa): keep the same password quality */;
            if (shouldReEnroll) {
                setLockCredentialInternal(credential, storedHash.type, credential,
                        reEnrollQuality, userId);
            } else {
                // Now that we've cleared of all required GK migration, let's do the final
                // migration to synthetic password.
                synchronized (mSpManager) {
                    if (shouldMigrateToSyntheticPasswordLocked(userId)) {
                        AuthenticationToken auth = initializeSyntheticPasswordLocked(
                                storedHash.hash, credential, storedHash.type, reEnrollQuality,
                                userId);
                        activateEscrowTokens(auth, userId);
                    }
                }
            }
            // Use credentials to create recoverable keystore snapshot.
            mRecoverableKeyStoreManager.lockScreenSecretAvailable(storedHash.type, credential,
                userId);

        } else if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
            if (response.getTimeout() > 0) {
                requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_LOCKOUT, userId);
            }
        }

        return response;
    }

    /**
     * Call this method to notify DPMS regarding the latest password metric. This should be called
     * when the user is authenticating or when a new password is being set.
     */
    private void notifyActivePasswordMetricsAvailable(byte[] password, @UserIdInt int userId) {
        final PasswordMetrics metrics;
        if (password == null) {
            metrics = new PasswordMetrics();
        } else {
            metrics = PasswordMetrics.computeForPassword(password);
            metrics.quality = mLockPatternUtils.getKeyguardStoredPasswordQuality(userId);
        }

        // Asynchronous to avoid dead lock
        mHandler.post(() -> {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.setActivePasswordState(metrics, userId);
        });
    }

    /**
     * Call after {@link #notifyActivePasswordMetricsAvailable} so metrics are updated before
     * reporting the password changed.
     */
    private void notifyPasswordChanged(@UserIdInt int userId) {
        // Same handler as notifyActivePasswordMetricsAvailable to ensure correct ordering
        mHandler.post(() -> {
            DevicePolicyManager dpm = (DevicePolicyManager)
                    mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
            dpm.reportPasswordChanged(userId);
            LocalServices.getService(WindowManagerInternal.class).reportPasswordChanged(userId);
        });
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
        final IStorageManager service = mInjector.getStorageManager();
        // TODO(b/120484642): Update vold to return a password as a byte array
        String password;
        long identity = Binder.clearCallingIdentity();
        try {
            password = service.getPassword();
            service.clearPassword();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        if (password == null) {
            return false;
        }

        try {
            if (mLockPatternUtils.isLockPatternEnabled(userId)) {
                if (checkCredential(password.getBytes(), LockPatternUtils.CREDENTIAL_TYPE_PATTERN,
                        userId, null /* progressCallback */)
                                .getResponseCode() == GateKeeperResponse.RESPONSE_OK) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        try {
            if (mLockPatternUtils.isLockPasswordEnabled(userId)) {
                if (checkCredential(password.getBytes(), LockPatternUtils.CREDENTIAL_TYPE_PASSWORD,
                        userId, null /* progressCallback */)
                                .getResponseCode() == GateKeeperResponse.RESPONSE_OK) {
                    return true;
                }
            }
        } catch (Exception e) {
        }

        return false;
    }

    private void removeUser(int userId, boolean unknownUser) {
        mSpManager.removeUser(userId);
        mStorage.removeUser(userId);
        mStrongAuth.removeUser(userId);
        tryRemoveUserFromSpCacheLater(userId);

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
        if (unknownUser || mUserManager.getUserInfo(userId).isManagedProfile()) {
            removeKeystoreProfileKey(userId);
        }
    }

    private void removeKeystoreProfileKey(int targetUserId) {
        if (DEBUG) Slog.v(TAG, "Remove keystore profile key for user: " + targetUserId);
        try {
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            keyStore.deleteEntry(LockPatternUtils.PROFILE_KEY_NAME_ENCRYPT + targetUserId);
            keyStore.deleteEntry(LockPatternUtils.PROFILE_KEY_NAME_DECRYPT + targetUserId);
        } catch (KeyStoreException | NoSuchAlgorithmException | CertificateException
                | IOException e) {
            // We have tried our best to remove all keys
            Slog.e(TAG, "Unable to remove keystore profile key for user:" + targetUserId, e);
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

    @Override
    public void userPresent(int userId) {
        checkWritePermission(userId);
        mStrongAuth.reportUnlock(userId);
    }

    @Override
    public int getStrongAuthForUser(int userId) {
        checkPasswordReadPermission(userId);
        return mStrongAuthTracker.getStrongAuthForUser(userId);
    }

    private boolean isCallerShell() {
        final int callingUid = Binder.getCallingUid();
        return callingUid == Process.SHELL_UID || callingUid == Process.ROOT_UID;
    }

    private void enforceShell() {
        if (!isCallerShell()) {
            throw new SecurityException("Caller must be shell");
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver)
            throws RemoteException {
        enforceShell();
        final long origId = Binder.clearCallingIdentity();
        try {
            (new LockSettingsShellCommand(new LockPatternUtils(mContext))).exec(
                    this, in, out, err, args, callback, resultReceiver);
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    @Override
    public void initRecoveryServiceWithSigFile(@NonNull String rootCertificateAlias,
            @NonNull byte[] recoveryServiceCertFile, @NonNull byte[] recoveryServiceSigFile)
            throws RemoteException {
        mRecoverableKeyStoreManager.initRecoveryServiceWithSigFile(rootCertificateAlias,
                recoveryServiceCertFile, recoveryServiceSigFile);
    }

    @Override
    public @NonNull KeyChainSnapshot getKeyChainSnapshot() throws RemoteException {
        return mRecoverableKeyStoreManager.getKeyChainSnapshot();
    }

    @Override
    public void setSnapshotCreatedPendingIntent(@Nullable PendingIntent intent)
            throws RemoteException {
        mRecoverableKeyStoreManager.setSnapshotCreatedPendingIntent(intent);
    }

    @Override
    public void setServerParams(byte[] serverParams) throws RemoteException {
        mRecoverableKeyStoreManager.setServerParams(serverParams);
    }

    @Override
    public void setRecoveryStatus(String alias, int status) throws RemoteException {
        mRecoverableKeyStoreManager.setRecoveryStatus(alias, status);
    }

    @Override
    public @NonNull Map getRecoveryStatus() throws RemoteException {
        return mRecoverableKeyStoreManager.getRecoveryStatus();
    }

    @Override
    public void setRecoverySecretTypes(@NonNull @KeyChainProtectionParams.UserSecretType
            int[] secretTypes) throws RemoteException {
        mRecoverableKeyStoreManager.setRecoverySecretTypes(secretTypes);
    }

    @Override
    public @NonNull int[] getRecoverySecretTypes() throws RemoteException {
        return mRecoverableKeyStoreManager.getRecoverySecretTypes();

    }

    @Override
    public @NonNull byte[] startRecoverySessionWithCertPath(@NonNull String sessionId,
            @NonNull String rootCertificateAlias, @NonNull RecoveryCertPath verifierCertPath,
            @NonNull byte[] vaultParams, @NonNull byte[] vaultChallenge,
            @NonNull List<KeyChainProtectionParams> secrets)
            throws RemoteException {
        return mRecoverableKeyStoreManager.startRecoverySessionWithCertPath(
                sessionId, rootCertificateAlias, verifierCertPath, vaultParams, vaultChallenge,
                secrets);
    }

    @Override
    public Map<String, String> recoverKeyChainSnapshot(
            @NonNull String sessionId,
            @NonNull byte[] recoveryKeyBlob,
            @NonNull List<WrappedApplicationKey> applicationKeys) throws RemoteException {
        return mRecoverableKeyStoreManager.recoverKeyChainSnapshot(
                sessionId, recoveryKeyBlob, applicationKeys);
    }

    @Override
    public void closeSession(@NonNull String sessionId) throws RemoteException {
        mRecoverableKeyStoreManager.closeSession(sessionId);
    }

    @Override
    public void removeKey(@NonNull String alias) throws RemoteException {
        mRecoverableKeyStoreManager.removeKey(alias);
    }

    @Override
    public @Nullable String generateKey(@NonNull String alias) throws RemoteException {
        return mRecoverableKeyStoreManager.generateKey(alias);
    }

    @Override
    public @Nullable String generateKeyWithMetadata(
            @NonNull String alias, @Nullable byte[] metadata) throws RemoteException {
        return mRecoverableKeyStoreManager.generateKeyWithMetadata(alias, metadata);
    }

    @Override
    public @Nullable String importKey(@NonNull String alias, @NonNull byte[] keyBytes)
            throws RemoteException {
        return mRecoverableKeyStoreManager.importKey(alias, keyBytes);
    }

    @Override
    public @Nullable String importKeyWithMetadata(@NonNull String alias, @NonNull byte[] keyBytes,
            @Nullable byte[] metadata) throws RemoteException {
        return mRecoverableKeyStoreManager.importKeyWithMetadata(alias, keyBytes, metadata);
    }

    @Override
    public @Nullable String getKey(@NonNull String alias) throws RemoteException {
        return mRecoverableKeyStoreManager.getKey(alias);
    }

    private static final String[] VALID_SETTINGS = new String[] {
            LockPatternUtils.LOCKOUT_PERMANENT_KEY,
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
            SEPARATE_PROFILE_CHALLENGE_KEY
    };

    private static final String[] SETTINGS_TO_BACKUP = new String[] {
            Secure.LOCK_SCREEN_OWNER_INFO_ENABLED,
            Secure.LOCK_SCREEN_OWNER_INFO,
            Secure.LOCK_PATTERN_VISIBLE,
            LockPatternUtils.LOCKSCREEN_POWER_BUTTON_INSTANTLY_LOCKS
    };

    private class GateKeeperDiedRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            mGateKeeperService.asBinder().unlinkToDeath(this, 0);
            mGateKeeperService = null;
        }
    }

    protected synchronized IGateKeeperService getGateKeeperService()
            throws RemoteException {
        if (mGateKeeperService != null) {
            return mGateKeeperService;
        }

        final IBinder service = ServiceManager.getService(Context.GATEKEEPER_SERVICE);
        if (service != null) {
            service.linkToDeath(new GateKeeperDiedRecipient(), 0);
            mGateKeeperService = IGateKeeperService.Stub.asInterface(service);
            return mGateKeeperService;
        }

        Slog.e(TAG, "Unable to acquire GateKeeperService");
        return null;
    }

    /**
     * A user's synthetic password does not change so it must be cached in certain circumstances to
     * enable untrusted credential reset.
     *
     * Untrusted credential reset will be removed in a future version (b/68036371) at which point
     * this cache is no longer needed as the SP will always be known when changing the user's
     * credential.
     */
    @GuardedBy("mSpManager")
    private SparseArray<AuthenticationToken> mSpCache = new SparseArray();

    private void onAuthTokenKnownForUser(@UserIdInt int userId, AuthenticationToken auth) {
        // Preemptively cache the SP and then try to remove it in a handler.
        Slog.i(TAG, "Caching SP for user " + userId);
        synchronized (mSpManager) {
            mSpCache.put(userId, auth);
        }
        tryRemoveUserFromSpCacheLater(userId);

        if (mInjector.isGsiRunning()) {
            Slog.w(TAG, "AuthSecret disabled in GSI");
            return;
        }

        // Pass the primary user's auth secret to the HAL
        if (mAuthSecretService != null && mUserManager.getUserInfo(userId).isPrimary()) {
            try {
                final byte[] rawSecret = auth.deriveVendorAuthSecret();
                final ArrayList<Byte> secret = new ArrayList<>(rawSecret.length);
                for (int i = 0; i < rawSecret.length; ++i) {
                    secret.add(rawSecret[i]);
                }
                mAuthSecretService.primaryUserCredential(secret);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to pass primary user secret to AuthSecret HAL", e);
            }
        }
    }

    private void tryRemoveUserFromSpCacheLater(@UserIdInt int userId) {
        mHandler.post(() -> {
            if (!shouldCacheSpForUser(userId)) {
                // The transition from 'should not cache' to 'should cache' can only happen if
                // certain admin apps are installed after provisioning e.g. via adb. This is not
                // a common case and we do not seamlessly support; it may result in the SP not
                // being cached when it is needed. The cache can be re-populated by verifying
                // the credential again.
                Slog.i(TAG, "Removing SP from cache for user " + userId);
                synchronized (mSpManager) {
                    mSpCache.remove(userId);
                }
            }
        });
    }

    /** Do not hold any of the locks from this service when calling. */
    private boolean shouldCacheSpForUser(@UserIdInt int userId) {
        // Before the user setup has completed, an admin could be installed that requires the SP to
        // be cached (see below).
        if (Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE, 0, userId) == 0) {
            return true;
        }

        // If the user has an admin which can perform an untrusted credential reset, the SP needs to
        // be cached. If there isn't a DevicePolicyManager then there can't be an admin in the first
        // place so caching is not necessary.
        final DevicePolicyManagerInternal dpmi = LocalServices.getService(
                DevicePolicyManagerInternal.class);
        if (dpmi == null) {
            return false;
        }
        return dpmi.canUserHaveUntrustedCredentialReset(userId);
    }

    /**
     * Precondition: vold and keystore unlocked.
     *
     * Create new synthetic password, set up synthetic password blob protected by the supplied
     * user credential, and make the newly-created SP blob active.
     *
     * The invariant under a synthetic password is:
     * 1. If user credential exists, then both vold and keystore and protected with keys derived
     *     from the synthetic password.
     * 2. If user credential does not exist, vold and keystore protection are cleared. This is to
     *     make it consistent with current behaviour. It also allows ActivityManager to call
     *     unlockUser() with empty secret.
     * 3. Once a user is migrated to have synthetic password, its value will never change, no matter
     *     whether the user changes his lockscreen PIN or clear/reset it. When the user clears its
     *     lockscreen PIN, we still maintain the existing synthetic password in a password blob
     *     protected by a default PIN.
     * 4. The user SID is linked with synthetic password, but its cleared/re-created when the user
     *     clears/re-creates his lockscreen PIN.
     *
     *
     * Different cases of calling this method:
     * 1. credentialHash != null
     *     This implies credential != null, a new SP blob will be provisioned, and existing SID
     *     migrated to associate with the new SP.
     *     This happens during a normal migration case when the user currently has password.
     *
     * 2. credentialhash == null and credential == null
     *     A new SP blob and will be created, while the user has no credentials.
     *     This can happens when we are activating an escrow token on a unsecured device, during
     *     which we want to create the SP structure with an empty user credential.
     *     This could also happen during an untrusted reset to clear password.
     *
     * 3. credentialhash == null and credential != null
     *     This is the untrusted credential reset, OR the user sets a new lockscreen password
     *     FOR THE FIRST TIME on a SP-enabled device. New credential and new SID will be created
     */
    @GuardedBy("mSpManager")
    @VisibleForTesting
    protected AuthenticationToken initializeSyntheticPasswordLocked(byte[] credentialHash,
            byte[] credential, int credentialType, int requestedQuality,
            int userId) throws RemoteException {
        Slog.i(TAG, "Initialize SyntheticPassword for user: " + userId);
        final AuthenticationToken auth = mSpManager.newSyntheticPasswordAndSid(
                getGateKeeperService(), credentialHash, credential, userId);
        onAuthTokenKnownForUser(userId, auth);
        if (auth == null) {
            Slog.wtf(TAG, "initializeSyntheticPasswordLocked returns null auth token");
            return null;
        }
        long handle = mSpManager.createPasswordBasedSyntheticPassword(getGateKeeperService(),
                credential, credentialType, auth, requestedQuality, userId);
        if (credential != null) {
            if (credentialHash == null) {
                // Since when initializing SP, we didn't provide an existing password handle
                // for it to migrate SID, we need to create a new SID for the user.
                mSpManager.newSidForUser(getGateKeeperService(), auth, userId);
            }
            mSpManager.verifyChallenge(getGateKeeperService(), auth, 0L, userId);
            setAuthlessUserKeyProtection(userId, auth.deriveDiskEncryptionKey());
            setKeystorePassword(auth.deriveKeyStorePassword(), userId);
        } else {
            clearUserKeyProtection(userId);
            setKeystorePassword(null, userId);
            getGateKeeperService().clearSecureUserId(userId);
        }
        fixateNewestUserKeyAuth(userId);
        setLong(SYNTHETIC_PASSWORD_HANDLE_KEY, handle, userId);
        return auth;
    }

    private long getSyntheticPasswordHandleLocked(int userId) {
        return getLong(SYNTHETIC_PASSWORD_HANDLE_KEY,
                SyntheticPasswordManager.DEFAULT_HANDLE, userId);
    }

    private boolean isSyntheticPasswordBasedCredentialLocked(int userId) {
        if (userId == USER_FRP) {
            final int type = mStorage.readPersistentDataBlock().type;
            return type == PersistentData.TYPE_SP || type == PersistentData.TYPE_SP_WEAVER;
        }
        long handle = getSyntheticPasswordHandleLocked(userId);
        // This is a global setting
        long enabled = getLong(SYNTHETIC_PASSWORD_ENABLED_KEY,
                SYNTHETIC_PASSWORD_ENABLED_BY_DEFAULT, UserHandle.USER_SYSTEM);
      return enabled != 0 && handle != SyntheticPasswordManager.DEFAULT_HANDLE;
    }

    @VisibleForTesting
    protected boolean shouldMigrateToSyntheticPasswordLocked(int userId) {
        long handle = getSyntheticPasswordHandleLocked(userId);
        // This is a global setting
        long enabled = getLong(SYNTHETIC_PASSWORD_ENABLED_KEY,
                SYNTHETIC_PASSWORD_ENABLED_BY_DEFAULT, UserHandle.USER_SYSTEM);
        return enabled != 0 && handle == SyntheticPasswordManager.DEFAULT_HANDLE;
    }

    private void enableSyntheticPasswordLocked() {
        setLong(SYNTHETIC_PASSWORD_ENABLED_KEY, 1, UserHandle.USER_SYSTEM);
    }

    private VerifyCredentialResponse spBasedDoVerifyCredential(byte[] userCredential, int
            credentialType, boolean hasChallenge, long challenge, int userId,
            ICheckCredentialProgressCallback progressCallback) throws RemoteException {
        if (DEBUG) Slog.d(TAG, "spBasedDoVerifyCredential: user=" + userId);
        if (credentialType == LockPatternUtils.CREDENTIAL_TYPE_NONE) {
            userCredential = null;
        }

        final PackageManager pm = mContext.getPackageManager();
        // TODO: When lockout is handled under the HAL for all biometrics (fingerprint),
        // we need to generate challenge for each one, have it signed by GK and reset lockout
        // for each modality.
        if (!hasChallenge && pm.hasSystemFeature(PackageManager.FEATURE_FACE)) {
            challenge = mContext.getSystemService(FaceManager.class).generateChallenge();
        }

        final AuthenticationResult authResult;
        VerifyCredentialResponse response;
        synchronized (mSpManager) {
            if (!isSyntheticPasswordBasedCredentialLocked(userId)) {
                return null;
            }
            if (userId == USER_FRP) {
                return mSpManager.verifyFrpCredential(getGateKeeperService(),
                        userCredential, credentialType, progressCallback);
            }

            long handle = getSyntheticPasswordHandleLocked(userId);
            authResult = mSpManager.unwrapPasswordBasedSyntheticPassword(
                    getGateKeeperService(), handle, userCredential, userId, progressCallback);

            if (authResult.credentialType != credentialType) {
                Slog.e(TAG, "Credential type mismatch.");
                return VerifyCredentialResponse.ERROR;
            }
            response = authResult.gkResponse;
            // credential has matched
            if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
                // perform verifyChallenge with synthetic password which generates the real GK auth
                // token and response for the current user
                response = mSpManager.verifyChallenge(getGateKeeperService(), authResult.authToken,
                        challenge, userId);
                if (response.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
                    // This shouldn't really happen: the unwrapping of SP succeeds, but SP doesn't
                    // match the recorded GK password handle.
                    Slog.wtf(TAG, "verifyChallenge with SP failed.");
                    return VerifyCredentialResponse.ERROR;
                }
            }
        }

        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
            notifyActivePasswordMetricsAvailable(userCredential, userId);
            unlockKeystore(authResult.authToken.deriveKeyStorePassword(), userId);
            // Reset lockout
            if (mInjector.hasBiometrics()) {
                BiometricManager bm = mContext.getSystemService(BiometricManager.class);
                Slog.i(TAG, "Resetting lockout, length: "
                        + authResult.gkResponse.getPayload().length);
                bm.resetLockout(authResult.gkResponse.getPayload());

                if (!hasChallenge && pm.hasSystemFeature(PackageManager.FEATURE_FACE)) {
                    mContext.getSystemService(FaceManager.class).revokeChallenge();
                }
            }

            final byte[] secret = authResult.authToken.deriveDiskEncryptionKey();
            Slog.i(TAG, "Unlocking user " + userId + " with secret only, length " + secret.length);
            unlockUser(userId, null, secret);

            activateEscrowTokens(authResult.authToken, userId);

            if (isManagedProfileWithSeparatedLock(userId)) {
                TrustManager trustManager =
                        (TrustManager) mContext.getSystemService(Context.TRUST_SERVICE);
                trustManager.setDeviceLockedForUser(userId, false);
            }
            mStrongAuth.reportSuccessfulStrongAuthUnlock(userId);

            onAuthTokenKnownForUser(userId, authResult.authToken);
        } else if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
            if (response.getTimeout() > 0) {
                requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_LOCKOUT, userId);
            }
        }

        return response;
    }

    /**
     * Change the user's lockscreen password by creating a new SP blob and update the handle, based
     * on an existing authentication token. Even though a new SP blob is created, the underlying
     * synthetic password is never changed.
     *
     * When clearing credential, we keep the SP unchanged, but clear its password handle so its
     * SID is gone. We also clear password from (software-based) keystore and vold, which will be
     * added back when new password is set in future.
     */
    @GuardedBy("mSpManager")
    private long setLockCredentialWithAuthTokenLocked(byte[] credential, int credentialType,
            AuthenticationToken auth, int requestedQuality, int userId) throws RemoteException {
        if (DEBUG) Slog.d(TAG, "setLockCredentialWithAuthTokenLocked: user=" + userId);
        long newHandle = mSpManager.createPasswordBasedSyntheticPassword(getGateKeeperService(),
                credential, credentialType, auth, requestedQuality, userId);
        final Map<Integer, byte[]> profilePasswords;
        if (credential != null) {
            // // not needed by synchronizeUnifiedWorkChallengeForProfiles()
            profilePasswords = null;

            if (mSpManager.hasSidForUser(userId)) {
                // We are changing password of a secured device, nothing more needed as
                // createPasswordBasedSyntheticPassword has already taken care of maintaining
                // the password handle and SID unchanged.

                //refresh auth token
                mSpManager.verifyChallenge(getGateKeeperService(), auth, 0L, userId);
            } else {
                // A new password is set on a previously-unsecured device, we need to generate
                // a new SID, and re-add keys to vold and keystore.
                mSpManager.newSidForUser(getGateKeeperService(), auth, userId);
                mSpManager.verifyChallenge(getGateKeeperService(), auth, 0L, userId);
                setAuthlessUserKeyProtection(userId, auth.deriveDiskEncryptionKey());
                fixateNewestUserKeyAuth(userId);
                setKeystorePassword(auth.deriveKeyStorePassword(), userId);
            }
        } else {
            // Cache all profile password if they use unified work challenge. This will later be
            // used to clear the profile's password in synchronizeUnifiedWorkChallengeForProfiles()
            profilePasswords = getDecryptedPasswordsForAllTiedProfiles(userId);

            // we are clearing password of a secured device, so need to nuke SID as well.
            mSpManager.clearSidForUser(userId);
            getGateKeeperService().clearSecureUserId(userId);
            // Clear key from vold so ActivityManager can just unlock the user with empty secret
            // during boot.
            clearUserKeyProtection(userId);
            fixateNewestUserKeyAuth(userId);
            setKeystorePassword(null, userId);
        }
        setLong(SYNTHETIC_PASSWORD_HANDLE_KEY, newHandle, userId);
        synchronizeUnifiedWorkChallengeForProfiles(userId, profilePasswords);

        notifyActivePasswordMetricsAvailable(credential, userId);

        if (profilePasswords != null) {
            for (Map.Entry<Integer, byte[]> entry : profilePasswords.entrySet()) {
                Arrays.fill(entry.getValue(), (byte) 0);
            }
        }

        return newHandle;
    }

    @GuardedBy("mSpManager")
    private void spBasedSetLockCredentialInternalLocked(byte[] credential, int credentialType,
            byte[] savedCredential, int requestedQuality, int userId) throws RemoteException {
        if (DEBUG) Slog.d(TAG, "spBasedSetLockCredentialInternalLocked: user=" + userId);
        if (isManagedProfileWithUnifiedLock(userId)) {
            // get credential from keystore when managed profile has unified lock
            try {
                savedCredential = getDecryptedPasswordForTiedProfile(userId);
            } catch (FileNotFoundException e) {
                Slog.i(TAG, "Child profile key not found");
            } catch (UnrecoverableKeyException | InvalidKeyException | KeyStoreException
                    | NoSuchAlgorithmException | NoSuchPaddingException
                    | InvalidAlgorithmParameterException | IllegalBlockSizeException
                    | BadPaddingException | CertificateException | IOException e) {
                Slog.e(TAG, "Failed to decrypt child profile key", e);
            }
        }
        long handle = getSyntheticPasswordHandleLocked(userId);
        AuthenticationResult authResult = mSpManager.unwrapPasswordBasedSyntheticPassword(
                getGateKeeperService(), handle, savedCredential, userId, null);
        VerifyCredentialResponse response = authResult.gkResponse;
        AuthenticationToken auth = authResult.authToken;

        // If existing credential is provided, then it must match.
        if (savedCredential != null && auth == null) {
            throw new RemoteException("Failed to enroll " +
                    (credentialType == LockPatternUtils.CREDENTIAL_TYPE_PASSWORD ? "password"
                            : "pattern"));
        }

        boolean untrustedReset = false;
        if (auth != null) {
            onAuthTokenKnownForUser(userId, auth);
        } else if (response != null
                && response.getResponseCode() == VerifyCredentialResponse.RESPONSE_ERROR) {
            // We are performing an untrusted credential change, by DevicePolicyManager or other
            // internal callers that don't provide the existing credential
            Slog.w(TAG, "Untrusted credential change invoked");
            // Try to get a cached auth token, so we can keep SP unchanged.
            auth = mSpCache.get(userId);
            untrustedReset = true;
        } else /* response == null || responseCode == VerifyCredentialResponse.RESPONSE_RETRY */ {
            Slog.w(TAG, "spBasedSetLockCredentialInternalLocked: " +
                    (response != null ? "rate limit exceeded" : "failed"));
            return;
        }

        if (auth != null) {
            if (untrustedReset) {
                // Force change the current SID to mantain existing behaviour that an untrusted
                // reset leads to a change of SID. If the untrusted reset is for clearing the
                // current password, the nuking of the SID will be done in
                // setLockCredentialWithAuthTokenLocked next
                mSpManager.newSidForUser(getGateKeeperService(), auth, userId);
            }
            setLockCredentialWithAuthTokenLocked(credential, credentialType, auth, requestedQuality,
                    userId);
            mSpManager.destroyPasswordBasedSyntheticPassword(handle, userId);
        } else {
            throw new IllegalStateException(
                    "Untrusted credential reset not possible without cached SP");
            // Could call initializeSyntheticPasswordLocked(null, credential, credentialType,
            // requestedQuality, userId) instead if we still allow untrusted reset that changes
            // synthetic password. That would invalidate existing escrow tokens though.
        }
        mRecoverableKeyStoreManager.lockScreenSecretChanged(credentialType, credential, userId);
    }

    /**
     * Returns a fixed pseudorandom byte string derived from the user's synthetic password.
     * This is used to salt the password history hash to protect the hash against offline
     * bruteforcing, since rederiving this value requires a successful authentication.
     * If user is a managed profile with unified challenge, currentCredential is ignored.
     */
    @Override
    public byte[] getHashFactor(byte[] currentCredential, int userId) throws RemoteException {
        checkPasswordReadPermission(userId);
        if (currentCredential == null || currentCredential.length == 0) {
            currentCredential = null;
        }
        if (isManagedProfileWithUnifiedLock(userId)) {
            try {
                currentCredential = getDecryptedPasswordForTiedProfile(userId);
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get work profile credential", e);
                return null;
            }
        }
        synchronized (mSpManager) {
            if (!isSyntheticPasswordBasedCredentialLocked(userId)) {
                Slog.w(TAG, "Synthetic password not enabled");
                return null;
            }
            long handle = getSyntheticPasswordHandleLocked(userId);
            AuthenticationResult auth = mSpManager.unwrapPasswordBasedSyntheticPassword(
                    getGateKeeperService(), handle, currentCredential, userId, null);
            if (auth.authToken == null) {
                Slog.w(TAG, "Current credential is incorrect");
                return null;
            }
            return auth.authToken.derivePasswordHashFactor();
        }
    }

    private long addEscrowToken(byte[] token, int userId) throws RemoteException {
        if (DEBUG) Slog.d(TAG, "addEscrowToken: user=" + userId);
        synchronized (mSpManager) {
            enableSyntheticPasswordLocked();
            // Migrate to synthetic password based credentials if the user has no password,
            // the token can then be activated immediately.
            AuthenticationToken auth = null;
            if (!isUserSecure(userId)) {
                if (shouldMigrateToSyntheticPasswordLocked(userId)) {
                    auth = initializeSyntheticPasswordLocked(null, null,
                            LockPatternUtils.CREDENTIAL_TYPE_NONE,
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userId);
                } else /* isSyntheticPasswordBasedCredentialLocked(userId) */ {
                    long pwdHandle = getSyntheticPasswordHandleLocked(userId);
                    auth = mSpManager.unwrapPasswordBasedSyntheticPassword(getGateKeeperService(),
                            pwdHandle, null, userId, null).authToken;
                }
            }
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                disableEscrowTokenOnNonManagedDevicesIfNeeded(userId);
                if (!mSpManager.hasEscrowData(userId)) {
                    throw new SecurityException("Escrow token is disabled on the current user");
                }
            }
            long handle = mSpManager.createTokenBasedSyntheticPassword(token, userId);
            if (auth != null) {
                mSpManager.activateTokenBasedSyntheticPassword(handle, auth, userId);
            }
            return handle;
        }
    }

    private void activateEscrowTokens(AuthenticationToken auth, int userId) {
        if (DEBUG) Slog.d(TAG, "activateEscrowTokens: user=" + userId);
        synchronized (mSpManager) {
            disableEscrowTokenOnNonManagedDevicesIfNeeded(userId);
            for (long handle : mSpManager.getPendingTokensForUser(userId)) {
                Slog.i(TAG, String.format("activateEscrowTokens: %x %d ", handle, userId));
                mSpManager.activateTokenBasedSyntheticPassword(handle, auth, userId);
            }
        }
    }

    private boolean isEscrowTokenActive(long handle, int userId) {
        synchronized (mSpManager) {
            return mSpManager.existsHandle(handle, userId);
        }
    }

    private boolean removeEscrowToken(long handle, int userId) {
        synchronized (mSpManager) {
            if (handle == getSyntheticPasswordHandleLocked(userId)) {
                Slog.w(TAG, "Cannot remove password handle");
                return false;
            }
            if (mSpManager.removePendingToken(handle, userId)) {
                return true;
            }
            if (mSpManager.existsHandle(handle, userId)) {
                mSpManager.destroyTokenBasedSyntheticPassword(handle, userId);
                return true;
            } else {
                return false;
            }
        }
    }

    private boolean setLockCredentialWithToken(byte[] credential, int type, long tokenHandle,
            byte[] token, int requestedQuality, int userId) throws RemoteException {
        boolean result;
        synchronized (mSpManager) {
            if (!mSpManager.hasEscrowData(userId)) {
                throw new SecurityException("Escrow token is disabled on the current user");
            }
            result = setLockCredentialWithTokenInternal(credential, type, tokenHandle, token,
                    requestedQuality, userId);
        }
        if (result) {
            synchronized (mSeparateChallengeLock) {
                setSeparateProfileChallengeEnabledLocked(userId, true, null);
            }
            notifyPasswordChanged(userId);
            notifySeparateProfileChallengeChanged(userId);
        }
        return result;
    }

    private boolean setLockCredentialWithTokenInternal(byte[] credential, int type,
            long tokenHandle, byte[] token, int requestedQuality, int userId) throws RemoteException {
        final AuthenticationResult result;
        synchronized (mSpManager) {
            result = mSpManager.unwrapTokenBasedSyntheticPassword(
                    getGateKeeperService(), tokenHandle, token, userId);
            if (result.authToken == null) {
                Slog.w(TAG, "Invalid escrow token supplied");
                return false;
            }
            if (result.gkResponse.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
                // Most likely, an untrusted credential reset happened in the past which
                // changed the synthetic password
                Slog.e(TAG, "Obsolete token: synthetic password derived but it fails GK "
                        + "verification.");
                return false;
            }
            // Update PASSWORD_TYPE_KEY since it's needed by notifyActivePasswordMetricsAvailable()
            // called by setLockCredentialWithAuthTokenLocked().
            // TODO: refactor usage of PASSWORD_TYPE_KEY b/65239740
            setLong(LockPatternUtils.PASSWORD_TYPE_KEY, requestedQuality, userId);
            long oldHandle = getSyntheticPasswordHandleLocked(userId);
            setLockCredentialWithAuthTokenLocked(credential, type, result.authToken,
                    requestedQuality, userId);
            mSpManager.destroyPasswordBasedSyntheticPassword(oldHandle, userId);
        }
        onAuthTokenKnownForUser(userId, result.authToken);
        return true;
    }

    private boolean unlockUserWithToken(long tokenHandle, byte[] token, int userId)
            throws RemoteException {
        AuthenticationResult authResult;
        synchronized (mSpManager) {
            if (!mSpManager.hasEscrowData(userId)) {
                throw new SecurityException("Escrow token is disabled on the current user");
            }
            authResult = mSpManager.unwrapTokenBasedSyntheticPassword(getGateKeeperService(),
                    tokenHandle, token, userId);
            if (authResult.authToken == null) {
                Slog.w(TAG, "Invalid escrow token supplied");
                return false;
            }
        }
        unlockUser(userId, null, authResult.authToken.deriveDiskEncryptionKey());
        onAuthTokenKnownForUser(userId, authResult.authToken);
        return true;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args){
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        pw.println("Current lock settings service state:");
        pw.println(String.format("SP Enabled = %b",
                mLockPatternUtils.isSyntheticPasswordEnabled()));

        List<UserInfo> users = mUserManager.getUsers();
        for (int user = 0; user < users.size(); user++) {
            final int userId = users.get(user).id;
            pw.println("    User " + userId);
            synchronized (mSpManager) {
                pw.println(String.format("        SP Handle = %x",
                        getSyntheticPasswordHandleLocked(userId)));
            }
            try {
                pw.println(String.format("        SID = %x",
                        getGateKeeperService().getSecureUserId(userId)));
            } catch (RemoteException e) {
                // ignore.
            }
        }
    }

    private void disableEscrowTokenOnNonManagedDevicesIfNeeded(int userId) {
        long ident = Binder.clearCallingIdentity();
        try {
            // Managed profile should have escrow enabled
            if (mUserManager.getUserInfo(userId).isManagedProfile()) {
                Slog.i(TAG, "Managed profile can have escrow token");
                return;
            }
            DevicePolicyManager dpm = mInjector.getDevicePolicyManager();
            // Devices with Device Owner should have escrow enabled on all users.
            if (dpm.getDeviceOwnerComponentOnAnyUser() != null) {
                Slog.i(TAG, "Corp-owned device can have escrow token");
                return;
            }
            // We could also have a profile owner on the given (non-managed) user for unicorn cases
            if (dpm.getProfileOwnerAsUser(userId) != null) {
                Slog.i(TAG, "User with profile owner can have escrow token");
                return;
            }
            // If the device is yet to be provisioned (still in SUW), there is still
            // a chance that Device Owner will be set on the device later, so postpone
            // disabling escrow token for now.
            if (!dpm.isDeviceProvisioned()) {
                Slog.i(TAG, "Postpone disabling escrow tokens until device is provisioned");
                return;
            }

            // Escrow tokens are enabled on automotive builds.
            if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                return;
            }

            // Disable escrow token permanently on all other device/user types.
            Slog.i(TAG, "Disabling escrow token on user " + userId);
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                mSpManager.destroyEscrowData(userId);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private class DeviceProvisionedObserver extends ContentObserver {
        private final Uri mDeviceProvisionedUri = Settings.Global.getUriFor(
                Settings.Global.DEVICE_PROVISIONED);
        private final Uri mUserSetupCompleteUri = Settings.Secure.getUriFor(
                Settings.Secure.USER_SETUP_COMPLETE);

        private boolean mRegistered;

        public DeviceProvisionedObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, @UserIdInt int userId) {
            if (mDeviceProvisionedUri.equals(uri)) {
                updateRegistration();

                if (isProvisioned()) {
                    Slog.i(TAG, "Reporting device setup complete to IGateKeeperService");
                    reportDeviceSetupComplete();
                    clearFrpCredentialIfOwnerNotSecure();
                }
            } else if (mUserSetupCompleteUri.equals(uri)) {
                tryRemoveUserFromSpCacheLater(userId);
            }
        }

        public void onSystemReady() {
            if (frpCredentialEnabled(mContext)) {
                updateRegistration();
            } else {
                // If we don't intend to use frpCredentials and we're not provisioned yet, send
                // deviceSetupComplete immediately, so gatekeeper can discard any lingering
                // credentials immediately.
                if (!isProvisioned()) {
                    Slog.i(TAG, "FRP credential disabled, reporting device setup complete "
                            + "to Gatekeeper immediately");
                    reportDeviceSetupComplete();
                }
            }
        }

        private void reportDeviceSetupComplete() {
            try {
                getGateKeeperService().reportDeviceSetupComplete();
            } catch (RemoteException e) {
                Slog.e(TAG, "Failure reporting to IGateKeeperService", e);
            }
        }

        /**
         * Clears the FRP credential if the user that controls it does not have a secure
         * lockscreen.
         */
        private void clearFrpCredentialIfOwnerNotSecure() {
            List<UserInfo> users = mUserManager.getUsers();
            for (UserInfo user : users) {
                if (userOwnsFrpCredential(mContext, user)) {
                    if (!isUserSecure(user.id)) {
                        mStorage.writePersistentDataBlock(PersistentData.TYPE_NONE, user.id,
                                0, null);
                    }
                    return;
                }
            }
        }

        private void updateRegistration() {
            boolean register = !isProvisioned();
            if (register == mRegistered) {
                return;
            }
            if (register) {
                mContext.getContentResolver().registerContentObserver(mDeviceProvisionedUri,
                        false, this);
                mContext.getContentResolver().registerContentObserver(mUserSetupCompleteUri,
                        false, this, UserHandle.USER_ALL);
            } else {
                mContext.getContentResolver().unregisterContentObserver(this);
            }
            mRegistered = register;
        }

        private boolean isProvisioned() {
            return Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0) != 0;
        }
    }

    private final class LocalService extends LockSettingsInternal {

        @Override
        public long addEscrowToken(byte[] token, int userId) {
            try {
                return LockSettingsService.this.addEscrowToken(token, userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        @Override
        public boolean removeEscrowToken(long handle, int userId) {
            return LockSettingsService.this.removeEscrowToken(handle, userId);
        }

        @Override
        public boolean isEscrowTokenActive(long handle, int userId) {
            return LockSettingsService.this.isEscrowTokenActive(handle, userId);
        }

        @Override
        public boolean setLockCredentialWithToken(byte[] credential, int type,
                long tokenHandle, byte[] token, int requestedQuality, int userId) {
            if (!mLockPatternUtils.hasSecureLockScreen()) {
                throw new UnsupportedOperationException(
                        "This operation requires secure lock screen feature.");
            }
            try {
                return LockSettingsService.this.setLockCredentialWithToken(credential, type,
                        tokenHandle, token, requestedQuality, userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }

        @Override
        public boolean unlockUserWithToken(long tokenHandle, byte[] token, int userId) {
            try {
                return LockSettingsService.this.unlockUserWithToken(tokenHandle, token, userId);
            } catch (RemoteException re) {
                throw re.rethrowFromSystemServer();
            }
        }
    }
}
