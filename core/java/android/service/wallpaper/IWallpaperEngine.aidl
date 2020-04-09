/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.service.wallpaper;

import android.graphics.Rect;
import android.view.MotionEvent;
import android.os.Bundle;

/**
 * @hide
 */
oneway interface IWallpaperEngine {
    void setDesiredSize(int width, int height);
    void setDisplayPadding(in Rect padding);
    @UnsupportedAppUsage
    void setVisibility(boolean visible);
    void setInAmbientMode(boolean inAmbientDisplay, long animationDuration);
    @UnsupportedAppUsage
    void dispatchPointer(in MotionEvent event);
    @UnsupportedAppUsage
    void dispatchWallpaperCommand(String action, int x, int y,
            int z, in Bundle extras);
    void requestWallpaperColors();
    @UnsupportedAppUsage
    void destroy();
    void setZoomOut(float scale);
    void scalePreview(in Rect positionInWindow);
}
