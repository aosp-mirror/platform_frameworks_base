/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.display;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a collection of {@link LogicalDisplay}s which act in unison for certain behaviors and
 * operations; particularly display-state.
 *
 * @hide
 */
public class DisplayGroup {

    private final List<LogicalDisplay> mDisplays = new ArrayList<>();
    private final int mGroupId;

    private int mChangeCount;

    DisplayGroup(int groupId) {
        mGroupId = groupId;
    }

    /** Returns the identifier for the Group. */
    int getGroupId() {
        return mGroupId;
    }

    /**
     * Adds the provided {@code display} to the Group
     *
     * @param display the {@link LogicalDisplay} to add to the Group
     */
    void addDisplayLocked(LogicalDisplay display) {
        if (!containsLocked(display)) {
            mChangeCount++;
            mDisplays.add(display);
        }
    }

    boolean containsLocked(LogicalDisplay display) {
        return mDisplays.contains(display);
    }

    /**
     * Removes the provided {@code display} from the Group.
     *
     * @param display The {@link LogicalDisplay} to remove from the Group.
     * @return {@code true} if the {@code display} was removed; otherwise {@code false}
     */
    boolean removeDisplayLocked(LogicalDisplay display) {
        mChangeCount++;
        return mDisplays.remove(display);
    }

    /** Returns {@code true} if there are no {@link LogicalDisplay LogicalDisplays} in the Group. */
    boolean isEmptyLocked() {
        return mDisplays.isEmpty();
    }

    /** Returns a count of the changes made to this display group. */
    int getChangeCountLocked() {
        return mChangeCount;
    }

    /** Returns the number of {@link LogicalDisplay LogicalDisplays} in the Group. */
    int getSizeLocked() {
        return mDisplays.size();
    }

    /** Returns the ID of the {@link LogicalDisplay} at the provided {@code index}. */
    int getIdLocked(int index) {
        return mDisplays.get(index).getDisplayIdLocked();
    }
}
