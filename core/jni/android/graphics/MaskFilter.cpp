#include "GraphicsJNI.h"
#include "SkMaskFilter.h"
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
    static void destructor(JNIEnv* env, jobject, SkMaskFilter* filter) {
        SkSafeUnref(filter);
    }

    static SkMaskFilter* createBlur(JNIEnv* env, jobject, float radius, int blurStyle) {
        SkMaskFilter* filter = SkBlurMaskFilter::Create(SkFloatToScalar(radius),
                                        (SkBlurMaskFilter::BlurStyle)blurStyle);
        ThrowIAE_IfNull(env, filter);
        return filter;
    }

    static SkMaskFilter* createEmboss(JNIEnv* env, jobject, jfloatArray dirArray, float ambient, float specular, float radius) {
        SkScalar direction[3];

        AutoJavaFloatArray autoDir(env, dirArray, 3);
        float* values = autoDir.ptr();
        for (int i = 0; i < 3; i++) {
            direction[i] = SkFloatToScalar(values[i]);
        }

        SkMaskFilter* filter =  SkBlurMaskFilter::CreateEmboss(direction,
                                                      SkFloatToScalar(ambient),
                                                      SkFloatToScalar(specular),
                                                      SkFloatToScalar(radius));
        ThrowIAE_IfNull(env, filter);
        return filter;
    }

    static SkMaskFilter* createTable(JNIEnv* env, jobject, jbyteArray jtable) {
        AutoJavaByteArray autoTable(env, jtable, 256);
        return new SkTableMaskFilter((const uint8_t*)autoTable.ptr());
    }

    static SkMaskFilter* createClipTable(JNIEnv* env, jobject, int min, int max) {
        return SkTableMaskFilter::CreateClip(min, max);
    }

    static SkMaskFilter* createGammaTable(JNIEnv* env, jobject, float gamma) {
        return SkTableMaskFilter::CreateGamma(gamma);
    }
};

static JNINativeMethod gMaskFilterMethods[] = {
    { "nativeDestructor",   "(I)V",     (void*)SkMaskFilterGlue::destructor      }
};

static JNINativeMethod gBlurMaskFilterMethods[] = {
    { "nativeConstructor",  "(FI)I",    (void*)SkMaskFilterGlue::createBlur      }
};

static JNINativeMethod gEmbossMaskFilterMethods[] = {
    { "nativeConstructor",  "([FFFF)I", (void*)SkMaskFilterGlue::createEmboss    }
};

static JNINativeMethod gTableMaskFilterMethods[] = {
    { "nativeNewTable", "([B)I", (void*)SkMaskFilterGlue::createTable    },
    { "nativeNewClip",  "(II)I", (void*)SkMaskFilterGlue::createClipTable    },
    { "nativeNewGamma", "(F)I", (void*)SkMaskFilterGlue::createGammaTable    }
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
