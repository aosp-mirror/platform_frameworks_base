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

import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;

public class VisibilityStoreTest {

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();
    private AppSearchImpl mAppSearchImpl;
    private VisibilityStore mVisibilityStore;

    @Before
    public void setUp() throws Exception {
        mAppSearchImpl = AppSearchImpl.create(mTemporaryFolder.newFolder());
        mVisibilityStore = mAppSearchImpl.getVisibilityStoreLocked();
    }

    /**
     * Make sure that we don't conflict with any special characters that AppSearchImpl has reserved.
     */
    @Test
    public void testValidPackageName() {
        assertThat(VisibilityStore.PACKAGE_NAME)
                .doesNotContain(
                        "" + AppSearchImpl.PACKAGE_DELIMITER); // Convert the chars to CharSequences
        assertThat(VisibilityStore.PACKAGE_NAME)
                .doesNotContain(
                        ""
                                + AppSearchImpl
                                        .DATABASE_DELIMITER); // Convert the chars to CharSequences
    }

    /**
     * Make sure that we don't conflict with any special characters that AppSearchImpl has reserved.
     */
    @Test
    public void testValidDatabaseName() {
        assertThat(VisibilityStore.DATABASE_NAME)
                .doesNotContain(
                        "" + AppSearchImpl.PACKAGE_DELIMITER); // Convert the chars to CharSequences
        assertThat(VisibilityStore.DATABASE_NAME)
                .doesNotContain(
                        ""
                                + AppSearchImpl
                                        .DATABASE_DELIMITER); // Convert the chars to CharSequences
    }

    @Test
    public void testSetVisibility() throws Exception {
        mVisibilityStore.setVisibility(
                "prefix",
                /*schemasNotPlatformSurfaceable=*/ ImmutableSet.of(
                        "prefix/schema1", "prefix/schema2"));
        assertThat(mVisibilityStore.isSchemaPlatformSurfaceable("prefix", "prefix/schema1"))
                .isFalse();
        assertThat(mVisibilityStore.isSchemaPlatformSurfaceable("prefix", "prefix/schema2"))
                .isFalse();

        // New .setVisibility() call completely overrides previous visibility settings. So
        // "schema2" isn't preserved.
        mVisibilityStore.setVisibility(
                "prefix",
                /*schemasNotPlatformSurfaceable=*/ ImmutableSet.of(
                        "prefix/schema1", "prefix/schema3"));
        assertThat(mVisibilityStore.isSchemaPlatformSurfaceable("prefix", "prefix/schema1"))
                .isFalse();
        assertThat(mVisibilityStore.isSchemaPlatformSurfaceable("prefix", "prefix/schema2"))
                .isTrue();
        assertThat(mVisibilityStore.isSchemaPlatformSurfaceable("prefix", "prefix/schema3"))
                .isFalse();

        mVisibilityStore.setVisibility(
                "prefix", /*schemasNotPlatformSurfaceable=*/ Collections.emptySet());
        assertThat(mVisibilityStore.isSchemaPlatformSurfaceable("prefix", "prefix/schema1"))
                .isTrue();
        assertThat(mVisibilityStore.isSchemaPlatformSurfaceable("prefix", "prefix/schema2"))
                .isTrue();
        assertThat(mVisibilityStore.isSchemaPlatformSurfaceable("prefix", "prefix/schema3"))
                .isTrue();
    }

    @Test
    public void testEmptyPrefix() throws Exception {
        mVisibilityStore.setVisibility(
                /*prefix=*/ "",
                /*schemasNotPlatformSurfaceable=*/ ImmutableSet.of("schema1", "schema2"));
        assertThat(mVisibilityStore.isSchemaPlatformSurfaceable(/*prefix=*/ "", "schema1"))
                .isFalse();
        assertThat(mVisibilityStore.isSchemaPlatformSurfaceable(/*prefix=*/ "", "schema2"))
                .isFalse();
    }
}
