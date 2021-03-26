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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * This class represents one of the results obtained from an AppSearch query.
 *
 * <p>This allows clients to obtain:
 *
 * <ul>
 *   <li>The document which matched, using {@link #getGenericDocument}
 *   <li>Information about which properties in the document matched, and "snippet" information
 *       containing textual summaries of the document's matches, using {@link #getMatches}
 * </ul>
 *
 * <p>"Snippet" refers to a substring of text from the content of document that is returned as a
 * part of search result.
 *
 * @see SearchResults
 */
public final class SearchResult {
    static final String DOCUMENT_FIELD = "document";
    static final String MATCHES_FIELD = "matches";
    static final String PACKAGE_NAME_FIELD = "packageName";
    static final String DATABASE_NAME_FIELD = "databaseName";
    static final String RANKING_SIGNAL_FIELD = "rankingSignal";

    @NonNull private final Bundle mBundle;

    /** Cache of the inflated document. Comes from inflating mDocumentBundle at first use. */
    @Nullable private GenericDocument mDocument;

    /** Cache of the inflated matches. Comes from inflating mMatchBundles at first use. */
    @Nullable private List<MatchInfo> mMatches;

    /** @hide */
    public SearchResult(@NonNull Bundle bundle) {
        mBundle = Preconditions.checkNotNull(bundle);
    }

    /** @hide */
    @NonNull
    public Bundle getBundle() {
        return mBundle;
    }

    /**
     * Contains the matching {@link GenericDocument}.
     *
     * @return Document object which matched the query.
     */
    @NonNull
    public GenericDocument getGenericDocument() {
        if (mDocument == null) {
            mDocument =
                    new GenericDocument(
                            Preconditions.checkNotNull(mBundle.getBundle(DOCUMENT_FIELD)));
        }
        return mDocument;
    }

    /**
     * Contains a list of Snippets that matched the request.
     *
     * @return List of matches based on {@link SearchSpec}. If snippeting is disabled using {@link
     *     SearchSpec.Builder#setSnippetCount} or {@link
     *     SearchSpec.Builder#setSnippetCountPerProperty}, for all results after that value, this
     *     method returns an empty list.
     */
    @NonNull
    public List<MatchInfo> getMatches() {
        if (mMatches == null) {
            List<Bundle> matchBundles =
                    Preconditions.checkNotNull(mBundle.getParcelableArrayList(MATCHES_FIELD));
            mMatches = new ArrayList<>(matchBundles.size());
            for (int i = 0; i < matchBundles.size(); i++) {
                MatchInfo matchInfo = new MatchInfo(matchBundles.get(i), getGenericDocument());
                mMatches.add(matchInfo);
            }
        }
        return mMatches;
    }

    /**
     * Contains the package name of the app that stored the {@link GenericDocument}.
     *
     * @return Package name that stored the document
     */
    @NonNull
    public String getPackageName() {
        return Preconditions.checkNotNull(mBundle.getString(PACKAGE_NAME_FIELD));
    }

    /**
     * Contains the database name that stored the {@link GenericDocument}.
     *
     * @return Name of the database within which the document is stored
     */
    @NonNull
    public String getDatabaseName() {
        return Preconditions.checkNotNull(mBundle.getString(DATABASE_NAME_FIELD));
    }

    /**
     * Returns the ranking signal of the {@link GenericDocument}, according to the ranking strategy
     * set in {@link SearchSpec.Builder#setRankingStrategy(int)}.
     *
     * <p>The meaning of the ranking signal and its value is determined by the selected ranking
     * strategy:
     *
     * <ul>
     *   <li>{@link SearchSpec#RANKING_STRATEGY_NONE} - this value will be 0
     *   <li>{@link SearchSpec#RANKING_STRATEGY_DOCUMENT_SCORE} - the value returned by calling
     *       {@link GenericDocument#getScore()} on the document returned by {@link
     *       #getGenericDocument()}
     *   <li>{@link SearchSpec#RANKING_STRATEGY_CREATION_TIMESTAMP} - the value returned by calling
     *       {@link GenericDocument#getCreationTimestampMillis()} on the document returned by {@link
     *       #getGenericDocument()}
     *   <li>{@link SearchSpec#RANKING_STRATEGY_RELEVANCE_SCORE} - an arbitrary double value where a
     *       higher value means more relevant
     *   <li>{@link SearchSpec#RANKING_STRATEGY_USAGE_COUNT} - the number of times usage has been
     *       reported for the document returned by {@link #getGenericDocument()}
     *   <li>{@link SearchSpec#RANKING_STRATEGY_USAGE_LAST_USED_TIMESTAMP} - the timestamp of the
     *       most recent usage that has been reported for the document returned by {@link
     *       #getGenericDocument()}
     * </ul>
     *
     * @return Ranking signal of the document
     */
    public double getRankingSignal() {
        return mBundle.getDouble(RANKING_SIGNAL_FIELD);
    }

    /** Builder for {@link SearchResult} objects. */
    public static final class Builder {
        private final Bundle mBundle = new Bundle();
        private final ArrayList<Bundle> mMatchInfos = new ArrayList<>();

        private boolean mBuilt;

        /**
         * Constructs a new builder for {@link SearchResult} objects.
         *
         * @param packageName the package name the matched document belongs to
         * @param databaseName the database name the matched document belongs to.
         */
        public Builder(@NonNull String packageName, @NonNull String databaseName) {
            mBundle.putString(PACKAGE_NAME_FIELD, Preconditions.checkNotNull(packageName));
            mBundle.putString(DATABASE_NAME_FIELD, Preconditions.checkNotNull(databaseName));
        }

        /**
         * Sets the document which matched.
         *
         * @throws IllegalStateException if the builder has already been used
         */
        @NonNull
        public Builder setGenericDocument(@NonNull GenericDocument document) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBundle.putBundle(DOCUMENT_FIELD, document.getBundle());
            return this;
        }

        /** Adds another match to this SearchResult. */
        @NonNull
        public Builder addMatch(@NonNull MatchInfo matchInfo) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            Preconditions.checkState(
                    matchInfo.mDocument == null,
                    "This MatchInfo is already associated with a SearchResult and can't be "
                            + "reassigned");
            mMatchInfos.add(matchInfo.mBundle);
            return this;
        }

        /** Sets the ranking signal of the matched document in this SearchResult. */
        @NonNull
        public Builder setRankingSignal(double rankingSignal) {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBundle.putDouble(RANKING_SIGNAL_FIELD, rankingSignal);
            return this;
        }

        /**
         * Constructs a new {@link SearchResult}.
         *
         * @throws IllegalStateException if the builder has already been used
         */
        @NonNull
        public SearchResult build() {
            Preconditions.checkState(!mBuilt, "Builder has already been used");
            mBundle.putParcelableArrayList(MATCHES_FIELD, mMatchInfos);
            mBuilt = true;
            return new SearchResult(mBundle);
        }
    }

    /**
     * This class represents a match objects for any Snippets that might be present in {@link
     * SearchResults} from query. Using this class user can get the full text, exact matches and
     * Snippets of document content for a given match.
     *
     * <p>Class Example 1: A document contains following text in property subject:
     *
     * <p>A commonly used fake word is foo. Another nonsense word that’s used a lot is bar.
     *
     * <p>If the queryExpression is "foo".
     *
     * <p>{@link MatchInfo#getPropertyPath()} returns "subject"
     *
     * <p>{@link MatchInfo#getFullText()} returns "A commonly used fake word is foo. Another
     * nonsense word that’s used a lot is bar."
     *
     * <p>{@link MatchInfo#getExactMatchRange()} returns [29, 32]
     *
     * <p>{@link MatchInfo#getExactMatch()} returns "foo"
     *
     * <p>{@link MatchInfo#getSnippetRange()} returns [26, 33]
     *
     * <p>{@link MatchInfo#getSnippet()} returns "is foo."
     *
     * <p>
     *
     * <p>Class Example 2: A document contains a property name sender which contains 2 property
     * names name and email, so we will have 2 property paths: {@code sender.name} and {@code
     * sender.email}.
     *
     * <p>Let {@code sender.name = "Test Name Jr."} and {@code sender.email =
     * "TestNameJr@gmail.com"}
     *
     * <p>If the queryExpression is "Test". We will have 2 matches.
     *
     * <p>Match-1
     *
     * <p>{@link MatchInfo#getPropertyPath()} returns "sender.name"
     *
     * <p>{@link MatchInfo#getFullText()} returns "Test Name Jr."
     *
     * <p>{@link MatchInfo#getExactMatchRange()} returns [0, 4]
     *
     * <p>{@link MatchInfo#getExactMatch()} returns "Test"
     *
     * <p>{@link MatchInfo#getSnippetRange()} returns [0, 9]
     *
     * <p>{@link MatchInfo#getSnippet()} returns "Test Name"
     *
     * <p>Match-2
     *
     * <p>{@link MatchInfo#getPropertyPath()} returns "sender.email"
     *
     * <p>{@link MatchInfo#getFullText()} returns "TestNameJr@gmail.com"
     *
     * <p>{@link MatchInfo#getExactMatchRange()} returns [0, 20]
     *
     * <p>{@link MatchInfo#getExactMatch()} returns "TestNameJr@gmail.com"
     *
     * <p>{@link MatchInfo#getSnippetRange()} returns [0, 20]
     *
     * <p>{@link MatchInfo#getSnippet()} returns "TestNameJr@gmail.com"
     */
    public static final class MatchInfo {
        /** The path of the matching snippet property. */
        private static final String PROPERTY_PATH_FIELD = "propertyPath";

        private static final String EXACT_MATCH_RANGE_LOWER_FIELD = "exactMatchRangeLower";
        private static final String EXACT_MATCH_RANGE_UPPER_FIELD = "exactMatchRangeUpper";
        private static final String SNIPPET_RANGE_LOWER_FIELD = "snippetRangeLower";
        private static final String SNIPPET_RANGE_UPPER_FIELD = "snippetRangeUpper";

        private final String mPropertyPath;
        final Bundle mBundle;

        /**
         * Document which the match comes from.
         *
         * <p>If this is {@code null}, methods which require access to the document, like {@link
         * #getExactMatch}, will throw {@link NullPointerException}.
         */
        @Nullable final GenericDocument mDocument;

        /** Full text of the matched property. Populated on first use. */
        @Nullable private String mFullText;

        /** Range of property that exactly matched the query. Populated on first use. */
        @Nullable private MatchRange mExactMatchRange;

        /** Range of some reasonable amount of context around the query. Populated on first use. */
        @Nullable private MatchRange mWindowRange;

        MatchInfo(@NonNull Bundle bundle, @Nullable GenericDocument document) {
            mBundle = Preconditions.checkNotNull(bundle);
            mDocument = document;
            mPropertyPath = Preconditions.checkNotNull(bundle.getString(PROPERTY_PATH_FIELD));
        }

        /**
         * Gets the property path corresponding to the given entry.
         *
         * <p>A property path is a '.' - delimited sequence of property names indicating which
         * property in the document these snippets correspond to.
         *
         * <p>Example properties: 'body', 'sender.name', 'sender.emailaddress', etc. For class
         * example 1 this returns "subject"
         */
        @NonNull
        public String getPropertyPath() {
            return mPropertyPath;
        }

        /**
         * Gets the full text corresponding to the given entry.
         *
         * <p>For class example this returns "A commonly used fake word is foo. Another nonsense
         * word that's used a lot is bar."
         */
        @NonNull
        public String getFullText() {
            if (mFullText == null) {
                Preconditions.checkState(
                        mDocument != null,
                        "Document has not been populated; this MatchInfo cannot be used yet");
                mFullText = getPropertyValues(mDocument, mPropertyPath);
            }
            return mFullText;
        }

        /**
         * Gets the exact {@link MatchRange} corresponding to the given entry.
         *
         * <p>For class example 1 this returns [29, 32]
         */
        @NonNull
        public MatchRange getExactMatchRange() {
            if (mExactMatchRange == null) {
                mExactMatchRange =
                        new MatchRange(
                                mBundle.getInt(EXACT_MATCH_RANGE_LOWER_FIELD),
                                mBundle.getInt(EXACT_MATCH_RANGE_UPPER_FIELD));
            }
            return mExactMatchRange;
        }

        /**
         * Gets the {@link MatchRange} corresponding to the given entry.
         *
         * <p>For class example 1 this returns "foo"
         */
        @NonNull
        public CharSequence getExactMatch() {
            return getSubstring(getExactMatchRange());
        }

        /**
         * Gets the snippet {@link MatchRange} corresponding to the given entry.
         *
         * <p>Only populated when set maxSnippetSize > 0 in {@link
         * SearchSpec.Builder#setMaxSnippetSize}.
         *
         * <p>For class example 1 this returns [29, 41].
         */
        @NonNull
        public MatchRange getSnippetRange() {
            if (mWindowRange == null) {
                mWindowRange =
                        new MatchRange(
                                mBundle.getInt(SNIPPET_RANGE_LOWER_FIELD),
                                mBundle.getInt(SNIPPET_RANGE_UPPER_FIELD));
            }
            return mWindowRange;
        }

        /**
         * Gets the snippet corresponding to the given entry.
         *
         * <p>Snippet - Provides a subset of the content to display. Only populated when requested
         * maxSnippetSize > 0. The size of this content can be changed by {@link
         * SearchSpec.Builder#setMaxSnippetSize}. Windowing is centered around the middle of the
         * matched token with content on either side clipped to token boundaries.
         *
         * <p>For class example 1 this returns "foo. Another"
         */
        @NonNull
        public CharSequence getSnippet() {
            return getSubstring(getSnippetRange());
        }

        private CharSequence getSubstring(MatchRange range) {
            return getFullText().substring(range.getStart(), range.getEnd());
        }

        /** Extracts the matching string from the document. */
        private static String getPropertyValues(GenericDocument document, String propertyName) {
            // In IcingLib snippeting is available for only 3 data types i.e String, double and
            // long, so we need to check which of these three are requested.
            // TODO (tytytyww): getPropertyStringArray takes property name, handle for property
            //  path.
            // TODO (tytytyww): support double[] and long[].
            String[] values = document.getPropertyStringArray(propertyName);
            if (values == null) {
                throw new IllegalStateException("No content found for requested property path!");
            }

            // TODO(b/175146044): Return the proper match based on the index in the propertyName.
            return values[0];
        }

        /** Builder for {@link MatchInfo} objects. */
        public static final class Builder {
            private final Bundle mBundle = new Bundle();
            private boolean mBuilt = false;

            /**
             * Sets the property path corresponding to the given entry.
             *
             * <p>A property path is a '.' - delimited sequence of property names indicating which
             * property in the document these snippets correspond to.
             *
             * <p>Example properties: 'body', 'sender.name', 'sender.emailaddress', etc. For class
             * example 1 this returns "subject"
             *
             * @throws IllegalStateException if the builder has already been used
             */
            @NonNull
            public Builder setPropertyPath(@NonNull String propertyPath) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                mBundle.putString(
                        SearchResult.MatchInfo.PROPERTY_PATH_FIELD,
                        Preconditions.checkNotNull(propertyPath));
                return this;
            }

            /**
             * Sets the exact {@link MatchRange} corresponding to the given entry.
             *
             * @throws IllegalStateException if the builder has already been used
             */
            @NonNull
            public Builder setExactMatchRange(@NonNull MatchRange matchRange) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Preconditions.checkNotNull(matchRange);
                mBundle.putInt(MatchInfo.EXACT_MATCH_RANGE_LOWER_FIELD, matchRange.getStart());
                mBundle.putInt(MatchInfo.EXACT_MATCH_RANGE_UPPER_FIELD, matchRange.getEnd());
                return this;
            }

            /**
             * Sets the snippet {@link MatchRange} corresponding to the given entry.
             *
             * @throws IllegalStateException if the builder has already been used
             */
            @NonNull
            public Builder setSnippetRange(@NonNull MatchRange matchRange) {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                Preconditions.checkNotNull(matchRange);
                mBundle.putInt(MatchInfo.SNIPPET_RANGE_LOWER_FIELD, matchRange.getStart());
                mBundle.putInt(MatchInfo.SNIPPET_RANGE_UPPER_FIELD, matchRange.getEnd());
                return this;
            }

            /**
             * Constructs a new {@link MatchInfo}.
             *
             * @throws IllegalStateException if the builder has already been used
             */
            @NonNull
            public MatchInfo build() {
                Preconditions.checkState(!mBuilt, "Builder has already been used");
                mBuilt = true;
                return new MatchInfo(mBundle, /*document=*/ null);
            }
        }
    }

    /**
     * Class providing the position range of matching information.
     *
     * <p>All ranges are finite, and the left side of the range is always {@code <=} the right side
     * of the range.
     *
     * <p>Example: MatchRange(0, 100) represent a hundred ints from 0 to 99."
     */
    public static final class MatchRange {
        private final int mEnd;
        private final int mStart;

        /**
         * Creates a new immutable range.
         *
         * <p>The endpoints are {@code [start, end)}; that is the range is bounded. {@code start}
         * must be lesser or equal to {@code end}.
         *
         * @param start The start point (inclusive)
         * @param end The end point (exclusive)
         */
        public MatchRange(int start, int end) {
            if (start > end) {
                throw new IllegalArgumentException(
                        "Start point must be less than or equal to " + "end point");
            }
            mStart = start;
            mEnd = end;
        }

        /** Gets the start point (inclusive). */
        public int getStart() {
            return mStart;
        }

        /** Gets the end point (exclusive). */
        public int getEnd() {
            return mEnd;
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MatchRange)) {
                return false;
            }
            MatchRange otherMatchRange = (MatchRange) other;
            return this.getStart() == otherMatchRange.getStart()
                    && this.getEnd() == otherMatchRange.getEnd();
        }

        @Override
        @NonNull
        public String toString() {
            return "MatchRange { start: " + mStart + " , end: " + mEnd + "}";
        }

        @Override
        public int hashCode() {
            return Objects.hash(mStart, mEnd);
        }
    }
}
