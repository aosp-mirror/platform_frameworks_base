#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <math.h>
#include <float.h>
#include "SkTypes.h"

class MathUtilsGlue {
public:
    static float FloorF(JNIEnv* env, jobject clazz, float x) {
        return floorf(x);
    }
    
    static float CeilF(JNIEnv* env, jobject clazz, float x) {
        return ceilf(x);
    }
    
    static float SinF(JNIEnv* env, jobject clazz, float x) {
        return sinf(x);
    }
    
    static float CosF(JNIEnv* env, jobject clazz, float x) {
        return cosf(x);
    }
    
    static float SqrtF(JNIEnv* env, jobject clazz, float x) {
        return sqrtf(x);
    }

    static float ExpF(JNIEnv* env, jobject clazz, float x) {
        return expf(x);
    }

    static float PowF(JNIEnv* env, jobject clazz, float x, float y) {
        return powf(x, y);
    }

    static float HypotF(JNIEnv* env, jobject clazz, float x, float y) {
        return hypotf(x, y);
    }
};

static JNINativeMethod gMathUtilsMethods[] = {
    {"floor", "(F)F", (void*) MathUtilsGlue::FloorF},
    {"ceil", "(F)F", (void*) MathUtilsGlue::CeilF},
    {"sin", "(F)F", (void*) MathUtilsGlue::SinF},
    {"cos", "(F)F", (void*) MathUtilsGlue::CosF},
    {"sqrt", "(F)F", (void*) MathUtilsGlue::SqrtF},
    {"exp", "(F)F", (void*) MathUtilsGlue::ExpF},
    {"pow", "(FF)F", (void*) MathUtilsGlue::PowF},
    {"hypot", "(FF)F", (void*) MathUtilsGlue::HypotF},
};

int register_android_util_FloatMath(JNIEnv* env)
{
    int result = android::AndroidRuntime::registerNativeMethods(env,
                                            "android/util/FloatMath",
                                            gMathUtilsMethods,
                                            SK_ARRAY_COUNT(gMathUtilsMethods));
    return result;
}

