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

package com.android.server.people.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.Pair;

import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(JUnit4.class)
public class PerPackageThrottlerImplTest {
    private static final int DEBOUNCE_INTERVAL = 500;
    private static final String PKG_ONE = "pkg_one";
    private static final String PKG_TWO = "pkg_two";
    private static final int USER_ID = 10;

    private final OffsettableClock mClock = new OffsettableClock.Stopped();
    private final TestHandler mTestHandler = new TestHandler(null, mClock);
    private PerPackageThrottlerImpl mThrottler;

    @Before
    public void setUp() {
        mThrottler = new PerPackageThrottlerImpl(mTestHandler, DEBOUNCE_INTERVAL);
    }

    @Test
    public void scheduleDebounced() {
        AtomicBoolean pkgOneRan = new AtomicBoolean();
        AtomicBoolean pkgTwoRan = new AtomicBoolean();

        mThrottler.scheduleDebounced(new Pair<>(PKG_ONE, USER_ID), () -> pkgOneRan.set(true));
        mThrottler.scheduleDebounced(new Pair<>(PKG_ONE, USER_ID), () -> pkgOneRan.set(true));
        mThrottler.scheduleDebounced(new Pair<>(PKG_TWO, USER_ID), () -> pkgTwoRan.set(true));
        mThrottler.scheduleDebounced(new Pair<>(PKG_TWO, USER_ID), () -> pkgTwoRan.set(true));

        assertFalse(pkgOneRan.get());
        assertFalse(pkgTwoRan.get());
        mClock.fastForward(DEBOUNCE_INTERVAL);
        mTestHandler.timeAdvance();
        assertTrue(pkgOneRan.get());
        assertTrue(pkgTwoRan.get());
    }
}
