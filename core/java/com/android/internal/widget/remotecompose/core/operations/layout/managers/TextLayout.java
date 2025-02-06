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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.Platform;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.Utils;
import com.android.internal.widget.remotecompose.core.operations.layout.Component;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.MeasurePass;
import com.android.internal.widget.remotecompose.core.operations.layout.measure.Size;
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;
import com.android.internal.widget.remotecompose.core.semantics.AccessibleComponent;
import com.android.internal.widget.remotecompose.core.serialize.MapSerializer;

import java.util.List;

/** Text component, referencing a text id */
public class TextLayout extends LayoutManager implements VariableSupport, AccessibleComponent {

    public static final int TEXT_ALIGN_LEFT = 1;
    public static final int TEXT_ALIGN_RIGHT = 2;
    public static final int TEXT_ALIGN_CENTER = 3;
    public static final int TEXT_ALIGN_JUSTIFY = 4;
    public static final int TEXT_ALIGN_START = 5;
    public static final int TEXT_ALIGN_END = 6;

    public static final int OVERFLOW_CLIP = 1;
    public static final int OVERFLOW_VISIBLE = 2;
    public static final int OVERFLOW_ELLIPSIS = 3;
    public static final int OVERFLOW_START_ELLIPSIS = 4;
    public static final int OVERFLOW_MIDDLE_ELLIPSIS = 5;

    private static final boolean DEBUG = false;
    private int mTextId = -1;
    private int mColor = 0;
    private float mFontSize = 16f;
    private int mFontStyle = 0;
    private float mFontWeight = 400f;
    private int mFontFamilyId = -1;
    private int mTextAlign = -1;
    private int mOverflow = 1;
    private int mMaxLines = Integer.MAX_VALUE;

    private int mType = -1;
    private float mTextX;
    private float mTextY;
    private float mTextW = -1;
    private float mTextH = -1;

    @Nullable private String mCachedString = "";

    Platform.ComputedTextLayout mComputedTextLayout;

    @Nullable
    @Override
    public Integer getTextId() {
        return mTextId;
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (mTextId != -1) {
            context.listensTo(mTextId, this);
        }
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        String cachedString = context.getText(mTextId);
        if (cachedString != null && cachedString.equalsIgnoreCase(mCachedString)) {
            return;
        }
        mCachedString = cachedString;
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
        mTextW = -1;
        mTextH = -1;

        if (mHorizontalScrollDelegate != null) {
            mHorizontalScrollDelegate.reset();
        }
        if (mVerticalScrollDelegate != null) {
            mVerticalScrollDelegate.reset();
        }
        invalidateMeasure();
    }

    public TextLayout(
            @Nullable Component parent,
            int componentId,
            int animationId,
            float x,
            float y,
            float width,
            float height,
            int textId,
            int color,
            float fontSize,
            int fontStyle,
            float fontWeight,
            int fontFamilyId,
            int textAlign,
            int overflow,
            int maxLines) {
        super(parent, componentId, animationId, x, y, width, height);
        mTextId = textId;
        mColor = color;
        mFontSize = fontSize;
        mFontStyle = fontStyle;
        mFontWeight = fontWeight;
        mFontFamilyId = fontFamilyId;
        mTextAlign = textAlign;
        mOverflow = overflow;
        mMaxLines = maxLines;
    }

    public TextLayout(
            @Nullable Component parent,
            int componentId,
            int animationId,
            int textId,
            int color,
            float fontSize,
            int fontStyle,
            float fontWeight,
            int fontFamilyId,
            int textAlign,
            int overflow,
            int maxLines) {
        this(
                parent,
                componentId,
                animationId,
                0,
                0,
                0,
                0,
                textId,
                color,
                fontSize,
                fontStyle,
                fontWeight,
                fontFamilyId,
                textAlign,
                overflow,
                maxLines);
    }

    @NonNull public PaintBundle mPaint = new PaintBundle();

