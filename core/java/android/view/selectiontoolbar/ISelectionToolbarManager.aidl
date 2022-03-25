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

import android.view.selectiontoolbar.ISelectionToolbarCallback;
import android.view.selectiontoolbar.ShowInfo;

/**
 * Mediator between apps and selection toolbar service implementation.
 *
 * @hide
 */
oneway interface ISelectionToolbarManager {
    void showToolbar(in ShowInfo showInfo, in ISelectionToolbarCallback callback, int userId);
    void hideToolbar(long widgetToken, int userId);
    void dismissToolbar(long widgetToken, int userId);
}