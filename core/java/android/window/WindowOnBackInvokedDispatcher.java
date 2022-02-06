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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.compat.CompatChanges;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.Log;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.OnBackInvokedCallback;
import android.view.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;

/**
 * Provides window based implementation of {@link android.view.OnBackInvokedDispatcher}.
 *
 * Callbacks with higher priorities receive back dispatching first.
 * Within the same priority, callbacks receive back dispatching in the reverse order
 * in which they are added.
 *
 * When the top priority callback is updated, the new callback is propagated to the Window Manager
 * if the window the instance is associated with has been attached. It is allowed to register /
 * unregister {@link android.view.OnBackInvokedCallback}s before the window is attached, although
 * callbacks will not receive dispatches until window attachment.
 *
 * @hide
 */
public class WindowOnBackInvokedDispatcher implements OnBackInvokedDispatcher {
    private IWindowSession mWindowSession;
    private IWindow mWindow;
    private static final String TAG = "WindowOnBackDispatcher";
    private static final boolean DEBUG = false;
    private static final String BACK_PREDICTABILITY_PROP = "persist.debug.back_predictability";
    private static final boolean IS_BACK_PREDICTABILITY_ENABLED = SystemProperties
            .getInt(BACK_PREDICTABILITY_PROP, 0) > 0;

    /** The currently most prioritized callback. */
    @Nullable
    private OnBackInvokedCallbackWrapper mTopCallback;

    /** Convenience hashmap to quickly decide if a callback has been added. */
    private final HashMap<OnBackInvokedCallback, Integer> mAllCallbacks = new HashMap<>();
    /** Holds all callbacks by priorities. */
    private final TreeMap<Integer, ArrayList<OnBackInvokedCallback>>
            mOnBackInvokedCallbacks = new TreeMap<>();

    /**
     * Sends the pending top callback (if one exists) to WM when the view root
     * is attached a window.
     */
    public void attachToWindow(@NonNull IWindowSession windowSession, @NonNull IWindow window) {
        mWindowSession = windowSession;
        mWindow = window;
        if (mTopCallback != null) {
            setTopOnBackInvokedCallback(mTopCallback);
        }
    }

    /** Detaches the dispatcher instance from its window. */
    public void detachFromWindow() {
        mWindow = null;
        mWindowSession = null;
    }

    // TODO: Take an Executor for the callback to run on.
    @Override
    public void registerOnBackInvokedCallback(
            @NonNull OnBackInvokedCallback callback, @Priority int priority) {
        if (priority < 0) {
            throw new IllegalArgumentException("Application registered OnBackInvokedCallback "
                    + "cannot have negative priority. Priority: " + priority);
        }
        registerOnBackInvokedCallbackUnchecked(callback, priority);
    }

    private void registerOnBackInvokedCallbackUnchecked(
            @NonNull OnBackInvokedCallback callback, @Priority int priority) {
        if (!mOnBackInvokedCallbacks.containsKey(priority)) {
            mOnBackInvokedCallbacks.put(priority, new ArrayList<>());
        }
        ArrayList<OnBackInvokedCallback> callbacks = mOnBackInvokedCallbacks.get(priority);

        // If callback has already been added, remove it and re-add it.
        if (mAllCallbacks.containsKey(callback)) {
            if (DEBUG) {
                Log.i(TAG, "Callback already added. Removing and re-adding it.");
            }
            Integer prevPriority = mAllCallbacks.get(callback);
            mOnBackInvokedCallbacks.get(prevPriority).remove(callback);
        }

        callbacks.add(callback);
        mAllCallbacks.put(callback, priority);
        if (mTopCallback == null || (mTopCallback.getCallback() != callback
                && mAllCallbacks.get(mTopCallback.getCallback()) <= priority)) {
            setTopOnBackInvokedCallback(new OnBackInvokedCallbackWrapper(callback, priority));
        }
    }

