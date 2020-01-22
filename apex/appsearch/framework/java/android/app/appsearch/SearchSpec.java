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

import android.annotation.IntDef;
import android.annotation.NonNull;

import com.google.android.icing.proto.ResultSpecProto;
import com.google.android.icing.proto.ScoringSpecProto;
import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.TermMatchType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


/**
 * This class represents the specification logic for AppSearch. It can be used to set the type of
 * search, like prefix or exact only or apply filters to search for a specific schema type only etc.
 * @hide
 */
// TODO(sidchhabra) : AddResultSpec fields for Snippets etc.
public final class SearchSpec {

    private final SearchSpecProto mSearchSpecProto;
    private final ResultSpecProto mResultSpecProto;
    private final ScoringSpecProto mScoringSpecProto;

    private SearchSpec(@NonNull SearchSpecProto searchSpecProto,
            @NonNull ResultSpecProto resultSpecProto, @NonNull ScoringSpecProto scoringSpecProto) {
        mSearchSpecProto = searchSpecProto;
        mResultSpecProto = resultSpecProto;
        mScoringSpecProto = scoringSpecProto;
    }

    /** Creates a new {@link SearchSpec.Builder}. */
    @NonNull
    public static SearchSpec.Builder newBuilder() {
        return new SearchSpec.Builder();
    }

    /** @hide */
    @NonNull
    SearchSpecProto getSearchSpecProto() {
        return mSearchSpecProto;
    }

    /** @hide */
    @NonNull
    ResultSpecProto getResultSpecProto() {
        return mResultSpecProto;
    }

    /** @hide */
    @NonNull
    ScoringSpecProto getScoringSpecProto() {
        return mScoringSpecProto;
    }

