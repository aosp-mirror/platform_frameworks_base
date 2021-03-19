/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.appsearch.external.localstorage.stats;

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.AppSearchResult;

import org.junit.Test;

public class AppSearchStatsTest {
    static final String TEST_PACKAGE_NAME = "com.google.test";
    static final String TEST_DATA_BASE = "testDataBase";
    static final int TEST_STATUS_CODE = AppSearchResult.RESULT_INTERNAL_ERROR;
    static final int TEST_TOTAL_LATENCY_MILLIS = 20;

    @Test
    public void testAppSearchStats_GeneralStats() {
        final GeneralStats gStats =
                new GeneralStats.Builder(TEST_PACKAGE_NAME, TEST_DATA_BASE)
                        .setStatusCode(TEST_STATUS_CODE)
                        .setTotalLatencyMillis(TEST_TOTAL_LATENCY_MILLIS)
                        .build();

        assertThat(gStats.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(gStats.getDatabase()).isEqualTo(TEST_DATA_BASE);
        assertThat(gStats.getStatusCode()).isEqualTo(TEST_STATUS_CODE);
        assertThat(gStats.getTotalLatencyMillis()).isEqualTo(TEST_TOTAL_LATENCY_MILLIS);
    }

    /** Make sure status code is UNKNOWN if not set in {@link GeneralStats} */
    @Test
    public void testAppSearchStats_GeneralStats_defaultStatsCode_Unknown() {
        final GeneralStats gStats =
                new GeneralStats.Builder(TEST_PACKAGE_NAME, TEST_DATA_BASE)
                        .setTotalLatencyMillis(TEST_TOTAL_LATENCY_MILLIS)
                        .build();

        assertThat(gStats.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(gStats.getDatabase()).isEqualTo(TEST_DATA_BASE);
        assertThat(gStats.getStatusCode()).isEqualTo(AppSearchResult.RESULT_UNKNOWN_ERROR);
        assertThat(gStats.getTotalLatencyMillis()).isEqualTo(TEST_TOTAL_LATENCY_MILLIS);
    }

    @Test
    public void testAppSearchStats_CallStats() {
        final int estimatedBinderLatencyMillis = 1;
        final int numOperationsSucceeded = 2;
        final int numOperationsFailed = 3;
        final @CallStats.CallType int callType = CallStats.CALL_TYPE_PUT_DOCUMENTS;

        final CallStats.Builder cStatsBuilder =
                new CallStats.Builder(TEST_PACKAGE_NAME, TEST_DATA_BASE)
                        .setCallType(callType)
                        .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                        .setNumOperationsSucceeded(numOperationsSucceeded)
                        .setNumOperationsFailed(numOperationsFailed);
        cStatsBuilder
                .getGeneralStatsBuilder()
                .setStatusCode(TEST_STATUS_CODE)
                .setTotalLatencyMillis(TEST_TOTAL_LATENCY_MILLIS);
        final CallStats cStats = cStatsBuilder.build();

        assertThat(cStats.getGeneralStats().getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(cStats.getGeneralStats().getDatabase()).isEqualTo(TEST_DATA_BASE);
        assertThat(cStats.getGeneralStats().getStatusCode()).isEqualTo(TEST_STATUS_CODE);
        assertThat(cStats.getGeneralStats().getTotalLatencyMillis())
                .isEqualTo(TEST_TOTAL_LATENCY_MILLIS);
        assertThat(cStats.getEstimatedBinderLatencyMillis())
                .isEqualTo(estimatedBinderLatencyMillis);
        assertThat(cStats.getCallType()).isEqualTo(callType);
        assertThat(cStats.getNumOperationsSucceeded()).isEqualTo(numOperationsSucceeded);
        assertThat(cStats.getNumOperationsFailed()).isEqualTo(numOperationsFailed);
    }

    @Test
    public void testAppSearchStats_PutDocumentStats() {
        final int generateDocumentProtoLatencyMillis = 1;
        final int rewriteDocumentTypesLatencyMillis = 2;
        final int nativeLatencyMillis = 3;
        final int nativeDocumentStoreLatencyMillis = 4;
        final int nativeIndexLatencyMillis = 5;
        final int nativeIndexMergeLatencyMillis = 6;
        final int nativeDocumentSize = 7;
        final int nativeNumTokensIndexed = 8;
        final boolean nativeExceededMaxNumTokens = true;
        final PutDocumentStats.Builder pStatsBuilder =
                new PutDocumentStats.Builder(TEST_PACKAGE_NAME, TEST_DATA_BASE)
                        .setGenerateDocumentProtoLatencyMillis(generateDocumentProtoLatencyMillis)
                        .setRewriteDocumentTypesLatencyMillis(rewriteDocumentTypesLatencyMillis)
                        .setNativeLatencyMillis(nativeLatencyMillis)
                        .setNativeDocumentStoreLatencyMillis(nativeDocumentStoreLatencyMillis)
                        .setNativeIndexLatencyMillis(nativeIndexLatencyMillis)
                        .setNativeIndexMergeLatencyMillis(nativeIndexMergeLatencyMillis)
                        .setNativeDocumentSizeBytes(nativeDocumentSize)
                        .setNativeNumTokensIndexed(nativeNumTokensIndexed)
                        .setNativeExceededMaxNumTokens(nativeExceededMaxNumTokens);
        pStatsBuilder
                .getGeneralStatsBuilder()
                .setStatusCode(TEST_STATUS_CODE)
                .setTotalLatencyMillis(TEST_TOTAL_LATENCY_MILLIS);
        final PutDocumentStats pStats = pStatsBuilder.build();

        assertThat(pStats.getGeneralStats().getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(pStats.getGeneralStats().getDatabase()).isEqualTo(TEST_DATA_BASE);
        assertThat(pStats.getGeneralStats().getStatusCode()).isEqualTo(TEST_STATUS_CODE);
        assertThat(pStats.getGeneralStats().getTotalLatencyMillis())
                .isEqualTo(TEST_TOTAL_LATENCY_MILLIS);
        assertThat(pStats.getGenerateDocumentProtoLatencyMillis())
                .isEqualTo(generateDocumentProtoLatencyMillis);
        assertThat(pStats.getRewriteDocumentTypesLatencyMillis())
                .isEqualTo(rewriteDocumentTypesLatencyMillis);
        assertThat(pStats.getNativeLatencyMillis()).isEqualTo(nativeLatencyMillis);
        assertThat(pStats.getNativeDocumentStoreLatencyMillis())
                .isEqualTo(nativeDocumentStoreLatencyMillis);
        assertThat(pStats.getNativeIndexLatencyMillis()).isEqualTo(nativeIndexLatencyMillis);
        assertThat(pStats.getNativeIndexMergeLatencyMillis())
                .isEqualTo(nativeIndexMergeLatencyMillis);
        assertThat(pStats.getNativeDocumentSizeBytes()).isEqualTo(nativeDocumentSize);
        assertThat(pStats.getNativeNumTokensIndexed()).isEqualTo(nativeNumTokensIndexed);
        assertThat(pStats.getNativeExceededMaxNumTokens()).isEqualTo(nativeExceededMaxNumTokens);
    }
}