    @Override
    public void unregisterOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback) {
        if (!mAllCallbacks.containsKey(callback)) {
            if (DEBUG) {
                Log.i(TAG, "Callback not found. returning...");
            }
            return;
        }
        Integer priority = mAllCallbacks.get(callback);
        mOnBackInvokedCallbacks.get(priority).remove(callback);
        mAllCallbacks.remove(callback);
        if (mTopCallback != null && mTopCallback.getCallback() == callback) {
            findAndSetTopOnBackInvokedCallback();
        }
    }

    @Override
    public void registerSystemOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback) {
        registerOnBackInvokedCallbackUnchecked(callback, OnBackInvokedDispatcher.PRIORITY_SYSTEM);
    }

    /** Clears all registered callbacks on the instance. */
    public void clear() {
        mAllCallbacks.clear();
        mTopCallback = null;
        mOnBackInvokedCallbacks.clear();
    }

    /**
     * Iterates through all callbacks to find the most prioritized one and pushes it to
     * window manager.
     */
    private void findAndSetTopOnBackInvokedCallback() {
        if (mAllCallbacks.isEmpty()) {
            setTopOnBackInvokedCallback(null);
            return;
        }

        for (Integer priority : mOnBackInvokedCallbacks.descendingKeySet()) {
            ArrayList<OnBackInvokedCallback> callbacks = mOnBackInvokedCallbacks.get(priority);
            if (!callbacks.isEmpty()) {
                OnBackInvokedCallbackWrapper callback = new OnBackInvokedCallbackWrapper(
                        callbacks.get(callbacks.size() - 1), priority);
                setTopOnBackInvokedCallback(callback);
                return;
            }
        }
        setTopOnBackInvokedCallback(null);
    }

    // Pushes the top priority callback to window manager.
    private void setTopOnBackInvokedCallback(@Nullable OnBackInvokedCallbackWrapper callback) {
        mTopCallback = callback;
        if (mWindowSession == null || mWindow == null) {
            return;
        }
        try {
            mWindowSession.setOnBackInvokedCallback(mWindow, mTopCallback);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set OnBackInvokedCallback to WM. Error: " + e);
        }
    }

    private class OnBackInvokedCallbackWrapper extends IOnBackInvokedCallback.Stub {
        private final OnBackInvokedCallback mCallback;
        private final @Priority int mPriority;

        OnBackInvokedCallbackWrapper(
                @NonNull OnBackInvokedCallback callback, @Priority int priority) {
            mCallback = callback;
            mPriority = priority;
        }

        @NonNull
        public OnBackInvokedCallback getCallback() {
            return mCallback;
        }

        @Override
        public void onBackStarted() throws RemoteException {
            Handler.getMain().post(() -> mCallback.onBackStarted());
        }

        @Override
        public void onBackProgressed(int touchX, int touchY, float progress)
                throws RemoteException {
            Handler.getMain().post(() -> mCallback.onBackProgressed(touchX, touchY, progress));
        }

        @Override
        public void onBackCancelled() throws RemoteException {
            Handler.getMain().post(() -> mCallback.onBackCancelled());
        }

        @Override
        public void onBackInvoked() throws RemoteException {
            Handler.getMain().post(() -> mCallback.onBackInvoked());
        }
    }

    @Override
    public OnBackInvokedCallback getTopCallback() {
        return mTopCallback == null ? null : mTopCallback.getCallback();
    }

    /**
     * Returns if the legacy back behavior should be used.
     *
     * Legacy back behavior dispatches KEYCODE_BACK instead of invoking the application registered
     * {@link android.view.OnBackInvokedCallback}.
     *
     */
    public static boolean shouldUseLegacyBack() {
        return !CompatChanges.isChangeEnabled(DISPATCH_BACK_INVOCATION_AHEAD_OF_TIME)
                || !IS_BACK_PREDICTABILITY_ENABLED;
    }
}
