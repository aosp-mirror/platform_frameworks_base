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

import static com.android.server.integrity.model.ComponentBitSize.VALUE_SIZE_BITS;
import static com.android.server.integrity.model.IndexingFileConstants.END_INDEXING_KEY;
import static com.android.server.integrity.model.IndexingFileConstants.START_INDEXING_KEY;
import static com.android.server.integrity.utils.TestUtils.getBits;
import static com.android.server.integrity.utils.TestUtils.getBytes;
import static com.android.server.integrity.utils.TestUtils.getValueBits;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.content.integrity.AppInstallMetadata;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(JUnit4.class)
public class RuleIndexingControllerTest {

    @Test
    public void verifyIndexRangeSearchIsCorrect() throws IOException {
        InputStream inputStream = obtainDefaultIndexingMapForTest();

        RuleIndexingController indexingController = new RuleIndexingController(inputStream);

        AppInstallMetadata appInstallMetadata =
                new AppInstallMetadata.Builder()
                        .setPackageName("ddd")
                        .setAppCertificates(Collections.singletonList("777"))
                        .build();

        List<RuleIndexRange> resultingIndexes =
                indexingController.identifyRulesToEvaluate(appInstallMetadata);

        assertThat(resultingIndexes)
                .containsExactly(
                        new RuleIndexRange(200, 300),
                        new RuleIndexRange(700, 800),
                        new RuleIndexRange(900, 945));
    }

    @Test
    public void verifyIndexRangeSearchIsCorrect_multipleAppCertificates() throws IOException {
        InputStream inputStream = obtainDefaultIndexingMapForTest();

        RuleIndexingController indexingController = new RuleIndexingController(inputStream);

        AppInstallMetadata appInstallMetadata =
                new AppInstallMetadata.Builder()
                        .setPackageName("ddd")
                        .setAppCertificates(Arrays.asList("777", "999"))
                        .build();

        List<RuleIndexRange> resultingIndexes =
                indexingController.identifyRulesToEvaluate(appInstallMetadata);

        assertThat(resultingIndexes)
                .containsExactly(
                        new RuleIndexRange(200, 300),
                        new RuleIndexRange(700, 800),
                        new RuleIndexRange(800, 900),
                        new RuleIndexRange(900, 945));
    }

    @Test
    public void verifyIndexRangeSearchIsCorrect_keysInFirstAndLastBlock() throws IOException {
        InputStream inputStream = obtainDefaultIndexingMapForTest();

        RuleIndexingController indexingController = new RuleIndexingController(inputStream);

        AppInstallMetadata appInstallMetadata =
                new AppInstallMetadata.Builder()
                        .setPackageName("bbb")
                        .setAppCertificates(Collections.singletonList("999"))
                        .build();

        List<RuleIndexRange> resultingIndexes =
                indexingController.identifyRulesToEvaluate(appInstallMetadata);

        assertThat(resultingIndexes)
                .containsExactly(
                        new RuleIndexRange(100, 200),
                        new RuleIndexRange(800, 900),
                        new RuleIndexRange(900, 945));
    }

    @Test
    public void verifyIndexRangeSearchIsCorrect_keysMatchWithValues() throws IOException {
        InputStream inputStream = obtainDefaultIndexingMapForTest();

        RuleIndexingController indexingController = new RuleIndexingController(inputStream);

        AppInstallMetadata appInstallMetadata =
                new AppInstallMetadata.Builder()
                        .setPackageName("ccc")
                        .setAppCertificates(Collections.singletonList("444"))
                        .build();

        List<RuleIndexRange> resultingIndexes =
                indexingController.identifyRulesToEvaluate(appInstallMetadata);

        assertThat(resultingIndexes)
                .containsExactly(
                        new RuleIndexRange(200, 300),
                        new RuleIndexRange(700, 800),
                        new RuleIndexRange(900, 945));
    }

    @Test
    public void verifyIndexRangeSearchIsCorrect_noIndexesAvailable() throws IOException {
        byte[] stringBytes =
                getBytes(
                        getKeyValueString(START_INDEXING_KEY, 100)
                                + getKeyValueString(END_INDEXING_KEY, 500)
                                + getKeyValueString(START_INDEXING_KEY, 500)
                                + getKeyValueString(END_INDEXING_KEY, 900)
                                + getKeyValueString(START_INDEXING_KEY, 900)
                                + getKeyValueString(END_INDEXING_KEY, 945));
        ByteBuffer rule = ByteBuffer.allocate(stringBytes.length);
        rule.put(stringBytes);
        InputStream inputStream = new ByteArrayInputStream(rule.array());

        RuleIndexingController indexingController = new RuleIndexingController(inputStream);

        AppInstallMetadata appInstallMetadata =
                new AppInstallMetadata.Builder()
                        .setPackageName("ccc")
                        .setAppCertificates(Collections.singletonList("444"))
                        .build();

        List<RuleIndexRange> resultingIndexes =
                indexingController.identifyRulesToEvaluate(appInstallMetadata);

        assertThat(resultingIndexes)
                .containsExactly(
                        new RuleIndexRange(100, 500),
                        new RuleIndexRange(500, 900),
                        new RuleIndexRange(900, 945));
    }

    @Test
    public void verifyIndexingFileIsCorrupt() throws IOException {
        byte[] stringBytes =
                getBytes(
                        getKeyValueString(START_INDEXING_KEY, 100)
                                + getKeyValueString("ccc", 200)
                                + getKeyValueString(END_INDEXING_KEY, 300)
                                + getKeyValueString(END_INDEXING_KEY, 900));
        ByteBuffer rule = ByteBuffer.allocate(stringBytes.length);
        rule.put(stringBytes);
        InputStream inputStream = new ByteArrayInputStream(rule.array());

        assertThrows(IllegalStateException.class,
                () -> new RuleIndexingController(inputStream));
    }

    private static InputStream obtainDefaultIndexingMapForTest() {
        byte[] stringBytes =
                getBytes(
                        getKeyValueString(START_INDEXING_KEY, 100)
                                + getKeyValueString("ccc", 200)
                                + getKeyValueString("eee", 300)
                                + getKeyValueString("hhh", 400)
                                + getKeyValueString(END_INDEXING_KEY, 500)
                                + getKeyValueString(START_INDEXING_KEY, 500)
                                + getKeyValueString("111", 600)
                                + getKeyValueString("444", 700)
                                + getKeyValueString("888", 800)
                                + getKeyValueString(END_INDEXING_KEY, 900)
                                + getKeyValueString(START_INDEXING_KEY, 900)
                                + getKeyValueString(END_INDEXING_KEY, 945));
        ByteBuffer rule = ByteBuffer.allocate(stringBytes.length);
        rule.put(stringBytes);
        return new ByteArrayInputStream(rule.array());
    }

    private static String getKeyValueString(String key, int value) {
        String isNotHashed = "0";
        return isNotHashed
                + getBits(key.length(), VALUE_SIZE_BITS)
                + getValueBits(key)
                + getBits(value, /* numOfBits= */ 32);
    }
}
