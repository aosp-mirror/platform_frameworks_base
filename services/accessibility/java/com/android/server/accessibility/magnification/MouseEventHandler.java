/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.accessibility.magnification;

import static android.view.InputDevice.SOURCE_MOUSE;
import static android.view.MotionEvent.ACTION_HOVER_MOVE;
import static android.view.MotionEvent.ACTION_MOVE;

import android.view.MotionEvent;

import com.android.server.accessibility.AccessibilityManagerService;

/** MouseEventHandler handles mouse and stylus events that should move the viewport. */
public final class MouseEventHandler {
    private final FullScreenMagnificationController mFullScreenMagnificationController;

    public MouseEventHandler(FullScreenMagnificationController fullScreenMagnificationController) {
        mFullScreenMagnificationController = fullScreenMagnificationController;
    }

    /**
     * Handles a mouse or stylus event, moving the magnifier if needed.
     *
     * @param event The mouse or stylus MotionEvent to consume
     * @param displayId The display that is being magnified
     */
    public void onEvent(MotionEvent event, int displayId) {
        // Ignore gesture events synthesized from the touchpad.
        // TODO(b/354696546): Use synthesized pinch gestures to control scale.
        boolean isSynthesizedFromTouchpad =
                event.getClassification() != MotionEvent.CLASSIFICATION_NONE;

        // Consume only move events from the mouse or hovers from any tool.
        if (!isSynthesizedFromTouchpad && (event.getAction() == ACTION_HOVER_MOVE
                || (event.getAction() == ACTION_MOVE && event.getSource() == SOURCE_MOUSE))) {
            final float eventX = event.getX();
            final float eventY = event.getY();

            // Only move the viewport when over a magnified region.
            // TODO(b/354696546): Ensure this doesn't stop the viewport from reaching the
            // corners and edges at high levels of magnification.
            if (mFullScreenMagnificationController.magnificationRegionContains(
                    displayId, eventX, eventY)) {
                mFullScreenMagnificationController.setCenter(
                        displayId,
                        eventX,
                        eventY,
                        /* animate= */ false,
                        AccessibilityManagerService.MAGNIFICATION_GESTURE_HANDLER_ID);
            }
        }
    }
}
