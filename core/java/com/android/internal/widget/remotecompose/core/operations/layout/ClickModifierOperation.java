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

import com.android.internal.widget.remotecompose.core.CoreDocument;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.TextData;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ModifierOperation;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;
import com.android.internal.widget.remotecompose.core.operations.utilities.ColorUtils;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.Easing;
import com.android.internal.widget.remotecompose.core.operations.utilities.easing.FloatAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a click modifier + actions
 */
public class ClickModifierOperation extends PaintOperation
        implements ModifierOperation, DecoratorComponent {
    private static final int OP_CODE = Operations.MODIFIER_CLICK;


    long mAnimateRippleStart = 0;
    float mAnimateRippleX = 0f;
    float mAnimateRippleY = 0f;
    int mAnimateRippleDuration = 1000;

    float mWidth = 0;
    float mHeight = 0;

    public float[] locationInWindow = new float[2];

    PaintBundle mPaint = new PaintBundle();

    public void animateRipple(float x, float y) {
        mAnimateRippleStart = System.currentTimeMillis();
        mAnimateRippleX = x;
        mAnimateRippleY = y;
    }
    public ArrayList<Operation> mList = new ArrayList<>();

    public ArrayList<Operation> getList() {
        return mList;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer);
    }

    @Override
    public String toString() {
        return "ClickModifier";
    }

    @Override
    public void apply(RemoteContext context) {
        for (Operation op : mList) {
            if (op instanceof TextData) {
                op.apply(context);
            }
        }
    }

    @Override
    public String deepToString(String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public void paint(PaintContext context) {
        if (mAnimateRippleStart == 0) {
            return;
        }
        context.needsRepaint();

        float progress = (System.currentTimeMillis() - mAnimateRippleStart);
        progress /= (float) mAnimateRippleDuration;
        if (progress > 1f) {
            mAnimateRippleStart = 0;
        }
        progress = Math.min(1f, progress);
        context.save();
        context.savePaint();
        mPaint.reset();

        FloatAnimation anim1 = new FloatAnimation(Easing.CUBIC_STANDARD, 1f,
                null, Float.NaN, Float.NaN);
        anim1.setInitialValue(0f);
        anim1.setTargetValue(1f);
        float tween = anim1.get(progress);

        FloatAnimation anim2 = new FloatAnimation(Easing.CUBIC_STANDARD, 0.5f,
                null, Float.NaN, Float.NaN);
        anim2.setInitialValue(0f);
        anim2.setTargetValue(1f);
        float tweenRadius = anim2.get(progress);

        int startColor = ColorUtils.createColor(250, 250, 250, 180);
        int endColor = ColorUtils.createColor(200, 200, 200, 0);
        int paintedColor = Utils.interpolateColor(startColor, endColor, tween);

        float radius = Math.max(mWidth, mHeight) * tweenRadius;
        mPaint.setColor(paintedColor);
        context.applyPaint(mPaint);
        context.clipRect(0f, 0f, mWidth, mHeight);
        context.drawCircle(mAnimateRippleX, mAnimateRippleY, radius);
        context.restorePaint();
        context.restore();
    }

    @Override
    public void layout(RemoteContext context, float width, float height) {
        mWidth = width;
        mHeight = height;
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, "CLICK_MODIFIER");
        for (Operation o : mList) {
            if (o instanceof ActionOperation) {
                ((ActionOperation) o).serializeToString(indent + 1, serializer);
            }
        }
    }

    @Override
    public void onClick(RemoteContext context, CoreDocument document,
                        Component component, float x, float y) {
        locationInWindow[0] = 0f;
        locationInWindow[1] = 0f;
        component.getLocationInWindow(locationInWindow);
        animateRipple(x - locationInWindow[0], y - locationInWindow[1]);
        for (Operation o : mList) {
            if (o instanceof ActionOperation) {
                ((ActionOperation) o).runAction(context, document, component, x, y);
            }
        }
    }

    public static String name() {
        return "ClickModifier";
    }

    public static void apply(WireBuffer buffer) {
        buffer.start(OP_CODE);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        operations.add(new ClickModifierOperation());
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Layout Operations", OP_CODE, name())
                .description("Click modifier. This operation contains"
                        + " a list of action executed on click");
    }
}
