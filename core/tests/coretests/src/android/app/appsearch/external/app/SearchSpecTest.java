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

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.expectThrows;

import android.os.Bundle;

import org.junit.Test;

public class SearchSpecTest {
    @Test
    public void buildSearchSpecWithoutTermMatchType() {
        expectThrows(RuntimeException.class, () -> new SearchSpec.Builder()
                .addSchema("testSchemaType")
                .build());
    }

    @Test
    public void testBuildSearchSpec() {
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                .addNamespace("namespace1", "namespace2")
                .addSchema("schemaTypes1", "schemaTypes2")
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
        assertThat(searchSpec.getSchemas())
                .containsExactly("schemaTypes1", "schemaTypes2").inOrder();
        assertThat(searchSpec.getSnippetCount()).isEqualTo(5);
        assertThat(searchSpec.getSnippetCountPerProperty()).isEqualTo(10);
        assertThat(searchSpec.getMaxSnippetSize()).isEqualTo(15);
        assertThat(searchSpec.getNumPerPage()).isEqualTo(42);
        assertThat(searchSpec.getOrder()).isEqualTo(SearchSpec.ORDER_ASCENDING);
        assertThat(searchSpec.getRankingStrategy())
                .isEqualTo(SearchSpec.RANKING_STRATEGY_DOCUMENT_SCORE);
    }

    @Test
    public void testGetBundle() {
        SearchSpec searchSpec = new SearchSpec.Builder()
                .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                .addNamespace("namespace1", "namespace2")
                .addSchema("schemaTypes1", "schemaTypes2")
                .setSnippetCount(5)
                .setSnippetCountPerProperty(10)
                .setMaxSnippetSize(15)
                .setNumPerPage(42)
                .setOrder(SearchSpec.ORDER_ASCENDING)
                .setRankingStrategy(SearchSpec.RANKING_STRATEGY_DOCUMENT_SCORE)
                .build();

        Bundle bundle = searchSpec.getBundle();
        assertThat(bundle.getInt(SearchSpec.TERM_MATCH_TYPE_FIELD))
                .isEqualTo(SearchSpec.TERM_MATCH_PREFIX);
        assertThat(bundle.getStringArrayList(SearchSpec.NAMESPACE_FIELD)).containsExactly(
                "namespace1", "namespace2");
        assertThat(bundle.getStringArrayList(SearchSpec.SCHEMA_TYPE_FIELD)).containsExactly(
                "schemaTypes1", "schemaTypes2");
        assertThat(bundle.getInt(SearchSpec.SNIPPET_COUNT_FIELD)).isEqualTo(5);
        assertThat(bundle.getInt(SearchSpec.SNIPPET_COUNT_PER_PROPERTY_FIELD)).isEqualTo(10);
        assertThat(bundle.getInt(SearchSpec.MAX_SNIPPET_FIELD)).isEqualTo(15);
        assertThat(bundle.getInt(SearchSpec.NUM_PER_PAGE_FIELD)).isEqualTo(42);
        assertThat(bundle.getInt(SearchSpec.ORDER_FIELD)).isEqualTo(SearchSpec.ORDER_ASCENDING);
        assertThat(bundle.getInt(SearchSpec.RANKING_STRATEGY_FIELD))
                .isEqualTo(SearchSpec.RANKING_STRATEGY_DOCUMENT_SCORE);
    }
}
