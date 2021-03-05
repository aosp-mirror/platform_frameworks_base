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
import android.content.Context;

import com.android.internal.util.Preconditions;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Provides access to the centralized AppSearch index maintained by the system.
 *
 * <p>AppSearch is a search library for managing structured data featuring:
 * <ul>
 *     <li>A fully offline on-device solution
 *     <li>A set of APIs for applications to index documents and retrieve them via full-text search
 *     <li>APIs for applications to allow the System to display their content on system UI surfaces
 *     <li>Similarly, APIs for applications to allow the System to share their content with other
 *     specified applications.
 * </ul>
 *
 * <p>Applications create a database by opening an {@link AppSearchSession}.
 *
 * <p>Example:
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
 * structure of data. The schema is set by calling {@link AppSearchSession#setSchema}. The schema
 * is composed of a collection of {@link AppSearchSchema} objects, each of which defines a unique
 * type of data.
 *
 * <p>Example:
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
 * containing a URI, namespace, time-to-live, score, and properties. A namespace organizes a
 * logical group of documents. For example, a namespace can be created to group documents on a
 * per-account basis. A URI identifies a single document within a namespace. The combination
 * of URI and namespace uniquely identifies a {@link GenericDocument} in the database.
 *
 * <p>Once the schema has been set, {@link GenericDocument} objects can be put into the database
 * and indexed by calling {@link AppSearchSession#put}.
 *
 * <p>Example:
 * <pre>
 * // Although for this example we use GenericDocument directly, we recommend extending
 * // GenericDocument to create specific types (i.e. Email) with specific setters/getters.
 * GenericDocument email = new GenericDocument.Builder<>(URI, EMAIL_SCHEMA_TYPE)
 *     .setNamespace(NAMESPACE)
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
 * <p>Alternatively, {@link AppSearchSession#getByUri} can be called to retrieve documents by URI
 * and namespace.
 *
 * <p>Document removal is done either by time-to-live expiration, or explicitly calling a remove
 * operation. Remove operations can be done by URI and namespace via
 * {@link AppSearchSession#remove(RemoveByUriRequest, Executor, BatchResultCallback)},
 * or by query via {@link AppSearchSession#remove(String, SearchSpec, Executor, Consumer)}.
 */
@SystemService(Context.APP_SEARCH_SERVICE)
public class AppSearchManager {
    /**
     * The default empty database name.
     *
     * @hide
     */
    public static final String DEFAULT_DATABASE_NAME = "";

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
            private String mDatabaseName = DEFAULT_DATABASE_NAME;
            private boolean mBuilt = false;

            /**
             * Sets the name of the database associated with {@link AppSearchSession}.
             *
             * <p>{@link AppSearchSession} will create or open a database under the given name.
             *
             * <p>Databases with different names are fully separate with distinct types, namespaces,
             * and data.
             *
             * <p>Database name cannot contain {@code '/'}.
             *
             * <p>If not specified, defaults to the empty string.
             *
             * @param databaseName The name of the database.
             * @throws IllegalArgumentException if the databaseName contains {@code '/'}.
             */
            @NonNull
            public Builder setDatabaseName(@NonNull String databaseName) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Objects.requireNonNull(databaseName);
                if (databaseName.contains("/")) {
                    throw new IllegalArgumentException("Database name cannot contain '/'");
                }
                mDatabaseName = databaseName;
                return this;
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
                mContext.getUserId(),
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
                mService, mContext.getUserId(), getPackageName(), executor, callback);
    }

    /** Returns the package name that should be used for uid verification. */
    @NonNull
    private String getPackageName() {
        return mContext.getOpPackageName();
    }
}
