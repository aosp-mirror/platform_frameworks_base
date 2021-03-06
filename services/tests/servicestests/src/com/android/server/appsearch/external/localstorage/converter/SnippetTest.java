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

import static com.google.common.truth.Truth.assertThat;

import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResultPage;

import com.android.server.appsearch.proto.DocumentProto;
import com.android.server.appsearch.proto.PropertyProto;
import com.android.server.appsearch.proto.SearchResultProto;
import com.android.server.appsearch.proto.SnippetMatchProto;
import com.android.server.appsearch.proto.SnippetProto;

import org.junit.Test;

import java.util.Collections;

public class SnippetTest {

    // TODO(tytytyww): Add tests for Double and Long Snippets.
    @Test
    public void testSingleStringSnippet() {

        final String propertyKeyString = "content";
        final String propertyValueString =
                "A commonly used fake word is foo.\n"
                        + "   Another nonsense word that’s used a lot\n"
                        + "   is bar.\n";
        final String uri = "uri1";
        final String schemaType = "schema1";
        final String searchWord = "foo";
        final String exactMatch = "foo";
        final String window = "is foo";

        // Building the SearchResult received from query.
        PropertyProto property =
                PropertyProto.newBuilder()
                        .setName(propertyKeyString)
                        .addStringValues(propertyValueString)
                        .build();
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri(uri)
                        .setSchema(schemaType)
                        .addProperties(property)
                        .build();
        SnippetProto snippetProto =
                SnippetProto.newBuilder()
                        .addEntries(
                                SnippetProto.EntryProto.newBuilder()
                                        .setPropertyName(propertyKeyString)
                                        .addSnippetMatches(
                                                SnippetMatchProto.newBuilder()
                                                        .setExactMatchPosition(29)
                                                        .setExactMatchBytes(3)
                                                        .setWindowPosition(26)
                                                        .setWindowBytes(6)
                                                        .build())
                                        .build())
                        .build();
        SearchResultProto.ResultProto resultProto =
                SearchResultProto.ResultProto.newBuilder()
                        .setDocument(documentProto)
                        .setSnippet(snippetProto)
                        .build();
        SearchResultProto searchResultProto =
                SearchResultProto.newBuilder().addResults(resultProto).build();

        // Making ResultReader and getting Snippet values.
        SearchResultPage searchResultPage =
                SearchResultToProtoConverter.toSearchResultPage(
                        searchResultProto,
                        Collections.singletonList("packageName"),
                        Collections.singletonList("databaseName"));
        for (SearchResult result : searchResultPage.getResults()) {
            SearchResult.MatchInfo match = result.getMatches().get(0);
            assertThat(match.getPropertyPath()).isEqualTo(propertyKeyString);
            assertThat(match.getFullText()).isEqualTo(propertyValueString);
            assertThat(match.getExactMatch()).isEqualTo(exactMatch);
            assertThat(match.getExactMatchPosition())
                    .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 29, /*upper=*/ 32));
            assertThat(match.getFullText()).isEqualTo(propertyValueString);
            assertThat(match.getSnippetPosition())
                    .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 26, /*upper=*/ 32));
            assertThat(match.getSnippet()).isEqualTo(window);
        }
    }

    // TODO(tytytyww): Add tests for Double and Long Snippets.
    @Test
    public void testNoSnippets() throws Exception {

        final String propertyKeyString = "content";
        final String propertyValueString =
                "A commonly used fake word is foo.\n"
                        + "   Another nonsense word that’s used a lot\n"
                        + "   is bar.\n";
        final String uri = "uri1";
        final String schemaType = "schema1";
        final String searchWord = "foo";
        final String exactMatch = "foo";
        final String window = "is foo";

        // Building the SearchResult received from query.
        PropertyProto property =
                PropertyProto.newBuilder()
                        .setName(propertyKeyString)
                        .addStringValues(propertyValueString)
                        .build();
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri(uri)
                        .setSchema(schemaType)
                        .addProperties(property)
                        .build();
        SearchResultProto.ResultProto resultProto =
                SearchResultProto.ResultProto.newBuilder().setDocument(documentProto).build();
        SearchResultProto searchResultProto =
                SearchResultProto.newBuilder().addResults(resultProto).build();

        SearchResultPage searchResultPage =
                SearchResultToProtoConverter.toSearchResultPage(
                        searchResultProto,
                        Collections.singletonList("packageName"),
                        Collections.singletonList("databaseName"));
        for (SearchResult result : searchResultPage.getResults()) {
            assertThat(result.getMatches()).isEmpty();
        }
    }

    @Test
    public void testMultipleStringSnippet() throws Exception {
        final String searchWord = "Test";

        // Building the SearchResult received from query.
        PropertyProto property1 =
                PropertyProto.newBuilder()
                        .setName("sender.name")
                        .addStringValues("Test Name Jr.")
                        .build();
        PropertyProto property2 =
                PropertyProto.newBuilder()
                        .setName("sender.email")
                        .addStringValues("TestNameJr@gmail.com")
                        .build();
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri("uri1")
                        .setSchema("schema1")
                        .addProperties(property1)
                        .addProperties(property2)
                        .build();
        SnippetProto snippetProto =
                SnippetProto.newBuilder()
                        .addEntries(
                                SnippetProto.EntryProto.newBuilder()
                                        .setPropertyName("sender.name")
                                        .addSnippetMatches(
                                                SnippetMatchProto.newBuilder()
                                                        .setExactMatchPosition(0)
                                                        .setExactMatchBytes(4)
                                                        .setWindowPosition(0)
                                                        .setWindowBytes(9)
                                                        .build())
                                        .build())
                        .addEntries(
                                SnippetProto.EntryProto.newBuilder()
                                        .setPropertyName("sender.email")
                                        .addSnippetMatches(
                                                SnippetMatchProto.newBuilder()
                                                        .setExactMatchPosition(0)
                                                        .setExactMatchBytes(20)
                                                        .setWindowPosition(0)
                                                        .setWindowBytes(20)
                                                        .build())
                                        .build())
                        .build();
        SearchResultProto.ResultProto resultProto =
                SearchResultProto.ResultProto.newBuilder()
                        .setDocument(documentProto)
                        .setSnippet(snippetProto)
                        .build();
        SearchResultProto searchResultProto =
                SearchResultProto.newBuilder().addResults(resultProto).build();

        // Making ResultReader and getting Snippet values.
        SearchResultPage searchResultPage =
                SearchResultToProtoConverter.toSearchResultPage(
                        searchResultProto,
                        Collections.singletonList("packageName"),
                        Collections.singletonList("databaseName"));
        for (SearchResult result : searchResultPage.getResults()) {

            SearchResult.MatchInfo match1 = result.getMatches().get(0);
            assertThat(match1.getPropertyPath()).isEqualTo("sender.name");
            assertThat(match1.getFullText()).isEqualTo("Test Name Jr.");
            assertThat(match1.getExactMatchPosition())
                    .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 4));
            assertThat(match1.getExactMatch()).isEqualTo("Test");
            assertThat(match1.getSnippetPosition())
                    .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 9));
            assertThat(match1.getSnippet()).isEqualTo("Test Name");

            SearchResult.MatchInfo match2 = result.getMatches().get(1);
            assertThat(match2.getPropertyPath()).isEqualTo("sender.email");
            assertThat(match2.getFullText()).isEqualTo("TestNameJr@gmail.com");
            assertThat(match2.getExactMatchPosition())
                    .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 20));
            assertThat(match2.getExactMatch()).isEqualTo("TestNameJr@gmail.com");
            assertThat(match2.getSnippetPosition())
                    .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 20));
            assertThat(match2.getSnippet()).isEqualTo("TestNameJr@gmail.com");
        }
    }
}
