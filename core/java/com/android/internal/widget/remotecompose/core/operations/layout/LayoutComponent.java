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
package com.android.internal.widget.remotecompose.core.operations.layout;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ComponentModifiers;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.DimensionModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.HeightModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.PaddingModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.WidthModifierOperation;

import java.util.ArrayList;

/**
 * Component with modifiers and children
 */
public class LayoutComponent extends Component {

    protected WidthModifierOperation mWidthModifier = null;
    protected HeightModifierOperation mHeightModifier = null;

    // Margins
    protected float mMarginLeft = 0f;
    protected float mMarginRight = 0f;
    protected float mMarginTop = 0f;
    protected float mMarginBottom = 0f;

    protected float mPaddingLeft = 0f;
    protected float mPaddingRight = 0f;
    protected float mPaddingTop = 0f;
    protected float mPaddingBottom = 0f;

    protected ComponentModifiers mComponentModifiers = new ComponentModifiers();
    protected ArrayList<Component> mChildrenComponents = new ArrayList<>();

    public LayoutComponent(Component parent, int componentId, int animationId,
                           float x, float y, float width, float height) {
        super(parent, componentId, animationId, x, y, width, height);
    }

    public float getMarginLeft() {
        return mMarginLeft;
    }
    public float getMarginRight() {
        return mMarginRight;
    }
    public float getMarginTop() {
        return mMarginTop;
    }
    public float getMarginBottom() {
        return mMarginBottom;
    }

    public WidthModifierOperation getWidthModifier() {
        return mWidthModifier;
    }
    public HeightModifierOperation getHeightModifier() {
        return mHeightModifier;
    }

    public void inflate() {
        for (Operation op : mList) {
            if (op instanceof LayoutComponentContent) {
                ((LayoutComponentContent) op).mParent = this;
                mChildrenComponents.clear();
                ((LayoutComponentContent) op).getComponents(mChildrenComponents);
                if (mChildrenComponents.isEmpty()) {
                    mChildrenComponents.add((Component) op);
                }
            } else if (op instanceof ModifierOperation) {
                mComponentModifiers.add((ModifierOperation) op);
            } else {
                // nothing
            }
        }

        mList.clear();
        mList.add(mComponentModifiers);
        for (Component c : mChildrenComponents) {
            c.mParent = this;
            mList.add(c);
        }

        mX = 0f;
        mY = 0f;
        mMarginLeft = 0f;
        mMarginTop = 0f;
        mMarginRight = 0f;
        mMarginBottom = 0f;
        mPaddingLeft = 0f;
        mPaddingTop = 0f;
        mPaddingRight = 0f;
        mPaddingBottom = 0f;

        boolean applyHorizontalMargin = true;
        boolean applyVerticalMargin = true;
        for (Operation op : mComponentModifiers.getList()) {
            if (op instanceof PaddingModifierOperation) {
                // We are accumulating padding modifiers to compute the margin
                // until we hit a dimension; the computed padding for the
                // content simply accumulate all the padding modifiers.
                float left = ((PaddingModifierOperation) op).getLeft();
                float right = ((PaddingModifierOperation) op).getRight();
                float top = ((PaddingModifierOperation) op).getTop();
                float bottom = ((PaddingModifierOperation) op).getBottom();
                if (applyHorizontalMargin) {
                    mMarginLeft += left;
                    mMarginRight += right;
                }
                if (applyVerticalMargin) {
                    mMarginTop += top;
                    mMarginBottom += bottom;
                }
                mPaddingLeft += left;
                mPaddingTop += top;
                mPaddingRight += right;
                mPaddingBottom += bottom;
            }
            if (op instanceof WidthModifierOperation && mWidthModifier == null) {
                mWidthModifier = (WidthModifierOperation) op;
                applyHorizontalMargin = false;
            }
            if (op instanceof HeightModifierOperation && mHeightModifier == null) {
                mHeightModifier = (HeightModifierOperation) op;
                applyVerticalMargin = false;
            }
        }
        if (mWidthModifier == null) {
            mWidthModifier = new WidthModifierOperation(DimensionModifierOperation.Type.WRAP);
        }
        if (mHeightModifier == null) {
            mHeightModifier = new HeightModifierOperation(DimensionModifierOperation.Type.WRAP);
        }
        mWidth = computeModifierDefinedWidth();
        mHeight = computeModifierDefinedHeight();
    }

    @Override
    public String toString() {
        return "UNKNOWN LAYOUT_COMPONENT";
    }

    @Override
    public void paintingComponent(PaintContext context) {
        context.save();
        context.translate(mX, mY);
        mComponentModifiers.paint(context);
        float tx = mPaddingLeft;
        float ty = mPaddingTop;
        context.translate(tx, ty);
        for (Component child : mChildrenComponents) {
            child.paint(context);
        }
        context.translate(-tx, -ty);
        context.restore();
    }

    /**
     * Traverse the modifiers to compute indicated dimension
     */
    public float computeModifierDefinedWidth() {
        float s = 0f;
        float e = 0f;
        float w = 0f;
        for (Operation c : mComponentModifiers.getList()) {
            if (c instanceof WidthModifierOperation) {
                WidthModifierOperation o = (WidthModifierOperation) c;
                if (o.getType() == DimensionModifierOperation.Type.EXACT) {
                    w = o.getValue();
                }
                break;
            }
            if (c instanceof PaddingModifierOperation) {
                PaddingModifierOperation pop = (PaddingModifierOperation) c;
                s += pop.getLeft();
                e += pop.getRight();
            }
        }
        return s + w + e;
    }

    /**
     * Traverse the modifiers to compute indicated dimension
     */
    public float computeModifierDefinedHeight() {
        float t = 0f;
        float b = 0f;
        float h = 0f;
        for (Operation c : mComponentModifiers.getList()) {
            if (c instanceof HeightModifierOperation) {
                HeightModifierOperation o = (HeightModifierOperation) c;
                if (o.getType() == DimensionModifierOperation.Type.EXACT) {
                    h = o.getValue();
                }
                break;
            }
            if (c instanceof PaddingModifierOperation) {
                PaddingModifierOperation pop = (PaddingModifierOperation) c;
                t += pop.getTop();
                b += pop.getBottom();
            }
        }
        return t + h + b;
    }
}
