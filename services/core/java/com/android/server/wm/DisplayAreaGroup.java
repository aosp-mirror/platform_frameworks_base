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

import android.content.pm.ActivityInfo;
import android.graphics.Rect;

/** The root of a partition of the logical display. */
class DisplayAreaGroup extends RootDisplayArea {

    DisplayAreaGroup(WindowManagerService wms, String name, int featureId) {
        super(wms, name, featureId);
    }

    @Override
    boolean isOrientationDifferentFromDisplay() {
        if (mDisplayContent == null) {
            return false;
        }

        final Rect bounds = getBounds();
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
}
