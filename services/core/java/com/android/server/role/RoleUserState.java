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
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.role.persistence.RolesPersistence;
import com.android.role.persistence.RolesState;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stores the state of roles for a user.
 */
public class RoleUserState {

    private static final String LOG_TAG = RoleUserState.class.getSimpleName();

    public static final int VERSION_UNDEFINED = -1;

    private static final String ROLES_FILE_NAME = "roles.xml";

    private static final long WRITE_DELAY_MILLIS = 200;

    private static final String TAG_ROLES = "roles";
    private static final String TAG_ROLE = "role";
    private static final String TAG_HOLDER = "holder";
    private static final String ATTRIBUTE_VERSION = "version";
    private static final String ATTRIBUTE_NAME = "name";
    private static final String ATTRIBUTE_PACKAGES_HASH = "packagesHash";

    private final RolesPersistence mPersistence = RolesPersistence.createInstance();

    @UserIdInt
    private final int mUserId;

    @NonNull
    private final Callback mCallback;

    @NonNull
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private int mVersion = VERSION_UNDEFINED;

    @GuardedBy("mLock")
    @Nullable
    private String mPackagesHash;

    /**
     * Maps role names to its holders' package names. The values should never be null.
     */
    @GuardedBy("mLock")
    @NonNull
    private ArrayMap<String, ArraySet<String>> mRoles = new ArrayMap<>();

    @GuardedBy("mLock")
    private boolean mWriteScheduled;

    @GuardedBy("mLock")
    private boolean mDestroyed;

    @NonNull
    private final Handler mWriteHandler = new Handler(BackgroundThread.getHandler().getLooper());

    /**
     * Create a new user state, and read its state from disk if previously persisted.
     *
     * @param userId the user id for this user state
     * @param callback the callback for this user state
     */
    public RoleUserState(@UserIdInt int userId, @NonNull Callback callback) {
        mUserId = userId;
        mCallback = callback;

        readFile();
    }

    /**
     * Get the version of this user state.
     */
    public int getVersion() {
        synchronized (mLock) {
            return mVersion;
        }
    }

    /**
     * Set the version of this user state.
     *
     * @param version the version to set
     */
    public void setVersion(int version) {
        synchronized (mLock) {
            if (mVersion == version) {
                return;
            }
            mVersion = version;
            scheduleWriteFileLocked();
        }
    }

    /**
     * Get the hash representing the state of packages during the last time initial grants was run.
     *
     * @return the hash representing the state of packages
     */
    @Nullable
    public String getPackagesHash() {
        synchronized (mLock) {
            return mPackagesHash;
        }
    }

    /**
     * Set the hash representing the state of packages during the last time initial grants was run.
     *
     * @param packagesHash the hash representing the state of packages
     */
    public void setPackagesHash(@Nullable String packagesHash) {
        synchronized (mLock) {
            if (Objects.equals(mPackagesHash, packagesHash)) {
                return;
            }
            mPackagesHash = packagesHash;
            scheduleWriteFileLocked();
        }
    }

    /**
     * Get whether the role is available.
     *
     * @param roleName the name of the role to get the holders for
     *
     * @return whether the role is available
     */
    public boolean isRoleAvailable(@NonNull String roleName) {
        synchronized (mLock) {
            return mRoles.containsKey(roleName);
        }
    }

    /**
     * Get the holders of a role.
     *
     * @param roleName the name of the role to query for
     *
     * @return the set of role holders, or {@code null} if and only if the role is not found
     */
    @Nullable
    public ArraySet<String> getRoleHolders(@NonNull String roleName) {
        synchronized (mLock) {
            ArraySet<String> packageNames = mRoles.get(roleName);
            if (packageNames == null) {
                return null;
            }
            return new ArraySet<>(packageNames);
        }
    }

    /**
     * Adds the given role, effectively marking it as {@link #isRoleAvailable available}
     *
     * @param roleName the name of the role
     *
     * @return whether any changes were made
     */
    public boolean addRoleName(@NonNull String roleName) {
        synchronized (mLock) {
            if (!mRoles.containsKey(roleName)) {
                mRoles.put(roleName, new ArraySet<>());
                Slog.i(LOG_TAG, "Added new role: " + roleName);
                scheduleWriteFileLocked();
                return true;
            } else {
                return false;
            }
        }
    }

