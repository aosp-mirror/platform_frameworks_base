#include "GraphicsJNI.h"
#include "SkMaskFilter.h"
#include "SkBlurMaskFilter.h"
#include "SkBlurTypes.h"
#include "SkTableMaskFilter.h"

static void ThrowIAE_IfNull(JNIEnv* env, void* ptr) {
    if (NULL == ptr) {
        doThrowIAE(env);
    }
}

// From https://skia.googlesource.com/skia/+/d74c99a3cd5eef5f16b2eb226e6b45fe523c8552/src/core/SkBlurMask.cpp#28
static constexpr float kBLUR_SIGMA_SCALE = 0.57735f;

static float convertRadiusToSigma(float radius) {
    return radius > 0 ? kBLUR_SIGMA_SCALE * radius + 0.5f : 0.0f;
}

class SkMaskFilterGlue {
public:
    static void destructor(JNIEnv* env, jobject, jlong filterHandle) {
        SkMaskFilter* filter = reinterpret_cast<SkMaskFilter *>(filterHandle);
        SkSafeUnref(filter);
    }

    static jlong createBlur(JNIEnv* env, jobject, jfloat radius, jint blurStyle) {
        SkScalar sigma = convertRadiusToSigma(radius);
        SkMaskFilter* filter = SkMaskFilter::MakeBlur((SkBlurStyle)blurStyle, sigma).release();
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

        SkScalar sigma = convertRadiusToSigma(radius);
        SkMaskFilter* filter =  SkBlurMaskFilter::MakeEmboss(sigma,
                direction, ambient, specular).release();
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

static const JNINativeMethod gMaskFilterMethods[] = {
    { "nativeDestructor",   "(J)V",     (void*)SkMaskFilterGlue::destructor      }
};

static const JNINativeMethod gBlurMaskFilterMethods[] = {
    { "nativeConstructor",  "(FI)J",    (void*)SkMaskFilterGlue::createBlur      }
};

static const JNINativeMethod gEmbossMaskFilterMethods[] = {
    { "nativeConstructor",  "([FFFF)J", (void*)SkMaskFilterGlue::createEmboss    }
};

static const JNINativeMethod gTableMaskFilterMethods[] = {
    { "nativeNewTable", "([B)J", (void*)SkMaskFilterGlue::createTable    },
    { "nativeNewClip",  "(II)J", (void*)SkMaskFilterGlue::createClipTable    },
    { "nativeNewGamma", "(F)J", (void*)SkMaskFilterGlue::createGammaTable    }
};

int register_android_graphics_MaskFilter(JNIEnv* env)
{
    android::RegisterMethodsOrDie(env, "android/graphics/MaskFilter", gMaskFilterMethods,
                                  NELEM(gMaskFilterMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/BlurMaskFilter", gBlurMaskFilterMethods,
                                  NELEM(gBlurMaskFilterMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/EmbossMaskFilter",
                                  gEmbossMaskFilterMethods, NELEM(gEmbossMaskFilterMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/TableMaskFilter", gTableMaskFilterMethods,
                                  NELEM(gTableMaskFilterMethods));

    return 0;
}
