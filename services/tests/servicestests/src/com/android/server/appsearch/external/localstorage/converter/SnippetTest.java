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

import com.android.server.appsearch.external.localstorage.util.PrefixUtil;
import com.android.server.appsearch.icing.proto.DocumentProto;
import com.android.server.appsearch.icing.proto.PropertyProto;
import com.android.server.appsearch.icing.proto.SchemaTypeConfigProto;
import com.android.server.appsearch.icing.proto.SearchResultProto;
import com.android.server.appsearch.icing.proto.SnippetMatchProto;
import com.android.server.appsearch.icing.proto.SnippetProto;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;

public class SnippetTest {
    private static final String SCHEMA_TYPE = "schema1";
    private static final String PACKAGE_NAME = "packageName";
    private static final String DATABASE_NAME = "databaseName";
    private static final String PREFIX = PrefixUtil.createPrefix(PACKAGE_NAME, DATABASE_NAME);
    private static final SchemaTypeConfigProto SCHEMA_TYPE_CONFIG_PROTO =
            SchemaTypeConfigProto.newBuilder().setSchemaType(PREFIX + SCHEMA_TYPE).build();
    private static final Map<String, Map<String, SchemaTypeConfigProto>> SCHEMA_MAP =
            Collections.singletonMap(
                    PREFIX,
                    Collections.singletonMap(PREFIX + SCHEMA_TYPE, SCHEMA_TYPE_CONFIG_PROTO));

