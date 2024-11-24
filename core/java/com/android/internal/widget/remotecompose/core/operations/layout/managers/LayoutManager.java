/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.layout.managers;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.LayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.ComponentMeasure;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Measurable;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.MeasurePass;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Size;

/** Base class for layout managers -- resizable components. */
public abstract class LayoutManager extends LayoutComponent implements Measurable {

    @NonNull Size mCachedWrapSize = new Size(0f, 0f);

    public LayoutManager(
            @Nullable Component parent,
            int componentId,
            int animationId,
            float x,
            float y,
            float width,
            float height) {
        super(parent, componentId, animationId, x, y, width, height);
    }

    /** Implemented by subclasses to provide a layout/measure pass */
    public void internalLayoutMeasure(@NonNull PaintContext context, @NonNull MeasurePass measure) {
        // nothing here
    }

    /** Subclasses can implement this to provide wrap sizing */
    public void computeWrapSize(
            @NonNull PaintContext context,
            float maxWidth,
            float maxHeight,
            @NonNull MeasurePass measure,
            @NonNull Size size) {
        // nothing here
    }

    @Override
    public float intrinsicHeight() {
        float height = computeModifierDefinedHeight();
        for (Component c : mChildrenComponents) {
            height = Math.max(c.intrinsicHeight(), height);
        }
        return height;
    }

    @Override
    public float intrinsicWidth() {
        float width = computeModifierDefinedWidth();
        for (Component c : mChildrenComponents) {
            width = Math.max(c.intrinsicWidth(), width);
        }
        return width;
    }

    /** Subclasses can implement this when not in wrap sizing */
    public void computeSize(
            @NonNull PaintContext context,
            float minWidth,
            float maxWidth,
            float minHeight,
            float maxHeight,
            @NonNull MeasurePass measure) {
        // nothing here
    }

    protected boolean childrenHaveHorizontalWeights() {
        for (Component c : mChildrenComponents) {
            if (c instanceof LayoutManager) {
                LayoutManager m = (LayoutManager) c;
                if (m.getWidthModifier() != null && m.getWidthModifier().hasWeight()) {
                    return true;
                }
            }
        }
        return false;
    }

