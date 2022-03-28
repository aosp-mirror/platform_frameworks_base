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
import static android.Manifest.permission.MANAGE_BIOMETRIC;
import static android.Manifest.permission.READ_CONTACTS;
import static android.Manifest.permission.SET_AND_VERIFY_LOCKSCREEN_CREDENTIALS;
import static android.Manifest.permission.SET_INITIAL_LOCK;
import static android.app.admin.DevicePolicyResources.Strings.Core.PROFILE_ENCRYPTED_DETAIL;
import static android.app.admin.DevicePolicyResources.Strings.Core.PROFILE_ENCRYPTED_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.PROFILE_ENCRYPTED_TITLE;
import static android.content.Context.KEYGUARD_SERVICE;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.UserHandle.USER_ALL;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD_OR_PIN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PATTERN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;
import static com.android.internal.widget.LockPatternUtils.EscrowTokenStateChangeCallback;
import static com.android.internal.widget.LockPatternUtils.PROFILE_KEY_NAME_DECRYPT;
import static com.android.internal.widget.LockPatternUtils.PROFILE_KEY_NAME_ENCRYPT;
import static com.android.internal.widget.LockPatternUtils.SYNTHETIC_PASSWORD_HANDLE_KEY;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE;
import static com.android.internal.widget.LockPatternUtils.USER_FRP;
import static com.android.internal.widget.LockPatternUtils.VERIFY_FLAG_REQUEST_GK_PW_HANDLE;
import static com.android.internal.widget.LockPatternUtils.frpCredentialEnabled;
import static com.android.internal.widget.LockPatternUtils.userOwnsFrpCredential;
import static com.android.server.locksettings.SyntheticPasswordManager.TOKEN_TYPE_STRONG;
import static com.android.server.locksettings.SyntheticPasswordManager.TOKEN_TYPE_WEAK;

import android.Manifest;
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
import android.app.admin.DeviceStateCache;
import android.app.admin.PasswordMetrics;
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
import android.hardware.face.Face;
import android.hardware.face.FaceManager;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.FingerprintManager;
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
import android.security.AndroidKeyStoreMaintenance;
import android.security.Authorization;
import android.security.KeyStore;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.security.keystore.UserNotAuthenticatedException;
import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.RecoveryCertPath;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.security.keystore2.AndroidKeyStoreLoadStoreParameter;
import android.security.keystore2.AndroidKeyStoreProvider;
import android.service.gatekeeper.GateKeeperResponse;
import android.service.gatekeeper.IGateKeeperService;
import android.system.keystore2.Domain;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.ICheckCredentialProgressCallback;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.IWeakEscrowTokenActivatedListener;
import com.android.internal.widget.IWeakEscrowTokenRemovedListener;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsInternal;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.RebootEscrowListener;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.locksettings.LockSettingsStorage.CredentialHash;
import com.android.server.locksettings.LockSettingsStorage.PersistentData;
import com.android.server.locksettings.SyntheticPasswordManager.AuthenticationResult;
import com.android.server.locksettings.SyntheticPasswordManager.AuthenticationToken;
import com.android.server.locksettings.SyntheticPasswordManager.TokenType;
import com.android.server.locksettings.recoverablekeystore.RecoverableKeyStoreManager;
import com.android.server.pm.UserManagerInternal;
import com.android.server.utils.Watchable;
import com.android.server.utils.Watcher;
import com.android.server.wm.WindowManagerInternal;

