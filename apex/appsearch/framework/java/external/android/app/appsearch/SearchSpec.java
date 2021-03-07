/*
 * Copyright 2020 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.app.appsearch.exceptions.IllegalSearchSpecException;
import android.os.Bundle;
import android.util.ArrayMap;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class represents the specification logic for AppSearch. It can be used to set the type of
 * search, like prefix or exact only or apply filters to search for a specific schema type only etc.
 */
// TODO(sidchhabra) : AddResultSpec fields for Snippets etc.
public final class SearchSpec {
    /**
     * Schema type to be used in {@link SearchSpec.Builder#addProjection} to apply property paths to
     * all results, excepting any types that have had their own, specific property paths set.
     */
    public static final String PROJECTION_SCHEMA_TYPE_WILDCARD = "*";

    static final String TERM_MATCH_TYPE_FIELD = "termMatchType";
    static final String SCHEMA_FIELD = "schema";
    static final String NAMESPACE_FIELD = "namespace";
    static final String PACKAGE_NAME_FIELD = "packageName";
    static final String NUM_PER_PAGE_FIELD = "numPerPage";
    static final String RANKING_STRATEGY_FIELD = "rankingStrategy";
    static final String ORDER_FIELD = "order";
    static final String SNIPPET_COUNT_FIELD = "snippetCount";
    static final String SNIPPET_COUNT_PER_PROPERTY_FIELD = "snippetCountPerProperty";
    static final String MAX_SNIPPET_FIELD = "maxSnippet";
    static final String PROJECTION_TYPE_PROPERTY_PATHS_FIELD = "projectionTypeFieldMasks";

    /** @hide */
    public static final int DEFAULT_NUM_PER_PAGE = 10;

    // TODO(b/170371356): In framework, we may want these limits to be flag controlled.
    //  If that happens, the @IntRange() directives in this class may have to change.
    private static final int MAX_NUM_PER_PAGE = 10_000;
    private static final int MAX_SNIPPET_COUNT = 10_000;
    private static final int MAX_SNIPPET_PER_PROPERTY_COUNT = 10_000;
    private static final int MAX_SNIPPET_SIZE_LIMIT = 10_000;

    /**
     * Term Match Type for the query.
     *
     * @hide
     */
    // NOTE: The integer values of these constants must match the proto enum constants in
    // {@link com.google.android.icing.proto.SearchSpecProto.termMatchType}
    @IntDef(value = {TERM_MATCH_EXACT_ONLY, TERM_MATCH_PREFIX})
    @Retention(RetentionPolicy.SOURCE)
    public @interface TermMatch {}

    /**
     * Query terms will only match exact tokens in the index.
     *
     * <p>Ex. A query term "foo" will only match indexed token "foo", and not "foot" or "football".
     */
    public static final int TERM_MATCH_EXACT_ONLY = 1;
    /**
     * Query terms will match indexed tokens when the query term is a prefix of the token.
     *
     * <p>Ex. A query term "foo" will match indexed tokens like "foo", "foot", and "football".
     */
    public static final int TERM_MATCH_PREFIX = 2;

