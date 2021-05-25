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
package android.app.appsearch;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.SystemService;
import android.app.appsearch.aidl.IAppSearchManager;
import android.content.Context;

import com.android.internal.util.Preconditions;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Provides access to the centralized AppSearch index maintained by the system.
 *
 * <p>AppSearch is an offline, on-device search library for managing structured data featuring:
 *
 * <ul>
 *   <li>APIs to index and retrieve data via full-text search.
 *   <li>An API for applications to explicitly grant read-access permission of their data to other
 *   applications.
 *   <b>See: {@link SetSchemaRequest.Builder#setSchemaTypeVisibilityForPackage}</b>
 *   <li>An API for applications to opt into or out of having their data displayed on System UI
 *   surfaces by the System-designated global querier.
 *   <b>See: {@link SetSchemaRequest.Builder#setSchemaTypeDisplayedBySystem}</b>
 * </ul>
 *
 * <p>Applications create a database by opening an {@link AppSearchSession}.
 *
 * <p>Example:
 *
 * <pre>
 * AppSearchManager appSearchManager = context.getSystemService(AppSearchManager.class);
 *
 * AppSearchManager.SearchContext searchContext = new AppSearchManager.SearchContext.Builder().
 *    setDatabaseName(dbName).build());
 * appSearchManager.createSearchSession(searchContext, mExecutor, appSearchSessionResult -&gt; {
 *      mAppSearchSession = appSearchSessionResult.getResultValue();
 * });</pre>
 *
 * <p>After opening the session, a schema must be set in order to define the organizational
 * structure of data. The schema is set by calling {@link AppSearchSession#setSchema}. The schema is
 * composed of a collection of {@link AppSearchSchema} objects, each of which defines a unique type
 * of data.
 *
 * <p>Example:
 *
 * <pre>
 * AppSearchSchema emailSchemaType = new AppSearchSchema.Builder("Email")
 *     .addProperty(new StringPropertyConfig.Builder("subject")
 *        .setCardinality(PropertyConfig.CARDINALITY_OPTIONAL)
 *        .setIndexingType(PropertyConfig.INDEXING_TYPE_PREFIXES)
 *        .setTokenizerType(PropertyConfig.TOKENIZER_TYPE_PLAIN)
 *    .build()
 * ).build();
 *
 * SetSchemaRequest request = new SetSchemaRequest.Builder().addSchema(emailSchemaType).build();
 * mAppSearchSession.set(request, mExecutor, appSearchResult -&gt; {
 *      if (appSearchResult.isSuccess()) {
 *           //Schema has been successfully set.
 *      }
 * });</pre>
 *
 * <p>The basic unit of data in AppSearch is represented as a {@link GenericDocument} object,
 * containing an ID, namespace, time-to-live, score, and properties. A namespace organizes a logical
 * group of documents. For example, a namespace can be created to group documents on a per-account
 * basis. An ID identifies a single document within a namespace. The combination of namespace and ID
 * uniquely identifies a {@link GenericDocument} in the database.
 *
 * <p>Once the schema has been set, {@link GenericDocument} objects can be put into the database and
 * indexed by calling {@link AppSearchSession#put}.
 *
 * <p>Example:
 *
 * <pre>
 * // Although for this example we use GenericDocument directly, we recommend extending
 * // GenericDocument to create specific types (i.e. Email) with specific setters/getters.
 * GenericDocument email = new GenericDocument.Builder<>(NAMESPACE, ID, EMAIL_SCHEMA_TYPE)
 *     .setPropertyString(“subject”, EMAIL_SUBJECT)
 *     .setScore(EMAIL_SCORE)
 *     .build();
 *
 * PutDocumentsRequest request = new PutDocumentsRequest.Builder().addGenericDocuments(email)
 *     .build();
 * mAppSearchSession.put(request, mExecutor, appSearchBatchResult -&gt; {
 *      if (appSearchBatchResult.isSuccess()) {
 *           //All documents have been successfully indexed.
 *      }
 * });</pre>
 *
 * <p>Searching within the database is done by calling {@link AppSearchSession#search} and providing
 * the query string to search for, as well as a {@link SearchSpec}.
 *
 * <p>Alternatively, {@link AppSearchSession#getByDocumentId} can be called to retrieve documents by
 * namespace and ID.
 *
 * <p>Document removal is done either by time-to-live expiration, or explicitly calling a remove
 * operation. Remove operations can be done by namespace and ID via {@link
 * AppSearchSession#remove(RemoveByDocumentIdRequest, Executor, BatchResultCallback)}, or by query
 * via {@link AppSearchSession#remove(String, SearchSpec, Executor, Consumer)}.
 */
@SystemService(Context.APP_SEARCH_SERVICE)
public class AppSearchManager {

    private final IAppSearchManager mService;
    private final Context mContext;

    /** @hide */
    public AppSearchManager(@NonNull Context context, @NonNull IAppSearchManager service) {
        mContext = Objects.requireNonNull(context);
        mService = Objects.requireNonNull(service);
    }

    /** Contains information about how to create the search session. */
    public static final class SearchContext {
        final String mDatabaseName;

        SearchContext(@NonNull String databaseName) {
            mDatabaseName = Objects.requireNonNull(databaseName);
        }

        /**
         * Returns the name of the database to create or open.
         *
         * <p>Databases with different names are fully separate with distinct types, namespaces, and
         * data.
         */
        @NonNull
        public String getDatabaseName() {
            return mDatabaseName;
        }

        /** Builder for {@link SearchContext} objects. */
        public static final class Builder {
            private final String mDatabaseName;
            private boolean mBuilt = false;

            /**
             * Creates a new {@link SearchContext.Builder}.
             *
             * <p>{@link AppSearchSession} will create or open a database under the given name.
             *
             * <p>Databases with different names are fully separate with distinct types, namespaces,
             * and data.
             *
             * <p>Database name cannot contain {@code '/'}.
             *
             * @param databaseName The name of the database.
             * @throws IllegalArgumentException if the databaseName contains {@code '/'}.
             */
            public Builder(@NonNull String databaseName) {
                Objects.requireNonNull(databaseName);
                Preconditions.checkArgument(
                        !databaseName.contains("/"), "Database name cannot contain '/'");
                mDatabaseName = databaseName;
            }

            /** Builds a {@link SearchContext} instance. */
            @NonNull
            public SearchContext build() {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                mBuilt = true;
                return new SearchContext(mDatabaseName);
            }
        }
    }

    /**
     * Creates a new {@link AppSearchSession}.
     *
     * <p>This process requires an AppSearch native indexing file system. If it's not created, the
     * initialization process will create one under the user's credential encrypted directory.
     *
     * @param searchContext The {@link SearchContext} contains all information to create a new
     *     {@link AppSearchSession}
     * @param executor Executor on which to invoke the callback.
     * @param callback The {@link AppSearchResult}&lt;{@link AppSearchSession}&gt; of performing
     *     this operation. Or a {@link AppSearchResult} with failure reason code and error
     *     information.
     */
    public void createSearchSession(
            @NonNull SearchContext searchContext,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<AppSearchSession>> callback) {
        Objects.requireNonNull(searchContext);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        AppSearchSession.createSearchSession(
                searchContext,
                mService,
                mContext.getUser(),
                getPackageName(),
                executor,
                callback);
    }

    /**
     * Creates a new {@link GlobalSearchSession}.
     *
     * <p>This process requires an AppSearch native indexing file system. If it's not created, the
     * initialization process will create one under the user's credential encrypted directory.
     *
     * @param executor Executor on which to invoke the callback.
     * @param callback The {@link AppSearchResult}&lt;{@link GlobalSearchSession}&gt; of performing
     *     this operation. Or a {@link AppSearchResult} with failure reason code and error
     *     information.
     */
    public void createGlobalSearchSession(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<AppSearchResult<GlobalSearchSession>> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        GlobalSearchSession.createGlobalSearchSession(
                mService, mContext.getUser(), getPackageName(), executor, callback);
    }

    /** Returns the package name that should be used for uid verification. */
    @NonNull
    private String getPackageName() {
        return mContext.getOpPackageName();
    }
}
