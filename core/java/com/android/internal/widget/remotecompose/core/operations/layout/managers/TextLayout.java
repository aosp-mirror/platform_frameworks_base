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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.FLOAT;
import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.ComponentStartOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.MeasurePass;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Size;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Text component, referencing a text id
 */
public class TextLayout extends LayoutManager implements ComponentStartOperation, VariableSupport {

    private static final boolean DEBUG = false;
    private int mTextId = -1;
    private int mColor = 0;
    private float mFontSize = 16f;
    private int mFontStyle = 0;
    private float mFontWeight = 400f;
    private int mFontFamilyId = -1;

    private int mType = -1;
    private float mTextX;
    private float mTextY;

    private String mCachedString = "";

    @Override
    public void registerListening(RemoteContext context) {
        if (mTextId != -1) {
            context.listensTo(mTextId, this);
        }
    }

    @Override
    public void updateVariables(RemoteContext context) {
        mCachedString = context.getText(mTextId);
        if (mType == -1) {
            if (mFontFamilyId != -1) {
                String fontFamily = context.getText(mFontFamilyId);
                if (fontFamily != null) {
                    mType = 0; // default
                    if (fontFamily.equalsIgnoreCase("default")) {
                        mType = 0;
                    } else if (fontFamily.equalsIgnoreCase("sans-serif")) {
                        mType = 1;
                    } else if (fontFamily.equalsIgnoreCase("serif")) {
                        mType = 2;
                    } else if (fontFamily.equalsIgnoreCase("monospace")) {
                        mType = 3;
                    }
                }
            } else {
                mType = 0;
            }
        }
        mNeedsMeasure = true;
        needsRepaint();
    }

    public TextLayout(Component parent, int componentId, int animationId,
                      float x, float y, float width, float height,
                      int textId, int color, float fontSize,
                      int fontStyle, float fontWeight, int fontFamilyId) {
        super(parent, componentId, animationId, x, y, width, height);
        mTextId = textId;
        mColor = color;
        mFontSize = fontSize;
        mFontStyle = fontStyle;
        mFontWeight = fontWeight;
        mFontFamilyId = fontFamilyId;
    }

    public TextLayout(Component parent, int componentId, int animationId,
                      int textId, int color, float fontSize,
                      int fontStyle, float fontWeight, int fontFamilyId) {
        this(parent, componentId, animationId, 0, 0, 0, 0,
                textId, color, fontSize, fontStyle, fontWeight, fontFamilyId);
    }

    public PaintBundle mPaint = new PaintBundle();

    @Override
    public void paintingComponent(PaintContext context) {
        context.save();
        context.translate(mX, mY);
        mComponentModifiers.paint(context);
        float tx = mPaddingLeft;
        float ty = mPaddingTop;
        context.translate(tx, ty);

        //////////////////////////////////////////////////////////
        // Text content
        //////////////////////////////////////////////////////////
        context.savePaint();
        mPaint.reset();
        mPaint.setStyle(PaintBundle.STYLE_FILL);
        mPaint.setColor(mColor);
        mPaint.setTextSize(mFontSize);
        mPaint.setTextStyle(mType, (int) mFontWeight, mFontStyle == 1);
        context.applyPaint(mPaint);
        int length = mCachedString.length();
        context.drawTextRun(mTextId, 0, length, 0, 0, mTextX, mTextY, false);
        if (DEBUG) {
            mPaint.setStyle(PaintBundle.STYLE_FILL_AND_STROKE);
            mPaint.setColor(1f, 1F, 1F, 1F);
            mPaint.setStrokeWidth(3f);
            context.applyPaint(mPaint);
            context.drawLine(0f, 0f, mWidth, mHeight);
            context.drawLine(0f, mHeight, mWidth, 0f);
            mPaint.setColor(1f, 0F, 0F, 1F);
            mPaint.setStrokeWidth(1f);
            context.applyPaint(mPaint);
            context.drawLine(0f, 0f, mWidth, mHeight);
            context.drawLine(0f, mHeight, mWidth, 0f);
        }
        context.restorePaint();
        //////////////////////////////////////////////////////////

        context.translate(-tx, -ty);
        context.restore();
    }

    @Override
    public String toString() {
        return "TEXT_LAYOUT [" + mComponentId + ":" + mAnimationId + "] (" + mX + ", "
                + mY + " - " + mWidth + " x " + mHeight + ") " + mVisibility;
    }

    protected String getSerializedName() {
        return "TEXT_LAYOUT";
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, getSerializedName() + " [" + mComponentId
                + ":" + mAnimationId + "] = "
                + "[" + mX + ", " + mY + ", " + mWidth + ", " + mHeight + "] "
                + mVisibility + " (" + mTextId + ":\"" + mCachedString + "\")"
        );
    }

    @Override
    public void computeWrapSize(PaintContext context, float maxWidth, float maxHeight,
                                MeasurePass measure, Size size) {
        context.savePaint();
        mPaint.reset();
        mPaint.setTextSize(mFontSize);
        mPaint.setTextStyle(mType, (int) mFontWeight, mFontStyle == 1);
        context.applyPaint(mPaint);
        float[] bounds = new float[4];
        int flags = PaintContext.TEXT_MEASURE_FONT_HEIGHT;
        context.getTextBounds(mTextId, 0, mCachedString.length(), flags, bounds);
        context.restorePaint();
        float w = bounds[2] - bounds[0];
        float h = bounds[3] - bounds[1];
        size.setWidth(w);
        mTextX = -bounds[0];
        size.setHeight(h);
        mTextY = -bounds[1];
    }

    public static String name() {
        return "TextLayout";
    }

    public static int id() {
        return Operations.LAYOUT_TEXT;
    }

    public static void apply(WireBuffer buffer, int componentId, int animationId,
                             int textId, int color, float fontSize, int fontStyle,
                             float fontWeight, int fontFamilyId) {
        buffer.start(id());
        buffer.writeInt(componentId);
        buffer.writeInt(animationId);
        buffer.writeInt(textId);
        buffer.writeInt(color);
        buffer.writeFloat(fontSize);
        buffer.writeInt(fontStyle);
        buffer.writeFloat(fontWeight);
        buffer.writeInt(fontFamilyId);
    }

    public static void read(WireBuffer buffer, List<Operation> operations) {
        int componentId = buffer.readInt();
        int animationId = buffer.readInt();
        int textId = buffer.readInt();
        int color = buffer.readInt();
        float fontSize = buffer.readFloat();
        int fontStyle = buffer.readInt();
        float fontWeight = buffer.readFloat();
        int fontFamilyId = buffer.readInt();
        operations.add(new TextLayout(null, componentId, animationId, textId, color, fontSize,
                fontStyle, fontWeight, fontFamilyId));
    }

    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Layout Operations", id(), name())
                .description("Text layout implementation.\n\n")
                .field(INT, "COMPONENT_ID", "unique id for this component")
                .field(INT, "ANIMATION_ID", "id used to match components,"
                        + " for animation purposes")
                .field(INT, "COLOR", "text color")
                .field(FLOAT, "FONT_SIZE", "font size")
                .field(INT, "FONT_STYLE", "font style (0 = normal, 1 = italic)")
                .field(FLOAT, "FONT_WEIGHT", "font weight (1-1000, normal = 400)")
                .field(INT, "FONT_FAMILY_ID", "font family id");
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mComponentId, mAnimationId,
                mTextId, mColor, mFontSize, mFontStyle,
                mFontWeight, mFontFamilyId);
    }
}