    /**
     * Ranking Strategy for query result.
     *
     * @hide
     */
    // NOTE: The integer values of these constants must match the proto enum constants in
    // {@link ScoringSpecProto.RankingStrategy.Code}
    @IntDef(
            value = {
                RANKING_STRATEGY_NONE,
                RANKING_STRATEGY_DOCUMENT_SCORE,
                RANKING_STRATEGY_CREATION_TIMESTAMP,
                RANKING_STRATEGY_RELEVANCE_SCORE,
                RANKING_STRATEGY_USAGE_COUNT,
                RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RankingStrategy {}

    /** No Ranking, results are returned in arbitrary order. */
    public static final int RANKING_STRATEGY_NONE = 0;
    /** Ranked by app-provided document scores. */
    public static final int RANKING_STRATEGY_DOCUMENT_SCORE = 1;
    /** Ranked by document creation timestamps. */
    public static final int RANKING_STRATEGY_CREATION_TIMESTAMP = 2;
    /** Ranked by document relevance score. */
    public static final int RANKING_STRATEGY_RELEVANCE_SCORE = 3;
    /** Ranked by number of usages. */
    public static final int RANKING_STRATEGY_USAGE_COUNT = 4;
    /** Ranked by timestamp of last usage. */
    public static final int RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP = 5;

    /**
     * Order for query result.
     *
     * @hide
     */
    // NOTE: The integer values of these constants must match the proto enum constants in
    // {@link ScoringSpecProto.Order.Code}
    @IntDef(value = {ORDER_DESCENDING, ORDER_ASCENDING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Order {}

    /** Search results will be returned in a descending order. */
    public static final int ORDER_DESCENDING = 0;
    /** Search results will be returned in an ascending order. */
    public static final int ORDER_ASCENDING = 1;

    private final Bundle mBundle;

    /** @hide */
    public SearchSpec(@NonNull Bundle bundle) {
        Preconditions.checkNotNull(bundle);
        mBundle = bundle;
    }

    /**
     * Returns the {@link Bundle} populated by this builder.
     *
     * @hide
     */
    @NonNull
    public Bundle getBundle() {
        return mBundle;
    }

    /** Returns how the query terms should match terms in the index. */
    public @TermMatch int getTermMatch() {
        return mBundle.getInt(TERM_MATCH_TYPE_FIELD, -1);
    }

    /**
     * Returns the list of schema types to search for.
     *
     * <p>If empty, the query will search over all schema types.
     */
    @NonNull
    public List<String> getFilterSchemas() {
        List<String> schemas = mBundle.getStringArrayList(SCHEMA_FIELD);
        if (schemas == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(schemas);
    }

    /**
     * Returns the list of namespaces to search over.
     *
     * <p>If empty, the query will search over all namespaces.
     */
    @NonNull
    public List<String> getFilterNamespaces() {
        List<String> namespaces = mBundle.getStringArrayList(NAMESPACE_FIELD);
        if (namespaces == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(namespaces);
    }

    /**
     * Returns the list of package name filters to search over.
     *
     * <p>If empty, the query will search over all packages that the caller has access to. If
     * package names are specified which caller doesn't have access to, then those package names
     * will be ignored.
     */
    @NonNull
    public List<String> getFilterPackageNames() {
        List<String> packageNames = mBundle.getStringArrayList(PACKAGE_NAME_FIELD);
        if (packageNames == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(packageNames);
    }

    /** Returns the number of results per page in the result set. */
    public int getResultCountPerPage() {
        return mBundle.getInt(NUM_PER_PAGE_FIELD, DEFAULT_NUM_PER_PAGE);
    }

    /** Returns the ranking strategy. */
    public @RankingStrategy int getRankingStrategy() {
        return mBundle.getInt(RANKING_STRATEGY_FIELD);
    }

    /** Returns the order of returned search results (descending or ascending). */
    public @Order int getOrder() {
        return mBundle.getInt(ORDER_FIELD);
    }

    /** Returns how many documents to generate snippets for. */
    public int getSnippetCount() {
        return mBundle.getInt(SNIPPET_COUNT_FIELD);
    }

    /**
     * Returns how many matches for each property of a matching document to generate snippets for.
     */
    public int getSnippetCountPerProperty() {
        return mBundle.getInt(SNIPPET_COUNT_PER_PROPERTY_FIELD);
    }

    /** Returns the maximum size of a snippet in characters. */
    public int getMaxSnippetSize() {
        return mBundle.getInt(MAX_SNIPPET_FIELD);
    }

    /**
     * Returns a map from schema type to property paths to be used for projection.
     *
     * <p>If the map is empty, then all properties will be retrieved for all results.
     *
     * <p>Calling this function repeatedly is inefficient. Prefer to retain the Map returned by this
     * function, rather than calling it multiple times.
     */
    @NonNull
    public Map<String, List<String>> getProjections() {
        Bundle typePropertyPathsBundle = mBundle.getBundle(PROJECTION_TYPE_PROPERTY_PATHS_FIELD);
        Set<String> schemas = typePropertyPathsBundle.keySet();
        Map<String, List<String>> typePropertyPathsMap = new ArrayMap<>(schemas.size());
        for (String schema : schemas) {
            typePropertyPathsMap.put(schema, typePropertyPathsBundle.getStringArrayList(schema));
        }
        return typePropertyPathsMap;
    }

    /** Builder for {@link SearchSpec objects}. */
    public static final class Builder {

        private final Bundle mBundle;
        private final ArrayList<String> mSchemas = new ArrayList<>();
        private final ArrayList<String> mNamespaces = new ArrayList<>();
        private final ArrayList<String> mPackageNames = new ArrayList<>();
        private final Bundle mProjectionTypePropertyMasks = new Bundle();
        private boolean mBuilt = false;

        /** Creates a new {@link SearchSpec.Builder}. */
        public Builder() {
            mBundle = new Bundle();
            mBundle.putInt(NUM_PER_PAGE_FIELD, DEFAULT_NUM_PER_PAGE);
        }

        /** Indicates how the query terms should match {@code TermMatchCode} in the index. */
        @NonNull
        public Builder setTermMatch(@TermMatch int termMatchTypeCode) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkArgumentInRange(
                    termMatchTypeCode, TERM_MATCH_EXACT_ONLY, TERM_MATCH_PREFIX, "Term match type");
            mBundle.putInt(TERM_MATCH_TYPE_FIELD, termMatchTypeCode);
            return this;
        }

        /**
         * Adds a Schema type filter to {@link SearchSpec} Entry. Only search for documents that
         * have the specified schema types.
         *
         * <p>If unset, the query will search over all schema types.
         */
        @NonNull
        public Builder addFilterSchemas(@NonNull String... schemas) {
            Preconditions.checkNotNull(schemas);
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            return addFilterSchemas(Arrays.asList(schemas));
        }

        /**
         * Adds a Schema type filter to {@link SearchSpec} Entry. Only search for documents that
         * have the specified schema types.
         *
         * <p>If unset, the query will search over all schema types.
         */
        @NonNull
        public Builder addFilterSchemas(@NonNull Collection<String> schemas) {
            Preconditions.checkNotNull(schemas);
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mSchemas.addAll(schemas);
            return this;
        }

        /**
         * Adds a namespace filter to {@link SearchSpec} Entry. Only search for documents that have
         * the specified namespaces.
         *
         * <p>If unset, the query will search over all namespaces.
         */
        @NonNull
        public Builder addFilterNamespaces(@NonNull String... namespaces) {
            Preconditions.checkNotNull(namespaces);
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            return addFilterNamespaces(Arrays.asList(namespaces));
        }

        /**
         * Adds a namespace filter to {@link SearchSpec} Entry. Only search for documents that have
         * the specified namespaces.
         *
         * <p>If unset, the query will search over all namespaces.
         */
        @NonNull
        public Builder addFilterNamespaces(@NonNull Collection<String> namespaces) {
            Preconditions.checkNotNull(namespaces);
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mNamespaces.addAll(namespaces);
            return this;
        }

        /**
         * Adds a package name filter to {@link SearchSpec} Entry. Only search for documents that
         * were indexed from the specified packages.
         *
         * <p>If unset, the query will search over all packages that the caller has access to. If
         * package names are specified which caller doesn't have access to, then those package names
         * will be ignored.
         */
        @NonNull
        public Builder addFilterPackageNames(@NonNull String... packageNames) {
            Preconditions.checkNotNull(packageNames);
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            return addFilterPackageNames(Arrays.asList(packageNames));
        }

        /**
         * Adds a package name filter to {@link SearchSpec} Entry. Only search for documents that
         * were indexed from the specified packages.
         *
         * <p>If unset, the query will search over all packages that the caller has access to. If
         * package names are specified which caller doesn't have access to, then those package names
         * will be ignored.
         */
        @NonNull
        public Builder addFilterPackageNames(@NonNull Collection<String> packageNames) {
            Preconditions.checkNotNull(packageNames);
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mPackageNames.addAll(packageNames);
            return this;
        }

        /**
         * Sets the number of results per page in the returned object.
         *
         * <p>The default number of results per page is 10.
         */
        @NonNull
        public SearchSpec.Builder setResultCountPerPage(
                @IntRange(from = 0, to = MAX_NUM_PER_PAGE) int numPerPage) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkArgumentInRange(numPerPage, 0, MAX_NUM_PER_PAGE, "NumPerPage");
            mBundle.putInt(NUM_PER_PAGE_FIELD, numPerPage);
            return this;
        }

        /** Sets ranking strategy for AppSearch results. */
        @NonNull
        public Builder setRankingStrategy(@RankingStrategy int rankingStrategy) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkArgumentInRange(
                    rankingStrategy,
                    RANKING_STRATEGY_NONE,
                    RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP,
                    "Result ranking strategy");
            mBundle.putInt(RANKING_STRATEGY_FIELD, rankingStrategy);
            return this;
        }

        /**
         * Indicates the order of returned search results, the default is {@link #ORDER_DESCENDING},
         * meaning that results with higher scores come first.
         *
         * <p>This order field will be ignored if RankingStrategy = {@code RANKING_STRATEGY_NONE}.
         */
        @NonNull
        public Builder setOrder(@Order int order) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkArgumentInRange(
                    order, ORDER_DESCENDING, ORDER_ASCENDING, "Result ranking order");
            mBundle.putInt(ORDER_FIELD, order);
            return this;
        }

        /**
         * Only the first {@code snippetCount} documents based on the ranking strategy will have
         * snippet information provided.
         *
         * <p>If set to 0 (default), snippeting is disabled and {@link SearchResult#getMatches} will
         * return {@code null} for that result.
         */
        @NonNull
        public SearchSpec.Builder setSnippetCount(
                @IntRange(from = 0, to = MAX_SNIPPET_COUNT) int snippetCount) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkArgumentInRange(snippetCount, 0, MAX_SNIPPET_COUNT, "snippetCount");
            mBundle.putInt(SNIPPET_COUNT_FIELD, snippetCount);
            return this;
        }

        /**
         * Sets {@code snippetCountPerProperty}. Only the first {@code snippetCountPerProperty}
         * snippets for each property of {@link GenericDocument} will contain snippet information.
         *
         * <p>If set to 0, snippeting is disabled and {@link SearchResult#getMatches} will return
         * {@code null} for that result.
         */
        @NonNull
        public SearchSpec.Builder setSnippetCountPerProperty(
                @IntRange(from = 0, to = MAX_SNIPPET_PER_PROPERTY_COUNT)
                        int snippetCountPerProperty) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkArgumentInRange(
                    snippetCountPerProperty,
                    0,
                    MAX_SNIPPET_PER_PROPERTY_COUNT,
                    "snippetCountPerProperty");
            mBundle.putInt(SNIPPET_COUNT_PER_PROPERTY_FIELD, snippetCountPerProperty);
            return this;
        }

        /**
         * Sets {@code maxSnippetSize}, the maximum snippet size. Snippet windows start at {@code
         * maxSnippetSize/2} bytes before the middle of the matching token and end at {@code
         * maxSnippetSize/2} bytes after the middle of the matching token. It respects token
         * boundaries, therefore the returned window may be smaller than requested.
         *
         * <p>Setting {@code maxSnippetSize} to 0 will disable windowing and an empty string will be
         * returned. If matches enabled is also set to false, then snippeting is disabled.
         *
         * <p>Ex. {@code maxSnippetSize} = 16. "foo bar baz bat rat" with a query of "baz" will
         * return a window of "bar baz bat" which is only 11 bytes long.
         */
        @NonNull
        public SearchSpec.Builder setMaxSnippetSize(
                @IntRange(from = 0, to = MAX_SNIPPET_SIZE_LIMIT) int maxSnippetSize) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkArgumentInRange(
                    maxSnippetSize, 0, MAX_SNIPPET_SIZE_LIMIT, "maxSnippetSize");
            mBundle.putInt(MAX_SNIPPET_FIELD, maxSnippetSize);
            return this;
        }

        /**
         * Adds property paths for the specified type to be used for projection. If property paths
         * are added for a type, then only the properties referred to will be retrieved for results
         * of that type. If a property path that is specified isn't present in a result, it will be
         * ignored for that result. Property paths cannot be null.
         *
         * <p>If no property paths are added for a particular type, then all properties of results
         * of that type will be retrieved.
         *
         * <p>If property path is added for the {@link SearchSpec#PROJECTION_SCHEMA_TYPE_WILDCARD},
         * then those property paths will apply to all results, excepting any types that have their
         * own, specific property paths set.
         *
         * <p>Suppose the following document is in the index.
         *
         * <pre>{@code
         * Email: Document {
         *   sender: Document {
         *     name: "Mr. Person"
         *     email: "mrperson123@google.com"
         *   }
         *   recipients: [
         *     Document {
         *       name: "John Doe"
         *       email: "johndoe123@google.com"
         *     }
         *     Document {
         *       name: "Jane Doe"
         *       email: "janedoe123@google.com"
         *     }
         *   ]
         *   subject: "IMPORTANT"
         *   body: "Limited time offer!"
         * }
         * }</pre>
         *
         * <p>Then, suppose that a query for "important" is issued with the following projection
         * type property paths:
         *
         * <pre>{@code
         * {schema: "Email", ["subject", "sender.name", "recipients.name"]}
         * }</pre>
         *
         * <p>The above document will be returned as:
         *
         * <pre>{@code
         * Email: Document {
         *   sender: Document {
         *     name: "Mr. Body"
         *   }
         *   recipients: [
         *     Document {
         *       name: "John Doe"
         *     }
         *     Document {
         *       name: "Jane Doe"
         *     }
         *   ]
         *   subject: "IMPORTANT"
         * }
         * }</pre>
         */
        @NonNull
        public SearchSpec.Builder addProjection(
                @NonNull String schema, @NonNull Collection<String> propertyPaths) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkNotNull(schema);
            Preconditions.checkNotNull(propertyPaths);
            ArrayList<String> propertyPathsArrayList = new ArrayList<>(propertyPaths.size());
            for (String propertyPath : propertyPaths) {
                Preconditions.checkNotNull(propertyPath);
                propertyPathsArrayList.add(propertyPath);
            }
            mProjectionTypePropertyMasks.putStringArrayList(schema, propertyPathsArrayList);
            return this;
        }

        /**
         * Constructs a new {@link SearchSpec} from the contents of this builder.
         *
         * <p>After calling this method, the builder must no longer be used.
         */
        @NonNull
        public SearchSpec build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            if (!mBundle.containsKey(TERM_MATCH_TYPE_FIELD)) {
                throw new IllegalSearchSpecException("Missing termMatchType field.");
            }
            mBundle.putStringArrayList(NAMESPACE_FIELD, mNamespaces);
            mBundle.putStringArrayList(SCHEMA_FIELD, mSchemas);
            mBundle.putStringArrayList(PACKAGE_NAME_FIELD, mPackageNames);
            mBundle.putBundle(PROJECTION_TYPE_PROPERTY_PATHS_FIELD, mProjectionTypePropertyMasks);
            mBuilt = true;
            return new SearchSpec(mBundle);
        }
    }
}
