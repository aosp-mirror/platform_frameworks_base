#include "GraphicsJNI.h"
#include "Sk1DPathEffect.h"
#include "SkCornerPathEffect.h"
#include "SkDashPathEffect.h"
#include "SkDiscretePathEffect.h"
#include "SkPathEffect.h"
#include "core_jni_helpers.h"

#include <jni.h>

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
        SkPathEffect* effect = SkPathEffect::MakeCompose(sk_ref_sp(outer),
                sk_ref_sp(inner)).release();
        return reinterpret_cast<jlong>(effect);
    }

    static jlong Sum_constructor(JNIEnv* env, jobject,
                                 jlong firstHandle, jlong secondHandle) {
        SkPathEffect* first = reinterpret_cast<SkPathEffect*>(firstHandle);
        SkPathEffect* second = reinterpret_cast<SkPathEffect*>(secondHandle);
        SkPathEffect* effect = SkPathEffect::MakeSum(sk_ref_sp(first),
                sk_ref_sp(second)).release();
        return reinterpret_cast<jlong>(effect);
    }

    static jlong Dash_constructor(JNIEnv* env, jobject,
                                      jfloatArray intervalArray, jfloat phase) {
        AutoJavaFloatArray autoInterval(env, intervalArray);
        int         count = autoInterval.length() & ~1;  // even number
#ifdef SK_SCALAR_IS_FLOAT
        SkScalar*   intervals = autoInterval.ptr();
#else
        #error Need to convert float array to SkScalar array before calling the following function.
#endif
        SkPathEffect* effect = SkDashPathEffect::Make(intervals, count, phase).release();
        return reinterpret_cast<jlong>(effect);
    }

    static jlong OneD_constructor(JNIEnv* env, jobject,
                  jlong shapeHandle, jfloat advance, jfloat phase, jint style) {
        const SkPath* shape = reinterpret_cast<SkPath*>(shapeHandle);
        SkASSERT(shape != NULL);
        SkPathEffect* effect = SkPath1DPathEffect::Make(*shape, advance, phase,
                (SkPath1DPathEffect::Style)style).release();
        return reinterpret_cast<jlong>(effect);
    }

    static jlong Corner_constructor(JNIEnv* env, jobject, jfloat radius){
        SkPathEffect* effect = SkCornerPathEffect::Make(radius).release();
        return reinterpret_cast<jlong>(effect);
    }

    static jlong Discrete_constructor(JNIEnv* env, jobject,
                                      jfloat length, jfloat deviation) {
        SkPathEffect* effect = SkDiscretePathEffect::Make(length, deviation).release();
        return reinterpret_cast<jlong>(effect);
    }

};

////////////////////////////////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gPathEffectMethods[] = {
    { "nativeDestructor", "(J)V", (void*)SkPathEffectGlue::destructor }
};

static const JNINativeMethod gComposePathEffectMethods[] = {
    { "nativeCreate", "(JJ)J", (void*)SkPathEffectGlue::Compose_constructor }
};

static const JNINativeMethod gSumPathEffectMethods[] = {
    { "nativeCreate", "(JJ)J", (void*)SkPathEffectGlue::Sum_constructor }
};

static const JNINativeMethod gDashPathEffectMethods[] = {
    { "nativeCreate", "([FF)J", (void*)SkPathEffectGlue::Dash_constructor }
};

static const JNINativeMethod gPathDashPathEffectMethods[] = {
    { "nativeCreate", "(JFFI)J", (void*)SkPathEffectGlue::OneD_constructor }
};

static const JNINativeMethod gCornerPathEffectMethods[] = {
    { "nativeCreate", "(F)J", (void*)SkPathEffectGlue::Corner_constructor }
};

static const JNINativeMethod gDiscretePathEffectMethods[] = {
    { "nativeCreate", "(FF)J", (void*)SkPathEffectGlue::Discrete_constructor }
};

int register_android_graphics_PathEffect(JNIEnv* env)
{
    android::RegisterMethodsOrDie(env, "android/graphics/PathEffect", gPathEffectMethods,
                         NELEM(gPathEffectMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/ComposePathEffect",
                                  gComposePathEffectMethods, NELEM(gComposePathEffectMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/SumPathEffect", gSumPathEffectMethods,
                                  NELEM(gSumPathEffectMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/DashPathEffect", gDashPathEffectMethods,
                                  NELEM(gDashPathEffectMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/PathDashPathEffect",
                                  gPathDashPathEffectMethods, NELEM(gPathDashPathEffectMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/CornerPathEffect",
                                  gCornerPathEffectMethods, NELEM(gCornerPathEffectMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/DiscretePathEffect",
                                  gDiscretePathEffectMethods, NELEM(gDiscretePathEffectMethods));

    return 0;
}
