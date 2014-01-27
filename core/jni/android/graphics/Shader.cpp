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

static void Color_RGBToHSV(JNIEnv* env, jobject, int red, int green, int blue, jfloatArray hsvArray)
{
    SkScalar hsv[3];
    SkRGBToHSV(red, green, blue, hsv);

    AutoJavaFloatArray  autoHSV(env, hsvArray, 3);
    float* values = autoHSV.ptr();
    for (int i = 0; i < 3; i++) {
        values[i] = SkScalarToFloat(hsv[i]);
    }
}

static int Color_HSVToColor(JNIEnv* env, jobject, int alpha, jfloatArray hsvArray)
{
    AutoJavaFloatArray  autoHSV(env, hsvArray, 3);
    float*      values = autoHSV.ptr();;
    SkScalar    hsv[3];

    for (int i = 0; i < 3; i++) {
        hsv[i] = SkFloatToScalar(values[i]);
    }

    return SkHSVToColor(alpha, hsv);
}

///////////////////////////////////////////////////////////////////////////////////////////////

static void Shader_destructor(JNIEnv* env, jobject o, SkShader* shader, SkiaShader* skiaShader)
{
    SkSafeUnref(shader);
    // skiaShader == NULL when not !USE_OPENGL_RENDERER, so no need to delete it outside the ifdef
#ifdef USE_OPENGL_RENDERER
    if (android::uirenderer::Caches::hasInstance()) {
        android::uirenderer::Caches::getInstance().resourceCache.destructor(skiaShader);
    } else {
        delete skiaShader;
    }
#endif
}

static void Shader_setLocalMatrix(JNIEnv* env, jobject o, SkShader* shader, SkiaShader* skiaShader,
        const SkMatrix* matrix)
{
    if (shader) {
        if (NULL == matrix) {
            shader->resetLocalMatrix();
        }
        else {
            shader->setLocalMatrix(*matrix);
        }
#ifdef USE_OPENGL_RENDERER
        skiaShader->setMatrix(const_cast<SkMatrix*>(matrix));
#endif
    }
}

///////////////////////////////////////////////////////////////////////////////////////////////

static SkShader* BitmapShader_constructor(JNIEnv* env, jobject o, const SkBitmap* bitmap,
                                          int tileModeX, int tileModeY)
{
    SkShader* s = SkShader::CreateBitmapShader(*bitmap,
                                        (SkShader::TileMode)tileModeX,
                                        (SkShader::TileMode)tileModeY);

    ThrowIAE_IfNull(env, s);
    return s;
}

static SkiaShader* BitmapShader_postConstructor(JNIEnv* env, jobject o, SkShader* shader,
        SkBitmap* bitmap, int tileModeX, int tileModeY) {
#ifdef USE_OPENGL_RENDERER
    SkiaShader* skiaShader = new SkiaBitmapShader(bitmap, shader,
            static_cast<SkShader::TileMode>(tileModeX), static_cast<SkShader::TileMode>(tileModeY),
            NULL, (shader->getFlags() & SkShader::kOpaqueAlpha_Flag) == 0);
    return skiaShader;
#else
    return NULL;
#endif
}

///////////////////////////////////////////////////////////////////////////////////////////////

static SkShader* LinearGradient_create1(JNIEnv* env, jobject o,
                                        float x0, float y0, float x1, float y1,
                                        jintArray colorArray, jfloatArray posArray, int tileMode)
{
    SkPoint pts[2];
    pts[0].set(SkFloatToScalar(x0), SkFloatToScalar(y0));
    pts[1].set(SkFloatToScalar(x1), SkFloatToScalar(y1));

    size_t count = env->GetArrayLength(colorArray);
    const jint* colorValues = env->GetIntArrayElements(colorArray, NULL);

    SkAutoSTMalloc<8, SkScalar> storage(posArray ? count : 0);
    SkScalar* pos = NULL;

    if (posArray) {
        AutoJavaFloatArray autoPos(env, posArray, count);
        const float* posValues = autoPos.ptr();
        pos = (SkScalar*)storage.get();
        for (size_t i = 0; i < count; i++) {
            pos[i] = SkFloatToScalar(posValues[i]);
        }
    }

    SkShader* shader = SkGradientShader::CreateLinear(pts,
                                reinterpret_cast<const SkColor*>(colorValues),
                                pos, count,
                                static_cast<SkShader::TileMode>(tileMode));

    env->ReleaseIntArrayElements(colorArray, const_cast<jint*>(colorValues), JNI_ABORT);
    ThrowIAE_IfNull(env, shader);
    return shader;
}

