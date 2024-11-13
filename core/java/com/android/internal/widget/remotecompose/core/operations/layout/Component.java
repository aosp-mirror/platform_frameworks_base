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
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.layout.animation.AnimateMeasure;
import com.android.internal.widget.remotecompose.core.operations.layout.animation.AnimationSpec;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.ComponentMeasure;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Measurable;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.MeasurePass;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ComponentModifiers;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.ArrayList;

/**
 * Generic Component class
 */
public class Component extends PaintOperation implements Measurable {

    protected int mComponentId = -1;
    protected float mX;
    protected float mY;
    protected float mWidth;
    protected float mHeight;
    protected Component mParent;
    protected int mAnimationId = -1;
    public Visibility mVisibility = Visibility.VISIBLE;
    public ArrayList<Operation> mList = new ArrayList<>();
    public PaintOperation mPreTranslate;
    public boolean mNeedsMeasure = true;
    public boolean mNeedsRepaint = false;
    public AnimateMeasure mAnimateMeasure;
    public AnimationSpec mAnimationSpec = new AnimationSpec();
    public boolean mFirstLayout = true;
    PaintBundle mPaint = new PaintBundle();

    public ArrayList<Operation> getList() {
        return mList;
    }
    public float getX() {
        return mX;
    }
    public float getY() {
        return mY;
    }
    public float getWidth() {
        return mWidth;
    }
    public float getHeight() {
        return mHeight;
    }
    public int getComponentId() {
        return mComponentId;
    }

    public int getAnimationId() {
        return mAnimationId;
    }

    public Component getParent() {
        return mParent;
    }
    public void setX(float value) {
        mX = value;
    }
    public void setY(float value) {
        mY = value;
    }
    public void setWidth(float value) {
        mWidth = value;
    }
    public void setHeight(float value) {
        mHeight = value;
    }

    public void setComponentId(int id) {
        mComponentId = id;
    }

    public void setAnimationId(int id) {
        mAnimationId = id;
    }

    public Component(Component parent, int componentId, int animationId,
                     float x, float y, float width, float height) {
        this.mComponentId = componentId;
        this.mX = x;
        this.mY = y;
        this.mWidth = width;
        this.mHeight = height;
        this.mParent = parent;
        this.mAnimationId = animationId;
    }

    public Component(int componentId, float x, float y, float width, float height,
                     Component parent) {
        this(parent, componentId, -1, x, y, width, height);
    }

    public Component(Component component) {
        this(component.mParent, component.mComponentId, component.mAnimationId,
                component.mX, component.mY, component.mWidth, component.mHeight
        );
        mList.addAll(component.mList);
        finalizeCreation();
    }

    public void finalizeCreation() {
        for (Operation op : mList) {
            if (op instanceof Component) {
                ((Component) op).mParent = this;
            }
            if (op instanceof AnimationSpec) {
                mAnimationSpec = (AnimationSpec) op;
                mAnimationId = mAnimationSpec.getAnimationId();
            }
        }
    }

    @Override
    public boolean needsMeasure() {
        return mNeedsMeasure;
    }

    public void setParent(Component parent) {
        mParent = parent;
    }

    public enum Visibility {
        VISIBLE,
        INVISIBLE,
        GONE
    }

    public boolean isVisible() {
        if (mVisibility != Visibility.VISIBLE || mParent == null) {
            return mVisibility == Visibility.VISIBLE;
        }
        if (mParent != null) {
            return mParent.isVisible();
        }
        return true;
    }

    @Override
    public void measure(PaintContext context, float minWidth, float maxWidth,
                        float minHeight, float maxHeight, MeasurePass measure) {
        ComponentMeasure m = measure.get(this);
        m.setW(mWidth);
        m.setH(mHeight);
    }

