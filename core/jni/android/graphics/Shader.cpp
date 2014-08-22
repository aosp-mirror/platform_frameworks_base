#include <jni.h>
#include "GraphicsJNI.h"

#include "SkShader.h"
#include "SkGradientShader.h"
#include "SkPorterDuff.h"
#include "SkComposeShader.h"
#include "SkTemplates.h"
#include "SkXfermode.h"

#include <SkiaShader.h>
#include <Caches.h>

using namespace android::uirenderer;

static struct {
    jclass clazz;
    jfieldID shader;
} gShaderClassInfo;

static void ThrowIAE_IfNull(JNIEnv* env, void* ptr) {
    if (NULL == ptr) {
        doThrowIAE(env);
    }
}

static void Color_RGBToHSV(JNIEnv* env, jobject, jint red, jint green, jint blue, jfloatArray hsvArray)
{
    SkScalar hsv[3];
    SkRGBToHSV(red, green, blue, hsv);

    AutoJavaFloatArray  autoHSV(env, hsvArray, 3);
    float* values = autoHSV.ptr();
    for (int i = 0; i < 3; i++) {
        values[i] = SkScalarToFloat(hsv[i]);
    }
}

static jint Color_HSVToColor(JNIEnv* env, jobject, jint alpha, jfloatArray hsvArray)
{
    AutoJavaFloatArray  autoHSV(env, hsvArray, 3);
#ifdef SK_SCALAR_IS_FLOAT
    SkScalar*   hsv = autoHSV.ptr();
#else
    #error Need to convert float array to SkScalar array before calling the following function.
#endif

    return static_cast<jint>(SkHSVToColor(alpha, hsv));
}

///////////////////////////////////////////////////////////////////////////////////////////////

static void Shader_destructor(JNIEnv* env, jobject o, jlong shaderHandle, jlong shaderWithLMHandle)
{
    SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);
    SkSafeUnref(shader);
}

