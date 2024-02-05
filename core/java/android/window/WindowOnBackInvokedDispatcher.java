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
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.TypedArray;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.view.IWindow;
import android.view.IWindowSession;

import androidx.annotation.VisibleForTesting;


import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Supplier;

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
    private static final boolean PREDICTIVE_BACK_FALLBACK_WINDOW_ATTRIBUTE =
            SystemProperties.getInt("persist.wm.debug.predictive_back_fallback_window_attribute", 0)
                    != 0;
    @Nullable
    private ImeOnBackInvokedDispatcher mImeDispatcher;

    /** Convenience hashmap to quickly decide if a callback has been added. */
    private final HashMap<OnBackInvokedCallback, Integer> mAllCallbacks = new HashMap<>();
    /** Holds all callbacks by priorities. */

    @VisibleForTesting
    public final TreeMap<Integer, ArrayList<OnBackInvokedCallback>>
            mOnBackInvokedCallbacks = new TreeMap<>();
    private Checker mChecker;

    public WindowOnBackInvokedDispatcher(@NonNull Context context) {
        mChecker = new Checker(context);
    }

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
        if (mChecker.checkApplicationCallbackRegistration(priority, callback)) {
            registerOnBackInvokedCallbackUnchecked(callback, priority);
        }
    }

    /**
     * Register a callback bypassing platform checks. This is used to register compatibility
     * callbacks.
     */
    public void registerOnBackInvokedCallbackUnchecked(
            @NonNull OnBackInvokedCallback callback, @Priority int priority) {
        if (mImeDispatcher != null) {
            mImeDispatcher.registerOnBackInvokedCallback(priority, callback);
            return;
        }
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
        if (mImeDispatcher != null) {
            mImeDispatcher.unregisterOnBackInvokedCallback(callback);
            return;
        }
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
            // We should call onBackCancelled() when an active callback is removed from dispatcher.
            sendCancelledIfInProgress(callback);
            setTopOnBackInvokedCallback(getTopCallback());
        }
    }

    /**
     * Indicates if the dispatcher is actively dispatching to a callback.
     */
    public boolean isDispatching() {
        return mIsDispatching;
    }

    private void onStartDispatching() {
        mIsDispatching = true;
    }

    private void onStopDispatching() {
        mIsDispatching = false;
    }

    private void sendCancelledIfInProgress(@NonNull OnBackInvokedCallback callback) {
        boolean isInProgress = mProgressAnimator.isBackAnimationInProgress();
        if (isInProgress && callback instanceof OnBackAnimationCallback) {
            OnBackAnimationCallback animatedCallback = (OnBackAnimationCallback) callback;
            animatedCallback.onBackCancelled();
            if (DEBUG) {
                Log.d(TAG, "sendCancelIfRunning: callback canceled");
            }
        } else {
            Log.w(TAG, "sendCancelIfRunning: isInProgress=" + isInProgress
                    + "callback=" + callback);
        }
    }

    @Override
    public void registerSystemOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback) {
        registerOnBackInvokedCallbackUnchecked(callback, OnBackInvokedDispatcher.PRIORITY_SYSTEM);
    }

    /** Clears all registered callbacks on the instance. */
    public void clear() {
        if (mImeDispatcher != null) {
            mImeDispatcher.clear();
            mImeDispatcher = null;
        }
        if (!mAllCallbacks.isEmpty()) {
            OnBackInvokedCallback topCallback = getTopCallback();
            if (topCallback != null) {
                sendCancelledIfInProgress(topCallback);
            } else {
                // Should not be possible
                Log.e(TAG, "There is no topCallback, even if mAllCallbacks is not empty");
            }
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
            OnBackInvokedCallbackInfo callbackInfo = null;
            if (callback != null) {
                int priority = mAllCallbacks.get(callback);
                final IOnBackInvokedCallback iCallback =
                        callback instanceof ImeOnBackInvokedDispatcher
                                    .ImeOnBackInvokedCallback
                                ? ((ImeOnBackInvokedDispatcher.ImeOnBackInvokedCallback)
                                        callback).getIOnBackInvokedCallback()
                                : new OnBackInvokedCallbackWrapper(callback, this);
                callbackInfo = new OnBackInvokedCallbackInfo(
                        iCallback,
                        priority,
                        callback instanceof OnBackAnimationCallback);
            }
            mWindowSession.setOnBackInvokedCallbackInfo(mWindow, callbackInfo);
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

    @NonNull
    private static final BackProgressAnimator mProgressAnimator = new BackProgressAnimator();
    private boolean mIsDispatching = false;

    /**
     * The {@link Context} in ViewRootImp and Activity could be different, this will make sure it
     * could update the checker condition base on the real context when binding the proxy
     * dispatcher in PhoneWindow.
     */
    public void updateContext(@NonNull Context context) {
        mChecker = new Checker(context);
    }

    /**
     * Returns false if the legacy back behavior should be used.
     */
    public boolean isOnBackInvokedCallbackEnabled() {
        return isOnBackInvokedCallbackEnabled(mChecker.getContext());
    }

    /**
     * Dump information about this WindowOnBackInvokedDispatcher
     * @param prefix the prefix that will be prepended to each line of the produced output
     * @param writer the writer that will receive the resulting text
     */
    public void dump(String prefix, PrintWriter writer) {
        String innerPrefix = prefix + "    ";
        writer.println(prefix + "WindowOnBackDispatcher:");
        if (mAllCallbacks.isEmpty()) {
            writer.println(prefix + "<None>");
            return;
        }

        writer.println(innerPrefix + "Top Callback: " + getTopCallback());
        writer.println(innerPrefix + "Callbacks: ");
        mAllCallbacks.forEach((callback, priority) -> {
            writer.println(innerPrefix + "  Callback: " + callback + " Priority=" + priority);
        });
    }

    static class OnBackInvokedCallbackWrapper extends IOnBackInvokedCallback.Stub {
        static class CallbackRef {
            final WeakReference<OnBackInvokedCallback> mWeakRef;
            final OnBackInvokedCallback mStrongRef;
            CallbackRef(@NonNull OnBackInvokedCallback callback, boolean useWeakRef) {
                if (useWeakRef) {
                    mWeakRef = new WeakReference<>(callback);
                    mStrongRef = null;
                } else {
                    mStrongRef = callback;
                    mWeakRef = null;
                }
            }

            OnBackInvokedCallback get() {
                if (mStrongRef != null) {
                    return mStrongRef;
                }
                return mWeakRef.get();
            }
        }
        final CallbackRef mCallbackRef;
        /**
         * The dispatcher this callback is registered with.
         * This can be null for callbacks on {@link ImeOnBackInvokedDispatcher} because they are
         * forwarded and registered on the app's {@link WindowOnBackInvokedDispatcher}. */
        @Nullable
        private final WindowOnBackInvokedDispatcher mDispatcher;

        OnBackInvokedCallbackWrapper(
                @NonNull OnBackInvokedCallback callback,
                WindowOnBackInvokedDispatcher dispatcher) {
            mCallbackRef = new CallbackRef(callback, true /* useWeakRef */);
            mDispatcher = dispatcher;
        }

        OnBackInvokedCallbackWrapper(
                @NonNull OnBackInvokedCallback callback,
                boolean useWeakRef) {
            mCallbackRef = new CallbackRef(callback, useWeakRef);
            mDispatcher = null;
        }

        @Override
        public void onBackStarted(BackMotionEvent backEvent) {
            Handler.getMain().post(() -> {
                if (mDispatcher != null) {
                    mDispatcher.onStartDispatching();
                }
                final OnBackAnimationCallback callback = getBackAnimationCallback();
                if (callback != null) {
                    callback.onBackStarted(new BackEvent(
                            backEvent.getTouchX(), backEvent.getTouchY(),
                            backEvent.getProgress(), backEvent.getSwipeEdge()));
                    mProgressAnimator.onBackStarted(backEvent, event ->
                            callback.onBackProgressed(event));
                }
            });
        }

        @Override
        public void onBackProgressed(BackMotionEvent backEvent) {
            Handler.getMain().post(() -> {
                final OnBackAnimationCallback callback = getBackAnimationCallback();
                if (callback != null) {
                    mProgressAnimator.onBackProgressed(backEvent);
                }
            });
        }

        @Override
        public void onBackCancelled() {
            Handler.getMain().post(() -> {
                if (mDispatcher != null) {
                    mDispatcher.onStopDispatching();
                }
                mProgressAnimator.onBackCancelled(() -> {
                    final OnBackAnimationCallback callback = getBackAnimationCallback();
                    if (callback != null) {
                        callback.onBackCancelled();
                    }
                });
            });
        }

        @Override
        public void onBackInvoked() throws RemoteException {
            Handler.getMain().post(() -> {
                if (mDispatcher != null) {
                    mDispatcher.onStopDispatching();
                }
                boolean isInProgress = mProgressAnimator.isBackAnimationInProgress();
                mProgressAnimator.reset();
                final OnBackInvokedCallback callback = mCallbackRef.get();
                if (callback == null) {
                    Log.d(TAG, "Trying to call onBackInvoked() on a null callback reference.");
                    return;
                }
                if (callback instanceof OnBackAnimationCallback && !isInProgress) {
                    Log.w(TAG, "ProgressAnimator was not in progress, skip onBackInvoked().");
                    return;
                }
                callback.onBackInvoked();
            });
        }

        @Nullable
        private OnBackAnimationCallback getBackAnimationCallback() {
            OnBackInvokedCallback callback = mCallbackRef.get();
            return callback instanceof OnBackAnimationCallback ? (OnBackAnimationCallback) callback
                    : null;
        }
    }

    /**
     * Returns false if the legacy back behavior should be used.
     * <p>
     * Legacy back behavior dispatches KEYCODE_BACK instead of invoking the application registered
     * {@link OnBackInvokedCallback}.
     */
    public static boolean isOnBackInvokedCallbackEnabled(@NonNull Context context) {
        final Context originalContext = context;
        while ((context instanceof ContextWrapper) && !(context instanceof Activity)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        final ActivityInfo activityInfo = (context instanceof Activity)
                ? ((Activity) context).getActivityInfo() : null;
        final ApplicationInfo applicationInfo = context.getApplicationInfo();

        return WindowOnBackInvokedDispatcher
                .isOnBackInvokedCallbackEnabled(activityInfo, applicationInfo,
                        () -> originalContext);
    }

    @Override
    public void setImeOnBackInvokedDispatcher(
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) {
        mImeDispatcher = imeDispatcher;
    }

    /** Returns true if a non-null {@link ImeOnBackInvokedDispatcher} has been set. **/
    public boolean hasImeOnBackInvokedDispatcher() {
        return mImeDispatcher != null;
    }

    /**
     * Class used to check whether a callback can be registered or not. This is meant to be
     * shared with {@link ProxyOnBackInvokedDispatcher} which needs to do the same checks.
     */
    public static class Checker {
        private WeakReference<Context> mContext;

        public Checker(@NonNull Context context) {
            mContext = new WeakReference<>(context);
        }

        /**
         * Checks whether the given callback can be registered with the given priority.
         * @return true if the callback can be added.
         * @throws IllegalArgumentException if the priority is negative.
         */
        public boolean checkApplicationCallbackRegistration(int priority,
                OnBackInvokedCallback callback) {
            if (!WindowOnBackInvokedDispatcher.isOnBackInvokedCallbackEnabled(getContext())
                    && !(callback instanceof CompatOnBackInvokedCallback)) {
                Log.w(TAG,
                        "OnBackInvokedCallback is not enabled for the application."
                                + "\nSet 'android:enableOnBackInvokedCallback=\"true\"' in the"
                                + " application manifest.");
                return false;
            }
            if (priority < 0) {
                throw new IllegalArgumentException("Application registered OnBackInvokedCallback "
                        + "cannot have negative priority. Priority: " + priority);
            }
            Objects.requireNonNull(callback);
            return true;
        }

        private Context getContext() {
            return mContext.get();
        }
    }

    /**
     * @hide
     */
    public static boolean isOnBackInvokedCallbackEnabled(@Nullable ActivityInfo activityInfo,
            @NonNull ApplicationInfo applicationInfo,
            @NonNull Supplier<Context> contextSupplier) {
        // new back is enabled if the feature flag is enabled AND the app does not explicitly
        // request legacy back.
        if (!ENABLE_PREDICTIVE_BACK) {
            return false;
        }

        if (ALWAYS_ENFORCE_PREDICTIVE_BACK) {
            return true;
        }

        boolean requestsPredictiveBack;
        // Activity
        if (activityInfo != null && activityInfo.hasOnBackInvokedCallbackEnabled()) {
            requestsPredictiveBack = activityInfo.isOnBackInvokedCallbackEnabled();
            if (DEBUG) {
                Log.d(TAG, TextUtils.formatSimple(
                        "Activity: %s isPredictiveBackEnabled=%s",
                        activityInfo.getComponentName(),
                        requestsPredictiveBack));
            }
            return requestsPredictiveBack;
        }

        // Application
        requestsPredictiveBack = applicationInfo.isOnBackInvokedCallbackEnabled();
        if (DEBUG) {
            Log.d(TAG, TextUtils.formatSimple("App: %s requestsPredictiveBack=%s",
                    applicationInfo.packageName,
                    requestsPredictiveBack));
        }
        if (requestsPredictiveBack) {
            return true;
        }

        if (PREDICTIVE_BACK_FALLBACK_WINDOW_ATTRIBUTE) {
            // Compatibility check for legacy window style flag used by Wear OS.
            // Note on compatibility behavior:
            // 1. windowSwipeToDismiss should be respected for all apps not opted in.
            // 2. windowSwipeToDismiss should be true for all apps not opted in, which
            //    enables the PB animation for them.
            // 3. windowSwipeToDismiss=false should be respected for apps not opted in,
            //    which disables PB & onBackPressed caused by BackAnimController's
            //    setTrigger(true)
            // Use the original context to resolve the styled attribute so that they stay
            // true to the window.
            final Context context = contextSupplier.get();
            boolean windowSwipeToDismiss = true;
            if (context != null) {
                final TypedArray array = context.obtainStyledAttributes(
                            new int[]{android.R.attr.windowSwipeToDismiss});
                if (array.getIndexCount() > 0) {
                    windowSwipeToDismiss = array.getBoolean(0, true);
                }
                array.recycle();
            }

            if (DEBUG) {
                Log.i(TAG, "falling back to windowSwipeToDismiss: " + windowSwipeToDismiss);
            }

            requestsPredictiveBack = windowSwipeToDismiss;
        }
        return requestsPredictiveBack;
    }
}