static SkiaShader* LinearGradient_postCreate1(JNIEnv* env, jobject o, SkShader* shader,
        float x0, float y0, float x1, float y1, jintArray colorArray,
        jfloatArray posArray, int tileMode) {
#ifdef USE_OPENGL_RENDERER
    size_t count = env->GetArrayLength(colorArray);
    const jint* colorValues = env->GetIntArrayElements(colorArray, NULL);

    jfloat* storedBounds = new jfloat[4];
    storedBounds[0] = x0; storedBounds[1] = y0;
    storedBounds[2] = x1; storedBounds[3] = y1;
    
    bool missFirst = false;
    bool missLast = false;
    size_t stopCount = count;

    jfloat* storedPositions = NULL;
    if (posArray) {
        AutoJavaFloatArray autoPos(env, posArray, count);
        const float* posValues = autoPos.ptr();

        missFirst = posValues[0] != 0.0f;
        missLast = posValues[count - 1] != 1.0f;

        stopCount += missFirst + missLast;
        storedPositions = new jfloat[stopCount];

        if (missFirst) {
            storedPositions[0] = 0.0f;
        }

        for (size_t i = missFirst; i < count + missFirst; i++) {
            storedPositions[i] = posValues[i - missFirst];
        }

        if (missLast) {
            storedPositions[stopCount - 1] = 1.0f;
        }
    } else {
        storedPositions = new jfloat[count];
        storedPositions[0] = 0.0f;
        const jfloat step = 1.0f / (count - 1);
        for (size_t i = 1; i < count - 1; i++) {
            storedPositions[i] = step * i;
        }
        storedPositions[count - 1] = 1.0f;
    }

    uint32_t* storedColors = new uint32_t[stopCount];

    if (missFirst) {
        storedColors[0] = static_cast<uint32_t>(colorValues[0]);
    }

    for (size_t i = missFirst; i < count + missFirst; i++) {
        storedColors[i] = static_cast<uint32_t>(colorValues[i - missFirst]);
    }

    if (missLast) {
        storedColors[stopCount - 1] = static_cast<uint32_t>(colorValues[count - 1]);
    }

    SkiaShader* skiaShader = new SkiaLinearGradientShader(storedBounds, storedColors,
            storedPositions, stopCount, shader, static_cast<SkShader::TileMode>(tileMode), NULL,
            (shader->getFlags() & SkShader::kOpaqueAlpha_Flag) == 0);

    env->ReleaseIntArrayElements(colorArray, const_cast<jint*>(colorValues), JNI_ABORT);
    return skiaShader;
#else
    return NULL;
#endif
}

static SkiaShader* LinearGradient_postCreate2(JNIEnv* env, jobject o, SkShader* shader,
        float x0, float y0, float x1, float y1, int color0, int color1, int tileMode) {
#ifdef USE_OPENGL_RENDERER
    float* storedBounds = new float[4];
    storedBounds[0] = x0; storedBounds[1] = y0;
    storedBounds[2] = x1; storedBounds[3] = y1;

    float* storedPositions = new float[2];
    storedPositions[0] = 0.0f;
    storedPositions[1] = 1.0f;

    uint32_t* storedColors = new uint32_t[2];
    storedColors[0] = static_cast<uint32_t>(color0);
    storedColors[1] = static_cast<uint32_t>(color1);

    SkiaShader* skiaShader = new SkiaLinearGradientShader(storedBounds, storedColors,
            storedPositions, 2, shader, static_cast<SkShader::TileMode>(tileMode), NULL,
            (shader->getFlags() & SkShader::kOpaqueAlpha_Flag) == 0);

    return skiaShader;
#else
    return NULL;
#endif
}

static SkShader* LinearGradient_create2(JNIEnv* env, jobject o,
                                        float x0, float y0, float x1, float y1,
                                        int color0, int color1, int tileMode)
{
    SkPoint pts[2];
    pts[0].set(SkFloatToScalar(x0), SkFloatToScalar(y0));
    pts[1].set(SkFloatToScalar(x1), SkFloatToScalar(y1));

    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;

    SkShader* s = SkGradientShader::CreateLinear(pts, colors, NULL, 2, (SkShader::TileMode)tileMode);

    ThrowIAE_IfNull(env, s);
    return s;
}

