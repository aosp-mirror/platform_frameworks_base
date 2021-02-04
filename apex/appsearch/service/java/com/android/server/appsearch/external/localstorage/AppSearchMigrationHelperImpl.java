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

import static android.app.appsearch.AppSearchResult.throwableToFailedResult;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchMigrationHelper;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.SearchResultPage;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.SetSchemaResponse;
import android.app.appsearch.exceptions.AppSearchException;
import android.os.Bundle;
import android.os.Parcel;

import com.android.internal.util.Preconditions;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.CodedOutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * An implementation of {@link AppSearchMigrationHelper} which query document and save post-migrated
 * documents to locally in the app's storage space.
 */
class AppSearchMigrationHelperImpl implements AppSearchMigrationHelper {
    private final AppSearchImpl mAppSearchImpl;
    private final String mPackageName;
    private final String mDatabaseName;
    private final File mFile;
    private final Map<String, Integer> mCurrentVersionMap;
    private final Map<String, Integer> mFinalVersionMap;

    AppSearchMigrationHelperImpl(
            @NonNull AppSearchImpl appSearchImpl,
            @NonNull Map<String, Integer> currentVersionMap,
            @NonNull Map<String, Integer> finalVersionMap,
            @NonNull String packageName,
            @NonNull String databaseName)
            throws IOException {
        mAppSearchImpl = Preconditions.checkNotNull(appSearchImpl);
        mCurrentVersionMap = Preconditions.checkNotNull(currentVersionMap);
        mFinalVersionMap = Preconditions.checkNotNull(finalVersionMap);
        mPackageName = Preconditions.checkNotNull(packageName);
        mDatabaseName = Preconditions.checkNotNull(databaseName);
        mFile = File.createTempFile(/*prefix=*/ "appsearch", /*suffix=*/ null);
    }

    @Override
    public void queryAndTransform(
            @NonNull String schemaType, @NonNull AppSearchMigrationHelper.Transformer migrator)
            throws Exception {
        Preconditions.checkState(mFile.exists(), "Internal temp file does not exist.");
        int currentVersion = mCurrentVersionMap.get(schemaType);
        int finalVersion = mFinalVersionMap.get(schemaType);
        try (FileOutputStream outputStream = new FileOutputStream(mFile)) {
            // TODO(b/151178558) change the output stream so that we can use it in platform
            CodedOutputStream codedOutputStream = CodedOutputStream.newInstance(outputStream);
            SearchResultPage searchResultPage =
                    mAppSearchImpl.query(
                            mPackageName,
                            mDatabaseName,
                            /*queryExpression=*/ "",
                            new SearchSpec.Builder()
                                    .addFilterSchemas(schemaType)
                                    .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                                    .build());
            while (!searchResultPage.getResults().isEmpty()) {
                for (int i = 0; i < searchResultPage.getResults().size(); i++) {
                    GenericDocument newDocument =
                            migrator.transform(
                                    currentVersion,
                                    finalVersion,
                                    searchResultPage.getResults().get(i).getDocument());
                    Bundle bundle = newDocument.getBundle();
                    Parcel parcel = Parcel.obtain();
                    parcel.writeBundle(bundle);
                    byte[] serializedMessage = parcel.marshall();
                    parcel.recycle();
                    codedOutputStream.writeByteArrayNoTag(serializedMessage);
                }
                codedOutputStream.flush();
                searchResultPage = mAppSearchImpl.getNextPage(searchResultPage.getNextPageToken());
                outputStream.flush();
            }
        }
    }

    /**
     * Reads {@link GenericDocument} from the temperate file and saves them to AppSearch.
     *
     * <p>This method should be only called once.
     *
     * @return the {@link AppSearchBatchResult} for migration documents.
     */
    @NonNull
    public SetSchemaResponse readAndPutDocuments(SetSchemaResponse.Builder responseBuilder)
            throws IOException, AppSearchException {
        Preconditions.checkState(mFile.exists(), "Internal temp file does not exist.");
        try (InputStream inputStream = new FileInputStream(mFile)) {
            CodedInputStream codedInputStream = CodedInputStream.newInstance(inputStream);
            while (!codedInputStream.isAtEnd()) {
                GenericDocument document = readDocumentFromInputStream(codedInputStream);
                try {
                    mAppSearchImpl.putDocument(mPackageName, mDatabaseName, document);
                } catch (Throwable t) {
                    responseBuilder.setFailure(
                            document.getSchemaType(),
                            document.getNamespace(),
                            document.getUri(),
                            throwableToFailedResult(t));
                }
            }
            mAppSearchImpl.persistToDisk();
            return responseBuilder.build();
        } finally {
            mFile.delete();
        }
    }

    void deleteTempFile() {
        mFile.delete();
    }

    /**
     * Reads {@link GenericDocument} from given {@link CodedInputStream}.
     *
     * @param codedInputStream The codedInputStream to read from
     * @throws IOException on File operation error.
     */
    @NonNull
    private static GenericDocument readDocumentFromInputStream(
            @NonNull CodedInputStream codedInputStream) throws IOException {
        byte[] serializedMessage = codedInputStream.readByteArray();

        Parcel parcel = Parcel.obtain();
        parcel.unmarshall(serializedMessage, 0, serializedMessage.length);
        parcel.setDataPosition(0);
        Bundle bundle = parcel.readBundle();
        parcel.recycle();

        return new GenericDocument(bundle);
    }
}
