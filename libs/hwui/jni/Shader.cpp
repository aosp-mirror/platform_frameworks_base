#include <vector>

#include "Gainmap.h"
#include "GraphicsJNI.h"
#include "SkBitmap.h"
#include "SkBlendMode.h"
#include "SkColor.h"
#include "SkColorFilter.h"
#include "SkGradientShader.h"
#include "SkImage.h"
#include "SkImagePriv.h"
#include "SkMatrix.h"
#include "SkPoint.h"
#include "SkRefCnt.h"
#include "SkSamplingOptions.h"
#include "SkScalar.h"
#include "SkShader.h"
#include "SkString.h"
#include "SkTileMode.h"
#include "effects/GainmapRenderer.h"
#include "include/effects/SkRuntimeEffect.h"

using namespace android::uirenderer;

/**
 * By default Skia gradients will interpolate their colors in unpremul space
 * and then premultiply each of the results. We must set this flag to preserve
 * backwards compatibility by premultiplying the colors of the gradient first,
 * and then interpolating between them.
 */
static const uint32_t sGradientShaderFlags = SkGradientShader::kInterpolateColorsInPremul_Flag;

#define ThrowIAE_IfNull(env, ptr)   \
    if (nullptr == ptr) {           \
        doThrowIAE(env);            \
        return 0;                   \
    }

///////////////////////////////////////////////////////////////////////////////////////////////

static void Shader_safeUnref(SkShader* shader) {
    SkSafeUnref(shader);
}

static jlong Shader_getNativeFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&Shader_safeUnref));
}

///////////////////////////////////////////////////////////////////////////////////////////////

static SkGainmapInfo sNoOpGainmap = {
        .fGainmapRatioMin = {1.f, 1.f, 1.f, 1.0},
        .fGainmapRatioMax = {1.f, 1.f, 1.f, 1.0},
        .fGainmapGamma = {1.f, 1.f, 1.f, 1.f},
        .fEpsilonSdr = {0.f, 0.f, 0.f, 1.0},
        .fEpsilonHdr = {0.f, 0.f, 0.f, 1.0},
        .fDisplayRatioSdr = 1.f,
        .fDisplayRatioHdr = 1.f,
};

