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

import com.google.android.icing.proto.SearchSpecProto;
import com.google.android.icing.proto.TermMatchType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class represents the specification logic for AppSearch. It can be used to set the type of
 * search, like prefix or exact only or apply filters to search for a specific schema type only etc.
 * @hide
 *
 */
// TODO(sidchhabra) : AddResultSpec fields for Snippets etc.
public final class SearchSpec {

    private final SearchSpecProto mSearchSpecProto;

    private SearchSpec(SearchSpecProto searchSpecProto) {
        mSearchSpecProto = searchSpecProto;
    }

    /** Creates a new {@link SearchSpec.Builder}. */
    @NonNull
    public static SearchSpec.Builder newBuilder() {
        return new SearchSpec.Builder();
    }

    /** @hide */
    @NonNull
    SearchSpecProto getProto() {
        return mSearchSpecProto;
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

    public static final int TERM_MATCH_TYPE_EXACT_ONLY = 1;
    public static final int TERM_MATCH_TYPE_PREFIX = 2;

    /** Builder for {@link SearchSpec objects}. */
    public static final class Builder {

        private final SearchSpecProto.Builder mBuilder = SearchSpecProto.newBuilder();

        private Builder(){}

        /**
         * Indicates how the query terms should match {@link TermMatchTypeCode} in the index.
         *
         *   TermMatchType.Code=EXACT_ONLY
         *   Query terms will only match exact tokens in the index.
         *   Ex. A query term "foo" will only match indexed token "foo", and not "foot"
         *   or "football"
         *
         *   TermMatchType.Code=PREFIX
         *   Query terms will match indexed tokens when the query term is a prefix of
         *   the token.
         *   Ex. A query term "foo" will match indexed tokens like "foo", "foot", and
         *   "football".
         */
        @NonNull
        public Builder setTermMatchType(@TermMatchTypeCode int termMatchTypeCode) {
            TermMatchType.Code termMatchTypeCodeProto =
                    TermMatchType.Code.forNumber(termMatchTypeCode);
            if (termMatchTypeCodeProto == null) {
                throw new IllegalArgumentException("Invalid term match type: " + termMatchTypeCode);
            }
            mBuilder.setTermMatchType(termMatchTypeCodeProto);
            return this;
        }

        /**
         * Adds a Schema type filter to {@link SearchSpec} Entry.
         * Only search for documents that have the specified schema types.
         * If unset, the query will search over all schema types.
         */
        @NonNull
        public Builder setSchemaTypes(@NonNull String... schemaTypes) {
            for (String schemaType : schemaTypes) {
                mBuilder.addSchemaTypeFilters(schemaType);
            }
            return this;
        }

        /**
         * Constructs a new {@link SearchSpec} from the contents of this builder.
         *
         * <p>After calling this method, the builder must no longer be used.
         */
        @NonNull
        public SearchSpec build() {
            if (mBuilder.getTermMatchType() == TermMatchType.Code.UNKNOWN) {
                throw new IllegalSearchSpecException("Missing termMatchType field.");
            }
            return new SearchSpec(mBuilder.build());
        }
    }

}
