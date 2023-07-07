/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.pip.phone;

import static com.android.wm.shell.pip.PipUtils.dpToPx;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.SystemProperties;
import android.util.Size;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.pip.PipDisplayLayoutState;

import java.io.PrintWriter;

/**
 * Acts as a source of truth for appropriate size spec for PIP.
 */
public class PipSizeSpecHandler {
    private static final String TAG = PipSizeSpecHandler.class.getSimpleName();

    @NonNull private final PipDisplayLayoutState mPipDisplayLayoutState;

    private final SizeSpecSource mSizeSpecSourceImpl;

    /** The preferred minimum (and default minimum) size specified by apps. */
    @Nullable private Size mOverrideMinSize;
    private int mOverridableMinSize;

    /** Used to store values obtained from resource files. */
    private Point mScreenEdgeInsets;
    private float mMinAspectRatioForMinSize;
    private float mMaxAspectRatioForMinSize;
    private int mDefaultMinSize;

    @NonNull private final Context mContext;

    private interface SizeSpecSource {
        /** Returns max size allowed for the PIP window */
        Size getMaxSize(float aspectRatio);

        /** Returns default size for the PIP window */
        Size getDefaultSize(float aspectRatio);

        /** Returns min size allowed for the PIP window */
        Size getMinSize(float aspectRatio);

        /** Returns the adjusted size based on current size and target aspect ratio */
        Size getSizeForAspectRatio(Size size, float aspectRatio);

        /** Updates internal resources on configuration changes */
        default void reloadResources() {}
    }

    /**
     * Determines PIP window size optimized for large screens and aspect ratios close to 1:1
     */
    private class SizeSpecLargeScreenOptimizedImpl implements SizeSpecSource {
        private static final float DEFAULT_OPTIMIZED_ASPECT_RATIO = 9f / 16;

        /** Default and minimum percentages for the PIP size logic. */
        private final float mDefaultSizePercent;
        private final float mMinimumSizePercent;

        /** Aspect ratio that the PIP size spec logic optimizes for. */
        private float mOptimizedAspectRatio;

        private SizeSpecLargeScreenOptimizedImpl() {
            mDefaultSizePercent = Float.parseFloat(SystemProperties
                    .get("com.android.wm.shell.pip.phone.def_percentage", "0.6"));
            mMinimumSizePercent = Float.parseFloat(SystemProperties
                    .get("com.android.wm.shell.pip.phone.min_percentage", "0.5"));
        }

        @Override
        public void reloadResources() {
            final Resources res = mContext.getResources();

            mOptimizedAspectRatio = res.getFloat(R.dimen.config_pipLargeScreenOptimizedAspectRatio);
            // make sure the optimized aspect ratio is valid with a default value to fall back to
            if (mOptimizedAspectRatio > 1) {
                mOptimizedAspectRatio = DEFAULT_OPTIMIZED_ASPECT_RATIO;
            }
        }

        /**
         * Calculates the max size of PIP.
         *
         * Optimizes for 16:9 aspect ratios, making them take full length of shortest display edge.
         * As aspect ratio approaches values close to 1:1, the logic does not let PIP occupy the
         * whole screen. A linear function is used to calculate these sizes.
         *
         * @param aspectRatio aspect ratio of the PIP window
         * @return dimensions of the max size of the PIP
         */
        @Override
        public Size getMaxSize(float aspectRatio) {
            final int totalHorizontalPadding = getInsetBounds().left
                    + (getDisplayBounds().width() - getInsetBounds().right);
            final int totalVerticalPadding = getInsetBounds().top
                    + (getDisplayBounds().height() - getInsetBounds().bottom);

            final int shorterLength = (int) (1f * Math.min(
                    getDisplayBounds().width() - totalHorizontalPadding,
                    getDisplayBounds().height() - totalVerticalPadding));

            int maxWidth, maxHeight;

            // use the optimized max sizing logic only within a certain aspect ratio range
            if (aspectRatio >= mOptimizedAspectRatio && aspectRatio <= 1 / mOptimizedAspectRatio) {
                // this formula and its derivation is explained in b/198643358#comment16
                maxWidth = (int) (mOptimizedAspectRatio * shorterLength
                        + shorterLength * (aspectRatio - mOptimizedAspectRatio) / (1
                        + aspectRatio));
                maxHeight = Math.round(maxWidth / aspectRatio);
            } else {
                if (aspectRatio > 1f) {
                    maxWidth = shorterLength;
                    maxHeight = Math.round(maxWidth / aspectRatio);
                } else {
                    maxHeight = shorterLength;
                    maxWidth = Math.round(maxHeight * aspectRatio);
                }
            }

            return new Size(maxWidth, maxHeight);
        }

