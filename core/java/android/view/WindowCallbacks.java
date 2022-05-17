/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.view;

import android.graphics.RecordingCanvas;
import android.graphics.Rect;

/**
 * These callbacks are used to communicate window configuration changes while the user is performing
 * window changes.
 * Note: Note that at the time of onWindowDragResizeStart the content size isn't known. A consumer
 * should therfore not draw anything before the additional onContentDraw call has arrived.
 * @hide
 */
public interface WindowCallbacks {

    int RESIZE_MODE_INVALID = -1;

    /**
     * The window is being resized by dragging one of the window corners,
     * in this case the surface would be fullscreen-sized. The client should
     * render to the actual frame location (instead of (0,curScrollY)).
     */
    int RESIZE_MODE_FREEFORM = 0;

    /**
     * The window is being resized by dragging on the docked divider. The client should render
     * at (0, 0) and extend its background to the background frame passed into
     * {@link IWindow#resized}.
     */
    int RESIZE_MODE_DOCKED_DIVIDER = 1;

    /**
     * Called by the system when the window got changed by the user, before the layouter got called.
     * It also gets called when the insets changed, or when the window switched between a fullscreen
     * layout or a non-fullscreen layout. It can be used to perform a "quick and dirty" resize which
     * should never take more then 4ms to complete.
     *
     * <p>At the time the layouting has not happened yet.
     *
     * @param newBounds The new window frame bounds.
     * @param fullscreen Whether the window is currently drawing in fullscreen.
     * @param systemInsets The current visible system insets for the window.
     * @param stableInsets The stable insets for the window.
     */
    void onWindowSizeIsChanging(Rect newBounds, boolean fullscreen, Rect systemInsets,
            Rect stableInsets);

    /**
     * Called when a drag resize starts.
     *
     * @param initialBounds The initial bounds where the window will be.
     * @param fullscreen Whether the window is currently drawing in fullscreen.
     * @param systemInsets The current visible system insets for the window.
     * @param stableInsets The stable insets for the window.
     */
    void onWindowDragResizeStart(Rect initialBounds, boolean fullscreen, Rect systemInsets,
            Rect stableInsets, int resizeMode);

    /**
     * Called when a drag resize ends.
     */
    void onWindowDragResizeEnd();

    /**
     * The content will now be drawn to these bounds. Returns true if
     * a draw should be requested after the next content draw.
     */
    boolean onContentDrawn(int offsetX, int offsetY, int sizeX, int sizeY);

    /**
     * Called to request the window to draw one frame.
     * @param reportNextDraw Whether it should report when the requested draw finishes.
     */
    void onRequestDraw(boolean reportNextDraw);

    /**
     * Called after all the content has drawn and the callback now has the ability to draw something
     * on top of everything. Call {@link ViewRootImpl#requestInvalidateRootRenderNode} when this
     * content needs to be redrawn.
     *
     * @param canvas The canvas to draw on.
     */
    void onPostDraw(RecordingCanvas canvas);
}
