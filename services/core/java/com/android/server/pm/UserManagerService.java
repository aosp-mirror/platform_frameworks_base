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

import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IStopUserCallback;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyEventLogger;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ShortcutServiceInternal;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.IUserManager;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManager.EnforcingUser;
import android.os.UserManagerInternal;
import android.os.UserManagerInternal.UserRestrictionsListener;
import android.os.storage.StorageManager;
import android.security.GateKeeper;
import android.service.gatekeeper.IGateKeeperService;
import android.stats.devicepolicy.DevicePolicyEnums;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsService;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.SystemService;
import com.android.server.am.UserState;
import com.android.server.storage.DeviceStorageMonitorInternal;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wm.ActivityTaskManagerInternal;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
    private static final boolean DBG_WITH_STACKTRACE = false; // DO NOT SUBMIT WITH TRUE
    // Can be used for manual testing of id recycling
    private static final boolean RELEASE_DELETED_USER_ID = false; // DO NOT SUBMIT WITH TRUE

    private static final String TAG_NAME = "name";
    private static final String TAG_ACCOUNT = "account";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_ICON_PATH = "icon";
    private static final String ATTR_ID = "id";
    private static final String ATTR_CREATION_TIME = "created";
    private static final String ATTR_LAST_LOGGED_IN_TIME = "lastLoggedIn";
    private static final String ATTR_LAST_LOGGED_IN_FINGERPRINT = "lastLoggedInFingerprint";
    private static final String ATTR_SERIAL_NO = "serialNumber";
    private static final String ATTR_NEXT_SERIAL_NO = "nextSerialNumber";
    private static final String ATTR_PARTIAL = "partial";
    private static final String ATTR_GUEST_TO_REMOVE = "guestToRemove";
    private static final String ATTR_USER_VERSION = "version";
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
    private static final String TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS =
            "device_policy_global_restrictions";
    /** Legacy name for device owner id tag. */
    private static final String TAG_GLOBAL_RESTRICTION_OWNER_ID = "globalRestrictionOwnerUserId";
    private static final String TAG_DEVICE_OWNER_USER_ID = "deviceOwnerUserId";
    private static final String TAG_ENTRY = "entry";
    private static final String TAG_VALUE = "value";
    private static final String TAG_SEED_ACCOUNT_OPTIONS = "seedAccountOptions";
    private static final String TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL =
            "lastRequestQuietModeEnabledCall";
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

    private static final int ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION =
            UserInfo.FLAG_MANAGED_PROFILE
            | UserInfo.FLAG_EPHEMERAL
            | UserInfo.FLAG_RESTRICTED
            | UserInfo.FLAG_GUEST
            | UserInfo.FLAG_DEMO;

    @VisibleForTesting
    static final int MIN_USER_ID = 10;
    // We need to keep process uid within Integer.MAX_VALUE.
    @VisibleForTesting
    static final int MAX_USER_ID = Integer.MAX_VALUE / UserHandle.PER_USER_RANGE;

    // Max size of the queue of recently removed users
    @VisibleForTesting
    static final int MAX_RECENTLY_REMOVED_IDS_SIZE = 100;

    private static final int USER_VERSION = 8;

    private static final long EPOCH_PLUS_30_YEARS = 30L * 365 * 24 * 60 * 60 * 1000L; // ms

    // Maximum number of managed profiles permitted per user is 1. This cannot be increased
    // without first making sure that the rest of the framework is prepared for it.
    @VisibleForTesting
    static final int MAX_MANAGED_PROFILES = 1;

    static final int WRITE_USER_MSG = 1;
    static final int WRITE_USER_DELAY = 2*1000;  // 2 seconds

    // Tron counters
    private static final String TRON_GUEST_CREATED = "users_guest_created";
    private static final String TRON_USER_CREATED = "users_user_created";
    private static final String TRON_DEMO_CREATED = "users_demo_created";

    private final Context mContext;
    private final PackageManagerService mPm;
    private final Object mPackagesLock;
    private final UserDataPreparer mUserDataPreparer;
    // Short-term lock for internal state, when interaction/sync with PM is not required
    private final Object mUsersLock = LockGuard.installNewLock(LockGuard.INDEX_USER);
    private final Object mRestrictionsLock = new Object();
    // Used for serializing access to app restriction files
    private final Object mAppRestrictionsLock = new Object();

    private final Handler mHandler;

    private final File mUsersDir;
    private final File mUserListFile;

    private static final IBinder mUserRestriconToken = new Binder();

    /**
     * Internal non-parcelable wrapper for UserInfo that is not exposed to other system apps.
     */
    @VisibleForTesting
    static class UserData {
        // Basic user information and properties
        UserInfo info;
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

        /** Elapsed realtime since boot when the user started. */
        long startRealtime;

        /** Elapsed realtime since boot when the user was unlocked. */
        long unlockRealtime;

        private long mLastRequestQuietModeEnabledMillis;

        void setLastRequestQuietModeEnabledMillis(long millis) {
            mLastRequestQuietModeEnabledMillis = millis;
        }

        long getLastRequestQuietModeEnabledMillis() {
            return mLastRequestQuietModeEnabledMillis;
        }

        void clearSeedAccountData() {
            seedAccountName = null;
            seedAccountType = null;
            seedAccountOptions = null;
            persistSeedData = false;
        }
    }

    @GuardedBy("mUsersLock")
    private final SparseArray<UserData> mUsers = new SparseArray<>();

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
    private final SparseArray<Bundle> mBaseUserRestrictions = new SparseArray<>();

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
    private final SparseArray<Bundle> mCachedEffectiveUserRestrictions = new SparseArray<>();

    /**
     * User restrictions that have already been applied in
     * {@link #updateUserRestrictionsInternalLR(Bundle, int)}.  We use it to detect restrictions
     * that have changed since the last
     * {@link #updateUserRestrictionsInternalLR(Bundle, int)} call.
     */
    @GuardedBy("mRestrictionsLock")
    private final SparseArray<Bundle> mAppliedUserRestrictions = new SparseArray<>();

    /**
     * User restrictions set by {@link com.android.server.devicepolicy.DevicePolicyManagerService}
     * that should be applied to all users, including guests. Only non-empty restriction bundles are
     * stored.
     */
    @GuardedBy("mRestrictionsLock")
    private final SparseArray<Bundle> mDevicePolicyGlobalUserRestrictions = new SparseArray<>();

    /**
     * Id of the user that set global restrictions.
     */
    @GuardedBy("mRestrictionsLock")
    private int mDeviceOwnerUserId = UserHandle.USER_NULL;

    /**
     * User restrictions set by {@link com.android.server.devicepolicy.DevicePolicyManagerService}
     * for each user. Only non-empty restriction bundles are stored.
     */
    @GuardedBy("mRestrictionsLock")
    private final SparseArray<Bundle> mDevicePolicyLocalUserRestrictions = new SparseArray<>();

    @GuardedBy("mGuestRestrictions")
    private final Bundle mGuestRestrictions = new Bundle();

    /**
     * Set of user IDs being actively removed. Removed IDs linger in this set
     * for several seconds to work around a VFS caching issue.
     * Use {@link #addRemovingUserIdLocked(int)} to add elements to this array
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
    @GuardedBy("mPackagesLock")
    private int mNextSerialNumber;
    private int mUserVersion = 0;

    private IAppOpsService mAppOpsService;

    private final LocalService mLocalService;

    @GuardedBy("mUsersLock")
    private boolean mIsDeviceManaged;

    @GuardedBy("mUsersLock")
    private final SparseBooleanArray mIsUserManaged = new SparseBooleanArray();

    @GuardedBy("mUserRestrictionsListeners")
    private final ArrayList<UserRestrictionsListener> mUserRestrictionsListeners =
            new ArrayList<>();

    private final LockPatternUtils mLockPatternUtils;

    private final String ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK =
            "com.android.server.pm.DISABLE_QUIET_MODE_AFTER_UNLOCK";

    private final BroadcastReceiver mDisableQuietModeCallback = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK.equals(intent.getAction())) {
                return;
            }
            final IntentSender target = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_ID, UserHandle.USER_NULL);
            // Call setQuietModeEnabled on bg thread to avoid ANR
            BackgroundThread.getHandler().post(() ->
                    setQuietModeEnabled(userId, false, target, /* callingPackage */ null));
        }
    };

    /**
     * Start an {@link IntentSender} when user is unlocked after disabling quiet mode.
     *
     * @see {@link #requestQuietModeEnabled(String, boolean, int, IntentSender)}
     */
    private class DisableQuietModeUserUnlockedCallback extends IProgressListener.Stub {
        private final IntentSender mTarget;

        public DisableQuietModeUserUnlockedCallback(IntentSender target) {
            Preconditions.checkNotNull(target);
            mTarget = target;
        }

        @Override
        public void onStarted(int id, Bundle extras) {}

        @Override
        public void onProgress(int id, int progress, Bundle extras) {}

        @Override
        public void onFinished(int id, Bundle extras) {
            try {
                mContext.startIntentSender(mTarget, null, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                Slog.e(LOG_TAG, "Failed to start the target in the callback", e);
            }
        }
    }

    /**
     * Whether all users should be created ephemeral.
     */
    @GuardedBy("mUsersLock")
    private boolean mForceEphemeralUsers;

    @GuardedBy("mUserStates")
    private final SparseIntArray mUserStates = new SparseIntArray();

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
            }
        }

        @Override
        public void onStartUser(@UserIdInt int userId) {
            synchronized (mUms.mUsersLock) {
                final UserData user = mUms.getUserDataLU(userId);
                if (user != null) {
                    user.startRealtime = SystemClock.elapsedRealtime();
                }
            }
        }

        @Override
        public void onUnlockUser(@UserIdInt int userId) {
            synchronized (mUms.mUsersLock) {
                final UserData user = mUms.getUserDataLU(userId);
                if (user != null) {
                    user.unlockRealtime = SystemClock.elapsedRealtime();
                }
            }
        }

        @Override
        public void onStopUser(@UserIdInt int userId) {
            synchronized (mUms.mUsersLock) {
                final UserData user = mUms.getUserDataLU(userId);
                if (user != null) {
                    user.startRealtime = 0;
                    user.unlockRealtime = 0;
                }
            }
        }
    }

    // TODO b/28848102 Add support for test dependencies injection
    @VisibleForTesting
    UserManagerService(Context context) {
        this(context, null, null, new Object(), context.getCacheDir());
    }

    /**
     * Called by package manager to create the service.  This is closely
     * associated with the package manager, and the given lock is the
     * package manager's own lock.
     */
    UserManagerService(Context context, PackageManagerService pm, UserDataPreparer userDataPreparer,
            Object packagesLock) {
        this(context, pm, userDataPreparer, packagesLock, Environment.getDataDirectory());
    }

    private UserManagerService(Context context, PackageManagerService pm,
            UserDataPreparer userDataPreparer, Object packagesLock, File dataDir) {
        mContext = context;
        mPm = pm;
        mPackagesLock = packagesLock;
        mHandler = new MainHandler();
        mUserDataPreparer = userDataPreparer;
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
        mLocalService = new LocalService();
        LocalServices.addService(UserManagerInternal.class, mLocalService);
        mLockPatternUtils = new LockPatternUtils(mContext);
        mUserStates.put(UserHandle.USER_SYSTEM, UserState.STATE_BOOTING);
    }

    void systemReady() {
        mAppOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));

        synchronized (mRestrictionsLock) {
            applyUserRestrictionsLR(UserHandle.USER_SYSTEM);
        }

        UserInfo currentGuestUser = findCurrentGuestUser();
        if (currentGuestUser != null && !hasUserRestriction(
                UserManager.DISALLOW_CONFIG_WIFI, currentGuestUser.id)) {
            // If a guest user currently exists, apply the DISALLOW_CONFIG_WIFI option
            // to it, in case this guest was created in a previous version where this
            // user restriction was not a default guest restriction.
            setUserRestriction(UserManager.DISALLOW_CONFIG_WIFI, true, currentGuestUser.id);
        }

        mContext.registerReceiver(mDisableQuietModeCallback,
                new IntentFilter(ACTION_DISABLE_QUIET_MODE_AFTER_UNLOCK),
                null, mHandler);
    }

    /**
     * This method retrieves the  {@link UserManagerInternal} only for the purpose of
     * PackageManagerService construction.
     */
    UserManagerInternal getInternalForInjectorOnly() {
        return mLocalService;
    }

    void cleanupPartialUsers() {
        // Prune out any partially created, partially removed and ephemeral users.
        ArrayList<UserInfo> partials = new ArrayList<>();
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if ((ui.partial || ui.guestToRemove || ui.isEphemeral()) && i != 0) {
                    partials.add(ui);
                    addRemovingUserIdLocked(ui.id);
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
    public @NonNull List<UserInfo> getUsers(boolean excludeDying) {
        checkManageOrCreateUsersPermission("query users");
        synchronized (mUsersLock) {
            ArrayList<UserInfo> users = new ArrayList<UserInfo>(mUsers.size());
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if (ui.partial) {
                    continue;
                }
                if (!excludeDying || !mRemovingUserIds.get(ui.id)) {
                    users.add(userWithName(ui));
                }
            }
            return users;
        }
    }

    @Override
    public List<UserInfo> getProfiles(@UserIdInt int userId, boolean enabledOnly) {
        boolean returnFullInfo = true;
        if (userId != UserHandle.getCallingUserId()) {
            checkManageOrCreateUsersPermission("getting profiles related to user " + userId);
        } else {
            returnFullInfo = hasManageUsersPermission();
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mUsersLock) {
                return getProfilesLU(userId, enabledOnly, returnFullInfo);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public int[] getProfileIds(@UserIdInt int userId, boolean enabledOnly) {
        if (userId != UserHandle.getCallingUserId()) {
            checkManageOrCreateUsersPermission("getting profiles related to user " + userId);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mUsersLock) {
                return getProfileIdsLU(userId, enabledOnly).toArray();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** Assume permissions already checked and caller's identity cleared */
    @GuardedBy("mUsersLock")
    private List<UserInfo> getProfilesLU(@UserIdInt int userId, boolean enabledOnly,
            boolean fullInfo) {
        IntArray profileIds = getProfileIdsLU(userId, enabledOnly);
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
     */
    @GuardedBy("mUsersLock")
    private IntArray getProfileIdsLU(@UserIdInt int userId, boolean enabledOnly) {
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
            result.add(profile.id);
        }
        return result;
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
        checkManageUsersPermission("check if in the same profile group");
        return isSameProfileGroupNoChecks(userId, otherUserId);
    }

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

    @Override
    public UserInfo getProfileParent(@UserIdInt int userId) {
        checkManageUsersPermission("get the profile parent");
        synchronized (mUsersLock) {
            return getProfileParentLU(userId);
        }
    }

    @Override
    public int getProfileParentId(@UserIdInt int userId) {
        checkManageUsersPermission("get the profile parent");
        return mLocalService.getProfileParentId(userId);
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

    private void broadcastProfileAvailabilityChanges(UserHandle profileHandle,
            UserHandle parentHandle, boolean inQuietMode) {
        Intent intent = new Intent();
        if (inQuietMode) {
            intent.setAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE);
        } else {
            intent.setAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE);
        }
        intent.putExtra(Intent.EXTRA_QUIET_MODE, inQuietMode);
        intent.putExtra(Intent.EXTRA_USER, profileHandle);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, profileHandle.getIdentifier());
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(intent, parentHandle);
    }

    @Override
    public boolean requestQuietModeEnabled(@NonNull String callingPackage, boolean enableQuietMode,
            @UserIdInt int userId, @Nullable IntentSender target) {
        Preconditions.checkNotNull(callingPackage);

        if (enableQuietMode && target != null) {
            throw new IllegalArgumentException(
                    "target should only be specified when we are disabling quiet mode.");
        }

        ensureCanModifyQuietMode(callingPackage, Binder.getCallingUid(), target != null);
        final long identity = Binder.clearCallingIdentity();
        try {
            boolean result = false;
            if (enableQuietMode) {
                setQuietModeEnabled(
                        userId, true /* enableQuietMode */, target, callingPackage);
                result = true;
            } else {
                boolean needToShowConfirmCredential =
                        mLockPatternUtils.isSecure(userId)
                                && !StorageManager.isUserKeyUnlocked(userId);
                if (needToShowConfirmCredential) {
                    showConfirmCredentialToDisableQuietMode(userId, target);
                } else {
                    setQuietModeEnabled(
                            userId, false /* enableQuietMode */, target, callingPackage);
                    result = true;
                }
            }
            return result;
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
     * </ul>
     * <p>
     * If caller wants to start an intent after disabling the quiet mode, it must has
     * {@link Manifest.permission#MANAGE_USERS}.
     */
    private void ensureCanModifyQuietMode(String callingPackage, int callingUid,
            boolean startIntent) {
        if (hasManageUsersPermission()) {
            return;
        }
        if (startIntent) {
            throw new SecurityException("MANAGE_USERS permission is required to start intent "
                    + "after disabling quiet mode.");
        }
        final boolean hasModifyQuietModePermission = hasPermissionGranted(
                Manifest.permission.MODIFY_QUIET_MODE, callingUid);
        if (hasModifyQuietModePermission) {
            return;
        }

        verifyCallingPackage(callingPackage, callingUid);
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

            if (profile == null || !profile.isManagedProfile()) {
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
                ActivityManager.getService().stopUser(userId, /* force */true, null);
                LocalServices.getService(ActivityManagerInternal.class)
                        .killForegroundAppsForUser(userId);
            } else {
                IProgressListener callback = target != null
                        ? new DisableQuietModeUserUnlockedCallback(target)
                        : null;
                ActivityManager.getService().startUserInBackgroundWithListener(
                        userId, callback);
            }
            logQuietModeEnabled(userId, enableQuietMode, callingPackage);
        } catch (RemoteException e) {
            // Should not happen, same process.
            e.rethrowAsRuntimeException();
        }
        broadcastProfileAvailabilityChanges(profile.getUserHandle(), parent.getUserHandle(),
                enableQuietMode);
    }

    private void logQuietModeEnabled(@UserIdInt int userId, boolean enableQuietMode,
            @Nullable String callingPackage) {
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
            if (info == null || !info.isManagedProfile()) {
                return false;
            }
            return info.isQuietModeEnabled();
        }
    }

    /**
     * Show confirm credential screen to unlock user in order to turn off quiet mode.
     */
    private void showConfirmCredentialToDisableQuietMode(
            @UserIdInt int userId, @Nullable IntentSender target) {
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
        callBackIntent.putExtra(Intent.EXTRA_USER_ID, userId);
        callBackIntent.setPackage(mContext.getPackageName());
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
        mContext.startActivity(unlockIntent);
    }

    @Override
    public void setUserEnabled(@UserIdInt int userId) {
        checkManageUsersPermission("enable user");
        synchronized (mPackagesLock) {
            UserInfo info;
            synchronized (mUsersLock) {
                info = getUserInfoLU(userId);
            }
            if (info != null && !info.isEnabled()) {
                info.flags ^= UserInfo.FLAG_DISABLED;
                writeUserLP(getUserDataLU(info.id));
            }
        }
    }

    @Override
    public void setUserAdmin(@UserIdInt int userId) {
        checkManageUserAndAcrossUsersFullPermission("set user admin");

        synchronized (mPackagesLock) {
            UserInfo info;
            synchronized (mUsersLock) {
                info = getUserInfoLU(userId);
            }
            if (info == null || info.isAdmin()) {
                // Exit if no user found with that id, or the user is already an Admin.
                return;
            }

            info.flags ^= UserInfo.FLAG_ADMIN;
            writeUserLP(getUserDataLU(info.id));
        }

        // Remove non-admin restrictions.
        // Keep synchronized with createUserEvenWhenDisallowed.
        setUserRestriction(UserManager.DISALLOW_SMS, false, userId);
        setUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS, false, userId);
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
        try {
            am.restartUserInBackground(userId);
        } catch (RemoteException re) {
            throw re.rethrowAsRuntimeException();
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public UserInfo getUserInfo(@UserIdInt int userId) {
        checkManageOrCreateUsersPermission("query user");
        synchronized (mUsersLock) {
            return userWithName(getUserInfoLU(userId));
        }
    }

    /**
     * Returns a UserInfo object with the name filled in, for Owner, or the original
     * if the name is already set.
     */
    private UserInfo userWithName(UserInfo orig) {
        if (orig != null && orig.name == null && orig.id == UserHandle.USER_SYSTEM) {
            UserInfo withName = new UserInfo(orig);
            withName.name = getOwnerName();
            return withName;
        } else {
            return orig;
        }
    }

    @Override
    public int getManagedProfileBadge(@UserIdInt int userId) {
        checkManageOrInteractPermIfCallerInOtherProfileGroup(userId, "getManagedProfileBadge");
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null ? userInfo.profileBadge : 0;
        }
    }

    @Override
    public boolean isManagedProfile(@UserIdInt int userId) {
        checkManageOrInteractPermIfCallerInOtherProfileGroup(userId, "isManagedProfile");
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.isManagedProfile();
        }
    }

    @Override
    public boolean isUserUnlockingOrUnlocked(@UserIdInt int userId) {
        checkManageOrInteractPermIfCallerInOtherProfileGroup(userId, "isUserUnlockingOrUnlocked");
        return mLocalService.isUserUnlockingOrUnlocked(userId);
    }

    @Override
    public boolean isUserUnlocked(@UserIdInt int userId) {
        checkManageOrInteractPermIfCallerInOtherProfileGroup(userId, "isUserUnlocked");
        return mLocalService.isUserUnlocked(userId);
    }

    @Override
    public boolean isUserRunning(@UserIdInt int userId) {
        checkManageOrInteractPermIfCallerInOtherProfileGroup(userId, "isUserRunning");
        return mLocalService.isUserRunning(userId);
    }

    @Override
    public String getUserName() {
        if (!hasManageUsersOrPermission(android.Manifest.permission.GET_ACCOUNTS_PRIVILEGED)) {
            throw new SecurityException("You need MANAGE_USERS or GET_ACCOUNTS_PRIVILEGED "
                    + "permissions to: get user name");
        }
        final int userId = UserHandle.getUserId(Binder.getCallingUid());
        synchronized (mUsersLock) {
            UserInfo userInfo = userWithName(getUserInfoLU(userId));
            return userInfo == null ? "" : userInfo.name;
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

    private void checkManageOrInteractPermIfCallerInOtherProfileGroup(@UserIdInt int userId,
            String name) {
        int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId == userId || isSameProfileGroupNoChecks(callingUserId, userId) ||
                hasManageUsersPermission()) {
            return;
        }
        if (!hasPermissionGranted(Manifest.permission.INTERACT_ACROSS_USERS,
                Binder.getCallingUid())) {
            throw new SecurityException("You need INTERACT_ACROSS_USERS or MANAGE_USERS permission "
                    + "to: check " + name);
        }
    }

    @Override
    public boolean isDemoUser(@UserIdInt int userId) {
        int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId && !hasManageUsersPermission()) {
            throw new SecurityException("You need MANAGE_USERS permission to query if u=" + userId
                    + " is a demo user");
        }
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            return userInfo != null && userInfo.isDemo();
        }
    }

    @Override
    public boolean isRestricted() {
        synchronized (mUsersLock) {
            return getUserInfoLU(UserHandle.getCallingUserId()).isRestricted();
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
    public boolean hasRestrictedProfiles() {
        checkManageUsersPermission("hasRestrictedProfiles");
        final int callingUserId = UserHandle.getCallingUserId();
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo profile = mUsers.valueAt(i).info;
                if (callingUserId != profile.id
                        && profile.restrictedProfileParentId == callingUserId) {
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

    @Override
    public void setUserName(@UserIdInt int userId, String name) {
        checkManageUsersPermission("rename users");
        boolean changed = false;
        synchronized (mPackagesLock) {
            UserData userData = getUserDataNoChecks(userId);
            if (userData == null || userData.info.partial) {
                Slog.w(LOG_TAG, "setUserName: unknown user #" + userId);
                return;
            }
            if (name != null && !name.equals(userData.info.name)) {
                userData.info.name = name;
                writeUserLP(userData);
                changed = true;
            }
        }
        if (changed) {
            long ident = Binder.clearCallingIdentity();
            try {
                sendUserInfoChangedBroadcast(userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public void setUserIcon(@UserIdInt int userId, Bitmap bitmap) {
        checkManageUsersPermission("update users");
        if (hasUserRestriction(UserManager.DISALLOW_SET_USER_ICON, userId)) {
            Log.w(LOG_TAG, "Cannot set user icon. DISALLOW_SET_USER_ICON is enabled.");
            return;
        }
        mLocalService.setUserIcon(userId, bitmap);
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
            final int callingGroupId = getUserInfoNoChecks(callingUserId).profileGroupId;
            final int targetGroupId = targetUserInfo.profileGroupId;
            final boolean sameGroup = (callingGroupId != UserInfo.NO_PROFILE_GROUP_ID
                    && callingGroupId == targetGroupId);
            if ((callingUserId != targetUserId) && !sameGroup) {
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
            Log.e(LOG_TAG, "Couldn't find icon file", e);
        }
        return null;
    }

    public void makeInitialized(@UserIdInt int userId) {
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
            scheduleWriteUser(userData);
        }
    }

    /**
     * If default guest restrictions haven't been initialized yet, add the basic
     * restrictions.
     */
    private void initDefaultGuestRestrictions() {
        synchronized (mGuestRestrictions) {
            if (mGuestRestrictions.isEmpty()) {
                mGuestRestrictions.putBoolean(UserManager.DISALLOW_CONFIG_WIFI, true);
                mGuestRestrictions.putBoolean(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, true);
                mGuestRestrictions.putBoolean(UserManager.DISALLOW_OUTGOING_CALLS, true);
                mGuestRestrictions.putBoolean(UserManager.DISALLOW_SMS, true);
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
        synchronized (mGuestRestrictions) {
            mGuestRestrictions.clear();
            mGuestRestrictions.putAll(restrictions);
        }
        synchronized (mPackagesLock) {
            writeUserListLP();
        }
    }

    /**
     * See {@link UserManagerInternal#setDevicePolicyUserRestrictions}
     */
    private void setDevicePolicyUserRestrictionsInner(@UserIdInt int userId,
            @Nullable Bundle restrictions, boolean isDeviceOwner, int cameraRestrictionScope) {
        final Bundle global = new Bundle();
        final Bundle local = new Bundle();

        // Sort restrictions into local and global ensuring they don't overlap.
        UserRestrictionsUtils.sortToGlobalAndLocal(restrictions, isDeviceOwner,
                cameraRestrictionScope, global, local);

        boolean globalChanged, localChanged;
        synchronized (mRestrictionsLock) {
            // Update global and local restrictions if they were changed.
            globalChanged = updateRestrictionsIfNeededLR(
                    userId, global, mDevicePolicyGlobalUserRestrictions);
            localChanged = updateRestrictionsIfNeededLR(
                    userId, local, mDevicePolicyLocalUserRestrictions);

            if (isDeviceOwner) {
                // Remember the global restriction owner userId to be able to make a distinction
                // in getUserRestrictionSource on who set local policies.
                mDeviceOwnerUserId = userId;
            } else {
                if (mDeviceOwnerUserId == userId) {
                    // When profile owner sets restrictions it passes null global bundle and we
                    // reset global restriction owner userId.
                    // This means this user used to have DO, but now the DO is gone and the user
                    // instead has PO.
                    mDeviceOwnerUserId = UserHandle.USER_NULL;
                }
            }
        }
        if (DBG) {
            Log.d(LOG_TAG, "setDevicePolicyUserRestrictions: userId=" + userId
                            + " global=" + global + (globalChanged ? " (changed)" : "")
                            + " local=" + local + (localChanged ? " (changed)" : "")
            );
        }
        // Don't call them within the mRestrictionsLock.
        synchronized (mPackagesLock) {
            if (localChanged || globalChanged) {
                writeUserLP(getUserDataNoChecks(userId));
            }
        }

        synchronized (mRestrictionsLock) {
            if (globalChanged) {
                applyUserRestrictionsForAllUsersLR();
            } else if (localChanged) {
                applyUserRestrictionsLR(userId);
            }
        }
    }

    /**
     * Updates restriction bundle for a given user in a given restriction array. If new bundle is
     * empty, record is removed from the array.
     * @return whether restrictions bundle is different from the old one.
     */
    private boolean updateRestrictionsIfNeededLR(@UserIdInt int userId,
            @Nullable Bundle restrictions, SparseArray<Bundle> restrictionsArray) {
        final boolean changed =
                !UserRestrictionsUtils.areEqual(restrictionsArray.get(userId), restrictions);
        if (changed) {
            if (!UserRestrictionsUtils.isEmpty(restrictions)) {
                restrictionsArray.put(userId, restrictions);
            } else {
                restrictionsArray.delete(userId);
            }
        }
        return changed;
    }

    @GuardedBy("mRestrictionsLock")
    private Bundle computeEffectiveUserRestrictionsLR(@UserIdInt int userId) {
        final Bundle baseRestrictions =
                UserRestrictionsUtils.nonNull(mBaseUserRestrictions.get(userId));
        final Bundle global = UserRestrictionsUtils.mergeAll(mDevicePolicyGlobalUserRestrictions);
        final Bundle local = mDevicePolicyLocalUserRestrictions.get(userId);

        if (UserRestrictionsUtils.isEmpty(global) && UserRestrictionsUtils.isEmpty(local)) {
            // Common case first.
            return baseRestrictions;
        }
        final Bundle effective = UserRestrictionsUtils.clone(baseRestrictions);
        UserRestrictionsUtils.merge(effective, global);
        UserRestrictionsUtils.merge(effective, local);

        return effective;
    }

    @GuardedBy("mRestrictionsLock")
    private void invalidateEffectiveUserRestrictionsLR(@UserIdInt int userId) {
        if (DBG) {
            Log.d(LOG_TAG, "invalidateEffectiveUserRestrictions userId=" + userId);
        }
        mCachedEffectiveUserRestrictions.remove(userId);
    }

    private Bundle getEffectiveUserRestrictions(@UserIdInt int userId) {
        synchronized (mRestrictionsLock) {
            Bundle restrictions = mCachedEffectiveUserRestrictions.get(userId);
            if (restrictions == null) {
                restrictions = computeEffectiveUserRestrictionsLR(userId);
                mCachedEffectiveUserRestrictions.put(userId, restrictions);
            }
            return restrictions;
        }
    }

    /** @return a specific user restriction that's in effect currently. */
    @Override
    public boolean hasUserRestriction(String restrictionKey, @UserIdInt int userId) {
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
        checkManageUsersPermission("getUserRestrictionSource");

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

        synchronized (mRestrictionsLock) {
            // Check if it is set by profile owner.
            Bundle profileOwnerRestrictions = mDevicePolicyLocalUserRestrictions.get(userId);
            if (UserRestrictionsUtils.contains(profileOwnerRestrictions, restrictionKey)) {
                result.add(getEnforcingUserLocked(userId));
            }

            // Iterate over all users who enforce global restrictions.
            for (int i = mDevicePolicyGlobalUserRestrictions.size() - 1; i >= 0; i--) {
                Bundle globalRestrictions = mDevicePolicyGlobalUserRestrictions.valueAt(i);
                int profileUserId = mDevicePolicyGlobalUserRestrictions.keyAt(i);
                if (UserRestrictionsUtils.contains(globalRestrictions, restrictionKey)) {
                    result.add(getEnforcingUserLocked(profileUserId));
                }
            }
        }
        return result;
    }

    @GuardedBy("mRestrictionsLock")
    private EnforcingUser getEnforcingUserLocked(@UserIdInt int userId) {
        int source = mDeviceOwnerUserId == userId ? UserManager.RESTRICTION_SOURCE_DEVICE_OWNER
                : UserManager.RESTRICTION_SOURCE_PROFILE_OWNER;
        return new EnforcingUser(userId, source);
    }

    /**
     * @return UserRestrictions that are in effect currently.  This always returns a new
     * {@link Bundle}.
     */
    @Override
    public Bundle getUserRestrictions(@UserIdInt int userId) {
        return UserRestrictionsUtils.clone(getEffectiveUserRestrictions(userId));
    }

    @Override
    public boolean hasBaseUserRestriction(String restrictionKey, @UserIdInt int userId) {
        checkManageUsersPermission("hasBaseUserRestriction");
        if (!UserRestrictionsUtils.isValidRestriction(restrictionKey)) {
            return false;
        }
        synchronized (mRestrictionsLock) {
            Bundle bundle = mBaseUserRestrictions.get(userId);
            return (bundle != null && bundle.getBoolean(restrictionKey, false));
        }
    }

    @Override
    public void setUserRestriction(String key, boolean value, @UserIdInt int userId) {
        checkManageUsersPermission("setUserRestriction");
        if (!UserRestrictionsUtils.isValidRestriction(key)) {
            return;
        }
        synchronized (mRestrictionsLock) {
            // Note we can't modify Bundles stored in mBaseUserRestrictions directly, so create
            // a copy.
            final Bundle newRestrictions = UserRestrictionsUtils.clone(
                    mBaseUserRestrictions.get(userId));
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
                mAppliedUserRestrictions.get(userId));

        // Update base restrictions.
        if (newBaseRestrictions != null) {
            // If newBaseRestrictions == the current one, it's probably a bug.
            final Bundle prevBaseRestrictions = mBaseUserRestrictions.get(userId);

            Preconditions.checkState(prevBaseRestrictions != newBaseRestrictions);
            Preconditions.checkState(mCachedEffectiveUserRestrictions.get(userId)
                    != newBaseRestrictions);

            if (updateRestrictionsIfNeededLR(userId, newBaseRestrictions, mBaseUserRestrictions)) {
                scheduleWriteUser(getUserDataNoChecks(userId));
            }
        }

        final Bundle effective = computeEffectiveUserRestrictionsLR(userId);

        mCachedEffectiveUserRestrictions.put(userId, effective);

        // Apply the new restrictions.
        if (DBG) {
            debug("Applying user restrictions: userId=" + userId
                    + " new=" + effective + " prev=" + prevAppliedRestrictions);
        }

        if (mAppOpsService != null) { // We skip it until system-ready.
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        mAppOpsService.setUserRestrictions(effective, mUserRestriconToken, userId);
                    } catch (RemoteException e) {
                        Log.w(LOG_TAG, "Unable to notify AppOpsService of UserRestrictions");
                    }
                }
            });
        }

        propagateUserRestrictionsLR(userId, effective, prevAppliedRestrictions);

        mAppliedUserRestrictions.put(userId, new Bundle(effective));
    }

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
                mContext.sendBroadcastAsUser(broadcast, UserHandle.of(userId));
            }
        });
    }

    // Package private for the inner class.
    @GuardedBy("mRestrictionsLock")
    void applyUserRestrictionsLR(@UserIdInt int userId) {
        updateUserRestrictionsInternalLR(null, userId);
    }

    @GuardedBy("mRestrictionsLock")
    // Package private for the inner class.
    void applyUserRestrictionsForAllUsersLR() {
        if (DBG) {
            debug("applyUserRestrictionsForAllUsersLR");
        }
        // First, invalidate all cached values.
        mCachedEffectiveUserRestrictions.clear();

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
                    Log.w(LOG_TAG, "Unable to access ActivityManagerService");
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
        return count >= UserManager.getMaxSupportedUsers();
    }

    @Override
    public boolean canAddMoreManagedProfiles(@UserIdInt int userId, boolean allowedToRemoveOne) {
        checkManageUsersPermission("check if more managed profiles can be added.");
        if (ActivityManager.isLowRamDeviceStatic()) {
            return false;
        }
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_MANAGED_USERS)) {
            return false;
        }
        // Limit number of managed profiles that can be created
        final int managedProfilesCount = getProfiles(userId, false).size() - 1;
        final int profilesRemovedCount = managedProfilesCount > 0 && allowedToRemoveOne ? 1 : 0;
        if (managedProfilesCount - profilesRemovedCount >= getMaxManagedProfiles()) {
            return false;
        }
        synchronized(mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            if (userInfo == null || !userInfo.canHaveProfile()) {
                return false;
            }
            int usersCountAfterRemoving = getAliveUsersExcludingGuestsCountLU()
                    - profilesRemovedCount;
            // We allow creating a managed profile in the special case where there is only one user.
            return usersCountAfterRemoving  == 1
                    || usersCountAfterRemoving < UserManager.getMaxSupportedUsers();
        }
    }

    @GuardedBy("mUsersLock")
    private int getAliveUsersExcludingGuestsCountLU() {
        int aliveUserCount = 0;
        final int totalUserCount = mUsers.size();
        // Skip over users being removed
        for (int i = 0; i < totalUserCount; i++) {
            UserInfo user = mUsers.valueAt(i).info;
            if (!mRemovingUserIds.get(user.id) && !user.isGuest()) {
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
     * @see #hasManageOrCreateUsersPermission()
     */
    private static final void checkManageOrCreateUsersPermission(String message) {
        if (!hasManageOrCreateUsersPermission()) {
            throw new SecurityException(
                    "You either need MANAGE_USERS or CREATE_USERS permission to: " + message);
        }
    }

    /**
     * Similar to {@link #checkManageOrCreateUsersPermission(String)} but when the caller is tries
     * to create user/profiles other than what is allowed for
     * {@link android.Manifest.permission#CREATE_USERS CREATE_USERS} permission, then it will only
     * allow callers with {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} permission.
     */
    private static final void checkManageOrCreateUsersPermission(int creationFlags) {
        if ((creationFlags & ~ALLOWED_FLAGS_FOR_CREATE_USERS_PERMISSION) == 0) {
            if (!hasManageOrCreateUsersPermission()) {
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
        return UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)
                || callingUid == Process.ROOT_UID
                || hasPermissionGranted(android.Manifest.permission.MANAGE_USERS, callingUid)
                || hasPermissionGranted(alternativePermission, callingUid);
    }

    /**
     * @return whether the calling UID is system UID or root's UID or the calling app has the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS} or
     * {@link android.Manifest.permission#CREATE_USERS CREATE_USERS}.
     */
    private static final boolean hasManageOrCreateUsersPermission() {
        return hasManageUsersOrPermission(android.Manifest.permission.CREATE_USERS);
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
     * Returns an array of user ids. This array is cached here for quick access, so do not modify or
     * cache it elsewhere.
     * @return the array of user ids.
     */
    public int[] getUserIds() {
        synchronized (mUsersLock) {
            return mUserIds;
        }
    }

    @GuardedBy({"mRestrictionsLock", "mPackagesLock"})
    private void readUserListLP() {
        if (!mUserListFile.exists()) {
            fallbackToSingleUserLP();
            return;
        }
        FileInputStream fis = null;
        AtomicFile userListFile = new AtomicFile(mUserListFile);
        try {
            fis = userListFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());
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
                String lastSerialNumber = parser.getAttributeValue(null, ATTR_NEXT_SERIAL_NO);
                if (lastSerialNumber != null) {
                    mNextSerialNumber = Integer.parseInt(lastSerialNumber);
                }
                String versionNumber = parser.getAttributeValue(null, ATTR_USER_VERSION);
                if (versionNumber != null) {
                    mUserVersion = Integer.parseInt(versionNumber);
                }
            }

            // Pre-O global user restriction were stored as a single bundle (as opposed to per-user
            // currently), take care of it in case of upgrade.
            Bundle oldDevicePolicyGlobalUserRestrictions = null;

            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    final String name = parser.getName();
                    if (name.equals(TAG_USER)) {
                        String id = parser.getAttributeValue(null, ATTR_ID);

                        UserData userData = readUserLP(Integer.parseInt(id));

                        if (userData != null) {
                            synchronized (mUsersLock) {
                                mUsers.put(userData.info.id, userData);
                                if (mNextSerialNumber < 0
                                        || mNextSerialNumber <= userData.info.id) {
                                    mNextSerialNumber = userData.info.id + 1;
                                }
                            }
                        }
                    } else if (name.equals(TAG_GUEST_RESTRICTIONS)) {
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
                    } else if (name.equals(TAG_DEVICE_OWNER_USER_ID)
                            // Legacy name, should only be encountered when upgrading from pre-O.
                            || name.equals(TAG_GLOBAL_RESTRICTION_OWNER_ID)) {
                        String ownerUserId = parser.getAttributeValue(null, ATTR_ID);
                        if (ownerUserId != null) {
                            mDeviceOwnerUserId = Integer.parseInt(ownerUserId);
                        }
                    } else if (name.equals(TAG_DEVICE_POLICY_RESTRICTIONS)) {
                        // Should only happen when upgrading from pre-O (version < 7).
                        oldDevicePolicyGlobalUserRestrictions =
                                UserRestrictionsUtils.readRestrictions(parser);
                    }
                }
            }

            updateUserIds();
            upgradeIfNecessaryLP(oldDevicePolicyGlobalUserRestrictions);
        } catch (IOException | XmlPullParserException e) {
            fallbackToSingleUserLP();
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    /**
     * Upgrade steps between versions, either for fixing bugs or changing the data format.
     * @param oldGlobalUserRestrictions Pre-O global device policy restrictions.
     */
    @GuardedBy({"mRestrictionsLock", "mPackagesLock"})
    private void upgradeIfNecessaryLP(Bundle oldGlobalUserRestrictions) {
        Set<Integer> userIdsToWrite = new ArraySet<>();
        final int originalVersion = mUserVersion;
        int userVersion = mUserVersion;
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
            final boolean splitSystemUser = UserManager.isSplitSystemUser();
            synchronized (mUsersLock) {
                for (int i = 0; i < mUsers.size(); i++) {
                    UserData userData = mUsers.valueAt(i);
                    // In non-split mode, only user 0 can have restricted profiles
                    if (!splitSystemUser && userData.info.isRestricted()
                            && (userData.info.restrictedProfileParentId
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
                if (!UserRestrictionsUtils.isEmpty(oldGlobalUserRestrictions)
                        && mDeviceOwnerUserId != UserHandle.USER_NULL) {
                    mDevicePolicyGlobalUserRestrictions.put(
                            mDeviceOwnerUserId, oldGlobalUserRestrictions);
                }
                // ENSURE_VERIFY_APPS is now enforced globally even if put by profile owner, so move
                // it from local to global bundle for all users who set it.
                UserRestrictionsUtils.moveRestriction(UserManager.ENSURE_VERIFY_APPS,
                        mDevicePolicyLocalUserRestrictions, mDevicePolicyGlobalUserRestrictions
                );
            }
            userVersion = 7;
        }

        if (userVersion < 8) {
            // Added FLAG_FULL and FLAG_SYSTEM flags.
            synchronized (mUsersLock) {
                UserData userData = mUsers.get(UserHandle.USER_SYSTEM);
                userData.info.flags |= UserInfo.FLAG_SYSTEM;
                if (!UserManager.isHeadlessSystemUserMode()) {
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

        if (userVersion < USER_VERSION) {
            Slog.w(LOG_TAG, "User version " + mUserVersion + " didn't upgrade as expected to "
                    + USER_VERSION);
        } else {
            mUserVersion = userVersion;

            if (originalVersion < mUserVersion) {
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

    @GuardedBy({"mPackagesLock", "mRestrictionsLock"})
    private void fallbackToSingleUserLP() {
        int flags = UserInfo.FLAG_SYSTEM | UserInfo.FLAG_INITIALIZED | UserInfo.FLAG_ADMIN
                | UserInfo.FLAG_PRIMARY;
        // In headless system user mode, headless system user is not a full user.
        if (!UserManager.isHeadlessSystemUserMode()) {
            flags |= UserInfo.FLAG_FULL;
        }
        // Create the system user
        UserInfo system = new UserInfo(UserHandle.USER_SYSTEM, null, null, flags);
        UserData userData = putUserInfo(system);
        mNextSerialNumber = MIN_USER_ID;
        mUserVersion = USER_VERSION;

        Bundle restrictions = new Bundle();
        try {
            final String[] defaultFirstUserRestrictions = mContext.getResources().getStringArray(
                    com.android.internal.R.array.config_defaultFirstUserRestrictions);
            for (String userRestriction : defaultFirstUserRestrictions) {
                if (UserRestrictionsUtils.isValidRestriction(userRestriction)) {
                    restrictions.putBoolean(userRestriction, true);
                }
            }
        } catch (Resources.NotFoundException e) {
            Log.e(LOG_TAG, "Couldn't find resource: config_defaultFirstUserRestrictions", e);
        }

        if (!restrictions.isEmpty()) {
            synchronized (mRestrictionsLock) {
                mBaseUserRestrictions.append(UserHandle.USER_SYSTEM, restrictions);
            }
        }

        updateUserIds();
        initDefaultGuestRestrictions();

        writeUserLP(userData);
        writeUserListLP();
    }

    private String getOwnerName() {
        return mContext.getResources().getString(com.android.internal.R.string.owner_name);
    }

    private void scheduleWriteUser(UserData userData) {
        if (DBG) {
            debug("scheduleWriteUser");
        }
        // No need to wrap it within a lock -- worst case, we'll just post the same message
        // twice.
        if (!mHandler.hasMessages(WRITE_USER_MSG, userData)) {
            Message msg = mHandler.obtainMessage(WRITE_USER_MSG, userData);
            mHandler.sendMessageDelayed(msg, WRITE_USER_DELAY);
        }
    }

    private void writeUserLP(UserData userData) {
        if (DBG) {
            debug("writeUserLP " + userData);
        }
        FileOutputStream fos = null;
        AtomicFile userFile = new AtomicFile(new File(mUsersDir, userData.info.id + XML_SUFFIX));
        try {
            fos = userFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);
            writeUserLP(userData, bos);
            userFile.finishWrite(fos);
        } catch (Exception ioe) {
            Slog.e(LOG_TAG, "Error writing user info " + userData.info.id, ioe);
            userFile.failWrite(fos);
        }
    }

    /*
     * Writes the user file in this format:
     *
     * <user flags="20039023" id="0">
     *   <name>Primary</name>
     * </user>
     */
    @VisibleForTesting
    void writeUserLP(UserData userData, OutputStream os)
            throws IOException, XmlPullParserException {
        // XmlSerializer serializer = XmlUtils.serializerInstance();
        final XmlSerializer serializer = new FastXmlSerializer();
        serializer.setOutput(os, StandardCharsets.UTF_8.name());
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

        final UserInfo userInfo = userData.info;
        serializer.startTag(null, TAG_USER);
        serializer.attribute(null, ATTR_ID, Integer.toString(userInfo.id));
        serializer.attribute(null, ATTR_SERIAL_NO, Integer.toString(userInfo.serialNumber));
        serializer.attribute(null, ATTR_FLAGS, Integer.toString(userInfo.flags));
        serializer.attribute(null, ATTR_CREATION_TIME, Long.toString(userInfo.creationTime));
        serializer.attribute(null, ATTR_LAST_LOGGED_IN_TIME,
                Long.toString(userInfo.lastLoggedInTime));
        if (userInfo.lastLoggedInFingerprint != null) {
            serializer.attribute(null, ATTR_LAST_LOGGED_IN_FINGERPRINT,
                    userInfo.lastLoggedInFingerprint);
        }
        if (userInfo.iconPath != null) {
            serializer.attribute(null,  ATTR_ICON_PATH, userInfo.iconPath);
        }
        if (userInfo.partial) {
            serializer.attribute(null, ATTR_PARTIAL, "true");
        }
        if (userInfo.guestToRemove) {
            serializer.attribute(null, ATTR_GUEST_TO_REMOVE, "true");
        }
        if (userInfo.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID) {
            serializer.attribute(null, ATTR_PROFILE_GROUP_ID,
                    Integer.toString(userInfo.profileGroupId));
        }
        serializer.attribute(null, ATTR_PROFILE_BADGE,
                Integer.toString(userInfo.profileBadge));
        if (userInfo.restrictedProfileParentId != UserInfo.NO_PROFILE_GROUP_ID) {
            serializer.attribute(null, ATTR_RESTRICTED_PROFILE_PARENT_ID,
                    Integer.toString(userInfo.restrictedProfileParentId));
        }
        // Write seed data
        if (userData.persistSeedData) {
            if (userData.seedAccountName != null) {
                serializer.attribute(null, ATTR_SEED_ACCOUNT_NAME, userData.seedAccountName);
            }
            if (userData.seedAccountType != null) {
                serializer.attribute(null, ATTR_SEED_ACCOUNT_TYPE, userData.seedAccountType);
            }
        }
        if (userInfo.name != null) {
            serializer.startTag(null, TAG_NAME);
            serializer.text(userInfo.name);
            serializer.endTag(null, TAG_NAME);
        }
        synchronized (mRestrictionsLock) {
            UserRestrictionsUtils.writeRestrictions(serializer,
                    mBaseUserRestrictions.get(userInfo.id), TAG_RESTRICTIONS);
            UserRestrictionsUtils.writeRestrictions(serializer,
                    mDevicePolicyLocalUserRestrictions.get(userInfo.id),
                    TAG_DEVICE_POLICY_RESTRICTIONS);
            UserRestrictionsUtils.writeRestrictions(serializer,
                    mDevicePolicyGlobalUserRestrictions.get(userInfo.id),
                    TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS);
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

        if (userData.getLastRequestQuietModeEnabledMillis() != 0L) {
            serializer.startTag(/* namespace */ null, TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL);
            serializer.text(String.valueOf(userData.getLastRequestQuietModeEnabledMillis()));
            serializer.endTag(/* namespace */ null, TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL);
        }

        serializer.endTag(null, TAG_USER);

        serializer.endDocument();
    }

    /*
     * Writes the user list file in this format:
     *
     * <users nextSerialNumber="3">
     *   <user id="0"></user>
     *   <user id="2"></user>
     * </users>
     */
    @GuardedBy({"mRestrictionsLock", "mPackagesLock"})
    private void writeUserListLP() {
        if (DBG) {
            debug("writeUserList");
        }
        FileOutputStream fos = null;
        AtomicFile userListFile = new AtomicFile(mUserListFile);
        try {
            fos = userListFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_USERS);
            serializer.attribute(null, ATTR_NEXT_SERIAL_NO, Integer.toString(mNextSerialNumber));
            serializer.attribute(null, ATTR_USER_VERSION, Integer.toString(mUserVersion));

            serializer.startTag(null, TAG_GUEST_RESTRICTIONS);
            synchronized (mGuestRestrictions) {
                UserRestrictionsUtils
                        .writeRestrictions(serializer, mGuestRestrictions, TAG_RESTRICTIONS);
            }
            serializer.endTag(null, TAG_GUEST_RESTRICTIONS);
            serializer.startTag(null, TAG_DEVICE_OWNER_USER_ID);
            serializer.attribute(null, ATTR_ID, Integer.toString(mDeviceOwnerUserId));
            serializer.endTag(null, TAG_DEVICE_OWNER_USER_ID);
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
                serializer.attribute(null, ATTR_ID, Integer.toString(id));
                serializer.endTag(null, TAG_USER);
            }

            serializer.endTag(null, TAG_USERS);

            serializer.endDocument();
            userListFile.finishWrite(fos);
        } catch (Exception e) {
            userListFile.failWrite(fos);
            Slog.e(LOG_TAG, "Error writing user list");
        }
    }

    private UserData readUserLP(int id) {
        FileInputStream fis = null;
        try {
            AtomicFile userFile =
                    new AtomicFile(new File(mUsersDir, Integer.toString(id) + XML_SUFFIX));
            fis = userFile.openRead();
            return readUserLP(id, fis);
        } catch (IOException ioe) {
            Slog.e(LOG_TAG, "Error reading user list");
        } catch (XmlPullParserException pe) {
            Slog.e(LOG_TAG, "Error reading user list");
        } finally {
            IoUtils.closeQuietly(fis);
        }
        return null;
    }

    @VisibleForTesting
    UserData readUserLP(int id, InputStream is) throws IOException,
            XmlPullParserException {
        int flags = 0;
        int serialNumber = id;
        String name = null;
        String account = null;
        String iconPath = null;
        long creationTime = 0L;
        long lastLoggedInTime = 0L;
        long lastRequestQuietModeEnabledTimestamp = 0L;
        String lastLoggedInFingerprint = null;
        int profileGroupId = UserInfo.NO_PROFILE_GROUP_ID;
        int profileBadge = 0;
        int restrictedProfileParentId = UserInfo.NO_PROFILE_GROUP_ID;
        boolean partial = false;
        boolean guestToRemove = false;
        boolean persistSeedData = false;
        String seedAccountName = null;
        String seedAccountType = null;
        PersistableBundle seedAccountOptions = null;
        Bundle baseRestrictions = null;
        Bundle localRestrictions = null;
        Bundle globalRestrictions = null;

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(is, StandardCharsets.UTF_8.name());
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
            int storedId = readIntAttribute(parser, ATTR_ID, -1);
            if (storedId != id) {
                Slog.e(LOG_TAG, "User id does not match the file name");
                return null;
            }
            serialNumber = readIntAttribute(parser, ATTR_SERIAL_NO, id);
            flags = readIntAttribute(parser, ATTR_FLAGS, 0);
            iconPath = parser.getAttributeValue(null, ATTR_ICON_PATH);
            creationTime = readLongAttribute(parser, ATTR_CREATION_TIME, 0);
            lastLoggedInTime = readLongAttribute(parser, ATTR_LAST_LOGGED_IN_TIME, 0);
            lastLoggedInFingerprint = parser.getAttributeValue(null,
                    ATTR_LAST_LOGGED_IN_FINGERPRINT);
            profileGroupId = readIntAttribute(parser, ATTR_PROFILE_GROUP_ID,
                    UserInfo.NO_PROFILE_GROUP_ID);
            profileBadge = readIntAttribute(parser, ATTR_PROFILE_BADGE, 0);
            restrictedProfileParentId = readIntAttribute(parser,
                    ATTR_RESTRICTED_PROFILE_PARENT_ID, UserInfo.NO_PROFILE_GROUP_ID);
            String valueString = parser.getAttributeValue(null, ATTR_PARTIAL);
            if ("true".equals(valueString)) {
                partial = true;
            }
            valueString = parser.getAttributeValue(null, ATTR_GUEST_TO_REMOVE);
            if ("true".equals(valueString)) {
                guestToRemove = true;
            }

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
                    localRestrictions = UserRestrictionsUtils.readRestrictions(parser);
                } else if (TAG_DEVICE_POLICY_GLOBAL_RESTRICTIONS.equals(tag)) {
                    globalRestrictions = UserRestrictionsUtils.readRestrictions(parser);
                } else if (TAG_ACCOUNT.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        account = parser.getText();
                    }
                } else if (TAG_SEED_ACCOUNT_OPTIONS.equals(tag)) {
                    seedAccountOptions = PersistableBundle.restoreFromXml(parser);
                    persistSeedData = true;
                } else if (TAG_LAST_REQUEST_QUIET_MODE_ENABLED_CALL.equals(tag)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        lastRequestQuietModeEnabledTimestamp = Long.parseLong(parser.getText());
                    }
                }
            }
        }

        // Create the UserInfo object that gets passed around
        UserInfo userInfo = new UserInfo(id, name, iconPath, flags);
        userInfo.serialNumber = serialNumber;
        userInfo.creationTime = creationTime;
        userInfo.lastLoggedInTime = lastLoggedInTime;
        userInfo.lastLoggedInFingerprint = lastLoggedInFingerprint;
        userInfo.partial = partial;
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
        userData.setLastRequestQuietModeEnabledMillis(lastRequestQuietModeEnabledTimestamp);

        synchronized (mRestrictionsLock) {
            if (baseRestrictions != null) {
                mBaseUserRestrictions.put(id, baseRestrictions);
            }
            if (localRestrictions != null) {
                mDevicePolicyLocalUserRestrictions.put(id, localRestrictions);
            }
            if (globalRestrictions != null) {
                mDevicePolicyGlobalUserRestrictions.put(id, globalRestrictions);
            }
        }
        return userData;
    }

    private int readIntAttribute(XmlPullParser parser, String attr, int defaultValue) {
        String valueString = parser.getAttributeValue(null, attr);
        if (valueString == null) return defaultValue;
        try {
            return Integer.parseInt(valueString);
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }

    private long readLongAttribute(XmlPullParser parser, String attr, long defaultValue) {
        String valueString = parser.getAttributeValue(null, attr);
        if (valueString == null) return defaultValue;
        try {
            return Long.parseLong(valueString);
        } catch (NumberFormatException nfe) {
            return defaultValue;
        }
    }

    /**
     * Removes the app restrictions file for a specific package and user id, if it exists.
     */
    private static void cleanAppRestrictionsForPackageLAr(String pkg, @UserIdInt int userId) {
        File dir = Environment.getUserSystemDirectory(userId);
        File resFile = new File(dir, packageToRestrictionsFileName(pkg));
        if (resFile.exists()) {
            resFile.delete();
        }
    }

    @Override
    public UserInfo createProfileForUser(String name, int flags, @UserIdInt int userId,
            String[] disallowedPackages) {
        checkManageOrCreateUsersPermission(flags);
        return createUserInternal(name, flags, userId, disallowedPackages);
    }

    @Override
    public UserInfo createProfileForUserEvenWhenDisallowed(String name, int flags,
            @UserIdInt int userId, String[] disallowedPackages) {
        checkManageOrCreateUsersPermission(flags);
        return createUserInternalUnchecked(name, flags, userId, disallowedPackages);
    }

    @Override
    public boolean removeUserEvenWhenDisallowed(@UserIdInt int userId) {
        checkManageOrCreateUsersPermission("Only the system can remove users");
        return removeUserUnchecked(userId);
    }

    @Override
    public UserInfo createUser(String name, int flags) {
        checkManageOrCreateUsersPermission(flags);
        return createUserInternal(name, flags, UserHandle.USER_NULL);
    }

    private UserInfo createUserInternal(String name, int flags, int parentId) {
        return createUserInternal(name, flags, parentId, null);
    }

    private UserInfo createUserInternal(String name, int flags, int parentId,
            String[] disallowedPackages) {
        String restriction = ((flags & UserInfo.FLAG_MANAGED_PROFILE) != 0)
                ? UserManager.DISALLOW_ADD_MANAGED_PROFILE
                : UserManager.DISALLOW_ADD_USER;
        if (hasUserRestriction(restriction, UserHandle.getCallingUserId())) {
            Log.w(LOG_TAG, "Cannot add user. " + restriction + " is enabled.");
            return null;
        }
        return createUserInternalUnchecked(name, flags, parentId, disallowedPackages);
    }

    private UserInfo createUserInternalUnchecked(String name, int flags, int parentId,
            String[] disallowedPackages) {
        DeviceStorageMonitorInternal dsm = LocalServices
                .getService(DeviceStorageMonitorInternal.class);
        if (dsm.isMemoryLow()) {
            Log.w(LOG_TAG, "Cannot add user. Not enough space on disk.");
            return null;
        }
        final boolean isGuest = (flags & UserInfo.FLAG_GUEST) != 0;
        final boolean isManagedProfile = (flags & UserInfo.FLAG_MANAGED_PROFILE) != 0;
        final boolean isRestricted = (flags & UserInfo.FLAG_RESTRICTED) != 0;
        final boolean isDemo = (flags & UserInfo.FLAG_DEMO) != 0;
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
                    if (parent == null) return null;
                }
                if (isManagedProfile && !canAddMoreManagedProfiles(parentId, false)) {
                    Log.e(LOG_TAG, "Cannot add more managed profiles for user " + parentId);
                    return null;
                }
                if (!isGuest && !isManagedProfile && !isDemo && isUserLimitReached()) {
                    // If we're not adding a guest/demo user or a managed profile and the limit has
                    // been reached, cannot add a user.
                    Log.e(LOG_TAG, "Cannot add user. Maximum user limit is reached.");
                    return null;
                }
                // If we're adding a guest and there already exists one, bail.
                if (isGuest && findCurrentGuestUser() != null) {
                    Log.e(LOG_TAG, "Cannot add guest user. Guest user already exists.");
                    return null;
                }
                // In legacy mode, restricted profile's parent can only be the owner user
                if (isRestricted && !UserManager.isSplitSystemUser()
                        && (parentId != UserHandle.USER_SYSTEM)) {
                    Log.w(LOG_TAG, "Cannot add restricted profile - parent user must be owner");
                    return null;
                }
                if (isRestricted && UserManager.isSplitSystemUser()) {
                    if (parent == null) {
                        Log.w(LOG_TAG, "Cannot add restricted profile - parent user must be "
                                + "specified");
                        return null;
                    }
                    if (!parent.info.canHaveProfile()) {
                        Log.w(LOG_TAG, "Cannot add restricted profile - profiles cannot be "
                                + "created for the specified parent user id " + parentId);
                        return null;
                    }
                }

                if (!isManagedProfile) {
                    // New users cannot be system, and it's not a profile, so per-force it's FULL.
                    flags |= UserInfo.FLAG_FULL;
                }

                userId = getNextAvailableId();
                Environment.getUserSystemDirectory(userId).mkdirs();
                boolean ephemeralGuests = Resources.getSystem()
                        .getBoolean(com.android.internal.R.bool.config_guestUserEphemeral);

                synchronized (mUsersLock) {
                    // Add ephemeral flag to guests/users if required. Also inherit it from parent.
                    if ((isGuest && ephemeralGuests) || mForceEphemeralUsers
                            || (parent != null && parent.info.isEphemeral())) {
                        flags |= UserInfo.FLAG_EPHEMERAL;
                    }

                    userInfo = new UserInfo(userId, name, null, flags);
                    userInfo.serialNumber = mNextSerialNumber++;
                    long now = System.currentTimeMillis();
                    userInfo.creationTime = (now > EPOCH_PLUS_30_YEARS) ? now : 0;
                    userInfo.partial = true;
                    userInfo.lastLoggedInFingerprint = Build.FINGERPRINT;
                    if (isManagedProfile && parentId != UserHandle.USER_NULL) {
                        userInfo.profileBadge = getFreeProfileBadgeLU(parentId);
                    }
                    userData = new UserData();
                    userData.info = userInfo;
                    mUsers.put(userId, userData);
                }
                writeUserLP(userData);
                writeUserListLP();
                if (parent != null) {
                    if (isManagedProfile) {
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
            final StorageManager storage = mContext.getSystemService(StorageManager.class);
            storage.createUserKey(userId, userInfo.serialNumber, userInfo.isEphemeral());
            mUserDataPreparer.prepareUserData(userId, userInfo.serialNumber,
                    StorageManager.FLAG_STORAGE_DE | StorageManager.FLAG_STORAGE_CE);
            mPm.createNewUser(userId, disallowedPackages);
            userInfo.partial = false;
            synchronized (mPackagesLock) {
                writeUserLP(userData);
            }
            updateUserIds();
            Bundle restrictions = new Bundle();
            if (isGuest) {
                synchronized (mGuestRestrictions) {
                    restrictions.putAll(mGuestRestrictions);
                }
            }
            synchronized (mRestrictionsLock) {
                mBaseUserRestrictions.append(userId, restrictions);
            }
            mPm.onNewUserCreated(userId);
            Intent addedIntent = new Intent(Intent.ACTION_USER_ADDED);
            addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            mContext.sendBroadcastAsUser(addedIntent, UserHandle.ALL,
                    android.Manifest.permission.MANAGE_USERS);
            MetricsLogger.count(mContext, isGuest ? TRON_GUEST_CREATED
                    : (isDemo ? TRON_DEMO_CREATED : TRON_USER_CREATED), 1);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return userInfo;
    }

    @VisibleForTesting
    UserData putUserInfo(UserInfo userInfo) {
        final UserData userData = new UserData();
        userData.info = userInfo;
        synchronized (mUsers) {
            mUsers.put(userInfo.id, userData);
        }
        return userData;
    }

    @VisibleForTesting
    void removeUserInfo(@UserIdInt int userId) {
        synchronized (mUsers) {
            mUsers.remove(userId);
        }
    }

    /**
     * @hide
     */
    @Override
    public UserInfo createRestrictedProfile(String name, int parentUserId) {
        checkManageOrCreateUsersPermission("setupRestrictedProfile");
        final UserInfo user = createProfileForUser(
                name, UserInfo.FLAG_RESTRICTED, parentUserId, null);
        if (user == null) {
            return null;
        }
        long identity = Binder.clearCallingIdentity();
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
     * Find the current guest user. If the Guest user is partial,
     * then do not include it in the results as it is about to die.
     */
    private UserInfo findCurrentGuestUser() {
        synchronized (mUsersLock) {
            final int size = mUsers.size();
            for (int i = 0; i < size; i++) {
                final UserInfo user = mUsers.valueAt(i).info;
                if (user.isGuest() && !user.guestToRemove && !mRemovingUserIds.get(user.id)) {
                    return user;
                }
            }
        }
        return null;
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
            Log.w(LOG_TAG, "Cannot remove user. DISALLOW_REMOVE_USER is enabled.");
            return false;
        }

        long ident = Binder.clearCallingIdentity();
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
     * Removes a user and all data directories created for that user. This method should be called
     * after the user's processes have been terminated.
     * @param userId the user's id
     */
    @Override
    public boolean removeUser(@UserIdInt int userId) {
        Slog.i(LOG_TAG, "removeUser u" + userId);
        checkManageOrCreateUsersPermission("Only the system can remove users");

        final boolean isManagedProfile;
        synchronized (mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            isManagedProfile = userInfo != null && userInfo.isManagedProfile();
        }
        String restriction = isManagedProfile
                ? UserManager.DISALLOW_REMOVE_MANAGED_PROFILE : UserManager.DISALLOW_REMOVE_USER;
        if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean(restriction, false)) {
            Log.w(LOG_TAG, "Cannot remove user. " + restriction + " is enabled.");
            return false;
        }
        return removeUserUnchecked(userId);
    }

    private boolean removeUserUnchecked(@UserIdInt int userId) {
        long ident = Binder.clearCallingIdentity();
        try {
            final UserData userData;
            int currentUser = ActivityManager.getCurrentUser();
            if (currentUser == userId) {
                Log.w(LOG_TAG, "Current user cannot be removed.");
                return false;
            }
            synchronized (mPackagesLock) {
                synchronized (mUsersLock) {
                    userData = mUsers.get(userId);
                    if (userId == UserHandle.USER_SYSTEM) {
                        Log.e(LOG_TAG, "System user cannot be removed.");
                        return false;
                    }

                    if (userData == null) {
                        Log.e(LOG_TAG, String.format(
                                "Cannot remove user %d, invalid user id provided.", userId));
                        return false;
                    }

                    if (mRemovingUserIds.get(userId)) {
                        Log.e(LOG_TAG, String.format(
                                "User %d is already scheduled for removal.", userId));
                        return false;
                    }

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
            try {
                mAppOpsService.removeUser(userId);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, "Unable to notify AppOpsService of removing user.", e);
            }

            if (userData.info.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                    && userData.info.isManagedProfile()) {
                // Send broadcast to notify system that the user removed was a
                // managed user.
                sendProfileRemovedBroadcast(userData.info.profileGroupId, userData.info.id);
            }

            if (DBG) Slog.i(LOG_TAG, "Stopping user " + userId);
            int res;
            try {
                res = ActivityManager.getService().stopUser(userId, /* force= */ true,
                new IStopUserCallback.Stub() {
                            @Override
                            public void userStopped(int userIdParam) {
                                finishRemoveUser(userIdParam);
                            }
                            @Override
                            public void userStopAborted(int userIdParam) {
                            }
                        });
            } catch (RemoteException e) {
                Log.w(LOG_TAG, "Failed to stop user during removal.", e);
                return false;
            }
            return res == ActivityManager.USER_OP_SUCCESS;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("mUsersLock")
    @VisibleForTesting
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

    void finishRemoveUser(final @UserIdInt int userId) {
        if (DBG) Slog.i(LOG_TAG, "finishRemoveUser " + userId);
        // Let other services shutdown any activity and clean up their state before completely
        // wiping the user's system directory and removing from the user list
        long ident = Binder.clearCallingIdentity();
        try {
            Intent addedIntent = new Intent(Intent.ACTION_USER_REMOVED);
            addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            mContext.sendOrderedBroadcastAsUser(addedIntent, UserHandle.ALL,
                    android.Manifest.permission.MANAGE_USERS,

                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            if (DBG) {
                                Slog.i(LOG_TAG,
                                        "USER_REMOVED broadcast sent, cleaning up user data "
                                        + userId);
                            }
                            new Thread() {
                                @Override
                                public void run() {
                                    // Clean up any ActivityTaskManager state
                                    LocalServices.getService(ActivityTaskManagerInternal.class)
                                            .onUserStopped(userId);
                                    removeUserState(userId);
                                }
                            }.start();
                        }
                    },

                    null, Activity.RESULT_OK, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void removeUserState(final @UserIdInt int userId) {
        try {
            mContext.getSystemService(StorageManager.class).destroyUserKey(userId);
        } catch (IllegalStateException e) {
            // This may be simply because the user was partially created.
            Slog.i(LOG_TAG, "Destroying key for user " + userId + " failed, continuing anyway", e);
        }

        // Cleanup gatekeeper secure user id
        try {
            final IGateKeeperService gk = GateKeeper.getService();
            if (gk != null) {
                gk.clearSecureUserId(userId);
            }
        } catch (Exception ex) {
            Slog.w(LOG_TAG, "unable to clear GK secure user id");
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
            mDevicePolicyLocalUserRestrictions.remove(userId);
            if (mDevicePolicyGlobalUserRestrictions.get(userId) != null) {
                mDevicePolicyGlobalUserRestrictions.remove(userId);
                applyUserRestrictionsForAllUsersLR();
            }
        }
        // Update the user list
        synchronized (mPackagesLock) {
            writeUserListLP();
        }
        // Remove user file
        AtomicFile userFile = new AtomicFile(new File(mUsersDir, userId + XML_SUFFIX));
        userFile.delete();
        updateUserIds();
        if (RELEASE_DELETED_USER_ID) {
            synchronized (mUsers) {
                mRemovingUserIds.delete(userId);
            }
        }
    }

    private void sendProfileRemovedBroadcast(int parentUserId, int removedUserId) {
        Intent managedProfileIntent = new Intent(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        managedProfileIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY |
                Intent.FLAG_RECEIVER_FOREGROUND);
        managedProfileIntent.putExtra(Intent.EXTRA_USER, new UserHandle(removedUserId));
        managedProfileIntent.putExtra(Intent.EXTRA_USER_HANDLE, removedUserId);
        mContext.sendBroadcastAsUser(managedProfileIntent, new UserHandle(parentUserId), null);
    }

    @Override
    public Bundle getApplicationRestrictions(String packageName) {
        return getApplicationRestrictionsForUser(packageName, UserHandle.getCallingUserId());
    }

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
        if (restrictions != null) {
            restrictions.setDefusable(true);
        }
        synchronized (mAppRestrictionsLock) {
            if (restrictions == null || restrictions.isEmpty()) {
                cleanAppRestrictionsForPackageLAr(packageName, userId);
            } else {
                // Write the restrictions to XML
                writeApplicationRestrictionsLAr(packageName, restrictions, userId);
            }
        }

        // Notify package of changes via an intent - only sent to explicitly registered receivers.
        Intent changeIntent = new Intent(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED);
        changeIntent.setPackage(packageName);
        changeIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(changeIntent, UserHandle.of(userId));
    }

    private int getUidForPackage(String packageName) {
        long ident = Binder.clearCallingIdentity();
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
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());
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
            Log.w(LOG_TAG, "Error parsing " + restrictionsFile.getBaseFile(), e);
        } finally {
            IoUtils.closeQuietly(fis);
        }
        return restrictions;
    }

    private static void readEntry(Bundle restrictions, ArrayList<String> values,
            XmlPullParser parser) throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_ENTRY)) {
            String key = parser.getAttributeValue(null, ATTR_KEY);
            String valType = parser.getAttributeValue(null, ATTR_VALUE_TYPE);
            String multiple = parser.getAttributeValue(null, ATTR_MULTIPLE);
            if (multiple != null) {
                values.clear();
                int count = Integer.parseInt(multiple);
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

    private static Bundle readBundleEntry(XmlPullParser parser, ArrayList<String> values)
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
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, StandardCharsets.UTF_8.name());
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

    private static void writeBundle(Bundle restrictions, XmlSerializer serializer)
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
                serializer.attribute(null, ATTR_MULTIPLE, Integer.toString(values.length));
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
            throw new SecurityException("userId can only be the calling user or a managed "
                    + "profile associated with this user");
        }
        return userInfo.creationTime;
    }

    /**
     * Caches the list of user ids in an array, adjusting the array size when necessary.
     */
    private void updateUserIds() {
        int num = 0;
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                if (!mUsers.valueAt(i).info.partial) {
                    num++;
                }
            }
            final int[] newUsers = new int[num];
            int n = 0;
            for (int i = 0; i < userSize; i++) {
                if (!mUsers.valueAt(i).info.partial) {
                    newUsers[n++] = mUsers.keyAt(i);
                }
            }
            mUserIds = newUsers;
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
        final int userSerial = userInfo.serialNumber;
        // Migrate only if build fingerprints mismatch
        boolean migrateAppsData = !Build.FINGERPRINT.equals(userInfo.lastLoggedInFingerprint);
        t.traceBegin("prepareUserData");
        mUserDataPreparer.prepareUserData(userId, userSerial, StorageManager.FLAG_STORAGE_DE);
        t.traceEnd();
        t.traceBegin("reconcileAppsData");
        mPm.reconcileAppsData(userId, StorageManager.FLAG_STORAGE_DE, migrateAppsData);
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
        final int userSerial = userInfo.serialNumber;
        // Migrate only if build fingerprints mismatch
        boolean migrateAppsData = !Build.FINGERPRINT.equals(userInfo.lastLoggedInFingerprint);
        mUserDataPreparer.prepareUserData(userId, userSerial, StorageManager.FLAG_STORAGE_CE);
        mPm.reconcileAppsData(userId, StorageManager.FLAG_STORAGE_CE, migrateAppsData);
    }

    /**
     * Examine all users present on given mounted volume, and destroy data
     * belonging to users that are no longer valid, or whose user ID has been
     * recycled.
     */
    void reconcileUsers(String volumeUuid) {
        mUserDataPreparer.reconcileUsers(volumeUuid, getUsers(true /* excludeDying */));
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
        userData.info.lastLoggedInFingerprint = Build.FINGERPRINT;
        scheduleWriteUser(userData);
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

    @Override
    public void setSeedAccountData(@UserIdInt int userId, String accountName, String accountType,
            PersistableBundle accountOptions, boolean persist) {
        checkManageUsersPermission("Require MANAGE_USERS permission to set user seed data");
        synchronized (mPackagesLock) {
            final UserData userData;
            synchronized (mUsersLock) {
                userData = getUserDataLU(userId);
                if (userData == null) {
                    Slog.e(LOG_TAG, "No such user for settings seed data u=" + userId);
                    return;
                }
                userData.seedAccountName = accountName;
                userData.seedAccountType = accountType;
                userData.seedAccountOptions = accountOptions;
                userData.persistSeedData = persist;
            }
            if (persist) {
                writeUserLP(userData);
            }
        }
    }

    @Override
    public String getSeedAccountName() throws RemoteException {
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (mUsersLock) {
            UserData userData = getUserDataLU(UserHandle.getCallingUserId());
            return userData.seedAccountName;
        }
    }

    @Override
    public String getSeedAccountType() throws RemoteException {
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (mUsersLock) {
            UserData userData = getUserDataLU(UserHandle.getCallingUserId());
            return userData.seedAccountType;
        }
    }

    @Override
    public PersistableBundle getSeedAccountOptions() throws RemoteException {
        checkManageUsersPermission("Cannot get seed account information");
        synchronized (mUsersLock) {
            UserData userData = getUserDataLU(UserHandle.getCallingUserId());
            return userData.seedAccountOptions;
        }
    }

    @Override
    public void clearSeedAccountData() throws RemoteException {
        checkManageUsersPermission("Cannot clear seed account information");
        synchronized (mPackagesLock) {
            UserData userData;
            synchronized (mUsersLock) {
                userData = getUserDataLU(UserHandle.getCallingUserId());
                if (userData == null) return;
                userData.clearSeedAccountData();
            }
            writeUserLP(userData);
        }
    }

    @Override
    public boolean someUserHasSeedAccount(String accountName, String accountType)
            throws RemoteException {
        checkManageUsersPermission("Cannot check seed account information");
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                final UserData data = mUsers.valueAt(i);
                if (data.info.isInitialized()) continue;
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
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new Shell()).exec(this, in, out, err, args, callback, resultReceiver);
    }

    int onShellCommand(Shell shell, String cmd) {
        if (cmd == null) {
            return shell.handleDefaultCommands(cmd);
        }

        final PrintWriter pw = shell.getOutPrintWriter();
        try {
            switch(cmd) {
                case "list":
                    return runList(pw);
                default:
                    return shell.handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int runList(PrintWriter pw) throws RemoteException {
        final IActivityManager am = ActivityManager.getService();
        final List<UserInfo> users = getUsers(false);
        if (users == null) {
            pw.println("Error: couldn't get users");
            return 1;
        } else {
            pw.println("Users:");
            for (int i = 0; i < users.size(); i++) {
                String running = am.isUserRunning(users.get(i).id, 0) ? " running" : "";
                pw.println("\t" + users.get(i).toString() + running);
            }
            return 0;
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, pw)) return;

        long now = System.currentTimeMillis();
        final long nowRealtime = SystemClock.elapsedRealtime();

        final int currentUser = LocalServices.getService(ActivityManagerInternal.class)
                .getCurrentUserId();
        pw.print("Current user: "); pw.println(currentUser);

        StringBuilder sb = new StringBuilder();
        synchronized (mPackagesLock) {
            synchronized (mUsersLock) {
                pw.println("Users:");
                for (int i = 0; i < mUsers.size(); i++) {
                    UserData userData = mUsers.valueAt(i);
                    if (userData == null) {
                        continue;
                    }
                    UserInfo userInfo = userData.info;
                    final int userId = userInfo.id;
                    pw.print("  "); pw.print(userInfo);
                    pw.print(" serialNo="); pw.print(userInfo.serialNumber);
                    pw.print(" isPrimary="); pw.print(userInfo.isPrimary());
                    if (mRemovingUserIds.get(userId)) {
                        pw.print(" <removing> ");
                    }
                    if (userInfo.partial) {
                        pw.print(" <partial>");
                    }
                    pw.println();
                    pw.print("    Flags: "); pw.print(userInfo.flags); pw.print(" (");
                    pw.print(UserInfo.flagsToString(userInfo.flags)); pw.println(")");
                    pw.print("    State: ");
                    final int state;
                    synchronized (mUserStates) {
                        state = mUserStates.get(userId, -1);
                    }
                    pw.println(UserState.stateToString(state));
                    pw.print("    Created: ");
                    dumpTimeAgo(pw, sb, now, userInfo.creationTime);

                    pw.print("    Last logged in: ");
                    dumpTimeAgo(pw, sb, now, userInfo.lastLoggedInTime);

                    pw.print("    Last logged in fingerprint: ");
                    pw.println(userInfo.lastLoggedInFingerprint);

                    pw.print("    Start time: ");
                    dumpTimeAgo(pw, sb, nowRealtime, userData.startRealtime);

                    pw.print("    Unlock time: ");
                    dumpTimeAgo(pw, sb, nowRealtime, userData.unlockRealtime);

                    pw.print("    Has profile owner: ");
                    pw.println(mIsUserManaged.get(userId));
                    pw.println("    Restrictions:");
                    synchronized (mRestrictionsLock) {
                        UserRestrictionsUtils.dumpRestrictions(
                                pw, "      ", mBaseUserRestrictions.get(userInfo.id));
                        pw.println("    Device policy global restrictions:");
                        UserRestrictionsUtils.dumpRestrictions(
                                pw, "      ", mDevicePolicyGlobalUserRestrictions.get(userInfo.id));
                        pw.println("    Device policy local restrictions:");
                        UserRestrictionsUtils.dumpRestrictions(
                                pw, "      ", mDevicePolicyLocalUserRestrictions.get(userInfo.id));
                        pw.println("    Effective restrictions:");
                        UserRestrictionsUtils.dumpRestrictions(
                                pw, "      ", mCachedEffectiveUserRestrictions.get(userInfo.id));
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
                }
            }
            pw.println();
            pw.println("  Device owner id:" + mDeviceOwnerUserId);
            pw.println();
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
                pw.println("  Started users state: " + mUserStates);
            }
        } // synchronized (mPackagesLock)

        // Dump some capabilities
        pw.println();
        pw.println("  Max users: " + UserManager.getMaxSupportedUsers());
        pw.println("  Supports switchable users: " + UserManager.supportsMultipleUsers());
        pw.println("  All guests ephemeral: " + Resources.getSystem().getBoolean(
                com.android.internal.R.bool.config_guestUserEphemeral));
        pw.println("  Is split-system user: " + UserManager.isSplitSystemUser());
        pw.println("  Is headless-system mode: " + UserManager.isHeadlessSystemUserMode());
        pw.println("  User version: " + mUserVersion);
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
                case WRITE_USER_MSG:
                    removeMessages(WRITE_USER_MSG, msg.obj);
                    synchronized (mPackagesLock) {
                        int userId = ((UserData) msg.obj).info.id;
                        UserData userData = getUserDataNoChecks(userId);
                        if (userData != null) {
                            writeUserLP(userData);
                        }
                    }
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
        public void setDevicePolicyUserRestrictions(@UserIdInt int userId,
                @Nullable Bundle restrictions, boolean isDeviceOwner, int cameraRestrictionScope) {
            UserManagerService.this.setDevicePolicyUserRestrictionsInner(userId, restrictions,
                isDeviceOwner, cameraRestrictionScope);
        }

        @Override
        public Bundle getBaseUserRestrictions(@UserIdInt int userId) {
            synchronized (mRestrictionsLock) {
                return mBaseUserRestrictions.get(userId);
            }
        }

        @Override
        public void setBaseUserRestrictionsByDpmsForMigration(
                @UserIdInt int userId, Bundle baseRestrictions) {
            synchronized (mRestrictionsLock) {
                if (updateRestrictionsIfNeededLR(
                        userId, new Bundle(baseRestrictions), mBaseUserRestrictions)) {
                    invalidateEffectiveUserRestrictionsLR(userId);
                }
            }

            final UserData userData = getUserDataNoChecks(userId);
            synchronized (mPackagesLock) {
                if (userData != null) {
                    writeUserLP(userData);
                } else {
                    Slog.w(LOG_TAG, "UserInfo not found for " + userId);
                }
            }
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
        public void setDeviceManaged(boolean isManaged) {
            synchronized (mUsersLock) {
                mIsDeviceManaged = isManaged;
            }
        }

        @Override
        public boolean isDeviceManaged() {
            synchronized (mUsersLock) {
                return mIsDeviceManaged;
            }
        }

        @Override
        public void setUserManaged(@UserIdInt int userId, boolean isManaged) {
            synchronized (mUsersLock) {
                mIsUserManaged.put(userId, isManaged);
            }
        }

        @Override
        public boolean isUserManaged(@UserIdInt int userId) {
            synchronized (mUsersLock) {
                return mIsUserManaged.get(userId);
            }
        }

        @Override
        public void setUserIcon(@UserIdInt int userId, Bitmap bitmap) {
            long ident = Binder.clearCallingIdentity();
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
            if (UserHandle.USER_SYSTEM == ActivityManager.getCurrentUser()) {
                // Remove the non-system users straight away.
                removeNonSystemUsers();
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
                        removeNonSystemUsers();
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
        public UserInfo createUserEvenWhenDisallowed(String name, int flags,
                String[] disallowedPackages) {
            UserInfo user = createUserInternalUnchecked(name, flags, UserHandle.USER_NULL,
                    disallowedPackages);
            // Keep this in sync with UserManager.createUser
            if (user != null && !user.isAdmin() && !user.isDemo()) {
                setUserRestriction(UserManager.DISALLOW_SMS, true, user.id);
                setUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS, true, user.id);
            }
            return user;
        }

        @Override
        public boolean removeUserEvenWhenDisallowed(@UserIdInt int userId) {
            return removeUserUnchecked(userId);
        }

        @Override
        public boolean isUserRunning(@UserIdInt int userId) {
            synchronized (mUserStates) {
                return mUserStates.get(userId, -1) >= 0;
            }
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
        public boolean isUserUnlockingOrUnlocked(@UserIdInt int userId) {
            int state;
            synchronized (mUserStates) {
                state = mUserStates.get(userId, -1);
            }
            // Special case, in the stopping/shutdown state user key can still be unlocked
            if (state == UserState.STATE_STOPPING || state == UserState.STATE_SHUTDOWN) {
                return StorageManager.isUserKeyUnlocked(userId);
            }
            return (state == UserState.STATE_RUNNING_UNLOCKING)
                    || (state == UserState.STATE_RUNNING_UNLOCKED);
        }

        @Override
        public boolean isUserUnlocked(@UserIdInt int userId) {
            int state;
            synchronized (mUserStates) {
                state = mUserStates.get(userId, -1);
            }
            // Special case, in the stopping/shutdown state user key can still be unlocked
            if (state == UserState.STATE_STOPPING || state == UserState.STATE_SHUTDOWN) {
                return StorageManager.isUserKeyUnlocked(userId);
            }
            return state == UserState.STATE_RUNNING_UNLOCKED;
        }

        @Override
        public boolean isUserInitialized(@UserIdInt int userId) {
            return (getUserInfo(userId).flags & UserInfo.FLAG_INITIALIZED) != 0;
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
                if (callingUserInfo == null || callingUserInfo.isManagedProfile()) {
                    if (throwSecurityException) {
                        throw new SecurityException(
                                debugMsg + " for another profile "
                                        + targetUserId + " from " + callingUserId);
                    }
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
            synchronized (mUsersLock) {
                UserInfo profileParent = getProfileParentLU(userId);
                if (profileParent == null) {
                    return userId;
                }
                return profileParent.id;
            }
        }

        @Override
        public boolean isSettingRestrictedForUser(String setting, @UserIdInt int userId,
                String value, int callingUid) {
            return UserRestrictionsUtils.isSettingRestrictedForUser(mContext, setting, userId,
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
    }

    /* Remove all the users except of the system one. */
    private void removeNonSystemUsers() {
        ArrayList<UserInfo> usersToRemove = new ArrayList<>();
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i).info;
                if (ui.id != UserHandle.USER_SYSTEM) {
                    usersToRemove.add(ui);
                }
            }
        }
        for (UserInfo ui: usersToRemove) {
            removeUser(ui.id);
        }
    }

    private class Shell extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            return onShellCommand(this, cmd);
        }

        @Override
        public void onHelp() {
            final PrintWriter pw = getOutPrintWriter();
            pw.println("User manager (user) commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("");
            pw.println("  list");
            pw.println("    Prints all users on the system.");
        }
    }

    private static void debug(String message) {
        Log.d(LOG_TAG, message +
                (DBG_WITH_STACKTRACE ? " called at\n" + Debug.getCallers(10, "  ") : ""));
    }

    @VisibleForTesting
    static int getMaxManagedProfiles() {
        // Allow overriding max managed profiles on debuggable builds for testing
        // of multiple profiles.
        if (!Build.IS_DEBUGGABLE) {
            return MAX_MANAGED_PROFILES;
        } else {
            return SystemProperties.getInt("persist.sys.max_profiles",
                    MAX_MANAGED_PROFILES);
        }
    }

    @GuardedBy("mUsersLock")
    @VisibleForTesting
    int getFreeProfileBadgeLU(int parentUserId) {
        int maxManagedProfiles = getMaxManagedProfiles();
        boolean[] usedBadges = new boolean[maxManagedProfiles];
        final int userSize = mUsers.size();
        for (int i = 0; i < userSize; i++) {
            UserInfo ui = mUsers.valueAt(i).info;
            // Check which badge indexes are already used by this profile group.
            if (ui.isManagedProfile()
                    && ui.profileGroupId == parentUserId
                    && !mRemovingUserIds.get(ui.id)
                    && ui.profileBadge < maxManagedProfiles) {
                usedBadges[ui.profileBadge] = true;
            }
        }
        for (int i = 0; i < maxManagedProfiles; i++) {
            if (!usedBadges[i]) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Checks if the given user has a managed profile associated with it.
     * @param userId The parent user
     * @return
     */
    boolean hasManagedProfile(@UserIdInt int userId) {
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
     * Check if the calling package name matches with the calling UID, throw
     * {@link SecurityException} if not.
     */
    private void verifyCallingPackage(String callingPackage, int callingUid) {
        int packageUid = mPm.getPackageUid(callingPackage, 0,  UserHandle.getUserId(callingUid));
        if (packageUid != callingUid) {
            throw new SecurityException("Specified package " + callingPackage
                    + " does not match the calling uid " + callingUid);
        }
    }
}
