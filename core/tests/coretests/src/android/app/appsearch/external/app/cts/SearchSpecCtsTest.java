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

package android.app.appsearch.cts;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.app.appsearch.SearchSpec;

import org.junit.Test;

public class SearchSpecCtsTest {
    @Test
    public void buildSearchSpecWithoutTermMatchType() {
        RuntimeException e = expectThrows(RuntimeException.class, () -> new SearchSpec.Builder()
                .addSchemaType("testSchemaType")
                .build());
        assertThat(e).hasMessageThat().contains("Missing termMatchType field");
    }

    @Test
    public void testBuildSearchSpec() {
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                .addNamespace("namespace1", "namespace2")
                .addSchemaType("schemaTypes1", "schemaTypes2")
                .setSnippetCount(5)
                .setSnippetCountPerProperty(10)
                .setMaxSnippetSize(15)
                .setNumPerPage(42)
                .setOrder(SearchSpec.ORDER_ASCENDING)
                .setRankingStrategy(SearchSpec.RANKING_STRATEGY_DOCUMENT_SCORE)
                .build();

        assertThat(searchSpec.getTermMatch()).isEqualTo(SearchSpec.TERM_MATCH_PREFIX);
        assertThat(searchSpec.getNamespaces())
                .containsExactly("namespace1", "namespace2").inOrder();
        assertThat(searchSpec.getSchemaTypes())
                .containsExactly("schemaTypes1", "schemaTypes2").inOrder();
        assertThat(searchSpec.getSnippetCount()).isEqualTo(5);
        assertThat(searchSpec.getSnippetCountPerProperty()).isEqualTo(10);
        assertThat(searchSpec.getMaxSnippetSize()).isEqualTo(15);
        assertThat(searchSpec.getNumPerPage()).isEqualTo(42);
        assertThat(searchSpec.getOrder()).isEqualTo(SearchSpec.ORDER_ASCENDING);
        assertThat(searchSpec.getRankingStrategy())
                .isEqualTo(SearchSpec.RANKING_STRATEGY_DOCUMENT_SCORE);
    }
}
