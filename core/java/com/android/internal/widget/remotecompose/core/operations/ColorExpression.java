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

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Operation to Colors
 * Color modes
 * mMode = 0 two colors and a tween
 * mMode = 1 color1 is a colorID.
 * mMode = 2 color2 is a colorID.
 * mMode = 3 color1 & color2 are ids
 * mMode = 4  H S V mode
 */
public class ColorExpression implements Operation, VariableSupport {
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
    public static final Companion COMPANION = new Companion();
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
    public void updateVariables(RemoteContext context) {
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
    public void registerListening(RemoteContext context) {
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
    public void apply(RemoteContext context) {
        if (mMode == 4) {
            context.loadColor(mId, (mAlpha << 24)
                    | (0xFFFFFF & Utils.hsvToRgb(mOutHue, mOutSat, mOutValue)));
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

            context.loadColor(mId,
                    Utils.interpolateColor(mOutColor1, mOutColor2, mOutTween));
        }

    }

    @Override
    public void write(WireBuffer buffer) {
        int mode = mMode | (mAlpha << 16);
        COMPANION.apply(buffer, mId, mode, mColor1, mColor2, mTween);
    }

    @Override
    public String toString() {
        if (mMode == 4) {
            return "ColorExpression[" + mId + "] = hsv (" + Utils.floatToString(mHue)
                    + ", " + Utils.floatToString(mSat)
                    + ", " + Utils.floatToString(mValue) + ")";
        }

        String c1 = (mMode & 1) == 1 ? "[" + mColor1 + "]" : Utils.colorInt(mColor1);
        String c2 = (mMode & 2) == 2 ? "[" + mColor2 + "]" : Utils.colorInt(mColor2);
        return "ColorExpression[" + mId + "] = tween(" + c1
                + ", " + c2 + ", "
                + Utils.floatToString(mTween) + ")";
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public String name() {
            return "ColorExpression";
        }

        @Override
        public int id() {
            return Operations.COLOR_EXPRESSIONS;
        }

        /**
         * Call to write a ColorExpression object on the buffer
         * @param buffer
         * @param id of the ColorExpression object
         * @param mode if colors are id or actual values
         * @param color1
         * @param color2
         * @param tween
         */
        public void apply(WireBuffer buffer,
                          int id, int mode,
                          int color1, int color2, float tween) {
            buffer.start(Operations.COLOR_EXPRESSIONS);
            buffer.writeInt(id);
            buffer.writeInt(mode);
            buffer.writeInt(color1);
            buffer.writeInt(color2);
            buffer.writeFloat(tween);

        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int id = buffer.readInt();
            int mode = buffer.readInt();
            int color1 = buffer.readInt();
            int color2 = buffer.readInt();
            float tween = buffer.readFloat();

            operations.add(new ColorExpression(id, mode, color1, color2, tween));
        }
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }

}