    /** Term Match Type for the query. */
    // NOTE: The integer values of these constants must match the proto enum constants in
    // {@link com.google.android.icing.proto.SearchSpecProto.termMatchType}
    @IntDef(prefix = {"TERM_MATCH_TYPE_"}, value = {
            TERM_MATCH_TYPE_EXACT_ONLY,
            TERM_MATCH_TYPE_PREFIX
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TermMatchTypeCode {}

    /**
     * Query terms will only match exact tokens in the index.
     * <p>Ex. A query term "foo" will only match indexed token "foo", and not "foot" or "football".
     */
    public static final int TERM_MATCH_TYPE_EXACT_ONLY = 1;
    /**
     * Query terms will match indexed tokens when the query term is a prefix of the token.
     * <p>Ex. A query term "foo" will match indexed tokens like "foo", "foot", and "football".
     */
    public static final int TERM_MATCH_TYPE_PREFIX = 2;

    /** Ranking Strategy for query result.*/
    // NOTE: The integer values of these constants must match the proto enum constants in
    // {@link ScoringSpecProto.RankingStrategy.Code }
    @IntDef(prefix = {"RANKING_STRATEGY_"}, value = {
            RANKING_STRATEGY_NONE,
            RANKING_STRATEGY_DOCUMENT_SCORE,
            RANKING_STRATEGY_CREATION_TIMESTAMP
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RankingStrategyCode {}

    /** No Ranking, results are returned in arbitrary order.*/
    public static final int RANKING_STRATEGY_NONE = 0;
    /** Ranked by app-provided document scores. */
    public static final int RANKING_STRATEGY_DOCUMENT_SCORE = 1;
    /** Ranked by document creation timestamps. */
    public static final int RANKING_STRATEGY_CREATION_TIMESTAMP = 2;

    /** Order for query result.*/
    // NOTE: The integer values of these constants must match the proto enum constants in
    // {@link ScoringSpecProto.Order.Code }
    @IntDef(prefix = {"ORDER_"}, value = {
            ORDER_DESCENDING,
            ORDER_ASCENDING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OrderCode {}

    /** Search results will be returned in a descending order. */
    public static final int ORDER_DESCENDING = 0;
    /** Search results will be returned in an ascending order. */
    public static final int ORDER_ASCENDING = 1;

    /** Builder for {@link SearchSpec objects}. */
    public static final class Builder {

        private final SearchSpecProto.Builder mSearchSpecBuilder = SearchSpecProto.newBuilder();
        private final ResultSpecProto.Builder mResultSpecBuilder = ResultSpecProto.newBuilder();
        private final ScoringSpecProto.Builder mScoringSpecBuilder = ScoringSpecProto.newBuilder();
        private final ResultSpecProto.SnippetSpecProto.Builder mSnippetSpecBuilder =
                ResultSpecProto.SnippetSpecProto.newBuilder();

        private Builder() {
        }

        /**
         * Indicates how the query terms should match {@link TermMatchTypeCode} in the index.
         */
        @NonNull
        public Builder setTermMatchType(@TermMatchTypeCode int termMatchTypeCode) {
            TermMatchType.Code termMatchTypeCodeProto =
                    TermMatchType.Code.forNumber(termMatchTypeCode);
            if (termMatchTypeCodeProto == null) {
                throw new IllegalArgumentException("Invalid term match type: "
                        + termMatchTypeCode);
            }
            mSearchSpecBuilder.setTermMatchType(termMatchTypeCodeProto);
            return this;
        }

        /**
         * Adds a Schema type filter to {@link SearchSpec} Entry. Only search for documents that
         * have the specified schema types.
         * <p>If unset, the query will search over all schema types.
         */
        @NonNull
        public Builder setSchemaTypes(@NonNull String... schemaTypes) {
            for (String schemaType : schemaTypes) {
                mSearchSpecBuilder.addSchemaTypeFilters(schemaType);
            }
            return this;
        }

        /** Sets the maximum number of results to retrieve from the query */
        @NonNull
        public SearchSpec.Builder setNumToRetrieve(int numToRetrieve) {
            mResultSpecBuilder.setNumToRetrieve(numToRetrieve);
            return this;
        }

        /** Sets ranking strategy for AppSearch results.*/
        @NonNull
        public Builder setRankingStrategy(@RankingStrategyCode int rankingStrategy) {
            ScoringSpecProto.RankingStrategy.Code rankingStrategyCodeProto =
                    ScoringSpecProto.RankingStrategy.Code.forNumber(rankingStrategy);
            if (rankingStrategyCodeProto == null) {
                throw new IllegalArgumentException("Invalid result ranking strategy: "
                        + rankingStrategyCodeProto);
            }
            mScoringSpecBuilder.setRankBy(rankingStrategyCodeProto);
            return this;
        }

        /**
         * Indicates the order of returned search results, the default is DESC, meaning that results
         * with higher scores come first.
         * <p>This order field will be ignored if RankingStrategy = {@code RANKING_STRATEGY_NONE}.
         */
        @NonNull
        public Builder setOrder(@OrderCode int order) {
            ScoringSpecProto.Order.Code orderCodeProto =
                    ScoringSpecProto.Order.Code.forNumber(order);
            if (orderCodeProto == null) {
                throw new IllegalArgumentException("Invalid result ranking order: "
                        + orderCodeProto);
            }
            mScoringSpecBuilder.setOrderBy(orderCodeProto);
            return this;
        }

        /**
         * Only the first {@code numToSnippet} documents based on the ranking strategy
         * will have snippet information provided.
         * <p>If set to 0 (default), snippeting is disabled and
         * {@link SearchResults.Result#getMatchInfo} will return {@code null} for that result.
         */
        @NonNull
        public SearchSpec.Builder setNumToSnippet(int numToSnippet) {
            mSnippetSpecBuilder.setNumToSnippet(numToSnippet);
            return this;
        }

        /**
         * Only the first {@code numMatchesPerProperty} matches for a every property of
         * {@link AppSearchDocument} will contain snippet information.
         * <p>If set to 0, snippeting is disabled and {@link SearchResults.Result#getMatchInfo}
         * will return {@code null} for that result.
         */
        @NonNull
        public SearchSpec.Builder setNumMatchesPerProperty(int numMatchesPerProperty) {
            mSnippetSpecBuilder.setNumMatchesPerProperty(numMatchesPerProperty);
            return this;
        }

        /**
         * Sets {@code maxSnippetSize}, the maximum snippet size. Snippet windows start at
         * {@code maxSnippetSize/2} bytes before the middle of the matching token and end at
         * {@code maxSnippetSize/2} bytes after the middle of the matching token. It respects
         * token boundaries, therefore the returned window may be smaller than requested.
         * <p> Setting {@code maxSnippetSize} to 0 will disable windowing and an empty string will
         * be returned. If matches enabled is also set to false, then snippeting is disabled.
         * <p>Ex. {@code maxSnippetSize} = 16. "foo bar baz bat rat" with a query of "baz" will
         * return a window of "bar baz bat" which is only 11 bytes long.
         */
        @NonNull
        public SearchSpec.Builder setMaxSnippetSize(int maxSnippetSize) {
            mSnippetSpecBuilder.setMaxWindowBytes(maxSnippetSize);
            return this;
        }

        /**
         * Constructs a new {@link SearchSpec} from the contents of this builder.
         *
         * <p>After calling this method, the builder must no longer be used.
         */
        @NonNull
        public SearchSpec build() {
            if (mSearchSpecBuilder.getTermMatchType() == TermMatchType.Code.UNKNOWN) {
                throw new IllegalSearchSpecException("Missing termMatchType field.");
            }
            mResultSpecBuilder.setSnippetSpec(mSnippetSpecBuilder);
            return new SearchSpec(mSearchSpecBuilder.build(), mResultSpecBuilder.build(),
                    mScoringSpecBuilder.build());
        }
    }
}
