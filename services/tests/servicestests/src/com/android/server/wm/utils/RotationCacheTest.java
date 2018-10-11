/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.utils;

import static android.util.Pair.create;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.FlakyTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Pair;

import com.android.server.wm.utils.RotationCache.RotationDependentComputation;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
@FlakyTest(bugId = 74078662)
@Presubmit
public class RotationCacheTest {

    private RotationCache<Object, Pair<Object, Integer>> mCache;
    private boolean mComputationCalled;

    @Before
    public void setUp() throws Exception {
        mComputationCalled = false;
        mCache = new RotationCache<>((o, rot) -> {
            mComputationCalled = true;
            return create(o, rot);
        });
    }

    @Test
    public void getOrCompute_computes() throws Exception {
        assertThat(mCache.getOrCompute("hello", 0), equalTo(create("hello", 0)));
        assertThat(mCache.getOrCompute("hello", 1), equalTo(create("hello", 1)));
        assertThat(mCache.getOrCompute("hello", 2), equalTo(create("hello", 2)));
        assertThat(mCache.getOrCompute("hello", 3), equalTo(create("hello", 3)));
    }

    @Test
    public void getOrCompute_sameParam_sameRot_hitsCache() throws Exception {
        assertNotNull(mCache.getOrCompute("hello", 1));

        mComputationCalled = false;
        assertThat(mCache.getOrCompute("hello", 1), equalTo(create("hello", 1)));
        assertThat(mComputationCalled, is(false));
    }

    @Test
    public void getOrCompute_sameParam_hitsCache_forAllRots() throws Exception {
        assertNotNull(mCache.getOrCompute("hello", 3));
        assertNotNull(mCache.getOrCompute("hello", 2));
        assertNotNull(mCache.getOrCompute("hello", 1));
        assertNotNull(mCache.getOrCompute("hello", 0));

        mComputationCalled = false;
        assertThat(mCache.getOrCompute("hello", 1), equalTo(create("hello", 1)));
        assertThat(mCache.getOrCompute("hello", 0), equalTo(create("hello", 0)));
        assertThat(mCache.getOrCompute("hello", 2), equalTo(create("hello", 2)));
        assertThat(mCache.getOrCompute("hello", 3), equalTo(create("hello", 3)));
        assertThat(mComputationCalled, is(false));
    }

    @Test
    public void getOrCompute_changingParam_recomputes() throws Exception {
        assertNotNull(mCache.getOrCompute("hello", 1));

        assertThat(mCache.getOrCompute("world", 1), equalTo(create("world", 1)));
    }

    @Test
    public void getOrCompute_changingParam_clearsCacheForDifferentRots() throws Exception {
        assertNotNull(mCache.getOrCompute("hello", 1));
        assertNotNull(mCache.getOrCompute("world", 2));

        mComputationCalled = false;
        assertThat(mCache.getOrCompute("hello", 1), equalTo(create("hello", 1)));
        assertThat(mComputationCalled, is(true));
    }
}
