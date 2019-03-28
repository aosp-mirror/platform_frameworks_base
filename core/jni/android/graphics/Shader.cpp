#include "GraphicsJNI.h"
#include "SkColorFilter.h"
#include "SkGradientShader.h"
#include "SkImagePriv.h"
#include "SkShader.h"
#include "SkBlendMode.h"
#include "core_jni_helpers.h"

#include <jni.h>

#include <vector>

using namespace android::uirenderer;

/**
 * By default Skia gradients will interpolate their colors in unpremul space
 * and then premultiply each of the results. We must set this flag to preserve
 * backwards compatiblity by premultiplying the colors of the gradient first,
 * and then interpolating between them.
 */
static const uint32_t sGradientShaderFlags = SkGradientShader::kInterpolateColorsInPremul_Flag;

#define ThrowIAE_IfNull(env, ptr)   \
    if (nullptr == ptr) {           \
        doThrowIAE(env);            \
        return 0;                   \
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

static jlong BitmapShader_constructor(JNIEnv* env, jobject o, jlong matrixPtr, jlong bitmapHandle,
        jint tileModeX, jint tileModeY) {
    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    sk_sp<SkImage> image;
    if (bitmapHandle) {
        // Only pass a valid SkBitmap object to the constructor if the Bitmap exists. Otherwise,
        // we'll pass an empty SkBitmap to avoid crashing/excepting for compatibility.
        image = android::bitmap::toBitmap(bitmapHandle).makeImage();
    }

    if (!image.get()) {
        SkBitmap bitmap;
        image = SkMakeImageFromRasterBitmap(bitmap, kNever_SkCopyPixelsMode);
    }
    sk_sp<SkShader> shader = image->makeShader(
            (SkShader::TileMode)tileModeX, (SkShader::TileMode)tileModeY);
    ThrowIAE_IfNull(env, shader.get());

    if (matrix) {
        shader = shader->makeWithLocalMatrix(*matrix);
    }

    return reinterpret_cast<jlong>(shader.release());
}

///////////////////////////////////////////////////////////////////////////////////////////////

static std::vector<SkColor4f> convertColorLongs(JNIEnv* env, jlongArray colorArray) {
    const size_t count = env->GetArrayLength(colorArray);
    const jlong* colorValues = env->GetLongArrayElements(colorArray, nullptr);

    std::vector<SkColor4f> colors(count);
    for (size_t i = 0; i < count; ++i) {
        colors[i] = GraphicsJNI::convertColorLong(colorValues[i]);
    }

    env->ReleaseLongArrayElements(colorArray, const_cast<jlong*>(colorValues), JNI_ABORT);
    return colors;
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong LinearGradient_create(JNIEnv* env, jobject, jlong matrixPtr,
        jfloat x0, jfloat y0, jfloat x1, jfloat y1, jlongArray colorArray,
        jfloatArray posArray, jint tileMode, jlong colorSpaceHandle) {
    SkPoint pts[2];
    pts[0].set(x0, y0);
    pts[1].set(x1, y1);

    std::vector<SkColor4f> colors = convertColorLongs(env, colorArray);

    AutoJavaFloatArray autoPos(env, posArray, colors.size());
#ifdef SK_SCALAR_IS_FLOAT
    SkScalar* pos = autoPos.ptr();
#else
    #error Need to convert float array to SkScalar array before calling the following function.
#endif

    sk_sp<SkShader> shader(SkGradientShader::MakeLinear(pts, &colors[0],
                GraphicsJNI::getNativeColorSpace(colorSpaceHandle), pos, colors.size(),
                static_cast<SkShader::TileMode>(tileMode), sGradientShaderFlags, nullptr));
    ThrowIAE_IfNull(env, shader);

    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    if (matrix) {
        shader = shader->makeWithLocalMatrix(*matrix);
    }

    return reinterpret_cast<jlong>(shader.release());
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong RadialGradient_create(JNIEnv* env, jobject, jlong matrixPtr, jfloat x, jfloat y,
        jfloat radius, jlongArray colorArray, jfloatArray posArray, jint tileMode,
        jlong colorSpaceHandle) {
    SkPoint center;
    center.set(x, y);

    std::vector<SkColor4f> colors = convertColorLongs(env, colorArray);

    AutoJavaFloatArray autoPos(env, posArray, colors.size());
#ifdef SK_SCALAR_IS_FLOAT
    SkScalar* pos = autoPos.ptr();
#else
    #error Need to convert float array to SkScalar array before calling the following function.
#endif

    sk_sp<SkShader> shader = SkGradientShader::MakeRadial(center, radius, &colors[0],
            GraphicsJNI::getNativeColorSpace(colorSpaceHandle), pos, colors.size(),
            static_cast<SkShader::TileMode>(tileMode), sGradientShaderFlags, nullptr);
    ThrowIAE_IfNull(env, shader);

    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    if (matrix) {
        shader = shader->makeWithLocalMatrix(*matrix);
    }

    return reinterpret_cast<jlong>(shader.release());
}

///////////////////////////////////////////////////////////////////////////////

static jlong SweepGradient_create(JNIEnv* env, jobject, jlong matrixPtr, jfloat x, jfloat y,
        jlongArray colorArray, jfloatArray jpositions, jlong colorSpaceHandle) {
    std::vector<SkColor4f> colors = convertColorLongs(env, colorArray);

    AutoJavaFloatArray autoPos(env, jpositions, colors.size());
#ifdef SK_SCALAR_IS_FLOAT
    SkScalar* pos = autoPos.ptr();
#else
    #error Need to convert float array to SkScalar array before calling the following function.
#endif

    sk_sp<SkShader> shader = SkGradientShader::MakeSweep(x, y, &colors[0],
            GraphicsJNI::getNativeColorSpace(colorSpaceHandle), pos, colors.size(),
            sGradientShaderFlags, nullptr);
    ThrowIAE_IfNull(env, shader);

    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    if (matrix) {
        shader = shader->makeWithLocalMatrix(*matrix);
    }

    return reinterpret_cast<jlong>(shader.release());
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
    { "nativeCreate",      "(JJII)J",  (void*)BitmapShader_constructor },
};

static const JNINativeMethod gLinearGradientMethods[] = {
    { "nativeCreate",     "(JFFFF[J[FIJ)J",  (void*)LinearGradient_create     },
};

static const JNINativeMethod gRadialGradientMethods[] = {
    { "nativeCreate",     "(JFFF[J[FIJ)J",  (void*)RadialGradient_create     },
};

static const JNINativeMethod gSweepGradientMethods[] = {
    { "nativeCreate",     "(JFF[J[FJ)J",  (void*)SweepGradient_create     },
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