import libcore.util.HexEncoding;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
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
    private static final String BIOMETRIC_PERMISSION = MANAGE_BIOMETRIC;
    private static final boolean DEBUG = false;

    private static final int PROFILE_KEY_IV_SIZE = 12;
    private static final String SEPARATE_PROFILE_CHALLENGE_KEY = "lockscreen.profilechallenge";
    private static final String PREV_SYNTHETIC_PASSWORD_HANDLE_KEY = "prev-sp-handle";
    private static final String SYNTHETIC_PASSWORD_UPDATE_TIME_KEY = "sp-handle-ts";
    private static final String USER_SERIAL_NUMBER_KEY = "serial-number";

    // Duration that LockSettingsService will store the gatekeeper password for. This allows
    // multiple biometric enrollments without prompting the user to enter their password via
    // ConfirmLockPassword/ConfirmLockPattern multiple times. This needs to be at least the duration
    // from the start of the first biometric sensor's enrollment to the start of the last biometric
    // sensor's enrollment. If biometric enrollment requests a password handle that has expired, the
    // user's credential must be presented again, e.g. via ConfirmLockPattern/ConfirmLockPassword.
    private static final int GK_PW_HANDLE_STORE_DURATION_MS = 10 * 60 * 1000; // 10 minutes

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
    private final Watcher mStorageWatcher;
    private final LockSettingsStrongAuth mStrongAuth;
    private final SynchronizedStrongAuthTracker mStrongAuthTracker;
    private final BiometricDeferredQueue mBiometricDeferredQueue;
    private final LongSparseArray<byte[]> mGatekeeperPasswords;
    private final Random mRandom;

    private final NotificationManager mNotificationManager;
    protected final UserManager mUserManager;
    private final IStorageManager mStorageManager;
    private final IActivityManager mActivityManager;
    private final SyntheticPasswordManager mSpManager;

    private final KeyStore mKeyStore;
    private final java.security.KeyStore mJavaKeyStore;
    private final RecoverableKeyStoreManager mRecoverableKeyStoreManager;
    private ManagedProfilePasswordCache mManagedProfilePasswordCache;

    private final RebootEscrowManager mRebootEscrowManager;

    // Current password metric for all users on the device. Updated when user unlocks
    // the device or changes password. Removed when user is stopped.
    @GuardedBy("this")
    final SparseArray<PasswordMetrics> mUserPasswordMetrics = new SparseArray<>();
    @VisibleForTesting
    protected boolean mHasSecureLockScreen;

    protected IGateKeeperService mGateKeeperService;
    protected IAuthSecret mAuthSecretService;

    private static final String GSI_RUNNING_PROP = "ro.gsid.image_running";

    /**
     * The UIDs that are used for system credential storage in keystore.
     */
    private static final int[] SYSTEM_CREDENTIAL_UIDS = {
            Process.VPN_UID, Process.ROOT_UID, Process.SYSTEM_UID};

    private HashMap<UserHandle, UserManager> mUserManagerCache = new HashMap<>();

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
                mLockSettingsService.loadEscrowData();
            }
        }

        @Override
        public void onUserStarting(@NonNull TargetUser user) {
            mLockSettingsService.onStartUser(user.getUserIdentifier());
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            mLockSettingsService.onUnlockUser(user.getUserIdentifier());
        }

        @Override
        public void onUserStopped(@NonNull TargetUser user) {
            mLockSettingsService.onCleanupUser(user.getUserIdentifier());
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
            strongAuth.registerStrongAuthTracker(getStub());
        }
    }

    private LockscreenCredential generateRandomProfilePassword() {
        byte[] randomLockSeed = new byte[] {};
        try {
            randomLockSeed = SecureRandom.getInstance("SHA1PRNG").generateSeed(40);
            char[] newPasswordChars = HexEncoding.encode(randomLockSeed);
            byte[] newPassword = new byte[newPasswordChars.length];
            for (int i = 0; i < newPasswordChars.length; i++) {
                newPassword[i] = (byte) newPasswordChars[i];
            }
            LockscreenCredential credential =
                    LockscreenCredential.createManagedPassword(newPassword);
            Arrays.fill(newPasswordChars, '\u0000');
            Arrays.fill(newPassword, (byte) 0);
            Arrays.fill(randomLockSeed, (byte) 0);
            return credential;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Fail to generate profile password", e);
        }
    }

    /**
     * Tie profile to primary profile if it is in unified mode and not tied before.
     * Only for profiles which share credential with parent. (e.g. managed and clone profiles)
     *
     * @param profileUserId  profile user Id
     * @param profileUserPassword  profile original password (when it has separated lock).
     */
    public void tieProfileLockIfNecessary(int profileUserId,
            LockscreenCredential profileUserPassword) {
        if (DEBUG) Slog.v(TAG, "Check child profile lock for user: " + profileUserId);
        // Only for profiles that shares credential with parent
        if (!isCredentialSharableWithParent(profileUserId)) {
            return;
        }
        // Do not tie profile when work challenge is enabled
        if (getSeparateProfileChallengeEnabledInternal(profileUserId)) {
            return;
        }
        // Do not tie profile to parent when it's done already
        if (mStorage.hasChildProfileLock(profileUserId)) {
            return;
        }
        // If parent does not have a screen lock, simply clear credential from the profile,
        // to maintain the invariant that unified profile should always have the same secure state
        // as its parent.
        final int parentId = mUserManager.getProfileParent(profileUserId).id;
        if (!isUserSecure(parentId) && !profileUserPassword.isNone()) {
            if (DEBUG) Slog.v(TAG, "Parent does not have a screen lock but profile has one");

            setLockCredentialInternal(LockscreenCredential.createNone(), profileUserPassword,
                    profileUserId, /* isLockTiedToParent= */ true);
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
        if (DEBUG) Slog.v(TAG, "Tie profile to parent now!");
        try (LockscreenCredential unifiedProfilePassword = generateRandomProfilePassword()) {
            setLockCredentialInternal(unifiedProfilePassword, profileUserPassword, profileUserId,
                    /* isLockTiedToParent= */ true);
            tieProfileLockToParent(profileUserId, unifiedProfilePassword);
            mManagedProfilePasswordCache.storePassword(profileUserId, unifiedProfilePassword);
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

        public ServiceThread getServiceThread() {
            ServiceThread handlerThread = new ServiceThread(TAG, Process.THREAD_PRIORITY_BACKGROUND,
                    true /*allowIo*/);
            handlerThread.start();
            return handlerThread;
        }

        public Handler getHandler(ServiceThread handlerThread) {
            return new Handler(handlerThread.getLooper());
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

        public NotificationManager getNotificationManager() {
            return (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
        }

        public UserManager getUserManager() {
            return (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        }

        public UserManagerInternal getUserManagerInternal() {
            return LocalServices.getService(UserManagerInternal.class);
        }

        /**
         * Return the {@link DevicePolicyManager} object.
         *
         * Since LockSettingsService is considered a lower-level component than DevicePolicyManager,
         * do NOT hold any lock in this class while calling into DevicePolicyManager to prevent
         * the risk of deadlock.
         */
        public DevicePolicyManager getDevicePolicyManager() {
            return (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        }

        public DeviceStateCache getDeviceStateCache() {
            return DeviceStateCache.getInstance();
        }

        public KeyStore getKeyStore() {
            return KeyStore.getInstance();
        }

        public RecoverableKeyStoreManager getRecoverableKeyStoreManager() {
            return RecoverableKeyStoreManager.getInstance(mContext);
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

        public RebootEscrowManager getRebootEscrowManager(RebootEscrowManager.Callbacks callbacks,
                LockSettingsStorage storage) {
            return new RebootEscrowManager(mContext, callbacks, storage);
        }

        public boolean hasEnrolledBiometrics(int userId) {
            BiometricManager bm = mContext.getSystemService(BiometricManager.class);
            return bm.hasEnrolledBiometrics(userId);
        }

        public int binderGetCallingUid() {
            return Binder.getCallingUid();
        }

        public boolean isGsiRunning() {
            return SystemProperties.getInt(GSI_RUNNING_PROP, 0) > 0;
        }

        public FingerprintManager getFingerprintManager() {
            if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) {
                return (FingerprintManager) mContext.getSystemService(Context.FINGERPRINT_SERVICE);
            } else {
                return null;
            }
        }

        public FaceManager getFaceManager() {
            if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FACE)) {
                return (FaceManager) mContext.getSystemService(Context.FACE_SERVICE);
            } else {
                return null;
            }
        }

        public int settingsGlobalGetInt(ContentResolver contentResolver, String keyName,
                int defaultValue) {
            return Settings.Global.getInt(contentResolver, keyName, defaultValue);
        }

        public int settingsSecureGetInt(ContentResolver contentResolver, String keyName,
                int defaultValue, int userId) {
            return Settings.Secure.getIntForUser(contentResolver, keyName, defaultValue, userId);
        }

        public java.security.KeyStore getJavaKeyStore() {
            try {
                java.security.KeyStore ks = java.security.KeyStore.getInstance(
                        SyntheticPasswordCrypto.androidKeystoreProviderName());
                ks.load(new AndroidKeyStoreLoadStoreParameter(
                        SyntheticPasswordCrypto.keyNamespace()));
                return ks;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot load keystore", e);
            }
        }

        public @NonNull ManagedProfilePasswordCache getManagedProfilePasswordCache(
                java.security.KeyStore ks) {
            return new ManagedProfilePasswordCache(ks, getUserManager());
        }
    }

    private class StorageWatcher extends Watcher {
        public void onChange(Watchable what) {
            LockSettingsService.this.onChange();
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
        mJavaKeyStore = injector.getJavaKeyStore();
        mRecoverableKeyStoreManager = injector.getRecoverableKeyStoreManager();
        mHandler = injector.getHandler(injector.getServiceThread());
        mStrongAuth = injector.getStrongAuth();
        mActivityManager = injector.getActivityManager();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_STARTING);
        filter.addAction(Intent.ACTION_USER_REMOVED);
        injector.getContext().registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter,
                null, null);

        mStorage = injector.getStorage();
        mNotificationManager = injector.getNotificationManager();
        mUserManager = injector.getUserManager();
        mStorageManager = injector.getStorageManager();
        mStrongAuthTracker = injector.getStrongAuthTracker();
        mStrongAuthTracker.register(mStrongAuth);
        mGatekeeperPasswords = new LongSparseArray<>();
        mRandom = new SecureRandom();

        mSpManager = injector.getSyntheticPasswordManager(mStorage);
        mManagedProfilePasswordCache = injector.getManagedProfilePasswordCache(mJavaKeyStore);
        mBiometricDeferredQueue = new BiometricDeferredQueue(mContext, mSpManager, mHandler);

        mRebootEscrowManager = injector.getRebootEscrowManager(new RebootEscrowCallbacks(),
                mStorage);

        LocalServices.addService(LockSettingsInternal.class, new LocalService());

        mStorageWatcher = new StorageWatcher();
        mStorage.registerObserver(mStorageWatcher);
    }

    /**
     * Invalidate caches if the storage has changed.
     */
    private void onChange() {
        LockPatternUtils.invalidateCredentialTypeCache();
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

        if (isUserKeyUnlocked(userId)) {
            // If storage is not locked, the user will be automatically unlocked so there is
            // no need to show the notification.
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
        CharSequence title = getEncryptionNotificationTitle();
        CharSequence message = getEncryptionNotificationMessage();
        CharSequence detail = getEncryptionNotificationDetail();

        final KeyguardManager km = (KeyguardManager) mContext.getSystemService(KEYGUARD_SERVICE);
        final Intent unlockIntent = km.createConfirmDeviceCredentialIntent(null, null,
                user.getIdentifier());
        if (unlockIntent == null) {
            return;
        }
        unlockIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        PendingIntent intent = PendingIntent.getActivity(mContext, 0, unlockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED);

        showEncryptionNotification(user, title, message, detail, intent);
    }

    private String getEncryptionNotificationTitle() {
        return mInjector.getDevicePolicyManager().getResources().getString(
                PROFILE_ENCRYPTED_TITLE,
                () -> mContext.getString(R.string.profile_encrypted_title));
    }

    private String getEncryptionNotificationDetail() {
        return mInjector.getDevicePolicyManager().getResources().getString(
                PROFILE_ENCRYPTED_DETAIL,
                () -> mContext.getString(R.string.profile_encrypted_detail));
    }

    private String getEncryptionNotificationMessage() {
        return mInjector.getDevicePolicyManager().getResources().getString(
                PROFILE_ENCRYPTED_MESSAGE,
                () -> mContext.getString(R.string.profile_encrypted_message));
    }

    private void showEncryptionNotification(UserHandle user, CharSequence title,
            CharSequence message, CharSequence detail, PendingIntent intent) {
        if (DEBUG) Slog.v(TAG, "showing encryption notification, user: " + user.getIdentifier());

        // Suppress all notifications on non-FBE devices for now
        if (!StorageManager.isFileEncryptedNativeOrEmulated()) return;

        Notification notification =
                new Notification.Builder(mContext, SystemNotificationChannels.DEVICE_ADMIN)
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
        // User is stopped with its CE key evicted. Restore strong auth requirement to the default
        // flags after boot since stopping and restarting a user later is equivalent to rebooting
        // the device.
        int strongAuthRequired = LockPatternUtils.StrongAuthTracker.getDefaultFlags(mContext);
        requireStrongAuth(strongAuthRequired, userId);
        synchronized (this) {
            mUserPasswordMetrics.remove(userId);
        }
    }

    public void onStartUser(final int userId) {
        maybeShowEncryptionNotificationForUser(userId);
    }

    /**
     * Clean up states associated with the given user, in case the userId is reused but LSS didn't
     * get a chance to do cleanup previously during ACTION_USER_REMOVED.
     *
     * Internally, LSS stores serial number for each user and check it against the current user's
     * serial number to determine if the userId is reused and invoke cleanup code.
     */
    private void cleanupDataForReusedUserIdIfNecessary(int userId) {
        if (userId == UserHandle.USER_SYSTEM) {
            // Short circuit as we never clean up user 0.
            return;
        }
        // Serial number is never reusued, so we can use it as a distinguisher for user Id reuse.
        int serialNumber = mUserManager.getUserSerialNumber(userId);

        int storedSerialNumber = mStorage.getInt(USER_SERIAL_NUMBER_KEY, -1, userId);
        if (storedSerialNumber != serialNumber) {
            // If LockSettingsStorage does not have a copy of the serial number, it could be either
            // this is a user created before the serial number recording logic is introduced, or
            // the user does not exist or was removed and cleaned up properly. In either case, don't
            // invoke removeUser().
            if (storedSerialNumber != -1) {
                removeUser(userId, /* unknownUser */ true);
            }
            mStorage.setInt(USER_SERIAL_NUMBER_KEY, serialNumber, userId);
        }
    }

    /**
     * Check if profile got unlocked but the keystore is still locked. This happens on full disk
     * encryption devices since the profile may not yet be running when we consider unlocking it
     * during the normal flow. In this case unlock the keystore for the profile.
     */
    private void ensureProfileKeystoreUnlocked(int userId) {
        final KeyStore ks = KeyStore.getInstance();
        if (ks.state(userId) == KeyStore.State.LOCKED
                && isCredentialSharableWithParent(userId)
                && hasUnifiedChallenge(userId)) {
            Slog.i(TAG, "Profile got unlocked, will unlock its keystore");
            // If boot took too long and the password in vold got expired, parent keystore will
            // be still locked, we ignore this case since the user will be prompted to unlock
            // the device after boot.
            unlockChildProfile(userId, true /* ignoreUserNotAuthenticated */);
        }
    }

    public void onUnlockUser(final int userId) {
        // Perform tasks which require locks in LSS on a handler, as we are callbacks from
        // ActivityManager.unlockUser()
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                cleanupDataForReusedUserIdIfNecessary(userId);
                ensureProfileKeystoreUnlocked(userId);
                // Hide notification first, as tie managed profile lock takes time
                hideEncryptionNotification(new UserHandle(userId));

                if (isCredentialSharableWithParent(userId)) {
                    tieProfileLockIfNecessary(userId, LockscreenCredential.createNone());
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

            final long handle = getSyntheticPasswordHandleLocked(userId);
            AuthenticationResult result =
                    mSpManager.unwrapPasswordBasedSyntheticPassword(getGateKeeperService(),
                            handle, LockscreenCredential.createNone(), userId, null);
            if (result.authToken != null) {
                Slog.i(TAG, "Retrieved auth token for user " + userId);
                onAuthTokenKnownForUser(userId, result.authToken);
            } else {
                Slog.e(TAG, "Auth token not available for user " + userId);
            }
        }
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_ADDED.equals(intent.getAction())) {
                // Notify keystore that a new user was added.
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                AndroidKeyStoreMaintenance.onUserAdded(userHandle);
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

        mHasSecureLockScreen = mContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN);
        migrateOldData();
        getGateKeeperService();
        mSpManager.initWeaverService();
        getAuthSecretHal();
        mDeviceProvisionedObserver.onSystemReady();

        // TODO: maybe skip this for split system user mode.
        mStorage.prefetchUser(UserHandle.USER_SYSTEM);
        mBiometricDeferredQueue.systemReady(mInjector.getFingerprintManager(),
                mInjector.getFaceManager());
    }

    private void loadEscrowData() {
        mRebootEscrowManager.loadRebootEscrowDataIfAvailable(mHandler);
    }

    private void getAuthSecretHal() {
        try {
            mAuthSecretService = IAuthSecret.getService(/* retry */ true);
        } catch (NoSuchElementException e) {
            Slog.i(TAG, "Device doesn't implement AuthSecret HAL");
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to get AuthSecret HAL", e);
        }
    }

    private void migrateOldData() {
        // These Settings moved before multi-user was enabled, so we only have to do it for the
        // root user.
        if (getString("migrated", null, 0) == null) {
            final ContentResolver cr = mContext.getContentResolver();
            for (String validSetting : VALID_SETTINGS) {
                String value = Settings.Secure.getStringForUser(cr, validSetting, cr.getUserId());
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

        boolean isWatch = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_WATCH);
        // Wear used to set DISABLE_LOCKSCREEN to 'true', but because Wear now allows accounts
        // and device management the lockscreen must be re-enabled now for users that upgrade.
        if (isWatch && getString("migrated_wear_lockscreen_disabled", null, 0) == null) {
            final List<UserInfo> users = mUserManager.getUsers();
            final int userCount = users.size();
            for (int i = 0; i < userCount; i++) {
                int id = users.get(i).id;
                setBoolean(LockPatternUtils.DISABLE_LOCKSCREEN_KEY, false, id);
            }
            setString("migrated_wear_lockscreen_disabled", "true", 0);
            Slog.i(TAG, "Migrated lockscreen_disabled for Wear devices");
        }

        if (getString("migrated_keystore_namespace", null, 0) == null) {
            boolean success = true;
            synchronized (mSpManager) {
                success &= mSpManager.migrateKeyNamespace();
            }
            success &= migrateProfileLockKeys();
            if (success) {
                setString("migrated_keystore_namespace", "true", 0);
                Slog.i(TAG, "Migrated keys to LSS namespace");
            } else {
                Slog.w(TAG, "Failed to migrate keys to LSS namespace");
            }
        }

    }

    private void migrateOldDataAfterSystemReady() {
        // Migrate the FRP credential to the persistent data block
        if (LockPatternUtils.frpCredentialEnabled(mContext)
                && !getBoolean("migrated_frp", false, 0)) {
            migrateFrpCredential();
            setBoolean("migrated_frp", true, 0);
            Slog.i(TAG, "Migrated migrated_frp.");
        }
    }

    /**
     * Migrate the credential for the FRP credential owner user if the following are satisfied:
     * - the user has a secure credential
     * - the FRP credential is not set up
     * - the credential is based on a synthetic password.
     */
    private void migrateFrpCredential() {
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

    private boolean migrateProfileLockKeys() {
        boolean success = true;
        final List<UserInfo> users = mUserManager.getUsers();
        final int userCount = users.size();
        for (int i = 0; i < userCount; i++) {
            UserInfo user = users.get(i);
            if (isCredentialSharableWithParent(user.id)
                    && !getSeparateProfileChallengeEnabledInternal(user.id)) {
                success &= SyntheticPasswordCrypto.migrateLockSettingsKey(
                        PROFILE_KEY_NAME_ENCRYPT + user.id);
                success &= SyntheticPasswordCrypto.migrateLockSettingsKey(
                        PROFILE_KEY_NAME_DECRYPT + user.id);
            }
        }
        return success;
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

    private void enforceFrpResolved() {
        final ContentResolver cr = mContext.getContentResolver();
        final boolean inSetupWizard = mInjector.settingsSecureGetInt(cr,
                Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_SYSTEM) == 0;
        final boolean secureFrp = mInjector.settingsSecureGetInt(cr,
                Settings.Secure.SECURE_FRP_MODE, 0, UserHandle.USER_SYSTEM) == 1;
        if (inSetupWizard && secureFrp) {
            throw new SecurityException("Cannot change credential in SUW while factory reset"
                    + " protection is not resolved yet");
        }
    }

    private final void checkWritePermission(int userId) {
        mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsWrite");
    }

    private final void checkPasswordReadPermission() {
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

    private final void checkBiometricPermission() {
        mContext.enforceCallingOrSelfPermission(BIOMETRIC_PERMISSION, "LockSettingsBiometric");
    }

    private boolean hasPermission(String permission) {
        return mContext.checkCallingOrSelfPermission(permission) == PERMISSION_GRANTED;
    }

    private void checkManageWeakEscrowTokenMethodUsage() {
        mContext.enforceCallingOrSelfPermission(
                Manifest.permission.MANAGE_WEAK_ESCROW_TOKEN,
                "Requires MANAGE_WEAK_ESCROW_TOKEN permission.");
        if (!mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            throw new IllegalArgumentException(
                    "Weak escrow token are only for automotive devices.");
        }
    }

    @Override
    public boolean hasSecureLockScreen() {
        return mHasSecureLockScreen;
    }

    @Override
    public boolean getSeparateProfileChallengeEnabled(int userId) {
        checkReadPermission(SEPARATE_PROFILE_CHALLENGE_KEY, userId);
        return getSeparateProfileChallengeEnabledInternal(userId);
    }

    private boolean getSeparateProfileChallengeEnabledInternal(int userId) {
        synchronized (mSeparateChallengeLock) {
            return mStorage.getBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, false, userId);
        }
    }

    @Override
    public void setSeparateProfileChallengeEnabled(int userId, boolean enabled,
            LockscreenCredential profileUserPassword) {
        checkWritePermission(userId);
        if (!mHasSecureLockScreen
                && profileUserPassword != null
                && profileUserPassword.getType() != CREDENTIAL_TYPE_NONE) {
            throw new UnsupportedOperationException(
                    "This operation requires secure lock screen feature.");
        }
        synchronized (mSeparateChallengeLock) {
            setSeparateProfileChallengeEnabledLocked(userId, enabled, profileUserPassword != null
                    ? profileUserPassword : LockscreenCredential.createNone());
        }
        notifySeparateProfileChallengeChanged(userId);
    }

    @GuardedBy("mSeparateChallengeLock")
    private void setSeparateProfileChallengeEnabledLocked(@UserIdInt int userId,
            boolean enabled, LockscreenCredential profileUserPassword) {
        final boolean old = getBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, false, userId);
        setBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, enabled, userId);
        try {
            if (enabled) {
                mStorage.removeChildProfileLock(userId);
                removeKeystoreProfileKey(userId);
            } else {
                tieProfileLockIfNecessary(userId, profileUserPassword);
            }
        } catch (IllegalStateException e) {
            setBoolean(SEPARATE_PROFILE_CHALLENGE_KEY, old, userId);
            throw e;
        }
    }

    private void notifySeparateProfileChallengeChanged(int userId) {
        // LSS cannot call into DPM directly, otherwise it will cause deadlock.
        // In this case, calling DPM on a handler thread is OK since DPM doesn't
        // expect reportSeparateProfileChallengeChanged() to happen synchronously.
        mHandler.post(() -> {
            final DevicePolicyManagerInternal dpmi = LocalServices.getService(
                    DevicePolicyManagerInternal.class);
            if (dpmi != null) {
                dpmi.reportSeparateProfileChallengeChanged(userId);
            }
        });
    }

    @Override
    public void setBoolean(String key, boolean value, int userId) {
        checkWritePermission(userId);
        mStorage.setBoolean(key, value, userId);
    }

    @Override
    public void setLong(String key, long value, int userId) {
        checkWritePermission(userId);
        mStorage.setLong(key, value, userId);
    }

    @Override
    public void setString(String key, String value, int userId) {
        checkWritePermission(userId);
        mStorage.setString(key, value, userId);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue, int userId) {
        checkReadPermission(key, userId);
        if (Settings.Secure.LOCK_PATTERN_ENABLED.equals(key)) {
            return getCredentialTypeInternal(userId) == CREDENTIAL_TYPE_PATTERN;
        }
        return mStorage.getBoolean(key, defaultValue, userId);
    }

    @Override
    public long getLong(String key, long defaultValue, int userId) {
        checkReadPermission(key, userId);
        return mStorage.getLong(key, defaultValue, userId);
    }

    @Override
    public String getString(String key, String defaultValue, int userId) {
        checkReadPermission(key, userId);
        return mStorage.getString(key, defaultValue, userId);
    }

    private void setKeyguardStoredQuality(int quality, int userId) {
        if (DEBUG) Slog.d(TAG, "setKeyguardStoredQuality: user=" + userId + " quality=" + quality);
        mStorage.setLong(LockPatternUtils.PASSWORD_TYPE_KEY, quality, userId);
    }

    private int getKeyguardStoredQuality(int userId) {
        return (int) mStorage.getLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userId);
    }

    /**
     * This API is cached; whenever the result would change,
     * {@link com.android.internal.widget.LockPatternUtils#invalidateCredentialTypeCache}
     * must be called.
     */
    @Override
    public int getCredentialType(int userId) {
        checkPasswordHavePermission(userId);
        return getCredentialTypeInternal(userId);
    }

    // TODO: this is a hot path, can we optimize it?
    /**
     * Returns the credential type of the user, can be one of {@link #CREDENTIAL_TYPE_NONE},
     * {@link #CREDENTIAL_TYPE_PATTERN}, {@link #CREDENTIAL_TYPE_PIN} and
     * {@link #CREDENTIAL_TYPE_PASSWORD}
     */
    public int getCredentialTypeInternal(int userId) {
        if (userId == USER_FRP) {
            return getFrpCredentialType();
        }
        synchronized (mSpManager) {
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                final long handle = getSyntheticPasswordHandleLocked(userId);
                int rawType = mSpManager.getCredentialType(handle, userId);
                if (rawType != CREDENTIAL_TYPE_PASSWORD_OR_PIN) {
                    return rawType;
                }
                return pinOrPasswordQualityToCredentialType(getKeyguardStoredQuality(userId));
            }
        }
        // Intentional duplication of the getKeyguardStoredQuality() call above since this is a
        // unlikely code path (device with pre-synthetic password credential). We want to skip
        // calling getKeyguardStoredQuality whenever possible.
        final int savedQuality = getKeyguardStoredQuality(userId);
        if (savedQuality == DevicePolicyManager.PASSWORD_QUALITY_SOMETHING
                && mStorage.hasPattern(userId)) {
            return CREDENTIAL_TYPE_PATTERN;
        }
        if (savedQuality >= DevicePolicyManager.PASSWORD_QUALITY_NUMERIC
                && mStorage.hasPassword(userId)) {
            return pinOrPasswordQualityToCredentialType(savedQuality);
        }
        return CREDENTIAL_TYPE_NONE;
    }

    private int getFrpCredentialType() {
        PersistentData data = mStorage.readPersistentDataBlock();
        if (data.type != PersistentData.TYPE_SP && data.type != PersistentData.TYPE_SP_WEAVER) {
            return CREDENTIAL_TYPE_NONE;
        }
        int credentialType = SyntheticPasswordManager.getFrpCredentialType(data.payload);
        if (credentialType != CREDENTIAL_TYPE_PASSWORD_OR_PIN) {
            return credentialType;
        }
        return pinOrPasswordQualityToCredentialType(data.qualityForUi);
    }

    private static int pinOrPasswordQualityToCredentialType(int quality) {
        if (LockPatternUtils.isQualityAlphabeticPassword(quality)) {
            return CREDENTIAL_TYPE_PASSWORD;
        }
        if (LockPatternUtils.isQualityNumericPin(quality)) {
            return CREDENTIAL_TYPE_PIN;
        }
        throw new IllegalArgumentException("Quality is neither Pin nor password: " + quality);
    }

    private boolean isUserSecure(int userId) {
        return getCredentialTypeInternal(userId) != CREDENTIAL_TYPE_NONE;
    }

    @VisibleForTesting /** Note: this method is overridden in unit tests */
    void setKeystorePassword(byte[] password, int userHandle) {
        AndroidKeyStoreMaintenance.onUserPasswordChanged(userHandle, password);
    }

    private void unlockKeystore(byte[] password, int userHandle) {
        if (DEBUG) Slog.v(TAG, "Unlock keystore for user: " + userHandle);
        Authorization.onLockScreenEvent(false, userHandle, password, null);
    }

    @VisibleForTesting /** Note: this method is overridden in unit tests */
    protected LockscreenCredential getDecryptedPasswordForTiedProfile(int userId)
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
        SecretKey decryptionKey = (SecretKey) mJavaKeyStore.getKey(
                PROFILE_KEY_NAME_DECRYPT + userId, null);

        Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                + KeyProperties.BLOCK_MODE_GCM + "/" + KeyProperties.ENCRYPTION_PADDING_NONE);

        cipher.init(Cipher.DECRYPT_MODE, decryptionKey, new GCMParameterSpec(128, iv));
        decryptionResult = cipher.doFinal(encryptedPassword);
        LockscreenCredential credential = LockscreenCredential.createManagedPassword(
                decryptionResult);
        Arrays.fill(decryptionResult, (byte) 0);
        mManagedProfilePasswordCache.storePassword(userId, credential);
        return credential;
    }

    private void unlockChildProfile(int profileHandle, boolean ignoreUserNotAuthenticated) {
        try {
            doVerifyCredential(getDecryptedPasswordForTiedProfile(profileHandle),
                    profileHandle, null /* progressCallback */, 0 /* flags */);
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

    /**
     * Unlock the user (both storage and user state) and its associated profiles
     * that share lock credential (e.g. managed and clone profiles) synchronously.
     *
     * <em>Be very careful about the risk of deadlock here: ActivityManager.unlockUser()
     * can end up calling into other system services to process user unlock request (via
     * {@link com.android.server.SystemServiceManager#unlockUser} </em>
     */
    private void unlockUser(int userId, byte[] secret) {
        Slog.i(TAG, "Unlocking user " + userId + " with secret only, length "
                + (secret != null ? secret.length : 0));
        // TODO: make this method fully async so we can update UI with progress strings
        final boolean alreadyUnlocked = mUserManager.isUserUnlockingOrUnlocked(userId);
        final CountDownLatch latch = new CountDownLatch(1);
        final IProgressListener listener = new IProgressListener.Stub() {
            @Override
            public void onStarted(int id, Bundle extras) throws RemoteException {
                Slog.d(TAG, "unlockUser started");
            }

            @Override
            public void onProgress(int id, int progress, Bundle extras) throws RemoteException {
                Slog.d(TAG, "unlockUser progress " + progress);
            }

            @Override
            public void onFinished(int id, Bundle extras) throws RemoteException {
                Slog.d(TAG, "unlockUser finished");
                latch.countDown();
            }
        };

        try {
            mActivityManager.unlockUser(userId, null, secret, listener);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }

        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (isCredentialSharableWithParent(userId)) {
            if (!hasUnifiedChallenge(userId)) {
                mBiometricDeferredQueue.processPendingLockoutResets();
            }
            return;
        }

        for (UserInfo profile : mUserManager.getProfiles(userId)) {
            if (profile.id == userId) continue;
            if (!isCredentialSharableWithParent(profile.id)) continue;

            if (hasUnifiedChallenge(profile.id)) {
                if (mUserManager.isUserRunning(profile.id)) {
                    // Unlock profile with unified lock
                    unlockChildProfile(profile.id, false /* ignoreUserNotAuthenticated */);
                } else {
                    try {
                        // Profile not ready for unlock yet, but decrypt the unified challenge now
                        // so it goes into the cache
                        getDecryptedPasswordForTiedProfile(profile.id);
                    } catch (GeneralSecurityException | IOException e) {
                        Slog.d(TAG, "Cache work profile password failed", e);
                    }
                }
            }
            // Now we have unlocked the parent user and attempted to unlock the profile we should
            // show notifications if the profile is still locked.
            if (!alreadyUnlocked) {
                final long ident = clearCallingIdentity();
                try {
                    maybeShowEncryptionNotificationForUser(profile.id);
                } finally {
                    restoreCallingIdentity(ident);
                }
            }
        }

        mBiometricDeferredQueue.processPendingLockoutResets();
    }

    private boolean hasUnifiedChallenge(int userId) {
        return !getSeparateProfileChallengeEnabledInternal(userId)
                && mStorage.hasChildProfileLock(userId);
    }

    private Map<Integer, LockscreenCredential> getDecryptedPasswordsForAllTiedProfiles(int userId) {
        if (isCredentialSharableWithParent(userId)) {
            return null;
        }
        Map<Integer, LockscreenCredential> result = new ArrayMap<>();
        final List<UserInfo> profiles = mUserManager.getProfiles(userId);
        final int size = profiles.size();
        for (int i = 0; i < size; i++) {
            final UserInfo profile = profiles.get(i);
            if (!isCredentialSharableWithParent(profile.id)) {
                continue;
            }
            final int profileUserId = profile.id;
            if (getSeparateProfileChallengeEnabledInternal(profileUserId)) {
                continue;
            }
            try {
                result.put(profileUserId, getDecryptedPasswordForTiedProfile(profileUserId));
            } catch (KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException
                    | NoSuchPaddingException | InvalidKeyException
                    | InvalidAlgorithmParameterException | IllegalBlockSizeException
                    | BadPaddingException | CertificateException | IOException e) {
                Slog.e(TAG, "getDecryptedPasswordsForAllTiedProfiles failed for user " +
                        profileUserId, e);
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
     * terminates when the user is a profile that shares lock credentials with parent.
     * (e.g. managed and clone profile).
     */
    private void synchronizeUnifiedWorkChallengeForProfiles(int userId,
            Map<Integer, LockscreenCredential> profilePasswordMap) {
        if (isCredentialSharableWithParent(userId)) {
            return;
        }
        final boolean isSecure = isUserSecure(userId);
        final List<UserInfo> profiles = mUserManager.getProfiles(userId);
        final int size = profiles.size();
        for (int i = 0; i < size; i++) {
            final UserInfo profile = profiles.get(i);
            final int profileUserId = profile.id;
            if (isCredentialSharableWithParent(profileUserId)) {
                if (getSeparateProfileChallengeEnabledInternal(profileUserId)) {
                    continue;
                }
                if (isSecure) {
                    tieProfileLockIfNecessary(profileUserId,
                            LockscreenCredential.createNone());
                } else {
                    // We use cached work profile password computed before clearing the parent's
                    // credential, otherwise they get lost
                    if (profilePasswordMap != null
                            && profilePasswordMap.containsKey(profileUserId)) {
                        setLockCredentialInternal(LockscreenCredential.createNone(),
                                profilePasswordMap.get(profileUserId),
                                profileUserId,
                                /* isLockTiedToParent= */ true);
                        mStorage.removeChildProfileLock(profileUserId);
                        removeKeystoreProfileKey(profileUserId);
                    } else {
                        Slog.wtf(TAG, "Attempt to clear tied challenge, but no password supplied.");
                    }
                }
            }
        }
    }

    private boolean isProfileWithUnifiedLock(int userId) {
        return isCredentialSharableWithParent(userId)
                && !getSeparateProfileChallengeEnabledInternal(userId);
    }

    private boolean isProfileWithSeparatedLock(int userId) {
        return isCredentialSharableWithParent(userId)
                && getSeparateProfileChallengeEnabledInternal(userId);
    }

    /**
     * Send credentials for user {@code userId} to {@link RecoverableKeyStoreManager} during an
     * unlock operation.
     */
    private void sendCredentialsOnUnlockIfRequired(LockscreenCredential credential, int userId) {
        // Don't send credentials during the factory reset protection flow.
        if (userId == USER_FRP) {
            return;
        }

        // A profile with a unified lock screen stores a randomly generated credential, so skip it.
        // Its parent will send credentials for the profile, as it stores the unified lock
        // credential.
        if (isProfileWithUnifiedLock(userId)) {
            return;
        }

        // RecoverableKeyStoreManager expects null for empty credential.
        final byte[] secret = credential.isNone() ? null : credential.getCredential();
        // Send credentials for the user and any child profiles that share its lock screen.
        for (int profileId : getProfilesWithSameLockScreen(userId)) {
            mRecoverableKeyStoreManager.lockScreenSecretAvailable(
                    credential.getType(), secret, profileId);
        }
    }

    /**
     * Send credentials for user {@code userId} to {@link RecoverableKeyStoreManager} when its
     * credentials are set/changed.
     */
    private void sendCredentialsOnChangeIfRequired(
            LockscreenCredential credential, int userId, boolean isLockTiedToParent) {
        // A profile whose lock screen is being tied to its parent's will either have a randomly
        // generated credential (creation) or null (removal). We rely on the parent to send its
        // credentials for the profile in both cases as it stores the unified lock credential.
        if (isLockTiedToParent) {
            return;
        }

        // RecoverableKeyStoreManager expects null for empty credential.
        final byte[] secret = credential.isNone() ? null : credential.getCredential();
        // Send credentials for the user and any child profiles that share its lock screen.
        for (int profileId : getProfilesWithSameLockScreen(userId)) {
            mRecoverableKeyStoreManager.lockScreenSecretChanged(
                    credential.getType(), secret, profileId);
        }
    }

    /**
     * Returns all profiles of {@code userId}, including itself, that have the same lock screen
     * challenge.
     */
    private Set<Integer> getProfilesWithSameLockScreen(int userId) {
        Set<Integer> profiles = new ArraySet<>();
        for (UserInfo profile : mUserManager.getProfiles(userId)) {
            if (profile.id == userId
                    || (profile.profileGroupId == userId
                            && isProfileWithUnifiedLock(profile.id))) {
                profiles.add(profile.id);
            }
        }
        return profiles;
    }

    // This method should be called by LockPatternUtil only, all internal methods in this class
    // should call setLockCredentialInternal.
    @Override
    public boolean setLockCredential(LockscreenCredential credential,
            LockscreenCredential savedCredential, int userId) {

        if (!mHasSecureLockScreen
                && credential != null && credential.getType() != CREDENTIAL_TYPE_NONE) {
            throw new UnsupportedOperationException(
                    "This operation requires secure lock screen feature");
        }
        if (!hasPermission(PERMISSION) && !hasPermission(SET_AND_VERIFY_LOCKSCREEN_CREDENTIALS)) {
            if (hasPermission(SET_INITIAL_LOCK) && savedCredential.isNone()) {
                // SET_INITIAL_LOCK can only be used if credential is not set.
            } else {
                throw new SecurityException(
                        "setLockCredential requires SET_AND_VERIFY_LOCKSCREEN_CREDENTIALS or "
                                + PERMISSION);
            }
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            enforceFrpResolved();
            // When changing credential for profiles with unified challenge, some callers
            // will pass in empty credential while others will pass in the credential of
            // the parent user. setLockCredentialInternal() handles the formal case (empty
            // credential) correctly but not the latter. As a stopgap fix, convert the latter
            // case to the formal. The long-term fix would be fixing LSS such that it should
            // accept only the parent user credential on its public API interfaces, swap it
            // with the profile's random credential at that API boundary (i.e. here) and make
            // sure LSS internally does not special case profile with unififed challenge: b/80170828
            if (!savedCredential.isNone() && isProfileWithUnifiedLock(userId)) {
                // Verify the parent credential again, to make sure we have a fresh enough
                // auth token such that getDecryptedPasswordForTiedProfile() inside
                // setLockCredentialInternal() can function correctly.
                verifyCredential(savedCredential, mUserManager.getProfileParent(userId).id,
                        0 /* flags */);
                savedCredential.zeroize();
                savedCredential = LockscreenCredential.createNone();
            }
            synchronized (mSeparateChallengeLock) {
                if (!setLockCredentialInternal(credential, savedCredential,
                        userId, /* isLockTiedToParent= */ false)) {
                    scheduleGc();
                    return false;
                }
                setSeparateProfileChallengeEnabledLocked(userId, true, /* unused */ null);
                notifyPasswordChanged(credential, userId);
            }
            if (isCredentialSharableWithParent(userId)) {
                // Make sure the profile doesn't get locked straight after setting work challenge.
                setDeviceUnlockedForUser(userId);
            }
            notifySeparateProfileChallengeChanged(userId);
            onPostPasswordChanged(credential, userId);
            scheduleGc();
            return true;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * @param savedCredential if the user is a profile with
     * {@link UserManager#isCredentialSharableWithParent()} with unified challenge and
     *   savedCredential is empty, LSS will try to re-derive the profile password internally.
     *     TODO (b/80170828): Fix this so profile password is always passed in.
     * @param isLockTiedToParent is {@code true} if {@code userId} is a profile and its new
     *     credentials are being tied to its parent's credentials.
     */
    private boolean setLockCredentialInternal(LockscreenCredential credential,
            LockscreenCredential savedCredential, int userId, boolean isLockTiedToParent) {
        Objects.requireNonNull(credential);
        Objects.requireNonNull(savedCredential);
        synchronized (mSpManager) {
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                return spBasedSetLockCredentialInternalLocked(credential, savedCredential, userId,
                        isLockTiedToParent);
            }
        }

        if (credential.isNone()) {
            clearUserKeyProtection(userId, null);
            gateKeeperClearSecureUserId(userId);
            mStorage.writeCredentialHash(CredentialHash.createEmptyHash(), userId);
            // Still update PASSWORD_TYPE_KEY if we are running in pre-synthetic password code path,
            // since it forms part of the state that determines the credential type
            // @see getCredentialTypeInternal
            setKeyguardStoredQuality(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userId);
            setKeystorePassword(null, userId);
            fixateNewestUserKeyAuth(userId);
            synchronizeUnifiedWorkChallengeForProfiles(userId, null);
            setUserPasswordMetrics(LockscreenCredential.createNone(), userId);
            sendCredentialsOnChangeIfRequired(credential, userId, isLockTiedToParent);
            return true;
        }

        CredentialHash currentHandle = mStorage.readCredentialHash(userId);
        if (isProfileWithUnifiedLock(userId)) {
            // get credential from keystore when managed/clone profile has unified lock
            if (savedCredential.isNone()) {
                try {
                    //TODO: remove as part of b/80170828
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
                if (!savedCredential.isNone()) {
                    Slog.w(TAG, "Saved credential provided, but none stored");
                }
                savedCredential.close();
                savedCredential = LockscreenCredential.createNone();
            }
        }
        synchronized (mSpManager) {
            initializeSyntheticPasswordLocked(currentHandle.hash, savedCredential, userId);
            return spBasedSetLockCredentialInternalLocked(credential, savedCredential, userId,
                    isLockTiedToParent);
        }
    }

    private void onPostPasswordChanged(LockscreenCredential newCredential, int userHandle) {
        if (userHandle == UserHandle.USER_SYSTEM && isDeviceEncryptionEnabled() &&
            shouldEncryptWithCredentials() && newCredential.isNone()) {
            setCredentialRequiredToDecrypt(false);
        }
        if (newCredential.isPattern()) {
            setBoolean(LockPatternUtils.PATTERN_EVER_CHOSEN_KEY, true, userHandle);
        }
        updatePasswordHistory(newCredential, userHandle);
        mContext.getSystemService(TrustManager.class).reportEnabledTrustAgentsChanged(userHandle);
    }

    /**
     * Store the hash of the *current* password in the password history list, if device policy
     * enforces password history requirement.
     */
    private void updatePasswordHistory(LockscreenCredential password, int userHandle) {
        if (password.isNone()) {
            return;
        }
        if (password.isPattern()) {
            // Do not keep track of historical patterns
            return;
        }
        // Add the password to the password history. We assume all
        // password hashes have the same length for simplicity of implementation.
        String passwordHistory = getString(
                LockPatternUtils.PASSWORD_HISTORY_KEY, /* defaultValue= */ null, userHandle);
        if (passwordHistory == null) {
            passwordHistory = "";
        }
        int passwordHistoryLength = getRequestedPasswordHistoryLength(userHandle);
        if (passwordHistoryLength == 0) {
            passwordHistory = "";
        } else {
            final byte[] hashFactor = getHashFactor(password, userHandle);
            final byte[] salt = getSalt(userHandle).getBytes();
            String hash = password.passwordToHistoryHash(salt, hashFactor);
            if (hash == null) {
                Slog.e(TAG, "Compute new style password hash failed, fallback to legacy style");
                hash = password.legacyPasswordToHash(salt);
            }
            if (TextUtils.isEmpty(passwordHistory)) {
                passwordHistory = hash;
            } else {
                String[] history = passwordHistory.split(
                        LockPatternUtils.PASSWORD_HISTORY_DELIMITER);
                StringJoiner joiner = new StringJoiner(LockPatternUtils.PASSWORD_HISTORY_DELIMITER);
                joiner.add(hash);
                for (int i = 0; i < passwordHistoryLength - 1 && i < history.length; i++) {
                    joiner.add(history[i]);
                }
                passwordHistory = joiner.toString();
            }
        }
        setString(LockPatternUtils.PASSWORD_HISTORY_KEY, passwordHistory, userHandle);
    }

    private String getSalt(int userId) {
        long salt = getLong(LockPatternUtils.LOCK_PASSWORD_SALT_KEY, 0, userId);
        if (salt == 0) {
            try {
                salt = SecureRandom.getInstance("SHA1PRNG").nextLong();
                setLong(LockPatternUtils.LOCK_PASSWORD_SALT_KEY, salt, userId);
                Slog.v(TAG, "Initialized lock password salt for user: " + userId);
            } catch (NoSuchAlgorithmException e) {
                // Throw an exception rather than storing a password we'll never be able to recover
                throw new IllegalStateException("Couldn't get SecureRandom number", e);
            }
        }
        return Long.toHexString(salt);
    }

    private int getRequestedPasswordHistoryLength(int userId) {
        return mInjector.getDevicePolicyManager().getPasswordHistoryLength(null, userId);
    }

    private static boolean isDeviceEncryptionEnabled() {
        return StorageManager.isEncrypted();
    }

    private boolean shouldEncryptWithCredentials() {
        return isCredentialRequiredToDecrypt() && !isDoNotAskCredentialsOnBootSet();
    }

    private boolean isDoNotAskCredentialsOnBootSet() {
        return mInjector.getDevicePolicyManager().getDoNotAskCredentialsOnBoot();
    }

    private boolean isCredentialRequiredToDecrypt() {
        final int value = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.REQUIRE_PASSWORD_TO_DECRYPT, -1);
        return value != 0;
    }

    private UserManager getUserManagerFromCache(int userId) {
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

    protected boolean isCredentialSharableWithParent(int userId) {
        return getUserManagerFromCache(userId).isCredentialSharableWithParent();
    }

    private VerifyCredentialResponse convertResponse(GateKeeperResponse gateKeeperResponse) {
        return VerifyCredentialResponse.fromGateKeeperResponse(gateKeeperResponse);
    }

    private void setCredentialRequiredToDecrypt(boolean required) {
        if (isDeviceEncryptionEnabled()) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    Settings.Global.REQUIRE_PASSWORD_TO_DECRYPT, required ? 1 : 0);
        }
    }

    /** Register the given WeakEscrowTokenRemovedListener. */
    @Override
    public boolean registerWeakEscrowTokenRemovedListener(
            @NonNull IWeakEscrowTokenRemovedListener listener) {
        checkManageWeakEscrowTokenMethodUsage();
        final long token = Binder.clearCallingIdentity();
        try {
            return mSpManager.registerWeakEscrowTokenRemovedListener(listener);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** Unregister the given WeakEscrowTokenRemovedListener. */
    @Override
    public boolean unregisterWeakEscrowTokenRemovedListener(
            @NonNull IWeakEscrowTokenRemovedListener listener) {
        checkManageWeakEscrowTokenMethodUsage();
        final long token = Binder.clearCallingIdentity();
        try {
            return mSpManager.unregisterWeakEscrowTokenRemovedListener(listener);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public long addWeakEscrowToken(byte[] token, int userId,
            @NonNull IWeakEscrowTokenActivatedListener listener) {
        checkManageWeakEscrowTokenMethodUsage();
        Objects.requireNonNull(listener, "Listener can not be null.");
        EscrowTokenStateChangeCallback internalListener = (handle, userId1) -> {
            try {
                listener.onWeakEscrowTokenActivated(handle, userId1);
            } catch (RemoteException e) {
                Slog.e(TAG, "Exception while notifying weak escrow token has been activated", e);
            }
        };
        final long restoreToken = Binder.clearCallingIdentity();
        try {
            return addEscrowToken(token, TOKEN_TYPE_WEAK, userId, internalListener);
        } finally {
            Binder.restoreCallingIdentity(restoreToken);
        }
    }

    @Override
    public boolean removeWeakEscrowToken(long handle, int userId) {
        checkManageWeakEscrowTokenMethodUsage();
        final long token = Binder.clearCallingIdentity();
        try {
            return removeEscrowToken(handle, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean isWeakEscrowTokenActive(long handle, int userId) {
        checkManageWeakEscrowTokenMethodUsage();
        final long token = Binder.clearCallingIdentity();
        try {
            return isEscrowTokenActive(handle, userId);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean isWeakEscrowTokenValid(long handle, byte[] token, int userId) {
        checkManageWeakEscrowTokenMethodUsage();
        final long restoreToken = Binder.clearCallingIdentity();
        try {
            synchronized (mSpManager) {
                if (!mSpManager.hasEscrowData(userId)) {
                    Slog.w(TAG, "Escrow token is disabled on the current user");
                    return false;
                }
                AuthenticationResult authResult = mSpManager.unwrapWeakTokenBasedSyntheticPassword(
                        getGateKeeperService(), handle, token, userId);
                if (authResult.authToken == null) {
                    Slog.w(TAG, "Invalid escrow token supplied");
                    return false;
                }
                return true;
            }
        } finally {
            Binder.restoreCallingIdentity(restoreToken);
        }
    }

    @VisibleForTesting /** Note: this method is overridden in unit tests */
    protected void tieProfileLockToParent(int userId, LockscreenCredential password) {
        if (DEBUG) Slog.v(TAG, "tieProfileLockToParent for user: " + userId);
        byte[] encryptionResult;
        byte[] iv;
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES);
            keyGenerator.init(new SecureRandom());
            SecretKey secretKey = keyGenerator.generateKey();
            try {
                mJavaKeyStore.setEntry(
                        PROFILE_KEY_NAME_ENCRYPT + userId,
                        new java.security.KeyStore.SecretKeyEntry(secretKey),
                        new KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build());
                mJavaKeyStore.setEntry(
                        PROFILE_KEY_NAME_DECRYPT + userId,
                        new java.security.KeyStore.SecretKeyEntry(secretKey),
                        new KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setUserAuthenticationRequired(true)
                                .setUserAuthenticationValidityDurationSeconds(30)
                                .build());
                // Key imported, obtain a reference to it.
                SecretKey keyStoreEncryptionKey = (SecretKey) mJavaKeyStore.getKey(
                        PROFILE_KEY_NAME_ENCRYPT + userId, null);
                Cipher cipher = Cipher.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_GCM + "/"
                                + KeyProperties.ENCRYPTION_PADDING_NONE);
                cipher.init(Cipher.ENCRYPT_MODE, keyStoreEncryptionKey);
                encryptionResult = cipher.doFinal(password.getCredential());
                iv = cipher.getIV();
            } finally {
                // The original key can now be discarded.
                mJavaKeyStore.deleteEntry(PROFILE_KEY_NAME_ENCRYPT + userId);
            }
        } catch (UnrecoverableKeyException
                | BadPaddingException | IllegalBlockSizeException | KeyStoreException
                | NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to encrypt key", e);
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            if (iv.length != PROFILE_KEY_IV_SIZE) {
                throw new IllegalArgumentException("Invalid iv length: " + iv.length);
            }
            outputStream.write(iv);
            outputStream.write(encryptionResult);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to concatenate byte arrays", e);
        }
        mStorage.writeChildProfileLock(userId, outputStream.toByteArray());
    }

    private void setUserKeyProtection(int userId, byte[] key) {
        if (DEBUG) Slog.d(TAG, "setUserKeyProtection: user=" + userId);
        addUserKeyAuth(userId, key);
    }

    private void clearUserKeyProtection(int userId, byte[] secret) {
        if (DEBUG) Slog.d(TAG, "clearUserKeyProtection user=" + userId);
        final UserInfo userInfo = mUserManager.getUserInfo(userId);
        final long callingId = Binder.clearCallingIdentity();
        try {
            mStorageManager.clearUserKeyAuth(userId, userInfo.serialNumber, secret);
        } catch (RemoteException e) {
            throw new IllegalStateException("clearUserKeyAuth failed user=" + userId);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private static byte[] secretFromCredential(LockscreenCredential credential) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            // Personalize the hash
            byte[] personalization = "Android FBE credential hash".getBytes();
            // Pad it to the block size of the hash function
            personalization = Arrays.copyOf(personalization, 128);
            digest.update(personalization);
            digest.update(credential.getCredential());
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("NoSuchAlgorithmException for SHA-512");
        }
    }

    private boolean isUserKeyUnlocked(int userId) {
        try {
            return mStorageManager.isUserKeyUnlocked(userId);
        } catch (RemoteException e) {
            Slog.e(TAG, "failed to check user key locked state", e);
            return false;
        }
    }

    /** Unlock disk encryption */
    private void unlockUserKey(int userId, byte[] secret) {
        final UserInfo userInfo = mUserManager.getUserInfo(userId);
        try {
            mStorageManager.unlockUserKey(userId, userInfo.serialNumber, secret);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to unlock user key " + userId, e);

        }
    }

    private void addUserKeyAuth(int userId, byte[] secret) {
        final UserInfo userInfo = mUserManager.getUserInfo(userId);
        final long callingId = Binder.clearCallingIdentity();
        try {
            mStorageManager.addUserKeyAuth(userId, userInfo.serialNumber, secret);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to add new key to vold " + userId, e);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private void fixateNewestUserKeyAuth(int userId) {
        if (DEBUG) Slog.d(TAG, "fixateNewestUserKeyAuth: user=" + userId);
        final long callingId = Binder.clearCallingIdentity();
        try {
            mStorageManager.fixateNewestUserKeyAuth(userId);
        } catch (RemoteException e) {
            // OK to ignore the exception as vold would just accept both old and new
            // keys if this call fails, and will fix itself during the next boot
            Slog.w(TAG, "fixateNewestUserKeyAuth failed", e);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public void resetKeyStore(int userId) {
        checkWritePermission(userId);
        if (DEBUG) Slog.v(TAG, "Reset keystore for user: " + userId);
        List<Integer> profileUserIds = new ArrayList<>();
        List<LockscreenCredential> profileUserDecryptedPasswords = new ArrayList<>();
        final List<UserInfo> profiles = mUserManager.getProfiles(userId);
        for (UserInfo pi : profiles) {
            // Unlock profile which shares credential with parent with unified lock
            if (isCredentialSharableWithParent(pi.id)
                    && !getSeparateProfileChallengeEnabledInternal(pi.id)
                    && mStorage.hasChildProfileLock(pi.id)) {
                try {
                    profileUserDecryptedPasswords.add(getDecryptedPasswordForTiedProfile(pi.id));
                    profileUserIds.add(pi.id);
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
                    AndroidKeyStoreMaintenance.clearNamespace(Domain.APP,
                            UserHandle.getUid(profileId, uid));
                }
            }
            if (mUserManager.getUserInfo(userId).isPrimary()) {
                AndroidKeyStoreMaintenance.clearNamespace(Domain.SELINUX,
                        KeyProperties.NAMESPACE_WIFI);
            }
        } finally {
            for (int i = 0; i < profileUserIds.size(); ++i) {
                int piUserId = profileUserIds.get(i);
                LockscreenCredential piUserDecryptedPassword = profileUserDecryptedPasswords.get(i);
                if (piUserId != -1 && piUserDecryptedPassword != null) {
                    if (DEBUG) Slog.v(TAG, "Restore tied profile lock");
                    tieProfileLockToParent(piUserId, piUserDecryptedPassword);
                }
                if (piUserDecryptedPassword != null) {
                    piUserDecryptedPassword.zeroize();
                }
            }
        }
    }

    @Override
    public VerifyCredentialResponse checkCredential(LockscreenCredential credential, int userId,
            ICheckCredentialProgressCallback progressCallback) {
        checkPasswordReadPermission();
        try {
            return doVerifyCredential(credential, userId, progressCallback, 0 /* flags */);
        } finally {
            scheduleGc();
        }
    }

    @Override
    @Nullable
    public VerifyCredentialResponse verifyCredential(LockscreenCredential credential,
            int userId, int flags) {
        if (!hasPermission(PERMISSION) && !hasPermission(SET_AND_VERIFY_LOCKSCREEN_CREDENTIALS)) {
            throw new SecurityException(
                    "verifyCredential requires SET_AND_VERIFY_LOCKSCREEN_CREDENTIALS or "
                            + PERMISSION);
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return doVerifyCredential(credential, userId, null /* progressCallback */, flags);
        } finally {
            Binder.restoreCallingIdentity(identity);
            scheduleGc();
        }
    }

    @Override
    public VerifyCredentialResponse verifyGatekeeperPasswordHandle(long gatekeeperPasswordHandle,
            long challenge, int userId) {

        checkPasswordReadPermission();

        final VerifyCredentialResponse response;
        final byte[] gatekeeperPassword;

        synchronized (mGatekeeperPasswords) {
            gatekeeperPassword = mGatekeeperPasswords.get(gatekeeperPasswordHandle);
        }

        synchronized (mSpManager) {
            if (gatekeeperPassword == null) {
                Slog.d(TAG, "No gatekeeper password for handle");
                response = VerifyCredentialResponse.ERROR;
            } else {
                response = mSpManager.verifyChallengeInternal(getGateKeeperService(),
                        gatekeeperPassword, challenge, userId);
            }
        }
        return response;
    }

    @Override
    public void removeGatekeeperPasswordHandle(long gatekeeperPasswordHandle) {
        checkPasswordReadPermission();
        synchronized (mGatekeeperPasswords) {
            mGatekeeperPasswords.remove(gatekeeperPasswordHandle);
        }
    }

    /*
     * Verify user credential and unlock the user. Fix pattern bug by deprecating the old base zero
     * format.
     * @param credential User's lockscreen credential
     * @param userId User to verify the credential for
     * @param progressCallback Receive progress callbacks
     * @param flags See {@link LockPatternUtils.VerifyFlag}
     * @return See {@link VerifyCredentialResponse}
     */
    private VerifyCredentialResponse doVerifyCredential(LockscreenCredential credential,
            int userId, ICheckCredentialProgressCallback progressCallback,
            @LockPatternUtils.VerifyFlag int flags) {
        if (credential == null || credential.isNone()) {
            throw new IllegalArgumentException("Credential can't be null or empty");
        }
        if (userId == USER_FRP && mInjector.settingsGlobalGetInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0) {
            Slog.e(TAG, "FRP credential can only be verified prior to provisioning.");
            return VerifyCredentialResponse.ERROR;
        }

        VerifyCredentialResponse response = spBasedDoVerifyCredential(credential, userId,
                progressCallback, flags);

        // The user employs synthetic password based credential.
        if (response != null) {
            if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
                sendCredentialsOnUnlockIfRequired(credential, userId);
            }
            return response;
        }

        if (userId == USER_FRP) {
            Slog.wtf(TAG, "Unexpected FRP credential type, should be SP based.");
            return VerifyCredentialResponse.ERROR;
        }

        final CredentialHash storedHash = mStorage.readCredentialHash(userId);
        if (!credential.checkAgainstStoredType(storedHash.type)) {
            Slog.wtf(TAG, "doVerifyCredential type mismatch with stored credential??"
                    + " stored: " + storedHash.type + " passed in: " + credential.getType());
            return VerifyCredentialResponse.ERROR;
        }

        response = verifyCredential(userId, storedHash, credential, progressCallback);

        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
            mStrongAuth.reportSuccessfulStrongAuthUnlock(userId);
        }

        return response;
    }

    @Override
    public VerifyCredentialResponse verifyTiedProfileChallenge(LockscreenCredential credential,
            int userId, @LockPatternUtils.VerifyFlag int flags) {
        checkPasswordReadPermission();
        if (!isProfileWithUnifiedLock(userId)) {
            throw new IllegalArgumentException(
                    "User id must be managed/clone profile with unified lock");
        }
        final int parentProfileId = mUserManager.getProfileParent(userId).id;
        // Unlock parent by using parent's challenge
        final VerifyCredentialResponse parentResponse = doVerifyCredential(
                credential,
                parentProfileId,
                null /* progressCallback */,
                flags);
        if (parentResponse.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
            // Failed, just return parent's response
            return parentResponse;
        }

        try {
            // Unlock work profile, and work profile with unified lock must use password only
            return doVerifyCredential(getDecryptedPasswordForTiedProfile(userId),
                    userId, null /* progressCallback */, flags);
        } catch (UnrecoverableKeyException | InvalidKeyException | KeyStoreException
                | NoSuchAlgorithmException | NoSuchPaddingException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException
                | BadPaddingException | CertificateException | IOException e) {
            Slog.e(TAG, "Failed to decrypt child profile key", e);
            throw new IllegalStateException("Unable to get tied profile token");
        } finally {
            scheduleGc();
        }
    }

    /**
     * Lowest-level credential verification routine that talks to GateKeeper. If verification
     * passes, unlock the corresponding user and keystore. Also handles the migration from legacy
     * hash to GK.
     */
    private VerifyCredentialResponse verifyCredential(int userId, CredentialHash storedHash,
            LockscreenCredential credential, ICheckCredentialProgressCallback progressCallback) {
        if ((storedHash == null || storedHash.hash.length == 0) && credential.isNone()) {
            // don't need to pass empty credentials to GateKeeper
            return VerifyCredentialResponse.OK;
        }

        if (storedHash == null || storedHash.hash.length == 0 || credential.isNone()) {
            return VerifyCredentialResponse.ERROR;
        }

        // We're potentially going to be doing a bunch of disk I/O below as part
        // of unlocking the user, so yell if calling from the main thread.
        StrictMode.noteDiskRead();

        GateKeeperResponse gateKeeperResponse;
        try {
            gateKeeperResponse = getGateKeeperService().verifyChallenge(
                    userId, 0L /* challenge */, storedHash.hash, credential.getCredential());
        } catch (RemoteException e) {
            Slog.e(TAG, "gatekeeper verify failed", e);
            gateKeeperResponse = GateKeeperResponse.ERROR;
        }
        VerifyCredentialResponse response = convertResponse(gateKeeperResponse);
        boolean shouldReEnroll = gateKeeperResponse.getShouldReEnroll();

        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {

            // credential has matched

            if (progressCallback != null) {
                try {
                    progressCallback.onCredentialVerified();
                } catch (RemoteException e) {
                    Slog.w(TAG, "progressCallback throws exception", e);
                }
            }
            setUserPasswordMetrics(credential, userId);
            unlockKeystore(credential.getCredential(), userId);

            Slog.i(TAG, "Unlocking user " + userId);
            unlockUser(userId, secretFromCredential(credential));

            if (isProfileWithSeparatedLock(userId)) {
                setDeviceUnlockedForUser(userId);
            }
            if (shouldReEnroll) {
                setLockCredentialInternal(credential, credential,
                        userId, /* isLockTiedToParent= */ false);
            } else {
                // Now that we've cleared of all required GK migration, let's do the final
                // migration to synthetic password.
                synchronized (mSpManager) {
                    if (shouldMigrateToSyntheticPasswordLocked(userId)) {
                        AuthenticationToken auth = initializeSyntheticPasswordLocked(
                                storedHash.hash, credential,  userId);
                        activateEscrowTokens(auth, userId);
                    }
                }
            }
            // Use credentials to create recoverable keystore snapshot.
            sendCredentialsOnUnlockIfRequired(credential, userId);

        } else if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
            if (response.getTimeout() > 0) {
                requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_LOCKOUT, userId);
            }
        }

        return response;
    }

    /**
     * Keep track of the given user's latest password metric. This should be called
     * when the user is authenticating or when a new password is being set. In comparison,
     * {@link #notifyPasswordChanged} only needs to be called when the user changes the password.
     */
    private void setUserPasswordMetrics(LockscreenCredential password, @UserIdInt int userHandle) {
        synchronized (this) {
            mUserPasswordMetrics.put(userHandle, PasswordMetrics.computeForCredential(password));
        }
    }

    @VisibleForTesting
    PasswordMetrics getUserPasswordMetrics(int userHandle) {
        if (!isUserSecure(userHandle)) {
            // for users without password, mUserPasswordMetrics might not be initialized
            // since the user never unlock the device manually. In this case, always
            // return a default metrics object. This is to distinguish this case from
            // the case where during boot user password is unknown yet (returning null here)
            return new PasswordMetrics(CREDENTIAL_TYPE_NONE);
        }
        synchronized (this) {
            return mUserPasswordMetrics.get(userHandle);
        }
    }

    private PasswordMetrics loadPasswordMetrics(AuthenticationToken auth, int userHandle) {
        synchronized (mSpManager) {
            return mSpManager.getPasswordMetrics(auth, getSyntheticPasswordHandleLocked(userHandle),
                    userHandle);
        }
    }

    /**
     * Call after {@link #setUserPasswordMetrics} so metrics are updated before
     * reporting the password changed.
     */
    private void notifyPasswordChanged(LockscreenCredential newCredential, @UserIdInt int userId) {
        mHandler.post(() -> {
            mInjector.getDevicePolicyManager().reportPasswordChanged(
                    PasswordMetrics.computeForCredential(newCredential),
                    userId);
            LocalServices.getService(WindowManagerInternal.class).reportPasswordChanged(userId);
        });
    }

    private void removeUser(int userId, boolean unknownUser) {
        Slog.i(TAG, "RemoveUser: " + userId);
        removeBiometricsForUser(userId);
        mSpManager.removeUser(getGateKeeperService(), userId);
        mStrongAuth.removeUser(userId);

        AndroidKeyStoreMaintenance.onUserRemoved(userId);
        mManagedProfilePasswordCache.removePassword(userId);

        gateKeeperClearSecureUserId(userId);
        if (unknownUser || isCredentialSharableWithParent(userId)) {
            removeKeystoreProfileKey(userId);
        }
        // Clean up storage last, this is to ensure that cleanupDataForReusedUserIdIfNecessary()
        // can make the assumption that no USER_SERIAL_NUMBER_KEY means user is fully removed.
        mStorage.removeUser(userId);
    }

    private void removeKeystoreProfileKey(int targetUserId) {
        Slog.i(TAG, "Remove keystore profile key for user: " + targetUserId);
        try {
            mJavaKeyStore.deleteEntry(PROFILE_KEY_NAME_ENCRYPT + targetUserId);
            mJavaKeyStore.deleteEntry(PROFILE_KEY_NAME_DECRYPT + targetUserId);
        } catch (KeyStoreException e) {
            // We have tried our best to remove all keys
            Slog.e(TAG, "Unable to remove keystore profile key for user:" + targetUserId, e);
        }
    }

    @Override
    public void registerStrongAuthTracker(IStrongAuthTracker tracker) {
        checkPasswordReadPermission();
        mStrongAuth.registerStrongAuthTracker(tracker);
    }

    @Override
    public void unregisterStrongAuthTracker(IStrongAuthTracker tracker) {
        checkPasswordReadPermission();
        mStrongAuth.unregisterStrongAuthTracker(tracker);
    }

    @Override
    public void requireStrongAuth(int strongAuthReason, int userId) {
        checkWritePermission(userId);
        mStrongAuth.requireStrongAuth(strongAuthReason, userId);
    }

    @Override
    public void reportSuccessfulBiometricUnlock(boolean isStrongBiometric, int userId) {
        checkBiometricPermission();
        mStrongAuth.reportSuccessfulBiometricUnlock(isStrongBiometric, userId);
    }

    @Override
    public void scheduleNonStrongBiometricIdleTimeout(int userId) {
        checkBiometricPermission();
        mStrongAuth.scheduleNonStrongBiometricIdleTimeout(userId);
    }

    @Override
    public void userPresent(int userId) {
        checkWritePermission(userId);
        mStrongAuth.reportUnlock(userId);
    }

    @Override
    public int getStrongAuthForUser(int userId) {
        checkPasswordReadPermission();
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
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        enforceShell();
        final int origPid = Binder.getCallingPid();
        final int origUid = Binder.getCallingUid();

        // The original identity is an opaque integer.
        final long origId = Binder.clearCallingIdentity();
        Slog.e(TAG, "Caller pid " + origPid + " Caller uid " + origUid);
        try {
            final LockSettingsShellCommand command =
                    new LockSettingsShellCommand(new LockPatternUtils(mContext), mContext, origPid,
                            origUid);
            command.exec(this, in, out, err, args, callback, resultReceiver);
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

    private class GateKeeperDiedRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            mGateKeeperService.asBinder().unlinkToDeath(this, 0);
            mGateKeeperService = null;
        }
    }

    protected synchronized IGateKeeperService getGateKeeperService() {
        if (mGateKeeperService != null) {
            return mGateKeeperService;
        }

        final IBinder service = ServiceManager.getService(Context.GATEKEEPER_SERVICE);
        if (service != null) {
            try {
                service.linkToDeath(new GateKeeperDiedRecipient(), 0);
            } catch (RemoteException e) {
                Slog.w(TAG, " Unable to register death recipient", e);
            }
            mGateKeeperService = IGateKeeperService.Stub.asInterface(service);
            return mGateKeeperService;
        }

        Slog.e(TAG, "Unable to acquire GateKeeperService");
        return null;
    }

    private void gateKeeperClearSecureUserId(int userId) {
        try {
            getGateKeeperService().clearSecureUserId(userId);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to clear SID", e);
        }
    }

    private void onAuthTokenKnownForUser(@UserIdInt int userId, AuthenticationToken auth) {
        if (mInjector.isGsiRunning()) {
            Slog.w(TAG, "Running in GSI; skipping calls to AuthSecret and RebootEscrow");
            return;
        }

        mRebootEscrowManager.callToRebootEscrowIfNeeded(userId, auth.getVersion(),
                auth.getSyntheticPassword());

        callToAuthSecretIfNeeded(userId, auth);
    }

    private void callToAuthSecretIfNeeded(@UserIdInt int userId,
            AuthenticationToken auth) {
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
     *     whether the user changes their lockscreen PIN or clear/reset it. When the user clears its
     *     lockscreen PIN, we still maintain the existing synthetic password in a password blob
     *     protected by a default PIN.
     * 4. The user SID is linked with synthetic password, but its cleared/re-created when the user
     *     clears/re-creates their lockscreen PIN.
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
     *     The user sets a new lockscreen password FOR THE FIRST TIME on a SP-enabled device.
     *     New credential and new SID will be created
     */
    @GuardedBy("mSpManager")
    @VisibleForTesting
    protected AuthenticationToken initializeSyntheticPasswordLocked(byte[] credentialHash,
            LockscreenCredential credential, int userId) {
        Slog.i(TAG, "Initialize SyntheticPassword for user: " + userId);
        Preconditions.checkState(
                getSyntheticPasswordHandleLocked(userId) == SyntheticPasswordManager.DEFAULT_HANDLE,
                "Cannot reinitialize SP");

        final AuthenticationToken auth = mSpManager.newSyntheticPasswordAndSid(
                getGateKeeperService(), credentialHash, credential, userId);
        if (auth == null) {
            Slog.wtf(TAG, "initializeSyntheticPasswordLocked returns null auth token");
            return null;
        }
        long handle = mSpManager.createPasswordBasedSyntheticPassword(getGateKeeperService(),
                credential, auth, userId);
        if (!credential.isNone()) {
            if (credentialHash == null) {
                // Since when initializing SP, we didn't provide an existing password handle
                // for it to migrate SID, we need to create a new SID for the user.
                mSpManager.newSidForUser(getGateKeeperService(), auth, userId);
            }
            mSpManager.verifyChallenge(getGateKeeperService(), auth, 0L, userId);
            setUserKeyProtection(userId, auth.deriveDiskEncryptionKey());
            setKeystorePassword(auth.deriveKeyStorePassword(), userId);
        } else {
            clearUserKeyProtection(userId, null);
            setKeystorePassword(null, userId);
            gateKeeperClearSecureUserId(userId);
        }
        fixateNewestUserKeyAuth(userId);
        setSyntheticPasswordHandleLocked(handle, userId);
        onAuthTokenKnownForUser(userId, auth);
        return auth;
    }

    @VisibleForTesting
    long getSyntheticPasswordHandleLocked(int userId) {
        return getLong(SYNTHETIC_PASSWORD_HANDLE_KEY,
                SyntheticPasswordManager.DEFAULT_HANDLE, userId);
    }

    private void setSyntheticPasswordHandleLocked(long handle, int userId) {
        final long oldHandle = getSyntheticPasswordHandleLocked(userId);
        setLong(SYNTHETIC_PASSWORD_HANDLE_KEY, handle, userId);
        setLong(PREV_SYNTHETIC_PASSWORD_HANDLE_KEY, oldHandle, userId);
        setLong(SYNTHETIC_PASSWORD_UPDATE_TIME_KEY, System.currentTimeMillis(), userId);

    }

    @VisibleForTesting
    boolean isSyntheticPasswordBasedCredential(int userId) {
        synchronized (mSpManager) {
            return isSyntheticPasswordBasedCredentialLocked(userId);
        }
    }

    private boolean isSyntheticPasswordBasedCredentialLocked(int userId) {
        if (userId == USER_FRP) {
            final int type = mStorage.readPersistentDataBlock().type;
            return type == PersistentData.TYPE_SP || type == PersistentData.TYPE_SP_WEAVER;
        }
        long handle = getSyntheticPasswordHandleLocked(userId);
        return handle != SyntheticPasswordManager.DEFAULT_HANDLE;
    }

    @VisibleForTesting
    protected boolean shouldMigrateToSyntheticPasswordLocked(int userId) {
        return getSyntheticPasswordHandleLocked(userId) == SyntheticPasswordManager.DEFAULT_HANDLE;
    }

    private VerifyCredentialResponse spBasedDoVerifyCredential(LockscreenCredential userCredential,
            int userId, ICheckCredentialProgressCallback progressCallback,
            @LockPatternUtils.VerifyFlag int flags) {
        final boolean hasEnrolledBiometrics = mInjector.hasEnrolledBiometrics(userId);

        Slog.d(TAG, "spBasedDoVerifyCredential: user=" + userId
                + " hasEnrolledBiometrics=" + hasEnrolledBiometrics);

        final AuthenticationResult authResult;
        VerifyCredentialResponse response;
        final boolean requestGkPw = (flags & VERIFY_FLAG_REQUEST_GK_PW_HANDLE) != 0;

        synchronized (mSpManager) {
            if (!isSyntheticPasswordBasedCredentialLocked(userId)) {
                return null;
            }
            if (userId == USER_FRP) {
                return mSpManager.verifyFrpCredential(getGateKeeperService(),
                        userCredential, progressCallback);
            }

            long handle = getSyntheticPasswordHandleLocked(userId);
            authResult = mSpManager.unwrapPasswordBasedSyntheticPassword(
                    getGateKeeperService(), handle, userCredential, userId, progressCallback);
            response = authResult.gkResponse;

            // credential has matched
            if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
                mBiometricDeferredQueue.addPendingLockoutResetForUser(userId,
                        authResult.authToken.deriveGkPassword());

                // perform verifyChallenge with synthetic password which generates the real GK auth
                // token and response for the current user
                response = mSpManager.verifyChallenge(getGateKeeperService(), authResult.authToken,
                        0L /* challenge */, userId);
                if (response.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
                    // This shouldn't really happen: the unwrapping of SP succeeds, but SP doesn't
                    // match the recorded GK password handle.
                    Slog.wtf(TAG, "verifyChallenge with SP failed.");
                    return VerifyCredentialResponse.ERROR;
                }
            }
        }
        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
            onCredentialVerified(authResult.authToken,
                    PasswordMetrics.computeForCredential(userCredential), userId);
        } else if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
            if (response.getTimeout() > 0) {
                requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_LOCKOUT, userId);
            }
        }

        if (response.isMatched() && requestGkPw) {
            final long handle = storeGatekeeperPasswordTemporarily(
                    authResult.authToken.deriveGkPassword());
            return new VerifyCredentialResponse.Builder().setGatekeeperPasswordHandle(handle)
                    .build();
        } else {
            return response;
        }
    }

    /**
     * Stores the gatekeeper password temporarily.
     * @param gatekeeperPassword unlocked upon successful Synthetic Password
     * @return non-zero handle to the gatekeeper password, which can be used for a set amount of
     *         time.
     */
    private long storeGatekeeperPasswordTemporarily(byte[] gatekeeperPassword) {
        long handle = 0L;

        synchronized (mGatekeeperPasswords) {
            while (handle == 0L || mGatekeeperPasswords.get(handle) != null) {
                handle = mRandom.nextLong();
            }
            mGatekeeperPasswords.put(handle, gatekeeperPassword);
        }

        final long finalHandle = handle;
        mHandler.postDelayed(() -> {
            synchronized (mGatekeeperPasswords) {
                Slog.d(TAG, "Removing handle: " + finalHandle);
                mGatekeeperPasswords.remove(finalHandle);
            }
        }, GK_PW_HANDLE_STORE_DURATION_MS);

        return handle;
    }

    private void onCredentialVerified(AuthenticationToken authToken, PasswordMetrics metrics,
            int userId) {

        if (metrics != null) {
            synchronized (this) {
                mUserPasswordMetrics.put(userId,  metrics);
            }
        } else {
            Slog.wtf(TAG, "Null metrics after credential verification");
        }

        unlockKeystore(authToken.deriveKeyStorePassword(), userId);

        {
            final byte[] secret = authToken.deriveDiskEncryptionKey();
            unlockUser(userId, secret);
            Arrays.fill(secret, (byte) 0);
        }
        activateEscrowTokens(authToken, userId);

        if (isProfileWithSeparatedLock(userId)) {
            setDeviceUnlockedForUser(userId);
        }
        mStrongAuth.reportSuccessfulStrongAuthUnlock(userId);

        onAuthTokenKnownForUser(userId, authToken);
    }

    private void setDeviceUnlockedForUser(int userId) {
        final TrustManager trustManager = mContext.getSystemService(TrustManager.class);
        trustManager.setDeviceLockedForUser(userId, false);
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
    private long setLockCredentialWithAuthTokenLocked(LockscreenCredential credential,
            AuthenticationToken auth, int userId) {
        if (DEBUG) Slog.d(TAG, "setLockCredentialWithAuthTokenLocked: user=" + userId);
        final int savedCredentialType = getCredentialTypeInternal(userId);
        long newHandle = mSpManager.createPasswordBasedSyntheticPassword(getGateKeeperService(),
                credential, auth, userId);
        final Map<Integer, LockscreenCredential> profilePasswords;
        if (!credential.isNone()) {
            // not needed by synchronizeUnifiedWorkChallengeForProfiles()
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
                setUserKeyProtection(userId, auth.deriveDiskEncryptionKey());
                fixateNewestUserKeyAuth(userId);
                setKeystorePassword(auth.deriveKeyStorePassword(), userId);
            }
        } else {
            // Cache all profile password if they use unified work challenge. This will later be
            // used to clear the profile's password in synchronizeUnifiedWorkChallengeForProfiles()
            profilePasswords = getDecryptedPasswordsForAllTiedProfiles(userId);

            // we are clearing password of a secured device, so need to nuke SID as well.
            mSpManager.clearSidForUser(userId);
            gateKeeperClearSecureUserId(userId);
            // Clear key from vold so ActivityManager can just unlock the user with empty secret
            // during boot. Vold storage needs to be unlocked before manipulation of the keys can
            // succeed.
            unlockUserKey(userId, auth.deriveDiskEncryptionKey());
            clearUserKeyProtection(userId, auth.deriveDiskEncryptionKey());
            fixateNewestUserKeyAuth(userId);
            unlockKeystore(auth.deriveKeyStorePassword(), userId);
            setKeystorePassword(null, userId);
            removeBiometricsForUser(userId);
        }
        setSyntheticPasswordHandleLocked(newHandle, userId);
        synchronizeUnifiedWorkChallengeForProfiles(userId, profilePasswords);

        setUserPasswordMetrics(credential, userId);
        mManagedProfilePasswordCache.removePassword(userId);
        if (savedCredentialType != CREDENTIAL_TYPE_NONE) {
            mSpManager.destroyAllWeakTokenBasedSyntheticPasswords(userId);
        }

        if (profilePasswords != null) {
            for (Map.Entry<Integer, LockscreenCredential> entry : profilePasswords.entrySet()) {
                entry.getValue().zeroize();
            }
        }

        return newHandle;
    }

    private void removeBiometricsForUser(int userId) {
        removeAllFingerprintForUser(userId);
        removeAllFaceForUser(userId);
    }

    private void removeAllFingerprintForUser(final int userId) {
        FingerprintManager mFingerprintManager = mInjector.getFingerprintManager();
        if (mFingerprintManager != null && mFingerprintManager.isHardwareDetected()) {
            if (mFingerprintManager.hasEnrolledFingerprints(userId)) {
                final CountDownLatch latch = new CountDownLatch(1);
                mFingerprintManager.removeAll(userId, fingerprintManagerRemovalCallback(latch));
                try {
                    latch.await(10000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Slog.e(TAG, "Latch interrupted when removing fingerprint", e);
                }
            }
        }
    }

    private void removeAllFaceForUser(final int userId) {
        FaceManager mFaceManager = mInjector.getFaceManager();
        if (mFaceManager != null && mFaceManager.isHardwareDetected()) {
            if (mFaceManager.hasEnrolledTemplates(userId)) {
                final CountDownLatch latch = new CountDownLatch(1);
                mFaceManager.removeAll(userId, faceManagerRemovalCallback(latch));
                try {
                    latch.await(10000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Slog.e(TAG, "Latch interrupted when removing face", e);
                }
            }
        }
    }

    private FingerprintManager.RemovalCallback fingerprintManagerRemovalCallback(
            CountDownLatch latch) {
        return new FingerprintManager.RemovalCallback() {
            @Override
            public void onRemovalError(@Nullable Fingerprint fp, int errMsgId, CharSequence err) {
                Slog.e(TAG, "Unable to remove fingerprint, error: " + err);
                latch.countDown();
            }

            @Override
            public void onRemovalSucceeded(Fingerprint fp, int remaining) {
                if (remaining == 0) {
                    latch.countDown();
                }
            }
        };
    }

    private FaceManager.RemovalCallback faceManagerRemovalCallback(CountDownLatch latch) {
        return new FaceManager.RemovalCallback() {
            @Override
            public void onRemovalError(@Nullable Face face, int errMsgId, CharSequence err) {
                Slog.e(TAG, "Unable to remove face, error: " + err);
                latch.countDown();
            }

            @Override
            public void onRemovalSucceeded(Face face, int remaining) {
                if (remaining == 0) {
                    latch.countDown();
                }
            }
        };
    }

    /**
     * @param savedCredential if the user is a profile with unified challenge and
     *   savedCredential is empty, LSS will try to re-derive the profile password internally.
     *     TODO (b/80170828): Fix this so profile password is always passed in.
     */
    @GuardedBy("mSpManager")
    private boolean spBasedSetLockCredentialInternalLocked(LockscreenCredential credential,
            LockscreenCredential savedCredential, int userId, boolean isLockTiedToParent) {
        if (DEBUG) Slog.d(TAG, "spBasedSetLockCredentialInternalLocked: user=" + userId);
        if (savedCredential.isNone() && isProfileWithUnifiedLock(userId)) {
            // get credential from keystore when profile has unified lock
            try {
                //TODO: remove as part of b/80170828
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

        if (auth == null) {
            if (response == null
                    || response.getResponseCode() == VerifyCredentialResponse.RESPONSE_ERROR) {
                Slog.w(TAG, "Failed to enroll: incorrect credential.");
                return false;
            }
            if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
                Slog.w(TAG, "Failed to enroll: rate limit exceeded.");
                return false;
            }
            // Should not be reachable, but just in case.
            throw new IllegalStateException("password change failed");
        }

        onAuthTokenKnownForUser(userId, auth);
        setLockCredentialWithAuthTokenLocked(credential, auth, userId);
        mSpManager.destroyPasswordBasedSyntheticPassword(handle, userId);
        sendCredentialsOnChangeIfRequired(credential, userId, isLockTiedToParent);
        return true;
    }

    /**
     * Returns a fixed pseudorandom byte string derived from the user's synthetic password.
     * This is used to salt the password history hash to protect the hash against offline
     * bruteforcing, since rederiving this value requires a successful authentication.
     * If user is a profile with {@link UserManager#isCredentialSharableWithParent()} true and with
     * unified challenge, currentCredential is ignored.
     */
    @Override
    public byte[] getHashFactor(LockscreenCredential currentCredential, int userId) {
        checkPasswordReadPermission();
        try {
            if (isProfileWithUnifiedLock(userId)) {
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
        } finally {
            scheduleGc();
        }
    }

    private long addEscrowToken(@NonNull byte[] token, @TokenType int type, int userId,
            @NonNull EscrowTokenStateChangeCallback callback) {
        if (DEBUG) Slog.d(TAG, "addEscrowToken: user=" + userId + ", type=" + type);
        synchronized (mSpManager) {
            // Migrate to synthetic password based credentials if the user has no password,
            // the token can then be activated immediately.
            AuthenticationToken auth = null;
            if (!isUserSecure(userId)) {
                if (shouldMigrateToSyntheticPasswordLocked(userId)) {
                    auth = initializeSyntheticPasswordLocked(
                            /* credentialHash */ null, LockscreenCredential.createNone(), userId);
                } else /* isSyntheticPasswordBasedCredentialLocked(userId) */ {
                    long pwdHandle = getSyntheticPasswordHandleLocked(userId);
                    auth = mSpManager.unwrapPasswordBasedSyntheticPassword(getGateKeeperService(),
                            pwdHandle, LockscreenCredential.createNone(), userId, null).authToken;
                }
            }
            if (isSyntheticPasswordBasedCredentialLocked(userId)) {
                disableEscrowTokenOnNonManagedDevicesIfNeeded(userId);
                if (!mSpManager.hasEscrowData(userId)) {
                    throw new SecurityException("Escrow token is disabled on the current user");
                }
            }
            long handle = mSpManager.createTokenBasedSyntheticPassword(token, type, userId,
                    callback);
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

    @Override
    public boolean hasPendingEscrowToken(int userId) {
        checkPasswordReadPermission();
        synchronized (mSpManager) {
            return !mSpManager.getPendingTokensForUser(userId).isEmpty();
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

    private boolean setLockCredentialWithToken(LockscreenCredential credential, long tokenHandle,
            byte[] token, int userId) {
        boolean result;
        synchronized (mSpManager) {
            if (!mSpManager.hasEscrowData(userId)) {
                throw new SecurityException("Escrow token is disabled on the current user");
            }
            result = setLockCredentialWithTokenInternalLocked(
                    credential, tokenHandle, token, userId);
        }
        if (result) {
            synchronized (mSeparateChallengeLock) {
                setSeparateProfileChallengeEnabledLocked(userId, true, /* unused */ null);
            }
            if (credential.isNone()) {
                // If clearing credential, unlock the user manually in order to progress user start
                // Call unlockUser() on a handler thread so no lock is held (either by LSS or by
                // the caller like DPMS), otherwise it can lead to deadlock.
                mHandler.post(() -> unlockUser(userId, null));
            }
            notifyPasswordChanged(credential, userId);
            notifySeparateProfileChallengeChanged(userId);
        }
        return result;
    }

    @GuardedBy("mSpManager")
    private boolean setLockCredentialWithTokenInternalLocked(LockscreenCredential credential,
            long tokenHandle, byte[] token, int userId) {
        final AuthenticationResult result;
        result = mSpManager.unwrapTokenBasedSyntheticPassword(getGateKeeperService(), tokenHandle,
                token, userId);
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
        onAuthTokenKnownForUser(userId, result.authToken);
        long oldHandle = getSyntheticPasswordHandleLocked(userId);
        setLockCredentialWithAuthTokenLocked(credential, result.authToken, userId);
        mSpManager.destroyPasswordBasedSyntheticPassword(oldHandle, userId);
        return true;
    }

    private boolean unlockUserWithToken(long tokenHandle, byte[] token, int userId) {
        AuthenticationResult authResult;
        synchronized (mSpManager) {
            if (!mSpManager.hasEscrowData(userId)) {
                Slog.w(TAG, "Escrow token is disabled on the current user");
                return false;
            }
            authResult = mSpManager.unwrapTokenBasedSyntheticPassword(getGateKeeperService(),
                    tokenHandle, token, userId);
            if (authResult.authToken == null) {
                Slog.w(TAG, "Invalid escrow token supplied");
                return false;
            }
        }

        onCredentialVerified(authResult.authToken,
                loadPasswordMetrics(authResult.authToken, userId), userId);
        return true;
    }

    @Override
    public boolean tryUnlockWithCachedUnifiedChallenge(int userId) {
        try (LockscreenCredential cred = mManagedProfilePasswordCache.retrievePassword(userId)) {
            if (cred == null) {
                return false;
            }
            return doVerifyCredential(cred, userId, null /* progressCallback */, 0 /* flags */)
                    .getResponseCode() == VerifyCredentialResponse.RESPONSE_OK;
        }
    }

    @Override
    public void removeCachedUnifiedChallenge(int userId) {
        mManagedProfilePasswordCache.removePassword(userId);
    }

    static String timestampToString(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
    }

    private static String credentialTypeToString(int credentialType) {
        switch (credentialType) {
            case CREDENTIAL_TYPE_NONE:
                return "None";
            case CREDENTIAL_TYPE_PATTERN:
                return "Pattern";
            case CREDENTIAL_TYPE_PIN:
                return "Pin";
            case CREDENTIAL_TYPE_PASSWORD:
                return "Password";
            default:
                return "Unknown " + credentialType;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, printWriter)) return;
        IndentingPrintWriter pw = new IndentingPrintWriter(printWriter, "  ");

        pw.println("Current lock settings service state:");
        pw.println();

        pw.println("User State:");
        pw.increaseIndent();
        List<UserInfo> users = mUserManager.getUsers();
        for (int user = 0; user < users.size(); user++) {
            final int userId = users.get(user).id;
            pw.println("User " + userId);
            pw.increaseIndent();
            synchronized (mSpManager) {
                pw.println(String.format("SP Handle: %x",
                        getSyntheticPasswordHandleLocked(userId)));
                pw.println(String.format("Last changed: %s (%x)",
                        timestampToString(getLong(SYNTHETIC_PASSWORD_UPDATE_TIME_KEY, 0, userId)),
                        getLong(PREV_SYNTHETIC_PASSWORD_HANDLE_KEY, 0, userId)));
            }
            try {
                pw.println(String.format("SID: %x",
                        getGateKeeperService().getSecureUserId(userId)));
            } catch (RemoteException e) {
                // ignore.
            }
            // It's OK to dump the password type since anyone with physical access can just
            // observe it from the keyguard directly.
            pw.println("Quality: " + getKeyguardStoredQuality(userId));
            pw.println("CredentialType: " + credentialTypeToString(
                    getCredentialTypeInternal(userId)));
            pw.println("SeparateChallenge: " + getSeparateProfileChallengeEnabledInternal(userId));
            pw.println(String.format("Metrics: %s",
                    getUserPasswordMetrics(userId) != null ? "known" : "unknown"));
            pw.decreaseIndent();
        }
        pw.println();
        pw.decreaseIndent();

        pw.println("Keys in namespace:");
        pw.increaseIndent();
        dumpKeystoreKeys(pw);
        pw.println();
        pw.decreaseIndent();

        pw.println("Storage:");
        pw.increaseIndent();
        mStorage.dump(pw);
        pw.println();
        pw.decreaseIndent();

        pw.println("StrongAuth:");
        pw.increaseIndent();
        mStrongAuth.dump(pw);
        pw.println();
        pw.decreaseIndent();

        pw.println("RebootEscrow:");
        pw.increaseIndent();
        mRebootEscrowManager.dump(pw);
        pw.println();
        pw.decreaseIndent();

        pw.println("PasswordHandleCount: " + mGatekeeperPasswords.size());
    }

    private void dumpKeystoreKeys(IndentingPrintWriter pw) {
        try {
            final Enumeration<String> aliases = mJavaKeyStore.aliases();
            while (aliases.hasMoreElements()) {
                pw.println(aliases.nextElement());
            }
        } catch (KeyStoreException e) {
            pw.println("Unable to get keys: " + e.toString());
            Slog.d(TAG, "Dump error", e);
        }
    }

    /**
     * Cryptographically disable escrow token support for the current user, if the user is not
     * managed (either user has a profile owner, or if device is managed). Do not disable
     * if we are running an automotive build.
     */
    private void disableEscrowTokenOnNonManagedDevicesIfNeeded(int userId) {
        final UserManagerInternal userManagerInternal = mInjector.getUserManagerInternal();

        // Managed profile should have escrow enabled
        if (userManagerInternal.isUserManaged(userId)) {
            Slog.i(TAG, "Managed profile can have escrow token");
            return;
        }

        // Devices with Device Owner should have escrow enabled on all users.
        if (userManagerInternal.isDeviceManaged()) {
            Slog.i(TAG, "Corp-owned device can have escrow token");
            return;
        }

        // If the device is yet to be provisioned (still in SUW), there is still
        // a chance that Device Owner will be set on the device later, so postpone
        // disabling escrow token for now.
        if (!mInjector.getDeviceStateCache().isDeviceProvisioned()) {
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
    }

    /**
     * Schedules garbage collection to sanitize lockscreen credential remnants in memory.
     *
     * One source of leftover lockscreen credentials is the unmarshalled binder method arguments.
     * Since this method will be called within the binder implementation method, a small delay is
     * added before the GC operation to allow the enclosing binder proxy code to complete and
     * release references to the argument.
     */
    private void scheduleGc() {
        mHandler.postDelayed(() -> {
            System.gc();
            System.runFinalization();
            System.gc();
        }, 2000);
    }

    private class DeviceProvisionedObserver extends ContentObserver {
        private final Uri mDeviceProvisionedUri = Settings.Global.getUriFor(
                Settings.Global.DEVICE_PROVISIONED);

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
        public long addEscrowToken(byte[] token, int userId,
                EscrowTokenStateChangeCallback callback) {
            return LockSettingsService.this.addEscrowToken(token, TOKEN_TYPE_STRONG, userId,
                    callback);
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
        public boolean setLockCredentialWithToken(LockscreenCredential credential, long tokenHandle,
                byte[] token, int userId) {
        if (!mHasSecureLockScreen
                && credential != null && credential.getType() != CREDENTIAL_TYPE_NONE) {
                throw new UnsupportedOperationException(
                        "This operation requires secure lock screen feature.");
            }
            if (!LockSettingsService.this.setLockCredentialWithToken(
                    credential, tokenHandle, token, userId)) {
                return false;
            }
            onPostPasswordChanged(credential, userId);
            return true;
        }

        @Override
        public boolean unlockUserWithToken(long tokenHandle, byte[] token, int userId) {
            return LockSettingsService.this.unlockUserWithToken(tokenHandle, token, userId);
        }

        @Override
        public PasswordMetrics getUserPasswordMetrics(int userHandle) {
            final long identity = Binder.clearCallingIdentity();
            try {
                if (isProfileWithUnifiedLock(userHandle)) {
                    // A managed/clone profile with unified challenge is supposed to be protected by
                    // the parent lockscreen, so asking for its password metrics is not really
                    // useful, as this method would just return the metrics of the random profile
                    // password
                    Slog.w(TAG, "Querying password metrics for unified challenge profile: "
                            + userHandle);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return LockSettingsService.this.getUserPasswordMetrics(userHandle);
        }

        @Override
        public boolean prepareRebootEscrow() {
            if (!mRebootEscrowManager.prepareRebootEscrow()) {
                return false;
            }
            mStrongAuth.requireStrongAuth(STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE, USER_ALL);
            return true;
        }

        @Override
        public void setRebootEscrowListener(RebootEscrowListener listener) {
            mRebootEscrowManager.setRebootEscrowListener(listener);
        }

        @Override
        public boolean clearRebootEscrow() {
            if (!mRebootEscrowManager.clearRebootEscrow()) {
                return false;
            }
            mStrongAuth.noLongerRequireStrongAuth(STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE,
                    USER_ALL);
            return true;
        }

        @Override
        public @ArmRebootEscrowErrorCode int armRebootEscrow() {
            return mRebootEscrowManager.armRebootEscrowIfNeeded();
        }

        @Override
        public void refreshStrongAuthTimeout(int userId) {
            mStrongAuth.refreshStrongAuthTimeout(userId);
        }
    }

    private class RebootEscrowCallbacks implements RebootEscrowManager.Callbacks {
        @Override
        public boolean isUserSecure(int userId) {
            return LockSettingsService.this.isUserSecure(userId);
        }

        @Override
        public void onRebootEscrowRestored(byte spVersion, byte[] syntheticPassword, int userId) {
            SyntheticPasswordManager.AuthenticationToken
                    authToken = new SyntheticPasswordManager.AuthenticationToken(spVersion);
            authToken.recreateDirectly(syntheticPassword);
            synchronized (mSpManager) {
                mSpManager.verifyChallenge(getGateKeeperService(), authToken, 0L, userId);
            }
            onCredentialVerified(authToken, loadPasswordMetrics(authToken, userId), userId);
        }
    }
}
