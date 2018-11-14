/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.role;

import android.annotation.CheckResult;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.os.Environment;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.function.pooled.PooledLambda;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Stores the state of roles for a user.
 */
public class RoleUserState {

    private static final String LOG_TAG = RoleUserState.class.getSimpleName();

    public static final int VERSION_UNDEFINED = -1;

    private static final String ROLES_FILE_NAME = "roles.xml";

    private static final String TAG_ROLES = "roles";
    private static final String TAG_ROLE = "role";
    private static final String TAG_HOLDER = "holder";
    private static final String ATTRIBUTE_VERSION = "version";
    private static final String ATTRIBUTE_NAME = "name";

    @UserIdInt
    private final int mUserId;

    @GuardedBy("RoleManagerService.mLock")
    private int mVersion = VERSION_UNDEFINED;

    /**
     * Maps role names to its holders' package names. The values should never be null.
     */
    @GuardedBy("RoleManagerService.mLock")
    private ArrayMap<String, ArraySet<String>> mRoles = null;

    @GuardedBy("RoleManagerService.mLock")
    private boolean mDestroyed;

    private final Handler mWriteHandler = new Handler(BackgroundThread.getHandler().getLooper());

    public RoleUserState(@UserIdInt int userId) {
        mUserId = userId;
    }

    /**
     * Get the version of this user state.
     */
    @GuardedBy("RoleManagerService.mLock")
    public int getVersionLocked() {
        throwIfDestroyedLocked();
        return mVersion;
    }

    /**
     * Set the version of this user state.
     *
     * @param version the version to set
     */
    @GuardedBy("RoleManagerService.mLock")
    public void setVersionLocked(int version) {
        throwIfDestroyedLocked();
        mVersion = version;
    }

    /**
     * Get whether the role is available.
     *
     * @param roleName the name of the role to get the holders for
     *
     * @return whether the role is available
     */
    @GuardedBy("RoleManagerService.mLock")
    public boolean isRoleAvailableLocked(@NonNull String roleName) {
        throwIfDestroyedLocked();
        return mRoles.containsKey(roleName);
    }

    /**
     * Get the holders of a role.
     *
     * @param roleName the name of the role to query for
     *
     * @return the set of role holders. {@code null} should not be returned and indicates an issue.
     */
    @GuardedBy("RoleManagerService.mLock")
    @Nullable
    public ArraySet<String> getRoleHoldersLocked(@NonNull String roleName) {
        throwIfDestroyedLocked();
        return mRoles.get(roleName);
    }

    /**
     * Add a holder to a role.
     *
     * @param roleName the name of the role to add the holder to
     * @param packageName the package name of the new holder
     *
     * @return {@code false} only if the set of role holders is null, which should not happen and
     *         indicates an issue.
     */
    @CheckResult
    @GuardedBy("RoleManagerService.mLock")
    public boolean addRoleHolderLocked(@NonNull String roleName, @NonNull String packageName) {
        throwIfDestroyedLocked();
        ArraySet<String> roleHolders = mRoles.get(roleName);
        if (roleHolders == null) {
            return false;
        }
        roleHolders.add(packageName);
        return true;
    }

    /**
     * Remove a holder from a role.
     *
     * @param roleName the name of the role to remove the holder from
     * @param packageName the package name of the holder to remove
     *
     * @return {@code false} only if the set of role holders is null, which should not happen and
     *         indicates an issue.
     */
    @CheckResult
    @GuardedBy("RoleManagerService.mLock")
    public boolean removeRoleHolderLocked(@NonNull String roleName, @NonNull String packageName) {
        throwIfDestroyedLocked();
        ArraySet<String> roleHolders = mRoles.get(roleName);
        if (roleHolders == null) {
            return false;
        }
        roleHolders.remove(packageName);
        return true;
    }

    /**
     * Schedule writing the state to file.
     */
    @GuardedBy("RoleManagerService.mLock")
    public void writeAsyncLocked() {
        throwIfDestroyedLocked();
        int version = mVersion;
        ArrayMap<String, ArraySet<String>> roles = new ArrayMap<>();
        for (int i = 0, size = mRoles.size(); i < size; ++i) {
            String roleName = mRoles.keyAt(i);
            ArraySet<String> roleHolders = mRoles.valueAt(i);
            roleHolders = new ArraySet<>(roleHolders);
            roles.put(roleName, roleHolders);
        }
        mWriteHandler.removeCallbacksAndMessages(null);
        mWriteHandler.sendMessage(PooledLambda.obtainMessage(
                RoleUserState::writeSync, this, version, roles));
    }

