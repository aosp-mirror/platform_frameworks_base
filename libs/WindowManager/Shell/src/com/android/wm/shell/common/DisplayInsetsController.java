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

package com.android.wm.shell.common;

import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.IDisplayWindowInsetsController;
import android.view.IWindowManager;
import android.view.InsetsSourceControl;
import android.view.InsetsState;

import androidx.annotation.BinderThread;

import com.android.wm.shell.common.annotations.ShellMainThread;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages insets from the core.
 */
public class DisplayInsetsController implements DisplayController.OnDisplaysChangedListener {
    private static final String TAG = "DisplayInsetsController";

    private final IWindowManager mWmService;
    private final ShellExecutor mMainExecutor;
    private final DisplayController mDisplayController;
    private final SparseArray<PerDisplay> mInsetsPerDisplay = new SparseArray<>();
    private final SparseArray<CopyOnWriteArrayList<OnInsetsChangedListener>> mListeners =
            new SparseArray<>();

    public DisplayInsetsController(IWindowManager wmService, DisplayController displayController,
            ShellExecutor mainExecutor) {
        mWmService = wmService;
        mDisplayController = displayController;
        mMainExecutor = mainExecutor;
    }

    /**
     * Starts listening for insets for each display.
     **/
    public void initialize() {
        mDisplayController.addDisplayWindowListener(this);
    }

    /**
     * Adds a callback to listen for insets changes for a particular display.  Note that the
     * listener will not be updated with the existing state of the insets on that display.
     */
    public void addInsetsChangedListener(int displayId, OnInsetsChangedListener listener) {
        CopyOnWriteArrayList<OnInsetsChangedListener> listeners = mListeners.get(displayId);
        if (listeners == null) {
            listeners = new CopyOnWriteArrayList<>();
            mListeners.put(displayId, listeners);
        }
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Removes a callback listening for insets changes from a particular display.
     */
    public void removeInsetsChangedListener(int displayId, OnInsetsChangedListener listener) {
        CopyOnWriteArrayList<OnInsetsChangedListener> listeners = mListeners.get(displayId);
        if (listeners == null) {
            return;
        }
        listeners.remove(listener);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        PerDisplay pd = new PerDisplay(displayId);
        pd.register();
        mInsetsPerDisplay.put(displayId, pd);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        PerDisplay pd = mInsetsPerDisplay.get(displayId);
        if (pd == null) {
            return;
        }
        pd.unregister();
        mInsetsPerDisplay.remove(displayId);
    }

    /**
     * An implementation of {@link IDisplayWindowInsetsController} for a given display id.
     **/
    public class PerDisplay {
        private final int mDisplayId;
        private final DisplayWindowInsetsControllerImpl mInsetsControllerImpl =
                new DisplayWindowInsetsControllerImpl();

        public PerDisplay(int displayId) {
            mDisplayId = displayId;
        }

        public void register() {
            try {
                mWmService.setDisplayWindowInsetsController(mDisplayId, mInsetsControllerImpl);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to set insets controller on display " + mDisplayId);
            }
        }

        public void unregister() {
            try {
                mWmService.setDisplayWindowInsetsController(mDisplayId, null);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to remove insets controller on display " + mDisplayId);
            }
        }

        private void insetsChanged(InsetsState insetsState) {
            CopyOnWriteArrayList<OnInsetsChangedListener> listeners = mListeners.get(mDisplayId);
            if (listeners == null) {
                return;
            }
            mDisplayController.updateDisplayInsets(mDisplayId, insetsState);
            for (OnInsetsChangedListener listener : listeners) {
                listener.insetsChanged(insetsState);
            }
        }

        private void insetsControlChanged(InsetsState insetsState,
                InsetsSourceControl[] activeControls) {
            CopyOnWriteArrayList<OnInsetsChangedListener> listeners = mListeners.get(mDisplayId);
            if (listeners == null) {
                return;
            }
            for (OnInsetsChangedListener listener : listeners) {
                listener.insetsControlChanged(insetsState, activeControls);
            }
        }

        private void showInsets(int types, boolean fromIme) {
            CopyOnWriteArrayList<OnInsetsChangedListener> listeners = mListeners.get(mDisplayId);
            if (listeners == null) {
                return;
            }
            for (OnInsetsChangedListener listener : listeners) {
                listener.showInsets(types, fromIme);
            }
        }

        private void hideInsets(int types, boolean fromIme) {
            CopyOnWriteArrayList<OnInsetsChangedListener> listeners = mListeners.get(mDisplayId);
            if (listeners == null) {
                return;
            }
            for (OnInsetsChangedListener listener : listeners) {
                listener.hideInsets(types, fromIme);
            }
        }

        private void topFocusedWindowChanged(String packageName) {
            CopyOnWriteArrayList<OnInsetsChangedListener> listeners = mListeners.get(mDisplayId);
            if (listeners == null) {
                return;
            }
            for (OnInsetsChangedListener listener : listeners) {
                listener.topFocusedWindowChanged(packageName);
            }
        }

        @BinderThread
        private class DisplayWindowInsetsControllerImpl
                extends IDisplayWindowInsetsController.Stub {
            @Override
            public void topFocusedWindowChanged(String packageName) throws RemoteException {
                mMainExecutor.execute(() -> {
                    PerDisplay.this.topFocusedWindowChanged(packageName);
                });
            }

            @Override
            public void insetsChanged(InsetsState insetsState) throws RemoteException {
                mMainExecutor.execute(() -> {
                    PerDisplay.this.insetsChanged(insetsState);
                });
            }

            @Override
            public void insetsControlChanged(InsetsState insetsState,
                    InsetsSourceControl[] activeControls) throws RemoteException {
                mMainExecutor.execute(() -> {
                    PerDisplay.this.insetsControlChanged(insetsState, activeControls);
                });
            }

            @Override
            public void showInsets(int types, boolean fromIme) throws RemoteException {
                mMainExecutor.execute(() -> {
                    PerDisplay.this.showInsets(types, fromIme);
                });
            }

            @Override
            public void hideInsets(int types, boolean fromIme) throws RemoteException {
                mMainExecutor.execute(() -> {
                    PerDisplay.this.hideInsets(types, fromIme);
                });
            }
        }
    }

    /**
     * Gets notified whenever the insets change.
     *
     * @see IDisplayWindowInsetsController
     */
    @ShellMainThread
    public interface OnInsetsChangedListener {
        /**
         * Called when top focused window changes to determine whether or not to take over insets
         * control. Won't be called if config_remoteInsetsControllerControlsSystemBars is false.
         * @param packageName: Passes the top package name
         */
        void topFocusedWindowChanged(String packageName);

        /**
         * Called when the window insets configuration has changed.
         */
        void insetsChanged(InsetsState insetsState);

        /**
         * Called when this window retrieved control over a specified set of insets sources.
         */
        void insetsControlChanged(InsetsState insetsState,
                InsetsSourceControl[] activeControls);

        /**
         * Called when a set of insets source window should be shown by policy.
         *
         * @param types internal insets types (WindowInsets.Type.InsetsType) to show
         * @param fromIme true if this request originated from IME (InputMethodService).
         */
        void showInsets(int types, boolean fromIme);

        /**
         * Called when a set of insets source window should be hidden by policy.
         *
         * @param types internal insets types (WindowInsets.Type.InsetsType) to hide
         * @param fromIme true if this request originated from IME (InputMethodService).
         */
        void hideInsets(int types, boolean fromIme);
    }
}