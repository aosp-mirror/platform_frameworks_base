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
package com.android.systemui.util.display;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.WindowManager;

import javax.inject.Inject;

/**
 * Utility class for working with displays.
 */
public class DisplayHelper {
    private final Context mContext;
    private final DisplayManager mDisplayManager;

    /**
     * Default constructor.
     */
    @Inject
    public DisplayHelper(Context context, DisplayManager displayManager) {
        mContext = context;
        mDisplayManager = displayManager;
    }


    /**
     * Returns the maximum display bounds for the given window context type.
     */
    public Rect getMaxBounds(int displayId, int windowContextType) {
        final Display display = mDisplayManager.getDisplay(displayId);
        WindowManager windowManager = mContext.createDisplayContext(display)
                .createWindowContext(windowContextType, null)
                .getSystemService(WindowManager.class);
        return windowManager.getMaximumWindowMetrics().getBounds();
    }
}
