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

package com.android.wm.shell.compatui;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.InsetsSourceControl;
import android.view.InsetsState;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayController.OnDisplaysChangedListener;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayInsetsController.OnInsetsChangedListener;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.annotations.ExternalThread;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Controls to show/update restart-activity buttons on Tasks based on whether the foreground
 * activities are in compatibility mode.
 */
public class CompatUIController implements OnDisplaysChangedListener,
        DisplayImeController.ImePositionProcessor {

    /** Callback for size compat UI interaction. */
    public interface CompatUICallback {
        /** Called when the size compat restart button appears. */
        void onSizeCompatRestartButtonAppeared(int taskId);
        /** Called when the size compat restart button is clicked. */
        void onSizeCompatRestartButtonClicked(int taskId);
    }

    private static final String TAG = "CompatUIController";

    /** Whether the IME is shown on display id. */
    private final Set<Integer> mDisplaysWithIme = new ArraySet<>(1);

    /** {@link PerDisplayOnInsetsChangedListener} by display id. */
    private final SparseArray<PerDisplayOnInsetsChangedListener> mOnInsetsChangedListeners =
            new SparseArray<>(0);

    /** The showing UIs by task id. */
    private final SparseArray<CompatUIWindowManager> mActiveLayouts = new SparseArray<>(0);

    /** Avoid creating display context frequently for non-default display. */
    private final SparseArray<WeakReference<Context>> mDisplayContextCache = new SparseArray<>(0);

    private final Context mContext;
    private final DisplayController mDisplayController;
    private final DisplayInsetsController mDisplayInsetsController;
    private final DisplayImeController mImeController;
    private final SyncTransactionQueue mSyncQueue;
    private final ShellExecutor mMainExecutor;
    private final CompatUIImpl mImpl = new CompatUIImpl();

    private CompatUICallback mCallback;

    /** Only show once automatically in the process life. */
    private boolean mHasShownHint;
    /** Indicates if the keyguard is currently occluded, in which case compat UIs shouldn't
     * be shown. */
    private boolean mKeyguardOccluded;

    public CompatUIController(Context context,
            DisplayController displayController,
            DisplayInsetsController displayInsetsController,
            DisplayImeController imeController,
            SyncTransactionQueue syncQueue,
            ShellExecutor mainExecutor) {
        mContext = context;
        mDisplayController = displayController;
        mDisplayInsetsController = displayInsetsController;
        mImeController = imeController;
        mSyncQueue = syncQueue;
        mMainExecutor = mainExecutor;
        mDisplayController.addDisplayWindowListener(this);
        mImeController.addPositionProcessor(this);
    }

    /** Returns implementation of {@link CompatUI}. */
    public CompatUI asCompatUI() {
        return mImpl;
    }

    /** Sets the callback for UI interactions. */
    public void setCompatUICallback(CompatUICallback callback) {
        mCallback = callback;
    }

    /**
     * Called when the Task info changed. Creates and updates the compat UI if there is an
     * activity in size compat, or removes the UI if there is no size compat activity.
     *
     * @param displayId display the task and activity are in.
     * @param taskId task the activity is in.
     * @param taskConfig task config to place the compat UI with.
     * @param taskListener listener to handle the Task Surface placement.
     */
    public void onCompatInfoChanged(int displayId, int taskId,
            @Nullable Configuration taskConfig,
            @Nullable ShellTaskOrganizer.TaskListener taskListener) {
        if (taskConfig == null || taskListener == null) {
            // Null token means the current foreground activity is not in compatibility mode.
            removeLayout(taskId);
        } else if (mActiveLayouts.contains(taskId)) {
            // UI already exists, update the UI layout.
            updateLayout(taskId, taskConfig, taskListener);
        } else {
            // Create a new compat UI.
            createLayout(displayId, taskId, taskConfig, taskListener);
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        addOnInsetsChangedListener(displayId);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        mDisplayContextCache.remove(displayId);
        removeOnInsetsChangedListener(displayId);

        // Remove all compat UIs on the removed display.
        final List<Integer> toRemoveTaskIds = new ArrayList<>();
        forAllLayoutsOnDisplay(displayId, layout -> toRemoveTaskIds.add(layout.getTaskId()));
        for (int i = toRemoveTaskIds.size() - 1; i >= 0; i--) {
            removeLayout(toRemoveTaskIds.get(i));
        }
    }

    private void addOnInsetsChangedListener(int displayId) {
        PerDisplayOnInsetsChangedListener listener = new PerDisplayOnInsetsChangedListener(
                displayId);
        listener.register();
        mOnInsetsChangedListeners.put(displayId, listener);
    }

    private void removeOnInsetsChangedListener(int displayId) {
        PerDisplayOnInsetsChangedListener listener = mOnInsetsChangedListeners.get(displayId);
        if (listener == null) {
            return;
        }
        listener.unregister();
        mOnInsetsChangedListeners.remove(displayId);
    }


    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        updateDisplayLayout(displayId);
    }

    private void updateDisplayLayout(int displayId) {
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

        // Hide the compat UIs when input method is showing.
        forAllLayoutsOnDisplay(displayId,
                layout -> layout.updateVisibility(showOnDisplay(displayId)));
    }

    @VisibleForTesting
    void onKeyguardOccludedChanged(boolean occluded) {
        mKeyguardOccluded = occluded;
        // Hide the compat UIs when keyguard is occluded.
        forAllLayouts(layout -> layout.updateVisibility(showOnDisplay(layout.getDisplayId())));
    }

    private boolean showOnDisplay(int displayId) {
        return !mKeyguardOccluded && !isImeShowingOnDisplay(displayId);
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

        final CompatUIWindowManager compatUIWindowManager =
                createLayout(context, displayId, taskId, taskConfig, taskListener);
        mActiveLayouts.put(taskId, compatUIWindowManager);
        compatUIWindowManager.createLayout(showOnDisplay(displayId));
    }

    @VisibleForTesting
    CompatUIWindowManager createLayout(Context context, int displayId, int taskId,
            Configuration taskConfig, ShellTaskOrganizer.TaskListener taskListener) {
        final CompatUIWindowManager compatUIWindowManager = new CompatUIWindowManager(context,
                taskConfig, mSyncQueue, mCallback, taskId, taskListener,
                mDisplayController.getDisplayLayout(displayId), mHasShownHint);
        // Only show hint for the first time.
        mHasShownHint = true;
        return compatUIWindowManager;
    }

    private void updateLayout(int taskId, Configuration taskConfig,
            ShellTaskOrganizer.TaskListener taskListener) {
        final CompatUIWindowManager layout = mActiveLayouts.get(taskId);
        if (layout == null) {
            return;
        }
        layout.updateCompatInfo(taskConfig, taskListener, showOnDisplay(layout.getDisplayId()));
    }

    private void removeLayout(int taskId) {
        final CompatUIWindowManager layout = mActiveLayouts.get(taskId);
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

    private void forAllLayoutsOnDisplay(int displayId, Consumer<CompatUIWindowManager> callback) {
        forAllLayouts(layout -> layout.getDisplayId() == displayId, callback);
    }

    private void forAllLayouts(Consumer<CompatUIWindowManager> callback) {
        forAllLayouts(layout -> true, callback);
    }

    private void forAllLayouts(Predicate<CompatUIWindowManager> condition,
            Consumer<CompatUIWindowManager> callback) {
        for (int i = 0; i < mActiveLayouts.size(); i++) {
            final int taskId = mActiveLayouts.keyAt(i);
            final CompatUIWindowManager layout = mActiveLayouts.get(taskId);
            if (layout != null && condition.test(layout)) {
                callback.accept(layout);
            }
        }
    }

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    @ExternalThread
    private class CompatUIImpl implements CompatUI {
        @Override
        public void onKeyguardOccludedChanged(boolean occluded) {
            mMainExecutor.execute(() -> {
                CompatUIController.this.onKeyguardOccludedChanged(occluded);
            });
        }
    }

    /** An implementation of {@link OnInsetsChangedListener} for a given display id. */
    private class PerDisplayOnInsetsChangedListener implements OnInsetsChangedListener {
        final int mDisplayId;
        final InsetsState mInsetsState = new InsetsState();

        PerDisplayOnInsetsChangedListener(int displayId) {
            mDisplayId = displayId;
        }

        void register() {
            mDisplayInsetsController.addInsetsChangedListener(mDisplayId, this);
        }

        void unregister() {
            mDisplayInsetsController.removeInsetsChangedListener(mDisplayId, this);
        }

        @Override
        public void insetsChanged(InsetsState insetsState) {
            if (mInsetsState.equals(insetsState)) {
                return;
            }
            mInsetsState.set(insetsState);
            updateDisplayLayout(mDisplayId);
        }

        @Override
        public void insetsControlChanged(InsetsState insetsState,
                InsetsSourceControl[] activeControls) {
            insetsChanged(insetsState);
        }
    }
}
