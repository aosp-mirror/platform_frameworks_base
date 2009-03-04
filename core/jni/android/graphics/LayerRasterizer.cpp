#include "SkLayerRasterizer.h"
#include <jni.h>

class SkLayerRasterizerGlue {
public:
    static SkRasterizer* create(JNIEnv* env, jobject) {
        return new SkLayerRasterizer();
    }

    static void addLayer(JNIEnv* env, jobject, SkLayerRasterizer* layer, const SkPaint* paint, float dx, float dy) {
        SkASSERT(layer);
        SkASSERT(paint);
        layer->addLayer(*paint, SkFloatToScalar(dx), SkFloatToScalar(dy));
    } 
};

/////////////////////////////////////////////////////////////////////////////////////////

#include <android_runtime/AndroidRuntime.h>

static JNINativeMethod gLayerRasterizerMethods[] = {
    { "nativeConstructor",  "()I",      (void*)SkLayerRasterizerGlue::create    },
    { "nativeAddLayer",     "(IIFF)V",  (void*)SkLayerRasterizerGlue::addLayer  }
};

int register_android_graphics_LayerRasterizer(JNIEnv* env);
int register_android_graphics_LayerRasterizer(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env,
                                                       "android/graphics/LayerRasterizer",
                                                       gLayerRasterizerMethods,
                                                       SK_ARRAY_COUNT(gLayerRasterizerMethods));
}

