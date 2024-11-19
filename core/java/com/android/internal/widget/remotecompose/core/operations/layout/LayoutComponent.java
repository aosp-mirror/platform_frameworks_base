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

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.operations.BitmapData;
import com.android.internal.widget.remotecompose.core.operations.MatrixRestore;
import com.android.internal.widget.remotecompose.core.operations.MatrixSave;
import com.android.internal.widget.remotecompose.core.operations.MatrixTranslate;
import com.android.internal.widget.remotecompose.core.operations.PaintData;
import com.android.internal.widget.remotecompose.core.operations.TextData;
import com.android.internal.widget.remotecompose.core.operations.TouchExpression;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ComponentModifiers;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ComponentVisibilityOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.DimensionModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.GraphicsLayerModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.HeightModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.PaddingModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.WidthModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ZIndexModifierOperation;

import java.util.ArrayList;

/** Component with modifiers and children */
public class LayoutComponent extends Component {

    @Nullable protected WidthModifierOperation mWidthModifier = null;
    @Nullable protected HeightModifierOperation mHeightModifier = null;
    @Nullable protected ZIndexModifierOperation mZIndexModifier = null;
    @Nullable protected GraphicsLayerModifierOperation mGraphicsLayerModifier = null;

    // Margins
    protected float mMarginLeft = 0f;
    protected float mMarginRight = 0f;
    protected float mMarginTop = 0f;
    protected float mMarginBottom = 0f;

    protected float mPaddingLeft = 0f;
    protected float mPaddingRight = 0f;
    protected float mPaddingTop = 0f;
    protected float mPaddingBottom = 0f;

    @NonNull protected ComponentModifiers mComponentModifiers = new ComponentModifiers();

    @NonNull
    protected ArrayList<Component> mChildrenComponents = new ArrayList<>(); // members are not null

    protected boolean mChildrenHaveZIndex = false;

