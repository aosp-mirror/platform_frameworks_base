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

package com.android.wm.shell.pip;

import static android.util.TypedValue.COMPLEX_UNIT_DIP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;

import com.android.wm.shell.common.DisplayLayout;

import java.io.PrintWriter;

/**
 * Calculates the default, normal, entry, inset and movement bounds of the PIP.
 */
public class PipBoundsAlgorithm {

    private static final String TAG = PipBoundsAlgorithm.class.getSimpleName();
    private static final float INVALID_SNAP_FRACTION = -1f;

    private final @NonNull PipBoundsState mPipBoundsState;
    private final PipSnapAlgorithm mSnapAlgorithm;

    private float mDefaultSizePercent;
    private float mMinAspectRatioForMinSize;
    private float mMaxAspectRatioForMinSize;
    private float mDefaultAspectRatio;
    private float mMinAspectRatio;
    private float mMaxAspectRatio;
    private int mDefaultStackGravity;
    private int mDefaultMinSize;
    private int mOverridableMinSize;
    private Point mScreenEdgeInsets;

    public PipBoundsAlgorithm(Context context, @NonNull PipBoundsState pipBoundsState,
            @NonNull PipSnapAlgorithm pipSnapAlgorithm) {
        mPipBoundsState = pipBoundsState;
        mSnapAlgorithm = pipSnapAlgorithm;
        reloadResources(context);
        // Initialize the aspect ratio to the default aspect ratio.  Don't do this in reload
        // resources as it would clobber mAspectRatio when entering PiP from fullscreen which
        // triggers a configuration change and the resources to be reloaded.
        mPipBoundsState.setAspectRatio(mDefaultAspectRatio);
        mPipBoundsState.setMinEdgeSize(mDefaultMinSize);
    }

