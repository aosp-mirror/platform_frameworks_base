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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;
import android.net.MacAddress;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.CollectionUtils;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Association store for CRUD.
 */
@SuppressLint("LongLogTag")
public class AssociationStore {

    @IntDef(prefix = { "CHANGE_TYPE_" }, value = {
            CHANGE_TYPE_ADDED,
            CHANGE_TYPE_REMOVED,
            CHANGE_TYPE_UPDATED_ADDRESS_CHANGED,
            CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ChangeType {}

    public static final int CHANGE_TYPE_ADDED = 0;
    public static final int CHANGE_TYPE_REMOVED = 1;
    public static final int CHANGE_TYPE_UPDATED_ADDRESS_CHANGED = 2;
    public static final int CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED = 3;

    /**  Listener for any changes to associations. */
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
        default void onAssociationAdded(AssociationInfo association) {}

        /**
         * Called when an association is removed.
         */
        default void onAssociationRemoved(AssociationInfo association) {}

        /**
         * Called when an association is updated.
         */
        default void onAssociationUpdated(AssociationInfo association, boolean addressChanged) {}
    }

    private static final String TAG = "CDM_AssociationStore";

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final Map<Integer, AssociationInfo> mIdMap = new HashMap<>();
    @GuardedBy("mLock")
    private final Map<MacAddress, Set<Integer>> mAddressMap = new HashMap<>();
    @GuardedBy("mLock")
    private final SparseArray<List<AssociationInfo>> mCachedPerUser = new SparseArray<>();

    @GuardedBy("mListeners")
    private final Set<OnChangeListener> mListeners = new LinkedHashSet<>();

    /**
     * Add an association.
     */
    public void addAssociation(@NonNull AssociationInfo association) {
        Slog.i(TAG, "Adding new association=" + association);

        // Validity check first.
        checkNotRevoked(association);

        final int id = association.getId();

        synchronized (mLock) {
            if (mIdMap.containsKey(id)) {
                Slog.e(TAG, "Association with id " + id + " already exists.");
                return;
            }
            mIdMap.put(id, association);

            final MacAddress address = association.getDeviceMacAddress();
            if (address != null) {
                mAddressMap.computeIfAbsent(address, it -> new HashSet<>()).add(id);
            }

            invalidateCacheForUserLocked(association.getUserId());

            Slog.i(TAG, "Done adding new association.");
        }

        broadcastChange(CHANGE_TYPE_ADDED, association);
    }

    /**
     * Update an association.
     */
    public void updateAssociation(@NonNull AssociationInfo updated) {
        Slog.i(TAG, "Updating new association=" + updated);
        // Validity check first.
        checkNotRevoked(updated);

        final int id = updated.getId();

        final AssociationInfo current;
        final boolean macAddressChanged;
        synchronized (mLock) {
            current = mIdMap.get(id);
            if (current == null) {
                Slog.w(TAG, "Can't update association. It does not exist.");
                return;
            }

            if (current.equals(updated)) {
                Slog.w(TAG, "Association is the same.");
                return;
            }

            // Update the ID-to-Association map.
            mIdMap.put(id, updated);
            // Invalidate the corresponding user cache entry.
            invalidateCacheForUserLocked(current.getUserId());

            // Update the MacAddress-to-List<Association> map if needed.
            final MacAddress updatedAddress = updated.getDeviceMacAddress();
            final MacAddress currentAddress = current.getDeviceMacAddress();
            macAddressChanged = !Objects.equals(currentAddress, updatedAddress);
            if (macAddressChanged) {
                if (currentAddress != null) {
                    mAddressMap.get(currentAddress).remove(id);
                }
                if (updatedAddress != null) {
                    mAddressMap.computeIfAbsent(updatedAddress, it -> new HashSet<>()).add(id);
                }
            }
            Slog.i(TAG, "Done updating association.");
        }

        final int changeType = macAddressChanged ? CHANGE_TYPE_UPDATED_ADDRESS_CHANGED
                : CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED;
        broadcastChange(changeType, updated);
    }

    /**
     * Remove an association
     */
    public void removeAssociation(int id) {
        Slog.i(TAG, "Removing association id=" + id);

        final AssociationInfo association;
        synchronized (mLock) {
            association = mIdMap.remove(id);

            if (association == null) {
                Slog.w(TAG, "Can't remove association. It does not exist.");
                return;
            }

            final MacAddress macAddress = association.getDeviceMacAddress();
            if (macAddress != null) {
                mAddressMap.get(macAddress).remove(id);
            }

            invalidateCacheForUserLocked(association.getUserId());

            Slog.i(TAG, "Done removing association.");
        }

        broadcastChange(CHANGE_TYPE_REMOVED, association);
    }

    /**
     * @return a "snapshot" of the current state of the existing associations.
     */
    public @NonNull Collection<AssociationInfo> getAssociations() {
        synchronized (mLock) {
            // IMPORTANT: make and return a COPY of the mIdMap.values(), NOT a "direct" reference.
            // The HashMap.values() returns a collection which is backed by the HashMap, so changes
            // to the HashMap are reflected in this collection.
            // For us this means that if mIdMap is modified while the iteration over mIdMap.values()
            // is in progress it may lead to "undefined results" (according to the HashMap's
            // documentation) or cause ConcurrentModificationExceptions in the iterator (according
            // to the bugreports...).
            return List.copyOf(mIdMap.values());
        }
    }

    /**
     * Get associations for the user.
     */
    public @NonNull List<AssociationInfo> getAssociationsForUser(@UserIdInt int userId) {
        synchronized (mLock) {
            return getAssociationsForUserLocked(userId);
        }
    }

    /**
     * Get associations for the package
     */
    public @NonNull List<AssociationInfo> getAssociationsForPackage(
            @UserIdInt int userId, @NonNull String packageName) {
        final List<AssociationInfo> associationsForUser = getAssociationsForUser(userId);
        final List<AssociationInfo> associationsForPackage =
                CollectionUtils.filter(associationsForUser,
                        it -> it.getPackageName().equals(packageName));
        return Collections.unmodifiableList(associationsForPackage);
    }

    /**
     * Get associations by mac address for the package.
     */
    public @Nullable AssociationInfo getAssociationsForPackageWithAddress(
            @UserIdInt int userId, @NonNull String packageName, @NonNull String macAddress) {
        final List<AssociationInfo> associations = getAssociationsByAddress(macAddress);
        return CollectionUtils.find(associations,
                it -> it.belongsToPackage(userId, packageName));
    }

    /**
     * Get association by id.
     */
    public @Nullable AssociationInfo getAssociationById(int id) {
        synchronized (mLock) {
            return mIdMap.get(id);
        }
    }

    /**
     * Get associations by mac address.
     */
    @NonNull
    public List<AssociationInfo> getAssociationsByAddress(@NonNull String macAddress) {
        final MacAddress address = MacAddress.fromString(macAddress);

        synchronized (mLock) {
            final Set<Integer> ids = mAddressMap.get(address);
            if (ids == null) return Collections.emptyList();

            final List<AssociationInfo> associations = new ArrayList<>(ids.size());
            for (Integer id : ids) {
                associations.add(mIdMap.get(id));
            }

            return Collections.unmodifiableList(associations);
        }
    }

    @GuardedBy("mLock")
    @NonNull
    private List<AssociationInfo> getAssociationsForUserLocked(@UserIdInt int userId) {
        final List<AssociationInfo> cached = mCachedPerUser.get(userId);
        if (cached != null) {
            return cached;
        }

        final List<AssociationInfo> associationsForUser = new ArrayList<>();
        for (AssociationInfo association : mIdMap.values()) {
            if (association.getUserId() == userId) {
                associationsForUser.add(association);
            }
        }
        final List<AssociationInfo> set = Collections.unmodifiableList(associationsForUser);
        mCachedPerUser.set(userId, set);
        return set;
    }

    @GuardedBy("mLock")
    private void invalidateCacheForUserLocked(@UserIdInt int userId) {
        mCachedPerUser.delete(userId);
    }

    /**
     * Register a listener for association changes.
     */
    public void registerListener(@NonNull OnChangeListener listener) {
        synchronized (mListeners) {
            mListeners.add(listener);
        }
    }

    /**
     * Unregister a listener previously registered for association changes.
     */
    public void unregisterListener(@NonNull OnChangeListener listener) {
        synchronized (mListeners) {
            mListeners.remove(listener);
        }
    }

    /**
     * Dumps current companion device association states.
     */
    public void dump(@NonNull PrintWriter out) {
        out.append("Companion Device Associations: ");
        if (getAssociations().isEmpty()) {
            out.append("<empty>\n");
        } else {
            out.append("\n");
            for (AssociationInfo a : getAssociations()) {
                out.append("  ").append(a.toString()).append('\n');
            }
        }
    }

    private void broadcastChange(@ChangeType int changeType, AssociationInfo association) {
        synchronized (mListeners) {
            for (OnChangeListener listener : mListeners) {
                listener.onAssociationChanged(changeType, association);
            }
        }
    }

    /**
     * Set associations to cache. It will clear the existing cache.
     */
    public void setAssociationsToCache(Collection<AssociationInfo> associations) {
        // Validity check first.
        associations.forEach(AssociationStore::checkNotRevoked);

        synchronized (mLock) {
            mIdMap.clear();
            mAddressMap.clear();
            mCachedPerUser.clear();

            for (AssociationInfo association : associations) {
                final int id = association.getId();
                mIdMap.put(id, association);

                final MacAddress address = association.getDeviceMacAddress();
                if (address != null) {
                    mAddressMap.computeIfAbsent(address, it -> new HashSet<>()).add(id);
                }
            }
        }
    }

    private static void checkNotRevoked(@NonNull AssociationInfo association) {
        if (association.isRevoked()) {
            throw new IllegalArgumentException(
                    "Revoked (removed) associations MUST NOT appear in the AssociationStore");
        }
    }
}
