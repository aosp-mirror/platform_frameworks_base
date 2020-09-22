#include "GraphicsJNI.h"
#include "SkColorFilter.h"
#include "SkGradientShader.h"
#include "SkImagePriv.h"
#include "SkShader.h"
#include "SkBlendMode.h"
#include "include/effects/SkRuntimeEffect.h"
#include "shader/Shader.h"
#include "shader/BitmapShader.h"
#include "shader/BlurShader.h"
#include "shader/ComposeShader.h"
#include "shader/LinearGradientShader.h"
#include "shader/RadialGradientShader.h"
#include "shader/RuntimeShader.h"
#include "shader/SweepGradientShader.h"

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

static void Shader_safeUnref(Shader* shader) {
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

    auto* shader = new BitmapShader(
            image,
            static_cast<SkTileMode>(tileModeX),
            static_cast<SkTileMode>(tileModeY),
            matrix
        );

    return reinterpret_cast<jlong>(shader);
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

    auto* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    auto* shader = new LinearGradientShader(
                pts,
                colors,
                GraphicsJNI::getNativeColorSpace(colorSpaceHandle),
                pos,
                static_cast<SkTileMode>(tileMode),
                sGradientShaderFlags,
                matrix
            );

    return reinterpret_cast<jlong>(shader);
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

    auto* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);

    auto* shader = new RadialGradientShader(
                center,
                radius,
                colors,
                GraphicsJNI::getNativeColorSpace(colorSpaceHandle),
                pos,
                static_cast<SkTileMode>(tileMode),
                sGradientShaderFlags,
                matrix
            );

    return reinterpret_cast<jlong>(shader);
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

    auto* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);

    auto* shader = new SweepGradientShader(
                x,
                y,
                colors,
                GraphicsJNI::getNativeColorSpace(colorSpaceHandle),
                pos,
                sGradientShaderFlags,
                matrix
            );

    return reinterpret_cast<jlong>(shader);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong ComposeShader_create(JNIEnv* env, jobject o, jlong matrixPtr,
        jlong shaderAHandle, jlong shaderBHandle, jint xfermodeHandle) {
    auto* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    auto* shaderA = reinterpret_cast<Shader*>(shaderAHandle);
    auto* shaderB = reinterpret_cast<Shader*>(shaderBHandle);

    auto mode = static_cast<SkBlendMode>(xfermodeHandle);

    auto* composeShader = new ComposeShader(
            *shaderA,
            *shaderB,
            mode,
            matrix
        );

    return reinterpret_cast<jlong>(composeShader);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong BlurShader_create(JNIEnv* env , jobject o, jlong matrixPtr, jfloat sigmaX,
        jfloat sigmaY, jlong shaderHandle, jint edgeTreatment) {
    auto* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    auto* inputShader = reinterpret_cast<Shader*>(shaderHandle);

    auto* blurShader = new BlurShader(
                sigmaX,
                sigmaY,
                inputShader,
                static_cast<SkTileMode>(edgeTreatment),
                matrix
            );
    return reinterpret_cast<jlong>(blurShader);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong RuntimeShader_create(JNIEnv* env, jobject, jlong shaderFactory, jlong matrixPtr,
        jbyteArray inputs, jlong colorSpaceHandle, jboolean isOpaque) {
    auto* effect = reinterpret_cast<SkRuntimeEffect*>(shaderFactory);
    AutoJavaByteArray arInputs(env, inputs);

    auto data = SkData::MakeWithCopy(arInputs.ptr(), arInputs.length());
    auto* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);

    auto* shader = new RuntimeShader(
            *effect,
            std::move(data),
            isOpaque == JNI_TRUE,
            matrix
        );
    return reinterpret_cast<jlong>(shader);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong RuntimeShader_createShaderFactory(JNIEnv* env, jobject, jstring sksl) {
    ScopedUtfChars strSksl(env, sksl);
    auto result = SkRuntimeEffect::Make(SkString(strSksl.c_str()));
    sk_sp<SkRuntimeEffect> effect = std::get<0>(result);
    if (!effect) {
        const auto& err = std::get<1>(result);
        doThrowIAE(env, err.c_str());
    }
    return reinterpret_cast<jlong>(effect.release());
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong RuntimeShader_getNativeFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&Shader_safeUnref));
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

static const JNINativeMethod gBlurShaderMethods[] = {
    { "nativeCreate",      "(JFFJI)J", (void*)BlurShader_create }
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

static const JNINativeMethod gRuntimeShaderMethods[] = {
    { "nativeGetFinalizer",   "()J",    (void*)RuntimeShader_getNativeFinalizer },
    { "nativeCreate",     "(JJ[BJZ)J",  (void*)RuntimeShader_create     },
    { "nativeCreateShaderFactory",     "(Ljava/lang/String;)J",
      (void*)RuntimeShader_createShaderFactory     },
};

int register_android_graphics_Shader(JNIEnv* env)
{
    android::RegisterMethodsOrDie(env, "android/graphics/Color", gColorMethods,
                                  NELEM(gColorMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/Shader", gShaderMethods,
                                  NELEM(gShaderMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/BitmapShader", gBitmapShaderMethods,
                                  NELEM(gBitmapShaderMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/BlurShader", gBlurShaderMethods,
                                  NELEM(gBlurShaderMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/LinearGradient", gLinearGradientMethods,
                                  NELEM(gLinearGradientMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/RadialGradient", gRadialGradientMethods,
                                  NELEM(gRadialGradientMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/SweepGradient", gSweepGradientMethods,
                                  NELEM(gSweepGradientMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/ComposeShader", gComposeShaderMethods,
                                  NELEM(gComposeShaderMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/RuntimeShader", gRuntimeShaderMethods,
                                  NELEM(gRuntimeShaderMethods));

    return 0;
}