    /**
     * TODO: move the resources to SysUI package.
     */
    private void reloadResources(Context context) {
        final Resources res = context.getResources();
        mDefaultAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureDefaultAspectRatio);
        mDefaultStackGravity = res.getInteger(
                com.android.internal.R.integer.config_defaultPictureInPictureGravity);
        mDefaultMinSize = res.getDimensionPixelSize(
                com.android.internal.R.dimen.default_minimal_size_pip_resizable_task);
        mOverridableMinSize = res.getDimensionPixelSize(
                com.android.internal.R.dimen.overridable_minimal_size_pip_resizable_task);
        final String screenEdgeInsetsDpString = res.getString(
                com.android.internal.R.string.config_defaultPictureInPictureScreenEdgeInsets);
        final Size screenEdgeInsetsDp = !screenEdgeInsetsDpString.isEmpty()
                ? Size.parseSize(screenEdgeInsetsDpString)
                : null;
        mScreenEdgeInsets = screenEdgeInsetsDp == null ? new Point()
                : new Point(dpToPx(screenEdgeInsetsDp.getWidth(), res.getDisplayMetrics()),
                        dpToPx(screenEdgeInsetsDp.getHeight(), res.getDisplayMetrics()));
        mMinAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMinAspectRatio);
        mMaxAspectRatio = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureMaxAspectRatio);
        mDefaultSizePercent = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureDefaultSizePercent);
        mMaxAspectRatioForMinSize = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureAspectRatioLimitForMinSize);
        mMinAspectRatioForMinSize = 1f / mMaxAspectRatioForMinSize;
    }

    /**
     * The {@link PipSnapAlgorithm} is couple on display bounds
     * @return {@link PipSnapAlgorithm}.
     */
    public PipSnapAlgorithm getSnapAlgorithm() {
        return mSnapAlgorithm;
    }

    /** Responds to configuration change. */
    public void onConfigurationChanged(Context context) {
        reloadResources(context);
    }

    /** Returns the normal bounds (i.e. the default entry bounds). */
    public Rect getNormalBounds() {
        // The normal bounds are the default bounds adjusted to the current aspect ratio.
        return transformBoundsToAspectRatioIfValid(getDefaultBounds(),
                mPipBoundsState.getAspectRatio(), false /* useCurrentMinEdgeSize */,
                false /* useCurrentSize */);
    }

    /** Returns the default bounds. */
    public Rect getDefaultBounds() {
        return getDefaultBounds(INVALID_SNAP_FRACTION, null /* size */);
    }

    /** Returns the destination bounds to place the PIP window on entry. */
    public Rect getEntryDestinationBounds() {
        final PipBoundsState.PipReentryState reentryState = mPipBoundsState.getReentryState();

        final Rect destinationBounds = reentryState != null
                ? getDefaultBounds(reentryState.getSnapFraction(), reentryState.getSize())
                : getDefaultBounds();

        final boolean useCurrentSize = reentryState != null && reentryState.getSize() != null;
        return transformBoundsToAspectRatioIfValid(destinationBounds,
                mPipBoundsState.getAspectRatio(), false /* useCurrentMinEdgeSize */,
                useCurrentSize);
    }

    /** Returns the current bounds adjusted to the new aspect ratio, if valid. */
    public Rect getAdjustedDestinationBounds(Rect currentBounds, float newAspectRatio) {
        return transformBoundsToAspectRatioIfValid(currentBounds, newAspectRatio,
                true /* useCurrentMinEdgeSize */, false /* useCurrentSize */);
    }

    /**
     *
     * Get the smallest/most minimal size allowed.
     */
    public Size getMinimalSize(ActivityInfo activityInfo) {
        if (activityInfo == null || activityInfo.windowLayout == null) {
            return null;
        }
        final ActivityInfo.WindowLayout windowLayout = activityInfo.windowLayout;
        // -1 will be populated if an activity specifies defaultWidth/defaultHeight in <layout>
        // without minWidth/minHeight
        if (windowLayout.minWidth > 0 && windowLayout.minHeight > 0) {
            // If either dimension is smaller than the allowed minimum, adjust them
            // according to mOverridableMinSize
            return new Size(Math.max(windowLayout.minWidth, mOverridableMinSize),
                    Math.max(windowLayout.minHeight, mOverridableMinSize));
        }
        return null;
    }

    /**
     * Returns the source hint rect if it is valid (if provided and is contained by the current
     * task bounds).
     */
    public static Rect getValidSourceHintRect(PictureInPictureParams params, Rect sourceBounds) {
        final Rect sourceHintRect = params != null && params.hasSourceBoundsHint()
                ? params.getSourceRectHint()
                : null;
        if (sourceHintRect != null && sourceBounds.contains(sourceHintRect)) {
            return sourceHintRect;
        }
        return null;
    }

    public float getDefaultAspectRatio() {
        return mDefaultAspectRatio;
    }

    /**
     *
     * Give the aspect ratio if the supplied PiP params have one, or else return default.
     */
    public float getAspectRatioOrDefault(
            @android.annotation.Nullable PictureInPictureParams params) {
        return params != null && params.hasSetAspectRatio()
                ? params.getAspectRatio()
                : getDefaultAspectRatio();
    }

    /**
     * @return whether the given {@param aspectRatio} is valid.
     */
    private boolean isValidPictureInPictureAspectRatio(float aspectRatio) {
        return Float.compare(mMinAspectRatio, aspectRatio) <= 0
                && Float.compare(aspectRatio, mMaxAspectRatio) <= 0;
    }

    private Rect transformBoundsToAspectRatioIfValid(Rect bounds, float aspectRatio,
            boolean useCurrentMinEdgeSize, boolean useCurrentSize) {
        final Rect destinationBounds = new Rect(bounds);
        if (isValidPictureInPictureAspectRatio(aspectRatio)) {
            transformBoundsToAspectRatio(destinationBounds, aspectRatio,
                    useCurrentMinEdgeSize, useCurrentSize);
        }
        return destinationBounds;
    }

    /**
     * Set the current bounds (or the default bounds if there are no current bounds) with the
     * specified aspect ratio.
     */
    public void transformBoundsToAspectRatio(Rect stackBounds, float aspectRatio,
            boolean useCurrentMinEdgeSize, boolean useCurrentSize) {
        // Save the snap fraction and adjust the size based on the new aspect ratio.
        final float snapFraction = mSnapAlgorithm.getSnapFraction(stackBounds,
                getMovementBounds(stackBounds), mPipBoundsState.getStashedState());

        final Size overrideMinSize = mPipBoundsState.getOverrideMinSize();
        final Size size;
        if (useCurrentMinEdgeSize || useCurrentSize) {
            // The default minimum edge size, or the override min edge size if set.
            final int defaultMinEdgeSize = overrideMinSize == null ? mDefaultMinSize
                    : mPipBoundsState.getOverrideMinEdgeSize();
            final int minEdgeSize = useCurrentMinEdgeSize ? mPipBoundsState.getMinEdgeSize()
                    : defaultMinEdgeSize;
            // Use the existing size but adjusted to the aspect ratio and min edge size.
            size = getSizeForAspectRatio(
                    new Size(stackBounds.width(), stackBounds.height()), aspectRatio, minEdgeSize);
        } else {
            if (overrideMinSize != null) {
                // The override minimal size is set, use that as the default size making sure it's
                // adjusted to the aspect ratio.
                size = adjustSizeToAspectRatio(overrideMinSize, aspectRatio);
            } else {
                // Calculate the default size using the display size and default min edge size.
                final DisplayLayout displayLayout = mPipBoundsState.getDisplayLayout();
                size = getSizeForAspectRatio(aspectRatio, mDefaultMinSize,
                        displayLayout.width(), displayLayout.height());
            }
        }

        final int left = (int) (stackBounds.centerX() - size.getWidth() / 2f);
        final int top = (int) (stackBounds.centerY() - size.getHeight() / 2f);
        stackBounds.set(left, top, left + size.getWidth(), top + size.getHeight());
        mSnapAlgorithm.applySnapFraction(stackBounds, getMovementBounds(stackBounds), snapFraction);
    }

    /** Adjusts the given size to conform to the given aspect ratio. */
    private Size adjustSizeToAspectRatio(@NonNull Size size, float aspectRatio) {
        final float sizeAspectRatio = size.getWidth() / (float) size.getHeight();
        if (sizeAspectRatio > aspectRatio) {
            // Size is wider, fix the width and increase the height
            return new Size(size.getWidth(), (int) (size.getWidth() / aspectRatio));
        } else {
            // Size is taller, fix the height and adjust the width.
            return new Size((int) (size.getHeight() * aspectRatio), size.getHeight());
        }
    }

    /**
     * @return the default bounds to show the PIP, if a {@param snapFraction} and {@param size} are
     * provided, then it will apply the default bounds to the provided snap fraction and size.
     */
    private Rect getDefaultBounds(float snapFraction, Size size) {
        final Rect defaultBounds = new Rect();
        if (snapFraction != INVALID_SNAP_FRACTION && size != null) {
            // The default bounds are the given size positioned at the given snap fraction.
            defaultBounds.set(0, 0, size.getWidth(), size.getHeight());
            final Rect movementBounds = getMovementBounds(defaultBounds);
            mSnapAlgorithm.applySnapFraction(defaultBounds, movementBounds, snapFraction);
            return defaultBounds;
        }

        // Calculate the default size.
        final Size defaultSize;
        final Rect insetBounds = new Rect();
        getInsetBounds(insetBounds);
        final DisplayLayout displayLayout = mPipBoundsState.getDisplayLayout();
        final Size overrideMinSize = mPipBoundsState.getOverrideMinSize();
        if (overrideMinSize != null) {
            // The override minimal size is set, use that as the default size making sure it's
            // adjusted to the aspect ratio.
            defaultSize = adjustSizeToAspectRatio(overrideMinSize, mDefaultAspectRatio);
        } else {
            // Calculate the default size using the display size and default min edge size.
            defaultSize = getSizeForAspectRatio(mDefaultAspectRatio,
                    mDefaultMinSize, displayLayout.width(), displayLayout.height());
        }

        // Now that we have the default size, apply the snap fraction if valid or position the
        // bounds using the default gravity.
        if (snapFraction != INVALID_SNAP_FRACTION) {
            defaultBounds.set(0, 0, defaultSize.getWidth(), defaultSize.getHeight());
            final Rect movementBounds = getMovementBounds(defaultBounds);
            mSnapAlgorithm.applySnapFraction(defaultBounds, movementBounds, snapFraction);
        } else {
            Gravity.apply(mDefaultStackGravity, defaultSize.getWidth(), defaultSize.getHeight(),
                    insetBounds, 0, Math.max(
                            mPipBoundsState.isImeShowing() ? mPipBoundsState.getImeHeight() : 0,
                            mPipBoundsState.isShelfShowing()
                                    ? mPipBoundsState.getShelfHeight() : 0), defaultBounds);
        }
        return defaultBounds;
    }

    /**
     * Populates the bounds on the screen that the PIP can be visible in.
     */
    public void getInsetBounds(Rect outRect) {
        final DisplayLayout displayLayout = mPipBoundsState.getDisplayLayout();
        Rect insets = mPipBoundsState.getDisplayLayout().stableInsets();
        outRect.set(insets.left + mScreenEdgeInsets.x,
                insets.top + mScreenEdgeInsets.y,
                displayLayout.width() - insets.right - mScreenEdgeInsets.x,
                displayLayout.height() - insets.bottom - mScreenEdgeInsets.y);
    }

    /**
     * @return the movement bounds for the given stackBounds and the current state of the
     *         controller.
     */
    public Rect getMovementBounds(Rect stackBounds) {
        return getMovementBounds(stackBounds, true /* adjustForIme */);
    }

    /**
     * @return the movement bounds for the given stackBounds and the current state of the
     *         controller.
     */
    public Rect getMovementBounds(Rect stackBounds, boolean adjustForIme) {
        final Rect movementBounds = new Rect();
        getInsetBounds(movementBounds);

        // Apply the movement bounds adjustments based on the current state.
        getMovementBounds(stackBounds, movementBounds, movementBounds,
                (adjustForIme && mPipBoundsState.isImeShowing())
                        ? mPipBoundsState.getImeHeight() : 0);

        return movementBounds;
    }

    /**
     * Adjusts movementBoundsOut so that it is the movement bounds for the given stackBounds.
     */
    public void getMovementBounds(Rect stackBounds, Rect insetBounds, Rect movementBoundsOut,
            int bottomOffset) {
        // Adjust the right/bottom to ensure the stack bounds never goes offscreen
        movementBoundsOut.set(insetBounds);
        movementBoundsOut.right = Math.max(insetBounds.left, insetBounds.right
                - stackBounds.width());
        movementBoundsOut.bottom = Math.max(insetBounds.top, insetBounds.bottom
                - stackBounds.height());
        movementBoundsOut.bottom -= bottomOffset;
    }

    /**
     * @return the default snap fraction to apply instead of the default gravity when calculating
     *         the default stack bounds when first entering PiP.
     */
    public float getSnapFraction(Rect stackBounds) {
        return getSnapFraction(stackBounds, getMovementBounds(stackBounds));
    }

    /**
     * @return the default snap fraction to apply instead of the default gravity when calculating
     *         the default stack bounds when first entering PiP.
     */
    public float getSnapFraction(Rect stackBounds, Rect movementBounds) {
        return mSnapAlgorithm.getSnapFraction(stackBounds, movementBounds);
    }

    /**
     * Applies the given snap fraction to the given stack bounds.
     */
    public void applySnapFraction(Rect stackBounds, float snapFraction) {
        final Rect movementBounds = getMovementBounds(stackBounds);
        mSnapAlgorithm.applySnapFraction(stackBounds, movementBounds, snapFraction);
    }

    public int getDefaultMinSize() {
        return mDefaultMinSize;
    }

    /**
     * @return the pixels for a given dp value.
     */
    private int dpToPx(float dpValue, DisplayMetrics dm) {
        return (int) TypedValue.applyDimension(COMPLEX_UNIT_DIP, dpValue, dm);
    }

    /**
     * @return the size of the PiP at the given aspectRatio, ensuring that the minimum edge
     * is at least minEdgeSize.
     */
    public Size getSizeForAspectRatio(float aspectRatio, float minEdgeSize, int displayWidth,
            int displayHeight) {
        final int smallestDisplaySize = Math.min(displayWidth, displayHeight);
        final int minSize = (int) Math.max(minEdgeSize, smallestDisplaySize * mDefaultSizePercent);

        final int width;
        final int height;
        if (aspectRatio <= mMinAspectRatioForMinSize || aspectRatio > mMaxAspectRatioForMinSize) {
            // Beyond these points, we can just use the min size as the shorter edge
            if (aspectRatio <= 1) {
                // Portrait, width is the minimum size
                width = minSize;
                height = Math.round(width / aspectRatio);
            } else {
                // Landscape, height is the minimum size
                height = minSize;
                width = Math.round(height * aspectRatio);
            }
        } else {
            // Within these points, we ensure that the bounds fit within the radius of the limits
            // at the points
            final float widthAtMaxAspectRatioForMinSize = mMaxAspectRatioForMinSize * minSize;
            final float radius = PointF.length(widthAtMaxAspectRatioForMinSize, minSize);
            height = (int) Math.round(Math.sqrt((radius * radius)
                    / (aspectRatio * aspectRatio + 1)));
            width = Math.round(height * aspectRatio);
        }
        return new Size(width, height);
    }

    /**
     * @return the adjusted size so that it conforms to the given aspectRatio, ensuring that the
     * minimum edge is at least minEdgeSize.
     */
    public Size getSizeForAspectRatio(Size size, float aspectRatio, float minEdgeSize) {
        final int smallestSize = Math.min(size.getWidth(), size.getHeight());
        final int minSize = (int) Math.max(minEdgeSize, smallestSize);

        final int width;
        final int height;
        if (aspectRatio <= 1) {
            // Portrait, width is the minimum size.
            width = minSize;
            height = Math.round(width / aspectRatio);
        } else {
            // Landscape, height is the minimum size
            height = minSize;
            width = Math.round(height * aspectRatio);
        }
        return new Size(width, height);
    }

    /**
     * @return the normal bounds adjusted so that they fit the menu actions.
     */
    public Rect adjustNormalBoundsToFitMenu(@NonNull Rect normalBounds,
            @Nullable Size minMenuSize) {
        if (minMenuSize == null) {
            return normalBounds;
        }
        if (normalBounds.width() >= minMenuSize.getWidth()
                && normalBounds.height() >= minMenuSize.getHeight()) {
            // The normal bounds can fit the menu as is, no need to adjust the bounds.
            return normalBounds;
        }
        final Rect adjustedNormalBounds = new Rect();
        final boolean needsWidthAdj = minMenuSize.getWidth() > normalBounds.width();
        final boolean needsHeightAdj = minMenuSize.getHeight() > normalBounds.height();
        final int adjWidth;
        final int adjHeight;
        if (needsWidthAdj && needsHeightAdj) {
            // Both the width and the height are too small - find the edge that needs the larger
            // adjustment and scale that edge. The other edge will scale beyond the minMenuSize
            // when the aspect ratio is applied.
            final float widthScaleFactor =
                    ((float) (minMenuSize.getWidth())) / ((float) (normalBounds.width()));
            final float heightScaleFactor =
                    ((float) (minMenuSize.getHeight())) / ((float) (normalBounds.height()));
            if (widthScaleFactor > heightScaleFactor) {
                adjWidth = minMenuSize.getWidth();
                adjHeight = Math.round(adjWidth / mPipBoundsState.getAspectRatio());
            } else {
                adjHeight = minMenuSize.getHeight();
                adjWidth = Math.round(adjHeight * mPipBoundsState.getAspectRatio());
            }
        } else if (needsWidthAdj) {
            // Width is too small - use the min menu size width instead.
            adjWidth = minMenuSize.getWidth();
            adjHeight = Math.round(adjWidth / mPipBoundsState.getAspectRatio());
        } else {
            // Height is too small - use the min menu size height instead.
            adjHeight = minMenuSize.getHeight();
            adjWidth = Math.round(adjHeight * mPipBoundsState.getAspectRatio());
        }
        adjustedNormalBounds.set(0, 0, adjWidth, adjHeight);
        // Make sure the bounds conform to the aspect ratio and min edge size.
        transformBoundsToAspectRatio(adjustedNormalBounds,
                mPipBoundsState.getAspectRatio(), true /* useCurrentMinEdgeSize */,
                true /* useCurrentSize */);
        return adjustedNormalBounds;
    }

    /**
     * Dumps internal states.
     */
    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mDefaultAspectRatio=" + mDefaultAspectRatio);
        pw.println(innerPrefix + "mMinAspectRatio=" + mMinAspectRatio);
        pw.println(innerPrefix + "mMaxAspectRatio=" + mMaxAspectRatio);
        pw.println(innerPrefix + "mDefaultStackGravity=" + mDefaultStackGravity);
        pw.println(innerPrefix + "mSnapAlgorithm" + mSnapAlgorithm);
    }
}
