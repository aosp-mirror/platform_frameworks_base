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

import static android.os.ParcelFileDescriptor.MODE_CREATE;
import static android.os.ParcelFileDescriptor.MODE_READ_WRITE;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FastXmlSerializer;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Binder;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IUserManager;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserId;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

public class UserManagerService extends IUserManager.Stub {

    private static final String TAG = "UserManagerService";

    private static final String TAG_NAME = "name";

    private static final String ATTR_FLAGS = "flags";

    private static final String ATTR_ICON_PATH = "icon";

    private static final String ATTR_ID = "id";

    private static final String TAG_USERS = "users";

    private static final String TAG_USER = "user";

    private static final String LOG_TAG = "UserManager";

    private static final String USER_INFO_DIR = "system" + File.separator + "users";
    private static final String USER_LIST_FILENAME = "userlist.xml";
    private static final String USER_PHOTO_FILENAME = "photo.png";

    private SparseArray<UserInfo> mUsers = new SparseArray<UserInfo>();

    private final File mUsersDir;
    private final File mUserListFile;
    private int[] mUserIds;
    private boolean mGuestEnabled;

    private Installer mInstaller;
    private File mBaseUserPath;
    private Context mContext;
    private static UserManagerService sInstance;
    private PackageManagerService mPm;

    public synchronized static UserManagerService getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new UserManagerService(context);
        }
        return sInstance;
    }

    /**
     * Available for testing purposes.
     */
    UserManagerService(File dataDir, File baseUserPath) {
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
        readUserList();
    }

    public UserManagerService(Context context) {
        this(Environment.getDataDirectory(), new File(Environment.getDataDirectory(), "user"));
        mContext = context;
    }

    void setInstaller(PackageManagerService pm, Installer installer) {
        mInstaller = installer;
        mPm = pm;
    }

    @Override
    public List<UserInfo> getUsers() {
        enforceSystemOrRoot("Only the system can query users");
        synchronized (mUsers) {
            ArrayList<UserInfo> users = new ArrayList<UserInfo>(mUsers.size());
            for (int i = 0; i < mUsers.size(); i++) {
                users.add(mUsers.valueAt(i));
            }
            return users;
        }
    }

    @Override
    public UserInfo getUserInfo(int userId) {
        enforceSystemOrRoot("Only the system can query user");
        synchronized (mUsers) {
            UserInfo info = mUsers.get(userId);
            return info;
        }
    }

    public boolean exists(int userId) {
        synchronized (mUsers) {
            return ArrayUtils.contains(mUserIds, userId);
        }
    }

    @Override
    public void setUserName(int userId, String name) {
        enforceSystemOrRoot("Only the system can rename users");
        synchronized (mUsers) {
            UserInfo info = mUsers.get(userId);
            if (name != null && !name.equals(info.name)) {
                info.name = name;
                writeUserLocked(info);
            }
        }
    }

    @Override
    public ParcelFileDescriptor setUserIcon(int userId) {
        enforceSystemOrRoot("Only the system can update users");
        synchronized (mUsers) {
            UserInfo info = mUsers.get(userId);
            if (info == null) return null;
            ParcelFileDescriptor fd = updateIconBitmapLocked(info);
            if (fd != null) {
                writeUserLocked(info);
            }
            return fd;
        }
    }

    @Override
    public void setGuestEnabled(boolean enable) {
        enforceSystemOrRoot("Only the system can enable guest users");
        synchronized (mUsers) {
            if (mGuestEnabled != enable) {
                mGuestEnabled = enable;
                // Erase any guest user that currently exists
                for (int i = 0; i < mUsers.size(); i++) {
                    UserInfo user = mUsers.valueAt(i);
                    if (user.isGuest()) {
                        if (!enable) {
                            removeUser(user.id);
                        }
                        return;
                    }
                }
                // No guest was found
                if (enable) {
                    createUser("Guest", UserInfo.FLAG_GUEST);
                }
            }
        }
    }

    @Override
    public boolean isGuestEnabled() {
        synchronized (mUsers) {
            return mGuestEnabled;
        }
    }

    @Override
    public void wipeUser(int userHandle) {
        enforceSystemOrRoot("Only the system can wipe users");
        // TODO:
    }

    /**
     * Enforces that only the system UID or root's UID can call a method exposed
     * via Binder.
     *
     * @param message used as message if SecurityException is thrown
     * @throws SecurityException if the caller is not system or root
     */
    private static final void enforceSystemOrRoot(String message) {
        final int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != 0) {
            throw new SecurityException(message);
        }
    }

    private ParcelFileDescriptor updateIconBitmapLocked(UserInfo info) {
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
            ParcelFileDescriptor fd = ParcelFileDescriptor.open(file,
                    MODE_CREATE|MODE_READ_WRITE);
            info.iconPath = file.getAbsolutePath();
            return fd;
        } catch (FileNotFoundException e) {
            Slog.w(TAG, "Error setting photo for user ", e);
        }
        return null;
    }

    /**
     * Returns an array of user ids. This array is cached here for quick access, so do not modify or
     * cache it elsewhere.
     * @return the array of user ids.
     */
    int[] getUserIds() {
        return mUserIds;
    }

    private void readUserList() {
        synchronized (mUsers) {
            readUserListLocked();
        }
    }

    private void readUserListLocked() {
        mGuestEnabled = false;
        if (!mUserListFile.exists()) {
            fallbackToSingleUserLocked();
            return;
        }
        FileInputStream fis = null;
        try {
            fis = new FileInputStream(mUserListFile);
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

            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_USER)) {
                    String id = parser.getAttributeValue(null, ATTR_ID);
                    UserInfo user = readUser(Integer.parseInt(id));
                    if (user != null) {
                        mUsers.put(user.id, user);
                    }
                    if (user.isGuest()) {
                        mGuestEnabled = true;
                    }
                }
            }
            updateUserIdsLocked();
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

    private void fallbackToSingleUserLocked() {
        // Create the primary user
        UserInfo primary = new UserInfo(0, "Primary", null,
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_PRIMARY);
        mUsers.put(0, primary);
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
        try {
            final File mUserFile = new File(mUsersDir, userInfo.id + ".xml");
            fos = new FileOutputStream(mUserFile);
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_USER);
            serializer.attribute(null, ATTR_ID, Integer.toString(userInfo.id));
            serializer.attribute(null, ATTR_FLAGS, Integer.toString(userInfo.flags));
            if (userInfo.iconPath != null) {
                serializer.attribute(null,  ATTR_ICON_PATH, userInfo.iconPath);
            }

            serializer.startTag(null, TAG_NAME);
            serializer.text(userInfo.name);
            serializer.endTag(null, TAG_NAME);

            serializer.endTag(null, TAG_USER);

            serializer.endDocument();
        } catch (IOException ioe) {
            Slog.e(LOG_TAG, "Error writing user info " + userInfo.id + "\n" + ioe);
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ioe) {
                }
            }
        }
    }

    /*
     * Writes the user list file in this format:
     *
     * <users>
     *   <user id="0"></user>
     *   <user id="2"></user>
     * </users>
     */
    private void writeUserListLocked() {
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(mUserListFile);
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_USERS);

            for (int i = 0; i < mUsers.size(); i++) {
                UserInfo user = mUsers.valueAt(i);
                serializer.startTag(null, TAG_USER);
                serializer.attribute(null, ATTR_ID, Integer.toString(user.id));
                serializer.endTag(null, TAG_USER);
            }

            serializer.endTag(null, TAG_USERS);

            serializer.endDocument();
        } catch (IOException ioe) {
            Slog.e(LOG_TAG, "Error writing user list");
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ioe) {
                }
            }
        }
    }

    private UserInfo readUser(int id) {
        int flags = 0;
        String name = null;
        String iconPath = null;

        FileInputStream fis = null;
        try {
            File userFile = new File(mUsersDir, Integer.toString(id) + ".xml");
            fis = new FileInputStream(userFile);
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
                String storedId = parser.getAttributeValue(null, ATTR_ID);
                if (Integer.parseInt(storedId) != id) {
                    Slog.e(LOG_TAG, "User id does not match the file name");
                    return null;
                }
                String flagString = parser.getAttributeValue(null, ATTR_FLAGS);
                flags = Integer.parseInt(flagString);
                iconPath = parser.getAttributeValue(null, ATTR_ICON_PATH);

                while ((type = parser.next()) != XmlPullParser.START_TAG
                        && type != XmlPullParser.END_DOCUMENT) {
                }
                if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_NAME)) {
                    type = parser.next();
                    if (type == XmlPullParser.TEXT) {
                        name = parser.getText();
                    }
                }
            }

            UserInfo userInfo = new UserInfo(id, name, iconPath, flags);
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

    @Override
    public UserInfo createUser(String name, int flags) {
        enforceSystemOrRoot("Only the system can create users");
        int userId = getNextAvailableId();
        UserInfo userInfo = new UserInfo(userId, name, null, flags);
        File userPath = new File(mBaseUserPath, Integer.toString(userId));
        if (!createPackageFolders(userId, userPath)) {
            return null;
        }
        synchronized (mUsers) {
            mUsers.put(userId, userInfo);
            writeUserListLocked();
            writeUserLocked(userInfo);
            updateUserIdsLocked();
        }
        if (userInfo != null) {
            Intent addedIntent = new Intent(Intent.ACTION_USER_ADDED);
            addedIntent.putExtra(Intent.EXTRA_USERID, userInfo.id);
            mContext.sendBroadcast(addedIntent, android.Manifest.permission.MANAGE_ACCOUNTS);
        }
        return userInfo;
    }

    /**
     * Removes a user and all data directories created for that user. This method should be called
     * after the user's processes have been terminated.
     * @param id the user's id
     */
    public boolean removeUser(int userHandle) {
        enforceSystemOrRoot("Only the system can remove users");
        synchronized (mUsers) {
            return removeUserLocked(userHandle);
        }
    }

    private boolean removeUserLocked(int userHandle) {
        final UserInfo user = mUsers.get(userHandle);
        if (userHandle == 0 || user == null) {
            return false;
        }

        mPm.cleanUpUser(userHandle);

        // Remove this user from the list
        mUsers.remove(userHandle);
        // Remove user file
        File userFile = new File(mUsersDir, userHandle + ".xml");
        userFile.delete();
        // Update the user list
        writeUserListLocked();
        updateUserIdsLocked();

        // Let other services shutdown any activity
        Intent addedIntent = new Intent(Intent.ACTION_USER_REMOVED);
        addedIntent.putExtra(Intent.EXTRA_USERID, userHandle);
        mContext.sendBroadcast(addedIntent, android.Manifest.permission.MANAGE_ACCOUNTS);

        removePackageFolders(userHandle);
        return true;
    }

    public void installPackageForAllUsers(String packageName, int uid) {
        for (int userId : mUserIds) {
            // Don't do it for the primary user, it will become recursive.
            if (userId == 0)
                continue;
            mInstaller.createUserData(packageName, UserId.getUid(userId, uid),
                    userId);
        }
    }

    public void clearUserDataForAllUsers(String packageName) {
        for (int userId : mUserIds) {
            // Don't do it for the primary user, it will become recursive.
            if (userId == 0)
                continue;
            mInstaller.clearUserData(packageName, userId);
        }
    }

    public void removePackageForAllUsers(String packageName) {
        for (int userId : mUserIds) {
            // Don't do it for the primary user, it will become recursive.
            if (userId == 0)
                continue;
            mInstaller.remove(packageName, userId);
        }
    }

    /**
     * Caches the list of user ids in an array, adjusting the array size when necessary.
     */
    private void updateUserIdsLocked() {
        if (mUserIds == null || mUserIds.length != mUsers.size()) {
            mUserIds = new int[mUsers.size()];
        }
        for (int i = 0; i < mUsers.size(); i++) {
            mUserIds[i] = mUsers.keyAt(i);
        }
    }

    /**
     * Returns the next available user id, filling in any holes in the ids.
     * TODO: May not be a good idea to recycle ids, in case it results in confusion
     * for data and battery stats collection, or unexpected cross-talk.
     * @return
     */
    private int getNextAvailableId() {
        int i = 0;
        while (i < Integer.MAX_VALUE) {
            if (mUsers.indexOfKey(i) < 0) {
                break;
            }
            i++;
        }
        return i;
    }

    private boolean createPackageFolders(int id, File userPath) {
        // mInstaller may not be available for unit-tests.
        if (mInstaller == null) return true;

        // Create the user path
        userPath.mkdir();
        FileUtils.setPermissions(userPath.toString(), FileUtils.S_IRWXU | FileUtils.S_IRWXG
                | FileUtils.S_IXOTH, -1, -1);

        mInstaller.cloneUserData(0, id, false);

        return true;
    }

    boolean removePackageFolders(int id) {
        // mInstaller may not be available for unit-tests.
        if (mInstaller == null) return true;

        mInstaller.removeUserDataDirs(id);
        return true;
    }
}
