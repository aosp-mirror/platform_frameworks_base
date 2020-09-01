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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;

/**
 * Describes the mode in which a window is drag resizing.
 */
class DragResizeMode {

    /**
     * Freeform mode: Client surface is fullscreen, and client is responsible to draw window at
     * the correct position.
     */
    static final int DRAG_RESIZE_MODE_FREEFORM = 0;

    /**
     * Mode for resizing the docked (and adjacent) stack: Client surface is fullscreen, but window
     * is drawn at (0, 0), window manager is responsible for positioning the surface when draging.
     */
    static final int DRAG_RESIZE_MODE_DOCKED_DIVIDER = 1;

    static boolean isModeAllowedForStack(ActivityStack stack, int mode) {
        switch (mode) {
            case DRAG_RESIZE_MODE_FREEFORM:
                return stack.getWindowingMode() == WINDOWING_MODE_FREEFORM;
            case DRAG_RESIZE_MODE_DOCKED_DIVIDER:
                return stack.inSplitScreenWindowingMode();
            default:
                return false;
        }
    }
}
