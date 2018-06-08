/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link AppTransition}.
 *
 * atest AppTransitionTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AppTransitionTests {

    @Rule
    public final WindowManagerServiceRule mRule = new WindowManagerServiceRule();
    private WindowManagerService mWm;

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        mWm = mRule.getWindowManagerService();
    }

    @Test
    public void testKeyguardOverride() throws Exception {
        mWm.prepareAppTransition(TRANSIT_ACTIVITY_OPEN, false /* alwaysKeepCurrent */);
        mWm.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY, false /* alwaysKeepCurrent */);
        assertEquals(TRANSIT_KEYGUARD_GOING_AWAY, mWm.mAppTransition.getAppTransition());
    }

    @Test
    public void testKeyguardKeep() throws Exception {
        mWm.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY, false /* alwaysKeepCurrent */);
        mWm.prepareAppTransition(TRANSIT_ACTIVITY_OPEN, false /* alwaysKeepCurrent */);
        assertEquals(TRANSIT_KEYGUARD_GOING_AWAY, mWm.mAppTransition.getAppTransition());
    }

    @Test
    public void testForceOverride() throws Exception {
        mWm.prepareAppTransition(TRANSIT_KEYGUARD_UNOCCLUDE, false /* alwaysKeepCurrent */);
        mWm.prepareAppTransition(TRANSIT_ACTIVITY_OPEN, false /* alwaysKeepCurrent */,
                0 /* flags */, true /* forceOverride */);
        assertEquals(TRANSIT_ACTIVITY_OPEN, mWm.mAppTransition.getAppTransition());
    }

    @Test
    public void testCrashing() throws Exception {
        mWm.prepareAppTransition(TRANSIT_ACTIVITY_OPEN, false /* alwaysKeepCurrent */);
        mWm.prepareAppTransition(TRANSIT_CRASHING_ACTIVITY_CLOSE, false /* alwaysKeepCurrent */);
        assertEquals(TRANSIT_CRASHING_ACTIVITY_CLOSE, mWm.mAppTransition.getAppTransition());
    }

    @Test
    public void testKeepKeyguard_withCrashing() throws Exception {
        mWm.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY, false /* alwaysKeepCurrent */);
        mWm.prepareAppTransition(TRANSIT_CRASHING_ACTIVITY_CLOSE, false /* alwaysKeepCurrent */);
        assertEquals(TRANSIT_KEYGUARD_GOING_AWAY, mWm.mAppTransition.getAppTransition());
    }
}
