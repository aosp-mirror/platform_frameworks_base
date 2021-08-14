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

package com.android.server.integrity.parser;

import static com.android.server.integrity.model.IndexingFileConstants.END_INDEXING_KEY;
import static com.android.server.integrity.model.IndexingFileConstants.START_INDEXING_KEY;
import static com.android.server.integrity.parser.BinaryFileOperations.getIntValue;
import static com.android.server.integrity.parser.BinaryFileOperations.getStringValue;

import android.content.integrity.AppInstallMetadata;

import com.android.server.integrity.model.BitInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/** Helper class to identify the necessary indexes that needs to be read. */
public class RuleIndexingController {

    private static LinkedHashMap<String, Integer> sPackageNameBasedIndexes;
    private static LinkedHashMap<String, Integer> sAppCertificateBasedIndexes;
    private static LinkedHashMap<String, Integer> sUnindexedRuleIndexes;

    /**
     * Provide the indexing file to read and the object will be constructed by reading and
     * identifying the indexes.
     */
    public RuleIndexingController(InputStream inputStream) throws IOException {
        BitInputStream bitInputStream = new BitInputStream(inputStream);
        sPackageNameBasedIndexes = getNextIndexGroup(bitInputStream);
        sAppCertificateBasedIndexes = getNextIndexGroup(bitInputStream);
        sUnindexedRuleIndexes = getNextIndexGroup(bitInputStream);
    }

    /**
     * Returns a list of integers with the starting and ending bytes of the rules that needs to be
     * read and evaluated.
     */
    public List<RuleIndexRange> identifyRulesToEvaluate(AppInstallMetadata appInstallMetadata) {
        List<RuleIndexRange> indexRanges = new ArrayList<>();

        // Add the range for package name indexes rules.
        indexRanges.add(
                searchIndexingKeysRangeContainingKey(
                        sPackageNameBasedIndexes, appInstallMetadata.getPackageName()));

        // Add the range for app certificate indexes rules of all certificates.
        for (String appCertificate : appInstallMetadata.getAppCertificates()) {
            indexRanges.add(
                    searchIndexingKeysRangeContainingKey(
                            sAppCertificateBasedIndexes, appCertificate));
        }

        // Add the range for unindexed rules.
        indexRanges.add(
                new RuleIndexRange(
                        sUnindexedRuleIndexes.get(START_INDEXING_KEY),
                        sUnindexedRuleIndexes.get(END_INDEXING_KEY)));

        return indexRanges;
    }

    private LinkedHashMap<String, Integer> getNextIndexGroup(BitInputStream bitInputStream)
            throws IOException {
        LinkedHashMap<String, Integer> keyToIndexMap = new LinkedHashMap<>();
        while (bitInputStream.hasNext()) {
            String key = getStringValue(bitInputStream);
            int value = getIntValue(bitInputStream);

            keyToIndexMap.put(key, value);

            if (key.matches(END_INDEXING_KEY)) {
                break;
            }
        }
        if (keyToIndexMap.size() < 2) {
            throw new IllegalStateException("Indexing file is corrupt.");
        }
        return keyToIndexMap;
    }

    private static RuleIndexRange searchIndexingKeysRangeContainingKey(
            LinkedHashMap<String, Integer> indexMap, String searchedKey) {
        List<String> keys = indexMap.keySet().stream().collect(Collectors.toList());
        List<String> identifiedKeyRange =
                searchKeysRangeContainingKey(keys, searchedKey, 0, keys.size() - 1);
        return new RuleIndexRange(
                indexMap.get(identifiedKeyRange.get(0)), indexMap.get(identifiedKeyRange.get(1)));
    }

    private static List<String> searchKeysRangeContainingKey(
            List<String> sortedKeyList, String key, int startIndex, int endIndex) {
        if (endIndex <= startIndex) {
            throw new IllegalStateException("Indexing file is corrupt.");
        }
        if (endIndex - startIndex == 1) {
            return Arrays.asList(sortedKeyList.get(startIndex), sortedKeyList.get(endIndex));
        }

        int midKeyIndex = startIndex + ((endIndex - startIndex) / 2);
        String midKey = sortedKeyList.get(midKeyIndex);

        if (key.compareTo(midKey) >= 0) {
            return searchKeysRangeContainingKey(sortedKeyList, key, midKeyIndex, endIndex);
        } else {
            return searchKeysRangeContainingKey(sortedKeyList, key, startIndex, midKeyIndex);
        }
    }
}
