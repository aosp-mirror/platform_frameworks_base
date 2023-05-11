/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.credentials.metrics.shared;

import android.annotation.NonNull;

import com.android.server.credentials.metrics.EntryEnum;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Some data is directly shared between the
 * {@link com.android.server.credentials.metrics.CandidatePhaseMetric} and the
 * {@link com.android.server.credentials.metrics.ChosenProviderFinalPhaseMetric}. This
 * aims to create an abstraction that holds that information, to avoid duplication.
 *
 * This class should be immutable and threadsafe once generated.
 */
public class ResponseCollective {
    /*
    Abstract Function (responseCounts, entryCounts) -> A 'ResponseCollective' containing information
    about a chosen or candidate providers available responses, be they entries or credentials.

    RepInvariant: mResponseCounts and mEntryCounts are always initialized

    Threadsafe and Immutability: Once generated, the maps remain unchangeable. The object is
    threadsafe and immutable, and safe from external changes. This is threadsafe because it is
    immutable after creation and only allows reads, not writes.
    */

    private static final String TAG = "ResponseCollective";

    // Stores the deduped credential response information, eg {"response":5} for this provider
    private final Map<String, Integer> mResponseCounts;
    // Stores the deduped entry information, eg {ENTRY_ENUM:5} for this provider
    private final Map<EntryEnum, Integer> mEntryCounts;

    public ResponseCollective(@NonNull Map<String, Integer> responseCounts,
            @NonNull Map<EntryEnum, Integer> entryCounts) {
        mResponseCounts = responseCounts == null ? new LinkedHashMap<>() :
                new LinkedHashMap<>(responseCounts);
        mEntryCounts = entryCounts == null ? new LinkedHashMap<>() :
                new LinkedHashMap<>(entryCounts);
    }

    /**
     * Returns the unique, deduped, response classtypes for logging associated with this provider.
     *
     * @return a string array for deduped classtypes
     */
    public String[] getUniqueResponseStrings() {
        String[] result = new String[mResponseCounts.keySet().size()];
        mResponseCounts.keySet().toArray(result);
        return result;
    }

    /**
     * Returns an unmodifiable map of the entry counts, safe under the immutability of the
     * class the original map is held within.
     * @return an unmodifiable map of the entry : counts
     */
    public Map<EntryEnum, Integer> getEntryCountsMap() {
        return Collections.unmodifiableMap(mEntryCounts);
    }

    /**
     * Returns an unmodifiable map of the response counts, safe under the immutability of the
     * class the original map is held within.
     * @return an unmodifiable map of the response : counts
     */
    public Map<String, Integer> getResponseCountsMap() {
        return Collections.unmodifiableMap(mResponseCounts);
    }

    /**
     * Returns the unique, deduped, response classtype counts for logging associated with this
     * provider.
     * @return a string array for deduped classtype counts
     */
    public int[] getUniqueResponseCounts() {
        return mResponseCounts.values().stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Returns the unique, deduped, entry types for logging associated with this provider.
     * @return an int array for deduped entries
     */
    public int[] getUniqueEntries() {
        return mEntryCounts.keySet().stream().mapToInt(Enum::ordinal).toArray();
    }

    /**
     * Returns the unique, deduped, entry classtype counts for logging associated with this
     * provider.
     * @return a string array for deduped classtype counts
     */
    public int[] getUniqueEntryCounts() {
        return mEntryCounts.values().stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Given a specific {@link EntryEnum}, this provides us with the count of that entry within
     * this particular provider.
     * @param e the entry enum with which we want to know the count of
     * @return a count of this particular entry enum stored by this provider
     */
    public int getCountForEntry(EntryEnum e) {
        return mEntryCounts.get(e);
    }

    /**
     * Indicates the total number of existing entries for this provider.
     * @return a count of the total number of entries for this provider
     */
    public int getNumEntriesTotal() {
        return mEntryCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * This combines the current collective with another collective, only if that other
     * collective is indeed a differing one in memory.
     * @param other the other response collective to combine with
     * @return a combined {@link ResponseCollective} object
     */
    public ResponseCollective combineCollectives(ResponseCollective other) {
        if (this == other) {
            return this;
        }

        Map<String, Integer> responseCounts = new LinkedHashMap<>(other.mResponseCounts);
        for (String response : mResponseCounts.keySet()) {
            responseCounts.merge(response, mResponseCounts.get(response), Integer::sum);
        }

        Map<EntryEnum, Integer> entryCounts = new LinkedHashMap<>(other.mEntryCounts);
        for (EntryEnum entry : mEntryCounts.keySet()) {
            entryCounts.merge(entry, mEntryCounts.get(entry), Integer::sum);
        }

        return new ResponseCollective(responseCounts, entryCounts);
    }

    /**
     * Given two maps of type : counts, this combines the second into the first, to get an aggregate
     * deduped type:count output.
     * @param first the first map of some type to counts used as the base
     * @param second the second map of some type to counts that mixies with the first
     * @param <T> The type of the object we are mix-deduping - i.e. responses or entries.
     * @return the first map updated with the second map's information for type:counts
     */
    public static <T> Map<T, Integer> combineTypeCountMaps(Map<T, Integer> first,
            Map<T, Integer> second) {
        for (T response : second.keySet()) {
            first.merge(response, first.getOrDefault(response, 0), Integer::sum);
        }
        return first;
    }
}
