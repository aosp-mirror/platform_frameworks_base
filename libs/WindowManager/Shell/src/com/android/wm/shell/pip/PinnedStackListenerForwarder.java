/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.wm.shell.pip;

import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.pm.ParceledListSlice;
import android.os.RemoteException;
import android.view.IPinnedStackListener;
import android.view.WindowManagerGlobal;

import androidx.annotation.BinderThread;

import com.android.wm.shell.common.ShellExecutor;

import java.util.ArrayList;

/**
 * PinnedStackListener that simply forwards all calls to each listener added via
 * {@link #addListener}. This is necessary since calling
 * {@link com.android.server.wm.WindowManagerService#registerPinnedStackListener} replaces any
 * previously set listener.
 */
public class PinnedStackListenerForwarder {

    private final IPinnedStackListener mListenerImpl = new PinnedStackListenerImpl();
    private final ShellExecutor mShellMainExecutor;
    private final ArrayList<PinnedStackListener> mListeners = new ArrayList<>();

    public PinnedStackListenerForwarder(ShellExecutor shellMainExecutor) {
        mShellMainExecutor = shellMainExecutor;
    }

    /** Adds a listener to receive updates from the WindowManagerService. */
    public void addListener(PinnedStackListener listener) {
        mListeners.add(listener);
    }

    /** Removes a listener so it will no longer receive updates from the WindowManagerService. */
    public void removeListener(PinnedStackListener listener) {
        mListeners.remove(listener);
    }

    public void register(int displayId) throws RemoteException {
        WindowManagerGlobal.getWindowManagerService().registerPinnedStackListener(
                displayId, mListenerImpl);
    }

    private void onMovementBoundsChanged(boolean fromImeAdjustment) {
        for (PinnedStackListener listener : mListeners) {
            listener.onMovementBoundsChanged(fromImeAdjustment);
        }
    }

    private void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
        for (PinnedStackListener listener : mListeners) {
            listener.onImeVisibilityChanged(imeVisible, imeHeight);
        }
    }

    private void onActionsChanged(ParceledListSlice<RemoteAction> actions) {
        for (PinnedStackListener listener : mListeners) {
            listener.onActionsChanged(actions);
        }
    }

    private void onActivityHidden(ComponentName componentName) {
        for (PinnedStackListener listener : mListeners) {
            listener.onActivityHidden(componentName);
        }
    }

    private void onAspectRatioChanged(float aspectRatio) {
        for (PinnedStackListener listener : mListeners) {
            listener.onAspectRatioChanged(aspectRatio);
        }
    }

    @BinderThread
    private class PinnedStackListenerImpl extends IPinnedStackListener.Stub {
        @Override
        public void onMovementBoundsChanged(boolean fromImeAdjustment) {
            mShellMainExecutor.execute(() -> {
                PinnedStackListenerForwarder.this.onMovementBoundsChanged(fromImeAdjustment);
            });
        }

        @Override
        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {
            mShellMainExecutor.execute(() -> {
                PinnedStackListenerForwarder.this.onImeVisibilityChanged(imeVisible, imeHeight);
            });
        }

        @Override
        public void onActionsChanged(ParceledListSlice<RemoteAction> actions) {
            mShellMainExecutor.execute(() -> {
                PinnedStackListenerForwarder.this.onActionsChanged(actions);
            });
        }

        @Override
        public void onActivityHidden(ComponentName componentName) {
            mShellMainExecutor.execute(() -> {
                PinnedStackListenerForwarder.this.onActivityHidden(componentName);
            });
        }

        @Override
        public void onAspectRatioChanged(float aspectRatio) {
            mShellMainExecutor.execute(() -> {
                PinnedStackListenerForwarder.this.onAspectRatioChanged(aspectRatio);
            });
        }
    }

    /**
     * A counterpart of {@link IPinnedStackListener} with empty implementations.
     * Subclasses can ignore those methods they do not intend to take action upon.
     */
    public static class PinnedStackListener {
        public void onMovementBoundsChanged(boolean fromImeAdjustment) {}

        public void onImeVisibilityChanged(boolean imeVisible, int imeHeight) {}

        public void onActionsChanged(ParceledListSlice<RemoteAction> actions) {}

        public void onActivityHidden(ComponentName componentName) {}

        public void onAspectRatioChanged(float aspectRatio) {}
    }
}