///////////////////////////////////////////////////////////////////////////////////////////////

static SkShader* RadialGradient_create1(JNIEnv* env, jobject, float x, float y, float radius,
        jintArray colorArray, jfloatArray posArray, int tileMode) {
    SkPoint center;
    center.set(SkFloatToScalar(x), SkFloatToScalar(y));

    size_t      count = env->GetArrayLength(colorArray);
    const jint* colorValues = env->GetIntArrayElements(colorArray, NULL);

    SkAutoSTMalloc<8, SkScalar> storage(posArray ? count : 0);
    SkScalar*                   pos = NULL;

    if (posArray) {
        AutoJavaFloatArray autoPos(env, posArray, count);
        const float* posValues = autoPos.ptr();
        pos = (SkScalar*)storage.get();
        for (size_t i = 0; i < count; i++)
            pos[i] = SkFloatToScalar(posValues[i]);
    }

    SkShader* shader = SkGradientShader::CreateRadial(center,
                                SkFloatToScalar(radius),
                                reinterpret_cast<const SkColor*>(colorValues),
                                pos, count,
                                static_cast<SkShader::TileMode>(tileMode));
    env->ReleaseIntArrayElements(colorArray, const_cast<jint*>(colorValues),
                                 JNI_ABORT);

    ThrowIAE_IfNull(env, shader);
    return shader;
}

static SkShader* RadialGradient_create2(JNIEnv* env, jobject, float x, float y, float radius,
        int color0, int color1, int tileMode) {
    SkPoint center;
    center.set(SkFloatToScalar(x), SkFloatToScalar(y));

    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;

    SkShader* s = SkGradientShader::CreateRadial(center, SkFloatToScalar(radius), colors, NULL,
                                          2, (SkShader::TileMode)tileMode);
    ThrowIAE_IfNull(env, s);
    return s;
}

static SkiaShader* RadialGradient_postCreate1(JNIEnv* env, jobject o, SkShader* shader,
        float x, float y, float radius, jintArray colorArray, jfloatArray posArray, int tileMode) {
#ifdef USE_OPENGL_RENDERER
    size_t count = env->GetArrayLength(colorArray);
    const jint* colorValues = env->GetIntArrayElements(colorArray, NULL);

    jfloat* storedPositions = new jfloat[count];
    uint32_t* storedColors = new uint32_t[count];
    for (size_t i = 0; i < count; i++) {
        storedColors[i] = static_cast<uint32_t>(colorValues[i]);
    }

    if (posArray) {
        AutoJavaFloatArray autoPos(env, posArray, count);
        const float* posValues = autoPos.ptr();
        for (size_t i = 0; i < count; i++) {
            storedPositions[i] = posValues[i];
        }
    } else {
        storedPositions[0] = 0.0f;
        const jfloat step = 1.0f / (count - 1);
        for (size_t i = 1; i < count - 1; i++) {
            storedPositions[i] = step * i;
        }
        storedPositions[count - 1] = 1.0f;
    }

    SkiaShader* skiaShader = new SkiaCircularGradientShader(x, y, radius, storedColors,
            storedPositions, count, shader, (SkShader::TileMode) tileMode, NULL,
            (shader->getFlags() & SkShader::kOpaqueAlpha_Flag) == 0);

    env->ReleaseIntArrayElements(colorArray, const_cast<jint*>(colorValues), JNI_ABORT);
    return skiaShader;
#else
    return NULL;
#endif
}

static SkiaShader* RadialGradient_postCreate2(JNIEnv* env, jobject o, SkShader* shader,
        float x, float y, float radius, int color0, int color1, int tileMode) {
#ifdef USE_OPENGL_RENDERER
    float* storedPositions = new float[2];
    storedPositions[0] = 0.0f;
    storedPositions[1] = 1.0f;

    uint32_t* storedColors = new uint32_t[2];
    storedColors[0] = static_cast<uint32_t>(color0);
    storedColors[1] = static_cast<uint32_t>(color1);

    SkiaShader* skiaShader = new SkiaCircularGradientShader(x, y, radius, storedColors,
            storedPositions, 2, shader, (SkShader::TileMode) tileMode, NULL,
            (shader->getFlags() & SkShader::kOpaqueAlpha_Flag) == 0);

    return skiaShader;
#else
    return NULL;
#endif
}