    /**
     * Set the names of all available roles.
     *
     * @param roleNames the names of all the available roles
     */
    public void setRoleNames(@NonNull List<String> roleNames) {
        synchronized (mLock) {
            boolean changed = false;

            for (int i = mRoles.size() - 1; i >= 0; i--) {
                String roleName = mRoles.keyAt(i);

                if (!roleNames.contains(roleName)) {
                    ArraySet<String> packageNames = mRoles.valueAt(i);
                    if (!packageNames.isEmpty()) {
                        Slog.e(LOG_TAG, "Holders of a removed role should have been cleaned up,"
                                + " role: " + roleName + ", holders: " + packageNames);
                    }
                    mRoles.removeAt(i);
                    changed = true;
                }
            }

            int roleNamesSize = roleNames.size();
            for (int i = 0; i < roleNamesSize; i++) {
                changed |= addRoleName(roleNames.get(i));
            }

            if (changed) {
                scheduleWriteFileLocked();
            }
        }
    }

    /**
     * Add a holder to a role.
     *
     * @param roleName the name of the role to add the holder to
     * @param packageName the package name of the new holder
     *
     * @return {@code false} if and only if the role is not found
     */
    @CheckResult
    public boolean addRoleHolder(@NonNull String roleName, @NonNull String packageName) {
        boolean changed;

        synchronized (mLock) {
            ArraySet<String> roleHolders = mRoles.get(roleName);
            if (roleHolders == null) {
                Slog.e(LOG_TAG, "Cannot add role holder for unknown role, role: " + roleName
                        + ", package: " + packageName);
                return false;
            }
            changed = roleHolders.add(packageName);
            if (changed) {
                scheduleWriteFileLocked();
            }
        }

        if (changed) {
            mCallback.onRoleHoldersChanged(roleName, mUserId, null, packageName);
        }
        return true;
    }

    /**
     * Remove a holder from a role.
     *
     * @param roleName the name of the role to remove the holder from
     * @param packageName the package name of the holder to remove
     *
     * @return {@code false} if and only if the role is not found
     */
    @CheckResult
    public boolean removeRoleHolder(@NonNull String roleName, @NonNull String packageName) {
        boolean changed;

        synchronized (mLock) {
            ArraySet<String> roleHolders = mRoles.get(roleName);
            if (roleHolders == null) {
                Slog.e(LOG_TAG, "Cannot remove role holder for unknown role, role: " + roleName
                        + ", package: " + packageName);
                return false;
            }

            changed = roleHolders.remove(packageName);
            if (changed) {
                scheduleWriteFileLocked();
            }
        }

        if (changed) {
            mCallback.onRoleHoldersChanged(roleName, mUserId, packageName, null);
        }
        return true;
    }

    /**
     * @see android.app.role.RoleManager#getHeldRolesFromController
     */
    @NonNull
    public List<String> getHeldRoles(@NonNull String packageName) {
        synchronized (mLock) {
            List<String> roleNames = new ArrayList<>();
            int size = mRoles.size();
            for (int i = 0; i < size; i++) {
                if (mRoles.valueAt(i).contains(packageName)) {
                    roleNames.add(mRoles.keyAt(i));
                }
            }
            return roleNames;
        }
    }

    /**
     * Schedule writing the state to file.
     */
    @GuardedBy("mLock")
    private void scheduleWriteFileLocked() {
        if (mDestroyed) {
            return;
        }

        if (!mWriteScheduled) {
            mWriteHandler.sendMessageDelayed(PooledLambda.obtainMessage(RoleUserState::writeFile,
                    this), WRITE_DELAY_MILLIS);
            mWriteScheduled = true;
        }
    }

    @WorkerThread
    private void writeFile() {
        RolesState roles;
        synchronized (mLock) {
            if (mDestroyed) {
                return;
            }

            mWriteScheduled = false;

            roles = new RolesState(mVersion, mPackagesHash,
                    (Map<String, Set<String>>) (Map<String, ?>) snapshotRolesLocked());
        }

        mPersistence.writeForUser(roles, UserHandle.of(mUserId));
    }

    private void readFile() {
        synchronized (mLock) {
            RolesState roles = mPersistence.readForUser(UserHandle.of(mUserId));
            if (roles == null) {
                readLegacyFileLocked();
                scheduleWriteFileLocked();
                return;
            }

            mVersion = roles.getVersion();
            mPackagesHash = roles.getPackagesHash();

            mRoles.clear();
            for (Map.Entry<String, Set<String>> entry : roles.getRoles().entrySet()) {
                String roleName = entry.getKey();
                ArraySet<String> roleHolders = new ArraySet<>(entry.getValue());
                mRoles.put(roleName, roleHolders);
            }
        }
    }

