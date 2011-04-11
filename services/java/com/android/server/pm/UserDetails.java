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

import com.android.internal.util.FastXmlSerializer;

import android.content.pm.UserInfo;
import android.os.Environment;
import android.os.FileUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

public class UserDetails {
    private static final String TAG_NAME = "name";

    private static final String ATTR_FLAGS = "flags";

    private static final String ATTR_ID = "id";

    private static final String TAG_USERS = "users";

    private static final String TAG_USER = "user";

    private static final String TAG = "UserDetails";

    private static final String USER_INFO_DIR = "system/users";
    private static final String USER_LIST_FILENAME = "userlist.xml";

    private SparseArray<UserInfo> mUsers;

    private final File mUsersDir;
    private final File mUserListFile;

    /**
     * Available for testing purposes.
     */
    UserDetails(File dataDir) {
        mUsersDir = new File(dataDir, USER_INFO_DIR);
        mUsersDir.mkdirs();
        FileUtils.setPermissions(mUsersDir.toString(),
                FileUtils.S_IRWXU|FileUtils.S_IRWXG
                |FileUtils.S_IROTH|FileUtils.S_IXOTH,
                -1, -1);
        mUserListFile = new File(mUsersDir, USER_LIST_FILENAME);
        readUserList();
    }

    public UserDetails() {
        this(Environment.getDataDirectory());
    }

    public List<UserInfo> getUsers() {
        ArrayList<UserInfo> users = new ArrayList<UserInfo>(mUsers.size());
        for (int i = 0; i < mUsers.size(); i++) {
            users.add(mUsers.valueAt(i));
        }
        return users;
    }

    private void readUserList() {
        mUsers = new SparseArray<UserInfo>();
        if (!mUserListFile.exists()) {
            fallbackToSingleUser();
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
                Slog.e(TAG, "Unable to read user list");
                fallbackToSingleUser();
                return;
            }

            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_USER)) {
                    String id = parser.getAttributeValue(null, ATTR_ID);
                    UserInfo user = readUser(Integer.parseInt(id));
                    if (user != null) {
                        mUsers.put(user.id, user);
                    }
                }
            }
        } catch (IOException ioe) {
            fallbackToSingleUser();
        } catch (XmlPullParserException pe) {
            fallbackToSingleUser();
        }
    }

    private void fallbackToSingleUser() {
        // Create the primary user
        UserInfo primary = new UserInfo(0, "Primary",
                UserInfo.FLAG_ADMIN | UserInfo.FLAG_PRIMARY);
        mUsers.put(0, primary);

        writeUserList();
        writeUser(primary);
    }

    /*
     * Writes the user file in this format:
     *
     * <user flags="20039023" id="0">
     *   <name>Primary</name>
     * </user>
     */
    private void writeUser(UserInfo userInfo) {
        try {
            final File mUserFile = new File(mUsersDir, userInfo.id + ".xml");
            final FileOutputStream fos = new FileOutputStream(mUserFile);
            final BufferedOutputStream bos = new BufferedOutputStream(fos);

            // XmlSerializer serializer = XmlUtils.serializerInstance();
            final XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(bos, "utf-8");
            serializer.startDocument(null, true);
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);

            serializer.startTag(null, TAG_USER);
            serializer.attribute(null, ATTR_ID, Integer.toString(userInfo.id));
            serializer.attribute(null, ATTR_FLAGS, Integer.toString(userInfo.flags));

            serializer.startTag(null, TAG_NAME);
            serializer.text(userInfo.name);
            serializer.endTag(null, TAG_NAME);

            serializer.endTag(null, TAG_USER);

            serializer.endDocument();
        } catch (IOException ioe) {
            Slog.e(TAG, "Error writing user info " + userInfo.id + "\n" + ioe);
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
    private void writeUserList() {
        try {
            final FileOutputStream fos = new FileOutputStream(mUserListFile);
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
                Slog.e(TAG, "Wrote user " + user.id + " to userlist.xml");
            }

            serializer.endTag(null, TAG_USERS);

            serializer.endDocument();
        } catch (IOException ioe) {
            Slog.e(TAG, "Error writing user list");
        }
    }

    private UserInfo readUser(int id) {
        int flags = 0;
        String name = null;

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
                Slog.e(TAG, "Unable to read user " + id);
                return null;
            }

            if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_USER)) {
                String storedId = parser.getAttributeValue(null, ATTR_ID);
                if (Integer.parseInt(storedId) != id) {
                    Slog.e(TAG, "User id does not match the file name");
                    return null;
                }
                String flagString = parser.getAttributeValue(null, ATTR_FLAGS);
                flags = Integer.parseInt(flagString);

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
            fis.close();

            UserInfo userInfo = new UserInfo(id, name, flags);
            return userInfo;

        } catch (IOException ioe) {
        } catch (XmlPullParserException pe) {
        }
        return null;
    }

    public UserInfo createUser(String name, int flags) {
        int id = getNextAvailableId();
        UserInfo userInfo = new UserInfo(id, name, flags);
        if (!createPackageFolders(id)) {
            return null;
        }
        mUsers.put(id, userInfo);
        writeUserList();
        writeUser(userInfo);
        return userInfo;
    }

    public void removeUser(int id) {
        // Remove from the list
        UserInfo userInfo = mUsers.get(id);
        if (userInfo != null) {
            // Remove this user from the list
            mUsers.remove(id);
            // Remove user file
            File userFile = new File(mUsersDir, id + ".xml");
            userFile.delete();
            writeUserList();
            removePackageFolders(id);
        }
    }

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

    private boolean createPackageFolders(int id) {
        // TODO: Create data directories for all the packages for a new user, w/ specified user id.
        return true;
    }

    private boolean removePackageFolders(int id) {
        // TODO: Remove all the data directories for the specified user.
        return true;
    }
}
