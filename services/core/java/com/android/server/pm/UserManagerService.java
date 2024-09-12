/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.Intent.ACTION_SCREEN_OFF;
import static android.content.Intent.ACTION_SCREEN_ON;
import static android.content.Intent.EXTRA_USER_ID;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageManager.FEATURE_AUTOMOTIVE;
import static android.content.pm.PackageManager.FEATURE_EMBEDDED;
import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.os.UserManager.DEV_CREATE_OVERRIDE_PROPERTY;
import static android.os.UserManager.DISALLOW_USER_SWITCH;
import static android.os.UserManager.SYSTEM_USER_MODE_EMULATION_PROPERTY;
import static android.os.UserManager.USER_OPERATION_ERROR_UNKNOWN;
import static android.os.UserManager.USER_TYPE_PROFILE_PRIVATE;

import static com.android.internal.app.SetScreenLockDialogActivity.EXTRA_ORIGIN_USER_ID;
import static com.android.internal.app.SetScreenLockDialogActivity.LAUNCH_REASON_DISABLE_QUIET_MODE;
import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;
import static com.android.server.pm.UserJourneyLogger.ERROR_CODE_ABORTED;
import static com.android.server.pm.UserJourneyLogger.ERROR_CODE_UNSPECIFIED;
import static com.android.server.pm.UserJourneyLogger.ERROR_CODE_USER_ALREADY_AN_ADMIN;
import static com.android.server.pm.UserJourneyLogger.ERROR_CODE_USER_IS_NOT_AN_ADMIN;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_GRANT_ADMIN;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_REVOKE_ADMIN;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_CREATE;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_LIFECYCLE;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_REMOVE;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.ColorRes;
import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.StringRes;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
import android.app.BroadcastOptions;
import android.app.IActivityManager;
import android.app.IStopUserCallback;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.StatsManager;
import android.app.admin.DevicePolicyEventLogger;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.LauncherUserInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PackagePartitions;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.content.pm.UserInfo.UserInfoFlag;
import android.content.pm.UserPackage;
import android.content.pm.UserProperties;
import android.content.pm.parsing.FrameworkParsingPackageUtils;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.multiuser.Flags;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.IUserManager;
import android.os.IUserRestrictionsListener;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManager.EnforcingUser;
import android.os.UserManager.QuietModeFlag;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.provider.Settings;
import android.service.voice.VoiceInteractionManagerInternal;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.IndentingPrintWriter;
import android.util.IntArray;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.StatsEvent;
import android.util.TimeUtils;
import android.util.TypedValue;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.SetScreenLockDialogActivity;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.BundleUtils;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.SystemService;
import com.android.server.am.UserState;
import com.android.server.pm.UserManagerInternal.UserLifecycleListener;
import com.android.server.pm.UserManagerInternal.UserRestrictionsListener;
import com.android.server.storage.DeviceStorageMonitorInternal;
import com.android.server.utils.Slogf;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wm.ActivityTaskManagerInternal;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for {@link UserManager}.
 *
 * Method naming convention:
 * <ul>
 * <li> Methods suffixed with "LAr" should be called within the {@link #mAppRestrictionsLock} lock.
 * <li> Methods suffixed with "LP" should be called within the {@link #mPackagesLock} lock.
 * <li> Methods suffixed with "LR" should be called within the {@link #mRestrictionsLock} lock.
 * <li> Methods suffixed with "LU" should be called within the {@link #mUsersLock} lock.
 * </ul>
 */
public class UserManagerService extends IUserManager.Stub {

    private static final String LOG_TAG = "UserManagerService";
    static final boolean DBG = false; // DO NOT SUBMIT WITH TRUE
    // For Multiple Users on Multiple Displays
    static final boolean DBG_MUMD = false; // DO NOT SUBMIT WITH TRUE
    private static final boolean DBG_WITH_STACKTRACE = false; // DO NOT SUBMIT WITH TRUE
    // Can be used for manual testing of id recycling
    private static final boolean RELEASE_DELETED_USER_ID = false; // DO NOT SUBMIT WITH TRUE

    private static final String TAG_NAME = "name";
    private static final String TAG_ACCOUNT = "account";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_TYPE = "type"; // userType
    private static final String ATTR_ICON_PATH = "icon";
    private static final String ATTR_ID = "id";
    private static final String ATTR_CREATION_TIME = "created";
    private static final String ATTR_LAST_LOGGED_IN_TIME = "lastLoggedIn";
    private static final String ATTR_LAST_LOGGED_IN_FINGERPRINT = "lastLoggedInFingerprint";
    private static final String ATTR_LAST_ENTERED_FOREGROUND_TIME = "lastEnteredForeground";
    private static final String ATTR_SERIAL_NO = "serialNumber";
    private static final String ATTR_NEXT_SERIAL_NO = "nextSerialNumber";
    private static final String ATTR_PARTIAL = "partial";
    private static final String ATTR_PRE_CREATED = "preCreated";
    private static final String ATTR_CONVERTED_FROM_PRE_CREATED = "convertedFromPreCreated";
    private static final String ATTR_GUEST_TO_REMOVE = "guestToRemove";
    private static final String ATTR_USER_VERSION = "version";
    private static final String ATTR_USER_TYPE_VERSION = "userTypeConfigVersion";
    private static final String ATTR_PROFILE_GROUP_ID = "profileGroupId";
    private static final String ATTR_PROFILE_BADGE = "profileBadge";
    private static final String ATTR_RESTRICTED_PROFILE_PARENT_ID = "restrictedProfileParentId";
    private static final String ATTR_SEED_ACCOUNT_NAME = "seedAccountName";
    private static final String ATTR_SEED_ACCOUNT_TYPE = "seedAccountType";

    private static final String TAG_GUEST_RESTRICTIONS = "guestRestrictions";
    private static final String TAG_USERS = "users";
    private static final String TAG_USER = "user";
    private static final String TAG_RESTRICTIONS = "restrictions";
    private static final String TAG_DEVICE_POLICY_RESTRICTIONS = "device_policy_restrictions";
    private static final String TAG_DEVICE_POLICY_LOCAL_RESTRICTIONS =
            "device_policy_local_restrictions";
    private static final String TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS =
            "device_policy_global_restrictions";
    /** Legacy name for device owner id tag. */
    private static final String TAG_GLOBAL_RESTRICTION_OWNER_ID = "globalRestrictionOwnerUserId";
    private static final String TAG_DEVICE_OWNER_USER_ID = "deviceOwnerUserId";
    private static final String TAG_ENTRY = "entry";
    private static final String TAG_VALUE = "value";
    private static final String TAG_SEED_ACCOUNT_OPTIONS = "seedAccountOptions";
    private static final String TAG_USER_PROPERTIES = "userProperties";
    private static final String TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL =
            "lastRequestQuietModeEnabledCall";
    private static final String TAG_IGNORE_PREPARE_STORAGE_ERRORS =
            "ignorePrepareStorageErrors";

    private static final String ATTR_KEY = "key";
    private static final String ATTR_VALUE_TYPE = "type";
    private static final String ATTR_MULTIPLE = "m";

    private static final String ATTR_TYPE_STRING_ARRAY = "sa";
    private static final String ATTR_TYPE_STRING = "s";
    private static final String ATTR_TYPE_BOOLEAN = "b";
    private static final String ATTR_TYPE_INTEGER = "i";
    private static final String ATTR_TYPE_BUNDLE = "B";
    private static final String ATTR_TYPE_BUNDLE_ARRAY = "BA";

    private static final String USER_INFO_DIR = "system" + File.separator + "users";
    private static final String USER_LIST_FILENAME = "userlist.xml";
    private static final String USER_PHOTO_FILENAME = "photo.png";
    private static final String USER_PHOTO_FILENAME_TMP = USER_PHOTO_FILENAME + ".tmp";

    private static final String RESTRICTIONS_FILE_PREFIX = "res_";
    private static final String XML_SUFFIX = ".xml";

    private static final String CUSTOM_BIOMETRIC_PROMPT_LOGO_RES_ID_KEY = "custom_logo_res_id";
    private static final String CUSTOM_BIOMETRIC_PROMPT_LOGO_DESCRIPTION_KEY =
            "custom_logo_description";

    private static final int ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION =
            UserInfo.FLAG_MANAGED_PROFILE
            | UserInfo.FLAG_PROFILE
            | UserInfo.FLAG_EPHEMERAL
            | UserInfo.FLAG_RESTRICTED
            | UserInfo.FLAG_GUEST
            | UserInfo.FLAG_DEMO
            | UserInfo.FLAG_FULL
            | UserInfo.FLAG_FOR_TESTING;

    @VisibleForTesting
    static final int MIN_USER_ID = UserHandle.MIN_SECONDARY_USER_ID;

    // We need to keep process uid within Integer.MAX_VALUE.
    @VisibleForTesting
    static final int MAX_USER_ID = UserHandle.MAX_SECONDARY_USER_ID;

    // Max size of the queue of recently removed users
    @VisibleForTesting
    static final int MAX_RECENTLY_REMOVED_IDS_SIZE = 100;

    private static final int USER_VERSION = 11;

    private static final long EPOCH_PLUS_30_YEARS = 30L * 365 * 24 * 60 * 60 * 1000L; // ms

    static final int WRITE_USER_MSG = 1;
    static final int WRITE_USER_LIST_MSG = 2;
    static final int WRITE_USER_DELAY = 2*1000;  // 2 seconds

    private static final long BOOT_USER_SET_TIMEOUT_MS = 300_000;

    /**
     * The time duration (in milliseconds) post device inactivity after which the private space
     * should be auto-locked if the corresponding settings option is selected by the user.
     */
    private static final long PRIVATE_SPACE_AUTO_LOCK_INACTIVITY_TIMEOUT_MS = 5 * 60 * 1000;

    // Tron counters
    private static final String TRON_GUEST_CREATED = "users_guest_created";
    private static final String TRON_USER_CREATED = "users_user_created";
    private static final String TRON_DEMO_CREATED = "users_demo_created";

    private final Context mContext;
    private final PackageManagerService mPm;

    /**
     * Lock for packages. If using with {@link #mUsersLock}, {@link #mPackagesLock} should be
     * acquired first.
     */
    private final Object mPackagesLock;
    private final UserDataPreparer mUserDataPreparer;
    /**
     * Short-term lock for internal state, when interaction/sync with PM is not required. If using
     * with {@link #mPackagesLock}, {@link #mPackagesLock} should be acquired first.
     */
    private final Object mUsersLock = LockGuard.installNewLock(LockGuard.INDEX_USER);
    private final Object mRestrictionsLock = new Object();
    // Used for serializing access to app restriction files
    private final Object mAppRestrictionsLock = new Object();

    private final Handler mHandler;

    private final ThreadPoolExecutor mInternalExecutor;

    private final File mUsersDir;
    private final File mUserListFile;

    private final IBinder mUserRestrictionToken = new Binder();

    /** Installs system packages based on user-type. */
    private final UserSystemPackageInstaller mSystemPackageInstaller;

    private PackageManagerInternal mPmInternal;
    private DevicePolicyManagerInternal mDevicePolicyManagerInternal;
    private ActivityManagerInternal mAmInternal;

    /** Indicates that this is the 1st boot after the system user mode was changed by emulation. */
    private boolean mUpdatingSystemUserMode;

    /** Count down latch to wait while boot user is not set.*/
    private final CountDownLatch mBootUserLatch = new CountDownLatch(1);
    /**
     * Internal non-parcelable wrapper for UserInfo that is not exposed to other system apps.
     */
    @VisibleForTesting
    static class UserData {
        // Basic user information and properties
        @NonNull UserInfo info;
        // Account name used when there is a strong association between a user and an account
        String account;
        // Account information for seeding into a newly created user. This could also be
        // used for login validation for an existing user, for updating their credentials.
        // In the latter case, data may not need to be persisted as it is only valid for the
        // current login session.
        String seedAccountName;
        String seedAccountType;
        PersistableBundle seedAccountOptions;
        // Whether to perist the seed account information to be available after a boot
        boolean persistSeedData;

        /** Properties of the user whose default values originate from its user type. */
        UserProperties userProperties;

        /** Elapsed realtime since boot when the user started. */
        long startRealtime;

        /** Elapsed realtime since boot when the user was unlocked. */
        long unlockRealtime;

        /** Wall clock time in millis when the user last entered the foreground. */
        long mLastEnteredForegroundTimeMillis;

        private long mLastRequestQuietModeEnabledMillis;

        /**
         * {@code true} if the system should ignore errors when preparing the
         * storage directories for this user. This is {@code false} for all new
         * users; it will only be {@code true} for users that already existed
         * on-disk from an older version of Android.
         */
        private boolean mIgnorePrepareStorageErrors;

        void setLastRequestQuietModeEnabledMillis(long millis) {
            mLastRequestQuietModeEnabledMillis = millis;
        }

        long getLastRequestQuietModeEnabledMillis() {
            return mLastRequestQuietModeEnabledMillis;
        }

        boolean getIgnorePrepareStorageErrors() {
            return mIgnorePrepareStorageErrors;
        }

        @SuppressWarnings("AndroidFrameworkCompatChange")  // This is not an app-visible API.
        void setIgnorePrepareStorageErrors() {
            // This method won't be called for new users.  But to fully rule out
            // the possibility of mIgnorePrepareStorageErrors ever being true
            // for any user on any device that launched with T or later, we also
            // explicitly check that DEVICE_INITIAL_SDK_INT is below T before
            // honoring the request to set mIgnorePrepareStorageErrors to true.
            if (Build.VERSION.DEVICE_INITIAL_SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                mIgnorePrepareStorageErrors = true;
                return;
            }
            Slog.w(LOG_TAG, "Not setting mIgnorePrepareStorageErrors to true"
                    + " since this is a new device");
        }

        void clearSeedAccountData() {
            seedAccountName = null;
            seedAccountType = null;
            seedAccountOptions = null;
            persistSeedData = false;
        }
    }

    @GuardedBy("mUsersLock")
    private final SparseArray<UserData> mUsers;

    /**
     * Map of user type names to their corresponding {@link UserTypeDetails}.
     * Should not be modified after UserManagerService constructor finishes.
     */
    private final ArrayMap<String, UserTypeDetails> mUserTypes;

    /**
     * User restrictions set via UserManager.  This doesn't include restrictions set by
     * device owner / profile owners. Only non-empty restriction bundles are stored.
     *
     * DO NOT Change existing {@link Bundle} in it.  When changing a restriction for a user,
     * a new {@link Bundle} should always be created and set.  This is because a {@link Bundle}
     * maybe shared between {@link #mBaseUserRestrictions} and
     * {@link #mCachedEffectiveUserRestrictions}, but they should always updated separately.
     * (Otherwise we won't be able to detect what restrictions have changed in
     * {@link #updateUserRestrictionsInternalLR}.
     */
    @GuardedBy("mRestrictionsLock")
    private final RestrictionsSet mBaseUserRestrictions = new RestrictionsSet();

    /**
     * Cached user restrictions that are in effect -- i.e. {@link #mBaseUserRestrictions} combined
     * with device / profile owner restrictions.  We'll initialize it lazily; use
     * {@link #getEffectiveUserRestrictions} to access it.
     *
     * DO NOT Change existing {@link Bundle} in it.  When changing a restriction for a user,
     * a new {@link Bundle} should always be created and set.  This is because a {@link Bundle}
     * maybe shared between {@link #mBaseUserRestrictions} and
     * {@link #mCachedEffectiveUserRestrictions}, but they should always updated separately.
     * (Otherwise we won't be able to detect what restrictions have changed in
     * {@link #updateUserRestrictionsInternalLR}.
     */
    @GuardedBy("mRestrictionsLock")
    private final RestrictionsSet mCachedEffectiveUserRestrictions = new RestrictionsSet();

    /**
     * User restrictions that have already been applied in
     * {@link #updateUserRestrictionsInternalLR(Bundle, int)}.  We use it to detect restrictions
     * that have changed since the last
     * {@link #updateUserRestrictionsInternalLR(Bundle, int)} call.
     */
    @GuardedBy("mRestrictionsLock")
    private final RestrictionsSet mAppliedUserRestrictions = new RestrictionsSet();

    /**
     * User restrictions set by {@link com.android.server.devicepolicy.DevicePolicyManagerService}
     * for each user. Restrictions that apply to all users (global) are represented by
     * {@link com.android.os.UserHandle.USER_ALL}.
     * The key is the user id of the user whom the restrictions are targeting.
     */
    @GuardedBy("mRestrictionsLock")
    private final RestrictionsSet mDevicePolicyUserRestrictions = new RestrictionsSet();

    @GuardedBy("mGuestRestrictions")
    private final Bundle mGuestRestrictions = new Bundle();

    /**
     * Set of user IDs that are being removed or were removed during the current boot.  User IDs in
     * this set aren't reused until the device is rebooted, unless MAX_USER_ID is reached.  Some
     * services don't fully clear out in-memory user state upon user removal; this behavior is
     * intended to mitigate such issues by limiting user ID reuse.  This array applies to any type
     * of user (including pre-created users) when they are removed.  Use {@link
     * #addRemovingUserIdLocked(int)} to add elements to this array.
     */
    @GuardedBy("mUsersLock")
    private final SparseBooleanArray mRemovingUserIds = new SparseBooleanArray();

    /**
     * Queue of recently removed userIds. Used for recycling of userIds
     */
    @GuardedBy("mUsersLock")
    private final LinkedList<Integer> mRecentlyRemovedIds = new LinkedList<>();

    @GuardedBy("mUsersLock")
    private int[] mUserIds;

    @GuardedBy("mUsersLock")
    private int[] mUserIdsIncludingPreCreated;

    @GuardedBy("mPackagesLock")
    private int mNextSerialNumber;
    private int mUserVersion = 0;
    private int mUserTypeVersion = 0;

    private IAppOpsService mAppOpsService;

    private final LocalService mLocalService;

    @GuardedBy("mUsersLock")
    private boolean mIsDeviceManaged;

    @GuardedBy("mUsersLock")
    private final SparseBooleanArray mIsUserManaged = new SparseBooleanArray();

    @GuardedBy("mUserRestrictionsListeners")
    private final ArrayList<UserRestrictionsListener> mUserRestrictionsListeners =
            new ArrayList<>();

    @GuardedBy("mUserLifecycleListeners")
    private final ArrayList<UserLifecycleListener> mUserLifecycleListeners = new ArrayList<>();

    private final UserJourneyLogger mUserJourneyLogger = new UserJourneyLogger();

    private final LockPatternUtils mLockPatternUtils;

    private KeyguardManager.KeyguardLockedStateListener mKeyguardLockedStateListener;

    /** Token to identify and remove already scheduled private space auto-lock messages */
    private static final Object PRIVATE_SPACE_AUTO_LOCK_MESSAGE_TOKEN = new Object();

    /** Content observer to get callbacks for privte space autolock settings changes */
    private final SettingsObserver mPrivateSpaceAutoLockSettingsObserver;

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (isAutoLockForPrivateSpaceEnabled()) {
                final String path = uri.getLastPathSegment();
                if (TextUtils.equals(path, Settings.Secure.PRIVATE_SPACE_AUTO_LOCK)) {
                    int autoLockPreference =
                            Settings.Secure.getIntForUser(mContext.getContentResolver(),
                                    Settings.Secure.PRIVATE_SPACE_AUTO_LOCK,
                                    Settings.Secure.PRIVATE_SPACE_AUTO_LOCK_AFTER_DEVICE_RESTART,
                                    getMainUserIdUnchecked());
                    Slog.i(LOG_TAG, "Auto-lock settings changed to " + autoLockPreference);
                    setOrUpdateAutoLockPreferenceForPrivateProfile(autoLockPreference);
                }
            }
        }
    }

    private final String ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK =
            "com.android.server.pm.DISABLE_QUIET_MODE_AFTER_UNLOCK";

    private final BroadcastReceiver mDisableQuietModeCallback = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK.equals(intent.getAction())) {
                return;
            }
            final IntentSender target = intent.getParcelableExtra(Intent.EXTRA_INTENT, android.content.IntentSender.class);
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_ID, UserHandle.USER_NULL);
            final String callingPackage = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
            setQuietModeEnabledAsync(userId, false, target, callingPackage);
        }
    };

    /** Checks if the device inactivity broadcast receiver is already registered*/
    private boolean mIsDeviceInactivityBroadcastReceiverRegistered = false;

    private final BroadcastReceiver mDeviceInactivityBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (isAutoLockForPrivateSpaceEnabled()) {
                if (ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    Slog.d(LOG_TAG, "SCREEN_OFF broadcast received");
                    maybeScheduleMessageToAutoLockPrivateSpace();
                } else if (ACTION_SCREEN_ON.equals(intent.getAction())) {
                    Slog.d(LOG_TAG, "SCREEN_ON broadcast received, "
                            + "removing queued message to auto-lock private space");
                    // Remove any queued messages since the device is interactive again
                    mHandler.removeCallbacksAndMessages(PRIVATE_SPACE_AUTO_LOCK_MESSAGE_TOKEN);
                }
            }
        }
    };

    @VisibleForTesting
    void maybeScheduleMessageToAutoLockPrivateSpace() {
        // No action needed if auto-lock on inactivity not selected
        int privateSpaceAutoLockPreference =
                Settings.Secure.getIntForUser(mContext.getContentResolver(),
                        Settings.Secure.PRIVATE_SPACE_AUTO_LOCK,
                        Settings.Secure.PRIVATE_SPACE_AUTO_LOCK_AFTER_DEVICE_RESTART,
                        getMainUserIdUnchecked());
        if (privateSpaceAutoLockPreference
                != Settings.Secure.PRIVATE_SPACE_AUTO_LOCK_AFTER_INACTIVITY) {
            Slogf.d(LOG_TAG, "Not scheduling auto-lock on inactivity,"
                    + "preference is set to %d", privateSpaceAutoLockPreference);
            return;
        }
        int privateProfileUserId = getPrivateProfileUserId();
        if (privateProfileUserId != UserHandle.USER_NULL) {
            scheduleMessageToAutoLockPrivateSpace(privateProfileUserId,
                    PRIVATE_SPACE_AUTO_LOCK_MESSAGE_TOKEN,
                    PRIVATE_SPACE_AUTO_LOCK_INACTIVITY_TIMEOUT_MS);
        }
    }

    @VisibleForTesting
    void scheduleMessageToAutoLockPrivateSpace(int userId, Object token,
            long delayInMillis) {
        Slog.i(LOG_TAG, "Scheduling auto-lock message");
        mHandler.postDelayed(() -> {
            final PowerManager powerManager = mContext.getSystemService(PowerManager.class);
            if (powerManager != null && !powerManager.isInteractive()) {
                Slog.i(LOG_TAG, "Auto-locking private space with user-id " + userId);
                setQuietModeEnabledAsync(userId, true,
                        /* target */ null, mContext.getPackageName());
            } else {
                Slog.i(LOG_TAG, "Device is interactive, skipping auto-lock");
            }
        }, token, delayInMillis);
    }

    @RequiresPermission(Manifest.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)
    private void initializeAndRegisterKeyguardLockedStateListener() {
        mKeyguardLockedStateListener = this::tryAutoLockingPrivateSpaceOnKeyguardChanged;
        // Register with keyguard to send locked state events to the listener initialized above
        try {
            final KeyguardManager keyguardManager =
                    mContext.getSystemService(KeyguardManager.class);
            Slog.i(LOG_TAG, "Adding keyguard locked state listener");
            keyguardManager.addKeyguardLockedStateListener(new HandlerExecutor(mHandler),
                    mKeyguardLockedStateListener);
        } catch (Exception e) {
            Slog.e(LOG_TAG, "Error adding keyguard locked listener ", e);
        }
    }

    @VisibleForTesting
    @RequiresPermission(Manifest.permission.SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE)
    void setOrUpdateAutoLockPreferenceForPrivateProfile(
            @Settings.Secure.PrivateSpaceAutoLockOption int autoLockPreference) {
        int privateProfileUserId = getPrivateProfileUserId();
        if (privateProfileUserId == UserHandle.USER_NULL) {
            Slog.e(LOG_TAG, "Auto-lock preference updated but private space user not found");
            return;
        }

        if (autoLockPreference == Settings.Secure.PRIVATE_SPACE_AUTO_LOCK_AFTER_INACTIVITY) {
            // Register inactivity broadcast
            if (!mIsDeviceInactivityBroadcastReceiverRegistered) {
                Slog.i(LOG_TAG, "Registering device inactivity broadcast receivers");
                mContext.registerReceiver(mDeviceInactivityBroadcastReceiver,
                        new IntentFilter(ACTION_SCREEN_OFF),
                        null, mHandler);

                mContext.registerReceiver(mDeviceInactivityBroadcastReceiver,
                        new IntentFilter(ACTION_SCREEN_ON),
                        null, mHandler);

                mIsDeviceInactivityBroadcastReceiverRegistered = true;
            }
        } else {
            // Unregister device inactivity broadcasts
            if (mIsDeviceInactivityBroadcastReceiverRegistered) {
                Slog.i(LOG_TAG, "Removing device inactivity broadcast receivers");
                mHandler.removeCallbacksAndMessages(PRIVATE_SPACE_AUTO_LOCK_MESSAGE_TOKEN);
                mContext.unregisterReceiver(mDeviceInactivityBroadcastReceiver);
                mIsDeviceInactivityBroadcastReceiverRegistered = false;
            }
        }

        if (autoLockPreference == Settings.Secure.PRIVATE_SPACE_AUTO_LOCK_ON_DEVICE_LOCK) {
            // Initialize and add keyguard state listener
            initializeAndRegisterKeyguardLockedStateListener();
        } else {
            // Remove keyguard state listener
            try {
                final KeyguardManager keyguardManager =
                        mContext.getSystemService(KeyguardManager.class);
                Slog.i(LOG_TAG, "Removing keyguard locked state listener");
                keyguardManager.removeKeyguardLockedStateListener(mKeyguardLockedStateListener);
            } catch (Exception e) {
                Slog.e(LOG_TAG, "Error adding keyguard locked state listener ", e);
            }
        }
    }

    @VisibleForTesting
    void tryAutoLockingPrivateSpaceOnKeyguardChanged(boolean isKeyguardLocked) {
        if (isAutoLockForPrivateSpaceEnabled()) {
            int autoLockPreference = Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.PRIVATE_SPACE_AUTO_LOCK,
                    Settings.Secure.PRIVATE_SPACE_AUTO_LOCK_AFTER_DEVICE_RESTART,
                    getMainUserIdUnchecked());
            boolean isAutoLockOnDeviceLockSelected =
                    autoLockPreference == Settings.Secure.PRIVATE_SPACE_AUTO_LOCK_ON_DEVICE_LOCK;
            if (isKeyguardLocked && isAutoLockOnDeviceLockSelected) {
                autoLockPrivateSpace();
            }
        }
    }

    @VisibleForTesting
    void autoLockPrivateSpace() {
        int privateProfileUserId = getPrivateProfileUserId();
        if (privateProfileUserId != UserHandle.USER_NULL) {
            Slog.i(LOG_TAG, "Auto-locking private space with user-id "
                    + privateProfileUserId);
            setQuietModeEnabledAsync(privateProfileUserId,
                    /* enableQuietMode */true, /* target */ null,
                    mContext.getPackageName());
        }
    }

    @VisibleForTesting
    void setQuietModeEnabledAsync(@UserIdInt int userId, boolean enableQuietMode,
            IntentSender target, @Nullable String callingPackage) {
        if (android.multiuser.Flags.moveQuietModeOperationsToSeparateThread()) {
            // Call setQuietModeEnabled on a separate thread. Calling this operation on the main
            // thread can cause ANRs, posting on a BackgroundThread can result in delays
            Slog.d(LOG_TAG, "Calling setQuietModeEnabled for user " + userId
                    + " on a separate thread");
            mInternalExecutor.execute(() -> setQuietModeEnabled(userId, enableQuietMode, target,
                    callingPackage));
        } else {
            // Call setQuietModeEnabled on bg thread to avoid ANR
            BackgroundThread.getHandler().post(
                    () -> setQuietModeEnabled(userId, enableQuietMode, target,
                            callingPackage)
            );
        }
    }

    /**
     * Cache the owner name string, since it could be read repeatedly on a critical code path
     * but hit by slow IO. This could be eliminated once we have the cached UserInfo in place.
     */
    private final AtomicReference<String> mOwnerName = new AtomicReference<>();

    private final TypedValue mOwnerNameTypedValue = new TypedValue();

    private final Configuration mLastConfiguration = new Configuration();

    private final BroadcastReceiver mConfigurationChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())) {
                return;
            }
            invalidateOwnerNameIfNecessary(context.getResources(), false /* forceUpdate */);
        }
    };

    // TODO(b/161915546): remove once userWithName() is fixed / removed
    // Use to debug / dump when user 0 is allocated at userWithName()
    public static final boolean DBG_ALLOCATION = false; // DO NOT SUBMIT WITH TRUE
    public final AtomicInteger mUser0Allocations;

    /**
     * Start an {@link IntentSender} when user is unlocked after disabling quiet mode.
     *
     * @see #requestQuietModeEnabled(String, boolean, int, IntentSender, int)
     */
    private class DisableQuietModeUserUnlockedCallback extends IProgressListener.Stub {
        private final IntentSender mTarget;

        public DisableQuietModeUserUnlockedCallback(IntentSender target) {
            Objects.requireNonNull(target);
            mTarget = target;
        }

        @Override
        public void onStarted(int id, Bundle extras) {}

        @Override
        public void onProgress(int id, int progress, Bundle extras) {}

        @Override
        public void onFinished(int id, Bundle extras) {
            mHandler.post(() -> {
                try {
                    ActivityOptions activityOptions =
                            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    mContext.startIntentSender(mTarget, null, 0, 0, 0, activityOptions.toBundle());
                } catch (IntentSender.SendIntentException e) {
                    Slog.e(LOG_TAG, "Failed to start the target in the callback", e);
                }
            });
        }
    }

    /**
     * Whether all users should be created ephemeral.
     */
    @GuardedBy("mUsersLock")
    private boolean mForceEphemeralUsers;

    /**
     * The member mUserStates affects the return value of isUserUnlocked.
     * If any value in mUserStates changes, then the binder cache for
     * isUserUnlocked must be invalidated.  When adding mutating methods to
     * WatchedUserStates, be sure to invalidate the cache in the new
     * methods.
     */
    private class WatchedUserStates {
        final SparseIntArray states;
        public WatchedUserStates() {
            states = new SparseIntArray();
            invalidateIsUserUnlockedCache();
        }
        public int get(@UserIdInt int userId) {
            return states.get(userId);
        }
        public int get(@UserIdInt int userId, int fallback) {
            return states.indexOfKey(userId) >= 0 ? states.get(userId) : fallback;
        }
        public void put(@UserIdInt int userId, int state) {
            states.put(userId, state);
            invalidateIsUserUnlockedCache();
        }
        public void delete(@UserIdInt int userId) {
            states.delete(userId);
            invalidateIsUserUnlockedCache();
        }
        public boolean has(@UserIdInt int userId) {
            return states.get(userId, UserHandle.USER_NULL) != UserHandle.USER_NULL;
        }
        @Override
        public String toString() {
            return states.toString();
        }
        private void invalidateIsUserUnlockedCache() {
            UserManager.invalidateIsUserUnlockedCache();
        }
    }
    @GuardedBy("mUserStates")
    private final WatchedUserStates mUserStates = new WatchedUserStates();

    private final UserVisibilityMediator mUserVisibilityMediator;

    @GuardedBy("mUsersLock")
    private @UserIdInt int mBootUser = UserHandle.USER_NULL;

    private static UserManagerService sInstance;

    public static UserManagerService getInstance() {
        synchronized (UserManagerService.class) {
            return sInstance;
        }
    }

    public static class LifeCycle extends SystemService {

        private UserManagerService mUms;

        /**
         * @param context
         */
        public LifeCycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mUms = UserManagerService.getInstance();
            publishBinderService(Context.USER_SERVICE, mUms);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mUms.cleanupPartialUsers();

                if (mUms.mPm.isDeviceUpgrading()) {
                    mUms.cleanupPreCreatedUsers();
                }

                mUms.registerStatsCallbacks();
            }
        }

        @Override
        public void onUserStarting(@NonNull TargetUser targetUser) {
            synchronized (mUms.mUsersLock) {
                final UserData user = mUms.getUserDataLU(targetUser.getUserIdentifier());
                if (user != null) {
                    user.startRealtime = SystemClock.elapsedRealtime();
                    if (targetUser.getUserIdentifier() == UserHandle.USER_SYSTEM
                            && targetUser.isFull()) {
                        mUms.setLastEnteredForegroundTimeToNow(user);
                    }
                }
            }
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser targetUser) {
            synchronized (mUms.mUsersLock) {
                final UserData user = mUms.getUserDataLU(targetUser.getUserIdentifier());
                if (user != null) {
                    user.unlockRealtime = SystemClock.elapsedRealtime();
                }
            }
            if (targetUser.getUserIdentifier() == UserHandle.USER_SYSTEM
                    && UserManager.isCommunalProfileEnabled()) {
                mUms.startCommunalProfile();
            }
        }

        @Override
        public void onUserSwitching(@NonNull TargetUser from, @NonNull TargetUser to) {
            synchronized (mUms.mUsersLock) {
                final UserData user = mUms.getUserDataLU(to.getUserIdentifier());
                if (user != null) {
                    mUms.setLastEnteredForegroundTimeToNow(user);
                }
            }
        }

        @Override
        public void onUserStopping(@NonNull TargetUser targetUser) {
            synchronized (mUms.mUsersLock) {
                final UserData user = mUms.getUserDataLU(targetUser.getUserIdentifier());
                if (user != null) {
                    user.startRealtime = 0;
                    user.unlockRealtime = 0;
                }
            }
        }
    }

    // TODO(b/28848102) Add support for test dependencies injection
    @VisibleForTesting
    UserManagerService(Context context) {
        this(context, /* pm= */ null, /* userDataPreparer= */ null,
                /* packagesLock= */ new Object(), context.getCacheDir(), /* users= */ null);
    }

    /**
     * Called by package manager to create the service.  This is closely
     * associated with the package manager, and the given lock is the
     * package manager's own lock.
     */
    UserManagerService(Context context, PackageManagerService pm, UserDataPreparer userDataPreparer,
            Object packagesLock) {
        this(context, pm, userDataPreparer, packagesLock, Environment.getDataDirectory(),
                /* users= */ null);
    }

    @VisibleForTesting
    UserManagerService(Context context, PackageManagerService pm,
            UserDataPreparer userDataPreparer, Object packagesLock, File dataDir,
            SparseArray<UserData> users) {
        mContext = context;
        mPm = pm;
        mPackagesLock = packagesLock;
        mUsers = users != null ? users : new SparseArray<>();
        mHandler = new MainHandler();
        mInternalExecutor = new ThreadPoolExecutor(/* corePoolSize */ 0, /* maximumPoolSize */ 1,
                /* keepAliveTime */ 24, TimeUnit.HOURS, new LinkedBlockingQueue<>());
        mUserVisibilityMediator = new UserVisibilityMediator(mHandler);
        mUserDataPreparer = userDataPreparer;
        mUserTypes = UserTypeFactory.getUserTypes();
        invalidateOwnerNameIfNecessary(context.getResources(), true /* forceUpdate */);
        synchronized (mPackagesLock) {
            mUsersDir = new File(dataDir, USER_INFO_DIR);
            mUsersDir.mkdirs();
            // Make zeroth user directory, for services to migrate their files to that location
            File userZeroDir = new File(mUsersDir, String.valueOf(UserHandle.USER_SYSTEM));
            userZeroDir.mkdirs();
            FileUtils.setPermissions(mUsersDir.toString(),
                    FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IROTH | FileUtils.S_IXOTH,
                    -1, -1);
            mUserListFile = new File(mUsersDir, USER_LIST_FILENAME);
            initDefaultGuestRestrictions();
            readUserListLP();
            sInstance = this;
        }
        mSystemPackageInstaller = new UserSystemPackageInstaller(this, mUserTypes);
        mLocalService = new LocalService();
        LocalServices.addService(UserManagerInternal.class, mLocalService);
        mLockPatternUtils = new LockPatternUtils(mContext);
        mUserStates.put(UserHandle.USER_SYSTEM, UserState.STATE_BOOTING);
        mUser0Allocations = DBG_ALLOCATION ? new AtomicInteger() : null;
        mPrivateSpaceAutoLockSettingsObserver = new SettingsObserver(mHandler);
        emulateSystemUserModeIfNeeded();
    }

    private boolean doesDeviceHardwareSupportPrivateSpace() {
        return !mPm.hasSystemFeature(FEATURE_EMBEDDED, 0)
                && !mPm.hasSystemFeature(FEATURE_WATCH, 0)
                && !mPm.hasSystemFeature(FEATURE_LEANBACK, 0)
                && !mPm.hasSystemFeature(FEATURE_AUTOMOTIVE, 0);
    }

    private static boolean isAutoLockForPrivateSpaceEnabled() {
        return android.os.Flags.allowPrivateProfile()
                && Flags.supportAutolockForPrivateSpace()
                && android.multiuser.Flags.enablePrivateSpaceFeatures();
    }

    void systemReady() {
        mAppOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));

        synchronized (mRestrictionsLock) {
            applyUserRestrictionsLR(UserHandle.USER_SYSTEM);
        }

        mContext.registerReceiver(mDisableQuietModeCallback,
                new IntentFilter(ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK),
                null, mHandler);

        mContext.registerReceiver(mConfigurationChangeReceiver,
                new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED),
                null, mHandler);

        if (isAutoLockForPrivateSpaceEnabled()) {

            int mainUserId = getMainUserIdUnchecked();
            if (mainUserId != UserHandle.USER_NULL) {
                mContext.getContentResolver().registerContentObserverAsUser(
                        Settings.Secure.getUriFor(
                                Settings.Secure.PRIVATE_SPACE_AUTO_LOCK), false,
                        mPrivateSpaceAutoLockSettingsObserver, UserHandle.of(mainUserId));

                setOrUpdateAutoLockPreferenceForPrivateProfile(
                        Settings.Secure.getIntForUser(mContext.getContentResolver(),
                                Settings.Secure.PRIVATE_SPACE_AUTO_LOCK,
                                Settings.Secure.PRIVATE_SPACE_AUTO_LOCK_AFTER_DEVICE_RESTART,
                                mainUserId));
            }
        }

        if (isAutoLockingPrivateSpaceOnRestartsEnabled()) {
            autoLockPrivateSpace();
        }
    }

    private boolean isAutoLockingPrivateSpaceOnRestartsEnabled() {
        return android.os.Flags.allowPrivateProfile()
                && android.multiuser.Flags.enablePrivateSpaceAutolockOnRestarts()
                && android.multiuser.Flags.enablePrivateSpaceFeatures();
    }

    /**
     * This method retrieves the  {@link UserManagerInternal} only for the purpose of
     * PackageManagerService construction.
     */
    UserManagerInternal getInternalForInjectorOnly() {
        return mLocalService;
    }

    private void startCommunalProfile() {
        final int communalProfileId = getCommunalProfileIdUnchecked();
        if (communalProfileId != UserHandle.USER_NULL) {
            Slogf.d(LOG_TAG, "Starting the Communal Profile");
            boolean started = false;
            try {
                started = ActivityManager.getService().startProfile(communalProfileId);
            } catch (RemoteException e) {
                // Should not happen - same process
                e.rethrowAsRuntimeException();
            }
            if (!started) {
                Slogf.wtf(LOG_TAG,
                        "Failed to start communal profile userId=%d", communalProfileId);
            }
        } else {
            Slogf.w(LOG_TAG, "Cannot start Communal Profile because there isn't one");
        }
    }

    /* Prunes out any partially created or partially removed users. */
    private void cleanupPartialUsers() {
        ArrayList<UserInfo> partials = new ArrayList<>();
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if ((ui.partial || ui.guestToRemove) && ui.id != UserHandle.USER_SYSTEM) {
                    partials.add(ui);
                    if (!mRemovingUserIds.get(ui.id)) {
                        addRemovingUserIdLocked(ui.id);
                    }
                    ui.partial = true;
                }
            }
        }
        final int partialsSize = partials.size();
        for (int i = 0; i < partialsSize; i++) {
            UserInfo ui = partials.get(i);
            Slog.w(LOG_TAG, "Removing partially created user " + ui.id
                    + " (name=" + ui.name + ")");
            removeUserState(ui.id);
        }
    }

    /**
     * Removes any pre-created users from the system. Should be invoked after OTAs, to ensure
     * pre-created users are not stale. New pre-created pool can be re-created after the update.
     */
    private void cleanupPreCreatedUsers() {
        final ArrayList<UserInfo> preCreatedUsers;
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            preCreatedUsers = new ArrayList<>(userSize);
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if (ui.preCreated) {
                    preCreatedUsers.add(ui);
                    addRemovingUserIdLocked(ui.id);
                    ui.flags |= UserInfo.FLAG_DISABLED;
                    ui.partial = true;
                }
            }
        }
        final int preCreatedSize = preCreatedUsers.size();
        for (int i = 0; i < preCreatedSize; i++) {
            UserInfo ui = preCreatedUsers.get(i);
            Slog.i(LOG_TAG, "Removing pre-created user " + ui.id);
            removeUserState(ui.id);
        }
    }

    @Override
    public String getUserAccount(@UserIdInt int userId) {
        checkManageUserAndAcrossUsersFullPermission("get user account");
        synchronized (mUsersLock) {
            return mUsers.get(userId).account;
        }
    }

    @Override
    public void setUserAccount(@UserIdInt int userId, String accountName) {
        checkManageUserAndAcrossUsersFullPermission("set user account");
        UserData userToUpdate = null;
        synchronized (mPackagesLock) {
            synchronized (mUsersLock) {
                final UserData userData = mUsers.get(userId);
                if (userData == null) {
                    Slog.e(LOG_TAG, "User not found for setting user account: u" + userId);
                    return;
                }
                String currentAccount = userData.account;
                if (!Objects.equals(currentAccount, accountName)) {
                    userData.account = accountName;
                    userToUpdate = userData;
                }
            }

            if (userToUpdate != null) {
                writeUserLP(userToUpdate);
            }
        }
    }

    @Override
    public UserInfo getPrimaryUser() {
        checkManageUsersPermission("query users");
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if (ui.isPrimary() && !mRemovingUserIds.get(ui.id)) {
                    return ui;
                }
            }
        }
        return null;
    }

    @Override
    public @UserIdInt int getMainUserId() {
        checkQueryOrCreateUsersPermission("get main user id");
        return getMainUserIdUnchecked();
    }

    private @UserIdInt int getMainUserIdUnchecked() {
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                final UserInfo user = mUsers.valueAt(i).info;
                if (user.isMain() && !mRemovingUserIds.get(user.id)) {
                    return user.id;
                }
            }
        }
        return UserHandle.USER_NULL;
    }

    private @UserIdInt int getPrivateProfileUserId() {
        synchronized (mUsersLock) {
            for (int userId : getUserIds()) {
                UserInfo userInfo = getUserInfoLU(userId);
                if (userInfo != null && userInfo.isPrivateProfile()) {
                    return userInfo.id;
                }
            }
        }
        return UserHandle.USER_NULL;
    }

    @Override
    public void setBootUser(@UserIdInt int userId) {
        checkCreateUsersPermission("Set boot user");
        synchronized (mUsersLock) {
            // TODO(b/263381643): Change to EventLog.
            Slogf.i(LOG_TAG, "setBootUser %d", userId);
            mBootUser = userId;
        }
        mBootUserLatch.countDown();
    }

    @Override
    public @UserIdInt int getBootUser() {
        checkCreateUsersPermission("Get boot user");
        try {
            return getBootUserUnchecked();
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }
    }

    private @UserIdInt int getBootUserUnchecked() throws UserManager.CheckedUserOperationException {
        synchronized (mUsersLock) {
            if (mBootUser != UserHandle.USER_NULL) {
                final UserData userData = mUsers.get(mBootUser);
                if (userData != null && userData.info.supportsSwitchToByUser()) {
                    Slogf.i(LOG_TAG, "Using provided boot user: %d", mBootUser);
                    return mBootUser;
                } else {
                    Slogf.w(LOG_TAG,
                            "Provided boot user cannot be switched to: %d", mBootUser);
                }
            }
        }

        if (isHeadlessSystemUserMode()) {
            // Return the previous foreground user, if there is one.
            final int previousUser = getPreviousFullUserToEnterForeground();
            if (previousUser != UserHandle.USER_NULL) {
                Slogf.i(LOG_TAG, "Boot user is previous user %d", previousUser);
                return previousUser;
            }
            // No previous user. Return the first switchable user if there is one.
            synchronized (mUsersLock) {
                final int userSize = mUsers.size();
                for (int i = 0; i < userSize; i++) {
                    final UserData userData = mUsers.valueAt(i);
                    if (userData.info.supportsSwitchToByUser()) {
                        int firstSwitchable = userData.info.id;
                        Slogf.i(LOG_TAG,
                                "Boot user is first switchable user %d", firstSwitchable);
                        return firstSwitchable;
                    }
                }
            }
            // No switchable users found. Uh oh!
            throw new UserManager.CheckedUserOperationException(
                    "No switchable users found", USER_OPERATION_ERROR_UNKNOWN);
        }
        // Not HSUM, return system user.
        return UserHandle.USER_SYSTEM;
    }


    @Override
    public int getPreviousFullUserToEnterForeground() {
        checkQueryOrCreateUsersPermission("get previous user");
        int previousUser = UserHandle.USER_NULL;
        long latestEnteredTime = 0;
        final int currentUser = getCurrentUserId();
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                final UserData userData = mUsers.valueAt(i);
                final int userId = userData.info.id;
                if (userId != currentUser && userData.info.isFull() && !userData.info.partial
                        && userData.info.isEnabled() && !mRemovingUserIds.get(userId)) {
                    final long userEnteredTime = userData.mLastEnteredForegroundTimeMillis;
                    if (userEnteredTime > latestEnteredTime) {
                        latestEnteredTime = userEnteredTime;
                        previousUser = userId;
                    }
                }
            }
        }
        return previousUser;
    }

    @Override
    public @UserIdInt int getCommunalProfileId() {
        checkQueryOrCreateUsersPermission("get communal profile user id");
        return getCommunalProfileIdUnchecked();
    }

    /** Returns the currently-designated communal profile, or USER_NULL if not present. */
    private @UserIdInt int getCommunalProfileIdUnchecked() {
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                final UserInfo user = mUsers.valueAt(i).info;
                if (user.isCommunalProfile() && !mRemovingUserIds.get(user.id)) {
                    return user.id;
                }
            }
        }
        return UserHandle.USER_NULL;
    }

    public @NonNull List<UserInfo> getUsers(boolean excludeDying) {
        return getUsers(/*excludePartial= */ true, excludeDying, /* excludePreCreated= */
                true);
    }

    @Override
    public @NonNull List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying,
            boolean excludePreCreated) {
        checkCreateUsersPermission("query users");
        return getUsersInternal(excludePartial, excludeDying, excludePreCreated);
    }

    private @NonNull List<UserInfo> getUsersInternal(boolean excludePartial, boolean excludeDying,
            boolean excludePreCreated) {
        synchronized (mUsersLock) {
            ArrayList<UserInfo> users = new ArrayList<>(mUsers.size());
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if ((excludePartial && ui.partial)
                        || (excludeDying && mRemovingUserIds.get(ui.id))
                        || (excludePreCreated && ui.preCreated)) {
                    continue;
                }
                users.add(userWithName(ui));
            }
            return users;
        }
    }

    @Override
    public List<UserInfo> getProfiles(@UserIdInt int userId, boolean enabledOnly) {
        boolean returnFullInfo;
        if (userId != UserHandle.getCallingUserId()) {
            checkQueryOrCreateUsersPermission("getting profiles related to user " + userId);
            returnFullInfo = true;
        } else {
            returnFullInfo = hasCreateUsersPermission();
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mUsersLock) {
                return getProfilesLU(userId, /* userType */ null, enabledOnly, returnFullInfo);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    // TODO(b/142482943): Will probably need a getProfiles(userType). But permissions may vary.

    @Override
    public int[] getProfileIds(@UserIdInt int userId, boolean enabledOnly) {
        return getProfileIds(userId, null, enabledOnly, /* excludeHidden */ false);
    }

    // TODO(b/142482943): Probably @Override and make this accessible in UserManager.
    /**
     * Returns all the users of type userType that are in the same profile group as userId
     * (including userId itself, if it is of the appropriate user type).
     *
     * <p>If userType is non-{@code null}, only returns users that are of type userType.
     * If enabledOnly, only returns users that are not {@link UserInfo#FLAG_DISABLED}.
     */
    public int[] getProfileIds(@UserIdInt int userId, @Nullable String userType,
            boolean enabledOnly, boolean excludeHidden) {
        if (userId != UserHandle.getCallingUserId()) {
            checkQueryOrCreateUsersPermission("getting profiles related to user " + userId);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mUsersLock) {
                return getProfileIdsLU(userId, userType, enabledOnly, excludeHidden).toArray();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** Assume permissions already checked and caller's identity cleared */
    @GuardedBy("mUsersLock")
    private List<UserInfo> getProfilesLU(@UserIdInt int userId, @Nullable String userType,
            boolean enabledOnly, boolean fullInfo) {
        IntArray profileIds = getProfileIdsLU(userId, userType, enabledOnly, /* excludeHidden */
                false);
        ArrayList<UserInfo> users = new ArrayList<>(profileIds.size());
        for (int i = 0; i < profileIds.size(); i++) {
            int profileId = profileIds.get(i);
            UserInfo userInfo = mUsers.get(profileId).info;
            // If full info is not required - clear PII data to prevent 3P apps from reading it
            if (!fullInfo) {
                userInfo = new UserInfo(userInfo);
                userInfo.name = null;
                userInfo.iconPath = null;
            } else {
                userInfo = userWithName(userInfo);
            }
            users.add(userInfo);
        }
        return users;
    }

    /**
     *  Assume permissions already checked and caller's identity cleared
     *  <p>If userType is {@code null}, returns all profiles for user; else, only returns
     *  profiles of that type.
     */
    @GuardedBy("mUsersLock")
    private IntArray getProfileIdsLU(@UserIdInt int userId, @Nullable String userType,
            boolean enabledOnly, boolean excludeHidden) {
        UserInfo user = getUserInfoLU(userId);
        IntArray result = new IntArray(mUsers.size());
        if (user == null) {
            // Probably a dying user
            return result;
        }
        final int userSize = mUsers.size();
        for (int i = 0; i < userSize; i++) {
            UserInfo profile = mUsers.valueAt(i).info;
            if (!isProfileOf(user, profile)) {
                continue;
            }
            if (enabledOnly && !profile.isEnabled()) {
                continue;
            }
            if (mRemovingUserIds.get(profile.id)) {
                continue;
            }
            if (profile.partial) {
                continue;
            }
            if (userType != null && !userType.equals(profile.userType)) {
                continue;
            }
            if (excludeHidden && isProfileHidden(profile.id)) {
                continue;
            }
            result.add(profile.id);
        }
        return result;
    }

    /*
     * Returns all the users that are in the same profile group as userId excluding those with
     * {@link UserProperties#getProfileApiVisibility()} set to hidden. The returned list includes
     * the user itself.
     */
    // TODO (b/323011770): Add a permission check to make an exception for App stores if we end
    //  up supporting Private Space on COPE devices
    @Override
    public int[] getProfileIdsExcludingHidden(@UserIdInt int userId, boolean enabledOnly) {
        return getProfileIds(userId, null, enabledOnly, /* excludeHidden */ true);
    }

    private boolean isProfileHidden(int userId) {
        UserProperties userProperties = getUserPropertiesCopy(userId);
        if (android.os.Flags.allowPrivateProfile()
                && android.multiuser.Flags.enableHidingProfiles()
                && android.multiuser.Flags.enablePrivateSpaceFeatures()) {
            return userProperties.getProfileApiVisibility()
                    == UserProperties.PROFILE_API_VISIBILITY_HIDDEN;
        }
        return false;
    }

    @Override
    public int getCredentialOwnerProfile(@UserIdInt int userId) {
        checkManageUsersPermission("get the credential owner");
        if (!mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)) {
            synchronized (mUsersLock) {
                UserInfo profileParent = getProfileParentLU(userId);
                if (profileParent != null) {
                    return profileParent.id;
                }
            }
        }

        return userId;
    }

    @Override
    public boolean isSameProfileGroup(@UserIdInt int userId, int otherUserId) {
        if (userId == otherUserId) return true;
        checkQueryUsersPermission("check if in the same profile group");
        return isSameProfileGroupNoChecks(userId, otherUserId);
    }

    /**
     * Returns whether users are in the same non-empty profile group.
     * Currently, false if empty profile group, even if they are the same user, for whatever reason.
     */
    private boolean isSameProfileGroupNoChecks(@UserIdInt int userId, int otherUserId) {
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            if (userInfo == null || userInfo.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID) {
                return false;
            }
            UserInfo otherUserInfo = getUserInfoLU(otherUserId);
            if (otherUserInfo == null
                    || otherUserInfo.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID) {
                return false;
            }
            return userInfo.profileGroupId == otherUserInfo.profileGroupId;
        }
    }

    /**
     * Returns whether users are in the same profile group, or if the target is a communal profile.
     */
    private boolean isSameUserOrProfileGroupOrTargetIsCommunal(UserInfo asker, UserInfo target) {
        if (asker.id == target.id) return true;
        if (android.multiuser.Flags.supportCommunalProfile()) {
            if (target.isCommunalProfile()) return true;
        }
        return (asker.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                && asker.profileGroupId == target.profileGroupId);
    }

    @Override
    public UserInfo getProfileParent(@UserIdInt int userId) {
        if (!hasManageUsersOrPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)) {
            throw new SecurityException(
                    "You need MANAGE_USERS or INTERACT_ACROSS_USERS permission to get the "
                            + "profile parent");
        }
        synchronized (mUsersLock) {
            return getProfileParentLU(userId);
        }
    }

    @Override
    public int getProfileParentId(@UserIdInt int userId) {
        checkManageUsersPermission("get the profile parent");
        return getProfileParentIdUnchecked(userId);
    }

    private @UserIdInt int getProfileParentIdUnchecked(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            UserInfo profileParent = getProfileParentLU(userId);
            if (profileParent == null) {
                return userId;
            }
            return profileParent.id;
        }
    }


    @GuardedBy("mUsersLock")
    private UserInfo getProfileParentLU(@UserIdInt int userId) {
        UserInfo profile = getUserInfoLU(userId);
        if (profile == null) {
            return null;
        }
        int parentUserId = profile.profileGroupId;
        if (parentUserId == userId || parentUserId == UserInfo.NO_PROFILE_GROUP_ID) {
            return null;
        } else {
            return getUserInfoLU(parentUserId);
        }
    }

    private static boolean isProfileOf(UserInfo user, UserInfo profile) {
        return user.id == profile.id ||
                (user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                && user.profileGroupId == profile.profileGroupId);
    }

    private String getAvailabilityIntentAction(boolean enableQuietMode, boolean useManagedActions) {
        return useManagedActions ?
                enableQuietMode ?
                        Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE
                        : Intent.ACTION_MANAGED_PROFILE_AVAILABLE
                : enableQuietMode ?
                        Intent.ACTION_PROFILE_UNAVAILABLE
                        : Intent.ACTION_PROFILE_AVAILABLE;
    }

    private void broadcastProfileAvailabilityChanges(UserInfo profileInfo,
            UserHandle parentHandle, boolean enableQuietMode, boolean useManagedActions) {
        Intent availabilityIntent = new Intent();
        availabilityIntent.setAction(
                getAvailabilityIntentAction(enableQuietMode, useManagedActions));
        availabilityIntent.putExtra(Intent.EXTRA_QUIET_MODE, enableQuietMode);
        availabilityIntent.putExtra(Intent.EXTRA_USER, profileInfo.getUserHandle());
        availabilityIntent.putExtra(Intent.EXTRA_USER_HANDLE,
                profileInfo.getUserHandle().getIdentifier());
        if (profileInfo.isManagedProfile()) {
            getDevicePolicyManagerInternal().broadcastIntentToManifestReceivers(
                    availabilityIntent, parentHandle, /* requiresPermission= */ true);
        }
        availabilityIntent.addFlags(
                Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);

        // TODO(b/302708423): Restrict the apps that can receive these intents in case of a private
        //  profile.
        final Bundle options = new BroadcastOptions()
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
                .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                // Both actions use single namespace because only the final state matters.
                .setDeliveryGroupMatchingKey(
                        useManagedActions ? Intent.ACTION_MANAGED_PROFILE_AVAILABLE
                                : Intent.ACTION_PROFILE_AVAILABLE,
                        String.valueOf(profileInfo.getUserHandle().getIdentifier()) /* key */)
                .toBundle();
        mContext.sendBroadcastAsUser(availabilityIntent, parentHandle, /* receiverPermission= */
                null, options);
    }

    @Override
    public boolean requestQuietModeEnabled(@NonNull String callingPackage, boolean enableQuietMode,
            @UserIdInt int userId, @Nullable IntentSender target, @QuietModeFlag int flags) {
        Objects.requireNonNull(callingPackage);

        if (enableQuietMode && target != null) {
            throw new IllegalArgumentException(
                    "target should only be specified when we are disabling quiet mode.");
        }

        final boolean dontAskCredential =
                (flags & UserManager.QUIET_MODE_DISABLE_DONT_ASK_CREDENTIAL) != 0;
        final boolean onlyIfCredentialNotRequired =
                (flags & UserManager.QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED) != 0;
        if (dontAskCredential && onlyIfCredentialNotRequired) {
            throw new IllegalArgumentException("invalid flags: " + flags);
        }

        ensureCanModifyQuietMode(
                callingPackage, Binder.getCallingUid(), userId, target != null, dontAskCredential);

        if (onlyIfCredentialNotRequired && callingPackage.equals(
                getPackageManagerInternal().getSystemUiServiceComponent().getPackageName())) {
            // This is to prevent SysUI from accidentally allowing the profile to turned on
            // without password when keyguard is still locked.
            throw new SecurityException("SystemUI is not allowed to set "
                    + "QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED");
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            // QUIET_MODE_DISABLE_DONT_ASK_CREDENTIAL is only allowed for managed-profiles
            if (dontAskCredential) {
                UserInfo userInfo;
                synchronized (mUsersLock) {
                    userInfo = getUserInfo(userId);
                }
                if (userInfo == null) {
                    throw new IllegalArgumentException("Invalid user. Can't find user details "
                            + "for userId " + userId);
                }
                if (!userInfo.isManagedProfile()) {
                    throw new IllegalArgumentException("Invalid flags: " + flags
                            + ". Can't skip credential check for the user");
                }
            }
            if (enableQuietMode) {
                setQuietModeEnabled(userId, true /* enableQuietMode */, target, callingPackage);
                return true;
            }
            if (android.os.Flags.allowPrivateProfile()
                    && android.multiuser.Flags.enablePrivateSpaceFeatures()) {
                final UserProperties userProperties = getUserPropertiesInternal(userId);
                if (userProperties != null
                        && userProperties.isAuthAlwaysRequiredToDisableQuietMode()) {
                    if (onlyIfCredentialNotRequired) {
                        return false;
                    }

                    final KeyguardManager km = mContext.getSystemService(KeyguardManager.class);
                    int parentUserId = getProfileParentId(userId);
                    if (km != null && km.isDeviceSecure(parentUserId)) {
                        showConfirmCredentialToDisableQuietMode(userId, target, callingPackage);
                        return false;
                    } else if (km != null && !km.isDeviceSecure(parentUserId)
                            && android.multiuser.Flags.showSetScreenLockDialog()
                            // TODO(b/330720545): Add a better way to accomplish this, also use it
                            //  to block profile creation w/o device credentials present.
                            && Settings.Secure.getIntForUser(mContext.getContentResolver(),
                                Settings.Secure.USER_SETUP_COMPLETE, 0, userId) == 1) {
                        Intent setScreenLockPromptIntent =
                                SetScreenLockDialogActivity
                                        .createBaseIntent(LAUNCH_REASON_DISABLE_QUIET_MODE);
                        setScreenLockPromptIntent.putExtra(EXTRA_ORIGIN_USER_ID, userId);
                        mContext.startActivityAsUser(setScreenLockPromptIntent,
                                UserHandle.of(parentUserId));
                        return false;
                    } else {
                        Slog.w(LOG_TAG, "Allowing profile unlock even when device credentials "
                                + "are not set for user " + userId);
                    }
                }
            }
            final boolean hasUnifiedChallenge =
                    mLockPatternUtils.isManagedProfileWithUnifiedChallenge(userId);
            if (hasUnifiedChallenge) {
                KeyguardManager km = mContext.getSystemService(KeyguardManager.class);
                // Normally only attempt to auto-unlock unified challenge if keyguard is not showing
                // (to stop turning profile on automatically via the QS tile), except when we
                // are called with QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED, in which
                // case always attempt to auto-unlock.
                if (!km.isDeviceLocked(mLocalService.getProfileParentId(userId))
                        || onlyIfCredentialNotRequired) {
                    mLockPatternUtils.tryUnlockWithCachedUnifiedChallenge(userId);
                }
            }
            final boolean needToShowConfirmCredential = !dontAskCredential
                    && mLockPatternUtils.isSecure(userId)
                    && (!hasUnifiedChallenge || !StorageManager.isCeStorageUnlocked(userId));
            if (needToShowConfirmCredential) {
                if (onlyIfCredentialNotRequired) {
                    return false;
                }
                showConfirmCredentialToDisableQuietMode(userId, target, callingPackage);
                return false;
            }
            setQuietModeEnabled(userId, false /* enableQuietMode */, target, callingPackage);
            return true;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * The caller can modify quiet mode if it meets one of these conditions:
     * <ul>
     *     <li>Has system UID or root UID</li>
     *     <li>Has {@link Manifest.permission#MODIFY_QUIET_MODE}</li>
     *     <li>Has {@link Manifest.permission#MANAGE_USERS}</li>
     *     <li>Is the foreground default launcher app</li>
     * </ul>
     * <p>
     * If caller wants to start an intent after disabling the quiet mode, or if it is targeting a
     * user in a different profile group from the caller, it must have
     * {@link Manifest.permission#MANAGE_USERS}.
     */
    private void ensureCanModifyQuietMode(String callingPackage, int callingUid,
            @UserIdInt int targetUserId, boolean startIntent, boolean dontAskCredential) {
        verifyCallingPackage(callingPackage, callingUid);

        if (hasManageUsersPermission()) {
            return;
        }
        if (startIntent) {
            throw new SecurityException("MANAGE_USERS permission is required to start intent "
                    + "after disabling quiet mode.");
        }
        if (dontAskCredential) {
            throw new SecurityException("MANAGE_USERS permission is required to disable quiet "
                    + "mode without credentials.");
        }
        if (!isSameProfileGroupNoChecks(UserHandle.getUserId(callingUid), targetUserId)) {
            throw new SecurityException("MANAGE_USERS permission is required to modify quiet mode "
                    + "for a different profile group.");
        }
        final boolean hasModifyQuietModePermission = hasPermissionGranted(
                Manifest.permission.MODIFY_QUIET_MODE, callingUid);
        if (hasModifyQuietModePermission) {
            return;
        }

        final ShortcutServiceInternal shortcutInternal =
                LocalServices.getService(ShortcutServiceInternal.class);
        if (shortcutInternal != null) {
            boolean isForegroundLauncher =
                    shortcutInternal.isForegroundDefaultLauncher(callingPackage, callingUid);
            if (isForegroundLauncher) {
                return;
            }
        }
        throw new SecurityException("Can't modify quiet mode, caller is neither foreground "
                + "default launcher nor has MANAGE_USERS/MODIFY_QUIET_MODE permission");
    }

    private void setQuietModeEnabled(@UserIdInt int userId, boolean enableQuietMode,
            IntentSender target, @Nullable String callingPackage) {
        final UserInfo profile, parent;
        final UserData profileUserData;
        synchronized (mUsersLock) {
            profile = getUserInfoLU(userId);
            parent = getProfileParentLU(userId);

            if (profile == null || !profile.isProfile()) {
                throw new IllegalArgumentException("User " + userId + " is not a profile");
            }
            if (profile.isQuietModeEnabled() == enableQuietMode) {
                Slog.i(LOG_TAG, "Quiet mode is already " + enableQuietMode);
                return;
            }
            profile.flags ^= UserInfo.FLAG_QUIET_MODE;
            profileUserData = getUserDataLU(profile.id);
        }
        synchronized (mPackagesLock) {
            writeUserLP(profileUserData);
        }

        try {
            if (enableQuietMode) {
                stopUserForQuietMode(userId);
                LocalServices.getService(ActivityManagerInternal.class)
                        .killForegroundAppsForUser(userId);
            } else {
                IProgressListener callback = target != null
                        ? new DisableQuietModeUserUnlockedCallback(target)
                        : null;
                ActivityManager.getService().startProfileWithListener(userId, callback);
            }
        } catch (RemoteException e) {
            // Should not happen, same process.
            e.rethrowAsRuntimeException();
        }

        logQuietModeEnabled(userId, enableQuietMode, callingPackage);

        // Broadcast generic intents for all profiles
        if (android.os.Flags.allowPrivateProfile()
                && android.multiuser.Flags.enablePrivateSpaceFeatures()) {
            broadcastProfileAvailabilityChanges(profile, parent.getUserHandle(),
                    enableQuietMode, false);
        }
        // Broadcast Managed profile availability intents too for managed profiles.
        if (profile.isManagedProfile()){
            broadcastProfileAvailabilityChanges(profile, parent.getUserHandle(),
                     enableQuietMode, true);
        }
    }

    private void stopUserForQuietMode(int userId) throws RemoteException {
        if (android.os.Flags.allowPrivateProfile()
                && android.multiuser.Flags.enableBiometricsToUnlockPrivateSpace()
                && android.multiuser.Flags.enablePrivateSpaceFeatures()) {
            // Allow delayed locking since some profile types want to be able to unlock again via
            // biometrics.
            ActivityManager.getService().stopUserWithDelayedLocking(userId, null);
            return;
        }
        ActivityManager.getService().stopUserWithCallback(userId, null);
    }

    private void logQuietModeEnabled(@UserIdInt int userId, boolean enableQuietMode,
            @Nullable String callingPackage) {
        Slogf.i(LOG_TAG,
                "requestQuietModeEnabled called by package %s, with enableQuietMode %b.",
                callingPackage,
                enableQuietMode);
        UserData userData;
        synchronized (mUsersLock) {
            userData = getUserDataLU(userId);
        }
        if (userData == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long period = (userData.getLastRequestQuietModeEnabledMillis() != 0L
                ? now - userData.getLastRequestQuietModeEnabledMillis()
                : now - userData.info.creationTime);
        DevicePolicyEventLogger
                .createEvent(DevicePolicyEnums.REQUEST_QUIET_MODE_ENABLED)
                .setInt(UserJourneyLogger.getUserTypeForStatsd(userData.info.userType))
                .setStrings(callingPackage)
                .setBoolean(enableQuietMode)
                .setTimePeriod(period)
                .write();
        userData.setLastRequestQuietModeEnabledMillis(now);
    }

    @Override
    public boolean isQuietModeEnabled(@UserIdInt int userId) {
        synchronized (mPackagesLock) {
            UserInfo info;
            synchronized (mUsersLock) {
                info = getUserInfoLU(userId);
            }
            if (info == null || !info.isProfile()) {
                return false;
            }
            return info.isQuietModeEnabled();
        }
    }

    /**
     * Show confirm credential screen to unlock user in order to turn off quiet mode.
     */
    private void showConfirmCredentialToDisableQuietMode(
            @UserIdInt int userId, @Nullable IntentSender target, @Nullable String callingPackage) {
        if (android.app.admin.flags.Flags.quietModeCredentialBugFix()) {
            if (!android.multiuser.Flags.restrictQuietModeCredentialBugFixToManagedProfiles()
                    || getUserInfo(userId).isManagedProfile()) {
                // TODO (b/308121702) It may be brittle to rely on user states to check managed
                //  profile state
                int state;
                synchronized (mUserStates) {
                    state = mUserStates.get(userId, UserState.STATE_NONE);
                }
                if (state != UserState.STATE_NONE) {
                    Slog.i(LOG_TAG,
                            "showConfirmCredentialToDisableQuietMode() called too early, managed "
                                    + "user " + userId + " is still alive.");
                    return;
                }
            }
        }
        // otherwise, we show a profile challenge to trigger decryption of the user
        final KeyguardManager km = (KeyguardManager) mContext.getSystemService(
                Context.KEYGUARD_SERVICE);
        // We should use userId not credentialOwnerUserId here, as even if it is unified
        // lock, confirm screenlock page will know and show personal challenge, and unlock
        // work profile when personal challenge is correct
        final Intent unlockIntent = km.createConfirmDeviceCredentialIntent(null, null, userId);
        if (unlockIntent == null) {
            return;
        }
        final Intent callBackIntent = new Intent(
                ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK);
        if (target != null) {
            callBackIntent.putExtra(Intent.EXTRA_INTENT, target);
        }
        callBackIntent.putExtra(EXTRA_USER_ID, userId);
        callBackIntent.setPackage(mContext.getPackageName());
        callBackIntent.putExtra(Intent.EXTRA_PACKAGE_NAME, callingPackage);
        callBackIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(
                mContext,
                0,
                callBackIntent,
                PendingIntent.FLAG_CANCEL_CURRENT |
                        PendingIntent.FLAG_ONE_SHOT |
                        PendingIntent.FLAG_IMMUTABLE);
        // After unlocking the challenge, it will disable quiet mode and run the original
        // intentSender
        unlockIntent.putExtra(Intent.EXTRA_INTENT, pendingIntent.getIntentSender());
        unlockIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        if (Flags.enablePrivateSpaceFeatures() && Flags.usePrivateSpaceIconInBiometricPrompt()
                && getUserInfo(userId).isPrivateProfile()) {
            unlockIntent.putExtra(CUSTOM_BIOMETRIC_PROMPT_LOGO_RES_ID_KEY,
                    com.android.internal.R.drawable.stat_sys_private_profile_status);
            unlockIntent.putExtra(CUSTOM_BIOMETRIC_PROMPT_LOGO_DESCRIPTION_KEY,
                    mContext.getString(R.string.private_space_biometric_prompt_title));
        }
        mContext.startActivityAsUser(
                unlockIntent, UserHandle.of(getProfileParentIdUnchecked(userId)));
    }

    @Override
    public void setUserEnabled(@UserIdInt int userId) {
        checkManageUsersPermission("enable user");
        UserInfo info;
        boolean wasUserDisabled = false;
        synchronized (mPackagesLock) {
            synchronized (mUsersLock) {
                info = getUserInfoLU(userId);
                if (info != null && !info.isEnabled()) {
                    wasUserDisabled = true;
                    info.flags ^= UserInfo.FLAG_DISABLED;
                    writeUserLP(getUserDataLU(info.id));
                }
            }
        }
        if (wasUserDisabled && info != null && info.isProfile()) {
            sendProfileAddedBroadcast(info.profileGroupId, info.id);
        }
    }

    @Override
    public void setUserAdmin(@UserIdInt int userId) {
        checkManageUserAndAcrossUsersFullPermission("set user admin");
        mUserJourneyLogger.logUserJourneyBegin(userId, USER_JOURNEY_GRANT_ADMIN);
        UserData user;
        synchronized (mPackagesLock) {
            synchronized (mUsersLock) {
                user = getUserDataLU(userId);
                if (user == null) {
                    // Exit if no user found with that id,
                    mUserJourneyLogger.logNullUserJourneyError(USER_JOURNEY_GRANT_ADMIN,
                        getCurrentUserId(), userId, /* userType */ "", /* userFlags */ -1);
                    return;
                } else if (user.info.isAdmin()) {
                    // Exit if the user is already an Admin.
                    mUserJourneyLogger.logUserJourneyFinishWithError(getCurrentUserId(),
                        user.info, USER_JOURNEY_GRANT_ADMIN,
                        ERROR_CODE_USER_ALREADY_AN_ADMIN);
                    return;
                }
                user.info.flags ^= UserInfo.FLAG_ADMIN;
                writeUserLP(user);
            }
        }
        mUserJourneyLogger.logUserJourneyFinishWithError(getCurrentUserId(), user.info,
                USER_JOURNEY_GRANT_ADMIN, ERROR_CODE_UNSPECIFIED);
    }

    @Override
    public void revokeUserAdmin(@UserIdInt int userId) {
        checkManageUserAndAcrossUsersFullPermission("revoke admin privileges");
        mUserJourneyLogger.logUserJourneyBegin(userId, USER_JOURNEY_REVOKE_ADMIN);
        UserData user;
        synchronized (mPackagesLock) {
            synchronized (mUsersLock) {
                user = getUserDataLU(userId);
                if (user == null) {
                    // Exit if no user found with that id
                    mUserJourneyLogger.logNullUserJourneyError(
                            USER_JOURNEY_REVOKE_ADMIN,
                            getCurrentUserId(), userId, "", -1);
                    return;
                } else if (!user.info.isAdmin()) {
                    // Exit if no user is not an Admin.
                    mUserJourneyLogger.logUserJourneyFinishWithError(getCurrentUserId(), user.info,
                            USER_JOURNEY_REVOKE_ADMIN, ERROR_CODE_USER_IS_NOT_AN_ADMIN);
                    return;
                }
                user.info.flags ^= UserInfo.FLAG_ADMIN;
                writeUserLP(user);
            }
        }
        mUserJourneyLogger.logUserJourneyFinishWithError(getCurrentUserId(), user.info,
                USER_JOURNEY_REVOKE_ADMIN, ERROR_CODE_UNSPECIFIED);
    }

    /**
     * Evicts a user's CE key by stopping and restarting the user.
     *
     * The key is evicted automatically by the user controller when the user has stopped.
     */
    @Override
    public void evictCredentialEncryptionKey(@UserIdInt int userId) {
        checkManageUsersPermission("evict CE key");
        final IActivityManager am = ActivityManagerNative.getDefault();
        final long identity = Binder.clearCallingIdentity();
        // TODO(b/280054081): save userStartMode when user started and re-use it here instead
        final int userStartMode = isProfileUnchecked(userId)
                ? UserManagerInternal.USER_START_MODE_BACKGROUND_VISIBLE
                : UserManagerInternal.USER_START_MODE_BACKGROUND;
        try {
            am.restartUserInBackground(userId, userStartMode);
        } catch (RemoteException re) {
            throw re.rethrowAsRuntimeException();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    /**
     * Returns whether the given user (specified by userId) is of the given user type, such as
     * {@link UserManager#USER_TYPE_FULL_GUEST}.
     */
    @Override
    public boolean isUserOfType(@UserIdInt int userId, String userType) {
        checkQueryOrCreateUsersPermission("check user type");
        return userType != null && userType.equals(getUserTypeNoChecks(userId));
    }

    /**
     * Returns the user type of the given userId, or null if the user doesn't exist.
     * <p>No permissions checks are made (but userId checks may be made).
     */
    private @Nullable String getUserTypeNoChecks(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            final UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null ? userInfo.userType : null;
        }
    }

    /**
     * Returns the UserTypeDetails of the given userId's user type, or null if the no such user.
     * <p>No permissions checks are made (but userId checks may be made).
     */
    private @Nullable UserTypeDetails getUserTypeDetailsNoChecks(@UserIdInt int userId) {
        final String typeStr = getUserTypeNoChecks(userId);
        return typeStr != null ? mUserTypes.get(typeStr) : null;
    }

    /**
     * Returns the UserTypeDetails of the given userInfo's user type (or null for a null userInfo).
     */
    private @Nullable UserTypeDetails getUserTypeDetails(@Nullable UserInfo userInfo) {
        final String typeStr = userInfo != null ? userInfo.userType : null;
        return typeStr != null ? mUserTypes.get(typeStr) : null;
    }

    @Override
    public UserInfo getUserInfo(@UserIdInt int userId) {
        checkQueryOrCreateUsersPermission("query user");
        synchronized (mUsersLock) {
            return userWithName(getUserInfoLU(userId));
        }
    }

    /**
     * Returns a UserInfo object with the name filled in, for Owner and Guest, or the original
     * if the name is already set.
     *
     * Note: Currently, the resulting name can be null if a user was truly created with a null name.
     */
    private UserInfo userWithName(UserInfo orig) {
        if (orig != null && orig.name == null) {
            String name = null;
            if (orig.id == UserHandle.USER_SYSTEM) {
                if (DBG_ALLOCATION) {
                    final int number = mUser0Allocations.incrementAndGet();
                    Slog.w(LOG_TAG, "System user instantiated at least " + number + " times");
                }
                name = getOwnerName();
            } else if (orig.isMain()) {
                name = getOwnerName();
            } else if (orig.isGuest()) {
                name = getGuestName();
            }
            if (name != null) {
                final UserInfo withName = new UserInfo(orig);
                withName.name = name;
                return withName;
            }
        }
        return orig;
    }

    /** Returns whether the given user type is one of the FULL user types. */
    boolean isUserTypeSubtypeOfFull(String userType) {
        UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        return userTypeDetails != null && userTypeDetails.isFull();
    }

    /** Returns whether the given user type is one of the PROFILE user types. */
    boolean isUserTypeSubtypeOfProfile(String userType) {
        UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        return userTypeDetails != null && userTypeDetails.isProfile();
    }

    /** Returns whether the given user type is one of the SYSTEM user types. */
    boolean isUserTypeSubtypeOfSystem(String userType) {
        UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        return userTypeDetails != null && userTypeDetails.isSystem();
    }

    /**
     * Returns a *copy* of the given user's UserProperties, stripping out any information for which
     * the caller lacks permission.
     */
    @Override
    public @NonNull UserProperties getUserPropertiesCopy(@UserIdInt int userId) {
        checkQueryOrInteractPermissionIfCallerInOtherProfileGroup(userId, "getUserProperties");
        final UserProperties origProperties = getUserPropertiesInternal(userId);
        if (origProperties != null) {
            boolean exposeAllFields = Binder.getCallingUid() == Process.SYSTEM_UID;
            boolean hasManage = hasManageUsersPermission();
            boolean hasQuery = hasQueryUsersPermission();
            return new UserProperties(origProperties, exposeAllFields, hasManage, hasQuery);
        }
        // A non-existent or partial user will reach here.
        throw new IllegalArgumentException("Cannot access properties for user " + userId);
    }

    /** Returns the user's actual, canonical UserProperties object. Do not edit it externally. */
    private @Nullable UserProperties getUserPropertiesInternal(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            final UserData userData = getUserDataLU(userId);
            if (userData != null) {
                return userData.userProperties;
            }
        }
        return null;
    }

    @Override
    public boolean hasBadge(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "hasBadge");
        final UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(userId);
        return userTypeDetails != null && userTypeDetails.hasBadge();
    }

    @Override
    public @StringRes int getUserBadgeLabelResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "getUserBadgeLabelResId");
        final UserInfo userInfo = getUserInfoNoChecks(userId);
        final UserTypeDetails userTypeDetails = getUserTypeDetails(userInfo);
        if (userInfo == null || userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested badge label for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        final int badgeIndex = userInfo.profileBadge;
        return userTypeDetails.getBadgeLabel(badgeIndex);
    }

    /**
     * @return the color (not the resource ID) to be used for the user's badge in light theme
     */
    @Override
    public @ColorRes int getUserBadgeColorResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "getUserBadgeColorResId");
        final UserInfo userInfo = getUserInfoNoChecks(userId);
        final UserTypeDetails userTypeDetails = getUserTypeDetails(userInfo);
        if (userInfo == null || userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested badge dark color for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        return userTypeDetails.getBadgeColor(userInfo.profileBadge);
    }

    /**
     * @return the color (not the resource ID) to be used for the user's badge in dark theme
     */
    @Override
    public @ColorRes int getUserBadgeDarkColorResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "getUserBadgeDarkColorResId");
        final UserInfo userInfo = getUserInfoNoChecks(userId);
        final UserTypeDetails userTypeDetails = getUserTypeDetails(userInfo);
        if (userInfo == null || userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested badge color for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        return userTypeDetails.getDarkThemeBadgeColor(userInfo.profileBadge);
    }

    @Override
    public @DrawableRes int getUserIconBadgeResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "getUserIconBadgeResId");
        final UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(userId);
        if (userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested icon badge for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        return userTypeDetails.getIconBadge();
    }

    @Override
    public @DrawableRes int getUserBadgeResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "getUserBadgeResId");
        final UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(userId);
        if (userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested badge for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        return userTypeDetails.getBadgePlain();
    }

    @Override
    public @DrawableRes int getUserBadgeNoBackgroundResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "getUserBadgeNoBackgroundResId");
        final UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(userId);
        if (userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.e(LOG_TAG, "Requested badge (no background) for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        return userTypeDetails.getBadgeNoBackground();
    }

    @Override
    public @DrawableRes int getUserStatusBarIconResId(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "getUserStatusBarIconResId");
        final UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(userId);
        if (userTypeDetails == null || !userTypeDetails.hasBadge()) {
            Slog.w(LOG_TAG, "Requested status bar icon for non-badged user " + userId);
            return Resources.ID_NULL;
        }
        return userTypeDetails.getStatusBarIcon();
    }

    @Override
    public @StringRes int getProfileLabelResId(@UserIdInt int userId) {
        checkQueryOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "getProfileLabelResId");
        final UserInfo userInfo = getUserInfoNoChecks(userId);
        final UserTypeDetails userTypeDetails = getUserTypeDetails(userInfo);
        if (userInfo == null || userTypeDetails == null) {
            return Resources.ID_NULL;
        }
        final int userIndex = userInfo.profileBadge;
        return userTypeDetails.getLabel(userIndex);
    }

    @Override
    public @StringRes int getProfileAccessibilityLabelResId(@UserIdInt int userId) {
        checkQueryOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "getProfileAccessibilityLabelResId");
        final UserInfo userInfo = getUserInfoNoChecks(userId);
        final UserTypeDetails userTypeDetails = getUserTypeDetails(userInfo);
        if (userInfo == null || userTypeDetails == null) {
            return Resources.ID_NULL;
        }
        return userTypeDetails.getAccessibilityString();
    }

    public boolean isProfile(@UserIdInt int userId) {
        checkQueryOrInteractPermissionIfCallerInOtherProfileGroup(userId, "isProfile");
        return isProfileUnchecked(userId);
    }

    private boolean isProfileUnchecked(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.isProfile();
        }
    }

    /**
     * Returns the user type (if it is a profile), empty string (if it isn't a profile),
     * or null (if the user doesn't exist).
     */
    @Override
    public @Nullable String getProfileType(@UserIdInt int userId) {
        checkQueryOrInteractPermissionIfCallerInOtherProfileGroup(userId, "getProfileType");
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            if (userInfo != null) {
                return userInfo.isProfile() ? userInfo.userType : "";
            }
            return null;
        }
    }

    @Override
    public boolean isUserUnlockingOrUnlocked(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId,
                "isUserUnlockingOrUnlocked");
        return mLocalService.isUserUnlockingOrUnlocked(userId);
    }

    @Override
    public boolean isUserUnlocked(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "isUserUnlocked");
        return mLocalService.isUserUnlocked(userId);
    }

    @Override
    public boolean isUserRunning(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "isUserRunning");
        return mLocalService.isUserRunning(userId);
    }

    @Override
    public boolean isUserForeground(@UserIdInt int userId) {
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId
                && !hasManageUsersOrPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)) {
            throw new SecurityException("Caller from user " + callingUserId + " needs MANAGE_USERS "
                    + "or INTERACT_ACROSS_USERS permission to check if another user (" + userId
                    + ") is running in the foreground");
        }

        return userId == getCurrentUserId();
    }

    @Override
    public boolean isUserVisible(@UserIdInt int userId) {
        int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId
                && !hasManageUsersOrPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)) {
            throw new SecurityException("Caller from user " + callingUserId + " needs MANAGE_USERS "
                    + "or INTERACT_ACROSS_USERS permission to check if another user (" + userId
                    + ") is visible");
        }

        return mUserVisibilityMediator.isUserVisible(userId);
    }

    /**
     * Gets the current and target user ids as a {@link Pair}, calling
     * {@link ActivityManagerInternal} directly (and without performing any permission check).
     *
     * @return ids of current foreground user and the target user. Target user will be
     * {@link UserHandle#USER_NULL} if there is not an ongoing user switch. And if
     * {@link ActivityManagerInternal} is not available yet, they will both be
     * {@link UserHandle#USER_NULL}.
     */
    @VisibleForTesting
    @NonNull
    Pair<Integer, Integer> getCurrentAndTargetUserIds() {
        ActivityManagerInternal activityManagerInternal = getActivityManagerInternal();
        if (activityManagerInternal == null) {
            Slog.w(LOG_TAG, "getCurrentAndTargetUserId() called too early, "
                    + "ActivityManagerInternal is not set yet");
            return new Pair<>(UserHandle.USER_NULL, UserHandle.USER_NULL);
        }
        return activityManagerInternal.getCurrentAndTargetUserIds();
    }

    /**
     * Gets the current user id, calling {@link ActivityManagerInternal} directly (and without
     * performing any permission check).
     *
     * @return id of current foreground user, or {@link UserHandle#USER_NULL} if
     * {@link ActivityManagerInternal} is not available yet.
     */
    @VisibleForTesting
    int getCurrentUserId() {
        ActivityManagerInternal activityManagerInternal = getActivityManagerInternal();
        if (activityManagerInternal == null) {
            Slog.w(LOG_TAG, "getCurrentUserId() called too early, ActivityManagerInternal"
                    + " is not set yet");
            return UserHandle.USER_NULL;
        }
        return activityManagerInternal.getCurrentUserId();
    }

    /**
     * Gets whether the user is the current foreground user or a started profile of that user.
     *
     * <p>Doesn't perform any permission check.
     */
    @VisibleForTesting
    boolean isCurrentUserOrRunningProfileOfCurrentUser(@UserIdInt int userId) {
        int currentUserId = getCurrentUserId();

        if (currentUserId == userId) {
            return true;
        }

        if (isProfileUnchecked(userId)) {
            int parentId = getProfileParentIdUnchecked(userId);
            if (parentId == currentUserId) {
                return isUserRunning(userId);
            }
        }

        return false;
    }

    // Called by UserManagerServiceShellCommand
    boolean isUserVisibleOnDisplay(@UserIdInt int userId, int displayId) {
        return mUserVisibilityMediator.isUserVisible(userId, displayId);
    }

    @Override
    public int[] getVisibleUsers() {
        if (!hasManageUsersOrPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)) {
            throw new SecurityException("Caller needs MANAGE_USERS or INTERACT_ACROSS_USERS "
                    + "permission to get list of visible users");
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            return mUserVisibilityMediator.getVisibleUsers().toArray();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int getMainDisplayIdAssignedToUser() {
        // Not checking for any permission as it returns info about calling user
        int userId = UserHandle.getUserId(Binder.getCallingUid());
        int displayId = mUserVisibilityMediator.getMainDisplayAssignedToUser(userId);
        return displayId;
    }

    @Override
    public boolean isForegroundUserAdmin() {
        // No permission requirements for this API.
        synchronized (mUsersLock) {
            final int currentUserId = getCurrentUserId();
            if (currentUserId != UserHandle.USER_NULL) {
                final UserInfo userInfo = getUserInfoLU(currentUserId);
                return userInfo != null && userInfo.isAdmin();
            }
        }
        return false;
    }

    @Override
    public @NonNull String getUserName() {
        final int callingUid = Binder.getCallingUid();
        if (!hasQueryOrCreateUsersPermission()
                && !hasPermissionGranted(
                        android.Manifest.permission.GET_ACCOUNTS_PRIVILEGED, callingUid)) {
            throw new SecurityException("You need MANAGE_USERS, CREATE_USERS, QUERY_USERS, or "
                    + "GET_ACCOUNTS_PRIVILEGED permissions to: get user name");
        }
        final int userId = UserHandle.getUserId(callingUid);
        synchronized (mUsersLock) {
            UserInfo userInfo = userWithName(getUserInfoLU(userId));
            if (userInfo != null && userInfo.name != null) {
                return userInfo.name;
            }
            return "";
        }
    }

    @Override
    public long getUserStartRealtime() {
        final int userId = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mUsersLock) {
            final UserData user = getUserDataLU(userId);
            if (user != null) {
                return user.startRealtime;
            }
            return 0;
        }
    }

    @Override
    public long getUserUnlockRealtime() {
        synchronized (mUsersLock) {
            final UserData user = getUserDataLU(UserHandle.getUserId(Binder.getCallingUid()));
            if (user != null) {
                return user.unlockRealtime;
            }
            return 0;
        }
    }

    /**
     * Enforces that the calling user is in the same profile group as {@code userId} or that only
     * the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS INTERACT_ACROSS_USERS}
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS}
     * can make certain calls to the UserManager.
     *
     * @param name used as message if SecurityException is thrown
     * @throws SecurityException if the caller lacks the required permissions.
     */
    private void checkManageOrInteractPermissionIfCallerInOtherProfileGroup(@UserIdInt int userId,
            String name) {
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId == userId || isSameProfileGroupNoChecks(callingUserId, userId)) {
            return;
        }
        if (hasManageUsersPermission()) {
            return;
        }
        if (hasPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS,
                Binder.getCallingUid())) {
            return;
        }
        throw new SecurityException("You need INTERACT_ACROSS_USERS or MANAGE_USERS permission "
                + "to: check " + name);
    }

    /**
     * Enforces that the calling user is in the same profile group as {@code userId} or that only
     * the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS INTERACT_ACROSS_USERS}
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or
     * {@link android.Manifest.permission#QUERY_USERS QUERY_USERS}
     * can make certain calls to the UserManager.
     *
     * @param name used as message if SecurityException is thrown
     * @throws SecurityException if the caller lacks the required permissions.
     */
    private void checkQueryOrInteractPermissionIfCallerInOtherProfileGroup(
            @UserIdInt int userId, String name) {
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId == userId || isSameProfileGroupNoChecks(callingUserId, userId)) {
            return;
        }
        if (hasQueryUsersPermission()) {
            return;
        }
        if (hasPermissionGranted(
                Manifest.permission.INTERACT_ACROSS_USERS, Binder.getCallingUid())) {
            return;
        }
        throw new SecurityException("You need INTERACT_ACROSS_USERS, MANAGE_USERS, or QUERY_USERS "
                + "permission to: check " + name);
    }

    /**
     * Enforces that the calling user is in the same profile group as {@code userId} or that only
     * the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or
     * {@link android.Manifest.permission#CREATE_USERS CREATE_USERS} or
     * {@link android.Manifest.permission#QUERY_USERS QUERY_USERS}
     * can make certain calls to the UserManager.
     *
     * @param userId the user's id
     * @param name used as message if SecurityException is thrown
     * @throws SecurityException if the caller lacks the required permissions.
     */
    private void checkQueryOrCreateUsersPermissionIfCallerInOtherProfileGroup(
            @UserIdInt int userId, String name) {
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId == userId || isSameProfileGroupNoChecks(callingUserId, userId)) {
            return;
        }
        checkQueryOrCreateUsersPermission(name);
    }

    @Override
    public boolean isDemoUser(@UserIdInt int userId) {
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId && !hasManageUsersPermission()) {
            throw new SecurityException("You need MANAGE_USERS permission to query if u=" + userId
                    + " is a demo user");
        }

        if (SystemProperties.getBoolean("ro.boot.arc_demo_mode", false)) {
            return true;
        }

        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.isDemo();
        }
    }

    @Override
    public boolean isAdminUser(@UserIdInt int userId) {
        checkQueryOrCreateUsersPermissionIfCallerInOtherProfileGroup(userId, "isAdminUser");
        synchronized (mUsersLock) {
            final UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.isAdmin();
        }
    }

    @Override
    public boolean isPreCreated(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "isPreCreated");
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.preCreated;
        }
    }

    /**
     * Returns whether switching users is currently allowed for the provided user.
     * <p>
     * Switching users is not allowed in the following cases:
     * <li>the user is in a phone call</li>
     * <li>{@link UserManager#DISALLOW_USER_SWITCH} is set</li>
     * <li>system user hasn't been unlocked yet</li>
     *
     * @return A {@link UserManager.UserSwitchabilityResult} flag indicating if the user is
     * switchable.
     */
    public @UserManager.UserSwitchabilityResult int getUserSwitchability(int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "getUserSwitchability");

        final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("getUserSwitchability-" + userId);

        int flags = UserManager.SWITCHABILITY_STATUS_OK;

        t.traceBegin("TM.isInCall");
        final long identity = Binder.clearCallingIdentity();
        try {
            final TelecomManager telecomManager = mContext.getSystemService(TelecomManager.class);
            if (com.android.internal.telephony.flags
                    .Flags.enforceTelephonyFeatureMappingForPublicApis()) {
                if (mContext.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_TELECOM)) {
                    if (telecomManager != null && telecomManager.isInCall()) {
                        flags |= UserManager.SWITCHABILITY_STATUS_USER_IN_CALL;
                    }
                }
            } else {
                if (telecomManager != null && telecomManager.isInCall()) {
                    flags |= UserManager.SWITCHABILITY_STATUS_USER_IN_CALL;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        t.traceEnd();

        t.traceBegin("hasUserRestriction-DISALLOW_USER_SWITCH");
        if (mLocalService.hasUserRestriction(DISALLOW_USER_SWITCH, userId)) {
            flags |= UserManager.SWITCHABILITY_STATUS_USER_SWITCH_DISALLOWED;
        }
        t.traceEnd();

        // System User is always unlocked in Headless System User Mode, so ignore this flag
        if (!isHeadlessSystemUserMode()) {
            t.traceBegin("getInt-ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED");
            final boolean allowUserSwitchingWhenSystemUserLocked = Settings.Global.getInt(
                    mContext.getContentResolver(),
                    Settings.Global.ALLOW_USER_SWITCHING_WHEN_SYSTEM_USER_LOCKED, 0) != 0;
            t.traceEnd();
            t.traceBegin("isUserUnlocked-USER_SYSTEM");
            final boolean systemUserUnlocked = mLocalService.isUserUnlocked(UserHandle.USER_SYSTEM);
            t.traceEnd();

            if (!allowUserSwitchingWhenSystemUserLocked && !systemUserUnlocked) {
                flags |= UserManager.SWITCHABILITY_STATUS_SYSTEM_USER_LOCKED;
            }
        }
        t.traceEnd();

        return flags;
    }

    @VisibleForTesting
    boolean isUserSwitcherEnabled(@UserIdInt int mUserId) {
        boolean multiUserSettingOn = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.USER_SWITCHER_ENABLED,
                Resources.getSystem().getBoolean(com.android.internal
                        .R.bool.config_showUserSwitcherByDefault) ? 1 : 0) != 0;

        return UserManager.supportsMultipleUsers()
                && !hasUserRestriction(DISALLOW_USER_SWITCH, mUserId)
                && !UserManager.isDeviceInDemoMode(mContext)
                && multiUserSettingOn;
    }

    @Override
    public boolean isUserSwitcherEnabled(boolean showEvenIfNotActionable,
            @UserIdInt int mUserId) {
        if (!isUserSwitcherEnabled(mUserId)) {
            return false;
        }
        // The feature is enabled. But is it worth showing?
        return showEvenIfNotActionable
                || !hasUserRestriction(UserManager.DISALLOW_ADD_USER, mUserId) // Can add new user
                || areThereMultipleSwitchableUsers(); // There are switchable users
    }

    /** Returns true if there is more than one user that can be switched to. */
    private boolean areThereMultipleSwitchableUsers() {
        List<UserInfo> aliveUsers = getUsers(true, true, true);
        boolean isAnyAliveUser = false;
        for (UserInfo userInfo : aliveUsers) {
            if (userInfo.supportsSwitchToByUser()) {
                if (isAnyAliveUser) {
                    return true;
                }
                isAnyAliveUser = true;
            }
        }
        return false;
    }

    @Override
    public boolean isRestricted(@UserIdInt int userId) {
        if (userId != UserHandle.getCallingUserId()) {
            checkQueryOrCreateUsersPermission("query isRestricted for user " + userId);
        }
        synchronized (mUsersLock) {
            final UserInfo userInfo = getUserInfoLU(userId);
            return userInfo == null ? false : userInfo.isRestricted();
        }
    }

    @Override
    public boolean canHaveRestrictedProfile(@UserIdInt int userId) {
        checkManageUsersPermission("canHaveRestrictedProfile");
        synchronized (mUsersLock) {
            final UserInfo userInfo = getUserInfoLU(userId);
            if (userInfo == null || !userInfo.canHaveProfile()) {
                return false;
            }
            if (!userInfo.isAdmin()) {
                return false;
            }
            // restricted profile can be created if there is no DO set and the admin user has no PO;
            return !mIsDeviceManaged && !mIsUserManaged.get(userId);
        }
    }

    @Override
    public boolean canAddPrivateProfile(@UserIdInt int userId) {
        checkCreateUsersPermission("canHaveRestrictedProfile");
        UserInfo parentUserInfo = getUserInfo(userId);
        return isUserTypeEnabled(USER_TYPE_PROFILE_PRIVATE)
                && canAddMoreProfilesToUser(USER_TYPE_PROFILE_PRIVATE,
                    userId, /* allowedToRemoveOne */ false)
                && (parentUserInfo != null && parentUserInfo.isMain())
                && doesDeviceHardwareSupportPrivateSpace()
                && !hasUserRestriction(UserManager.DISALLOW_ADD_PRIVATE_PROFILE, userId);
    }

    @Override
    public boolean hasRestrictedProfiles(@UserIdInt int userId) {
        checkManageUsersPermission("hasRestrictedProfiles");
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo profile = mUsers.valueAt(i).info;
                if (userId != profile.id
                        && profile.restrictedProfileParentId == userId) {
                    return true;
                }
            }
            return false;
        }
    }

    /*
     * Should be locked on mUsers before calling this.
     */
    @GuardedBy("mUsersLock")
    private UserInfo getUserInfoLU(@UserIdInt int userId) {
        final UserData userData = mUsers.get(userId);
        // If it is partial and not in the process of being removed, return as unknown user.
        if (userData != null && userData.info.partial && !mRemovingUserIds.get(userId)) {
            Slog.w(LOG_TAG, "getUserInfo: unknown user #" + userId);
            return null;
        }
        return userData != null ? userData.info : null;
    }

    @GuardedBy("mUsersLock")
    private UserData getUserDataLU(@UserIdInt int userId) {
        final UserData userData = mUsers.get(userId);
        // If it is partial and not in the process of being removed, return as unknown user.
        if (userData != null && userData.info.partial && !mRemovingUserIds.get(userId)) {
            return null;
        }
        return userData;
    }

    /**
     * Obtains {@link #mUsersLock} and return UserInfo from mUsers.
     * <p>No permissions checking or any addition checks are made</p>
     */
    private UserInfo getUserInfoNoChecks(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            final UserData userData = mUsers.get(userId);
            return userData != null ? userData.info : null;
        }
    }

    /**
     * Obtains {@link #mUsersLock} and return UserData from mUsers.
     * <p>No permissions checking or any addition checks are made</p>
     */
    private UserData getUserDataNoChecks(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            return mUsers.get(userId);
        }
    }

    /** Called by PackageManagerService */
    public boolean exists(@UserIdInt int userId) {
        return mLocalService.exists(userId);
    }

    /**
     * Returns user's {@link  CrossProfileIntentFilter.AccessControlLevel}, which is derived from
     * {@link UserTypeDetails}. If user does not have defined their access control level,
     * returns default {@link CrossProfileIntentFilter#ACCESS_LEVEL_ALL}
     */
    private @CrossProfileIntentFilter.AccessControlLevel int
                getCrossProfileIntentFilterAccessControl(@UserIdInt int userId) {
        final UserProperties userProperties = getUserPropertiesInternal(userId);
        return userProperties != null ? userProperties.getCrossProfileIntentFilterAccessControl() :
                CrossProfileIntentFilter.ACCESS_LEVEL_ALL;
    }

    /**
     * Verifies if calling user is allowed to access {@link CrossProfileIntentFilter} between given
     * source and target user.
     * @param sourceUserId userId for which CrossProfileIntentFilter would be configured
     * @param targetUserId target user where we can resolve given intent filter
     * @param callingUid user accessing api
     * @param addCrossProfileIntentFilter if the operation is addition or not.
     * @throws SecurityException is calling user is not allowed to access.
     */
    public void enforceCrossProfileIntentFilterAccess(
            int sourceUserId, int targetUserId,
            int callingUid, boolean addCrossProfileIntentFilter) {
        if (!isCrossProfileIntentFilterAccessible(sourceUserId, targetUserId,
                addCrossProfileIntentFilter)) {
            throw new SecurityException("CrossProfileIntentFilter cannot be accessed by user "
                    + callingUid);
        }
    }

    /**
     * Checks if {@link CrossProfileIntentFilter} can be accessed by calling user for given source
     * and target user. There are following rules of access
     * 1. For {@link CrossProfileIntentFilter#ACCESS_LEVEL_ALL},
     *  irrespective of user we would allow access(addition/modification/removal)
     * 2. For {@link CrossProfileIntentFilter#ACCESS_LEVEL_SYSTEM},
     *  only system/root user would be able to access(addition/modification/removal)
     * 3. For {@link CrossProfileIntentFilter#ACCESS_LEVEL_SYSTEM_ADD_ONLY},
     *  only system/root user would be able to add but not modify/remove. Once added, it cannot be
     *  modified or removed
     * @param sourceUserId userId for which CrossProfileIntentFilter would be configured
     * @param targetUserId target user where we can resolve given intent filter
     * @param addCrossProfileIntentFilter if the operation is addition or not.
     * @return true if {@link CrossProfileIntentFilter} can be accessed by calling user
     */
    public boolean isCrossProfileIntentFilterAccessible(int sourceUserId, int targetUserId,
            boolean addCrossProfileIntentFilter) {
        int effectiveAccessControl =
                getCrossProfileIntentFilterAccessControl(sourceUserId, targetUserId);

        /*
        For {@link CrossProfileIntentFilter#ACCESS_LEVEL_SYSTEM}, if accessing user is not
        system or root disallowing access to {@link CrossProfileIntentFilter}
         */
        if (CrossProfileIntentFilter.ACCESS_LEVEL_SYSTEM == effectiveAccessControl
                && !PackageManagerServiceUtils.isSystemOrRoot()) {
            return false;
        }

        /*
        For {@link CrossProfileIntentFilter#ACCESS_LEVEL_SYSTEM_ADD_ONLY}, allowing only
        system user to add {@link CrossProfileIntentFilter}. All users(including system) are
        disallowed to modify/remove.
         */
        if (CrossProfileIntentFilter.ACCESS_LEVEL_SYSTEM_ADD_ONLY == effectiveAccessControl
                && (!addCrossProfileIntentFilter || !PackageManagerServiceUtils.isSystemOrRoot())) {
            return false;
        }
        return true;
    }

    /**
     * Returns {@link CrossProfileIntentFilter.AccessControlLevel}
     * that should be assigned to {@link CrossProfileIntentFilter}
     * computed from source user's and target user's
     * {@link CrossProfileIntentFilter.AccessControlLevel}.
     * The Access Level is configured per {@link CrossProfileIntentFilter} and its property of edge
     * between source and target user e.g. for all {@link CrossProfileIntentFilter}s configured
     * between Primary user and Clone profile should have access level of
     * {@link CrossProfileIntentFilter#ACCESS_LEVEL_SYSTEM} which is driven by highest
     * access value from source or target. The higher value means higher restrictions.
     * @param sourceUserId userId of source user for whom CrossProfileIntentFilter will be stored
     * @param targetUserId userId of target user for whom Cross Profile access would be allowed
     * @return least privileged {@link CrossProfileIntentFilter.AccessControlLevel} from source or
     * target user.
     */
    public @CrossProfileIntentFilter.AccessControlLevel int
                getCrossProfileIntentFilterAccessControl(int sourceUserId, int targetUserId) {
        int sourceAccessControlLevel,
                targetAccessControlLevel, effectiveAccessControl;
        sourceAccessControlLevel = getCrossProfileIntentFilterAccessControl(sourceUserId);
        targetAccessControlLevel = getCrossProfileIntentFilterAccessControl(targetUserId);
        effectiveAccessControl = Math.max(sourceAccessControlLevel, targetAccessControlLevel);
        return effectiveAccessControl;
    }

    @Override
    public void setUserName(@UserIdInt int userId, String name) {
        checkManageUsersPermission("rename users");
        synchronized (mPackagesLock) {
            UserData userData = getUserDataNoChecks(userId);
            if (userData == null || userData.info.partial) {
                Slogf.w(LOG_TAG, "setUserName: unknown user #%d", userId);
                return;
            }
            if (Objects.equals(name, userData.info.name)) {
                Slogf.i(LOG_TAG, "setUserName: ignoring for user #%d as it didn't change (%s)",
                        userId, getRedacted(name));
                return;
            }
            if (name == null) {
                Slogf.i(LOG_TAG, "setUserName: resetting name of user #%d", userId);
            } else {
                Slogf.i(LOG_TAG, "setUserName: setting name of user #%d to %s", userId,
                        getRedacted(name));
            }
            userData.info.name = name;
            writeUserLP(userData);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            sendUserInfoChangedBroadcast(userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean setUserEphemeral(@UserIdInt int userId, boolean enableEphemeral) {
        checkCreateUsersPermission("update ephemeral user flag");
        return enableEphemeral
                ? UserManager.isRemoveResultSuccessful(setUserEphemeralUnchecked(userId))
                : setUserNonEphemeralUnchecked(userId);
    }

    private boolean setUserNonEphemeralUnchecked(@UserIdInt int userId) {
        synchronized (mPackagesLock) {
            final UserData userData;
            synchronized (mUsersLock) {
                userData = mUsers.get(userId);
                if (userData == null) {
                    Slog.e(LOG_TAG, TextUtils.formatSimple(
                            "Cannot set user %d non-ephemeral, invalid user id provided.", userId));
                    return false;
                }
                if (!userData.info.isEphemeral()) {
                    return true;
                }

                if ((userData.info.flags & UserInfo.FLAG_EPHEMERAL_ON_CREATE) != 0) {
                    // when user is created in ephemeral mode via FLAG_EPHEMERAL
                    // its state cannot be changed to non-ephemeral.
                    // FLAG_EPHEMERAL_ON_CREATE is used to keep track of this state
                    Slog.e(LOG_TAG, TextUtils.formatSimple("User %d can not be changed to "
                            + "non-ephemeral because it was set ephemeral on create.", userId));
                    return false;
                }
            }
            userData.info.flags &= ~UserInfo.FLAG_EPHEMERAL;
            writeUserLP(userData);
        }
        return true;
    }

    private @UserManager.RemoveResult int setUserEphemeralUnchecked(@UserIdInt int userId) {
        synchronized (mPackagesLock) {
            final UserData userData;
            synchronized (mUsersLock) {
                final int userRemovability = getUserRemovabilityLocked(userId, "set as ephemeral");
                if (userRemovability != UserManager.REMOVE_RESULT_USER_IS_REMOVABLE) {
                    return userRemovability;
                }
                userData = mUsers.get(userId);
            }
            userData.info.flags |= UserInfo.FLAG_EPHEMERAL;
            writeUserLP(userData);
        }
        Slog.i(LOG_TAG, TextUtils.formatSimple(
                "User %d is set ephemeral and will be removed on user switch or reboot.", userId));
        return UserManager.REMOVE_RESULT_DEFERRED;
    }

    @Override
    public void setUserIcon(@UserIdInt int userId, Bitmap bitmap) {
        try {
            checkManageUsersPermission("update users");
            enforceUserRestriction(UserManager.DISALLOW_SET_USER_ICON, userId,
                    "Cannot set user icon");
            mLocalService.setUserIcon(userId, bitmap);
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }
    }



    private void sendUserInfoChangedBroadcast(@UserIdInt int userId) {
        Intent changedIntent = new Intent(Intent.ACTION_USER_INFO_CHANGED);
        changedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        changedIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(changedIntent, UserHandle.ALL);
    }

    @Override
    public ParcelFileDescriptor getUserIcon(int targetUserId) {
        if (!hasManageUsersOrPermission(android.Manifest.permission.GET_ACCOUNTS_PRIVILEGED)) {
            throw new SecurityException("You need MANAGE_USERS or GET_ACCOUNTS_PRIVILEGED "
                    + "permissions to: get user icon");
        }
        String iconPath;
        synchronized (mPackagesLock) {
            UserInfo targetUserInfo = getUserInfoNoChecks(targetUserId);
            if (targetUserInfo == null || targetUserInfo.partial) {
                Slog.w(LOG_TAG, "getUserIcon: unknown user #" + targetUserId);
                return null;
            }

            final int callingUserId = UserHandle.getCallingUserId();
            final UserInfo callingUserInfo = getUserInfoNoChecks(callingUserId);
            if (!isSameUserOrProfileGroupOrTargetIsCommunal(callingUserInfo, targetUserInfo)) {
                checkManageUsersPermission("get the icon of a user who is not related");
            }

            if (targetUserInfo.iconPath == null) {
                return null;
            }
            iconPath = targetUserInfo.iconPath;
        }

        try {
            return ParcelFileDescriptor.open(
                    new File(iconPath), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            Slog.e(LOG_TAG, "Couldn't find icon file", e);
        }
        return null;
    }

    public void makeInitialized(@UserIdInt int userId) {
        if (DBG) Slog.d(LOG_TAG, "makeInitialized(" + userId + ")");
        checkManageUsersPermission("makeInitialized");
        boolean scheduleWriteUser = false;
        UserData userData;
        synchronized (mUsersLock) {
            userData = mUsers.get(userId);
            if (userData == null || userData.info.partial) {
                Slog.w(LOG_TAG, "makeInitialized: unknown user #" + userId);
                return;
            }
            if ((userData.info.flags & UserInfo.FLAG_INITIALIZED) == 0) {
                userData.info.flags |= UserInfo.FLAG_INITIALIZED;
                scheduleWriteUser = true;
            }
        }
        if (scheduleWriteUser) {
            scheduleWriteUser(userId);
        }
    }

    /**
     * If default guest restrictions haven't been initialized yet, add the basic
     * restrictions.
     */
    private void initDefaultGuestRestrictions() {
        synchronized (mGuestRestrictions) {
            if (mGuestRestrictions.isEmpty()) {
                UserTypeDetails guestType = mUserTypes.get(UserManager.USER_TYPE_FULL_GUEST);
                if (guestType == null) {
                    Slog.wtf(LOG_TAG, "Can't set default guest restrictions: type doesn't exist.");
                    return;
                }
                guestType.addDefaultRestrictionsTo(mGuestRestrictions);
            }
        }
    }

    @Override
    public Bundle getDefaultGuestRestrictions() {
        checkManageUsersPermission("getDefaultGuestRestrictions");
        synchronized (mGuestRestrictions) {
            return new Bundle(mGuestRestrictions);
        }
    }

    @Override
    public void setDefaultGuestRestrictions(Bundle restrictions) {
        checkManageUsersPermission("setDefaultGuestRestrictions");
        final List<UserInfo> guests = getGuestUsers();
        synchronized (mRestrictionsLock) {
            for (int i = 0; i < guests.size(); i++) {
                updateUserRestrictionsInternalLR(restrictions, guests.get(i).id);
            }
        }
        synchronized (mGuestRestrictions) {
            mGuestRestrictions.clear();
            mGuestRestrictions.putAll(restrictions);
        }
        synchronized (mPackagesLock) {
            writeUserListLP();
        }
    }

    @VisibleForTesting
    void setUserRestrictionInner(int userId, @NonNull String key, boolean value) {
        if (!UserRestrictionsUtils.isValidRestriction(key)) {
            Slog.e(LOG_TAG, "Setting invalid restriction " + key);
            return;
        }
        synchronized (mRestrictionsLock) {
            final Bundle newRestrictions = BundleUtils.clone(
                    mDevicePolicyUserRestrictions.getRestrictions(userId));
            newRestrictions.putBoolean(key, value);

            if (mDevicePolicyUserRestrictions.updateRestrictions(userId, newRestrictions)) {
                if (userId == UserHandle.USER_ALL) {
                    applyUserRestrictionsForAllUsersLR();
                } else {
                    applyUserRestrictionsLR(userId);
                }
            }
        }
    }

    /**
     * See {@link UserManagerInternal#setDevicePolicyUserRestrictions}
     */
    private void setDevicePolicyUserRestrictionsInner(@UserIdInt int originatingUserId,
            @NonNull Bundle global, @NonNull RestrictionsSet local,
            boolean isDeviceOwner) {
        synchronized (mRestrictionsLock) {
            final IntArray updatedUserIds = mDevicePolicyUserRestrictions.getUserIds();

            mCachedEffectiveUserRestrictions.removeAllRestrictions();
            mDevicePolicyUserRestrictions.removeAllRestrictions();

            mDevicePolicyUserRestrictions.updateRestrictions(UserHandle.USER_ALL, global);

            final IntArray localUserIds = local.getUserIds();
            for (int i = 0; i < localUserIds.size(); i++) {
                final int userId = localUserIds.get(i);
                mDevicePolicyUserRestrictions.updateRestrictions(userId,
                        local.getRestrictions(userId));
                updatedUserIds.add(userId);
            }

            applyUserRestrictionsForAllUsersLR();
            for (int i = 0; i < updatedUserIds.size(); i++) {
                if (updatedUserIds.get(i) == UserHandle.USER_ALL) {
                    continue;
                }
                applyUserRestrictionsLR(updatedUserIds.get(i));
            }
        }
    }

    @GuardedBy("mRestrictionsLock")
    private Bundle computeEffectiveUserRestrictionsLR(@UserIdInt int userId) {
        final Bundle baseRestrictions = mBaseUserRestrictions.getRestrictionsNonNull(userId);

        final Bundle global = mDevicePolicyUserRestrictions.getRestrictionsNonNull(
                UserHandle.USER_ALL);
        final Bundle local = mDevicePolicyUserRestrictions.getRestrictionsNonNull(userId);

        if (global.isEmpty() && local.isEmpty()) {
            // Common case first.
            return baseRestrictions;
        }
        final Bundle effective = BundleUtils.clone(baseRestrictions);
        UserRestrictionsUtils.merge(effective, global);
        UserRestrictionsUtils.merge(effective, local);

        return effective;
    }

    @GuardedBy("mRestrictionsLock")
    private void invalidateEffectiveUserRestrictionsLR(@UserIdInt int userId) {
        if (DBG) {
            Slog.d(LOG_TAG, "invalidateEffectiveUserRestrictions userId=" + userId);
        }
        mCachedEffectiveUserRestrictions.remove(userId);
    }

    private Bundle getEffectiveUserRestrictions(@UserIdInt int userId) {
        synchronized (mRestrictionsLock) {
            Bundle restrictions = mCachedEffectiveUserRestrictions.getRestrictions(userId);
            if (restrictions == null) {
                restrictions = computeEffectiveUserRestrictionsLR(userId);
                mCachedEffectiveUserRestrictions.updateRestrictions(userId, restrictions);
            }
            return restrictions;
        }
    }

    /** @return a specific user restriction that's in effect currently. */
    @Override
    public boolean hasUserRestriction(String restrictionKey, @UserIdInt int userId) {
        if (!userExists(userId)) {
            return false;
        }
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "hasUserRestriction");
        return mLocalService.hasUserRestriction(restrictionKey, userId);
    }

    /** @return if any user has the given restriction. */
    @Override
    public boolean hasUserRestrictionOnAnyUser(String restrictionKey) {
        if (!UserRestrictionsUtils.isValidRestriction(restrictionKey)) {
            return false;
        }
        final List<UserInfo> users = getUsers(/* excludeDying= */ true);
        for (int i = 0; i < users.size(); i++) {
            final int userId = users.get(i).id;
            Bundle restrictions = getEffectiveUserRestrictions(userId);
            if (restrictions != null && restrictions.getBoolean(restrictionKey)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isSettingRestrictedForUser(String setting, @UserIdInt int userId,
            String value, int callingUid) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Non-system caller");
        }
        return UserRestrictionsUtils.isSettingRestrictedForUser(mContext, setting, userId,
                value, callingUid);
    }

    @Override
    public void addUserRestrictionsListener(final IUserRestrictionsListener listener) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Non-system caller");
        }

        // NOTE: unregistering not supported; only client is the settings provider,
        // which installs a single static permanent listener.  If that listener goes
        // bad it implies the whole system process is going to crash.
        mLocalService.addUserRestrictionsListener(
                (int userId, Bundle newRestrict, Bundle prevRestrict) -> {
                    try {
                        listener.onUserRestrictionsChanged(userId, newRestrict, prevRestrict);
                    } catch (RemoteException re) {
                        Slog.e("IUserRestrictionsListener",
                                "Unable to invoke listener: " + re.getMessage());
                    }
                });
    }

    /**
     * @hide
     *
     * Returns who set a user restriction on a user.
     * Requires {@link android.Manifest.permission#MANAGE_USERS} permission.
     * @param restrictionKey the string key representing the restriction
     * @param userId the id of the user for whom to retrieve the restrictions.
     * @return The source of user restriction. Any combination of
     *         {@link UserManager#RESTRICTION_NOT_SET},
     *         {@link UserManager#RESTRICTION_SOURCE_SYSTEM},
     *         {@link UserManager#RESTRICTION_SOURCE_DEVICE_OWNER}
     *         and {@link UserManager#RESTRICTION_SOURCE_PROFILE_OWNER}
     */
    @Override
    public int getUserRestrictionSource(String restrictionKey, @UserIdInt int userId) {
        List<EnforcingUser> enforcingUsers = getUserRestrictionSources(restrictionKey,  userId);
        // Get "bitwise or" of restriction sources for all enforcing users.
        int result = UserManager.RESTRICTION_NOT_SET;
        for (int i = enforcingUsers.size() - 1; i >= 0; i--) {
            result |= enforcingUsers.get(i).getUserRestrictionSource();
        }
        return result;
    }

    @Override
    public List<EnforcingUser> getUserRestrictionSources(
            String restrictionKey, @UserIdInt int userId) {
        checkQueryUsersPermission("call getUserRestrictionSources.");

        // Shortcut for the most common case
        if (!hasUserRestriction(restrictionKey, userId)) {
            return Collections.emptyList();
        }

        final List<EnforcingUser> result = new ArrayList<>();

        // Check if it is base restriction.
        if (hasBaseUserRestriction(restrictionKey, userId)) {
            result.add(new EnforcingUser(
                    UserHandle.USER_NULL, UserManager.RESTRICTION_SOURCE_SYSTEM));
        }

        final DevicePolicyManagerInternal dpmi = getDevicePolicyManagerInternal();
        if (dpmi != null) {
            result.addAll(dpmi.getUserRestrictionSources(restrictionKey, userId));
        }
        return result;
    }

    /**
     * @return UserRestrictions that are in effect currently.  This always returns a new
     * {@link Bundle}.
     */
    @Override
    public Bundle getUserRestrictions(@UserIdInt int userId) {
        checkManageOrInteractPermissionIfCallerInOtherProfileGroup(userId, "getUserRestrictions");
        return BundleUtils.clone(getEffectiveUserRestrictions(userId));
    }

    @Override
    public boolean hasBaseUserRestriction(String restrictionKey, @UserIdInt int userId) {
        checkCreateUsersPermission("hasBaseUserRestriction");
        if (!UserRestrictionsUtils.isValidRestriction(restrictionKey)) {
            return false;
        }
        synchronized (mRestrictionsLock) {
            Bundle bundle = mBaseUserRestrictions.getRestrictions(userId);
            return (bundle != null && bundle.getBoolean(restrictionKey, false));
        }
    }

    @Override
    public void setUserRestriction(String key, boolean value, @UserIdInt int userId) {
        checkManageUsersPermission("setUserRestriction");
        if (!UserRestrictionsUtils.isValidRestriction(key)) {
            return;
        }

        if (!userExists(userId)) {
            Slogf.w(LOG_TAG, "Cannot set user restriction %s. User with id %d does not exist",
                    key, userId);
            return;
        }
        synchronized (mRestrictionsLock) {
            // Note we can't modify Bundles stored in mBaseUserRestrictions directly, so create
            // a copy.
            final Bundle newRestrictions = BundleUtils.clone(
                    mBaseUserRestrictions.getRestrictions(userId));
            newRestrictions.putBoolean(key, value);

            updateUserRestrictionsInternalLR(newRestrictions, userId);
        }
    }

    /**
     * Optionally updating user restrictions, calculate the effective user restrictions and also
     * propagate to other services and system settings.
     *
     * @param newBaseRestrictions User restrictions to set.
     *      If null, will not update user restrictions and only does the propagation.
     * @param userId target user ID.
     */
    @GuardedBy("mRestrictionsLock")
    private void updateUserRestrictionsInternalLR(
            @Nullable Bundle newBaseRestrictions, @UserIdInt int userId) {
        final Bundle prevAppliedRestrictions = UserRestrictionsUtils.nonNull(
                mAppliedUserRestrictions.getRestrictions(userId));

        // Update base restrictions.
        if (newBaseRestrictions != null) {
            // If newBaseRestrictions == the current one, it's probably a bug.
            final Bundle prevBaseRestrictions = mBaseUserRestrictions.getRestrictions(userId);

            Preconditions.checkState(prevBaseRestrictions != newBaseRestrictions);
            Preconditions.checkState(mCachedEffectiveUserRestrictions.getRestrictions(userId)
                    != newBaseRestrictions);

            if (mBaseUserRestrictions.updateRestrictions(userId, new Bundle(newBaseRestrictions))) {
                scheduleWriteUser(userId);
            }
        }

        final Bundle effective = computeEffectiveUserRestrictionsLR(userId);

        mCachedEffectiveUserRestrictions.updateRestrictions(userId, new Bundle(effective));

        // Apply the new restrictions.
        if (DBG) {
            debug("Applying user restrictions: userId=" + userId
                    + " new=" + effective + " prev=" + prevAppliedRestrictions);
        }

        if (mAppOpsService != null) { // We skip it until system-ready.
            mHandler.post(() -> {
                try {
                    mAppOpsService.setUserRestrictions(effective, mUserRestrictionToken, userId);
                } catch (RemoteException e) {
                    Slog.w(LOG_TAG, "Unable to notify AppOpsService of UserRestrictions");
                }
            });
        }

        propagateUserRestrictionsLR(userId, effective, prevAppliedRestrictions);

        mAppliedUserRestrictions.updateRestrictions(userId, new Bundle(effective));
    }

    @GuardedBy("mRestrictionsLock")
    private void propagateUserRestrictionsLR(final int userId,
            Bundle newRestrictions, Bundle prevRestrictions) {
        // Note this method doesn't touch any state, meaning it doesn't require mRestrictionsLock
        // actually, but we still need some kind of synchronization otherwise we might end up
        // calling listeners out-of-order, thus "LR".

        if (UserRestrictionsUtils.areEqual(newRestrictions, prevRestrictions)) {
            return;
        }

        final Bundle newRestrictionsFinal = new Bundle(newRestrictions);
        final Bundle prevRestrictionsFinal = new Bundle(prevRestrictions);

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                UserRestrictionsUtils.applyUserRestrictions(
                        mContext, userId, newRestrictionsFinal, prevRestrictionsFinal);

                final UserRestrictionsListener[] listeners;
                synchronized (mUserRestrictionsListeners) {
                    listeners = new UserRestrictionsListener[mUserRestrictionsListeners.size()];
                    mUserRestrictionsListeners.toArray(listeners);
                }
                for (int i = 0; i < listeners.length; i++) {
                    listeners[i].onUserRestrictionsChanged(userId,
                            newRestrictionsFinal, prevRestrictionsFinal);
                }

                final Intent broadcast = new Intent(UserManager.ACTION_USER_RESTRICTIONS_CHANGED)
                        .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                // Setting the MOST_RECENT policy allows us to discard older broadcasts
                // still waiting to be delivered.
                final Bundle options = BroadcastOptions.makeBasic()
                        .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MOST_RECENT)
                        .toBundle();
                mContext.sendBroadcastAsUser(broadcast, UserHandle.of(userId),
                        null /* receiverPermission */, options);
            }
        });
    }

    // Package private for the inner class.
    @GuardedBy("mRestrictionsLock")
    private void applyUserRestrictionsLR(@UserIdInt int userId) {
        updateUserRestrictionsInternalLR(null, userId);
        scheduleWriteUser(userId);
    }

    @GuardedBy("mRestrictionsLock")
    // Package private for the inner class.
    private void applyUserRestrictionsForAllUsersLR() {
        if (DBG) {
            debug("applyUserRestrictionsForAllUsersLR");
        }
        // First, invalidate all cached values.
        mCachedEffectiveUserRestrictions.removeAllRestrictions();

        // We don't want to call into ActivityManagerService while taking a lock, so we'll call
        // it on a handler.
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                // Then get the list of running users.
                final int[] runningUsers;
                try {
                    runningUsers = ActivityManager.getService().getRunningUserIds();
                } catch (RemoteException e) {
                    Slog.w(LOG_TAG, "Unable to access ActivityManagerService");
                    return;
                }
                // Then re-calculate the effective restrictions and apply, only for running users.
                // It's okay if a new user has started after the getRunningUserIds() call,
                // because we'll do the same thing (re-calculate the restrictions and apply)
                // when we start a user.
                synchronized (mRestrictionsLock) {
                    for (int i = 0; i < runningUsers.length; i++) {
                        applyUserRestrictionsLR(runningUsers[i]);
                    }
                }
            }
        };
        mHandler.post(r);
    }

    /**
     * Check if we've hit the limit of how many users can be created.
     */
    private boolean isUserLimitReached() {
        int count;
        synchronized (mUsersLock) {
            count = getAliveUsersExcludingGuestsCountLU();
        }
        return count >= UserManager.getMaxSupportedUsers()
                && !isCreationOverrideEnabled();
    }

    /**
     * Returns whether more users of the given type can be added (based on how many users of that
     * type already exist).
     *
     * <p>For checking whether more profiles can be added to a particular parent use
     * {@link #canAddMoreProfilesToUser}.
     */
    private boolean canAddMoreUsersOfType(@NonNull UserTypeDetails userTypeDetails) {
        if (!isUserTypeEnabled(userTypeDetails)) {
            return false;
        }
        final int max = userTypeDetails.getMaxAllowed();
        if (max == UserTypeDetails.UNLIMITED_NUMBER_OF_USERS) {
            return true; // Indicates that there is no max.
        }
        return getNumberOfUsersOfType(userTypeDetails.getName()) < max
                || isCreationOverrideEnabled();
    }

    /**
     * Returns the remaining number of users of the given type that can be created. (taking into
     * account the total number of users on the device as well as how many exist of that type)
     */
    @Override
    public int getRemainingCreatableUserCount(String userType) {
        checkQueryOrCreateUsersPermission("get the remaining number of users that can be added.");
        final UserTypeDetails type = mUserTypes.get(userType);
        if (type == null || !isUserTypeEnabled(type)) {
            return 0;
        }
        synchronized (mUsersLock) {
            final int userCount = getAliveUsersExcludingGuestsCountLU();

            // Limit total number of users that can be created (except for guest and demo)
            int result =
                    UserManager.isUserTypeGuest(userType) || UserManager.isUserTypeDemo(userType)
                        ? Integer.MAX_VALUE
                        : (UserManager.getMaxSupportedUsers() - userCount);

            // Managed profiles have their own specific rules.
            if (type.isManagedProfile()) {
                if (!mContext.getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_MANAGED_USERS)) {
                    return 0;
                }
                // Special case: Allow creating a managed profile anyway if there's only 1 user
                if (result <= 0 & userCount == 1) {
                    result = 1;
                }
            }
            if (result <= 0) {
                return 0;
            }

            // Limit against max allowed for type
            result = Math.min(result,
                    type.getMaxAllowed() == UserTypeDetails.UNLIMITED_NUMBER_OF_USERS
                        ? Integer.MAX_VALUE
                        : (type.getMaxAllowed() - getNumberOfUsersOfType(userType)));

            return Math.max(0, result);
        }
    }

    /**
     * Gets the number of users of the given user type.
     * Does not include users that are about to die.
     */
    private int getNumberOfUsersOfType(String userType) {
        int count = 0;
        synchronized (mUsersLock) {
            final int size = mUsers.size();
            for (int i = 0; i < size; i++) {
                final UserInfo user = mUsers.valueAt(i).info;
                if (user.userType.equals(userType)
                        && !user.guestToRemove
                        && !mRemovingUserIds.get(user.id)
                        && !user.preCreated) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Returns whether more users of the given type can be added (based on how many users of that
     * type already exist).
     */
    @Override
    public boolean canAddMoreUsersOfType(String userType) {
        checkCreateUsersPermission("check if more users can be added.");
        final UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        return userTypeDetails != null && canAddMoreUsersOfType(userTypeDetails);
    }

    /** Returns whether the creation of users of the given user type is enabled on this device. */
    @Override
    public boolean isUserTypeEnabled(String userType) {
        checkCreateUsersPermission("check if user type is enabled.");
        final UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        return userTypeDetails != null && isUserTypeEnabled(userTypeDetails);
    }

    /** Returns whether the creation of users of the given user type is enabled on this device. */
    private boolean isUserTypeEnabled(@NonNull UserTypeDetails userTypeDetails) {
        return userTypeDetails.isEnabled() || isCreationOverrideEnabled();
    }

    /**
     * Returns whether to almost-always allow creating users even beyond their limit or if disabled.
     * For Debug builds only.
     */
    private boolean isCreationOverrideEnabled() {
        return Build.isDebuggable()
                && SystemProperties.getBoolean(DEV_CREATE_OVERRIDE_PROPERTY, false);
    }

    @Override
    public boolean canAddMoreManagedProfiles(@UserIdInt int userId, boolean allowedToRemoveOne) {
        return canAddMoreProfilesToUser(UserManager.USER_TYPE_PROFILE_MANAGED, userId,
                allowedToRemoveOne);
    }

    /** Returns whether more profiles of the given type can be added to the given parent userId. */
    @Override
    public boolean canAddMoreProfilesToUser(String userType, @UserIdInt int userId,
            boolean allowedToRemoveOne) {
        return 0 < getRemainingCreatableProfileCount(userType, userId, allowedToRemoveOne)
                || isCreationOverrideEnabled();
    }

    @Override
    public int getRemainingCreatableProfileCount(@NonNull String userType, @UserIdInt int userId) {
        return getRemainingCreatableProfileCount(userType, userId, false);
    }

    /**
     * Returns the remaining number of profiles of the given type that can be added to the given
     * user. (taking into account the total number of users on the device as well as how many
     * profiles exist of that type both in general and for the given user)
     */
    private int getRemainingCreatableProfileCount(@NonNull String userType, @UserIdInt int userId,
            boolean allowedToRemoveOne) {
        checkQueryOrCreateUsersPermission(
                "get the remaining number of profiles that can be added to the given user.");
        final UserTypeDetails type = mUserTypes.get(userType);
        if (type == null || !isUserTypeEnabled(type)) {
            return 0;
        }
        // Managed profiles have their own specific rules.
        final boolean isManagedProfile = type.isManagedProfile();
        if (isManagedProfile) {
            if (!mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_MANAGED_USERS)) {
                return 0;
            }
        }
        synchronized (mUsersLock) {
            // Check if the parent exists and its type is even allowed to have a profile.
            UserInfo userInfo = getUserInfoLU(userId);
            if (userInfo == null || !userInfo.canHaveProfile()) {
                return 0;
            }

            final int userTypeCount = getProfileIds(userId, userType, false, /* excludeHidden */
                    false).length;
            final int profilesRemovedCount = userTypeCount > 0 && allowedToRemoveOne ? 1 : 0;
            final int usersCountAfterRemoving = getAliveUsersExcludingGuestsCountLU()
                    - profilesRemovedCount;

            // Limit total number of users that can be created
            int result = UserManager.getMaxSupportedUsers() - usersCountAfterRemoving;

            // Special case: Allow creating a managed profile anyway if there's only 1 user
            if (result <= 0 && isManagedProfile && usersCountAfterRemoving == 1) {
                result = 1;
            }

            // Limit the number of profiles of this type that can be created.
            final int maxUsersOfType = getMaxUsersOfTypePerParent(type);
            if (maxUsersOfType != UserTypeDetails.UNLIMITED_NUMBER_OF_USERS) {
                result = Math.min(result, maxUsersOfType - (userTypeCount - profilesRemovedCount));
            }
            if (result <= 0) {
                return 0;
            }

            // Limit against max allowed for type (beyond max allowed per parent)
            if (type.getMaxAllowed() != UserTypeDetails.UNLIMITED_NUMBER_OF_USERS) {
                result = Math.min(result, type.getMaxAllowed()
                        - (getNumberOfUsersOfType(userType) - profilesRemovedCount));
            }

            return Math.max(0, result);
        }
    }

    @GuardedBy("mUsersLock")
    private int getAliveUsersExcludingGuestsCountLU() {
        int aliveUserCount = 0;
        final int totalUserCount = mUsers.size();
        // Skip over users being removed
        for (int i = 0; i < totalUserCount; i++) {
            UserInfo user = mUsers.valueAt(i).info;
            if (!mRemovingUserIds.get(user.id) && !user.isGuest() && !user.preCreated) {
                aliveUserCount++;
            }
        }
        return aliveUserCount;
    }

    /**
     * Enforces that only the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} and
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL INTERACT_ACROSS_USERS_FULL}
     * permissions can make certain calls to the UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller does not have enough privilege.
     */
    private static final void checkManageUserAndAcrossUsersFullPermission(String message) {
        final int uid = Binder.getCallingUid();

        if (uid == Process.SYSTEM_UID || uid == 0) {
            // System UID or root's UID are granted privilege.
            return;
        }

        if (hasPermissionGranted(Manifest.permission.MANAGE_USERS, uid)
                && hasPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS_FULL, uid)) {
            // Apps with both permissions are granted privilege.
            return;
        }

        throw new SecurityException(
                "You need MANAGE_USERS and INTERACT_ACROSS_USERS_FULL permission to: " + message);
    }

    private static boolean hasPermissionGranted(String permission, int uid) {
        return ActivityManager.checkComponentPermission(
                permission, uid, /* owningUid = */-1, /* exported = */ true) ==
                PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Enforces that only the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS}
     * permission can make certain calls to the UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     * @see #hasManageUsersPermission()
     */
    private static final void checkManageUsersPermission(String message) {
        if (!hasManageUsersPermission()) {
            throw new SecurityException("You need MANAGE_USERS permission to: " + message);
        }
    }

    /**
     * Enforces that only the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or
     * {@link android.Manifest.permission#CREATE_USERS CREATE_USERS}
     * can make certain calls to the UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     * @see #hasCreateUsersPermission()
     */
    private static final void checkCreateUsersPermission(String message) {
        if (!hasCreateUsersPermission()) {
            throw new SecurityException(
                    "You either need MANAGE_USERS or CREATE_USERS permission to: " + message);
        }
    }

    /**
     * Enforces that only the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or
     * {@link android.Manifest.permission#QUERY_USERS QUERY_USERS}
     * can make certain calls to the UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller lacks the required permissions.
     */
    private static final void checkQueryUsersPermission(String message) {
        if (!hasQueryUsersPermission()) {
            throw new SecurityException(
                    "You either need MANAGE_USERS or QUERY_USERS permission to: " + message);
        }
    }

    /**
     * Enforces that only the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or
     * {@link android.Manifest.permission#CREATE_USERS CREATE_USERS} or
     * {@link android.Manifest.permission#QUERY_USERS QUERY_USERS}
     * can make certain calls to the UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller lacks the required permissions.
     */
    private static final void checkQueryOrCreateUsersPermission(String message) {
        if (!hasQueryOrCreateUsersPermission()) {
            throw new SecurityException(
                    "You either need MANAGE_USERS, CREATE_USERS, or QUERY_USERS permission to: "
                            + message);
        }
    }

    /**
     * Similar to {@link #checkCreateUsersPermission(String)} but when the caller is tries
     * to create user/profiles other than what is allowed for
     * {@link android.Manifest.permission#CREATE_USERS CREATE_USERS} permission, then it will only
     * allow callers with {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} permission.
     */
    private static final void checkCreateUsersPermission(int creationFlags) {
        if ((creationFlags & ~ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION) == 0) {
            if (!hasCreateUsersPermission()) {
                throw new SecurityException("You either need MANAGE_USERS or CREATE_USERS "
                        + "permission to create an user with flags: " + creationFlags);
            }
        } else if (!hasManageUsersPermission()) {
            throw new SecurityException("You need MANAGE_USERS permission to create an user "
                    + " with flags: " + creationFlags);
        }
    }

    /**
     * @return whether the calling UID is system UID or root's UID or the calling app has the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS}.
     */
    private static final boolean hasManageUsersPermission() {
        final int callingUid = Binder.getCallingUid();
        return hasManageUsersPermission(callingUid);
    }

    /**
     * @return whether the given UID is system UID or root's UID or the has the permission
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS}.
     */
    private static boolean hasManageUsersPermission(int callingUid) {
        return UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)
                || callingUid == Process.ROOT_UID
                || hasPermissionGranted(android.Manifest.permission.MANAGE_USERS, callingUid);
    }

    /**
     * @return whether the calling UID is system UID or root's UID or the calling app has the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or the provided permission.
     */
    private static final boolean hasManageUsersOrPermission(String alternativePermission) {
        final int callingUid = Binder.getCallingUid();
        return hasManageUsersPermission(callingUid)
                || hasPermissionGranted(alternativePermission, callingUid);
    }

    /**
     * @return whether the calling UID is system UID or root's UID or the calling app has the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or
     * {@link android.Manifest.permission#CREATE_USERS CREATE_USERS}.
     */
    private static final boolean hasCreateUsersPermission() {
        return hasManageUsersOrPermission(android.Manifest.permission.CREATE_USERS);
    }

    /**
     * @return whether the calling UID is system UID or root's UID or the calling app has the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or
     * {@link android.Manifest.permission#QUERY_USERS QUERY_USERS}.
     */
    private static final boolean hasQueryUsersPermission() {
        return hasManageUsersOrPermission(android.Manifest.permission.QUERY_USERS);
    }

    /**
     * @return whether the calling UID is system UID or root's UID or the calling app has
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or
     * {@link android.Manifest.permission#CREATE_USERS CREATE_USERS} or
     * {@link android.Manifest.permission#QUERY_USERS QUERY_USERS}.
     */
    private static final boolean hasQueryOrCreateUsersPermission() {
        return hasCreateUsersPermission()
                || hasPermissionGranted(Manifest.permission.QUERY_USERS, Binder.getCallingUid());
    }

    /**
     * Enforces that only the system UID or root's UID (on any user) can make certain calls to the
     * UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private static void checkSystemOrRoot(String message) {
        final int uid = Binder.getCallingUid();
        if (!UserHandle.isSameApp(uid, Process.SYSTEM_UID) && uid != Process.ROOT_UID) {
            throw new SecurityException("Only system may: " + message);
        }
    }

    @GuardedBy({"mPackagesLock"})
    private void writeBitmapLP(UserInfo info, Bitmap bitmap) {
        try {
            File dir = new File(mUsersDir, Integer.toString(info.id));
            File file = new File(dir, USER_PHOTO_FILENAME);
            File tmp = new File(dir, USER_PHOTO_FILENAME_TMP);
            if (!dir.exists()) {
                dir.mkdir();
                FileUtils.setPermissions(
                        dir.getPath(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                        -1, -1);
            }
            FileOutputStream os;
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, os = new FileOutputStream(tmp))
                    && tmp.renameTo(file) && SELinux.restorecon(file)) {
                info.iconPath = file.getAbsolutePath();
            }
            try {
                os.close();
            } catch (IOException ioe) {
                // What the ... !
            }
            tmp.delete();
        } catch (FileNotFoundException e) {
            Slog.w(LOG_TAG, "Error setting photo for user ", e);
        }
    }

    /**
     * Returns an array of user ids.
     *
     * <p>This array is cached here for quick access, so do not modify or cache it elsewhere.
     *
     * @return the array of user ids.
     */
    public @NonNull int[] getUserIds() {
        synchronized (mUsersLock) {
            return mUserIds;
        }
    }

    /**
     * Checks whether user with a given ID exists.
     * @param id User id to be checked.
     */
    @VisibleForTesting
    boolean userExists(int id) {
        synchronized (mUsersLock) {
            for (int userId : mUserIds) {
                if (userId == id) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns an array of user ids, including pre-created users.
     *
     * <p>This method should only used for the specific cases that need to handle pre-created users;
     * most callers should call {@link #getUserIds()} instead.
     *
     * <p>This array is cached here for quick access, so do not modify or
     * cache it elsewhere.
     *
     * @return the array of user ids.
     */
    public @NonNull int[] getUserIdsIncludingPreCreated() {
        synchronized (mUsersLock) {
            return mUserIdsIncludingPreCreated;
        }
    }

    /** Checks whether the device is currently in headless system user mode (for any reason). */
    @Override
    public boolean isHeadlessSystemUserMode() {
        synchronized (mUsersLock) {
            final UserData systemUserData = mUsers.get(UserHandle.USER_SYSTEM);
            return !systemUserData.info.isFull();
        }
    }

    /**
     * Checks whether the default state of the device is headless system user mode, i.e. what the
     * mode would be if we did a fresh factory reset.
     * If the mode is  being emulated (via SYSTEM_USER_MODE_EMULATION_PROPERTY) then that will be
     * returned instead.
     * Note that, even in the absence of emulation, a device might deviate from the current default
     * due to an OTA changing the default (which won't change the already-decided mode).
     */
    private boolean isDefaultHeadlessSystemUserMode() {
        if (!Build.isDebuggable()) {
            return RoSystemProperties.MULTIUSER_HEADLESS_SYSTEM_USER;
        }

        final String emulatedValue = SystemProperties.get(SYSTEM_USER_MODE_EMULATION_PROPERTY);
        if (!TextUtils.isEmpty(emulatedValue)) {
            if (UserManager.SYSTEM_USER_MODE_EMULATION_HEADLESS.equals(emulatedValue)) return true;
            if (UserManager.SYSTEM_USER_MODE_EMULATION_FULL.equals(emulatedValue)) return false;
            if (!UserManager.SYSTEM_USER_MODE_EMULATION_DEFAULT.equals(emulatedValue)) {
                Slogf.e(LOG_TAG, "isDefaultHeadlessSystemUserMode(): ignoring invalid valued of "
                                + "property %s: %s",
                        SYSTEM_USER_MODE_EMULATION_PROPERTY, emulatedValue);
            }
        }

        return RoSystemProperties.MULTIUSER_HEADLESS_SYSTEM_USER;
    }

    /**
     * Called on boot to change the system user mode (for example, from headless to full or
     * vice versa) for development purposes.
     */
    private void emulateSystemUserModeIfNeeded() {
        if (!Build.isDebuggable()) {
            return;
        }
        if (TextUtils.isEmpty(SystemProperties.get(SYSTEM_USER_MODE_EMULATION_PROPERTY))) {
            return;
        }

        final boolean newHeadlessSystemUserMode = isDefaultHeadlessSystemUserMode();

        // Update system user type
        synchronized (mPackagesLock) {
            synchronized (mUsersLock) {
                final UserData systemUserData = mUsers.get(UserHandle.USER_SYSTEM);
                if (systemUserData == null) {
                    Slogf.wtf(LOG_TAG, "emulateSystemUserModeIfNeeded(): no system user data");
                    return;
                }
                final int oldMainUserId = getMainUserIdUnchecked();
                final int oldSysFlags = systemUserData.info.flags;
                final int newSysFlags;
                final String newUserType;
                if (newHeadlessSystemUserMode) {
                    newUserType = UserManager.USER_TYPE_SYSTEM_HEADLESS;
                    newSysFlags = oldSysFlags & ~UserInfo.FLAG_FULL & ~UserInfo.FLAG_MAIN;
                } else {
                    newUserType = UserManager.USER_TYPE_FULL_SYSTEM;
                    newSysFlags = oldSysFlags | UserInfo.FLAG_FULL | UserInfo.FLAG_MAIN;
                }

                if (systemUserData.info.userType.equals(newUserType)) {
                    Slogf.d(LOG_TAG, "emulateSystemUserModeIfNeeded(): system user type is already "
                            + "%s, returning", newUserType);
                    return;
                }
                Slogf.i(LOG_TAG, "Persisting emulated system user data: type changed from %s to "
                        + "%s, flags changed from %s to %s",
                        systemUserData.info.userType, newUserType,
                        UserInfo.flagsToString(oldSysFlags), UserInfo.flagsToString(newSysFlags));

                systemUserData.info.userType = newUserType;
                systemUserData.info.flags = newSysFlags;
                writeUserLP(systemUserData);

                // Designate the MainUser to a reasonable choice if needed.
                final UserData oldMain = getUserDataNoChecks(oldMainUserId);
                if (newHeadlessSystemUserMode) {
                    final boolean mainIsAlreadyNonSystem =
                            oldMain != null && (oldMain.info.flags & UserInfo.FLAG_SYSTEM) == 0;
                    if (!mainIsAlreadyNonSystem && isMainUserPermanentAdmin()) {
                        // We need a new choice for Main. Pick the oldest.
                        // If no oldest, don't set any. Let the BootUserInitializer do that later.
                        final UserInfo newMainUser = getEarliestCreatedFullUser();
                        if (newMainUser != null) {
                            Slogf.i(LOG_TAG, "Designating user " + newMainUser.id + " to be Main");
                            newMainUser.flags |= UserInfo.FLAG_MAIN;
                            writeUserLP(getUserDataNoChecks(newMainUser.id));
                        }
                    }
                } else {
                    // We already made user 0 Main above. Now strip it from the old Main user.
                    // TODO(b/256624031): For now, we demand the Main user (if there is one) is
                    //  always the system in non-HSUM. In the future, when we relax this, change how
                    //  we handle MAIN.
                    if (oldMain != null && (oldMain.info.flags & UserInfo.FLAG_SYSTEM) == 0) {
                        Slogf.i(LOG_TAG, "Transferring Main to user 0 from " + oldMain.info.id);
                        oldMain.info.flags &= ~UserInfo.FLAG_MAIN;
                        writeUserLP(oldMain);
                    } else {
                        Slogf.i(LOG_TAG, "Designated user 0 to be Main");
                    }
                }
            }
        }

        // Update emulated mode, which will used to trigger an update on user packages
        mUpdatingSystemUserMode = true;
    }


    private ResilientAtomicFile getUserListFile() {
        File tempBackup = new File(mUserListFile.getParent(), mUserListFile.getName() + ".backup");
        File reserveCopy = new File(mUserListFile.getParent(),
                mUserListFile.getName() + ".reservecopy");
        int fileMode = FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH;
        return new ResilientAtomicFile(mUserListFile, tempBackup, reserveCopy, fileMode,
                "user list", (priority, msg) -> {
            Slog.e(LOG_TAG, msg);
            // Something went wrong, schedule full rewrite.
            scheduleWriteUserList();
        });
    }

    @GuardedBy({"mPackagesLock"})
    private void readUserListLP() {
        // Whether guest restrictions are present on userlist.xml
        boolean guestRestrictionsArePresentOnUserListXml = false;
        try (ResilientAtomicFile file = getUserListFile()) {
            FileInputStream fin = null;
            try {
                fin = file.openRead();
                if (fin == null) {
                    Slog.e(LOG_TAG, "userlist.xml not found, fallback to single user");
                    fallbackToSingleUserLP();
                    return;
                }

                final TypedXmlPullParser parser = Xml.resolvePullParser(fin);
                int type;
                while ((type = parser.next()) != XmlPullParser.START_TAG
                        && type != XmlPullParser.END_DOCUMENT) {
                    // Skip
                }

                if (type != XmlPullParser.START_TAG) {
                    Slog.e(LOG_TAG, "Unable to read user list");
                    fallbackToSingleUserLP();
                    return;
                }

                mNextSerialNumber = -1;
                if (parser.getName().equals(TAG_USERS)) {
                    mNextSerialNumber =
                            parser.getAttributeInt(null, ATTR_NEXT_SERIAL_NO, mNextSerialNumber);
                    mUserVersion =
                            parser.getAttributeInt(null, ATTR_USER_VERSION, mUserVersion);
                    mUserTypeVersion =
                            parser.getAttributeInt(null, ATTR_USER_TYPE_VERSION, mUserTypeVersion);
                }

                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type == XmlPullParser.START_TAG) {
                        final String name = parser.getName();
                        if (name.equals(TAG_USER)) {
                            UserData userData = readUserLP(parser.getAttributeInt(null, ATTR_ID),
                                    mUserVersion);

                            if (userData != null) {
                                synchronized (mUsersLock) {
                                    mUsers.put(userData.info.id, userData);
                                    if (mNextSerialNumber < 0
                                            || mNextSerialNumber <= userData.info.id) {
                                        mNextSerialNumber = userData.info.id + 1;
                                    }
                                    if (userData.info.isEphemeral() && !userData.info.preCreated
                                            && userData.info.id != UserHandle.USER_SYSTEM) {
                                        // Mark ephemeral user as slated for deletion.
                                        addRemovingUserIdLocked(userData.info.id);
                                        userData.info.partial = true;
                                        userData.info.flags |= UserInfo.FLAG_DISABLED;
                                    }
                                }
                            }
                        } else if (name.equals(TAG_GUEST_RESTRICTIONS)) {
                            guestRestrictionsArePresentOnUserListXml = true;
                            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                                    && type != XmlPullParser.END_TAG) {
                                if (type == XmlPullParser.START_TAG) {
                                    if (parser.getName().equals(TAG_RESTRICTIONS)) {
                                        synchronized (mGuestRestrictions) {
                                            UserRestrictionsUtils
                                                    .readRestrictions(parser, mGuestRestrictions);
                                        }
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }

                updateUserIds();
                upgradeIfNecessaryLP();
                updateUsersWithFeatureFlags(guestRestrictionsArePresentOnUserListXml);
            } catch (Exception e) {
                // Remove corrupted file and retry.
                file.failRead(fin, e);
                readUserListLP();
                return;
            }
        }

        synchronized (mUsersLock) {
            if (mUsers.size() == 0) {
                Slog.e(LOG_TAG, "mUsers is empty, fallback to single user");
                fallbackToSingleUserLP();
            }
        }
    }

    /**
     * Upgrade steps between versions, either for fixing bugs or changing the data format.
     */
    @GuardedBy({"mPackagesLock"})
    private void upgradeIfNecessaryLP() {
        upgradeIfNecessaryLP(mUserVersion, mUserTypeVersion);
    }

    /**
     * Update any user formats or Xml data that need to be updated based on the current user state
     * and the feature flag settings.
     */
    @GuardedBy({"mPackagesLock"})
    private void updateUsersWithFeatureFlags(boolean guestRestrictionsArePresentOnUserListXml) {
        // User Xml re-writes are required when guest restrictions are saved on userlist.xml but
        // as per the feature flag it should be on the SYSTEM user's xml or guest restrictions
        // are saved on SYSTEM user's xml but as per the flags it should not be saved there.
        if (guestRestrictionsArePresentOnUserListXml
                == Flags.saveGlobalAndGuestRestrictionsOnSystemUserXmlReadOnly()) {
            for (int userId: getUserIds()) {
                writeUserLP(getUserDataNoChecks(userId));
            }
            writeUserListLP();
        }
    }

    /**
     * Version of {@link #upgradeIfNecessaryLP()} that takes in the userVersion for testing
     * purposes. For non-tests, use {@link #upgradeIfNecessaryLP()}.
     */
    @GuardedBy({"mPackagesLock"})
    @VisibleForTesting
    void upgradeIfNecessaryLP(int userVersion, int userTypeVersion) {
        Slog.i(LOG_TAG, "Upgrading users from userVersion " + userVersion + " to " + USER_VERSION);
        Set<Integer> userIdsToWrite = new ArraySet<>();
        final int originalVersion = mUserVersion;
        final int originalUserTypeVersion = mUserTypeVersion;
        if (userVersion < 1) {
            // Assign a proper name for the owner, if not initialized correctly before
            UserData userData = getUserDataNoChecks(UserHandle.USER_SYSTEM);
            if ("Primary".equals(userData.info.name)) {
                userData.info.name =
                        mContext.getResources().getString(com.android.internal.R.string.owner_name);
                userIdsToWrite.add(userData.info.id);
            }
            userVersion = 1;
        }

        if (userVersion < 2) {
            // Owner should be marked as initialized
            UserData userData = getUserDataNoChecks(UserHandle.USER_SYSTEM);
            if ((userData.info.flags & UserInfo.FLAG_INITIALIZED) == 0) {
                userData.info.flags |= UserInfo.FLAG_INITIALIZED;
                userIdsToWrite.add(userData.info.id);
            }
            userVersion = 2;
        }


        if (userVersion < 4) {
            userVersion = 4;
        }

        if (userVersion < 5) {
            initDefaultGuestRestrictions();
            userVersion = 5;
        }

        if (userVersion < 6) {
            synchronized (mUsersLock) {
                for (int i = 0; i < mUsers.size(); i++) {
                    UserData userData = mUsers.valueAt(i);
                    // Only system user can have restricted profiles
                    if (userData.info.isRestricted() && (userData.info.restrictedProfileParentId
                            == UserInfo.NO_PROFILE_GROUP_ID)) {
                        userData.info.restrictedProfileParentId = UserHandle.USER_SYSTEM;
                        userIdsToWrite.add(userData.info.id);
                    }
                }
            }
            userVersion = 6;
        }

        if (userVersion < 7) {
            // Previously only one user could enforce global restrictions, now it is per-user.
            synchronized (mRestrictionsLock) {
                if (mDevicePolicyUserRestrictions.removeRestrictionsForAllUsers(
                        UserManager.ENSURE_VERIFY_APPS)) {
                    mDevicePolicyUserRestrictions.getRestrictionsNonNull(UserHandle.USER_ALL)
                            .putBoolean(UserManager.ENSURE_VERIFY_APPS, true);
                }
            }
            // DISALLOW_CONFIG_WIFI was made a default guest restriction some time during version 6.
            final List<UserInfo> guestUsers = getGuestUsers();
            for (int i = 0; i < guestUsers.size(); i++) {
                final UserInfo guestUser = guestUsers.get(i);
                if (guestUser != null && !hasUserRestriction(
                        UserManager.DISALLOW_CONFIG_WIFI, guestUser.id)) {
                    setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, true, guestUser.id);
                }
            }
            userVersion = 7;
        }

        if (userVersion < 8) {
            // Added FLAG_FULL and FLAG_SYSTEM flags.
            synchronized (mUsersLock) {
                UserData userData = mUsers.get(UserHandle.USER_SYSTEM);
                userData.info.flags |= UserInfo.FLAG_SYSTEM;
                // We assume that isDefaultHeadlessSystemUserMode() does not change during the OTA
                // from userVersion < 8 since it is documented that pre-R devices do not support its
                // modification. Therefore, its current value should be the same as the pre-update
                // version.
                if (!isDefaultHeadlessSystemUserMode()) {
                    userData.info.flags |= UserInfo.FLAG_FULL;
                }
                userIdsToWrite.add(userData.info.id);

                // Mark FULL all non-profile users except USER_SYSTEM.
                // Start index at 1 since USER_SYSTEM is the smallest userId and we're skipping it.
                for (int i = 1; i < mUsers.size(); i++) {
                    userData = mUsers.valueAt(i);
                    if ((userData.info.flags & UserInfo.FLAG_MANAGED_PROFILE) == 0) {
                        userData.info.flags |= UserInfo.FLAG_FULL;
                        userIdsToWrite.add(userData.info.id);
                    }
                }
            }
            userVersion = 8;
        }

        if (userVersion < 9) {
            // Convert from UserInfo flags to UserTypes. Apply FLAG_PROFILE to FLAG_MANAGED_PROFILE.
            synchronized (mUsersLock) {
                for (int i = 0; i < mUsers.size(); i++) {
                    UserData userData = mUsers.valueAt(i);
                    final int flags = userData.info.flags;
                    if ((flags & UserInfo.FLAG_SYSTEM) != 0) {
                        if ((flags & UserInfo.FLAG_FULL) != 0) {
                            userData.info.userType = UserManager.USER_TYPE_FULL_SYSTEM;
                        } else {
                            userData.info.userType = UserManager.USER_TYPE_SYSTEM_HEADLESS;
                        }
                    } else {
                        try {
                            userData.info.userType = UserInfo.getDefaultUserType(flags);
                        } catch (IllegalArgumentException e) {
                            // TODO(b/142482943): What should we do here? Delete user? Crashloop?
                            throw new IllegalStateException("Cannot upgrade user with flags "
                                    + Integer.toHexString(flags) + " because it doesn't correspond "
                                    + "to a valid user type.", e);
                        }
                    }
                    // OEMs are responsible for their own custom upgrade logic here.

                    final UserTypeDetails userTypeDetails = mUserTypes.get(userData.info.userType);
                    if (userTypeDetails == null) {
                        throw new IllegalStateException(
                                "Cannot upgrade user with flags " + Integer.toHexString(flags)
                                        + " because " + userData.info.userType + " isn't defined"
                                        + " on this device!");
                    }
                    userData.info.flags |= userTypeDetails.getDefaultUserInfoFlags();
                    userIdsToWrite.add(userData.info.id);
                }
            }
            userVersion = 9;
        }

        if (userVersion < 10) {
            // Add UserProperties.
            synchronized (mUsersLock) {
                for (int i = 0; i < mUsers.size(); i++) {
                    final UserData userData = mUsers.valueAt(i);
                    final UserTypeDetails userTypeDetails = mUserTypes.get(userData.info.userType);
                    if (userTypeDetails == null) {
                        throw new IllegalStateException(
                                "Cannot upgrade user because " + userData.info.userType
                                        + " isn't defined on this device!");
                    }
                    userData.userProperties = new UserProperties(
                            userTypeDetails.getDefaultUserPropertiesReference());
                    userIdsToWrite.add(userData.info.id);
                }
            }
            userVersion = 10;
        }

        if (userVersion < 11) {
            // Add FLAG_MAIN
            if (isHeadlessSystemUserMode()) {
                if (isMainUserPermanentAdmin()) {
                    final UserInfo earliestCreatedUser = getEarliestCreatedFullUser();
                    if (earliestCreatedUser != null) {
                        earliestCreatedUser.flags |= UserInfo.FLAG_MAIN;
                        userIdsToWrite.add(earliestCreatedUser.id);
                    }
                }
            } else { // not isHeadlessSystemUserMode
                synchronized (mUsersLock) {
                    final UserData userData = mUsers.get(UserHandle.USER_SYSTEM);
                    userData.info.flags |= UserInfo.FLAG_MAIN;
                    userIdsToWrite.add(userData.info.id);
                }
            }
            userVersion = 11;
        }

        // Reminder: If you add another upgrade, make sure to increment USER_VERSION too.

        // Done with userVersion changes, moving on to deal with userTypeVersion upgrades
        // Upgrade from previous user type to a new user type
        final int newUserTypeVersion = UserTypeFactory.getUserTypeVersion();
        if (newUserTypeVersion > userTypeVersion) {
            synchronized (mUsersLock) {
                upgradeUserTypesLU(UserTypeFactory.getUserTypeUpgrades(), mUserTypes,
                        userTypeVersion, userIdsToWrite);
            }
        }

        if (userVersion < USER_VERSION) {
            Slog.w(LOG_TAG, "User version " + mUserVersion + " didn't upgrade as expected to "
                    + USER_VERSION);
        } else {
            if (userVersion > USER_VERSION) {
                Slog.wtf(LOG_TAG, "Upgraded user version " + mUserVersion + " is higher the SDK's "
                        + "one of " + USER_VERSION + ". Someone forgot to update USER_VERSION?");
            }

            mUserVersion = userVersion;
            mUserTypeVersion = newUserTypeVersion;

            if (originalVersion < mUserVersion || originalUserTypeVersion < mUserTypeVersion) {
                for (int userId : userIdsToWrite) {
                    UserData userData = getUserDataNoChecks(userId);
                    if (userData != null) {
                        writeUserLP(userData);
                    }
                }
                writeUserListLP();
            }
        }
    }

    @GuardedBy("mUsersLock")
    private void upgradeUserTypesLU(@NonNull List<UserTypeFactory.UserTypeUpgrade> upgradeOps,
            @NonNull ArrayMap<String, UserTypeDetails> userTypes,
            final int formerUserTypeVersion,
            @NonNull Set<Integer> userIdsToWrite) {
        for (UserTypeFactory.UserTypeUpgrade userTypeUpgrade : upgradeOps) {
            if (DBG) {
                Slog.i(LOG_TAG, "Upgrade: " + userTypeUpgrade.getFromType() + " to: "
                        + userTypeUpgrade.getToType() + " maxVersion: "
                        + userTypeUpgrade.getUpToVersion());
            }

            // upgrade user type if version up to getUpToVersion()
            if (formerUserTypeVersion <= userTypeUpgrade.getUpToVersion()) {
                for (int i = 0; i < mUsers.size(); i++) {
                    UserData userData = mUsers.valueAt(i);
                    if (userTypeUpgrade.getFromType().equals(userData.info.userType)) {
                        final UserTypeDetails newUserType = userTypes.get(
                                userTypeUpgrade.getToType());

                        if (newUserType == null) {
                            throw new IllegalStateException(
                                    "Upgrade destination user type not defined: "
                                            + userTypeUpgrade.getToType());
                        }

                        upgradeProfileToTypeLU(userData.info, newUserType);
                        userIdsToWrite.add(userData.info.id);
                    }
                }
            }
        }
    }

    /**
     * Changes the user type of a profile to a new user type.
     * @param userInfo    The user to be updated.
     * @param newUserType The new user type.
     */
    @GuardedBy("mUsersLock")
    @VisibleForTesting
    void upgradeProfileToTypeLU(@NonNull UserInfo userInfo, @NonNull UserTypeDetails newUserType) {
        Slog.i(LOG_TAG, "Upgrading user " + userInfo.id
                + " from " + userInfo.userType
                + " to " + newUserType.getName());

        if (!userInfo.isProfile()) {
            throw new IllegalStateException(
                    "Can only upgrade profile types. " + userInfo.userType
                            + " is not a profile type.");
        }

        // Exceeded maximum profiles for parent user: log error, but allow upgrade
        if (!canAddMoreProfilesToUser(newUserType.getName(), userInfo.profileGroupId, false)) {
            Slog.w(LOG_TAG,
                    "Exceeded maximum profiles of type " + newUserType.getName() + " for user "
                            + userInfo.id + ". Maximum allowed= "
                            + newUserType.getMaxAllowedPerParent());
        }

        final UserTypeDetails oldUserType = mUserTypes.get(userInfo.userType);
        final int oldFlags;
        if (oldUserType != null) {
            oldFlags = oldUserType.getDefaultUserInfoFlags();
        } else {
            // if oldUserType is missing from config_user_types.xml -> can only assume FLAG_PROFILE
            oldFlags = UserInfo.FLAG_PROFILE;
        }

        //convert userData to newUserType
        userInfo.userType = newUserType.getName();
        // remove old default flags and add newUserType's default flags
        userInfo.flags = newUserType.getDefaultUserInfoFlags() | (userInfo.flags ^ oldFlags);

        // merge existing base restrictions with the new type's default restrictions
        synchronized (mRestrictionsLock) {
            if (!BundleUtils.isEmpty(newUserType.getDefaultRestrictions())) {
                final Bundle newRestrictions = BundleUtils.clone(
                        mBaseUserRestrictions.getRestrictions(userInfo.id));
                UserRestrictionsUtils.merge(newRestrictions,
                        newUserType.getDefaultRestrictions());
                updateUserRestrictionsInternalLR(newRestrictions, userInfo.id);
                if (DBG) {
                    Slog.i(LOG_TAG, "Updated user " + userInfo.id
                            + " restrictions to " + newRestrictions);
                }
            }
        }

        // re-compute badge index
        userInfo.profileBadge = getFreeProfileBadgeLU(userInfo.profileGroupId, userInfo.userType);
    }

    /** Returns the oldest Full Admin user, or null is if there none. */
    private @Nullable UserInfo getEarliestCreatedFullUser() {
        final List<UserInfo> users = getUsersInternal(true, true, true);
        UserInfo earliestUser = null;
        long earliestCreationTime = Long.MAX_VALUE;
        for (int i = 0; i < users.size(); i++) {
            final UserInfo info = users.get(i);
            if (info.isFull() && info.isAdmin() && info.creationTime >= 0
                    && info.creationTime < earliestCreationTime) {
                earliestCreationTime = info.creationTime;
                earliestUser = info;
            }
        }
        return earliestUser;
    }

    @GuardedBy({"mPackagesLock"})
    private void fallbackToSingleUserLP() {
        // Create the system user
        final String systemUserType = isDefaultHeadlessSystemUserMode()
                ? UserManager.USER_TYPE_SYSTEM_HEADLESS
                : UserManager.USER_TYPE_FULL_SYSTEM;
        final int flags = mUserTypes.get(systemUserType).getDefaultUserInfoFlags()
                | UserInfo.FLAG_INITIALIZED;
        final UserInfo system = new UserInfo(UserHandle.USER_SYSTEM,
                /* name= */ null, /* iconPath= */ null, flags, systemUserType);
        final UserData userData = putUserInfo(system);
        userData.userProperties = new UserProperties(
                mUserTypes.get(userData.info.userType).getDefaultUserPropertiesReference());
        mNextSerialNumber = MIN_USER_ID;
        mUserVersion = USER_VERSION;
        mUserTypeVersion = UserTypeFactory.getUserTypeVersion();

        final Bundle restrictions = new Bundle();
        try {
            final String[] defaultFirstUserRestrictions = mContext.getResources().getStringArray(
                    com.android.internal.R.array.config_defaultFirstUserRestrictions);
            for (String userRestriction : defaultFirstUserRestrictions) {
                if (UserRestrictionsUtils.isValidRestriction(userRestriction)) {
                    restrictions.putBoolean(userRestriction, true);
                }
            }
        } catch (Resources.NotFoundException e) {
            Slog.e(LOG_TAG, "Couldn't find resource: config_defaultFirstUserRestrictions", e);
        }

        if (!restrictions.isEmpty()) {
            synchronized (mRestrictionsLock) {
                mBaseUserRestrictions.updateRestrictions(UserHandle.USER_SYSTEM,
                        restrictions);
            }
        }

        initDefaultGuestRestrictions();

        writeUserLP(userData);
        writeUserListLP();
    }

    private String getOwnerName() {
        return mOwnerName.get();
    }

    private String getGuestName() {
        return mContext.getString(com.android.internal.R.string.guest_name);
    }

    private void invalidateOwnerNameIfNecessary(@NonNull Resources res, boolean forceUpdate) {
        final int configChanges = mLastConfiguration.updateFrom(res.getConfiguration());
        if (forceUpdate || (configChanges & mOwnerNameTypedValue.changingConfigurations) != 0) {
            res.getValue(com.android.internal.R.string.owner_name, mOwnerNameTypedValue, true);
            final CharSequence ownerName = mOwnerNameTypedValue.coerceToString();
            mOwnerName.set(ownerName != null ? ownerName.toString() : null);
        }
    }

    private void scheduleWriteUserList() {
        if (DBG) {
            debug("scheduleWriteUserList");
        }
        // No need to wrap it within a lock -- worst case, we'll just post the same message
        // twice.
        if (!mHandler.hasMessages(WRITE_USER_LIST_MSG)) {
            Message msg = mHandler.obtainMessage(WRITE_USER_LIST_MSG);
            mHandler.sendMessageDelayed(msg, WRITE_USER_DELAY);
        }
    }

    private void scheduleWriteUser(@UserIdInt int userId) {
        if (DBG) {
            debug("scheduleWriteUser");
        }
        // No need to wrap it within a lock -- worst case, we'll just post the same message
        // twice.
        if (!mHandler.hasMessages(WRITE_USER_MSG, userId)) {
            Message msg = mHandler.obtainMessage(WRITE_USER_MSG, userId);
            mHandler.sendMessageDelayed(msg, WRITE_USER_DELAY);
        }
    }

    private ResilientAtomicFile getUserFile(int userId) {
        File file = new File(mUsersDir, userId + XML_SUFFIX);
        File tempBackup = new File(mUsersDir, userId + XML_SUFFIX + ".backup");
        File reserveCopy = new File(mUsersDir, userId + XML_SUFFIX + ".reservecopy");
        int fileMode = FileUtils.S_IRWXU | FileUtils.S_IRWXG | FileUtils.S_IXOTH;
        return new ResilientAtomicFile(file, tempBackup, reserveCopy, fileMode,
                "user info", (priority, msg) -> {
            Slog.e(LOG_TAG, msg);
            // Something went wrong, schedule full rewrite.
            UserData userData = getUserDataNoChecks(userId);
            if (userData != null) {
                scheduleWriteUser(userId);
            }
        });
    }

    @GuardedBy({"mPackagesLock"})
    private void writeUserLP(UserData userData) {
        if (DBG) {
            debug("writeUserLP " + userData);
        }
        try (ResilientAtomicFile userFile = getUserFile(userData.info.id)) {
            FileOutputStream fos = null;
            try {
                fos = userFile.startWrite();
                writeUserLP(userData, fos);
                userFile.finishWrite(fos);
            } catch (Exception ioe) {
                Slog.e(LOG_TAG, "Error writing user info " + userData.info.id, ioe);
                userFile.failWrite(fos);
            }
        }
    }

    /*
     * Writes the user file in this format:
     *
     * <user flags="20039023" id="0">
     *   <name>Primary</name>
     * </user>
     */
    @GuardedBy({"mPackagesLock"})
    @VisibleForTesting
    void writeUserLP(UserData userData, OutputStream os)
            throws IOException, XmlPullParserException {
        final TypedXmlSerializer serializer = Xml.resolveSerializer(os);
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

        final UserInfo userInfo = userData.info;
        serializer.startTag(null, TAG_USER);
        serializer.attributeInt(null, ATTR_ID, userInfo.id);
        serializer.attributeInt(null, ATTR_SERIAL_NO, userInfo.serialNumber);
        serializer.attributeInt(null, ATTR_FLAGS, userInfo.flags);
        serializer.attribute(null, ATTR_TYPE, userInfo.userType);
        serializer.attributeLong(null, ATTR_CREATION_TIME, userInfo.creationTime);
        serializer.attributeLong(null, ATTR_LAST_LOGGED_IN_TIME, userInfo.lastLoggedInTime);
        if (userInfo.lastLoggedInFingerprint != null) {
            serializer.attribute(null, ATTR_LAST_LOGGED_IN_FINGERPRINT,
                    userInfo.lastLoggedInFingerprint);
        }
        serializer.attributeLong(
                null, ATTR_LAST_ENTERED_FOREGROUND_TIME, userData.mLastEnteredForegroundTimeMillis);
        if (userInfo.iconPath != null) {
            serializer.attribute(null,  ATTR_ICON_PATH, userInfo.iconPath);
        }
        if (userInfo.partial) {
            serializer.attributeBoolean(null, ATTR_PARTIAL, true);
        }
        if (userInfo.preCreated) {
            serializer.attributeBoolean(null, ATTR_PRE_CREATED, true);
        }
        if (userInfo.convertedFromPreCreated) {
            serializer.attributeBoolean(null, ATTR_CONVERTED_FROM_PRE_CREATED, true);
        }
        if (userInfo.guestToRemove) {
            serializer.attributeBoolean(null, ATTR_GUEST_TO_REMOVE, true);
        }
        if (userInfo.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID) {
            serializer.attributeInt(null, ATTR_PROFILE_GROUP_ID, userInfo.profileGroupId);
        }
        serializer.attributeInt(null, ATTR_PROFILE_BADGE, userInfo.profileBadge);
        if (userInfo.restrictedProfileParentId != UserInfo.NO_PROFILE_GROUP_ID) {
            serializer.attributeInt(null, ATTR_RESTRICTED_PROFILE_PARENT_ID,
                    userInfo.restrictedProfileParentId);
        }
        // Write seed data
        if (userData.persistSeedData) {
            if (userData.seedAccountName != null) {
                serializer.attribute(null, ATTR_SEED_ACCOUNT_NAME,
                        truncateString(userData.seedAccountName,
                                UserManager.MAX_ACCOUNT_STRING_LENGTH));
            }
            if (userData.seedAccountType != null) {
                serializer.attribute(null, ATTR_SEED_ACCOUNT_TYPE,
                        truncateString(userData.seedAccountType,
                                UserManager.MAX_ACCOUNT_STRING_LENGTH));
            }
        }
        if (userInfo.name != null) {
            serializer.startTag(null, TAG_NAME);
            serializer.text(truncateString(userInfo.name, UserManager.MAX_USER_NAME_LENGTH));
            serializer.endTag(null, TAG_NAME);
        }
        synchronized (mRestrictionsLock) {
            UserRestrictionsUtils.writeRestrictions(serializer,
                    mBaseUserRestrictions.getRestrictions(userInfo.id), TAG_RESTRICTIONS);

            if (Flags.saveGlobalAndGuestRestrictionsOnSystemUserXmlReadOnly()) {
                if (userInfo.id == UserHandle.USER_SYSTEM) {
                    UserRestrictionsUtils.writeRestrictions(serializer,
                            mDevicePolicyUserRestrictions.getRestrictions(UserHandle.USER_ALL),
                            TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS);

                    serializer.startTag(null, TAG_GUEST_RESTRICTIONS);
                    synchronized (mGuestRestrictions) {
                        UserRestrictionsUtils.writeRestrictions(serializer, mGuestRestrictions,
                                TAG_RESTRICTIONS);
                    }
                    serializer.endTag(null, TAG_GUEST_RESTRICTIONS);
                }
            } else {
                UserRestrictionsUtils.writeRestrictions(serializer,
                        mDevicePolicyUserRestrictions.getRestrictions(UserHandle.USER_ALL),
                        TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS);
            }

            UserRestrictionsUtils.writeRestrictions(serializer,
                    mDevicePolicyUserRestrictions.getRestrictions(userInfo.id),
                    TAG_DEVICE_POLICY_LOCAL_RESTRICTIONS);
        }

        if (userData.account != null) {
            serializer.startTag(null, TAG_ACCOUNT);
            serializer.text(userData.account);
            serializer.endTag(null, TAG_ACCOUNT);
        }

        if (userData.persistSeedData && userData.seedAccountOptions != null) {
            serializer.startTag(null, TAG_SEED_ACCOUNT_OPTIONS);
            userData.seedAccountOptions.saveToXml(serializer);
            serializer.endTag(null, TAG_SEED_ACCOUNT_OPTIONS);
        }

        if (userData.userProperties != null) {
            serializer.startTag(null, TAG_USER_PROPERTIES);
            userData.userProperties.writeToXml(serializer);
            serializer.endTag(null, TAG_USER_PROPERTIES);
        }

        if (userData.getLastRequestQuietModeEnabledMillis() != 0L) {
            serializer.startTag(/* namespace */ null, TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL);
            serializer.text(String.valueOf(userData.getLastRequestQuietModeEnabledMillis()));
            serializer.endTag(/* namespace */ null, TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL);
        }

        serializer.startTag(/* namespace */ null, TAG_IGNORE_PREPARE_STORAGE_ERRORS);
        serializer.text(String.valueOf(userData.getIgnorePrepareStorageErrors()));
        serializer.endTag(/* namespace */ null, TAG_IGNORE_PREPARE_STORAGE_ERRORS);

        serializer.endTag(null, TAG_USER);

        serializer.endDocument();
    }

    private String truncateString(String original, int limit) {
        if (original == null || original.length() <= limit) {
            return original;
        }
        return original.substring(0, limit);
    }

    /*
     * Writes the user list file in this format:
     *
     * <users nextSerialNumber="3">
     *   <user id="0"></user>
     *   <user id="2"></user>
     * </users>
     */
    @GuardedBy({"mPackagesLock"})
    private void writeUserListLP() {
        if (DBG) {
            debug("writeUserList");
        }

        try (ResilientAtomicFile file = getUserListFile()) {
            FileOutputStream fos = null;
            try {
                fos = file.startWrite();

                final TypedXmlSerializer serializer = Xml.resolveSerializer(fos);
                serializer.startDocument(null, true);
                serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output",
                        true);

                serializer.startTag(null, TAG_USERS);
                serializer.attributeInt(null, ATTR_NEXT_SERIAL_NO, mNextSerialNumber);
                serializer.attributeInt(null, ATTR_USER_VERSION, mUserVersion);
                serializer.attributeInt(null, ATTR_USER_TYPE_VERSION, mUserTypeVersion);

                if (!Flags.saveGlobalAndGuestRestrictionsOnSystemUserXmlReadOnly()) {
                    serializer.startTag(null, TAG_GUEST_RESTRICTIONS);
                    synchronized (mGuestRestrictions) {
                        UserRestrictionsUtils
                                .writeRestrictions(serializer, mGuestRestrictions,
                                        TAG_RESTRICTIONS);
                    }
                    serializer.endTag(null, TAG_GUEST_RESTRICTIONS);
                }
                int[] userIdsToWrite;
                synchronized (mUsersLock) {
                    userIdsToWrite = new int[mUsers.size()];
                    for (int i = 0; i < userIdsToWrite.length; i++) {
                        UserInfo user = mUsers.valueAt(i).info;
                        userIdsToWrite[i] = user.id;
                    }
                }
                for (int id : userIdsToWrite) {
                    serializer.startTag(null, TAG_USER);
                    serializer.attributeInt(null, ATTR_ID, id);
                    serializer.endTag(null, TAG_USER);
                }

                serializer.endTag(null, TAG_USERS);

                serializer.endDocument();
                file.finishWrite(fos);
            } catch (Exception e) {
                Slog.e(LOG_TAG, "Error writing user list", e);
                file.failWrite(fos);
            }
        }
    }

    @GuardedBy({"mPackagesLock"})
    private UserData readUserLP(int id, int userVersion) {
        try (ResilientAtomicFile file = getUserFile(id)) {
            FileInputStream fis = null;
            try {
                fis = file.openRead();
                if (fis == null) {
                    Slog.e(LOG_TAG, "User info not found, returning null, user id: " + id);
                    return null;
                }
                return readUserLP(id, fis, userVersion);
            } catch (Exception e) {
                // Remove corrupted file and retry.
                Slog.e(LOG_TAG, "Error reading user info, user id: " + id);
                file.failRead(fis, e);
                return readUserLP(id, userVersion);
            }
        }
    }

    @GuardedBy({"mPackagesLock"})
    @VisibleForTesting
    UserData readUserLP(int id, InputStream is, int userVersion) throws IOException,
            XmlPullParserException {
        int flags = 0;
        String userType = null;
        int serialNumber = id;
        String name = null;
        String account = null;
        String iconPath = null;
        long creationTime = 0L;
        long lastLoggedInTime = 0L;
        long lastRequestQuietModeEnabledTimestamp = 0L;
        String lastLoggedInFingerprint = null;
        long lastEnteredForegroundTime = 0L;
        int profileGroupId = UserInfo.NO_PROFILE_GROUP_ID;
        int profileBadge = 0;
        int restrictedProfileParentId = UserInfo.NO_PROFILE_GROUP_ID;
        boolean partial = false;
        boolean preCreated = false;
        boolean converted = false;
        boolean guestToRemove = false;
        boolean persistSeedData = false;
        String seedAccountName = null;
        String seedAccountType = null;
        PersistableBundle seedAccountOptions = null;
        UserProperties userProperties = null;
        Bundle baseRestrictions = null;
        Bundle legacyLocalRestrictions = null;
        Bundle localRestrictions = null;
        Bundle globalRestrictions = null;
        boolean ignorePrepareStorageErrors = true; // default is true for old users

        final TypedXmlPullParser parser = Xml.resolvePullParser(is);
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
            // Skip
        }

        if (type != XmlPullParser.START_TAG) {
            Slog.e(LOG_TAG, "Unable to read user " + id);
            return null;
        }

        if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_USER)) {
            int storedId = parser.getAttributeInt(null, ATTR_ID, -1);
            if (storedId != id) {
                Slog.e(LOG_TAG, "User id does not match the file name");
                return null;
            }
            serialNumber = parser.getAttributeInt(null, ATTR_SERIAL_NO, id);
            flags = parser.getAttributeInt(null, ATTR_FLAGS, 0);
            userType = parser.getAttributeValue(null, ATTR_TYPE);
            userType = userType != null ? userType.intern() : null;
            iconPath = parser.getAttributeValue(null, ATTR_ICON_PATH);
            creationTime = parser.getAttributeLong(null, ATTR_CREATION_TIME, 0);
            lastLoggedInTime = parser.getAttributeLong(null, ATTR_LAST_LOGGED_IN_TIME, 0);
            lastLoggedInFingerprint = parser.getAttributeValue(null,
                    ATTR_LAST_LOGGED_IN_FINGERPRINT);
            lastEnteredForegroundTime =
                    parser.getAttributeLong(null, ATTR_LAST_ENTERED_FOREGROUND_TIME, 0L);
            profileGroupId = parser.getAttributeInt(null, ATTR_PROFILE_GROUP_ID,
                    UserInfo.NO_PROFILE_GROUP_ID);
            profileBadge = parser.getAttributeInt(null, ATTR_PROFILE_BADGE, 0);
            restrictedProfileParentId = parser.getAttributeInt(null,
                    ATTR_RESTRICTED_PROFILE_PARENT_ID, UserInfo.NO_PROFILE_GROUP_ID);
            partial = parser.getAttributeBoolean(null, ATTR_PARTIAL, false);
            preCreated = parser.getAttributeBoolean(null, ATTR_PRE_CREATED, false);
            converted = parser.getAttributeBoolean(null, ATTR_CONVERTED_FROM_PRE_CREATED, false);
            guestToRemove = parser.getAttributeBoolean(null, ATTR_GUEST_TO_REMOVE, false);

            seedAccountName = parser.getAttributeValue(null, ATTR_SEED_ACCOUNT_NAME);
            seedAccountType = parser.getAttributeValue(null, ATTR_SEED_ACCOUNT_TYPE);
            if (seedAccountName != null || seedAccountType != null) {
                persistSeedData = true;
            }

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }
                String tag = parser.getName();
                if (TAG_NAME.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        name = parser.getText();
                    }
                } else if (TAG_RESTRICTIONS.equals(tag)) {
                    baseRestrictions = UserRestrictionsUtils.readRestrictions(parser);
                } else if (TAG_DEVICE_POLICY_RESTRICTIONS.equals(tag)) {
                    legacyLocalRestrictions = UserRestrictionsUtils.readRestrictions(parser);
                } else if (TAG_DEVICE_POLICY_LOCAL_RESTRICTIONS.equals(tag)) {
                    if (userVersion < 10) {
                        // Prior to version 10, the local user restrictions were stored as sub tags
                        // grouped by the user id of the source user. The source is no longer stored
                        // on versions 10+ as this is now stored in the DevicePolicyEngine.
                        RestrictionsSet oldLocalRestrictions =
                                RestrictionsSet.readRestrictions(
                                    parser, TAG_DEVICE_POLICY_LOCAL_RESTRICTIONS);
                        localRestrictions = oldLocalRestrictions.mergeAll();
                    } else {
                        localRestrictions = UserRestrictionsUtils.readRestrictions(parser);
                    }
                } else if (TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS.equals(tag)) {
                    globalRestrictions = UserRestrictionsUtils.readRestrictions(parser);
                } else if (TAG_GUEST_RESTRICTIONS.equals(tag)) {
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                            && type != XmlPullParser.END_TAG) {
                        if (type == XmlPullParser.START_TAG) {
                            if (parser.getName().equals(TAG_RESTRICTIONS)) {
                                synchronized (mGuestRestrictions) {
                                    UserRestrictionsUtils
                                            .readRestrictions(parser, mGuestRestrictions);
                                }
                            }
                            break;
                        }
                    }
                } else if (TAG_ACCOUNT.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        account = parser.getText();
                    }
                } else if (TAG_SEED_ACCOUNT_OPTIONS.equals(tag)) {
                    seedAccountOptions = PersistableBundle.restoreFromXml(parser);
                    persistSeedData = true;
                } else if (TAG_USER_PROPERTIES.equals(tag)) {
                    // We already got the userType above (if it exists), so we can use it.
                    // And it must exist, since ATTR_TYPE historically predates PROPERTIES.
                    final UserTypeDetails userTypeDetails = mUserTypes.get(userType);
                    if (userTypeDetails == null) {
                        Slog.e(LOG_TAG, "User has properties but no user type!");
                        return null;
                    }
                    final UserProperties defaultProps
                            = userTypeDetails.getDefaultUserPropertiesReference();
                    userProperties = new UserProperties(parser, defaultProps);
                } else if (TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        lastRequestQuietModeEnabledTimestamp = Long.parseLong(parser.getText());
                    }
                } else if (TAG_IGNORE_PREPARE_STORAGE_ERRORS.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        ignorePrepareStorageErrors = Boolean.parseBoolean(parser.getText());
                    }
                }
            }
        }

        // Create the UserInfo object that gets passed around
        UserInfo userInfo = new UserInfo(id, name, iconPath, flags, userType);
        userInfo.serialNumber = serialNumber;
        userInfo.creationTime = creationTime;
        userInfo.lastLoggedInTime = lastLoggedInTime;
        userInfo.lastLoggedInFingerprint = lastLoggedInFingerprint;
        userInfo.partial = partial;
        userInfo.preCreated = preCreated;
        userInfo.convertedFromPreCreated = converted;
        userInfo.guestToRemove = guestToRemove;
        userInfo.profileGroupId = profileGroupId;
        userInfo.profileBadge = profileBadge;
        userInfo.restrictedProfileParentId = restrictedProfileParentId;

        // Create the UserData object that's internal to this class
        UserData userData = new UserData();
        userData.info = userInfo;
        userData.account = account;
        userData.seedAccountName = seedAccountName;
        userData.seedAccountType = seedAccountType;
        userData.persistSeedData = persistSeedData;
        userData.seedAccountOptions = seedAccountOptions;
        userData.userProperties = userProperties;
        userData.setLastRequestQuietModeEnabledMillis(lastRequestQuietModeEnabledTimestamp);
        userData.mLastEnteredForegroundTimeMillis = lastEnteredForegroundTime;
        if (ignorePrepareStorageErrors) {
            userData.setIgnorePrepareStorageErrors();
        }

        synchronized (mRestrictionsLock) {
            if (baseRestrictions != null) {
                mBaseUserRestrictions.updateRestrictions(id, baseRestrictions);
            }
            if (localRestrictions != null) {
                mDevicePolicyUserRestrictions.updateRestrictions(id, localRestrictions);
                if (legacyLocalRestrictions != null) {
                    Slog.wtf(LOG_TAG, "Seeing both legacy and current local restrictions in xml");
                }
            } else if (legacyLocalRestrictions != null) {
                mDevicePolicyUserRestrictions.updateRestrictions(id, legacyLocalRestrictions);
            }
            if (globalRestrictions != null) {
                mDevicePolicyUserRestrictions.updateRestrictions(UserHandle.USER_ALL,
                        globalRestrictions);
            }
        }
        return userData;
    }

    /**
     * Removes the app restrictions file for a specific package and user id, if it exists.
     *
     * @return whether there were any restrictions.
     */
    @GuardedBy({"mAppRestrictionsLock"})
    private static boolean cleanAppRestrictionsForPackageLAr(String pkg, @UserIdInt int userId) {
        final File dir = Environment.getUserSystemDirectory(userId);
        final File resFile = new File(dir, packageToRestrictionsFileName(pkg));
        if (resFile.exists()) {
            resFile.delete();
            return true;
        }
        return false;
    }

    /**
     * Creates a profile user. Used for actual profiles, like
     * {@link UserManager#USER_TYPE_PROFILE_MANAGED},
     * as well as for {@link UserManager#USER_TYPE_FULL_RESTRICTED}.
     */
    @Override
    public @NonNull UserInfo createProfileForUserWithThrow(
            @Nullable String name, @NonNull String userType, @UserInfoFlag int flags,
            @UserIdInt int userId, @Nullable String[] disallowedPackages)
            throws ServiceSpecificException {

        checkCreateUsersPermission(flags);
        try {
            return createUserInternal(name, userType, flags, userId, disallowedPackages);
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }
    }

    /**
     * @see #createProfileForUser
     */
    @Override
    public @NonNull UserInfo createProfileForUserEvenWhenDisallowedWithThrow(
            @Nullable String name, @NonNull String userType, @UserInfoFlag int flags,
            @UserIdInt int userId, @Nullable String[] disallowedPackages)
            throws ServiceSpecificException {

        checkCreateUsersPermission(flags);
        try {
            return createUserInternalUnchecked(name, userType, flags, userId,
                    /* preCreate= */ false, disallowedPackages, /* token= */ null);
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }
    }

    @Override
    public @NonNull UserInfo createUserWithThrow(
            @Nullable String name, @NonNull String userType, @UserInfoFlag int flags)
            throws ServiceSpecificException {

        checkCreateUsersPermission(flags);
        try {
            return createUserInternal(name, userType, flags, UserHandle.USER_NULL,
                    /* disallowedPackages= */ null);
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }
    }

    @Override
    public @NonNull UserInfo preCreateUserWithThrow(
            @NonNull String userType)
            throws ServiceSpecificException {

        final UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        final int flags = userTypeDetails != null ? userTypeDetails.getDefaultUserInfoFlags() : 0;

        checkCreateUsersPermission(flags);

        Preconditions.checkArgument(isUserTypeEligibleForPreCreation(userTypeDetails),
                "cannot pre-create user of type " + userType);
        Slog.i(LOG_TAG, "Pre-creating user of type " + userType);

        try {
            return createUserInternalUnchecked(/* name= */ null, userType, flags,
                    /* parentId= */ UserHandle.USER_NULL, /* preCreate= */ true,
                    /* disallowedPackages= */ null, /* token= */ null);
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }
    }

    @Override
    public @NonNull UserHandle createUserWithAttributes(
            @Nullable String userName, @NonNull String userType, @UserInfoFlag int flags,
            @Nullable Bitmap userIcon, @Nullable String accountName, @Nullable String accountType,
            @Nullable PersistableBundle accountOptions)
            throws ServiceSpecificException {

        checkCreateUsersPermission(flags);

        if (someUserHasAccountNoChecks(accountName, accountType)) {
            throw new ServiceSpecificException(
                    UserManager.USER_OPERATION_ERROR_USER_ACCOUNT_ALREADY_EXISTS);
        }

        UserInfo userInfo;
        try {
            userInfo = createUserInternal(userName, userType, flags, UserHandle.USER_NULL, null);
        } catch (UserManager.CheckedUserOperationException e) {
            throw e.toServiceSpecificException();
        }

        if (userIcon != null) {
            mLocalService.setUserIcon(userInfo.id, userIcon);
        }

        setSeedAccountDataNoChecks(userInfo.id, accountName, accountType, accountOptions, true);

        return userInfo.getUserHandle();
    }

    private @NonNull UserInfo createUserInternal(
            @Nullable String name, @NonNull String userType,
            @UserInfoFlag int flags, @UserIdInt int parentId,
            @Nullable String[] disallowedPackages)
            throws UserManager.CheckedUserOperationException {

        // Checking user restriction before creating new user,
        // default check is for DISALLOW_ADD_USER
        // If new user is of type CLONE, check if creation of clone profile is allowed
        // If new user is of type MANAGED, check if creation of managed profile is allowed
        // If new user is of type PRIVATE, check if creation of private profile is allowed
        String restriction = UserManager.DISALLOW_ADD_USER;
        if (UserManager.isUserTypeCloneProfile(userType)) {
            restriction = UserManager.DISALLOW_ADD_CLONE_PROFILE;
        } else if (UserManager.isUserTypeManagedProfile(userType)) {
            restriction = UserManager.DISALLOW_ADD_MANAGED_PROFILE;
        } else if (UserManager.isUserTypePrivateProfile(userType)) {
            restriction = UserManager.DISALLOW_ADD_PRIVATE_PROFILE;
        }

        enforceUserRestriction(restriction, UserHandle.getCallingUserId(),
                "Cannot add user");
        return createUserInternalUnchecked(name, userType, flags, parentId,
                /* preCreate= */ false, disallowedPackages, /* token= */ null);
    }

    private @NonNull UserInfo createUserInternalUnchecked(
            @Nullable String name, @NonNull String userType, @UserInfoFlag int flags,
            @UserIdInt int parentId, boolean preCreate, @Nullable String[] disallowedPackages,
            @Nullable Object token)
            throws UserManager.CheckedUserOperationException {

        final int noneUserId = -1;
        final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("createUser-" + flags);
        mUserJourneyLogger.logUserJourneyBegin(noneUserId, USER_JOURNEY_USER_CREATE);
        UserInfo newUser = null;
        try {
            newUser = createUserInternalUncheckedNoTracing(name, userType, flags, parentId,
                        preCreate, disallowedPackages, t, token);
            return newUser;
        } finally {
            if (newUser != null) {
                mUserJourneyLogger.logUserCreateJourneyFinish(getCurrentUserId(), newUser);
            } else {
                mUserJourneyLogger.logNullUserJourneyError(
                        USER_JOURNEY_USER_CREATE,
                        getCurrentUserId(), noneUserId, userType, flags);
            }
            t.traceEnd();
        }
    }

    private @NonNull UserInfo createUserInternalUncheckedNoTracing(
            @Nullable String name, @NonNull String userType, @UserInfoFlag int flags,
            @UserIdInt int parentId, boolean preCreate, @Nullable String[] disallowedPackages,
            @NonNull TimingsTraceAndSlog t, @Nullable Object token)
            throws UserManager.CheckedUserOperationException {
        String truncatedName = truncateString(name, UserManager.MAX_USER_NAME_LENGTH);
        final UserTypeDetails userTypeDetails = mUserTypes.get(userType);
        if (userTypeDetails == null) {
            throwCheckedUserOperationException(
                    "Cannot create user of invalid user type: " + userType,
                    USER_OPERATION_ERROR_UNKNOWN);
        }
        userType = userType.intern(); // Now that we know it's valid, we can intern it.
        flags |= userTypeDetails.getDefaultUserInfoFlags();
        if (!checkUserTypeConsistency(flags)) {
            throwCheckedUserOperationException(
                    "Cannot add user. Flags (" + Integer.toHexString(flags)
                            + ") and userTypeDetails (" + userType +  ") are inconsistent.",
                    USER_OPERATION_ERROR_UNKNOWN);
        }
        if ((flags & UserInfo.FLAG_SYSTEM) != 0) {
            throwCheckedUserOperationException(
                    "Cannot add user. Flags (" + Integer.toHexString(flags)
                            + ") indicated SYSTEM user, which cannot be created.",
                    USER_OPERATION_ERROR_UNKNOWN);
        }
        if (!isUserTypeEnabled(userTypeDetails)) {
            throwCheckedUserOperationException(
                    "Cannot add a user of disabled type " + userType + ".",
                    UserManager.USER_OPERATION_ERROR_DISABLED_USER);
        }

        synchronized (mUsersLock) {
            if (mForceEphemeralUsers) {
                flags |= UserInfo.FLAG_EPHEMERAL;
            }
        }

        // Try to use a pre-created user (if available).
        if (!preCreate && parentId < 0 && isUserTypeEligibleForPreCreation(userTypeDetails)) {
            final UserInfo preCreatedUser = convertPreCreatedUserIfPossible(userType, flags,
                    truncatedName, token);
            if (preCreatedUser != null) {
                return preCreatedUser;
            }
        }

        DeviceStorageMonitorInternal dsm = LocalServices
                .getService(DeviceStorageMonitorInternal.class);
        if (dsm.isMemoryLow()) {
            throwCheckedUserOperationException(
                    "Cannot add user. Not enough space on disk.",
                    UserManager.USER_OPERATION_ERROR_LOW_STORAGE);
        }

        final boolean isMainUser = (flags & UserInfo.FLAG_MAIN) != 0;
        final boolean isProfile = userTypeDetails.isProfile();
        final boolean isGuest = UserManager.isUserTypeGuest(userType);
        final boolean isRestricted = UserManager.isUserTypeRestricted(userType);
        final boolean isDemo = UserManager.isUserTypeDemo(userType);
        final boolean isManagedProfile = UserManager.isUserTypeManagedProfile(userType);
        final boolean isCommunalProfile = UserManager.isUserTypeCommunalProfile(userType);
        final boolean isPrivateProfile = UserManager.isUserTypePrivateProfile(userType);

        final long ident = Binder.clearCallingIdentity();
        UserInfo userInfo;
        UserData userData;
        final int userId;
        try {
            synchronized (mPackagesLock) {
                UserData parent = null;
                if (parentId != UserHandle.USER_NULL) {
                    synchronized (mUsersLock) {
                        parent = getUserDataLU(parentId);
                    }
                    if (parent == null) {
                        throwCheckedUserOperationException(
                                "Cannot find user data for parent user " + parentId,
                                USER_OPERATION_ERROR_UNKNOWN);
                    }
                }
                if (isMainUser && getMainUserIdUnchecked() != UserHandle.USER_NULL) {
                    throwCheckedUserOperationException(
                            "Cannot add user with FLAG_MAIN as main user already exists.",
                            UserManager.USER_OPERATION_ERROR_MAX_USERS);
                }
                if (!preCreate && !canAddMoreUsersOfType(userTypeDetails)) {
                    throwCheckedUserOperationException(
                            "Cannot add more users of type " + userType
                                    + ". Maximum number of that type already exists.",
                            UserManager.USER_OPERATION_ERROR_MAX_USERS);
                }
                // Keep logic in sync with getRemainingCreatableUserCount()
                if (!isGuest && !isManagedProfile && !isDemo && isUserLimitReached()) {
                    // If the user limit has been reached, we cannot add a user (except guest/demo).
                    // Note that managed profiles can bypass it in certain circumstances (taken
                    // into account in the profile check below).
                    throwCheckedUserOperationException(
                            "Cannot add user. Maximum user limit is reached.",
                            UserManager.USER_OPERATION_ERROR_MAX_USERS);
                }
                // TODO(b/142482943): Perhaps let the following code apply to restricted users too.
                if (isProfile && !isCommunalProfile &&
                        !canAddMoreProfilesToUser(userType, parentId, false)) {
                    throwCheckedUserOperationException(
                            "Cannot add more profiles of type " + userType
                                    + " for user " + parentId,
                            UserManager.USER_OPERATION_ERROR_MAX_USERS);
                }
                if (android.multiuser.Flags.blockPrivateSpaceCreation()
                        && isPrivateProfile && !canAddPrivateProfile(parentId)) {
                    throwCheckedUserOperationException(
                            "Cannot add profile of type " + userType + " for user " + parentId,
                            UserManager.USER_OPERATION_ERROR_PRIVATE_PROFILE);
                }
                if (isRestricted && (parentId != UserHandle.USER_SYSTEM)
                        && !isCreationOverrideEnabled()) {
                    throwCheckedUserOperationException(
                            "Cannot add restricted profile - parent user must be system",
                            USER_OPERATION_ERROR_UNKNOWN);
                }

                userId = getNextAvailableId();
                Slog.i(LOG_TAG, "Creating user " + userId + " of type " + userType);
                Environment.getUserSystemDirectory(userId).mkdirs();

                synchronized (mUsersLock) {
                    // Inherit ephemeral flag from parent.
                    if (parent != null && parent.info.isEphemeral()) {
                        flags |= UserInfo.FLAG_EPHEMERAL;
                    }

                    // Always clear EPHEMERAL for pre-created users, otherwise the storage key
                    // won't be persisted. The flag will be re-added (if needed) when the
                    // pre-created user is "converted" to a normal user.
                    if (preCreate) {
                        flags &= ~UserInfo.FLAG_EPHEMERAL;
                    }

                    if ((flags & UserInfo.FLAG_EPHEMERAL) != 0) {
                        flags |= UserInfo.FLAG_EPHEMERAL_ON_CREATE;
                    }

                    userInfo = new UserInfo(userId, truncatedName, null, flags, userType);
                    userInfo.serialNumber = mNextSerialNumber++;
                    userInfo.creationTime = getCreationTime();
                    userInfo.partial = true;
                    userInfo.preCreated = preCreate;
                    userInfo.lastLoggedInFingerprint = PackagePartitions.FINGERPRINT;
                    if (userTypeDetails.hasBadge() && parentId != UserHandle.USER_NULL) {
                        userInfo.profileBadge = getFreeProfileBadgeLU(parentId, userType);
                    }
                    userData = new UserData();
                    userData.info = userInfo;
                    userData.userProperties = new UserProperties(
                            userTypeDetails.getDefaultUserPropertiesReference());
                    mUsers.put(userId, userData);
                }
                writeUserLP(userData);
                writeUserListLP();
                if (parent != null) {
                    if (isProfile) {
                        if (parent.info.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID) {
                            parent.info.profileGroupId = parent.info.id;
                            writeUserLP(parent);
                        }
                        userInfo.profileGroupId = parent.info.profileGroupId;
                    } else if (isRestricted) {
                        if (parent.info.restrictedProfileParentId == UserInfo.NO_PROFILE_GROUP_ID) {
                            parent.info.restrictedProfileParentId = parent.info.id;
                            writeUserLP(parent);
                        }
                        userInfo.restrictedProfileParentId = parent.info.restrictedProfileParentId;
                    }
                }
            }

            t.traceBegin("createUserStorageKeys");
            final StorageManager storage = mContext.getSystemService(StorageManager.class);
            storage.createUserStorageKeys(userId, userInfo.isEphemeral());
            t.traceEnd();

            // Only prepare DE storage here.  CE storage will be prepared later, when the user is
            // unlocked.  We do this to ensure that CE storage isn't prepared before the CE key is
            // saved to disk.  This also matches what is done for user 0.
            t.traceBegin("prepareUserData");
            mUserDataPreparer.prepareUserData(userInfo, StorageManager.FLAG_STORAGE_DE);
            t.traceEnd();

            t.traceBegin("LSS.createNewUser");
            mLockPatternUtils.createNewUser(userId, userInfo.serialNumber);
            t.traceEnd();

            final Set<String> userTypeInstallablePackages =
                    mSystemPackageInstaller.getInstallablePackagesForUserType(userType);
            t.traceBegin("PM.createNewUser");
            mPm.createNewUser(userId, userTypeInstallablePackages, disallowedPackages);
            t.traceEnd();

            Bundle restrictions = new Bundle();
            if (isGuest) {
                // Guest default restrictions can be modified via setDefaultGuestRestrictions.
                synchronized (mGuestRestrictions) {
                    restrictions.putAll(mGuestRestrictions);
                }
            } else {
                userTypeDetails.addDefaultRestrictionsTo(restrictions);
                if (isMainUser) {
                    restrictions.remove(UserManager.DISALLOW_OUTGOING_CALLS);
                    restrictions.remove(UserManager.DISALLOW_SMS);
                }
            }
            synchronized (mRestrictionsLock) {
                mBaseUserRestrictions.updateRestrictions(userId, restrictions);
            }

            userInfo.partial = false;
            synchronized (mPackagesLock) {
                writeUserLP(userData);
            }
            updateUserIds();

            t.traceBegin("PM.onNewUserCreated-" + userId);
            mPm.onNewUserCreated(userId, /* convertedFromPreCreated= */ false);
            t.traceEnd();
            applyDefaultUserSettings(userTypeDetails, userId);
            setDefaultCrossProfileIntentFilters(userId, userTypeDetails, restrictions, parentId);

            if (preCreate) {
                // Must start user (which will be stopped right away, through
                // UserController.finishUserUnlockedCompleted) so services can properly
                // intialize it.
                // NOTE: user will be stopped on UserController.finishUserUnlockedCompleted().
                Slog.i(LOG_TAG, "starting pre-created user " + userInfo.toFullString());
                final IActivityManager am = ActivityManager.getService();
                try {
                    am.startUserInBackground(userId);
                } catch (RemoteException e) {
                    Slog.w(LOG_TAG, "could not start pre-created user " + userId, e);
                }
            } else {
                dispatchUserAdded(userInfo, token);
            }

        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        // TODO(b/143092698): it's possible to reach "max users overflow" when the user is created
        // "from scratch" (i.e., not from a pre-created user) and reaches the maximum number of
        // users without counting the pre-created one. Then when the pre-created is converted, the
        // "effective" number of max users is exceeds. Example:
        // Max: 3 Current: 2 full (u0 and u10) + 1 pre-created (u11)
        // Step 1: create(/* flags doesn't match u11 */): u12 is created, "effective max" is now 3
        //         (u0, u10, u12) but "real" max is 4 (u0, u10, u11, u12)
        // Step 2: create(/* flags match u11 */): u11 is converted, now "effective max" is also 4
        //         (u0, u10, u11, u12)
        // One way to avoid this issue is by removing a pre-created user from the pool when the
        // "real" max exceeds the max here.

        return userInfo;
    }

    private void applyDefaultUserSettings(UserTypeDetails userTypeDetails, @UserIdInt int userId) {
        final Bundle systemSettings = userTypeDetails.getDefaultSystemSettings();
        final Bundle secureSettings = userTypeDetails.getDefaultSecureSettings();
        if (systemSettings.isEmpty() && secureSettings.isEmpty()) {
            return;
        }

        final int systemSettingsSize = systemSettings.size();
        final String[] systemSettingsArray = systemSettings.keySet().toArray(
                new String[systemSettingsSize]);
        for (int i = 0; i < systemSettingsSize; i++) {
            final String setting = systemSettingsArray[i];
            if (!Settings.System.putStringForUser(
                    mContext.getContentResolver(), setting, systemSettings.getString(setting),
                    userId)) {
                Slog.e(LOG_TAG, "Failed to insert default system setting: " + setting);
            }
        }

        final int secureSettingsSize = secureSettings.size();
        final String[] secureSettingsArray = secureSettings.keySet().toArray(
                new String[secureSettingsSize]);
        for (int i = 0; i < secureSettingsSize; i++) {
            final String setting = secureSettingsArray[i];
            if (!Settings.Secure.putStringForUser(
                    mContext.getContentResolver(), setting, secureSettings.getString(setting),
                    userId)) {
                Slog.e(LOG_TAG, "Failed to insert default secure setting: " + setting);
            }
        }
    }

    /**
     * Sets all default cross profile intent filters between {@code parentUserId} and
     * {@code profileUserId}, does nothing if {@code userType} is not a profile.
     */
    private void setDefaultCrossProfileIntentFilters(
            @UserIdInt int profileUserId, UserTypeDetails profileDetails,
            Bundle profileRestrictions, @UserIdInt int parentUserId) {
        if (profileDetails == null || !profileDetails.isProfile()) {
            return;
        }
        final List<DefaultCrossProfileIntentFilter> filters =
                profileDetails.getDefaultCrossProfileIntentFilters();
        if (filters.isEmpty()) {
            return;
        }

        // Skip filters that allow data to be shared into the profile, if admin has disabled it.
        final boolean disallowSharingIntoProfile =
                profileRestrictions.getBoolean(
                        UserManager.DISALLOW_SHARE_INTO_MANAGED_PROFILE,
                        /* defaultValue = */ false);
        final int size = profileDetails.getDefaultCrossProfileIntentFilters().size();
        for (int i = 0; i < size; i++) {
            final DefaultCrossProfileIntentFilter filter =
                    profileDetails.getDefaultCrossProfileIntentFilters().get(i);
            if (disallowSharingIntoProfile && filter.letsPersonalDataIntoProfile) {
                continue;
            }
            if (filter.direction == DefaultCrossProfileIntentFilter.Direction.TO_PARENT) {
                mPm.addCrossProfileIntentFilter(mPm.snapshotComputer(),
                        filter.filter, mContext.getOpPackageName(), profileUserId, parentUserId,
                        filter.flags);
            } else {
                mPm.addCrossProfileIntentFilter(mPm.snapshotComputer(),
                        filter.filter, mContext.getOpPackageName(), parentUserId, profileUserId,
                        filter.flags);
            }
        }
    }

    /**
     * Finds and converts a previously pre-created user into a regular user, if possible.
     *
     * @return the converted user, or {@code null} if no pre-created user could be converted.
     */
    private @Nullable UserInfo convertPreCreatedUserIfPossible(String userType,
            @UserInfoFlag int flags, @Nullable String name, @Nullable Object token) {
        final UserData preCreatedUserData;
        synchronized (mUsersLock) {
            preCreatedUserData = getPreCreatedUserLU(userType);
        }
        if (preCreatedUserData == null) {
            return null;
        }
        synchronized (mUserStates) {
            if (mUserStates.has(preCreatedUserData.info.id)) {
                Slog.w(LOG_TAG, "Cannot reuse pre-created user "
                        + preCreatedUserData.info.id + " because it didn't stop yet");
                return null;
            }
        }
        final UserInfo preCreatedUser = preCreatedUserData.info;
        final int newFlags = preCreatedUser.flags | flags;
        if (!checkUserTypeConsistency(newFlags)) {
            Slog.wtf(LOG_TAG, "Cannot reuse pre-created user " + preCreatedUser.id
                    + " of type " + userType + " because flags are inconsistent. "
                    + "Flags (" + Integer.toHexString(flags) + "); preCreatedUserFlags ( "
                    + Integer.toHexString(preCreatedUser.flags) + ").");
            return null;
        }
        Slog.i(LOG_TAG, "Reusing pre-created user " + preCreatedUser.id + " of type "
                + userType + " and bestowing on it flags " + UserInfo.flagsToString(flags));
        preCreatedUser.name = name;
        preCreatedUser.flags = newFlags;
        preCreatedUser.preCreated = false;
        preCreatedUser.convertedFromPreCreated = true;
        preCreatedUser.creationTime = getCreationTime();

        synchronized (mPackagesLock) {
            writeUserLP(preCreatedUserData);
            writeUserListLP();
        }
        updateUserIds();
        Binder.withCleanCallingIdentity(() -> {
            mPm.onNewUserCreated(preCreatedUser.id, /* convertedFromPreCreated= */ true);
            dispatchUserAdded(preCreatedUser, token);
            VoiceInteractionManagerInternal vimi = LocalServices
                    .getService(VoiceInteractionManagerInternal.class);
            if (vimi != null) {
                vimi.onPreCreatedUserConversion(preCreatedUser.id);
            }
        });
        return preCreatedUser;
    }

    /** Checks that the flags do not contain mutually exclusive types/properties. */
    @VisibleForTesting
    static boolean checkUserTypeConsistency(@UserInfoFlag int flags) {
        // Mask to check that flags don't refer to multiple user types.
        final int userTypeFlagMask = UserInfo.FLAG_GUEST | UserInfo.FLAG_DEMO
                | UserInfo.FLAG_RESTRICTED | UserInfo.FLAG_PROFILE;
        return isAtMostOneFlag(flags & userTypeFlagMask)
                && isAtMostOneFlag(flags & (UserInfo.FLAG_PROFILE | UserInfo.FLAG_FULL))
                && isAtMostOneFlag(flags & (UserInfo.FLAG_PROFILE | UserInfo.FLAG_SYSTEM));
    }

    /** Returns whether the given flags contains at most one 1. */
    private static boolean isAtMostOneFlag(int flags) {
        return (flags & (flags - 1)) == 0;
        // If !=0, this means that flags is not a power of 2, and therefore is multiple types.
    }

    /** Install/uninstall system packages for all users based on their user-type, as applicable. */
    boolean installWhitelistedSystemPackages(boolean isFirstBoot, boolean isUpgrade,
            @Nullable ArraySet<String> existingPackages) {
        return mSystemPackageInstaller.installWhitelistedSystemPackages(
                isFirstBoot || mUpdatingSystemUserMode, isUpgrade, existingPackages);
    }

    @Override
    public String[] getPreInstallableSystemPackages(@NonNull String userType) {
        checkCreateUsersPermission("getPreInstallableSystemPackages");
        final Set<String> installableSystemPackages =
                mSystemPackageInstaller.getInstallablePackagesForUserType(userType);
        if (installableSystemPackages == null) {
            return null;
        }
        return installableSystemPackages.toArray(new String[installableSystemPackages.size()]);
    }

    private long getCreationTime() {
        final long now = System.currentTimeMillis();
        return (now > EPOCH_PLUS_30_YEARS) ? now : 0;
    }

    private void dispatchUserAdded(@NonNull UserInfo userInfo, @Nullable Object token) {
        // Notify internal listeners first...
        synchronized (mUserLifecycleListeners) {
            for (int i = 0; i < mUserLifecycleListeners.size(); i++) {
                mUserLifecycleListeners.get(i).onUserCreated(userInfo, token);
            }
        }

        //...then external ones
        Intent addedIntent = new Intent(Intent.ACTION_USER_ADDED);
        addedIntent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        // In HSUM, MainUser might be created before PHASE_ACTIVITY_MANAGER_READY has been sent.
        addedIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userInfo.id);
        // Also, add the UserHandle for mainline modules which can't use the @hide
        // EXTRA_USER_HANDLE.
        addedIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(userInfo.id));
        mContext.sendBroadcastAsUser(addedIntent, UserHandle.ALL,
                android.Manifest.permission.MANAGE_USERS);
        MetricsLogger.count(mContext, userInfo.isGuest() ? TRON_GUEST_CREATED
                : (userInfo.isDemo() ? TRON_DEMO_CREATED : TRON_USER_CREATED), 1);

        if (userInfo.isProfile()) {
            sendProfileAddedBroadcast(userInfo.profileGroupId, userInfo.id);
        } else {
            // If the user switch hasn't been explicitly toggled on or off by the user, turn it on.
            if (android.provider.Settings.Global.getString(mContext.getContentResolver(),
                    android.provider.Settings.Global.USER_SWITCHER_ENABLED) == null) {
                android.provider.Settings.Global.putInt(mContext.getContentResolver(),
                        android.provider.Settings.Global.USER_SWITCHER_ENABLED, 1);
            }
        }
    }

    /**
     * Gets a pre-created user for the given user type.
     *
     * <p>Should be used only during user creation, so the pre-created user can be used (instead of
     * creating and initializing a new user from scratch).
     */
    // TODO(b/143092698): add unit test
    @GuardedBy("mUsersLock")
    private @Nullable UserData getPreCreatedUserLU(String userType) {
        if (DBG) Slog.d(LOG_TAG, "getPreCreatedUser(): userType= " + userType);
        final int userSize = mUsers.size();
        for (int i = 0; i < userSize; i++) {
            final UserData user = mUsers.valueAt(i);
            if (DBG) Slog.d(LOG_TAG, i + ":" + user.info.toFullString());
            if (user.info.preCreated && !user.info.partial && user.info.userType.equals(userType)) {
                if (!user.info.isInitialized()) {
                    Slog.w(LOG_TAG, "found pre-created user of type " + userType
                            + ", but it's not initialized yet: " + user.info.toFullString());
                    continue;
                }
                return user;
            }
        }
        return null;
    }

    /**
     * Returns whether a user with the given userTypeDetails is eligible to be
     * {@link UserInfo#preCreated}.
     */
    private static boolean isUserTypeEligibleForPreCreation(UserTypeDetails userTypeDetails) {
        if (userTypeDetails == null) {
            return false;
        }
        return !userTypeDetails.isProfile()
                && !userTypeDetails.getName().equals(UserManager.USER_TYPE_FULL_RESTRICTED);
    }

    /** Register callbacks for statsd pulled atoms. */
    private void registerStatsCallbacks() {
        final StatsManager statsManager = mContext.getSystemService(StatsManager.class);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.USER_INFO,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                this::onPullAtom);
        statsManager.setPullAtomCallback(
                FrameworkStatsLog.MULTI_USER_INFO,
                null, // use default PullAtomMetadata values
                DIRECT_EXECUTOR,
                this::onPullAtom);
    }

    /** Writes a UserInfo pulled atom for each user on the device. */
    private int onPullAtom(int atomTag, List<StatsEvent> data) {
        if (atomTag == FrameworkStatsLog.USER_INFO) {
            final List<UserInfo> users = getUsersInternal(true, true, true);
            final int size = users.size();
            if (size > 1) {
                for (int idx = 0; idx < size; idx++) {
                    final UserInfo user = users.get(idx);
                    final int userTypeStandard = mUserJourneyLogger
                            .getUserTypeForStatsd(user.userType);
                    final String userTypeCustom = (userTypeStandard == FrameworkStatsLog
                            .USER_LIFECYCLE_JOURNEY_REPORTED__USER_TYPE__TYPE_UNKNOWN)
                            ?
                            user.userType : null;

                    boolean isUserRunningUnlocked;
                    synchronized (mUserStates) {
                        isUserRunningUnlocked =
                                mUserStates.get(user.id, -1) == UserState.STATE_RUNNING_UNLOCKED;
                    }

                    data.add(FrameworkStatsLog.buildStatsEvent(FrameworkStatsLog.USER_INFO,
                            user.id,
                            userTypeStandard,
                            userTypeCustom,
                            user.flags,
                            user.creationTime,
                            user.lastLoggedInTime,
                            isUserRunningUnlocked
                    ));
                }
            }
        } else if (atomTag == FrameworkStatsLog.MULTI_USER_INFO) {
            if (UserManager.getMaxSupportedUsers() > 1) {
                data.add(FrameworkStatsLog.buildStatsEvent(FrameworkStatsLog.MULTI_USER_INFO,
                        UserManager.getMaxSupportedUsers(),
                        isUserSwitcherEnabled(UserHandle.USER_ALL),
                        UserManager.supportsMultipleUsers()
                                && !hasUserRestriction(UserManager.DISALLOW_ADD_USER,
                                        UserHandle.USER_ALL)));
            }
        } else {
            Slogf.e(LOG_TAG, "Unexpected atom tag: %d", atomTag);
            return android.app.StatsManager.PULL_SKIP;
        }
        return android.app.StatsManager.PULL_SUCCESS;
    }

    @VisibleForTesting
    UserData putUserInfo(UserInfo userInfo) {
        final UserData userData = new UserData();
        userData.info = userInfo;
        synchronized (mUsersLock) {
            mUsers.put(userInfo.id, userData);
        }
        updateUserIds();
        return userData;
    }

    @VisibleForTesting
    void removeUserInfo(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            mUsers.remove(userId);
        }
    }

    /**
     * @hide
     */
    @Override
    public @NonNull UserInfo createRestrictedProfileWithThrow(
            @Nullable String name, @UserIdInt int parentUserId)
            throws ServiceSpecificException {

        checkCreateUsersPermission("setupRestrictedProfile");
        final UserInfo user = createProfileForUserWithThrow(
                name, UserManager.USER_TYPE_FULL_RESTRICTED, 0, parentUserId, null);
        final long identity = Binder.clearCallingIdentity();
        try {
            setUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS, true, user.id);
            // Change the setting before applying the DISALLOW_SHARE_LOCATION restriction, otherwise
            // the putIntForUser() will fail.
            android.provider.Settings.Secure.putIntForUser(mContext.getContentResolver(),
                    android.provider.Settings.Secure.LOCATION_MODE,
                    android.provider.Settings.Secure.LOCATION_MODE_OFF, user.id);
            setUserRestriction(UserManager.DISALLOW_SHARE_LOCATION, true, user.id);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return user;
    }

    /**
     * Gets the existing guest users. If a Guest user is partial,
     * then do not include it in the results as it is about to die.
     *
     * @return list of existing Guest users currently on the device.
     */
    @Override
    public List<UserInfo> getGuestUsers() {
        checkManageUsersPermission("getGuestUsers");
        final ArrayList<UserInfo> guestUsers = new ArrayList<>();
        synchronized (mUsersLock) {
            final int size = mUsers.size();
            for (int i = 0; i < size; i++) {
                final UserInfo user = mUsers.valueAt(i).info;
                if (user.isGuest() && !user.guestToRemove && !user.preCreated
                        && !mRemovingUserIds.get(user.id)) {
                    guestUsers.add(user);
                }
            }
        }
        return guestUsers;
    }

    /**
     * Mark this guest user for deletion to allow us to create another guest
     * and switch to that user before actually removing this guest.
     * @param userId the userid of the current guest
     * @return whether the user could be marked for deletion
     */
    @Override
    public boolean markGuestForDeletion(@UserIdInt int userId) {
        checkManageUsersPermission("Only the system can remove users");
        if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean(
                UserManager.DISALLOW_REMOVE_USER, false)) {
            Slog.w(LOG_TAG, "Cannot remove user. DISALLOW_REMOVE_USER is enabled.");
            return false;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            final UserData userData;
            synchronized (mPackagesLock) {
                synchronized (mUsersLock) {
                    userData = mUsers.get(userId);
                    if (userId == 0 || userData == null || mRemovingUserIds.get(userId)) {
                        return false;
                    }
                }
                if (!userData.info.isGuest()) {
                    return false;
                }
                // We set this to a guest user that is to be removed. This is a temporary state
                // where we are allowed to add new Guest users, even if this one is still not
                // removed. This user will still show up in getUserInfo() calls.
                // If we don't get around to removing this Guest user, it will be purged on next
                // startup.
                userData.info.guestToRemove = true;
                // Mark it as disabled, so that it isn't returned any more when
                // profiles are queried.
                userData.info.flags |= UserInfo.FLAG_DISABLED;
                writeUserLP(userData);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return true;
    }

    /**
     * Removes a user and its profiles along with all data directories created for that user
     * and its profile.
     * This method should be called after the user's processes have been terminated.
     * @param userId the user's id
     */
    @Override
    public boolean removeUser(@UserIdInt int userId) {
        Slog.i(LOG_TAG, "removeUser u" + userId);
        checkCreateUsersPermission("Only the system can remove users");

        final String restriction = getUserRemovalRestriction(userId);
        if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean(restriction, false)) {
            Slog.w(LOG_TAG, "Cannot remove user. " + restriction + " is enabled.");
            return false;
        }
        return removeUserWithProfilesUnchecked(userId);
    }

    private boolean removeUserWithProfilesUnchecked(@UserIdInt int userId) {
        final UserData userData;
        final boolean isProfile;
        final IntArray profileIds;
        synchronized (mUsersLock) {
            final int userRemovability = getUserRemovabilityLocked(userId, "removed");
            if (userRemovability != UserManager.REMOVE_RESULT_USER_IS_REMOVABLE) {
                return UserManager.isRemoveResultSuccessful(userRemovability);
            }
            userData = mUsers.get(userId);
            isProfile = userData.info.isProfile();
            profileIds = isProfile ? null : getProfileIdsLU(userId, null, false, /* excludeHidden */
                    false);
        }

        if (!isProfile) {
            Pair<Integer, Integer> currentAndTargetUserIds = getCurrentAndTargetUserIds();
            if (userId == currentAndTargetUserIds.first) {
                Slog.w(LOG_TAG, "Current user cannot be removed.");
                return false;
            }
            if (userId == currentAndTargetUserIds.second) {
                Slog.w(LOG_TAG, "Target user of an ongoing user switch cannot be removed.");
                return false;
            }
            for (int i = profileIds.size() - 1; i >= 0; i--) {
                int profileId = profileIds.get(i);
                if (profileId == userId) {
                    //Remove the associated profiles first and then remove the user
                    continue;
                }
                Slog.i(LOG_TAG, "removing profile:" + profileId
                        + " associated with user:" + userId);
                if (!removeUserUnchecked(profileId)) {
                    // If the profile was not immediately removed, make sure it is marked as
                    // ephemeral. Don't mark as disabled since, per UserInfo.FLAG_DISABLED
                    // documentation, an ephemeral user should only be marked as disabled
                    // when its removal is in progress.
                    Slog.i(LOG_TAG, "Unable to immediately remove profile " + profileId
                            + "associated with user " + userId + ". User is set as ephemeral "
                            + "and will be removed on user switch or reboot.");
                    synchronized (mPackagesLock) {
                        UserData profileData = getUserDataNoChecks(userId);
                        profileData.info.flags |= UserInfo.FLAG_EPHEMERAL;

                        writeUserLP(profileData);
                    }
                }
            }
        }

        return removeUserUnchecked(userId);
    }

    @Override
    public boolean removeUserEvenWhenDisallowed(@UserIdInt int userId) {
        checkCreateUsersPermission("Only the system can remove users");
        return removeUserWithProfilesUnchecked(userId);
    }

    /**
     * Returns the string name of the restriction to check for user removal. The restriction name
     * varies depending on whether the user is a managed profile.
     */
    private String getUserRemovalRestriction(@UserIdInt int userId) {
        final boolean isManagedProfile;
        final UserInfo userInfo;
        synchronized (mUsersLock) {
            userInfo = getUserInfoLU(userId);
        }
        isManagedProfile = userInfo != null && userInfo.isManagedProfile();
        return isManagedProfile
                ? UserManager.DISALLOW_REMOVE_MANAGED_PROFILE : UserManager.DISALLOW_REMOVE_USER;
    }

    private boolean removeUserUnchecked(@UserIdInt int userId) {
        final long ident = Binder.clearCallingIdentity();
        try {
            final UserData userData;
            synchronized (mPackagesLock) {
                synchronized (mUsersLock) {
                    final int userRemovability = getUserRemovabilityLocked(userId, "removed");
                    if (userRemovability != UserManager.REMOVE_RESULT_USER_IS_REMOVABLE) {
                        return UserManager.isRemoveResultSuccessful(userRemovability);
                    }
                    userData = mUsers.get(userId);
                    Slog.i(LOG_TAG, "Removing user " + userId);
                    addRemovingUserIdLocked(userId);
                }
                // Set this to a partially created user, so that the user will be purged
                // on next startup, in case the runtime stops now before stopping and
                // removing the user completely.
                userData.info.partial = true;
                // Mark it as disabled, so that it isn't returned any more when
                // profiles are queried.
                userData.info.flags |= UserInfo.FLAG_DISABLED;
                writeUserLP(userData);
            }

            mUserJourneyLogger.logUserJourneyBegin(userId, USER_JOURNEY_USER_REMOVE);
            mUserJourneyLogger.startSessionForDelayedJourney(userId,
                    USER_JOURNEY_USER_LIFECYCLE, userData.info.creationTime);

            try {
                mAppOpsService.removeUser(userId);
            } catch (RemoteException e) {
                Slog.w(LOG_TAG, "Unable to notify AppOpsService of removing user.", e);
            }

            if (userData.info.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                    && userData.info.isProfile()) {
                sendProfileRemovedBroadcast(userData.info.profileGroupId, userData.info.id,
                        userData.info.userType);
            }

            if (DBG) Slog.i(LOG_TAG, "Stopping user " + userId);
            int res;
            try {
                res = ActivityManager.getService().stopUserWithCallback(userId,
                new IStopUserCallback.Stub() {
                            @Override
                            public void userStopped(int userIdParam) {
                                finishRemoveUser(userIdParam);
                                int originUserId = UserManagerService.this.getCurrentUserId();
                                mUserJourneyLogger.logUserJourneyFinishWithError(originUserId,
                                        userData.info, USER_JOURNEY_USER_REMOVE,
                                        ERROR_CODE_UNSPECIFIED);
                                mUserJourneyLogger
                                        .logDelayedUserJourneyFinishWithError(originUserId,
                                                userData.info, USER_JOURNEY_USER_LIFECYCLE,
                                                ERROR_CODE_UNSPECIFIED);
                            }
                            @Override
                            public void userStopAborted(int userIdParam) {
                                int originUserId = UserManagerService.this.getCurrentUserId();
                                mUserJourneyLogger.logUserJourneyFinishWithError(originUserId,
                                        userData.info, USER_JOURNEY_USER_REMOVE,
                                        ERROR_CODE_ABORTED);
                                mUserJourneyLogger
                                        .logDelayedUserJourneyFinishWithError(originUserId,
                                                userData.info, USER_JOURNEY_USER_LIFECYCLE,
                                                ERROR_CODE_ABORTED);
                            }
                        });
            } catch (RemoteException e) {
                Slog.w(LOG_TAG, "Failed to stop user during removal.", e);
                return false;
            }
            return res == ActivityManager.USER_OP_SUCCESS;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @VisibleForTesting
    void addRemovingUserId(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            addRemovingUserIdLocked(userId);
        }
    }

    @GuardedBy("mUsersLock")
    void addRemovingUserIdLocked(@UserIdInt int userId) {
        // We remember deleted user IDs to prevent them from being
        // reused during the current boot; they can still be reused
        // after a reboot or recycling of userIds.
        mRemovingUserIds.put(userId, true);
        mRecentlyRemovedIds.add(userId);
        // Keep LRU queue of recently removed IDs for recycling
        if (mRecentlyRemovedIds.size() > MAX_RECENTLY_REMOVED_IDS_SIZE) {
            mRecentlyRemovedIds.removeFirst();
        }
    }

    @Override
    public @UserManager.RemoveResult int removeUserWhenPossible(@UserIdInt int userId,
            boolean overrideDevicePolicy) {
        Slog.i(LOG_TAG, "removeUserWhenPossible u" + userId);
        checkCreateUsersPermission("Only the system can remove users");

        if (!overrideDevicePolicy) {
            final String restriction = getUserRemovalRestriction(userId);
            if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean(restriction, false)) {
                Slog.w(LOG_TAG, "Cannot remove user. " + restriction + " is enabled.");
                return UserManager.REMOVE_RESULT_ERROR_USER_RESTRICTION;
            }
        }
        Slog.i(LOG_TAG, "Attempting to immediately remove user " + userId);
        if (removeUserWithProfilesUnchecked(userId)) {
            return UserManager.REMOVE_RESULT_REMOVED;
        }
        Slog.i(LOG_TAG, TextUtils.formatSimple(
                "Unable to immediately remove user %d. Now trying to set it ephemeral.", userId));
        return setUserEphemeralUnchecked(userId);
    }

    /**
     * Returns the user's removability status.
     * User is removable if the return value is {@link UserManager#REMOVE_RESULT_USER_IS_REMOVABLE}.
     * If the user is not removable this method also prints the reason.
     * See also {@link UserManager#isRemoveResultSuccessful}.
     */
    @GuardedBy("mUsersLock")
    private @UserManager.RemoveResult int getUserRemovabilityLocked(@UserIdInt int userId,
            String msg) {
        String prefix = TextUtils.formatSimple("User %d can not be %s, ", userId, msg);
        if (userId == UserHandle.USER_SYSTEM) {
            Slog.e(LOG_TAG, prefix + "system user cannot be removed.");
            return UserManager.REMOVE_RESULT_ERROR_SYSTEM_USER;
        }
        final UserData userData = mUsers.get(userId);
        if (userData == null) {
            Slog.e(LOG_TAG, prefix + "invalid user id provided.");
            return UserManager.REMOVE_RESULT_ERROR_USER_NOT_FOUND;
        }
        if (isNonRemovableMainUser(userData.info)) {
            Slog.e(LOG_TAG, prefix
                    + "main user cannot be removed when it's a permanent admin user.");
            return UserManager.REMOVE_RESULT_ERROR_MAIN_USER_PERMANENT_ADMIN;
        }
        if (mRemovingUserIds.get(userId)) {
            Slog.w(LOG_TAG, prefix + "it is already scheduled for removal.");
            return UserManager.REMOVE_RESULT_ALREADY_BEING_REMOVED;
        }
        return UserManager.REMOVE_RESULT_USER_IS_REMOVABLE;
    }


    private void finishRemoveUser(final @UserIdInt int userId) {
        Slog.i(LOG_TAG, "finishRemoveUser " + userId);

        UserInfo user;
        synchronized (mUsersLock) {
            user = getUserInfoLU(userId);
        }
        if (user != null && user.preCreated) {
            Slog.i(LOG_TAG, "Removing a pre-created user with user id: " + userId);
            // Don't want to fire ACTION_USER_REMOVED, so cleanup the state and exit early.
            LocalServices.getService(ActivityTaskManagerInternal.class).onUserStopped(userId);
            removeUserState(userId);
            return;
        }

        synchronized (mUserLifecycleListeners) {
            for (int i = 0; i < mUserLifecycleListeners.size(); i++) {
                mUserLifecycleListeners.get(i).onUserRemoved(user);
            }
        }

        // Let other services shutdown any activity and clean up their state before completely
        // wiping the user's system directory and removing from the user list
        final long ident = Binder.clearCallingIdentity();
        try {
            Intent removedIntent = new Intent(Intent.ACTION_USER_REMOVED);
            removedIntent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            removedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            // Also, add the UserHandle for mainline modules which can't use the @hide
            // EXTRA_USER_HANDLE.
            removedIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(userId));
            getActivityManagerInternal().broadcastIntentWithCallback(removedIntent,
                    new IIntentReceiver.Stub() {
                        @Override
                        public void performReceive(Intent intent, int resultCode, String data,
                                Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                            if (DBG) {
                                Slog.i(LOG_TAG,
                                        "USER_REMOVED broadcast sent, cleaning up user data "
                                                + userId);
                            }
                            new Thread(() -> {
                                getActivityManagerInternal().onUserRemoved(userId);
                                removeUserState(userId);
                            }).start();
                        }
                    },
                    new String[] {android.Manifest.permission.MANAGE_USERS},
                    UserHandle.USER_ALL, null, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void removeUserState(final @UserIdInt int userId) {
        Slog.i(LOG_TAG, "Removing user state of user " + userId);

        // Cleanup lock settings.  This requires that the user's DE storage still be accessible, so
        // this must happen before destroyUserStorageKeys().
        mLockPatternUtils.removeUser(userId);

        // Evict and destroy the user's CE and DE encryption keys.  At this point, the user's CE and
        // DE storage is made inaccessible, except to delete its contents.
        try {
            mContext.getSystemService(StorageManager.class).destroyUserStorageKeys(userId);
        } catch (IllegalStateException e) {
            // This may be simply because the user was partially created.
            Slog.i(LOG_TAG, "Destroying storage keys for user " + userId
                    + " failed, continuing anyway", e);
        }

        // Cleanup package manager settings
        mPm.cleanUpUser(this, userId);

        // Clean up all data before removing metadata
        mUserDataPreparer.destroyUserData(userId,
                StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE);

        // Remove this user from the list
        synchronized (mUsersLock) {
            mUsers.remove(userId);
            mIsUserManaged.delete(userId);
        }
        synchronized (mUserStates) {
            mUserStates.delete(userId);
        }
        synchronized (mRestrictionsLock) {
            mBaseUserRestrictions.remove(userId);
            mAppliedUserRestrictions.remove(userId);
            mCachedEffectiveUserRestrictions.remove(userId);
            // Remove restrictions affecting the user
            if (mDevicePolicyUserRestrictions.remove(userId)) {
                applyUserRestrictionsForAllUsersLR();
            }
        }
        // Update the user list
        synchronized (mPackagesLock) {
            writeUserListLP();
        }
        // Remove user file(s)
        getUserFile(userId).delete();
        updateUserIds();
        if (RELEASE_DELETED_USER_ID) {
            synchronized (mUsersLock) {
                mRemovingUserIds.delete(userId);
            }
        }
    }

    /**
     * Send {@link Intent#ACTION_PROFILE_ADDED} broadcast when a user of type
     * {@link UserInfo#isProfile()} is added. This broadcast is sent only to dynamic receivers
     * created with {@link Context#registerReceiver}.
     */
    private void sendProfileAddedBroadcast(int parentUserId, int addedUserId) {
        sendProfileBroadcast(
                new Intent(Intent.ACTION_PROFILE_ADDED),
                parentUserId, addedUserId);
    }

    /**
     * Send {@link Intent#ACTION_PROFILE_REMOVED} broadcast when a user of type
     * {@link UserInfo#isProfile()} is removed. Additionally sends
     * {@link Intent#ACTION_MANAGED_PROFILE_REMOVED} broadcast if the profile is of type
     * {@link UserManager#USER_TYPE_PROFILE_MANAGED}
     *
     * <p> {@link Intent#ACTION_PROFILE_REMOVED} is a generalized broadcast for all users of type
     *     {@link UserInfo#isProfile()} and is sent only to dynamic receivers.
     *
     * <p> In contrast, the {@link Intent#ACTION_MANAGED_PROFILE_REMOVED} broadcast is specific to
     *     {@link UserManager#USER_TYPE_PROFILE_MANAGED} and is sent to both manifest and dynamic
     *     receivers thus it is still needed as manifest receivers will not be able to listen to
     *     the aforementioned generalized broadcast.
     */
    private void sendProfileRemovedBroadcast(int parentUserId, int removedUserId, String userType) {
        if (Objects.equals(userType, UserManager.USER_TYPE_PROFILE_MANAGED)) {
            sendManagedProfileRemovedBroadcast(parentUserId, removedUserId);
        }
        sendProfileBroadcast(
                new Intent(Intent.ACTION_PROFILE_REMOVED),
                parentUserId, removedUserId);
    }

    private void sendProfileBroadcast(Intent intent,
            int parentUserId, int userId) {
        final UserHandle parentHandle = UserHandle.of(parentUserId);
        intent.putExtra(Intent.EXTRA_USER, UserHandle.of(userId));
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(intent, parentHandle, /* receiverPermission= */null);
    }

    private void sendManagedProfileRemovedBroadcast(int parentUserId, int removedUserId) {
        Intent managedProfileIntent = new Intent(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        managedProfileIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(removedUserId));
        managedProfileIntent.putExtra(Intent.EXTRA_USER_HANDLE, removedUserId);
        final UserHandle parentHandle = UserHandle.of(parentUserId);
        getDevicePolicyManagerInternal().broadcastIntentToManifestReceivers(
                managedProfileIntent, parentHandle, /* requiresPermission= */ false);
        managedProfileIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendBroadcastAsUser(managedProfileIntent, parentHandle,
                /* receiverPermission= */null);
    }

    /**
     * <p>Starting from Android version {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * it is possible for there to be multiple managing agents on the device with the ability to set
     * restrictions, e.g. an Enterprise DPC and a Supervision admin. This API will only to return
     * the restrictions set by the DPCs. To retrieve restrictions set by all agents, use
     * {@link android.content.RestrictionsManager#getApplicationRestrictionsPerAdmin} instead.
     */
    @Override
    public Bundle getApplicationRestrictions(String packageName) {
        return getApplicationRestrictionsForUser(packageName, UserHandle.getCallingUserId());
    }

    /**
     * <p>Starting from Android version {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * it is possible for there to be multiple managing agents on the device with the ability to set
     * restrictions, e.g. an Enterprise DPC and a Supervision admin. This API will only to return
     * the restrictions set by the DPCs. To retrieve restrictions set by all agents, use
     * {@link android.content.RestrictionsManager#getApplicationRestrictionsPerAdmin} instead.
     */
    @Override
    public Bundle getApplicationRestrictionsForUser(String packageName, @UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId
                || !UserHandle.isSameApp(Binder.getCallingUid(), getUidForPackage(packageName))) {
            checkSystemOrRoot("get application restrictions for other user/app " + packageName);
        }
        synchronized (mAppRestrictionsLock) {
            // Read the restrictions from XML
            return readApplicationRestrictionsLAr(packageName, userId);
        }
    }

    @Override
    public void setApplicationRestrictions(String packageName, Bundle restrictions,
            @UserIdInt int userId) {
        checkSystemOrRoot("set application restrictions");
        String validationResult =
                FrameworkParsingPackageUtils.validateName(packageName, false, false);
        if (validationResult != null) {
            throw new IllegalArgumentException("Invalid package name: " + validationResult);
        }
        if (restrictions != null) {
            restrictions.setDefusable(true);
        }
        final boolean changed;
        synchronized (mAppRestrictionsLock) {
            if (restrictions == null || restrictions.isEmpty()) {
                changed = cleanAppRestrictionsForPackageLAr(packageName, userId);
            } else {
                // Write the restrictions to XML
                writeApplicationRestrictionsLAr(packageName, restrictions, userId);
                // TODO(b/154323615): avoid unnecessary broadcast when there is no change.
                changed = true;
            }
        }

        if (!changed) {
            return;
        }

        // Notify package of changes via an intent - only sent to explicitly registered receivers.
        final Intent changeIntent = new Intent(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED);
        changeIntent.setPackage(packageName);
        changeIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(changeIntent, UserHandle.of(userId));
    }

    private int getUidForPackage(String packageName) {
        final long ident = Binder.clearCallingIdentity();
        try {
            return mContext.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.MATCH_ANY_USER).uid;
        } catch (NameNotFoundException nnfe) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("mAppRestrictionsLock")
    private static Bundle readApplicationRestrictionsLAr(String packageName,
            @UserIdInt int userId) {
        AtomicFile restrictionsFile =
                new AtomicFile(new File(Environment.getUserSystemDirectory(userId),
                        packageToRestrictionsFileName(packageName)));
        return readApplicationRestrictionsLAr(restrictionsFile);
    }

    @VisibleForTesting
    @GuardedBy("mAppRestrictionsLock")
    static Bundle readApplicationRestrictionsLAr(AtomicFile restrictionsFile) {
        final Bundle restrictions = new Bundle();
        final ArrayList<String> values = new ArrayList<>();
        if (!restrictionsFile.getBaseFile().exists()) {
            return restrictions;
        }

        FileInputStream fis = null;
        try {
            fis = restrictionsFile.openRead();
            final TypedXmlPullParser parser = Xml.resolvePullParser(fis);
            XmlUtils.nextElement(parser);
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                Slog.e(LOG_TAG, "Unable to read restrictions file "
                        + restrictionsFile.getBaseFile());
                return restrictions;
            }
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                readEntry(restrictions, values, parser);
            }
        } catch (IOException|XmlPullParserException e) {
            Slog.w(LOG_TAG, "Error parsing " + restrictionsFile.getBaseFile(), e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
        return restrictions;
    }

    private static void readEntry(Bundle restrictions, ArrayList<String> values,
            TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_ENTRY)) {
            String key = parser.getAttributeValue(null, ATTR_KEY);
            String valType = parser.getAttributeValue(null, ATTR_VALUE_TYPE);
            int count = parser.getAttributeInt(null, ATTR_MULTIPLE, -1);
            if (count != -1) {
                values.clear();
                while (count > 0 && (type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (type == XmlPullParser.START_TAG
                            && parser.getName().equals(TAG_VALUE)) {
                        values.add(parser.nextText().trim());
                        count--;
                    }
                }
                String [] valueStrings = new String[values.size()];
                values.toArray(valueStrings);
                restrictions.putStringArray(key, valueStrings);
            } else if (ATTR_TYPE_BUNDLE.equals(valType)) {
                restrictions.putBundle(key, readBundleEntry(parser, values));
            } else if (ATTR_TYPE_BUNDLE_ARRAY.equals(valType)) {
                final int outerDepth = parser.getDepth();
                ArrayList<Bundle> bundleList = new ArrayList<>();
                while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                    Bundle childBundle = readBundleEntry(parser, values);
                    bundleList.add(childBundle);
                }
                restrictions.putParcelableArray(key,
                        bundleList.toArray(new Bundle[bundleList.size()]));
            } else {
                String value = parser.nextText().trim();
                if (ATTR_TYPE_BOOLEAN.equals(valType)) {
                    restrictions.putBoolean(key, Boolean.parseBoolean(value));
                } else if (ATTR_TYPE_INTEGER.equals(valType)) {
                    restrictions.putInt(key, Integer.parseInt(value));
                } else {
                    restrictions.putString(key, value);
                }
            }
        }
    }

    private static Bundle readBundleEntry(TypedXmlPullParser parser, ArrayList<String> values)
            throws IOException, XmlPullParserException {
        Bundle childBundle = new Bundle();
        final int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            readEntry(childBundle, values, parser);
        }
        return childBundle;
    }

    @GuardedBy("mAppRestrictionsLock")
    private static void writeApplicationRestrictionsLAr(String packageName,
            Bundle restrictions, @UserIdInt int userId) {
        AtomicFile restrictionsFile = new AtomicFile(
                new File(Environment.getUserSystemDirectory(userId),
                        packageToRestrictionsFileName(packageName)));
        writeApplicationRestrictionsLAr(restrictions, restrictionsFile);
    }

    @VisibleForTesting
    @GuardedBy("mAppRestrictionsLock")
    static void writeApplicationRestrictionsLAr(Bundle restrictions, AtomicFile restrictionsFile) {
        FileOutputStream fos = null;
        try {
            fos = restrictionsFile.startWrite();
            final TypedXmlSerializer serializer = Xml.resolveSerializer(fos);
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_RESTRICTIONS);
            writeBundle(restrictions, serializer);
            serializer.endTag(null, TAG_RESTRICTIONS);

            serializer.endDocument();
            restrictionsFile.finishWrite(fos);
        } catch (Exception e) {
            restrictionsFile.failWrite(fos);
            Slog.e(LOG_TAG, "Error writing application restrictions list", e);
        }
    }

    private static void writeBundle(Bundle restrictions, TypedXmlSerializer serializer)
            throws IOException {
        for (String key : restrictions.keySet()) {
            Object value = restrictions.get(key);
            serializer.startTag(null, TAG_ENTRY);
            serializer.attribute(null, ATTR_KEY, key);

            if (value instanceof Boolean) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BOOLEAN);
                serializer.text(value.toString());
            } else if (value instanceof Integer) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_INTEGER);
                serializer.text(value.toString());
            } else if (value == null || value instanceof String) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_STRING);
                serializer.text(value != null ? (String) value : "");
            } else if (value instanceof Bundle) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BUNDLE);
                writeBundle((Bundle) value, serializer);
            } else if (value instanceof Parcelable[]) {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BUNDLE_ARRAY);
                Parcelable[] array = (Parcelable[]) value;
                for (Parcelable parcelable : array) {
                    if (!(parcelable instanceof Bundle)) {
                        throw new IllegalArgumentException("bundle-array can only hold Bundles");
                    }
                    serializer.startTag(null, TAG_ENTRY);
                    serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_BUNDLE);
                    writeBundle((Bundle) parcelable, serializer);
                    serializer.endTag(null, TAG_ENTRY);
                }
            } else {
                serializer.attribute(null, ATTR_VALUE_TYPE, ATTR_TYPE_STRING_ARRAY);
                String[] values = (String[]) value;
                serializer.attributeInt(null, ATTR_MULTIPLE, values.length);
                for (String choice : values) {
                    serializer.startTag(null, TAG_VALUE);
                    serializer.text(choice != null ? choice : "");
                    serializer.endTag(null, TAG_VALUE);
                }
            }
            serializer.endTag(null, TAG_ENTRY);
        }
    }

    @Override
    public int getUserSerialNumber(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            final UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null ? userInfo.serialNumber : -1;
        }
    }

    @Override
    public boolean isUserNameSet(@UserIdInt int userId) {
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (!hasQueryOrCreateUsersPermission()
                && !(callingUserId == userId && hasPermissionGranted(
                android.Manifest.permission.GET_ACCOUNTS_PRIVILEGED, callingUid))) {
            throw new SecurityException("You need MANAGE_USERS, CREATE_USERS, QUERY_USERS, or "
                    + "GET_ACCOUNTS_PRIVILEGED permissions to: get whether user name is set");
        }
        synchronized (mUsersLock) {
            final UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.name != null;
        }
    }

    @Override
    public int getUserHandle(int userSerialNumber) {
        synchronized (mUsersLock) {
            for (int userId : mUserIds) {
                UserInfo info = getUserInfoLU(userId);
                if (info != null && info.serialNumber == userSerialNumber) return userId;
            }
            // Not found
            return -1;
        }
    }

    @Override
    public long getUserCreationTime(@UserIdInt int userId) {
        int callingUserId = UserHandle.getCallingUserId();
        UserInfo userInfo = null;
        synchronized (mUsersLock) {
            if (callingUserId == userId) {
                userInfo = getUserInfoLU(userId);
            } else {
                UserInfo parent = getProfileParentLU(userId);
                if (parent != null && parent.id == callingUserId) {
                    userInfo = getUserInfoLU(userId);
                }
            }
        }
        if (userInfo == null) {
            throw new SecurityException("userId can only be the calling user or a "
                    + "profile associated with this user");
        }
        return userInfo.creationTime;
    }

    /**
     * Caches the list of user ids in an array, adjusting the array size when necessary.
     */
    private void updateUserIds() {
        int num = 0;
        int numIncludingPreCreated = 0;
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                final UserInfo userInfo = mUsers.valueAt(i).info;
                if (!userInfo.partial) {
                    numIncludingPreCreated++;
                    if (!userInfo.preCreated) {
                        num++;
                    }
                }
            }
            if (DBG) {
                Slog.d(LOG_TAG, "updateUserIds(): numberUsers= " + num
                        + " includingPreCreated=" + numIncludingPreCreated);
            }
            final int[] newUsers = new int[num];
            final int[] newUsersIncludingPreCreated = new int[numIncludingPreCreated];

            int n = 0;
            int nIncludingPreCreated = 0;
            for (int i = 0; i < userSize; i++) {
                final UserInfo userInfo = mUsers.valueAt(i).info;
                if (!userInfo.partial) {
                    final int userId = mUsers.keyAt(i);
                    newUsersIncludingPreCreated[nIncludingPreCreated++] = userId;
                    if (!userInfo.preCreated) {
                        newUsers[n++] = userId;
                    }
                }
            }
            mUserIds = newUsers;
            mUserIdsIncludingPreCreated = newUsersIncludingPreCreated;
            if (DBG) {
                Slog.d(LOG_TAG, "updateUserIds(): userIds= " + Arrays.toString(mUserIds)
                        + " includingPreCreated=" + Arrays.toString(mUserIdsIncludingPreCreated));
            }
            UserPackage.setValidUserIds(mUserIds);
        }
    }

    /**
     * Called right before a user is started. This gives us a chance to prepare
     * app storage and apply any user restrictions.
     */
    public void onBeforeStartUser(@UserIdInt int userId) {
        UserInfo userInfo = getUserInfo(userId);
        if (userInfo == null) {
            return;
        }
        TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("onBeforeStartUser-" + userId);
        // Migrate only if build fingerprints mismatch
        boolean migrateAppsData = !PackagePartitions.FINGERPRINT.equals(
                userInfo.lastLoggedInFingerprint);
        t.traceBegin("prepareUserData");
        mUserDataPreparer.prepareUserData(userInfo, StorageManager.FLAG_STORAGE_DE);
        t.traceEnd();
        t.traceBegin("reconcileAppsData");
        getPackageManagerInternal().reconcileAppsData(userId, StorageManager.FLAG_STORAGE_DE,
                migrateAppsData);
        t.traceEnd();

        if (userId != UserHandle.USER_SYSTEM) {
            t.traceBegin("applyUserRestrictions");
            synchronized (mRestrictionsLock) {
                applyUserRestrictionsLR(userId);
            }
            t.traceEnd();
        }
        t.traceEnd(); // onBeforeStartUser
    }

    /**
     * Called right before a user is unlocked. This gives us a chance to prepare
     * app storage.
     */
    public void onBeforeUnlockUser(@UserIdInt int userId) {
        UserInfo userInfo = getUserInfo(userId);
        if (userInfo == null) {
            return;
        }
        // Migrate only if build fingerprints mismatch
        boolean migrateAppsData = !PackagePartitions.FINGERPRINT.equals(
                userInfo.lastLoggedInFingerprint);

        final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("prepareUserData-" + userId);
        mUserDataPreparer.prepareUserData(userInfo, StorageManager.FLAG_STORAGE_CE);
        t.traceEnd();

        StorageManagerInternal smInternal = LocalServices.getService(StorageManagerInternal.class);
        smInternal.markCeStoragePrepared(userId);

        t.traceBegin("reconcileAppsData-" + userId);
        getPackageManagerInternal().reconcileAppsData(userId, StorageManager.FLAG_STORAGE_CE,
                migrateAppsData);
        t.traceEnd();
    }

    /**
     * Examine all users present on given mounted volume, and destroy data
     * belonging to users that are no longer valid, or whose user ID has been
     * recycled.
     */
    void reconcileUsers(String volumeUuid) {
        mUserDataPreparer.reconcileUsers(volumeUuid, getUsers(
                /* excludePartial= */ true,
                /* excludeDying= */ true,
                /* excludePreCreated= */ false));
    }

    /**
     * Make a note of the last started time of a user and do some cleanup.
     * This is called with ActivityManagerService lock held.
     * @param userId the user that was just foregrounded
     */
    public void onUserLoggedIn(@UserIdInt int userId) {
        UserData userData = getUserDataNoChecks(userId);
        if (userData == null || userData.info.partial) {
            Slog.w(LOG_TAG, "userForeground: unknown user #" + userId);
            return;
        }

        final long now = System.currentTimeMillis();
        if (now > EPOCH_PLUS_30_YEARS) {
            userData.info.lastLoggedInTime = now;
        }
        userData.info.lastLoggedInFingerprint = PackagePartitions.FINGERPRINT;
        scheduleWriteUser(userId);
    }

    /**
     * Returns the next available user id, filling in any holes in the ids.
     */
    @VisibleForTesting
    int getNextAvailableId() {
        int nextId;
        synchronized (mUsersLock) {
            nextId = scanNextAvailableIdLocked();
            if (nextId >= 0) {
                return nextId;
            }
            // All ids up to MAX_USER_ID were used. Remove all mRemovingUserIds,
            // except most recently removed
            if (mRemovingUserIds.size() > 0) {
                Slog.i(LOG_TAG, "All available IDs are used. Recycling LRU ids.");
                mRemovingUserIds.clear();
                for (Integer recentlyRemovedId : mRecentlyRemovedIds) {
                    mRemovingUserIds.put(recentlyRemovedId, true);
                }
                nextId = scanNextAvailableIdLocked();
            }
        }
        // If we got here, we probably recycled user ids, so invalidate any caches.
        UserManager.invalidateStaticUserProperties();
        UserManager.invalidateUserPropertiesCache();
        if (nextId < 0) {
            throw new IllegalStateException("No user id available!");
        }
        return nextId;
    }

    @GuardedBy("mUsersLock")
    private int scanNextAvailableIdLocked() {
        for (int i = MIN_USER_ID; i < MAX_USER_ID; i++) {
            if (mUsers.indexOfKey(i) < 0 && !mRemovingUserIds.get(i)) {
                return i;
            }
        }
        return -1;
    }

    private static String packageToRestrictionsFileName(String packageName) {
        return RESTRICTIONS_FILE_PREFIX + packageName + XML_SUFFIX;
    }

    @Nullable
    private static String getRedacted(@Nullable String string) {
        return string == null ? null : string.length() + "_chars";
    }

    @Override
    public void setSeedAccountData(@UserIdInt int userId, String accountName, String accountType,
            PersistableBundle accountOptions, boolean persist) {
        checkManageUsersPermission("set user seed account data");
        setSeedAccountDataNoChecks(userId, accountName, accountType, accountOptions, persist);
    }

    private void setSeedAccountDataNoChecks(@UserIdInt int userId, @Nullable String accountName,
            @Nullable String accountType, @Nullable PersistableBundle accountOptions,
            boolean persist) {

        synchronized (mPackagesLock) {
            final UserData userData;
            synchronized (mUsersLock) {
                userData = getUserDataLU(userId);
                if (userData == null) {
                    Slog.e(LOG_TAG, "No such user for settings seed data u=" + userId);
                    return;
                }
                userData.seedAccountName = truncateString(accountName,
                        UserManager.MAX_ACCOUNT_STRING_LENGTH);
                userData.seedAccountType = truncateString(accountType,
                        UserManager.MAX_ACCOUNT_STRING_LENGTH);
                if (accountOptions != null && accountOptions.isBundleContentsWithinLengthLimit(
                        UserManager.MAX_ACCOUNT_OPTIONS_LENGTH)) {
                    userData.seedAccountOptions = accountOptions;
                }
                userData.persistSeedData = persist;
            }
            if (persist) {
                writeUserLP(userData);
            }
        }
    }

    @Override
    public String getSeedAccountName(@UserIdInt int userId) throws RemoteException {
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (mUsersLock) {
            final UserData userData = getUserDataLU(userId);
            return userData == null ? null : userData.seedAccountName;
        }
    }

    @Override
    public String getSeedAccountType(@UserIdInt int userId) throws RemoteException {
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (mUsersLock) {
            final UserData userData = getUserDataLU(userId);
            return userData == null ? null : userData.seedAccountType;
        }
    }

    @Override
    public PersistableBundle getSeedAccountOptions(@UserIdInt int userId) throws RemoteException {
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (mUsersLock) {
            final UserData userData = getUserDataLU(userId);
            return userData == null ? null : userData.seedAccountOptions;
        }
    }

    @Override
    public void clearSeedAccountData(@UserIdInt int userId) throws RemoteException {
        checkManageUsersPermission("Cannot clear seed account information");
        synchronized (mPackagesLock) {
            UserData userData;
            synchronized (mUsersLock) {
                userData = getUserDataLU(userId);
                if (userData == null) return;
                userData.clearSeedAccountData();
            }
            writeUserLP(userData);
        }
    }

    @Override
    public boolean someUserHasSeedAccount(String accountName, String accountType) {
        checkManageUsersPermission("check seed account information");
        return someUserHasSeedAccountNoChecks(accountName, accountType);
    }

    private boolean someUserHasSeedAccountNoChecks(String accountName, String accountType) {
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                final UserData data = mUsers.valueAt(i);
                if (data.info.isInitialized()) continue;
                if (mRemovingUserIds.get(data.info.id)) continue;
                if (data.seedAccountName == null || !data.seedAccountName.equals(accountName)) {
                    continue;
                }
                if (data.seedAccountType == null || !data.seedAccountType.equals(accountType)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean someUserHasAccount(String accountName, String accountType) {
        checkCreateUsersPermission("check seed account information");
        return someUserHasAccountNoChecks(accountName, accountType);
    }

    private boolean someUserHasAccountNoChecks(
            String accountName, String accountType) {
        if (TextUtils.isEmpty(accountName) || TextUtils.isEmpty(accountType)) {
            return false;
        }

        final Account account = new Account(accountName, accountType);

        return Binder.withCleanCallingIdentity(() ->
                AccountManager.get(mContext).someUserHasAccount(account)
                        || someUserHasSeedAccountNoChecks(accountName, accountType));
    }

    private void setLastEnteredForegroundTimeToNow(@NonNull UserData userData) {
        userData.mLastEnteredForegroundTimeMillis = System.currentTimeMillis();
        scheduleWriteUser(userData.info.id);
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new UserManagerServiceShellCommand(this, mSystemPackageInstaller,
                mLockPatternUtils, mContext))
                .exec(this, in, out, err, args, callback, resultReceiver);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;

        final long now = System.currentTimeMillis();
        final long nowRealtime = SystemClock.elapsedRealtime();
        final StringBuilder sb = new StringBuilder();

        if (args != null && args.length > 0) {
            switch (args[0]) {
                case "--user":
                    dumpUser(pw, UserHandle.parseUserArg(args[1]), sb, now, nowRealtime);
                    return;
                case "--visibility-mediator":
                    mUserVisibilityMediator.dump(pw, args);
                    return;
            }
        }

        final int currentUserId = getCurrentUserId();
        pw.print("Current user: ");
        if (currentUserId != UserHandle.USER_NULL) {
            pw.println(currentUserId);
        } else {
            pw.println("N/A");
        }

        pw.println();
        synchronized (mPackagesLock) {
            synchronized (mUsersLock) {
                pw.println("Users:");
                for (int i = 0; i < mUsers.size(); i++) {
                    UserData userData = mUsers.valueAt(i);
                    if (userData == null) {
                        continue;
                    }
                    dumpUserLocked(pw, userData, sb, now, nowRealtime);
                }
            }

            pw.println();
            pw.println("Device properties:");
            pw.println("  Device policy global restrictions:");
            synchronized (mRestrictionsLock) {
                UserRestrictionsUtils.dumpRestrictions(
                        pw, "    ",
                        mDevicePolicyUserRestrictions.getRestrictions(UserHandle.USER_ALL));
            }
            pw.println("  Guest restrictions:");
            synchronized (mGuestRestrictions) {
                UserRestrictionsUtils.dumpRestrictions(pw, "    ", mGuestRestrictions);
            }
            synchronized (mUsersLock) {
                pw.println();
                pw.println("  Device managed: " + mIsDeviceManaged);
                if (mRemovingUserIds.size() > 0) {
                    pw.println();
                    pw.println("  Recently removed userIds: " + mRecentlyRemovedIds);
                }
            }
            synchronized (mUserStates) {
                pw.print("  Started users state: [");
                final int size = mUserStates.states.size();
                for (int i = 0; i < size; i++) {
                    final int userId = mUserStates.states.keyAt(i);
                    final int state = mUserStates.states.valueAt(i);
                    pw.print(userId);
                    pw.print('=');
                    pw.print(UserState.stateToString(state));
                    if (i != size - 1) pw.print(", ");
                }
                pw.println(']');
            }
            synchronized (mUsersLock) {
                pw.print("  Cached user IDs: ");
                pw.println(Arrays.toString(mUserIds));
                pw.print("  Cached user IDs (including pre-created): ");
                pw.println(Arrays.toString(mUserIdsIncludingPreCreated));
            }
        } // synchronized (mPackagesLock)

        pw.println();
        mUserVisibilityMediator.dump(pw, args);
        pw.println();

        // Dump some capabilities
        pw.println();
        pw.print("  Max users: " + UserManager.getMaxSupportedUsers());
        pw.println(" (limit reached: " + isUserLimitReached() + ")");
        pw.println("  Supports switchable users: " + UserManager.supportsMultipleUsers());
        pw.println("  All guests ephemeral: " + Resources.getSystem().getBoolean(
                com.android.internal.R.bool.config_guestUserEphemeral));
        pw.println("  Force ephemeral users: " + mForceEphemeralUsers);
        final boolean isHeadlessSystemUserMode = isHeadlessSystemUserMode();
        pw.println("  Is headless-system mode: " + isHeadlessSystemUserMode);
        if (isHeadlessSystemUserMode != RoSystemProperties.MULTIUSER_HEADLESS_SYSTEM_USER) {
            pw.println("  (differs from the current default build value)");
        }
        if (!TextUtils.isEmpty(SystemProperties.get(SYSTEM_USER_MODE_EMULATION_PROPERTY))) {
            pw.println("  (emulated by 'cmd user set-system-user-mode-emulation')");
            if (mUpdatingSystemUserMode) {
                pw.println("  (and being updated after boot)");
            }
        }
        pw.println("  User version: " + mUserVersion);
        pw.println("  Owner name: " + getOwnerName());
        if (DBG_ALLOCATION) {
            pw.println("  System user allocations: " + mUser0Allocations.get());
        }
        synchronized (mUsersLock) {
            pw.println("  Boot user: " + mBootUser);
        }
        pw.println("Can add private profile: "+ canAddPrivateProfile(currentUserId));

        pw.println();
        pw.println("Number of listeners for");
        synchronized (mUserRestrictionsListeners) {
            pw.println("  restrictions: " + mUserRestrictionsListeners.size());
        }
        synchronized (mUserLifecycleListeners) {
            pw.println("  user lifecycle events: " + mUserLifecycleListeners.size());
        }

        // Dump UserTypes
        pw.println();
        pw.println("User types version: " + mUserTypeVersion);
        pw.println("User types (" + mUserTypes.size() + " types):");
        for (int i = 0; i < mUserTypes.size(); i++) {
            pw.println("    " + mUserTypes.keyAt(i) + ": ");
            mUserTypes.valueAt(i).dump(pw, "        ");
        }

        // TODO: create IndentingPrintWriter at the beginning of dump() and use the proper
        // indentation methods instead of explicit printing "  "
        try (IndentingPrintWriter ipw = new IndentingPrintWriter(pw)) {

            // Dump SystemPackageInstaller info
            ipw.println();
            mSystemPackageInstaller.dump(ipw);

            // NOTE: pw's not available after this point as it's auto-closed by ipw, so new dump
            // statements should use ipw below
        }
    }

    private void dumpUser(PrintWriter pw, @UserIdInt int userId, StringBuilder sb, long now,
            long nowRealtime) {
        if (userId == UserHandle.USER_CURRENT) {
            final int currentUserId = getCurrentUserId();
            pw.print("Current user: ");
            if (currentUserId == UserHandle.USER_NULL) {
                pw.println("Cannot determine current user");
                return;
            }
            userId = currentUserId;
        }

        synchronized (mUsersLock) {
            final UserData userData = mUsers.get(userId);
            if (userData == null) {
                pw.println("User " + userId + " not found");
                return;
            }
            dumpUserLocked(pw, userData, sb, now, nowRealtime);
        }
    }

    @GuardedBy("mUsersLock")
    private void dumpUserLocked(PrintWriter pw, UserData userData, StringBuilder tempStringBuilder,
            long now, long nowRealtime) {
        final UserInfo userInfo = userData.info;
        final int userId = userInfo.id;
        pw.print("  "); pw.print(userInfo);
        pw.print(" serialNo="); pw.print(userInfo.serialNumber);
        pw.print(" isPrimary="); pw.print(userInfo.isPrimary());
        if (userInfo.profileGroupId != userInfo.id
                &&  userInfo.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID) {
            pw.print(" parentId="); pw.print(userInfo.profileGroupId);
        }

        if (mRemovingUserIds.get(userId)) {
            pw.print(" <removing> ");
        }
        if (userInfo.partial) {
            pw.print(" <partial>");
        }
        if (userInfo.preCreated) {
            pw.print(" <pre-created>");
        }
        if (userInfo.convertedFromPreCreated) {
            pw.print(" <converted>");
        }
        pw.println();
        pw.print("    Type: "); pw.println(userInfo.userType);
        pw.print("    Flags: "); pw.print(userInfo.flags); pw.print(" (");
        pw.print(UserInfo.flagsToString(userInfo.flags)); pw.println(")");
        pw.print("    State: ");
        final int state;
        synchronized (mUserStates) {
            state = mUserStates.get(userId, -1);
        }
        pw.println(UserState.stateToString(state));
        pw.print("    Created: ");
        dumpTimeAgo(pw, tempStringBuilder, now, userInfo.creationTime);

        pw.print("    Last logged in: ");
        dumpTimeAgo(pw, tempStringBuilder, now, userInfo.lastLoggedInTime);

        pw.print("    Last logged in fingerprint: ");
        pw.println(userInfo.lastLoggedInFingerprint);

        pw.print("    Start time: ");
        dumpTimeAgo(pw, tempStringBuilder, nowRealtime, userData.startRealtime);

        pw.print("    Unlock time: ");
        dumpTimeAgo(pw, tempStringBuilder, nowRealtime, userData.unlockRealtime);

        pw.print("    Last entered foreground: ");
        dumpTimeAgo(pw, tempStringBuilder, now, userData.mLastEnteredForegroundTimeMillis);

        pw.print("    Has profile owner: ");
        pw.println(mIsUserManaged.get(userId));
        pw.println("    Restrictions:");
        synchronized (mRestrictionsLock) {
            UserRestrictionsUtils.dumpRestrictions(
                    pw, "      ", mBaseUserRestrictions.getRestrictions(userInfo.id));
            pw.println("    Device policy restrictions:");
            UserRestrictionsUtils.dumpRestrictions(
                    pw, "      ",
                    mDevicePolicyUserRestrictions.getRestrictions(userInfo.id));
            pw.println("    Effective restrictions:");
            UserRestrictionsUtils.dumpRestrictions(
                    pw, "      ",
                    mCachedEffectiveUserRestrictions.getRestrictions(userInfo.id));
        }

        if (userData.account != null) {
            pw.print("    Account name: " + userData.account);
            pw.println();
        }

        if (userData.seedAccountName != null) {
            pw.print("    Seed account name: " + userData.seedAccountName);
            pw.println();
            if (userData.seedAccountType != null) {
                pw.print("         account type: " + userData.seedAccountType);
                pw.println();
            }
            if (userData.seedAccountOptions != null) {
                pw.print("         account options exist");
                pw.println();
            }
        }

        if (userData.userProperties != null) {
            userData.userProperties.println(pw, "    ");
        }

        pw.println("    Ignore errors preparing storage: "
                + userData.getIgnorePrepareStorageErrors());
    }

    private static void dumpTimeAgo(PrintWriter pw, StringBuilder sb, long nowTime, long time) {
        if (time == 0) {
            pw.println("<unknown>");
        } else {
            sb.setLength(0);
            TimeUtils.formatDuration(nowTime - time, sb);
            sb.append(" ago");
            pw.println(sb);
        }
    }

    final class MainHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case WRITE_USER_LIST_MSG: {
                    removeMessages(WRITE_USER_LIST_MSG);
                    synchronized (mPackagesLock) {
                        writeUserListLP();
                    }
                    break;
                }
                case WRITE_USER_MSG:
                    removeMessages(WRITE_USER_MSG, msg.obj);
                    synchronized (mPackagesLock) {
                        int userId = (int) msg.obj;
                        UserData userData = getUserDataNoChecks(userId);
                        if (userData != null) {
                            writeUserLP(userData);
                        } else {
                            Slog.i(LOG_TAG, "handle(WRITE_USER_MSG): no data for user " + userId
                                    + ", it was probably removed before handler could handle it");
                        }
                    }
                    break;
            }
        }
    }

    /**
     * @param userId
     * @return whether the user has been initialized yet
     */
    boolean isUserInitialized(@UserIdInt int userId) {
        return mLocalService.isUserInitialized(userId);
    }

    private class LocalService extends UserManagerInternal {
        @Override
        public void setDevicePolicyUserRestrictions(@UserIdInt int originatingUserId,
                @NonNull Bundle global, @NonNull RestrictionsSet local,
                boolean isDeviceOwner) {
            UserManagerService.this.setDevicePolicyUserRestrictionsInner(originatingUserId,
                    global, local, isDeviceOwner);
        }

        @Override
        public void setUserRestriction(int userId, @NonNull String key, boolean value) {
            UserManagerService.this.setUserRestrictionInner(userId, key, value);
        }

        @Override
        public boolean getUserRestriction(@UserIdInt int userId, String key) {
            return getUserRestrictions(userId).getBoolean(key);
        }

        @Override
        public void addUserRestrictionsListener(UserRestrictionsListener listener) {
            synchronized (mUserRestrictionsListeners) {
                mUserRestrictionsListeners.add(listener);
            }
        }

        @Override
        public void removeUserRestrictionsListener(UserRestrictionsListener listener) {
            synchronized (mUserRestrictionsListeners) {
                mUserRestrictionsListeners.remove(listener);
            }
        }

        @Override
        public void addUserLifecycleListener(UserLifecycleListener listener) {
            synchronized (mUserLifecycleListeners) {
                mUserLifecycleListeners.add(listener);
            }
        }

        @Override
        public void removeUserLifecycleListener(UserLifecycleListener listener) {
            synchronized (mUserLifecycleListeners) {
                mUserLifecycleListeners.remove(listener);
            }
        }

        // TODO(b/258213147): Remove
        @Override
        public void setDeviceManaged(boolean isManaged) {
            synchronized (mUsersLock) {
                mIsDeviceManaged = isManaged;
            }
        }

        // TODO(b/258213147): Remove
        @Override
        public boolean isDeviceManaged() {
            synchronized (mUsersLock) {
                return mIsDeviceManaged;
            }
        }

        // TODO(b/258213147): Remove
        @Override
        public void setUserManaged(@UserIdInt int userId, boolean isManaged) {
            synchronized (mUsersLock) {
                mIsUserManaged.put(userId, isManaged);
            }
        }

        // TODO(b/258213147): Remove
        @Override
        public boolean isUserManaged(@UserIdInt int userId) {
            synchronized (mUsersLock) {
                return mIsUserManaged.get(userId);
            }
        }

        @Override
        public void setUserIcon(@UserIdInt int userId, Bitmap bitmap) {
            final long ident = Binder.clearCallingIdentity();
            try {
                synchronized (mPackagesLock) {
                    UserData userData = getUserDataNoChecks(userId);
                    if (userData == null || userData.info.partial) {
                        Slog.w(LOG_TAG, "setUserIcon: unknown user #" + userId);
                        return;
                    }
                    writeBitmapLP(userData.info, bitmap);
                    writeUserLP(userData);
                }
                sendUserInfoChangedBroadcast(userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override
        public void setForceEphemeralUsers(boolean forceEphemeralUsers) {
            synchronized (mUsersLock) {
                mForceEphemeralUsers = forceEphemeralUsers;
            }
        }

        @Override
        public void removeAllUsers() {
            if (UserHandle.USER_SYSTEM == getCurrentUserId()) {
                // Remove the non-system users straight away.
                removeAllUsersExceptSystemAndPermanentAdminMain();
            } else {
                // Switch to the system user first and then remove the other users.
                BroadcastReceiver userSwitchedReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        int userId =
                                intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                        if (userId != UserHandle.USER_SYSTEM) {
                            return;
                        }
                        mContext.unregisterReceiver(this);
                        removeAllUsersExceptSystemAndPermanentAdminMain();
                    }
                };
                IntentFilter userSwitchedFilter = new IntentFilter();
                userSwitchedFilter.addAction(Intent.ACTION_USER_SWITCHED);
                mContext.registerReceiver(
                        userSwitchedReceiver, userSwitchedFilter, null, mHandler);

                // Switch to the system user.
                ActivityManager am =
                        (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                am.switchUser(UserHandle.USER_SYSTEM);
            }
        }

        @Override
        public void onEphemeralUserStop(@UserIdInt int userId) {
            synchronized (mUsersLock) {
               UserInfo userInfo = getUserInfoLU(userId);
               if (userInfo != null && userInfo.isEphemeral()) {
                    // Do not allow switching back to the ephemeral user again as the user is going
                    // to be deleted.
                    userInfo.flags |= UserInfo.FLAG_DISABLED;
                    if (userInfo.isGuest()) {
                        // Indicate that the guest will be deleted after it stops.
                        userInfo.guestToRemove = true;
                    }
               }
            }
        }

        @Override
        public @NonNull UserInfo createUserEvenWhenDisallowed(
                @Nullable String name, @NonNull String userType, @UserInfoFlag int flags,
                @Nullable String[] disallowedPackages, @Nullable Object token)
                throws UserManager.CheckedUserOperationException {

            return createUserInternalUnchecked(name, userType, flags,
                    UserHandle.USER_NULL, /* preCreated= */ false, disallowedPackages, token);
        }

        @Override
        public boolean removeUserEvenWhenDisallowed(@UserIdInt int userId) {
            return removeUserWithProfilesUnchecked(userId);
        }

        @Override
        public boolean isUserRunning(@UserIdInt int userId) {
            int state;
            synchronized (mUserStates) {
                state =  mUserStates.get(userId, UserState.STATE_NONE);
            }

            return state != UserState.STATE_NONE
                    && state != UserState.STATE_STOPPING
                    && state != UserState.STATE_SHUTDOWN;
        }

        @Override
        public void setUserState(@UserIdInt int userId, int userState) {
            synchronized (mUserStates) {
                mUserStates.put(userId, userState);
            }
        }

        @Override
        public void removeUserState(@UserIdInt int userId) {
            synchronized (mUserStates) {
                mUserStates.delete(userId);
            }
        }

        @Override
        public int[] getUserIds() {
            return UserManagerService.this.getUserIds();
        }

        @Override
        public @NonNull List<UserInfo> getUsers(boolean excludeDying) {
            return getUsers(/*excludePartial= */ true, excludeDying, /* excludePreCreated= */ true);
        }

        @Override
        public @NonNull List<UserInfo> getUsers(boolean excludePartial, boolean excludeDying,
                boolean excludePreCreated) {
            return UserManagerService.this.getUsersInternal(excludePartial, excludeDying,
                    excludePreCreated);
        }

        @Override
        public @NonNull int[] getProfileIds(@UserIdInt int userId, boolean enabledOnly) {
            synchronized (mUsersLock) {
                return getProfileIdsLU(userId, null /* userType */, enabledOnly, /* excludeHidden */
                        false).toArray();
            }
        }

        @Override
        public @Nullable LauncherUserInfo getLauncherUserInfo(@UserIdInt int userId) {
            UserInfo userInfo;
            synchronized (mUsersLock) {
                userInfo = getUserInfoLU(userId);
            }
            if (userInfo != null) {
                final UserTypeDetails userDetails = getUserTypeDetails(userInfo);
                final LauncherUserInfo uiInfo = new LauncherUserInfo.Builder(
                        userDetails.getName(),
                        userInfo.serialNumber)
                        .build();
                return uiInfo;
            } else {
                return null;
            }
        }

        @Override
        public boolean isUserUnlockingOrUnlocked(@UserIdInt int userId) {
            int state;
            synchronized (mUserStates) {
                state = mUserStates.get(userId, -1);
            }
            // Special case: in the stopping/shutdown state, CE storage can still be unlocked.
            if (state == UserState.STATE_STOPPING || state == UserState.STATE_SHUTDOWN) {
                return StorageManager.isCeStorageUnlocked(userId);
            }
            return (state == UserState.STATE_RUNNING_UNLOCKING)
                    || (state == UserState.STATE_RUNNING_UNLOCKED);
        }

        /**
         * The return values of this method are cached in clients.  If the
         * logic in this function changes then the cache invalidation code
         * may need to be revisited.
         */
        @Override
        public boolean isUserUnlocked(@UserIdInt int userId) {
            int state;
            synchronized (mUserStates) {
                state = mUserStates.get(userId, -1);
            }
            // Special case: in the stopping/shutdown state, CE storage can still be unlocked.
            if (state == UserState.STATE_STOPPING || state == UserState.STATE_SHUTDOWN) {
                return StorageManager.isCeStorageUnlocked(userId);
            }
            return state == UserState.STATE_RUNNING_UNLOCKED;
        }

        @Override
        public boolean isUserInitialized(@UserIdInt int userId) {
            final UserInfo userInfo = getUserInfo(userId);
            return userInfo != null && (userInfo.flags & UserInfo.FLAG_INITIALIZED) != 0;
        }

        @Override
        public boolean exists(@UserIdInt int userId) {
            return getUserInfoNoChecks(userId) != null;
        }

        @Override
        public boolean isProfileAccessible(int callingUserId, int targetUserId, String debugMsg,
                boolean throwSecurityException) {
            if (targetUserId == callingUserId) {
                return true;
            }
            synchronized (mUsersLock) {
                UserInfo callingUserInfo = getUserInfoLU(callingUserId);
                if (callingUserInfo == null || callingUserInfo.isProfile()) {
                    if (throwSecurityException) {
                        throw new SecurityException(
                                debugMsg + " for another profile "
                                        + targetUserId + " from " + callingUserId);
                    }
                    Slog.w(LOG_TAG, debugMsg + " for another profile "
                            + targetUserId + " from " + callingUserId);
                    return false;
                }

                UserInfo targetUserInfo = getUserInfoLU(targetUserId);
                if (targetUserInfo == null || !targetUserInfo.isEnabled()) {
                    // Do not throw any exception here as this could happen due to race conditions
                    // between the system updating its state and the client getting notified.
                    if (throwSecurityException) {
                        Slog.w(LOG_TAG, debugMsg + " for disabled profile "
                                + targetUserId + " from " + callingUserId);
                    }
                    return false;
                }

                // TODO(b/276473320): Probably use isSameUserOrProfileGroupOrTargetIsCommunal.
                if (targetUserInfo.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID ||
                        targetUserInfo.profileGroupId != callingUserInfo.profileGroupId) {
                    if (throwSecurityException) {
                        throw new SecurityException(
                                debugMsg + " for unrelated profile " + targetUserId);
                    }
                    return false;
                }
            }
            return true;
        }

        @Override
        public int getProfileParentId(@UserIdInt int userId) {
            return getProfileParentIdUnchecked(userId);
        }

        @Override
        public boolean isSettingRestrictedForUser(String setting, @UserIdInt int userId,
                String value, int callingUid) {
            return UserManagerService.this.isSettingRestrictedForUser(setting, userId,
                    value, callingUid);
        }

        @Override
        public boolean hasUserRestriction(String restrictionKey, @UserIdInt int userId) {
            if (!UserRestrictionsUtils.isValidRestriction(restrictionKey)) {
                return false;
            }
            Bundle restrictions = getEffectiveUserRestrictions(userId);
            return restrictions != null && restrictions.getBoolean(restrictionKey);
        }

        @Override
        public @Nullable UserInfo getUserInfo(@UserIdInt int userId) {
            UserData userData;
            synchronized (mUsersLock) {
                userData = mUsers.get(userId);
            }
            return userData == null ? null : userData.info;
        }

        @Override
        public @NonNull UserInfo[] getUserInfos() {
            synchronized (mUsersLock) {
                int userSize = mUsers.size();
                UserInfo[] allInfos = new UserInfo[userSize];
                for (int i = 0; i < userSize; i++) {
                    allInfos[i] = mUsers.valueAt(i).info;
                }
                return allInfos;
            }
        }

        @Override
        public void setDefaultCrossProfileIntentFilters(
                @UserIdInt int parentUserId, @UserIdInt int profileUserId) {
            final UserTypeDetails userTypeDetails = getUserTypeDetailsNoChecks(profileUserId);
            final Bundle restrictions = getEffectiveUserRestrictions(profileUserId);
            UserManagerService.this.setDefaultCrossProfileIntentFilters(
                    profileUserId, userTypeDetails, restrictions, parentUserId);
        }

        @Override
        public boolean shouldIgnorePrepareStorageErrors(int userId) {
            synchronized (mUsersLock) {
                UserData userData = mUsers.get(userId);
                return userData != null && userData.getIgnorePrepareStorageErrors();
            }
        }

        @Override
        public @Nullable UserProperties getUserProperties(@UserIdInt int userId) {
            final UserProperties props = getUserPropertiesInternal(userId);
            if (props == null) {
                Slog.w(LOG_TAG, "A null UserProperties was returned for user " + userId);
            }
            return props;
        }

        @Override
        @UserAssignmentResult
        public int assignUserToDisplayOnStart(@UserIdInt int userId,
                @UserIdInt int profileGroupId, @UserStartMode int userStartMode, int displayId) {

            final UserProperties properties = getUserProperties(userId);
            final boolean isAlwaysVisible =  properties != null && properties.getAlwaysVisible();

            return mUserVisibilityMediator.assignUserToDisplayOnStart(userId, profileGroupId,
                    userStartMode, displayId, isAlwaysVisible);
        }

        @Override
        public boolean assignUserToExtraDisplay(int userId, int displayId) {
            return mUserVisibilityMediator.assignUserToExtraDisplay(userId, displayId);
        }

        @Override
        public boolean unassignUserFromExtraDisplay(int userId, int displayId) {
            return mUserVisibilityMediator.unassignUserFromExtraDisplay(userId, displayId);
        }

        @Override
        public void unassignUserFromDisplayOnStop(@UserIdInt int userId) {
            mUserVisibilityMediator.unassignUserFromDisplayOnStop(userId);
        }

        @Override
        public boolean isUserVisible(@UserIdInt int userId) {
            return mUserVisibilityMediator.isUserVisible(userId);
        }

        @Override
        public boolean isUserVisible(@UserIdInt int userId, int displayId) {
            return mUserVisibilityMediator.isUserVisible(userId, displayId);
        }

        @Override
        public int getMainDisplayAssignedToUser(@UserIdInt int userId) {
            return mUserVisibilityMediator.getMainDisplayAssignedToUser(userId);
        }

        @Override
        public @Nullable int[] getDisplaysAssignedToUser(@UserIdInt int userId) {
            return mUserVisibilityMediator.getDisplaysAssignedToUser(userId);
        }

        @Override
        public @UserIdInt int getUserAssignedToDisplay(int displayId) {
            return mUserVisibilityMediator.getUserAssignedToDisplay(displayId);
        }

        @Override
        public void addUserVisibilityListener(UserVisibilityListener listener) {
            mUserVisibilityMediator.addListener(listener);
        }

        @Override
        public void removeUserVisibilityListener(UserVisibilityListener listener) {
            mUserVisibilityMediator.removeListener(listener);
        }

        @Override
        public void onSystemUserVisibilityChanged(boolean visible) {
            mUserVisibilityMediator.onSystemUserVisibilityChanged(visible);
        }

        @Override
        public int[] getUserTypesForStatsd(@UserIdInt int[] userIds) {
            if (userIds == null) {
                return null;
            }
            final int[] userTypes = new int[userIds.length];
            for (int i = 0; i < userTypes.length; i++) {
                final UserInfo userInfo = getUserInfo(userIds[i]);
                if (userInfo == null) {
                    // Not possible because the input user ids should all be valid
                    userTypes[i] = mUserJourneyLogger.getUserTypeForStatsd("");
                } else {
                    userTypes[i] = mUserJourneyLogger.getUserTypeForStatsd(userInfo.userType);
                }
            }
            return userTypes;
        }

        @Override
        public @UserIdInt int getMainUserId() {
            return getMainUserIdUnchecked();
        }

        @Override
        public @UserIdInt int getBootUser(boolean waitUntilSet)
                throws UserManager.CheckedUserOperationException {
            if (waitUntilSet) {
                final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
                t.traceBegin("wait-boot-user");
                try {
                    if (mBootUserLatch.getCount() != 0) {
                        Slogf.d(LOG_TAG,
                                "Sleeping for boot user to be set. "
                                + "Max sleep for Time: %d", BOOT_USER_SET_TIMEOUT_MS);
                    }
                    if (!mBootUserLatch.await(BOOT_USER_SET_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        Slogf.w(LOG_TAG, "Boot user not set. Timeout: %d",
                                BOOT_USER_SET_TIMEOUT_MS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Slogf.w(LOG_TAG, e, "InterruptedException during wait for boot user.");
                }
                t.traceEnd();
            }

            return getBootUserUnchecked();
        }

        @Override
        public @UserIdInt int getCommunalProfileId() {
            return getCommunalProfileIdUnchecked();
        }

    } // class LocalService



    /**
     * Check if user has restrictions
     * @param restriction restrictions to check
     * @param userId id of the user
     *
     * @throws {@link android.os.UserManager.CheckedUserOperationException} if user has any of the
     *      specified restrictions
     */
    private void enforceUserRestriction(String restriction, @UserIdInt int userId, String message)
            throws UserManager.CheckedUserOperationException {
        if (hasUserRestriction(restriction, userId)) {
            String errorMessage = (message != null ? (message + ": ") : "")
                    + restriction + " is enabled.";
            Slog.w(LOG_TAG, errorMessage);
            throw new UserManager.CheckedUserOperationException(errorMessage,
                    USER_OPERATION_ERROR_UNKNOWN);
        }
    }

    /**
     * Throws CheckedUserOperationException and shows error log
     * @param message message for exception and logging
     * @param userOperationResult result/error code
     * @throws UserManager.CheckedUserOperationException
     */
    private void throwCheckedUserOperationException(@NonNull String message,
            @UserManager.UserOperationResult int userOperationResult)
            throws UserManager.CheckedUserOperationException {
        Slog.e(LOG_TAG, message);
        throw new UserManager.CheckedUserOperationException(message, userOperationResult);
    }

    /* Remove all the users except the system and permanent admin main.*/
    private void removeAllUsersExceptSystemAndPermanentAdminMain() {
        ArrayList<UserInfo> usersToRemove = new ArrayList<>();
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if (ui.id != UserHandle.USER_SYSTEM && !isNonRemovableMainUser(ui)) {
                    usersToRemove.add(ui);
                }
            }
        }
        for (UserInfo ui: usersToRemove) {
            removeUser(ui.id);
        }
    }

    private static void debug(String message) {
        Slog.d(LOG_TAG, message
                + (DBG_WITH_STACKTRACE ? " called at\n" + Debug.getCallers(10, "  ") : ""));
    }

    /** @see #getMaxUsersOfTypePerParent(UserTypeDetails) */
    @VisibleForTesting
    int getMaxUsersOfTypePerParent(String userType) {
        final UserTypeDetails type = mUserTypes.get(userType);
        if (type == null) {
            return 0;
        }
        return getMaxUsersOfTypePerParent(type);
    }

    /**
     * Returns the maximum number of users allowed for the given userTypeDetails per parent user.
     * This is applicable for user types that are {@link UserTypeDetails#isProfile()}.
     * If there is no maximum, {@link UserTypeDetails#UNLIMITED_NUMBER_OF_USERS} is returned.
     * Under certain circumstances (such as after a change-user-type) the max value can actually
     * be exceeded: this is allowed in order to keep the device in a usable state.
     * An error is logged in {@link UserManagerService#upgradeProfileToTypeLU}
     */
    private static int getMaxUsersOfTypePerParent(UserTypeDetails userTypeDetails) {
        final int defaultMax = userTypeDetails.getMaxAllowedPerParent();
        if (!Build.IS_DEBUGGABLE) {
            return defaultMax;
        } else {
            if (userTypeDetails.isManagedProfile()) {
                return SystemProperties.getInt("persist.sys.max_profiles", defaultMax);
            }
        }
        return defaultMax;
    }

    @GuardedBy("mUsersLock")
    @VisibleForTesting
    int getFreeProfileBadgeLU(int parentUserId, String userType) {
        Set<Integer> usedBadges = new ArraySet<>();
        final int userSize = mUsers.size();
        for (int i = 0; i < userSize; i++) {
            UserInfo ui = mUsers.valueAt(i).info;
            // Check which badge indexes are already used by this profile group.
            if (ui.userType.equals(userType)
                    && ui.profileGroupId == parentUserId
                    && !mRemovingUserIds.get(ui.id)) {
                usedBadges.add(ui.profileBadge);
            }
        }
        int maxUsersOfType = getMaxUsersOfTypePerParent(userType);
        if (maxUsersOfType == UserTypeDetails.UNLIMITED_NUMBER_OF_USERS) {
            maxUsersOfType = Integer.MAX_VALUE;
        }
        for (int i = 0; i < maxUsersOfType; i++) {
            if (!usedBadges.contains(i)) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Checks if the given user has a profile associated with it.
     * @param userId The parent user
     * @return
     */
    boolean hasProfile(@UserIdInt int userId) {
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo profile = mUsers.valueAt(i).info;
                if (userId != profile.id && isProfileOf(userInfo, profile)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Checks if the calling package name matches with the calling UID, throw
     * {@link SecurityException} if not.
     */
    private void verifyCallingPackage(String callingPackage, int callingUid) {
        int packageUid = mPm.snapshotComputer()
                .getPackageUid(callingPackage, 0,  UserHandle.getUserId(callingUid));
        if (packageUid != callingUid) {
            throw new SecurityException("Specified package " + callingPackage
                    + " does not match the calling uid " + callingUid);
        }
    }

    /** Retrieves the internal package manager interface. */
    private PackageManagerInternal getPackageManagerInternal() {
        // Don't need to synchonize; worst-case scenario LocalServices will be called twice.
        if (mPmInternal == null) {
            mPmInternal = LocalServices.getService(PackageManagerInternal.class);
        }
        return mPmInternal;
    }

    /** Returns the internal device policy manager interface. */
    private DevicePolicyManagerInternal getDevicePolicyManagerInternal() {
        if (mDevicePolicyManagerInternal == null) {
            mDevicePolicyManagerInternal =
                    LocalServices.getService(DevicePolicyManagerInternal.class);
        }
        return mDevicePolicyManagerInternal;
    }

    /** Returns the internal activity manager interface. */
    private @Nullable ActivityManagerInternal getActivityManagerInternal() {
        if (mAmInternal == null) {
            mAmInternal = LocalServices.getService(ActivityManagerInternal.class);
        }
        return mAmInternal;
    }

    /**
     * Returns true, when user has {@link UserInfo#FLAG_MAIN} and system property
     * {@link com.android.internal.R.bool#config_isMainUserPermanentAdmin} is true.
     */
    private boolean isNonRemovableMainUser(UserInfo userInfo) {
        return userInfo.isMain() && isMainUserPermanentAdmin();
    }

    /**
     * Returns true if {@link com.android.internal.R.bool#config_isMainUserPermanentAdmin} is true.
     * If the main user is a permanent admin user it can't be deleted
     * or downgraded to non-admin status.
     */
    public boolean isMainUserPermanentAdmin() {
        return Resources.getSystem()
                .getBoolean(R.bool.config_isMainUserPermanentAdmin);
    }

    /**
     * Returns true if {@link com.android.internal.R.bool#config_canSwitchToHeadlessSystemUser}
     * is true. If allowed, headless system user can run in the foreground even though
     * it is not a full user.
     */
    public boolean canSwitchToHeadlessSystemUser() {
        return Resources.getSystem()
                .getBoolean(R.bool.config_canSwitchToHeadlessSystemUser);
    }

    /**
     * Returns instance of {@link com.android.server.pm.UserJourneyLogger}.
     */
    public UserJourneyLogger getUserJourneyLogger() {
        return mUserJourneyLogger;
    }

}
