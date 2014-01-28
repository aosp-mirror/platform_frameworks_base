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

    static void destructor(JNIEnv* env, jobject, jlong effectHandle) {
        SkPathEffect* effect = reinterpret_cast<SkPathEffect*>(effectHandle);
        SkSafeUnref(effect);
    }

    static jlong Compose_constructor(JNIEnv* env, jobject,
                                     jlong outerHandle, jlong innerHandle) {
        SkPathEffect* outer = reinterpret_cast<SkPathEffect*>(outerHandle);
        SkPathEffect* inner = reinterpret_cast<SkPathEffect*>(innerHandle);
        SkPathEffect* effect = new SkComposePathEffect(outer, inner);
        return reinterpret_cast<jlong>(effect);
    }

    static jlong Sum_constructor(JNIEnv* env, jobject,
                                 jlong firstHandle, jlong secondHandle) {
        SkPathEffect* first = reinterpret_cast<SkPathEffect*>(firstHandle);
        SkPathEffect* second = reinterpret_cast<SkPathEffect*>(secondHandle);
        SkPathEffect* effect = new SkSumPathEffect(first, second);
        return reinterpret_cast<jlong>(effect);
    }

    static jlong Dash_constructor(JNIEnv* env, jobject,
                                      jfloatArray intervalArray, jfloat phase) {
        AutoJavaFloatArray autoInterval(env, intervalArray);
        int     count = autoInterval.length() & ~1;  // even number
        float*  values = autoInterval.ptr();

        SkAutoSTMalloc<32, SkScalar>    storage(count);
        SkScalar*                       intervals = storage.get();
        for (int i = 0; i < count; i++) {
            intervals[i] = SkFloatToScalar(values[i]);
        }
        SkPathEffect* effect = new SkDashPathEffect(intervals, count, SkFloatToScalar(phase));
        return reinterpret_cast<jlong>(effect);
    }

    static jlong OneD_constructor(JNIEnv* env, jobject,
                  jlong shapeHandle, jfloat advance, jfloat phase, jint style) {
        const SkPath* shape = reinterpret_cast<SkPath*>(shapeHandle);
        SkASSERT(shape != NULL);
        SkPathEffect* effect = new SkPath1DPathEffect(*shape, SkFloatToScalar(advance),
                     SkFloatToScalar(phase), (SkPath1DPathEffect::Style)style);
        return reinterpret_cast<jlong>(effect);
    }

    static jlong Corner_constructor(JNIEnv* env, jobject, jfloat radius){
        SkPathEffect* effect = new SkCornerPathEffect(SkFloatToScalar(radius));
        return reinterpret_cast<jlong>(effect);
    }

    static jlong Discrete_constructor(JNIEnv* env, jobject,
                                      jfloat length, jfloat deviation) {
        SkPathEffect* effect = new SkDiscretePathEffect(SkFloatToScalar(length),
                                        SkFloatToScalar(deviation));
        return reinterpret_cast<jlong>(effect);
    }

};

////////////////////////////////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gPathEffectMethods[] = {
    { "nativeDestructor", "(J)V", (void*)SkPathEffectGlue::destructor }
};

static JNINativeMethod gComposePathEffectMethods[] = {
    { "nativeCreate", "(JJ)J", (void*)SkPathEffectGlue::Compose_constructor }
};

static JNINativeMethod gSumPathEffectMethods[] = {
    { "nativeCreate", "(JJ)J", (void*)SkPathEffectGlue::Sum_constructor }
};

static JNINativeMethod gDashPathEffectMethods[] = {
    { "nativeCreate", "([FF)J", (void*)SkPathEffectGlue::Dash_constructor }
};

static JNINativeMethod gPathDashPathEffectMethods[] = {
    { "nativeCreate", "(JFFI)J", (void*)SkPathEffectGlue::OneD_constructor }
};

static JNINativeMethod gCornerPathEffectMethods[] = {
    { "nativeCreate", "(F)J", (void*)SkPathEffectGlue::Corner_constructor }
};

static JNINativeMethod gDiscretePathEffectMethods[] = {
    { "nativeCreate", "(FF)J", (void*)SkPathEffectGlue::Discrete_constructor }
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
