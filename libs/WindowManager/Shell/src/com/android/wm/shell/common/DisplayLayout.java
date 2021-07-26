/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.wm.shell.common;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.UI_MODE_TYPE_CAR;
import static android.content.res.Configuration.UI_MODE_TYPE_MASK;
import static android.os.Process.SYSTEM_UID;
import static android.provider.Settings.Global.DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS;
import static android.util.RotationUtils.rotateBounds;
import static android.util.RotationUtils.rotateInsets;
import static android.view.Display.FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.Surface;

import com.android.internal.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Contains information about the layout-properties of a display. This refers to internal layout
 * like insets/cutout/rotation. In general, this can be thought of as the shell analog to
 * DisplayPolicy.
 */
public class DisplayLayout {
    @IntDef(prefix = { "NAV_BAR_" }, value = {
            NAV_BAR_LEFT,
            NAV_BAR_RIGHT,
            NAV_BAR_BOTTOM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NavBarPosition {}

    // Navigation bar position values
    public static final int NAV_BAR_LEFT = 1 << 0;
    public static final int NAV_BAR_RIGHT = 1 << 1;
    public static final int NAV_BAR_BOTTOM = 1 << 2;

    private int mUiMode;
    private int mWidth;
    private int mHeight;
    private DisplayCutout mCutout;
    private int mRotation;
    private int mDensityDpi;
    private final Rect mNonDecorInsets = new Rect();
    private final Rect mStableInsets = new Rect();
    private boolean mHasNavigationBar = false;
    private boolean mHasStatusBar = false;
    private int mNavBarFrameHeight = 0;
    private boolean mAllowSeamlessRotationDespiteNavBarMoving = false;
    private boolean mNavigationBarCanMove = false;
    private boolean mReverseDefaultRotation = false;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DisplayLayout)) return false;
        final DisplayLayout other = (DisplayLayout) o;
        return mUiMode == other.mUiMode
                && mWidth == other.mWidth
                && mHeight == other.mHeight
                && Objects.equals(mCutout, other.mCutout)
                && mRotation == other.mRotation
                && mDensityDpi == other.mDensityDpi
                && Objects.equals(mNonDecorInsets, other.mNonDecorInsets)
                && Objects.equals(mStableInsets, other.mStableInsets)
                && mHasNavigationBar == other.mHasNavigationBar
                && mHasStatusBar == other.mHasStatusBar
                && mAllowSeamlessRotationDespiteNavBarMoving
                        == other.mAllowSeamlessRotationDespiteNavBarMoving
                && mNavigationBarCanMove == other.mNavigationBarCanMove
                && mReverseDefaultRotation == other.mReverseDefaultRotation
                && mNavBarFrameHeight == other.mNavBarFrameHeight;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUiMode, mWidth, mHeight, mCutout, mRotation, mDensityDpi,
                mNonDecorInsets, mStableInsets, mHasNavigationBar, mHasStatusBar,
                mNavBarFrameHeight, mAllowSeamlessRotationDespiteNavBarMoving,
                mNavigationBarCanMove, mReverseDefaultRotation);
    }

    /**
     * Create empty layout.
     */
    public DisplayLayout() {
    }

    /**
     * Construct a custom display layout using a DisplayInfo.
     * @param info
     * @param res
     */
    public DisplayLayout(DisplayInfo info, Resources res, boolean hasNavigationBar,
            boolean hasStatusBar) {
        init(info, res, hasNavigationBar, hasStatusBar);
    }

    /**
     * Construct a display layout based on a live display.
     * @param context Used for resources.
     */
    public DisplayLayout(@NonNull Context context, @NonNull Display rawDisplay) {
        final int displayId = rawDisplay.getDisplayId();
        DisplayInfo info = new DisplayInfo();
        rawDisplay.getDisplayInfo(info);
        init(info, context.getResources(), hasNavigationBar(info, context, displayId),
                hasStatusBar(displayId));
    }

    public DisplayLayout(DisplayLayout dl) {
        set(dl);
    }

    /** sets this DisplayLayout to a copy of another on. */
    public void set(DisplayLayout dl) {
        mUiMode = dl.mUiMode;
        mWidth = dl.mWidth;
        mHeight = dl.mHeight;
        mCutout = dl.mCutout;
        mRotation = dl.mRotation;
        mDensityDpi = dl.mDensityDpi;
        mHasNavigationBar = dl.mHasNavigationBar;
        mHasStatusBar = dl.mHasStatusBar;
        mAllowSeamlessRotationDespiteNavBarMoving = dl.mAllowSeamlessRotationDespiteNavBarMoving;
        mNavigationBarCanMove = dl.mNavigationBarCanMove;
        mReverseDefaultRotation = dl.mReverseDefaultRotation;
        mNavBarFrameHeight = dl.mNavBarFrameHeight;
        mNonDecorInsets.set(dl.mNonDecorInsets);
        mStableInsets.set(dl.mStableInsets);
    }

    private void init(DisplayInfo info, Resources res, boolean hasNavigationBar,
            boolean hasStatusBar) {
        mUiMode = res.getConfiguration().uiMode;
        mWidth = info.logicalWidth;
        mHeight = info.logicalHeight;
        mRotation = info.rotation;
        mCutout = info.displayCutout;
        mDensityDpi = info.logicalDensityDpi;
        mHasNavigationBar = hasNavigationBar;
        mHasStatusBar = hasStatusBar;
        mAllowSeamlessRotationDespiteNavBarMoving = res.getBoolean(
            R.bool.config_allowSeamlessRotationDespiteNavBarMoving);
        mNavigationBarCanMove = res.getBoolean(R.bool.config_navBarCanMove);
        mReverseDefaultRotation = res.getBoolean(R.bool.config_reverseDefaultRotation);
        recalcInsets(res);
    }

    private void recalcInsets(Resources res) {
        computeNonDecorInsets(res, mRotation, mWidth, mHeight, mCutout, mUiMode, mNonDecorInsets,
                mHasNavigationBar);
        mStableInsets.set(mNonDecorInsets);
        if (mHasStatusBar) {
            convertNonDecorInsetsToStableInsets(res, mStableInsets, mWidth, mHeight, mHasStatusBar);
        }
        mNavBarFrameHeight = getNavigationBarFrameHeight(res, mWidth > mHeight);
    }

    /**
     * Apply a rotation to this layout and its parameters.
     * @param res
     * @param targetRotation
     */
    public void rotateTo(Resources res, @Surface.Rotation int targetRotation) {
        final int rotationDelta = (targetRotation - mRotation + 4) % 4;
        final boolean changeOrient = (rotationDelta % 2) != 0;

        final int origWidth = mWidth;
        final int origHeight = mHeight;

        mRotation = targetRotation;
        if (changeOrient) {
            mWidth = origHeight;
            mHeight = origWidth;
        }

        if (mCutout != null && !mCutout.isEmpty()) {
            mCutout = calculateDisplayCutoutForRotation(mCutout, rotationDelta, origWidth,
                    origHeight);
        }

        recalcInsets(res);
    }

    /** Get this layout's non-decor insets. */
    public Rect nonDecorInsets() {
        return mNonDecorInsets;
    }

    /** Get this layout's stable insets. */
    public Rect stableInsets() {
        return mStableInsets;
    }

    /** Get this layout's width. */
    public int width() {
        return mWidth;
    }

    /** Get this layout's height. */
    public int height() {
        return mHeight;
    }

    /** Get this layout's display rotation. */
    public int rotation() {
        return mRotation;
    }

    /** Get this layout's display density. */
    public int densityDpi() {
        return mDensityDpi;
    }

    /** Get the density scale for the display. */
    public float density() {
        return mDensityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
    }

    /** Get whether this layout is landscape. */
    public boolean isLandscape() {
        return mWidth > mHeight;
    }

    /** Get the navbar frame height (used by ime). */
    public int navBarFrameHeight() {
        return mNavBarFrameHeight;
    }

    /** @return whether we can seamlessly rotate even if nav-bar can change sides. */
    public boolean allowSeamlessRotationDespiteNavBarMoving() {
        return mAllowSeamlessRotationDespiteNavBarMoving;
    }

    /** @return whether the navigation bar will change sides during rotation. */
    public boolean navigationBarCanMove() {
        return mNavigationBarCanMove;
    }

    /** @return the rotation that would make the physical display "upside down". */
    public int getUpsideDownRotation() {
        boolean displayHardwareIsLandscape = mWidth > mHeight;
        if ((mRotation % 2) != 0) {
            displayHardwareIsLandscape = !displayHardwareIsLandscape;
        }
        if (displayHardwareIsLandscape) {
            return mReverseDefaultRotation ? Surface.ROTATION_270 : Surface.ROTATION_90;
        }
        return Surface.ROTATION_180;
    }

    /** Gets the orientation of this layout */
    public int getOrientation() {
        return (mWidth > mHeight) ? ORIENTATION_LANDSCAPE : ORIENTATION_PORTRAIT;
    }

    /** Gets the calculated stable-bounds for this layout */
    public void getStableBounds(Rect outBounds) {
        outBounds.set(0, 0, mWidth, mHeight);
        outBounds.inset(mStableInsets);
    }

    /**
     * Gets navigation bar position for this layout
     * @return Navigation bar position for this layout.
     */
    public @NavBarPosition int getNavigationBarPosition(Resources res) {
        return navigationBarPosition(res, mWidth, mHeight, mRotation);
    }

    /**
     * Calculates the stable insets if we already have the non-decor insets.
     */
    private static void convertNonDecorInsetsToStableInsets(Resources res, Rect inOutInsets,
            int displayWidth, int displayHeight, boolean hasStatusBar) {
        if (!hasStatusBar) {
            return;
        }
        int statusBarHeight = getStatusBarHeight(displayWidth > displayHeight, res);
        inOutInsets.top = Math.max(inOutInsets.top, statusBarHeight);
    }

    /**
     * Calculates the insets for the areas that could never be removed in Honeycomb, i.e. system
     * bar or button bar.
     *
     * @param displayRotation the current display rotation
     * @param displayWidth the current display width
     * @param displayHeight the current display height
     * @param displayCutout the current display cutout
     * @param outInsets the insets to return
     */
    static void computeNonDecorInsets(Resources res, int displayRotation, int displayWidth,
            int displayHeight, DisplayCutout displayCutout, int uiMode, Rect outInsets,
            boolean hasNavigationBar) {
        outInsets.setEmpty();

        // Only navigation bar
        if (hasNavigationBar) {
            int position = navigationBarPosition(res, displayWidth, displayHeight, displayRotation);
            int navBarSize =
                    getNavigationBarSize(res, position, displayWidth > displayHeight, uiMode);
            if (position == NAV_BAR_BOTTOM) {
                outInsets.bottom = navBarSize;
            } else if (position == NAV_BAR_RIGHT) {
                outInsets.right = navBarSize;
            } else if (position == NAV_BAR_LEFT) {
                outInsets.left = navBarSize;
            }
        }

        if (displayCutout != null) {
            outInsets.left += displayCutout.getSafeInsetLeft();
            outInsets.top += displayCutout.getSafeInsetTop();
            outInsets.right += displayCutout.getSafeInsetRight();
            outInsets.bottom += displayCutout.getSafeInsetBottom();
        }
    }

    /**
     * Calculates the stable insets without running a layout.
     *
     * @param displayRotation the current display rotation
     * @param displayWidth the current display width
     * @param displayHeight the current display height
     * @param displayCutout the current display cutout
     * @param outInsets the insets to return
     */
    static void computeStableInsets(Resources res, int displayRotation, int displayWidth,
            int displayHeight, DisplayCutout displayCutout, int uiMode, Rect outInsets,
            boolean hasNavigationBar, boolean hasStatusBar) {
        outInsets.setEmpty();

        // Navigation bar and status bar.
        computeNonDecorInsets(res, displayRotation, displayWidth, displayHeight, displayCutout,
                uiMode, outInsets, hasNavigationBar);
        convertNonDecorInsetsToStableInsets(res, outInsets, displayWidth, displayHeight,
                hasStatusBar);
    }

    /** Retrieve the statusbar height from resources. */
    static int getStatusBarHeight(boolean landscape, Resources res) {
        return landscape ? res.getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height_landscape)
                : res.getDimensionPixelSize(
                        com.android.internal.R.dimen.status_bar_height_portrait);
    }

    /** Calculate the DisplayCutout for a particular display size/rotation. */
    public static DisplayCutout calculateDisplayCutoutForRotation(
            DisplayCutout cutout, int rotation, int displayWidth, int displayHeight) {
        if (cutout == null || cutout == DisplayCutout.NO_CUTOUT) {
            return null;
        }
        if (rotation == ROTATION_0) {
            return computeSafeInsets(cutout, displayWidth, displayHeight);
        }
        final Insets waterfallInsets = rotateInsets(cutout.getWaterfallInsets(), rotation);
        final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
        Rect[] cutoutRects = cutout.getBoundingRectsAll();
        final Rect[] newBounds = new Rect[cutoutRects.length];
        final Rect displayBounds = new Rect(0, 0, displayWidth, displayHeight);
        for (int i = 0; i < cutoutRects.length; ++i) {
            final Rect rect = new Rect(cutoutRects[i]);
            if (!rect.isEmpty()) {
                rotateBounds(rect, displayBounds, rotation);
            }
            newBounds[getBoundIndexFromRotation(i, rotation)] = rect;
        }
        final DisplayCutout.CutoutPathParserInfo info = cutout.getCutoutPathParserInfo();
        final DisplayCutout.CutoutPathParserInfo newInfo = new DisplayCutout.CutoutPathParserInfo(
                info.getDisplayWidth(), info.getDisplayHeight(), info.getDensity(),
                info.getCutoutSpec(), rotation, info.getScale());
        return computeSafeInsets(
                DisplayCutout.constructDisplayCutout(newBounds, waterfallInsets, newInfo),
                rotated ? displayHeight : displayWidth,
                rotated ? displayWidth : displayHeight);
    }

    private static int getBoundIndexFromRotation(int index, int rotation) {
        return (index - rotation) < 0
                ? index - rotation + DisplayCutout.BOUNDS_POSITION_LENGTH
                : index - rotation;
    }

    /** Calculate safe insets. */
    public static DisplayCutout computeSafeInsets(DisplayCutout inner,
            int displayWidth, int displayHeight) {
        if (inner == DisplayCutout.NO_CUTOUT) {
            return null;
        }

        final Size displaySize = new Size(displayWidth, displayHeight);
        final Rect safeInsets = computeSafeInsets(displaySize, inner);
        return inner.replaceSafeInsets(safeInsets);
    }

    private static Rect computeSafeInsets(
            Size displaySize, DisplayCutout cutout) {
        if (displaySize.getWidth() == displaySize.getHeight()) {
            throw new UnsupportedOperationException("not implemented: display=" + displaySize
                    + " cutout=" + cutout);
        }

        int leftInset = Math.max(cutout.getWaterfallInsets().left,
                findCutoutInsetForSide(displaySize, cutout.getBoundingRectLeft(), Gravity.LEFT));
        int topInset = Math.max(cutout.getWaterfallInsets().top,
                findCutoutInsetForSide(displaySize, cutout.getBoundingRectTop(), Gravity.TOP));
        int rightInset = Math.max(cutout.getWaterfallInsets().right,
                findCutoutInsetForSide(displaySize, cutout.getBoundingRectRight(), Gravity.RIGHT));
        int bottomInset = Math.max(cutout.getWaterfallInsets().bottom,
                findCutoutInsetForSide(displaySize, cutout.getBoundingRectBottom(),
                        Gravity.BOTTOM));

        return new Rect(leftInset, topInset, rightInset, bottomInset);
    }

    private static int findCutoutInsetForSide(Size display, Rect boundingRect, int gravity) {
        if (boundingRect.isEmpty()) {
            return 0;
        }

        int inset = 0;
        switch (gravity) {
            case Gravity.TOP:
                return Math.max(inset, boundingRect.bottom);
            case Gravity.BOTTOM:
                return Math.max(inset, display.getHeight() - boundingRect.top);
            case Gravity.LEFT:
                return Math.max(inset, boundingRect.right);
            case Gravity.RIGHT:
                return Math.max(inset, display.getWidth() - boundingRect.left);
            default:
                throw new IllegalArgumentException("unknown gravity: " + gravity);
        }
    }

    static boolean hasNavigationBar(DisplayInfo info, Context context, int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) {
            // Allow a system property to override this. Used by the emulator.
            final String navBarOverride = SystemProperties.get("qemu.hw.mainkeys");
            if ("1".equals(navBarOverride)) {
                return false;
            } else if ("0".equals(navBarOverride)) {
                return true;
            }
            return context.getResources().getBoolean(R.bool.config_showNavigationBar);
        } else {
            boolean isUntrustedVirtualDisplay = info.type == Display.TYPE_VIRTUAL
                    && info.ownerUid != SYSTEM_UID;
            final ContentResolver resolver = context.getContentResolver();
            boolean forceDesktopOnExternal = Settings.Global.getInt(resolver,
                    DEVELOPMENT_FORCE_DESKTOP_MODE_ON_EXTERNAL_DISPLAYS, 0) != 0;

            return ((info.flags & FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS) != 0
                    || (forceDesktopOnExternal && !isUntrustedVirtualDisplay));
            // TODO(b/142569966): make sure VR2D and DisplayWindowSettings are moved here somehow.
        }
    }

    static boolean hasStatusBar(int displayId) {
        return displayId == Display.DEFAULT_DISPLAY;
    }

    /** Retrieve navigation bar position from resources based on rotation and size. */
    public static @NavBarPosition int navigationBarPosition(Resources res, int displayWidth,
            int displayHeight, int rotation) {
        boolean navBarCanMove = displayWidth != displayHeight && res.getBoolean(
                com.android.internal.R.bool.config_navBarCanMove);
        if (navBarCanMove && displayWidth > displayHeight) {
            if (rotation == Surface.ROTATION_90) {
                return NAV_BAR_RIGHT;
            } else {
                return NAV_BAR_LEFT;
            }
        }
        return NAV_BAR_BOTTOM;
    }

    /** Retrieve navigation bar size from resources based on side/orientation/ui-mode */
    public static int getNavigationBarSize(Resources res, int navBarSide, boolean landscape,
            int uiMode) {
        final boolean carMode = (uiMode & UI_MODE_TYPE_MASK) == UI_MODE_TYPE_CAR;
        if (carMode) {
            if (navBarSide == NAV_BAR_BOTTOM) {
                return res.getDimensionPixelSize(landscape
                        ? R.dimen.navigation_bar_height_landscape_car_mode
                        : R.dimen.navigation_bar_height_car_mode);
            } else {
                return res.getDimensionPixelSize(R.dimen.navigation_bar_width_car_mode);
            }

        } else {
            if (navBarSide == NAV_BAR_BOTTOM) {
                return res.getDimensionPixelSize(landscape
                        ? R.dimen.navigation_bar_height_landscape
                        : R.dimen.navigation_bar_height);
            } else {
                return res.getDimensionPixelSize(R.dimen.navigation_bar_width);
            }
        }
    }

    /** @see com.android.server.wm.DisplayPolicy#getNavigationBarFrameHeight */
    public static int getNavigationBarFrameHeight(Resources res, boolean landscape) {
        return res.getDimensionPixelSize(landscape
                ? R.dimen.navigation_bar_frame_height_landscape
                : R.dimen.navigation_bar_frame_height);
    }
}