        /**
         * Decreases the dimensions by a percentage relative to max size to get default size.
         *
         * @param aspectRatio aspect ratio of the PIP window
         * @return dimensions of the default size of the PIP
         */
        @Override
        public Size getDefaultSize(float aspectRatio) {
            Size minSize = this.getMinSize(aspectRatio);

            if (mOverrideMinSize != null) {
                return minSize;
            }

            Size maxSize = this.getMaxSize(aspectRatio);

            int defaultWidth = Math.max(Math.round(maxSize.getWidth() * mDefaultSizePercent),
                    minSize.getWidth());
            int defaultHeight = Math.round(defaultWidth / aspectRatio);

            return new Size(defaultWidth, defaultHeight);
        }

        /**
         * Decreases the dimensions by a certain percentage relative to max size to get min size.
         *
         * @param aspectRatio aspect ratio of the PIP window
         * @return dimensions of the min size of the PIP
         */
        @Override
        public Size getMinSize(float aspectRatio) {
            // if there is an overridden min size provided, return that
            if (mOverrideMinSize != null) {
                return adjustOverrideMinSizeToAspectRatio(aspectRatio);
            }

            Size maxSize = this.getMaxSize(aspectRatio);

            int minWidth = Math.round(maxSize.getWidth() * mMinimumSizePercent);
            int minHeight = Math.round(maxSize.getHeight() * mMinimumSizePercent);

            // make sure the calculated min size is not smaller than the allowed default min size
            if (aspectRatio > 1f) {
                minHeight = Math.max(minHeight, mDefaultMinSize);
                minWidth = Math.round(minHeight * aspectRatio);
            } else {
                minWidth = Math.max(minWidth, mDefaultMinSize);
                minHeight = Math.round(minWidth / aspectRatio);
            }
            return new Size(minWidth, minHeight);
        }

        /**
         * Returns the size for target aspect ratio making sure new size conforms with the rules.
         *
         * <p>Recalculates the dimensions such that the target aspect ratio is achieved, while
         * maintaining the same maximum size to current size ratio.
         *
         * @param size current size
         * @param aspectRatio target aspect ratio
         */
        @Override
        public Size getSizeForAspectRatio(Size size, float aspectRatio) {
            float currAspectRatio = (float) size.getWidth() / size.getHeight();

            // getting the percentage of the max size that current size takes
            Size currentMaxSize = getMaxSize(currAspectRatio);
            float currentPercent = (float) size.getWidth() / currentMaxSize.getWidth();

            // getting the max size for the target aspect ratio
            Size updatedMaxSize = getMaxSize(aspectRatio);

            int width = Math.round(updatedMaxSize.getWidth() * currentPercent);
            int height = Math.round(updatedMaxSize.getHeight() * currentPercent);

            // adjust the dimensions if below allowed min edge size
            if (width < getMinEdgeSize() && aspectRatio <= 1) {
                width = getMinEdgeSize();
                height = Math.round(width / aspectRatio);
            } else if (height < getMinEdgeSize() && aspectRatio > 1) {
                height = getMinEdgeSize();
                width = Math.round(height * aspectRatio);
            }

            // reduce the dimensions of the updated size to the calculated percentage
            return new Size(width, height);
        }
    }

    private class SizeSpecDefaultImpl implements SizeSpecSource {
        private float mDefaultSizePercent;
        private float mMinimumSizePercent;

        @Override
        public void reloadResources() {
            final Resources res = mContext.getResources();

            mMaxAspectRatioForMinSize = res.getFloat(
                    R.dimen.config_pictureInPictureAspectRatioLimitForMinSize);
            mMinAspectRatioForMinSize = 1f / mMaxAspectRatioForMinSize;

            mDefaultSizePercent = res.getFloat(R.dimen.config_pictureInPictureDefaultSizePercent);
            mMinimumSizePercent = res.getFraction(R.fraction.config_pipShortestEdgePercent, 1, 1);
        }

