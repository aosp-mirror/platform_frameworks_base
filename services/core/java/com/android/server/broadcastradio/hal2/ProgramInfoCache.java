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

    // Flag indicating whether mProgramInfoMap is considered complete based upon the received
    // updates.
    private boolean mComplete = true;

    // Optional filter used in filterAndUpdateFrom(). Usually this field is null for a HAL-side
    // cache and non-null for an AIDL-side cache.
    private final ProgramList.Filter mFilter;

    ProgramInfoCache(@Nullable ProgramList.Filter filter) {
        mFilter = filter;
    }

    // Constructor for testing.
    @VisibleForTesting
    ProgramInfoCache(@Nullable ProgramList.Filter filter, boolean complete,
            RadioManager.ProgramInfo... programInfos) {
        mFilter = filter;
        mComplete = complete;
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
        StringBuilder sb = new StringBuilder("ProgramInfoCache(mComplete = ");
        sb.append(mComplete);
        sb.append(", mFilter = ");
        sb.append(mFilter);
        sb.append(", mProgramInfoMap = [");
        mProgramInfoMap.forEach((id, programInfo) -> {
            sb.append("\n");
            sb.append(programInfo.toString());
        });
        sb.append("]");
        return sb.toString();
    }

    public boolean isComplete() {
        return mComplete;
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
        mComplete = chunk.complete;
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

        Set<RadioManager.ProgramInfo> modified = new HashSet<>();
        Set<ProgramSelector.Identifier> removed = new HashSet<>(mProgramInfoMap.keySet());
        for (Map.Entry<ProgramSelector.Identifier, RadioManager.ProgramInfo> entry
                : other.mProgramInfoMap.entrySet()) {
            ProgramSelector.Identifier id = entry.getKey();
            if (!passesFilter(id)) {
                continue;
            }
            removed.remove(id);

            RadioManager.ProgramInfo newInfo = entry.getValue();
            if (!shouldIncludeInModified(newInfo)) {
                continue;
            }
            mProgramInfoMap.put(id, newInfo);
            modified.add(newInfo);
        }
        for (ProgramSelector.Identifier rem : removed) {
            mProgramInfoMap.remove(rem);
        }
        mComplete = other.mComplete;
        return buildChunks(purge, mComplete, modified, maxNumModifiedPerChunk, removed,
                maxNumRemovedPerChunk);
    }

    @Nullable List<ProgramList.Chunk> filterAndApplyChunk(@NonNull ProgramList.Chunk chunk) {
        return filterAndApplyChunkInternal(chunk, MAX_NUM_MODIFIED_PER_CHUNK,
                MAX_NUM_REMOVED_PER_CHUNK);
    }

    @VisibleForTesting
    @Nullable List<ProgramList.Chunk> filterAndApplyChunkInternal(@NonNull ProgramList.Chunk chunk,
            int maxNumModifiedPerChunk, int maxNumRemovedPerChunk) {
        if (chunk.isPurge()) {
            mProgramInfoMap.clear();
        }

        Set<RadioManager.ProgramInfo> modified = new HashSet<>();
        Set<ProgramSelector.Identifier> removed = new HashSet<>();
        for (RadioManager.ProgramInfo info : chunk.getModified()) {
            ProgramSelector.Identifier id = info.getSelector().getPrimaryId();
            if (!passesFilter(id) || !shouldIncludeInModified(info)) {
                continue;
            }
            mProgramInfoMap.put(id, info);
            modified.add(info);
        }
        for (ProgramSelector.Identifier id : chunk.getRemoved()) {
            if (mProgramInfoMap.containsKey(id)) {
                mProgramInfoMap.remove(id);
                removed.add(id);
            }
        }
        if (modified.isEmpty() && removed.isEmpty() && mComplete == chunk.isComplete()
                && !chunk.isPurge()) {
            return null;
        }
        mComplete = chunk.isComplete();
        return buildChunks(chunk.isPurge(), mComplete, modified, maxNumModifiedPerChunk, removed,
                maxNumRemovedPerChunk);
    }

    private boolean passesFilter(ProgramSelector.Identifier id) {
        if (mFilter == null) {
            return true;
        }
        if (!mFilter.getIdentifierTypes().isEmpty()
                && !mFilter.getIdentifierTypes().contains(id.getType())) {
            return false;
        }
        if (!mFilter.getIdentifiers().isEmpty() && !mFilter.getIdentifiers().contains(id)) {
            return false;
        }
        if (!mFilter.areCategoriesIncluded() && id.isCategoryType()) {
            return false;
        }
        return true;
    }

    private boolean shouldIncludeInModified(RadioManager.ProgramInfo newInfo) {
        RadioManager.ProgramInfo oldInfo = mProgramInfoMap.get(
                newInfo.getSelector().getPrimaryId());
        if (oldInfo == null) {
            return true;
        }
        if (mFilter != null && mFilter.areModificationsExcluded()) {
            return false;
        }
        return !oldInfo.equals(newInfo);
    }

    private static int roundUpFraction(int numerator, int denominator) {
        return (numerator / denominator) + (numerator % denominator > 0 ? 1 : 0);
    }

    private static @NonNull List<ProgramList.Chunk> buildChunks(boolean purge, boolean complete,
            @Nullable Collection<RadioManager.ProgramInfo> modified, int maxNumModifiedPerChunk,
            @Nullable Collection<ProgramSelector.Identifier> removed, int maxNumRemovedPerChunk) {
        // Communication protocol requires that if purge is set, removed is empty.
        if (purge) {
            removed = null;
        }

        // Determine number of chunks we need to send.
        int numChunks = purge ? 1 : 0;
        if (modified != null) {
            numChunks = Math.max(numChunks,
                    roundUpFraction(modified.size(), maxNumModifiedPerChunk));
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
            chunks.add(new ProgramList.Chunk(purge && i == 0, complete && (i == numChunks - 1),
                      modifiedChunk, removedChunk));
        }
        return chunks;
    }
}
