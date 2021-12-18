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

package com.android.server.selectiontoolbar;

import android.service.selectiontoolbar.SelectionToolbarRenderService;
import android.util.Log;
import android.view.selectiontoolbar.ShowInfo;

/**
 * The default implementation of {@link SelectionToolbarRenderService}.
 */
public final class DefaultSelectionToolbarRenderService extends SelectionToolbarRenderService {

    private static final String TAG = "DefaultSelectionToolbarRenderService";

    @Override
    public void onShow(ShowInfo showInfo,
            SelectionToolbarRenderService.RemoteCallbackWrapper callbackWrapper) {
        // TODO: Add implementation
        Log.w(TAG, "onShow()");
    }

    @Override
    public void onHide(long widgetToken) {
        // TODO: Add implementation
        Log.w(TAG, "onHide()");
    }

    @Override
    public void onDismiss(long widgetToken) {
        // TODO: Add implementation
        Log.w(TAG, "onDismiss()");
    }
}
