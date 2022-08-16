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

import android.app.ILocalWallpaperColorConsumer;
import android.app.WallpaperColors;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.os.Bundle;

/**
 * @hide
 */
interface IWallpaperEngine {
    oneway void setDesiredSize(int width, int height);
    oneway void setDisplayPadding(in Rect padding);
    @UnsupportedAppUsage
    oneway void setVisibility(boolean visible);
    oneway void setInAmbientMode(boolean inAmbientDisplay, long animationDuration);
    @UnsupportedAppUsage
    oneway void dispatchPointer(in MotionEvent event);
    @UnsupportedAppUsage
    oneway void dispatchWallpaperCommand(String action, int x, int y,
            int z, in Bundle extras);
    oneway void requestWallpaperColors();
    @UnsupportedAppUsage
    oneway void destroy();
    oneway void setZoomOut(float scale);
    oneway void scalePreview(in Rect positionInWindow);
    oneway void removeLocalColorsAreas(in List<RectF> regions);
    oneway void addLocalColorsAreas(in List<RectF> regions);
    SurfaceControl mirrorSurfaceControl();
    oneway void applyDimming(float dimAmount);
}
