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

package com.android.server.appsearch.external.localstorage.converter;

import android.annotation.NonNull;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResultPage;
import android.os.Bundle;

import com.android.internal.util.Preconditions;

import com.google.android.icing.proto.SearchResultProto;
import com.google.android.icing.proto.SearchResultProtoOrBuilder;
import com.google.android.icing.proto.SnippetMatchProto;
import com.google.android.icing.proto.SnippetProto;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates a {@link SearchResultProto} into {@link SearchResult}s.
 *
 * @hide
 */
public class SearchResultToProtoConverter {
    private SearchResultToProtoConverter() {}

    /**
     * Translate a {@link SearchResultProto} into {@link SearchResultPage}.
     *
     * @param proto The {@link SearchResultProto} containing results.
     * @param packageNames A parallel array of package names. The package name at index 'i' of this
     *     list should be the package that indexed the document at index 'i' of proto.getResults(i).
     * @param databaseNames A parallel array of database names. The database name at index 'i' of
     *     this list shold be the database that indexed the document at index 'i' of
     *     proto.getResults(i).
     * @return {@link SearchResultPage} of results.
     */
    @NonNull
    public static SearchResultPage toSearchResultPage(
            @NonNull SearchResultProtoOrBuilder proto,
            @NonNull List<String> packageNames,
            @NonNull List<String> databaseNames) {
        Preconditions.checkArgument(
                proto.getResultsCount() == packageNames.size(),
                "Size of results does not match the number of package names.");
        Bundle bundle = new Bundle();
        bundle.putLong(SearchResultPage.NEXT_PAGE_TOKEN_FIELD, proto.getNextPageToken());
        ArrayList<Bundle> resultBundles = new ArrayList<>(proto.getResultsCount());
        for (int i = 0; i < proto.getResultsCount(); i++) {
            SearchResult result =
                    toSearchResult(proto.getResults(i), packageNames.get(i), databaseNames.get(i));
            resultBundles.add(result.getBundle());
        }
        bundle.putParcelableArrayList(SearchResultPage.RESULTS_FIELD, resultBundles);
        return new SearchResultPage(bundle);
    }

    /**
     * Translate a {@link SearchResultProto.ResultProto} into {@link SearchResult}.
     *
     * @param proto The proto to be converted.
     * @param packageName The package name associated with the document in {@code proto}.
     * @param databaseName The database name associated with the document in {@code proto}.
     * @return A {@link SearchResult} bundle.
     */
    @NonNull
    private static SearchResult toSearchResult(
            @NonNull SearchResultProto.ResultProtoOrBuilder proto,
            @NonNull String packageName,
            @NonNull String databaseName) {
        GenericDocument document =
                GenericDocumentToProtoConverter.toGenericDocument(proto.getDocument());
        SearchResult.Builder builder =
                new SearchResult.Builder(packageName, databaseName)
                        .setGenericDocument(document)
                        .setRankingSignal(proto.getScore());
        if (proto.hasSnippet()) {
            for (int i = 0; i < proto.getSnippet().getEntriesCount(); i++) {
                SnippetProto.EntryProto entry = proto.getSnippet().getEntries(i);
                for (int j = 0; j < entry.getSnippetMatchesCount(); j++) {
                    SearchResult.MatchInfo matchInfo =
                            toMatchInfo(entry.getSnippetMatches(j), entry.getPropertyName());
                    builder.addMatch(matchInfo);
                }
            }
        }
        return builder.build();
    }

    private static SearchResult.MatchInfo toMatchInfo(
            @NonNull SnippetMatchProto snippetMatchProto, @NonNull String propertyPath) {
        return new SearchResult.MatchInfo.Builder()
                .setPropertyPath(propertyPath)
                .setExactMatchRange(
                        new SearchResult.MatchRange(
                                snippetMatchProto.getExactMatchPosition(),
                                snippetMatchProto.getExactMatchPosition()
                                        + snippetMatchProto.getExactMatchBytes()))
                .setSnippetRange(
                        new SearchResult.MatchRange(
                                snippetMatchProto.getWindowPosition(),
                                snippetMatchProto.getWindowPosition()
                                        + snippetMatchProto.getWindowBytes()))
                .build();
    }
}
