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
    public void testAppSearchStats_CallStats() {
        final int estimatedBinderLatencyMillis = 1;
        final int numOperationsSucceeded = 2;
        final int numOperationsFailed = 3;
        final @CallStats.CallType int callType = CallStats.CALL_TYPE_PUT_DOCUMENTS;

        final CallStats cStats =
                new CallStats.Builder()
                        .setPackageName(TEST_PACKAGE_NAME)
                        .setDatabase(TEST_DATA_BASE)
                        .setStatusCode(TEST_STATUS_CODE)
                        .setTotalLatencyMillis(TEST_TOTAL_LATENCY_MILLIS)
                        .setCallType(callType)
                        .setEstimatedBinderLatencyMillis(estimatedBinderLatencyMillis)
                        .setNumOperationsSucceeded(numOperationsSucceeded)
                        .setNumOperationsFailed(numOperationsFailed)
                        .build();

        assertThat(cStats.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(cStats.getDatabase()).isEqualTo(TEST_DATA_BASE);
        assertThat(cStats.getStatusCode()).isEqualTo(TEST_STATUS_CODE);
        assertThat(cStats.getTotalLatencyMillis()).isEqualTo(TEST_TOTAL_LATENCY_MILLIS);
        assertThat(cStats.getEstimatedBinderLatencyMillis())
                .isEqualTo(estimatedBinderLatencyMillis);
        assertThat(cStats.getCallType()).isEqualTo(callType);
        assertThat(cStats.getNumOperationsSucceeded()).isEqualTo(numOperationsSucceeded);
        assertThat(cStats.getNumOperationsFailed()).isEqualTo(numOperationsFailed);
    }

    @Test
    public void testAppSearchCallStats_nullValues() {
        final @CallStats.CallType int callType = CallStats.CALL_TYPE_PUT_DOCUMENTS;

        final CallStats.Builder cStatsBuilder = new CallStats.Builder().setCallType(callType);

        final CallStats cStats = cStatsBuilder.build();

        assertThat(cStats.getPackageName()).isNull();
        assertThat(cStats.getDatabase()).isNull();
        assertThat(cStats.getCallType()).isEqualTo(callType);
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
                        .setStatusCode(TEST_STATUS_CODE)
                        .setTotalLatencyMillis(TEST_TOTAL_LATENCY_MILLIS)
                        .setGenerateDocumentProtoLatencyMillis(generateDocumentProtoLatencyMillis)
                        .setRewriteDocumentTypesLatencyMillis(rewriteDocumentTypesLatencyMillis)
                        .setNativeLatencyMillis(nativeLatencyMillis)
                        .setNativeDocumentStoreLatencyMillis(nativeDocumentStoreLatencyMillis)
                        .setNativeIndexLatencyMillis(nativeIndexLatencyMillis)
                        .setNativeIndexMergeLatencyMillis(nativeIndexMergeLatencyMillis)
                        .setNativeDocumentSizeBytes(nativeDocumentSize)
                        .setNativeNumTokensIndexed(nativeNumTokensIndexed)
                        .setNativeExceededMaxNumTokens(nativeExceededMaxNumTokens);

        final PutDocumentStats pStats = pStatsBuilder.build();

        assertThat(pStats.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(pStats.getDatabase()).isEqualTo(TEST_DATA_BASE);
        assertThat(pStats.getStatusCode()).isEqualTo(TEST_STATUS_CODE);
        assertThat(pStats.getTotalLatencyMillis()).isEqualTo(TEST_TOTAL_LATENCY_MILLIS);
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

    @Test
    public void testAppSearchStats_InitializeStats() {
        int prepareSchemaAndNamespacesLatencyMillis = 1;
        int prepareVisibilityFileLatencyMillis = 2;
        int nativeLatencyMillis = 3;
        int nativeDocumentStoreRecoveryCause = 4;
        int nativeIndexRestorationCause = 5;
        int nativeSchemaStoreRecoveryCause = 6;
        int nativeDocumentStoreRecoveryLatencyMillis = 7;
        int nativeIndexRestorationLatencyMillis = 8;
        int nativeSchemaStoreRecoveryLatencyMillis = 9;
        int nativeDocumentStoreDataStatus = 10;
        int nativeNumDocuments = 11;
        int nativeNumSchemaTypes = 12;

        final InitializeStats.Builder iStatsBuilder =
                new InitializeStats.Builder()
                        .setStatusCode(TEST_STATUS_CODE)
                        .setTotalLatencyMillis(TEST_TOTAL_LATENCY_MILLIS)
                        .setHasDeSync(/* hasDeSyncs= */ true)
                        .setPrepareSchemaAndNamespacesLatencyMillis(
                                prepareSchemaAndNamespacesLatencyMillis)
                        .setPrepareVisibilityStoreLatencyMillis(prepareVisibilityFileLatencyMillis)
                        .setNativeLatencyMillis(nativeLatencyMillis)
                        .setDocumentStoreRecoveryCause(nativeDocumentStoreRecoveryCause)
                        .setIndexRestorationCause(nativeIndexRestorationCause)
                        .setSchemaStoreRecoveryCause(nativeSchemaStoreRecoveryCause)
                        .setDocumentStoreRecoveryLatencyMillis(
                                nativeDocumentStoreRecoveryLatencyMillis)
                        .setIndexRestorationLatencyMillis(nativeIndexRestorationLatencyMillis)
                        .setSchemaStoreRecoveryLatencyMillis(nativeSchemaStoreRecoveryLatencyMillis)
                        .setDocumentStoreDataStatus(nativeDocumentStoreDataStatus)
                        .setDocumentCount(nativeNumDocuments)
                        .setSchemaTypeCount(nativeNumSchemaTypes)
                        .setHasReset(true)
                        .setResetStatusCode(AppSearchResult.RESULT_INVALID_SCHEMA);
        final InitializeStats iStats = iStatsBuilder.build();

        assertThat(iStats.getStatusCode()).isEqualTo(TEST_STATUS_CODE);
        assertThat(iStats.getTotalLatencyMillis()).isEqualTo(TEST_TOTAL_LATENCY_MILLIS);
        assertThat(iStats.hasDeSync()).isTrue();
        assertThat(iStats.getPrepareSchemaAndNamespacesLatencyMillis())
                .isEqualTo(prepareSchemaAndNamespacesLatencyMillis);
        assertThat(iStats.getPrepareVisibilityStoreLatencyMillis())
                .isEqualTo(prepareVisibilityFileLatencyMillis);
        assertThat(iStats.getNativeLatencyMillis()).isEqualTo(nativeLatencyMillis);
        assertThat(iStats.getDocumentStoreRecoveryCause())
                .isEqualTo(nativeDocumentStoreRecoveryCause);
        assertThat(iStats.getIndexRestorationCause()).isEqualTo(nativeIndexRestorationCause);
        assertThat(iStats.getSchemaStoreRecoveryCause()).isEqualTo(nativeSchemaStoreRecoveryCause);
        assertThat(iStats.getDocumentStoreRecoveryLatencyMillis())
                .isEqualTo(nativeDocumentStoreRecoveryLatencyMillis);
        assertThat(iStats.getIndexRestorationLatencyMillis())
                .isEqualTo(nativeIndexRestorationLatencyMillis);
        assertThat(iStats.getSchemaStoreRecoveryLatencyMillis())
                .isEqualTo(nativeSchemaStoreRecoveryLatencyMillis);
        assertThat(iStats.getDocumentStoreDataStatus()).isEqualTo(nativeDocumentStoreDataStatus);
        assertThat(iStats.getDocumentCount()).isEqualTo(nativeNumDocuments);
        assertThat(iStats.getSchemaTypeCount()).isEqualTo(nativeNumSchemaTypes);
        assertThat(iStats.hasReset()).isTrue();
        assertThat(iStats.getResetStatusCode()).isEqualTo(AppSearchResult.RESULT_INVALID_SCHEMA);
    }

    @Test
    public void testAppSearchStats_SearchStats() {
        int rewriteSearchSpecLatencyMillis = 1;
        int rewriteSearchResultLatencyMillis = 2;
        int visibilityScope = SearchStats.VISIBILITY_SCOPE_LOCAL;
        int nativeLatencyMillis = 4;
        int nativeNumTerms = 5;
        int nativeQueryLength = 6;
        int nativeNumNamespacesFiltered = 7;
        int nativeNumSchemaTypesFiltered = 8;
        int nativeRequestedPageSize = 9;
        int nativeNumResultsReturnedCurrentPage = 10;
        boolean nativeIsFirstPage = true;
        int nativeParseQueryLatencyMillis = 11;
        int nativeRankingStrategy = 12;
        int nativeNumDocumentsScored = 13;
        int nativeScoringLatencyMillis = 14;
        int nativeRankingLatencyMillis = 15;
        int nativeNumResultsSnippeted = 16;
        int nativeDocumentRetrievingLatencyMillis = 17;
        final SearchStats.Builder sStatsBuilder =
                new SearchStats.Builder(visibilityScope, TEST_PACKAGE_NAME)
                        .setDatabase(TEST_DATA_BASE)
                        .setStatusCode(TEST_STATUS_CODE)
                        .setTotalLatencyMillis(TEST_TOTAL_LATENCY_MILLIS)
                        .setRewriteSearchSpecLatencyMillis(rewriteSearchSpecLatencyMillis)
                        .setRewriteSearchResultLatencyMillis(rewriteSearchResultLatencyMillis)
                        .setNativeLatencyMillis(nativeLatencyMillis)
                        .setTermCount(nativeNumTerms)
                        .setQueryLength(nativeQueryLength)
                        .setFilteredNamespaceCount(nativeNumNamespacesFiltered)
                        .setFilteredSchemaTypeCount(nativeNumSchemaTypesFiltered)
                        .setRequestedPageSize(nativeRequestedPageSize)
                        .setCurrentPageReturnedResultCount(nativeNumResultsReturnedCurrentPage)
                        .setIsFirstPage(nativeIsFirstPage)
                        .setParseQueryLatencyMillis(nativeParseQueryLatencyMillis)
                        .setRankingStrategy(nativeRankingStrategy)
                        .setScoredDocumentCount(nativeNumDocumentsScored)
                        .setScoringLatencyMillis(nativeScoringLatencyMillis)
                        .setRankingLatencyMillis(nativeRankingLatencyMillis)
                        .setResultWithSnippetsCount(nativeNumResultsSnippeted)
                        .setDocumentRetrievingLatencyMillis(nativeDocumentRetrievingLatencyMillis);
        final SearchStats sStats = sStatsBuilder.build();

        assertThat(sStats.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(sStats.getDatabase()).isEqualTo(TEST_DATA_BASE);
        assertThat(sStats.getStatusCode()).isEqualTo(TEST_STATUS_CODE);
        assertThat(sStats.getTotalLatencyMillis()).isEqualTo(TEST_TOTAL_LATENCY_MILLIS);
        assertThat(sStats.getRewriteSearchSpecLatencyMillis())
                .isEqualTo(rewriteSearchSpecLatencyMillis);
        assertThat(sStats.getRewriteSearchResultLatencyMillis())
                .isEqualTo(rewriteSearchResultLatencyMillis);
        assertThat(sStats.getVisibilityScope()).isEqualTo(visibilityScope);
        assertThat(sStats.getNativeLatencyMillis()).isEqualTo(nativeLatencyMillis);
        assertThat(sStats.getTermCount()).isEqualTo(nativeNumTerms);
        assertThat(sStats.getQueryLength()).isEqualTo(nativeQueryLength);
        assertThat(sStats.getFilteredNamespaceCount()).isEqualTo(nativeNumNamespacesFiltered);
        assertThat(sStats.getFilteredSchemaTypeCount()).isEqualTo(nativeNumSchemaTypesFiltered);
        assertThat(sStats.getRequestedPageSize()).isEqualTo(nativeRequestedPageSize);
        assertThat(sStats.getCurrentPageReturnedResultCount())
                .isEqualTo(nativeNumResultsReturnedCurrentPage);
        assertThat(sStats.isFirstPage()).isTrue();
        assertThat(sStats.getParseQueryLatencyMillis()).isEqualTo(nativeParseQueryLatencyMillis);
        assertThat(sStats.getRankingStrategy()).isEqualTo(nativeRankingStrategy);
        assertThat(sStats.getScoredDocumentCount()).isEqualTo(nativeNumDocumentsScored);
        assertThat(sStats.getScoringLatencyMillis()).isEqualTo(nativeScoringLatencyMillis);
        assertThat(sStats.getRankingLatencyMillis()).isEqualTo(nativeRankingLatencyMillis);
        assertThat(sStats.getResultWithSnippetsCount()).isEqualTo(nativeNumResultsSnippeted);
        assertThat(sStats.getDocumentRetrievingLatencyMillis())
                .isEqualTo(nativeDocumentRetrievingLatencyMillis);
    }

    @Test
    public void testAppSearchStats_SetSchemaStats() {
        int nativeLatencyMillis = 1;
        int newTypeCount = 2;
        int compatibleTypeChangeCount = 3;
        int indexIncompatibleTypeChangeCount = 4;
        int backwardsIncompatibleTypeChangeCount = 5;
        final SetSchemaStats sStats =
                new SetSchemaStats.Builder(TEST_PACKAGE_NAME, TEST_DATA_BASE)
                        .setStatusCode(TEST_STATUS_CODE)
                        .setTotalLatencyMillis(TEST_TOTAL_LATENCY_MILLIS)
                        .setNativeLatencyMillis(nativeLatencyMillis)
                        .setNewTypeCount(newTypeCount)
                        .setCompatibleTypeChangeCount(compatibleTypeChangeCount)
                        .setIndexIncompatibleTypeChangeCount(indexIncompatibleTypeChangeCount)
                        .setBackwardsIncompatibleTypeChangeCount(
                                backwardsIncompatibleTypeChangeCount)
                        .build();

        assertThat(sStats.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(sStats.getDatabase()).isEqualTo(TEST_DATA_BASE);
        assertThat(sStats.getStatusCode()).isEqualTo(TEST_STATUS_CODE);
        assertThat(sStats.getTotalLatencyMillis()).isEqualTo(TEST_TOTAL_LATENCY_MILLIS);
        assertThat(sStats.getNativeLatencyMillis()).isEqualTo(nativeLatencyMillis);
        assertThat(sStats.getNewTypeCount()).isEqualTo(newTypeCount);
        assertThat(sStats.getCompatibleTypeChangeCount()).isEqualTo(compatibleTypeChangeCount);
        assertThat(sStats.getIndexIncompatibleTypeChangeCount())
                .isEqualTo(indexIncompatibleTypeChangeCount);
        assertThat(sStats.getBackwardsIncompatibleTypeChangeCount())
                .isEqualTo(backwardsIncompatibleTypeChangeCount);
    }

    @Test
    public void testAppSearchStats_RemoveStats() {
        int nativeLatencyMillis = 1;
        @RemoveStats.DeleteType int deleteType = 2;
        int documentDeletedCount = 3;

        final RemoveStats rStats =
                new RemoveStats.Builder(TEST_PACKAGE_NAME, TEST_DATA_BASE)
                        .setStatusCode(TEST_STATUS_CODE)
                        .setTotalLatencyMillis(TEST_TOTAL_LATENCY_MILLIS)
                        .setNativeLatencyMillis(nativeLatencyMillis)
                        .setDeleteType(deleteType)
                        .setDeletedDocumentCount(documentDeletedCount)
                        .build();

        assertThat(rStats.getPackageName()).isEqualTo(TEST_PACKAGE_NAME);
        assertThat(rStats.getDatabase()).isEqualTo(TEST_DATA_BASE);
        assertThat(rStats.getStatusCode()).isEqualTo(TEST_STATUS_CODE);
        assertThat(rStats.getTotalLatencyMillis()).isEqualTo(TEST_TOTAL_LATENCY_MILLIS);
        assertThat(rStats.getNativeLatencyMillis()).isEqualTo(nativeLatencyMillis);
        assertThat(rStats.getDeleteType()).isEqualTo(deleteType);
        assertThat(rStats.getDeletedDocumentCount()).isEqualTo(documentDeletedCount);
    }
}