    @Override
    public void layout(RemoteContext context, MeasurePass measure) {
        ComponentMeasure m = measure.get(this);
        if (!mFirstLayout && context.isAnimationEnabled()) {
            if (mAnimateMeasure == null) {
                ComponentMeasure origin = new ComponentMeasure(mComponentId,
                        mX, mY, mWidth, mHeight, mVisibility);
                ComponentMeasure target = new ComponentMeasure(mComponentId,
                        m.getX(), m.getY(), m.getW(), m.getH(), m.getVisibility());
                mAnimateMeasure = new AnimateMeasure(context.currentTime, this,
                        origin, target,
                        mAnimationSpec.getMotionDuration(), mAnimationSpec.getVisibilityDuration(),
                        mAnimationSpec.getEnterAnimation(), mAnimationSpec.getExitAnimation(),
                        mAnimationSpec.getMotionEasingType(),
                        mAnimationSpec.getVisibilityEasingType());
            } else {
                mAnimateMeasure.updateTarget(m, context.currentTime);
            }
        } else {
            mVisibility = m.getVisibility();
        }
        mWidth = m.getW();
        mHeight = m.getH();
        setLayoutPosition(m.getX(), m.getY());
        mFirstLayout = false;
    }

    public float[] locationInWindow = new float[2];

    public boolean contains(float x, float y) {
        locationInWindow[0] = 0f;
        locationInWindow[1] = 0f;
        getLocationInWindow(locationInWindow);
        float lx1 = locationInWindow[0];
        float lx2 = lx1 + mWidth;
        float ly1 = locationInWindow[1];
        float ly2 = ly1 + mHeight;
        return x >= lx1 && x < lx2 && y >= ly1 && y < ly2;
    }

    public void onClick(float x, float y) {
        if (!contains(x, y)) {
            return;
        }
        for (Operation op : mList) {
            if (op instanceof Component) {
                ((Component) op).onClick(x, y);
            }
            if (op instanceof ComponentModifiers) {
                ((ComponentModifiers) op).onClick(x, y);
            }
        }
    }

    public void getLocationInWindow(float[] value) {
        value[0] += mX;
        value[1] += mY;
        if (mParent != null && mParent instanceof Component) {
            if (mParent instanceof LayoutComponent) {
                value[0] += ((LayoutComponent) mParent).getMarginLeft();
                value[1] += ((LayoutComponent) mParent).getMarginTop();
            }
            mParent.getLocationInWindow(value);
        }
    }

    @Override
    public String toString() {
        return "COMPONENT(<" + mComponentId + "> " + getClass().getSimpleName()
                + ") [" + mX + "," + mY + " - " + mWidth + " x " + mHeight + "] " + textContent()
                + " Visibility (" + mVisibility + ") ";
    }