static void Shader_setLocalMatrix(JNIEnv* env, jobject o, jlong shaderHandle, jlong matrixHandle)
{
    SkShader* shader       = reinterpret_cast<SkShader*>(shaderHandle);
    const SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    if (shader) {
        if (matrix) {
            shader->setLocalMatrix(*matrix);
        } else {
            shader->resetLocalMatrix();
        }
    }
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong BitmapShader_constructor(JNIEnv* env, jobject o, jlong bitmapHandle,
                                      jint tileModeX, jint tileModeY)
{
    const SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkShader* s = SkShader::CreateBitmapShader(*bitmap,
                                        (SkShader::TileMode)tileModeX,
                                        (SkShader::TileMode)tileModeY);

    ThrowIAE_IfNull(env, s);
    return reinterpret_cast<jlong>(s);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong LinearGradient_create1(JNIEnv* env, jobject o,
                                    jfloat x0, jfloat y0, jfloat x1, jfloat y1,
                                    jintArray colorArray, jfloatArray posArray, jint tileMode)
{
    SkPoint pts[2];
    pts[0].set(x0, y0);
    pts[1].set(x1, y1);

    size_t count = env->GetArrayLength(colorArray);
    const jint* colorValues = env->GetIntArrayElements(colorArray, NULL);

    AutoJavaFloatArray autoPos(env, posArray, count);
#ifdef SK_SCALAR_IS_FLOAT
    SkScalar* pos = autoPos.ptr();
#else
    #error Need to convert float array to SkScalar array before calling the following function.
#endif

    SkShader* shader = SkGradientShader::CreateLinear(pts,
            reinterpret_cast<const SkColor*>(colorValues), pos, count,
            static_cast<SkShader::TileMode>(tileMode));

    env->ReleaseIntArrayElements(colorArray, const_cast<jint*>(colorValues), JNI_ABORT);
    ThrowIAE_IfNull(env, shader);
    return reinterpret_cast<jlong>(shader);
}

static jlong LinearGradient_create2(JNIEnv* env, jobject o,
                                    jfloat x0, jfloat y0, jfloat x1, jfloat y1,
                                    jint color0, jint color1, jint tileMode)
{
    SkPoint pts[2];
    pts[0].set(x0, y0);
    pts[1].set(x1, y1);

    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;

    SkShader* s = SkGradientShader::CreateLinear(pts, colors, NULL, 2, (SkShader::TileMode)tileMode);

    ThrowIAE_IfNull(env, s);
    return reinterpret_cast<jlong>(s);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong RadialGradient_create1(JNIEnv* env, jobject, jfloat x, jfloat y, jfloat radius,
        jintArray colorArray, jfloatArray posArray, jint tileMode) {
    SkPoint center;
    center.set(x, y);

    size_t      count = env->GetArrayLength(colorArray);
    const jint* colorValues = env->GetIntArrayElements(colorArray, NULL);

    AutoJavaFloatArray autoPos(env, posArray, count);
#ifdef SK_SCALAR_IS_FLOAT
    SkScalar* pos = autoPos.ptr();
#else
    #error Need to convert float array to SkScalar array before calling the following function.
#endif

    SkShader* shader = SkGradientShader::CreateRadial(center, radius,
            reinterpret_cast<const SkColor*>(colorValues), pos, count,
            static_cast<SkShader::TileMode>(tileMode));
    env->ReleaseIntArrayElements(colorArray, const_cast<jint*>(colorValues),
                                 JNI_ABORT);

    ThrowIAE_IfNull(env, shader);
    return reinterpret_cast<jlong>(shader);
}

static jlong RadialGradient_create2(JNIEnv* env, jobject, jfloat x, jfloat y, jfloat radius,
        jint color0, jint color1, jint tileMode) {
    SkPoint center;
    center.set(x, y);

    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;

    SkShader* s = SkGradientShader::CreateRadial(center, radius, colors, NULL, 2,
            (SkShader::TileMode)tileMode);
    ThrowIAE_IfNull(env, s);
    return reinterpret_cast<jlong>(s);
}

///////////////////////////////////////////////////////////////////////////////

static jlong SweepGradient_create1(JNIEnv* env, jobject, jfloat x, jfloat y,
        jintArray jcolors, jfloatArray jpositions) {
    size_t      count = env->GetArrayLength(jcolors);
    const jint* colors = env->GetIntArrayElements(jcolors, NULL);

    AutoJavaFloatArray autoPos(env, jpositions, count);
#ifdef SK_SCALAR_IS_FLOAT
    SkScalar* pos = autoPos.ptr();
#else
    #error Need to convert float array to SkScalar array before calling the following function.
#endif

    SkShader* shader = SkGradientShader::CreateSweep(x, y,
            reinterpret_cast<const SkColor*>(colors), pos, count);
    env->ReleaseIntArrayElements(jcolors, const_cast<jint*>(colors),
                                 JNI_ABORT);
    ThrowIAE_IfNull(env, shader);
    return reinterpret_cast<jlong>(shader);
}

static jlong SweepGradient_create2(JNIEnv* env, jobject, jfloat x, jfloat y,
        int color0, int color1) {
    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;
    SkShader* s = SkGradientShader::CreateSweep(x, y, colors, NULL, 2);
    ThrowIAE_IfNull(env, s);
    return reinterpret_cast<jlong>(s);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong ComposeShader_create1(JNIEnv* env, jobject o,
        jlong shaderAHandle, jlong shaderBHandle, jlong modeHandle)
{
    SkShader* shaderA = reinterpret_cast<SkShader *>(shaderAHandle);
    SkShader* shaderB = reinterpret_cast<SkShader *>(shaderBHandle);
    SkXfermode* mode = reinterpret_cast<SkXfermode *>(modeHandle);
    SkShader* shader = new SkComposeShader(shaderA, shaderB, mode);
    return reinterpret_cast<jlong>(shader);
}

static jlong ComposeShader_create2(JNIEnv* env, jobject o,
        jlong shaderAHandle, jlong shaderBHandle, jint porterDuffModeHandle)
{
    SkShader* shaderA = reinterpret_cast<SkShader *>(shaderAHandle);
    SkShader* shaderB = reinterpret_cast<SkShader *>(shaderBHandle);
    SkPorterDuff::Mode porterDuffMode = static_cast<SkPorterDuff::Mode>(porterDuffModeHandle);
    SkAutoUnref au(SkPorterDuff::CreateXfermode(porterDuffMode));
    SkXfermode* mode = (SkXfermode*) au.get();
    SkShader* shader = new SkComposeShader(shaderA, shaderB, mode);
    return reinterpret_cast<jlong>(shader);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gColorMethods[] = {
    { "nativeRGBToHSV",     "(III[F)V", (void*)Color_RGBToHSV   },
    { "nativeHSVToColor",   "(I[F)I",   (void*)Color_HSVToColor }
};

static JNINativeMethod gShaderMethods[] = {
    { "nativeDestructor",        "(J)V",    (void*)Shader_destructor        },
    { "nativeSetLocalMatrix",    "(JJ)V",   (void*)Shader_setLocalMatrix    }
};

static JNINativeMethod gBitmapShaderMethods[] = {
    { "nativeCreate",     "(JII)J",  (void*)BitmapShader_constructor },
};

static JNINativeMethod gLinearGradientMethods[] = {
    { "nativeCreate1",     "(FFFF[I[FI)J",  (void*)LinearGradient_create1     },
    { "nativeCreate2",     "(FFFFIII)J",    (void*)LinearGradient_create2     },
};

static JNINativeMethod gRadialGradientMethods[] = {
    { "nativeCreate1",     "(FFF[I[FI)J",  (void*)RadialGradient_create1     },
    { "nativeCreate2",     "(FFFIII)J",    (void*)RadialGradient_create2     },
};

static JNINativeMethod gSweepGradientMethods[] = {
    { "nativeCreate1",     "(FF[I[F)J",  (void*)SweepGradient_create1     },
    { "nativeCreate2",     "(FFII)J",    (void*)SweepGradient_create2     },
};

static JNINativeMethod gComposeShaderMethods[] = {
    { "nativeCreate1",      "(JJJ)J",   (void*)ComposeShader_create1     },
    { "nativeCreate2",      "(JJI)J",   (void*)ComposeShader_create2     },
};

#include <android_runtime/AndroidRuntime.h>

#define REG(env, name, array)                                                                       \
    result = android::AndroidRuntime::registerNativeMethods(env, name, array, SK_ARRAY_COUNT(array));  \
    if (result < 0) return result

int register_android_graphics_Shader(JNIEnv* env)
{
    int result;

    REG(env, "android/graphics/Color", gColorMethods);
    REG(env, "android/graphics/Shader", gShaderMethods);
    REG(env, "android/graphics/BitmapShader", gBitmapShaderMethods);
    REG(env, "android/graphics/LinearGradient", gLinearGradientMethods);
    REG(env, "android/graphics/RadialGradient", gRadialGradientMethods);
    REG(env, "android/graphics/SweepGradient", gSweepGradientMethods);
    REG(env, "android/graphics/ComposeShader", gComposeShaderMethods);

    return result;
}
