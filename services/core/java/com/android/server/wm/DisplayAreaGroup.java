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

package com.android.server.wm;

import static android.content.pm.ActivityInfo.reverseOrientation;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;

/** The root of a partition of the logical display. */
class DisplayAreaGroup extends RootDisplayArea {

    DisplayAreaGroup(WindowManagerService wms, String name, int featureId) {
        super(wms, name, featureId);
    }

    @Override
    boolean isOrientationDifferentFromDisplay() {
        return isOrientationDifferentFromDisplay(getBounds());
    }

    /**
     * Whether the orientation should be different from the {@link DisplayContent}.
     * @param bounds the bounds of this DAG.
     */
    private boolean isOrientationDifferentFromDisplay(Rect bounds) {
        if (mDisplayContent == null) {
            return false;
        }

        final Rect displayBounds = mDisplayContent.getBounds();
        return (bounds.width() < bounds.height())
                != (displayBounds.width() < displayBounds.height());
    }

    @ActivityInfo.ScreenOrientation
    @Override
    int getOrientation(int candidate) {
        int orientation = super.getOrientation(candidate);

        // Reverse the requested orientation if the orientation of this DAG is different from the
        // display, so that when the display rotates to the reversed orientation, this DAG will be
        // in the requested orientation, so as the requested app.
        // For example, if the display is 1200x900 (landscape), and this DAG is 600x900 (portrait).
        // When an app below this DAG is requesting landscape, it should actually request the
        // display to be portrait, so that the DAG and the app will be in landscape.
        return isOrientationDifferentFromDisplay() ? reverseOrientation(orientation) : orientation;
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfiguration) {
        super.resolveOverrideConfiguration(newParentConfiguration);
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();
        if (resolvedConfig.orientation != ORIENTATION_UNDEFINED) {
            // Don't change the orientation if it is requested on this window.
            return;
        }

        // Use the override bounds because getBounds() may not be merged yet.
        Rect overrideBounds = resolvedConfig.windowConfiguration.getBounds();
        // It should fill parent if there is no override bounds.
        overrideBounds = overrideBounds.isEmpty()
                ? newParentConfiguration.windowConfiguration.getBounds()
                : overrideBounds;
        // Resolve the DAG orientation:
        // If the orientation of this DAG should always be different from the display based on their
        // dimensions, we need to reverse the config orientation.
        // For example, if the display is 1200x900 (landscape), and this DAG is 600x900 (portrait).
        // The orientation from the Display will be landscape, but we want to reverse it to be
        // portrait for the DAG and its children.
        if (isOrientationDifferentFromDisplay(overrideBounds)) {
            if (newParentConfiguration.orientation == ORIENTATION_PORTRAIT) {
                resolvedConfig.orientation = ORIENTATION_LANDSCAPE;
            } else if (newParentConfiguration.orientation == ORIENTATION_LANDSCAPE) {
                resolvedConfig.orientation = ORIENTATION_PORTRAIT;
            }
        }
    }
}
