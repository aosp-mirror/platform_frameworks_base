/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.app.WallpaperColors;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.ArrayMap;
import android.util.ArraySet;

import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * This class represents a page of a launcher page used by the wallpaper
 * @hide
 */
public class EngineWindowPage {
    private Bitmap mScreenShot;
    private volatile long  mLastUpdateTime = 0;
    private Set<RectF> mCallbackAreas = new ArraySet<>();
    private Map<RectF, WallpaperColors> mRectFColors = new ArrayMap<>();

    /** should be locked extrnally */
    public void addArea(RectF area) {
        mCallbackAreas.add(area);
    }

    /** should be locked extrnally */
    public void addWallpaperColors(RectF area, WallpaperColors colors) {
        mCallbackAreas.add(area);
        mRectFColors.put(area, colors);
    }

    /** get screenshot bitmap */
    public Bitmap getBitmap() {
        if (mScreenShot == null || mScreenShot.isRecycled()) return null;
        return mScreenShot;
    }

    /** remove callbacks for an area */
    public void removeArea(RectF area) {
        mCallbackAreas.remove(area);
        mRectFColors.remove(area);
    }

    /** set the last time the screenshot was updated */
    public void setLastUpdateTime(long lastUpdateTime) {
        mLastUpdateTime = lastUpdateTime;
    }

    /** get last screenshot time */
    public long getLastUpdateTime() {
        return mLastUpdateTime;
    }

    /** get colors for an area */
    public WallpaperColors getColors(RectF rect) {
        return mRectFColors.get(rect);
    }

    /** set the new bitmap version */
    public void setBitmap(Bitmap screenShot) {
        mScreenShot = screenShot;
    }

    /** get areas of interest */
    public Set<RectF> getAreas() {
        return mCallbackAreas;
    }

    /** run operations on this page */
    public synchronized void execSync(Consumer<EngineWindowPage> run) {
        run.accept(this);
    }

    /** nullify the area color */
    public void removeColor(RectF colorArea) {
        mRectFColors.remove(colorArea);
    }
}
