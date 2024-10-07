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

import static android.view.Display.INVALID_DISPLAY;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;
import static android.window.TransitionInfo.FLAG_MOVED_TO_TOP;

import static com.android.window.flags.Flags.enableDisplayFocusInShellTransitions;
import static com.android.wm.shell.transition.Transitions.TransitionObserver;

import android.annotation.NonNull;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.SurfaceControl;
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
public class FocusTransitionObserver implements TransitionObserver {
    private static final String TAG = FocusTransitionObserver.class.getSimpleName();

    private IFocusTransitionListener mRemoteListener;
    private final Map<FocusTransitionListener, Executor> mLocalListeners =
            new HashMap<>();

    private int mFocusedDisplayId = INVALID_DISPLAY;

    public FocusTransitionObserver() {}

    @Override
    public void onTransitionReady(@NonNull IBinder transition,
            @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        final List<TransitionInfo.Change> changes = info.getChanges();
        for (int i = changes.size() - 1; i >= 0; i--) {
            final TransitionInfo.Change change = changes.get(i);
            if (change.hasFlags(FLAG_IS_DISPLAY) && change.hasFlags(FLAG_MOVED_TO_TOP)) {
                if (mFocusedDisplayId != change.getEndDisplayId()) {
                    mFocusedDisplayId = change.getEndDisplayId();
                    notifyFocusedDisplayChanged();
                }
                return;
            }
        }
    }

    @Override
    public void onTransitionStarting(@NonNull IBinder transition) {}

    @Override
    public void onTransitionMerged(@NonNull IBinder merged, @NonNull IBinder playing) {}

    @Override
    public void onTransitionFinished(@NonNull IBinder transition, boolean aborted) {}

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
        executor.execute(() -> listener.onFocusedDisplayChanged(mFocusedDisplayId));
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

    /**
     * Notifies the listener that display focus has changed.
     */
    public void notifyFocusedDisplayChanged() {
        notifyFocusedDisplayChangedToRemote();
        mLocalListeners.forEach((listener, executor) ->
                executor.execute(() -> listener.onFocusedDisplayChanged(mFocusedDisplayId)));
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
}
