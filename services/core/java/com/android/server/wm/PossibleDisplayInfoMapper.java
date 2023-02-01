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

package com.android.server.wm;

import android.hardware.display.DisplayManagerInternal;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Maintains a map of possible {@link DisplayInfo} for displays and states that may be encountered
 * on a device. This is not guaranteed to include all possible device states for all displays.
 *
 * By 'possible', this class only handles device states for displays and display groups it is
 * currently aware of. It can not handle all eventual states the system may enter, for example, if
 * an external display is added, or a new display is added to the group.
 */
public class PossibleDisplayInfoMapper {
    private static final String TAG = "PossibleDisplayInfoMapper";
    private static final boolean DEBUG = false;

    private final DisplayManagerInternal mDisplayManagerInternal;

    /**
     * Map of all logical displays, indexed by logical display id.
     * Each logical display has multiple entries, one for each device state.
     *
     * Emptied and re-calculated when a display is added, removed, or changed.
     */
    private final SparseArray<Set<DisplayInfo>> mDisplayInfos = new SparseArray<>();

    PossibleDisplayInfoMapper(DisplayManagerInternal displayManagerInternal) {
        mDisplayManagerInternal = displayManagerInternal;
    }


    /**
     * Returns, for the given displayId, a list of unique display infos. List contains each
     * supported device state.
     * <p>List contents are guaranteed to be unique, but returned as a list rather than a set to
     * minimize copies needed to make an iteraable data structure.
     */
    public List<DisplayInfo> getPossibleDisplayInfos(int displayId) {
        // Update display infos before returning, since any cached values would have been removed
        // in response to any display event. This model avoids re-computing the cache for every
        // display change event (which occurs extremely frequently in the normal usage of the
        // device).
        updatePossibleDisplayInfos(displayId);
        if (!mDisplayInfos.contains(displayId)) {
            return new ArrayList<>();
        }
        return List.copyOf(mDisplayInfos.get(displayId));
    }

    /**
     * Updates the possible {@link DisplayInfo}s for the given display, by saving the DisplayInfo
     * across supported device states.
     */
    public void updatePossibleDisplayInfos(int displayId) {
        Set<DisplayInfo> displayInfos = mDisplayManagerInternal.getPossibleDisplayInfo(displayId);
        if (DEBUG) {
            Slog.v(TAG, "updatePossibleDisplayInfos, given DisplayInfo "
                    + displayInfos.size() + " on display " + displayId);
        }
        updateDisplayInfos(displayInfos);
    }

    /**
     * For the given displayId, removes all possible {@link DisplayInfo}.
     */
    public void removePossibleDisplayInfos(int displayId) {
        if (DEBUG && mDisplayInfos.get(displayId) != null) {
            Slog.v(TAG, "onDisplayRemoved, remove all DisplayInfo (" + mDisplayInfos.get(
                    displayId).size() + ") with id " + displayId);
        }
        mDisplayInfos.remove(displayId);
    }

    private void updateDisplayInfos(Set<DisplayInfo> displayInfos) {
        // Empty out cache before re-computing.
        mDisplayInfos.clear();
        // Iterate over each logical display layout for the current state.
        for (DisplayInfo di : displayInfos) {
            // Combine all results under the logical display id.
            Set<DisplayInfo> priorDisplayInfos = mDisplayInfos.get(di.displayId, new ArraySet<>());
            priorDisplayInfos.add(di);
            mDisplayInfos.put(di.displayId, priorDisplayInfos);
        }
    }
}
