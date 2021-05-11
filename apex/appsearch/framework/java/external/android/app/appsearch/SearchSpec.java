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
import android.annotation.SuppressLint;
import android.app.appsearch.util.BundleUtil;
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
import java.util.Objects;
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
    static final String RESULT_GROUPING_TYPE_FLAGS = "resultGroupingTypeFlags";
    static final String RESULT_GROUPING_LIMIT = "resultGroupingLimit";

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
                RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP,
                RANKING_STRATEGY_SYSTEM_USAGE_COUNT,
                RANKING_STRATEGY_SYSTEM_USAGE_LAST_USED_TIMESTAMP,
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
    /** Ranked by number of usages, as reported by the app. */
    public static final int RANKING_STRATEGY_USAGE_COUNT = 4;
    /** Ranked by timestamp of last usage, as reported by the app. */
    public static final int RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP = 5;
    /** Ranked by number of usages from a system UI surface. */
    public static final int RANKING_STRATEGY_SYSTEM_USAGE_COUNT = 6;
    /** Ranked by timestamp of last usage from a system UI surface. */
    public static final int RANKING_STRATEGY_SYSTEM_USAGE_LAST_USED_TIMESTAMP = 7;

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

    /**
     * Grouping type for result limits.
     *
     * @hide
     */
    @IntDef(
            flag = true,
            value = {GROUPING_TYPE_PER_PACKAGE, GROUPING_TYPE_PER_NAMESPACE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface GroupingType {}

    /**
     * Results should be grouped together by package for the purpose of enforcing a limit on the
     * number of results returned per package.
     */
    public static final int GROUPING_TYPE_PER_PACKAGE = 0b01;
    /**
     * Results should be grouped together by namespace for the purpose of enforcing a limit on the
     * number of results returned per namespace.
     */
    public static final int GROUPING_TYPE_PER_NAMESPACE = 0b10;

    private final Bundle mBundle;

    /** @hide */
    public SearchSpec(@NonNull Bundle bundle) {
        Objects.requireNonNull(bundle);
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

    /**
     * Get the type of grouping limit to apply, or 0 if {@link Builder#setResultGrouping} was not
     * called.
     */
    public @GroupingType int getResultGroupingTypeFlags() {
        return mBundle.getInt(RESULT_GROUPING_TYPE_FLAGS);
    }

    /**
     * Get the maximum number of results to return for each group.
     *
     * @return the maximum number of results to return for each group or Integer.MAX_VALUE if {@link
     *     Builder#setResultGrouping(int, int)} was not called.
     */
    public int getResultGroupingLimit() {
        return mBundle.getInt(RESULT_GROUPING_LIMIT, Integer.MAX_VALUE);
    }

    /** Builder for {@link SearchSpec objects}. */
    public static final class Builder {
        private ArrayList<String> mSchemas = new ArrayList<>();
        private ArrayList<String> mNamespaces = new ArrayList<>();
        private ArrayList<String> mPackageNames = new ArrayList<>();
        private Bundle mProjectionTypePropertyMasks = new Bundle();

        private int mResultCountPerPage = DEFAULT_NUM_PER_PAGE;
        private @TermMatch int mTermMatchType = TERM_MATCH_PREFIX;
        private int mSnippetCount = 0;
        private int mSnippetCountPerProperty = MAX_SNIPPET_PER_PROPERTY_COUNT;
        private int mMaxSnippetSize = 0;
        private @RankingStrategy int mRankingStrategy = RANKING_STRATEGY_NONE;
        private @Order int mOrder = ORDER_DESCENDING;
        private @GroupingType int mGroupingTypeFlags = 0;
        private int mGroupingLimit = 0;
        private boolean mBuilt = false;

        /**
         * Indicates how the query terms should match {@code TermMatchCode} in the index.
         *
         * <p>If this method is not called, the default term match type is {@link
         * SearchSpec#TERM_MATCH_PREFIX}.
         */
        @NonNull
        public Builder setTermMatch(@TermMatch int termMatchType) {
            Preconditions.checkArgumentInRange(
                    termMatchType, TERM_MATCH_EXACT_ONLY, TERM_MATCH_PREFIX, "Term match type");
            resetIfBuilt();
            mTermMatchType = termMatchType;
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
            Objects.requireNonNull(schemas);
            resetIfBuilt();
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
            Objects.requireNonNull(schemas);
            resetIfBuilt();
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
            Objects.requireNonNull(namespaces);
            resetIfBuilt();
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
            Objects.requireNonNull(namespaces);
            resetIfBuilt();
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
            Objects.requireNonNull(packageNames);
            resetIfBuilt();
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
            Objects.requireNonNull(packageNames);
            resetIfBuilt();
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
                @IntRange(from = 0, to = MAX_NUM_PER_PAGE) int resultCountPerPage) {
            Preconditions.checkArgumentInRange(
                    resultCountPerPage, 0, MAX_NUM_PER_PAGE, "resultCountPerPage");
            resetIfBuilt();
            mResultCountPerPage = resultCountPerPage;
            return this;
        }

        /** Sets ranking strategy for AppSearch results. */
        @NonNull
        public Builder setRankingStrategy(@RankingStrategy int rankingStrategy) {
            Preconditions.checkArgumentInRange(
                    rankingStrategy,
                    RANKING_STRATEGY_NONE,
                    RANKING_STRATEGY_SYSTEM_USAGE_LAST_USED_TIMESTAMP,
                    "Result ranking strategy");
            resetIfBuilt();
            mRankingStrategy = rankingStrategy;
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
            Preconditions.checkArgumentInRange(
                    order, ORDER_DESCENDING, ORDER_ASCENDING, "Result ranking order");
            resetIfBuilt();
            mOrder = order;
            return this;
        }

        /**
         * Only the first {@code snippetCount} documents based on the ranking strategy will have
         * snippet information provided.
         *
         * <p>The list returned from {@link SearchResult#getMatchInfos} will contain at most this
         * many entries.
         *
         * <p>If set to 0 (default), snippeting is disabled and the list returned from {@link
         * SearchResult#getMatchInfos} will be empty.
         */
        @NonNull
        public SearchSpec.Builder setSnippetCount(
                @IntRange(from = 0, to = MAX_SNIPPET_COUNT) int snippetCount) {
            Preconditions.checkArgumentInRange(snippetCount, 0, MAX_SNIPPET_COUNT, "snippetCount");
            resetIfBuilt();
            mSnippetCount = snippetCount;
            return this;
        }

        /**
         * Sets {@code snippetCountPerProperty}. Only the first {@code snippetCountPerProperty}
         * snippets for each property of each {@link GenericDocument} will contain snippet
         * information.
         *
         * <p>If set to 0, snippeting is disabled and the list returned from {@link
         * SearchResult#getMatchInfos} will be empty.
         *
         * <p>The default behavior is to snippet all matches a property contains, up to the maximum
         * value of 10,000.
         */
        @NonNull
        public SearchSpec.Builder setSnippetCountPerProperty(
                @IntRange(from = 0, to = MAX_SNIPPET_PER_PROPERTY_COUNT)
                        int snippetCountPerProperty) {
            Preconditions.checkArgumentInRange(
                    snippetCountPerProperty,
                    0,
                    MAX_SNIPPET_PER_PROPERTY_COUNT,
                    "snippetCountPerProperty");
            resetIfBuilt();
            mSnippetCountPerProperty = snippetCountPerProperty;
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
            Preconditions.checkArgumentInRange(
                    maxSnippetSize, 0, MAX_SNIPPET_SIZE_LIMIT, "maxSnippetSize");
            resetIfBuilt();
            mMaxSnippetSize = maxSnippetSize;
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
            Objects.requireNonNull(schema);
            Objects.requireNonNull(propertyPaths);
            resetIfBuilt();
            ArrayList<String> propertyPathsArrayList = new ArrayList<>(propertyPaths.size());
            for (String propertyPath : propertyPaths) {
                Objects.requireNonNull(propertyPath);
                propertyPathsArrayList.add(propertyPath);
            }
            mProjectionTypePropertyMasks.putStringArrayList(schema, propertyPathsArrayList);
            return this;
        }

        /**
         * Set the maximum number of results to return for each group, where groups are defined by
         * grouping type.
         *
         * <p>Calling this method will override any previous calls. So calling
         * setResultGrouping(GROUPING_TYPE_PER_PACKAGE, 7) and then calling
         * setResultGrouping(GROUPING_TYPE_PER_PACKAGE, 2) will result in only the latter, a limit
         * of two results per package, being applied. Or calling setResultGrouping
         * (GROUPING_TYPE_PER_PACKAGE, 1) and then calling setResultGrouping
         * (GROUPING_TYPE_PER_PACKAGE | GROUPING_PER_NAMESPACE, 5) will result in five results per
         * package per namespace.
         *
         * @param groupingTypeFlags One or more combination of grouping types.
         * @param limit Number of results to return per {@code groupingTypeFlags}.
         * @throws IllegalArgumentException if groupingTypeFlags is zero.
         */
        // Individual parameters available from getResultGroupingTypeFlags and
        // getResultGroupingLimit
        @SuppressLint("MissingGetterMatchingBuilder")
        @NonNull
        public Builder setResultGrouping(@GroupingType int groupingTypeFlags, int limit) {
            Preconditions.checkState(
                    groupingTypeFlags != 0, "Result grouping type cannot be zero.");
            resetIfBuilt();
            mGroupingTypeFlags = groupingTypeFlags;
            mGroupingLimit = limit;
            return this;
        }

        /** Constructs a new {@link SearchSpec} from the contents of this builder. */
        @NonNull
        public SearchSpec build() {
            Bundle bundle = new Bundle();
            bundle.putStringArrayList(SCHEMA_FIELD, mSchemas);
            bundle.putStringArrayList(NAMESPACE_FIELD, mNamespaces);
            bundle.putStringArrayList(PACKAGE_NAME_FIELD, mPackageNames);
            bundle.putBundle(PROJECTION_TYPE_PROPERTY_PATHS_FIELD, mProjectionTypePropertyMasks);
            bundle.putInt(NUM_PER_PAGE_FIELD, mResultCountPerPage);
            bundle.putInt(TERM_MATCH_TYPE_FIELD, mTermMatchType);
            bundle.putInt(SNIPPET_COUNT_FIELD, mSnippetCount);
            bundle.putInt(SNIPPET_COUNT_PER_PROPERTY_FIELD, mSnippetCountPerProperty);
            bundle.putInt(MAX_SNIPPET_FIELD, mMaxSnippetSize);
            bundle.putInt(RANKING_STRATEGY_FIELD, mRankingStrategy);
            bundle.putInt(ORDER_FIELD, mOrder);
            bundle.putInt(RESULT_GROUPING_TYPE_FLAGS, mGroupingTypeFlags);
            bundle.putInt(RESULT_GROUPING_LIMIT, mGroupingLimit);
            mBuilt = true;
            return new SearchSpec(bundle);
        }

        private void resetIfBuilt() {
            if (mBuilt) {
                mSchemas = new ArrayList<>(mSchemas);
                mNamespaces = new ArrayList<>(mNamespaces);
                mPackageNames = new ArrayList<>(mPackageNames);
                mProjectionTypePropertyMasks = BundleUtil.deepCopy(mProjectionTypePropertyMasks);
                mBuilt = false;
            }
        }
    }
}
