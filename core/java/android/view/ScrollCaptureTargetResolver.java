/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.view;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;


import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Queries additional state from a list of {@link ScrollCaptureTarget targets} via asynchronous
 * callbacks, then aggregates and reduces the target list to a single target, or null if no target
 * is suitable.
 * <p>
 * The rules for selection are (in order):
 * <ul>
 * <li>prefer getScrollBounds(): non-empty
 * <li>prefer View.getScrollCaptureHint == SCROLL_CAPTURE_HINT_INCLUDE
 * <li>prefer descendants before parents
 * <li>prefer larger area for getScrollBounds() (clipped to view bounds)
 * </ul>
 *
 * <p>
 * All calls to {@link ScrollCaptureCallback#onScrollCaptureSearch} are made on the main thread,
 * with results are queued and consumed to the main thread as well.
 *
 * @see #start(Handler, long, Consumer)
 *
 * @hide
 */
@UiThread
public class ScrollCaptureTargetResolver {
    private static final String TAG = "ScrollCaptureTargetRes";
    private static final boolean DEBUG = true;

    private final Object mLock = new Object();

    private final Queue<ScrollCaptureTarget> mTargets;
    private Handler mHandler;
    private long mTimeLimitMillis;

    private Consumer<ScrollCaptureTarget> mWhenComplete;
    private int mPendingBoundsRequests;
    private long mDeadlineMillis;

    private ScrollCaptureTarget mResult;
    private boolean mFinished;

    private boolean mStarted;

    private static int area(Rect r) {
        return r.width() * r.height();
    }

    private static boolean nullOrEmpty(Rect r) {
        return r == null || r.isEmpty();
    }

    /**
     * Binary operator which selects the best {@link ScrollCaptureTarget}.
     */
    private static ScrollCaptureTarget chooseTarget(ScrollCaptureTarget a, ScrollCaptureTarget b) {
        Log.d(TAG, "chooseTarget: " + a + " or " + b);
        // Nothing plus nothing is still nothing.
        if (a == null && b == null) {
            Log.d(TAG, "chooseTarget: (both null) return " + null);
            return null;
        }
        // Prefer non-null.
        if (a == null || b == null) {
            ScrollCaptureTarget c = (a == null) ? b : a;
            Log.d(TAG, "chooseTarget: (other is null) return " + c);
            return c;

        }

        boolean emptyScrollBoundsA = nullOrEmpty(a.getScrollBounds());
        boolean emptyScrollBoundsB = nullOrEmpty(b.getScrollBounds());
        if (emptyScrollBoundsA || emptyScrollBoundsB) {
            if (emptyScrollBoundsA && emptyScrollBoundsB) {
                // Both have an empty or null scrollBounds
                Log.d(TAG, "chooseTarget: (both have empty or null bounds) return " + null);
                return null;
            }
            // Prefer the one with a non-empty scroll bounds
            if (emptyScrollBoundsA) {
                Log.d(TAG, "chooseTarget: (a has empty or null bounds) return " + b);
                return b;
            }
            Log.d(TAG, "chooseTarget: (b has empty or null bounds) return " + a);
            return a;
        }

        final View viewA = a.getContainingView();
        final View viewB = b.getContainingView();

        // Prefer any view with scrollCaptureHint="INCLUDE", over one without
        // This is an escape hatch for the next rule (descendants first)
        boolean hintIncludeA = hasIncludeHint(viewA);
        boolean hintIncludeB = hasIncludeHint(viewB);
        if (hintIncludeA != hintIncludeB) {
            ScrollCaptureTarget c = (hintIncludeA) ? a : b;
            Log.d(TAG, "chooseTarget: (has hint=INCLUDE) return " + c);
            return c;
        }

        // If the views are relatives, prefer the descendant. This allows implementations to
        // leverage nested scrolling APIs by interacting with the innermost scrollable view (as
        // would happen with touch input).
        if (isDescendant(viewA, viewB)) {
            Log.d(TAG, "chooseTarget: (b is descendant of a) return " + b);
            return b;
        }
        if (isDescendant(viewB, viewA)) {
            Log.d(TAG, "chooseTarget: (a is descendant of b) return " + a);
            return a;
        }

        // finally, prefer one with larger scroll bounds
        int scrollAreaA = area(a.getScrollBounds());
        int scrollAreaB = area(b.getScrollBounds());
        ScrollCaptureTarget c = (scrollAreaA >= scrollAreaB) ? a : b;
        Log.d(TAG, "chooseTarget: return " + c);
        return c;
    }

    /**
     * Creates an instance to query and filter {@code target}.
     *
     * @param targets   a list of {@link ScrollCaptureTarget} as collected by {@link
     *                  View#dispatchScrollCaptureSearch}.
     * @param uiHandler the UI thread handler for the view tree
     * @see #start(long, Consumer)
     */
    public ScrollCaptureTargetResolver(Queue<ScrollCaptureTarget> targets) {
        mTargets = targets;
    }

    void checkThread() {
        if (mHandler.getLooper() != Looper.myLooper()) {
            throw new IllegalStateException("Called from wrong thread! ("
                    + Thread.currentThread().getName() + ")");
        }
    }

    /**
     * Blocks until a result is returned (after completion or timeout).
     * <p>
     * For testing only. Normal usage should receive a callback after calling {@link #start}.
     */
    @VisibleForTesting
    public ScrollCaptureTarget waitForResult() throws InterruptedException {
        synchronized (mLock) {
            while (!mFinished) {
                mLock.wait();
            }
        }
        return mResult;
    }


    private void supplyResult(ScrollCaptureTarget target) {
        checkThread();
        if (mFinished) {
            return;
        }
        mResult = chooseTarget(mResult, target);
        boolean finish = mPendingBoundsRequests == 0
                || SystemClock.elapsedRealtime() >= mDeadlineMillis;
        if (finish) {
            System.err.println("We think we're done, or timed out");
            mPendingBoundsRequests = 0;
            mWhenComplete.accept(mResult);
            synchronized (mLock) {
                mFinished = true;
                mLock.notify();
            }
            mWhenComplete = null;
        }
    }

    /**
     * Asks all targets for {@link ScrollCaptureCallback#onScrollCaptureSearch(Consumer)
     * scrollBounds}, and selects the primary target according to the {@link
     * #chooseTarget} function.
     *
     * @param timeLimitMillis the amount of time to wait for all responses before delivering the top
     *                        result
     * @param resultConsumer  the consumer to receive the primary target
     */
    @AnyThread
    public void start(Handler uiHandler, long timeLimitMillis,
            Consumer<ScrollCaptureTarget> resultConsumer) {
        synchronized (mLock) {
            if (mStarted) {
                throw new IllegalStateException("already started!");
            }
            if (timeLimitMillis < 0) {
                throw new IllegalArgumentException("Time limit must be positive");
            }
            mHandler = uiHandler;
            mTimeLimitMillis = timeLimitMillis;
            mWhenComplete = resultConsumer;
            if (mTargets.isEmpty()) {
                mHandler.post(() -> supplyResult(null));
                return;
            }
            mStarted = true;
            uiHandler.post(() -> run(timeLimitMillis, resultConsumer));
        }
    }


    private void run(long timeLimitMillis, Consumer<ScrollCaptureTarget> resultConsumer) {
        checkThread();

        mPendingBoundsRequests = mTargets.size();
        for (ScrollCaptureTarget target : mTargets) {
            queryTarget(target);
        }
        mDeadlineMillis = SystemClock.elapsedRealtime() + mTimeLimitMillis;
        mHandler.postAtTime(mTimeoutRunnable, mDeadlineMillis);
    }

    private final Runnable mTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            checkThread();
            supplyResult(null);
        }
    };


    /**
     * Adds a target to the list and requests {@link ScrollCaptureCallback#onScrollCaptureSearch}
     * scrollBounds} from it. Results are returned by a call to {@link #onScrollBoundsProvided}.
     *
     * @param target the target to add
     */
    @UiThread
    private void queryTarget(@NonNull ScrollCaptureTarget target) {
        checkThread();
        final ScrollCaptureCallback callback = target.getCallback();
        // from the UI thread, request scroll bounds
        callback.onScrollCaptureSearch(
                // allow only one callback to onReady.accept():
                new SingletonConsumer<Rect>(
                        // Queue and consume on the UI thread
                        ((scrollBounds) -> mHandler.post(
                                () -> onScrollBoundsProvided(target, scrollBounds)))));

    }

    @UiThread
    private void onScrollBoundsProvided(ScrollCaptureTarget target, @Nullable Rect scrollBounds) {
        checkThread();
        if (mFinished) {
            return;
        }

        // Record progress.
        mPendingBoundsRequests--;

        // Remove the timeout.
        mHandler.removeCallbacks(mTimeoutRunnable);

        boolean doneOrTimedOut = mPendingBoundsRequests == 0
                || SystemClock.elapsedRealtime() >= mDeadlineMillis;

        final View containingView = target.getContainingView();
        if (!nullOrEmpty(scrollBounds) && containingView.isAggregatedVisible()) {
            target.updatePositionInWindow();
            target.setScrollBounds(scrollBounds);
            supplyResult(target);
        }

        System.err.println("mPendingBoundsRequests: " + mPendingBoundsRequests);
        System.err.println("mDeadlineMillis: " + mDeadlineMillis);
        System.err.println("SystemClock.elapsedRealtime(): " + SystemClock.elapsedRealtime());

        if (!mFinished) {
            // Reschedule the timeout.
            System.err.println(
                    "We think we're NOT done yet and will check back at " + mDeadlineMillis);
            mHandler.postAtTime(mTimeoutRunnable, mDeadlineMillis);
        }
    }

    private static boolean hasIncludeHint(View view) {
        return (view.getScrollCaptureHint() & View.SCROLL_CAPTURE_HINT_INCLUDE) != 0;
    }

    /**
     * Determines if {@code otherView} is a descendant of {@code view}.
     *
     * @param view      a view
     * @param otherView another view
     * @return true if {@code view} is an ancestor of {@code otherView}
     */
    private static boolean isDescendant(@NonNull View view, @NonNull View otherView) {
        if (view == otherView) {
            return false;
        }
        ViewParent otherParent = otherView.getParent();
        while (otherParent != view && otherParent != null) {
            otherParent = otherParent.getParent();
        }
        return otherParent == view;
    }

    private static int findRelation(@NonNull View a, @NonNull View b) {
        if (a == b) {
            return 0;
        }

        ViewParent parentA = a.getParent();
        ViewParent parentB = b.getParent();

        while (parentA != null || parentB != null) {
            if (parentA == parentB) {
                return 0;
            }
            if (parentA == b) {
                return 1; // A is descendant of B
            }
            if (parentB == a) {
                return -1; // B is descendant of A
            }
            if (parentA != null) {
                parentA = parentA.getParent();
            }
            if (parentB != null) {
                parentB = parentB.getParent();
            }
        }
        return 0;
    }

    /**
     * A safe wrapper for a consumer callbacks intended to accept a single value. It ensures
     * that the receiver of the consumer does not retain a reference to {@code target} after use nor
     * cause race conditions by invoking {@link Consumer#accept accept} more than once.
     *
     * @param target the target consumer
     */
    static class SingletonConsumer<T> implements Consumer<T> {
        final AtomicReference<Consumer<T>> mAtomicRef;

        SingletonConsumer(Consumer<T> target) {
            mAtomicRef = new AtomicReference<>(target);
        }

        @Override
        public void accept(T t) {
            final Consumer<T> consumer = mAtomicRef.getAndSet(null);
            if (consumer != null) {
                consumer.accept(t);
            }
        }
    }
}
