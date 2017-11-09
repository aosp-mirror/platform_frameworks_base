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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;

import com.android.settingslib.Utils;
import com.android.systemui.R;

/**
 * Like {@link ExpandableView}, but setting an outline for the height and clipping.
 */
public abstract class ExpandableOutlineView extends ExpandableView {

    private final Rect mOutlineRect = new Rect();
    private boolean mCustomOutline;
    private float mOutlineAlpha = -1f;
    private float mOutlineRadius;
    private boolean mAlwaysRoundBothCorners;
    private Path mTmpPath = new Path();
    private Path mTmpPath2 = new Path();
    private float mTopRoundness;
    private float mBottomRoundNess;

    /**
     * {@code true} if the children views of the {@link ExpandableOutlineView} are translated when
     * it is moved. Otherwise, the translation is set on the {@code ExpandableOutlineView} itself.
     */
    protected boolean mShouldTranslateContents;

    private final ViewOutlineProvider mProvider = new ViewOutlineProvider() {
        @Override
        public void getOutline(View view, Outline outline) {
            Path clipPath = getClipPath();
            if (clipPath != null && clipPath.isConvex()) {
                // The path might not be convex in border cases where the view is small and clipped
                outline.setConvexPath(clipPath);
            }
            outline.setAlpha(mOutlineAlpha);
        }
    };

    private Path getClipPath() {
        int left;
        int top;
        int right;
        int bottom;
        int height;
        Path intersectPath = null;
        if (!mCustomOutline) {
            left = mShouldTranslateContents ? (int) getTranslation() : 0;
            top = mClipTopAmount;
            right = getWidth() + Math.min(left, 0);
            left = Math.max(left, 0);
            bottom = Math.max(getActualHeight(), top);
            int intersectBottom = Math.max(getActualHeight() - mClipBottomAmount, top);
            if (bottom != intersectBottom) {
                getRoundedRectPath(left, top, right,
                        intersectBottom, 0.0f,
                        0.0f, mTmpPath2);
                intersectPath = mTmpPath2;
            }
        } else {
            left = mOutlineRect.left;
            top = mOutlineRect.top;
            right = mOutlineRect.right;
            bottom = mOutlineRect.bottom;
        }
        height = bottom - top;
        if (height == 0) {
            return null;
        }
        float topRoundness = mAlwaysRoundBothCorners
                ? mOutlineRadius : mTopRoundness * mOutlineRadius;
        float bottomRoundness = mAlwaysRoundBothCorners
                ? mOutlineRadius : mBottomRoundNess * mOutlineRadius;
        if (topRoundness + bottomRoundness > height) {
            float overShoot = topRoundness + bottomRoundness - height;
            topRoundness -= overShoot * mTopRoundness
                    / (mTopRoundness + mBottomRoundNess);
            bottomRoundness -= overShoot * mBottomRoundNess
                    / (mTopRoundness + mBottomRoundNess);
        }
        getRoundedRectPath(left, top, right, bottom, topRoundness,
                bottomRoundness, mTmpPath);
        Path roundedRectPath = mTmpPath;
        if (intersectPath != null) {
            roundedRectPath.op(intersectPath, Path.Op.INTERSECT);
        }
        return roundedRectPath;
    }

    protected Path getRoundedRectPath(int left, int top, int right, int bottom, float topRoundness,
            float bottomRoundness) {
        getRoundedRectPath(left, top, right, bottom, topRoundness, bottomRoundness,
                mTmpPath);
        return mTmpPath;
    }