    protected String getSerializedName() {
        return "COMPONENT";
    }

    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, getSerializedName() + " [" + mComponentId
                + ":" + mAnimationId + "] = "
                + "[" + mX + ", " + mY + ", " + mWidth + ", " + mHeight + "] "
                + mVisibility
        //        + " [" + mNeedsMeasure + ", " + mNeedsRepaint + "]"
        );
    }

    @Override
    public void write(WireBuffer buffer) {
        // nothing
    }

    /**
     * Returns the top-level RootLayoutComponent
     */
    public RootLayoutComponent getRoot() throws Exception {
        if (this instanceof RootLayoutComponent) {
            return (RootLayoutComponent) this;
        }
        Component p = mParent;
        while (!(p instanceof RootLayoutComponent)) {
            if (p == null) {
                throw new Exception("No RootLayoutComponent found");
            }
            p = p.mParent;
        }
        return (RootLayoutComponent) p;
    }

    @Override
    public String deepToString(String indent) {
        StringBuilder builder = new StringBuilder();
        builder.append(indent);
        builder.append(toString());
        builder.append("\n");
        String indent2 = "  " + indent;
        for (Operation op : mList) {
            builder.append(op.deepToString(indent2));
            builder.append("\n");
        }
        return builder.toString();
    }

    /**
     * Mark itself as needing to be remeasured, and walk back up the tree
     * to mark each parents as well.
     */
    public void invalidateMeasure() {
        needsRepaint();
        mNeedsMeasure = true;
        Component p = mParent;
        while (p != null) {
            p.mNeedsMeasure = true;
            p = p.mParent;
        }
    }

    public void needsRepaint() {
        try {
            getRoot().mNeedsRepaint = true;
        } catch (Exception e) {
            // nothing
        }
    }

    public String content() {
        StringBuilder builder = new StringBuilder();
        for (Operation op : mList) {
            builder.append("- ");
            builder.append(op);
            builder.append("\n");
        }
        return builder.toString();
    }

    public String textContent() {
        StringBuilder builder = new StringBuilder();
        for (Operation op : mList) {
            String letter = "";
            // if (op instanceof DrawTextRun) {
            //   letter = "[" + ((DrawTextRun) op).text + "]";
            // }
            builder.append(letter);
        }
        return builder.toString();
    }

    public void debugBox(Component component, PaintContext context) {
        float width = component.mWidth;
        float height = component.mHeight;

        context.savePaint();
        mPaint.reset();
        mPaint.setColor(0, 0, 255, 255); // Blue color
        context.applyPaint(mPaint);
        context.drawLine(0f, 0f, width, 0f);
        context.drawLine(width, 0f, width, height);
        context.drawLine(width, height, 0f, height);
        context.drawLine(0f, height, 0f, 0f);
        //        context.setColor(255, 0, 0, 255)
        //        context.drawLine(0f, 0f, width, height)
        //        context.drawLine(0f, height, width, 0f)
        context.restorePaint();
    }

    public void setLayoutPosition(float x, float y) {
        this.mX = x;
        this.mY = y;
    }

    public float getTranslateX() {
        if (mParent != null) {
            return mX - mParent.mX;
        }
        return 0f;
    }

    public float getTranslateY() {
        if (mParent != null) {
            return mY - mParent.mY;
        }
        return 0f;
    }

    public void paintingComponent(PaintContext context) {
        if (mPreTranslate != null) {
            mPreTranslate.paint(context);
        }
        context.save();
        context.translate(mX, mY);
        if (context.isDebug()) {
            debugBox(this, context);
        }
        for (Operation op : mList) {
            if (op instanceof PaintOperation) {
                ((PaintOperation) op).paint(context);
            }
        }
        context.restore();
    }

    public boolean applyAnimationAsNeeded(PaintContext context) {
        if (context.isAnimationEnabled() && mAnimateMeasure != null) {
            mAnimateMeasure.apply(context);
            needsRepaint();
            return true;
        }
        return false;
    }

    @Override
    public void paint(PaintContext context) {
        if (context.isDebug()) {
            context.save();
            context.translate(mX, mY);
            context.savePaint();
            mPaint.reset();
            mPaint.setColor(0, 255, 0, 255); // Green
            context.applyPaint(mPaint);
            context.drawLine(0f, 0f, mWidth, 0f);
            context.drawLine(mWidth, 0f, mWidth, mHeight);
            context.drawLine(mWidth, mHeight, 0f, mHeight);
            context.drawLine(0f, mHeight, 0f, 0f);
            mPaint.setColor(255, 0, 0, 255); // Red
            context.applyPaint(mPaint);
            context.drawLine(0f, 0f, mWidth, mHeight);
            context.drawLine(0f, mHeight, mWidth, 0f);
            context.restorePaint();
            context.restore();
        }
        if (applyAnimationAsNeeded(context)) {
            return;
        }
        if (mVisibility == Visibility.GONE) {
            return;
        }
        paintingComponent(context);
    }

    public void getComponents(ArrayList<Component> components) {
        for (Operation op : mList) {
            if (op instanceof Component) {
                components.add((Component) op);
            }
        }
    }

    public int getComponentCount() {
        int count = 0;
        for (Operation op : mList) {
            if (op instanceof Component) {
                count += 1 + ((Component) op).getComponentCount();
            }
        }
        return count;
    }

    public int getPaintId() {
        if (mAnimationId != -1) {
            return mAnimationId;
        }
        return mComponentId;
    }

    public boolean doesNeedsRepaint() {
        return mNeedsRepaint;
    }

    public Component getComponent(int cid) {
        if (mComponentId == cid || mAnimationId == cid) {
            return this;
        }
        for (Operation c : mList) {
            if (c instanceof Component) {
                Component search = ((Component) c).getComponent(cid);
                if (search != null) {
                    return search;
                }
            }
        }
        return null;
    }
}
