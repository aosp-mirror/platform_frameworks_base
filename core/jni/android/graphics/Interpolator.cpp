#include "jni.h"
#include <android_runtime/AndroidRuntime.h>

#include "GraphicsJNI.h"
#include "SkInterpolator.h"
#include "SkTemplates.h"

static SkInterpolator* Interpolator_constructor(JNIEnv* env, jobject clazz, int valueCount, int frameCount)
{
    return new SkInterpolator(valueCount, frameCount);
}

static void Interpolator_destructor(JNIEnv* env, jobject clazz, SkInterpolator* interp)
{
    delete interp;
}

static void Interpolator_reset(JNIEnv* env, jobject clazz, SkInterpolator* interp, int valueCount, int frameCount)
{
    interp->reset(valueCount, frameCount);
}

static void Interpolator_setKeyFrame(JNIEnv* env, jobject clazz, SkInterpolator* interp, int index, int msec, jfloatArray valueArray, jfloatArray blendArray)
{
    SkScalar    blendStorage[4];
    SkScalar*   blend = NULL;

    AutoJavaFloatArray  autoValues(env, valueArray);
    float* values = autoValues.ptr();
    int i, n = autoValues.length();

    SkAutoSTMalloc<16, SkScalar>  storage(n);
    SkScalar*                     scalars = storage.get();

    for (i = 0; i < n; i++)
        scalars[i] = SkFloatToScalar(values[i]);

    if (blendArray != NULL) {
        AutoJavaFloatArray autoBlend(env, blendArray, 4);
        values = autoBlend.ptr();
        for (i = 0; i < 4; i++)
            blendStorage[i] = SkFloatToScalar(values[i]);
        blend = blendStorage;
    }

    interp->setKeyFrame(index, msec, scalars, blend);
}

static void Interpolator_setRepeatMirror(JNIEnv* env, jobject clazz, SkInterpolator* interp, float repeatCount, jboolean mirror)
{
    if (repeatCount > 32000)
        repeatCount = 32000;

    interp->setRepeatCount(SkFloatToScalar(repeatCount));
    interp->setMirror(mirror != 0);
}

static int Interpolator_timeToValues(JNIEnv* env, jobject clazz, SkInterpolator* interp, int msec, jfloatArray valueArray)
{
    SkInterpolatorBase::Result result;

    float* values = valueArray ? env->GetFloatArrayElements(valueArray, NULL) : NULL;
    result = interp->timeToValues(msec, (SkScalar*)values);
    
    if (valueArray) {
        int n = env->GetArrayLength(valueArray);
        for (int i = 0; i < n; i++) {
            values[i] = SkScalarToFloat(*(SkScalar*)&values[i]);
        }
        env->ReleaseFloatArrayElements(valueArray, values, 0);
    }
    
    return result;
}

// ----------------------------------------------------------------------------

/*
 * JNI registration.
 */
static JNINativeMethod gInterpolatorMethods[] = {
    { "nativeConstructor",      "(II)I",        (void*)Interpolator_constructor     },
    { "nativeDestructor",       "(I)V",         (void*)Interpolator_destructor      },
    { "nativeReset",            "(III)V",       (void*)Interpolator_reset           },
    { "nativeSetKeyFrame",      "(III[F[F)V",   (void*)Interpolator_setKeyFrame     },
    { "nativeSetRepeatMirror",  "(IFZ)V",       (void*)Interpolator_setRepeatMirror },
    { "nativeTimeToValues",     "(II[F)I",      (void*)Interpolator_timeToValues    }
};

int register_android_graphics_Interpolator(JNIEnv* env);
int register_android_graphics_Interpolator(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env,
                                                       "android/graphics/Interpolator",
                                                       gInterpolatorMethods,
                                                       SK_ARRAY_COUNT(gInterpolatorMethods));
}