    private void readLegacyFileLocked() {
        File file = getFile(mUserId);
        try (FileInputStream in = new AtomicFile(file).openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            parseXmlLocked(parser);
            Slog.i(LOG_TAG, "Read roles.xml successfully");
        } catch (FileNotFoundException e) {
            Slog.i(LOG_TAG, "roles.xml not found");
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
        Slog.w(LOG_TAG, "Missing <" + TAG_ROLES + "> in roles.xml");
    }

    private void parseRolesLocked(@NonNull XmlPullParser parser) throws IOException,
            XmlPullParserException {
        mVersion = Integer.parseInt(parser.getAttributeValue(null, ATTRIBUTE_VERSION));
        mPackagesHash = parser.getAttributeValue(null, ATTRIBUTE_PACKAGES_HASH);
        mRoles.clear();

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
     * Dump this user state.
     *
     * @param dumpOutputStream the output stream to dump to
     */
    public void dump(@NonNull DualDumpOutputStream dumpOutputStream, @NonNull String fieldName,
            long fieldId) {
        int version;
        String packagesHash;
        ArrayMap<String, ArraySet<String>> roles;
        synchronized (mLock) {
            version = mVersion;
            packagesHash = mPackagesHash;
            roles = snapshotRolesLocked();
        }

        long fieldToken = dumpOutputStream.start(fieldName, fieldId);
        dumpOutputStream.write("user_id", RoleUserStateProto.USER_ID, mUserId);
        dumpOutputStream.write("version", RoleUserStateProto.VERSION, version);
        dumpOutputStream.write("packages_hash", RoleUserStateProto.PACKAGES_HASH, packagesHash);

        int rolesSize = roles.size();
        for (int rolesIndex = 0; rolesIndex < rolesSize; rolesIndex++) {
            String roleName = roles.keyAt(rolesIndex);
            ArraySet<String> roleHolders = roles.valueAt(rolesIndex);

            long rolesToken = dumpOutputStream.start("roles", RoleUserStateProto.ROLES);
            dumpOutputStream.write("name", RoleProto.NAME, roleName);

            int roleHoldersSize = roleHolders.size();
            for (int roleHoldersIndex = 0; roleHoldersIndex < roleHoldersSize; roleHoldersIndex++) {
                String roleHolder = roleHolders.valueAt(roleHoldersIndex);

                dumpOutputStream.write("holders", RoleProto.HOLDERS, roleHolder);
            }

            dumpOutputStream.end(rolesToken);
        }

        dumpOutputStream.end(fieldToken);
    }

    /**
     * Get the roles and their holders.
     *
     * @return A copy of the roles and their holders
     */
    @NonNull
    public ArrayMap<String, ArraySet<String>> getRolesAndHolders() {
        synchronized (mLock) {
            return snapshotRolesLocked();
        }
    }

    @GuardedBy("mLock")
    @NonNull
    private ArrayMap<String, ArraySet<String>> snapshotRolesLocked() {
        ArrayMap<String, ArraySet<String>> roles = new ArrayMap<>();
        for (int i = 0, size = CollectionUtils.size(mRoles); i < size; ++i) {
            String roleName = mRoles.keyAt(i);
            ArraySet<String> roleHolders = mRoles.valueAt(i);

            roleHolders = new ArraySet<>(roleHolders);
            roles.put(roleName, roleHolders);
        }
        return roles;
    }

    /**
     * Destroy this user state and delete the corresponding file. Any pending writes to the file
     * will be cancelled, and any future interaction with this state will throw an exception.
     */
    public void destroy() {
        synchronized (mLock) {
            if (mDestroyed) {
                throw new IllegalStateException("This RoleUserState has already been destroyed");
            }
            mWriteHandler.removeCallbacksAndMessages(null);
            mPersistence.deleteForUser(UserHandle.of(mUserId));
            mDestroyed = true;
        }
    }

    @NonNull
    private static File getFile(@UserIdInt int userId) {
        return new File(Environment.getUserSystemDirectory(userId), ROLES_FILE_NAME);
    }

    /**
     * Callback for a user state.
     */
    public interface Callback {

        /**
         * Called when the holders of roles are changed.
         *
         * @param roleName the name of the role whose holders are changed
         * @param userId the user id for this role holder change
         */
        void onRoleHoldersChanged(@NonNull String roleName, @UserIdInt int userId,
                @Nullable String removedHolder, @Nullable String addedHolder);
    }
}
