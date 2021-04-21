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

package com.android.wm.shell.splitscreen;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.content.Context;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.window.DisplayAreaInfo;
import android.window.IWindowContainerToken;
import android.window.WindowContainerToken;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.split.SplitLayout;
import com.android.wm.shell.transition.Transitions;

public class SplitTestUtils {

    static SplitLayout createMockSplitLayout() {
        final Rect dividerBounds = new Rect(48, 0, 52, 100);
        final SurfaceControl leash = createMockSurface();
        SplitLayout out = mock(SplitLayout.class);
        doReturn(dividerBounds).when(out).getDividerBounds();
        doReturn(leash).when(out).getDividerLeash();
        return out;
    }

    static SurfaceControl createMockSurface() {
        return createMockSurface(true);
    }

    static SurfaceControl createMockSurface(boolean valid) {
        SurfaceControl sc = mock(SurfaceControl.class);
        ExtendedMockito.doReturn(valid).when(sc).isValid();
        return sc;
    }

    static class TestStageCoordinator extends StageCoordinator {
        final DisplayAreaInfo mDisplayAreaInfo;

        TestStageCoordinator(Context context, int displayId, SyncTransactionQueue syncQueue,
                RootTaskDisplayAreaOrganizer rootTDAOrganizer, ShellTaskOrganizer taskOrganizer,
                MainStage mainStage, SideStage sideStage, DisplayImeController imeController,
                SplitLayout splitLayout, Transitions transitions, TransactionPool transactionPool) {
            super(context, displayId, syncQueue, rootTDAOrganizer, taskOrganizer, mainStage,
                    sideStage, imeController, splitLayout, transitions, transactionPool);

            // Prepare default TaskDisplayArea for testing.
            mDisplayAreaInfo = new DisplayAreaInfo(
                    new WindowContainerToken(new IWindowContainerToken.Default()),
                    DEFAULT_DISPLAY,
                    FEATURE_DEFAULT_TASK_CONTAINER);
            this.onDisplayAreaAppeared(mDisplayAreaInfo);
        }
    }
}
