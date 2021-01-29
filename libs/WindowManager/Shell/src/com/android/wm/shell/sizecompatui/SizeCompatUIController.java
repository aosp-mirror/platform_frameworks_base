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

package com.android.wm.shell.sizecompatui;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.ShellExecutor;

/**
 * Shows a restart-activity button on Task when the foreground activity is in size compatibility
 * mode.
 */
public class SizeCompatUIController implements DisplayController.OnDisplaysChangedListener,
        DisplayImeController.ImePositionProcessor {
    private static final String TAG = "SizeCompatUI";

    @VisibleForTesting
    final SizeCompatUI mImpl = new SizeCompatUIImpl();
    private final Context mContext;
    private final ShellExecutor mMainExecutor;
    private final DisplayController mDisplayController;
    private final DisplayImeController mImeController;

    /** Creates the {@link SizeCompatUIController}. */
    public static SizeCompatUI create(Context context,
            DisplayController displayController,
            DisplayImeController imeController,
            ShellExecutor mainExecutor) {
        return new SizeCompatUIController(context, displayController, imeController, mainExecutor)
                .mImpl;
    }

    @VisibleForTesting
    SizeCompatUIController(Context context,
            DisplayController displayController,
            DisplayImeController imeController,
            ShellExecutor mainExecutor) {
        mContext = context;
        mMainExecutor = mainExecutor;
        mDisplayController = displayController;
        mImeController = imeController;
        mDisplayController.addDisplayWindowListener(this);
        mImeController.addPositionProcessor(this);
    }

    private void onSizeCompatInfoChanged(int displayId, int taskId, @Nullable Rect taskBounds,
            @Nullable IBinder sizeCompatActivity,
            @Nullable ShellTaskOrganizer.TaskListener taskListener) {
        // TODO need to deduplicate task info changed
    }

    // TODO move from SizeCompatModeActivityController from system UI.
    @Override
    public void onDisplayRemoved(int displayId) {
    }

    @Override
    public void onImeVisibilityChanged(int displayId, boolean isShowing) {
    }

    private class SizeCompatUIImpl implements SizeCompatUI {
        @Override
        public void onSizeCompatInfoChanged(int displayId, int taskId, @Nullable Rect taskBounds,
                @Nullable IBinder sizeCompatActivity,
                @Nullable ShellTaskOrganizer.TaskListener taskListener) {
            mMainExecutor.execute(() ->
                    SizeCompatUIController.this.onSizeCompatInfoChanged(displayId, taskId,
                            taskBounds, sizeCompatActivity, taskListener));
        }
    }
}
