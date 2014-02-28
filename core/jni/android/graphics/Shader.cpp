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
    float*      values = autoHSV.ptr();;
    SkScalar    hsv[3];

    for (int i = 0; i < 3; i++) {
        hsv[i] = SkFloatToScalar(values[i]);
    }

    return static_cast<jint>(SkHSVToColor(alpha, hsv));
}

///////////////////////////////////////////////////////////////////////////////////////////////

static void Shader_destructor(JNIEnv* env, jobject o, jlong shaderHandle, jlong skiaShaderHandle)
{
    SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);
    SkiaShader* skiaShader = reinterpret_cast<SkiaShader*>(skiaShaderHandle);
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

static void Shader_setLocalMatrix(JNIEnv* env, jobject o, jlong shaderHandle,
        jlong skiaShaderHandle, jlong matrixHandle)
{
    SkShader* shader       = reinterpret_cast<SkShader*>(shaderHandle);
    SkiaShader* skiaShader = reinterpret_cast<SkiaShader*>(skiaShaderHandle);
    const SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
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

static jlong BitmapShader_postConstructor(JNIEnv* env, jobject o, jlong shaderHandle,
        jlong bitmapHandle, jint tileModeX, jint tileModeY) {
    SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
#ifdef USE_OPENGL_RENDERER
    SkiaShader* skiaShader = new SkiaBitmapShader(bitmap, shader,
            static_cast<SkShader::TileMode>(tileModeX), static_cast<SkShader::TileMode>(tileModeY),
            NULL, (shader->getFlags() & SkShader::kOpaqueAlpha_Flag) == 0);
    return reinterpret_cast<jlong>(skiaShader);
#else
    return NULL;
#endif
}

///////////////////////////////////////////////////////////////////////////////////////////////

static jlong LinearGradient_create1(JNIEnv* env, jobject o,
                                    jfloat x0, jfloat y0, jfloat x1, jfloat y1,
                                    jintArray colorArray, jfloatArray posArray, jint tileMode)
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
    return reinterpret_cast<jlong>(shader);
}

static jlong LinearGradient_postCreate1(JNIEnv* env, jobject o, jlong shaderHandle,
        jfloat x0, jfloat y0, jfloat x1, jfloat y1, jintArray colorArray,
        jfloatArray posArray, jint tileMode) {
#ifdef USE_OPENGL_RENDERER
    SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);
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
    return reinterpret_cast<jlong>(skiaShader);
#else
    return NULL;
#endif
}

static jlong LinearGradient_postCreate2(JNIEnv* env, jobject o, jlong shaderHandle,
        jfloat x0, jfloat y0, jfloat x1, jfloat y1, jint color0, jint color1, jint tileMode) {
#ifdef USE_OPENGL_RENDERER
    SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);
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

    return reinterpret_cast<jlong>(skiaShader);
#else
    return NULL;
#endif
}

static jlong LinearGradient_create2(JNIEnv* env, jobject o,
                                    jfloat x0, jfloat y0, jfloat x1, jfloat y1,
                                    jint color0, jint color1, jint tileMode)
{
    SkPoint pts[2];
    pts[0].set(SkFloatToScalar(x0), SkFloatToScalar(y0));
    pts[1].set(SkFloatToScalar(x1), SkFloatToScalar(y1));

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
    return reinterpret_cast<jlong>(shader);
}

static jlong RadialGradient_create2(JNIEnv* env, jobject, jfloat x, jfloat y, jfloat radius,
        jint color0, jint color1, jint tileMode) {
    SkPoint center;
    center.set(SkFloatToScalar(x), SkFloatToScalar(y));

    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;

    SkShader* s = SkGradientShader::CreateRadial(center, SkFloatToScalar(radius), colors, NULL,
                                          2, (SkShader::TileMode)tileMode);
    ThrowIAE_IfNull(env, s);
    return reinterpret_cast<jlong>(s);
}

static jlong RadialGradient_postCreate1(JNIEnv* env, jobject o, jlong shaderHandle,
        jfloat x, jfloat y, jfloat radius, jintArray colorArray, jfloatArray posArray, jint tileMode) {
#ifdef USE_OPENGL_RENDERER
    SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);
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
    return reinterpret_cast<jlong>(skiaShader);
#else
    return NULL;
#endif
}

static jlong RadialGradient_postCreate2(JNIEnv* env, jobject o, jlong shaderHandle,
        jfloat x, jfloat y, jfloat radius, jint color0, jint color1, jint tileMode) {
#ifdef USE_OPENGL_RENDERER
    SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);
    float* storedPositions = new float[2];
    storedPositions[0] = 0.0f;
    storedPositions[1] = 1.0f;

    uint32_t* storedColors = new uint32_t[2];
    storedColors[0] = static_cast<uint32_t>(color0);
    storedColors[1] = static_cast<uint32_t>(color1);

    SkiaShader* skiaShader = new SkiaCircularGradientShader(x, y, radius, storedColors,
            storedPositions, 2, shader, (SkShader::TileMode) tileMode, NULL,
            (shader->getFlags() & SkShader::kOpaqueAlpha_Flag) == 0);

    return reinterpret_cast<jlong>(skiaShader);
#else
    return NULL;
#endif
}

///////////////////////////////////////////////////////////////////////////////

static jlong SweepGradient_create1(JNIEnv* env, jobject, jfloat x, jfloat y,
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
    return reinterpret_cast<jlong>(shader);
}

static jlong SweepGradient_create2(JNIEnv* env, jobject, jfloat x, jfloat y,
        int color0, int color1) {
    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;
    SkShader* s = SkGradientShader::CreateSweep(SkFloatToScalar(x), SkFloatToScalar(y),
                                         colors, NULL, 2);
    ThrowIAE_IfNull(env, s);
    return reinterpret_cast<jlong>(s);
}

