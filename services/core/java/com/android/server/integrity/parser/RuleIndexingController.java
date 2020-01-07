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
import static com.android.server.integrity.parser.BinaryFileOperations.getIntValue;
import static com.android.server.integrity.parser.BinaryFileOperations.getStringValue;

import android.content.integrity.AppInstallMetadata;

import com.android.server.integrity.model.BitInputStream;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Helper class to identify the necessary indexes that needs to be read. */
public class RuleIndexingController {

    private static TreeMap<String, Integer> sPackageNameBasedIndexes;
    private static TreeMap<String, Integer> sAppCertificateBasedIndexes;
    private static TreeMap<String, Integer> sUnindexedRuleIndexes;

    /**
     * Provide the indexing file to read and the object will be constructed by reading and
     * identifying the indexes.
     */
    public RuleIndexingController(FileInputStream fileInputStream) throws IOException {
        BitInputStream bitInputStream = new BitInputStream(fileInputStream);
        sPackageNameBasedIndexes = getNextIndexGroup(bitInputStream);
        sAppCertificateBasedIndexes = getNextIndexGroup(bitInputStream);
        sUnindexedRuleIndexes = getNextIndexGroup(bitInputStream);
    }

    /**
     * Returns a list of integers with the starting and ending bytes of the rules that needs to be
     * read and evaluated.
     */
    public List<List<Integer>> identifyRulesToEvaluate(AppInstallMetadata appInstallMetadata) {
        // TODO(b/145493956): Identify and return the indexes that needs to be read.
        return new ArrayList<>();
    }

    private TreeMap<String, Integer> getNextIndexGroup(BitInputStream bitInputStream)
            throws IOException {
        TreeMap<String, Integer> keyToIndexMap = new TreeMap<>();
        while (bitInputStream.hasNext()) {
            String key = getStringValue(bitInputStream);
            int value = getIntValue(bitInputStream);

            keyToIndexMap.put(key, value);

            if (key == END_INDEXING_KEY) {
                break;
            }
        }
        return keyToIndexMap;
    }
}
