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
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Controls to show/update restart-activity buttons on Tasks based on whether the foreground
 * activities are in size compatibility mode.
 */
public class SizeCompatUIController implements DisplayController.OnDisplaysChangedListener,
        DisplayImeController.ImePositionProcessor {

    /** Callback for size compat UI interaction. */
    public interface SizeCompatUICallback {
        /** Called when the size compat restart button appears. */
        void onSizeCompatRestartButtonAppeared(int taskId);
        /** Called when the size compat restart button is clicked. */
        void onSizeCompatRestartButtonClicked(int taskId);
    }

    private static final String TAG = "SizeCompatUIController";

    /** Whether the IME is shown on display id. */
    private final Set<Integer> mDisplaysWithIme = new ArraySet<>(1);

    /** The showing UIs by task id. */
    private final SparseArray<SizeCompatUILayout> mActiveLayouts = new SparseArray<>(0);

    /** Avoid creating display context frequently for non-default display. */
    private final SparseArray<WeakReference<Context>> mDisplayContextCache = new SparseArray<>(0);

    private final Context mContext;
    private final DisplayController mDisplayController;
    private final DisplayImeController mImeController;
    private final SyncTransactionQueue mSyncQueue;

    private SizeCompatUICallback mCallback;

    /** Only show once automatically in the process life. */
    private boolean mHasShownHint;

    public SizeCompatUIController(Context context,
            DisplayController displayController,
            DisplayImeController imeController,
            SyncTransactionQueue syncQueue) {
        mContext = context;
        mDisplayController = displayController;
        mImeController = imeController;
        mSyncQueue = syncQueue;
        mDisplayController.addDisplayWindowListener(this);
        mImeController.addPositionProcessor(this);
    }

    /** Sets the callback for UI interactions. */
    public void setSizeCompatUICallback(SizeCompatUICallback callback) {
        mCallback = callback;
    }

    /**
     * Called when the Task info changed. Creates and updates the size compat UI if there is an
     * activity in size compat, or removes the UI if there is no size compat activity.
     * @param displayId display the task and activity are in.
     * @param taskId task the activity is in.
     * @param taskConfig task config to place the size compat UI with.
     * @param taskListener listener to handle the Task Surface placement.
     */
    public void onSizeCompatInfoChanged(int displayId, int taskId,
            @Nullable Configuration taskConfig,
            @Nullable ShellTaskOrganizer.TaskListener taskListener) {
        if (taskConfig == null || taskListener == null) {
            // Null token means the current foreground activity is not in size compatibility mode.
            removeLayout(taskId);
        } else if (mActiveLayouts.contains(taskId)) {
            // UI already exists, update the UI layout.
            updateLayout(taskId, taskConfig, taskListener);
        } else {
            // Create a new size compat UI.
            createLayout(displayId, taskId, taskConfig, taskListener);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        mDisplayContextCache.remove(displayId);

        // Remove all size compat UIs on the removed display.
        final List<Integer> toRemoveTaskIds = new ArrayList<>();
        forAllLayoutsOnDisplay(displayId, layout -> toRemoveTaskIds.add(layout.getTaskId()));
        for (int i = toRemoveTaskIds.size() - 1; i >= 0; i--) {
            removeLayout(toRemoveTaskIds.get(i));
        }
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        final DisplayLayout displayLayout = mDisplayController.getDisplayLayout(displayId);
        forAllLayoutsOnDisplay(displayId, layout -> layout.updateDisplayLayout(displayLayout));
    }

    @Override
    public void onImeVisibilityChanged(int displayId, boolean isShowing) {
        if (isShowing) {
            mDisplaysWithIme.add(displayId);
        } else {
            mDisplaysWithIme.remove(displayId);
        }

        // Hide the size compat UIs when input method is showing.
        forAllLayoutsOnDisplay(displayId, layout -> layout.updateImeVisibility(isShowing));
    }

    private boolean isImeShowingOnDisplay(int displayId) {
        return mDisplaysWithIme.contains(displayId);
    }

    private void createLayout(int displayId, int taskId, Configuration taskConfig,
            ShellTaskOrganizer.TaskListener taskListener) {
        final Context context = getOrCreateDisplayContext(displayId);
        if (context == null) {
            Log.e(TAG, "Cannot get context for display " + displayId);
            return;
        }

        final SizeCompatUILayout layout = createLayout(context, displayId, taskId, taskConfig,
                taskListener);
        mActiveLayouts.put(taskId, layout);
        layout.createSizeCompatButton(isImeShowingOnDisplay(displayId));
    }

    @VisibleForTesting
    SizeCompatUILayout createLayout(Context context, int displayId, int taskId,
            Configuration taskConfig, ShellTaskOrganizer.TaskListener taskListener) {
        final SizeCompatUILayout layout = new SizeCompatUILayout(mSyncQueue, mCallback, context,
                taskConfig, taskId, taskListener, mDisplayController.getDisplayLayout(displayId),
                mHasShownHint);
        // Only show hint for the first time.
        mHasShownHint = true;
        return layout;
    }

    private void updateLayout(int taskId, Configuration taskConfig,
            ShellTaskOrganizer.TaskListener taskListener) {
        final SizeCompatUILayout layout = mActiveLayouts.get(taskId);
        if (layout == null) {
            return;
        }
        layout.updateSizeCompatInfo(taskConfig, taskListener,
                isImeShowingOnDisplay(layout.getDisplayId()));
    }

    private void removeLayout(int taskId) {
        final SizeCompatUILayout layout = mActiveLayouts.get(taskId);
        if (layout != null) {
            layout.release();
            mActiveLayouts.remove(taskId);
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

    private void forAllLayoutsOnDisplay(int displayId, Consumer<SizeCompatUILayout> callback) {
        for (int i = 0; i < mActiveLayouts.size(); i++) {
            final int taskId = mActiveLayouts.keyAt(i);
            final SizeCompatUILayout layout = mActiveLayouts.get(taskId);
            if (layout != null && layout.getDisplayId() == displayId) {
                callback.accept(layout);
            }
        }
    }
}
