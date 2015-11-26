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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IStopUserCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IUserManager;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.UserManagerInternal;
import android.os.UserManagerInternal.UserRestrictionsListener;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsService;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.LocalServices;

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
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import libcore.io.IoUtils;

/**
 * Service for {@link UserManager}.
 *
 * Method naming convention:
 * <ul>
 * <li> Methods suffixed with "LP" should be called within the {@link #mPackagesLock} lock.
 * <li> Methods suffixed with "LR" should be called within the {@link #mRestrictionsLock} lock.
 * <li> Methods suffixed with "LU" should be called within the {@link #mUsersLock} lock.
 * </ul>
 */
public class UserManagerService extends IUserManager.Stub {
    private static final String LOG_TAG = "UserManagerService";
    static final boolean DBG = false; // DO NOT SUBMIT WITH TRUE
    private static final boolean DBG_WITH_STACKTRACE = false; // DO NOT SUBMIT WITH TRUE

    private static final String TAG_NAME = "name";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_ICON_PATH = "icon";
    private static final String ATTR_ID = "id";
    private static final String ATTR_CREATION_TIME = "created";
    private static final String ATTR_LAST_LOGGED_IN_TIME = "lastLoggedIn";
    private static final String ATTR_SERIAL_NO = "serialNumber";
    private static final String ATTR_NEXT_SERIAL_NO = "nextSerialNumber";
    private static final String ATTR_PARTIAL = "partial";
    private static final String ATTR_GUEST_TO_REMOVE = "guestToRemove";
    private static final String ATTR_USER_VERSION = "version";
    private static final String ATTR_PROFILE_GROUP_ID = "profileGroupId";
    private static final String ATTR_RESTRICTED_PROFILE_PARENT_ID = "restrictedProfileParentId";
    private static final String TAG_GUEST_RESTRICTIONS = "guestRestrictions";
    private static final String TAG_USERS = "users";
    private static final String TAG_USER = "user";
    private static final String TAG_RESTRICTIONS = "restrictions";
    private static final String TAG_DEVICE_POLICY_RESTRICTIONS = "device_policy_restrictions";
    private static final String TAG_ENTRY = "entry";
    private static final String TAG_VALUE = "value";
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

    private static final int MIN_USER_ID = 10;
    // We need to keep process uid within Integer.MAX_VALUE.
    private static final int MAX_USER_ID = Integer.MAX_VALUE / UserHandle.PER_USER_RANGE;

    private static final int USER_VERSION = 6;

    private static final long EPOCH_PLUS_30_YEARS = 30L * 365 * 24 * 60 * 60 * 1000L; // ms

    // Maximum number of managed profiles permitted per user is 1. This cannot be increased
    // without first making sure that the rest of the framework is prepared for it.
    private static final int MAX_MANAGED_PROFILES = 1;

    static final int WRITE_USER_MSG = 1;
    static final int WRITE_USER_DELAY = 2*1000;  // 2 seconds

    private static final String XATTR_SERIAL = "user.serial";

    private final Context mContext;
    private final PackageManagerService mPm;
    private final Object mPackagesLock;
    // Short-term lock for internal state, when interaction/sync with PM is not required
    private final Object mUsersLock = new Object();
    private final Object mRestrictionsLock = new Object();

    private final Handler mHandler;

    private final File mUsersDir;
    private final File mUserListFile;

    @GuardedBy("mUsersLock")
    private final SparseArray<UserInfo> mUsers = new SparseArray<>();

    /**
     * User restrictions set via UserManager.  This doesn't include restrictions set by
     * device owner / profile owners.
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
     * that should be applied to all users, including guests.
     */
    @GuardedBy("mRestrictionsLock")
    private Bundle mDevicePolicyGlobalUserRestrictions;

    /**
     * User restrictions set by {@link com.android.server.devicepolicy.DevicePolicyManagerService}
     * for each user.
     */
    @GuardedBy("mRestrictionsLock")
    private final SparseArray<Bundle> mDevicePolicyLocalUserRestrictions = new SparseArray<>();

    @GuardedBy("mGuestRestrictions")
    private final Bundle mGuestRestrictions = new Bundle();

    /**
     * Set of user IDs being actively removed. Removed IDs linger in this set
     * for several seconds to work around a VFS caching issue.
     */
    @GuardedBy("mUsersLock")
    private final SparseBooleanArray mRemovingUserIds = new SparseBooleanArray();

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

    private static UserManagerService sInstance;

    public static UserManagerService getInstance() {
        synchronized (UserManagerService.class) {
            return sInstance;
        }
    }

    @VisibleForTesting
    UserManagerService(File dataDir) {
        this(null, null, new Object(), dataDir);
    }

    /**
     * Called by package manager to create the service.  This is closely
     * associated with the package manager, and the given lock is the
     * package manager's own lock.
     */
    UserManagerService(Context context, PackageManagerService pm, Object packagesLock) {
        this(context, pm, packagesLock, Environment.getDataDirectory());
    }

    private UserManagerService(Context context, PackageManagerService pm,
            Object packagesLock, File dataDir) {
        mContext = context;
        mPm = pm;
        mPackagesLock = packagesLock;
        mHandler = new MainHandler();
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
    }

