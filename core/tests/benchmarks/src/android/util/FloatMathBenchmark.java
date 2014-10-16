package android.util;

import com.google.caliper.Param;
import com.google.caliper.Runner;
import com.google.caliper.SimpleBenchmark;

import android.util.FloatMath;

import dalvik.system.VMDebug;

public class FloatMathBenchmark extends SimpleBenchmark {

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
