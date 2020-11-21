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

package com.android.server.appsearch.external.localstorage;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;
import java.util.Set;

public class VisibilityStoreTest {

    @Rule
    public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private AppSearchImpl mAppSearchImpl;
    private VisibilityStore mVisibilityStore;

    @Before
    public void setUp() throws Exception {
        mAppSearchImpl = AppSearchImpl.create(mTemporaryFolder.newFolder());
        mVisibilityStore = mAppSearchImpl.getVisibilityStoreLocked();
    }

    @Test
    public void testSetVisibility() throws Exception {
        mVisibilityStore.setVisibility(
                "database", /*platformHiddenSchemas=*/ Set.of("schema1", "schema2"));
        assertThat(mVisibilityStore.getPlatformHiddenSchemas("database"))
                .containsExactly("schema1", "schema2");

        // New .setVisibility() call completely overrides previous visibility settings. So
        // "schema1" isn't preserved.
        mVisibilityStore.setVisibility(
                "database", /*platformHiddenSchemas=*/ Set.of("schema1", "schema3"));
        assertThat(mVisibilityStore.getPlatformHiddenSchemas("database"))
                .containsExactly("schema1", "schema3");

        mVisibilityStore.setVisibility(
                "database", /*platformHiddenSchemas=*/ Collections.emptySet());
        assertThat(mVisibilityStore.getPlatformHiddenSchemas("database")).isEmpty();
    }

    @Test
    public void testRemoveSchemas() throws Exception {
        mVisibilityStore.setVisibility(
                "database", /*platformHiddenSchemas=*/ Set.of("schema1", "schema2"));

        // Removed just schema1
        mVisibilityStore.updateSchemas("database", /*schemasToRemove=*/ Set.of("schema1"));
        assertThat(mVisibilityStore.getPlatformHiddenSchemas("database"))
                .containsExactly("schema2");

        // Removed everything now
        mVisibilityStore.updateSchemas("database", /*schemasToRemove=*/ Set.of("schema2"));
        assertThat(mVisibilityStore.getPlatformHiddenSchemas("database")).isEmpty();
    }

}
