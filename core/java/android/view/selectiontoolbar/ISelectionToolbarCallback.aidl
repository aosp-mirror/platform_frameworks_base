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

package android.view.selectiontoolbar;

import android.view.selectiontoolbar.ToolbarMenuItem;
import android.view.selectiontoolbar.WidgetInfo;

/**
 * Binder interface to notify the selection toolbar events from one process to the other.
 * @hide
 */
oneway interface ISelectionToolbarCallback {
    void onShown(in WidgetInfo info);
    void onWidgetUpdated(in WidgetInfo info);
    void onToolbarShowTimeout();
    void onMenuItemClicked(in ToolbarMenuItem item);
    void onError(int errorCode);
}
