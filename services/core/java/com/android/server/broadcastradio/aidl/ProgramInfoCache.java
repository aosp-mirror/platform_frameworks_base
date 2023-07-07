/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.broadcastradio.aidl;

import android.annotation.Nullable;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A class to filter and update program info for HAL clients from broadcast radio AIDL HAL
 */
final class ProgramInfoCache {

    private static final String TAG = "BcRadioAidlSrv.cache";
    /**
     * Maximum number of {@link RadioManager#ProgramInfo} elements that will be put into a
     * ProgramList.Chunk.mModified array. Used to try to ensure a single ProgramList.Chunk
     * stays within the AIDL data size limit.
     */
    private static final int MAX_NUM_MODIFIED_PER_CHUNK = 100;

    /**
     * Maximum number of {@link ProgramSelector#Identifier} elements that will be put
     * into the removed array of {@link ProgramList#Chunk}. Used to try to ensure a single
     * {@link ProgramList#Chunk} stays within the AIDL data size limit.
     */
    private static final int MAX_NUM_REMOVED_PER_CHUNK = 500;

    /**
     * Map from primary identifier to corresponding {@link RadioManager#ProgramInfo}.
     */
    private final Map<ProgramSelector.Identifier, RadioManager.ProgramInfo> mProgramInfoMap =
            new ArrayMap<>();

    /**
     * Flag indicating whether mProgramInfoMap is considered complete based upon the received
     * updates.
     */
    private boolean mComplete = true;

    /**
     * Optional filter used in {@link ProgramInfoCache#filterAndUpdateFromInternal}. Usually this
     * field is null for a HAL-side cache and non-null for an AIDL-side cache.
     */
    @Nullable private final ProgramList.Filter mFilter;

    ProgramInfoCache(@Nullable ProgramList.Filter filter) {
        mFilter = filter;
    }

    @VisibleForTesting
    ProgramInfoCache(@Nullable ProgramList.Filter filter, boolean complete,
            RadioManager.ProgramInfo... programInfos) {
        mFilter = filter;
        mComplete = complete;
        for (int i = 0; i < programInfos.length; i++) {
            mProgramInfoMap.put(programInfos[i].getSelector().getPrimaryId(), programInfos[i]);
        }
    }

    @VisibleForTesting
    List<RadioManager.ProgramInfo> toProgramInfoList() {
        return new ArrayList<>(mProgramInfoMap.values());
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ProgramInfoCache(mComplete = ");
        sb.append(mComplete);
        sb.append(", mFilter = ");
        sb.append(mFilter);
        sb.append(", mProgramInfoMap = [");
        mProgramInfoMap.forEach((id, programInfo) -> {
            sb.append(", ");
            sb.append(programInfo);
        });
        return sb.append("])").toString();
    }

    public boolean isComplete() {
        return mComplete;
    }

    @Nullable
    public ProgramList.Filter getFilter() {
        return mFilter;
    }

    @VisibleForTesting
    void updateFromHalProgramListChunk(
            android.hardware.broadcastradio.ProgramListChunk chunk) {
        if (chunk.purge) {
            mProgramInfoMap.clear();
        }
        for (int i = 0; i < chunk.modified.length; i++) {
            RadioManager.ProgramInfo programInfo =
                    ConversionUtils.programInfoFromHalProgramInfo(chunk.modified[i]);
            if (programInfo == null) {
                Slogf.e(TAG, "Program info in program info %s in chunk is not valid",
                        chunk.modified[i]);
            }
            mProgramInfoMap.put(programInfo.getSelector().getPrimaryId(), programInfo);
        }
        if (chunk.removed != null) {
            for (int i = 0; i < chunk.removed.length; i++) {
                mProgramInfoMap.remove(
                        ConversionUtils.identifierFromHalProgramIdentifier(chunk.removed[i]));
            }
        }
        mComplete = chunk.complete;
    }

    List<ProgramList.Chunk> filterAndUpdateFromInternal(ProgramInfoCache other,
            boolean purge) {
        return filterAndUpdateFromInternal(other, purge, MAX_NUM_MODIFIED_PER_CHUNK,
                MAX_NUM_REMOVED_PER_CHUNK);
    }

    @VisibleForTesting
    List<ProgramList.Chunk> filterAndUpdateFromInternal(ProgramInfoCache other,
            boolean purge, int maxNumModifiedPerChunk, int maxNumRemovedPerChunk) {
        if (purge) {
            mProgramInfoMap.clear();
        }
        // If mProgramInfoMap is empty, we treat this update as a purge because this might be the
        // first update to an AIDL client that changed its filter.
        if (mProgramInfoMap.isEmpty()) {
            purge = true;
        }

        Set<RadioManager.ProgramInfo> modified = new ArraySet<>();
        Set<ProgramSelector.Identifier> removed = new ArraySet<>(mProgramInfoMap.keySet());
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

    @Nullable
    List<ProgramList.Chunk> filterAndApplyChunk(ProgramList.Chunk chunk) {
        return filterAndApplyChunkInternal(chunk, MAX_NUM_MODIFIED_PER_CHUNK,
                MAX_NUM_REMOVED_PER_CHUNK);
    }

    @VisibleForTesting
    @Nullable
    List<ProgramList.Chunk> filterAndApplyChunkInternal(ProgramList.Chunk chunk,
            int maxNumModifiedPerChunk, int maxNumRemovedPerChunk) {
        if (chunk.isPurge()) {
            mProgramInfoMap.clear();
        }

        Set<RadioManager.ProgramInfo> modified = new ArraySet<>();
        Set<ProgramSelector.Identifier> removed = new ArraySet<>();
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
        return mFilter.areCategoriesIncluded() || !id.isCategoryType();
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

    private static List<ProgramList.Chunk> buildChunks(boolean purge, boolean complete,
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
            return new ArrayList<>();
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
        List<ProgramList.Chunk> chunks = new ArrayList<>(numChunks);
        for (int i = 0; i < numChunks; i++) {
            ArraySet<RadioManager.ProgramInfo> modifiedChunk = new ArraySet<>();
            ArraySet<ProgramSelector.Identifier> removedChunk = new ArraySet<>();
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
