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

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FakeIcingTest {
    @Test
    public void query() {
        FakeIcing icing = new FakeIcing();
        icing.put("uri:cat", "The cat said meow");
        icing.put("uri:dog", "The dog said woof");

        assertThat(icing.query("meow")).containsExactly("uri:cat");
        assertThat(icing.query("said")).containsExactly("uri:cat", "uri:dog");
        assertThat(icing.query("fred")).isEmpty();
    }

    @Test
    public void queryNorm() {
        FakeIcing icing = new FakeIcing();
        icing.put("uri:cat", "The cat said meow");
        icing.put("uri:dog", "The dog said woof");

        assertThat(icing.query("the")).containsExactly("uri:cat", "uri:dog");
        assertThat(icing.query("The")).containsExactly("uri:cat", "uri:dog");
        assertThat(icing.query("tHe")).containsExactly("uri:cat", "uri:dog");
    }

    @Test
    public void get() {
        FakeIcing icing = new FakeIcing();
        icing.put("uri:cat", "The cat said meow");
        assertThat(icing.get("uri:cat")).isEqualTo("The cat said meow");
    }

    @Test
    public void replace() {
        FakeIcing icing = new FakeIcing();
        icing.put("uri:cat", "The cat said meow");
        icing.put("uri:dog", "The dog said woof");

        assertThat(icing.query("meow")).containsExactly("uri:cat");
        assertThat(icing.query("said")).containsExactly("uri:cat", "uri:dog");
        assertThat(icing.get("uri:cat")).isEqualTo("The cat said meow");

        // Replace
        icing.put("uri:cat", "The cat said purr");
        icing.put("uri:bird", "The cat said tweet");

        assertThat(icing.query("meow")).isEmpty();
        assertThat(icing.query("said")).containsExactly("uri:cat", "uri:dog", "uri:bird");
        assertThat(icing.get("uri:cat")).isEqualTo("The cat said purr");
    }

    @Test
    public void delete() {
        FakeIcing icing = new FakeIcing();
        icing.put("uri:cat", "The cat said meow");
        icing.put("uri:dog", "The dog said woof");

        assertThat(icing.query("meow")).containsExactly("uri:cat");
        assertThat(icing.query("said")).containsExactly("uri:cat", "uri:dog");
        assertThat(icing.get("uri:cat")).isEqualTo("The cat said meow");

        // Delete
        icing.delete("uri:cat");
        icing.delete("uri:notreal");

        assertThat(icing.query("meow")).isEmpty();
        assertThat(icing.query("said")).containsExactly("uri:dog");
        assertThat(icing.get("uri:cat")).isNull();
    }
}
