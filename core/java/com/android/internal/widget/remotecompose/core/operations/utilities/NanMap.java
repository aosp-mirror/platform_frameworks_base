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
package com.android.internal.widget.remotecompose.core.operations.utilities;

import com.android.internal.widget.remotecompose.core.operations.Utils;

/**
 * This defines the major id maps and ranges used by remote compose
 * Generally ids ranging from 0 ... FFF (4095) are for ids
 * 0x1000-0x1100 are used for path operations in PathData
 * 0x1100-0x1200 are used for math operations in Animated float
 * 0x
 */
public class NanMap {

    public static final int MOVE = 0x1000;
    public static final int LINE = 0x1001;
    public static final int QUADRATIC = 0x1002;
    public static final int CONIC = 0x1003;
    public static final int CUBIC = 0x1004;
    public static final int CLOSE = 0x1005;
    public static final int DONE = 0x1006;
    public static final float MOVE_NAN = Utils.asNan(MOVE);
    public static final float LINE_NAN = Utils.asNan(LINE);
    public static final float QUADRATIC_NAN = Utils.asNan(QUADRATIC);
    public static final float CONIC_NAN = Utils.asNan(CONIC);
    public static final float CUBIC_NAN = Utils.asNan(CUBIC);
    public static final float CLOSE_NAN = Utils.asNan(CLOSE);
    public static final float DONE_NAN = Utils.asNan(DONE);

    /**
     *
     */
    public static final float ADD = asNan(0x1100);
    public static final float SUB = asNan(0x1101);
    public static final float MUL = asNan(0x1102);
    public static final float DIV = asNan(0x1103);
    public static final float MOD = asNan(0x1104);
    public static final float MIN = asNan(0x1105);
    public static final float MAX = asNan(0x1106);
    public static final float POW = asNan(0x1107);


    /**
     * Get ID from Nan float
     * @param v
     * @return
     */
    public static int fromNaN(float v) {
        int b = Float.floatToRawIntBits(v);
        return b & 0xFFFFF;
    }

    /**
     * Given id return as a Nan float
     * @param v
     * @return
     */
    public static float asNan(int v) {
        return Float.intBitsToFloat(v | 0xFF800000);
    }
}
