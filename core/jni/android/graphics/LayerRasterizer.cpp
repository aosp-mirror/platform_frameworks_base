#include "SkLayerRasterizer.h"
#include <jni.h>

class SkLayerRasterizerGlue {
public:
    static jlong create(JNIEnv* env, jobject) {
        return reinterpret_cast<jlong>(new SkLayerRasterizer());
    }

    static void addLayer(JNIEnv* env, jobject, jlong layerHandle, jlong paintHandle, jfloat dx, jfloat dy) {
        SkLayerRasterizer* layer = reinterpret_cast<SkLayerRasterizer *>(layerHandle);
        const SkPaint* paint = reinterpret_cast<SkPaint *>(paintHandle);
        SkASSERT(layer);
        SkASSERT(paint);
        layer->addLayer(*paint, SkFloatToScalar(dx), SkFloatToScalar(dy));
    }
};

/////////////////////////////////////////////////////////////////////////////////////////

#include <android_runtime/AndroidRuntime.h>

static JNINativeMethod gLayerRasterizerMethods[] = {
    { "nativeConstructor",  "()J",      (void*)SkLayerRasterizerGlue::create    },
    { "nativeAddLayer",     "(JJFF)V",  (void*)SkLayerRasterizerGlue::addLayer  }
};

int register_android_graphics_LayerRasterizer(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env,
                                                       "android/graphics/LayerRasterizer",
                                                       gLayerRasterizerMethods,
                                                       SK_ARRAY_COUNT(gLayerRasterizerMethods));
}
