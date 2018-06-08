#include "GraphicsJNI.h"
#include "SkColorFilter.h"
#include "SkGradientShader.h"
#include "SkImagePriv.h"
#include "SkShader.h"
#include "SkBlendMode.h"
#include "core_jni_helpers.h"

#include <Caches.h>
#include <jni.h>

using namespace android::uirenderer;

/**
 * By default Skia gradients will interpolate their colors in unpremul space
 * and then premultiply each of the results. We must set this flag to preserve
 * backwards compatiblity by premultiplying the colors of the gradient first,
 * and then interpolating between them.
 */
static const uint32_t sGradientShaderFlags = SkGradientShader::kInterpolateColorsInPremul_Flag;

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

static void Shader_safeUnref(SkShader* shader) {
    SkSafeUnref(shader);
}

static jlong Shader_getNativeFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&Shader_safeUnref));
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong BitmapShader_constructor(JNIEnv* env, jobject o, jlong matrixPtr, jobject jbitmap,
        jint tileModeX, jint tileModeY) {
    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    sk_sp<SkImage> image;
    sk_sp<SkColorFilter> colorFilter;
    if (jbitmap) {
        // Only pass a valid SkBitmap object to the constructor if the Bitmap exists. Otherwise,
        // we'll pass an empty SkBitmap to avoid crashing/excepting for compatibility.
        image = android::bitmap::toBitmap(env, jbitmap).makeImage(&colorFilter);
    }

    if (!image.get()) {
        SkBitmap bitmap;
        image = SkMakeImageFromRasterBitmap(bitmap, kNever_SkCopyPixelsMode);
    }
    sk_sp<SkShader> shader = image->makeShader(
            (SkShader::TileMode)tileModeX, (SkShader::TileMode)tileModeY);

    if (matrix) {
        shader = shader->makeWithLocalMatrix(*matrix);
    }
    if(colorFilter) {
        shader = shader->makeWithColorFilter(colorFilter);
    }

    ThrowIAE_IfNull(env, shader.get());
    return reinterpret_cast<jlong>(shader.release());
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong LinearGradient_create1(JNIEnv* env, jobject o, jlong matrixPtr,
        jfloat x0, jfloat y0, jfloat x1, jfloat y1,
        jintArray colorArray, jfloatArray posArray, jint tileMode) {
    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
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

    sk_sp<SkShader> baseShader(SkGradientShader::MakeLinear(pts,
            reinterpret_cast<const SkColor*>(colorValues), pos, count,
            static_cast<SkShader::TileMode>(tileMode), sGradientShaderFlags, NULL));

    SkShader* shader;
    if (matrix) {
        shader = baseShader->makeWithLocalMatrix(*matrix).release();
    } else {
        shader = baseShader.release();
    }

    env->ReleaseIntArrayElements(colorArray, const_cast<jint*>(colorValues), JNI_ABORT);
    ThrowIAE_IfNull(env, shader);
    return reinterpret_cast<jlong>(shader);
}

static jlong LinearGradient_create2(JNIEnv* env, jobject o, jlong matrixPtr,
        jfloat x0, jfloat y0, jfloat x1, jfloat y1, jint color0, jint color1, jint tileMode) {
    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);

    SkPoint pts[2];
    pts[0].set(x0, y0);
    pts[1].set(x1, y1);

    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;

    sk_sp<SkShader> baseShader(SkGradientShader::MakeLinear(pts, colors, NULL, 2,
            static_cast<SkShader::TileMode>(tileMode), sGradientShaderFlags, NULL));

    SkShader* s;
    if (matrix) {
        s = baseShader->makeWithLocalMatrix(*matrix).release();
    } else {
        s = baseShader.release();
    }

    ThrowIAE_IfNull(env, s);
    return reinterpret_cast<jlong>(s);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong RadialGradient_create1(JNIEnv* env, jobject, jlong matrixPtr, jfloat x, jfloat y,
        jfloat radius, jintArray colorArray, jfloatArray posArray, jint tileMode) {
    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
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

    sk_sp<SkShader> baseShader = SkGradientShader::MakeRadial(center, radius,
            reinterpret_cast<const SkColor*>(colorValues), pos, count,
            static_cast<SkShader::TileMode>(tileMode), sGradientShaderFlags, NULL);

    SkShader* shader;
    if (matrix) {
        shader = baseShader->makeWithLocalMatrix(*matrix).release();
    } else {
        shader = baseShader.release();
    }

    env->ReleaseIntArrayElements(colorArray, const_cast<jint*>(colorValues),
                                 JNI_ABORT);

    ThrowIAE_IfNull(env, shader);
    return reinterpret_cast<jlong>(shader);
}

static jlong RadialGradient_create2(JNIEnv* env, jobject, jlong matrixPtr, jfloat x, jfloat y, jfloat radius,
        jint color0, jint color1, jint tileMode) {
    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    SkPoint center;
    center.set(x, y);

    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;

    sk_sp<SkShader> baseShader = SkGradientShader::MakeRadial(center, radius, colors, NULL, 2,
            static_cast<SkShader::TileMode>(tileMode), sGradientShaderFlags, NULL);

    SkShader* shader;
    if (matrix) {
        shader = baseShader->makeWithLocalMatrix(*matrix).release();
    } else {
        shader = baseShader.release();
    }
    ThrowIAE_IfNull(env, shader);
    return reinterpret_cast<jlong>(shader);
}

///////////////////////////////////////////////////////////////////////////////

static jlong SweepGradient_create1(JNIEnv* env, jobject, jlong matrixPtr, jfloat x, jfloat y,
        jintArray jcolors, jfloatArray jpositions) {
    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    size_t      count = env->GetArrayLength(jcolors);
    const jint* colors = env->GetIntArrayElements(jcolors, NULL);

    AutoJavaFloatArray autoPos(env, jpositions, count);
#ifdef SK_SCALAR_IS_FLOAT
    SkScalar* pos = autoPos.ptr();
#else
    #error Need to convert float array to SkScalar array before calling the following function.
#endif

    sk_sp<SkShader> baseShader = SkGradientShader::MakeSweep(x, y,
            reinterpret_cast<const SkColor*>(colors), pos, count,
            sGradientShaderFlags, NULL);

    SkShader* shader;
    if (matrix) {
        shader = baseShader->makeWithLocalMatrix(*matrix).release();
    } else {
        shader = baseShader.release();
    }

    env->ReleaseIntArrayElements(jcolors, const_cast<jint*>(colors),
                                 JNI_ABORT);
    ThrowIAE_IfNull(env, shader);
    return reinterpret_cast<jlong>(shader);
}

static jlong SweepGradient_create2(JNIEnv* env, jobject, jlong matrixPtr, jfloat x, jfloat y,
        int color0, int color1) {
    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;

    sk_sp<SkShader> baseShader = SkGradientShader::MakeSweep(x, y, colors,
            NULL, 2, sGradientShaderFlags, NULL);

    SkShader* shader;
    if (matrix) {
        shader = baseShader->makeWithLocalMatrix(*matrix).release();
    } else {
        shader = baseShader.release();
    }
    ThrowIAE_IfNull(env, shader);
    return reinterpret_cast<jlong>(shader);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong ComposeShader_create(JNIEnv* env, jobject o, jlong matrixPtr,
        jlong shaderAHandle, jlong shaderBHandle, jint xfermodeHandle) {
    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    SkShader* shaderA = reinterpret_cast<SkShader *>(shaderAHandle);
    SkShader* shaderB = reinterpret_cast<SkShader *>(shaderBHandle);
    SkBlendMode mode = static_cast<SkBlendMode>(xfermodeHandle);
    sk_sp<SkShader> baseShader(SkShader::MakeComposeShader(
            sk_ref_sp(shaderA), sk_ref_sp(shaderB), mode));

    SkShader* shader;

    if (matrix) {
        shader = baseShader->makeWithLocalMatrix(*matrix).release();
    } else {
        shader = baseShader.release();
    }
    return reinterpret_cast<jlong>(shader);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gColorMethods[] = {
    { "nativeRGBToHSV",    "(III[F)V", (void*)Color_RGBToHSV   },
    { "nativeHSVToColor",  "(I[F)I",   (void*)Color_HSVToColor }
};

static const JNINativeMethod gShaderMethods[] = {
    { "nativeGetFinalizer",   "()J",    (void*)Shader_getNativeFinalizer },
};

static const JNINativeMethod gBitmapShaderMethods[] = {
    { "nativeCreate",      "(JLandroid/graphics/Bitmap;II)J",  (void*)BitmapShader_constructor },
};

static const JNINativeMethod gLinearGradientMethods[] = {
    { "nativeCreate1",     "(JFFFF[I[FI)J",  (void*)LinearGradient_create1     },
    { "nativeCreate2",     "(JFFFFIII)J",    (void*)LinearGradient_create2     },
};

static const JNINativeMethod gRadialGradientMethods[] = {
    { "nativeCreate1",     "(JFFF[I[FI)J",  (void*)RadialGradient_create1     },
    { "nativeCreate2",     "(JFFFIII)J",    (void*)RadialGradient_create2     },
};

static const JNINativeMethod gSweepGradientMethods[] = {
    { "nativeCreate1",     "(JFF[I[F)J",  (void*)SweepGradient_create1     },
    { "nativeCreate2",     "(JFFII)J",    (void*)SweepGradient_create2     },
};

static const JNINativeMethod gComposeShaderMethods[] = {
    { "nativeCreate",      "(JJJI)J",   (void*)ComposeShader_create     },
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
