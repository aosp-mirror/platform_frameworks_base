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

import static android.security.Flags.reportPrimaryAuthAttempts;
import static android.Manifest.permission.ACCESS_KEYGUARD_SECURE_STORAGE;
import static android.Manifest.permission.CONFIGURE_FACTORY_RESET_PROTECTION;
import static android.Manifest.permission.MANAGE_BIOMETRIC;
import static android.Manifest.permission.SET_AND_VERIFY_LOCKSCREEN_CREDENTIALS;
import static android.Manifest.permission.SET_INITIAL_LOCK;
import static android.app.admin.DevicePolicyManager.DEPRECATE_USERMANAGERINTERNAL_DEVICEPOLICY_DEFAULT;
import static android.app.admin.DevicePolicyManager.DEPRECATE_USERMANAGERINTERNAL_DEVICEPOLICY_FLAG;
import static android.app.admin.DevicePolicyResources.Strings.Core.PROFILE_ENCRYPTED_DETAIL;
import static android.app.admin.DevicePolicyResources.Strings.Core.PROFILE_ENCRYPTED_MESSAGE;
import static android.app.admin.DevicePolicyResources.Strings.Core.PROFILE_ENCRYPTED_TITLE;
import static android.content.Context.KEYGUARD_SERVICE;
import static android.content.Intent.ACTION_MAIN_USER_LOCKSCREEN_KNOWLEDGE_FACTOR_CHANGED;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_SYSTEM;

