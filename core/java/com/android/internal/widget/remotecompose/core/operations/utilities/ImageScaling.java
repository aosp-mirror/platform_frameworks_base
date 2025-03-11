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
package com.android.internal.widget.remotecompose.core.operations.utilities;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.operations.Utils;

/** Implement the scaling logic for Compose Image or ImageView */
public class ImageScaling {

    private static final boolean DEBUG = false;

    public static final int SCALE_NONE = 0;
    public static final int SCALE_INSIDE = 1;
    public static final int SCALE_FILL_WIDTH = 2;
    public static final int SCALE_FILL_HEIGHT = 3;
    public static final int SCALE_FIT = 4;
    public static final int SCALE_CROP = 5;
    public static final int SCALE_FILL_BOUNDS = 6;
    public static final int SCALE_FIXED_SCALE = 7;

    private float mSrcLeft;
    private float mSrcTop;
    private float mSrcRight;
    private float mSrcBottom;
    private float mDstLeft;
    private float mDstTop;
    private float mDstRight;
    private float mDstBottom;
    private float mScaleFactor;
    private int mScaleType;

    public float mFinalDstLeft;
    public float mFinalDstTop;
    public float mFinalDstRight;
    public float mFinalDstBottom;

    public ImageScaling() {}

    public ImageScaling(
            float srcLeft,
            float srcTop,
            float srcRight,
            float srcBottom,
            float dstLeft,
            float dstTop,
            float dstRight,
            float dstBottom,
            int type,
            float scale) {

        mSrcLeft = srcLeft;
        mSrcTop = srcTop;
        mSrcRight = srcRight;
        mSrcBottom = srcBottom;
        mDstLeft = dstLeft;
        mDstTop = dstTop;
        mDstRight = dstRight;
        mDstBottom = dstBottom;
        mScaleType = type;
        mScaleFactor = scale;
        adjustDrawToType();
    }

    public void setup(
            float srcLeft,
            float srcTop,
            float srcRight,
            float srcBottom,
            float dstLeft,
            float dstTop,
            float dstRight,
            float dstBottom,
            int type,
            float scale) {

        mSrcLeft = srcLeft;
        mSrcTop = srcTop;
        mSrcRight = srcRight;
        mSrcBottom = srcBottom;
        mDstLeft = dstLeft;
        mDstTop = dstTop;
        mDstRight = dstRight;
        mDstBottom = dstBottom;
        mScaleType = type;
        mScaleFactor = scale;
        adjustDrawToType();
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
        Utils.log(s);
    }

    /** This adjust destnation on the DrawBitMapInt to support all contentScale types */
    private void adjustDrawToType() {
        int sw = (int) (mSrcRight - mSrcLeft);
        int sh = (int) (mSrcBottom - mSrcTop);
        float width = mDstRight - mDstLeft;
        float height = mDstBottom - mDstTop;
        int dw = (int) width;
        int dh = (int) height;
        int dLeft = 0;
        int dRight = dw;
        int dTop = 0;
        int dBottom = dh;
        if (DEBUG) {
            print("test rc ", mSrcLeft, mSrcTop, mSrcRight, mSrcBottom);
            print("test dst ", mDstLeft, mDstTop, mDstRight, mDstBottom);
        }
        if (sh == 0 || sw == 0) return;
        switch (mScaleType) {
            case SCALE_NONE:
                dh = sh;
                dw = sw;
                dTop = ((int) height - dh) / 2;
                dBottom = dh + dTop;
                dLeft = ((int) width - dw) / 2;
                dRight = dw + dLeft;
                break;
            case SCALE_INSIDE:
                if (dh > sh && dw > sw) {
                    dh = sh;
                    dw = sw;
                } else if (sw * height > width * sh) { // width dominated
                    dh = (dw * sh) / sw;
                } else {
                    dw = (dh * sw) / sh;
                }
                dTop = ((int) height - dh) / 2;
                dBottom = dh + dTop;
                dLeft = ((int) width - dw) / 2;
                dRight = dw + dLeft;
                break;
            case SCALE_FILL_WIDTH:
                dh = (dw * sh) / sw;

                dTop = ((int) height - dh) / 2;
                dBottom = dh + dTop;
                dLeft = ((int) width - dw) / 2;
                dRight = dw + dLeft;
                break;
            case SCALE_FILL_HEIGHT:
                dw = (dh * sw) / sh;

                dTop = ((int) height - dh) / 2;
                dBottom = dh + dTop;
                dLeft = ((int) width - dw) / 2;
                dRight = dw + dLeft;
                break;
            case SCALE_FIT:
                if (sw * height > width * sh) { // width dominated
                    dh = (dw * sh) / sw;
                    dTop = ((int) height - dh) / 2;
                    dBottom = dh + dTop;
                } else {
                    dw = (dh * sw) / sh;
                    dLeft = ((int) width - dw) / 2;
                    dRight = dw + dLeft;
                }
                break;
            case SCALE_CROP:
                if (sw * height < width * sh) { // width dominated
                    dh = (dw * sh) / sw;
                    dTop = ((int) height - dh) / 2;
                    dBottom = dh + dTop;
                } else {
                    dw = (dh * sw) / sh;
                    dLeft = ((int) width - dw) / 2;
                    dRight = dw + dLeft;
                }
                break;
            case SCALE_FILL_BOUNDS:
                // do nothing
                break;
            case SCALE_FIXED_SCALE:
                dh = (int) (sh * mScaleFactor);
                dw = (int) (sw * mScaleFactor);
                dTop = ((int) height - dh) / 2;
                dBottom = dh + dTop;
                dLeft = ((int) width - dw) / 2;
                dRight = dw + dLeft;
                break;
        }

        mFinalDstRight = dRight + mDstLeft;
        mFinalDstLeft = dLeft + mDstLeft;
        mFinalDstBottom = dBottom + mDstTop;
        mFinalDstTop = dTop + mDstTop;

        if (DEBUG) {
            print("test  out ", mFinalDstLeft, mFinalDstTop, mFinalDstRight, mFinalDstBottom);
        }
    }

    @NonNull
    public static String typeToString(int type) {
        String[] typeString = {
            "none",
            "inside",
            "fill_width",
            "fill_height",
            "fit",
            "crop",
            "fill_bounds",
            "fixed_scale"
        };
        return typeString[type];
    }
}
