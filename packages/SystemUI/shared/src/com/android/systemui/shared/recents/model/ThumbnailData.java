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

package com.android.systemui.shared.recents.model;

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;

import static com.android.systemui.shared.system.WindowManagerWrapper.WINDOWING_MODE_UNDEFINED;

import android.app.ActivityManager.TaskSnapshot;
import android.graphics.Bitmap;
import android.graphics.Rect;

/**
 * Data for a single thumbnail.
 */
public class ThumbnailData {

    public final Bitmap thumbnail;
    public int orientation;
    public int rotation;
    public Rect insets;
    public boolean reducedResolution;
    public boolean isRealSnapshot;
    public boolean isTranslucent;
    public int windowingMode;
    public int systemUiVisibility;
    public float scale;
    public long snapshotId;

    public ThumbnailData() {
        thumbnail = null;
        orientation = ORIENTATION_UNDEFINED;
        rotation = ROTATION_UNDEFINED;
        insets = new Rect();
        reducedResolution = false;
        scale = 1f;
        isRealSnapshot = true;
        isTranslucent = false;
        windowingMode = WINDOWING_MODE_UNDEFINED;
        systemUiVisibility = 0;
        snapshotId = 0;
    }

    public ThumbnailData(TaskSnapshot snapshot) {
        thumbnail = Bitmap.wrapHardwareBuffer(snapshot.getHardwareBuffer(),
                snapshot.getColorSpace());
        insets = new Rect(snapshot.getContentInsets());
        orientation = snapshot.getOrientation();
        rotation = snapshot.getRotation();
        reducedResolution = snapshot.isLowResolution();
        // TODO(b/149579527): Pass task size instead of computing scale.
        // Assume width and height were scaled the same; compute scale only for width
        scale = (float) thumbnail.getWidth() / snapshot.getTaskSize().x;
        isRealSnapshot = snapshot.isRealSnapshot();
        isTranslucent = snapshot.isTranslucent();
        windowingMode = snapshot.getWindowingMode();
        systemUiVisibility = snapshot.getSystemUiVisibility();
        snapshotId = snapshot.getId();
    }
}
