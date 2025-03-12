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

package android.view;

import static android.view.flags.Flags.scrollCaptureTargetZOrderFix;

import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.CancellationSignal;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Collects nodes in the view hierarchy which have been identified as scrollable content.
 *
 * @hide
 */
@UiThread
public final class ScrollCaptureSearchResults {
    private final Executor mExecutor;
    private final List<ScrollCaptureTarget> mTargets;
    private final CancellationSignal mCancel;

    private Runnable mOnCompleteListener;
    private int mCompleted;
    private boolean mComplete = true;

    public ScrollCaptureSearchResults(Executor executor) {
        mExecutor = executor;
        mTargets = new ArrayList<>();
        mCancel = new CancellationSignal();
    }

    // Public

    /**
     * Add the given target to the results.
     *
     * @param target the target to consider
     */
    public void addTarget(@NonNull ScrollCaptureTarget target) {
        requireNonNull(target);

        mTargets.add(target);
        mComplete = false;
        final ScrollCaptureCallback callback = target.getCallback();
        final Consumer<Rect> consumer = new SearchRequest(target);

        // Defer so the view hierarchy scan completes first
        mExecutor.execute(
                () -> callback.onScrollCaptureSearch(mCancel, consumer));
    }

    public boolean isComplete() {
        return mComplete;
    }

    /**
     * Provides a callback to be invoked as soon as all responses have been received from all
     * targets to this point.
     *
     * @param onComplete listener to add
     */
    public void setOnCompleteListener(Runnable onComplete) {
        if (mComplete) {
            onComplete.run();
        } else {
            mOnCompleteListener = onComplete;
        }
    }

    /**
     * Indicates whether the search results are empty.
     *
     * @return true if no targets have been added
     */
    public boolean isEmpty() {
        return mTargets.isEmpty();
    }

    /**
     * Force the results to complete now, cancelling any pending requests and calling a complete
     * listener if provided.
     */
    public void finish() {
        if (!mComplete) {
            mCancel.cancel();
            signalComplete();
        }
    }

    private void signalComplete() {
        mComplete = true;
        if (!scrollCaptureTargetZOrderFix()) {
            mTargets.sort(PRIORITY_ORDER);
        }
        if (mOnCompleteListener != null) {
            mOnCompleteListener.run();
            mOnCompleteListener = null;
        }
    }

    @VisibleForTesting
    public List<ScrollCaptureTarget> getTargets() {
        return new ArrayList<>(mTargets);
    }

    private Rect getScrollBoundsInWindow(@Nullable ScrollCaptureTarget target) {
        if (target == null || target.getScrollBounds() == null) {
            return new Rect();
        }
        Rect windowRect = new Rect(target.getScrollBounds());
        Point windowPosition = target.getPositionInWindow();
        windowRect.offset(windowPosition.x, windowPosition.y);
        return windowRect;
    }

    /**
     * Get the top ranked result out of all completed requests.
     *
     * @return the top ranked result
     */
    @Nullable
    public ScrollCaptureTarget getTopResult() {
        if (!scrollCaptureTargetZOrderFix()) {
            ScrollCaptureTarget target = mTargets.isEmpty() ? null : mTargets.get(0);
            return target != null && target.getScrollBounds() != null ? target : null;
        }
        List<ScrollCaptureTarget> filtered = new ArrayList<>();

        mTargets.removeIf(a -> nullOrEmpty(a.getScrollBounds()));

        // Remove scroll targets obscured or covered by other scrolling views.
        nextTarget:
        for (int i = 0; i <  mTargets.size(); i++) {
            ScrollCaptureTarget current = mTargets.get(i);

            View currentView = current.getContainingView();

            // Nested scroll containers:
            // Check if the next view is a child of the current. If so, skip the current.
            if (i + 1 < mTargets.size()) {
                ScrollCaptureTarget next = mTargets.get(i + 1);
                View nextView = next.getContainingView();
                // Honor explicit include hint on parent as escape hatch (unless both have it)
                if (isDescendant(currentView, nextView)
                        && (!hasIncludeHint(currentView) || hasIncludeHint(nextView))) {
                    continue;
                }
            }

            // Check if any views will be drawn partially or fully over this one.
            for (int j = i + 1; j < mTargets.size(); j++) {
                ScrollCaptureTarget above = mTargets.get(j);
                if (Rect.intersects(getScrollBoundsInWindow(current),
                        getScrollBoundsInWindow(above))) {
                    continue nextTarget;
                }
            }

            filtered.add(current);
        }

        // natural order, false->true
        Comparator<ScrollCaptureTarget> byIncludeHintPresence = comparing(
                ScrollCaptureSearchResults::hasIncludeHint);

        // natural order, smallest->largest area
        Comparator<ScrollCaptureTarget> byArea = comparing(
                target -> area(requireNonNullElse(target.getScrollBounds(), new Rect())));

        // The top result is the last one (with include hint if present, then by largest area)
        filtered.sort(byIncludeHintPresence.thenComparing(byArea));
        return filtered.isEmpty() ? null : filtered.getLast();
    }

