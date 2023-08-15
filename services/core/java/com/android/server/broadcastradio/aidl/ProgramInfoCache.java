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
import android.hardware.broadcastradio.ProgramListChunk;
import android.hardware.radio.ProgramList;
import android.hardware.radio.ProgramSelector.Identifier;
import android.hardware.radio.RadioManager;
import android.hardware.radio.UniqueProgramIdentifier;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A class to filter and update program info for HAL clients from broadcast radio AIDL HAL
 */
final class ProgramInfoCache {

    private static final String TAG = "BcRadioAidlSrv.cache";
    /**
     * Maximum number of {@link RadioManager.ProgramInfo} elements that will be put into a
     * ProgramList.Chunk.mModified array. Used to try to ensure a single ProgramList.Chunk
     * stays within the AIDL data size limit.
     */
    private static final int MAX_NUM_MODIFIED_PER_CHUNK = 100;

    /**
     * Maximum number of {@link Identifier} elements that will be put into the removed array
     * of {@link ProgramList.Chunk}. Use to attempt and keep the single {@link ProgramList.Chunk}
     * within the AIDL data size limit.
     */
    private static final int MAX_NUM_REMOVED_PER_CHUNK = 500;

    /**
     * Map from primary identifier to {@link UniqueProgramIdentifier} and then to corresponding
     * {@link RadioManager.ProgramInfo}.
     */
    private final ArrayMap<Identifier, ArrayMap<UniqueProgramIdentifier, RadioManager.ProgramInfo>>
            mProgramInfoMap = new ArrayMap<>();

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
            putInfo(programInfos[i]);
        }
    }

    @VisibleForTesting
    List<RadioManager.ProgramInfo> toProgramInfoList() {
        List<RadioManager.ProgramInfo> programInfoList = new ArrayList<>();
        for (int index = 0; index < mProgramInfoMap.size(); index++) {
            programInfoList.addAll(mProgramInfoMap.valueAt(index).values());
        }
        return programInfoList;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ProgramInfoCache(mComplete = ");
        sb.append(mComplete);
        sb.append(", mFilter = ");
        sb.append(mFilter);
        sb.append(", mProgramInfoMap = [");
        for (int index = 0; index < mProgramInfoMap.size(); index++) {
            ArrayMap<UniqueProgramIdentifier, RadioManager.ProgramInfo> entries =
                    mProgramInfoMap.valueAt(index);
            for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
                sb.append(", ");
                sb.append(entries.valueAt(entryIndex));
            }
        }
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
    void updateFromHalProgramListChunk(ProgramListChunk chunk) {
        if (chunk.purge) {
            mProgramInfoMap.clear();
        }
        for (int i = 0; i < chunk.modified.length; i++) {
            RadioManager.ProgramInfo programInfo =
                    ConversionUtils.programInfoFromHalProgramInfo(chunk.modified[i]);
            if (programInfo == null) {
                Slogf.e(TAG, "Program info in program info %s in chunk is not valid",
                        chunk.modified[i]);
                continue;
            }
            putInfo(programInfo);
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

        ArraySet<RadioManager.ProgramInfo> modified = new ArraySet<>();
        ArraySet<UniqueProgramIdentifier> removed = new ArraySet<>();
        for (int index = 0; index < mProgramInfoMap.size(); index++) {
            removed.addAll(mProgramInfoMap.valueAt(index).keySet());
        }
        for (int index = 0; index < other.mProgramInfoMap.size(); index++) {
            Identifier id = other.mProgramInfoMap.keyAt(index);
            if (!passesFilter(id)) {
                continue;
            }
            ArrayMap<UniqueProgramIdentifier, RadioManager.ProgramInfo> entries =
                    other.mProgramInfoMap.valueAt(index);
            for (int entryIndex = 0; entryIndex < entries.size(); entryIndex++) {
                removed.remove(entries.keyAt(entryIndex));

                RadioManager.ProgramInfo newInfo = entries.valueAt(entryIndex);
                if (!shouldIncludeInModified(newInfo)) {
                    continue;
                }
                putInfo(newInfo);
                modified.add(newInfo);
            }
        }
        for (int removedIndex = 0; removedIndex < removed.size(); removedIndex++) {
            removeUniqueId(removed.valueAt(removedIndex));
        }
        mComplete = other.mComplete;
        return buildChunks(purge, mComplete, modified, maxNumModifiedPerChunk, removed,
                maxNumRemovedPerChunk);
    }

    @Nullable
    List<ProgramList.Chunk> filterAndApplyChunk(ProgramListChunk chunk) {
        return filterAndApplyChunkInternal(chunk, MAX_NUM_MODIFIED_PER_CHUNK,
                MAX_NUM_REMOVED_PER_CHUNK);
    }

    @VisibleForTesting
    @Nullable
    List<ProgramList.Chunk> filterAndApplyChunkInternal(ProgramListChunk chunk,
            int maxNumModifiedPerChunk, int maxNumRemovedPerChunk) {
        if (chunk.purge) {
            mProgramInfoMap.clear();
        }

        Set<RadioManager.ProgramInfo> modified = new ArraySet<>();
        for (int i = 0; i < chunk.modified.length; i++) {
            RadioManager.ProgramInfo info =
                    ConversionUtils.programInfoFromHalProgramInfo(chunk.modified[i]);
            if (info == null) {
                Slogf.w(TAG, "Program info %s in program list chunk is not valid",
                        chunk.modified[i]);
                continue;
            }
            Identifier primaryId = info.getSelector().getPrimaryId();
            if (!passesFilter(primaryId) || !shouldIncludeInModified(info)) {
                continue;
            }
            putInfo(info);
            modified.add(info);
        }
        Set<UniqueProgramIdentifier> removed = new ArraySet<>();
        if (chunk.removed != null) {
            for (int i = 0; i < chunk.removed.length; i++) {
                Identifier removedId = ConversionUtils.identifierFromHalProgramIdentifier(
                        chunk.removed[i]);
                if (removedId == null) {
                    Slogf.w(TAG, "Removed identifier %s in program list chunk is not valid",
                            chunk.modified[i]);
                    continue;
                }
                if (mProgramInfoMap.containsKey(removedId)) {
                    removed.addAll(mProgramInfoMap.get(removedId).keySet());
                    mProgramInfoMap.remove(removedId);
                }
            }
        }
        if (modified.isEmpty() && removed.isEmpty() && mComplete == chunk.complete
                && !chunk.purge) {
            return null;
        }
        mComplete = chunk.complete;
        return buildChunks(chunk.purge, mComplete, modified, maxNumModifiedPerChunk, removed,
                maxNumRemovedPerChunk);
    }

    private boolean passesFilter(Identifier id) {
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

    private void putInfo(RadioManager.ProgramInfo info) {
        Identifier primaryId = info.getSelector().getPrimaryId();
        if (!mProgramInfoMap.containsKey(primaryId)) {
            mProgramInfoMap.put(primaryId, new ArrayMap<>());
        }
        mProgramInfoMap.get(primaryId).put(new UniqueProgramIdentifier(info.getSelector()), info);
    }

    private void removeUniqueId(UniqueProgramIdentifier uniqueId) {
        Identifier primaryId =  uniqueId.getPrimaryId();
        if (!mProgramInfoMap.containsKey(primaryId)) {
            return;
        }
        mProgramInfoMap.get(primaryId).remove(uniqueId);
        if (mProgramInfoMap.get(primaryId).isEmpty()) {
            mProgramInfoMap.remove(primaryId);
        }
    }

    private boolean shouldIncludeInModified(RadioManager.ProgramInfo newInfo) {
        Identifier primaryId = newInfo.getSelector().getPrimaryId();
        RadioManager.ProgramInfo oldInfo = null;
        if (mProgramInfoMap.containsKey(primaryId)) {
            UniqueProgramIdentifier uniqueId = new UniqueProgramIdentifier(newInfo.getSelector());
            oldInfo = mProgramInfoMap.get(primaryId).get(uniqueId);
        }
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
            @Nullable Collection<UniqueProgramIdentifier> removed, int maxNumRemovedPerChunk) {
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
        Iterator<UniqueProgramIdentifier> removedIter = null;
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
            ArraySet<UniqueProgramIdentifier> removedChunk = new ArraySet<>();
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
