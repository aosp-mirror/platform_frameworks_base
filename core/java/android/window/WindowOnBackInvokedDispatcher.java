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
import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.view.IWindow;
import android.view.IWindowSession;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;

/**
 * Provides window based implementation of {@link OnBackInvokedDispatcher}.
 * <p>
 * Callbacks with higher priorities receive back dispatching first.
 * Within the same priority, callbacks receive back dispatching in the reverse order
 * in which they are added.
 * <p>
 * When the top priority callback is updated, the new callback is propagated to the Window Manager
 * if the window the instance is associated with has been attached. It is allowed to register /
 * unregister {@link OnBackInvokedCallback}s before the window is attached, although
 * callbacks will not receive dispatches until window attachment.
 *
 * @hide
 */
public class WindowOnBackInvokedDispatcher implements OnBackInvokedDispatcher {
    private IWindowSession mWindowSession;
    private IWindow mWindow;
    private static final String TAG = "WindowOnBackDispatcher";
    private static final boolean ENABLE_PREDICTIVE_BACK = SystemProperties
            .getInt("persist.wm.debug.predictive_back", 1) != 0;
    private static final boolean ALWAYS_ENFORCE_PREDICTIVE_BACK = SystemProperties
            .getInt("persist.wm.debug.predictive_back_always_enforce", 0) != 0;

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
        if (!mAllCallbacks.isEmpty()) {
            setTopOnBackInvokedCallback(getTopCallback());
        }
    }

    /** Detaches the dispatcher instance from its window. */
    public void detachFromWindow() {
        clear();
        mWindow = null;
        mWindowSession = null;
    }

    // TODO: Take an Executor for the callback to run on.
    @Override
    public void registerOnBackInvokedCallback(
            @Priority int priority, @NonNull OnBackInvokedCallback callback) {
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

        OnBackInvokedCallback previousTopCallback = getTopCallback();
        callbacks.add(callback);
        mAllCallbacks.put(callback, priority);
        if (previousTopCallback == null
                || (previousTopCallback != callback
                        && mAllCallbacks.get(previousTopCallback) <= priority)) {
            setTopOnBackInvokedCallback(callback);
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
        OnBackInvokedCallback previousTopCallback = getTopCallback();
        Integer priority = mAllCallbacks.get(callback);
        ArrayList<OnBackInvokedCallback> callbacks = mOnBackInvokedCallbacks.get(priority);
        callbacks.remove(callback);
        if (callbacks.isEmpty()) {
            mOnBackInvokedCallbacks.remove(priority);
        }
        mAllCallbacks.remove(callback);
        // Re-populate the top callback to WM if the removed callback was previously the top one.
        if (previousTopCallback == callback) {
            setTopOnBackInvokedCallback(getTopCallback());
        }
    }

    @Override
    public void registerSystemOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback) {
        registerOnBackInvokedCallbackUnchecked(callback, OnBackInvokedDispatcher.PRIORITY_SYSTEM);
    }

    /** Clears all registered callbacks on the instance. */
    public void clear() {
        if (!mAllCallbacks.isEmpty()) {
            // Clear binder references in WM.
            setTopOnBackInvokedCallback(null);
        }
        mAllCallbacks.clear();
        mOnBackInvokedCallbacks.clear();
    }

    private void setTopOnBackInvokedCallback(@Nullable OnBackInvokedCallback callback) {
        if (mWindowSession == null || mWindow == null) {
            return;
        }
        try {
            if (callback == null) {
                mWindowSession.setOnBackInvokedCallbackInfo(mWindow, null);
            } else {
                int priority = mAllCallbacks.get(callback);
                mWindowSession.setOnBackInvokedCallbackInfo(
                        mWindow, new OnBackInvokedCallbackInfo(
                                new OnBackInvokedCallbackWrapper(callback), priority));
            }
            if (DEBUG && callback == null) {
                Log.d(TAG, TextUtils.formatSimple("setTopOnBackInvokedCallback(null) Callers:%s",
                        Debug.getCallers(5, "  ")));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set OnBackInvokedCallback to WM. Error: " + e);
        }
    }

    public OnBackInvokedCallback getTopCallback() {
        if (mAllCallbacks.isEmpty()) {
            return null;
        }
        for (Integer priority : mOnBackInvokedCallbacks.descendingKeySet()) {
            ArrayList<OnBackInvokedCallback> callbacks = mOnBackInvokedCallbacks.get(priority);
            if (!callbacks.isEmpty()) {
                return callbacks.get(callbacks.size() - 1);
            }
        }
        return null;
    }

    private static class OnBackInvokedCallbackWrapper extends IOnBackInvokedCallback.Stub {
        private final WeakReference<OnBackInvokedCallback> mCallback;

        OnBackInvokedCallbackWrapper(@NonNull OnBackInvokedCallback callback) {
            mCallback = new WeakReference<>(callback);
        }

        @Override
        public void onBackStarted() {
            Handler.getMain().post(() -> {
                final OnBackAnimationCallback callback = getBackAnimationCallback();
                if (callback != null) {
                    callback.onBackStarted();
                }
            });
        }

        @Override
        public void onBackProgressed(BackEvent backEvent) {
            Handler.getMain().post(() -> {
                final OnBackAnimationCallback callback = getBackAnimationCallback();
                if (callback != null) {
                    callback.onBackProgressed(backEvent);
                }
            });
        }

        @Override
        public void onBackCancelled() {
            Handler.getMain().post(() -> {
                final OnBackAnimationCallback callback = getBackAnimationCallback();
                if (callback != null) {
                    callback.onBackCancelled();
                }
            });
        }

        @Override
        public void onBackInvoked() throws RemoteException {
            Handler.getMain().post(() -> {
                final OnBackInvokedCallback callback = mCallback.get();
                if (callback == null) {
                    return;
                }

                callback.onBackInvoked();
            });
        }

        @Nullable
        private OnBackAnimationCallback getBackAnimationCallback() {
            OnBackInvokedCallback callback = mCallback.get();
            return callback instanceof OnBackAnimationCallback ? (OnBackAnimationCallback) callback
                    : null;
        }
    }

    /**
     * Returns if the legacy back behavior should be used.
     * <p>
     * Legacy back behavior dispatches KEYCODE_BACK instead of invoking the application registered
     * {@link OnBackInvokedCallback}.
     */
    public static boolean isOnBackInvokedCallbackEnabled(@Nullable Context context) {
        // new back is enabled if the feature flag is enabled AND the app does not explicitly
        // request legacy back.
        boolean featureFlagEnabled = ENABLE_PREDICTIVE_BACK;
        // If the context is null, we assume true and fallback on the two other conditions.
        boolean appRequestsPredictiveBack =
                context != null && context.getApplicationInfo().isOnBackInvokedCallbackEnabled();

        if (DEBUG) {
            Log.d(TAG, TextUtils.formatSimple("App: %s featureFlagEnabled=%s "
                            + "appRequestsPredictiveBack=%s alwaysEnforce=%s",
                    context != null ? context.getApplicationInfo().packageName : "null context",
                    featureFlagEnabled, appRequestsPredictiveBack, ALWAYS_ENFORCE_PREDICTIVE_BACK));
        }

        return featureFlagEnabled && (appRequestsPredictiveBack || ALWAYS_ENFORCE_PREDICTIVE_BACK);
    }
}
