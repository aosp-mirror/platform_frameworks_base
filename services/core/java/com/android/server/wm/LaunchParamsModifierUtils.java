/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.util.Size;
import android.view.Gravity;

class LaunchParamsModifierUtils {

    /**
     * Calculates bounds based on window layout size manifest values. These can include width,
     * height, width fraction and height fraction. In the event only one dimension of values are
     * specified in the manifest (e.g. width but no height value), the corresponding display area
     * dimension will be used as the default value unless some desired sizes have been specified.
     */
    static void calculateLayoutBounds(@NonNull Rect stableBounds,
            @NonNull ActivityInfo.WindowLayout windowLayout, @NonNull Rect inOutBounds,
            @Nullable Size desiredSize) {
        final int defaultWidth = stableBounds.width();
        final int defaultHeight = stableBounds.height();
        int width;
        int height;

        if (desiredSize == null) {
            // If desired bounds have not been specified, use the exiting default bounds as the
            // desired.
            desiredSize = new Size(stableBounds.width(), stableBounds.height());
        }

        width = desiredSize.getWidth();
        if (windowLayout.width > 0 && windowLayout.width < defaultWidth) {
            width = windowLayout.width;
        } else if (windowLayout.widthFraction > 0 && windowLayout.widthFraction < 1.0f) {
            width = (int) (defaultWidth * windowLayout.widthFraction);
        }

        height = desiredSize.getHeight();
        if (windowLayout.height > 0 && windowLayout.height < defaultHeight) {
            height = windowLayout.height;
        } else if (windowLayout.heightFraction > 0 && windowLayout.heightFraction < 1.0f) {
            height = (int) (defaultHeight * windowLayout.heightFraction);
        }

        inOutBounds.set(0, 0, width, height);
    }

    /**
     * Applies a vertical and horizontal gravity on the inOutBounds in relation to the stableBounds.
     */
    static void applyLayoutGravity(int verticalGravity, int horizontalGravity,
            @NonNull Rect inOutBounds, @NonNull Rect stableBounds) {
        final int width = inOutBounds.width();
        final int height = inOutBounds.height();

        final float fractionOfHorizontalOffset;
        switch (horizontalGravity) {
            case Gravity.LEFT:
                fractionOfHorizontalOffset = 0f;
                break;
            case Gravity.RIGHT:
                fractionOfHorizontalOffset = 1f;
                break;
            default:
                fractionOfHorizontalOffset = 0.5f;
        }

        final float fractionOfVerticalOffset;
        switch (verticalGravity) {
            case Gravity.TOP:
                fractionOfVerticalOffset = 0f;
                break;
            case Gravity.BOTTOM:
                fractionOfVerticalOffset = 1f;
                break;
            default:
                fractionOfVerticalOffset = 0.5f;
        }

        inOutBounds.offsetTo(stableBounds.left, stableBounds.top);
        final int xOffset = (int) (fractionOfHorizontalOffset * (stableBounds.width() - width));
        final int yOffset = (int) (fractionOfVerticalOffset * (stableBounds.height() - height));
        inOutBounds.offset(xOffset, yOffset);
    }
}
