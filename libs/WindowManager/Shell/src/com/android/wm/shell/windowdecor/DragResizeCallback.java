/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.windowdecor;

/**
 * Callback called when receiving drag-resize or drag-move related input events.
 */
public interface DragResizeCallback {
    /**
     * Called when a drag resize starts.
     *
     * @param ctrlType {@link TaskPositioner.CtrlType} indicating the direction of resizing, use
     *                 {@code 0} to indicate it's a move
     * @param x x coordinate in window decoration coordinate system where the drag resize starts
     * @param y y coordinate in window decoration coordinate system where the drag resize starts
     */
    void onDragResizeStart(@TaskPositioner.CtrlType int ctrlType, float x, float y);

    /**
     * Called when the pointer moves during a drag resize.
     * @param x x coordinate in window decoration coordinate system of the new pointer location
     * @param y y coordinate in window decoration coordinate system of the new pointer location
     */
    void onDragResizeMove(float x, float y);

    /**
     * Called when a drag resize stops.
     * @param x x coordinate in window decoration coordinate system where the drag resize stops
     * @param y y coordinate in window decoration coordinate system where the drag resize stops
     */
    void onDragResizeEnd(float x, float y);
}
