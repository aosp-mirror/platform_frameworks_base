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
 * limitations under the License.
 */

package com.android.systemui.recents.model;

import android.app.ActivityManager.TaskSnapshot;
import android.graphics.Bitmap;
import android.graphics.Rect;

/**
 * Data for a single thumbnail.
 */
public class ThumbnailData {

    // TODO: Make these final once the non-snapshot path is removed.
    public Bitmap thumbnail;
    public int orientation;
    public final Rect insets = new Rect();
    public boolean reducedResolution;
    public float scale;

    public static ThumbnailData createFromTaskSnapshot(TaskSnapshot snapshot) {
        ThumbnailData out = new ThumbnailData();
        out.thumbnail = Bitmap.createHardwareBitmap(snapshot.getSnapshot());
        out.insets.set(snapshot.getContentInsets());
        out.orientation = snapshot.getOrientation();
        out.reducedResolution = snapshot.isReducedResolution();
        out.scale = snapshot.getScale();
        return out;
    }
}