    @WorkerThread
    private void writeSync(int version, @NonNull ArrayMap<String, ArraySet<String>> roles) {
        AtomicFile destination = new AtomicFile(getFile(mUserId), "roles-" + mUserId);
        FileOutputStream out = null;
        try {
            out = destination.startWrite();

            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, StandardCharsets.UTF_8.name());
            serializer.setFeature(
                    "http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);

            serializeRoles(serializer, version, roles);

            serializer.endDocument();
            destination.finishWrite(out);
        } catch (Throwable t) {
            // Any error while writing is fatal.
            Slog.wtf(LOG_TAG, "Failed to write roles file, restoring backup", t);
            destination.failWrite(out);
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    @WorkerThread
    private void serializeRoles(@NonNull XmlSerializer serializer, int version,
            @NonNull ArrayMap<String, ArraySet<String>> roles) throws IOException {
        serializer.startTag(null, TAG_ROLES);
        serializer.attribute(null, ATTRIBUTE_VERSION, Integer.toString(version));
        for (int i = 0, size = roles.size(); i < size; ++i) {
            String roleName = roles.keyAt(i);
            ArraySet<String> roleHolders = roles.valueAt(i);
            serializer.startTag(null, TAG_ROLE);
            serializer.attribute(null, ATTRIBUTE_NAME, roleName);
            serializeRoleHolders(serializer, roleHolders);
            serializer.endTag(null, TAG_ROLE);
        }
        serializer.endTag(null, TAG_ROLES);
    }

    @WorkerThread
    private void serializeRoleHolders(@NonNull XmlSerializer serializer,
            @NonNull ArraySet<String> roleHolders) throws IOException {
        for (int i = 0, size = roleHolders.size(); i < size; ++i) {
            String roleHolder = roleHolders.valueAt(i);
            serializer.startTag(null, TAG_HOLDER);
            serializer.attribute(null, ATTRIBUTE_NAME, roleHolder);
            serializer.endTag(null, TAG_HOLDER);
        }
    }

    /**
     * Read the state from file.
     */
    @GuardedBy("RoleManagerService.mLock")
    public void readSyncLocked() {
        if (mRoles != null) {
            throw new IllegalStateException("This RoleUserState has already read the XML file");
        }
        File file = getFile(mUserId);
        FileInputStream in;
        try {
            in = new AtomicFile(file).openRead();
        } catch (FileNotFoundException e) {
            Slog.i(LOG_TAG, "No roles file found");
            return;
        }

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            parseXmlLocked(parser);
        } catch (XmlPullParserException | IOException e) {
            throw new IllegalStateException("Failed to parse roles file: " + file , e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private void parseXmlLocked(@NonNull XmlPullParser parser) throws IOException,
            XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (parser.getName().equals(TAG_ROLES)) {
                parseRolesLocked(parser);
                return;
            }
        }
    }

    private void parseRolesLocked(@NonNull XmlPullParser parser) throws IOException,
            XmlPullParserException {
        mVersion = Integer.parseInt(parser.getAttributeValue(null, ATTRIBUTE_VERSION));
        mRoles = new ArrayMap<>();
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (parser.getName().equals(TAG_ROLE)) {
                String roleName = parser.getAttributeValue(null, ATTRIBUTE_NAME);
                ArraySet<String> roleHolders = parseRoleHoldersLocked(parser);
                mRoles.put(roleName, roleHolders);
            }
        }
    }

    @NonNull
    private ArraySet<String> parseRoleHoldersLocked(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        ArraySet<String> roleHolders = new ArraySet<>();
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (parser.getName().equals(TAG_HOLDER)) {
                String roleHolder = parser.getAttributeValue(null, ATTRIBUTE_NAME);
                roleHolders.add(roleHolder);
            }
        }
        return roleHolders;
    }

    /**
     * Destroy this state and delete the corresponding file. Any pending writes to the file will be
     * cancelled and any future interaction with this state will throw an exception.
     */
    @GuardedBy("RoleManagerService.mLock")
    public void destroySyncLocked() {
        throwIfDestroyedLocked();
        mWriteHandler.removeCallbacksAndMessages(null);
        getFile(mUserId).delete();
        mDestroyed = true;
    }

    @GuardedBy("RoleManagerService.mLock")
    private void throwIfDestroyedLocked() {
        if (mDestroyed) {
            throw new IllegalStateException("This RoleUserState has already been destroyed");
        }
    }

    private static @NonNull File getFile(@UserIdInt int userId) {
        return new File(Environment.getUserSystemDirectory(userId), ROLES_FILE_NAME);
    }
}
