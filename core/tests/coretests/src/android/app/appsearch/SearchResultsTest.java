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

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.app.appsearch.proto.DocumentProto;
import android.app.appsearch.proto.SearchResultProto;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
public class SearchResultsTest {

    @Test
    public void testSearchResultsEqual() {
        final String uri = "testUri";
        final String schemaType = "testSchema";
        SearchResultProto.ResultProto result1 = SearchResultProto.ResultProto.newBuilder()
                .setDocument(DocumentProto.newBuilder()
                        .setUri(uri)
                        .setSchema(schemaType)
                        .build())
                .build();
        SearchResultProto searchResults1 = SearchResultProto.newBuilder()
                .addResults(result1)
                .build();
        SearchResults res1 = new SearchResults(searchResults1);
        SearchResultProto.ResultProto result2 = SearchResultProto.ResultProto.newBuilder()
                .setDocument(DocumentProto.newBuilder()
                        .setUri(uri)
                        .setSchema(schemaType)
                        .build())
                .build();
        SearchResultProto searchResults2 = SearchResultProto.newBuilder()
                .addResults(result2)
                .build();
        SearchResults res2 = new SearchResults(searchResults2);
        assertThat(res1.toString()).isEqualTo(res2.toString());
    }

    @Test
    public void buildSearchSpecWithoutTermMatchType() {
        assertThrows(RuntimeException.class, () -> SearchSpec.newBuilder()
                .setSchemaTypes("testSchemaType")
                .build());
    }
}
