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

package com.android.server.inputmethod;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Provides the window context for the IME switcher dialog.
 */
@VisibleForTesting(visibility = PACKAGE)
public final class InputMethodDialogWindowContext {
    @Nullable
    private Context mDialogWindowContext;

    /**
     * Returns the window context for IME switch dialogs to receive configuration changes.
     *
     * This method initializes the window context if it was not initialized, or moves the context to
     * the targeted display if the current display of context is different from the display
     * specified by {@code displayId}.
     */
    @NonNull
    @VisibleForTesting(visibility = PACKAGE)
    public Context get(int displayId) {
        if (mDialogWindowContext == null || mDialogWindowContext.getDisplayId() != displayId) {
            final Context systemUiContext = ActivityThread.currentActivityThread()
                    .getSystemUiContext(displayId);
            final Context windowContext = systemUiContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG, null /* options */);
            mDialogWindowContext = new ContextThemeWrapper(
                    windowContext, com.android.internal.R.style.Theme_DeviceDefault_Settings);
        }
        return mDialogWindowContext;
    }
}
