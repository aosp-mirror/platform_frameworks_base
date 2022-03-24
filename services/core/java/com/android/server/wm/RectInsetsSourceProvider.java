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

package com.android.server.wm;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.graphics.Rect;
import android.util.Slog;
import android.view.InsetsSource;

/**
 * An {@link InsetsSourceProvider} which doesn't have a backing window or a window container.
 */
public class RectInsetsSourceProvider extends InsetsSourceProvider {
    private static final String TAG = TAG_WITH_CLASS_NAME
            ? RectInsetsSourceProvider.class.getSimpleName()
            : TAG_WM;

    RectInsetsSourceProvider(InsetsSource source,
            InsetsStateController stateController, DisplayContent displayContent) {
        super(source, stateController, displayContent);
    }

    /**
     * Sets the given {@code rect} as the frame of the underlying {@link InsetsSource}.
     */
    void setRect(Rect rect) {
        mSource.setFrame(rect);
        mSource.setVisible(true);
    }

    @Override
    void onPostLayout() {
        if (WindowManagerDebugConfig.DEBUG) {
            Slog.d(TAG, "onPostLayout(), not calling super.onPostLayout().");
        }
    }
}