    private void getRoundedRectPath(int left, int top, int right, int bottom, float topRoundness,
            float bottomRoundness, Path outPath) {
        outPath.reset();
        int width = right - left;
        float topRoundnessX = topRoundness;
        float bottomRoundnessX = bottomRoundness;
        topRoundnessX = Math.min(width / 2, topRoundnessX);
        bottomRoundnessX = Math.min(width / 2, bottomRoundnessX);
        if (topRoundness > 0.0f) {
            outPath.moveTo(left, top + topRoundness);
            outPath.quadTo(left, top, left + topRoundnessX, top);
            outPath.lineTo(right - topRoundnessX, top);
            outPath.quadTo(right, top, right, top + topRoundness);
        } else {
            outPath.moveTo(left, top);
            outPath.lineTo(right, top);
        }
        if (bottomRoundness > 0.0f) {
            outPath.lineTo(right, bottom - bottomRoundness);
            outPath.quadTo(right, bottom, right - bottomRoundnessX, bottom);
            outPath.lineTo(left + bottomRoundnessX, bottom);
            outPath.quadTo(left, bottom, left, bottom - bottomRoundness);
        } else {
            outPath.lineTo(right, bottom);
            outPath.lineTo(left, bottom);
        }
        outPath.close();
    }

    public ExpandableOutlineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOutlineProvider(mProvider);
        initDimens();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        if (needsContentClipping() && (mAlwaysRoundBothCorners || mTopRoundness > 0
                || mBottomRoundNess > 0 || mCustomOutline)) {
            Path clipPath = getCustomClipPath();
            if (clipPath == null) {
                clipPath = getClipPath();
            }
            if (clipPath != null) {
                canvas.clipPath(clipPath);
            }
        }
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    protected boolean needsContentClipping() {
        return false;
    }

    private void initDimens() {
        Resources res = getResources();
        mShouldTranslateContents =
                res.getBoolean(R.bool.config_translateNotificationContentsOnSwipe);
        mOutlineRadius = res.getDimension(R.dimen.notification_shadow_radius);
        mAlwaysRoundBothCorners = res.getBoolean(R.bool.config_clipNotificationsToOutline);
        if (!mAlwaysRoundBothCorners) {
            mOutlineRadius = res.getDimensionPixelSize(
                    Utils.getThemeAttr(mContext, android.R.attr.dialogCornerRadius));
        }
        setClipToOutline(mAlwaysRoundBothCorners);
    }

    public void setTopRoundness(float topRoundness) {
        if (mTopRoundness != topRoundness) {
            mTopRoundness = topRoundness;
            applyRoundness();
        }
    }

    protected void applyRoundness() {
        invalidateOutline();
        invalidate();
    }

    protected float getBackgroundRadiusTop() {
        return mTopRoundness * mOutlineRadius;
    }

    protected float getTopRoundness() {
        return mTopRoundness;
    }

    protected float getBackgroundRadiusBottom() {
        return mBottomRoundNess * mOutlineRadius;
    }

    public void setBottomRoundNess(float bottomRoundness) {
        if (mBottomRoundNess != bottomRoundness) {
            mBottomRoundNess = bottomRoundness;
            applyRoundness();
        }
    }

    public void onDensityOrFontScaleChanged() {
        initDimens();
        applyRoundness();
    }

    @Override
    public void setActualHeight(int actualHeight, boolean notifyListeners) {
        int previousHeight = getActualHeight();
        super.setActualHeight(actualHeight, notifyListeners);
        if (previousHeight != actualHeight) {
            applyRoundness();
        }
    }

    @Override
    public void setClipTopAmount(int clipTopAmount) {
        int previousAmount = getClipTopAmount();
        super.setClipTopAmount(clipTopAmount);
        if (previousAmount != clipTopAmount) {
            applyRoundness();
        }
    }

    @Override
    public void setClipBottomAmount(int clipBottomAmount) {
        int previousAmount = getClipBottomAmount();
        super.setClipBottomAmount(clipBottomAmount);
        if (previousAmount != clipBottomAmount) {
            applyRoundness();
        }
    }

    protected void setOutlineAlpha(float alpha) {
        if (alpha != mOutlineAlpha) {
            mOutlineAlpha = alpha;
            applyRoundness();
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
            applyRoundness();
        }
    }

    @Override
    public int getOutlineTranslation() {
        return mCustomOutline ? mOutlineRect.left : (int) getTranslation();
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
        applyRoundness();
    }

    public Path getCustomClipPath() {
        return null;
    }
}
