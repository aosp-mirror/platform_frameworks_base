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
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.View;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.ShellExecutor;

import java.lang.ref.WeakReference;

/**
 * Shows a restart-activity button on Task when the foreground activity is in size compatibility
 * mode.
 */
public class SizeCompatUIController implements DisplayController.OnDisplaysChangedListener,
        DisplayImeController.ImePositionProcessor {
    private static final String TAG = "SizeCompatUI";

    /** The showing buttons by task id. */
    private final SparseArray<SizeCompatRestartButton> mActiveButtons = new SparseArray<>(1);
    /** Avoid creating display context frequently for non-default display. */
    private final SparseArray<WeakReference<Context>> mDisplayContextCache = new SparseArray<>(0);

    @VisibleForTesting
    private final Context mContext;
    private final ShellExecutor mMainExecutor;
    private final DisplayController mDisplayController;
    private final DisplayImeController mImeController;

    /** Only show once automatically in the process life. */
    private boolean mHasShownHint;

    @VisibleForTesting
    public SizeCompatUIController(Context context,
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

    public void onSizeCompatInfoChanged(int displayId, int taskId, @Nullable Rect taskBounds,
            @Nullable IBinder sizeCompatActivity,
            @Nullable ShellTaskOrganizer.TaskListener taskListener) {
        // TODO Draw button on Task surface
        if (taskBounds == null || sizeCompatActivity == null || taskListener == null) {
            // Null token means the current foreground activity is not in size compatibility mode.
            removeRestartButton(taskId);
        } else {
            updateRestartButton(displayId, taskId, sizeCompatActivity);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        mDisplayContextCache.remove(displayId);
        for (int i = 0; i < mActiveButtons.size(); i++) {
            final int taskId = mActiveButtons.keyAt(i);
            final SizeCompatRestartButton button = mActiveButtons.get(taskId);
            if (button != null && button.mDisplayId == displayId) {
                removeRestartButton(taskId);
            }
        }
    }

    @Override
    public void onImeVisibilityChanged(int displayId, boolean isShowing) {
        final int newVisibility = isShowing ? View.GONE : View.VISIBLE;
        for (int i = 0; i < mActiveButtons.size(); i++) {
            final int taskId = mActiveButtons.keyAt(i);
            final SizeCompatRestartButton button = mActiveButtons.get(taskId);
            if (button == null || button.mDisplayId != displayId) {
                continue;
            }

            // Hide the button when input method is showing.
            if (button.getVisibility() != newVisibility) {
                button.setVisibility(newVisibility);
            }
        }
    }

    private void updateRestartButton(int displayId, int taskId, IBinder activityToken) {
        SizeCompatRestartButton restartButton = mActiveButtons.get(taskId);
        if (restartButton != null) {
            restartButton.updateLastTargetActivity(activityToken);
            return;
        }

        final Context context = getOrCreateDisplayContext(displayId);
        if (context == null) {
            Log.i(TAG, "Cannot get context for display " + displayId);
            return;
        }

        restartButton = createRestartButton(context, displayId);
        restartButton.updateLastTargetActivity(activityToken);
        if (restartButton.show()) {
            mActiveButtons.append(taskId, restartButton);
        } else {
            onDisplayRemoved(displayId);
        }
    }

    @VisibleForTesting
    SizeCompatRestartButton createRestartButton(Context context, int displayId) {
        final SizeCompatRestartButton button = new SizeCompatRestartButton(context, displayId,
                mHasShownHint);
        // Only show hint for the first time.
        mHasShownHint = true;
        return button;
    }

    private void removeRestartButton(int taskId) {
        final SizeCompatRestartButton button = mActiveButtons.get(taskId);
        if (button != null) {
            button.remove();
            mActiveButtons.remove(taskId);
        }
    }

    private Context getOrCreateDisplayContext(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            return mContext;
        }
        Context context = null;
        final WeakReference<Context> ref = mDisplayContextCache.get(displayId);
        if (ref != null) {
            context = ref.get();
        }
        if (context == null) {
            Display display = mContext.getSystemService(DisplayManager.class).getDisplay(displayId);
            if (display != null) {
                context = mContext.createDisplayContext(display);
                mDisplayContextCache.put(displayId, new WeakReference<>(context));
            }
        }
        return context;
    }
}
