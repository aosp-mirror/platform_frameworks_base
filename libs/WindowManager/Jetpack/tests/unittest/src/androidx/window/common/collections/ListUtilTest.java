/*
 * Copyright (C) 2024 The Android Open Source Project
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

package androidx.window.common.collections;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Test class for {@link ListUtil}.
 *
 * Build/Install/Run:
 *  atest WMJetpackUnitTests:ListUtil
 */
public class ListUtilTest {

    @Test
    public void test_map_empty_returns_empty() {
        final List<String> emptyList = new ArrayList<>();
        final List<Integer> result = ListUtil.map(emptyList, String::length);
        assertThat(result).isEmpty();
    }

    @Test
    public void test_map_maintains_order() {
        final List<String> source = new ArrayList<>();
        source.add("a");
        source.add("aa");

        final List<Integer> result = ListUtil.map(source, String::length);

        assertThat(result).containsExactly(1, 2).inOrder();
    }
}
