/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.appsearch.impl;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.appsearch.proto.DocumentProto;
import com.android.server.appsearch.proto.PropertyProto;
import com.android.server.appsearch.proto.SearchResultProto;
import com.android.server.appsearch.proto.StatusProto;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class FakeIcingTest {
    @Test
    public void query() {
        FakeIcing icing = new FakeIcing();
        icing.put(createDoc("uri:cat", "The cat said meow"));
        icing.put(createDoc("uri:dog", "The dog said woof"));

        assertThat(queryGetUris(icing, "meow")).containsExactly("uri:cat");
        assertThat(queryGetUris(icing, "said")).containsExactly("uri:cat", "uri:dog");
        assertThat(queryGetUris(icing, "fred")).isEmpty();
    }

    @Test
    public void queryNorm() {
        FakeIcing icing = new FakeIcing();
        icing.put(createDoc("uri:cat", "The cat said meow"));
        icing.put(createDoc("uri:dog", "The dog said woof"));

        assertThat(queryGetUris(icing, "the")).containsExactly("uri:cat", "uri:dog");
        assertThat(queryGetUris(icing, "The")).containsExactly("uri:cat", "uri:dog");
        assertThat(queryGetUris(icing, "tHe")).containsExactly("uri:cat", "uri:dog");
    }

    @Test
    public void get() {
        DocumentProto cat = createDoc("uri:cat", "The cat said meow");
        FakeIcing icing = new FakeIcing();
        icing.put(cat);
        assertThat(icing.get("uri:cat")).isEqualTo(cat);
    }

    @Test
    public void replace() {
        DocumentProto cat = createDoc("uri:cat", "The cat said meow");
        DocumentProto dog = createDoc("uri:dog", "The dog said woof");

        FakeIcing icing = new FakeIcing();
        icing.put(cat);
        icing.put(dog);

        assertThat(queryGetUris(icing, "meow")).containsExactly("uri:cat");
        assertThat(queryGetUris(icing, "said")).containsExactly("uri:cat", "uri:dog");
        assertThat(icing.get("uri:cat")).isEqualTo(cat);

        // Replace
        DocumentProto cat2 = createDoc("uri:cat", "The cat said purr");
        DocumentProto bird = createDoc("uri:bird", "The cat said tweet");
        icing.put(cat2);
        icing.put(bird);

        assertThat(queryGetUris(icing, "meow")).isEmpty();
        assertThat(queryGetUris(icing, "said")).containsExactly("uri:cat", "uri:dog", "uri:bird");
        assertThat(icing.get("uri:cat")).isEqualTo(cat2);
    }

    @Test
    public void delete() {
        DocumentProto cat = createDoc("uri:cat", "The cat said meow");
        DocumentProto dog = createDoc("uri:dog", "The dog said woof");

        FakeIcing icing = new FakeIcing();
        icing.put(cat);
        icing.put(dog);

        assertThat(queryGetUris(icing, "meow")).containsExactly("uri:cat");
        assertThat(queryGetUris(icing, "said")).containsExactly("uri:cat", "uri:dog");
        assertThat(icing.get("uri:cat")).isEqualTo(cat);

        // Delete
        icing.delete("uri:cat");
        icing.delete("uri:notreal");

        assertThat(queryGetUris(icing, "meow")).isEmpty();
        assertThat(queryGetUris(icing, "said")).containsExactly("uri:dog");
        assertThat(icing.get("uri:cat")).isNull();
    }

    private static DocumentProto createDoc(String uri, String body) {
        return DocumentProto.newBuilder()
                .setUri(uri)
                .addProperties(PropertyProto.newBuilder().addStringValues(body))
                .build();
    }

    private static List<String> queryGetUris(FakeIcing icing, String term) {
        List<String> uris = new ArrayList<>();
        SearchResultProto results = icing.query(term);
        assertThat(results.getStatus().getCode()).isEqualTo(StatusProto.Code.OK);
        for (SearchResultProto.ResultProto result : results.getResultsList()) {
            uris.add(result.getDocument().getUri());
        }
        return uris;
    }
}