static jlong BitmapShader_constructor(JNIEnv* env, jobject o, jlong matrixPtr, jlong bitmapHandle,
                                      jint tileModeX, jint tileModeY, jint maxAniso, bool filter,
                                      bool isDirectSampled, jlong overrideGainmapPtr) {
    SkSamplingOptions sampling = maxAniso > 0 ? SkSamplingOptions::Aniso(static_cast<int>(maxAniso))
                                              : SkSamplingOptions(filter ? SkFilterMode::kLinear
                                                                         : SkFilterMode::kNearest,
                                                                  SkMipmapMode::kNone);
    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    const Gainmap* gainmap = reinterpret_cast<Gainmap*>(overrideGainmapPtr);
    sk_sp<SkImage> image;
    if (bitmapHandle) {
        // Only pass a valid SkBitmap object to the constructor if the Bitmap exists. Otherwise,
        // we'll pass an empty SkBitmap to avoid crashing/excepting for compatibility.
        auto& bitmap = android::bitmap::toBitmap(bitmapHandle);
        image = bitmap.makeImage();
        if (!gainmap && bitmap.hasGainmap()) {
            gainmap = bitmap.gainmap().get();
        }

        if (!isDirectSampled && gainmap && gainmap->info != sNoOpGainmap) {
            sk_sp<SkShader> gainmapShader =
                    MakeGainmapShader(image, gainmap->bitmap->makeImage(), gainmap->info,
                                      (SkTileMode)tileModeX, (SkTileMode)tileModeY, sampling);
            if (gainmapShader) {
                if (matrix) {
                    gainmapShader = gainmapShader->makeWithLocalMatrix(*matrix);
                }
                return reinterpret_cast<jlong>(gainmapShader.release());
            }
        }
    }

    if (!image.get()) {
        SkBitmap bitmap;
        image = SkMakeImageFromRasterBitmap(bitmap, kNever_SkCopyPixelsMode);
    }

    sk_sp<SkShader> shader;
    if (isDirectSampled) {
        shader = image->makeRawShader((SkTileMode)tileModeX, (SkTileMode)tileModeY, sampling);
    } else {
        shader = image->makeShader((SkTileMode)tileModeX, (SkTileMode)tileModeY, sampling);
    }
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
    SkScalar* pos = autoPos.ptr();

    sk_sp<SkShader> shader(SkGradientShader::MakeLinear(pts, &colors[0],
                GraphicsJNI::getNativeColorSpace(colorSpaceHandle), pos, colors.size(),
                static_cast<SkTileMode>(tileMode), sGradientShaderFlags, nullptr));
    ThrowIAE_IfNull(env, shader);

    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    if (matrix) {
        shader = shader->makeWithLocalMatrix(*matrix);
    }

    return reinterpret_cast<jlong>(shader.release());
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong RadialGradient_create(JNIEnv* env,
        jobject,
        jlong matrixPtr,
        jfloat startX,
        jfloat startY,
        jfloat startRadius,
        jfloat endX,
        jfloat endY,
        jfloat endRadius,
        jlongArray colorArray,
        jfloatArray posArray,
        jint tileMode,
        jlong colorSpaceHandle) {

    SkPoint start;
    start.set(startX, startY);

    SkPoint end;
    end.set(endX, endY);

    std::vector<SkColor4f> colors = convertColorLongs(env, colorArray);

    AutoJavaFloatArray autoPos(env, posArray, colors.size());
    SkScalar* pos = autoPos.ptr();

    auto colorSpace = GraphicsJNI::getNativeColorSpace(colorSpaceHandle);
    auto skTileMode = static_cast<SkTileMode>(tileMode);
    sk_sp<SkShader> shader = SkGradientShader::MakeTwoPointConical(start, startRadius, end,
                    endRadius, &colors[0], std::move(colorSpace), pos, colors.size(), skTileMode,
                    sGradientShaderFlags, nullptr);
    ThrowIAE_IfNull(env, shader);

    // Explicitly create a new shader with the specified matrix to match existing behavior.
    // Passing in the matrix in the instantiation above can throw exceptions for non-invertible
    // matrices. However, makeWithLocalMatrix will still allow for the shader to be created
    // and skia handles null-shaders internally (i.e. is ignored)
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
    SkScalar* pos = autoPos.ptr();

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
    sk_sp<SkShader> baseShader(SkShaders::Blend(mode,
            sk_ref_sp(shaderA), sk_ref_sp(shaderB)));

    SkShader* shader;

    if (matrix) {
        shader = baseShader->makeWithLocalMatrix(*matrix).release();
    } else {
        shader = baseShader.release();
    }
    return reinterpret_cast<jlong>(shader);
}

///////////////////////////////////////////////////////////////////////////////////////////////

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong RuntimeShader_createShaderBuilder(JNIEnv* env, jobject, jstring sksl) {
    ScopedUtfChars strSksl(env, sksl);
    auto result = SkRuntimeEffect::MakeForShader(SkString(strSksl.c_str()),
                                                 SkRuntimeEffect::Options{});
    if (result.effect.get() == nullptr) {
        doThrowIAE(env, result.errorText.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(new SkRuntimeShaderBuilder(std::move(result.effect)));
}

static void SkRuntimeShaderBuilder_delete(SkRuntimeShaderBuilder* builder) {
    delete builder;
}

static jlong RuntimeShader_getNativeFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&SkRuntimeShaderBuilder_delete));
}

static jlong RuntimeShader_create(JNIEnv* env, jobject, jlong shaderBuilder, jlong matrixPtr) {
    SkRuntimeShaderBuilder* builder = reinterpret_cast<SkRuntimeShaderBuilder*>(shaderBuilder);
    const SkMatrix* matrix = reinterpret_cast<const SkMatrix*>(matrixPtr);
    sk_sp<SkShader> shader = builder->makeShader(matrix);
    ThrowIAE_IfNull(env, shader);
    return reinterpret_cast<jlong>(shader.release());
}

static inline int ThrowIAEFmt(JNIEnv* env, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    int ret = jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", fmt, args);
    va_end(args);
    return ret;
}

