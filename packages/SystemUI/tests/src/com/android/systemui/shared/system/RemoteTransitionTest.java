/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.shared.system;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.window.TransitionInfo.FLAG_IS_WALLPAPER;
import static android.window.TransitionInfo.FLAG_SHOW_WALLPAPER;
import static android.window.TransitionInfo.FLAG_TRANSLUCENT;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.ACTIVITY_TYPE_HOME;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.MODE_CHANGING;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.MODE_CLOSING;
import static com.android.systemui.shared.system.RemoteAnimationTargetCompat.MODE_OPENING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;

import android.app.ActivityManager;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class RemoteTransitionTest extends SysuiTestCase {

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testLegacyTargetExtract() {
        TransitionInfo combined = new TransitionInfoBuilder(TRANSIT_CLOSE)
                .addChange(TRANSIT_CHANGE, FLAG_SHOW_WALLPAPER)
                .addChange(TRANSIT_CLOSE, 0 /* flags */)
                .addChange(TRANSIT_OPEN, FLAG_IS_WALLPAPER).build();
        // Check non-wallpaper extraction
        RemoteAnimationTargetCompat[] wrapped = RemoteAnimationTargetCompat.wrap(combined,
                false /* wallpapers */, mock(SurfaceControl.Transaction.class), null /* leashes */);
        assertEquals(2, wrapped.length);
        int changeLayer = -1;
        int closeLayer = -1;
        for (RemoteAnimationTargetCompat t : wrapped) {
            if (t.mode == MODE_CHANGING) {
                changeLayer = t.prefixOrderIndex;
            } else if (t.mode == MODE_CLOSING) {
                closeLayer = t.prefixOrderIndex;
            } else {
                fail();
            }
        }
        // verify ordering
        assertTrue(closeLayer < changeLayer);

        // Check wallpaper extraction
        RemoteAnimationTargetCompat[] wallps = RemoteAnimationTargetCompat.wrap(combined,
                true /* wallpapers */, mock(SurfaceControl.Transaction.class), null /* leashes */);
        assertEquals(1, wallps.length);
        assertTrue(wallps[0].prefixOrderIndex < closeLayer);
        assertEquals(MODE_OPENING, wallps[0].mode);
    }

    @Test
    public void testLegacyTargetWrapper() {
        TransitionInfo tinfo = new TransitionInfoBuilder(TRANSIT_CLOSE)
                .addChange(TRANSIT_CHANGE, FLAG_TRANSLUCENT).build();
        final TransitionInfo.Change change = tinfo.getChanges().get(0);
        final Rect endBounds = new Rect(40, 60, 140, 200);
        change.setTaskInfo(createTaskInfo(1 /* taskId */, ACTIVITY_TYPE_HOME));
        change.setEndAbsBounds(endBounds);
        change.setEndRelOffset(0, 0);
        final RemoteAnimationTargetCompat wrapped = new RemoteAnimationTargetCompat(change,
                0 /* order */, tinfo, mock(SurfaceControl.Transaction.class));
        assertEquals(ACTIVITY_TYPE_HOME, wrapped.activityType);
        assertEquals(new Rect(0, 0, 100, 140), wrapped.localBounds);
        assertEquals(endBounds, wrapped.screenSpaceBounds);
        assertTrue(wrapped.isTranslucent);
    }

    class TransitionInfoBuilder {
        final TransitionInfo mInfo;

        TransitionInfoBuilder(@WindowManager.TransitionType int type) {
            mInfo = new TransitionInfo(type, 0 /* flags */);
            mInfo.setRootLeash(createMockSurface(true /* valid */), 0, 0);
        }

        TransitionInfoBuilder addChange(@WindowManager.TransitionType int mode,
                @TransitionInfo.ChangeFlags int flags) {
            final TransitionInfo.Change change =
                    new TransitionInfo.Change(null /* token */, createMockSurface(true));
            change.setMode(mode);
            change.setFlags(flags);
            mInfo.addChange(change);
            return this;
        }

        TransitionInfo build() {
            return mInfo;
        }
    }

    private static SurfaceControl createMockSurface(boolean valid) {
        SurfaceControl sc = mock(SurfaceControl.class);
        if (valid) {
            doReturn(true).when(sc).isValid();
            doReturn("TestSurface").when(sc).toString();
        }
        return sc;
    }

    private static ActivityManager.RunningTaskInfo createTaskInfo(int taskId, int activityType) {
        ActivityManager.RunningTaskInfo taskInfo = new ActivityManager.RunningTaskInfo();
        taskInfo.taskId = taskId;
        taskInfo.configuration.windowConfiguration.setActivityType(activityType);
        return taskInfo;
    }

}
