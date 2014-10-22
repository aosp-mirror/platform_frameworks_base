#include "GraphicsJNI.h"
#include "SkMaskFilter.h"
#include "SkBlurMask.h"
#include "SkBlurMaskFilter.h"
#include "SkTableMaskFilter.h"

#include <jni.h>

static void ThrowIAE_IfNull(JNIEnv* env, void* ptr) {
    if (NULL == ptr) {
        doThrowIAE(env);
    }
}

class SkMaskFilterGlue {
public:
    static void destructor(JNIEnv* env, jobject, jlong filterHandle) {
        SkMaskFilter* filter = reinterpret_cast<SkMaskFilter *>(filterHandle);
        SkSafeUnref(filter);
    }

    static jlong createBlur(JNIEnv* env, jobject, jfloat radius, jint blurStyle) {
        SkScalar sigma = SkBlurMask::ConvertRadiusToSigma(radius);
        SkMaskFilter* filter = SkBlurMaskFilter::Create((SkBlurStyle)blurStyle, sigma);
        ThrowIAE_IfNull(env, filter);
        return reinterpret_cast<jlong>(filter);
    }

    static jlong createEmboss(JNIEnv* env, jobject, jfloatArray dirArray, jfloat ambient, jfloat specular, jfloat radius) {
        SkScalar direction[3];

        AutoJavaFloatArray autoDir(env, dirArray, 3);
        float* values = autoDir.ptr();
        for (int i = 0; i < 3; i++) {
            direction[i] = values[i];
        }

        SkScalar sigma = SkBlurMask::ConvertRadiusToSigma(radius);
        SkMaskFilter* filter =  SkBlurMaskFilter::CreateEmboss(sigma,
                direction, ambient, specular);
        ThrowIAE_IfNull(env, filter);
        return reinterpret_cast<jlong>(filter);
    }

    static jlong createTable(JNIEnv* env, jobject, jbyteArray jtable) {
        AutoJavaByteArray autoTable(env, jtable, 256);
        SkMaskFilter* filter = SkTableMaskFilter::Create((const uint8_t*)autoTable.ptr());
        return reinterpret_cast<jlong>(filter);
    }

    static jlong createClipTable(JNIEnv* env, jobject, jint min, jint max) {
        SkMaskFilter* filter = SkTableMaskFilter::CreateClip(min, max);
        return reinterpret_cast<jlong>(filter);
    }

    static jlong createGammaTable(JNIEnv* env, jobject, jfloat gamma) {
        SkMaskFilter* filter = SkTableMaskFilter::CreateGamma(gamma);
        return reinterpret_cast<jlong>(filter);
    }
};

static JNINativeMethod gMaskFilterMethods[] = {
    { "nativeDestructor",   "(J)V",     (void*)SkMaskFilterGlue::destructor      }
};

static JNINativeMethod gBlurMaskFilterMethods[] = {
    { "nativeConstructor",  "(FI)J",    (void*)SkMaskFilterGlue::createBlur      }
};

static JNINativeMethod gEmbossMaskFilterMethods[] = {
    { "nativeConstructor",  "([FFFF)J", (void*)SkMaskFilterGlue::createEmboss    }
};

static JNINativeMethod gTableMaskFilterMethods[] = {
    { "nativeNewTable", "([B)J", (void*)SkMaskFilterGlue::createTable    },
    { "nativeNewClip",  "(II)J", (void*)SkMaskFilterGlue::createClipTable    },
    { "nativeNewGamma", "(F)J", (void*)SkMaskFilterGlue::createGammaTable    }
};

#include <android_runtime/AndroidRuntime.h>

#define REG(env, name, array)                                                                       \
    result = android::AndroidRuntime::registerNativeMethods(env, name, array, SK_ARRAY_COUNT(array));  \
    if (result < 0) return result

int register_android_graphics_MaskFilter(JNIEnv* env)
{
    int result;

    REG(env, "android/graphics/MaskFilter", gMaskFilterMethods);
    REG(env, "android/graphics/BlurMaskFilter", gBlurMaskFilterMethods);
    REG(env, "android/graphics/EmbossMaskFilter", gEmbossMaskFilterMethods);
    REG(env, "android/graphics/TableMaskFilter", gTableMaskFilterMethods);

    return 0;
}