static bool isIntUniformType(const SkRuntimeEffect::Uniform::Type& type) {
    switch (type) {
        case SkRuntimeEffect::Uniform::Type::kFloat:
        case SkRuntimeEffect::Uniform::Type::kFloat2:
        case SkRuntimeEffect::Uniform::Type::kFloat3:
        case SkRuntimeEffect::Uniform::Type::kFloat4:
        case SkRuntimeEffect::Uniform::Type::kFloat2x2:
        case SkRuntimeEffect::Uniform::Type::kFloat3x3:
        case SkRuntimeEffect::Uniform::Type::kFloat4x4:
            return false;
        case SkRuntimeEffect::Uniform::Type::kInt:
        case SkRuntimeEffect::Uniform::Type::kInt2:
        case SkRuntimeEffect::Uniform::Type::kInt3:
        case SkRuntimeEffect::Uniform::Type::kInt4:
            return true;
    }
}

static void UpdateFloatUniforms(JNIEnv* env, SkRuntimeShaderBuilder* builder,
                                const char* uniformName, const float values[], int count,
                                bool isColor) {
    SkRuntimeShaderBuilder::BuilderUniform uniform = builder->uniform(uniformName);
    if (uniform.fVar == nullptr) {
        ThrowIAEFmt(env, "unable to find uniform named %s", uniformName);
    } else if (isColor != ((uniform.fVar->flags & SkRuntimeEffect::Uniform::kColor_Flag) != 0)) {
        if (isColor) {
            jniThrowExceptionFmt(
                    env, "java/lang/IllegalArgumentException",
                    "attempting to set a color uniform using the non-color specific APIs: %s %x",
                    uniformName, uniform.fVar->flags);
        } else {
            ThrowIAEFmt(env,
                        "attempting to set a non-color uniform using the setColorUniform APIs: %s",
                        uniformName);
        }
    } else if (isIntUniformType(uniform.fVar->type)) {
        ThrowIAEFmt(env, "attempting to set a int uniform using the setUniform APIs: %s",
                    uniformName);
    } else if (!uniform.set<float>(values, count)) {
        ThrowIAEFmt(env, "mismatch in byte size for uniform [expected: %zu actual: %zu]",
                    uniform.fVar->sizeInBytes(), sizeof(float) * count);
    }
}

static void RuntimeShader_updateFloatUniforms(JNIEnv* env, jobject, jlong shaderBuilder,
                                              jstring jUniformName, jfloat value1, jfloat value2,
                                              jfloat value3, jfloat value4, jint count) {
    SkRuntimeShaderBuilder* builder = reinterpret_cast<SkRuntimeShaderBuilder*>(shaderBuilder);
    ScopedUtfChars name(env, jUniformName);
    const float values[4] = {value1, value2, value3, value4};
    UpdateFloatUniforms(env, builder, name.c_str(), values, count, false);
}

static void RuntimeShader_updateFloatArrayUniforms(JNIEnv* env, jobject, jlong shaderBuilder,
                                                   jstring jUniformName, jfloatArray jvalues,
                                                   jboolean isColor) {
    SkRuntimeShaderBuilder* builder = reinterpret_cast<SkRuntimeShaderBuilder*>(shaderBuilder);
    ScopedUtfChars name(env, jUniformName);
    AutoJavaFloatArray autoValues(env, jvalues, 0, kRO_JNIAccess);
    UpdateFloatUniforms(env, builder, name.c_str(), autoValues.ptr(), autoValues.length(), isColor);
}

static void UpdateIntUniforms(JNIEnv* env, SkRuntimeShaderBuilder* builder, const char* uniformName,
                              const int values[], int count) {
    SkRuntimeShaderBuilder::BuilderUniform uniform = builder->uniform(uniformName);
    if (uniform.fVar == nullptr) {
        ThrowIAEFmt(env, "unable to find uniform named %s", uniformName);
    } else if (!isIntUniformType(uniform.fVar->type)) {
        ThrowIAEFmt(env, "attempting to set a non-int uniform using the setIntUniform APIs: %s",
                    uniformName);
    } else if (!uniform.set<int>(values, count)) {
        ThrowIAEFmt(env, "mismatch in byte size for uniform [expected: %zu actual: %zu]",
                    uniform.fVar->sizeInBytes(), sizeof(float) * count);
    }
}

