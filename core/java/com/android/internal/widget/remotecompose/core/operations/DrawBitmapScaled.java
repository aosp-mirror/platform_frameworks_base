/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.ImageScaling;
import com.android.internal.widget.remotecompose.core.semantics.AccessibleComponent;

import java.util.List;

/** Operation to draw a given cached bitmap */
public class DrawBitmapScaled extends PaintOperation
        implements VariableSupport, AccessibleComponent {
    private static final int OP_CODE = Operations.DRAW_BITMAP_SCALED;
    private static final String CLASS_NAME = "DrawBitmapScaled";
    int mImageId;
    float mSrcLeft, mOutSrcLeft;
    float mSrcTop, mOutSrcTop;
    float mSrcRight, mOutSrcRight;
    float mSrcBottom, mOutSrcBottom;
    float mDstLeft, mOutDstLeft;
    float mDstTop, mOutDstTop;
    float mDstRight, mOutDstRight;
    float mDstBottom, mOutDstBottom;
    int mContentDescId;
    float mScaleFactor, mOutScaleFactor;
    int mScaleType;
    int mMode;

    @NonNull ImageScaling mScaling = new ImageScaling();
    public static final int SCALE_NONE = ImageScaling.SCALE_NONE;
    public static final int SCALE_INSIDE = ImageScaling.SCALE_INSIDE;
    public static final int SCALE_FILL_WIDTH = ImageScaling.SCALE_FILL_WIDTH;
    public static final int SCALE_FILL_HEIGHT = ImageScaling.SCALE_FILL_HEIGHT;
    public static final int SCALE_FIT = ImageScaling.SCALE_FIT;
    public static final int SCALE_CROP = ImageScaling.SCALE_CROP;
    public static final int SCALE_FILL_BOUNDS = ImageScaling.SCALE_FILL_BOUNDS;
    public static final int SCALE_FIXED_SCALE = ImageScaling.SCALE_FIXED_SCALE;

    public DrawBitmapScaled(
            int imageId,
            float srcLeft,
            float srcTop,
            float srcRight,
            float srcBottom,
            float dstLeft,
            float dstTop,
            float dstRight,
            float dstBottom,
            int type,
            float scale,
            int cdId) {
        this.mImageId = imageId;
        mOutSrcLeft = mSrcLeft = srcLeft;
        mOutSrcTop = mSrcTop = srcTop;
        mOutSrcRight = mSrcRight = srcRight;
        mOutSrcBottom = mSrcBottom = srcBottom;
        mOutDstLeft = mDstLeft = dstLeft;
        mOutDstTop = mDstTop = dstTop;
        mOutDstRight = mDstRight = dstRight;
        mOutDstBottom = mDstBottom = dstBottom;
        mScaleType = type & 0xFF;
        mMode = type >> 8;
        mOutScaleFactor = mScaleFactor = scale;
        this.mContentDescId = cdId;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        mOutSrcLeft =
                Float.isNaN(mSrcLeft) ? context.getFloat(Utils.idFromNan(mSrcLeft)) : mSrcLeft;
        mOutSrcTop = Float.isNaN(mSrcTop) ? context.getFloat(Utils.idFromNan(mSrcTop)) : mSrcTop;
        mOutSrcRight =
                Float.isNaN(mSrcRight) ? context.getFloat(Utils.idFromNan(mSrcRight)) : mSrcRight;
        mOutSrcBottom =
                Float.isNaN(mSrcBottom)
                        ? context.getFloat(Utils.idFromNan(mSrcBottom))
                        : mSrcBottom;
        mOutDstLeft =
                Float.isNaN(mDstLeft) ? context.getFloat(Utils.idFromNan(mDstLeft)) : mDstLeft;
        mOutDstTop = Float.isNaN(mDstTop) ? context.getFloat(Utils.idFromNan(mDstTop)) : mDstTop;
        mOutDstRight =
                Float.isNaN(mDstRight) ? context.getFloat(Utils.idFromNan(mDstRight)) : mDstRight;
        mOutDstBottom =
                Float.isNaN(mDstBottom)
                        ? context.getFloat(Utils.idFromNan(mDstBottom))
                        : mDstBottom;
        mOutScaleFactor =
                Float.isNaN(mScaleFactor)
                        ? context.getFloat(Utils.idFromNan(mScaleFactor))
                        : mScaleFactor;
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        register(context, mSrcLeft);
        register(context, mSrcTop);
        register(context, mSrcRight);
        register(context, mSrcBottom);
        register(context, mDstLeft);
        register(context, mDstTop);
        register(context, mDstRight);
        register(context, mDstBottom);
        register(context, mScaleFactor);
    }

    private void register(@NonNull RemoteContext context, float value) {
        if (Float.isNaN(value)) {
            context.listensTo(Utils.idFromNan(value), this);
        }
    }

    @NonNull
    static String str(float v) {
        String s = "  " + (int) v;
        return s.substring(s.length() - 3);
    }

    void print(String str, float left, float top, float right, float bottom) {
        String s = str;
        s += str(left) + ", " + str(top) + ", " + str(right) + ", " + str(bottom) + ", ";
        s += " [" + str(right - left) + " x " + str(bottom - top) + "]";
        System.out.println(s);
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(
                buffer,
                mImageId,
                mSrcLeft,
                mSrcTop,
                mSrcRight,
                mSrcBottom,
                mDstLeft,
                mDstTop,
                mDstRight,
                mDstBottom,
                mScaleType,
                mScaleFactor,
                mContentDescId);
    }

    @NonNull
    @Override
    public String toString() {
        return "DrawBitmapScaled "
                + mImageId
                + " ["
                + Utils.floatToString(mSrcLeft, mOutSrcLeft)
                + " "
                + Utils.floatToString(mSrcTop, mOutSrcTop)
                + " "
                + Utils.floatToString(mSrcRight, mOutSrcRight)
                + " "
                + Utils.floatToString(mSrcBottom, mOutSrcBottom)
                + "] "
                + "- ["
                + Utils.floatToString(mDstLeft, mOutDstLeft)
                + " "
                + Utils.floatToString(mDstTop, mOutDstTop)
                + " "
                + Utils.floatToString(mDstRight, mOutDstRight)
                + " "
                + Utils.floatToString(mDstBottom, mOutDstBottom)
                + "] "
                + " "
                + mScaleType
                + " "
                + Utils.floatToString(mScaleFactor, mOutScaleFactor);
    }

    @Override
    public Integer getContentDescriptionId() {
        return mContentDescId;
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return CLASS_NAME;
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    public static void apply(
            @NonNull WireBuffer buffer,
            int imageId,
            float srcLeft,
            float srcTop,
            float srcRight,
            float srcBottom,
            float dstLeft,
            float dstTop,
            float dstRight,
            float dstBottom,
            int scaleType,
            float scaleFactor,
            int cdId) {
        buffer.start(OP_CODE);
        buffer.writeInt(imageId);

        buffer.writeFloat(srcLeft);
        buffer.writeFloat(srcTop);
        buffer.writeFloat(srcRight);
        buffer.writeFloat(srcBottom);

        buffer.writeFloat(dstLeft);
        buffer.writeFloat(dstTop);
        buffer.writeFloat(dstRight);
        buffer.writeFloat(dstBottom);

        buffer.writeInt(scaleType);
        buffer.writeFloat(scaleFactor);
        buffer.writeInt(cdId);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int imageId = buffer.readInt();

        float sLeft = buffer.readFloat();
        float srcTop = buffer.readFloat();
        float srcRight = buffer.readFloat();
        float srcBottom = buffer.readFloat();

        float dstLeft = buffer.readFloat();
        float dstTop = buffer.readFloat();
        float dstRight = buffer.readFloat();
        float dstBottom = buffer.readFloat();
        int scaleType = buffer.readInt();
        float scaleFactor = buffer.readFloat();
        int cdId = buffer.readInt();
        DrawBitmapScaled op =
                new DrawBitmapScaled(
                        imageId,
                        sLeft,
                        srcTop,
                        srcRight,
                        srcBottom,
                        dstLeft,
                        dstTop,
                        dstRight,
                        dstBottom,
                        scaleType,
                        scaleFactor,
                        cdId);

        operations.add(op);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Draw Operations", OP_CODE, CLASS_NAME)
                .description("Draw a bitmap using integer coordinates")
                .field(DocumentedOperation.INT, "id", "id of bitmap")
                .field(DocumentedOperation.FLOAT, "srcLeft", "The left side of the image")
                .field(DocumentedOperation.FLOAT, "srcTop", "The top of the image")
                .field(DocumentedOperation.FLOAT, "srcRight", "The right side of the image")
                .field(DocumentedOperation.FLOAT, "srcBottom", "The bottom of the output")
                .field(DocumentedOperation.FLOAT, "dstLeft", "The left side of the output")
                .field(DocumentedOperation.FLOAT, "dstTop", "The top of the output")
                .field(DocumentedOperation.FLOAT, "dstRight", "The right side of the output")
                .field(DocumentedOperation.INT, "type", "type of auto scaling")
                .field(DocumentedOperation.INT, "scaleFactor", "for allowed")
                .field(DocumentedOperation.INT, "cdId", "id of string");
    }

    //    private String typeToString(int type) {
    //        String[] typeString = {
    //            "none",
    //            "inside",
    //            "fill_width",
    //            "fill_height",
    //            "fit",
    //            "crop",
    //            "fill_bounds",
    //            "fixed_scale"
    //        };
    //        return typeString[type];
    //    }

    @Override
    public void paint(@NonNull PaintContext context) {
        mScaling.setup(
                mOutSrcLeft,
                mOutSrcTop,
                mOutSrcRight,
                mOutSrcBottom,
                mOutDstLeft,
                mOutDstTop,
                mOutDstRight,
                mOutDstBottom,
                mScaleType,
                mOutScaleFactor);
        context.save();
        context.clipRect(mOutDstLeft, mOutDstTop, mOutDstRight, mOutDstBottom);

        int imageId = mImageId;
        if ((mMode & 0x1) != 0) {
            imageId = context.getContext().getInteger(imageId);
        }

        context.drawBitmap(
                imageId,
                (int) mOutSrcLeft,
                (int) mOutSrcTop,
                (int) mOutSrcRight,
                (int) mOutSrcBottom,
                (int) mScaling.mFinalDstLeft,
                (int) mScaling.mFinalDstTop,
                (int) mScaling.mFinalDstRight,
                (int) mScaling.mFinalDstBottom,
                mContentDescId);
        context.restore();
    }
}
