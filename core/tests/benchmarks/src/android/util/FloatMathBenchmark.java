/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.util;

import android.util.FloatMath;

public class FloatMathBenchmark {

    public float timeFloatMathCeil(int reps) {
        // Keep an answer so we don't optimize the method call away.
        float f = 0.0f;
        for (int i = 0; i < reps; i++) {
            f += FloatMath.ceil(100.123f);
        }
        return f;
    }

    public float timeFloatMathCeil_math(int reps) {
        // Keep an answer so we don't optimize the method call away.
        float f = 0.0f;
        for (int i = 0; i < reps; i++) {
            f += (float) Math.ceil(100.123f);
        }
        return f;
    }

    public float timeFloatMathCos(int reps) {
        // Keep an answer so we don't optimize the method call away.
        float f = 0.0f;
        for (int i = 0; i < reps; i++) {
            f += FloatMath.cos(100.123f);
        }
        return f;
    }

    public float timeFloatMathExp(int reps) {
        // Keep an answer so we don't optimize the method call away.
        float f = 0.0f;
        for (int i = 0; i < reps; i++) {
            f += FloatMath.exp(100.123f);
        }
        return f;
    }

    public float timeFloatMathFloor(int reps) {
        // Keep an answer so we don't optimize the method call away.
        float f = 0.0f;
        for (int i = 0; i < reps; i++) {
            f += FloatMath.floor(100.123f);
        }
        return f;
    }

    public float timeFloatMathHypot(int reps) {
        // Keep an answer so we don't optimize the method call away.
        float f = 0.0f;
        for (int i = 0; i < reps; i++) {
            f += FloatMath.hypot(100.123f, 100.123f);
        }
        return f;
    }

    public float timeFloatMathPow(int reps) {
        // Keep an answer so we don't optimize the method call away.
        float f = 0.0f;
        for (int i = 0; i < reps; i++) {
            f += FloatMath.pow(10.123f, 10.123f);
        }
        return f;
    }

    public float timeFloatMathSin(int reps) {
        // Keep an answer so we don't optimize the method call away.
        float f = 0.0f;
        for (int i = 0; i < reps; i++) {
            f += FloatMath.sin(100.123f);
        }
        return f;
    }

    public float timeFloatMathSqrt(int reps) {
        // Keep an answer so we don't optimize the method call away.
        float f = 0.0f;
        for (int i = 0; i < reps; i++) {
            f += FloatMath.sqrt(100.123f);
        }
        return f;
    }

    public float timeFloatMathSqrt_math(int reps) {
        // Keep an answer so we don't optimize the method call away.
        float f = 0.0f;
        for (int i = 0; i < reps; i++) {
            f += (float) Math.sqrt(100.123f);
        }
        return f;
    }
}
