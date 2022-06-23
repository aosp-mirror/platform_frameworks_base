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

package android.view;

import android.app.WindowConfiguration.WindowingMode;
import android.graphics.Rect;
import android.window.ClientWindowFrames;

/**
 * Computes window frames for the windowless window.
 * @hide
 */
public class WindowlessWindowLayout extends WindowLayout {

    @Override
    public void computeFrames(WindowManager.LayoutParams attrs, InsetsState state,
            Rect displayCutoutSafe, Rect windowBounds, @WindowingMode int windowingMode,
            int requestedWidth, int requestedHeight, InsetsVisibilities requestedVisibilities,
            float compatScale, ClientWindowFrames frames) {
        frames.frame.set(0, 0, attrs.width, attrs.height);
        frames.displayFrame.set(frames.frame);
        frames.parentFrame.set(frames.frame);
    }
}

