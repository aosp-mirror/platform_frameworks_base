/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.graphics.Insets;
import android.graphics.Rect;

import com.android.internal.util.Preconditions;

import java.util.Objects;

/**
 * Describes a set of insets for window content.
 *
 * <p>WindowInsets are immutable and may be expanded to include more inset types in the future.
 * To adjust insets, use one of the supplied clone methods to obtain a new WindowInsets instance
 * with the adjusted properties.</p>
 *
 * <p>Note: Before {@link android.os.Build.VERSION_CODES#P P}, WindowInsets instances were only
 * immutable during a single layout pass (i.e. would return the same values between
 * {@link View#onApplyWindowInsets} and {@link View#onLayout}, but could return other values
 * otherwise). Starting with {@link android.os.Build.VERSION_CODES#P P}, WindowInsets are
 * always immutable and implement equality.
 *
 * @see View.OnApplyWindowInsetsListener
 * @see View#onApplyWindowInsets(WindowInsets)
 */
public final class WindowInsets {

    @NonNull private final Insets mSystemWindowInsets;
    @NonNull private final Insets mWindowDecorInsets;
    @NonNull private final Insets mStableInsets;
    @Nullable private Rect mTempRect;
    private final boolean mIsRound;
    @Nullable private final DisplayCutout mDisplayCutout;

    /**
     * In multi-window we force show the navigation bar. Because we don't want that the surface size
     * changes in this mode, we instead have a flag whether the navigation bar size should always
     * be consumed, so the app is treated like there is no virtual navigation bar at all.
     */
    private final boolean mAlwaysConsumeNavBar;

    private final boolean mSystemWindowInsetsConsumed;
    private final boolean mWindowDecorInsetsConsumed;
    private final boolean mStableInsetsConsumed;
    private final boolean mDisplayCutoutConsumed;

    /**
     * Since new insets may be added in the future that existing apps couldn't
     * know about, this fully empty constant shouldn't be made available to apps
     * since it would allow them to inadvertently consume unknown insets by returning it.
     * @hide
     */
    @UnsupportedAppUsage
    public static final WindowInsets CONSUMED;

    static {
        CONSUMED = new WindowInsets((Insets) null, null, null, false, false, null);
    }

    /**
     * Construct a new WindowInsets from individual insets.
     *
     * A {@code null} inset indicates that the respective inset is consumed.
     *
     * @hide
     */
    public WindowInsets(Rect systemWindowInsets, Rect windowDecorInsets, Rect stableInsets,
            boolean isRound, boolean alwaysConsumeNavBar, DisplayCutout displayCutout) {
        this(insetsOrNull(systemWindowInsets), insetsOrNull(windowDecorInsets),
                insetsOrNull(stableInsets), isRound, alwaysConsumeNavBar, displayCutout);
    }

    private WindowInsets(Insets systemWindowInsets, Insets windowDecorInsets,
            Insets stableInsets, boolean isRound, boolean alwaysConsumeNavBar,
            DisplayCutout displayCutout) {
        mSystemWindowInsetsConsumed = systemWindowInsets == null;
        mSystemWindowInsets = mSystemWindowInsetsConsumed ? Insets.NONE : systemWindowInsets;

        mWindowDecorInsetsConsumed = windowDecorInsets == null;
        mWindowDecorInsets = mWindowDecorInsetsConsumed ? Insets.NONE : windowDecorInsets;

        mStableInsetsConsumed = stableInsets == null;
        mStableInsets = mStableInsetsConsumed ? Insets.NONE : stableInsets;

        mIsRound = isRound;
        mAlwaysConsumeNavBar = alwaysConsumeNavBar;

        mDisplayCutoutConsumed = displayCutout == null;
        mDisplayCutout = (mDisplayCutoutConsumed || displayCutout.isEmpty())
                ? null : displayCutout;
    }