static void RuntimeShader_updateIntUniforms(JNIEnv* env, jobject, jlong shaderBuilder,
                                            jstring jUniformName, jint value1, jint value2,
                                            jint value3, jint value4, jint count) {
    SkRuntimeShaderBuilder* builder = reinterpret_cast<SkRuntimeShaderBuilder*>(shaderBuilder);
    ScopedUtfChars name(env, jUniformName);
    const int values[4] = {value1, value2, value3, value4};
    UpdateIntUniforms(env, builder, name.c_str(), values, count);
}

static void RuntimeShader_updateIntArrayUniforms(JNIEnv* env, jobject, jlong shaderBuilder,
                                                 jstring jUniformName, jintArray jvalues) {
    SkRuntimeShaderBuilder* builder = reinterpret_cast<SkRuntimeShaderBuilder*>(shaderBuilder);
    ScopedUtfChars name(env, jUniformName);
    AutoJavaIntArray autoValues(env, jvalues, 0);
    UpdateIntUniforms(env, builder, name.c_str(), autoValues.ptr(), autoValues.length());
}

static void RuntimeShader_updateShader(JNIEnv* env, jobject, jlong shaderBuilder,
                                           jstring jUniformName, jlong shaderHandle) {
    SkRuntimeShaderBuilder* builder = reinterpret_cast<SkRuntimeShaderBuilder*>(shaderBuilder);
    ScopedUtfChars name(env, jUniformName);
    SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);

    SkRuntimeShaderBuilder::BuilderChild child = builder->child(name.c_str());
    if (child.fChild == nullptr) {
        ThrowIAEFmt(env, "unable to find shader named %s", name.c_str());
        return;
    }

    builder->child(name.c_str()) = sk_ref_sp(shader);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gShaderMethods[] = {
    { "nativeGetFinalizer",   "()J",    (void*)Shader_getNativeFinalizer },
};

static const JNINativeMethod gBitmapShaderMethods[] = {
        {"nativeCreate", "(JJIIIZZJ)J", (void*)BitmapShader_constructor},

};

static const JNINativeMethod gLinearGradientMethods[] = {
    { "nativeCreate",     "(JFFFF[J[FIJ)J",  (void*)LinearGradient_create     },
};

static const JNINativeMethod gRadialGradientMethods[] = {
    { "nativeCreate",     "(JFFFFFF[J[FIJ)J",  (void*)RadialGradient_create     },
};

static const JNINativeMethod gSweepGradientMethods[] = {
    { "nativeCreate",     "(JFF[J[FJ)J",  (void*)SweepGradient_create     },
};

static const JNINativeMethod gComposeShaderMethods[] = {
    { "nativeCreate",      "(JJJI)J",   (void*)ComposeShader_create     },
};

static const JNINativeMethod gRuntimeShaderMethods[] = {
        {"nativeGetFinalizer", "()J", (void*)RuntimeShader_getNativeFinalizer},
        {"nativeCreateShader", "(JJ)J", (void*)RuntimeShader_create},
        {"nativeCreateBuilder", "(Ljava/lang/String;)J", (void*)RuntimeShader_createShaderBuilder},
        {"nativeUpdateUniforms", "(JLjava/lang/String;[FZ)V",
         (void*)RuntimeShader_updateFloatArrayUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;FFFFI)V",
         (void*)RuntimeShader_updateFloatUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;[I)V",
         (void*)RuntimeShader_updateIntArrayUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;IIIII)V",
         (void*)RuntimeShader_updateIntUniforms},
        {"nativeUpdateShader", "(JLjava/lang/String;J)V", (void*)RuntimeShader_updateShader},
};

int register_android_graphics_Shader(JNIEnv* env)
{
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
    android::RegisterMethodsOrDie(env, "android/graphics/RuntimeShader", gRuntimeShaderMethods,
                                  NELEM(gRuntimeShaderMethods));

    return 0;
}
