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
import android.app.appsearch.IAppSearchManager;
import android.content.Context;
import android.os.Binder;
import android.os.UserHandle;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;
import com.android.server.appsearch.impl.AppSearchImpl;
import com.android.server.appsearch.impl.ImplInstanceManager;

import com.google.android.icing.proto.DocumentProto;
import com.google.android.icing.proto.ResultSpecProto;
import com.google.android.icing.proto.SchemaProto;
import com.google.android.icing.proto.ScoringSpecProto;
import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.StatusProto;

import java.io.IOException;
import java.util.List;

/**
 * TODO(b/142567528): add comments when implement this class
 */
public class AppSearchManagerService extends SystemService {

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
                @NonNull byte[] schemaBytes,
                boolean forceOverride,
                @NonNull AndroidFuture<AppSearchResult> callback) {
            Preconditions.checkNotNull(schemaBytes);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            long callingIdentity = Binder.clearCallingIdentity();
            try {
                SchemaProto schema = SchemaProto.parseFrom(schemaBytes);
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                impl.setSchema(callingUid, schema, forceOverride);
                callback.complete(AppSearchResult.newSuccessfulResult(/*value=*/ null));
            } catch (Throwable t) {
                callback.complete(throwableToFailedResult(t));
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public void putDocuments(
                @NonNull List documentsBytes,
                @NonNull AndroidFuture<AppSearchBatchResult> callback) {
            Preconditions.checkNotNull(documentsBytes);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            long callingIdentity = Binder.clearCallingIdentity();
            try {
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                AppSearchBatchResult.Builder<String, Void> resultBuilder =
                        new AppSearchBatchResult.Builder<>();
                for (int i = 0; i < documentsBytes.size(); i++) {
                    byte[] documentBytes = (byte[]) documentsBytes.get(i);
                    DocumentProto document = DocumentProto.parseFrom(documentBytes);
                    try {
                        impl.putDocument(callingUid, document);
                        resultBuilder.setSuccess(document.getUri(), /*value=*/ null);
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
        public void getDocuments(
                @NonNull List<String> uris, @NonNull AndroidFuture<AppSearchBatchResult> callback) {
            Preconditions.checkNotNull(uris);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            long callingIdentity = Binder.clearCallingIdentity();
            try {
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                AppSearchBatchResult.Builder<String, byte[]> resultBuilder =
                        new AppSearchBatchResult.Builder<>();
                for (int i = 0; i < uris.size(); i++) {
                    String uri = uris.get(i);
                    try {
                        DocumentProto document = impl.getDocument(callingUid, uri);
                        if (document == null) {
                            resultBuilder.setFailure(
                                    uri, AppSearchResult.RESULT_NOT_FOUND, /*errorMessage=*/ null);
                        } else {
                            resultBuilder.setSuccess(uri, document.toByteArray());
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
                @NonNull byte[] searchSpecBytes,
                @NonNull byte[] resultSpecBytes,
                @NonNull byte[] scoringSpecBytes,
                @NonNull AndroidFuture<AppSearchResult> callback) {
            Preconditions.checkNotNull(searchSpecBytes);
            Preconditions.checkNotNull(resultSpecBytes);
            Preconditions.checkNotNull(scoringSpecBytes);
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            long callingIdentity = Binder.clearCallingIdentity();
            try {
                SearchSpecProto searchSpecProto = SearchSpecProto.parseFrom(searchSpecBytes);
                ResultSpecProto resultSpecProto = ResultSpecProto.parseFrom(resultSpecBytes);
                ScoringSpecProto scoringSpecProto = ScoringSpecProto.parseFrom(scoringSpecBytes);
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                SearchResultProto searchResultProto =
                        impl.query(callingUid, searchSpecProto, resultSpecProto, scoringSpecProto);
                // TODO(sidchhabra): Translate SearchResultProto errors into error codes. This might
                //     better be done in AppSearchImpl by throwing an AppSearchException.
                if (searchResultProto.getStatus().getCode() != StatusProto.Code.OK) {
                    callback.complete(
                            AppSearchResult.newFailedResult(
                                    AppSearchResult.RESULT_INTERNAL_ERROR,
                                    searchResultProto.getStatus().getMessage()));
                } else {
                    callback.complete(
                            AppSearchResult.newSuccessfulResult(searchResultProto.toByteArray()));
                }
            } catch (Throwable t) {
                callback.complete(throwableToFailedResult(t));
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public void deleteAll(@NonNull AndroidFuture<AppSearchResult> callback) {
            Preconditions.checkNotNull(callback);
            int callingUid = Binder.getCallingUidOrThrow();
            int callingUserId = UserHandle.getUserId(callingUid);
            long callingIdentity = Binder.clearCallingIdentity();
            try {
                AppSearchImpl impl = ImplInstanceManager.getInstance(getContext(), callingUserId);
                impl.deleteAll(callingUid);
                callback.complete(AppSearchResult.newSuccessfulResult(null));
            } catch (Throwable t) {
                callback.complete(throwableToFailedResult(t));
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
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
