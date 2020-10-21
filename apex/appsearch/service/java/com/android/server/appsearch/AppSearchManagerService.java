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
package com.android.server.appsearch;

import android.annotation.NonNull;
import android.app.appsearch.AppSearchBatchResult;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.AppSearchSchema;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.IAppSearchManager;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.app.appsearch.exceptions.AppSearchException;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.UserHandle;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;
import com.android.server.appsearch.external.localbackend.AppSearchImpl;
import com.android.server.appsearch.external.localbackend.converter.GenericDocumentToProtoConverter;
import com.android.server.appsearch.external.localbackend.converter.SchemaToProtoConverter;
import com.android.server.appsearch.external.localbackend.converter.SearchResultToProtoConverter;
import com.android.server.appsearch.external.localbackend.converter.SearchSpecToProtoConverter;

import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.SchemaTypeConfigProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchSpecProto;

import java.io.IOException;
import java.util.List;

/**
 * TODO(b/142567528): add comments when implement this class
 */
public class AppSearchManagerService extends SystemService {
    private static final String TAG = "AppSearchManagerService";
    private static final char CALLING_NAME_DATABASE_DELIMITER = '$';

    public AppSearchManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.APP_SEARCH_SERVICE, new Stub());
    }

    private class Stub extends IAppSearchManager.Stub {
        @Override
        public void setSchema(
                @NonNull String databaseName,
                @NonNull List<Bundle> schemaBundles,
                boolean forceOverride,
                @NonNull AndroidFuture<AppSearchResult> callback) {
            Preconditions.checkNotNull(schemaBundles);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                SchemaProto.Builder schemaProtoBuilder = SchemaProto.newBuilder();
                for (int i = 0; i < schemaBundles.size(); i++) {
                    AppSearchSchema schema = new AppSearchSchema(schemaBundles.get(i));
                    SchemaTypeConfigProto schemaTypeProto = SchemaToProtoConverter.convert(schema);
                    schemaProtoBuilder.addTypes(schemaTypeProto);
                }
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                databaseName = rewriteDatabaseNameWithUid(databaseName, callingUid);
                impl.setSchema(databaseName, schemaProtoBuilder.build(), forceOverride);
                callback.complete(AppSearchResult.newSuccessfulResult(/*result=*/ null));
            } catch (Throwable t) {
                callback.complete(throwableToFailedResult(t));
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public void putDocuments(
                @NonNull String databaseName,
                @NonNull List<Bundle> documentBundles,
                @NonNull AndroidFuture<AppSearchBatchResult> callback) {
            Preconditions.checkNotNull(documentBundles);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                databaseName = rewriteDatabaseNameWithUid(databaseName, callingUid);
                AppSearchBatchResult.Builder<String, Void> resultBuilder =
                        new AppSearchBatchResult.Builder<>();
                for (int i = 0; i < documentBundles.size(); i++) {
                    GenericDocument document = new GenericDocument(documentBundles.get(i));
                    DocumentProto documentProto = GenericDocumentToProtoConverter.convert(document);
                    try {
                        impl.putDocument(databaseName, documentProto);
                        resultBuilder.setSuccess(document.getUri(), /*result=*/ null);
                    } catch (Throwable t) {
                        resultBuilder.setResult(document.getUri(), throwableToFailedResult(t));
                    }
                }
                callback.complete(resultBuilder.build());
            } catch (Throwable t) {
                callback.completeExceptionally(t);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public void getDocuments(@NonNull String databaseName, @NonNull String namespace,
                @NonNull List<String> uris, @NonNull AndroidFuture<AppSearchBatchResult> callback) {
            Preconditions.checkNotNull(uris);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                databaseName = rewriteDatabaseNameWithUid(databaseName, callingUid);
                AppSearchBatchResult.Builder<String, Bundle> resultBuilder =
                        new AppSearchBatchResult.Builder<>();
                for (int i = 0; i < uris.size(); i++) {
                    String uri = uris.get(i);
                    try {
                        DocumentProto documentProto = impl.getDocument(
                                databaseName, namespace, uri);
                        if (documentProto == null) {
                            resultBuilder.setFailure(
                                    uri, AppSearchResult.RESULT_NOT_FOUND, /*errorMessage=*/ null);
                        } else {
                            GenericDocument genericDocument =
                                    GenericDocumentToProtoConverter.convert(documentProto);
                            resultBuilder.setSuccess(uri, genericDocument.getBundle());
                        }
                    } catch (Throwable t) {
                        resultBuilder.setResult(uri, throwableToFailedResult(t));
                    }
                }
                callback.complete(resultBuilder.build());
            } catch (Throwable t) {
                callback.completeExceptionally(t);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        // TODO(sidchhabra): Do this in a threadpool.
        @Override
        public void query(
                @NonNull String databaseName,
                @NonNull String queryExpression,
                @NonNull Bundle searchSpecBundle,
                @NonNull AndroidFuture<AppSearchResult> callback) {
            Preconditions.checkNotNull(queryExpression);
            Preconditions.checkNotNull(searchSpecBundle);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                SearchSpec searchSpec = new SearchSpec(searchSpecBundle);
                SearchSpecProto searchSpecProto =
                        SearchSpecToProtoConverter.toSearchSpecProto(searchSpec);
                searchSpecProto = searchSpecProto.toBuilder()
                        .setQuery(queryExpression).build();
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                databaseName = rewriteDatabaseNameWithUid(databaseName, callingUid);
                // TODO(adorokhine): handle pagination
                SearchResultProto searchResultProto = impl.query(
                        databaseName,
                        searchSpecProto,
                        SearchSpecToProtoConverter.toResultSpecProto(searchSpec),
                        SearchSpecToProtoConverter.toScoringSpecProto(searchSpec));
                List<SearchResult> searchResultList =
                        SearchResultToProtoConverter.convert(searchResultProto);
                SearchResults searchResults =
                        new SearchResults(searchResultList, searchResultProto.getNextPageToken());
                callback.complete(AppSearchResult.newSuccessfulResult(searchResults));
            } catch (Throwable t) {
                callback.complete(throwableToFailedResult(t));
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public void delete(@NonNull String databaseName, @NonNull String namespace,
                List<String> uris, AndroidFuture<AppSearchBatchResult> callback) {
            Preconditions.checkNotNull(uris);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                databaseName = rewriteDatabaseNameWithUid(databaseName, callingUid);
                AppSearchBatchResult.Builder<String, Void> resultBuilder =
                        new AppSearchBatchResult.Builder<>();
                for (int i = 0; i < uris.size(); i++) {
                    String uri = uris.get(i);
                    try {
                        impl.remove(databaseName, namespace, uri);
                        resultBuilder.setSuccess(uri, /*result= */null);
                    } catch (Throwable t) {
                        resultBuilder.setResult(uri, throwableToFailedResult(t));
                    }
                }
                callback.complete(resultBuilder.build());
            } catch (Throwable t) {
                callback.completeExceptionally(t);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public void deleteByTypes(@NonNull String databaseName,
                List<String> schemaTypes, AndroidFuture<AppSearchBatchResult> callback) {
            Preconditions.checkNotNull(schemaTypes);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                databaseName = rewriteDatabaseNameWithUid(databaseName, callingUid);
                AppSearchBatchResult.Builder<String, Void> resultBuilder =
                        new AppSearchBatchResult.Builder<>();
                for (int i = 0; i < schemaTypes.size(); i++) {
                    String schemaType = schemaTypes.get(i);
                    try {
                        impl.removeByType(databaseName, schemaType);
                        resultBuilder.setSuccess(schemaType, /*result=*/ null);
                    } catch (Throwable t) {
                        resultBuilder.setResult(schemaType, throwableToFailedResult(t));
                    }
                }
                callback.complete(resultBuilder.build());
            } catch (Throwable t) {
                callback.completeExceptionally(t);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public void deleteAll(@NonNull String databaseName,
                @NonNull AndroidFuture<AppSearchResult> callback) {
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                databaseName = rewriteDatabaseNameWithUid(databaseName, callingUid);
                impl.removeAll(databaseName);
                callback.complete(AppSearchResult.newSuccessfulResult(null));
            } catch (Throwable t) {
                callback.complete(throwableToFailedResult(t));
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        /**
         * Rewrites the database name by adding a prefix of unique name for the given uid.
         *
         * <p>The current implementation returns the package name of the app with this uid in a
         * format like {@code com.example.package} or {@code com.example.sharedname:5678}.
         */
        @NonNull
        private String rewriteDatabaseNameWithUid(String databaseName, int callingUid) {
            // For regular apps, this call will return the package name. If callingUid is an
            // android:sharedUserId, this value may be another type of name and have a :uid suffix.
            String callingUidName = getContext().getPackageManager().getNameForUid(callingUid);
            if (callingUidName == null) {
                // Not sure how this is possible --- maybe app was uninstalled?
                throw new IllegalStateException(
                        "Failed to look up package name for uid " + callingUid);
            }
            return callingUidName + CALLING_NAME_DATABASE_DELIMITER + databaseName;
        }

        private <ValueType> AppSearchResult<ValueType> throwableToFailedResult(
                @NonNull Throwable t) {
            if (t instanceof AppSearchException) {
                return ((AppSearchException) t).toAppSearchResult();
            }

            @AppSearchResult.ResultCode int resultCode;
            if (t instanceof IllegalStateException) {
                resultCode = AppSearchResult.RESULT_INTERNAL_ERROR;
            } else if (t instanceof IllegalArgumentException) {
                resultCode = AppSearchResult.RESULT_INVALID_ARGUMENT;
            } else if (t instanceof IOException) {
                resultCode = AppSearchResult.RESULT_IO_ERROR;
            } else {
                resultCode = AppSearchResult.RESULT_UNKNOWN_ERROR;
            }
            return AppSearchResult.newFailedResult(resultCode, t.getMessage());
        }
    }
}
