#include <jni.h>
#include "GraphicsJNI.h"

#include "SkShader.h"
#include "SkGradientShader.h"
#include "SkPorterDuff.h"
#include "SkComposeShader.h"
#include "SkTemplates.h"
#include "SkXfermode.h"

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

static void Shader_destructor(JNIEnv* env, jobject, SkShader* shader)
{
    shader->safeUnref();
}

static bool Shader_getLocalMatrix(JNIEnv* env, jobject, const SkShader* shader, SkMatrix* matrix)
{
    return shader ? shader->getLocalMatrix(matrix) : false;
}
 
static void Shader_setLocalMatrix(JNIEnv* env, jobject, SkShader* shader, const SkMatrix* matrix)
{
    if (shader) {
        if (NULL == matrix) {
            shader->resetLocalMatrix();
        }
        else {
            shader->setLocalMatrix(*matrix);
        }
    }
}

///////////////////////////////////////////////////////////////////////////////////////////////

static SkShader* BitmapShader_constructor(JNIEnv* env, jobject, const SkBitmap* bitmap,
                                          int tileModeX, int tileModeY)
{
    SkShader* s = SkShader::CreateBitmapShader(*bitmap,
                                        (SkShader::TileMode)tileModeX,
                                        (SkShader::TileMode)tileModeY);
    ThrowIAE_IfNull(env, s);
    return s;
}
    
///////////////////////////////////////////////////////////////////////////////////////////////

static SkShader* LinearGradient_create1(JNIEnv* env, jobject,
                                        float x0, float y0, float x1, float y1,
                                        jintArray colorArray, jfloatArray posArray, int tileMode)
{
    SkPoint pts[2];
    pts[0].set(SkFloatToScalar(x0), SkFloatToScalar(y0));
    pts[1].set(SkFloatToScalar(x1), SkFloatToScalar(y1));

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

    SkShader* shader = SkGradientShader::CreateLinear(pts,
                                reinterpret_cast<const SkColor*>(colorValues),
                                pos, count,
                                static_cast<SkShader::TileMode>(tileMode));
    env->ReleaseIntArrayElements(colorArray, const_cast<jint*>(colorValues),
                                 JNI_ABORT);
    ThrowIAE_IfNull(env, shader);
    return shader;
}

static SkShader* LinearGradient_create2(JNIEnv* env, jobject,
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

static SkShader* RadialGradient_create1(JNIEnv* env, jobject,
                                        float x, float y, float radius,
                                        jintArray colorArray, jfloatArray posArray, int tileMode)
{
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

static SkShader* RadialGradient_create2(JNIEnv* env, jobject,
                                        float x, float y, float radius,
                                        int color0, int color1, int tileMode)
{
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

///////////////////////////////////////////////////////////////////////////////

static SkShader* SweepGradient_create1(JNIEnv* env, jobject, float x, float y,
                                    jintArray jcolors, jfloatArray jpositions)
{
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
                                        int color0, int color1)
{
    SkColor colors[2];
    colors[0] = color0;
    colors[1] = color1;
    SkShader* s = SkGradientShader::CreateSweep(SkFloatToScalar(x), SkFloatToScalar(y),
                                         colors, NULL, 2);
    ThrowIAE_IfNull(env, s);
    return s;
}

///////////////////////////////////////////////////////////////////////////////////////////////

static SkShader* ComposeShader_create1(JNIEnv* env, jobject,
                                       SkShader* shaderA, SkShader* shaderB, SkXfermode* mode)
{
    return new SkComposeShader(shaderA, shaderB, mode);
}

static SkShader* ComposeShader_create2(JNIEnv* env, jobject,
                                       SkShader* shaderA, SkShader* shaderB, SkPorterDuff::Mode mode)
{
    SkAutoUnref au(SkPorterDuff::CreateXfermode(mode));

    return new SkComposeShader(shaderA, shaderB, (SkXfermode*)au.get());
}

///////////////////////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gColorMethods[] = {
    { "nativeRGBToHSV",     "(III[F)V", (void*)Color_RGBToHSV   },
    { "nativeHSVToColor",   "(I[F)I",   (void*)Color_HSVToColor }
};

static JNINativeMethod gShaderMethods[] = {
    { "nativeDestructor",        "(I)V",     (void*)Shader_destructor        },
    { "nativeGetLocalMatrix",    "(II)Z",    (void*)Shader_getLocalMatrix    },
    { "nativeSetLocalMatrix",    "(II)V",    (void*)Shader_setLocalMatrix    }
};

static JNINativeMethod gBitmapShaderMethods[] = {
    { "nativeCreate",   "(III)I",  (void*)BitmapShader_constructor }
};

static JNINativeMethod gLinearGradientMethods[] = {
    { "nativeCreate1",  "(FFFF[I[FI)I", (void*)LinearGradient_create1   },
    { "nativeCreate2",  "(FFFFIII)I",   (void*)LinearGradient_create2   }
};

static JNINativeMethod gRadialGradientMethods[] = {
    {"nativeCreate1",   "(FFF[I[FI)I",  (void*)RadialGradient_create1   },
    {"nativeCreate2",   "(FFFIII)I",    (void*)RadialGradient_create2   }
};

static JNINativeMethod gSweepGradientMethods[] = {
    {"nativeCreate1",   "(FF[I[F)I",  (void*)SweepGradient_create1   },
    {"nativeCreate2",   "(FFII)I",    (void*)SweepGradient_create2   }
};

static JNINativeMethod gComposeShaderMethods[] = {
    {"nativeCreate1",  "(III)I",    (void*)ComposeShader_create1 },
    {"nativeCreate2",  "(III)I",    (void*)ComposeShader_create2 }
};

#include <android_runtime/AndroidRuntime.h>

#define REG(env, name, array)                                                                       \
    result = android::AndroidRuntime::registerNativeMethods(env, name, array, SK_ARRAY_COUNT(array));  \
    if (result < 0) return result

int register_android_graphics_Shader(JNIEnv* env);
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

