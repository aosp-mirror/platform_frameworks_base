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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.TRANSIT_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_TASK_OPEN;
import static junit.framework.Assert.assertEquals;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.WindowManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WindowSurfacePlacerTest extends WindowTestsBase {

    private WindowSurfacePlacer mWindowSurfacePlacer;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mWindowSurfacePlacer = new WindowSurfacePlacer(sWm);
    }

    @Test
    public void testTranslucentOpen() throws Exception {
        synchronized (sWm.mWindowMap) {
            final AppWindowToken behind = createAppWindowToken(mDisplayContent,
                    WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
            final AppWindowToken translucentOpening = createAppWindowToken(mDisplayContent,
                    WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
            translucentOpening.setFillsParent(false);
            translucentOpening.setHidden(true);
            sWm.mOpeningApps.add(behind);
            sWm.mOpeningApps.add(translucentOpening);
            assertEquals(WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_OPEN,
                    mWindowSurfacePlacer.maybeUpdateTransitToTranslucentAnim(TRANSIT_TASK_OPEN));
        }
    }

    @Test
    public void testTranslucentClose() throws Exception {
        synchronized (sWm.mWindowMap) {
            final AppWindowToken behind = createAppWindowToken(mDisplayContent,
                    WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
            final AppWindowToken translucentClosing = createAppWindowToken(mDisplayContent,
                    WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
            translucentClosing.setFillsParent(false);
            sWm.mClosingApps.add(translucentClosing);
            assertEquals(WindowManager.TRANSIT_TRANSLUCENT_ACTIVITY_CLOSE,
                    mWindowSurfacePlacer.maybeUpdateTransitToTranslucentAnim(TRANSIT_TASK_CLOSE));
        }
    }
}