    /**
     * Construct a new WindowInsets, copying all values from a source WindowInsets.
     *
     * @param src Source to copy insets from
     */
    public WindowInsets(WindowInsets src) {
        this(src.mSystemWindowInsetsConsumed ? null : src.mSystemWindowInsets,
                src.mWindowDecorInsetsConsumed ? null : src.mWindowDecorInsets,
                src.mStableInsetsConsumed ? null : src.mStableInsets,
                src.mIsRound, src.mAlwaysConsumeNavBar,
                displayCutoutCopyConstructorArgument(src));
    }

    private static DisplayCutout displayCutoutCopyConstructorArgument(WindowInsets w) {
        if (w.mDisplayCutoutConsumed) {
            return null;
        } else if (w.mDisplayCutout == null) {
            return DisplayCutout.NO_CUTOUT;
        } else {
            return w.mDisplayCutout;
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public WindowInsets(Rect systemWindowInsets) {
        this(systemWindowInsets, null, null, false, false, null);
    }

    /**
     * Used to provide a safe copy of the system window insets to pass through
     * to the existing fitSystemWindows method and other similar internals.
     * @hide
     *
     * @deprecated use {@link #getSystemWindowInsets()} instead.
     */
    @Deprecated
    @NonNull
    public Rect getSystemWindowInsetsAsRect() {
        if (mTempRect == null) {
            mTempRect = new Rect();
        }
        mTempRect.set(mSystemWindowInsets.left, mSystemWindowInsets.top,
                mSystemWindowInsets.right, mSystemWindowInsets.bottom);
        return mTempRect;
    }

    /**
     * Returns the system window insets in pixels.
     *
     * <p>The system window inset represents the area of a full-screen window that is
     * partially or fully obscured by the status bar, navigation bar, IME or other system windows.
     * </p>
     *
     * @return The system window insets
     */
    @NonNull
    public Insets getSystemWindowInsets() {
        return mSystemWindowInsets;
    }

    /**
     * Returns the left system window inset in pixels.
     *
     * <p>The system window inset represents the area of a full-screen window that is
     * partially or fully obscured by the status bar, navigation bar, IME or other system windows.
     * </p>
     *
     * @return The left system window inset
     */
    public int getSystemWindowInsetLeft() {
        return mSystemWindowInsets.left;
    }

    /**
     * Returns the top system window inset in pixels.
     *
     * <p>The system window inset represents the area of a full-screen window that is
     * partially or fully obscured by the status bar, navigation bar, IME or other system windows.
     * </p>
     *
     * @return The top system window inset
     */
    public int getSystemWindowInsetTop() {
        return mSystemWindowInsets.top;
    }

    /**
     * Returns the right system window inset in pixels.
     *
     * <p>The system window inset represents the area of a full-screen window that is
     * partially or fully obscured by the status bar, navigation bar, IME or other system windows.
     * </p>
     *
     * @return The right system window inset
     */
    public int getSystemWindowInsetRight() {
        return mSystemWindowInsets.right;
    }

    /**
     * Returns the bottom system window inset in pixels.
     *
     * <p>The system window inset represents the area of a full-screen window that is
     * partially or fully obscured by the status bar, navigation bar, IME or other system windows.
     * </p>
     *
     * @return The bottom system window inset
     */
    public int getSystemWindowInsetBottom() {
        return mSystemWindowInsets.bottom;
    }

    /**
     * Returns the left window decor inset in pixels.
     *
     * <p>The window decor inset represents the area of the window content area that is
     * partially or fully obscured by decorations within the window provided by the framework.
     * This can include action bars, title bars, toolbars, etc.</p>
     *
     * @return The left window decor inset
     * @hide pending API
     */
    public int getWindowDecorInsetLeft() {
        return mWindowDecorInsets.left;
    }

    /**
     * Returns the top window decor inset in pixels.
     *
     * <p>The window decor inset represents the area of the window content area that is
     * partially or fully obscured by decorations within the window provided by the framework.
     * This can include action bars, title bars, toolbars, etc.</p>
     *
     * @return The top window decor inset
     * @hide pending API
     */
    public int getWindowDecorInsetTop() {
        return mWindowDecorInsets.top;
    }

    /**
     * Returns the right window decor inset in pixels.
     *
     * <p>The window decor inset represents the area of the window content area that is
     * partially or fully obscured by decorations within the window provided by the framework.
     * This can include action bars, title bars, toolbars, etc.</p>
     *
     * @return The right window decor inset
     * @hide pending API
     */
    public int getWindowDecorInsetRight() {
        return mWindowDecorInsets.right;
    }

    /**
     * Returns the bottom window decor inset in pixels.
     *
     * <p>The window decor inset represents the area of the window content area that is
     * partially or fully obscured by decorations within the window provided by the framework.
     * This can include action bars, title bars, toolbars, etc.</p>
     *
     * @return The bottom window decor inset
     * @hide pending API
     */
    public int getWindowDecorInsetBottom() {
        return mWindowDecorInsets.bottom;
    }

    /**
     * Returns true if this WindowInsets has nonzero system window insets.
     *
     * <p>The system window inset represents the area of a full-screen window that is
     * partially or fully obscured by the status bar, navigation bar, IME or other system windows.
     * </p>
     *
     * @return true if any of the system window inset values are nonzero
     */
    public boolean hasSystemWindowInsets() {
        return mSystemWindowInsets.left != 0 || mSystemWindowInsets.top != 0 ||
                mSystemWindowInsets.right != 0 || mSystemWindowInsets.bottom != 0;
    }

    /**
     * Returns true if this WindowInsets has nonzero window decor insets.
     *
     * <p>The window decor inset represents the area of the window content area that is
     * partially or fully obscured by decorations within the window provided by the framework.
     * This can include action bars, title bars, toolbars, etc.</p>
     *
     * @return true if any of the window decor inset values are nonzero
     * @hide pending API
     */
    public boolean hasWindowDecorInsets() {
        return mWindowDecorInsets.left != 0 || mWindowDecorInsets.top != 0 ||
                mWindowDecorInsets.right != 0 || mWindowDecorInsets.bottom != 0;
    }

    /**
     * Returns true if this WindowInsets has any nonzero insets.
     *
     * @return true if any inset values are nonzero
     */
    public boolean hasInsets() {
        return hasSystemWindowInsets() || hasWindowDecorInsets() || hasStableInsets()
                || mDisplayCutout != null;
    }

    /**
     * Returns the display cutout if there is one.
     *
     * @return the display cutout or null if there is none
     * @see DisplayCutout
     */
    @Nullable
    public DisplayCutout getDisplayCutout() {
        return mDisplayCutout;
    }

    /**
     * Returns a copy of this WindowInsets with the cutout fully consumed.
     *
     * @return A modified copy of this WindowInsets
     */
    @NonNull
    public WindowInsets consumeDisplayCutout() {
        return new WindowInsets(mSystemWindowInsetsConsumed ? null : mSystemWindowInsets,
                mWindowDecorInsetsConsumed ? null : mWindowDecorInsets,
                mStableInsetsConsumed ? null : mStableInsets,
                mIsRound, mAlwaysConsumeNavBar,
                null /* displayCutout */);
    }


    /**
     * Check if these insets have been fully consumed.
     *
     * <p>Insets are considered "consumed" if the applicable <code>consume*</code> methods
     * have been called such that all insets have been set to zero. This affects propagation of
     * insets through the view hierarchy; insets that have not been fully consumed will continue
     * to propagate down to child views.</p>
     *
     * <p>The result of this method is equivalent to the return value of
     * {@link View#fitSystemWindows(android.graphics.Rect)}.</p>
     *
     * @return true if the insets have been fully consumed.
     */
    public boolean isConsumed() {
        return mSystemWindowInsetsConsumed && mWindowDecorInsetsConsumed && mStableInsetsConsumed
                && mDisplayCutoutConsumed;
    }

    /**
     * Returns true if the associated window has a round shape.
     *
     * <p>A round window's left, top, right and bottom edges reach all the way to the
     * associated edges of the window but the corners may not be visible. Views responding
     * to round insets should take care to not lay out critical elements within the corners
     * where they may not be accessible.</p>
     *
     * @return True if the window is round
     */
    public boolean isRound() {
        return mIsRound;
    }

    /**
     * Returns a copy of this WindowInsets with the system window insets fully consumed.
     *
     * @return A modified copy of this WindowInsets
     */
    @NonNull
    public WindowInsets consumeSystemWindowInsets() {
        return new WindowInsets(null /* systemWindowInsets */,
                mWindowDecorInsetsConsumed ? null : mWindowDecorInsets,
                mStableInsetsConsumed ? null : mStableInsets,
                mIsRound, mAlwaysConsumeNavBar,
                displayCutoutCopyConstructorArgument(this));
    }

    // TODO(b/119190588): replace @code with @link below
    /**
     * Returns a copy of this WindowInsets with selected system window insets replaced
     * with new values.
     *
     * <p>Note: If the system window insets are already consumed, this method will return them
     * unchanged on {@link android.os.Build.VERSION_CODES#Q Q} and later. Prior to
     * {@link android.os.Build.VERSION_CODES#Q Q}, the new values were applied regardless of
     * whether they were consumed, and this method returns invalid non-zero consumed insets.
     *
     * @param left New left inset in pixels
     * @param top New top inset in pixels
     * @param right New right inset in pixels
     * @param bottom New bottom inset in pixels
     * @return A modified copy of this WindowInsets
     * @deprecated use {@code Builder#Builder(WindowInsets)} with
     *             {@link Builder#setSystemWindowInsets(Insets)} instead.
     */
    @Deprecated
    @NonNull
    public WindowInsets replaceSystemWindowInsets(int left, int top, int right, int bottom) {
        // Compat edge case: what should this do if the insets have already been consumed?
        // On platforms prior to Q, the behavior was to override the insets with non-zero values,
        // but leave them consumed, which is invalid (consumed insets must be zero).
        // The behavior is now keeping them consumed and discarding the new insets.
        if (mSystemWindowInsetsConsumed) {
            return this;
        }
        return new Builder(this).setSystemWindowInsets(Insets.of(left, top, right, bottom)).build();
    }

    // TODO(b/119190588): replace @code with @link below
    /**
     * Returns a copy of this WindowInsets with selected system window insets replaced
     * with new values.
     *
     * <p>Note: If the system window insets are already consumed, this method will return them
     * unchanged on {@link android.os.Build.VERSION_CODES#Q Q} and later. Prior to
     * {@link android.os.Build.VERSION_CODES#Q Q}, the new values were applied regardless of
     * whether they were consumed, and this method returns invalid non-zero consumed insets.
     *
     * @param systemWindowInsets New system window insets. Each field is the inset in pixels
     *                           for that edge
     * @return A modified copy of this WindowInsets
     * @deprecated use {@code Builder#Builder(WindowInsets)} with
     *             {@link Builder#setSystemWindowInsets(Insets)} instead.
     */
    @Deprecated
    @NonNull
    public WindowInsets replaceSystemWindowInsets(Rect systemWindowInsets) {
        return replaceSystemWindowInsets(systemWindowInsets.left, systemWindowInsets.top,
                systemWindowInsets.right, systemWindowInsets.bottom);
    }

    /**
     * @hide
     */
    @NonNull
    public WindowInsets consumeWindowDecorInsets() {
        return new WindowInsets(mSystemWindowInsetsConsumed ? null : mSystemWindowInsets,
                null /* windowDecorInsets */,
                mStableInsetsConsumed ? null : mStableInsets,
                mIsRound, mAlwaysConsumeNavBar,
                displayCutoutCopyConstructorArgument(this));
    }

    /**
     * Returns the stable insets in pixels.
     *
     * <p>The stable inset represents the area of a full-screen window that <b>may</b> be
     * partially or fully obscured by the system UI elements.  This value does not change
     * based on the visibility state of those elements; for example, if the status bar is
     * normally shown, but temporarily hidden, the stable inset will still provide the inset
     * associated with the status bar being shown.</p>
     *
     * @return The stable insets
     */
    @NonNull
    public Insets getStableInsets() {
        return mStableInsets;
    }

    /**
     * Returns the top stable inset in pixels.
     *
     * <p>The stable inset represents the area of a full-screen window that <b>may</b> be
     * partially or fully obscured by the system UI elements.  This value does not change
     * based on the visibility state of those elements; for example, if the status bar is
     * normally shown, but temporarily hidden, the stable inset will still provide the inset
     * associated with the status bar being shown.</p>
     *
     * @return The top stable inset
     */
    public int getStableInsetTop() {
        return mStableInsets.top;
    }

    /**
     * Returns the left stable inset in pixels.
     *
     * <p>The stable inset represents the area of a full-screen window that <b>may</b> be
     * partially or fully obscured by the system UI elements.  This value does not change
     * based on the visibility state of those elements; for example, if the status bar is
     * normally shown, but temporarily hidden, the stable inset will still provide the inset
     * associated with the status bar being shown.</p>
     *
     * @return The left stable inset
     */
    public int getStableInsetLeft() {
        return mStableInsets.left;
    }

    /**
     * Returns the right stable inset in pixels.
     *
     * <p>The stable inset represents the area of a full-screen window that <b>may</b> be
     * partially or fully obscured by the system UI elements.  This value does not change
     * based on the visibility state of those elements; for example, if the status bar is
     * normally shown, but temporarily hidden, the stable inset will still provide the inset
     * associated with the status bar being shown.</p>
     *
     * @return The right stable inset
     */
    public int getStableInsetRight() {
        return mStableInsets.right;
    }

    /**
     * Returns the bottom stable inset in pixels.
     *
     * <p>The stable inset represents the area of a full-screen window that <b>may</b> be
     * partially or fully obscured by the system UI elements.  This value does not change
     * based on the visibility state of those elements; for example, if the status bar is
     * normally shown, but temporarily hidden, the stable inset will still provide the inset
     * associated with the status bar being shown.</p>
     *
     * @return The bottom stable inset
     */
    public int getStableInsetBottom() {
        return mStableInsets.bottom;
    }

    /**
     * Returns true if this WindowInsets has nonzero stable insets.
     *
     * <p>The stable inset represents the area of a full-screen window that <b>may</b> be
     * partially or fully obscured by the system UI elements.  This value does not change
     * based on the visibility state of those elements; for example, if the status bar is
     * normally shown, but temporarily hidden, the stable inset will still provide the inset
     * associated with the status bar being shown.</p>
     *
     * @return true if any of the stable inset values are nonzero
     */
    public boolean hasStableInsets() {
        return mStableInsets.top != 0 || mStableInsets.left != 0 || mStableInsets.right != 0
                || mStableInsets.bottom != 0;
    }

    /**
     * Returns a copy of this WindowInsets with the stable insets fully consumed.
     *
     * @return A modified copy of this WindowInsets
     */
    @NonNull
    public WindowInsets consumeStableInsets() {
        return new WindowInsets(mSystemWindowInsetsConsumed ? null : mSystemWindowInsets,
                mWindowDecorInsetsConsumed ? null : mWindowDecorInsets,
                null /* stableInsets */,
                mIsRound, mAlwaysConsumeNavBar,
                displayCutoutCopyConstructorArgument(this));
    }

    /**
     * @hide
     */
    public boolean shouldAlwaysConsumeNavBar() {
        return mAlwaysConsumeNavBar;
    }

    @Override
    public String toString() {
        return "WindowInsets{systemWindowInsets=" + mSystemWindowInsets
                + " windowDecorInsets=" + mWindowDecorInsets
                + " stableInsets=" + mStableInsets
                + (mDisplayCutout != null ? " cutout=" + mDisplayCutout : "")
                + (isRound() ? " round" : "")
                + "}";
    }

    /**
     * Returns a copy of this instance inset in the given directions.
     *
     * @see #inset(int, int, int, int)
     * @deprecated use {@link #inset(Insets)}
     * @hide
     */
    @Deprecated
    @NonNull
    public WindowInsets inset(Rect r) {
        return inset(r.left, r.top, r.right, r.bottom);
    }

    /**
     * Returns a copy of this instance inset in the given directions.
     *
     * @see #inset(int, int, int, int)
     * @hide
     */
    @NonNull
    public WindowInsets inset(Insets insets) {
        return inset(insets.left, insets.top, insets.right, insets.bottom);
    }

    /**
     * Returns a copy of this instance inset in the given directions.
     *
     * This is intended for dispatching insets to areas of the window that are smaller than the
     * current area.
     *
     * <p>Example:
     * <pre>
     * childView.dispatchApplyWindowInsets(insets.inset(
     *         childMarginLeft, childMarginTop, childMarginBottom, childMarginRight));
     * </pre>
     *
     * @param left the amount of insets to remove from the left. Must be non-negative.
     * @param top the amount of insets to remove from the top. Must be non-negative.
     * @param right the amount of insets to remove from the right. Must be non-negative.
     * @param bottom the amount of insets to remove from the bottom. Must be non-negative.
     *
     * @return the inset insets
     */
    @NonNull
    public WindowInsets inset(int left, int top, int right, int bottom) {
        Preconditions.checkArgumentNonnegative(left);
        Preconditions.checkArgumentNonnegative(top);
        Preconditions.checkArgumentNonnegative(right);
        Preconditions.checkArgumentNonnegative(bottom);

        return new WindowInsets(
                mSystemWindowInsetsConsumed ? null :
                        insetInsets(mSystemWindowInsets, left, top, right, bottom),
                mWindowDecorInsetsConsumed ? null :
                        insetInsets(mWindowDecorInsets, left, top, right, bottom),
                mStableInsetsConsumed ? null :
                        insetInsets(mStableInsets, left, top, right, bottom),
                mIsRound, mAlwaysConsumeNavBar,
                mDisplayCutoutConsumed
                        ? null :
                        mDisplayCutout == null
                                ? DisplayCutout.NO_CUTOUT
                                : mDisplayCutout.inset(left, top, right, bottom));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof WindowInsets)) return false;
        WindowInsets that = (WindowInsets) o;
        return mIsRound == that.mIsRound
                && mAlwaysConsumeNavBar == that.mAlwaysConsumeNavBar
                && mSystemWindowInsetsConsumed == that.mSystemWindowInsetsConsumed
                && mWindowDecorInsetsConsumed == that.mWindowDecorInsetsConsumed
                && mStableInsetsConsumed == that.mStableInsetsConsumed
                && mDisplayCutoutConsumed == that.mDisplayCutoutConsumed
                && Objects.equals(mSystemWindowInsets, that.mSystemWindowInsets)
                && Objects.equals(mWindowDecorInsets, that.mWindowDecorInsets)
                && Objects.equals(mStableInsets, that.mStableInsets)
                && Objects.equals(mDisplayCutout, that.mDisplayCutout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSystemWindowInsets, mWindowDecorInsets, mStableInsets, mIsRound,
                mDisplayCutout, mAlwaysConsumeNavBar, mSystemWindowInsetsConsumed,
                mWindowDecorInsetsConsumed, mStableInsetsConsumed, mDisplayCutoutConsumed);
    }