    private class SearchRequest implements Consumer<Rect> {
        private ScrollCaptureTarget mTarget;

        SearchRequest(ScrollCaptureTarget target) {
            mTarget = target;
        }

        @Override
        public void accept(Rect scrollBounds) {
            if (mTarget == null || mCancel.isCanceled()) {
                return;
            }
            mExecutor.execute(() -> consume(scrollBounds));
        }

        private void consume(Rect scrollBounds) {
            if (mTarget == null || mCancel.isCanceled()) {
                return;
            }
            if (!nullOrEmpty(scrollBounds)) {
                mTarget.setScrollBounds(scrollBounds);
                mTarget.updatePositionInWindow();
            }
            mCompleted++;
            mTarget = null;

            // All done?
            if (mCompleted == mTargets.size()) {
                signalComplete();
            }
        }
    }

    private static final int AFTER = 1;
    private static final int BEFORE = -1;
    private static final int EQUAL = 0;

    static final Comparator<ScrollCaptureTarget> PRIORITY_ORDER = (a, b) -> {
        if (a == null && b == null) {
            return 0;
        } else if (a == null || b == null) {
            return (a == null) ? 1 : -1;
        }

        boolean emptyScrollBoundsA = nullOrEmpty(a.getScrollBounds());
        boolean emptyScrollBoundsB = nullOrEmpty(b.getScrollBounds());
        if (emptyScrollBoundsA || emptyScrollBoundsB) {
            if (emptyScrollBoundsA && emptyScrollBoundsB) {
                return EQUAL;
            }
            // Prefer the one with a non-empty scroll bounds
            if (emptyScrollBoundsA) {
                return AFTER;
            }
            return BEFORE;
        }

        final View viewA = a.getContainingView();
        final View viewB = b.getContainingView();

        // Prefer any view with scrollCaptureHint="INCLUDE", over one without
        // This is an escape hatch for the next rule (descendants first)
        boolean hintIncludeA = hasIncludeHint(viewA);
        boolean hintIncludeB = hasIncludeHint(viewB);
        if (hintIncludeA != hintIncludeB) {
            return (hintIncludeA) ? BEFORE : AFTER;
        }
        // If the views are relatives, prefer the descendant. This allows implementations to
        // leverage nested scrolling APIs by interacting with the innermost scrollable view (as
        // would happen with touch input).
        if (isDescendant(viewA, viewB)) {
            return BEFORE;
        }
        if (isDescendant(viewB, viewA)) {
            return AFTER;
        }

        // finally, prefer one with larger scroll bounds
        int scrollAreaA = area(a.getScrollBounds());
        int scrollAreaB = area(b.getScrollBounds());
        return (scrollAreaA >= scrollAreaB) ? BEFORE : AFTER;
    };

    private static int area(Rect r) {
        return r.width() * r.height();
    }

    private static boolean nullOrEmpty(Rect r) {
        return r == null || r.isEmpty();
    }

    private static boolean hasIncludeHint(ScrollCaptureTarget target) {
        return hasIncludeHint(target.getContainingView());
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

    void dump(IndentingPrintWriter writer) {
        writer.println("results:");
        writer.increaseIndent();
        writer.println("complete: " + isComplete());
        writer.println("cancelled: " + mCancel.isCanceled());
        writer.println("targets:");
        writer.increaseIndent();
        if (isEmpty()) {
            writer.println("None");
        } else {
            for (int i = 0; i < mTargets.size(); i++) {
                writer.println("[" + i + "]");
                writer.increaseIndent();
                mTargets.get(i).dump(writer);
                writer.decreaseIndent();
            }
            writer.decreaseIndent();
        }
        writer.decreaseIndent();
    }
}
