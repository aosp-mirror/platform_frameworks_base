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

import android.graphics.Rect;

/**
 * These callbacks are used to communicate window configuration changes while the user is performing
 * window changes.
 * @hide
 */
public interface WindowCallbacks {
    /**
     * Called by the system when the window got changed by the user, before the layouter got called.
     * It can be used to perform a "quick and dirty" resize which should never take more then 4ms to
     * complete.
     *
     * <p>At the time the layouting has not happened yet.
     *
     * @param newBounds The new window frame bounds.
     */
    void onWindowSizeIsChanging(Rect newBounds);

    /**
     * Called when a drag resize starts.
     * @param initialBounds The initial bounds where the window will be.
     */
    void onWindowDragResizeStart(Rect initialBounds);

    /**
     * Called when a drag resize ends.
     */
    void onWindowDragResizeEnd();
}
