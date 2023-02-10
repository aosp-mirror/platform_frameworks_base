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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.IndentingPrintWriter;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.RoundableState;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer;
import com.android.systemui.util.DumpUtilsKt;

import java.io.PrintWriter;

/**
 * Like {@link ExpandableView}, but setting an outline for the height and clipping.
 */
public abstract class ExpandableOutlineView extends ExpandableView {

    private RoundableState mRoundableState;
    private static final Path EMPTY_PATH = new Path();
    private final Rect mOutlineRect = new Rect();
    private boolean mCustomOutline;
    private float mOutlineAlpha = -1f;
    private boolean mAlwaysRoundBothCorners;
    private Path mTmpPath = new Path();

    /**
     * {@code false} if the children views of the {@link ExpandableOutlineView} are translated when
     * it is moved. Otherwise, the translation is set on the {@code ExpandableOutlineView} itself.
     */
    protected boolean mDismissUsingRowTranslationX = true;
    private float[] mTmpCornerRadii = new float[8];

    private final ViewOutlineProvider mProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            if (!mCustomOutline && !hasRoundedCorner() && !mAlwaysRoundBothCorners) {
                // Only when translating just the contents, does the outline need to be shifted.
                int translation = !mDismissUsingRowTranslationX ? (int) getTranslation() : 0;
                int left = Math.max(translation, 0);
                int top = mClipTopAmount;
                int right = getWidth() + Math.min(translation, 0);
                int bottom = Math.max(getActualHeight() - mClipBottomAmount, top);
                outline.setRect(left, top, right, bottom);
            } else {
                Path clipPath = getClipPath(false /* ignoreTranslation */);
                if (clipPath != null) {
                    outline.setPath(clipPath);
                }
            }
            outline.setAlpha(mOutlineAlpha);
        }
    };

    @Override
    public RoundableState getRoundableState() {
        return mRoundableState;
    }

    protected Path getClipPath(boolean ignoreTranslation) {
        int left;
        int top;
        int right;
        int bottom;
        int height;
        float topRadius = mAlwaysRoundBothCorners ? getMaxRadius() : getTopCornerRadius();
        if (!mCustomOutline) {
            // The outline just needs to be shifted if we're translating the contents. Otherwise
            // it's already in the right place.
            int translation = !mDismissUsingRowTranslationX && !ignoreTranslation
                    ? (int) getTranslation() : 0;
            int halfExtraWidth = (int) (mExtraWidthForClipping / 2.0f);
            left = Math.max(translation, 0) - halfExtraWidth;
            top = mClipTopAmount;
            right = getWidth() + halfExtraWidth + Math.min(translation, 0);
            // If the top is rounded we want the bottom to be at most at the top roundness, in order
            // to avoid the shadow changing when scrolling up.
            bottom = Math.max(mMinimumHeightForClipping,
                    Math.max(getActualHeight() - mClipBottomAmount, (int) (top + topRadius)));
        } else {
            left = mOutlineRect.left;
            top = mOutlineRect.top;
            right = mOutlineRect.right;
            bottom = mOutlineRect.bottom;
        }
        height = bottom - top;
        if (height == 0) {
            return EMPTY_PATH;
        }
        float bottomRadius = mAlwaysRoundBothCorners ? getMaxRadius() : getBottomCornerRadius();
        if (topRadius + bottomRadius > height) {
            float overShoot = topRadius + bottomRadius - height;
            float currentTopRoundness = getTopRoundness();
            float currentBottomRoundness = getBottomRoundness();
            topRadius -= overShoot * currentTopRoundness
                    / (currentTopRoundness + currentBottomRoundness);
            bottomRadius -= overShoot * currentBottomRoundness
                    / (currentTopRoundness + currentBottomRoundness);
        }
        getRoundedRectPath(left, top, right, bottom, topRadius, bottomRadius, mTmpPath);
        return mTmpPath;
    }

    /**
     * Add a round rect in {@code outPath}
     * @param outPath destination path
     */
    public void getRoundedRectPath(
            int left,
            int top,
            int right,
            int bottom,
            float topRoundness,
            float bottomRoundness,
            Path outPath) {
        outPath.reset();
        mTmpCornerRadii[0] = topRoundness;
        mTmpCornerRadii[1] = topRoundness;
        mTmpCornerRadii[2] = topRoundness;
        mTmpCornerRadii[3] = topRoundness;
        mTmpCornerRadii[4] = bottomRoundness;
        mTmpCornerRadii[5] = bottomRoundness;
        mTmpCornerRadii[6] = bottomRoundness;
        mTmpCornerRadii[7] = bottomRoundness;
        outPath.addRoundRect(left, top, right, bottom, mTmpCornerRadii, Path.Direction.CW);
    }

    public ExpandableOutlineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOutlineProvider(mProvider);
        initDimens();
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        canvas.save();
        Path clipPath = null;
        Path childClipPath = null;
        if (childNeedsClipping(child)) {
            clipPath = getCustomClipPath(child);
            if (clipPath == null) {
                clipPath = getClipPath(false /* ignoreTranslation */);
            }
            // If the notification uses "RowTranslationX" as dismiss behavior, we should clip the
            // children instead.
            if (mDismissUsingRowTranslationX && child instanceof NotificationChildrenContainer) {
                childClipPath = clipPath;
                clipPath = null;
            }
        }

        if (child instanceof NotificationChildrenContainer) {
            ((NotificationChildrenContainer) child).setChildClipPath(childClipPath);
        }
        if (clipPath != null) {
            canvas.clipPath(clipPath);
        }

        boolean result = super.drawChild(canvas, child, drawingTime);
        canvas.restore();
        return result;
    }

    @Override
    public void setExtraWidthForClipping(float extraWidthForClipping) {
        super.setExtraWidthForClipping(extraWidthForClipping);
        invalidate();
    }

    @Override
    public void setMinimumHeightForClipping(int minimumHeightForClipping) {
        super.setMinimumHeightForClipping(minimumHeightForClipping);
        invalidate();
    }

    protected boolean childNeedsClipping(View child) {
        return false;
    }

    protected boolean isClippingNeeded() {
        // When translating the contents instead of the overall view, we need to make sure we clip
        // rounded to the contents.
        boolean forTranslation = getTranslation() != 0 && !mDismissUsingRowTranslationX;
        return mAlwaysRoundBothCorners || mCustomOutline || forTranslation;
    }

    private void initDimens() {
        Resources res = getResources();
        mAlwaysRoundBothCorners = res.getBoolean(R.bool.config_clipNotificationsToOutline);
        float maxRadius;
        if (mAlwaysRoundBothCorners) {
            maxRadius = res.getDimension(R.dimen.notification_shadow_radius);
        } else {
            maxRadius = res.getDimensionPixelSize(R.dimen.notification_corner_radius);
        }
        if (mRoundableState == null) {
            mRoundableState = new RoundableState(this, this, maxRadius);
        } else {
            mRoundableState.setMaxRadius(maxRadius);
        }
        setClipToOutline(mAlwaysRoundBothCorners);
    }

    @Override
    public void applyRoundnessAndInvalidate() {
        invalidateOutline();
        super.applyRoundnessAndInvalidate();
    }

    public void onDensityOrFontScaleChanged() {
        initDimens();
        applyRoundnessAndInvalidate();
    }

    @Override
    public void setActualHeight(int actualHeight, boolean notifyListeners) {
        int previousHeight = getActualHeight();
        super.setActualHeight(actualHeight, notifyListeners);
        if (previousHeight != actualHeight) {
            applyRoundnessAndInvalidate();
        }
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        int previousAmount = getClipTopAmount();
        super.setClipTopAmount(clipTopAmount);
        if (previousAmount != clipTopAmount) {
            applyRoundnessAndInvalidate();
        }
    }

    @Override
    public void setClipBottomAmount(int clipBottomAmount) {
        int previousAmount = getClipBottomAmount();
        super.setClipBottomAmount(clipBottomAmount);
        if (previousAmount != clipBottomAmount) {
            applyRoundnessAndInvalidate();
        }
    }

    protected void setOutlineAlpha(float alpha) {
        if (alpha != mOutlineAlpha) {
            mOutlineAlpha = alpha;
            applyRoundnessAndInvalidate();
        }
    }

    @Override
    public float getOutlineAlpha() {
        return mOutlineAlpha;
    }

    protected void setOutlineRect(RectF rect) {
        if (rect != null) {
            setOutlineRect(rect.left, rect.top, rect.right, rect.bottom);
        } else {
            mCustomOutline = false;
            applyRoundnessAndInvalidate();
        }
    }

    /**
     * Set the dismiss behavior of the view.
     *
     * @param usingRowTranslationX {@code true} if the view should translate using regular
     *                             translationX, otherwise the contents will be
     *                             translated.
     */
    public void setDismissUsingRowTranslationX(boolean usingRowTranslationX) {
        mDismissUsingRowTranslationX = usingRowTranslationX;
    }

    @Override
    public int getOutlineTranslation() {
        if (mCustomOutline) {
            return mOutlineRect.left;
        }
        if (mDismissUsingRowTranslationX) {
            return 0;
        }
        return (int) getTranslation();
    }

    public void updateOutline() {
        if (mCustomOutline) {
            return;
        }
        boolean hasOutline = needsOutline();
        setOutlineProvider(hasOutline ? mProvider : null);
    }

    /**
     * @return Whether the view currently needs an outline. This is usually {@code false} in case
     * it doesn't have a background.
     */
    protected boolean needsOutline() {
        if (isChildInGroup()) {
            return isGroupExpanded() && !isGroupExpansionChanging();
        } else if (isSummaryWithChildren()) {
            return !isGroupExpanded() || isGroupExpansionChanging();
        }
        return true;
    }

    public boolean isOutlineShowing() {
        ViewOutlineProvider op = getOutlineProvider();
        return op != null;
    }

    protected void setOutlineRect(float left, float top, float right, float bottom) {
        mCustomOutline = true;

        mOutlineRect.set((int) left, (int) top, (int) right, (int) bottom);

        // Outlines need to be at least 1 dp
        mOutlineRect.bottom = (int) Math.max(top, mOutlineRect.bottom);
        mOutlineRect.right = (int) Math.max(left, mOutlineRect.right);
        applyRoundnessAndInvalidate();
    }

    public Path getCustomClipPath(View child) {
        return null;
    }

    @Override
    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        super.dump(pw, args);
        DumpUtilsKt.withIncreasedIndent(pw, () -> {
            pw.println("Roundness: " + getRoundableState().debugString());
            if (DUMP_VERBOSE) {
                pw.println("mCustomOutline: " + mCustomOutline + " mOutlineRect: " + mOutlineRect);
                pw.println("mOutlineAlpha: " + mOutlineAlpha);
                pw.println("mAlwaysRoundBothCorners: " + mAlwaysRoundBothCorners);
            }
        });
    }
}
