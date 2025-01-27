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

    /**
     * Mode of the color expression 0 = two colors and a tween 1 = color1 is a colorID. 2 color2 is
     * a colorID. 3 = color1 & color2 are ids 4 = H S V mode 5 = RGB mode 6 = ARGB mode
     */
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

    private float mArgbAlpha = 0.0f;
    private float mArgbRed = 0.0f;
    private float mArgbGreen = 0.0f;
    private float mArgbBlue = 0.0f;

    private float mOutArgbAlpha = 0.0f;
    private float mOutArgbRed = 0.0f;
    private float mOutArgbGreen = 0.0f;
    private float mOutArgbBlue = 0.0f;

    public float mOutTween = 0.0f;
    public int mOutColor1;
    public int mOutColor2;

    /** COLOR_COLOR_INTERPOLATE */
    public static final byte COLOR_COLOR_INTERPOLATE = 0;

    /** COLOR_ID_INTERPOLATE */
    public static final byte ID_COLOR_INTERPOLATE = 1;

    /** ID_COLOR_INTERPOLATE */
    public static final byte COLOR_ID_INTERPOLATE = 2;

    /** ID_ID_INTERPOLATE */
    public static final byte ID_ID_INTERPOLATE = 3;

    /** H S V mode */
    public static final byte HSV_MODE = 4;

    /** ARGB mode */
    public static final byte ARGB_MODE = 5;

    /** ARGB mode with a being an id */
    public static final byte IDARGB_MODE = 6;

    /**
     * Create a new ColorExpression object
     *
     * @param id the id of the color
     * @param hue the hue of the color
     * @param sat the saturation of the color
     * @param value the value of the color
     */
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

    /**
     * Create a new ColorExpression object based on HSV
     *
     * @param id id of the color
     * @param mode the mode of the color
     * @param alpha the alpha of the color
     * @param hue the hue of the color
     * @param sat the saturation of the color
     * @param value the value (brightness) of the color
     */
    public ColorExpression(int id, byte mode, int alpha, float hue, float sat, float value) {
        if (mode != HSV_MODE) {
            throw new RuntimeException("Invalid mode " + mode);
        }
        mId = id;
        mMode = HSV_MODE;
        mAlpha = alpha;
        mOutHue = mHue = hue;
        mOutSat = mSat = sat;
        mOutValue = mValue = value;
        mColor1 = Float.floatToRawIntBits(hue);
        mColor2 = Float.floatToRawIntBits(sat);
        mTween = value;
    }

    /**
     * Create a new ColorExpression object based interpolationg two colors
     *
     * @param id the id of the color
     * @param mode the type of mode (are colors ids or actual values)
     * @param color1 the first color to use
     * @param color2 the second color to use
     * @param tween the value to use to interpolate between the two colors
     */
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

    /**
     * Create a new ColorExpression object based on ARGB
     *
     * @param id the id of the color
     * @param mode the mode must be ARGB_MODE
     * @param alpha the alpha value of the color
     * @param red the red of component the color
     * @param green the greej component of the color
     * @param blue the blue of component the color
     */
    public ColorExpression(int id, byte mode, float alpha, float red, float green, float blue) {
        if (mode != ARGB_MODE) {
            throw new RuntimeException("Invalid mode " + mode);
        }
        mMode = ARGB_MODE;
        mId = id;
        mOutArgbAlpha = mArgbAlpha = alpha;
        mOutArgbRed = mArgbRed = red;
        mOutArgbGreen = mArgbGreen = green;
        mOutArgbBlue = mArgbBlue = blue;
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
        if (mMode == ARGB_MODE) {
            if (Float.isNaN(mArgbAlpha)) {
                mOutArgbAlpha = context.getFloat(Utils.idFromNan(mArgbAlpha));
            }
            if (Float.isNaN(mArgbRed)) {
                mOutArgbRed = context.getFloat(Utils.idFromNan(mArgbRed));
            }
            if (Float.isNaN(mArgbGreen)) {
                mOutArgbGreen = context.getFloat(Utils.idFromNan(mArgbGreen));
            }
            if (Float.isNaN(mArgbBlue)) {
                mOutArgbBlue = context.getFloat(Utils.idFromNan(mArgbBlue));
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
        if (mMode == HSV_MODE) {
            context.loadColor(
                    mId, (mAlpha << 24) | (0xFFFFFF & Utils.hsvToRgb(mOutHue, mOutSat, mOutValue)));
            return;
        }
        if (mMode == ARGB_MODE) {
            context.loadColor(
                    mId, Utils.toARGB(mOutArgbAlpha, mOutArgbRed, mOutArgbGreen, mOutArgbBlue));
            return;
        }
        if (mOutTween == 0.0) {
            if ((mMode & 1) == 1) {
                mOutColor1 = context.getColor(mColor1);
            }
            context.loadColor(mId, mOutColor1);
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
        int mode;
        switch (mMode) {
            case ARGB_MODE:
                apply(buffer, mId, mArgbAlpha, mArgbRed, mArgbGreen, mArgbBlue);
                break;

            case HSV_MODE:
                mOutValue = mValue;
                mColor1 = Float.floatToRawIntBits(mHue);
                mColor2 = Float.floatToRawIntBits(mSat);
                mode = mMode | (mAlpha << 16);
                apply(buffer, mId, mode, mColor1, mColor2, mTween);

                break;
            case COLOR_ID_INTERPOLATE:
            case ID_COLOR_INTERPOLATE:
            case ID_ID_INTERPOLATE:
            case COLOR_COLOR_INTERPOLATE:
                apply(buffer, mId, mMode, mColor1, mColor2, mTween);

                break;
            default:
                throw new RuntimeException("Invalid mode ");
        }
    }

    @NonNull
    @Override
    public String toString() {
        if (mMode == HSV_MODE) {
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
        Utils.log(" ColorExpression toString" + mId + " " + mMode);
        if (mMode == ARGB_MODE) {
            return "ColorExpression["
                    + mId
                    + "] = rgb ("
                    + Utils.floatToString(mArgbAlpha)
                    + ", "
                    + Utils.floatToString(mArgbRed)
                    + ", "
                    + Utils.floatToString(mArgbGreen)
                    + ", "
                    + Utils.floatToString(mArgbRed)
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
        apply(buffer, id, mode, color1, color2, Float.floatToRawIntBits(tween));
    }

    /**
     * Call to write a ColorExpression object on the buffer
     *
     * @param buffer
     * @param id of the ColorExpression object
     * @param alpha
     * @param red
     * @param green
     * @param blue
     */
    public static void apply(
            @NonNull WireBuffer buffer, int id, float alpha, float red, float green, float blue) {
        int param1 = (Float.isNaN(alpha)) ? IDARGB_MODE : ARGB_MODE;
        param1 |=
                (Float.isNaN(alpha)) ? Utils.idFromNan(alpha) << 16 : ((int) (alpha * 1024)) << 16;
        int param2 = Float.floatToRawIntBits(red);
        int param3 = Float.floatToRawIntBits(green);
        int param4 = Float.floatToRawIntBits(blue);
        apply(buffer, id, param1, param2, param3, param4);
    }

    private static void apply(
            @NonNull WireBuffer buffer, int id, int param1, int param2, int param3, int param4) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(param1);
        buffer.writeInt(param2);
        buffer.writeInt(param3);
        buffer.writeInt(param4);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();
        int param1 = buffer.readInt();
        int param2 = buffer.readInt();
        int param3 = buffer.readInt();
        int param4 = buffer.readInt();
        int mode = param1 & 0xFF;
        float alpha;
        float red;
        float green;
        float blue;
        switch (mode) {
            case IDARGB_MODE:
                alpha = Utils.asNan(param1 >> 16);
                red = Float.intBitsToFloat(param2);
                green = Float.intBitsToFloat(param3);
                blue = Float.intBitsToFloat(param4);
                operations.add(new ColorExpression(id, (byte) ARGB_MODE, alpha, red, green, blue));
                break;
            case ARGB_MODE:
                alpha = (param1 >> 16) / 1024.0f;
                red = Float.intBitsToFloat(param2);
                green = Float.intBitsToFloat(param3);
                blue = Float.intBitsToFloat(param4);
                operations.add(new ColorExpression(id, (byte) ARGB_MODE, alpha, red, green, blue));
                break;
            case HSV_MODE:
                alpha = (param1 >> 16) / 1024.0f;
                float hue = Float.intBitsToFloat(param2);
                float sat = Float.intBitsToFloat(param3);
                float value = Float.intBitsToFloat(param4);
                operations.add(new ColorExpression(id, HSV_MODE, (param1 >> 16), hue, sat, value));
                break;
            case COLOR_ID_INTERPOLATE:
            case ID_COLOR_INTERPOLATE:
            case ID_ID_INTERPOLATE:
            case COLOR_COLOR_INTERPOLATE:
                operations.add(
                        new ColorExpression(
                                id, mode, param2, param3, Float.intBitsToFloat(param4)));
                break;
            default:
                throw new RuntimeException("Invalid mode " + mode);
        }
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