///////////////////////////////////////////////////////////////////////////////

static SkShader* SweepGradient_create1(JNIEnv* env, jobject, float x, float y,
        jintArray jcolors, jfloatArray jpositions) {
    size_t      count = env->GetArrayLength(jcolors);
    const jint* colors = env->GetIntArrayElements(jcolors, NULL);

    SkAutoSTMalloc<8, SkScalar> storage(jpositions ? count : 0);
    SkScalar*                   pos = NULL;

    if (NULL != jpositions) {
        AutoJavaFloatArray autoPos(env, jpositions, count);
        const float* posValues = autoPos.ptr();
        pos = (SkScalar*)storage.get();
        for (size_t i = 0; i < count; i++) {
            pos[i] = SkFloatToScalar(posValues[i]);
        }
    }

    SkShader* shader = SkGradientShader::CreateSweep(SkFloatToScalar(x),
                                     SkFloatToScalar(y),
                                     reinterpret_cast<const SkColor*>(colors),
                                     pos, count);
    env->ReleaseIntArrayElements(jcolors, const_cast<jint*>(colors),
                                 JNI_ABORT);
    ThrowIAE_IfNull(env, shader);
    return shader;
}

static SkShader* SweepGradient_create2(JNIEnv* env, jobject, float x, float y,
        int color0, int color1) {
    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;
    SkShader* s = SkGradientShader::CreateSweep(SkFloatToScalar(x), SkFloatToScalar(y),
                                         colors, NULL, 2);
    ThrowIAE_IfNull(env, s);
    return s;
}

static SkiaShader* SweepGradient_postCreate1(JNIEnv* env, jobject o, SkShader* shader,
        float x, float y, jintArray colorArray, jfloatArray posArray) {
#ifdef USE_OPENGL_RENDERER
    size_t count = env->GetArrayLength(colorArray);
    const jint* colorValues = env->GetIntArrayElements(colorArray, NULL);

    jfloat* storedPositions = new jfloat[count];
    uint32_t* storedColors = new uint32_t[count];
    for (size_t i = 0; i < count; i++) {
        storedColors[i] = static_cast<uint32_t>(colorValues[i]);
    }

    if (posArray) {
        AutoJavaFloatArray autoPos(env, posArray, count);
        const float* posValues = autoPos.ptr();
        for (size_t i = 0; i < count; i++) {
            storedPositions[i] = posValues[i];
        }
    } else {
        storedPositions[0] = 0.0f;
        const jfloat step = 1.0f / (count - 1);
        for (size_t i = 1; i < count - 1; i++) {
            storedPositions[i] = step * i;
        }
        storedPositions[count - 1] = 1.0f;
    }

    SkiaShader* skiaShader = new SkiaSweepGradientShader(x, y, storedColors, storedPositions, count,
            shader, NULL, (shader->getFlags() & SkShader::kOpaqueAlpha_Flag) == 0);

    env->ReleaseIntArrayElements(colorArray, const_cast<jint*>(colorValues), JNI_ABORT);
    return skiaShader;
#else
    return NULL;
#endif
}

static SkiaShader* SweepGradient_postCreate2(JNIEnv* env, jobject o, SkShader* shader,
        float x, float y, int color0, int color1) {
#ifdef USE_OPENGL_RENDERER
    float* storedPositions = new float[2];
    storedPositions[0] = 0.0f;
    storedPositions[1] = 1.0f;

    uint32_t* storedColors = new uint32_t[2];
    storedColors[0] = static_cast<uint32_t>(color0);
    storedColors[1] = static_cast<uint32_t>(color1);

    SkiaShader* skiaShader = new SkiaSweepGradientShader(x, y, storedColors, storedPositions, 2,
            shader, NULL, (shader->getFlags() & SkShader::kOpaqueAlpha_Flag) == 0);

    return skiaShader;
#else
    return NULL;
#endif
}

///////////////////////////////////////////////////////////////////////////////////////////////

