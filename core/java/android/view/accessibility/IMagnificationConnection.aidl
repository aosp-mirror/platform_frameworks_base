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

package android.view.accessibility;

import android.graphics.PointF;
import android.graphics.Rect;
import android.view.accessibility.IMagnificationConnectionCallback;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;

/**
 * Interface for interaction between {@link AccessibilityManagerService}
 * and {@link Magnification} in SystemUI.
 *
 * @hide
 */
oneway interface IMagnificationConnection {

    /**
     * Enables window magnification on specified display with given center and scale and animation.
     *
     * @param displayId the logical display id.
     * @param scale magnification scale.
     * @param centerX the screen-relative X coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY the screen-relative Y coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     * @param magnificationFrameOffsetRatioX Indicate the X coordinate offset between
     *                                       frame position X and centerX
     * @param magnificationFrameOffsetRatioY Indicate the Y coordinate offset between
     *                                       frame position Y and centerY
     * @param callback The callback called when the animation is completed or interrupted.
     */
    void enableWindowMagnification(int displayId, float scale, float centerX, float centerY,
        float magnificationFrameOffsetRatioX, float magnificationFrameOffsetRatioY,
        in IRemoteMagnificationAnimationCallback callback);

    /**
     * Sets the scale of the window magnifier on specified display.
     *
     * @param displayId the logical display id.
     * @param scale magnification scale.
     */
    void setScaleForWindowMagnification(int displayId, float scale);

     /**
     * Disables window magnification on specified display with animation.
     *
     * @param displayId the logical display id.
     * @param callback The callback called when the animation is completed or interrupted.
     */
    void disableWindowMagnification(int displayId,
        in IRemoteMagnificationAnimationCallback callback);

    /**
     * Moves the window magnifier on the specified display. It has no effect while animating.
     *
     * @param displayId the logical display id.
     * @param offsetX the amount in pixels to offset the window magnifier in the X direction, in
     *                current screen pixels.
     * @param offsetY the amount in pixels to offset the window magnifier in the Y direction, in
     *                current screen pixels.
     */
    void moveWindowMagnifier(int displayId, float offsetX, float offsetY);

    /**
     * Moves the window magnifier on the given display.
     *
     * @param displayId the logical display id.
     * @param positionX the x-axis position of the center of the magnified source bounds.
     * @param positionY the y-axis position of the center of the magnified source bounds.
     * @param callback the callback called when the animation is completed or interrupted.
     */
    void moveWindowMagnifierToPosition(int displayId, float positionX, float positionY,
        in IRemoteMagnificationAnimationCallback callback);

    /**
     * Requests System UI show magnification mode button UI on the specified display.
     *
     * @param displayId the logical display id.
     * @param magnificationMode the current magnification mode.
     */
    void showMagnificationButton(int displayId, int magnificationMode);

    /**
     * Requests System UI remove magnification mode button UI on the specified display.
     *
     * @param displayId the logical display id.
     */
    void removeMagnificationButton(int displayId);

    /**
     * Requests System UI remove magnification settings panel on the specified display.
     *
     * @param displayId the logical display id.
     */
    void removeMagnificationSettingsPanel(int displayId);

    /**
     * Sets {@link IMagnificationConnectionCallback} to receive the request or the callback.
     *
     * @param callback the interface to be called.
     */
    void setConnectionCallback(in IMagnificationConnectionCallback callback);

    /**
     * Notify System UI the magnification scale on the specified display for userId is changed.
     *
     * @param userId the user id.
     * @param displayId the logical display id.
     * @param scale magnification scale.
     */
    void onUserMagnificationScaleChanged(int userId, int displayId, float scale);

    /**
     * Notify the changes of fullscreen magnification activation on the specified display
     */
    void onFullscreenMagnificationActivationChanged(int displayId, boolean activated);
}