    @Test
    public void testSingleStringSnippet() {
        final String propertyKeyString = "content";
        final String propertyValueString =
                "A commonly used fake word is foo.\n"
                        + "   Another nonsense word that’s used a lot\n"
                        + "   is bar.\n";
        final String id = "id1";
        final String exactMatch = "foo";
        final String window = "is foo";

        // Building the SearchResult received from query.
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri(id)
                        .setSchema(SCHEMA_TYPE)
                        .addProperties(
                                PropertyProto.newBuilder()
                                        .setName(propertyKeyString)
                                        .addStringValues(propertyValueString))
                        .build();
        SnippetProto snippetProto =
                SnippetProto.newBuilder()
                        .addEntries(
                                SnippetProto.EntryProto.newBuilder()
                                        .setPropertyName(propertyKeyString)
                                        .addSnippetMatches(
                                                SnippetMatchProto.newBuilder()
                                                        .setExactMatchBytePosition(29)
                                                        .setExactMatchByteLength(3)
                                                        .setExactMatchUtf16Position(29)
                                                        .setExactMatchUtf16Length(3)
                                                        .setWindowBytePosition(26)
                                                        .setWindowByteLength(6)
                                                        .setWindowUtf16Position(26)
                                                        .setWindowUtf16Length(6)))
                        .build();
        SearchResultProto searchResultProto =
                SearchResultProto.newBuilder()
                        .addResults(
                                SearchResultProto.ResultProto.newBuilder()
                                        .setDocument(documentProto)
                                        .setSnippet(snippetProto))
                        .build();

        // Making ResultReader and getting Snippet values.
        SearchResultPage searchResultPage =
                SearchResultToProtoConverter.toSearchResultPage(
                        searchResultProto,
                        Collections.singletonList(PACKAGE_NAME),
                        Collections.singletonList(DATABASE_NAME),
                        SCHEMA_MAP);
        assertThat(searchResultPage.getResults()).hasSize(1);
        SearchResult.MatchInfo match = searchResultPage.getResults().get(0).getMatchInfos().get(0);
        assertThat(match.getPropertyPath()).isEqualTo(propertyKeyString);
        assertThat(match.getFullText()).isEqualTo(propertyValueString);
        assertThat(match.getExactMatch()).isEqualTo(exactMatch);
        assertThat(match.getExactMatchRange())
                .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 29, /*upper=*/ 32));
        assertThat(match.getFullText()).isEqualTo(propertyValueString);
        assertThat(match.getSnippetRange())
                .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 26, /*upper=*/ 32));
        assertThat(match.getSnippet()).isEqualTo(window);
    }

    @Test
    public void testNoSnippets() {
        final String propertyKeyString = "content";
        final String propertyValueString =
                "A commonly used fake word is foo.\n"
                        + "   Another nonsense word that’s used a lot\n"
                        + "   is bar.\n";
        final String id = "id1";

        // Building the SearchResult received from query.
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri(id)
                        .setSchema(SCHEMA_TYPE)
                        .addProperties(
                                PropertyProto.newBuilder()
                                        .setName(propertyKeyString)
                                        .addStringValues(propertyValueString))
                        .build();
        SearchResultProto searchResultProto =
                SearchResultProto.newBuilder()
                        .addResults(
                                SearchResultProto.ResultProto.newBuilder()
                                        .setDocument(documentProto))
                        .build();

        SearchResultPage searchResultPage =
                SearchResultToProtoConverter.toSearchResultPage(
                        searchResultProto,
                        Collections.singletonList(PACKAGE_NAME),
                        Collections.singletonList(DATABASE_NAME),
                        SCHEMA_MAP);
        assertThat(searchResultPage.getResults()).hasSize(1);
        assertThat(searchResultPage.getResults().get(0).getMatchInfos()).isEmpty();
    }

    @Test
    public void testMultipleStringSnippet() {
        // Building the SearchResult received from query.
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri("uri1")
                        .setSchema(SCHEMA_TYPE)
                        .addProperties(
                                PropertyProto.newBuilder()
                                        .setName("senderName")
                                        .addStringValues("Test Name Jr."))
                        .addProperties(
                                PropertyProto.newBuilder()
                                        .setName("senderEmail")
                                        .addStringValues("TestNameJr@gmail.com"))
                        .build();
        SnippetProto snippetProto =
                SnippetProto.newBuilder()
                        .addEntries(
                                SnippetProto.EntryProto.newBuilder()
                                        .setPropertyName("senderName")
                                        .addSnippetMatches(
                                                SnippetMatchProto.newBuilder()
                                                        .setExactMatchBytePosition(0)
                                                        .setExactMatchByteLength(4)
                                                        .setExactMatchUtf16Position(0)
                                                        .setExactMatchUtf16Length(4)
                                                        .setWindowBytePosition(0)
                                                        .setWindowByteLength(9)
                                                        .setWindowUtf16Position(0)
                                                        .setWindowUtf16Length(9)))
                        .addEntries(
                                SnippetProto.EntryProto.newBuilder()
                                        .setPropertyName("senderEmail")
                                        .addSnippetMatches(
                                                SnippetMatchProto.newBuilder()
                                                        .setExactMatchBytePosition(0)
                                                        .setExactMatchByteLength(20)
                                                        .setExactMatchUtf16Position(0)
                                                        .setExactMatchUtf16Length(20)
                                                        .setWindowBytePosition(0)
                                                        .setWindowByteLength(20)
                                                        .setWindowUtf16Position(0)
                                                        .setWindowUtf16Length(20)))
                        .build();
        SearchResultProto searchResultProto =
                SearchResultProto.newBuilder()
                        .addResults(
                                SearchResultProto.ResultProto.newBuilder()
                                        .setDocument(documentProto)
                                        .setSnippet(snippetProto))
                        .build();

        // Making ResultReader and getting Snippet values.
        SearchResultPage searchResultPage =
                SearchResultToProtoConverter.toSearchResultPage(
                        searchResultProto,
                        Collections.singletonList(PACKAGE_NAME),
                        Collections.singletonList(DATABASE_NAME),
                        SCHEMA_MAP);
        assertThat(searchResultPage.getResults()).hasSize(1);
        SearchResult.MatchInfo match1 = searchResultPage.getResults().get(0).getMatchInfos().get(0);
        assertThat(match1.getPropertyPath()).isEqualTo("senderName");
        assertThat(match1.getFullText()).isEqualTo("Test Name Jr.");
        assertThat(match1.getExactMatchRange())
                .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 4));
        assertThat(match1.getExactMatch()).isEqualTo("Test");
        assertThat(match1.getSnippetRange())
                .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 9));
        assertThat(match1.getSnippet()).isEqualTo("Test Name");

        SearchResult.MatchInfo match2 = searchResultPage.getResults().get(0).getMatchInfos().get(1);
        assertThat(match2.getPropertyPath()).isEqualTo("senderEmail");
        assertThat(match2.getFullText()).isEqualTo("TestNameJr@gmail.com");
        assertThat(match2.getExactMatchRange())
                .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 20));
        assertThat(match2.getExactMatch()).isEqualTo("TestNameJr@gmail.com");
        assertThat(match2.getSnippetRange())
                .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 20));
        assertThat(match2.getSnippet()).isEqualTo("TestNameJr@gmail.com");
    }

    @Test
    public void testNestedDocumentSnippet() {
        // Building the SearchResult received from query.
        DocumentProto documentProto =
                DocumentProto.newBuilder()
                        .setUri("id1")
                        .setSchema(SCHEMA_TYPE)
                        .addProperties(
                                PropertyProto.newBuilder()
                                        .setName("sender")
                                        .addDocumentValues(
                                                DocumentProto.newBuilder()
                                                        .addProperties(
                                                                PropertyProto.newBuilder()
                                                                        .setName("name")
                                                                        .addStringValues(
                                                                                "Test Name Jr."))
                                                        .addProperties(
                                                                PropertyProto.newBuilder()
                                                                        .setName("email")
                                                                        .addStringValues(
                                                                                "TestNameJr@gmail.com")
                                                                        .addStringValues(
                                                                                "TestNameJr2@gmail.com"))))
                        .build();
        SnippetProto snippetProto =
                SnippetProto.newBuilder()
                        .addEntries(
                                SnippetProto.EntryProto.newBuilder()
                                        .setPropertyName("sender.name")
                                        .addSnippetMatches(
                                                SnippetMatchProto.newBuilder()
                                                        .setExactMatchBytePosition(0)
                                                        .setExactMatchByteLength(4)
                                                        .setExactMatchUtf16Position(0)
                                                        .setExactMatchUtf16Length(4)
                                                        .setWindowBytePosition(0)
                                                        .setWindowByteLength(9)
                                                        .setWindowUtf16Position(0)
                                                        .setWindowUtf16Length(9)))
                        .addEntries(
                                SnippetProto.EntryProto.newBuilder()
                                        .setPropertyName("sender.email[1]")
                                        .addSnippetMatches(
                                                SnippetMatchProto.newBuilder()
                                                        .setExactMatchBytePosition(0)
                                                        .setExactMatchByteLength(21)
                                                        .setExactMatchUtf16Position(0)
                                                        .setExactMatchUtf16Length(21)
                                                        .setWindowBytePosition(0)
                                                        .setWindowByteLength(21)
                                                        .setWindowUtf16Position(0)
                                                        .setWindowUtf16Length(21)))
                        .build();
        SearchResultProto searchResultProto =
                SearchResultProto.newBuilder()
                        .addResults(
                                SearchResultProto.ResultProto.newBuilder()
                                        .setDocument(documentProto)
                                        .setSnippet(snippetProto))
                        .build();

        // Making ResultReader and getting Snippet values.
        SearchResultPage searchResultPage =
                SearchResultToProtoConverter.toSearchResultPage(
                        searchResultProto,
                        Collections.singletonList(PACKAGE_NAME),
                        Collections.singletonList(DATABASE_NAME),
                        SCHEMA_MAP);
        assertThat(searchResultPage.getResults()).hasSize(1);
        SearchResult.MatchInfo match1 = searchResultPage.getResults().get(0).getMatchInfos().get(0);
        assertThat(match1.getPropertyPath()).isEqualTo("sender.name");
        assertThat(match1.getFullText()).isEqualTo("Test Name Jr.");
        assertThat(match1.getExactMatchRange())
                .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 4));
        assertThat(match1.getExactMatch()).isEqualTo("Test");
        assertThat(match1.getSnippetRange())
                .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 9));
        assertThat(match1.getSnippet()).isEqualTo("Test Name");

        SearchResult.MatchInfo match2 = searchResultPage.getResults().get(0).getMatchInfos().get(1);
        assertThat(match2.getPropertyPath()).isEqualTo("sender.email[1]");
        assertThat(match2.getFullText()).isEqualTo("TestNameJr2@gmail.com");
        assertThat(match2.getExactMatchRange())
                .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 21));
        assertThat(match2.getExactMatch()).isEqualTo("TestNameJr2@gmail.com");
        assertThat(match2.getSnippetRange())
                .isEqualTo(new SearchResult.MatchRange(/*lower=*/ 0, /*upper=*/ 21));
        assertThat(match2.getSnippet()).isEqualTo("TestNameJr2@gmail.com");
    }
}
