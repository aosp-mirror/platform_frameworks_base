#include "GraphicsJNI.h"
#include "SkGradientShader.h"
#include "SkShader.h"
#include "SkXfermode.h"
#include "core_jni_helpers.h"

#include <Caches.h>
#include <jni.h>

using namespace android::uirenderer;

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

static jlong Shader_setLocalMatrix(JNIEnv* env, jobject o, jlong shaderHandle, jlong matrixHandle)
{
    // ensure we have a valid matrix to use
    const SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    if (NULL == matrix) {
        matrix = &SkMatrix::I();
    }

    // The current shader will no longer need a direct reference owned by Shader.java
    // as all the data needed is contained within the newly created LocalMatrixShader.
    SkASSERT(shaderHandle);
    SkAutoTUnref<SkShader> currentShader(reinterpret_cast<SkShader*>(shaderHandle));

    // Attempt to peel off an existing proxy shader and get the proxy's matrix. If
    // the proxy existed and it's matrix equals the desired matrix then just return
    // the proxy, otherwise replace it with a new proxy containing the desired matrix.
    //
    // refAsALocalMatrixShader(): if the shader contains a proxy then it unwraps the proxy
    //                            returning both the underlying shader and the proxy's matrix.
    // newWithLocalMatrix(): will return a proxy shader that wraps the provided shader and
    //                       concats the provided local matrix with the shader's matrix.
    //
    // WARNING: This proxy replacement only behaves like a setter because the Java
    //          API enforces that all local matrices are set using this call and
    //          not passed to the constructor of the Shader.
    SkMatrix proxyMatrix;
    SkAutoTUnref<SkShader> baseShader(currentShader->refAsALocalMatrixShader(&proxyMatrix));
    if (baseShader.get()) {
        if (proxyMatrix == *matrix) {
            return reinterpret_cast<jlong>(currentShader.detach());
        }
        return reinterpret_cast<jlong>(baseShader->newWithLocalMatrix(*matrix));
    }
    return reinterpret_cast<jlong>(currentShader->newWithLocalMatrix(*matrix));
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong BitmapShader_constructor(JNIEnv* env, jobject o, jobject jbitmap,
                                      jint tileModeX, jint tileModeY)
{
    SkBitmap bitmap;
    if (jbitmap) {
        // Only pass a valid SkBitmap object to the constructor if the Bitmap exists. Otherwise,
        // we'll pass an empty SkBitmap to avoid crashing/excepting for compatibility.
        GraphicsJNI::getSkBitmap(env, jbitmap, &bitmap);
    }
    SkShader* s = SkShader::CreateBitmapShader(bitmap,
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
    SkShader* shader = SkShader::CreateComposeShader(shaderA, shaderB, mode);
    return reinterpret_cast<jlong>(shader);
}

static jlong ComposeShader_create2(JNIEnv* env, jobject o,
        jlong shaderAHandle, jlong shaderBHandle, jint xfermodeHandle)
{
    SkShader* shaderA = reinterpret_cast<SkShader *>(shaderAHandle);
    SkShader* shaderB = reinterpret_cast<SkShader *>(shaderBHandle);
    SkXfermode::Mode mode = static_cast<SkXfermode::Mode>(xfermodeHandle);
    SkAutoTUnref<SkXfermode> xfermode(SkXfermode::Create(mode));
    SkShader* shader = SkShader::CreateComposeShader(shaderA, shaderB, xfermode.get());
    return reinterpret_cast<jlong>(shader);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gColorMethods[] = {
    { "nativeRGBToHSV",     "(III[F)V", (void*)Color_RGBToHSV   },
    { "nativeHSVToColor",   "(I[F)I",   (void*)Color_HSVToColor }
};

static const JNINativeMethod gShaderMethods[] = {
    { "nativeDestructor",        "(J)V",    (void*)Shader_destructor        },
    { "nativeSetLocalMatrix",    "(JJ)J",   (void*)Shader_setLocalMatrix    }
};

static const JNINativeMethod gBitmapShaderMethods[] = {
    { "nativeCreate",     "(Landroid/graphics/Bitmap;II)J",  (void*)BitmapShader_constructor },
};

static const JNINativeMethod gLinearGradientMethods[] = {
    { "nativeCreate1",     "(FFFF[I[FI)J",  (void*)LinearGradient_create1     },
    { "nativeCreate2",     "(FFFFIII)J",    (void*)LinearGradient_create2     },
};

static const JNINativeMethod gRadialGradientMethods[] = {
    { "nativeCreate1",     "(FFF[I[FI)J",  (void*)RadialGradient_create1     },
    { "nativeCreate2",     "(FFFIII)J",    (void*)RadialGradient_create2     },
};

static const JNINativeMethod gSweepGradientMethods[] = {
    { "nativeCreate1",     "(FF[I[F)J",  (void*)SweepGradient_create1     },
    { "nativeCreate2",     "(FFII)J",    (void*)SweepGradient_create2     },
};

static const JNINativeMethod gComposeShaderMethods[] = {
    { "nativeCreate1",      "(JJJ)J",   (void*)ComposeShader_create1     },
    { "nativeCreate2",      "(JJI)J",   (void*)ComposeShader_create2     },
};

int register_android_graphics_Shader(JNIEnv* env)
{
    android::RegisterMethodsOrDie(env, "android/graphics/Color", gColorMethods,
                                  NELEM(gColorMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/Shader", gShaderMethods,
                                  NELEM(gShaderMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/BitmapShader", gBitmapShaderMethods,
                                  NELEM(gBitmapShaderMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/LinearGradient", gLinearGradientMethods,
                                  NELEM(gLinearGradientMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/RadialGradient", gRadialGradientMethods,
                                  NELEM(gRadialGradientMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/SweepGradient", gSweepGradientMethods,
                                  NELEM(gSweepGradientMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/ComposeShader", gComposeShaderMethods,
                                  NELEM(gComposeShaderMethods));

    return 0;
}
