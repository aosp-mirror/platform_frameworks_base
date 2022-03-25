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

package com.android.server.companion;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.companion.AssociationInfo;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.List;

/**
 * Interface for a store of {@link AssociationInfo}-s.
 */
public interface AssociationStore {

    @IntDef(prefix = { "CHANGE_TYPE_" }, value = {
            CHANGE_TYPE_ADDED,
            CHANGE_TYPE_REMOVED,
            CHANGE_TYPE_UPDATED_ADDRESS_CHANGED,
            CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ChangeType {}

    int CHANGE_TYPE_ADDED = 0;
    int CHANGE_TYPE_REMOVED = 1;
    int CHANGE_TYPE_UPDATED_ADDRESS_CHANGED = 2;
    int CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED = 3;

    /**  Listener for any changes to {@link AssociationInfo}-s. */
    interface OnChangeListener {
        default void onAssociationChanged(
                @ChangeType int changeType, AssociationInfo association) {
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

        default void onAssociationAdded(AssociationInfo association) {}

        default void onAssociationRemoved(AssociationInfo association) {}

        default void onAssociationUpdated(AssociationInfo association, boolean addressChanged) {}
    }

    /**
     * @return all CDM associations.
     */
    @NonNull
    Collection<AssociationInfo> getAssociations();

    /**
     * @return a {@link List} of associations that belong to the user.
     */
    @NonNull
    List<AssociationInfo> getAssociationsForUser(@UserIdInt int userId);

    /**
     * @return a {@link List} of association that belong to the package.
     */
    @NonNull
    List<AssociationInfo> getAssociationsForPackage(
            @UserIdInt int userId, @NonNull String packageName);

    /**
     * @return an association with the given address that belong to the given package if such an
     * association exists, otherwise {@code null}.
     */
    @Nullable
    AssociationInfo getAssociationsForPackageWithAddress(
            @UserIdInt int userId, @NonNull String packageName, @NonNull String macAddress);

    /**
     * @return an association with the given id if such an association exists, otherwise
     * {@code null}.
     */
    @Nullable
    AssociationInfo getAssociationById(int id);

    /**
     * @return all associations with the given MAc address.
     */
    @NonNull
    List<AssociationInfo> getAssociationsByAddress(@NonNull String macAddress);

    /** Register a {@link OnChangeListener} */
    void registerListener(@NonNull OnChangeListener listener);

    /** Un-register a previously registered {@link OnChangeListener} */
    void unregisterListener(@NonNull OnChangeListener listener);

    /** @hide */
    static String changeTypeToString(@ChangeType int changeType) {
        switch (changeType) {
            case CHANGE_TYPE_ADDED:
                return "ASSOCIATION_ADDED";

            case CHANGE_TYPE_REMOVED:
                return "ASSOCIATION_REMOVED";

            case CHANGE_TYPE_UPDATED_ADDRESS_CHANGED:
                return "ASSOCIATION_UPDATED";

            case CHANGE_TYPE_UPDATED_ADDRESS_UNCHANGED:
                return "ASSOCIATION_UPDATED_ADDRESS_UNCHANGED";

            default:
                return "Unknown (" + changeType + ")";
        }
    }
}