    private static Insets insetInsets(Insets insets, int left, int top, int right, int bottom) {
        int newLeft = Math.max(0, insets.left - left);
        int newTop = Math.max(0, insets.top - top);
        int newRight = Math.max(0, insets.right - right);
        int newBottom = Math.max(0, insets.bottom - bottom);
        if (newLeft == left && newTop == top && newRight == right && newBottom == bottom) {
            return insets;
        }
        return Insets.of(newLeft, newTop, newRight, newBottom);
    }

    private static Insets insetsOrNull(Rect insets) {
        return insets != null ? Insets.of(insets) : null;
    }

    /**
     * @return whether system window insets have been consumed.
     */
    boolean isSystemWindowInsetsConsumed() {
        return mSystemWindowInsetsConsumed;
    }

    /**
     * Builder for WindowInsets.
     */
    public static class Builder {

        private Insets mSystemWindowInsets;
        private Insets mStableInsets;
        private DisplayCutout mDisplayCutout;

        private Insets mWindowDecorInsets;
        private boolean mIsRound;
        private boolean mAlwaysConsumeNavBar;

        /**
         * Creates a builder where all insets are initially consumed.
         */
        public Builder() {
        }

        /**
         * Creates a builder where all insets are initialized from {@link WindowInsets}.
         *
         * @param insets the instance to initialize from.
         */
        public Builder(WindowInsets insets) {
            mSystemWindowInsets = insets.mSystemWindowInsetsConsumed ? null
                    : insets.mSystemWindowInsets;
            mStableInsets = insets.mStableInsetsConsumed ? null : insets.mStableInsets;
            mDisplayCutout = displayCutoutCopyConstructorArgument(insets);
            mWindowDecorInsets =  insets.mWindowDecorInsetsConsumed ? null
                    : insets.mWindowDecorInsets;
            mIsRound = insets.mIsRound;
            mAlwaysConsumeNavBar = insets.mAlwaysConsumeNavBar;
        }

