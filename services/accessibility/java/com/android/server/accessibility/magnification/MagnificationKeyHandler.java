/*
 * Copyright (C) 2025 The Android Open Source Project
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

import android.view.Display;
import android.view.KeyEvent;

import com.android.server.accessibility.BaseEventStreamTransformation;
import com.android.server.accessibility.Flags;

/*
 * A class that listens to key presses used to control magnification.
 */
public class MagnificationKeyHandler extends BaseEventStreamTransformation {

    /** Callback interface to report that a user is intending to interact with Magnification. */
    public interface Callback {
        /**
         * Called when a keyboard shortcut to pan magnification in direction {@code direction} is
         * pressed by a user. Note that this can be called for multiple directions if multiple
         * arrows are pressed at the same time (e.g. diagonal panning).
         *
         * @param displayId The logical display ID
         * @param direction The direction to start panning
         */
        void onPanMagnificationStart(int displayId,
                @MagnificationController.PanDirection int direction);

        /**
         * Called when a keyboard shortcut to pan magnification in direction {@code direction} is
         * unpressed by a user. Note that this can be called for multiple directions if multiple
         * arrows had been pressed at the same time (e.g. diagonal panning).
         *
         * @param displayId The logical display ID
         */
        void onPanMagnificationStop(int displayId);

        /**
         * Called when a keyboard shortcut to scale magnification in direction `direction` is
         * pressed by a user.
         *
         * @param displayId The logical display ID
         * @param direction The direction in which scaling started
         */
        void onScaleMagnificationStart(int displayId,
                @MagnificationController.ZoomDirection int direction);

        /**
         * Called when a keyboard shortcut to scale magnification in direction `direction` is
         * unpressed by a user.
         *
         * @param direction The direction in which scaling stopped
         */
        void onScaleMagnificationStop(@MagnificationController.ZoomDirection int direction);

        /**
         * Called when all keyboard interaction with magnification should be stopped.
         */
        void onKeyboardInteractionStop();
    }

    protected final MagnificationKeyHandler.Callback mCallback;
    private boolean mIsKeyboardInteracting = false;

    public MagnificationKeyHandler(Callback callback) {
        mCallback = callback;
    }

    @Override
    public void onKeyEvent(KeyEvent event, int policyFlags) {
        if (!Flags.enableMagnificationKeyboardControl()) {
            // Send to the rest of the handlers.
            super.onKeyEvent(event, policyFlags);
            return;
        }
        boolean modifiersPressed = event.isAltPressed() && event.isMetaPressed();
        if (!modifiersPressed) {
            super.onKeyEvent(event, policyFlags);
            if (mIsKeyboardInteracting) {
                // When modifier keys are no longer pressed, ensure that scaling and
                // panning are fully stopped.
                mCallback.onKeyboardInteractionStop();
                mIsKeyboardInteracting = false;
            }
            return;
        }
        boolean isDown = event.getAction() == KeyEvent.ACTION_DOWN;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int panDirection = switch(keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT -> MagnificationController.PAN_DIRECTION_LEFT;
                case KeyEvent.KEYCODE_DPAD_RIGHT -> MagnificationController.PAN_DIRECTION_RIGHT;
                case KeyEvent.KEYCODE_DPAD_UP -> MagnificationController.PAN_DIRECTION_UP;
                default -> MagnificationController.PAN_DIRECTION_DOWN;
            };
            if (isDown) {
                mCallback.onPanMagnificationStart(getDisplayId(event), panDirection);
                mIsKeyboardInteracting = true;
            } else {
                mCallback.onPanMagnificationStop(panDirection);
            }
            return;
        } else if (keyCode == KeyEvent.KEYCODE_EQUALS || keyCode == KeyEvent.KEYCODE_MINUS) {
            int zoomDirection = MagnificationController.ZOOM_DIRECTION_OUT;
            if (keyCode == KeyEvent.KEYCODE_EQUALS) {
                zoomDirection = MagnificationController.ZOOM_DIRECTION_IN;
            }
            if (isDown) {
                mCallback.onScaleMagnificationStart(getDisplayId(event), zoomDirection);
                mIsKeyboardInteracting = true;
            } else {
                mCallback.onScaleMagnificationStop(zoomDirection);
            }
            return;
        }

        // Continue down the eventing chain if this was unused.
        super.onKeyEvent(event, policyFlags);
    }

    private int getDisplayId(KeyEvent event) {
        // Display ID may be invalid, e.g. for external keyboard attached to phone.
        // In that case, use the default display.
        if (event.getDisplayId() != Display.INVALID_DISPLAY) {
            return event.getDisplayId();
        }
        return Display.DEFAULT_DISPLAY;
    }
}
