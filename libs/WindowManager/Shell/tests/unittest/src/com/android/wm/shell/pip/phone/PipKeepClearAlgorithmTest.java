/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.pip.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;

/**
 * Unit tests against {@link PipKeepClearAlgorithm}.
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class PipKeepClearAlgorithmTest extends ShellTestCase {

    private PipKeepClearAlgorithm mPipKeepClearAlgorithm;


    @Before
    public void setUp() throws Exception {
        mPipKeepClearAlgorithm = new PipKeepClearAlgorithm();
    }

    @Test
    public void adjust_withCollidingRestrictedKeepClearAreas_movesBounds() {
        final Rect inBounds = new Rect(0, 0, 100, 100);
        final Rect keepClearRect = new Rect(50, 50, 150, 150);

        final Rect outBounds = mPipKeepClearAlgorithm.adjust(inBounds, Set.of(keepClearRect),
                Set.of());

        assertFalse(outBounds.contains(keepClearRect));
    }

    @Test
    public void adjust_withNonCollidingRestrictedKeepClearAreas_boundsDoNotChange() {
        final Rect inBounds = new Rect(0, 0, 100, 100);
        final Rect keepClearRect = new Rect(100, 100, 150, 150);

        final Rect outBounds = mPipKeepClearAlgorithm.adjust(inBounds, Set.of(keepClearRect),
                Set.of());

        assertEquals(inBounds, outBounds);
    }

    @Test
    public void adjust_withCollidingUnrestrictedKeepClearAreas_boundsDoNotChange() {
        // TODO(b/183746978): update this test to accommodate for the updated algorithm
        final Rect inBounds = new Rect(0, 0, 100, 100);
        final Rect keepClearRect = new Rect(50, 50, 150, 150);

        final Rect outBounds = mPipKeepClearAlgorithm.adjust(inBounds, Set.of(),
                Set.of(keepClearRect));

        assertEquals(inBounds, outBounds);
    }

    @Test
    public void adjust_withNonCollidingUnrestrictedKeepClearAreas_boundsDoNotChange() {
        final Rect inBounds = new Rect(0, 0, 100, 100);
        final Rect keepClearRect = new Rect(100, 100, 150, 150);

        final Rect outBounds = mPipKeepClearAlgorithm.adjust(inBounds, Set.of(),
                Set.of(keepClearRect));

        assertEquals(inBounds, outBounds);
    }
}
