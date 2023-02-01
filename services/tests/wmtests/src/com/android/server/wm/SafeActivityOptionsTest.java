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
 * limitations under the License
 */

package com.android.server.wm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.mock;

import android.app.ActivityOptions;
import android.platform.test.annotations.Presubmit;
import android.window.WindowContainerToken;

import androidx.test.filters.MediumTest;

import org.junit.Test;

/**
 * Build/Install/Run:
 *  atest WmTests:SafeActivityOptionsTest
 */
@MediumTest
@Presubmit
public class SafeActivityOptionsTest {

    @Test
    public void testMerge() {
        final ActivityOptions opts1 = ActivityOptions.makeBasic();
        opts1.setLaunchDisplayId(5);
        final ActivityOptions opts2 = ActivityOptions.makeBasic();
        opts2.setLaunchDisplayId(6);
        final SafeActivityOptions options = new SafeActivityOptions(opts1);
        final ActivityOptions result = options.mergeActivityOptions(opts1, opts2);
        assertEquals(6, result.getLaunchDisplayId());
    }

    @Test
    public void test_selectiveCloneDisplayOptions() {
        final WindowContainerToken token = mock(WindowContainerToken.class);
        final int launchDisplayId = 5;
        final int callerDisplayId = 6;

        final SafeActivityOptions clone = new SafeActivityOptions(ActivityOptions.makeBasic()
                .setLaunchTaskDisplayArea(token)
                .setLaunchDisplayId(launchDisplayId)
                .setCallerDisplayId(callerDisplayId))
                .selectiveCloneDisplayOptions();

        assertSame(clone.getOriginalOptions().getLaunchTaskDisplayArea(), token);
        assertEquals(clone.getOriginalOptions().getLaunchDisplayId(), launchDisplayId);
        assertEquals(clone.getOriginalOptions().getCallerDisplayId(), callerDisplayId);
    }
}
