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

package com.android.systemui.pip;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.view.Surface;

/**
 * Compact {@link WindowConfiguration} for PiP usage and supports operations such as rotate.
 */
class PipWindowConfigurationCompact {
    private @Surface.Rotation int mRotation;
    private Rect mBounds;

    PipWindowConfigurationCompact(WindowConfiguration windowConfiguration) {
        mRotation = windowConfiguration.getRotation();
        mBounds = windowConfiguration.getBounds();
    }

    @Surface.Rotation int getRotation() {
        return mRotation;
    }

    Rect getBounds() {
        return mBounds;
    }

    void syncWithScreenOrientation(@ActivityInfo.ScreenOrientation int screenOrientation,
            @Surface.Rotation int displayRotation) {
        if (mBounds.top != 0 || mBounds.left != 0) {
            // Supports fullscreen bounds like (0, 0, width, height) only now.
            return;
        }
        boolean rotateNeeded = false;
        if (ActivityInfo.isFixedOrientationPortrait(screenOrientation)
                && (mRotation == ROTATION_90 || mRotation == ROTATION_270)) {
            mRotation = ROTATION_0;
            rotateNeeded = true;
        } else if (ActivityInfo.isFixedOrientationLandscape(screenOrientation)
                && (mRotation == ROTATION_0 || mRotation == ROTATION_180)) {
            mRotation = ROTATION_90;
            rotateNeeded = true;
        } else if (screenOrientation == SCREEN_ORIENTATION_UNSPECIFIED
                && mRotation != displayRotation) {
            mRotation = displayRotation;
            rotateNeeded = true;
        }
        if (rotateNeeded) {
            mBounds.set(0, 0, mBounds.height(), mBounds.width());
        }
    }

    @Override
    public String toString() {
        return "PipWindowConfigurationCompact(rotation=" + mRotation
                + " bounds=" + mBounds + ")";
    }
}
