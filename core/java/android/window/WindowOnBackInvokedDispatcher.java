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

import static com.android.window.flags.Flags.predictiveBackPrioritySystemNavigationObserver;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.ImeBackAnimationController;
import android.view.MotionEvent;
import android.view.ViewRootImpl;

import androidx.annotation.VisibleForTesting;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
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
    private ViewRootImpl mViewRoot;
    @VisibleForTesting
    public final BackTouchTracker mTouchTracker = new BackTouchTracker();
    @VisibleForTesting
    public final BackProgressAnimator mProgressAnimator = new BackProgressAnimator();
    // The handler to run callbacks on.
    // This should be on the same thread the ViewRootImpl holding this instance is created on.
    @NonNull
    private final Handler mHandler;
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

    @Nullable
    private ImeBackAnimationController mImeBackAnimationController;

    @GuardedBy("mLock")
    /** Convenience hashmap to quickly decide if a callback has been added. */
    private final HashMap<OnBackInvokedCallback, Integer> mAllCallbacks = new HashMap<>();
    /** Holds all callbacks by priorities. */

    @VisibleForTesting
    @GuardedBy("mLock")
    public final TreeMap<Integer, ArrayList<OnBackInvokedCallback>>
            mOnBackInvokedCallbacks = new TreeMap<>();

    @VisibleForTesting
    public OnBackInvokedCallback mSystemNavigationObserverCallback = null;

    private Checker mChecker;
    private final Object mLock = new Object();
    // The threshold for back swipe full progress.
    private float mBackSwipeLinearThreshold;
    private float mNonLinearProgressFactor;

    public WindowOnBackInvokedDispatcher(@NonNull Context context, Looper looper) {
        mChecker = new Checker(context);
        mHandler = new Handler(looper);
    }

    /** Updates the dispatcher state on a new {@link MotionEvent}. */
    public void onMotionEvent(MotionEvent ev) {
        if (!isBackGestureInProgress() || ev == null || ev.getAction() != MotionEvent.ACTION_MOVE) {
            return;
        }
        mTouchTracker.update(ev.getX(), ev.getY());
        if (mTouchTracker.shouldUpdateStartLocation()) {
            // Reset the start location on the first event after starting back, so that
            // the beginning of the animation feels smooth.
            mTouchTracker.updateStartLocation();
        }
        if (!mProgressAnimator.isBackAnimationInProgress()) {
            return;
        }
        final BackMotionEvent backEvent = mTouchTracker.createProgressEvent();
        mProgressAnimator.onBackProgressed(backEvent);
    }

    /**
     * Sends the pending top callback (if one exists) to WM when the view root
     * is attached a window.
     */
    public void attachToWindow(@NonNull IWindowSession windowSession, @NonNull IWindow window,
            @Nullable ViewRootImpl viewRoot,
            @Nullable ImeBackAnimationController imeBackAnimationController) {
        synchronized (mLock) {
            mWindowSession = windowSession;
            mWindow = window;
            mViewRoot = viewRoot;
            mImeBackAnimationController = imeBackAnimationController;
            if (!mAllCallbacks.isEmpty()) {
                setTopOnBackInvokedCallback(getTopCallback());
            }
        }
    }

    /** Detaches the dispatcher instance from its window. */
    public void detachFromWindow() {
        synchronized (mLock) {
            clear();
            mWindow = null;
            mWindowSession = null;
            mViewRoot = null;
            mImeBackAnimationController = null;
        }
    }

    // TODO: Take an Executor for the callback to run on.
    @Override
    public void registerOnBackInvokedCallback(
            @Priority int priority, @NonNull OnBackInvokedCallback callback) {
        if (mChecker.checkApplicationCallbackRegistration(priority, callback)) {
            registerOnBackInvokedCallbackUnchecked(callback, priority);
        }
    }

    private void registerSystemNavigationObserverCallback(@NonNull OnBackInvokedCallback callback) {
        synchronized (mLock) {
            // If callback has already been added as regular callback, remove it.
            if (mAllCallbacks.containsKey(callback)) {
                if (DEBUG) {
                    Log.i(TAG, "Callback already added. Removing and re-adding it as "
                            + "system-navigation-observer-callback.");
                }
                removeCallbackInternal(callback);
            }
            mSystemNavigationObserverCallback = callback;
        }
    }

    /**
     * Register a callback bypassing platform checks. This is used to register compatibility
     * callbacks.
     */
    public void registerOnBackInvokedCallbackUnchecked(
            @NonNull OnBackInvokedCallback callback, @Priority int priority) {
        synchronized (mLock) {
            if (mImeDispatcher != null) {
                mImeDispatcher.registerOnBackInvokedCallback(priority, callback);
                return;
            }
            if (predictiveBackPrioritySystemNavigationObserver()) {
                if (priority == PRIORITY_SYSTEM_NAVIGATION_OBSERVER) {
                    registerSystemNavigationObserverCallback(callback);
                    return;
                }
            }
            if (callback instanceof ImeOnBackInvokedDispatcher.ImeOnBackInvokedCallback) {
                if (callback instanceof ImeOnBackInvokedDispatcher.DefaultImeOnBackAnimationCallback
                        && mImeBackAnimationController != null) {
                    // register ImeBackAnimationController instead to play predictive back animation
                    callback = mImeBackAnimationController;
                }
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
            if (mSystemNavigationObserverCallback == callback) {
                mSystemNavigationObserverCallback = null;
                if (DEBUG) {
                    Log.i(TAG, "Callback already registered (as system-navigation-observer "
                            + "callback). Removing and re-adding it.");
                }
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
    }

    @Override
    public void unregisterOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback) {
        synchronized (mLock) {
            if (mImeDispatcher != null) {
                mImeDispatcher.unregisterOnBackInvokedCallback(callback);
                return;
            }
            if (mSystemNavigationObserverCallback == callback) {
                mSystemNavigationObserverCallback = null;
                return;
            }
            if (callback instanceof ImeOnBackInvokedDispatcher.DefaultImeOnBackAnimationCallback) {
                callback = mImeBackAnimationController;
            }
            if (!mAllCallbacks.containsKey(callback)) {
                if (DEBUG) {
                    Log.i(TAG, "Callback not found. returning...");
                }
                return;
            }
            removeCallbackInternal(callback);
        }
    }

    private void removeCallbackInternal(@NonNull OnBackInvokedCallback callback) {
        OnBackInvokedCallback previousTopCallback = getTopCallback();
        Integer priority = mAllCallbacks.get(callback);
        ArrayList<OnBackInvokedCallback> callbacks = mOnBackInvokedCallbacks.get(priority);
        callbacks.remove(callback);
        if (callbacks.isEmpty()) {
            mOnBackInvokedCallbacks.remove(priority);
        }
        mAllCallbacks.remove(callback);
        // Re-populate the top callback to WM if the removed callback was previously the top
        // one.
        if (previousTopCallback == callback) {
            // We should call onBackCancelled() when an active callback is removed from
            // dispatcher.
            mProgressAnimator.removeOnBackCancelledFinishCallback();
            mProgressAnimator.removeOnBackInvokedFinishCallback();
            sendCancelledIfInProgress(callback);
            mHandler.post(mProgressAnimator::reset);
            setTopOnBackInvokedCallback(getTopCallback());
        }
    }

    /**
     * Indicates if a user gesture is currently in progress.
     */
    public boolean isBackGestureInProgress() {
        synchronized (mLock) {
            return mTouchTracker.isActive();
        }
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
                    + " callback=" + callback);
        }
    }

    @Override
    public void registerSystemOnBackInvokedCallback(@NonNull OnBackInvokedCallback callback) {
        registerOnBackInvokedCallbackUnchecked(callback, OnBackInvokedDispatcher.PRIORITY_SYSTEM);
    }

    /** Clears all registered callbacks on the instance. */
    public void clear() {
        synchronized (mLock) {
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

            // We should also stop running animations since all callbacks have been removed.
            // note: mSpring.skipToEnd(), in ProgressAnimator.reset(), requires the main handler.
            mHandler.post(mProgressAnimator::reset);
            mAllCallbacks.clear();
            mOnBackInvokedCallbacks.clear();
            mSystemNavigationObserverCallback = null;
        }
    }

    private boolean callOnKeyPreIme() {
        if (mViewRoot != null && !isOnBackInvokedCallbackEnabled()) {
            return mViewRoot.injectBackKeyEvents(/*preImeOnly*/ true);
        } else {
            return false;
        }
    }

    /**
     * Tries to call {@link OnBackInvokedCallback#onBackInvoked} on the system navigation observer
     * callback (if one is set and if the top-most regular callback has
     * {@link OnBackInvokedDispatcher#PRIORITY_SYSTEM})
     */
    public void tryInvokeSystemNavigationObserverCallback() {
        OnBackInvokedCallback topCallback = getTopCallback();
        Integer callbackPriority = mAllCallbacks.getOrDefault(topCallback, null);
        if (callbackPriority != null && callbackPriority == PRIORITY_SYSTEM) {
            invokeSystemNavigationObserverCallback();
        }
    }

    private void invokeSystemNavigationObserverCallback() {
        if (mSystemNavigationObserverCallback != null) {
            mSystemNavigationObserverCallback.onBackInvoked();
        }
    }

    private void setTopOnBackInvokedCallback(@Nullable OnBackInvokedCallback callback) {
        if (mWindowSession == null || mWindow == null) {
            return;
        }
        try {
            OnBackInvokedCallbackInfo callbackInfo = null;
            if (callback != null) {
                int priority = mAllCallbacks.get(callback);
                final IOnBackInvokedCallback iCallback = new OnBackInvokedCallbackWrapper(callback,
                        mTouchTracker, mProgressAnimator, mHandler, this::callOnKeyPreIme,
                        this::invokeSystemNavigationObserverCallback,
                        /*isSystemCallback*/ priority == PRIORITY_SYSTEM);
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
        synchronized (mLock) {
            if (mAllCallbacks.isEmpty()) {
                return null;
            }
            for (Integer priority : mOnBackInvokedCallbacks.descendingKeySet()) {
                ArrayList<OnBackInvokedCallback> callbacks = mOnBackInvokedCallbacks.get(priority);
                if (!callbacks.isEmpty()) {
                    return callbacks.get(callbacks.size() - 1);
                }
            }
        }
        return null;
    }

    /**
     * The {@link Context} in ViewRootImp and Activity could be different, this will make sure it
     * could update the checker condition base on the real context when binding the proxy
     * dispatcher in PhoneWindow.
     */
    public void updateContext(@NonNull Context context) {
        mChecker = new Checker(context);
        // Set swipe threshold values.
        Resources res = context.getResources();
        mBackSwipeLinearThreshold =
                res.getDimension(R.dimen.navigation_edge_action_progress_threshold);
        TypedValue typedValue = new TypedValue();
        res.getValue(R.dimen.back_progress_non_linear_factor, typedValue, true);
        mNonLinearProgressFactor = typedValue.getFloat();
        onConfigurationChanged(context.getResources().getConfiguration());
    }

    /** Updates the threshold values for computing progress. */
    public void onConfigurationChanged(Configuration configuration) {
        float maxDistance = configuration.windowConfiguration.getMaxBounds().width();
        float linearDistance = Math.min(maxDistance, mBackSwipeLinearThreshold);
        mTouchTracker.setProgressThresholds(
                linearDistance, maxDistance, mNonLinearProgressFactor);
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
        synchronized (mLock) {
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
    }

    private static class OnBackInvokedCallbackWrapper extends IOnBackInvokedCallback.Stub {
        @NonNull
        private final WeakReference<OnBackInvokedCallback> mCallback;
        @NonNull
        private final BackProgressAnimator mProgressAnimator;
        @NonNull
        private final BackTouchTracker mTouchTracker;
        @NonNull
        private final Handler mHandler;
        @NonNull
        private final BooleanSupplier mOnKeyPreIme;
        @NonNull
        private final Runnable mSystemNavigationObserverCallbackRunnable;
        private final boolean mIsSystemCallback;

        OnBackInvokedCallbackWrapper(
                @NonNull OnBackInvokedCallback callback,
                @NonNull BackTouchTracker touchTracker,
                @NonNull BackProgressAnimator progressAnimator,
                @NonNull Handler handler,
                @NonNull BooleanSupplier onKeyPreIme,
                @NonNull Runnable systemNavigationObserverCallbackRunnable,
                boolean isSystemCallback
        ) {
            mCallback = new WeakReference<>(callback);
            mTouchTracker = touchTracker;
            mProgressAnimator = progressAnimator;
            mHandler = handler;
            mOnKeyPreIme = onKeyPreIme;
            mSystemNavigationObserverCallbackRunnable = systemNavigationObserverCallbackRunnable;
            mIsSystemCallback = isSystemCallback;
        }

        @Override
        public void onBackStarted(BackMotionEvent backEvent) {
            mHandler.post(() -> {
                final OnBackAnimationCallback callback = getBackAnimationCallback();

                // reset progress animator before dispatching onBackStarted to callback. This
                // ensures that onBackCancelled (of a previous gesture) is always dispatched
                // before onBackStarted
                if (callback != null && mProgressAnimator.isBackAnimationInProgress()) {
                    mProgressAnimator.reset();
                }
                mTouchTracker.setState(BackTouchTracker.TouchTrackerState.ACTIVE);
                mTouchTracker.setShouldUpdateStartLocation(true);
                mTouchTracker.setGestureStartLocation(
                        backEvent.getTouchX(), backEvent.getTouchY(), backEvent.getSwipeEdge());

                if (callback != null) {
                    callback.onBackStarted(BackEvent.fromBackMotionEvent(backEvent));
                    mProgressAnimator.onBackStarted(backEvent, callback::onBackProgressed);
                }
            });
        }

        @Override
        public void onBackProgressed(BackMotionEvent backEvent) {
            // This is only called in some special cases such as when activity embedding is active
            // or when the activity is letterboxed. Otherwise mProgressAnimator#onBackProgressed is
            // called from WindowOnBackInvokedDispatcher#onMotionEvent
            mHandler.post(() -> {
                if (getBackAnimationCallback() != null) {
                    mProgressAnimator.onBackProgressed(backEvent);
                }
            });
        }

        @Override
        public void onBackCancelled() {
            mHandler.post(() -> {
                final OnBackAnimationCallback callback = getBackAnimationCallback();
                mTouchTracker.reset();
                if (callback == null) return;
                mProgressAnimator.onBackCancelled(callback::onBackCancelled);
            });
        }

        @Override
        public void onBackInvoked() throws RemoteException {
            mHandler.post(() -> {
                mTouchTracker.reset();
                if (consumedByOnKeyPreIme()) return;
                boolean isInProgress = mProgressAnimator.isBackAnimationInProgress();
                final OnBackInvokedCallback callback = mCallback.get();
                if (callback == null) {
                    mProgressAnimator.reset();
                    Log.d(TAG, "Trying to call onBackInvoked() on a null callback reference.");
                    return;
                }
                if (callback instanceof OnBackAnimationCallback && !isInProgress) {
                    Log.w(TAG, "ProgressAnimator was not in progress, skip onBackInvoked().");
                    return;
                }
                OnBackAnimationCallback animationCallback = getBackAnimationCallback();
                if (animationCallback != null
                        && !(callback instanceof ImeBackAnimationController)) {
                    mProgressAnimator.onBackInvoked(() -> {
                        if (mIsSystemCallback) {
                            mSystemNavigationObserverCallbackRunnable.run();
                        }
                        callback.onBackInvoked();
                    });
                } else {
                    mProgressAnimator.reset();
                    if (mIsSystemCallback) {
                        mSystemNavigationObserverCallbackRunnable.run();
                    }
                    callback.onBackInvoked();
                }
            });
        }

        private boolean consumedByOnKeyPreIme() {
            final OnBackInvokedCallback callback = mCallback.get();
            if (callback instanceof ImeBackAnimationController
                    || callback instanceof ImeOnBackInvokedDispatcher.ImeOnBackInvokedCallback) {
                // call onKeyPreIme API if the current callback is an IME callback and the app has
                // not set enableOnBackInvokedCallback="true"
                try {
                    boolean consumed = mOnKeyPreIme.getAsBoolean();
                    if (consumed) {
                        // back event intercepted by app in onKeyPreIme -> cancel the IME animation.
                        final OnBackAnimationCallback animationCallback =
                                getBackAnimationCallback();
                        if (animationCallback != null) {
                            mProgressAnimator.onBackCancelled(animationCallback::onBackCancelled);
                        }
                        return true;
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Failed to call onKeyPreIme", e);
                }
            }
            return false;
        }

        @Override
        public void setTriggerBack(boolean triggerBack) throws RemoteException {
            mTouchTracker.setTriggerBack(triggerBack);
        }

        @Nullable
        private OnBackAnimationCallback getBackAnimationCallback() {
            OnBackInvokedCallback callback = mCallback.get();
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
        mImeDispatcher.setHandler(mHandler);
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
            if (predictiveBackPrioritySystemNavigationObserver()) {
                if (priority < 0 && priority != PRIORITY_SYSTEM_NAVIGATION_OBSERVER) {
                    throw new IllegalArgumentException("Application registered "
                            + "OnBackInvokedCallback cannot have negative priority. Priority: "
                            + priority);
                }
            } else {
                if (priority < 0) {
                    throw new IllegalArgumentException("Application registered "
                            + "OnBackInvokedCallback cannot have negative priority. Priority: "
                            + priority);
                }
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
