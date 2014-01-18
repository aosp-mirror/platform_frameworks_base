/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.hardware.display;

import android.graphics.Rect;

/**
 * Describes how the pixels of physical display device reflects the content of
 * a logical display.
 * <p>
 * This information is used by the input system to translate touch input from
 * physical display coordinates into logical display coordinates.
 * </p>
 *
 * @hide Only for use within the system server.
 */
public final class DisplayViewport {
    // True if this viewport is valid.
    public boolean valid;

    // The logical display id.
    public int displayId;

    // The rotation applied to the physical coordinate system.
    public int orientation;

    // The portion of the logical display that are presented on this physical display.
    public final Rect logicalFrame = new Rect();

    // The portion of the (rotated) physical display that shows the logical display contents.
    // The relation between logical and physical frame defines how the coordinate system
    // should be scaled or translated after rotation.
    public final Rect physicalFrame = new Rect();

    // The full width and height of the display device, rotated in the same
    // manner as physicalFrame.  This expresses the full native size of the display device.
    // The physical frame should usually fit within this area.
    public int deviceWidth;
    public int deviceHeight;

    public void copyFrom(DisplayViewport viewport) {
        valid = viewport.valid;
        displayId = viewport.displayId;
        orientation = viewport.orientation;
        logicalFrame.set(viewport.logicalFrame);
        physicalFrame.set(viewport.physicalFrame);
        deviceWidth = viewport.deviceWidth;
        deviceHeight = viewport.deviceHeight;
    }

    // For debugging purposes.
    @Override
    public String toString() {
        return "DisplayViewport{valid=" + valid
                + ", displayId=" + displayId
                + ", orientation=" + orientation
                + ", logicalFrame=" + logicalFrame
                + ", physicalFrame=" + physicalFrame
                + ", deviceWidth=" + deviceWidth
                + ", deviceHeight=" + deviceHeight
                + "}";
    }
}