    @Override
    public void paintingComponent(@NonNull PaintContext context) {
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
        if (mCachedString == null) {
            return;
        }
        int length = mCachedString.length();
        if (mComputedTextLayout != null) {
            context.drawComplexText(mComputedTextLayout);
        } else {
            float px = mTextX;
            switch (mTextAlign) {
                case TEXT_ALIGN_CENTER:
                    px = (mWidth - mPaddingLeft - mPaddingRight - mTextW) / 2f;
                    break;
                case TEXT_ALIGN_RIGHT:
                case TEXT_ALIGN_END:
                    px = (mWidth - mPaddingRight - mTextW);
                    break;
                case TEXT_ALIGN_LEFT:
                case TEXT_ALIGN_START:
                default:
            }
            if (mTextW > (mWidth - mPaddingLeft - mPaddingRight)) {
                context.save();
                context.clipRect(
                        0f,
                        0f,
                        mWidth - mPaddingLeft - mPaddingRight,
                        mHeight - mPaddingTop - mPaddingBottom);
                context.translate(getScrollX(), getScrollY());
                context.drawTextRun(mTextId, 0, length, 0, 0, px, mTextY, false);
                context.restore();
            } else {
                context.drawTextRun(mTextId, 0, length, 0, 0, px, mTextY, false);
            }
        }
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

    @NonNull
    @Override
    public String toString() {
        return "TEXT_LAYOUT ["
                + mComponentId
                + ":"
                + mAnimationId
                + "] ("
                + mX
                + ", "
                + mY
                + " - "
                + mWidth
                + " x "
                + mHeight
                + ") "
                + mVisibility;
    }

    @NonNull
    @Override
    protected String getSerializedName() {
        return "TEXT_LAYOUT";
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(
                indent,
                getSerializedName()
                        + " ["
                        + mComponentId
                        + ":"
                        + mAnimationId
                        + "] = "
                        + "["
                        + mX
                        + ", "
                        + mY
                        + ", "
                        + mWidth
                        + ", "
                        + mHeight
                        + "] "
                        + mVisibility
                        + " ("
                        + mTextId
                        + ":\""
                        + mCachedString
                        + "\")");
    }

    @Override
    public void computeWrapSize(
            @NonNull PaintContext context,
            float maxWidth,
            float maxHeight,
            boolean horizontalWrap,
            boolean verticalWrap,
            @NonNull MeasurePass measure,
            @NonNull Size size) {
        context.savePaint();
        mPaint.reset();
        mPaint.setTextSize(mFontSize);
        mPaint.setTextStyle(mType, (int) mFontWeight, mFontStyle == 1);
        mPaint.setColor(mColor);
        context.applyPaint(mPaint);
        float[] bounds = new float[4];
        if (mCachedString == null) {
            return;
        }
        int flags = PaintContext.TEXT_MEASURE_FONT_HEIGHT | PaintContext.TEXT_MEASURE_SPACES;
        if (mMaxLines == 1
                && (mOverflow == OVERFLOW_START_ELLIPSIS
                        || mOverflow == OVERFLOW_MIDDLE_ELLIPSIS
                        || mOverflow == OVERFLOW_ELLIPSIS)) {
            flags |= PaintContext.TEXT_COMPLEX;
        }
        context.getTextBounds(mTextId, 0, mCachedString.length(), flags, bounds);
        if (bounds[2] - bounds[1] > maxWidth) {
            mComputedTextLayout =
                    context.layoutComplexText(
                            mTextId,
                            0,
                            mCachedString.length(),
                            mTextAlign,
                            mOverflow,
                            mMaxLines,
                            maxWidth,
                            flags);
            if (mComputedTextLayout != null) {
                bounds[0] = 0f;
                bounds[1] = 0f;
                bounds[2] = mComputedTextLayout.getWidth();
                bounds[3] = mComputedTextLayout.getHeight();
            }
        } else {
            mComputedTextLayout = null;
        }
        context.restorePaint();
        float w = bounds[2] - bounds[0];
        float h = bounds[3] - bounds[1];
        size.setWidth(Math.min(maxWidth, w));
        mTextX = -bounds[0];
        size.setHeight(Math.min(maxHeight, h));
        mTextY = -bounds[1];
        mTextW = w;
        mTextH = h;
    }

    @Override
    public float intrinsicHeight(@Nullable RemoteContext context) {
        return mTextH;
    }

    @Override
    public float intrinsicWidth(@Nullable RemoteContext context) {
        return mTextW;
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return "TextLayout";
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return Operations.LAYOUT_TEXT;
    }

    /**
     * Write the operation in the buffer
     *
     * @param buffer the WireBuffer we write on
     * @param componentId the component id
     * @param animationId the animation id (-1 if not set)
     * @param textId the text id
     * @param color the text color
     * @param fontSize the font size
     * @param fontStyle the font style
     * @param fontWeight the font weight
     * @param fontFamilyId the font family id
     * @param textAlign the alignment rules
     * @param overflow
     * @param maxLines
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            int componentId,
            int animationId,
            int textId,
            int color,
            float fontSize,
            int fontStyle,
            float fontWeight,
            int fontFamilyId,
            int textAlign,
            int overflow,
            int maxLines) {
        buffer.start(id());
        buffer.writeInt(componentId);
        buffer.writeInt(animationId);
        buffer.writeInt(textId);
        buffer.writeInt(color);
        buffer.writeFloat(fontSize);
        buffer.writeInt(fontStyle);
        buffer.writeFloat(fontWeight);
        buffer.writeInt(fontFamilyId);
        buffer.writeInt(textAlign);
        buffer.writeInt(overflow);
        buffer.writeInt(maxLines);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int componentId = buffer.readInt();
        int animationId = buffer.readInt();
        int textId = buffer.readInt();
        int color = buffer.readInt();
        float fontSize = buffer.readFloat();
        int fontStyle = buffer.readInt();
        float fontWeight = buffer.readFloat();
        int fontFamilyId = buffer.readInt();
        int textAlign = buffer.readInt();
        int overflow = buffer.readInt();
        int maxLines = buffer.readInt();
        operations.add(
                new TextLayout(
                        null,
                        componentId,
                        animationId,
                        textId,
                        color,
                        fontSize,
                        fontStyle,
                        fontWeight,
                        fontFamilyId,
                        textAlign,
                        overflow,
                        maxLines));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Layout Operations", id(), name())
                .description("Text layout implementation.\n\n")
                .field(INT, "COMPONENT_ID", "unique id for this component")
                .field(
                        INT,
                        "ANIMATION_ID",
                        "id used to match components," + " for animation purposes")
                .field(INT, "COLOR", "text color")
                .field(FLOAT, "FONT_SIZE", "font size")
                .field(INT, "FONT_STYLE", "font style (0 = normal, 1 = italic)")
                .field(FLOAT, "FONT_WEIGHT", "font weight (1-1000, normal = 400)")
                .field(INT, "FONT_FAMILY_ID", "font family id");
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(
                buffer,
                mComponentId,
                mAnimationId,
                mTextId,
                mColor,
                mFontSize,
                mFontStyle,
                mFontWeight,
                mFontFamilyId,
                mTextAlign,
                mOverflow,
                mMaxLines);
    }

    @Override
    public void serialize(MapSerializer serializer) {
        super.serialize(serializer);
        serializer.add("textId", mTextId);
        serializer.add("color", Utils.colorInt(mColor));
        serializer.add("fontSize", mFontSize);
        serializer.add("fontStyle", mFontStyle);
        serializer.add("fontWeight", mFontWeight);
        serializer.add("fontFamilyId", mFontFamilyId);
        serializer.add("textAlign", mTextAlign);
    }
}
