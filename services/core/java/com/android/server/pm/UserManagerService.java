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

import static android.text.format.DateUtils.MINUTE_IN_MILLIS;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.IStopUserCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IUserManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.app.IAppOpsService;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class UserManagerService extends IUserManager.Stub {

    private static final String LOG_TAG = "UserManagerService";

    private static final boolean DBG = false;

    private static final String TAG_NAME = "name";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_ICON_PATH = "icon";
    private static final String ATTR_ID = "id";
    private static final String ATTR_CREATION_TIME = "created";
    private static final String ATTR_LAST_LOGGED_IN_TIME = "lastLoggedIn";
    private static final String ATTR_SALT = "salt";
    private static final String ATTR_PIN_HASH = "pinHash";
    private static final String ATTR_FAILED_ATTEMPTS = "failedAttempts";
    private static final String ATTR_LAST_RETRY_MS = "lastAttemptMs";
    private static final String ATTR_SERIAL_NO = "serialNumber";
    private static final String ATTR_NEXT_SERIAL_NO = "nextSerialNumber";
    private static final String ATTR_PARTIAL = "partial";
    private static final String ATTR_USER_VERSION = "version";
    private static final String ATTR_PROFILE_GROUP_ID = "profileGroupId";
    private static final String TAG_GUEST_RESTRICTIONS = "guestRestrictions";
    private static final String TAG_USERS = "users";
    private static final String TAG_USER = "user";
    private static final String TAG_RESTRICTIONS = "restrictions";
    private static final String TAG_ENTRY = "entry";
    private static final String TAG_VALUE = "value";
    private static final String ATTR_KEY = "key";
    private static final String ATTR_VALUE_TYPE = "type";
    private static final String ATTR_MULTIPLE = "m";

    private static final String ATTR_TYPE_STRING_ARRAY = "sa";
    private static final String ATTR_TYPE_STRING = "s";
    private static final String ATTR_TYPE_BOOLEAN = "b";
    private static final String ATTR_TYPE_INTEGER = "i";

    private static final String USER_INFO_DIR = "system" + File.separator + "users";
    private static final String USER_LIST_FILENAME = "userlist.xml";
    private static final String USER_PHOTO_FILENAME = "photo.png";

    private static final String RESTRICTIONS_FILE_PREFIX = "res_";
    private static final String XML_SUFFIX = ".xml";

    private static final int MIN_USER_ID = 10;

    private static final int USER_VERSION = 4;

    private static final long EPOCH_PLUS_30_YEARS = 30L * 365 * 24 * 60 * 60 * 1000L; // ms

    // Number of attempts before jumping to the next BACKOFF_TIMES slot
    private static final int BACKOFF_INC_INTERVAL = 5;

    // Maximum number of managed profiles permitted is 1. This cannot be increased
    // without first making sure that the rest of the framework is prepared for it.
    private static final int MAX_MANAGED_PROFILES = 1;

    // Amount of time to force the user to wait before entering the PIN again, after failing
    // BACKOFF_INC_INTERVAL times.
    private static final int[] BACKOFF_TIMES = { 0, 30*1000, 60*1000, 5*60*1000, 30*60*1000 };


    private final Context mContext;
    private final PackageManagerService mPm;
    private final Object mInstallLock;
    private final Object mPackagesLock;

    private final Handler mHandler;

    private final File mUsersDir;
    private final File mUserListFile;
    private final File mBaseUserPath;

    private final SparseArray<UserInfo> mUsers = new SparseArray<UserInfo>();
    private final SparseArray<Bundle> mUserRestrictions = new SparseArray<Bundle>();
    private final Bundle mGuestRestrictions = new Bundle();

    class RestrictionsPinState {
        long salt;
        String pinHash;
        int failedAttempts;
        long lastAttemptTime;
    }

    private final SparseArray<RestrictionsPinState> mRestrictionsPinStates =
            new SparseArray<RestrictionsPinState>();

    /**
     * Set of user IDs being actively removed. Removed IDs linger in this set
     * for several seconds to work around a VFS caching issue.
     */
    // @GuardedBy("mPackagesLock")
    private final SparseBooleanArray mRemovingUserIds = new SparseBooleanArray();

    private int[] mUserIds;
    private int mNextSerialNumber;
    private int mUserVersion = 0;

    private IAppOpsService mAppOpsService;

    private static UserManagerService sInstance;

    public static UserManagerService getInstance() {
        synchronized (UserManagerService.class) {
            return sInstance;
        }
    }

    /**
     * Available for testing purposes.
     */
    UserManagerService(File dataDir, File baseUserPath) {
        this(null, null, new Object(), new Object(), dataDir, baseUserPath);
    }

    /**
     * Called by package manager to create the service.  This is closely
     * associated with the package manager, and the given lock is the
     * package manager's own lock.
     */
    UserManagerService(Context context, PackageManagerService pm,
            Object installLock, Object packagesLock) {
        this(context, pm, installLock, packagesLock,
                Environment.getDataDirectory(),
                new File(Environment.getDataDirectory(), "user"));
    }

    /**
     * Available for testing purposes.
     */
    private UserManagerService(Context context, PackageManagerService pm,
            Object installLock, Object packagesLock,
            File dataDir, File baseUserPath) {
        mContext = context;
        mPm = pm;
        mInstallLock = installLock;
        mPackagesLock = packagesLock;
        mHandler = new Handler();
        synchronized (mInstallLock) {
            synchronized (mPackagesLock) {
                mUsersDir = new File(dataDir, USER_INFO_DIR);
                mUsersDir.mkdirs();
                // Make zeroth user directory, for services to migrate their files to that location
                File userZeroDir = new File(mUsersDir, "0");
                userZeroDir.mkdirs();
                mBaseUserPath = baseUserPath;
                FileUtils.setPermissions(mUsersDir.toString(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG
                        |FileUtils.S_IROTH|FileUtils.S_IXOTH,
                        -1, -1);
                mUserListFile = new File(mUsersDir, USER_LIST_FILENAME);
                readUserListLocked();
                // Prune out any partially created/partially removed users.
                ArrayList<UserInfo> partials = new ArrayList<UserInfo>();
                for (int i = 0; i < mUsers.size(); i++) {
                    UserInfo ui = mUsers.valueAt(i);
                    if (ui.partial && i != 0) {
                        partials.add(ui);
                    }
                }
                for (int i = 0; i < partials.size(); i++) {
                    UserInfo ui = partials.get(i);
                    Slog.w(LOG_TAG, "Removing partially created user #" + i
                            + " (name=" + ui.name + ")");
                    removeUserStateLocked(ui.id);
                }
                sInstance = this;
            }
        }
    }

    void systemReady() {
        userForeground(UserHandle.USER_OWNER);
        mAppOpsService = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
        for (int i = 0; i < mUserIds.length; ++i) {
            try {
                mAppOpsService.setUserRestrictions(mUserRestrictions.get(mUserIds[i]), mUserIds[i]);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, "Unable to notify AppOpsService of UserRestrictions");
            }
        }
    }

    @Override
    public List<UserInfo> getUsers(boolean excludeDying) {
        checkManageUsersPermission("query users");
        synchronized (mPackagesLock) {
            ArrayList<UserInfo> users = new ArrayList<UserInfo>(mUsers.size());
            for (int i = 0; i < mUsers.size(); i++) {
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
            synchronized (mPackagesLock) {
                return getProfilesLocked(userId, enabledOnly);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** Assume permissions already checked and caller's identity cleared */
    private List<UserInfo> getProfilesLocked(int userId, boolean enabledOnly) {
        UserInfo user = getUserInfoLocked(userId);
        ArrayList<UserInfo> users = new ArrayList<UserInfo>(mUsers.size());
        for (int i = 0; i < mUsers.size(); i++) {
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
    public UserInfo getProfileParent(int userHandle) {
        checkManageUsersPermission("get the profile parent");
        synchronized (mPackagesLock) {
            UserInfo profile = getUserInfoLocked(userHandle);
            int parentUserId = profile.profileGroupId;
            if (parentUserId == UserInfo.NO_PROFILE_GROUP_ID) {
                return null;
            } else {
                return getUserInfoLocked(parentUserId);
            }
        }
    }

    private boolean isProfileOf(UserInfo user, UserInfo profile) {
        return user.id == profile.id ||
                (user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID
                && user.profileGroupId == profile.profileGroupId);
    }

    @Override
    public void setUserEnabled(int userId) {
        checkManageUsersPermission("enable user");
        synchronized (mPackagesLock) {
            UserInfo info = getUserInfoLocked(userId);
            if (info != null && !info.isEnabled()) {
                info.flags ^= UserInfo.FLAG_DISABLED;
                writeUserLocked(info);
            }
        }
    }

    @Override
    public UserInfo getUserInfo(int userId) {
        checkManageUsersPermission("query user");
        synchronized (mPackagesLock) {
            return getUserInfoLocked(userId);
        }
    }

    @Override
    public boolean isRestricted() {
        synchronized (mPackagesLock) {
            return getUserInfoLocked(UserHandle.getCallingUserId()).isRestricted();
        }
    }

    /*
     * Should be locked on mUsers before calling this.
     */
    private UserInfo getUserInfoLocked(int userId) {
        UserInfo ui = mUsers.get(userId);
        // If it is partial and not in the process of being removed, return as unknown user.
        if (ui != null && ui.partial && !mRemovingUserIds.get(userId)) {
            Slog.w(LOG_TAG, "getUserInfo: unknown user #" + userId);
            return null;
        }
        return ui;
    }

    public boolean exists(int userId) {
        synchronized (mPackagesLock) {
            return ArrayUtils.contains(mUserIds, userId);
        }
    }

    @Override
    public void setUserName(int userId, String name) {
        checkManageUsersPermission("rename users");
        boolean changed = false;
        synchronized (mPackagesLock) {
            UserInfo info = mUsers.get(userId);
            if (info == null || info.partial) {
                Slog.w(LOG_TAG, "setUserName: unknown user #" + userId);
                return;
            }
            if (name != null && !name.equals(info.name)) {
                info.name = name;
                writeUserLocked(info);
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
                UserInfo info = mUsers.get(userId);
                if (info == null || info.partial) {
                    Slog.w(LOG_TAG, "setUserIcon: unknown user #" + userId);
                    return;
                }
                writeBitmapLocked(info, bitmap);
                writeUserLocked(info);
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
    public Bitmap getUserIcon(int userId) {
        synchronized (mPackagesLock) {
            UserInfo info = mUsers.get(userId);
            if (info == null || info.partial) {
                Slog.w(LOG_TAG, "getUserIcon: unknown user #" + userId);
                return null;
            }
            int callingGroupId = mUsers.get(UserHandle.getCallingUserId()).profileGroupId;
            if (callingGroupId == UserInfo.NO_PROFILE_GROUP_ID
                    || callingGroupId != info.profileGroupId) {
                checkManageUsersPermission("get the icon of a user who is not related");
            }
            if (info.iconPath == null) {
                return null;
            }
            return BitmapFactory.decodeFile(info.iconPath);
        }
    }

    public void makeInitialized(int userId) {
        checkManageUsersPermission("makeInitialized");
        synchronized (mPackagesLock) {
            UserInfo info = mUsers.get(userId);
            if (info == null || info.partial) {
                Slog.w(LOG_TAG, "makeInitialized: unknown user #" + userId);
            }
            if ((info.flags&UserInfo.FLAG_INITIALIZED) == 0) {
                info.flags |= UserInfo.FLAG_INITIALIZED;
                writeUserLocked(info);
            }
        }
    }

    @Override
    public Bundle getDefaultGuestRestrictions() {
        checkManageUsersPermission("getDefaultGuestRestrictions");
        synchronized (mPackagesLock) {
            return new Bundle(mGuestRestrictions);
        }
    }

    @Override
    public void setDefaultGuestRestrictions(Bundle restrictions) {
        checkManageUsersPermission("setDefaultGuestRestrictions");
        synchronized (mPackagesLock) {
            mGuestRestrictions.clear();
            mGuestRestrictions.putAll(restrictions);
            writeUserListLocked();
        }
    }

    @Override
    public Bundle getUserRestrictions(int userId) {
        // checkManageUsersPermission("getUserRestrictions");

        synchronized (mPackagesLock) {
            Bundle restrictions = mUserRestrictions.get(userId);
            return restrictions != null ? new Bundle(restrictions) : new Bundle();
        }
    }

    @Override
    public void setUserRestrictions(Bundle restrictions, int userId) {
        checkManageUsersPermission("setUserRestrictions");
        if (restrictions == null) return;

        synchronized (mPackagesLock) {
            mUserRestrictions.get(userId).clear();
            mUserRestrictions.get(userId).putAll(restrictions);
            long token = Binder.clearCallingIdentity();
            try {
                mAppOpsService.setUserRestrictions(mUserRestrictions.get(userId), userId);
            } catch (RemoteException e) {
                Log.w(LOG_TAG, "Unable to notify AppOpsService of UserRestrictions");
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            writeUserLocked(mUsers.get(userId));
        }
    }

    /**
     * Check if we've hit the limit of how many users can be created.
     */
    private boolean isUserLimitReachedLocked() {
        int aliveUserCount = 0;
        final int totalUserCount = mUsers.size();
        // Skip over users being removed
        for (int i = 0; i < totalUserCount; i++) {
            UserInfo user = mUsers.valueAt(i);
            if (!mRemovingUserIds.get(user.id)
                    && !user.isGuest()) {
                aliveUserCount++;
            }
        }
        return aliveUserCount >= UserManager.getMaxSupportedUsers();
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

    private void writeBitmapLocked(UserInfo info, Bitmap bitmap) {
        try {
            File dir = new File(mUsersDir, Integer.toString(info.id));
            File file = new File(dir, USER_PHOTO_FILENAME);
            if (!dir.exists()) {
                dir.mkdir();
                FileUtils.setPermissions(
                        dir.getPath(),
                        FileUtils.S_IRWXU|FileUtils.S_IRWXG|FileUtils.S_IXOTH,
                        -1, -1);
            }
            FileOutputStream os;
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, os = new FileOutputStream(file))) {
                info.iconPath = file.getAbsolutePath();
            }
            try {
                os.close();
            } catch (IOException ioe) {
                // What the ... !
            }
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
        synchronized (mPackagesLock) {
            return mUserIds;
        }
    }

    int[] getUserIdsLPr() {
        return mUserIds;
    }

    private void readUserListLocked() {
        if (!mUserListFile.exists()) {
            fallbackToSingleUserLocked();
            return;
        }
        FileInputStream fis = null;
        AtomicFile userListFile = new AtomicFile(mUserListFile);
        try {
            fis = userListFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                Slog.e(LOG_TAG, "Unable to read user list");
                fallbackToSingleUserLocked();
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

            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    final String name = parser.getName();
                    if (name.equals(TAG_USER)) {
                        String id = parser.getAttributeValue(null, ATTR_ID);
                        UserInfo user = readUserLocked(Integer.parseInt(id));

                        if (user != null) {
                            mUsers.put(user.id, user);
                            if (mNextSerialNumber < 0 || mNextSerialNumber <= user.id) {
                                mNextSerialNumber = user.id + 1;
                            }
                        }
                    } else if (name.equals(TAG_GUEST_RESTRICTIONS)) {
                        mGuestRestrictions.clear();
                        readRestrictionsLocked(parser, mGuestRestrictions);
                    }
                }
            }
            updateUserIdsLocked();
            upgradeIfNecessaryLocked();
        } catch (IOException ioe) {
            fallbackToSingleUserLocked();
        } catch (XmlPullParserException pe) {
            fallbackToSingleUserLocked();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException e) {
                }
            }
        }
    }

    /**
     * Upgrade steps between versions, either for fixing bugs or changing the data format.
     */
    private void upgradeIfNecessaryLocked() {
        int userVersion = mUserVersion;
        if (userVersion < 1) {
            // Assign a proper name for the owner, if not initialized correctly before
            UserInfo user = mUsers.get(UserHandle.USER_OWNER);
            if ("Primary".equals(user.name)) {
                user.name = mContext.getResources().getString(com.android.internal.R.string.owner_name);
                writeUserLocked(user);
            }
            userVersion = 1;
        }

        if (userVersion < 2) {
            // Owner should be marked as initialized
            UserInfo user = mUsers.get(UserHandle.USER_OWNER);
            if ((user.flags & UserInfo.FLAG_INITIALIZED) == 0) {
                user.flags |= UserInfo.FLAG_INITIALIZED;
                writeUserLocked(user);
            }
            userVersion = 2;
        }


        if (userVersion < 4) {
            userVersion = 4;
        }

        if (userVersion < USER_VERSION) {
            Slog.w(LOG_TAG, "User version " + mUserVersion + " didn't upgrade as expected to "
                    + USER_VERSION);
        } else {
            mUserVersion = userVersion;
            writeUserListLocked();
        }
    }

    private void fallbackToSingleUserLocked() {
        // Create the primary user
        UserInfo primary = new UserInfo(UserHandle.USER_OWNER,
                mContext.getResources().getString(com.android.internal.R.string.owner_name), null,
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_PRIMARY | UserInfo.FLAG_INITIALIZED);
        mUsers.put(0, primary);
        mNextSerialNumber = MIN_USER_ID;
        mUserVersion = USER_VERSION;

        Bundle restrictions = new Bundle();
        mUserRestrictions.append(UserHandle.USER_OWNER, restrictions);

        updateUserIdsLocked();

        writeUserListLocked();
        writeUserLocked(primary);
    }

    /*
     * Writes the user file in this format:
     *
     * <user flags="20039023" id="0">
     *   <name>Primary</name>
     * </user>
     */
    private void writeUserLocked(UserInfo userInfo) {
        FileOutputStream fos = null;
        AtomicFile userFile = new AtomicFile(new File(mUsersDir, userInfo.id + XML_SUFFIX));
        try {
            fos = userFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_USER);
            serializer.attribute(null, ATTR_ID, Integer.toString(userInfo.id));
            serializer.attribute(null, ATTR_SERIAL_NO, Integer.toString(userInfo.serialNumber));
            serializer.attribute(null, ATTR_FLAGS, Integer.toString(userInfo.flags));
            serializer.attribute(null, ATTR_CREATION_TIME, Long.toString(userInfo.creationTime));
            serializer.attribute(null, ATTR_LAST_LOGGED_IN_TIME,
                    Long.toString(userInfo.lastLoggedInTime));
            RestrictionsPinState pinState = mRestrictionsPinStates.get(userInfo.id);
            if (pinState != null) {
                if (pinState.salt != 0) {
                    serializer.attribute(null, ATTR_SALT, Long.toString(pinState.salt));
                }
                if (pinState.pinHash != null) {
                    serializer.attribute(null, ATTR_PIN_HASH, pinState.pinHash);
                }
                if (pinState.failedAttempts != 0) {
                    serializer.attribute(null, ATTR_FAILED_ATTEMPTS,
                            Integer.toString(pinState.failedAttempts));
                    serializer.attribute(null, ATTR_LAST_RETRY_MS,
                            Long.toString(pinState.lastAttemptTime));
                }
            }
            if (userInfo.iconPath != null) {
                serializer.attribute(null,  ATTR_ICON_PATH, userInfo.iconPath);
            }
            if (userInfo.partial) {
                serializer.attribute(null, ATTR_PARTIAL, "true");
            }
            if (userInfo.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID) {
                serializer.attribute(null, ATTR_PROFILE_GROUP_ID,
                        Integer.toString(userInfo.profileGroupId));
            }

            serializer.startTag(null, TAG_NAME);
            serializer.text(userInfo.name);
            serializer.endTag(null, TAG_NAME);
            Bundle restrictions = mUserRestrictions.get(userInfo.id);
            if (restrictions != null) {
                writeRestrictionsLocked(serializer, restrictions);
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
    private void writeUserListLocked() {
        FileOutputStream fos = null;
        AtomicFile userListFile = new AtomicFile(mUserListFile);
        try {
            fos = userListFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_USERS);
            serializer.attribute(null, ATTR_NEXT_SERIAL_NO, Integer.toString(mNextSerialNumber));
            serializer.attribute(null, ATTR_USER_VERSION, Integer.toString(mUserVersion));

            serializer.startTag(null,  TAG_GUEST_RESTRICTIONS);
            writeRestrictionsLocked(serializer, mGuestRestrictions);
            serializer.endTag(null, TAG_GUEST_RESTRICTIONS);
            for (int i = 0; i < mUsers.size(); i++) {
                UserInfo user = mUsers.valueAt(i);
                serializer.startTag(null, TAG_USER);
                serializer.attribute(null, ATTR_ID, Integer.toString(user.id));
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

    private void writeRestrictionsLocked(XmlSerializer serializer, Bundle restrictions) 
            throws IOException {
        serializer.startTag(null, TAG_RESTRICTIONS);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_CONFIG_WIFI);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_MODIFY_ACCOUNTS);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_INSTALL_APPS);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_UNINSTALL_APPS);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_SHARE_LOCATION);
        writeBoolean(serializer, restrictions,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_CONFIG_BLUETOOTH);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_USB_FILE_TRANSFER);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_CONFIG_CREDENTIALS);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_REMOVE_USER);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_DEBUGGING_FEATURES);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_CONFIG_VPN);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_CONFIG_TETHERING);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_FACTORY_RESET);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_ADD_USER);
        writeBoolean(serializer, restrictions, UserManager.ENSURE_VERIFY_APPS);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_CONFIG_CELL_BROADCASTS);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_APPS_CONTROL);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_UNMUTE_MICROPHONE);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_ADJUST_VOLUME);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_OUTGOING_CALLS);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_SMS);
        writeBoolean(serializer, restrictions, UserManager.DISALLOW_CREATE_WINDOWS);
        serializer.endTag(null, TAG_RESTRICTIONS);
    }

    private UserInfo readUserLocked(int id) {
        int flags = 0;
        int serialNumber = id;
        String name = null;
        String iconPath = null;
        long creationTime = 0L;
        long lastLoggedInTime = 0L;
        long salt = 0L;
        String pinHash = null;
        int failedAttempts = 0;
        int profileGroupId = UserInfo.NO_PROFILE_GROUP_ID;
        long lastAttemptTime = 0L;
        boolean partial = false;
        Bundle restrictions = new Bundle();

        FileInputStream fis = null;
        try {
            AtomicFile userFile =
                    new AtomicFile(new File(mUsersDir, Integer.toString(id) + XML_SUFFIX));
            fis = userFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
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
                salt = readLongAttribute(parser, ATTR_SALT, 0L);
                pinHash = parser.getAttributeValue(null, ATTR_PIN_HASH);
                failedAttempts = readIntAttribute(parser, ATTR_FAILED_ATTEMPTS, 0);
                lastAttemptTime = readLongAttribute(parser, ATTR_LAST_RETRY_MS, 0L);
                profileGroupId = readIntAttribute(parser, ATTR_PROFILE_GROUP_ID,
                        UserInfo.NO_PROFILE_GROUP_ID);
                if (profileGroupId == UserInfo.NO_PROFILE_GROUP_ID) {
                    // This attribute was added and renamed during development of L.
                    // TODO Remove upgrade path by 1st May 2014
                    profileGroupId = readIntAttribute(parser, "relatedGroupId",
                            UserInfo.NO_PROFILE_GROUP_ID);
                }
                String valueString = parser.getAttributeValue(null, ATTR_PARTIAL);
                if ("true".equals(valueString)) {
                    partial = true;
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
                        readRestrictionsLocked(parser, restrictions);
                    }
                }
            }

            UserInfo userInfo = new UserInfo(id, name, iconPath, flags);
            userInfo.serialNumber = serialNumber;
            userInfo.creationTime = creationTime;
            userInfo.lastLoggedInTime = lastLoggedInTime;
            userInfo.partial = partial;
            userInfo.profileGroupId = profileGroupId;
            mUserRestrictions.append(id, restrictions);
            if (salt != 0L) {
                RestrictionsPinState pinState = mRestrictionsPinStates.get(id);
                if (pinState == null) {
                    pinState = new RestrictionsPinState();
                    mRestrictionsPinStates.put(id, pinState);
                }
                pinState.salt = salt;
                pinState.pinHash = pinHash;
                pinState.failedAttempts = failedAttempts;
                pinState.lastAttemptTime = lastAttemptTime;
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

    private void readRestrictionsLocked(XmlPullParser parser, Bundle restrictions)
            throws IOException {
        readBoolean(parser, restrictions, UserManager.DISALLOW_CONFIG_WIFI);
        readBoolean(parser, restrictions, UserManager.DISALLOW_MODIFY_ACCOUNTS);
        readBoolean(parser, restrictions, UserManager.DISALLOW_INSTALL_APPS);
        readBoolean(parser, restrictions, UserManager.DISALLOW_UNINSTALL_APPS);
        readBoolean(parser, restrictions, UserManager.DISALLOW_SHARE_LOCATION);
        readBoolean(parser, restrictions,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        readBoolean(parser, restrictions, UserManager.DISALLOW_CONFIG_BLUETOOTH);
        readBoolean(parser, restrictions, UserManager.DISALLOW_USB_FILE_TRANSFER);
        readBoolean(parser, restrictions, UserManager.DISALLOW_CONFIG_CREDENTIALS);
        readBoolean(parser, restrictions, UserManager.DISALLOW_REMOVE_USER);
        readBoolean(parser, restrictions, UserManager.DISALLOW_DEBUGGING_FEATURES);
        readBoolean(parser, restrictions, UserManager.DISALLOW_CONFIG_VPN);
        readBoolean(parser, restrictions, UserManager.DISALLOW_CONFIG_TETHERING);
        readBoolean(parser, restrictions, UserManager.DISALLOW_FACTORY_RESET);
        readBoolean(parser, restrictions, UserManager.DISALLOW_ADD_USER);
        readBoolean(parser, restrictions, UserManager.ENSURE_VERIFY_APPS);
        readBoolean(parser, restrictions, UserManager.DISALLOW_CONFIG_CELL_BROADCASTS);
        readBoolean(parser, restrictions, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS);
        readBoolean(parser, restrictions, UserManager.DISALLOW_APPS_CONTROL);
        readBoolean(parser, restrictions,
                UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA);
        readBoolean(parser, restrictions, UserManager.DISALLOW_UNMUTE_MICROPHONE);
        readBoolean(parser, restrictions, UserManager.DISALLOW_ADJUST_VOLUME);
        readBoolean(parser, restrictions, UserManager.DISALLOW_OUTGOING_CALLS);
        readBoolean(parser, restrictions, UserManager.DISALLOW_SMS);
        readBoolean(parser, restrictions, UserManager.DISALLOW_CREATE_WINDOWS);
    }

    private void readBoolean(XmlPullParser parser, Bundle restrictions,
            String restrictionKey) {
        String value = parser.getAttributeValue(null, restrictionKey);
        if (value != null) {
            restrictions.putBoolean(restrictionKey, Boolean.parseBoolean(value));
        }
    }

    private void writeBoolean(XmlSerializer xml, Bundle restrictions, String restrictionKey)
            throws IOException {
        if (restrictions.containsKey(restrictionKey)) {
            xml.attribute(null, restrictionKey,
                    Boolean.toString(restrictions.getBoolean(restrictionKey)));
        }
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
        if (userId != UserHandle.USER_OWNER) {
            Slog.w(LOG_TAG, "Only user owner can have profiles");
            return null;
        }
        return createUserInternal(name, flags, userId);
    }

    @Override
    public UserInfo createUser(String name, int flags) {
        checkManageUsersPermission("Only the system can create users");
        return createUserInternal(name, flags, UserHandle.USER_NULL);
    }

    private UserInfo createUserInternal(String name, int flags, int parentId) {
        if (getUserRestrictions(UserHandle.getCallingUserId()).getBoolean(
                UserManager.DISALLOW_ADD_USER, false)) {
            Log.w(LOG_TAG, "Cannot add user. DISALLOW_ADD_USER is enabled.");
            return null;
        }
        final boolean isGuest = (flags & UserInfo.FLAG_GUEST) != 0;
        final long ident = Binder.clearCallingIdentity();
        UserInfo userInfo = null;
        try {
            synchronized (mInstallLock) {
                synchronized (mPackagesLock) {
                    UserInfo parent = null;
                    if (parentId != UserHandle.USER_NULL) {
                        parent = getUserInfoLocked(parentId);
                        if (parent == null) return null;
                    }
                    // If we're not adding a guest user and the limit has been reached,
                    // cannot add a user.
                    if (!isGuest && isUserLimitReachedLocked()) {
                        return null;
                    }
                    // If we're adding a guest and there already exists one, bail.
                    if (isGuest && numberOfUsersOfTypeLocked(UserInfo.FLAG_GUEST, true) > 0) {
                        return null;
                    }
                    // Limit number of managed profiles that can be created
                    if ((flags & UserInfo.FLAG_MANAGED_PROFILE) != 0
                            && numberOfUsersOfTypeLocked(UserInfo.FLAG_MANAGED_PROFILE, true)
                                >= MAX_MANAGED_PROFILES) {
                        return null;
                    }
                    int userId = getNextAvailableIdLocked();
                    userInfo = new UserInfo(userId, name, null, flags);
                    File userPath = new File(mBaseUserPath, Integer.toString(userId));
                    userInfo.serialNumber = mNextSerialNumber++;
                    long now = System.currentTimeMillis();
                    userInfo.creationTime = (now > EPOCH_PLUS_30_YEARS) ? now : 0;
                    userInfo.partial = true;
                    Environment.getUserSystemDirectory(userInfo.id).mkdirs();
                    mUsers.put(userId, userInfo);
                    writeUserListLocked();
                    if (parent != null) {
                        if (parent.profileGroupId == UserInfo.NO_PROFILE_GROUP_ID) {
                            parent.profileGroupId = parent.id;
                            writeUserLocked(parent);
                        }
                        userInfo.profileGroupId = parent.profileGroupId;
                    }
                    writeUserLocked(userInfo);
                    mPm.createNewUserLILPw(userId, userPath);
                    userInfo.partial = false;
                    writeUserLocked(userInfo);
                    updateUserIdsLocked();
                    Bundle restrictions = new Bundle();
                    mUserRestrictions.append(userId, restrictions);
                }
            }
            if (userInfo != null) {
                Intent addedIntent = new Intent(Intent.ACTION_USER_ADDED);
                addedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userInfo.id);
                mContext.sendBroadcastAsUser(addedIntent, UserHandle.ALL,
                        android.Manifest.permission.MANAGE_USERS);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
        return userInfo;
    }

    private int numberOfUsersOfTypeLocked(int flags, boolean excludeDying) {
        int count = 0;
        for (int i = mUsers.size() - 1; i >= 0; i--) {
            UserInfo user = mUsers.valueAt(i);
            if (!excludeDying || !mRemovingUserIds.get(user.id)) {
                if ((user.flags & flags) != 0) {
                    count++;
                }
            }
        }
        return count;
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
            synchronized (mPackagesLock) {
                user = mUsers.get(userHandle);
                if (userHandle == 0 || user == null || mRemovingUserIds.get(userHandle)) {
                    return false;
                }
                mRemovingUserIds.put(userHandle, true);
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
                writeUserLocked(user);
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
                                    synchronized (mInstallLock) {
                                        synchronized (mPackagesLock) {
                                            removeUserStateLocked(userHandle);
                                        }
                                    }
                                }
                            }.start();
                        }
                    },

                    null, Activity.RESULT_OK, null, null);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private void removeUserStateLocked(final int userHandle) {
        // Cleanup package manager settings
        mPm.cleanUpUserLILPw(userHandle);

        // Remove this user from the list
        mUsers.remove(userHandle);

        // Have user ID linger for several seconds to let external storage VFS
        // cache entries expire. This must be greater than the 'entry_valid'
        // timeout used by the FUSE daemon.
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (mPackagesLock) {
                    mRemovingUserIds.delete(userHandle);
                }
            }
        }, MINUTE_IN_MILLIS);

        mRestrictionsPinStates.remove(userHandle);
        // Remove user file
        AtomicFile userFile = new AtomicFile(new File(mUsersDir, userHandle + XML_SUFFIX));
        userFile.delete();
        // Update the user list
        writeUserListLocked();
        updateUserIdsLocked();
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
            checkManageUsersPermission("Only system can get restrictions for other users/apps");
        }
        synchronized (mPackagesLock) {
            // Read the restrictions from XML
            return readApplicationRestrictionsLocked(packageName, userId);
        }
    }

    @Override
    public void setApplicationRestrictions(String packageName, Bundle restrictions,
            int userId) {
        if (UserHandle.getCallingUserId() != userId
                || !UserHandle.isSameApp(Binder.getCallingUid(), getUidForPackage(packageName))) {
            checkManageUsersPermission("Only system can set restrictions for other users/apps");
        }
        synchronized (mPackagesLock) {
            if (restrictions == null || restrictions.isEmpty()) {
                cleanAppRestrictionsForPackage(packageName, userId);
            } else {
                // Write the restrictions to XML
                writeApplicationRestrictionsLocked(packageName, restrictions, userId);
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

    @Override
    public boolean setRestrictionsChallenge(String newPin) {
        checkManageUsersPermission("Only system can modify the restrictions pin");
        int userId = UserHandle.getCallingUserId();
        synchronized (mPackagesLock) {
            RestrictionsPinState pinState = mRestrictionsPinStates.get(userId);
            if (pinState == null) {
                pinState = new RestrictionsPinState();
            }
            if (newPin == null) {
                pinState.salt = 0;
                pinState.pinHash = null;
            } else {
                try {
                    pinState.salt = SecureRandom.getInstance("SHA1PRNG").nextLong();
                } catch (NoSuchAlgorithmException e) {
                    pinState.salt = (long) (Math.random() * Long.MAX_VALUE);
                }
                pinState.pinHash = passwordToHash(newPin, pinState.salt);
                pinState.failedAttempts = 0;
            }
            mRestrictionsPinStates.put(userId, pinState);
            writeUserLocked(mUsers.get(userId));
        }
        return true;
    }

    @Override
    public int checkRestrictionsChallenge(String pin) {
        checkManageUsersPermission("Only system can verify the restrictions pin");
        int userId = UserHandle.getCallingUserId();
        synchronized (mPackagesLock) {
            RestrictionsPinState pinState = mRestrictionsPinStates.get(userId);
            // If there's no pin set, return error code
            if (pinState == null || pinState.salt == 0 || pinState.pinHash == null) {
                return UserManager.PIN_VERIFICATION_FAILED_NOT_SET;
            } else if (pin == null) {
                // If just checking if user can be prompted, return remaining time
                int waitTime = getRemainingTimeForPinAttempt(pinState);
                Slog.d(LOG_TAG, "Remaining waittime peek=" + waitTime);
                return waitTime;
            } else {
                int waitTime = getRemainingTimeForPinAttempt(pinState);
                Slog.d(LOG_TAG, "Remaining waittime=" + waitTime);
                if (waitTime > 0) {
                    return waitTime;
                }
                if (passwordToHash(pin, pinState.salt).equals(pinState.pinHash)) {
                    pinState.failedAttempts = 0;
                    writeUserLocked(mUsers.get(userId));
                    return UserManager.PIN_VERIFICATION_SUCCESS;
                } else {
                    pinState.failedAttempts++;
                    pinState.lastAttemptTime = System.currentTimeMillis();
                    writeUserLocked(mUsers.get(userId));
                    return waitTime;
                }
            }
        }
    }

    private int getRemainingTimeForPinAttempt(RestrictionsPinState pinState) {
        int backoffIndex = Math.min(pinState.failedAttempts / BACKOFF_INC_INTERVAL,
                BACKOFF_TIMES.length - 1);
        int backoffTime = (pinState.failedAttempts % BACKOFF_INC_INTERVAL) == 0 ?
                BACKOFF_TIMES[backoffIndex] : 0;
        return (int) Math.max(backoffTime + pinState.lastAttemptTime - System.currentTimeMillis(),
                0);
    }

    @Override
    public boolean hasRestrictionsChallenge() {
        int userId = UserHandle.getCallingUserId();
        synchronized (mPackagesLock) {
            return hasRestrictionsPinLocked(userId);
        }
    }

    private boolean hasRestrictionsPinLocked(int userId) {
        RestrictionsPinState pinState = mRestrictionsPinStates.get(userId);
        if (pinState == null || pinState.salt == 0 || pinState.pinHash == null) {
            return false;
        }
        return true;
    }

    @Override
    public void removeRestrictions() {
        checkManageUsersPermission("Only system can remove restrictions");
        final int userHandle = UserHandle.getCallingUserId();
        removeRestrictionsForUser(userHandle, true);
    }

    private void removeRestrictionsForUser(final int userHandle, boolean unhideApps) {
        synchronized (mPackagesLock) {
            // Remove all user restrictions
            setUserRestrictions(new Bundle(), userHandle);
            // Remove restrictions pin
            setRestrictionsChallenge(null);
            // Remove any app restrictions
            cleanAppRestrictions(userHandle);
        }
        if (unhideApps) {
            unhideAllInstalledAppsForUser(userHandle);
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
                                && (appInfo.flags & ApplicationInfo.FLAG_HIDDEN) != 0) {
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

    /*
     * Generate a hash for the given password. To avoid brute force attacks, we use a salted hash.
     * Not the most secure, but it is at least a second level of protection. First level is that
     * the file is in a location only readable by the system process.
     * @param password the password.
     * @param salt the randomly generated salt
     * @return the hash of the pattern in a String.
     */
    private String passwordToHash(String password, long salt) {
        if (password == null) {
            return null;
        }
        String algo = null;
        String hashed = salt + password;
        try {
            byte[] saltedPassword = (password + salt).getBytes();
            byte[] sha1 = MessageDigest.getInstance(algo = "SHA-1").digest(saltedPassword);
            byte[] md5 = MessageDigest.getInstance(algo = "MD5").digest(saltedPassword);
            hashed = toHex(sha1) + toHex(md5);
        } catch (NoSuchAlgorithmException e) {
            Log.w(LOG_TAG, "Failed to encode string because of missing algorithm: " + algo);
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

    private Bundle readApplicationRestrictionsLocked(String packageName,
            int userId) {
        final Bundle restrictions = new Bundle();
        final ArrayList<String> values = new ArrayList<String>();

        FileInputStream fis = null;
        try {
            AtomicFile restrictionsFile =
                    new AtomicFile(new File(Environment.getUserSystemDirectory(userId),
                            packageToRestrictionsFileName(packageName)));
            fis = restrictionsFile.openRead();
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, null);
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                ;
            }

            if (type != XmlPullParser.START_TAG) {
                Slog.e(LOG_TAG, "Unable to read restrictions file "
                        + restrictionsFile.getBaseFile());
                return restrictions;
            }

            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
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
        return restrictions;
    }

    private void writeApplicationRestrictionsLocked(String packageName,
            Bundle restrictions, int userId) {
        FileOutputStream fos = null;
        AtomicFile restrictionsFile = new AtomicFile(
                new File(Environment.getUserSystemDirectory(userId),
                        packageToRestrictionsFileName(packageName)));
        try {
            fos = restrictionsFile.startWrite();
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_RESTRICTIONS);

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

            serializer.endTag(null, TAG_RESTRICTIONS);

            serializer.endDocument();
            restrictionsFile.finishWrite(fos);
        } catch (Exception e) {
            restrictionsFile.failWrite(fos);
            Slog.e(LOG_TAG, "Error writing application restrictions list");
        }
    }

    @Override
    public int getUserSerialNumber(int userHandle) {
        synchronized (mPackagesLock) {
            if (!exists(userHandle)) return -1;
            return getUserInfoLocked(userHandle).serialNumber;
        }
    }

    @Override
    public int getUserHandle(int userSerialNumber) {
        synchronized (mPackagesLock) {
            for (int userId : mUserIds) {
                if (getUserInfoLocked(userId).serialNumber == userSerialNumber) return userId;
            }
            // Not found
            return -1;
        }
    }

    /**
     * Caches the list of user ids in an array, adjusting the array size when necessary.
     */
    private void updateUserIdsLocked() {
        int num = 0;
        for (int i = 0; i < mUsers.size(); i++) {
            if (!mUsers.valueAt(i).partial) {
                num++;
            }
        }
        final int[] newUsers = new int[num];
        int n = 0;
        for (int i = 0; i < mUsers.size(); i++) {
            if (!mUsers.valueAt(i).partial) {
                newUsers[n++] = mUsers.keyAt(i);
            }
        }
        mUserIds = newUsers;
    }

    /**
     * Make a note of the last started time of a user and do some cleanup.
     * @param userId the user that was just foregrounded
     */
    public void userForeground(int userId) {
        synchronized (mPackagesLock) {
            UserInfo user = mUsers.get(userId);
            long now = System.currentTimeMillis();
            if (user == null || user.partial) {
                Slog.w(LOG_TAG, "userForeground: unknown user #" + userId);
                return;
            }
            if (now > EPOCH_PLUS_30_YEARS) {
                user.lastLoggedInTime = now;
                writeUserLocked(user);
            }
        }
    }

    /**
     * Returns the next available user id, filling in any holes in the ids.
     * TODO: May not be a good idea to recycle ids, in case it results in confusion
     * for data and battery stats collection, or unexpected cross-talk.
     * @return
     */
    private int getNextAvailableIdLocked() {
        synchronized (mPackagesLock) {
            int i = MIN_USER_ID;
            while (i < Integer.MAX_VALUE) {
                if (mUsers.indexOfKey(i) < 0 && !mRemovingUserIds.get(i)) {
                    break;
                }
                i++;
            }
            return i;
        }
    }

    private String packageToRestrictionsFileName(String packageName) {
        return RESTRICTIONS_FILE_PREFIX + packageName + XML_SUFFIX;
    }

    private String restrictionsFileNameToPackage(String fileName) {
        return fileName.substring(RESTRICTIONS_FILE_PREFIX.length(),
                (int) (fileName.length() - XML_SUFFIX.length()));
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
            pw.println("Users:");
            for (int i = 0; i < mUsers.size(); i++) {
                UserInfo user = mUsers.valueAt(i);
                if (user == null) continue;
                pw.print("  "); pw.print(user); pw.print(" serialNo="); pw.print(user.serialNumber);
                if (mRemovingUserIds.get(mUsers.keyAt(i))) pw.print(" <removing> ");
                if (user.partial) pw.print(" <partial>");
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
            }
        }
    }
}