        /**
         * Sets system window insets in pixels.
         *
         * <p>The system window inset represents the area of a full-screen window that is
         * partially or fully obscured by the status bar, navigation bar, IME or other system
         * windows.</p>
         *
         * @see #getSystemWindowInsets()
         * @return itself
         */
        @NonNull
        public Builder setSystemWindowInsets(@NonNull Insets systemWindowInsets) {
            Preconditions.checkNotNull(systemWindowInsets);
            mSystemWindowInsets = systemWindowInsets;
            return this;
        }

        /**
         * Sets the stable insets in pixels.
         *
         * <p>The stable inset represents the area of a full-screen window that <b>may</b> be
         * partially or fully obscured by the system UI elements.  This value does not change
         * based on the visibility state of those elements; for example, if the status bar is
         * normally shown, but temporarily hidden, the stable inset will still provide the inset
         * associated with the status bar being shown.</p>
         *
         * @see #getStableInsets()
         * @return itself
         */
        @NonNull
        public Builder setStableInsets(@NonNull Insets stableInsets) {
            Preconditions.checkNotNull(stableInsets);
            mStableInsets = stableInsets;
            return this;
        }

        /**
         * Sets the display cutout.
         *
         * @see #getDisplayCutout()
         * @param displayCutout the display cutout or null if there is none
         * @return itself
         */
        @NonNull
        public Builder setDisplayCutout(@Nullable DisplayCutout displayCutout) {
            mDisplayCutout = displayCutout != null ? displayCutout : DisplayCutout.NO_CUTOUT;
            return this;
        }

        /** @hide */
        @NonNull
        public Builder setWindowDecorInsets(@NonNull Insets windowDecorInsets) {
            Preconditions.checkNotNull(windowDecorInsets);
            mWindowDecorInsets = windowDecorInsets;
            return this;
        }

        /** @hide */
        @NonNull
        public Builder setRound(boolean round) {
            mIsRound = round;
            return this;
        }

        /** @hide */
        @NonNull
        public Builder setAlwaysConsumeNavBar(boolean alwaysConsumeNavBar) {
            mAlwaysConsumeNavBar = alwaysConsumeNavBar;
            return this;
        }

        /**
         * Builds a {@link WindowInsets} instance.
         *
         * @return the {@link WindowInsets} instance.
         */
        @NonNull
        public WindowInsets build() {
            return new WindowInsets(mSystemWindowInsets, mWindowDecorInsets, mStableInsets,
                    mIsRound, mAlwaysConsumeNavBar, mDisplayCutout);
        }
    }
}