    protected boolean childrenHaveVerticalWeights() {
        for (Component c : mChildrenComponents) {
            if (c instanceof LayoutManager) {
                LayoutManager m = (LayoutManager) c;
                if (m.getHeightModifier() != null && m.getHeightModifier().hasWeight()) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isInHorizontalFill() {
        return mWidthModifier.isFill();
    }

    public boolean isInVerticalFill() {
        return mHeightModifier.isFill();
    }

    /** Base implementation of the measure resolution */
    @Override
    public void measure(
            @NonNull PaintContext context,
            float minWidth,
            float maxWidth,
            float minHeight,
            float maxHeight,
            @NonNull MeasurePass measure) {
        boolean hasWrap = true;
        float measuredWidth =
                Math.min(maxWidth, computeModifierDefinedWidth() - mMarginLeft - mMarginRight);
        float measuredHeight =
                Math.min(maxHeight, computeModifierDefinedHeight() - mMarginTop - mMarginBottom);
        float insetMaxWidth = maxWidth - mMarginLeft - mMarginRight;
        float insetMaxHeight = maxHeight - mMarginTop - mMarginBottom;
        if (mWidthModifier.isIntrinsicMin()) {
            maxWidth = intrinsicWidth();
        }
        if (mHeightModifier.isIntrinsicMin()) {
            maxHeight = intrinsicHeight();
        }
        if (mWidthModifier.isWrap() || mHeightModifier.isWrap()) { // TODO: potential npe -- bbade@
            mCachedWrapSize.setWidth(0f);
            mCachedWrapSize.setHeight(0f);
            computeWrapSize(context, maxWidth, maxHeight, measure, mCachedWrapSize);
            measuredWidth = mCachedWrapSize.getWidth();
            measuredHeight = mCachedWrapSize.getHeight();
        } else {
            hasWrap = false;
        }
        if (isInHorizontalFill()) {
            measuredWidth = insetMaxWidth;
        } else if (mWidthModifier.hasWeight()) {
            measuredWidth = Math.max(measuredWidth, computeModifierDefinedWidth());
        } else {
            measuredWidth = Math.max(measuredWidth, minWidth);
            measuredWidth = Math.min(measuredWidth, insetMaxWidth);
        }
        if (isInVerticalFill()) { // todo: potential npe -- bbade@
            measuredHeight = insetMaxHeight;
        } else if (mHeightModifier.hasWeight()) {
            measuredHeight = Math.max(measuredHeight, computeModifierDefinedHeight());
        } else {
            measuredHeight = Math.max(measuredHeight, minHeight);
            measuredHeight = Math.min(measuredHeight, insetMaxHeight);
        }
        if (minWidth == maxWidth) {
            measuredWidth = maxWidth;
        }
        if (minHeight == maxHeight) {
            measuredHeight = maxHeight;
        }
        measuredWidth = Math.min(measuredWidth, insetMaxWidth);
        measuredHeight = Math.min(measuredHeight, insetMaxHeight);
        if (!hasWrap) {
            if (hasHorizontalScroll()) {
                mCachedWrapSize.setWidth(0f);
                mCachedWrapSize.setHeight(0f);
                computeWrapSize(context, Float.MAX_VALUE, maxHeight, measure, mCachedWrapSize);
                float w = mCachedWrapSize.getWidth();
                computeSize(context, 0f, w, 0, measuredHeight, measure);
                mComponentModifiers.setHorizontalScrollDimension(measuredWidth, w);
            } else if (hasVerticalScroll()) {
                mCachedWrapSize.setWidth(0f);
                mCachedWrapSize.setHeight(0f);
                computeWrapSize(context, maxWidth, Float.MAX_VALUE, measure, mCachedWrapSize);
                float h = mCachedWrapSize.getHeight();
                computeSize(context, 0f, measuredWidth, 0, h, measure);
                mComponentModifiers.setVerticalScrollDimension(measuredHeight, h);
            } else {
                computeSize(context, 0f, measuredWidth, 0f, measuredHeight, measure);
            }
        }

        if (mContent != null) {
            ComponentMeasure cm = measure.get(mContent);
            cm.setX(0f);
            cm.setY(0f);
            cm.setW(measuredWidth);
            cm.setH(measuredHeight);
        }

        measuredWidth += mMarginLeft + mMarginRight;
        measuredHeight += mMarginTop + mMarginBottom;

        ComponentMeasure m = measure.get(this);
        m.setW(measuredWidth);
        m.setH(measuredHeight);
        m.setVisibility(mScheduledVisibility);

        internalLayoutMeasure(context, measure);
    }

    private boolean hasHorizontalScroll() {
        return mComponentModifiers.hasHorizontalScroll();
    }

    private boolean hasVerticalScroll() {
        return mComponentModifiers.hasVerticalScroll();
    }

    /** basic layout of internal components */
    @Override
    public void layout(@NonNull RemoteContext context, @NonNull MeasurePass measure) {
        super.layout(context, measure);
        ComponentMeasure self = measure.get(this);

        mComponentModifiers.layout(context, self.getW(), self.getH());
        for (Component c : mChildrenComponents) {
            c.layout(context, measure);
        }
        this.mNeedsMeasure = false;
    }

    /**
     * Only layout self, not children
     *
     * @param context
     * @param measure
     */
    public void selfLayout(@NonNull RemoteContext context, @NonNull MeasurePass measure) {
        super.layout(context, measure);
        ComponentMeasure self = measure.get(this);

        mComponentModifiers.layout(context, self.getW(), self.getH());
        this.mNeedsMeasure = false;
    }
}
