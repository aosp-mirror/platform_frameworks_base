/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.flags;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class FlagCacheTest {
    private static final String NS = "ns";
    private static final String NAME = "name";

    FlagCache mFlagCache = new FlagCache();

    @Test
    public void testGetOrNull_unset() {
        assertThat(mFlagCache.getOrNull(NS, NAME)).isNull();
    }

    @Test
    public void testGetOrSet_unset() {
        assertThat(mFlagCache.getOrSet(NS, NAME, "value")).isEqualTo("value");
    }

    @Test
    public void testGetOrSet_alreadySet() {
        mFlagCache.setIfChanged(NS, NAME, "value");
        assertThat(mFlagCache.getOrSet(NS, NAME, "newvalue")).isEqualTo("value");
    }

    @Test
    public void testSetIfChanged_unset() {
        assertThat(mFlagCache.setIfChanged(NS, NAME, "value")).isTrue();
    }

    @Test
    public void testSetIfChanged_noChange() {
        mFlagCache.setIfChanged(NS, NAME, "value");
        assertThat(mFlagCache.setIfChanged(NS, NAME, "value")).isFalse();
    }

    @Test
    public void testSetIfChanged_changing() {
        mFlagCache.setIfChanged(NS, NAME, "value");
        assertThat(mFlagCache.setIfChanged(NS, NAME, "newvalue")).isTrue();
    }

    @Test
    public void testContainsNamespace_unset() {
        assertThat(mFlagCache.containsNamespace(NS)).isFalse();
    }

    @Test
    public void testContainsNamespace_set() {
        mFlagCache.setIfChanged(NS, NAME, "value");
        assertThat(mFlagCache.containsNamespace(NS)).isTrue();
    }

    @Test
    public void testContains_unset() {
        assertThat(mFlagCache.contains(NS, NAME)).isFalse();
    }

    @Test
    public void testContains_set() {
        mFlagCache.setIfChanged(NS, NAME, "value");
        assertThat(mFlagCache.contains(NS, NAME)).isTrue();
    }
}
