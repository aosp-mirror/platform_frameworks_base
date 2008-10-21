#include <utils/ResourceTypes.h>

#include "SkRegion.h"
#include "GraphicsJNI.h"

#include "JNIHelp.h"

extern void NinePatch_Draw(SkCanvas* canvas, const SkRect& bounds,
                const SkBitmap& bitmap, const android::Res_png_9patch& chunk,
                           const SkPaint* paint, SkRegion** outRegion);
    
using namespace android;

class SkNinePatchGlue {
public:
    static jboolean isNinePatchChunk(JNIEnv* env, jobject, jbyteArray obj)
    {
        if (NULL == obj) {
            return false;
        }
        if (env->GetArrayLength(obj) < (int)sizeof(Res_png_9patch)) {
            return false;
        }
        jbyte* array = (jbyte*)env->GetPrimitiveArrayCritical(obj, 0);
        if (array != NULL)
        {
            Res_png_9patch* chunk = (Res_png_9patch*)array;
            int8_t numXDivs = chunk->numXDivs;
            env->ReleasePrimitiveArrayCritical(obj, array, 0);
            return array[0] != -1;
        }
        return false;
    }

    static void validateNinePatchChunk(JNIEnv* env, jobject, jint, jbyteArray obj)
    {
        if (env->GetArrayLength(obj) < (int) (sizeof(Res_png_9patch))) {
            jniThrowException(env, "java/lang/RuntimeException",
                              "Array too small for chunk.");
            return;
        }

        // XXX Also check that dimensions are correct.
    }

    static void draw(JNIEnv* env, SkCanvas* canvas, SkRect& bounds,
                      const SkBitmap* bitmap, jbyteArray chunkObj, const SkPaint* paint)
    {
        jbyte* array = env->GetByteArrayElements(chunkObj, 0);
        if (array != NULL)
        {
            size_t chunkSize = env->GetArrayLength(chunkObj);
            void* deserializedArray = alloca(chunkSize);
            Res_png_9patch* chunk = (Res_png_9patch*) deserializedArray;
            assert(chunkSize == ((Res_png_9patch*) array)->serializedSize());
            memcpy(chunk, array, chunkSize);
            Res_png_9patch::deserialize(chunk);            
            NinePatch_Draw(canvas, bounds, *bitmap, *chunk, paint, NULL);
            env->ReleaseByteArrayElements(chunkObj, array, 0);
        }
    } 

    static void drawF(JNIEnv* env, jobject, SkCanvas* canvas, jobject boundsRectF,
                      const SkBitmap* bitmap, jbyteArray chunkObj, const SkPaint* paint)
    {
        SkASSERT(canvas);
        SkASSERT(boundsRectF);
        SkASSERT(bitmap);
        SkASSERT(chunkObj);
        // paint is optional

        SkRect      bounds;
        GraphicsJNI::jrectf_to_rect(env, boundsRectF, &bounds);

        draw(env, canvas, bounds, bitmap, chunkObj, paint);
    }
 
    static void drawI(JNIEnv* env, jobject, SkCanvas* canvas, jobject boundsRect,
                      const SkBitmap* bitmap, jbyteArray chunkObj, const SkPaint* paint)
    {
        SkASSERT(canvas);
        SkASSERT(boundsRect);
        SkASSERT(bitmap);
        SkASSERT(chunkObj);
        // paint is optional

        SkRect      bounds;
        GraphicsJNI::jrect_to_rect(env, boundsRect, &bounds);
        draw(env, canvas, bounds, bitmap, chunkObj, paint);
    }
    
    static jint getTransparentRegion(JNIEnv* env, jobject,
                    const SkBitmap* bitmap, jbyteArray chunkObj,
                    jobject boundsRect)
    {
        SkASSERT(bitmap);
        SkASSERT(chunkObj);
        SkASSERT(boundsRect);
        
        SkRect      bounds;
        GraphicsJNI::jrect_to_rect(env, boundsRect, &bounds);
        jbyte* array = (jbyte*)env->GetByteArrayElements(chunkObj, 0);
        if (array != NULL)
        {
            size_t chunkSize = env->GetArrayLength(chunkObj);
            void* deserializedArray = alloca(chunkSize);
            Res_png_9patch* chunk = (Res_png_9patch*) deserializedArray;
            assert(chunkSize == ((Res_png_9patch*) array)->serializedSize());
            memcpy(chunk, array, chunkSize);
            Res_png_9patch::deserialize(chunk);
            SkRegion* region = NULL;
            NinePatch_Draw(NULL, bounds, *bitmap, *chunk, NULL, &region);
            env->ReleaseByteArrayElements(chunkObj, array, 0);
            return (jint)region;
        }
        
        return 0;
    }

};

/////////////////////////////////////////////////////////////////////////////////////////

#include <android_runtime/AndroidRuntime.h>

static JNINativeMethod gNinePatchMethods[] = {
    { "isNinePatchChunk", "([B)Z",                      (void*)SkNinePatchGlue::isNinePatchChunk   },
    { "validateNinePatchChunk", "(I[B)V",               (void*)SkNinePatchGlue::validateNinePatchChunk   },
    { "nativeDraw", "(ILandroid/graphics/RectF;I[BI)V", (void*)SkNinePatchGlue::drawF   },
    { "nativeDraw", "(ILandroid/graphics/Rect;I[BI)V",  (void*)SkNinePatchGlue::drawI   },
    { "nativeGetTransparentRegion", "(I[BLandroid/graphics/Rect;)I", 
                                                        (void*)SkNinePatchGlue::getTransparentRegion   }
};

int register_android_graphics_NinePatch(JNIEnv* env);
int register_android_graphics_NinePatch(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env,
                                                       "android/graphics/NinePatch",
                                                       gNinePatchMethods,
                                                       SK_ARRAY_COUNT(gNinePatchMethods));
}
