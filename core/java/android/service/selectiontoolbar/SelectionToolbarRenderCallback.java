/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.service.selectiontoolbar;

import android.view.selectiontoolbar.ToolbarMenuItem;
import android.view.selectiontoolbar.WidgetInfo;

/**
 * The callback that the render service uses to communicate with the host of the selection toolbar
 * container.
 *
 * @hide
 */
public interface SelectionToolbarRenderCallback {
    /**
     * The selection toolbar is shown.
     */
    void onShown(WidgetInfo widgetInfo);
    /**
     * The selection toolbar has changed.
     */
    void onWidgetUpdated(WidgetInfo info);
    /**
     * The menu item on the selection toolbar has been clicked.
     */
    void onMenuItemClicked(ToolbarMenuItem item);
    /**
     * The toolbar doesn't be dismissed after showing on a given timeout.
     */
    void onToolbarShowTimeout();
    /**
     * The error occurred when operating on the selection toolbar.
     */
    void onError(int errorCode);
}