        @Override
        public Size getMaxSize(float aspectRatio) {
            final int shorterLength = Math.min(getDisplayBounds().width(),
                    getDisplayBounds().height());

            final int totalHorizontalPadding = getInsetBounds().left
                    + (getDisplayBounds().width() - getInsetBounds().right);
            final int totalVerticalPadding = getInsetBounds().top
                    + (getDisplayBounds().height() - getInsetBounds().bottom);

            final int maxWidth, maxHeight;

            if (aspectRatio > 1f) {
                maxWidth = (int) Math.max(getDefaultSize(aspectRatio).getWidth(),
                        shorterLength - totalHorizontalPadding);
                maxHeight = (int) (maxWidth / aspectRatio);
            } else {
                maxHeight = (int) Math.max(getDefaultSize(aspectRatio).getHeight(),
                        shorterLength - totalVerticalPadding);
                maxWidth = (int) (maxHeight * aspectRatio);
            }

            return new Size(maxWidth, maxHeight);
        }

        @Override
        public Size getDefaultSize(float aspectRatio) {
            if (mOverrideMinSize != null) {
                return this.getMinSize(aspectRatio);
            }

            final int smallestDisplaySize = Math.min(getDisplayBounds().width(),
                    getDisplayBounds().height());
            final int minSize = (int) Math.max(getMinEdgeSize(),
                    smallestDisplaySize * mDefaultSizePercent);

            final int width;
            final int height;

            if (aspectRatio <= mMinAspectRatioForMinSize
                    || aspectRatio > mMaxAspectRatioForMinSize) {
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
                // Within these points, ensure that the bounds fit within the radius of the limits
                // at the points
                final float widthAtMaxAspectRatioForMinSize = mMaxAspectRatioForMinSize * minSize;
                final float radius = PointF.length(widthAtMaxAspectRatioForMinSize, minSize);
                height = (int) Math.round(Math.sqrt((radius * radius)
                        / (aspectRatio * aspectRatio + 1)));
                width = Math.round(height * aspectRatio);
            }

            return new Size(width, height);
        }

        @Override
        public Size getMinSize(float aspectRatio) {
            if (mOverrideMinSize != null) {
                return adjustOverrideMinSizeToAspectRatio(aspectRatio);
            }

            final int shorterLength = Math.min(getDisplayBounds().width(),
                    getDisplayBounds().height());
            final int minWidth, minHeight;

            if (aspectRatio > 1f) {
                minWidth = (int) Math.min(getDefaultSize(aspectRatio).getWidth(),
                        shorterLength * mMinimumSizePercent);
                minHeight = (int) (minWidth / aspectRatio);
            } else {
                minHeight = (int) Math.min(getDefaultSize(aspectRatio).getHeight(),
                        shorterLength * mMinimumSizePercent);
                minWidth = (int) (minHeight * aspectRatio);
            }

            return new Size(minWidth, minHeight);
        }

        @Override
        public Size getSizeForAspectRatio(Size size, float aspectRatio) {
            final int smallestSize = Math.min(size.getWidth(), size.getHeight());
            final int minSize = Math.max(getMinEdgeSize(), smallestSize);

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
    }

    public PipSizeSpecHandler(Context context, PipDisplayLayoutState pipDisplayLayoutState) {
        mContext = context;
        mPipDisplayLayoutState = pipDisplayLayoutState;

        // choose between two implementations of size spec logic
        if (supportsPipSizeLargeScreen()) {
            mSizeSpecSourceImpl = new SizeSpecLargeScreenOptimizedImpl();
        } else {
            mSizeSpecSourceImpl = new SizeSpecDefaultImpl();
        }

        reloadResources();
    }

    /** Reloads the resources */
    public void onConfigurationChanged() {
        reloadResources();
    }

    private void reloadResources() {
        final Resources res = mContext.getResources();

        mDefaultMinSize = res.getDimensionPixelSize(
                R.dimen.default_minimal_size_pip_resizable_task);
        mOverridableMinSize = res.getDimensionPixelSize(
                R.dimen.overridable_minimal_size_pip_resizable_task);

        final String screenEdgeInsetsDpString = res.getString(
                R.string.config_defaultPictureInPictureScreenEdgeInsets);
        final Size screenEdgeInsetsDp = !screenEdgeInsetsDpString.isEmpty()
                ? Size.parseSize(screenEdgeInsetsDpString)
                : null;
        mScreenEdgeInsets = screenEdgeInsetsDp == null ? new Point()
                : new Point(dpToPx(screenEdgeInsetsDp.getWidth(), res.getDisplayMetrics()),
                        dpToPx(screenEdgeInsetsDp.getHeight(), res.getDisplayMetrics()));

        // update the internal resources of the size spec source's stub
        mSizeSpecSourceImpl.reloadResources();
    }

    @NonNull
    private Rect getDisplayBounds() {
        return mPipDisplayLayoutState.getDisplayBounds();
    }

