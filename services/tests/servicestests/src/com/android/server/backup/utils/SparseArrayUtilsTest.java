/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.utils;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.SparseArray;

import com.google.android.collect.Sets;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashSet;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SparseArrayUtilsTest {
    @Test
    public void union_mergesSets() {
        SparseArray<HashSet<String>> sparseArray = new SparseArray<>();
        sparseArray.append(12, Sets.newHashSet("a", "b", "c"));
        sparseArray.append(45, Sets.newHashSet("d", "e"));
        sparseArray.append(46, Sets.newHashSet());
        sparseArray.append(66, Sets.newHashSet("a", "e", "f"));

        assertThat(SparseArrayUtils.union(sparseArray)).isEqualTo(
                Sets.newHashSet("a", "b", "c", "d", "e", "f"));
    }

    @Test
    public void union_returnsEmptySetForEmptyList() {
        SparseArray<HashSet<String>> sparseArray = new SparseArray<>();

        assertThat(SparseArrayUtils.union(sparseArray)).isEqualTo(Sets.newHashSet());
    }
}
