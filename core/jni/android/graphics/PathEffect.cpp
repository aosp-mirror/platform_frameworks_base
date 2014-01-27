#include <jni.h>
#include "GraphicsJNI.h"

#include "SkPathEffect.h"
#include "SkCornerPathEffect.h"
#include "SkDashPathEffect.h"
#include "SkDiscretePathEffect.h"
#include "Sk1DPathEffect.h"
#include "SkTemplates.h"

class SkPathEffectGlue {
public:

    static void destructor(JNIEnv* env, jobject, SkPathEffect* effect) {
        SkSafeUnref(effect);
    }

    static SkPathEffect* Compose_constructor(JNIEnv* env, jobject,
                                   SkPathEffect* outer, SkPathEffect* inner) {
        return new SkComposePathEffect(outer, inner);
    }

    static SkPathEffect* Sum_constructor(JNIEnv* env, jobject,
                                  SkPathEffect* first, SkPathEffect* second) {
        return new SkSumPathEffect(first, second);
    }

    static SkPathEffect* Dash_constructor(JNIEnv* env, jobject,
                                      jfloatArray intervalArray, float phase) {
        AutoJavaFloatArray autoInterval(env, intervalArray);
        int     count = autoInterval.length() & ~1;  // even number
        float*  values = autoInterval.ptr();

        SkAutoSTMalloc<32, SkScalar>    storage(count);
        SkScalar*                       intervals = storage.get();
        for (int i = 0; i < count; i++) {
            intervals[i] = SkFloatToScalar(values[i]);
        }
        return new SkDashPathEffect(intervals, count, SkFloatToScalar(phase));
    }

    static SkPathEffect* OneD_constructor(JNIEnv* env, jobject,
                  const SkPath* shape, float advance, float phase, int style) {
        SkASSERT(shape != NULL);
        return new SkPath1DPathEffect(*shape, SkFloatToScalar(advance),
                     SkFloatToScalar(phase), (SkPath1DPathEffect::Style)style);
    }

    static SkPathEffect* Corner_constructor(JNIEnv* env, jobject, float radius){
        return new SkCornerPathEffect(SkFloatToScalar(radius));
    }

    static SkPathEffect* Discrete_constructor(JNIEnv* env, jobject,
                                              float length, float deviation) {
        return new SkDiscretePathEffect(SkFloatToScalar(length),
                                        SkFloatToScalar(deviation));
    }

};

////////////////////////////////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gPathEffectMethods[] = {
    { "nativeDestructor", "(I)V", (void*)SkPathEffectGlue::destructor }
};

static JNINativeMethod gComposePathEffectMethods[] = {
    { "nativeCreate", "(II)I", (void*)SkPathEffectGlue::Compose_constructor }
};

static JNINativeMethod gSumPathEffectMethods[] = {
    { "nativeCreate", "(II)I", (void*)SkPathEffectGlue::Sum_constructor }
};

static JNINativeMethod gDashPathEffectMethods[] = {
    { "nativeCreate", "([FF)I", (void*)SkPathEffectGlue::Dash_constructor }
};

static JNINativeMethod gPathDashPathEffectMethods[] = {
    { "nativeCreate", "(IFFI)I", (void*)SkPathEffectGlue::OneD_constructor }
};

static JNINativeMethod gCornerPathEffectMethods[] = {
    { "nativeCreate", "(F)I", (void*)SkPathEffectGlue::Corner_constructor }
};

static JNINativeMethod gDiscretePathEffectMethods[] = {
    { "nativeCreate", "(FF)I", (void*)SkPathEffectGlue::Discrete_constructor }
};

#include <android_runtime/AndroidRuntime.h>

#define REG(env, name, array)                                              \
    result = android::AndroidRuntime::registerNativeMethods(env, name, array, \
                                                  SK_ARRAY_COUNT(array));  \
    if (result < 0) return result

int register_android_graphics_PathEffect(JNIEnv* env)
{
    int result;

    REG(env, "android/graphics/PathEffect", gPathEffectMethods);
    REG(env, "android/graphics/ComposePathEffect", gComposePathEffectMethods);
    REG(env, "android/graphics/SumPathEffect", gSumPathEffectMethods);
    REG(env, "android/graphics/DashPathEffect", gDashPathEffectMethods);
    REG(env, "android/graphics/PathDashPathEffect", gPathDashPathEffectMethods);
    REG(env, "android/graphics/CornerPathEffect", gCornerPathEffectMethods);
    REG(env, "android/graphics/DiscretePathEffect", gDiscretePathEffectMethods);

    return 0;
}
