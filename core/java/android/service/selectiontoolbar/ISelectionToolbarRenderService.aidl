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

import android.view.selectiontoolbar.ISelectionToolbarCallback;
import android.view.selectiontoolbar.ShowInfo;

/**
 * The service to render the selection toolbar menus.
 *
 * @hide
 */
oneway interface ISelectionToolbarRenderService {
    void onConnected(in IBinder callback);
    void onShow(int callingUid, in ShowInfo showInfo, in ISelectionToolbarCallback callback);
    void onHide(long widgetToken);
    void onDismiss(int callingUid, long widgetToken);
}
