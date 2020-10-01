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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.text.TextUtils;

import java.lang.annotation.Retention;

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

    // Viewport constants defined in InputReader.h.
    public static final int VIEWPORT_INTERNAL = 1;
    public static final int VIEWPORT_EXTERNAL = 2;
    public static final int VIEWPORT_VIRTUAL = 3;
    @IntDef(prefix = { "VIEWPORT_" }, value = {
            VIEWPORT_INTERNAL, VIEWPORT_EXTERNAL, VIEWPORT_VIRTUAL})
    @Retention(SOURCE)
    public @interface ViewportType {};

    // True if this viewport is valid.
    public boolean valid;

    // True if this viewport is active.
    public boolean isActive;

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

    // The ID used to uniquely identify this display.
    public String uniqueId;

    // The physical port that the associated display device is connected to.
    public @Nullable Byte physicalPort;

    public @ViewportType int type;

    public void copyFrom(DisplayViewport viewport) {
        valid = viewport.valid;
        isActive = viewport.isActive;
        displayId = viewport.displayId;
        orientation = viewport.orientation;
        logicalFrame.set(viewport.logicalFrame);
        physicalFrame.set(viewport.physicalFrame);
        deviceWidth = viewport.deviceWidth;
        deviceHeight = viewport.deviceHeight;
        uniqueId = viewport.uniqueId;
        physicalPort = viewport.physicalPort;
        type = viewport.type;
    }

    /**
     * Creates a copy of this DisplayViewport.
     */
    public DisplayViewport makeCopy() {
        DisplayViewport dv = new DisplayViewport();
        dv.copyFrom(this);
        return dv;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        if (!(o instanceof DisplayViewport)) {
            return false;
        }

        DisplayViewport other = (DisplayViewport) o;
        return valid == other.valid
              && isActive == other.isActive
              && displayId == other.displayId
              && orientation == other.orientation
              && logicalFrame.equals(other.logicalFrame)
              && physicalFrame.equals(other.physicalFrame)
              && deviceWidth == other.deviceWidth
              && deviceHeight == other.deviceHeight
              && TextUtils.equals(uniqueId, other.uniqueId)
              && physicalPort == other.physicalPort
              && type == other.type;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result += prime * result + (valid ? 1 : 0);
        result += prime * result + (isActive ? 1 : 0);
        result += prime * result + displayId;
        result += prime * result + orientation;
        result += prime * result + logicalFrame.hashCode();
        result += prime * result + physicalFrame.hashCode();
        result += prime * result + deviceWidth;
        result += prime * result + deviceHeight;
        result += prime * result + uniqueId.hashCode();
        if (physicalPort != null) {
            result += prime * result + physicalPort.hashCode();
        }
        result += prime * result + type;
        return result;
    }

    // For debugging purposes.
    @Override
    public String toString() {
        final Integer port = physicalPort == null ? null : Byte.toUnsignedInt(physicalPort);
        return "DisplayViewport{type=" + typeToString(type)
                + ", valid=" + valid
                + ", isActive=" + isActive
                + ", displayId=" + displayId
                + ", uniqueId='" + uniqueId + "'"
                + ", physicalPort=" + port
                + ", orientation=" + orientation
                + ", logicalFrame=" + logicalFrame
                + ", physicalFrame=" + physicalFrame
                + ", deviceWidth=" + deviceWidth
                + ", deviceHeight=" + deviceHeight
                + "}";
    }

    /**
     * Human-readable viewport type.
     */
    public static String typeToString(@ViewportType int viewportType) {
        switch (viewportType) {
            case VIEWPORT_INTERNAL:
                return "INTERNAL";
            case VIEWPORT_EXTERNAL:
                return "EXTERNAL";
            case VIEWPORT_VIRTUAL:
                return "VIRTUAL";
            default:
                return "UNKNOWN (" + viewportType + ")";
        }
    }
}