import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_NONE;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PASSWORD_OR_PIN;
import static com.android.internal.widget.LockPatternUtils.CREDENTIAL_TYPE_PIN;
import static com.android.internal.widget.LockPatternUtils.CURRENT_LSKF_BASED_PROTECTOR_ID_KEY;
import static com.android.internal.widget.LockPatternUtils.EscrowTokenStateChangeCallback;
import static com.android.internal.widget.LockPatternUtils.PIN_LENGTH_UNAVAILABLE;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT;
import static com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE;
import static com.android.internal.widget.LockPatternUtils.USER_FRP;
import static com.android.internal.widget.LockPatternUtils.USER_REPAIR_MODE;
import static com.android.internal.widget.LockPatternUtils.VERIFY_FLAG_REQUEST_GK_PW_HANDLE;
import static com.android.internal.widget.LockPatternUtils.VERIFY_FLAG_WRITE_REPAIR_MODE_PW;
import static com.android.internal.widget.LockPatternUtils.frpCredentialEnabled;
import static com.android.internal.widget.LockPatternUtils.isSpecialUserId;
import static com.android.internal.widget.LockPatternUtils.pinOrPasswordQualityToCredentialType;
import static com.android.internal.widget.LockPatternUtils.userOwnsFrpCredential;
import static com.android.server.locksettings.SyntheticPasswordManager.TOKEN_TYPE_STRONG;
import static com.android.server.locksettings.SyntheticPasswordManager.TOKEN_TYPE_WEAK;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteLockscreenValidationResult;
import android.app.RemoteLockscreenValidationSession;
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
import android.content.pm.UserProperties;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.authsecret.IAuthSecret;
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
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.ICeStorageLockEventListener;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.security.AndroidKeyStoreMaintenance;
import android.security.KeyStoreAuthorization;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyProtection;
import android.security.keystore.recovery.KeyChainProtectionParams;
import android.security.keystore.recovery.KeyChainSnapshot;
import android.security.keystore.recovery.RecoveryCertPath;
import android.security.keystore.recovery.WrappedApplicationKey;
import android.security.keystore2.AndroidKeyStoreLoadStoreParameter;
import android.security.keystore2.AndroidKeyStoreProvider;
import android.service.gatekeeper.IGateKeeperService;
import android.service.notification.StatusBarNotification;
import android.system.keystore2.Domain;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.ICheckCredentialProgressCallback;
import com.android.internal.widget.ILockSettings;
import com.android.internal.widget.IWeakEscrowTokenActivatedListener;
import com.android.internal.widget.IWeakEscrowTokenRemovedListener;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockSettingsInternal;
import com.android.internal.widget.LockSettingsStateListener;
import com.android.internal.widget.LockscreenCredential;
import com.android.internal.widget.RebootEscrowListener;
import com.android.internal.widget.VerifyCredentialResponse;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.locksettings.LockSettingsStorage.PersistentData;
import com.android.server.locksettings.SyntheticPasswordManager.AuthenticationResult;
import com.android.server.locksettings.SyntheticPasswordManager.SyntheticPassword;
import com.android.server.locksettings.SyntheticPasswordManager.TokenType;
import com.android.server.locksettings.recoverablekeystore.RecoverableKeyStoreManager;
import com.android.server.pm.UserManagerInternal;
import com.android.server.utils.Slogf;
import com.android.server.wm.WindowManagerInternal;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
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
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * LockSettingsService (LSS) mainly has the following responsibilities:
 * <p>
 * <ul>
 *   <li>Provide APIs to verify and change the Lock Screen Knowledge Factor (LSKF) ("lockscreen
 *   credential") of each user.  Unlock users when their correct LSKF is given.</li>
 *
 *   <li>Store other lockscreen related settings, such as some Keyguard (UI) settings.</li>
 *
 *   <li>Manage each user's synthetic password (SP), which is their main cryptographic secret.
 *   See {@link SyntheticPasswordManager}.</li>
 *
 *   <li>Protect each user's SP using their LSKF.  Use the Gatekeeper or Weaver HAL to ensure that
 *   guesses of the LSKF are ratelimited by the TEE or secure element.</li>
 *
 *   <li>Protect each user's data using their SP.  For example, use the SP to encrypt/decrypt the
 *   user's credential-encrypted (CE) key for file-based encryption (FBE).</li>
 *
 *   <li>Generate, protect, and use unified profile passwords.</li>
 *
 *   <li>Support unlocking the SP by alternative means: resume-on-reboot (reboot escrow) for easier
 *   OTA updates, and escrow tokens when set up by the Device Policy Controller (DPC).</li>
 *
 *   <li>Implement part of the Factory Reset Protection (FRP) and Repair Mode features by storing
 *   the information needed to verify a user's LSKF on the persist or metadata partition.</li>
 *
 *   <li>Support insider attack resistance using the AuthSecret HAL.</li>
 *
 *   <li>Implement "recoverable keystore", a feature that enables end-to-end encrypted backups.
 *   See {@link android.security.keystore.recovery.RecoveryController}.</li>
 * </ul>
 * <p>
 * The main clients of LockSettingsService are Keyguard (i.e. the lockscreen UI, which is part of
 * System UI), the Settings app (com.android.settings), and other parts of system_server.  Most
 * methods are protected by ACCESS_KEYGUARD_SECURE_STORAGE which only system processes can have.
 *
 * @hide
 */
public class LockSettingsService extends ILockSettings.Stub {
    private static final String TAG = "LockSettingsService";
    private static final String PERMISSION = ACCESS_KEYGUARD_SECURE_STORAGE;
    private static final String BIOMETRIC_PERMISSION = MANAGE_BIOMETRIC;

    private static final int PROFILE_KEY_IV_SIZE = 12;
    private static final String SEPARATE_PROFILE_CHALLENGE_KEY = "lockscreen.profilechallenge";
    private static final String PREV_LSKF_BASED_PROTECTOR_ID_KEY = "prev-sp-handle";
    private static final String LSKF_LAST_CHANGED_TIME_KEY = "sp-handle-ts";
    private static final String USER_SERIAL_NUMBER_KEY = "serial-number";

    private static final String MIGRATED_FRP2 = "migrated_frp2";
    private static final String MIGRATED_KEYSTORE_NS = "migrated_keystore_namespace";
    private static final String MIGRATED_SP_CE_ONLY = "migrated_all_users_to_sp_and_bound_ce";
    private static final String MIGRATED_SP_FULL = "migrated_all_users_to_sp_and_bound_keys";

    private static final boolean FIX_UNLOCKED_DEVICE_REQUIRED_KEYS =
            android.security.Flags.fixUnlockedDeviceRequiredKeysV2();

    // Duration that LockSettingsService will store the gatekeeper password for. This allows
    // multiple biometric enrollments without prompting the user to enter their password via
    // ConfirmLockPassword/ConfirmLockPattern multiple times. This needs to be at least the duration
    // from the start of the first biometric sensor's enrollment to the start of the last biometric
    // sensor's enrollment. If biometric enrollment requests a password handle that has expired, the
    // user's credential must be presented again, e.g. via ConfirmLockPattern/ConfirmLockPassword.
    private static final int GK_PW_HANDLE_STORE_DURATION_MS = 10 * 60 * 1000; // 10 minutes

    private static final String PROFILE_KEY_NAME_ENCRYPT = "profile_key_name_encrypt_";
    private static final String PROFILE_KEY_NAME_DECRYPT = "profile_key_name_decrypt_";

    private static final int HEADLESS_VENDOR_AUTH_SECRET_LENGTH = 32;

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
    private final BiometricDeferredQueue mBiometricDeferredQueue;
    private final LongSparseArray<byte[]> mGatekeeperPasswords;

    private final NotificationManager mNotificationManager;
    protected final UserManager mUserManager;
    private final IStorageManager mStorageManager;
    private final IActivityManager mActivityManager;
    private final SyntheticPasswordManager mSpManager;

    private final KeyStore mKeyStore;
    private final KeyStoreAuthorization mKeyStoreAuthorization;
    private final RecoverableKeyStoreManager mRecoverableKeyStoreManager;
    private final UnifiedProfilePasswordCache mUnifiedProfilePasswordCache;

    private final RebootEscrowManager mRebootEscrowManager;

    // Locking order is mUserCreationAndRemovalLock -> mSpManager.
    private final Object mUserCreationAndRemovalLock = new Object();
    // These two arrays are only used at boot time.  To save memory, they are set to null near the
    // end of the boot, when onThirdPartyAppsStarted() is called.
    @GuardedBy("mUserCreationAndRemovalLock")
    private SparseIntArray mEarlyCreatedUsers = new SparseIntArray();
    @GuardedBy("mUserCreationAndRemovalLock")
    private SparseIntArray mEarlyRemovedUsers = new SparseIntArray();
    @GuardedBy("mUserCreationAndRemovalLock")
    private boolean mThirdPartyAppsStarted;

    // Current password metrics for all secured users on the device. Updated when user unlocks the
    // device or changes password. Removed if user is stopped with its CE key evicted.
    @GuardedBy("this")
    private final SparseArray<PasswordMetrics> mUserPasswordMetrics = new SparseArray<>();
    @VisibleForTesting
    protected boolean mHasSecureLockScreen;

    @VisibleForTesting
    protected final Object mHeadlessAuthSecretLock = new Object();

    @VisibleForTesting
    @GuardedBy("mHeadlessAuthSecretLock")
    protected byte[] mAuthSecret;

    protected IGateKeeperService mGateKeeperService;
    protected IAuthSecret mAuthSecretService;

    /**
     * The UIDs that are used for system credential storage in keystore.
     */
    private static final int[] SYSTEM_CREDENTIAL_UIDS = {
            Process.VPN_UID, Process.ROOT_UID, Process.SYSTEM_UID};

    private HashMap<UserHandle, UserManager> mUserManagerCache = new HashMap<>();

    private final CopyOnWriteArrayList<LockSettingsStateListener> mLockSettingsStateListeners =
            new CopyOnWriteArrayList<>();

    private final StorageManagerInternal mStorageManagerInternal;

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
                mLockSettingsService.deleteRepairModePersistentDataIfNeeded();
            } else if (phase == PHASE_BOOT_COMPLETED) {
                mLockSettingsService.loadEscrowData();
            }
        }

        @Override
        public void onUserStarting(@NonNull TargetUser user) {
            mLockSettingsService.onUserStarting(user.getUserIdentifier());
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            mLockSettingsService.onUserUnlocking(user.getUserIdentifier());
        }

        @Override
        public void onUserStopped(@NonNull TargetUser user) {
            mLockSettingsService.onUserStopped(user.getUserIdentifier());
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
        byte[] randomLockSeed = SecureRandomUtils.randomBytes(40);
        char[] newPasswordChars = HexEncoding.encode(randomLockSeed);
        byte[] newPassword = new byte[newPasswordChars.length];
        for (int i = 0; i < newPasswordChars.length; i++) {
            newPassword[i] = (byte) newPasswordChars[i];
        }
        LockscreenCredential credential =
                LockscreenCredential.createUnifiedProfilePassword(newPassword);
        Arrays.fill(newPasswordChars, '\u0000');
        Arrays.fill(newPassword, (byte) 0);
        Arrays.fill(randomLockSeed, (byte) 0);
        return credential;
    }

    /**
     * Tie profile to primary profile if it is in unified mode and not tied before.
     * Only for profiles which share credential with parent. (e.g. managed and clone profiles)
     *
     * @param profileUserId  profile user Id
     * @param profileUserPassword  profile original password (when it has separated lock).
     */
    private void tieProfileLockIfNecessary(int profileUserId,
            LockscreenCredential profileUserPassword) {
        // Only for profiles that shares credential with parent
        if (!isCredentialSharableWithParent(profileUserId)) {
            return;
        }
        // Do not tie profile when separate challenge is enabled
        if (getSeparateProfileChallengeEnabledInternal(profileUserId)) {
            return;
        }
        // Do not tie profile to parent when it's done already
        if (mStorage.hasChildProfileLock(profileUserId)) {
            return;
        }
        final UserInfo parent = mUserManager.getProfileParent(profileUserId);
        if (parent == null) {
            return;
        }
        // If parent does not have a screen lock, simply clear credential from the profile,
        // to maintain the invariant that unified profile should always have the same secure state
        // as its parent.
        if (!isUserSecure(parent.id) && !profileUserPassword.isNone()) {
            Slogf.i(TAG, "Clearing password for profile user %d to match parent", profileUserId);
            setLockCredentialInternal(LockscreenCredential.createNone(), profileUserPassword,
                    profileUserId, /* isLockTiedToParent= */ true);
            return;
        }
        final long parentSid;
        // Do not tie when the parent has no SID (but does have a screen lock).
        // This can only happen during an upgrade path where SID is yet to be
        // generated when the user unlocks for the first time.
        try {
            parentSid = getGateKeeperService().getSecureUserId(parent.id);
            if (parentSid == 0) {
                return;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to talk to GateKeeper service", e);
            return;
        }
        try (LockscreenCredential unifiedProfilePassword = generateRandomProfilePassword()) {
            setLockCredentialInternal(unifiedProfilePassword, profileUserPassword, profileUserId,
                    /* isLockTiedToParent= */ true);
            tieProfileLockToParent(profileUserId, parent.id, unifiedProfilePassword);
            mUnifiedProfilePasswordCache.storePassword(profileUserId, unifiedProfilePassword,
                    parentSid);
        }
    }

    static class Injector {

        protected Context mContext;
        private ServiceThread mHandlerThread;
        private Handler mHandler;

        public Injector(Context context) {
            mContext = context;
        }

        public Context getContext() {
            return mContext;
        }

        public ServiceThread getServiceThread() {
            if (mHandlerThread == null) {
                mHandlerThread = new ServiceThread(TAG,
                        Process.THREAD_PRIORITY_BACKGROUND,
                        true /*allowIo*/);
                mHandlerThread.start();
            }
            return mHandlerThread;
        }

        public Handler getHandler(ServiceThread handlerThread) {
            if (mHandler == null) {
                mHandler = new Handler(handlerThread.getLooper());
            }
            return mHandler;
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

        public StorageManagerInternal getStorageManagerInternal() {
            return LocalServices.getService(StorageManagerInternal.class);
        }

        public SyntheticPasswordManager getSyntheticPasswordManager(LockSettingsStorage storage) {
            return new SyntheticPasswordManager(getContext(), storage, getUserManager(),
                    new PasswordSlotManager());
        }

        public RebootEscrowManager getRebootEscrowManager(RebootEscrowManager.Callbacks callbacks,
                LockSettingsStorage storage) {
            return new RebootEscrowManager(mContext, callbacks, storage,
                    getHandler(getServiceThread()));
        }

        public int binderGetCallingUid() {
            return Binder.getCallingUid();
        }

        public boolean isGsiRunning() {
            return LockPatternUtils.isGsiRunning();
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

        public BiometricManager getBiometricManager() {
            return (BiometricManager) mContext.getSystemService(Context.BIOMETRIC_SERVICE);
        }

        public KeyStore getKeyStore() {
            try {
                KeyStore ks = KeyStore.getInstance(
                        SyntheticPasswordCrypto.androidKeystoreProviderName());
                ks.load(new AndroidKeyStoreLoadStoreParameter(
                        SyntheticPasswordCrypto.keyNamespace()));
                return ks;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot load keystore", e);
            }
        }

        public KeyStoreAuthorization getKeyStoreAuthorization() {
            return KeyStoreAuthorization.getInstance();
        }

        public @NonNull UnifiedProfilePasswordCache getUnifiedProfilePasswordCache(KeyStore ks) {
            return new UnifiedProfilePasswordCache(ks);
        }

        public boolean isHeadlessSystemUserMode() {
            return UserManager.isHeadlessSystemUserMode();
        }

        public boolean isMainUserPermanentAdmin() {
            return Resources.getSystem()
                    .getBoolean(com.android.internal.R.bool.config_isMainUserPermanentAdmin);
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
        mKeyStoreAuthorization = injector.getKeyStoreAuthorization();
        mRecoverableKeyStoreManager = injector.getRecoverableKeyStoreManager();
        mHandler = injector.getHandler(injector.getServiceThread());
        mStrongAuth = injector.getStrongAuth();
        mActivityManager = injector.getActivityManager();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_ADDED);
        filter.addAction(Intent.ACTION_USER_STARTING);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        injector.getContext().registerReceiverAsUser(mBroadcastReceiver, UserHandle.ALL, filter,
                null, null);

        mStorage = injector.getStorage();
        mNotificationManager = injector.getNotificationManager();
        mUserManager = injector.getUserManager();
        mStorageManager = injector.getStorageManager();
        mStorageManagerInternal = injector.getStorageManagerInternal();
        mStrongAuthTracker = injector.getStrongAuthTracker();
        mStrongAuthTracker.register(mStrongAuth);
        mGatekeeperPasswords = new LongSparseArray<>();

        mSpManager = injector.getSyntheticPasswordManager(mStorage);
        mUnifiedProfilePasswordCache = injector.getUnifiedProfilePasswordCache(mKeyStore);
        mBiometricDeferredQueue = new BiometricDeferredQueue(mSpManager, mHandler);

        mRebootEscrowManager = injector.getRebootEscrowManager(new RebootEscrowCallbacks(),
                mStorage);

        LocalServices.addService(LockSettingsInternal.class, new LocalService());
    }

    private void updateActivatedEncryptionNotifications(String reason) {
        for (UserInfo userInfo : mUserManager.getUsers()) {
            Context userContext = mContext.createContextAsUser(UserHandle.of(userInfo.id), 0);
            NotificationManager nm = (NotificationManager)
                    userContext.getSystemService(Context.NOTIFICATION_SERVICE);
            for (StatusBarNotification notification : nm.getActiveNotifications()) {
                if (notification.getId() == SystemMessage.NOTE_FBE_ENCRYPTED_NOTIFICATION) {
                    maybeShowEncryptionNotificationForUser(userInfo.id, reason);
                    break;
                }
            }
        }
    }

    /**
     * If the user is a managed profile whose credential-encrypted storage is locked, show a
     * notification requesting the user to unlock the device.
     */
    private void maybeShowEncryptionNotificationForUser(@UserIdInt int userId, String reason) {
        final UserInfo user = mUserManager.getUserInfo(userId);
        if (!user.isManagedProfile()) {
            // When the user is locked, we communicate it loud-and-clear
            // on the lockscreen; we only show a notification below for
            // locked managed profiles.
            return;
        }

        if (isCeStorageUnlocked(userId)) {
            // If the user's CE storage is already unlocked, then the user will be automatically
            // unlocked, so there is no need to show the notification.
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
                showEncryptionNotificationForProfile(userHandle, parent.getUserHandle(), reason);
            }
        }
    }

    private void showEncryptionNotificationForProfile(UserHandle user, UserHandle parent,
            String reason) {
        CharSequence title = getEncryptionNotificationTitle();
        CharSequence message = getEncryptionNotificationMessage();
        CharSequence detail = getEncryptionNotificationDetail();

        final KeyguardManager km = (KeyguardManager) mContext.getSystemService(KEYGUARD_SERVICE);
        final Intent unlockIntent =
                km.createConfirmDeviceCredentialIntent(null, null, user.getIdentifier());
        if (unlockIntent == null) {
            return;
        }

        // Suppress all notifications on non-FBE devices for now
        if (!StorageManager.isFileEncrypted()) return;

        unlockIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        PendingIntent intent;
        if (android.app.admin.flags.Flags.hsumUnlockNotificationFix()) {
            intent = PendingIntent.getActivityAsUser(mContext, 0, unlockIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED,
                    null, parent);
        } else {
            intent = PendingIntent.getActivity(mContext, 0, unlockIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE_UNAUDITED);
        }

        Slogf.d(TAG, "Showing encryption notification for user %d; reason: %s",
                user.getIdentifier(), reason);

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
        Slogf.d(TAG, "Hiding encryption notification for user %d", userHandle.getIdentifier());
        mNotificationManager.cancelAsUser(null, SystemMessage.NOTE_FBE_ENCRYPTED_NOTIFICATION,
            userHandle);
    }

    @VisibleForTesting
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.QUERY_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS}, conditional = true)
    void onUserStopped(int userId) {
        hideEncryptionNotification(new UserHandle(userId));

        // Normally, CE storage is locked when a user is stopped, and restarting the user requires
        // strong auth.  Therefore, reset the user's strong auth flags.  The exception is users that
        // allow delayed locking; under some circumstances, biometric authentication is allowed to
        // restart such users.  Don't reset the strong auth flags for such users.
        //
        // TODO(b/319142556): It might make more sense to reset the strong auth flags when CE
        // storage is locked, instead of when the user is stopped.  This would ensure the flags get
        // reset if CE storage is locked later for a user that allows delayed locking.
        if (android.os.Flags.allowPrivateProfile()
                && android.multiuser.Flags.enableBiometricsToUnlockPrivateSpace()
                && android.multiuser.Flags.enablePrivateSpaceFeatures()) {
            UserProperties userProperties = mUserManager.getUserProperties(UserHandle.of(userId));
            if (userProperties != null && userProperties.getAllowStoppingUserWithDelayedLocking()) {
                return;
            }
        }
        int strongAuthRequired = LockPatternUtils.StrongAuthTracker.getDefaultFlags(mContext);
        requireStrongAuth(strongAuthRequired, userId);

        // Don't keep the password metrics in memory for a stopped user that will require strong
        // auth to start again, since strong auth will make the password metrics available again.
        synchronized (this) {
            mUserPasswordMetrics.remove(userId);
        }
    }

    private void onUserStarting(final int userId) {
        maybeShowEncryptionNotificationForUser(userId, "user started");
    }

    /**
     * Removes the LSS state for the given userId if the userId was reused without its LSS state
     * being fully removed.
     * <p>
     * This is primarily needed for users that were removed by Android 13 or earlier, which didn't
     * guarantee removal of LSS state as it relied on the {@code ACTION_USER_REMOVED} intent.  It is
     * also needed because {@link #removeUser()} delays requests to remove LSS state until Weaver is
     * guaranteed to be available, so they can be lost.
     * <p>
     * Stale state is detected by checking whether the user serial number changed.  This works
     * because user serial numbers are never reused.
     */
    private void removeStateForReusedUserIdIfNecessary(@UserIdInt int userId, int serialNumber) {
        if (userId == UserHandle.USER_SYSTEM) {
            // Short circuit as we never clean up user 0.
            return;
        }
        int storedSerialNumber = mStorage.getInt(USER_SERIAL_NUMBER_KEY, -1, userId);
        if (storedSerialNumber != serialNumber) {
            // If LockSettingsStorage does not have a copy of the serial number, it could be either
            // this is a user created before the serial number recording logic is introduced, or
            // the user does not exist or was removed and cleaned up properly. In either case, don't
            // invoke removeUserState().
            if (storedSerialNumber != -1) {
                Slogf.i(TAG, "Removing stale state for reused userId %d (serial %d => %d)", userId,
                        storedSerialNumber, serialNumber);
                removeUserState(userId);
            }
            mStorage.setInt(USER_SERIAL_NUMBER_KEY, serialNumber, userId);
        }
    }

    private void onUserUnlocking(final int userId) {
        // Perform tasks which require locks in LSS on a handler, as we are callbacks from
        // ActivityManager.unlockUser()
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                // Hide notification first, as tie profile lock takes time
                hideEncryptionNotification(new UserHandle(userId));

                if (isCredentialSharableWithParent(userId)) {
                    tieProfileLockIfNecessary(userId, LockscreenCredential.createNone());
                }
            }
        });
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_ADDED.equals(intent.getAction())) {
                if (!FIX_UNLOCKED_DEVICE_REQUIRED_KEYS) {
                    // Notify keystore that a new user was added.
                    final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                    AndroidKeyStoreMaintenance.onUserAdded(userHandle);
                }
            } else if (Intent.ACTION_USER_STARTING.equals(intent.getAction())) {
                final int userHandle = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                mStorage.prefetchUser(userHandle);
            } else if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                updateActivatedEncryptionNotifications("locale changed");
            }
        }
    };

    @Override // binder interface
    public void systemReady() {
        checkWritePermission();

        mHasSecureLockScreen = mContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_SECURE_LOCK_SCREEN);
        migrateOldData();
        getAuthSecretHal();
        mDeviceProvisionedObserver.onSystemReady();

        // Work around an issue in PropertyInvalidatedCache where the cache doesn't work until the
        // first invalidation.  This can be removed if PropertyInvalidatedCache is fixed.
        LockPatternUtils.invalidateCredentialTypeCache();

        // TODO: maybe skip this for split system user mode.
        mStorage.prefetchUser(UserHandle.USER_SYSTEM);
        mBiometricDeferredQueue.systemReady(mInjector.getFingerprintManager(),
                mInjector.getFaceManager(), mInjector.getBiometricManager());
        if (android.os.Flags.allowPrivateProfile()
                && android.multiuser.Flags.enablePrivateSpaceFeatures()
                && android.multiuser.Flags.enableBiometricsToUnlockPrivateSpace()) {
            mStorageManagerInternal.registerStorageLockEventListener(mCeStorageLockEventListener);
        }
    }

    private final ICeStorageLockEventListener mCeStorageLockEventListener =
            new ICeStorageLockEventListener() {
                @Override
                public void onStorageLocked(int userId) {
                    Slog.i(TAG, "Storage lock event received for " + userId);
                    if (android.os.Flags.allowPrivateProfile()
                            && android.multiuser.Flags.enablePrivateSpaceFeatures()
                            && android.multiuser.Flags.enableBiometricsToUnlockPrivateSpace()) {
                        mHandler.post(() -> {
                            try {
                                UserProperties userProperties =
                                        mUserManager.getUserProperties(UserHandle.of(userId));
                                if (userProperties != null && userProperties
                                        .getAllowStoppingUserWithDelayedLocking()) {
                                    int strongAuthRequired = LockPatternUtils.StrongAuthTracker
                                            .getDefaultFlags(mContext);
                                    requireStrongAuth(strongAuthRequired, userId);
                                }
                            } catch (IllegalArgumentException e) {
                                Slogf.d(TAG, "User %d does not exist or has been removed",
                                        userId);
                            }
                        });
                    }
                }};

    private void loadEscrowData() {
        mRebootEscrowManager.loadRebootEscrowDataIfAvailable(mHandler);
    }

    private void getAuthSecretHal() {
        mAuthSecretService =
                IAuthSecret.Stub.asInterface(
                        ServiceManager.waitForDeclaredService(IAuthSecret.DESCRIPTOR + "/default"));
        if (mAuthSecretService != null) {
            Slog.i(TAG, "Device implements AIDL AuthSecret HAL");
        } else {
            try {
                android.hardware.authsecret.V1_0.IAuthSecret authSecretServiceHidl =
                        android.hardware.authsecret.V1_0.IAuthSecret.getService(/* retry */ true);
                mAuthSecretService = new AuthSecretHidlAdapter(authSecretServiceHidl);
                Slog.i(TAG, "Device implements HIDL AuthSecret HAL");
            } catch (NoSuchElementException e) {
                Slog.i(TAG, "Device doesn't implement AuthSecret HAL");
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to get AuthSecret HAL(hidl)", e);
            }
        }
    }

    private void migrateOldData() {
        if (getString(MIGRATED_KEYSTORE_NS, null, 0) == null) {
            boolean success = true;
            synchronized (mSpManager) {
                success &= mSpManager.migrateKeyNamespace();
            }
            success &= migrateProfileLockKeys();
            if (success) {
                setString(MIGRATED_KEYSTORE_NS, "true", 0);
                Slog.i(TAG, "Migrated keys to LSS namespace");
            } else {
                Slog.w(TAG, "Failed to migrate keys to LSS namespace");
            }
        }

    }

    @VisibleForTesting
    void migrateOldDataAfterSystemReady() {
        // Write the FRP persistent data block if needed.
        //
        // The original purpose of this code was to write the FRP block for the first time, when
        // upgrading from Android 8.1 or earlier which didn't use the FRP block.  This code has
        // since been repurposed to also fix the "bad" (non-forwards-compatible) FRP block written
        // by Android 14 Beta 2.  For this reason, the database key used here has been renamed from
        // "migrated_frp" to "migrated_frp2" to cause migrateFrpCredential() to run again on devices
        // where it had run before.
        if (LockPatternUtils.frpCredentialEnabled(mContext)
                && !getBoolean(MIGRATED_FRP2, false, 0)) {
            migrateFrpCredential();
            setBoolean(MIGRATED_FRP2, true, 0);
        }
    }

    /**
     * Write the FRP persistent data block if the following are satisfied:
     * - the user who owns the FRP credential has a nonempty credential
     * - the FRP persistent data block doesn't exist or uses the "bad" format from Android 14 Beta 2
     */
    private void migrateFrpCredential() {
        PersistentData data = mStorage.readPersistentDataBlock();
        if (data != PersistentData.NONE && !data.isBadFormatFromAndroid14Beta()) {
            return;
        }
        for (UserInfo userInfo : mUserManager.getUsers()) {
            if (userOwnsFrpCredential(mContext, userInfo) && isUserSecure(userInfo.id)) {
                synchronized (mSpManager) {
                    int actualQuality = (int) getLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                            DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userInfo.id);

                    mSpManager.migrateFrpPasswordLocked(
                            getCurrentLskfBasedProtectorId(userInfo.id),
                            userInfo,
                            redactActualQualityToMostLenientEquivalentQuality(actualQuality));
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

    @VisibleForTesting
    void deleteRepairModePersistentDataIfNeeded() {
        if (!LockPatternUtils.isRepairModeSupported(mContext)
                || LockPatternUtils.isRepairModeActive(mContext)
                || mInjector.isGsiRunning()) {
            return;
        }
        mStorage.deleteRepairModePersistentData();
    }

    // This is called when Weaver is guaranteed to be available (if the device supports Weaver).
    // It does any synthetic password related work that was delayed from earlier in the boot.
    private void onThirdPartyAppsStarted() {
        synchronized (mUserCreationAndRemovalLock) {
            // Handle delayed calls to LSS.removeUser() and LSS.createNewUser().
            for (int i = 0; i < mEarlyRemovedUsers.size(); i++) {
                int userId = mEarlyRemovedUsers.keyAt(i);
                Slogf.i(TAG, "Removing locksettings state for removed user %d now that boot "
                        + "is complete", userId);
                removeUserState(userId);
            }
            mEarlyRemovedUsers = null; // no longer needed
            for (int i = 0; i < mEarlyCreatedUsers.size(); i++) {
                int userId = mEarlyCreatedUsers.keyAt(i);
                int serialNumber = mEarlyCreatedUsers.valueAt(i);

                removeStateForReusedUserIdIfNecessary(userId, serialNumber);
                Slogf.i(TAG, "Creating locksettings state for user %d now that boot is complete",
                        userId);
                initializeSyntheticPassword(userId);
            }
            mEarlyCreatedUsers = null; // no longer needed

            // Do a one-time migration for any unsecured users: create the user's synthetic password
            // if not already done, encrypt the user's CE key with the synthetic password if not
            // already done, and create the user's Keystore super keys if not already done.
            //
            // This is needed for the following cases:
            //
            // - Finalizing the creation of the system user on the first boot of a device, as the
            //   system user is special and doesn't go through the normal user creation flow.
            //
            // - Upgrading from Android 13 or earlier, where unsecured users didn't necessarily have
            //   a synthetic password, and if they did have a synthetic password their CE key wasn't
            //   encrypted by it.  Also, unsecured users didn't have Keystore super keys.
            //
            // - Upgrading from Android 14, where unsecured users didn't have Keystore super keys.
            //
            // The end result is that all users, regardless of whether they are secured or not, have
            // a synthetic password with all keys initialized and protected by it.
            //
            // Note: if this migration gets interrupted (e.g. by the device powering off), there
            // shouldn't be a problem since this will run again on the next boot, and
            // setCeStorageProtection() and initKeystoreSuperKeys(..., true) are idempotent.
            if (FIX_UNLOCKED_DEVICE_REQUIRED_KEYS) {
                if (!getBoolean(MIGRATED_SP_FULL, false, 0)) {
                    for (UserInfo user : mUserManager.getAliveUsers()) {
                        removeStateForReusedUserIdIfNecessary(user.id, user.serialNumber);
                        synchronized (mSpManager) {
                            migrateUserToSpWithBoundKeysLocked(user.id);
                        }
                    }
                    setBoolean(MIGRATED_SP_FULL, true, 0);
                }
            } else {
                if (getString(MIGRATED_SP_CE_ONLY, null, 0) == null) {
                    for (UserInfo user : mUserManager.getAliveUsers()) {
                        removeStateForReusedUserIdIfNecessary(user.id, user.serialNumber);
                        synchronized (mSpManager) {
                            migrateUserToSpWithBoundCeKeyLocked(user.id);
                        }
                    }
                    setString(MIGRATED_SP_CE_ONLY, "true", 0);
                }

                if (getBoolean(MIGRATED_SP_FULL, false, 0)) {
                    // The FIX_UNLOCKED_DEVICE_REQUIRED_KEYS flag was enabled but then got disabled.
                    // Ensure the full migration runs again the next time the flag is enabled...
                    setBoolean(MIGRATED_SP_FULL, false, 0);
                }
            }

            mThirdPartyAppsStarted = true;
        }
    }

    @GuardedBy("mSpManager")
    private void migrateUserToSpWithBoundCeKeyLocked(@UserIdInt int userId) {
        if (isUserSecure(userId)) {
            Slogf.d(TAG, "User %d is secured; no migration needed", userId);
            return;
        }
        long protectorId = getCurrentLskfBasedProtectorId(userId);
        if (protectorId == SyntheticPasswordManager.NULL_PROTECTOR_ID) {
            Slogf.i(TAG, "Migrating unsecured user %d to SP-based credential", userId);
            initializeSyntheticPassword(userId);
        } else {
            Slogf.i(TAG, "Existing unsecured user %d has a synthetic password; re-encrypting CE " +
                    "key with it", userId);
            AuthenticationResult result = mSpManager.unlockLskfBasedProtector(
                    getGateKeeperService(), protectorId, LockscreenCredential.createNone(), userId,
                    null);
            if (result.syntheticPassword == null) {
                Slogf.wtf(TAG, "Failed to unwrap synthetic password for unsecured user %d", userId);
                return;
            }
            setCeStorageProtection(userId, result.syntheticPassword);
        }
    }

    @GuardedBy("mSpManager")
    private void migrateUserToSpWithBoundKeysLocked(@UserIdInt int userId) {
        if (isUserSecure(userId)) {
            Slogf.d(TAG, "User %d is secured; no migration needed", userId);
            return;
        }
        long protectorId = getCurrentLskfBasedProtectorId(userId);
        if (protectorId == SyntheticPasswordManager.NULL_PROTECTOR_ID) {
            Slogf.i(TAG, "Migrating unsecured user %d to SP-based credential", userId);
            initializeSyntheticPassword(userId);
            return;
        }
        Slogf.i(TAG, "Existing unsecured user %d has a synthetic password", userId);
        AuthenticationResult result = mSpManager.unlockLskfBasedProtector(
                getGateKeeperService(), protectorId, LockscreenCredential.createNone(), userId,
                null);
        SyntheticPassword sp = result.syntheticPassword;
        if (sp == null) {
            Slogf.wtf(TAG, "Failed to unwrap synthetic password for unsecured user %d", userId);
            return;
        }
        // While setCeStorageProtection() is idempotent, it does log some error messages when called
        // again.  Skip it if we know it was already handled by an earlier upgrade to Android 14.
        if (getString(MIGRATED_SP_CE_ONLY, null, 0) == null) {
            Slogf.i(TAG, "Encrypting CE key of user %d with synthetic password", userId);
            setCeStorageProtection(userId, sp);
        }
        Slogf.i(TAG, "Initializing Keystore super keys for user %d", userId);
        initKeystoreSuperKeys(userId, sp, /* allowExisting= */ true);
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

    private void enforceFrpNotActive() {
        final int mainUserId = mInjector.getUserManagerInternal().getMainUserId();
        if (mainUserId < 0) {
            Slog.d(TAG, "No Main user on device; skipping enforceFrpNotActive");
            return;
        }

        final ContentResolver cr = mContext.getContentResolver();
        final boolean inSetupWizard = Settings.Secure.getIntForUser(cr,
                Settings.Secure.USER_SETUP_COMPLETE, 0, mainUserId) == 0;
        final boolean isFrpActive = android.security.Flags.frpEnforcement()
                ? mStorage.isFactoryResetProtectionActive()
                : (Settings.Global.getInt(cr, Settings.Global.SECURE_FRP_MODE, 0) == 1)
                        && inSetupWizard;

        if (isFrpActive) {
            throw new SecurityException("Cannot change credential while factory reset protection"
                    + " is active");
        }
    }

    private final void checkWritePermission() {
        mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsWrite");
    }

    private final void checkPasswordReadPermission() {
        mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsRead");
    }

    private final void checkPasswordHavePermission() {
        mContext.enforceCallingOrSelfPermission(PERMISSION, "LockSettingsHave");
    }

    private final void checkDatabaseReadPermission(String requestedKey, int userId) {
        if (!hasPermission(PERMISSION)) {
            throw new SecurityException("uid=" + getCallingUid() + " needs permission "
                    + PERMISSION + " to read " + requestedKey + " for user " + userId);
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
        checkDatabaseReadPermission(SEPARATE_PROFILE_CHALLENGE_KEY, userId);
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
        checkWritePermission();
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
        checkWritePermission();
        Objects.requireNonNull(key);
        mStorage.setBoolean(key, value, userId);
    }

    @Override
    public void setLong(String key, long value, int userId) {
        checkWritePermission();
        Objects.requireNonNull(key);
        mStorage.setLong(key, value, userId);
    }

    @Override
    public void setString(String key, String value, int userId) {
        checkWritePermission();
        Objects.requireNonNull(key);
        mStorage.setString(key, value, userId);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue, int userId) {
        checkDatabaseReadPermission(key, userId);
        return mStorage.getBoolean(key, defaultValue, userId);
    }

    @Override
    public long getLong(String key, long defaultValue, int userId) {
        checkDatabaseReadPermission(key, userId);
        return mStorage.getLong(key, defaultValue, userId);
    }

    @Override
    public String getString(String key, String defaultValue, int userId) {
        checkDatabaseReadPermission(key, userId);
        return mStorage.getString(key, defaultValue, userId);
    }

    // Not relevant for new devices, but some legacy devices still have PASSWORD_TYPE_KEY around to
    // distinguish between credential types.
    private int getKeyguardStoredQuality(int userId) {
        return (int) mStorage.getLong(LockPatternUtils.PASSWORD_TYPE_KEY,
                DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED, userId);
    }

    /*
     * Gets the PIN length for the given user if it is currently available.
     * Can only be invoked by process/activity that have the right permission.
     * Returns:
     *      A. Actual PIN length if credential type PIN and auto confirm feature is enabled
     *         for the user or user's PIN has been successfully verified since the device booted
     *      B. PIN_LENGTH_UNAVAILABLE if pin length is not stored/available
     */
    @Override
    public int getPinLength(int userId) {
        checkPasswordHavePermission();
        PasswordMetrics passwordMetrics = getUserPasswordMetrics(userId);
        if (passwordMetrics != null && passwordMetrics.credType == CREDENTIAL_TYPE_PIN) {
            return passwordMetrics.length;
        }
        synchronized (mSpManager) {
            final long protectorId = getCurrentLskfBasedProtectorId(userId);
            if (protectorId == SyntheticPasswordManager.NULL_PROTECTOR_ID) {
                // Only possible for new users during early boot (before onThirdPartyAppsStarted())
                return PIN_LENGTH_UNAVAILABLE;
            }
            return mSpManager.getPinLength(protectorId, userId);
        }
    }

    /**
     * {@link LockPatternUtils#refreshStoredPinLength(int)}
     * @param userId user id of the user whose pin length we want to save
     * @return true/false depending on whether PIN length has been saved or not
     */
    @Override
    public boolean refreshStoredPinLength(int userId) {
        checkPasswordHavePermission();
        synchronized (mSpManager) {
            PasswordMetrics passwordMetrics = getUserPasswordMetrics(userId);
            if (passwordMetrics != null) {
                final long protectorId = getCurrentLskfBasedProtectorId(userId);
                return mSpManager.refreshPinLengthOnDisk(passwordMetrics, protectorId, userId);
            } else {
                Log.w(TAG, "PasswordMetrics is not available");
                return false;
            }
        }
    }

    /**
     * This API is cached; whenever the result would change,
     * {@link com.android.internal.widget.LockPatternUtils#invalidateCredentialTypeCache}
     * must be called.
     */
    @Override
    public int getCredentialType(int userId) {
        checkPasswordHavePermission();
        return getCredentialTypeInternal(userId);
    }

    /**
     * Returns the credential type of the user, can be one of {@link #CREDENTIAL_TYPE_NONE},
     * {@link #CREDENTIAL_TYPE_PATTERN}, {@link #CREDENTIAL_TYPE_PIN} and
     * {@link #CREDENTIAL_TYPE_PASSWORD}
     */
    private int getCredentialTypeInternal(int userId) {
        if (isSpecialUserId(userId)) {
            return mSpManager.getSpecialUserCredentialType(userId);
        }
        synchronized (mSpManager) {
            final long protectorId = getCurrentLskfBasedProtectorId(userId);
            if (protectorId == SyntheticPasswordManager.NULL_PROTECTOR_ID) {
                // Only possible for new users during early boot (before onThirdPartyAppsStarted())
                return CREDENTIAL_TYPE_NONE;
            }
            int rawType = mSpManager.getCredentialType(protectorId, userId);
            if (rawType != CREDENTIAL_TYPE_PASSWORD_OR_PIN) {
                return rawType;
            }
            return pinOrPasswordQualityToCredentialType(getKeyguardStoredQuality(userId));
        }
    }

    private boolean isUserSecure(int userId) {
        return getCredentialTypeInternal(userId) != CREDENTIAL_TYPE_NONE;
    }

    @VisibleForTesting /** Note: this method is overridden in unit tests */
    void setKeystorePassword(byte[] password, int userHandle) {
        AndroidKeyStoreMaintenance.onUserPasswordChanged(userHandle, password);
    }

    @VisibleForTesting /** Note: this method is overridden in unit tests */
    void initKeystoreSuperKeys(@UserIdInt int userId, SyntheticPassword sp, boolean allowExisting) {
        final byte[] password = sp.deriveKeyStorePassword();
        try {
            int res = AndroidKeyStoreMaintenance.initUserSuperKeys(userId, password, allowExisting);
            if (res != 0) {
                throw new IllegalStateException("Failed to initialize Keystore super keys for user "
                        + userId);
            }
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    private void unlockKeystore(int userId, SyntheticPassword sp) {
        mKeyStoreAuthorization.onDeviceUnlocked(userId, sp.deriveKeyStorePassword());
    }

    @VisibleForTesting /** Note: this method is overridden in unit tests */
    protected LockscreenCredential getDecryptedPasswordForTiedProfile(int userId)
            throws KeyStoreException, UnrecoverableKeyException,
            NoSuchAlgorithmException, NoSuchPaddingException, InvalidKeyException,
            InvalidAlgorithmParameterException, IllegalBlockSizeException, BadPaddingException,
            CertificateException, IOException {
        Slogf.d(TAG, "Decrypting password for tied profile %d", userId);
        byte[] storedData = mStorage.readChildProfileLock(userId);
        if (storedData == null) {
            throw new FileNotFoundException("Child profile lock file not found");
        }
        byte[] iv = Arrays.copyOfRange(storedData, 0, PROFILE_KEY_IV_SIZE);
        byte[] encryptedPassword = Arrays.copyOfRange(storedData, PROFILE_KEY_IV_SIZE,
                storedData.length);
        byte[] decryptionResult;
        SecretKey decryptionKey = (SecretKey) mKeyStore.getKey(
                PROFILE_KEY_NAME_DECRYPT + userId, null);

        Cipher cipher = Cipher.getInstance(KeyProperties.KEY_ALGORITHM_AES + "/"
                + KeyProperties.BLOCK_MODE_GCM + "/" + KeyProperties.ENCRYPTION_PADDING_NONE);

        cipher.init(Cipher.DECRYPT_MODE, decryptionKey, new GCMParameterSpec(128, iv));
        decryptionResult = cipher.doFinal(encryptedPassword);
        LockscreenCredential credential = LockscreenCredential.createUnifiedProfilePassword(
                decryptionResult);
        Arrays.fill(decryptionResult, (byte) 0);
        try {
            long parentSid = getGateKeeperService().getSecureUserId(
                    mUserManager.getProfileParent(userId).id);
            mUnifiedProfilePasswordCache.storePassword(userId, credential, parentSid);
        } catch (RemoteException e) {
            Slogf.w(TAG, "Failed to talk to GateKeeper service", e);
        }
        return credential;
    }

    private void unlockChildProfile(int profileHandle) {
        try {
            doVerifyCredential(getDecryptedPasswordForTiedProfile(profileHandle),
                    profileHandle, null /* progressCallback */, 0 /* flags */);
        } catch (UnrecoverableKeyException | InvalidKeyException | KeyStoreException
                | NoSuchAlgorithmException | NoSuchPaddingException
                | InvalidAlgorithmParameterException | IllegalBlockSizeException
                | BadPaddingException | CertificateException | IOException e) {
            if (e instanceof FileNotFoundException) {
                Slog.i(TAG, "Child profile key not found");
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
    private void unlockUser(@UserIdInt int userId) {
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
            mActivityManager.unlockUser2(userId, listener);
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
                    unlockChildProfile(profile.id);
                } else {
                    try {
                        // Profile not ready for unlock yet, but decrypt the unified challenge now
                        // so it goes into the cache
                        getDecryptedPasswordForTiedProfile(profile.id);
                    } catch (GeneralSecurityException | IOException e) {
                        Slog.d(TAG, "Cache unified profile password failed", e);
                    }
                }
            }
            // Now we have unlocked the parent user and attempted to unlock the profile we should
            // show notifications if the profile is still locked.
            if (!alreadyUnlocked) {
                final long ident = clearCallingIdentity();
                try {
                    maybeShowEncryptionNotificationForUser(profile.id, "parent unlocked");
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
     * Synchronize all profile's challenge of the given user if it's unified: tie or clear them
     * depending on the parent user's secure state.
     *
     * When clearing tied challenges, a pre-computed password table for profiles are required, since
     * changing password for profiles requires existing password, and existing passwords can only be
     * computed before the parent user's password is cleared.
     *
     * Strictly this is a recursive function, since setLockCredentialInternal ends up calling this
     * method again on profiles. However the recursion is guaranteed to terminate as this method
     * terminates when the user is a profile that shares lock credentials with parent.
     * (e.g. managed and clone profile).
     */
    private void synchronizeUnifiedChallengeForProfiles(int userId,
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
                    // We use cached profile password computed before clearing the parent's
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

    /**
     * Send credentials for user {@code userId} to {@link RecoverableKeyStoreManager} during an
     * unlock operation.
     */
    private void sendCredentialsOnUnlockIfRequired(LockscreenCredential credential, int userId) {
        // Don't send credentials during the special user flow.
        if (isSpecialUserId(userId)) {
            return;
        }

        // Don't send empty credentials on unlock.
        if (credential.isNone()) {
            return;
        }

        // A profile with a unified lock screen stores a randomly generated credential, so skip it.
        // Its parent will send credentials for the profile, as it stores the unified lock
        // credential.
        if (isProfileWithUnifiedLock(userId)) {
            return;
        }

        // Send credentials for the user and any child profiles that share its lock screen.
        for (int profileId : getProfilesWithSameLockScreen(userId)) {
            mRecoverableKeyStoreManager.lockScreenSecretAvailable(
                    credential.getType(), credential.getCredential(), profileId);
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
        credential.validateBasicRequirements();

        final long identity = Binder.clearCallingIdentity();
        try {
            enforceFrpNotActive();
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
                // Make sure the profile doesn't get locked straight after setting challenge.
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
            final long oldProtectorId = getCurrentLskfBasedProtectorId(userId);
            AuthenticationResult authResult = mSpManager.unlockLskfBasedProtector(
                    getGateKeeperService(), oldProtectorId, savedCredential, userId, null);
            VerifyCredentialResponse response = authResult.gkResponse;
            SyntheticPassword sp = authResult.syntheticPassword;

            if (sp == null) {
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

            onSyntheticPasswordUnlocked(userId, sp);
            setLockCredentialWithSpLocked(credential, sp, userId);
            sendCredentialsOnChangeIfRequired(credential, userId, isLockTiedToParent);
            return true;
        }
    }

    private void onPostPasswordChanged(LockscreenCredential newCredential, int userId) {
        updatePasswordHistory(newCredential, userId);
        mContext.getSystemService(TrustManager.class).reportEnabledTrustAgentsChanged(userId);
        sendMainUserCredentialChangedNotificationIfNeeded(userId);
    }

    /**
     * Store the hash of the new password in the password history list, if device policy enforces
     * a password history requirement.
     *
     * This must not be called while the mSpManager lock is held, as this calls into
     * DevicePolicyManagerService to get the requested password history length.
     */
    private void updatePasswordHistory(LockscreenCredential password, int userHandle) {
        if (password.isNone()) {
            return;
        }
        if (password.isPattern()) {
            // Do not keep track of historical patterns
            return;
        }
        // Add the password to the password history.
        String passwordHistory = getString(
                LockPatternUtils.PASSWORD_HISTORY_KEY, /* defaultValue= */ null, userHandle);
        if (passwordHistory == null) {
            passwordHistory = "";
        }
        int passwordHistoryLength = getRequestedPasswordHistoryLength(userHandle);
        if (passwordHistoryLength == 0) {
            passwordHistory = "";
        } else {
            Slogf.d(TAG, "Adding new password to password history for user %d", userHandle);
            final byte[] hashFactor = getHashFactor(password, userHandle);
            final byte[] salt = getSalt(userHandle).getBytes();
            String hash = password.passwordToHistoryHash(salt, hashFactor);
            if (hash == null) {
                // This should never happen, as all information needed to compute the hash should be
                // available.  In particular, unwrapping the SP in getHashFactor() should always
                // succeed, as we're using the LSKF that was just set.
                Slog.e(TAG, "Failed to compute password hash; password history won't be updated");
                return;
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
            salt = SecureRandomUtils.randomLong();
            setLong(LockPatternUtils.LOCK_PASSWORD_SALT_KEY, salt, userId);
        }
        return Long.toHexString(salt);
    }

    private int getRequestedPasswordHistoryLength(int userId) {
        return mInjector.getDevicePolicyManager().getPasswordHistoryLength(null, userId);
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

    @VisibleForTesting /** Note: this method is overridden in unit tests */
    protected boolean isCredentialSharableWithParent(int userId) {
        return getUserManagerFromCache(userId).isCredentialSharableWithParent();
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
                AuthenticationResult authResult = mSpManager.unlockWeakTokenBasedProtector(
                        getGateKeeperService(), handle, token, userId);
                if (authResult.syntheticPassword == null) {
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
    protected void tieProfileLockToParent(int profileUserId, int parentUserId,
            LockscreenCredential password) {
        Slogf.i(TAG, "Tying lock for profile user %d to parent user %d", profileUserId,
                parentUserId);
        final byte[] iv;
        final byte[] ciphertext;
        final long parentSid;
        try {
            parentSid = getGateKeeperService().getSecureUserId(parentUserId);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to talk to GateKeeper service", e);
        }

        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES);
            keyGenerator.init(new SecureRandom());
            SecretKey secretKey = keyGenerator.generateKey();
            try {
                mKeyStore.setEntry(
                        PROFILE_KEY_NAME_ENCRYPT + profileUserId,
                        new KeyStore.SecretKeyEntry(secretKey),
                        new KeyProtection.Builder(KeyProperties.PURPOSE_ENCRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build());
                mKeyStore.setEntry(
                        PROFILE_KEY_NAME_DECRYPT + profileUserId,
                        new KeyStore.SecretKeyEntry(secretKey),
                        new KeyProtection.Builder(KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setUserAuthenticationRequired(true)
                                .setBoundToSpecificSecureUserId(parentSid)
                                .setUserAuthenticationValidityDurationSeconds(30)
                                .build());
                // Key imported, obtain a reference to it.
                SecretKey keyStoreEncryptionKey = (SecretKey) mKeyStore.getKey(
                        PROFILE_KEY_NAME_ENCRYPT + profileUserId, null);
                Cipher cipher = Cipher.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES + "/" + KeyProperties.BLOCK_MODE_GCM + "/"
                                + KeyProperties.ENCRYPTION_PADDING_NONE);
                cipher.init(Cipher.ENCRYPT_MODE, keyStoreEncryptionKey);
                ciphertext = cipher.doFinal(password.getCredential());
                iv = cipher.getIV();
            } finally {
                // The original key can now be discarded.
                mKeyStore.deleteEntry(PROFILE_KEY_NAME_ENCRYPT + profileUserId);
            }
        } catch (UnrecoverableKeyException
                | BadPaddingException | IllegalBlockSizeException | KeyStoreException
                | NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Failed to encrypt key", e);
        }
        if (iv.length != PROFILE_KEY_IV_SIZE) {
            throw new IllegalArgumentException("Invalid iv length: " + iv.length);
        }
        mStorage.writeChildProfileLock(profileUserId, ArrayUtils.concat(iv, ciphertext));
    }

    private void setCeStorageProtection(@UserIdInt int userId, SyntheticPassword sp) {
        final byte[] secret = sp.deriveFileBasedEncryptionKey();
        final long callingId = Binder.clearCallingIdentity();
        try {
            mStorageManager.setCeStorageProtection(userId, secret);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to protect CE key for user " + userId, e);
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    private boolean isCeStorageUnlocked(int userId) {
        try {
            return mStorageManager.isCeStorageUnlocked(userId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error checking whether CE storage is unlocked", e);
            return false;
        }
    }

    /**
     * Unlocks the user's CE (credential-encrypted) storage if it's not already unlocked.
     * <p>
     * This method doesn't throw exceptions because it is called opportunistically whenever a user
     * is started.  Whether it worked or not can be detected by whether the key got unlocked or not.
     */
    private void unlockCeStorage(@UserIdInt int userId, SyntheticPassword sp) {
        if (isCeStorageUnlocked(userId)) {
            Slogf.d(TAG, "CE storage for user %d is already unlocked", userId);
            return;
        }
        final String userType = isUserSecure(userId) ? "secured" : "unsecured";
        final byte[] secret = sp.deriveFileBasedEncryptionKey();
        try {
            mStorageManager.unlockCeStorage(userId, secret);
            Slogf.i(TAG, "Unlocked CE storage for %s user %d", userType, userId);
        } catch (RemoteException e) {
            Slogf.wtf(TAG, e, "Failed to unlock CE storage for %s user %d", userType, userId);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    @Override
    public void unlockUserKeyIfUnsecured(@UserIdInt int userId) {
        checkPasswordReadPermission();
        synchronized (mSpManager) {
            if (isCeStorageUnlocked(userId)) {
                Slogf.d(TAG, "CE storage for user %d is already unlocked", userId);
                // This method actually does more than unlock CE storage.  However, if CE storage is
                // already unlocked, then the other parts must have already been done too.
                return;
            }
            if (isUserSecure(userId)) {
                Slogf.d(TAG, "Not unlocking CE storage for user %d yet because user is secured",
                        userId);
                return;
            }
            Slogf.i(TAG, "Unwrapping synthetic password for unsecured user %d", userId);
            AuthenticationResult result = mSpManager.unlockLskfBasedProtector(
                    getGateKeeperService(), getCurrentLskfBasedProtectorId(userId),
                    LockscreenCredential.createNone(), userId, null);
            if (result.syntheticPassword == null) {
                Slogf.wtf(TAG, "Failed to unwrap synthetic password for unsecured user %d", userId);
                return;
            }
            onSyntheticPasswordUnlocked(userId, result.syntheticPassword);
            if (FIX_UNLOCKED_DEVICE_REQUIRED_KEYS) {
                unlockKeystore(userId, result.syntheticPassword);
            }
            unlockCeStorage(userId, result.syntheticPassword);
        }
    }

    @Override
    public void resetKeyStore(int userId) {
        checkWritePermission();
        Slogf.d(TAG, "Resetting keystore for user %d", userId);
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
                    tieProfileLockToParent(piUserId, userId, piUserDecryptedPassword);
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
        final long identity = Binder.clearCallingIdentity();
        try {
            return doVerifyCredential(credential, userId, progressCallback, 0 /* flags */);
        } finally {
            Binder.restoreCallingIdentity(identity);
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

    /**
     * Verify user credential and unlock the user.
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
        if (userId == USER_FRP && Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.DEVICE_PROVISIONED, 0) != 0) {
            Slog.e(TAG, "FRP credential can only be verified prior to provisioning.");
            return VerifyCredentialResponse.ERROR;
        }
        if (userId == USER_REPAIR_MODE && !LockPatternUtils.isRepairModeActive(mContext)) {
            Slog.e(TAG, "Repair mode is not active on the device.");
            return VerifyCredentialResponse.ERROR;
        }
        Slogf.i(TAG, "Verifying lockscreen credential for user %d", userId);

        final AuthenticationResult authResult;
        VerifyCredentialResponse response;

        synchronized (mSpManager) {
            if (isSpecialUserId(userId)) {
                response = mSpManager.verifySpecialUserCredential(userId, getGateKeeperService(),
                        credential, progressCallback);
                if (android.security.Flags.frpEnforcement() && response.isMatched()
                        && userId == USER_FRP) {
                    mStorage.deactivateFactoryResetProtectionWithoutSecret();
                }
                return response;
            }

            long protectorId = getCurrentLskfBasedProtectorId(userId);
            authResult = mSpManager.unlockLskfBasedProtector(
                    getGateKeeperService(), protectorId, credential, userId, progressCallback);
            response = authResult.gkResponse;

            if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
                if ((flags & VERIFY_FLAG_WRITE_REPAIR_MODE_PW) != 0) {
                    if (!mSpManager.writeRepairModeCredentialLocked(protectorId, userId)) {
                        Slog.e(TAG, "Failed to write repair mode credential");
                        return VerifyCredentialResponse.ERROR;
                    }
                }
                // credential has matched
                mBiometricDeferredQueue.addPendingLockoutResetForUser(userId,
                        authResult.syntheticPassword.deriveGkPassword());
            }
        }
        if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK) {
            Slogf.i(TAG, "Successfully verified lockscreen credential for user %d", userId);
            onCredentialVerified(authResult.syntheticPassword,
                    PasswordMetrics.computeForCredential(credential), userId);
            if ((flags & VERIFY_FLAG_REQUEST_GK_PW_HANDLE) != 0) {
                final long gkHandle = storeGatekeeperPasswordTemporarily(
                        authResult.syntheticPassword.deriveGkPassword());
                response = new VerifyCredentialResponse.Builder()
                        .setGatekeeperPasswordHandle(gkHandle)
                        .build();
            }
            sendCredentialsOnUnlockIfRequired(credential, userId);
        } else if (response.getResponseCode() == VerifyCredentialResponse.RESPONSE_RETRY) {
            if (response.getTimeout() > 0) {
                requireStrongAuth(STRONG_AUTH_REQUIRED_AFTER_LOCKOUT, userId);
            }
        }
        if (reportPrimaryAuthAttempts()) {
            final boolean success =
                    response.getResponseCode() == VerifyCredentialResponse.RESPONSE_OK;
            notifyLockSettingsStateListeners(success, userId);
        }
        return response;
    }

    private void notifyLockSettingsStateListeners(boolean success, int userId) {
        for (LockSettingsStateListener listener : mLockSettingsStateListeners) {
            if (success) {
                listener.onAuthenticationSucceeded(userId);
            } else {
                listener.onAuthenticationFailed(userId);
            }
        }
    }

    @Override
    public VerifyCredentialResponse verifyTiedProfileChallenge(LockscreenCredential credential,
            int userId, @LockPatternUtils.VerifyFlag int flags) {
        checkPasswordReadPermission();
        Slogf.i(TAG, "Verifying tied profile challenge for user %d", userId);

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
            // Unlock profile with unified lock
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
     * Keep track of the given user's latest password metric. This should be called
     * when the user is authenticating or when a new password is being set. In comparison,
     * {@link #notifyPasswordChanged} only needs to be called when the user changes the password.
     */
    private void setUserPasswordMetrics(LockscreenCredential password, @UserIdInt int userHandle) {
        synchronized (this) {
            mUserPasswordMetrics.put(userHandle, PasswordMetrics.computeForCredential(password));
        }
    }

    /**
     * Returns the PasswordMetrics for the current user
     * @param userHandle The id of the user for which we return the password metrics object
     * @return passwordmetrics for the user or null if not available
     */
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

    private @Nullable PasswordMetrics loadPasswordMetrics(SyntheticPassword sp, int userHandle) {
        synchronized (mSpManager) {
            if (!isUserSecure(userHandle)) {
                return null;
            }
            return mSpManager.getPasswordMetrics(sp, getCurrentLskfBasedProtectorId(userHandle),
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

    private void createNewUser(@UserIdInt int userId, int userSerialNumber) {

        // Delete all Keystore keys for userId, just in case any were left around from a removed
        // user with the same userId.  This should be unnecessary, but we've been doing this for a
        // long time, so for now we keep doing it just in case it's ever important.  Don't wait
        // until initKeystoreSuperKeys() to do this; that can be delayed if the user is being
        // created during early boot, and maybe something will use Keystore before then.
        if (FIX_UNLOCKED_DEVICE_REQUIRED_KEYS) {
            AndroidKeyStoreMaintenance.onUserAdded(userId);
        }

        synchronized (mUserCreationAndRemovalLock) {
            // During early boot, don't actually create the synthetic password yet, but rather
            // automatically delay it to later.  We do this because protecting the synthetic
            // password requires the Weaver HAL if the device supports it, and some devices don't
            // make Weaver available until fairly late in the boot process.  This logic ensures a
            // consistent flow across all devices, regardless of their Weaver implementation.
            if (!mThirdPartyAppsStarted) {
                Slogf.i(TAG, "Delaying locksettings state creation for user %d until third-party " +
                        "apps are started", userId);
                mEarlyCreatedUsers.put(userId, userSerialNumber);
                mEarlyRemovedUsers.delete(userId);
                return;
            }
            removeStateForReusedUserIdIfNecessary(userId, userSerialNumber);
            initializeSyntheticPassword(userId);
        }
    }

    private void removeUser(@UserIdInt int userId) {
        synchronized (mUserCreationAndRemovalLock) {
            // During early boot, don't actually remove the LSS state yet, but rather automatically
            // delay it to later.  We do this because deleting synthetic password protectors
            // requires the Weaver HAL if the device supports it, and some devices don't make Weaver
            // available until fairly late in the boot process.  This logic ensures a consistent
            // flow across all devices, regardless of their Weaver implementation.
            if (!mThirdPartyAppsStarted) {
                Slogf.i(TAG, "Delaying locksettings state removal for user %d until third-party " +
                        "apps are started", userId);
                if (mEarlyCreatedUsers.indexOfKey(userId) >= 0) {
                    mEarlyCreatedUsers.delete(userId);
                } else {
                    mEarlyRemovedUsers.put(userId, -1 /* unused */);
                }
                return;
            }
            Slogf.i(TAG, "Removing state for user %d", userId);
            removeUserState(userId);
        }
    }

    private void removeUserState(@UserIdInt int userId) {
        removeBiometricsForUser(userId);
        mSpManager.removeUser(getGateKeeperService(), userId);
        mStrongAuth.removeUser(userId);

        AndroidKeyStoreMaintenance.onUserRemoved(userId);
        mUnifiedProfilePasswordCache.removePassword(userId);

        gateKeeperClearSecureUserId(userId);
        removeKeystoreProfileKey(userId);
        // Clean up storage last, so that removeStateForReusedUserIdIfNecessary() can assume that no
        // USER_SERIAL_NUMBER_KEY means user is fully removed.
        mStorage.removeUser(userId);
    }

    private void removeKeystoreProfileKey(int targetUserId) {
        final String encryptAlias = PROFILE_KEY_NAME_ENCRYPT + targetUserId;
        final String decryptAlias = PROFILE_KEY_NAME_DECRYPT + targetUserId;
        try {
            if (mKeyStore.containsAlias(encryptAlias) || mKeyStore.containsAlias(decryptAlias)) {
                Slogf.i(TAG, "Removing keystore profile key for user %d", targetUserId);
                mKeyStore.deleteEntry(encryptAlias);
                mKeyStore.deleteEntry(decryptAlias);
            }
        } catch (KeyStoreException e) {
            // We have tried our best to remove the key.
            Slogf.e(TAG, e, "Error removing keystore profile key for user %d", targetUserId);
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
        checkWritePermission();
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
        checkWritePermission();
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
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();

        // Don't log arguments other than the first one (the command name), since they might contain
        // secrets that must not be written to the log.
        Slogf.i(TAG, "Executing shell command '%s'; callingPid=%d, callingUid=%d",
                ArrayUtils.isEmpty(args) ? "" : args[0], callingPid, callingUid);

        // The original identity is an opaque integer.
        final long origId = Binder.clearCallingIdentity();
        try {
            final LockSettingsShellCommand command =
                    new LockSettingsShellCommand(new LockPatternUtils(mContext), mContext,
                            callingPid, callingUid);
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

    /**
     * Starts a session to verify lock screen credentials provided by a remote device.
     */
    @NonNull
    public RemoteLockscreenValidationSession startRemoteLockscreenValidation() {
        return mRecoverableKeyStoreManager.startRemoteLockscreenValidation(this);
    }

    /**
     * Verifies encrypted credentials guess from a remote device.
     */
    @NonNull
    public RemoteLockscreenValidationResult
            validateRemoteLockscreen(@NonNull byte[] encryptedCredential) {
        return mRecoverableKeyStoreManager.validateRemoteLockscreen(encryptedCredential, this);
    }

    private class GateKeeperDiedRecipient implements IBinder.DeathRecipient {
        @Override
        public void binderDied() {
            mGateKeeperService.asBinder().unlinkToDeath(this, 0);
            mGateKeeperService = null;
        }
    }

    private synchronized IGateKeeperService getGateKeeperService() {
        if (mGateKeeperService != null) {
            return mGateKeeperService;
        }

        final IBinder service = ServiceManager.waitForService(Context.GATEKEEPER_SERVICE);
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

    private void onSyntheticPasswordCreated(@UserIdInt int userId, SyntheticPassword sp) {
        onSyntheticPasswordKnown(userId, sp, true);
    }

    private void onSyntheticPasswordUnlocked(@UserIdInt int userId, SyntheticPassword sp) {
        onSyntheticPasswordKnown(userId, sp, false);
    }

    private void onSyntheticPasswordKnown(
            @UserIdInt int userId, SyntheticPassword sp, boolean justCreated) {
        if (mInjector.isGsiRunning()) {
            Slog.w(TAG, "Running in GSI; skipping calls to AuthSecret and RebootEscrow");
            return;
        }

        mRebootEscrowManager.callToRebootEscrowIfNeeded(
                userId, sp.getVersion(), sp.getSyntheticPassword());
        callToAuthSecretIfNeeded(userId, sp, justCreated);
    }

    /**
     * Handles generation, storage, and sending of the vendor auth secret. Here we try to retrieve
     * the auth secret to send it to the auth secret HAL, generate a fresh secret if need be, store
     * it encrypted on disk so that the given user can unlock it in future, and stash it in memory
     * so that when future users are created they can also unlock it.
     *
     * <p>Called whenever the SP of a user is available, except in GSI.
     */
    private void callToAuthSecretIfNeeded(
            @UserIdInt int userId, SyntheticPassword sp, boolean justCreated) {
        if (mAuthSecretService == null) {
            // If there's no IAuthSecret service, we don't need to maintain a auth secret
            return;
        }
        // User may be partially created, so use the internal user manager interface
        final UserManagerInternal userManagerInternal = mInjector.getUserManagerInternal();
        final UserInfo userInfo = userManagerInternal.getUserInfo(userId);
        if (userInfo == null) {
            // User may be partially deleted, skip this.
            return;
        }
        final byte[] authSecret;
        if (!mInjector.isHeadlessSystemUserMode()) {
            // On non-headless systems, the auth secret is derived from user 0's
            // SP, and only user 0 passes it to the HAL.
            if (userId != USER_SYSTEM) {
                return;
            }
            authSecret = sp.deriveVendorAuthSecret();
        } else if (!mInjector.isMainUserPermanentAdmin() || !userInfo.isFull()) {
            // Only full users can receive or pass on the auth secret.
            // If there is no main permanent admin user, we don't try to create or send
            // an auth secret, since there may sometimes be no full users.
            return;
        } else if (justCreated) {
            if (userInfo.isMain()) {
                // The first user is just being created, so we create a new auth secret
                // at the same time.
                Slog.i(TAG, "Generating new vendor auth secret and storing for user: " + userId);
                authSecret = SecureRandomUtils.randomBytes(HEADLESS_VENDOR_AUTH_SECRET_LENGTH);
                // Store it in memory, for when new users are created.
                synchronized (mHeadlessAuthSecretLock) {
                    mAuthSecret = authSecret;
                }
            } else {
                // A new user is being created. Another user should already have logged in at
                // this point, and therefore the auth secret should be stored in memory.
                synchronized (mHeadlessAuthSecretLock) {
                    authSecret = mAuthSecret;
                }
                if (authSecret == null) {
                    Slog.e(TAG, "Creating non-main user " + userId
                            + " but vendor auth secret is not in memory");
                    return;
                }
            }
            // Store the auth secret encrypted using the user's SP (which was just created).
            mSpManager.writeVendorAuthSecret(authSecret, sp, userId);
        } else {
            // The user already exists, so the auth secret should be stored encrypted
            // with that user's SP.
            authSecret = mSpManager.readVendorAuthSecret(sp, userId);
            if (authSecret == null) {
                Slog.e(TAG, "Unable to read vendor auth secret for user: " + userId);
                return;
            }
            // Store it in memory, for when new users are created.
            synchronized (mHeadlessAuthSecretLock) {
                mAuthSecret = authSecret;
            }
        }
        Slog.i(TAG, "Sending vendor auth secret to IAuthSecret HAL as user: " + userId);
        try {
            mAuthSecretService.setPrimaryUserCredential(authSecret);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed to send vendor auth secret to IAuthSecret HAL", e);
        }
    }

    /**
     * Creates the synthetic password (SP) for the given user, protects it with an empty LSKF, and
     * protects the user's CE storage key and Keystore super keys with keys derived from the SP.
     *
     * <p>This is called just once in the lifetime of the user: at user creation time (possibly
     * delayed until the time when Weaver is guaranteed to be available), or when upgrading from
     * Android 13 or earlier where users with no LSKF didn't necessarily have an SP.
     */
    @VisibleForTesting
    SyntheticPassword initializeSyntheticPassword(int userId) {
        synchronized (mSpManager) {
            Slogf.i(TAG, "Initializing synthetic password for user %d", userId);
            Preconditions.checkState(getCurrentLskfBasedProtectorId(userId) ==
                    SyntheticPasswordManager.NULL_PROTECTOR_ID,
                    "Cannot reinitialize SP");

            final SyntheticPassword sp = mSpManager.newSyntheticPassword(userId);
            final long protectorId = mSpManager.createLskfBasedProtector(getGateKeeperService(),
                    LockscreenCredential.createNone(), sp, userId);
            setCurrentLskfBasedProtectorId(protectorId, userId);
            setCeStorageProtection(userId, sp);
            if (FIX_UNLOCKED_DEVICE_REQUIRED_KEYS) {
                initKeystoreSuperKeys(userId, sp, /* allowExisting= */ false);
            }
            onSyntheticPasswordCreated(userId, sp);
            Slogf.i(TAG, "Successfully initialized synthetic password for user %d", userId);
            return sp;
        }
    }

    @VisibleForTesting
    long getCurrentLskfBasedProtectorId(int userId) {
        return getLong(CURRENT_LSKF_BASED_PROTECTOR_ID_KEY,
                SyntheticPasswordManager.NULL_PROTECTOR_ID, userId);
    }

    private void setCurrentLskfBasedProtectorId(long newProtectorId, int userId) {
        final long oldProtectorId = getCurrentLskfBasedProtectorId(userId);
        setLong(CURRENT_LSKF_BASED_PROTECTOR_ID_KEY, newProtectorId, userId);
        setLong(PREV_LSKF_BASED_PROTECTOR_ID_KEY, oldProtectorId, userId);
        setLong(LSKF_LAST_CHANGED_TIME_KEY, System.currentTimeMillis(), userId);
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
                handle = SecureRandomUtils.randomLong();
            }
            mGatekeeperPasswords.put(handle, gatekeeperPassword);
        }

        final long finalHandle = handle;
        mHandler.postDelayed(() -> {
            synchronized (mGatekeeperPasswords) {
                if (mGatekeeperPasswords.get(finalHandle) != null) {
                    Slogf.d(TAG, "Cached Gatekeeper password with handle %016x has expired",
                            finalHandle);
                    mGatekeeperPasswords.remove(finalHandle);
                }
            }
        }, GK_PW_HANDLE_STORE_DURATION_MS);

        return handle;
    }

    private void onCredentialVerified(SyntheticPassword sp, @Nullable PasswordMetrics metrics,
            int userId) {

        if (metrics != null) {
            synchronized (this) {
                mUserPasswordMetrics.put(userId,  metrics);
            }
        }

        unlockKeystore(userId, sp);

        unlockCeStorage(userId, sp);

        unlockUser(userId);

        activateEscrowTokens(sp, userId);

        if (isCredentialSharableWithParent(userId)) {
            if (getSeparateProfileChallengeEnabledInternal(userId)) {
                setDeviceUnlockedForUser(userId);
            } else {
                // Here only clear StrongAuthFlags for a profile that has a unified challenge.
                // StrongAuth for a profile with a separate challenge is handled differently and
                // is cleared after the user successfully confirms the separate challenge to enter
                // the profile. StrongAuth for the full user (e.g. userId 0) is also handled
                // separately by Keyguard.
                mStrongAuth.reportUnlock(userId);
            }
        }

        mStrongAuth.reportSuccessfulStrongAuthUnlock(userId);

        onSyntheticPasswordUnlocked(userId, sp);
    }

    private void setDeviceUnlockedForUser(int userId) {
        final TrustManager trustManager = mContext.getSystemService(TrustManager.class);
        trustManager.setDeviceLockedForUser(userId, false);
    }

    /**
     * Changes the user's LSKF by creating an LSKF-based protector that uses the new LSKF (which may
     * be empty) and replacing the old LSKF-based protector with it.  The SP itself is not changed.
     * <p>
     * Also maintains the invariants described in {@link SyntheticPasswordManager} by enrolling /
     * deleting the synthetic password into Gatekeeper as the LSKF is set / cleared, and asking
     * Keystore to delete the user's auth-bound keys when the LSKF is cleared.
     */
    @GuardedBy("mSpManager")
    private long setLockCredentialWithSpLocked(LockscreenCredential credential,
            SyntheticPassword sp, int userId) {
        Slogf.i(TAG, "Changing lockscreen credential of user %d; newCredentialType=%s\n",
                userId, LockPatternUtils.credentialTypeToString(credential.getType()));
        final int savedCredentialType = getCredentialTypeInternal(userId);
        final long oldProtectorId = getCurrentLskfBasedProtectorId(userId);
        final long newProtectorId = mSpManager.createLskfBasedProtector(getGateKeeperService(),
                credential, sp, userId);
        final Map<Integer, LockscreenCredential> profilePasswords;
        if (!credential.isNone()) {
            // not needed by synchronizeUnifiedChallengeForProfiles()
            profilePasswords = null;

            if (!mSpManager.hasSidForUser(userId)) {
                mSpManager.newSidForUser(getGateKeeperService(), sp, userId);
                mSpManager.verifyChallenge(getGateKeeperService(), sp, 0L, userId);
                if (!FIX_UNLOCKED_DEVICE_REQUIRED_KEYS) {
                    setKeystorePassword(sp.deriveKeyStorePassword(), userId);
                }
            }
        } else {
            // Cache all profile password if they use unified challenge. This will later be used to
            // clear the profile's password in synchronizeUnifiedChallengeForProfiles().
            profilePasswords = getDecryptedPasswordsForAllTiedProfiles(userId);

            mSpManager.clearSidForUser(userId);
            gateKeeperClearSecureUserId(userId);
            unlockCeStorage(userId, sp);
            unlockKeystore(userId, sp);
            if (FIX_UNLOCKED_DEVICE_REQUIRED_KEYS) {
                AndroidKeyStoreMaintenance.onUserLskfRemoved(userId);
            } else {
                setKeystorePassword(null, userId);
            }
            removeBiometricsForUser(userId);
        }
        setCurrentLskfBasedProtectorId(newProtectorId, userId);
        LockPatternUtils.invalidateCredentialTypeCache();
        synchronizeUnifiedChallengeForProfiles(userId, profilePasswords);

        setUserPasswordMetrics(credential, userId);
        mUnifiedProfilePasswordCache.removePassword(userId);
        if (savedCredentialType != CREDENTIAL_TYPE_NONE) {
            mSpManager.destroyAllWeakTokenBasedProtectors(userId);
        }

        if (profilePasswords != null) {
            for (Map.Entry<Integer, LockscreenCredential> entry : profilePasswords.entrySet()) {
                entry.getValue().zeroize();
            }
        }
        mSpManager.destroyLskfBasedProtector(oldProtectorId, userId);
        Slogf.i(TAG, "Successfully changed lockscreen credential of user %d", userId);
        return newProtectorId;
    }

    private void sendMainUserCredentialChangedNotificationIfNeeded(int userId) {
        if (!android.security.Flags.frpEnforcement()) {
            return;
        }

        if (userId != mInjector.getUserManagerInternal().getMainUserId()) {
            return;
        }

        sendBroadcast(new Intent(ACTION_MAIN_USER_LOCKSCREEN_KNOWLEDGE_FACTOR_CHANGED),
                UserHandle.of(userId), CONFIGURE_FACTORY_RESET_PROTECTION);
    }

    @VisibleForTesting
    void sendBroadcast(Intent intent, UserHandle userHandle, String permission) {
        mContext.sendBroadcastAsUser(intent, userHandle, permission, /* options */ null);
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
            Slogf.d(TAG, "Getting password history hash factor for user %d", userId);
            if (isProfileWithUnifiedLock(userId)) {
                try {
                    currentCredential = getDecryptedPasswordForTiedProfile(userId);
                } catch (Exception e) {
                    Slog.e(TAG, "Failed to get unified profile password", e);
                    return null;
                }
            }
            synchronized (mSpManager) {
                long protectorId = getCurrentLskfBasedProtectorId(userId);
                AuthenticationResult auth = mSpManager.unlockLskfBasedProtector(
                        getGateKeeperService(), protectorId, currentCredential, userId, null);
                if (auth.syntheticPassword == null) {
                    Slog.w(TAG, "Current credential is incorrect");
                    return null;
                }
                return auth.syntheticPassword.derivePasswordHashFactor();
            }
        } finally {
            scheduleGc();
        }
    }

    private long addEscrowToken(@NonNull byte[] token, @TokenType int type, int userId,
            @NonNull EscrowTokenStateChangeCallback callback) {
        Slogf.i(TAG, "Adding escrow token for user %d", userId);
        synchronized (mSpManager) {
            // If the user has no LSKF, then the token can be activated immediately.  Otherwise, the
            // token can't be activated until the SP is unlocked by another protector (normally the
            // LSKF-based one).
            SyntheticPassword sp = null;
            if (!isUserSecure(userId)) {
                long protectorId = getCurrentLskfBasedProtectorId(userId);
                sp = mSpManager.unlockLskfBasedProtector(getGateKeeperService(), protectorId,
                        LockscreenCredential.createNone(), userId, null).syntheticPassword;
            }
            disableEscrowTokenOnNonManagedDevicesIfNeeded(userId);
            if (!mSpManager.hasEscrowData(userId)) {
                throw new SecurityException("Escrow token is disabled on the current user");
            }
            long handle = mSpManager.addPendingToken(token, type, userId, callback);
            if (sp != null) {
                // Activate the token immediately
                Slogf.i(TAG, "Immediately activating escrow token %016x", handle);
                mSpManager.createTokenBasedProtector(handle, sp, userId);
            } else {
                Slogf.i(TAG, "Escrow token %016x will be activated when user is unlocked", handle);
            }
            return handle;
        }
    }

    private void activateEscrowTokens(SyntheticPassword sp, int userId) {
        synchronized (mSpManager) {
            disableEscrowTokenOnNonManagedDevicesIfNeeded(userId);
            for (long handle : mSpManager.getPendingTokensForUser(userId)) {
                Slogf.i(TAG, "Activating escrow token %016x for user %d", handle, userId);
                mSpManager.createTokenBasedProtector(handle, sp, userId);
            }
        }
    }

    private boolean isEscrowTokenActive(long handle, int userId) {
        synchronized (mSpManager) {
            return mSpManager.protectorExists(handle, userId);
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
            if (handle == getCurrentLskfBasedProtectorId(userId)) {
                Slog.w(TAG, "Escrow token handle equals LSKF-based protector ID");
                return false;
            }
            if (mSpManager.removePendingToken(handle, userId)) {
                return true;
            }
            if (mSpManager.protectorExists(handle, userId)) {
                mSpManager.destroyTokenBasedProtector(handle, userId);
                return true;
            } else {
                return false;
            }
        }
    }

    private boolean setLockCredentialWithToken(LockscreenCredential credential, long tokenHandle,
            byte[] token, int userId) {
        boolean result;
        credential.validateBasicRequirements();
        synchronized (mSpManager) {
            if (!mSpManager.hasEscrowData(userId)) {
                throw new SecurityException("Escrow token is disabled on the current user");
            }
            if (!isEscrowTokenActive(tokenHandle, userId)) {
                Slog.e(TAG, "Unknown or unactivated token: " + Long.toHexString(tokenHandle));
                return false;
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
                mHandler.post(() -> unlockUser(userId));
            }
            notifyPasswordChanged(credential, userId);
            notifySeparateProfileChallengeChanged(userId);
        }
        return result;
    }

    @GuardedBy("mSpManager")
    private boolean setLockCredentialWithTokenInternalLocked(LockscreenCredential credential,
            long tokenHandle, byte[] token, int userId) {
        Slogf.i(TAG, "Resetting lockscreen credential of user %d using escrow token %016x",
                userId, tokenHandle);
        final AuthenticationResult result;
        result = mSpManager.unlockTokenBasedProtector(getGateKeeperService(), tokenHandle, token,
                    userId);
        if (result.syntheticPassword == null) {
            Slog.w(TAG, "Invalid escrow token supplied");
            return false;
        }
        if (result.gkResponse.getResponseCode() != VerifyCredentialResponse.RESPONSE_OK) {
            // Most likely, an untrusted credential reset happened in the past which
            // changed the synthetic password
            Slog.e(TAG, "Obsolete token: synthetic password decrypted but it fails GK "
                    + "verification.");
            return false;
        }
        onSyntheticPasswordUnlocked(userId, result.syntheticPassword);
        setLockCredentialWithSpLocked(credential, result.syntheticPassword, userId);
        return true;
    }

    private boolean unlockUserWithToken(long tokenHandle, byte[] token, int userId) {
        AuthenticationResult authResult;
        synchronized (mSpManager) {
            Slogf.i(TAG, "Unlocking user %d using escrow token %016x", userId, tokenHandle);
            if (!mSpManager.hasEscrowData(userId)) {
                Slogf.w(TAG, "Escrow token support is disabled on user %d", userId);
                return false;
            }
            authResult = mSpManager.unlockTokenBasedProtector(getGateKeeperService(), tokenHandle,
                    token, userId);
            if (authResult.syntheticPassword == null) {
                Slog.w(TAG, "Invalid escrow token supplied");
                return false;
            }
        }

        Slogf.i(TAG, "Unlocked synthetic password for user %d using escrow token", userId);
        onCredentialVerified(authResult.syntheticPassword,
                loadPasswordMetrics(authResult.syntheticPassword, userId), userId);
        return true;
    }

    @Override
    public boolean tryUnlockWithCachedUnifiedChallenge(int userId) {
        checkPasswordReadPermission();
        try (LockscreenCredential cred = mUnifiedProfilePasswordCache.retrievePassword(userId)) {
            if (cred == null) {
                return false;
            }
            return doVerifyCredential(cred, userId, null /* progressCallback */, 0 /* flags */)
                    .getResponseCode() == VerifyCredentialResponse.RESPONSE_OK;
        }
    }

    @Override
    public void removeCachedUnifiedChallenge(int userId) {
        checkWritePermission();
        mUnifiedProfilePasswordCache.removePassword(userId);
    }

    static String timestampToString(long timestamp) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(timestamp));
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter printWriter, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, printWriter)) return;

        final long identity = Binder.clearCallingIdentity();
        try {
            dumpInternal(printWriter);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void dumpInternal(PrintWriter printWriter) {
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
                pw.println(TextUtils.formatSimple("LSKF-based SP protector ID: %016x",
                        getCurrentLskfBasedProtectorId(userId)));
                pw.println(TextUtils.formatSimple(
                            "LSKF last changed: %s (previous protector: %016x)",
                            timestampToString(getLong(LSKF_LAST_CHANGED_TIME_KEY, 0, userId)),
                            getLong(PREV_LSKF_BASED_PROTECTOR_ID_KEY, 0, userId)));
            }
            try {
                pw.println(TextUtils.formatSimple("SID: %016x",
                        getGateKeeperService().getSecureUserId(userId)));
            } catch (RemoteException e) {
                // ignore.
            }
            // It's OK to dump the credential type since anyone with physical access can just
            // observe it from the keyguard directly.
            pw.println("Quality: " + getKeyguardStoredQuality(userId));
            pw.println("CredentialType: " + LockPatternUtils.credentialTypeToString(
                    getCredentialTypeInternal(userId)));
            pw.println("SeparateChallenge: " + getSeparateProfileChallengeEnabledInternal(userId));
            pw.println(TextUtils.formatSimple("Metrics: %s",
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
        synchronized (mUserCreationAndRemovalLock) {
            pw.println("ThirdPartyAppsStarted: " + mThirdPartyAppsStarted);
        }
    }

    private void dumpKeystoreKeys(IndentingPrintWriter pw) {
        try {
            final Enumeration<String> aliases = mKeyStore.aliases();
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

        if (!mSpManager.hasAnyEscrowData(userId)) {
            return;
        }

        // TODO(b/258213147): Remove
        final long identity = Binder.clearCallingIdentity();
        try {
            if (DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_DEVICE_POLICY_MANAGER,
                    DEPRECATE_USERMANAGERINTERNAL_DEVICEPOLICY_FLAG,
                    DEPRECATE_USERMANAGERINTERNAL_DEVICEPOLICY_DEFAULT)) {

                if (mInjector.getDeviceStateCache().isUserOrganizationManaged(userId)) {
                    Slog.i(TAG, "Organization managed users can have escrow token");
                    return;
                }
            } else {
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
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
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
        Slogf.i(TAG, "Permanently disabling support for escrow tokens on user %d", userId);
        mSpManager.destroyEscrowData(userId);
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
                        Slogf.d(TAG, "Clearing FRP credential tied to user %d", user.id);
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
        public void onThirdPartyAppsStarted() {
            LockSettingsService.this.onThirdPartyAppsStarted();
        }

        @Override
        public void createNewUser(@UserIdInt int userId, int userSerialNumber) {
            LockSettingsService.this.createNewUser(userId, userSerialNumber);
        }

        @Override
        public void removeUser(@UserIdInt int userId) {
            LockSettingsService.this.removeUser(userId);
        }

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
                return LockSettingsService.this.getUserPasswordMetrics(userHandle);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
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

        @Override
        public void registerLockSettingsStateListener(@NonNull LockSettingsStateListener listener) {
            Objects.requireNonNull(listener, "listener cannot be null");
            mLockSettingsStateListeners.add(listener);
        }

        @Override
        public void unregisterLockSettingsStateListener(
                @NonNull LockSettingsStateListener listener) {
            mLockSettingsStateListeners.remove(listener);
        }
    }

    private class RebootEscrowCallbacks implements RebootEscrowManager.Callbacks {
        @Override
        public boolean isUserSecure(int userId) {
            return LockSettingsService.this.isUserSecure(userId);
        }

        @Override
        public void onRebootEscrowRestored(byte spVersion, byte[] rawSyntheticPassword,
                int userId) {
            SyntheticPasswordManager.SyntheticPassword
                    sp = new SyntheticPasswordManager.SyntheticPassword(spVersion);
            sp.recreateDirectly(rawSyntheticPassword);
            synchronized (mSpManager) {
                mSpManager.verifyChallenge(getGateKeeperService(), sp, 0L, userId);
            }
            Slogf.i(TAG, "Restored synthetic password for user %d using reboot escrow", userId);
            onCredentialVerified(sp, loadPasswordMetrics(sp, userId), userId);
        }
    }
}
