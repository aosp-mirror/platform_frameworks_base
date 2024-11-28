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
 * This defines the major id maps and ranges used by remote compose Generally ids ranging from 1 ...
 * 7FFFFF (4095) are for ids The data range is divided int to bits 0xxxxx are allocated for
 * Predefined Global System Variables 1xxxxx are allocated to normal variables 2xxxxx are allocated
 * to List&MAPS (Arrays of stuff) 3xxxxx are allocated to path & float operations
 * 4xxxxx,5xxxxx,7xxxxx are reserved for future use 0x1000-0x1100 are used for path operations in
 * PathData 0x1100-0x1200 are used for math operations in Animated float 0x
 */
public class NanMap {
    public static final int MOVE = 0x300_000;
    public static final int LINE = 0x300_001;
    public static final int QUADRATIC = 0x300_002;
    public static final int CONIC = 0x300_003;
    public static final int CUBIC = 0x300_004;
    public static final int CLOSE = 0x300_005;
    public static final int DONE = 0x300_006;
    public static final float MOVE_NAN = Utils.asNan(MOVE);
    public static final float LINE_NAN = Utils.asNan(LINE);
    public static final float QUADRATIC_NAN = Utils.asNan(QUADRATIC);
    public static final float CONIC_NAN = Utils.asNan(CONIC);
    public static final float CUBIC_NAN = Utils.asNan(CUBIC);
    public static final float CLOSE_NAN = Utils.asNan(CLOSE);
    public static final float DONE_NAN = Utils.asNan(DONE);

    public static boolean isSystemVariable(float value) {
        return (fromNaN(value) >> 20) == 0;
    }

    public static boolean isNormalVariable(float value) {
        return (fromNaN(value) >> 20) == 1;
    }

    public static boolean isDataVariable(float value) {
        return (fromNaN(value) >> 20) == 2;
    }

    public static boolean isOperationVariable(float value) {
        return (fromNaN(value) >> 20) == 3;
    }

    public static final int START_VAR = (1 << 20) + 42;
    public static final int START_ARRAY = (2 << 20) + 42;
    public static final int TYPE_SYSTEM = 0;
    public static final int TYPE_VARIABLE = 1;
    public static final int TYPE_ARRAY = 2;
    public static final int TYPE_OPERATION = 3;
    public static final int ID_REGION_MASK = 0x700000;
    public static final int ID_REGION_ARRAY = 0x200000;

    /**
     * Get ID from Nan float
     *
     * @param v
     * @return
     */
    public static int fromNaN(float v) {
        int b = Float.floatToRawIntBits(v);
        return b & 0x7FFFFF;
    }

    /**
     * Given id return as a Nan float
     *
     * @param v
     * @return
     */
    public static float asNan(int v) {
        return Float.intBitsToFloat(v | 0xFF800000);
    }
}
