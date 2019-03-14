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

import static android.view.WindowInsets.Type.FIRST;
import static android.view.WindowInsets.Type.IME;
import static android.view.WindowInsets.Type.LAST;
import static android.view.WindowInsets.Type.MANDATORY_SYSTEM_GESTURES;
import static android.view.WindowInsets.Type.SIDE_BARS;
import static android.view.WindowInsets.Type.SIZE;
import static android.view.WindowInsets.Type.SYSTEM_GESTURES;
import static android.view.WindowInsets.Type.TAPPABLE_ELEMENT;
import static android.view.WindowInsets.Type.TOP_BAR;
import static android.view.WindowInsets.Type.all;
import static android.view.WindowInsets.Type.compatSystemInsets;
import static android.view.WindowInsets.Type.indexOf;
import static android.view.WindowInsets.Type.mandatorySystemGestures;
import static android.view.WindowInsets.Type.systemGestures;
import static android.view.WindowInsets.Type.tappableElement;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.graphics.Insets;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.WindowInsets.Type.InsetType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethod;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
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

    private final Insets[] mTypeInsetsMap;
    private final Insets[] mTypeMaxInsetsMap;
    private final boolean[] mTypeVisibilityMap;

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
        CONSUMED = new WindowInsets((Rect) null, null, false, false, null);
    }

    /**
     * Construct a new WindowInsets from individual insets.
     *
     * A {@code null} inset indicates that the respective inset is consumed.
     *
     * @hide
     * @deprecated Use {@link WindowInsets(SparseArray, SparseArray, boolean, boolean, DisplayCutout)}
     */
    public WindowInsets(Rect systemWindowInsetsRect, Rect stableInsetsRect,
            boolean isRound, boolean alwaysConsumeNavBar, DisplayCutout displayCutout) {
        this(createCompatTypeMap(systemWindowInsetsRect), createCompatTypeMap(stableInsetsRect),
                createCompatVisibilityMap(createCompatTypeMap(systemWindowInsetsRect)),
                isRound, alwaysConsumeNavBar, displayCutout);
    }

    /**
     * Construct a new WindowInsets from individual insets.
     *
     * {@code typeInsetsMap} and {@code typeMaxInsetsMap} are a map of indexOf(type) -> insets that
     * contain the information what kind of system bars causes how much insets. The insets in this
     * map are non-additive; i.e. they have the same origin. In other words: If two system bars
     * overlap on one side, the insets of the larger bar will also include the insets of the smaller
     * bar.
     *
     * {@code null} type inset map indicates that the respective inset is fully consumed.
     * @hide
     */
    public WindowInsets(@Nullable Insets[] typeInsetsMap,
            @Nullable Insets[] typeMaxInsetsMap,
            boolean[] typeVisibilityMap,
            boolean isRound,
            boolean alwaysConsumeNavBar, DisplayCutout displayCutout) {
        mSystemWindowInsetsConsumed = typeInsetsMap == null;
        mTypeInsetsMap = mSystemWindowInsetsConsumed
                ? new Insets[SIZE]
                : typeInsetsMap.clone();

        mStableInsetsConsumed = typeMaxInsetsMap == null;
        mTypeMaxInsetsMap = mStableInsetsConsumed
                ? new Insets[SIZE]
                : typeMaxInsetsMap.clone();

        mTypeVisibilityMap = typeVisibilityMap;
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
        this(src.mTypeInsetsMap, src.mTypeMaxInsetsMap, src.mTypeVisibilityMap, src.mIsRound,
                src.mAlwaysConsumeNavBar, displayCutoutCopyConstructorArgument(src));
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

    /**
     * @return The insets that include system bars indicated by {@code typeMask}, taken from
     *         {@code typeInsetMap}.
     */
    private static Insets getInsets(Insets[] typeInsetsMap, @InsetType int typeMask) {
        Insets result = null;
        for (int i = FIRST; i <= LAST; i = i << 1) {
            if ((typeMask & i) == 0) {
                continue;
            }
            Insets insets = typeInsetsMap[indexOf(i)];
            if (insets == null) {
                continue;
            }
            if (result == null) {
                result = insets;
            } else {
                result = Insets.max(result, insets);
            }
        }
        return result == null ? Insets.NONE : result;
    }

    /**
     * Sets all entries in {@code typeInsetsMap} that belong to {@code typeMask} to {@code insets},
     */
    private static void setInsets(Insets[] typeInsetsMap, @InsetType int typeMask, Insets insets) {
        for (int i = FIRST; i <= LAST; i = i << 1) {
            if ((typeMask & i) == 0) {
                continue;
            }
            typeInsetsMap[indexOf(i)] = insets;
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public WindowInsets(Rect systemWindowInsets) {
        this(createCompatTypeMap(systemWindowInsets), null, new boolean[SIZE], false, false, null);
    }

    /**
     * Creates a indexOf(type) -> inset map for which the {@code insets} is just mapped to
     * {@link InsetType#topBar()} and {@link InsetType#sideBars()}, depending on the location of the
     * inset.
     */
    private static Insets[] createCompatTypeMap(@Nullable Rect insets) {
        if (insets == null) {
            return null;
        }
        Insets[] typeInsetMap = new Insets[SIZE];
        assignCompatInsets(typeInsetMap, insets);
        // TODO: set system gesture insets based on actual system gesture area.
        typeInsetMap[indexOf(systemGestures())] = Insets.of(insets);
        typeInsetMap[indexOf(mandatorySystemGestures())] = Insets.of(insets);
        typeInsetMap[indexOf(tappableElement())] = Insets.of(insets);
        return typeInsetMap;
    }

    /**
     * @hide
     */
    static void assignCompatInsets(Insets[] typeInsetMap, Rect insets) {
        typeInsetMap[indexOf(TOP_BAR)] = Insets.of(0, insets.top, 0, 0);
        typeInsetMap[indexOf(SIDE_BARS)] = Insets.of(insets.left, 0, insets.right, insets.bottom);
    }

    private static boolean[] createCompatVisibilityMap(@Nullable Insets[] typeInsetMap) {
        boolean[] typeVisibilityMap = new boolean[SIZE];
        if (typeInsetMap == null) {
            return typeVisibilityMap;
        }
        for (int i = FIRST; i <= LAST; i = i << 1) {
            int index = indexOf(i);
            if (!Insets.NONE.equals(typeInsetMap[index])) {
                typeVisibilityMap[index] = true;
            }
        }
        return typeVisibilityMap;
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
        Insets insets = getSystemWindowInsets();
        mTempRect.set(insets.left, insets.top, insets.right, insets.bottom);
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
        return getInsets(mTypeInsetsMap, compatSystemInsets());
    }

    /**
     * Returns the insets of a specific set of windows causing insets, denoted by the
     * {@code typeMask} bit mask of {@link InsetType}s.
     *
     * @param typeMask Bit mask of {@link InsetType}s to query the insets for.
     * @return The insets.
     *
     * @hide pending unhide
     */
    public Insets getInsets(@InsetType int typeMask) {
        return getInsets(mTypeInsetsMap, typeMask);
    }

    /**
     * Returns the maximum amount of insets a specific set of windows can cause, denoted by the
     * {@code typeMask} bit mask of {@link InsetType}s.
     *
     * <p>The maximum insets represents the area of a a window that that <b>may</b> be partially
     * or fully obscured by the system window identified by {@code type}. This value does not
     * change based on the visibility state of those elements. for example, if the status bar is
     * normally shown, but temporarily hidden, the maximum inset will still provide the inset
     * associated with the status bar being shown.</p>
     *
     * @param typeMask Bit mask of {@link InsetType}s to query the insets for.
     * @return The insets.
     *
     * @throws IllegalArgumentException If the caller tries to query {@link Type#ime()}. Maximum
     *                                  insets are not available for this type as the height of the
     *                                  IME is dynamic depending on the {@link EditorInfo} of the
     *                                  currently focused view, as well as the UI state of the IME.
     * @hide pending unhide
     */
    public Insets getMaxInsets(@InsetType int typeMask) throws IllegalArgumentException {
        if ((typeMask & IME) != 0) {
            throw new IllegalArgumentException("Unable to query the maximum insets for IME");
        }
        return getInsets(mTypeMaxInsetsMap, typeMask);
    }

    /**
     * Returns whether a set of windows that may cause insets is currently visible on screen,
     * regardless of whether it actually overlaps with this window.
     *
     * @param typeMask Bit mask of {@link InsetType}s to query visibility status.
     * @return {@code true} if and only if all windows included in {@code typeMask} are currently
     *         visible on screen.
     * @hide pending unhide
     */
    public boolean isVisible(@InsetType int typeMask) {
        for (int i = FIRST; i <= LAST; i = i << 1) {
            if ((typeMask & i) == 0) {
                continue;
            }
            if (!mTypeVisibilityMap[indexOf(i)]) {
                return false;
            }
        }
        return true;
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
        return getSystemWindowInsets().left;
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
        return getSystemWindowInsets().top;
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
        return getSystemWindowInsets().right;
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
        return getSystemWindowInsets().bottom;
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
        return !getSystemWindowInsets().equals(Insets.NONE);
    }

    /**
     * Returns true if this WindowInsets has any nonzero insets.
     *
     * @return true if any inset values are nonzero
     */
    public boolean hasInsets() {
        return !getInsets(mTypeInsetsMap, all()).equals(Insets.NONE)
                || !getInsets(mTypeMaxInsetsMap, all()).equals(Insets.NONE)
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
        return new WindowInsets(mSystemWindowInsetsConsumed ? null : mTypeInsetsMap,
                mStableInsetsConsumed ? null : mTypeMaxInsetsMap,
                mTypeVisibilityMap,
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
        return mSystemWindowInsetsConsumed && mStableInsetsConsumed
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
        return new WindowInsets(null, mStableInsetsConsumed ? null : mTypeMaxInsetsMap,
                mTypeVisibilityMap,
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
        return getInsets(mTypeMaxInsetsMap, compatSystemInsets());
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
        return getStableInsets().top;
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
        return getStableInsets().left;
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
        return getStableInsets().right;
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
        return getStableInsets().bottom;
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
        return !getStableInsets().equals(Insets.NONE);
    }

    /**
     * Returns the system gesture insets.
     *
     * <p>The system gesture insets represent the area of a window where system gestures have
     * priority and may consume some or all touch input, e.g. due to the a system bar
     * occupying it, or it being reserved for touch-only gestures.
     *
     * <p>An app can declare priority over system gestures with
     * {@link View#setSystemGestureExclusionRects} outside of the
     * {@link #getMandatorySystemGestureInsets() mandatory system gesture insets}.
     *
     * <p>Simple taps are guaranteed to reach the window even within the system gesture insets,
     * as long as they are outside the {@link #getTappableElementInsets() system window insets}.
     *
     * <p>When {@link View#SYSTEM_UI_FLAG_LAYOUT_STABLE} is requested, an inset will be returned
     * even when the system gestures are inactive due to
     * {@link View#SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN} or
     * {@link View#SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION}.
     *
     * <p>This inset is consumed together with the {@link #getSystemWindowInsets()
     * system window insets} by {@link #consumeSystemWindowInsets()}.
     *
     * @see #getMandatorySystemGestureInsets
     */
    @NonNull
    public Insets getSystemGestureInsets() {
        return getInsets(mTypeInsetsMap, SYSTEM_GESTURES);
    }

    /**
     * Returns the mandatory system gesture insets.
     *
     * <p>The mandatory system gesture insets represent the area of a window where mandatory system
     * gestures have priority and may consume some or all touch input, e.g. due to the a system bar
     * occupying it, or it being reserved for touch-only gestures.
     *
     * <p>In contrast to {@link #getSystemGestureInsets regular system gestures}, <b>mandatory</b>
     * system gestures cannot be overriden by {@link View#setSystemGestureExclusionRects}.
     *
     * <p>Simple taps are guaranteed to reach the window even within the system gesture insets,
     * as long as they are outside the {@link #getTappableElementInsets() system window insets}.
     *
     * <p>When {@link View#SYSTEM_UI_FLAG_LAYOUT_STABLE} is requested, an inset will be returned
     * even when the system gestures are inactive due to
     * {@link View#SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN} or
     * {@link View#SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION}.
     *
     * <p>This inset is consumed together with the {@link #getSystemWindowInsets()
     * system window insets} by {@link #consumeSystemWindowInsets()}.
     *
     * @see #getSystemGestureInsets
     */
    @NonNull
    public Insets getMandatorySystemGestureInsets() {
        return getInsets(mTypeInsetsMap, MANDATORY_SYSTEM_GESTURES);
    }

    /**
     * Returns the tappable element insets.
     *
     * <p>The tappable element insets represent how much tappable elements <b>must at least</b> be
     * inset to remain both tappable and visually unobstructed by persistent system windows.
     *
     * <p>This may be smaller than {@link #getSystemWindowInsets()} if the system window is
     * largely transparent and lets through simple taps (but not necessarily more complex gestures).
     *
     * <p>Note that generally, tappable elements <strong>should</strong> be aligned with the
     * {@link #getSystemWindowInsets() system window insets} instead to avoid overlapping with the
     * system bars.
     *
     * <p>When {@link View#SYSTEM_UI_FLAG_LAYOUT_STABLE} is requested, an inset will be returned
     * even when the area covered by the inset would be tappable due to
     * {@link View#SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN} or
     * {@link View#SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION}.
     *
     * <p>This inset is consumed together with the {@link #getSystemWindowInsets()
     * system window insets} by {@link #consumeSystemWindowInsets()}.
     */
    @NonNull
    public Insets getTappableElementInsets() {
        return getInsets(mTypeInsetsMap, TAPPABLE_ELEMENT);
    }

    /**
     * Returns a copy of this WindowInsets with the stable insets fully consumed.
     *
     * @return A modified copy of this WindowInsets
     */
    @NonNull
    public WindowInsets consumeStableInsets() {
        return new WindowInsets(mSystemWindowInsetsConsumed ? null : mTypeInsetsMap, null,
                mTypeVisibilityMap, mIsRound, mAlwaysConsumeNavBar,
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
        return "WindowInsets{systemWindowInsets=" + getSystemWindowInsets()
                + " stableInsets=" + getStableInsets()
                + " sysGestureInsets=" + getSystemGestureInsets()
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
    public WindowInsets inset(@IntRange(from = 0) int left, @IntRange(from = 0) int top,
            @IntRange(from = 0) int right, @IntRange(from = 0) int bottom) {
        Preconditions.checkArgumentNonnegative(left);
        Preconditions.checkArgumentNonnegative(top);
        Preconditions.checkArgumentNonnegative(right);
        Preconditions.checkArgumentNonnegative(bottom);

        return new WindowInsets(
                mSystemWindowInsetsConsumed
                        ? null
                        : insetInsets(mTypeInsetsMap, left, top, right, bottom),
                mStableInsetsConsumed
                        ? null
                        : insetInsets(mTypeMaxInsetsMap, left, top, right, bottom),
                mTypeVisibilityMap,
                mIsRound, mAlwaysConsumeNavBar,
                mDisplayCutoutConsumed
                        ? null
                        : mDisplayCutout == null
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
                && mStableInsetsConsumed == that.mStableInsetsConsumed
                && mDisplayCutoutConsumed == that.mDisplayCutoutConsumed
                && Arrays.equals(mTypeInsetsMap, that.mTypeInsetsMap)
                && Arrays.equals(mTypeMaxInsetsMap, that.mTypeMaxInsetsMap)
                && Arrays.equals(mTypeVisibilityMap, that.mTypeVisibilityMap)
                && Objects.equals(mDisplayCutout, that.mDisplayCutout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(mTypeInsetsMap), Arrays.hashCode(mTypeMaxInsetsMap),
                Arrays.hashCode(mTypeVisibilityMap), mIsRound, mDisplayCutout, mAlwaysConsumeNavBar,
                mSystemWindowInsetsConsumed, mStableInsetsConsumed, mDisplayCutoutConsumed);
    }


    /**
     * Insets every inset in {@code typeInsetsMap} by the specified left, top, right, bottom.
     *
     * @return {@code typeInsetsMap} if no inset was modified; a copy of the map with the modified
     *          insets otherwise.
     */
    private static Insets[] insetInsets(
            Insets[] typeInsetsMap, int left, int top, int right, int bottom) {
        boolean cloned = false;
        for (int i = 0; i < SIZE; i++) {
            Insets insets = typeInsetsMap[i];
            if (insets == null) {
                continue;
            }
            Insets insetInsets = insetInsets(insets, left, top, right, bottom);
            if (insetInsets != insets) {
                if (!cloned) {
                    typeInsetsMap = typeInsetsMap.clone();
                    cloned = true;
                }
                typeInsetsMap[i] = insetInsets;
            }
        }
        return typeInsetsMap;
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

    /**
     * @return whether system window insets have been consumed.
     */
    boolean isSystemWindowInsetsConsumed() {
        return mSystemWindowInsetsConsumed;
    }

    /**
     * Builder for WindowInsets.
     */
    public static final class Builder {

        private final Insets[] mTypeInsetsMap;
        private final Insets[] mTypeMaxInsetsMap;
        private final boolean[] mTypeVisibilityMap;
        private boolean mSystemInsetsConsumed = true;
        private boolean mStableInsetsConsumed = true;

        private DisplayCutout mDisplayCutout;

        private boolean mIsRound;
        private boolean mAlwaysConsumeNavBar;

        /**
         * Creates a builder where all insets are initially consumed.
         */
        public Builder() {
            mTypeInsetsMap = new Insets[SIZE];
            mTypeMaxInsetsMap = new Insets[SIZE];
            mTypeVisibilityMap = new boolean[SIZE];
        }

        /**
         * Creates a builder where all insets are initialized from {@link WindowInsets}.
         *
         * @param insets the instance to initialize from.
         */
        public Builder(@NonNull WindowInsets insets) {
            mTypeInsetsMap = insets.mTypeInsetsMap.clone();
            mTypeMaxInsetsMap = insets.mTypeMaxInsetsMap.clone();
            mTypeVisibilityMap = insets.mTypeVisibilityMap.clone();
            mSystemInsetsConsumed = insets.mSystemWindowInsetsConsumed;
            mStableInsetsConsumed = insets.mStableInsetsConsumed;
            mDisplayCutout = displayCutoutCopyConstructorArgument(insets);
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
            assignCompatInsets(mTypeInsetsMap, systemWindowInsets.toRect());
            mSystemInsetsConsumed = false;
            return this;
        }

        /**
         * Sets system gesture insets in pixels.
         *
         * <p>The system gesture insets represent the area of a window where system gestures have
         * priority and may consume some or all touch input, e.g. due to the a system bar
         * occupying it, or it being reserved for touch-only gestures.
         *
         * @see #getSystemGestureInsets()
         * @return itself
         */
        @NonNull
        public Builder setSystemGestureInsets(@NonNull Insets insets) {
            WindowInsets.setInsets(mTypeInsetsMap, SYSTEM_GESTURES, insets);
            return this;
        }

        /**
         * Sets mandatory system gesture insets in pixels.
         *
         * <p>The mandatory system gesture insets represent the area of a window where mandatory
         * system gestures have priority and may consume some or all touch input, e.g. due to the a
         * system bar occupying it, or it being reserved for touch-only gestures.
         *
         * <p>In contrast to {@link #setSystemGestureInsets regular system gestures},
         * <b>mandatory</b> system gestures cannot be overriden by
         * {@link View#setSystemGestureExclusionRects}.
         *
         * @see #getMandatorySystemGestureInsets()
         * @return itself
         */
        @NonNull
        public Builder setMandatorySystemGestureInsets(@NonNull Insets insets) {
            WindowInsets.setInsets(mTypeInsetsMap, MANDATORY_SYSTEM_GESTURES, insets);
            return this;
        }

        /**
         * Sets tappable element insets in pixels.
         *
         * <p>The tappable element insets represent how much tappable elements <b>must at least</b>
         * be inset to remain both tappable and visually unobstructed by persistent system windows.
         *
         * @see #getTappableElementInsets()
         * @return itself
         */
        @NonNull
        public Builder setTappableElementInsets(@NonNull Insets insets) {
            WindowInsets.setInsets(mTypeInsetsMap, TAPPABLE_ELEMENT, insets);
            return this;
        }

        /**
         * Sets the insets of a specific window type in pixels.
         *
         * <p>The insets represents the area of a a window that is partially or fully obscured by
         * the system windows identified by {@code typeMask}.
         * </p>
         *
         * @see #getInsets(int)
         *
         * @param typeMask The bitmask of {@link InsetType} to set the insets for.
         * @param insets The insets to set.
         *
         * @return itself
         * @hide pending unhide
         */
        @NonNull
        public Builder setInsets(@InsetType int typeMask, @NonNull Insets insets) {
            Preconditions.checkNotNull(insets);
            WindowInsets.setInsets(mTypeInsetsMap, typeMask, insets);
            mSystemInsetsConsumed = false;
            return this;
        }

        /**
         * Sets the maximum amount of insets a specific window type in pixels.
         *
         * <p>The maximum insets represents the area of a a window that that <b>may</b> be partially
         * or fully obscured by the system windows identified by {@code typeMask}. This value does
         * not change based on the visibility state of those elements. for example, if the status
         * bar is normally shown, but temporarily hidden, the maximum inset will still provide the
         * inset associated with the status bar being shown.</p>
         *
         * @see #getMaxInsets(int)
         *
         * @param typeMask The bitmask of {@link InsetType} to set the insets for.
         * @param insets The insets to set.
         *
         * @return itself
         *
         * @throws IllegalArgumentException If {@code typeMask} contains {@link Type#ime()}. Maximum
         *                                  insets are not available for this type as the height of
         *                                  the IME is dynamic depending on the {@link EditorInfo}
         *                                  of the currently focused view, as well as the UI
         *                                  state of the IME.
         * @hide pending unhide
         */
        @NonNull
        public Builder setMaxInsets(@InsetType int typeMask, @NonNull Insets insets)
                throws IllegalArgumentException{
            if (typeMask == IME) {
                throw new IllegalArgumentException("Maximum inset not available for IME");
            }
            Preconditions.checkNotNull(insets);
            WindowInsets.setInsets(mTypeMaxInsetsMap, typeMask, insets);
            mStableInsetsConsumed = false;
            return this;
        }

        /**
         * Sets whether windows that can cause insets are currently visible on screen.
         *
         *
         * @see #isVisible(int)
         *
         * @param typeMask The bitmask of {@link InsetType} to set the visibility for.
         * @param visible Whether to mark the windows as visible or not.
         *
         * @return itself
         * @hide pending unhide
         */
        @NonNull
        public Builder setVisible(@InsetType int typeMask, boolean visible) {
            for (int i = FIRST; i <= LAST; i = i << 1) {
                if ((typeMask & i) == 0) {
                    continue;
                }
                mTypeVisibilityMap[indexOf(i)] = visible;
            }
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
            assignCompatInsets(mTypeMaxInsetsMap, stableInsets.toRect());
            mStableInsetsConsumed = false;
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
            return new WindowInsets(mSystemInsetsConsumed ? null : mTypeInsetsMap,
                    mStableInsetsConsumed ? null : mTypeMaxInsetsMap, mTypeVisibilityMap,
                    mIsRound, mAlwaysConsumeNavBar, mDisplayCutout);
        }
    }

    /**
     * Class that defines different types of sources causing window insets.
     * @hide pending unhide
     */
    public static final class Type {

        static final int FIRST = 1 << 0;
        static final int TOP_BAR = FIRST;

        static final int IME = 1 << 1;
        static final int SIDE_BARS = 1 << 2;

        static final int SYSTEM_GESTURES = 1 << 3;
        static final int MANDATORY_SYSTEM_GESTURES = 1 << 4;
        static final int TAPPABLE_ELEMENT = 1 << 5;

        static final int LAST = 1 << 6;
        static final int SIZE = 7;
        static final int WINDOW_DECOR = LAST;

        static int indexOf(@InsetType int type) {
            switch (type) {
                case TOP_BAR:
                    return 0;
                case IME:
                    return 1;
                case SIDE_BARS:
                    return 2;
                case SYSTEM_GESTURES:
                    return 3;
                case MANDATORY_SYSTEM_GESTURES:
                    return 4;
                case TAPPABLE_ELEMENT:
                    return 5;
                case WINDOW_DECOR:
                    return 6;
                default:
                    throw new IllegalArgumentException("type needs to be >= FIRST and <= LAST,"
                            + " type=" + type);
            }
        }

        private Type() {
        }

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, value = { TOP_BAR, IME, SIDE_BARS, WINDOW_DECOR, SYSTEM_GESTURES,
                MANDATORY_SYSTEM_GESTURES, TAPPABLE_ELEMENT})
        public @interface InsetType {
        }

        /**
         * @return An inset type representing the top bar of a window, which can be the status
         *         bar on handheld-like devices as well as a caption bar.
         */
        public static @InsetType int topBar() {
            return TOP_BAR;
        }

        /**
         * @return An inset type representing the window of an {@link InputMethod}.
         */
        public static @InsetType int ime() {
            return IME;
        }

        /**
         * @return An inset type representing any system bars that are not {@link #topBar()}.
         */
        public static @InsetType int sideBars() {
            return SIDE_BARS;
        }

        /**
         * @return An inset type representing decor that is being app-controlled.
         */
        public static @InsetType int windowDecor() {
            return WINDOW_DECOR;
        }

        /**
         * Returns an inset type representing the system gesture insets.
         *
         * <p>The system gesture insets represent the area of a window where system gestures have
         * priority and may consume some or all touch input, e.g. due to the a system bar
         * occupying it, or it being reserved for touch-only gestures.
         *
         * <p>Simple taps are guaranteed to reach the window even within the system gesture insets,
         * as long as they are outside the {@link #getSystemWindowInsets() system window insets}.
         *
         * <p>When {@link View#SYSTEM_UI_FLAG_LAYOUT_STABLE} is requested, an inset will be returned
         * even when the system gestures are inactive due to
         * {@link View#SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN} or
         * {@link View#SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION}.
         *
         * @see #getSystemGestureInsets()
         */
        public static @InsetType int systemGestures() {
            return SYSTEM_GESTURES;
        }

        /**
         * @see #getMandatorySystemGestureInsets
         */
        public static @InsetType int mandatorySystemGestures() {
            return MANDATORY_SYSTEM_GESTURES;
        }

        /**
         * @see #getTappableElementInsets
         */
        public static @InsetType int tappableElement() {
            return TAPPABLE_ELEMENT;
        }

        /**
         * @return All system bars. Includes {@link #topBar()} as well as {@link #sideBars()}, but
         *         not {@link #ime()}.
         */
        public static @InsetType int systemBars() {
            return TOP_BAR | SIDE_BARS;
        }

        /**
         * @return Inset types representing the list of bars that traditionally were denoted as
         *         system insets.
         * @hide
         */
        static @InsetType int compatSystemInsets() {
            return TOP_BAR | SIDE_BARS | IME;
        }

        /**
         * @return All inset types combined.
         *
         * TODO: Figure out if this makes sense at all, mixing e.g {@link #systemGestures()} and
         *       {@link #ime()} does not seem very useful.
         */
        public static @InsetType int all() {
            return 0xFFFFFFFF;
        }
    }
}