    public LayoutComponent(
            @Nullable Component parent,
            int componentId,
            int animationId,
            float x,
            float y,
            float width,
            float height) {
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

    public float getPaddingLeft() {
        return mPaddingLeft;
    }

    public float getPaddingTop() {
        return mPaddingTop;
    }

    public float getPaddingRight() {
        return mPaddingRight;
    }

    public float getPaddingBottom() {
        return mPaddingBottom;
    }

    @Nullable
    public WidthModifierOperation getWidthModifier() {
        return mWidthModifier;
    }

    @Nullable
    public HeightModifierOperation getHeightModifier() {
        return mHeightModifier;
    }

    @Override
    public float getZIndex() {
        if (mZIndexModifier != null) {
            return mZIndexModifier.getValue();
        }
        return mZIndex;
    }

    @Nullable protected LayoutComponentContent mContent = null;

    // Should be removed after ImageLayout is in
    private static final boolean USE_IMAGE_TEMP_FIX = true;

    public void inflate() {
        ArrayList<TextData> data = new ArrayList<>();
        ArrayList<TouchExpression> touchExpressions = new ArrayList<>();
        ArrayList<PaintData> paintData = new ArrayList<>();

        for (Operation op : mList) {
            if (op instanceof LayoutComponentContent) {
                mContent = (LayoutComponentContent) op;
                mContent.mParent = this;
                mChildrenComponents.clear();
                LayoutComponentContent content = (LayoutComponentContent) op;
                content.getComponents(mChildrenComponents);
                if (USE_IMAGE_TEMP_FIX) {
                    if (mChildrenComponents.isEmpty() && !mContent.mList.isEmpty()) {
                        CanvasContent canvasContent =
                                new CanvasContent(-1, 0f, 0f, 0f, 0f, this, -1);
                        for (Operation opc : mContent.mList) {
                            if (opc instanceof BitmapData) {
                                canvasContent.mList.add(opc);
                                int w = ((BitmapData) opc).getWidth();
                                int h = ((BitmapData) opc).getHeight();
                                canvasContent.setWidth(w);
                                canvasContent.setHeight(h);
                            } else {
                                if (!((opc instanceof MatrixTranslate)
                                        || (opc instanceof MatrixSave)
                                        || (opc instanceof MatrixRestore))) {
                                    canvasContent.mList.add(opc);
                                }
                            }
                        }
                        if (!canvasContent.mList.isEmpty()) {
                            mContent.mList.clear();
                            mChildrenComponents.add(canvasContent);
                        }
                    } else {
                        content.getData(data);
                    }
                } else {
                    content.getData(data);
                }
            } else if (op instanceof ModifierOperation) {
                if (op instanceof ComponentVisibilityOperation) {
                    ((ComponentVisibilityOperation) op).setParent(this);
                }
                mComponentModifiers.add((ModifierOperation) op);
            } else if (op instanceof TextData) {
                data.add((TextData) op);
            } else if (op instanceof TouchExpression) {
                touchExpressions.add((TouchExpression) op);
            } else if (op instanceof PaintData) {
                paintData.add((PaintData) op);
            } else {
                // nothing
            }
        }

        mList.clear();
        mList.addAll(data);
        mList.addAll(touchExpressions);
        mList.addAll(paintData);
        mList.add(mComponentModifiers);
        for (Component c : mChildrenComponents) {
            c.mParent = this;
            mList.add(c);
            if (c instanceof LayoutComponent && ((LayoutComponent) c).mZIndexModifier != null) {
                mChildrenHaveZIndex = true;
            }
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
            if (op instanceof ZIndexModifierOperation) {
                mZIndexModifier = (ZIndexModifierOperation) op;
            }
            if (op instanceof GraphicsLayerModifierOperation) {
                mGraphicsLayerModifier = (GraphicsLayerModifierOperation) op;
            }
        }
        if (mWidthModifier == null) {
            mWidthModifier = new WidthModifierOperation(DimensionModifierOperation.Type.WRAP);
        }
        if (mHeightModifier == null) {
            mHeightModifier = new HeightModifierOperation(DimensionModifierOperation.Type.WRAP);
        }
        setWidth(computeModifierDefinedWidth());
        setHeight(computeModifierDefinedHeight());
    }

    @NonNull
    @Override
    public String toString() {
        return "UNKNOWN LAYOUT_COMPONENT";
    }

    @Override
    public float getScrollX() {
        return mComponentModifiers.getScrollX();
    }

    @Override
    public float getScrollY() {
        return mComponentModifiers.getScrollY();
    }

    @Override
    public void paintingComponent(@NonNull PaintContext context) {
        Component prev = context.getContext().mLastComponent;
        context.getContext().mLastComponent = this;
        context.save();
        context.translate(mX, mY);
        if (context.isVisualDebug()) {
            debugBox(this, context);
        }
        if (mGraphicsLayerModifier != null) {
            context.startGraphicsLayer((int) getWidth(), (int) getHeight());
            float scaleX = mGraphicsLayerModifier.getScaleX();
            float scaleY = mGraphicsLayerModifier.getScaleY();
            float rotationX = mGraphicsLayerModifier.getRotationX();
            float rotationY = mGraphicsLayerModifier.getRotationY();
            float rotationZ = mGraphicsLayerModifier.getRotationZ();
            float shadowElevation = mGraphicsLayerModifier.getShadowElevation();
            float transformOriginX = mGraphicsLayerModifier.getTransformOriginX();
            float transformOriginY = mGraphicsLayerModifier.getTransformOriginY();
            float alpha = mGraphicsLayerModifier.getAlpha();
            int renderEffectId = mGraphicsLayerModifier.getRenderEffectId();
            context.setGraphicsLayer(
                    scaleX,
                    scaleY,
                    rotationX,
                    rotationY,
                    rotationZ,
                    shadowElevation,
                    transformOriginX,
                    transformOriginY,
                    alpha,
                    renderEffectId);
        }
        mComponentModifiers.paint(context);
        float tx = mPaddingLeft + getScrollX();
        float ty = mPaddingTop + getScrollY();
        context.translate(tx, ty);
        if (mChildrenHaveZIndex) {
            // TODO -- should only sort when something has changed
            ArrayList<Component> sorted = new ArrayList<Component>(mChildrenComponents);
            sorted.sort((a, b) -> (int) (a.getZIndex() - b.getZIndex()));
            for (Component child : sorted) {
                child.paint(context);
            }
        } else {
            for (Component child : mChildrenComponents) {
                child.paint(context);
            }
        }
        if (mGraphicsLayerModifier != null) {
            context.endGraphicsLayer();
        }
        context.translate(-tx, -ty);
        context.restore();
        context.getContext().mLastComponent = prev;
    }

    /** Traverse the modifiers to compute indicated dimension */
    public float computeModifierDefinedWidth() {
        float s = 0f;
        float e = 0f;
        float w = 0f;
        for (Operation c : mComponentModifiers.getList()) {
            if (c instanceof WidthModifierOperation) {
                WidthModifierOperation o = (WidthModifierOperation) c;
                if (o.getType() == DimensionModifierOperation.Type.EXACT
                        || o.getType() == DimensionModifierOperation.Type.EXACT_DP) {
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
     * Traverse the modifiers to compute padding width
     *
     * @param padding output start and end padding values
     * @return padding width
     */
    public float computeModifierDefinedPaddingWidth(@NonNull float[] padding) {
        float s = 0f;
        float e = 0f;
        for (Operation c : mComponentModifiers.getList()) {
            if (c instanceof PaddingModifierOperation) {
                PaddingModifierOperation pop = (PaddingModifierOperation) c;
                s += pop.getLeft();
                e += pop.getRight();
            }
        }
        padding[0] = s;
        padding[1] = e;
        return s + e;
    }

    /** Traverse the modifiers to compute indicated dimension */
    public float computeModifierDefinedHeight() {
        float t = 0f;
        float b = 0f;
        float h = 0f;
        for (Operation c : mComponentModifiers.getList()) {
            if (c instanceof HeightModifierOperation) {
                HeightModifierOperation o = (HeightModifierOperation) c;
                if (o.getType() == DimensionModifierOperation.Type.EXACT
                        || o.getType() == DimensionModifierOperation.Type.EXACT_DP) {
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

    /**
     * Traverse the modifiers to compute padding height
     *
     * @param padding output top and bottom padding values
     * @return padding height
     */
    public float computeModifierDefinedPaddingHeight(@NonNull float[] padding) {
        float t = 0f;
        float b = 0f;
        for (Operation c : mComponentModifiers.getList()) {
            if (c instanceof PaddingModifierOperation) {
                PaddingModifierOperation pop = (PaddingModifierOperation) c;
                t += pop.getTop();
                b += pop.getBottom();
            }
        }
        padding[0] = t;
        padding[1] = b;
        return t + b;
    }

    @NonNull
    public ArrayList<Component> getChildrenComponents() {
        return mChildrenComponents;
    }
}
