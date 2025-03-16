/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.transition;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;
import static android.window.TransitionInfo.FLAG_MOVED_TO_TOP;

import static com.android.window.flags.Flags.enableDisplayFocusInShellTransitions;
import static com.android.wm.shell.transition.Transitions.TransitionObserver;

import android.annotation.NonNull;
import android.app.ActivityManager.RunningTaskInfo;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.window.TransitionInfo;

import com.android.wm.shell.shared.FocusTransitionListener;
import com.android.wm.shell.shared.IFocusTransitionListener;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The {@link TransitionObserver} that observes for transitions involving focus switch.
 * It reports transitions to callers outside of the process via {@link IFocusTransitionListener},
 * and callers within the process via {@link FocusTransitionListener}.
 */
public class FocusTransitionObserver {
    private static final String TAG = FocusTransitionObserver.class.getSimpleName();

    private IFocusTransitionListener mRemoteListener;
    private final Map<FocusTransitionListener, Executor> mLocalListeners =
            new HashMap<>();

    private int mFocusedDisplayId = DEFAULT_DISPLAY;
    private final SparseArray<RunningTaskInfo> mFocusedTaskOnDisplay = new SparseArray<>();

    private final ArraySet<RunningTaskInfo> mTmpTasksToBeNotified = new ArraySet<>();

    public FocusTransitionObserver() {}

    /**
     * Update display/window focus state from the given transition info and notifies changes if any.
     */
    public void updateFocusState(@NonNull TransitionInfo info) {
        if (!enableDisplayFocusInShellTransitions()) {
            return;
        }
        final List<TransitionInfo.Change> changes = info.getChanges();
        for (int i = changes.size() - 1; i >= 0; i--) {
            final TransitionInfo.Change change = changes.get(i);

            final RunningTaskInfo task = change.getTaskInfo();
            if (task != null
                    && (change.hasFlags(FLAG_MOVED_TO_TOP) || change.getMode() == TRANSIT_OPEN)) {
                final RunningTaskInfo lastFocusedTaskOnDisplay =
                        mFocusedTaskOnDisplay.get(task.displayId);
                if (lastFocusedTaskOnDisplay != null) {
                    mTmpTasksToBeNotified.add(lastFocusedTaskOnDisplay);
                }
                mTmpTasksToBeNotified.add(task);
                mFocusedTaskOnDisplay.put(task.displayId, task);
            }

            if (change.hasFlags(FLAG_IS_DISPLAY) && change.hasFlags(FLAG_MOVED_TO_TOP)) {
                if (mFocusedDisplayId != change.getEndDisplayId()) {
                    final RunningTaskInfo lastGloballyFocusedTask =
                            mFocusedTaskOnDisplay.get(mFocusedDisplayId);
                    if (lastGloballyFocusedTask != null) {
                        mTmpTasksToBeNotified.add(lastGloballyFocusedTask);
                    }
                    mFocusedDisplayId = change.getEndDisplayId();
                    notifyFocusedDisplayChanged();
                    final RunningTaskInfo currentGloballyFocusedTask =
                            mFocusedTaskOnDisplay.get(mFocusedDisplayId);
                    if (currentGloballyFocusedTask != null) {
                        mTmpTasksToBeNotified.add(currentGloballyFocusedTask);
                    }
                }
            }
        }
        mTmpTasksToBeNotified.forEach(this::notifyTaskFocusChanged);
        mTmpTasksToBeNotified.clear();
    }

    /**
     * Sets the focus transition listener that receives any transitions resulting in focus switch.
     * This is for calls from outside the Shell, within the host process.
     *
     */
    public void setLocalFocusTransitionListener(FocusTransitionListener listener,
            Executor executor) {
        if (!enableDisplayFocusInShellTransitions()) {
            return;
        }
        mLocalListeners.put(listener, executor);
        executor.execute(() -> {
            listener.onFocusedDisplayChanged(mFocusedDisplayId);
            mTmpTasksToBeNotified.forEach(this::notifyTaskFocusChanged);
        });
    }

    /**
     * Sets the focus transition listener that receives any transitions resulting in focus switch.
     * This is for calls from outside the Shell, within the host process.
     *
     */
    public void unsetLocalFocusTransitionListener(FocusTransitionListener listener) {
        if (!enableDisplayFocusInShellTransitions()) {
            return;
        }
        mLocalListeners.remove(listener);
    }

    /**
     * Sets the focus transition listener that receives any transitions resulting in focus switch.
     * This is for calls from outside the host process.
     */
    public void setRemoteFocusTransitionListener(Transitions transitions,
            IFocusTransitionListener listener) {
        if (!enableDisplayFocusInShellTransitions()) {
            return;
        }
        mRemoteListener = listener;
        notifyFocusedDisplayChangedToRemote();
    }

    private void notifyTaskFocusChanged(RunningTaskInfo task) {
        final boolean isFocusedOnDisplay = isFocusedOnDisplay(task);
        final boolean isFocusedGlobally = hasGlobalFocus(task);
        mLocalListeners.forEach((listener, executor) ->
                executor.execute(() -> listener.onFocusedTaskChanged(task.taskId,
                        isFocusedOnDisplay, isFocusedGlobally)));
    }

    private void notifyFocusedDisplayChanged() {
        notifyFocusedDisplayChangedToRemote();
        mLocalListeners.forEach((listener, executor) ->
                executor.execute(() -> {
                    listener.onFocusedDisplayChanged(mFocusedDisplayId);
                }));
    }

    private void notifyFocusedDisplayChangedToRemote() {
        if (mRemoteListener != null) {
            try {
                mRemoteListener.onFocusedDisplayChanged(mFocusedDisplayId);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed call notifyFocusedDisplayChangedToRemote", e);
            }
        }
    }

    private boolean isFocusedOnDisplay(@NonNull RunningTaskInfo task) {
        if (!enableDisplayFocusInShellTransitions()) {
            return task.isFocused;
        }
        final RunningTaskInfo focusedTaskOnDisplay = mFocusedTaskOnDisplay.get(task.displayId);
        return focusedTaskOnDisplay != null && focusedTaskOnDisplay.taskId == task.taskId;
    }

    /**
     * Checks whether the given task has focused globally on the system.
     * (Note {@link RunningTaskInfo#isFocused} represents per-display focus.)
     */
    public boolean hasGlobalFocus(@NonNull RunningTaskInfo task) {
        if (!enableDisplayFocusInShellTransitions()) {
            return task.isFocused;
        }
        return task.displayId == mFocusedDisplayId && isFocusedOnDisplay(task);
    }
}
