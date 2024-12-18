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

import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.LayoutComponent;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.ComponentMeasure;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Measurable;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.MeasurePass;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Size;

/**
 * Base class for layout managers -- resizable components.
 */
public abstract class LayoutManager extends LayoutComponent implements Measurable {

    Size mCachedWrapSize = new Size(0f, 0f);

    public LayoutManager(Component parent, int componentId, int animationId,
                         float x, float y, float width, float height) {
        super(parent, componentId, animationId, x, y, width, height);
    }

    /**
     * Implemented by subclasses to provide a layout/measure pass
     */
    public void internalLayoutMeasure(PaintContext context,
                                      MeasurePass measure) {
        // nothing here
    }

    /**
     * Subclasses can implement this to provide wrap sizing
     */
    public void computeWrapSize(PaintContext context, float maxWidth, float maxHeight,
                                MeasurePass measure, Size size) {
        // nothing here
    }

    /**
     * Subclasses can implement this when not in wrap sizing
     */
    public void computeSize(PaintContext context, float minWidth, float maxWidth,
                            float minHeight, float maxHeight, MeasurePass measure) {
        // nothing here
    }

    /**
     * Base implementation of the measure resolution
     */
    @Override
    public void measure(PaintContext context, float minWidth, float maxWidth,
                        float minHeight, float maxHeight, MeasurePass measure) {
        boolean hasWrap = true;
        float measuredWidth = Math.min(maxWidth,
                computeModifierDefinedWidth() - mMarginLeft - mMarginRight);
        float measuredHeight = Math.min(maxHeight,
                computeModifierDefinedHeight() - mMarginTop - mMarginBottom);
        float insetMaxWidth = maxWidth - mMarginLeft - mMarginRight;
        float insetMaxHeight = maxHeight - mMarginTop - mMarginBottom;
        if (mWidthModifier.isWrap() || mHeightModifier.isWrap()) {
            mCachedWrapSize.setWidth(0f);
            mCachedWrapSize.setHeight(0f);
            computeWrapSize(context, maxWidth, maxHeight, measure, mCachedWrapSize);
            measuredWidth = mCachedWrapSize.getWidth();
            measuredHeight = mCachedWrapSize.getHeight();
        } else {
            hasWrap = false;
        }
        if (mWidthModifier.isFill()) {
            measuredWidth = insetMaxWidth;
        } else if (mWidthModifier.hasWeight()) {
            measuredWidth = Math.max(measuredWidth, computeModifierDefinedWidth());
        } else {
            measuredWidth = Math.max(measuredWidth, minWidth);
            measuredWidth = Math.min(measuredWidth, insetMaxWidth);
        }
        if (mHeightModifier.isFill()) {
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
            computeSize(context, 0f, measuredWidth, 0f, measuredHeight, measure);
        }
        measuredWidth += mMarginLeft + mMarginRight;
        measuredHeight += mMarginTop + mMarginBottom;

        ComponentMeasure m = measure.get(this);
        m.setW(measuredWidth);
        m.setH(measuredHeight);

        internalLayoutMeasure(context, measure);
    }

    /**
     * basic layout of internal components
     */
    @Override
    public void layout(RemoteContext context, MeasurePass measure) {
        super.layout(context, measure);
        ComponentMeasure self = measure.get(this);

        mComponentModifiers.layout(context, self.getW(), self.getH());
        for (Component c : mChildrenComponents) {
            c.layout(context, measure);
        }
        this.mNeedsMeasure = false;
    }
}
