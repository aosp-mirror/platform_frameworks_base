/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.app.appsearch;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.RemoteException;

import com.android.internal.infra.AndroidFuture;

import com.google.android.icing.proto.SchemaProto;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides access to the centralized AppSearch index maintained by the system.
 *
 * <p>Apps can index structured text documents with AppSearch, which can then be retrieved through
 * the query API.
 *
 * @hide
 */
@SystemService(Context.APP_SEARCH_SERVICE)
public class AppSearchManager {
    private final IAppSearchManager mService;

    /** @hide */
    public AppSearchManager(@NonNull IAppSearchManager service) {
        mService = service;
    }

    /**
     * Sets the schema being used by documents provided to the #put method.
     *
     * <p>This operation is performed asynchronously. On success, the provided callback will be
     * called with {@code null}. On failure, the provided callback will be called with a
     * {@link Throwable} describing the failure.
     *
     * <p>It is a no-op to set the same schema as has been previously set; this is handled
     * efficiently.
     *
     * <p>AppSearch automatically handles the following types of schema changes:
     * <ul>
     *     <li>Addition of new types (No changes to storage or index)
     *     <li>Removal of an existing type (All documents of the removed type are deleted)
     *     <li>Addition of new 'optional' property to a type (No changes to storage or index)
     *     <li>Removal of existing property of any cardinality (All documents reindexed)
     * </ul>
     *
     * <p>This method will return an error when attempting to make the following types of changes:
     * <ul>
     *     <li>Changing the type of an existing property
     *     <li>Adding a 'required' property
     * </ul>
     *
     * @param schema The schema config for this app.
     * @param executor Executor on which to invoke the callback.
     * @param callback Callback to receive errors resulting from setting the schema. If the
     *                 operation succeeds, the callback will be invoked with {@code null}.
     *
     * @hide
     */
    // TODO(b/143789408): linkify #put after that API is created
    // TODO(b/145635424): add a 'force' param to setSchema after the corresponding API is finalized
    //     in Icing Library
    // TODO(b/145635424): Update the documentation above once the Schema mutation APIs of Icing
    //     Library are finalized
    public void setSchema(
            @NonNull AppSearchSchema schema,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<? super Throwable> callback) {
        SchemaProto schemaProto = schema.getProto();
        byte[] schemaBytes = schemaProto.toByteArray();
        AndroidFuture<Void> future = new AndroidFuture<>();
        try {
            mService.setSchema(schemaBytes, future);
        } catch (RemoteException e) {
            future.completeExceptionally(e);
        }
        future.whenCompleteAsync((noop, err) -> callback.accept(err), executor);
    }
}
