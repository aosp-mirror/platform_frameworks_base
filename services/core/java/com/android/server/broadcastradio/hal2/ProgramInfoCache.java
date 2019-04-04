/**
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.broadcastradio.hal2;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ProgramInfoCache {
    // Maximum number of RadioManager.ProgramInfo elements that will be put into a
    // ProgramList.Chunk.mModified array. Used to try to ensure a single ProgramList.Chunk stays
    // within the AIDL data size limit.
    private static final int MAX_NUM_MODIFIED_PER_CHUNK = 100;

    // Maximum number of ProgramSelector.Identifier elements that will be put into a
    // ProgramList.Chunk.mRemoved array. Used to try to ensure a single ProgramList.Chunk stays
    // within the AIDL data size limit.
    private static final int MAX_NUM_REMOVED_PER_CHUNK = 500;

    // Map from primary identifier to corresponding ProgramInfo.
    private final Map<ProgramSelector.Identifier, RadioManager.ProgramInfo> mProgramInfoMap =
            new HashMap<>();

    // Optional filter used in filterAndUpdateFrom(). Usually this field is null for a HAL-side
    // cache and non-null for an AIDL-side cache.
    private final ProgramList.Filter mFilter;

    ProgramInfoCache(@Nullable ProgramList.Filter filter) {
        mFilter = filter;
    }

    // Constructor for testing.
    @VisibleForTesting
    ProgramInfoCache(@Nullable ProgramList.Filter filter,
            RadioManager.ProgramInfo... programInfos) {
        mFilter = filter;
        for (RadioManager.ProgramInfo programInfo : programInfos) {
            mProgramInfoMap.put(programInfo.getSelector().getPrimaryId(), programInfo);
        }
    }

    @VisibleForTesting
    boolean programInfosAreExactly(RadioManager.ProgramInfo... programInfos) {
        Map<ProgramSelector.Identifier, RadioManager.ProgramInfo> expectedMap = new HashMap<>();
        for (RadioManager.ProgramInfo programInfo : programInfos) {
            expectedMap.put(programInfo.getSelector().getPrimaryId(), programInfo);
        }
        return expectedMap.equals(mProgramInfoMap);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ProgramInfoCache(");
        mProgramInfoMap.forEach((id, programInfo) -> {
            sb.append("\n");
            sb.append(programInfo.toString());
        });
        sb.append(")");
        return sb.toString();
    }

    public @Nullable ProgramList.Filter getFilter() {
        return mFilter;
    }

    void updateFromHalProgramListChunk(
            @NonNull android.hardware.broadcastradio.V2_0.ProgramListChunk chunk) {
        if (chunk.purge) {
            mProgramInfoMap.clear();
        }
        for (android.hardware.broadcastradio.V2_0.ProgramInfo halProgramInfo : chunk.modified) {
            RadioManager.ProgramInfo programInfo = Convert.programInfoFromHal(halProgramInfo);
            mProgramInfoMap.put(programInfo.getSelector().getPrimaryId(), programInfo);
        }
        for (android.hardware.broadcastradio.V2_0.ProgramIdentifier halProgramId : chunk.removed) {
            mProgramInfoMap.remove(Convert.programIdentifierFromHal(halProgramId));
        }
    }

    @NonNull List<ProgramList.Chunk> filterAndUpdateFrom(@NonNull ProgramInfoCache other,
            boolean purge) {
        return filterAndUpdateFromInternal(other, purge, MAX_NUM_MODIFIED_PER_CHUNK,
                MAX_NUM_REMOVED_PER_CHUNK);
    }

    @VisibleForTesting
    @NonNull List<ProgramList.Chunk> filterAndUpdateFromInternal(@NonNull ProgramInfoCache other,
            boolean purge, int maxNumModifiedPerChunk, int maxNumRemovedPerChunk) {
        if (purge) {
            mProgramInfoMap.clear();
        }
        // If mProgramInfoMap is empty, we treat this update as a purge because this might be the
        // first update to an AIDL client that changed its filter.
        if (mProgramInfoMap.isEmpty()) {
            purge = true;
        }

        Set<Integer> idTypes = mFilter != null ? mFilter.getIdentifierTypes() : null;
        Set<ProgramSelector.Identifier> ids = mFilter != null ? mFilter.getIdentifiers() : null;
        boolean includeCategories = mFilter != null ? mFilter.areCategoriesIncluded() : true;
        boolean includeModifications = mFilter != null ? !mFilter.areModificationsExcluded() : true;

        Set<RadioManager.ProgramInfo> modified = new HashSet<>();
        Set<ProgramSelector.Identifier> removed = new HashSet<>(mProgramInfoMap.keySet());
        for (Map.Entry<ProgramSelector.Identifier, RadioManager.ProgramInfo> entry
                : other.mProgramInfoMap.entrySet()) {
            ProgramSelector.Identifier id = entry.getKey();
            if ((idTypes != null && !idTypes.isEmpty() && !idTypes.contains(id.getType()))
                    || (ids != null && !ids.isEmpty() && !ids.contains(id))
                    || (!includeCategories && id.isCategoryType())) {
                continue;
            }

            removed.remove(id);
            RadioManager.ProgramInfo oldInfo = mProgramInfoMap.get(id);
            RadioManager.ProgramInfo newInfo = entry.getValue();
            if (oldInfo != null && (!includeModifications || oldInfo.equals(newInfo))) {
                continue;
            }
            mProgramInfoMap.put(id, newInfo);
            modified.add(newInfo);
        }
        for (ProgramSelector.Identifier rem : removed) {
            mProgramInfoMap.remove(rem);
        }
        return buildChunks(purge, modified, maxNumModifiedPerChunk, removed, maxNumRemovedPerChunk);
    }

    private static int roundUpFraction(int numerator, int denominator) {
        return (numerator / denominator) + (numerator % denominator > 0 ? 1 : 0);
    }

    private @NonNull List<ProgramList.Chunk> buildChunks(boolean purge,
            @Nullable Collection<RadioManager.ProgramInfo> modified, int maxNumModifiedPerChunk,
            @Nullable Collection<ProgramSelector.Identifier> removed, int maxNumRemovedPerChunk) {
        // Communication protocol requires that if purge is set, removed is empty.
        if (purge) {
            removed = null;
        }

        // Determine number of chunks we need to send.
        int numChunks = 0;
        if (modified != null) {
            numChunks = roundUpFraction(modified.size(), maxNumModifiedPerChunk);
        }
        if (removed != null) {
            numChunks = Math.max(numChunks, roundUpFraction(removed.size(), maxNumRemovedPerChunk));
        }
        if (numChunks == 0) {
            return new ArrayList<ProgramList.Chunk>();
        }

        // Try to make similarly-sized chunks by evenly distributing elements from modified and
        // removed among them.
        int modifiedPerChunk = 0;
        int removedPerChunk = 0;
        Iterator<RadioManager.ProgramInfo> modifiedIter = null;
        Iterator<ProgramSelector.Identifier> removedIter = null;
        if (modified != null) {
            modifiedPerChunk = roundUpFraction(modified.size(), numChunks);
            modifiedIter = modified.iterator();
        }
        if (removed != null) {
            removedPerChunk = roundUpFraction(removed.size(), numChunks);
            removedIter = removed.iterator();
        }
        List<ProgramList.Chunk> chunks = new ArrayList<ProgramList.Chunk>(numChunks);
        for (int i = 0; i < numChunks; i++) {
            HashSet<RadioManager.ProgramInfo> modifiedChunk = new HashSet<>();
            HashSet<ProgramSelector.Identifier> removedChunk = new HashSet<>();
            if (modifiedIter != null) {
                for (int j = 0; j < modifiedPerChunk && modifiedIter.hasNext(); j++) {
                    modifiedChunk.add(modifiedIter.next());
                }
            }
            if (removedIter != null) {
                for (int j = 0; j < removedPerChunk && removedIter.hasNext(); j++) {
                    removedChunk.add(removedIter.next());
                }
            }
            chunks.add(new ProgramList.Chunk(purge && i == 0, i == numChunks - 1, modifiedChunk,
                      removedChunk));
        }
        return chunks;
    }
}
