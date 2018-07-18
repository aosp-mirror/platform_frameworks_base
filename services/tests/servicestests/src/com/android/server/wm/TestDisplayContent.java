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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.view.Display;
import android.view.Surface;

import com.android.server.policy.WindowManagerPolicy;

public class TestDisplayContent extends DisplayContent {

    TestDisplayContent(Display display, WindowManagerService service,
            WallpaperController wallpaperController, DisplayWindowController controller) {
        super(display, service, wallpaperController, controller);
    }

    public static TestDisplayContent create(WindowManagerPolicy policy, Context context) {
        final TestDisplayContent displayContent = mock(TestDisplayContent.class);
        displayContent.isDefaultDisplay = true;

        final DisplayRotation displayRotation = new DisplayRotation(
                displayContent, policy, context);
        displayRotation.mPortraitRotation = Surface.ROTATION_0;
        displayRotation.mLandscapeRotation = Surface.ROTATION_90;
        displayRotation.mUpsideDownRotation = Surface.ROTATION_180;
        displayRotation.mSeascapeRotation = Surface.ROTATION_270;

        when(displayContent.getDisplayRotation()).thenReturn(displayRotation);

        return displayContent;
    }
}