    void systemReady() {
        // Prune out any partially created/partially removed users.
        ArrayList<UserInfo> partials = new ArrayList<>();
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i);
                if ((ui.partial || ui.guestToRemove) && i != 0) {
                    partials.add(ui);
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

        onUserForeground(UserHandle.USER_SYSTEM);

        mAppOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));

        synchronized (mRestrictionsLock) {
            applyUserRestrictionsLR(UserHandle.USER_SYSTEM);
        }
    }

    @Override
    public UserInfo getPrimaryUser() {
        checkManageUsersPermission("query users");
        synchronized (mUsersLock) {
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i);
                if (ui.isPrimary() && !mRemovingUserIds.get(ui.id)) {
                    return ui;
                }
            }
        }
        return null;
    }

    @Override
    public @NonNull List<UserInfo> getUsers(boolean excludeDying) {
        checkManageUsersPermission("query users");
        synchronized (mUsersLock) {
            ArrayList<UserInfo> users = new ArrayList<UserInfo>(mUsers.size());
            final int userSize = mUsers.size();
            for (int i = 0; i < userSize; i++) {
                UserInfo ui = mUsers.valueAt(i);
                if (ui.partial) {
                    continue;
                }
                if (!excludeDying || !mRemovingUserIds.get(ui.id)) {
                    users.add(ui);
                }
            }
            return users;
        }
    }

    @Override
    public List<UserInfo> getProfiles(int userId, boolean enabledOnly) {
        if (userId != UserHandle.getCallingUserId()) {
            checkManageUsersPermission("getting profiles related to user " + userId);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mUsersLock) {
                return getProfilesLU(userId, enabledOnly);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** Assume permissions already checked and caller's identity cleared */
    private List<UserInfo> getProfilesLU(int userId, boolean enabledOnly) {
        UserInfo user = getUserInfoLU(userId);
        ArrayList<UserInfo> users = new ArrayList<UserInfo>(mUsers.size());
        if (user == null) {
            // Probably a dying user
            return users;
        }
        final int userSize = mUsers.size();
        for (int i = 0; i < userSize; i++) {
            UserInfo profile = mUsers.valueAt(i);
            if (!isProfileOf(user, profile)) {
                continue;
            }
            if (enabledOnly && !profile.isEnabled()) {
                continue;
            }
            if (mRemovingUserIds.get(profile.id)) {
                continue;
            }
            users.add(profile);
        }
        return users;
    }

    @Override
    public int getCredentialOwnerProfile(int userHandle) {
        checkManageUsersPermission("get the credential owner");
        if (!StorageManager.isFileBasedEncryptionEnabled()) {
            synchronized (mUsersLock) {
                UserInfo profileParent = getProfileParentLU(userHandle);
                if (profileParent != null) {
                    return profileParent.id;
                }
            }
        }

        return userHandle;
    }

    @Override
    public boolean isSameProfileGroup(int userId, int otherUserId) {
        if (userId == otherUserId) return true;
        checkManageUsersPermission("check if in the same profile group");
        synchronized (mPackagesLock) {
            return isSameProfileGroupLP(userId, otherUserId);
        }
    }

    private boolean isSameProfileGroupLP(int userId, int otherUserId) {
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
    public UserInfo getProfileParent(int userHandle) {
        checkManageUsersPermission("get the profile parent");
        synchronized (mUsersLock) {
            return getProfileParentLU(userHandle);
        }
    }

    private UserInfo getProfileParentLU(int userHandle) {
        UserInfo profile = getUserInfoLU(userHandle);
        if (profile == null) {
            return null;
        }
        int parentUserId = profile.profileGroupId;
        if (parentUserId == UserInfo.NO_PROFILE_GROUP_ID) {
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

    @Override
    public void setUserEnabled(int userId) {
        checkManageUsersPermission("enable user");
        synchronized (mPackagesLock) {
            UserInfo info;
            synchronized (mUsersLock) {
                info = getUserInfoLU(userId);
            }
            if (info != null && !info.isEnabled()) {
                info.flags ^= UserInfo.FLAG_DISABLED;
                writeUserLP(info);
            }
        }
    }

    @Override
    public UserInfo getUserInfo(int userId) {
        checkManageUsersPermission("query user");
        synchronized (mUsersLock) {
            return getUserInfoLU(userId);
        }
    }

    @Override
    public boolean isRestricted() {
        synchronized (mUsersLock) {
            return getUserInfoLU(UserHandle.getCallingUserId()).isRestricted();
        }
    }

    @Override
    public boolean canHaveRestrictedProfile(int userId) {
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

    /*
     * Should be locked on mUsers before calling this.
     */
    private UserInfo getUserInfoLU(int userId) {
        UserInfo ui = mUsers.get(userId);
        // If it is partial and not in the process of being removed, return as unknown user.
        if (ui != null && ui.partial && !mRemovingUserIds.get(userId)) {
            Slog.w(LOG_TAG, "getUserInfo: unknown user #" + userId);
            return null;
        }
        return ui;
    }

    /**
     * Obtains {@link #mUsersLock} and return UserInfo from mUsers.
     * <p>No permissions checking or any addition checks are made</p>
     */
    private UserInfo getUserInfoNoChecks(int userId) {
        synchronized (mUsersLock) {
            return mUsers.get(userId);
        }
    }

    /** Called by PackageManagerService */
    public boolean exists(int userId) {
        return getUserInfoNoChecks(userId) != null;
    }

    @Override
    public void setUserName(int userId, String name) {
        checkManageUsersPermission("rename users");
        boolean changed = false;
        synchronized (mPackagesLock) {
            UserInfo info = getUserInfoNoChecks(userId);
            if (info == null || info.partial) {
                Slog.w(LOG_TAG, "setUserName: unknown user #" + userId);
                return;
            }
            if (name != null && !name.equals(info.name)) {
                info.name = name;
                writeUserLP(info);
                changed = true;
            }
        }
        if (changed) {
            sendUserInfoChangedBroadcast(userId);
        }
    }

    @Override
    public void setUserIcon(int userId, Bitmap bitmap) {
        checkManageUsersPermission("update users");
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mPackagesLock) {
                UserInfo info = getUserInfoNoChecks(userId);
                if (info == null || info.partial) {
                    Slog.w(LOG_TAG, "setUserIcon: unknown user #" + userId);
                    return;
                }
                writeBitmapLP(info, bitmap);
                writeUserLP(info);
            }
            sendUserInfoChangedBroadcast(userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void sendUserInfoChangedBroadcast(int userId) {
        Intent changedIntent = new Intent(Intent.ACTION_USER_INFO_CHANGED);
        changedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        changedIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        mContext.sendBroadcastAsUser(changedIntent, UserHandle.ALL);
    }

    @Override
    public ParcelFileDescriptor getUserIcon(int userId) {
        String iconPath;
        synchronized (mPackagesLock) {
            UserInfo info = getUserInfoNoChecks(userId);
            if (info == null || info.partial) {
                Slog.w(LOG_TAG, "getUserIcon: unknown user #" + userId);
                return null;
            }
            int callingGroupId = getUserInfoNoChecks(UserHandle.getCallingUserId()).profileGroupId;
            if (callingGroupId == UserInfo.NO_PROFILE_GROUP_ID
                    || callingGroupId != info.profileGroupId) {
                checkManageUsersPermission("get the icon of a user who is not related");
            }
            if (info.iconPath == null) {
                return null;
            }
            iconPath = info.iconPath;
        }

        try {
            return ParcelFileDescriptor.open(
                    new File(iconPath), ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, "Couldn't find icon file", e);
        }
        return null;
    }

    public void makeInitialized(int userId) {
        checkManageUsersPermission("makeInitialized");
        boolean scheduleWriteUser = false;
        UserInfo info;
        synchronized (mUsersLock) {
            info = mUsers.get(userId);
            if (info == null || info.partial) {
                Slog.w(LOG_TAG, "makeInitialized: unknown user #" + userId);
                return;
            }
            if ((info.flags & UserInfo.FLAG_INITIALIZED) == 0) {
                info.flags |= UserInfo.FLAG_INITIALIZED;
                scheduleWriteUser = true;
            }
        }
        if (scheduleWriteUser) {
            scheduleWriteUser(info);
        }
    }

    /**
     * If default guest restrictions haven't been initialized yet, add the basic
     * restrictions.
     */
    private void initDefaultGuestRestrictions() {
        synchronized (mGuestRestrictions) {
            if (mGuestRestrictions.isEmpty()) {
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
     * See {@link UserManagerInternal#setDevicePolicyUserRestrictions(int, Bundle, Bundle)}
     */
    void setDevicePolicyUserRestrictions(int userId, @NonNull Bundle local,
            @Nullable Bundle global) {
        Preconditions.checkNotNull(local);
        boolean globalChanged = false;
        boolean localChanged;
        synchronized (mRestrictionsLock) {
            if (global != null) {
                // Update global.
                globalChanged = !UserRestrictionsUtils.areEqual(
                        mDevicePolicyGlobalUserRestrictions, global);
                if (globalChanged) {
                    mDevicePolicyGlobalUserRestrictions = global;
                }
            }
            {
                // Update local.
                final Bundle prev = mDevicePolicyLocalUserRestrictions.get(userId);
                localChanged = !UserRestrictionsUtils.areEqual(prev, local);
                if (localChanged) {
                    mDevicePolicyLocalUserRestrictions.put(userId, local);
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
            if (globalChanged) {
                writeUserListLP();
            }
            if (localChanged) {
                writeUserLP(getUserInfoNoChecks(userId));
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

    @GuardedBy("mRestrictionsLock")
    private Bundle computeEffectiveUserRestrictionsLR(int userId) {
        final Bundle baseRestrictions =
                UserRestrictionsUtils.nonNull(mBaseUserRestrictions.get(userId));
        final Bundle global = mDevicePolicyGlobalUserRestrictions;
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
    private void invalidateEffectiveUserRestrictionsLR(int userId) {
        if (DBG) {
            Log.d(LOG_TAG, "invalidateEffectiveUserRestrictions userId=" + userId);
        }
        mCachedEffectiveUserRestrictions.remove(userId);
    }

    private Bundle getEffectiveUserRestrictions(int userId) {
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
    public boolean hasUserRestriction(String restrictionKey, int userId) {
        Bundle restrictions = getEffectiveUserRestrictions(userId);
        return restrictions != null && restrictions.getBoolean(restrictionKey);
    }

    /**
     * @return UserRestrictions that are in effect currently.  This always returns a new
     * {@link Bundle}.
     */
    @Override
    public Bundle getUserRestrictions(int userId) {
        return UserRestrictionsUtils.clone(getEffectiveUserRestrictions(userId));
    }

    @Override
    public boolean hasBaseUserRestriction(String restrictionKey, int userId) {
        checkManageUsersPermission("hasBaseUserRestriction");
        synchronized (mRestrictionsLock) {
            Bundle bundle = mBaseUserRestrictions.get(userId);
            return (bundle != null && bundle.getBoolean(restrictionKey, false));
        }
    }

    @Override
    public void setUserRestriction(String key, boolean value, int userId) {
        checkManageUsersPermission("setUserRestriction");
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
     * @param newRestrictions User restrictions to set.
     *      If null, will not update user restrictions and only does the propagation.
     * @param userId target user ID.
     */
    @GuardedBy("mRestrictionsLock")
    private void updateUserRestrictionsInternalLR(
            @Nullable Bundle newRestrictions, int userId) {

        final Bundle prevAppliedRestrictions = UserRestrictionsUtils.nonNull(
                mAppliedUserRestrictions.get(userId));

        // Update base restrictions.
        if (newRestrictions != null) {
            // If newRestrictions == the current one, it's probably a bug.
            final Bundle prevBaseRestrictions = mBaseUserRestrictions.get(userId);

            Preconditions.checkState(prevBaseRestrictions != newRestrictions);
            Preconditions.checkState(mCachedEffectiveUserRestrictions.get(userId)
                    != newRestrictions);

            if (!UserRestrictionsUtils.areEqual(prevBaseRestrictions, newRestrictions)) {
                mBaseUserRestrictions.put(userId, newRestrictions);
                scheduleWriteUser(getUserInfoNoChecks(userId));
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
            final long token = Binder.clearCallingIdentity();
            try {
                mAppOpsService.setUserRestrictions(effective, userId);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, "Unable to notify AppOpsService of UserRestrictions");
            } finally {
                Binder.restoreCallingIdentity(token);
            }
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
                synchronized (mRestrictionsLock) {
                    UserRestrictionsUtils.applyUserRestrictionsLR(
                            mContext, userId, newRestrictionsFinal, prevRestrictionsFinal);
                }

                final UserRestrictionsListener[] listeners;
                synchronized (mUserRestrictionsListeners) {
                    listeners = new UserRestrictionsListener[mUserRestrictionsListeners.size()];
                    mUserRestrictionsListeners.toArray(listeners);
                }
                for (int i = 0; i < listeners.length; i++) {
                    listeners[i].onUserRestrictionsChanged(userId,
                            newRestrictionsFinal, prevRestrictionsFinal);
                }
            }
        });
    }

    // Package private for the inner class.
    void applyUserRestrictionsLR(int userId) {
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

        // We don't want to call into ActivityManagerNative while taking a lock, so we'll call
        // it on a handler.
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                // Then get the list of running users.
                final int[] runningUsers;
                try {
                    runningUsers = ActivityManagerNative.getDefault().getRunningUserIds();
                } catch (RemoteException e) {
                    Log.w(LOG_TAG, "Unable to access ActivityManagerNative");
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
    public boolean canAddMoreManagedProfiles(int userId, boolean allowedToRemoveOne) {
        checkManageUsersPermission("check if more managed profiles can be added.");
        if (ActivityManager.isLowRamDeviceStatic()) {
            return false;
        }
        if (!mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_MANAGED_USERS)) {
            return false;
        }
        // Limit number of managed profiles that can be created
        final int managedProfilesCount = getProfiles(userId, true).size() - 1;
        final int profilesRemovedCount = managedProfilesCount > 0 && allowedToRemoveOne ? 1 : 0;
        if (managedProfilesCount - profilesRemovedCount >= MAX_MANAGED_PROFILES) {
            return false;
        }
        synchronized(mUsersLock) {
            UserInfo userInfo = getUserInfoLU(userId);
            if (!userInfo.canHaveProfile()) {
                return false;
            }
            int usersCountAfterRemoving = getAliveUsersExcludingGuestsCountLU()
                    - profilesRemovedCount;
            // We allow creating a managed profile in the special case where there is only one user.
            return usersCountAfterRemoving  == 1
                    || usersCountAfterRemoving < UserManager.getMaxSupportedUsers();
        }
    }

    private int getAliveUsersExcludingGuestsCountLU() {
        int aliveUserCount = 0;
        final int totalUserCount = mUsers.size();
        // Skip over users being removed
        for (int i = 0; i < totalUserCount; i++) {
            UserInfo user = mUsers.valueAt(i);
            if (!mRemovingUserIds.get(user.id)
                    && !user.isGuest() && !user.partial) {
                aliveUserCount++;
            }
        }
        return aliveUserCount;
    }

    /**
     * Enforces that only the system UID or root's UID or apps that have the
     * {@link android.Manifest.permission#MANAGE_USERS MANAGE_USERS}
     * permission can make certain calls to the UserManager.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private static final void checkManageUsersPermission(String message) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != 0
                && ActivityManager.checkComponentPermission(
                        android.Manifest.permission.MANAGE_USERS,
                        uid, -1, true) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("You need MANAGE_USERS permission to: " + message);
        }
    }

    private static void checkSystemOrRoot(String message) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != 0) {
            throw new SecurityException("Only system may call: " + message);
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
                    && tmp.renameTo(file)) {
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
                ;
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

            final Bundle newDevicePolicyGlobalUserRestrictions = new Bundle();

            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    final String name = parser.getName();
                    if (name.equals(TAG_USER)) {
                        String id = parser.getAttributeValue(null, ATTR_ID);
                        UserInfo user = readUserLP(Integer.parseInt(id));

                        if (user != null) {
                            synchronized (mUsersLock) {
                                mUsers.put(user.id, user);
                                if (mNextSerialNumber < 0 || mNextSerialNumber <= user.id) {
                                    mNextSerialNumber = user.id + 1;
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
                                } else if (parser.getName().equals(TAG_DEVICE_POLICY_RESTRICTIONS)
                                        ) {
                                    UserRestrictionsUtils.readRestrictions(parser,
                                            newDevicePolicyGlobalUserRestrictions);
                                }
                                break;
                            }
                        }
                    }
                }
            }
            synchronized (mRestrictionsLock) {
                mDevicePolicyGlobalUserRestrictions = newDevicePolicyGlobalUserRestrictions;
            }
            updateUserIds();
            upgradeIfNecessaryLP();
        } catch (IOException | XmlPullParserException e) {
            fallbackToSingleUserLP();
        } finally {
            IoUtils.closeQuietly(fis);
        }
    }

    /**
     * Upgrade steps between versions, either for fixing bugs or changing the data format.
     */
    private void upgradeIfNecessaryLP() {
        final int originalVersion = mUserVersion;
        int userVersion = mUserVersion;
        if (userVersion < 1) {
            // Assign a proper name for the owner, if not initialized correctly before
            UserInfo user = getUserInfoNoChecks(UserHandle.USER_SYSTEM);
            if ("Primary".equals(user.name)) {
                user.name = mContext.getResources().getString(com.android.internal.R.string.owner_name);
                scheduleWriteUser(user);
            }
            userVersion = 1;
        }

        if (userVersion < 2) {
            // Owner should be marked as initialized
            UserInfo user = getUserInfoNoChecks(UserHandle.USER_SYSTEM);
            if ((user.flags & UserInfo.FLAG_INITIALIZED) == 0) {
                user.flags |= UserInfo.FLAG_INITIALIZED;
                scheduleWriteUser(user);
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
                    UserInfo user = mUsers.valueAt(i);
                    // In non-split mode, only user 0 can have restricted profiles
                    if (!splitSystemUser && user.isRestricted()
                            && (user.restrictedProfileParentId == UserInfo.NO_PROFILE_GROUP_ID)) {
                        user.restrictedProfileParentId = UserHandle.USER_SYSTEM;
                        scheduleWriteUser(user);
                    }
                }
            }
            userVersion = 6;
        }

        if (userVersion < USER_VERSION) {
            Slog.w(LOG_TAG, "User version " + mUserVersion + " didn't upgrade as expected to "
                    + USER_VERSION);
        } else {
            mUserVersion = userVersion;

            if (originalVersion < mUserVersion) {
                writeUserListLP();
            }
        }
    }

    private void fallbackToSingleUserLP() {
        int flags = UserInfo.FLAG_INITIALIZED;
        // In split system user mode, the admin and primary flags are assigned to the first human
        // user.
        if (!UserManager.isSplitSystemUser()) {
            flags |= UserInfo.FLAG_ADMIN | UserInfo.FLAG_PRIMARY;
        }
        // Create the system user
        UserInfo system = new UserInfo(UserHandle.USER_SYSTEM,
                mContext.getResources().getString(com.android.internal.R.string.owner_name), null,
                flags);
        synchronized (mUsersLock) {
            mUsers.put(system.id, system);
        }
        mNextSerialNumber = MIN_USER_ID;
        mUserVersion = USER_VERSION;

        Bundle restrictions = new Bundle();
        synchronized (mRestrictionsLock) {
            mBaseUserRestrictions.append(UserHandle.USER_SYSTEM, restrictions);
        }

        updateUserIds();
        initDefaultGuestRestrictions();

        writeUserListLP();
        writeUserLP(system);
    }

    private void scheduleWriteUser(UserInfo userInfo) {
        if (DBG) {
            debug("scheduleWriteUser");
        }
        // No need to wrap it within a lock -- worst case, we'll just post the same message
        // twice.
        if (!mHandler.hasMessages(WRITE_USER_MSG, userInfo)) {
            Message msg = mHandler.obtainMessage(WRITE_USER_MSG, userInfo);
            mHandler.sendMessageDelayed(msg, WRITE_USER_DELAY);
        }
    }

    /*
     * Writes the user file in this format:
     *
     * <user flags="20039023" id="0">
     *   <name>Primary</name>
     * </user>
     */
    private void writeUserLP(UserInfo userInfo) {
        if (DBG) {
            debug("writeUserLP " + userInfo);
        }
        FileOutputStream fos = null;
        AtomicFile userFile = new AtomicFile(new File(mUsersDir, userInfo.id + XML_SUFFIX));
        try {
            fos = userFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, StandardCharsets.UTF_8.name());
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_USER);
            serializer.attribute(null, ATTR_ID, Integer.toString(userInfo.id));
            serializer.attribute(null, ATTR_SERIAL_NO, Integer.toString(userInfo.serialNumber));
            serializer.attribute(null, ATTR_FLAGS, Integer.toString(userInfo.flags));
            serializer.attribute(null, ATTR_CREATION_TIME, Long.toString(userInfo.creationTime));
            serializer.attribute(null, ATTR_LAST_LOGGED_IN_TIME,
                    Long.toString(userInfo.lastLoggedInTime));
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
            if (userInfo.restrictedProfileParentId != UserInfo.NO_PROFILE_GROUP_ID) {
                serializer.attribute(null, ATTR_RESTRICTED_PROFILE_PARENT_ID,
                        Integer.toString(userInfo.restrictedProfileParentId));
            }
            serializer.startTag(null, TAG_NAME);
            serializer.text(userInfo.name);
            serializer.endTag(null, TAG_NAME);
            synchronized (mRestrictionsLock) {
                UserRestrictionsUtils.writeRestrictions(serializer,
                        mBaseUserRestrictions.get(userInfo.id), TAG_RESTRICTIONS);
                UserRestrictionsUtils.writeRestrictions(serializer,
                        mDevicePolicyLocalUserRestrictions.get(userInfo.id),
                        TAG_DEVICE_POLICY_RESTRICTIONS);
            }
            serializer.endTag(null, TAG_USER);

            serializer.endDocument();
            userFile.finishWrite(fos);
        } catch (Exception ioe) {
            Slog.e(LOG_TAG, "Error writing user info " + userInfo.id + "\n" + ioe);
            userFile.failWrite(fos);
        }
    }

    /*
     * Writes the user list file in this format:
     *
     * <users nextSerialNumber="3">
     *   <user id="0"></user>
     *   <user id="2"></user>
     * </users>
     */
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
            synchronized (mRestrictionsLock) {
                UserRestrictionsUtils.writeRestrictions(serializer,
                        mDevicePolicyGlobalUserRestrictions, TAG_DEVICE_POLICY_RESTRICTIONS);
            }
            int[] userIdsToWrite;
            synchronized (mUsersLock) {
                userIdsToWrite = new int[mUsers.size()];
                for (int i = 0; i < userIdsToWrite.length; i++) {
                    UserInfo user = mUsers.valueAt(i);
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

    private UserInfo readUserLP(int id) {
        int flags = 0;
        int serialNumber = id;
        String name = null;
        String iconPath = null;
        long creationTime = 0L;
        long lastLoggedInTime = 0L;
        int profileGroupId = UserInfo.NO_PROFILE_GROUP_ID;
        int restrictedProfileParentId = UserInfo.NO_PROFILE_GROUP_ID;
        boolean partial = false;
        boolean guestToRemove = false;
        Bundle baseRestrictions = new Bundle();
        Bundle localRestrictions = new Bundle();

        FileInputStream fis = null;
        try {
            AtomicFile userFile =
                    new AtomicFile(new File(mUsersDir, Integer.toString(id) + XML_SUFFIX));
            fis = userFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
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
                profileGroupId = readIntAttribute(parser, ATTR_PROFILE_GROUP_ID,
                        UserInfo.NO_PROFILE_GROUP_ID);
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
                        UserRestrictionsUtils.readRestrictions(parser, baseRestrictions);
                    } else if (TAG_DEVICE_POLICY_RESTRICTIONS.equals(tag)) {
                        UserRestrictionsUtils.readRestrictions(parser, localRestrictions);
                    }
                }
            }

            UserInfo userInfo = new UserInfo(id, name, iconPath, flags);
            userInfo.serialNumber = serialNumber;
            userInfo.creationTime = creationTime;
            userInfo.lastLoggedInTime = lastLoggedInTime;
            userInfo.partial = partial;
            userInfo.guestToRemove = guestToRemove;
            userInfo.profileGroupId = profileGroupId;
            userInfo.restrictedProfileParentId = restrictedProfileParentId;
            synchronized (mRestrictionsLock) {
                mBaseUserRestrictions.put(id, baseRestrictions);
                mDevicePolicyLocalUserRestrictions.put(id, localRestrictions);
            }
            return userInfo;

        } catch (IOException ioe) {
        } catch (XmlPullParserException pe) {
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }
        return null;
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

    private boolean isPackageInstalled(String pkg, int userId) {
        final ApplicationInfo info = mPm.getApplicationInfo(pkg,
                PackageManager.GET_UNINSTALLED_PACKAGES,
                userId);
        if (info == null || (info.flags&ApplicationInfo.FLAG_INSTALLED) == 0) {
            return false;
        }
        return true;
    }

    /**
     * Removes all the restrictions files (res_<packagename>) for a given user.
     * Does not do any permissions checking.
     */
    private void cleanAppRestrictions(int userId) {
        synchronized (mPackagesLock) {
            File dir = Environment.getUserSystemDirectory(userId);
            String[] files = dir.list();
            if (files == null) return;
            for (String fileName : files) {
                if (fileName.startsWith(RESTRICTIONS_FILE_PREFIX)) {
                    File resFile = new File(dir, fileName);
                    if (resFile.exists()) {
                        resFile.delete();
                    }
                }
            }
        }
    }

    /**
     * Removes the app restrictions file for a specific package and user id, if it exists.
     */
    private void cleanAppRestrictionsForPackage(String pkg, int userId) {
        synchronized (mPackagesLock) {
            File dir = Environment.getUserSystemDirectory(userId);
            File resFile = new File(dir, packageToRestrictionsFileName(pkg));
            if (resFile.exists()) {
                resFile.delete();
            }
        }
    }

    @Override
    public UserInfo createProfileForUser(String name, int flags, int userId) {
        checkManageUsersPermission("Only the system can create users");
        return createUserInternal(name, flags, userId);
    }

    @Override
    public UserInfo createUser(String name, int flags) {
        checkManageUsersPermission("Only the system can create users");
        return createUserInternal(name, flags, UserHandle.USER_NULL);
    }

    private UserInfo createUserInternal(String name, int flags, int parentId) {
        if (hasUserRestriction(UserManager.DISALLOW_ADD_USER, UserHandle.getCallingUserId())) {
            Log.w(LOG_TAG, "Cannot add user. DISALLOW_ADD_USER is enabled.");
            return null;
        }
        if (ActivityManager.isLowRamDeviceStatic()) {
            return null;
        }
        final boolean isGuest = (flags & UserInfo.FLAG_GUEST) != 0;
        final boolean isManagedProfile = (flags & UserInfo.FLAG_MANAGED_PROFILE) != 0;
        final boolean isRestricted = (flags & UserInfo.FLAG_RESTRICTED) != 0;
        final long ident = Binder.clearCallingIdentity();
        UserInfo userInfo;
        final int userId;
        try {
            synchronized (mPackagesLock) {
                UserInfo parent = null;
                if (parentId != UserHandle.USER_NULL) {
                    synchronized (mUsersLock) {
                        parent = getUserInfoLU(parentId);
                    }
                    if (parent == null) return null;
                }
                if (isManagedProfile && !canAddMoreManagedProfiles(parentId, false)) {
                    Log.e(LOG_TAG, "Cannot add more managed profiles for user " + parentId);
                    return null;
                }
                if (!isGuest && !isManagedProfile && isUserLimitReached()) {
                    // If we're not adding a guest user or a managed profile and the limit has
                    // been reached, cannot add a user.
                    return null;
                }
                // If we're adding a guest and there already exists one, bail.
                if (isGuest && findCurrentGuestUser() != null) {
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
                    if (!parent.canHaveProfile()) {
                        Log.w(LOG_TAG, "Cannot add restricted profile - profiles cannot be "
                                + "created for the specified parent user id " + parentId);
                        return null;
                    }
                }
                // In split system user mode, we assign the first human user the primary flag.
                // And if there is no device owner, we also assign the admin flag to primary user.
                if (UserManager.isSplitSystemUser()
                        && !isGuest && !isManagedProfile && getPrimaryUser() == null) {
                    flags |= UserInfo.FLAG_PRIMARY;
                    synchronized (mUsersLock) {
                        if (!mIsDeviceManaged) {
                            flags |= UserInfo.FLAG_ADMIN;
                        }
                    }
                }
                userId = getNextAvailableId();
                userInfo = new UserInfo(userId, name, null, flags);
                userInfo.serialNumber = mNextSerialNumber++;
                long now = System.currentTimeMillis();
                userInfo.creationTime = (now > EPOCH_PLUS_30_YEARS) ? now : 0;
                userInfo.partial = true;
                Environment.getUserSystemDirectory(userInfo.id).mkdirs();
                synchronized (mUsersLock) {
                    mUsers.put(userId, userInfo);
                }
                writeUserListLP();
                if (parent != null) {
                    if (isManagedProfile) {
                        if (parent.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID) {
                            parent.profileGroupId = parent.id;
                            writeUserLP(parent);
                        }
                        userInfo.profileGroupId = parent.profileGroupId;
                    } else if (isRestricted) {
                        if (parent.restrictedProfileParentId == UserInfo.NO_PROFILE_GROUP_ID) {
                            parent.restrictedProfileParentId = parent.id;
                            writeUserLP(parent);
                        }
                        userInfo.restrictedProfileParentId = parent.restrictedProfileParentId;
                    }
                }
            }
            final StorageManager storage = mContext.getSystemService(StorageManager.class);
            storage.createUserKey(userId, userInfo.serialNumber);
            for (VolumeInfo vol : storage.getWritablePrivateVolumes()) {
                final String volumeUuid = vol.getFsUuid();
                try {
                    final File userDir = Environment.getDataUserDirectory(volumeUuid, userId);
                    storage.prepareUserStorage(volumeUuid, userId, userInfo.serialNumber);
                    enforceSerialNumber(userDir, userInfo.serialNumber);
                } catch (IOException e) {
                    Log.wtf(LOG_TAG, "Failed to create user directory on " + volumeUuid, e);
                }
            }
            mPm.createNewUser(userId);
            userInfo.partial = false;
            synchronized (mPackagesLock) {
                writeUserLP(userInfo);
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
            mPm.newUserCreated(userId);
            Intent addedIntent = new Intent(Intent.ACTION_USER_ADDED);
            addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            mContext.sendBroadcastAsUser(addedIntent, UserHandle.ALL,
                    android.Manifest.permission.MANAGE_USERS);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return userInfo;
    }

    /**
     * @hide
     */
    public UserInfo createRestrictedProfile(String name, int parentUserId) {
        checkManageUsersPermission("setupRestrictedProfile");
        final UserInfo user = createProfileForUser(name, UserInfo.FLAG_RESTRICTED, parentUserId);
        if (user == null) {
            return null;
        }
        setUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS, true, user.id);
        // Change the setting before applying the DISALLOW_SHARE_LOCATION restriction, otherwise
        // the putIntForUser() will fail.
        android.provider.Settings.Secure.putIntForUser(mContext.getContentResolver(),
                android.provider.Settings.Secure.LOCATION_MODE,
                android.provider.Settings.Secure.LOCATION_MODE_OFF, user.id);
        setUserRestriction(UserManager.DISALLOW_SHARE_LOCATION, true, user.id);
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
                final UserInfo user = mUsers.valueAt(i);
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
     * @param userHandle the userid of the current guest
     * @return whether the user could be marked for deletion
     */
    public boolean markGuestForDeletion(int userHandle) {
        checkManageUsersPermission("Only the system can remove users");
        if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean(
                UserManager.DISALLOW_REMOVE_USER, false)) {
            Log.w(LOG_TAG, "Cannot remove user. DISALLOW_REMOVE_USER is enabled.");
            return false;
        }

        long ident = Binder.clearCallingIdentity();
        try {
            final UserInfo user;
            synchronized (mPackagesLock) {
                synchronized (mUsersLock) {
                    user = mUsers.get(userHandle);
                    if (userHandle == 0 || user == null || mRemovingUserIds.get(userHandle)) {
                        return false;
                    }
                }
                if (!user.isGuest()) {
                    return false;
                }
                // We set this to a guest user that is to be removed. This is a temporary state
                // where we are allowed to add new Guest users, even if this one is still not
                // removed. This user will still show up in getUserInfo() calls.
                // If we don't get around to removing this Guest user, it will be purged on next
                // startup.
                user.guestToRemove = true;
                // Mark it as disabled, so that it isn't returned any more when
                // profiles are queried.
                user.flags |= UserInfo.FLAG_DISABLED;
                writeUserLP(user);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return true;
    }

    /**
     * Removes a user and all data directories created for that user. This method should be called
     * after the user's processes have been terminated.
     * @param userHandle the user's id
     */
    public boolean removeUser(int userHandle) {
        checkManageUsersPermission("Only the system can remove users");
        if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean(
                UserManager.DISALLOW_REMOVE_USER, false)) {
            Log.w(LOG_TAG, "Cannot remove user. DISALLOW_REMOVE_USER is enabled.");
            return false;
        }

        long ident = Binder.clearCallingIdentity();
        try {
            final UserInfo user;
            int currentUser = ActivityManager.getCurrentUser();
            if (currentUser == userHandle) {
                Log.w(LOG_TAG, "Current user cannot be removed");
                return false;
            }
            synchronized (mPackagesLock) {
                synchronized (mUsersLock) {
                    user = mUsers.get(userHandle);
                    if (userHandle == 0 || user == null || mRemovingUserIds.get(userHandle)) {
                        return false;
                    }

                    // We remember deleted user IDs to prevent them from being
                    // reused during the current boot; they can still be reused
                    // after a reboot.
                    mRemovingUserIds.put(userHandle, true);
                }

                try {
                    mAppOpsService.removeUser(userHandle);
                } catch (RemoteException e) {
                    Log.w(LOG_TAG, "Unable to notify AppOpsService of removing user", e);
                }
                // Set this to a partially created user, so that the user will be purged
                // on next startup, in case the runtime stops now before stopping and
                // removing the user completely.
                user.partial = true;
                // Mark it as disabled, so that it isn't returned any more when
                // profiles are queried.
                user.flags |= UserInfo.FLAG_DISABLED;
                writeUserLP(user);
            }

            if (user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                    && user.isManagedProfile()) {
                // Send broadcast to notify system that the user removed was a
                // managed user.
                sendProfileRemovedBroadcast(user.profileGroupId, user.id);
            }

            if (DBG) Slog.i(LOG_TAG, "Stopping user " + userHandle);
            int res;
            try {
                res = ActivityManagerNative.getDefault().stopUser(userHandle,
                        new IStopUserCallback.Stub() {
                            @Override
                            public void userStopped(int userId) {
                                finishRemoveUser(userId);
                            }
                            @Override
                            public void userStopAborted(int userId) {
                            }
                        });
            } catch (RemoteException e) {
                return false;
            }
            return res == ActivityManager.USER_OP_SUCCESS;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void finishRemoveUser(final int userHandle) {
        if (DBG) Slog.i(LOG_TAG, "finishRemoveUser " + userHandle);
        // Let other services shutdown any activity and clean up their state before completely
        // wiping the user's system directory and removing from the user list
        long ident = Binder.clearCallingIdentity();
        try {
            Intent addedIntent = new Intent(Intent.ACTION_USER_REMOVED);
            addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userHandle);
            mContext.sendOrderedBroadcastAsUser(addedIntent, UserHandle.ALL,
                    android.Manifest.permission.MANAGE_USERS,

                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            if (DBG) {
                                Slog.i(LOG_TAG,
                                        "USER_REMOVED broadcast sent, cleaning up user data "
                                        + userHandle);
                            }
                            new Thread() {
                                public void run() {
                                    // Clean up any ActivityManager state
                                    LocalServices.getService(ActivityManagerInternal.class)
                                            .onUserRemoved(userHandle);
                                    removeUserState(userHandle);
                                }
                            }.start();
                        }
                    },

                    null, Activity.RESULT_OK, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void removeUserState(final int userHandle) {
        mContext.getSystemService(StorageManager.class).destroyUserKey(userHandle);
        // Cleanup package manager settings
        mPm.cleanUpUser(this, userHandle);

        // Remove this user from the list
        synchronized (mUsersLock) {
            mUsers.remove(userHandle);
            mIsUserManaged.delete(userHandle);
        }
        synchronized (mRestrictionsLock) {
            mBaseUserRestrictions.remove(userHandle);
            mAppliedUserRestrictions.remove(userHandle);
            mCachedEffectiveUserRestrictions.remove(userHandle);
            mDevicePolicyLocalUserRestrictions.remove(userHandle);
        }
        // Remove user file
        AtomicFile userFile = new AtomicFile(new File(mUsersDir, userHandle + XML_SUFFIX));
        userFile.delete();
        // Update the user list
        synchronized (mPackagesLock) {
            writeUserListLP();
        }
        updateUserIds();
        removeDirectoryRecursive(Environment.getUserSystemDirectory(userHandle));
    }

    private void removeDirectoryRecursive(File parent) {
        if (parent.isDirectory()) {
            String[] files = parent.list();
            for (String filename : files) {
                File child = new File(parent, filename);
                removeDirectoryRecursive(child);
            }
        }
        parent.delete();
    }

    private void sendProfileRemovedBroadcast(int parentUserId, int removedUserId) {
        Intent managedProfileIntent = new Intent(Intent.ACTION_MANAGED_PROFILE_REMOVED);
        managedProfileIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY |
                Intent.FLAG_RECEIVER_FOREGROUND);
        managedProfileIntent.putExtra(Intent.EXTRA_USER, new UserHandle(removedUserId));
        mContext.sendBroadcastAsUser(managedProfileIntent, new UserHandle(parentUserId), null);
    }

    @Override
    public Bundle getApplicationRestrictions(String packageName) {
        return getApplicationRestrictionsForUser(packageName, UserHandle.getCallingUserId());
    }

    @Override
    public Bundle getApplicationRestrictionsForUser(String packageName, int userId) {
        if (UserHandle.getCallingUserId() != userId
                || !UserHandle.isSameApp(Binder.getCallingUid(), getUidForPackage(packageName))) {
            checkManageUsersPermission("get application restrictions for other users/apps");
        }
        synchronized (mPackagesLock) {
            // Read the restrictions from XML
            return readApplicationRestrictionsLP(packageName, userId);
        }
    }

    @Override
    public void setApplicationRestrictions(String packageName, Bundle restrictions,
            int userId) {
        checkManageUsersPermission("set application restrictions");
        synchronized (mPackagesLock) {
            if (restrictions == null || restrictions.isEmpty()) {
                cleanAppRestrictionsForPackage(packageName, userId);
            } else {
                // Write the restrictions to XML
                writeApplicationRestrictionsLP(packageName, restrictions, userId);
            }
        }

        if (isPackageInstalled(packageName, userId)) {
            // Notify package of changes via an intent - only sent to explicitly registered receivers.
            Intent changeIntent = new Intent(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED);
            changeIntent.setPackage(packageName);
            changeIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            mContext.sendBroadcastAsUser(changeIntent, new UserHandle(userId));
        }
    }

    private void unhideAllInstalledAppsForUser(final int userHandle) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                List<ApplicationInfo> apps =
                        mPm.getInstalledApplications(PackageManager.GET_UNINSTALLED_PACKAGES,
                                userHandle).getList();
                final long ident = Binder.clearCallingIdentity();
                try {
                    for (ApplicationInfo appInfo : apps) {
                        if ((appInfo.flags & ApplicationInfo.FLAG_INSTALLED) != 0
                                && (appInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_HIDDEN)
                                        != 0) {
                            mPm.setApplicationHiddenSettingAsUser(appInfo.packageName, false,
                                    userHandle);
                        }
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        });
    }
    private int getUidForPackage(String packageName) {
        long ident = Binder.clearCallingIdentity();
        try {
            return mContext.getPackageManager().getApplicationInfo(packageName,
                    PackageManager.GET_UNINSTALLED_PACKAGES).uid;
        } catch (NameNotFoundException nnfe) {
            return -1;
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private Bundle readApplicationRestrictionsLP(String packageName, int userId) {
        AtomicFile restrictionsFile =
                new AtomicFile(new File(Environment.getUserSystemDirectory(userId),
                        packageToRestrictionsFileName(packageName)));
        return readApplicationRestrictionsLP(restrictionsFile);
    }

    @VisibleForTesting
    static Bundle readApplicationRestrictionsLP(AtomicFile restrictionsFile) {
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

    private void writeApplicationRestrictionsLP(String packageName,
            Bundle restrictions, int userId) {
        AtomicFile restrictionsFile = new AtomicFile(
                new File(Environment.getUserSystemDirectory(userId),
                        packageToRestrictionsFileName(packageName)));
        writeApplicationRestrictionsLP(restrictions, restrictionsFile);
    }

    @VisibleForTesting
    static void writeApplicationRestrictionsLP(Bundle restrictions, AtomicFile restrictionsFile) {
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
    public int getUserSerialNumber(int userHandle) {
        synchronized (mUsersLock) {
            if (!exists(userHandle)) return -1;
            return getUserInfoLU(userHandle).serialNumber;
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
    public long getUserCreationTime(int userHandle) {
        int callingUserId = UserHandle.getCallingUserId();
        UserInfo userInfo = null;
        synchronized (mUsersLock) {
            if (callingUserId == userHandle) {
                userInfo = getUserInfoLU(userHandle);
            } else {
                UserInfo parent = getProfileParentLU(userHandle);
                if (parent != null && parent.id == callingUserId) {
                    userInfo = getUserInfoLU(userHandle);
                }
            }
        }
        if (userInfo == null) {
            throw new SecurityException("userHandle can only be the calling user or a managed "
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
                if (!mUsers.valueAt(i).partial) {
                    num++;
                }
            }
            final int[] newUsers = new int[num];
            int n = 0;
            for (int i = 0; i < userSize; i++) {
                if (!mUsers.valueAt(i).partial) {
                    newUsers[n++] = mUsers.keyAt(i);
                }
            }
            mUserIds = newUsers;
        }
    }

    /**
     * Called right before a user starts.  This will not be called for the system user.
     */
    public void onBeforeStartUser(int userId) {
        synchronized (mRestrictionsLock) {
            applyUserRestrictionsLR(userId);
        }
    }

    /**
     * Make a note of the last started time of a user and do some cleanup.
     * @param userId the user that was just foregrounded
     */
    public void onUserForeground(int userId) {
        UserInfo user = getUserInfoNoChecks(userId);
        if (user == null || user.partial) {
            Slog.w(LOG_TAG, "userForeground: unknown user #" + userId);
            return;
        }
        long now = System.currentTimeMillis();
        if (now > EPOCH_PLUS_30_YEARS) {
            user.lastLoggedInTime = now;
            scheduleWriteUser(user);
        }
    }

    /**
     * Returns the next available user id, filling in any holes in the ids.
     * TODO: May not be a good idea to recycle ids, in case it results in confusion
     * for data and battery stats collection, or unexpected cross-talk.
     */
    private int getNextAvailableId() {
        synchronized (mUsersLock) {
            int i = MIN_USER_ID;
            while (i < MAX_USER_ID) {
                if (mUsers.indexOfKey(i) < 0 && !mRemovingUserIds.get(i)) {
                    return i;
                }
                i++;
            }
        }
        throw new IllegalStateException("No user id available!");
    }

    private String packageToRestrictionsFileName(String packageName) {
        return RESTRICTIONS_FILE_PREFIX + packageName + XML_SUFFIX;
    }

    /**
     * Enforce that serial number stored in user directory inode matches the
     * given expected value. Gracefully sets the serial number if currently
     * undefined.
     *
     * @throws IOException when problem extracting serial number, or serial
     *             number is mismatched.
     */
    public static void enforceSerialNumber(File file, int serialNumber) throws IOException {
        final int foundSerial = getSerialNumber(file);
        Slog.v(LOG_TAG, "Found " + file + " with serial number " + foundSerial);

        if (foundSerial == -1) {
            Slog.d(LOG_TAG, "Serial number missing on " + file + "; assuming current is valid");
            try {
                setSerialNumber(file, serialNumber);
            } catch (IOException e) {
                Slog.w(LOG_TAG, "Failed to set serial number on " + file, e);
            }

        } else if (foundSerial != serialNumber) {
            throw new IOException("Found serial number " + foundSerial
                    + " doesn't match expected " + serialNumber);
        }
    }

    /**
     * Set serial number stored in user directory inode.
     *
     * @throws IOException if serial number was already set
     */
    private static void setSerialNumber(File file, int serialNumber)
            throws IOException {
        try {
            final byte[] buf = Integer.toString(serialNumber).getBytes(StandardCharsets.UTF_8);
            Os.setxattr(file.getAbsolutePath(), XATTR_SERIAL, buf, OsConstants.XATTR_CREATE);
        } catch (ErrnoException e) {
            throw e.rethrowAsIOException();
        }
    }

    /**
     * Return serial number stored in user directory inode.
     *
     * @return parsed serial number, or -1 if not set
     */
    private static int getSerialNumber(File file) throws IOException {
        try {
            final byte[] buf = new byte[256];
            final int len = Os.getxattr(file.getAbsolutePath(), XATTR_SERIAL, buf);
            final String serial = new String(buf, 0, len);
            try {
                return Integer.parseInt(serial);
            } catch (NumberFormatException e) {
                throw new IOException("Bad serial number: " + serial);
            }
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENODATA) {
                return -1;
            } else {
                throw e.rethrowAsIOException();
            }
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ResultReceiver resultReceiver) {
        (new Shell()).exec(this, in, out, err, args, resultReceiver);
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
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private int runList(PrintWriter pw) throws RemoteException {
        final IActivityManager am = ActivityManagerNative.getDefault();
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
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump UserManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " without permission "
                    + android.Manifest.permission.DUMP);
            return;
        }

        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        synchronized (mPackagesLock) {
            synchronized (mUsersLock) {
                pw.println("Users:");
                for (int i = 0; i < mUsers.size(); i++) {
                    UserInfo user = mUsers.valueAt(i);
                    if (user == null) {
                        continue;
                    }
                    final int userId = user.id;
                    pw.print("  "); pw.print(user);
                    pw.print(" serialNo="); pw.print(user.serialNumber);
                    if (mRemovingUserIds.get(userId)) {
                        pw.print(" <removing> ");
                    }
                    if (user.partial) {
                        pw.print(" <partial>");
                    }
                    pw.println();
                    pw.print("    Created: ");
                    if (user.creationTime == 0) {
                        pw.println("<unknown>");
                    } else {
                        sb.setLength(0);
                        TimeUtils.formatDuration(now - user.creationTime, sb);
                        sb.append(" ago");
                        pw.println(sb);
                    }
                    pw.print("    Last logged in: ");
                    if (user.lastLoggedInTime == 0) {
                        pw.println("<unknown>");
                    } else {
                        sb.setLength(0);
                        TimeUtils.formatDuration(now - user.lastLoggedInTime, sb);
                        sb.append(" ago");
                        pw.println(sb);
                    }
                    pw.print("    Has profile owner: ");
                    pw.println(mIsUserManaged.get(userId));
                    pw.println("    Restrictions:");
                    synchronized (mRestrictionsLock) {
                        UserRestrictionsUtils.dumpRestrictions(
                                pw, "      ", mBaseUserRestrictions.get(user.id));
                        pw.println("    Device policy local restrictions:");
                        UserRestrictionsUtils.dumpRestrictions(
                                pw, "      ", mDevicePolicyLocalUserRestrictions.get(user.id));
                        pw.println("    Effective restrictions:");
                        UserRestrictionsUtils.dumpRestrictions(
                                pw, "      ", mCachedEffectiveUserRestrictions.get(user.id));
                    }
                    pw.println();
                }
            }
            pw.println("  Device policy global restrictions:");
            synchronized (mRestrictionsLock) {
                UserRestrictionsUtils
                        .dumpRestrictions(pw, "    ", mDevicePolicyGlobalUserRestrictions);
            }
            pw.println();
            pw.println("  Guest restrictions:");
            synchronized (mGuestRestrictions) {
                UserRestrictionsUtils.dumpRestrictions(pw, "    ", mGuestRestrictions);
            }
            synchronized (mUsersLock) {
                pw.println();
                pw.println("  Device managed: " + mIsDeviceManaged);
            }
        }
    }

    final class MainHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case WRITE_USER_MSG:
                    removeMessages(WRITE_USER_MSG, msg.obj);
                    synchronized (mPackagesLock) {
                        int userId = ((UserInfo) msg.obj).id;
                        UserInfo userInfo = getUserInfoNoChecks(userId);
                        if (userInfo != null) {
                            writeUserLP(userInfo);
                        }
                    }
            }
        }
    }

    /**
     * @param userId
     * @return whether the user has been initialized yet
     */
    boolean isInitialized(int userId) {
        return (getUserInfo(userId).flags & UserInfo.FLAG_INITIALIZED) != 0;
    }

    private class LocalService extends UserManagerInternal {
        @Override
        public void setDevicePolicyUserRestrictions(int userId, @NonNull Bundle localRestrictions,
                @Nullable Bundle globalRestrictions) {
            UserManagerService.this.setDevicePolicyUserRestrictions(userId, localRestrictions,
                    globalRestrictions);
        }

        @Override
        public Bundle getBaseUserRestrictions(int userId) {
            synchronized (mRestrictionsLock) {
                return mBaseUserRestrictions.get(userId);
            }
        }

        @Override
        public void setBaseUserRestrictionsByDpmsForMigration(
                int userId, Bundle baseRestrictions) {
            synchronized (mRestrictionsLock) {
                mBaseUserRestrictions.put(userId, new Bundle(baseRestrictions));
                invalidateEffectiveUserRestrictionsLR(userId);
            }

            final UserInfo userInfo = getUserInfoNoChecks(userId);
            synchronized (mPackagesLock) {
                if (userInfo != null) {
                    writeUserLP(userInfo);
                } else {
                    Slog.w(LOG_TAG, "UserInfo not found for " + userId);
                }
            }
        }

        @Override
        public boolean getUserRestriction(int userId, String key) {
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
        public void setUserManaged(int userId, boolean isManaged) {
            synchronized (mUsersLock) {
                mIsUserManaged.put(userId, isManaged);
            }
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
}
