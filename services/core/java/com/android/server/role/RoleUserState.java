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
import java.util.List;

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
    private int mVersion;

    /**
     * Maps role names to its holders' package names. The values should never be null.
     */
    @GuardedBy("RoleManagerService.mLock")
    private ArrayMap<String, ArraySet<String>> mRoles;

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
        if (mVersion == version) {
            return;
        }
        mVersion = version;
        writeAsyncLocked();
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
     * Set the names of all available roles.
     *
     * @param roleNames the names of all the available roles
     */
    @GuardedBy("RoleManagerService.mLock")
    public void setRoleNamesLocked(@NonNull List<String> roleNames) {
        throwIfDestroyedLocked();
        boolean changed = false;
        for (int i = mRoles.size() - 1; i >= 0; i--) {
            String roleName = mRoles.keyAt(i);
            if (!roleNames.contains(roleName)) {
                ArraySet<String> packageNames = mRoles.valueAt(i);
                if (!packageNames.isEmpty()) {
                    Slog.e(LOG_TAG, "Holders of a removed role should have been cleaned up, role: "
                            + roleName + ", holders: " + packageNames);
                }
                mRoles.removeAt(i);
                changed = true;
            }
        }
        int roleNamesSize = roleNames.size();
        for (int i = 0; i < roleNamesSize; i++) {
            String roleName = roleNames.get(i);
            if (!mRoles.containsKey(roleName)) {
                mRoles.put(roleName, new ArraySet<>());
                Slog.i(LOG_TAG, "Added new role: " + roleName);
                changed = true;
            }
        }
        if (changed) {
            writeAsyncLocked();
        }
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
            Slog.e(LOG_TAG, "Cannot add role holder for unknown role, role: " + roleName
                    + ", package: " + packageName);
            return false;
        }
        boolean changed = roleHolders.add(packageName);
        if (changed) {
            writeAsyncLocked();
        }
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
            Slog.e(LOG_TAG, "Cannot remove role holder for unknown role, role: " + roleName
                    + ", package: " + packageName);
            return false;
        }
        boolean changed = roleHolders.remove(packageName);
        if (changed) {
            writeAsyncLocked();
        }
        return true;
    }

    /**
     * Schedule writing the state to file.
     */
    @GuardedBy("RoleManagerService.mLock")
    private void writeAsyncLocked() {
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
        // TODO: Throttle writes.
        mWriteHandler.sendMessage(PooledLambda.obtainMessage(
                RoleUserState::writeSync, this, version, roles));
    }

    @WorkerThread
    private void writeSync(int version, @NonNull ArrayMap<String, ArraySet<String>> roles) {
        AtomicFile atomicFile = new AtomicFile(getFile(mUserId), "roles-" + mUserId);
        FileOutputStream out = null;
        try {
            out = atomicFile.startWrite();

            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, StandardCharsets.UTF_8.name());
            serializer.setFeature(
                    "http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);

            serializeRoles(serializer, version, roles);

            serializer.endDocument();
            atomicFile.finishWrite(out);
        } catch (IllegalArgumentException | IllegalStateException | IOException e) {
            Slog.wtf(LOG_TAG, "Failed to write roles.xml, restoring backup", e);
            if (out != null) {
                atomicFile.failWrite(out);
            }
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
            throw new IllegalStateException("This RoleUserState has already read the roles.xml");
        }

        File file = getFile(mUserId);
        try (FileInputStream in = new AtomicFile(file).openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            parseXmlLocked(parser);
        } catch (FileNotFoundException e) {
            Slog.i(LOG_TAG, "roles.xml not found");
            mRoles = new ArrayMap<>();
            mVersion = VERSION_UNDEFINED;
        } catch (XmlPullParserException | IOException e) {
            throw new IllegalStateException("Failed to parse roles.xml: " + file, e);
        }
    }

    private void parseXmlLocked(@NonNull XmlPullParser parser) throws IOException,
            XmlPullParserException {
        int type;
        int depth;
        int innerDepth = parser.getDepth() + 1;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (depth > innerDepth || type != XmlPullParser.START_TAG) {
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

        int type;
        int depth;
        int innerDepth = parser.getDepth() + 1;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (depth > innerDepth || type != XmlPullParser.START_TAG) {
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

        int type;
        int depth;
        int innerDepth = parser.getDepth() + 1;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && ((depth = parser.getDepth()) >= innerDepth || type != XmlPullParser.END_TAG)) {
            if (depth > innerDepth || type != XmlPullParser.START_TAG) {
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
