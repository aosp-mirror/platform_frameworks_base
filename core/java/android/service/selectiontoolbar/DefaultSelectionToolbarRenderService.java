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

import android.util.Log;
import android.view.selectiontoolbar.ShowInfo;

/**
 * The default implementation of {@link SelectionToolbarRenderService}.
 *
 *  @hide
 */
// TODO(b/214122495): fix class not found then move to system service folder
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