static jlong SweepGradient_postCreate1(JNIEnv* env, jobject o, jlong shaderHandle,
        jfloat x, jfloat y, jintArray colorArray, jfloatArray posArray) {
#ifdef USE_OPENGL_RENDERER
    SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);
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
    return reinterpret_cast<jlong>(skiaShader);
#else
    return NULL;
#endif
}

static jlong SweepGradient_postCreate2(JNIEnv* env, jobject o, jlong shaderHandle,
        jfloat x, jfloat y, jint color0, jint color1) {
#ifdef USE_OPENGL_RENDERER
    SkShader* shader = reinterpret_cast<SkShader*>(shaderHandle);
    float* storedPositions = new float[2];
    storedPositions[0] = 0.0f;
    storedPositions[1] = 1.0f;

    uint32_t* storedColors = new uint32_t[2];
    storedColors[0] = static_cast<uint32_t>(color0);
    storedColors[1] = static_cast<uint32_t>(color1);

    SkiaShader* skiaShader = new SkiaSweepGradientShader(x, y, storedColors, storedPositions, 2,
            shader, NULL, (shader->getFlags() & SkShader::kOpaqueAlpha_Flag) == 0);

    return reinterpret_cast<jlong>(skiaShader);
#else
    return NULL;
#endif
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

static jlong ComposeShader_postCreate2(JNIEnv* env, jobject o, jlong shaderHandle,
        jlong shaderAHandle, jlong shaderBHandle, jint porterDuffModeHandle) {
#ifdef USE_OPENGL_RENDERER
    SkShader* shader = reinterpret_cast<SkShader *>(shaderHandle);
    SkiaShader* shaderA = reinterpret_cast<SkiaShader *>(shaderAHandle);
    SkiaShader* shaderB = reinterpret_cast<SkiaShader *>(shaderBHandle);
    SkPorterDuff::Mode porterDuffMode = static_cast<SkPorterDuff::Mode>(porterDuffModeHandle);
    SkXfermode::Mode mode = SkPorterDuff::ToXfermodeMode(porterDuffMode);
    SkiaShader* skiaShader = new SkiaComposeShader(shaderA, shaderB, mode, shader);
    return reinterpret_cast<jlong>(skiaShader);
#else
    return NULL;
#endif
}

static jlong ComposeShader_postCreate1(JNIEnv* env, jobject o, jlong shaderHandle,
        jlong shaderAHandle, jlong shaderBHandle, jlong modeHandle) {
#ifdef USE_OPENGL_RENDERER
    SkShader* shader = reinterpret_cast<SkShader *>(shaderHandle);
    SkiaShader* shaderA = reinterpret_cast<SkiaShader *>(shaderAHandle);
    SkiaShader* shaderB = reinterpret_cast<SkiaShader *>(shaderBHandle);
    SkXfermode* mode = reinterpret_cast<SkXfermode *>(modeHandle);
    SkXfermode::Mode skiaMode;
    if (!SkXfermode::AsMode(mode, &skiaMode)) {
        // TODO: Support other modes
        skiaMode = SkXfermode::kSrcOver_Mode;
    }
    SkiaShader* skiaShader = new SkiaComposeShader(shaderA, shaderB, skiaMode, shader);
    return reinterpret_cast<jlong>(skiaShader);
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
    { "nativeDestructor",        "(JJ)V",    (void*)Shader_destructor        },
    { "nativeSetLocalMatrix",    "(JJJ)V",   (void*)Shader_setLocalMatrix    }
};

static JNINativeMethod gBitmapShaderMethods[] = {
    { "nativeCreate",     "(JII)J",  (void*)BitmapShader_constructor },
    { "nativePostCreate", "(JJII)J", (void*)BitmapShader_postConstructor }
};

static JNINativeMethod gLinearGradientMethods[] = {
    { "nativeCreate1",     "(FFFF[I[FI)J",  (void*)LinearGradient_create1     },
    { "nativeCreate2",     "(FFFFIII)J",    (void*)LinearGradient_create2     },
    { "nativePostCreate1", "(JFFFF[I[FI)J", (void*)LinearGradient_postCreate1 },
    { "nativePostCreate2", "(JFFFFIII)J",   (void*)LinearGradient_postCreate2 }
};

static JNINativeMethod gRadialGradientMethods[] = {
    { "nativeCreate1",     "(FFF[I[FI)J",  (void*)RadialGradient_create1     },
    { "nativeCreate2",     "(FFFIII)J",    (void*)RadialGradient_create2     },
    { "nativePostCreate1", "(JFFF[I[FI)J", (void*)RadialGradient_postCreate1 },
    { "nativePostCreate2", "(JFFFIII)J",   (void*)RadialGradient_postCreate2 }
};

static JNINativeMethod gSweepGradientMethods[] = {
    { "nativeCreate1",     "(FF[I[F)J",  (void*)SweepGradient_create1     },
    { "nativeCreate2",     "(FFII)J",    (void*)SweepGradient_create2     },
    { "nativePostCreate1", "(JFF[I[F)J", (void*)SweepGradient_postCreate1 },
    { "nativePostCreate2", "(JFFII)J",   (void*)SweepGradient_postCreate2 }
};

static JNINativeMethod gComposeShaderMethods[] = {
    { "nativeCreate1",      "(JJJ)J",   (void*)ComposeShader_create1     },
    { "nativeCreate2",      "(JJI)J",   (void*)ComposeShader_create2     },
    { "nativePostCreate1",  "(JJJJ)J",  (void*)ComposeShader_postCreate1 },
    { "nativePostCreate2",  "(JJJI)J",  (void*)ComposeShader_postCreate2 }
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