static SkShader* ComposeShader_create1(JNIEnv* env, jobject o,
        SkShader* shaderA, SkShader* shaderB, SkXfermode* mode)
{
    return new SkComposeShader(shaderA, shaderB, mode);
}

static SkShader* ComposeShader_create2(JNIEnv* env, jobject o,
        SkShader* shaderA, SkShader* shaderB, SkPorterDuff::Mode porterDuffMode)
{
    SkAutoUnref au(SkPorterDuff::CreateXfermode(porterDuffMode));
    SkXfermode* mode = (SkXfermode*) au.get();
    return new SkComposeShader(shaderA, shaderB, mode);
}

static SkiaShader* ComposeShader_postCreate2(JNIEnv* env, jobject o, SkShader* shader,
        SkiaShader* shaderA, SkiaShader* shaderB, SkPorterDuff::Mode porterDuffMode) {
#ifdef USE_OPENGL_RENDERER
    SkXfermode::Mode mode = SkPorterDuff::ToXfermodeMode(porterDuffMode);
    return new SkiaComposeShader(shaderA, shaderB, mode, shader);
#else
    return NULL;
#endif
}

static SkiaShader* ComposeShader_postCreate1(JNIEnv* env, jobject o, SkShader* shader,
        SkiaShader* shaderA, SkiaShader* shaderB, SkXfermode* mode) {
#ifdef USE_OPENGL_RENDERER
    SkXfermode::Mode skiaMode;
    if (!SkXfermode::IsMode(mode, &skiaMode)) {
        // TODO: Support other modes
        skiaMode = SkXfermode::kSrcOver_Mode;
    }
    return new SkiaComposeShader(shaderA, shaderB, skiaMode, shader);
#else
    return NULL;
#endif
}

///////////////////////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gColorMethods[] = {
    { "nativeRGBToHSV",     "(III[F)V", (void*)Color_RGBToHSV   },
    { "nativeHSVToColor",   "(I[F)I",   (void*)Color_HSVToColor }
};

static JNINativeMethod gShaderMethods[] = {
    { "nativeDestructor",        "(II)V",    (void*)Shader_destructor        },
    { "nativeSetLocalMatrix",    "(III)V",   (void*)Shader_setLocalMatrix    }
};

static JNINativeMethod gBitmapShaderMethods[] = {
    { "nativeCreate",     "(III)I",  (void*)BitmapShader_constructor },
    { "nativePostCreate", "(IIII)I", (void*)BitmapShader_postConstructor }
};

static JNINativeMethod gLinearGradientMethods[] = {
    { "nativeCreate1",     "(FFFF[I[FI)I",  (void*)LinearGradient_create1     },
    { "nativeCreate2",     "(FFFFIII)I",    (void*)LinearGradient_create2     },
    { "nativePostCreate1", "(IFFFF[I[FI)I", (void*)LinearGradient_postCreate1 },
    { "nativePostCreate2", "(IFFFFIII)I",   (void*)LinearGradient_postCreate2 }
};

static JNINativeMethod gRadialGradientMethods[] = {
    { "nativeCreate1",     "(FFF[I[FI)I",  (void*)RadialGradient_create1     },
    { "nativeCreate2",     "(FFFIII)I",    (void*)RadialGradient_create2     },
    { "nativePostCreate1", "(IFFF[I[FI)I", (void*)RadialGradient_postCreate1 },
    { "nativePostCreate2", "(IFFFIII)I",   (void*)RadialGradient_postCreate2 }
};

static JNINativeMethod gSweepGradientMethods[] = {
    { "nativeCreate1",     "(FF[I[F)I",  (void*)SweepGradient_create1     },
    { "nativeCreate2",     "(FFII)I",    (void*)SweepGradient_create2     },
    { "nativePostCreate1", "(IFF[I[F)I", (void*)SweepGradient_postCreate1 },
    { "nativePostCreate2", "(IFFII)I",   (void*)SweepGradient_postCreate2 }
};

static JNINativeMethod gComposeShaderMethods[] = {
    { "nativeCreate1",      "(III)I",   (void*)ComposeShader_create1     },
    { "nativeCreate2",      "(III)I",   (void*)ComposeShader_create2     },
    { "nativePostCreate1",  "(IIII)I",  (void*)ComposeShader_postCreate1 },
    { "nativePostCreate2",  "(IIII)I",  (void*)ComposeShader_postCreate2 }
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
