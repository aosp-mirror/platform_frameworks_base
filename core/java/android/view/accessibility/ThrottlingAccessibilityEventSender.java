/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.accessibility;


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewRootImpl;
import android.view.ViewRootImpl.CalledFromWrongThreadException;

/**
 * A throttling {@link AccessibilityEvent} sender that relies on its currently associated
 * 'source' view's {@link View#postDelayed delayed execution} to delay and possibly
 * {@link #tryMerge merge} together any events that come in less than
 * {@link ViewConfiguration#getSendRecurringAccessibilityEventsInterval
 * the configured amount of milliseconds} apart.
 *
 * The suggested usage is to create a singleton extending this class, holding any state specific to
 * the particular event type that the subclass represents, and have an 'entrypoint' method that
 * delegates to {@link #scheduleFor(View)}.
 * For example:
 *
 * {@code
 *     public void post(View view, String text, int resId) {
 *         mText = text;
 *         mId = resId;
 *         scheduleFor(view);
 *     }
 * }
 *
 * @see #scheduleFor(View)
 * @see #tryMerge(View, View)
 * @see #performSendEvent(View)
 * @hide
 */
public abstract class ThrottlingAccessibilityEventSender {

    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "ThrottlingA11ySender";

    View mSource;
    private long mLastSendTimeMillis = Long.MIN_VALUE;
    private boolean mIsPending = false;

    private final Runnable mWorker = () -> {
        View source = mSource;
        if (DEBUG) Log.d(LOG_TAG, thisClass() + ".run(mSource = " + source + ")");

        if (!checkAndResetIsPending() || source == null) {
            resetStateInternal();
            return;
        }

        // Accessibility may be turned off while we were waiting
        if (isAccessibilityEnabled(source)) {
            mLastSendTimeMillis = SystemClock.uptimeMillis();
            performSendEvent(source);
        }
        resetStateInternal();
    };

    /**
     * Populate and send an {@link AccessibilityEvent} using the given {@code source} view, as well
     * as any extra data from this instance's state.
     *
     * Send the event via {@link View#sendAccessibilityEventUnchecked(AccessibilityEvent)} or
     * {@link View#sendAccessibilityEvent(int)} on the provided {@code source} view to allow for
     * overrides of those methods on {@link View} subclasses to take effect, and/or make sure that
     * an {@link View#getAccessibilityDelegate() accessibility delegate} is not ignored if any.
     */
    protected abstract void performSendEvent(@NonNull View source);

    /**
     * Perform optional cleanup after {@link #performSendEvent}
     *
     * @param source the view this event was associated with
     */
    protected abstract void resetState(@Nullable View source);

    /**
     * Attempt to merge the pending events for source views {@code oldSource} and {@code newSource}
     * into one, with source set to the resulting {@link View}
     *
     * A result of {@code null} means merger is not possible, resulting in the currently pending
     * event being flushed before proceeding.
     */
    protected @Nullable View tryMerge(@NonNull View oldSource, @NonNull View newSource) {
        return null;
    }

    /**
     * Schedules a {@link #performSendEvent} with the source {@link View} set to given
     * {@code source}
     *
     * If an event is already scheduled a {@link #tryMerge merge} will be attempted.
     * If merging is not possible (as indicated by the null result from {@link #tryMerge}),
     * the currently scheduled event will be {@link #sendNow sent immediately} and the new one
     * will be scheduled afterwards.
     */
    protected final void scheduleFor(@NonNull View source) {
        if (DEBUG) Log.d(LOG_TAG, thisClass() + ".scheduleFor(source = " + source + ")");

        Handler uiHandler = source.getHandler();
        if (uiHandler == null || uiHandler.getLooper() != Looper.myLooper()) {
            CalledFromWrongThreadException e = new CalledFromWrongThreadException(
                    "Expected to be called from main thread but was called from "
                            + Thread.currentThread());
            // TODO: Throw the exception
            Log.e(LOG_TAG, "Accessibility content change on non-UI thread. Future Android "
                    + "versions will throw an exception.", e);
        }

        if (!isAccessibilityEnabled(source)) return;

        if (mIsPending) {
            View merged = tryMerge(mSource, source);
            if (merged != null) {
                setSource(merged);
                return;
            } else {
                sendNow();
            }
        }

        setSource(source);

        final long timeSinceLastMillis = SystemClock.uptimeMillis() - mLastSendTimeMillis;
        final long minEventIntervalMillis =
                ViewConfiguration.getSendRecurringAccessibilityEventsInterval();
        if (timeSinceLastMillis >= minEventIntervalMillis) {
            sendNow();
        } else {
            mSource.postDelayed(mWorker, minEventIntervalMillis - timeSinceLastMillis);
        }
    }

    static boolean isAccessibilityEnabled(@NonNull View contextProvider) {
        return AccessibilityManager.getInstance(contextProvider.getContext()).isEnabled();
    }

    protected final void sendNow(View source) {
        setSource(source);
        sendNow();
    }

    private void sendNow() {
        mSource.removeCallbacks(mWorker);
        mWorker.run();
    }

    /**
     * Flush the event if one is pending
     */
    public void sendNowIfPending() {
        if (mIsPending) sendNow();
    }

    /**
     * Cancel the event if one is pending and is for the given view
     */
    public final void cancelIfPendingFor(@NonNull View source) {
        if (isPendingFor(source)) cancelIfPending(this);
    }

    /**
     * @return whether an event is currently pending for the given source view
     */
    protected final boolean isPendingFor(@Nullable View source) {
        return mIsPending && mSource == source;
    }

    /**
     * Cancel the event if one is not null and pending
     */
    public static void cancelIfPending(@Nullable ThrottlingAccessibilityEventSender sender) {
        if (sender == null || !sender.checkAndResetIsPending()) return;
        sender.mSource.removeCallbacks(sender.mWorker);
        sender.resetStateInternal();
    }

    void resetStateInternal() {
        if (DEBUG) Log.d(LOG_TAG, thisClass() + ".resetStateInternal()");

        resetState(mSource);
        setSource(null);
    }

    boolean checkAndResetIsPending() {
        if (mIsPending) {
            mIsPending = false;
            return true;
        } else {
            return false;
        }
    }

    private void setSource(@Nullable View source) {
        if (DEBUG) Log.d(LOG_TAG, thisClass() + ".setSource(" + source + ")");

        if (source == null && mIsPending) {
            Log.e(LOG_TAG, "mSource nullified while callback still pending: " + this);
            return;
        }

        if (source != null && !mIsPending) {
            // At most one can be pending at any given time
            View oldSource = mSource;
            if (oldSource != null) {
                ViewRootImpl viewRootImpl = oldSource.getViewRootImpl();
                if (viewRootImpl != null) {
                    viewRootImpl.flushPendingAccessibilityEvents();
                }
            }
            mIsPending = true;
        }
        mSource = source;
    }

    String thisClass() {
        return getClass().getSimpleName();
    }

    @Override
    public String toString() {
        return thisClass() + "(" + mSource + ")";
    }

}
