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

import static android.view.Surface.ROTATION_0;
import static android.view.WindowInsets.Type.DISPLAY_CUTOUT;
import static android.view.WindowInsets.Type.FIRST;
import static android.view.WindowInsets.Type.IME;
import static android.view.WindowInsets.Type.LAST;
import static android.view.WindowInsets.Type.MANDATORY_SYSTEM_GESTURES;
import static android.view.WindowInsets.Type.NAVIGATION_BARS;
import static android.view.WindowInsets.Type.SIZE;
import static android.view.WindowInsets.Type.STATUS_BARS;
import static android.view.WindowInsets.Type.SYSTEM_GESTURES;
import static android.view.WindowInsets.Type.TAPPABLE_ELEMENT;
import static android.view.WindowInsets.Type.all;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.indexOf;
import static android.view.WindowInsets.Type.systemBars;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.graphics.Insets;
import android.graphics.Rect;
import android.util.SparseArray;
import android.view.View.OnApplyWindowInsetsListener;
import android.view.WindowInsets.Type.InsetsType;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethod;

import com.android.internal.annotations.VisibleForTesting;
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
    @Nullable private final RoundedCorners mRoundedCorners;
    @Nullable private final PrivacyIndicatorBounds mPrivacyIndicatorBounds;

    /**
     * In multi-window we force show the navigation bar. Because we don't want that the surface size
     * changes in this mode, we instead have a flag whether the navigation bar size should always
     * be consumed, so the app is treated like there is no virtual navigation bar at all.
     */
    private final boolean mAlwaysConsumeSystemBars;

    private final boolean mSystemWindowInsetsConsumed;
    private final boolean mStableInsetsConsumed;
    private final boolean mDisplayCutoutConsumed;

    private final int mCompatInsetsTypes;
    private final boolean mCompatIgnoreVisibility;

    /**
     * A {@link WindowInsets} instance for which {@link #isConsumed()} returns {@code true}.
     * <p>
     * This can be used during insets dispatch in the view hierarchy by returning this value from
     * {@link View#onApplyWindowInsets(WindowInsets)} or
     * {@link OnApplyWindowInsetsListener#onApplyWindowInsets(View, WindowInsets)} to stop dispatch
     * the insets to its children to avoid traversing the entire view hierarchy.
     * <p>
     * The application should return this instance once it has taken care of all insets on a certain
     * level in the view hierarchy, and doesn't need to dispatch to its children anymore for better
     * performance.
     *
     * @see #isConsumed()
     */
    public static final @NonNull WindowInsets CONSUMED;

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
    @Deprecated
    public WindowInsets(Rect systemWindowInsetsRect, Rect stableInsetsRect, boolean isRound,
            boolean alwaysConsumeSystemBars, DisplayCutout displayCutout) {
        this(createCompatTypeMap(systemWindowInsetsRect), createCompatTypeMap(stableInsetsRect),
                createCompatVisibilityMap(createCompatTypeMap(systemWindowInsetsRect)),
                isRound, alwaysConsumeSystemBars, displayCutout, null, null,
                systemBars(), false /* compatIgnoreVisibility */);
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
            boolean alwaysConsumeSystemBars, DisplayCutout displayCutout,
            RoundedCorners roundedCorners,
            PrivacyIndicatorBounds privacyIndicatorBounds,
            @InsetsType int compatInsetsTypes, boolean compatIgnoreVisibility) {
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
        mAlwaysConsumeSystemBars = alwaysConsumeSystemBars;
        mCompatInsetsTypes = compatInsetsTypes;
        mCompatIgnoreVisibility = compatIgnoreVisibility;

        mDisplayCutoutConsumed = displayCutout == null;
        mDisplayCutout = (mDisplayCutoutConsumed || displayCutout.isEmpty())
                ? null : displayCutout;

        mRoundedCorners = roundedCorners;
        mPrivacyIndicatorBounds = privacyIndicatorBounds;
    }

    /**
     * Construct a new WindowInsets, copying all values from a source WindowInsets.
     *
     * @param src Source to copy insets from
     */
    public WindowInsets(WindowInsets src) {
        this(src.mSystemWindowInsetsConsumed ? null : src.mTypeInsetsMap,
                src.mStableInsetsConsumed ? null : src.mTypeMaxInsetsMap,
                src.mTypeVisibilityMap, src.mIsRound,
                src.mAlwaysConsumeSystemBars, displayCutoutCopyConstructorArgument(src),
                src.mRoundedCorners,
                src.mPrivacyIndicatorBounds,
                src.mCompatInsetsTypes,
                src.mCompatIgnoreVisibility);
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
     *         {@code typeInsetsMap}.
     */
    static Insets getInsets(Insets[] typeInsetsMap, @InsetsType int typeMask) {
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
    private static void setInsets(Insets[] typeInsetsMap, @InsetsType int typeMask, Insets insets) {
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
        this(createCompatTypeMap(systemWindowInsets), null, new boolean[SIZE], false, false, null,
                null, null, systemBars(), false /* compatIgnoreVisibility */);
    }

    /**
     * Creates a indexOf(type) -> inset map for which the {@code insets} is just mapped to
     * {@link Type#statusBars()} and {@link Type#navigationBars()}, depending on the
     * location of the inset.
     */
    private static Insets[] createCompatTypeMap(@Nullable Rect insets) {
        if (insets == null) {
            return null;
        }
        Insets[] typeInsetsMap = new Insets[SIZE];
        assignCompatInsets(typeInsetsMap, insets);
        return typeInsetsMap;
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public static void assignCompatInsets(Insets[] typeInsetsMap, Rect insets) {
        typeInsetsMap[indexOf(STATUS_BARS)] = Insets.of(0, insets.top, 0, 0);
        typeInsetsMap[indexOf(NAVIGATION_BARS)] =
                Insets.of(insets.left, 0, insets.right, insets.bottom);
    }

    private static boolean[] createCompatVisibilityMap(@Nullable Insets[] typeInsetsMap) {
        boolean[] typeVisibilityMap = new boolean[SIZE];
        if (typeInsetsMap == null) {
            return typeVisibilityMap;
        }
        for (int i = FIRST; i <= LAST; i = i << 1) {
            int index = indexOf(i);
            if (!Insets.NONE.equals(typeInsetsMap[index])) {
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
     * @deprecated Use {@link #getInsets(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
    @NonNull
    public Insets getSystemWindowInsets() {
        Insets result = mCompatIgnoreVisibility
                ? getInsetsIgnoringVisibility(mCompatInsetsTypes & ~ime())
                : getInsets(mCompatInsetsTypes);

        // We can't query max insets for IME, so we need to add it manually after.
        if ((mCompatInsetsTypes & ime()) != 0 && mCompatIgnoreVisibility) {
            result = Insets.max(result, getInsets(ime()));
        }
        return result;
    }

    /**
     * Returns the insets of a specific set of windows causing insets, denoted by the
     * {@code typeMask} bit mask of {@link Type}s.
     *
     * @param typeMask Bit mask of {@link Type}s to query the insets for.
     * @return The insets.
     */
    @NonNull
    public Insets getInsets(@InsetsType int typeMask) {
        return getInsets(mTypeInsetsMap, typeMask);
    }

    /**
     * Returns the insets a specific set of windows can cause, denoted by the
     * {@code typeMask} bit mask of {@link Type}s, regardless of whether that type is
     * currently visible or not.
     *
     * <p>The insets represents the area of a a window that that <b>may</b> be partially
     * or fully obscured by the system window identified by {@code type}. This value does not
     * change based on the visibility state of those elements. For example, if the status bar is
     * normally shown, but temporarily hidden, the inset returned here will still provide the inset
     * associated with the status bar being shown.</p>
     *
     * @param typeMask Bit mask of {@link Type}s to query the insets for.
     * @return The insets.
     *
     * @throws IllegalArgumentException If the caller tries to query {@link Type#ime()}. Insets are
     *                                  not available if the IME isn't visible as the height of the
     *                                  IME is dynamic depending on the {@link EditorInfo} of the
     *                                  currently focused view, as well as the UI state of the IME.
     */
    @NonNull
    public Insets getInsetsIgnoringVisibility(@InsetsType int typeMask) {
        if ((typeMask & IME) != 0) {
            throw new IllegalArgumentException("Unable to query the maximum insets for IME");
        }
        return getInsets(mTypeMaxInsetsMap, typeMask);
    }

    /**
     * Returns whether a set of windows that may cause insets is currently visible on screen,
     * regardless of whether it actually overlaps with this window.
     *
     * @param typeMask Bit mask of {@link Type}s to query visibility status.
     * @return {@code true} if and only if all windows included in {@code typeMask} are currently
     *         visible on screen.
     */
    public boolean isVisible(@InsetsType int typeMask) {
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
     * @deprecated Use {@link #getInsets(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
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
     * @deprecated Use {@link #getInsets(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
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
     * @deprecated Use {@link #getInsets(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
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
     * @deprecated Use {@link #getInsets(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
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
     * @deprecated Use {@link #getInsets(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
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
                || mDisplayCutout != null || mRoundedCorners != null;
    }

    /**
     * Returns the display cutout if there is one.
     *
     * <p>Note: the display cutout will already be {@link #consumeDisplayCutout consumed} during
     * dispatch to {@link View#onApplyWindowInsets}, unless the window has requested a
     * {@link WindowManager.LayoutParams#layoutInDisplayCutoutMode} other than
     * {@link WindowManager.LayoutParams#LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER never} or
     * {@link WindowManager.LayoutParams#LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT default}.
     *
     * @return the display cutout or null if there is none
     * @see DisplayCutout
     */
    @Nullable
    public DisplayCutout getDisplayCutout() {
        return mDisplayCutout;
    }

    /**
     * Returns the {@link RoundedCorner} of the given position if there is one.
     *
     * @param position the position of the rounded corner on the display. The value should be one of
     *                 the following:
     *                 {@link RoundedCorner#POSITION_TOP_LEFT},
     *                 {@link RoundedCorner#POSITION_TOP_RIGHT},
     *                 {@link RoundedCorner#POSITION_BOTTOM_RIGHT},
     *                 {@link RoundedCorner#POSITION_BOTTOM_LEFT}.
     * @return the rounded corner of the given position. Returns {@code null} if there is none or
     *         the rounded corner area is not inside the application's bounds.
     */
    @Nullable
    public RoundedCorner getRoundedCorner(@RoundedCorner.Position int position) {
        return mRoundedCorners == null ? null : mRoundedCorners.getRoundedCorner(position);
    }

    /**
     * Returns the {@link Rect} of the maximum bounds of the system privacy indicator, for the
     * current orientation, in relative coordinates, or null if the bounds have not been loaded yet.
     * <p>
     * The privacy indicator bounds are determined by SystemUI, and subsequently loaded once the
     * StatusBar window has been created and attached. The bounds for all rotations are calculated
     * and loaded at once, and this value is only expected to ever change on display or font scale
     * changes. As long as there is a StatusBar window, this value should not be expected to be
     * null.
     * <p>
     * The privacy indicator shows over apps when an app uses the microphone or camera permissions,
     * while an app is in immersive mode.
     *
     * @return A rectangle representing the maximum bounds of the indicator
     */
    public @Nullable Rect getPrivacyIndicatorBounds() {
        return mPrivacyIndicatorBounds == null ? null
                : mPrivacyIndicatorBounds.getStaticPrivacyIndicatorBounds();
    }

    /**
     * Returns a copy of this WindowInsets with the cutout fully consumed.
     *
     * @return A modified copy of this WindowInsets
     * @deprecated Consuming of different parts individually of a {@link WindowInsets} instance is
     * deprecated, since {@link WindowInsets} contains many different insets. Use {@link #CONSUMED}
     * instead to stop dispatching insets.
     */
    @Deprecated
    @NonNull
    public WindowInsets consumeDisplayCutout() {
        return new WindowInsets(mSystemWindowInsetsConsumed ? null : mTypeInsetsMap,
                mStableInsetsConsumed ? null : mTypeMaxInsetsMap,
                mTypeVisibilityMap,
                mIsRound, mAlwaysConsumeSystemBars,
                null /* displayCutout */, mRoundedCorners, mPrivacyIndicatorBounds,
                mCompatInsetsTypes, mCompatIgnoreVisibility);
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
     * @deprecated Consuming of different parts individually of a {@link WindowInsets} instance is
     * deprecated, since {@link WindowInsets} contains many different insets. Use {@link #CONSUMED}
     * instead to stop dispatching insets.
     */
    @Deprecated
    @NonNull
    public WindowInsets consumeSystemWindowInsets() {
        return new WindowInsets(null, null,
                mTypeVisibilityMap,
                mIsRound, mAlwaysConsumeSystemBars,
                displayCutoutCopyConstructorArgument(this),
                mRoundedCorners, mPrivacyIndicatorBounds, mCompatInsetsTypes,
                mCompatIgnoreVisibility);
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
     * @deprecated Use {@link #getInsetsIgnoringVisibility(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
    @NonNull
    public Insets getStableInsets() {
        return getInsets(mTypeMaxInsetsMap, systemBars());
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
     * @deprecated Use {@link #getInsetsIgnoringVisibility(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
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
     * @deprecated Use {@link #getInsetsIgnoringVisibility(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
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
     * @deprecated Use {@link #getInsetsIgnoringVisibility(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
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
     * @deprecated Use {@link #getInsetsIgnoringVisibility(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
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
     * @deprecated Use {@link #getInsetsIgnoringVisibility(int)} with {@link Type#systemBars()}
     * instead.
     */
    @Deprecated
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
     * <p>Note: the system will put a limit of <code>200dp</code> on the vertical extent of the
     * exclusions it takes into account. The limit does not apply while the navigation
     * bar is {@link View#SYSTEM_UI_FLAG_IMMERSIVE_STICKY stickily} hidden, nor to the
     * {@link android.inputmethodservice.InputMethodService input method} and
     * {@link Intent#CATEGORY_HOME home activity}.
     * </p>
     *
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
     * @deprecated Use {@link #getInsets(int)} with {@link Type#systemGestures()} instead.
     */
    @Deprecated
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
     * @deprecated Use {@link #getInsets(int)} with {@link Type#mandatorySystemGestures()} instead.
     */
    @Deprecated
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
     *
     * @deprecated Use {@link #getInsets(int)} with {@link Type#tappableElement()} instead.
     */
    @Deprecated
    @NonNull
    public Insets getTappableElementInsets() {
        return getInsets(mTypeInsetsMap, TAPPABLE_ELEMENT);
    }

    /**
     * Returns a copy of this WindowInsets with the stable insets fully consumed.
     *
     * @return A modified copy of this WindowInsets
     * @deprecated Consuming of different parts individually of a {@link WindowInsets} instance is
     * deprecated, since {@link WindowInsets} contains many different insets. Use {@link #CONSUMED}
     * instead to stop dispatching insets. On {@link android.os.Build.VERSION_CODES#R R}, this
     * method has no effect.
     */
    @Deprecated
    @NonNull
    public WindowInsets consumeStableInsets() {
        return this;
    }

    /**
     * @hide
     */
    public boolean shouldAlwaysConsumeSystemBars() {
        return mAlwaysConsumeSystemBars;
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder("WindowInsets{\n    ");
        for (int i = 0; i < SIZE; i++) {
            Insets insets = mTypeInsetsMap[i];
            Insets maxInsets = mTypeMaxInsetsMap[i];
            boolean visible = mTypeVisibilityMap[i];
            if (!Insets.NONE.equals(insets) || !Insets.NONE.equals(maxInsets) || visible) {
                result.append(Type.toString(1 << i)).append("=").append(insets)
                        .append(" max=").append(maxInsets)
                        .append(" vis=").append(visible)
                        .append("\n    ");
            }
        }

        result.append(mDisplayCutout != null ? "cutout=" + mDisplayCutout : "");
        result.append("\n    ");
        result.append(mRoundedCorners != null ? "roundedCorners=" + mRoundedCorners : "");
        result.append("\n    ");
        result.append(mPrivacyIndicatorBounds != null ? "privacyIndicatorBounds="
                + mPrivacyIndicatorBounds : "");
        result.append("\n    ");
        result.append("compatInsetsTypes=" + mCompatInsetsTypes);
        result.append("\n    ");
        result.append("compatIgnoreVisibility=" + mCompatIgnoreVisibility);
        result.append("\n    ");
        result.append("systemWindowInsetsConsumed=" + mSystemWindowInsetsConsumed);
        result.append("\n    ");
        result.append("stableInsetsConsumed=" + mStableInsetsConsumed);
        result.append("\n    ");
        result.append("displayCutoutConsumed=" + mDisplayCutoutConsumed);
        result.append("\n    ");
        result.append(isRound() ? "round" : "");
        result.append("}");
        return result.toString();
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
     * This is intended for dispatching insets to areas of the window that are smaller than the
     * current area.
     *
     * <p>Example:
     * <pre>
     * childView.dispatchApplyWindowInsets(insets.inset(childMargins));
     * </pre>
     *
     * @param insets the amount of insets to remove from all sides.
     *
     * @see #inset(int, int, int, int)
     */
    @NonNull
    public WindowInsets inset(@NonNull Insets insets) {
        Objects.requireNonNull(insets);
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
     *
     * @see #inset(Insets)
     */
    @NonNull
    public WindowInsets inset(@IntRange(from = 0) int left, @IntRange(from = 0) int top,
            @IntRange(from = 0) int right, @IntRange(from = 0) int bottom) {
        Preconditions.checkArgumentNonnegative(left);
        Preconditions.checkArgumentNonnegative(top);
        Preconditions.checkArgumentNonnegative(right);
        Preconditions.checkArgumentNonnegative(bottom);

        return insetUnchecked(left, top, right, bottom);
    }

    /**
     * @see #inset(int, int, int, int)
     * @hide
     */
    @NonNull
    public WindowInsets insetUnchecked(int left, int top, int right, int bottom) {
        return new WindowInsets(
                mSystemWindowInsetsConsumed
                        ? null
                        : insetInsets(mTypeInsetsMap, left, top, right, bottom),
                mStableInsetsConsumed
                        ? null
                        : insetInsets(mTypeMaxInsetsMap, left, top, right, bottom),
                mTypeVisibilityMap,
                mIsRound, mAlwaysConsumeSystemBars,
                mDisplayCutoutConsumed
                        ? null
                        : mDisplayCutout == null
                                ? DisplayCutout.NO_CUTOUT
                                : mDisplayCutout.inset(left, top, right, bottom),
                mRoundedCorners == null
                        ? RoundedCorners.NO_ROUNDED_CORNERS
                        : mRoundedCorners.inset(left, top, right, bottom),
                mPrivacyIndicatorBounds == null
                        ? null
                        : mPrivacyIndicatorBounds.inset(left, top, right, bottom),
                mCompatInsetsTypes, mCompatIgnoreVisibility);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof WindowInsets)) return false;
        WindowInsets that = (WindowInsets) o;

        return mIsRound == that.mIsRound
                && mAlwaysConsumeSystemBars == that.mAlwaysConsumeSystemBars
                && mSystemWindowInsetsConsumed == that.mSystemWindowInsetsConsumed
                && mStableInsetsConsumed == that.mStableInsetsConsumed
                && mDisplayCutoutConsumed == that.mDisplayCutoutConsumed
                && Arrays.equals(mTypeInsetsMap, that.mTypeInsetsMap)
                && Arrays.equals(mTypeMaxInsetsMap, that.mTypeMaxInsetsMap)
                && Arrays.equals(mTypeVisibilityMap, that.mTypeVisibilityMap)
                && Objects.equals(mDisplayCutout, that.mDisplayCutout)
                && Objects.equals(mRoundedCorners, that.mRoundedCorners)
                && Objects.equals(mPrivacyIndicatorBounds, that.mPrivacyIndicatorBounds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(mTypeInsetsMap), Arrays.hashCode(mTypeMaxInsetsMap),
                Arrays.hashCode(mTypeVisibilityMap), mIsRound, mDisplayCutout, mRoundedCorners,
                mAlwaysConsumeSystemBars, mSystemWindowInsetsConsumed, mStableInsetsConsumed,
                mDisplayCutoutConsumed, mPrivacyIndicatorBounds);
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

    static Insets insetInsets(Insets insets, int left, int top, int right, int bottom) {
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
        private RoundedCorners mRoundedCorners = RoundedCorners.NO_ROUNDED_CORNERS;

        private boolean mIsRound;
        private boolean mAlwaysConsumeSystemBars;

        private PrivacyIndicatorBounds mPrivacyIndicatorBounds = new PrivacyIndicatorBounds();

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
            mRoundedCorners = insets.mRoundedCorners;
            mIsRound = insets.mIsRound;
            mAlwaysConsumeSystemBars = insets.mAlwaysConsumeSystemBars;
            mPrivacyIndicatorBounds = insets.mPrivacyIndicatorBounds;
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
         * @deprecated Use {@link #setInsets(int, Insets)} with {@link Type#systemBars()}.
         */
        @Deprecated
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
         * @deprecated Use {@link #setInsets(int, Insets)} with {@link Type#systemGestures()}.
         */
        @Deprecated
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
         * @deprecated Use {@link #setInsets(int, Insets)} with
         *             {@link Type#mandatorySystemGestures()}.
         */
        @Deprecated
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
         * @deprecated Use {@link #setInsets(int, Insets)} with {@link Type#tappableElement()}.
         */
        @Deprecated
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
         * @param typeMask The bitmask of {@link Type} to set the insets for.
         * @param insets The insets to set.
         *
         * @return itself
         */
        @NonNull
        public Builder setInsets(@InsetsType int typeMask, @NonNull Insets insets) {
            Preconditions.checkNotNull(insets);
            WindowInsets.setInsets(mTypeInsetsMap, typeMask, insets);
            mSystemInsetsConsumed = false;
            return this;
        }

        /**
         * Sets the insets a specific window type in pixels, while ignoring its visibility state.
         *
         * <p>The insets represents the area of a a window that that <b>may</b> be partially
         * or fully obscured by the system window identified by {@code type}. This value does not
         * change based on the visibility state of those elements. For example, if the status bar is
         * normally shown, but temporarily hidden, the inset returned here will still provide the
         * inset associated with the status bar being shown.</p>
         *
         * @see #getInsetsIgnoringVisibility(int)
         *
         * @param typeMask The bitmask of {@link Type} to set the insets for.
         * @param insets The insets to set.
         *
         * @return itself
         *
         * @throws IllegalArgumentException If {@code typeMask} contains {@link Type#ime()}. Maximum
         *                                  insets are not available for this type as the height of
         *                                  the IME is dynamic depending on the {@link EditorInfo}
         *                                  of the currently focused view, as well as the UI
         *                                  state of the IME.
         */
        @NonNull
        public Builder setInsetsIgnoringVisibility(@InsetsType int typeMask, @NonNull Insets insets)
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
         * @param typeMask The bitmask of {@link Type} to set the visibility for.
         * @param visible Whether to mark the windows as visible or not.
         *
         * @return itself
         */
        @NonNull
        public Builder setVisible(@InsetsType int typeMask, boolean visible) {
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
         * @deprecated Use {@link #setInsetsIgnoringVisibility(int, Insets)} with
         *             {@link Type#systemBars()}.
         */
        @Deprecated
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
            if (!mDisplayCutout.isEmpty()) {
                final Insets safeInsets = Insets.of(mDisplayCutout.getSafeInsets());
                final int index = indexOf(DISPLAY_CUTOUT);
                mTypeInsetsMap[index] = safeInsets;
                mTypeMaxInsetsMap[index] = safeInsets;
                mTypeVisibilityMap[index] = true;
            }
            return this;
        }

        /** @hide */
        @NonNull
        public Builder setRoundedCorners(RoundedCorners roundedCorners) {
            mRoundedCorners = roundedCorners != null
                    ? roundedCorners : RoundedCorners.NO_ROUNDED_CORNERS;
            return this;
        }

        /**
         * Sets the rounded corner of given position.
         *
         * @see #getRoundedCorner(int)
         * @param position the position of this rounded corner
         * @param roundedCorner the rounded corner or null if there is none
         * @return itself
         */
        @NonNull
        public Builder setRoundedCorner(@RoundedCorner.Position int position,
                @Nullable RoundedCorner roundedCorner) {
            mRoundedCorners.setRoundedCorner(position, roundedCorner);
            return this;
        }

        /** @hide */
        @NonNull
        public Builder setPrivacyIndicatorBounds(@Nullable PrivacyIndicatorBounds bounds) {
            mPrivacyIndicatorBounds = bounds;
            return this;
        }

        /**
         * Sets the bounds of the system privacy indicator.
         *
         * @param bounds The bounds of the system privacy indicator
         */
        @NonNull
        public Builder setPrivacyIndicatorBounds(@Nullable Rect bounds) {
            //TODO 188788786: refactor the indicator bounds
            Rect[] boundsArr = { bounds, bounds, bounds, bounds };
            mPrivacyIndicatorBounds = new PrivacyIndicatorBounds(boundsArr, ROTATION_0);
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
        public Builder setAlwaysConsumeSystemBars(boolean alwaysConsumeSystemBars) {
            mAlwaysConsumeSystemBars = alwaysConsumeSystemBars;
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
                    mIsRound, mAlwaysConsumeSystemBars, mDisplayCutout, mRoundedCorners,
                    mPrivacyIndicatorBounds, systemBars(), false /* compatIgnoreVisibility */);
        }
    }

    /**
     * Class that defines different types of sources causing window insets.
     */
    public static final class Type {

        static final int FIRST = 1 << 0;
        static final int STATUS_BARS = FIRST;
        static final int NAVIGATION_BARS = 1 << 1;
        static final int CAPTION_BAR = 1 << 2;

        static final int IME = 1 << 3;

        static final int SYSTEM_GESTURES = 1 << 4;
        static final int MANDATORY_SYSTEM_GESTURES = 1 << 5;
        static final int TAPPABLE_ELEMENT = 1 << 6;

        static final int DISPLAY_CUTOUT = 1 << 7;

        static final int LAST = 1 << 8;
        static final int SIZE = 9;
        static final int WINDOW_DECOR = LAST;

        static int indexOf(@InsetsType int type) {
            switch (type) {
                case STATUS_BARS:
                    return 0;
                case NAVIGATION_BARS:
                    return 1;
                case CAPTION_BAR:
                    return 2;
                case IME:
                    return 3;
                case SYSTEM_GESTURES:
                    return 4;
                case MANDATORY_SYSTEM_GESTURES:
                    return 5;
                case TAPPABLE_ELEMENT:
                    return 6;
                case DISPLAY_CUTOUT:
                    return 7;
                case WINDOW_DECOR:
                    return 8;
                default:
                    throw new IllegalArgumentException("type needs to be >= FIRST and <= LAST,"
                            + " type=" + type);
            }
        }

        static String toString(@InsetsType int types) {
            StringBuilder result = new StringBuilder();
            if ((types & STATUS_BARS) != 0) {
                result.append("statusBars |");
            }
            if ((types & NAVIGATION_BARS) != 0) {
                result.append("navigationBars |");
            }
            if ((types & CAPTION_BAR) != 0) {
                result.append("captionBar |");
            }
            if ((types & IME) != 0) {
                result.append("ime |");
            }
            if ((types & SYSTEM_GESTURES) != 0) {
                result.append("systemGestures |");
            }
            if ((types & MANDATORY_SYSTEM_GESTURES) != 0) {
                result.append("mandatorySystemGestures |");
            }
            if ((types & TAPPABLE_ELEMENT) != 0) {
                result.append("tappableElement |");
            }
            if ((types & DISPLAY_CUTOUT) != 0) {
                result.append("displayCutout |");
            }
            if ((types & WINDOW_DECOR) != 0) {
                result.append("windowDecor |");
            }
            if (result.length() > 0) {
                result.delete(result.length() - 2, result.length());
            }
            return result.toString();
        }

        private Type() {
        }

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, value = {STATUS_BARS, NAVIGATION_BARS, CAPTION_BAR, IME, WINDOW_DECOR,
                SYSTEM_GESTURES, MANDATORY_SYSTEM_GESTURES, TAPPABLE_ELEMENT, DISPLAY_CUTOUT})
        public @interface InsetsType {
        }

        /**
         * @return An insets type representing any system bars for displaying status.
         */
        public static @InsetsType int statusBars() {
            return STATUS_BARS;
        }

        /**
         * @return An insets type representing any system bars for navigation.
         */
        public static @InsetsType int navigationBars() {
            return NAVIGATION_BARS;
        }

        /**
         * @return An insets type representing the window of a caption bar.
         */
        public static @InsetsType int captionBar() {
            return CAPTION_BAR;
        }

        /**
         * @return An insets type representing the window of an {@link InputMethod}.
         */
        public static @InsetsType int ime() {
            return IME;
        }

        /**
         * Returns an insets type representing the system gesture insets.
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
        public static @InsetsType int systemGestures() {
            return SYSTEM_GESTURES;
        }

        /**
         * @see #getMandatorySystemGestureInsets
         */
        public static @InsetsType int mandatorySystemGestures() {
            return MANDATORY_SYSTEM_GESTURES;
        }

        /**
         * @see #getTappableElementInsets
         */
        public static @InsetsType int tappableElement() {
            return TAPPABLE_ELEMENT;
        }

        /**
         * Returns an insets type representing the area that used by {@link DisplayCutout}.
         *
         * <p>This is equivalent to the safe insets on {@link #getDisplayCutout()}.
         *
         * <p>Note: During dispatch to {@link View#onApplyWindowInsets}, if the window is using
         * the {@link WindowManager.LayoutParams#LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT default}
         * {@link WindowManager.LayoutParams#layoutInDisplayCutoutMode}, {@link #getDisplayCutout()}
         * will return {@code null} even if the window overlaps a display cutout area, in which case
         * the {@link #displayCutout() displayCutout() inset} will still report the accurate value.
         *
         * @see DisplayCutout#getSafeInsetLeft()
         * @see DisplayCutout#getSafeInsetTop()
         * @see DisplayCutout#getSafeInsetRight()
         * @see DisplayCutout#getSafeInsetBottom()
         */
        public static @InsetsType int displayCutout() {
            return DISPLAY_CUTOUT;
        }

        /**
         * @return All system bars. Includes {@link #statusBars()}, {@link #captionBar()} as well as
         *         {@link #navigationBars()}, but not {@link #ime()}.
         */
        public static @InsetsType int systemBars() {
            return STATUS_BARS | NAVIGATION_BARS | CAPTION_BAR;
        }

        /**
         * @return All inset types combined.
         *
         * @hide
         */
        public static @InsetsType int all() {
            return 0xFFFFFFFF;
        }

        /**
         * Checks whether the specified type is considered to be part of visible insets.
         * @hide
         */
        public static boolean isVisibleInsetsType(int type,
                @SoftInputModeFlags int softInputModeFlags) {
            int softInputMode = softInputModeFlags & SOFT_INPUT_MASK_ADJUST;
            return (type & Type.systemBars()) != 0
                    || (softInputMode != SOFT_INPUT_ADJUST_NOTHING && (type & Type.ime()) != 0);
        }
    }

    /**
     * Class that defines different sides for insets.
     */
    public static final class Side {

        public static final int LEFT = 1 << 0;
        public static final int TOP = 1 << 1;
        public static final int RIGHT = 1 << 2;
        public static final int BOTTOM = 1 << 3;

        private Side() {
        }

        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = true, value = {LEFT, TOP, RIGHT, BOTTOM})
        public @interface InsetsSide {}

        /**
         * @return all four sides.
         */
        public static @InsetsSide int all() {
            return LEFT | TOP | RIGHT | BOTTOM;
        }
    }
}
