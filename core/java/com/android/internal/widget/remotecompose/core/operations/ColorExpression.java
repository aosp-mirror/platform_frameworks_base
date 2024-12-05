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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.List;

/**
 * Operation to Colors Color modes mMode = 0 two colors and a tween mMode = 1 color1 is a colorID.
 * mMode = 2 color2 is a colorID. mMode = 3 color1 & color2 are ids mMode = 4 H S V mode
 */
public class ColorExpression extends Operation implements VariableSupport {
    private static final int OP_CODE = Operations.COLOR_EXPRESSIONS;
    private static final String CLASS_NAME = "ColorExpression";
    public int mId;
    int mMode;
    public int mColor1;
    public int mColor2;
    public float mTween = 0.0f;

    public float mHue = 0; // only in Mode 4
    public float mSat = 0;
    public float mValue = 0;
    public float mOutHue = 0; // only in Mode 4
    public float mOutSat = 0;
    public float mOutValue = 0;
    public int mAlpha = 0xFF; // only used in hsv mode

    public float mOutTween = 0.0f;
    public int mOutColor1;
    public int mOutColor2;
    public static final int HSV_MODE = 4;

    public ColorExpression(int id, float hue, float sat, float value) {
        mMode = HSV_MODE;
        mAlpha = 0xFF;
        mOutHue = mHue = hue;
        mOutSat = mSat = sat;
        mOutValue = mValue = value;
        mColor1 = Float.floatToRawIntBits(hue);
        mColor2 = Float.floatToRawIntBits(sat);
        mTween = value;
    }

    public ColorExpression(int id, int alpha, float hue, float sat, float value) {
        mMode = HSV_MODE;
        mAlpha = alpha;
        mOutHue = mHue = hue;
        mOutSat = mSat = sat;
        mOutValue = mValue = value;
        mColor1 = Float.floatToRawIntBits(hue);
        mColor2 = Float.floatToRawIntBits(sat);
        mTween = value;
    }

    public ColorExpression(int id, int mode, int color1, int color2, float tween) {
        this.mId = id;
        this.mMode = mode & 0xFF;
        this.mAlpha = (mode >> 16) & 0xFF;
        if (mMode == HSV_MODE) {
            mOutHue = mHue = Float.intBitsToFloat(color1);
            mOutSat = mSat = Float.intBitsToFloat(color2);
            mOutValue = mValue = tween;
        }
        this.mColor1 = color1;
        this.mColor2 = color2;
        this.mTween = tween;
        this.mOutTween = tween;
        this.mOutColor1 = color1;
        this.mOutColor2 = color2;
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        if (mMode == 4) {
            if (Float.isNaN(mHue)) {
                mOutHue = context.getFloat(Utils.idFromNan(mHue));
            }
            if (Float.isNaN(mSat)) {
                mOutSat = context.getFloat(Utils.idFromNan(mSat));
            }
            if (Float.isNaN(mValue)) {
                mOutValue = context.getFloat(Utils.idFromNan(mValue));
            }
        }
        if (Float.isNaN(mTween)) {
            mOutTween = context.getFloat(Utils.idFromNan(mTween));
        }
        if ((mMode & 1) == 1) {
            mOutColor1 = context.getColor(mColor1);
        }
        if ((mMode & 2) == 2) {
            mOutColor2 = context.getColor(mColor2);
        }
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        if (mMode == 4) {
            if (Float.isNaN(mHue)) {
                context.listensTo(Utils.idFromNan(mHue), this);
            }
            if (Float.isNaN(mSat)) {
                context.listensTo(Utils.idFromNan(mSat), this);
            }
            if (Float.isNaN(mValue)) {
                context.listensTo(Utils.idFromNan(mValue), this);
            }
            return;
        }
        if (Float.isNaN(mTween)) {
            context.listensTo(Utils.idFromNan(mTween), this);
        }
        if ((mMode & 1) == 1) {
            context.listensTo(mColor1, this);
        }
        if ((mMode & 2) == 2) {
            context.listensTo(mColor2, this);
        }
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        if (mMode == 4) {
            context.loadColor(
                    mId, (mAlpha << 24) | (0xFFFFFF & Utils.hsvToRgb(mOutHue, mOutSat, mOutValue)));
            return;
        }
        if (mOutTween == 0.0) {
            context.loadColor(mId, mColor1);
        } else {
            if ((mMode & 1) == 1) {
                mOutColor1 = context.getColor(mColor1);
            }
            if ((mMode & 2) == 2) {
                mOutColor2 = context.getColor(mColor2);
            }

            context.loadColor(mId, Utils.interpolateColor(mOutColor1, mOutColor2, mOutTween));
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        int mode = mMode | (mAlpha << 16);
        apply(buffer, mId, mode, mColor1, mColor2, mTween);
    }

    @NonNull
    @Override
    public String toString() {
        if (mMode == 4) {
            return "ColorExpression["
                    + mId
                    + "] = hsv ("
                    + Utils.floatToString(mHue)
                    + ", "
                    + Utils.floatToString(mSat)
                    + ", "
                    + Utils.floatToString(mValue)
                    + ")";
        }

        String c1 = (mMode & 1) == 1 ? "[" + mColor1 + "]" : Utils.colorInt(mColor1);
        String c2 = (mMode & 2) == 2 ? "[" + mColor2 + "]" : Utils.colorInt(mColor2);
        return "ColorExpression["
                + mId
                + "] = tween("
                + c1
                + ", "
                + c2
                + ", "
                + Utils.floatToString(mTween)
                + ")";
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

    /**
     * Call to write a ColorExpression object on the buffer
     *
     * @param buffer
     * @param id of the ColorExpression object
     * @param mode if colors are id or actual values
     * @param color1
     * @param color2
     * @param tween
     */
    public static void apply(
            @NonNull WireBuffer buffer, int id, int mode, int color1, int color2, float tween) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(mode);
        buffer.writeInt(color1);
        buffer.writeInt(color2);
        buffer.writeFloat(tween);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();
        int mode = buffer.readInt();
        int color1 = buffer.readInt();
        int color2 = buffer.readInt();
        float tween = buffer.readFloat();

        operations.add(new ColorExpression(id, mode, color1, color2, tween));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Expressions Operations", OP_CODE, CLASS_NAME)
                .description("A Color defined by an expression")
                .field(DocumentedOperation.INT, "id", "Id of the color")
                .field(INT, "mode", "The use of the next 3 fields")
                .possibleValues("COLOR_COLOR_INTERPOLATE", 0)
                .possibleValues("COLOR_ID_INTERPOLATE", 1)
                .possibleValues("ID_COLOR_INTERPOLATE", 2)
                .possibleValues("ID_ID_INTERPOLATE", 3)
                .possibleValues("HSV", 4)
                .field(INT, "color1", "32 bit ARGB color")
                .field(INT, "color2", "32 bit ARGB color")
                .field(FLOAT, "tween", "32 bit ARGB color");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }
}
