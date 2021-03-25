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

package com.android.server.appsearch.external.localstorage;

import static com.google.common.truth.Truth.assertThat;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.appsearch.external.localstorage.stats.CallStats;
import com.android.server.appsearch.external.localstorage.stats.PutDocumentStats;
import com.android.server.appsearch.proto.PutDocumentStatsProto;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.List;

public class AppSearchLoggerTest {
    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private AppSearchImpl mAppSearchImpl;
    private TestLogger mLogger;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();

        // Give ourselves global query permissions
        mAppSearchImpl =
                AppSearchImpl.create(
                        mTemporaryFolder.newFolder(),
                        context,
                        VisibilityStore.NO_OP_USER_ID,
                        /*globalQuerierPackage=*/ context.getPackageName());
        mLogger = new TestLogger();
    }

    // Test only not thread safe.
    public class TestLogger implements AppSearchLogger {
        @Nullable PutDocumentStats mPutDocumentStats;

        @Override
        public void logStats(@NonNull CallStats stats) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void logStats(@NonNull PutDocumentStats stats) {
            mPutDocumentStats = stats;
        }
    }

    @Test
    public void testAppSearchLoggerHelper_testCopyNativeStats_putDocument() {
        final int nativeLatencyMillis = 3;
        final int nativeDocumentStoreLatencyMillis = 4;
        final int nativeIndexLatencyMillis = 5;
        final int nativeIndexMergeLatencyMillis = 6;
        final int nativeDocumentSize = 7;
        final int nativeNumTokensIndexed = 8;
        final boolean nativeExceededMaxNumTokens = true;
        PutDocumentStatsProto nativePutDocumentStats =
                PutDocumentStatsProto.newBuilder()
                        .setLatencyMs(nativeLatencyMillis)
                        .setDocumentStoreLatencyMs(nativeDocumentStoreLatencyMillis)
                        .setIndexLatencyMs(nativeIndexLatencyMillis)
                        .setIndexMergeLatencyMs(nativeIndexMergeLatencyMillis)
                        .setDocumentSize(nativeDocumentSize)
                        .setTokenizationStats(
                                PutDocumentStatsProto.TokenizationStats.newBuilder()
                                        .setNumTokensIndexed(nativeNumTokensIndexed)
                                        .setExceededMaxTokenNum(nativeExceededMaxNumTokens)
                                        .build())
                        .build();
        PutDocumentStats.Builder pBuilder = new PutDocumentStats.Builder("packageName", "database");

        AppSearchLoggerHelper.copyNativeStats(nativePutDocumentStats, pBuilder);

        PutDocumentStats pStats = pBuilder.build();
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

    //
    // Testing actual logging
    //
    @Test
    public void testLoggingStats_putDocument() throws Exception {
        // Insert schema
        final String testPackageName = "testPackage";
        final String testDatabase = "testDatabase";
        List<AppSearchSchema> schemas =
                Collections.singletonList(new AppSearchSchema.Builder("type").build());
        mAppSearchImpl.setSchema(
                testPackageName,
                testDatabase,
                schemas,
                /*schemasNotPlatformSurfaceable=*/ Collections.emptyList(),
                /*schemasPackageAccessible=*/ Collections.emptyMap(),
                /*forceOverride=*/ false,
                /*version=*/ 0);
        GenericDocument document =
                new GenericDocument.Builder<>("namespace", "uri", "type").build();

        mAppSearchImpl.putDocument(testPackageName, testDatabase, document, mLogger);

        PutDocumentStats pStats = mLogger.mPutDocumentStats;
        assertThat(pStats).isNotNull();
        assertThat(pStats.getGeneralStats().getPackageName()).isEqualTo(testPackageName);
        assertThat(pStats.getGeneralStats().getDatabase()).isEqualTo(testDatabase);
        assertThat(pStats.getGeneralStats().getStatusCode()).isEqualTo(AppSearchResult.RESULT_OK);
        // The rest of native stats have been tested in testCopyNativeStats
        assertThat(pStats.getNativeDocumentSizeBytes()).isGreaterThan(0);
    }
}