    public Point getScreenEdgeInsets() {
        return mScreenEdgeInsets;
    }

    /**
     * Returns the inset bounds the PIP window can be visible in.
     */
    public Rect getInsetBounds() {
        Rect insetBounds = new Rect();
        DisplayLayout displayLayout = mPipDisplayLayoutState.getDisplayLayout();
        Rect insets = displayLayout.stableInsets();
        insetBounds.set(insets.left + mScreenEdgeInsets.x,
                insets.top + mScreenEdgeInsets.y,
                displayLayout.width() - insets.right - mScreenEdgeInsets.x,
                displayLayout.height() - insets.bottom - mScreenEdgeInsets.y);
        return insetBounds;
    }

    /** Sets the preferred size of PIP as specified by the activity in PIP mode. */
    public void setOverrideMinSize(@Nullable Size overrideMinSize) {
        mOverrideMinSize = overrideMinSize;
    }

    /** Returns the preferred minimal size specified by the activity in PIP. */
    @Nullable
    public Size getOverrideMinSize() {
        if (mOverrideMinSize != null
                && (mOverrideMinSize.getWidth() < mOverridableMinSize
                || mOverrideMinSize.getHeight() < mOverridableMinSize)) {
            return new Size(mOverridableMinSize, mOverridableMinSize);
        }

        return mOverrideMinSize;
    }

    /** Returns the minimum edge size of the override minimum size, or 0 if not set. */
    public int getOverrideMinEdgeSize() {
        if (mOverrideMinSize == null) return 0;
        return Math.min(getOverrideMinSize().getWidth(), getOverrideMinSize().getHeight());
    }

    public int getMinEdgeSize() {
        return mOverrideMinSize == null ? mDefaultMinSize : getOverrideMinEdgeSize();
    }

    /**
     * Returns the size for the max size spec.
     */
    public Size getMaxSize(float aspectRatio) {
        return mSizeSpecSourceImpl.getMaxSize(aspectRatio);
    }

    /**
     * Returns the size for the default size spec.
     */
    public Size getDefaultSize(float aspectRatio) {
        return mSizeSpecSourceImpl.getDefaultSize(aspectRatio);
    }

    /**
     * Returns the size for the min size spec.
     */
    public Size getMinSize(float aspectRatio) {
        return mSizeSpecSourceImpl.getMinSize(aspectRatio);
    }

    /**
     * Returns the adjusted size so that it conforms to the given aspectRatio.
     *
     * @param size current size
     * @param aspectRatio target aspect ratio
     */
    public Size getSizeForAspectRatio(@NonNull Size size, float aspectRatio) {
        if (size.equals(mOverrideMinSize)) {
            return adjustOverrideMinSizeToAspectRatio(aspectRatio);
        }

        return mSizeSpecSourceImpl.getSizeForAspectRatio(size, aspectRatio);
    }

    /**
     * Returns the adjusted overridden min size if it is set; otherwise, returns null.
     *
     * <p>Overridden min size needs to be adjusted in its own way while making sure that the target
     * aspect ratio is maintained
     *
     * @param aspectRatio target aspect ratio
     */
    @Nullable
    @VisibleForTesting
    Size adjustOverrideMinSizeToAspectRatio(float aspectRatio) {
        if (mOverrideMinSize == null) {
            return null;
        }
        final Size size = getOverrideMinSize();
        final float sizeAspectRatio = size.getWidth() / (float) size.getHeight();
        if (sizeAspectRatio > aspectRatio) {
            // Size is wider, fix the width and increase the height
            return new Size(size.getWidth(), (int) (size.getWidth() / aspectRatio));
        } else {
            // Size is taller, fix the height and adjust the width.
            return new Size((int) (size.getHeight() * aspectRatio), size.getHeight());
        }
    }

    @VisibleForTesting
    boolean supportsPipSizeLargeScreen() {
        // TODO(b/271468706): switch Tv to having a dedicated SizeSpecSource once the SizeSpecSource
        // can be injected
        return SystemProperties
                .getBoolean("persist.wm.debug.enable_pip_size_large_screen", true) && !isTv();
    }

    private boolean isTv() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    /** Dumps internal state. */
    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mSizeSpecSourceImpl=" + mSizeSpecSourceImpl);
        pw.println(innerPrefix + "mOverrideMinSize=" + mOverrideMinSize);
        pw.println(innerPrefix + "mScreenEdgeInsets=" + mScreenEdgeInsets);
    }
}
