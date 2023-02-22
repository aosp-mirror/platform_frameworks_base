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
import android.app.TaskInfo;
import android.app.TaskInfo.CameraCompatControlState;
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
import com.android.wm.shell.common.DockStateReader;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.compatui.CompatUIWindowManager.CompatUIHintsState;
import com.android.wm.shell.compatui.letterboxedu.LetterboxEduWindowManager;
import com.android.wm.shell.sysui.KeyguardChangeListener;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import dagger.Lazy;

/**
 * Controller to show/update compat UI components on Tasks based on whether the foreground
 * activities are in compatibility mode.
 */
public class CompatUIController implements OnDisplaysChangedListener,
        DisplayImeController.ImePositionProcessor, KeyguardChangeListener {

    /** Callback for compat UI interaction. */
    public interface CompatUICallback {
        /** Called when the size compat restart button appears. */
        void onSizeCompatRestartButtonAppeared(int taskId);
        /** Called when the size compat restart button is clicked. */
        void onSizeCompatRestartButtonClicked(int taskId);
        /** Called when the camera compat control state is updated. */
        void onCameraControlStateUpdated(int taskId, @CameraCompatControlState int state);
    }

    private static final String TAG = "CompatUIController";

    /** Whether the IME is shown on display id. */
    private final Set<Integer> mDisplaysWithIme = new ArraySet<>(1);

    /** {@link PerDisplayOnInsetsChangedListener} by display id. */
    private final SparseArray<PerDisplayOnInsetsChangedListener> mOnInsetsChangedListeners =
            new SparseArray<>(0);

    /**
     * The active Compat Control UI layouts by task id.
     *
     * <p>An active layout is a layout that is eligible to be shown for the associated task but
     * isn't necessarily shown at a given time.
     */
    private final SparseArray<CompatUIWindowManager> mActiveCompatLayouts = new SparseArray<>(0);

    /**
     * The active Letterbox Education layout if there is one (there can be at most one active).
     *
     * <p>An active layout is a layout that is eligible to be shown for the associated task but
     * isn't necessarily shown at a given time.
     */
    @Nullable
    private LetterboxEduWindowManager mActiveLetterboxEduLayout;

    /** Avoid creating display context frequently for non-default display. */
    private final SparseArray<WeakReference<Context>> mDisplayContextCache = new SparseArray<>(0);

    private final Context mContext;
    private final ShellController mShellController;
    private final DisplayController mDisplayController;
    private final DisplayInsetsController mDisplayInsetsController;
    private final DisplayImeController mImeController;
    private final SyncTransactionQueue mSyncQueue;
    private final ShellExecutor mMainExecutor;
    private final Lazy<Transitions> mTransitionsLazy;
    private final DockStateReader mDockStateReader;

    private CompatUICallback mCallback;

    // Only show each hint once automatically in the process life.
    private final CompatUIHintsState mCompatUIHintsState;

    // Indicates if the keyguard is currently showing, in which case compat UIs shouldn't
    // be shown.
    private boolean mKeyguardShowing;

    public CompatUIController(Context context,
            ShellInit shellInit,
            ShellController shellController,
            DisplayController displayController,
            DisplayInsetsController displayInsetsController,
            DisplayImeController imeController,
            SyncTransactionQueue syncQueue,
            ShellExecutor mainExecutor,
            Lazy<Transitions> transitionsLazy,
            DockStateReader dockStateReader) {
        mContext = context;
        mShellController = shellController;
        mDisplayController = displayController;
        mDisplayInsetsController = displayInsetsController;
        mImeController = imeController;
        mSyncQueue = syncQueue;
        mMainExecutor = mainExecutor;
        mTransitionsLazy = transitionsLazy;
        mCompatUIHintsState = new CompatUIHintsState();
        shellInit.addInitCallback(this::onInit, this);
        mDockStateReader = dockStateReader;
    }

    private void onInit() {
        mShellController.addKeyguardChangeListener(this);
        mDisplayController.addDisplayWindowListener(this);
        mImeController.addPositionProcessor(this);
    }

    /** Sets the callback for UI interactions. */
    public void setCompatUICallback(CompatUICallback callback) {
        mCallback = callback;
    }

    /**
     * Called when the Task info changed. Creates and updates the compat UI if there is an
     * activity in size compat, or removes the UI if there is no size compat activity.
     *
     * @param taskInfo {@link TaskInfo} task the activity is in.
     * @param taskListener listener to handle the Task Surface placement.
     */
    public void onCompatInfoChanged(TaskInfo taskInfo,
            @Nullable ShellTaskOrganizer.TaskListener taskListener) {
        if (taskInfo.configuration == null || taskListener == null) {
            // Null token means the current foreground activity is not in compatibility mode.
            removeLayouts(taskInfo.taskId);
            return;
        }

        createOrUpdateCompatLayout(taskInfo, taskListener);
        createOrUpdateLetterboxEduLayout(taskInfo, taskListener);
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
            removeLayouts(toRemoveTaskIds.get(i));
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

    @Override
    public void onKeyguardVisibilityChanged(boolean visible, boolean occluded,
            boolean animatingDismiss) {
        mKeyguardShowing = visible;
        // Hide the compat UIs when keyguard is showing.
        forAllLayouts(layout -> layout.updateVisibility(showOnDisplay(layout.getDisplayId())));
    }

    private boolean showOnDisplay(int displayId) {
        return !mKeyguardShowing && !isImeShowingOnDisplay(displayId);
    }

    private boolean isImeShowingOnDisplay(int displayId) {
        return mDisplaysWithIme.contains(displayId);
    }

    private void createOrUpdateCompatLayout(TaskInfo taskInfo,
            ShellTaskOrganizer.TaskListener taskListener) {
        CompatUIWindowManager layout = mActiveCompatLayouts.get(taskInfo.taskId);
        if (layout != null) {
            // UI already exists, update the UI layout.
            if (!layout.updateCompatInfo(taskInfo, taskListener,
                    showOnDisplay(layout.getDisplayId()))) {
                // The layout is no longer eligible to be shown, remove from active layouts.
                mActiveCompatLayouts.remove(taskInfo.taskId);
            }
            return;
        }

        // Create a new UI layout.
        final Context context = getOrCreateDisplayContext(taskInfo.displayId);
        if (context == null) {
            return;
        }
        layout = createCompatUiWindowManager(context, taskInfo, taskListener);
        if (layout.createLayout(showOnDisplay(taskInfo.displayId))) {
            // The new layout is eligible to be shown, add it the active layouts.
            mActiveCompatLayouts.put(taskInfo.taskId, layout);
        }
    }

    @VisibleForTesting
    CompatUIWindowManager createCompatUiWindowManager(Context context, TaskInfo taskInfo,
            ShellTaskOrganizer.TaskListener taskListener) {
        return new CompatUIWindowManager(context,
                taskInfo, mSyncQueue, mCallback, taskListener,
                mDisplayController.getDisplayLayout(taskInfo.displayId), mCompatUIHintsState);
    }

    private void createOrUpdateLetterboxEduLayout(TaskInfo taskInfo,
            ShellTaskOrganizer.TaskListener taskListener) {
        if (mActiveLetterboxEduLayout != null
                && mActiveLetterboxEduLayout.getTaskId() == taskInfo.taskId) {
            // UI already exists, update the UI layout.
            if (!mActiveLetterboxEduLayout.updateCompatInfo(taskInfo, taskListener,
                    showOnDisplay(mActiveLetterboxEduLayout.getDisplayId()))) {
                // The layout is no longer eligible to be shown, clear active layout.
                mActiveLetterboxEduLayout = null;
            }
            return;
        }

        // Create a new UI layout.
        final Context context = getOrCreateDisplayContext(taskInfo.displayId);
        if (context == null) {
            return;
        }
        LetterboxEduWindowManager newLayout = createLetterboxEduWindowManager(context, taskInfo,
                taskListener);
        if (newLayout.createLayout(showOnDisplay(taskInfo.displayId))) {
            // The new layout is eligible to be shown, make it the active layout.
            if (mActiveLetterboxEduLayout != null) {
                // Release the previous layout since at most one can be active.
                // Since letterbox education is only shown once to the user, releasing the previous
                // layout is only a precaution.
                mActiveLetterboxEduLayout.release();
            }
            mActiveLetterboxEduLayout = newLayout;
        }
    }

    @VisibleForTesting
    LetterboxEduWindowManager createLetterboxEduWindowManager(Context context, TaskInfo taskInfo,
            ShellTaskOrganizer.TaskListener taskListener) {
        return new LetterboxEduWindowManager(context, taskInfo,
                mSyncQueue, taskListener, mDisplayController.getDisplayLayout(taskInfo.displayId),
                mTransitionsLazy.get(),
                this::onLetterboxEduDismissed,
                mDockStateReader);
    }

    private void onLetterboxEduDismissed() {
        mActiveLetterboxEduLayout = null;
    }

    private void removeLayouts(int taskId) {
        final CompatUIWindowManager layout = mActiveCompatLayouts.get(taskId);
        if (layout != null) {
            layout.release();
            mActiveCompatLayouts.remove(taskId);
        }

        if (mActiveLetterboxEduLayout != null && mActiveLetterboxEduLayout.getTaskId() == taskId) {
            mActiveLetterboxEduLayout.release();
            mActiveLetterboxEduLayout = null;
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
            } else {
                Log.e(TAG, "Cannot get context for display " + displayId);
            }
        }
        return context;
    }

    private void forAllLayoutsOnDisplay(int displayId,
            Consumer<CompatUIWindowManagerAbstract> callback) {
        forAllLayouts(layout -> layout.getDisplayId() == displayId, callback);
    }

    private void forAllLayouts(Consumer<CompatUIWindowManagerAbstract> callback) {
        forAllLayouts(layout -> true, callback);
    }

    private void forAllLayouts(Predicate<CompatUIWindowManagerAbstract> condition,
            Consumer<CompatUIWindowManagerAbstract> callback) {
        for (int i = 0; i < mActiveCompatLayouts.size(); i++) {
            final int taskId = mActiveCompatLayouts.keyAt(i);
            final CompatUIWindowManager layout = mActiveCompatLayouts.get(taskId);
            if (layout != null && condition.test(layout)) {
                callback.accept(layout);
            }
        }
        if (mActiveLetterboxEduLayout != null && condition.test(mActiveLetterboxEduLayout)) {
            callback.accept(mActiveLetterboxEduLayout);
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
