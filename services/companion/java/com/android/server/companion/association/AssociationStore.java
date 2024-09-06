/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion.association;

import static com.android.server.companion.utils.MetricUtils.logCreateAssociation;
import static com.android.server.companion.utils.MetricUtils.logRemoveAssociation;
import static com.android.server.companion.utils.PermissionsUtils.enforceCallerCanManageAssociationsForPackage;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;
import android.companion.IOnAssociationsChangedListener;
import android.content.Context;
import android.content.pm.UserInfo;
import android.net.MacAddress;
import android.os.Binder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.CollectionUtils;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Association store for CRUD.
 */
@SuppressLint("LongLogTag")
public class AssociationStore {

    @IntDef(prefix = {"CHANGE_TYPE_"}, value = {
            CHANGE_TYPE_ADDED,
            CHANGE_TYPE_REMOVED,
            CHANGE_TYPE_UPDATED_ADDRESS_CHANGED,
            CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ChangeType {
    }

    public static final int CHANGE_TYPE_ADDED = 0;
    public static final int CHANGE_TYPE_REMOVED = 1;
    public static final int CHANGE_TYPE_UPDATED_ADDRESS_CHANGED = 2;
    public static final int CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED = 3;

    /** Listener for any changes to associations. */
    public interface OnChangeListener {
        /**
         * Called when there are association changes.
         */
        default void onAssociationChanged(
                @AssociationStore.ChangeType int changeType, AssociationInfo association) {
            switch (changeType) {
                case CHANGE_TYPE_ADDED:
                    onAssociationAdded(association);
                    break;

                case CHANGE_TYPE_REMOVED:
                    onAssociationRemoved(association);
                    break;

                case CHANGE_TYPE_UPDATED_ADDRESS_CHANGED:
                    onAssociationUpdated(association, true);
                    break;

                case CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED:
                    onAssociationUpdated(association, false);
                    break;
            }
        }

        /**
         * Called when an association is added.
         */
        default void onAssociationAdded(AssociationInfo association) {
        }

        /**
         * Called when an association is removed.
         */
        default void onAssociationRemoved(AssociationInfo association) {
        }

        /**
         * Called when an association is updated.
         */
        default void onAssociationUpdated(AssociationInfo association, boolean addressChanged) {
        }
    }

    private static final String TAG = "CDM_AssociationStore";

    private final Context mContext;
    private final UserManager mUserManager;
    private final AssociationDiskStore mDiskStore;
    private final ExecutorService mExecutor;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private boolean mPersisted = false;
    @GuardedBy("mLock")
    private final Map<Integer, AssociationInfo> mIdToAssociationMap = new HashMap<>();
    @GuardedBy("mLock")
    private int mMaxId = 0;

    @GuardedBy("mLocalListeners")
    private final Set<OnChangeListener> mLocalListeners = new LinkedHashSet<>();
    @GuardedBy("mRemoteListeners")
    private final RemoteCallbackList<IOnAssociationsChangedListener> mRemoteListeners =
            new RemoteCallbackList<>();

    public AssociationStore(Context context, UserManager userManager,
            AssociationDiskStore diskStore) {
        mContext = context;
        mUserManager = userManager;
        mDiskStore = diskStore;
        mExecutor = Executors.newSingleThreadExecutor();
    }

    /**
     * Load all alive users' associations from disk to cache.
     */
    public void refreshCache() {
        Binder.withCleanCallingIdentity(() -> {
            List<Integer> userIds = new ArrayList<>();
            for (UserInfo user : mUserManager.getAliveUsers()) {
                userIds.add(user.id);
            }

            synchronized (mLock) {
                mPersisted = false;

                mIdToAssociationMap.clear();
                mMaxId = 0;

                // The data is stored in DE directories, so we can read the data for all users now
                // (which would not be possible if the data was stored to CE directories).
                Map<Integer, Associations> userToAssociationsMap =
                        mDiskStore.readAssociationsByUsers(userIds);
                for (Map.Entry<Integer, Associations> entry : userToAssociationsMap.entrySet()) {
                    for (AssociationInfo association : entry.getValue().getAssociations()) {
                        mIdToAssociationMap.put(association.getId(), association);
                    }
                    mMaxId = Math.max(mMaxId, entry.getValue().getMaxId());
                }

                mPersisted = true;
            }
        });
    }

    /**
     * Get the current max association id.
     */
    public int getMaxId() {
        synchronized (mLock) {
            return mMaxId;
        }
    }

    /**
     * Get the next available association id.
     */
    public int getNextId() {
        synchronized (mLock) {
            return getMaxId() + 1;
        }
    }

    /**
     * Add an association.
     */
    public void addAssociation(@NonNull AssociationInfo association) {
        Slog.i(TAG, "Adding new association=[" + association + "]...");

        final int id = association.getId();
        final int userId = association.getUserId();

        synchronized (mLock) {
            if (mIdToAssociationMap.containsKey(id)) {
                Slog.e(TAG, "Association id=[" + id + "] already exists.");
                return;
            }

            mIdToAssociationMap.put(id, association);
            mMaxId = Math.max(mMaxId, id);

            writeCacheToDisk(userId);

            Slog.i(TAG, "Done adding new association.");
        }

        logCreateAssociation(association.getDeviceProfile());

        if (association.isActive()) {
            broadcastChange(CHANGE_TYPE_ADDED, association);
        }
    }

    /**
     * Update an association.
     */
    public void updateAssociation(@NonNull AssociationInfo updated) {
        Slog.i(TAG, "Updating new association=[" + updated + "]...");

        final int id = updated.getId();
        final AssociationInfo current;
        final boolean macAddressChanged;

        synchronized (mLock) {
            current = mIdToAssociationMap.get(id);
            if (current == null) {
                Slog.w(TAG, "Can't update association id=[" + id + "]. It does not exist.");
                return;
            }

            if (current.equals(updated)) {
                Slog.w(TAG, "Association is the same.");
                return;
            }

            mIdToAssociationMap.put(id, updated);

            writeCacheToDisk(updated.getUserId());
        }

        Slog.i(TAG, "Done updating association.");

        if (current.isActive() && !updated.isActive()) {
            broadcastChange(CHANGE_TYPE_REMOVED, updated);
            return;
        }

        if (updated.isActive()) {
            // Check if the MacAddress has changed.
            final MacAddress updatedAddress = updated.getDeviceMacAddress();
            final MacAddress currentAddress = current.getDeviceMacAddress();
            macAddressChanged = !Objects.equals(currentAddress, updatedAddress);

            broadcastChange(macAddressChanged ? CHANGE_TYPE_UPDATED_ADDRESS_CHANGED
                    : CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED, updated);
        }
    }

    /**
     * Remove an association.
     */
    public void removeAssociation(int id) {
        Slog.i(TAG, "Removing association id=[" + id + "]...");

        final AssociationInfo association;

        synchronized (mLock) {
            association = mIdToAssociationMap.remove(id);

            if (association == null) {
                Slog.w(TAG, "Can't remove association id=[" + id + "]. It does not exist.");
                return;
            }

            writeCacheToDisk(association.getUserId());

            Slog.i(TAG, "Done removing association.");
        }

        logRemoveAssociation(association.getDeviceProfile());

        if (association.isActive()) {
            broadcastChange(CHANGE_TYPE_REMOVED, association);
        }
    }

    private void writeCacheToDisk(@UserIdInt int userId) {
        mExecutor.execute(() -> {
            Associations associations = new Associations();
            synchronized (mLock) {
                associations.setMaxId(mMaxId);
                associations.setAssociations(
                        CollectionUtils.filter(mIdToAssociationMap.values().stream().toList(),
                                a -> a.getUserId() == userId));
            }
            mDiskStore.writeAssociationsForUser(userId, associations);
        });
    }

    /**
     * Get a copy of all associations including pending and revoked ones.
     * Modifying the copy won't modify the actual associations.
     *
     * If a cache miss happens, read from disk.
     */
    @NonNull
    public List<AssociationInfo> getAssociations() {
        synchronized (mLock) {
            if (!mPersisted) {
                refreshCache();
            }
            return List.copyOf(mIdToAssociationMap.values());
        }
    }

    /**
     * Get a copy of active associations.
     */
    @NonNull
    public List<AssociationInfo> getActiveAssociations() {
        synchronized (mLock) {
            return CollectionUtils.filter(getAssociations(), AssociationInfo::isActive);
        }
    }

    /**
     * Get a copy of all associations by user.
     */
    @NonNull
    public List<AssociationInfo> getAssociationsByUser(@UserIdInt int userId) {
        synchronized (mLock) {
            return CollectionUtils.filter(getAssociations(), a -> a.getUserId() == userId);
        }
    }

    /**
     * Get a copy of active associations by user.
     */
    @NonNull
    public List<AssociationInfo> getActiveAssociationsByUser(@UserIdInt int userId) {
        synchronized (mLock) {
            return CollectionUtils.filter(getActiveAssociations(), a -> a.getUserId() == userId);
        }
    }

    /**
     * Get a copy of all associations by package.
     */
    @NonNull
    public List<AssociationInfo> getAssociationsByPackage(@UserIdInt int userId,
            @NonNull String packageName) {
        synchronized (mLock) {
            return CollectionUtils.filter(getAssociationsByUser(userId),
                    a -> a.getPackageName().equals(packageName));
        }
    }

    /**
     * Get a copy of active associations by package.
     */
    @NonNull
    public List<AssociationInfo> getActiveAssociationsByPackage(@UserIdInt int userId,
            @NonNull String packageName) {
        synchronized (mLock) {
            return CollectionUtils.filter(getActiveAssociationsByUser(userId),
                    a -> a.getPackageName().equals(packageName));
        }
    }

    /**
     * Get the first active association with the mac address.
     */
    @Nullable
    public AssociationInfo getFirstAssociationByAddress(
            @UserIdInt int userId, @NonNull String packageName, @NonNull String macAddress) {
        synchronized (mLock) {
            return CollectionUtils.find(getActiveAssociationsByPackage(userId, packageName),
                    a -> a.getDeviceMacAddress() != null && a.getDeviceMacAddress()
                            .equals(MacAddress.fromString(macAddress)));
        }
    }

    /**
     * Get the association by id.
     */
    @Nullable
    public AssociationInfo getAssociationById(int id) {
        synchronized (mLock) {
            return mIdToAssociationMap.get(id);
        }
    }

    /**
     * Get a copy of active associations by mac address.
     */
    @NonNull
    public List<AssociationInfo> getActiveAssociationsByAddress(@NonNull String macAddress) {
        synchronized (mLock) {
            return CollectionUtils.filter(getActiveAssociations(),
                    a -> a.getDeviceMacAddress() != null && a.getDeviceMacAddress()
                            .equals(MacAddress.fromString(macAddress)));
        }
    }

    /**
     * Get a copy of revoked associations.
     */
    @NonNull
    public List<AssociationInfo> getRevokedAssociations() {
        synchronized (mLock) {
            return CollectionUtils.filter(getAssociations(), AssociationInfo::isRevoked);
        }
    }

    /**
     * Get a copy of revoked associations for the package.
     */
    @NonNull
    public List<AssociationInfo> getRevokedAssociations(@UserIdInt int userId,
            @NonNull String packageName) {
        synchronized (mLock) {
            return CollectionUtils.filter(getAssociations(),
                    a -> packageName.equals(a.getPackageName()) && a.getUserId() == userId
                            && a.isRevoked());
        }
    }

    /**
     * Get a copy of active associations.
     */
    @NonNull
    public List<AssociationInfo> getPendingAssociations(@UserIdInt int userId,
            @NonNull String packageName) {
        synchronized (mLock) {
            return CollectionUtils.filter(getAssociations(),
                    a -> packageName.equals(a.getPackageName()) && a.getUserId() == userId
                            && a.isPending());
        }
    }

    /**
     * Get association by id with caller checks.
     *
     * If the association is not found, an IllegalArgumentException would be thrown.
     *
     * If the caller can't access the association, a SecurityException would be thrown.
     */
    @NonNull
    public AssociationInfo getAssociationWithCallerChecks(int associationId) {
        AssociationInfo association = getAssociationById(associationId);
        if (association == null) {
            throw new IllegalArgumentException(
                    "getAssociationWithCallerChecks() Association id=[" + associationId
                            + "] doesn't exist.");
        }
        enforceCallerCanManageAssociationsForPackage(mContext, association.getUserId(),
                association.getPackageName(), null);
        return association;
    }

    /**
     * Register a local listener for association changes.
     */
    public void registerLocalListener(@NonNull OnChangeListener listener) {
        synchronized (mLocalListeners) {
            mLocalListeners.add(listener);
        }
    }

    /**
     * Unregister a local listener previously registered for association changes.
     */
    public void unregisterLocalListener(@NonNull OnChangeListener listener) {
        synchronized (mLocalListeners) {
            mLocalListeners.remove(listener);
        }
    }

    /**
     * Register a remote listener for association changes.
     */
    public void registerRemoteListener(@NonNull IOnAssociationsChangedListener listener,
            int userId) {
        synchronized (mRemoteListeners) {
            mRemoteListeners.register(listener, userId);
        }
    }

    /**
     * Unregister a remote listener previously registered for association changes.
     */
    public void unregisterRemoteListener(@NonNull IOnAssociationsChangedListener listener) {
        synchronized (mRemoteListeners) {
            mRemoteListeners.unregister(listener);
        }
    }

    /**
     * Dumps current companion device association states.
     */
    public void dump(@NonNull PrintWriter out) {
        out.append("Companion Device Associations: ");
        if (getActiveAssociations().isEmpty()) {
            out.append("<empty>\n");
        } else {
            out.append("\n");
            for (AssociationInfo a : getActiveAssociations()) {
                out.append("  ").append(a.toString()).append('\n');
            }
        }
    }

    private void broadcastChange(@ChangeType int changeType, AssociationInfo association) {
        Slog.i(TAG, "Broadcasting association changes - changeType=[" + changeType + "]...");

        synchronized (mLocalListeners) {
            for (OnChangeListener listener : mLocalListeners) {
                listener.onAssociationChanged(changeType, association);
            }
        }
        synchronized (mRemoteListeners) {
            final int userId = association.getUserId();
            final List<AssociationInfo> updatedAssociations = getActiveAssociationsByUser(userId);
            // Notify listeners if ADDED, REMOVED or UPDATED_ADDRESS_CHANGED.
            // Do NOT notify when UPDATED_ADDRESS_UNCHANGED, which means a minor tweak in
            // association's configs, which "listeners" won't (and shouldn't) be able to see.
            if (changeType != CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED) {
                mRemoteListeners.broadcast((listener, callbackUserId) -> {
                    int listenerUserId = (int) callbackUserId;
                    if (listenerUserId == userId || listenerUserId == UserHandle.USER_ALL) {
                        try {
                            listener.onAssociationsChanged(updatedAssociations);
                        } catch (RemoteException ignored) {
                        }
                    }
                });
            }
        }
    }
}
