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

import android.annotation.NonNull;
import android.view.selectiontoolbar.ISelectionToolbarCallback;
import android.view.selectiontoolbar.ShowInfo;

import com.android.server.infra.AbstractPerUserSystemService;

final class SelectionToolbarManagerServiceImpl extends
        AbstractPerUserSystemService<SelectionToolbarManagerServiceImpl,
                SelectionToolbarManagerService> {

    private static final String TAG = "SelectionToolbarManagerServiceImpl";

    protected SelectionToolbarManagerServiceImpl(@NonNull SelectionToolbarManagerService master,
            @NonNull Object lock, int userId) {
        super(master, lock, userId);
    }

    void showToolbar(ShowInfo showInfo, ISelectionToolbarCallback callback) {
        // TODO: add implementation to bind service
    }

    void hideToolbar(long widgetToken) {
        // TODO: add implementation to bind service
    }

    void dismissToolbar(long widgetToken) {
        // TODO: add implementation to bind service
    }
}
