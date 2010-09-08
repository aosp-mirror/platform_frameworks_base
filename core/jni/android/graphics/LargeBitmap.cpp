#define LOG_TAG "LargeBitmap"

#include "SkBitmap.h"
#include "SkImageEncoder.h"
#include "SkColorPriv.h"
#include "GraphicsJNI.h"
#include "SkDither.h"
#include "SkUnPreMultiply.h"
#include "SkUtils.h"
#include "SkTemplates.h"
#include "SkPixelRef.h"
#include "BitmapFactory.h"
#include "AutoDecodeCancel.h"
#include "SkLargeBitmap.h"

#include <binder/Parcel.h>
#include "android_util_Binder.h"
#include "android_nio_utils.h"
#include "CreateJavaOutputStreamAdaptor.h"

#include <jni.h>

#if 0
    #define TRACE_BITMAP(code)  code
#else
    #define TRACE_BITMAP(code)
#endif

static jobject nullObjectReturn(const char msg[]) {
    if (msg) {
        SkDebugf("--- %s\n", msg);
    }
    return NULL;
}

/*
 * nine patch not supported
 *
 * purgeable not supported
 * reportSizeToVM not supported
 */
static jobject nativeDecodeRegion(JNIEnv* env, jobject, SkLargeBitmap *bm,
        int start_x, int start_y, int width, int height, jobject options) {
    SkImageDecoder *decoder = bm->getDecoder();
    int sampleSize = 1;
    SkBitmap::Config prefConfig = SkBitmap::kNo_Config;
    bool doDither = true;

    if (NULL != options) {
        sampleSize = env->GetIntField(options, gOptions_sampleSizeFieldID);
        // initialize these, in case we fail later on
        env->SetIntField(options, gOptions_widthFieldID, -1);
        env->SetIntField(options, gOptions_heightFieldID, -1);
        env->SetObjectField(options, gOptions_mimeFieldID, 0);

        jobject jconfig = env->GetObjectField(options, gOptions_configFieldID);
        prefConfig = GraphicsJNI::getNativeBitmapConfig(env, jconfig);
        doDither = env->GetBooleanField(options, gOptions_ditherFieldID);
    }

    decoder->setDitherImage(doDither);
    SkBitmap*           bitmap = new SkBitmap;
    SkAutoTDelete<SkBitmap>       adb(bitmap);
    AutoDecoderCancel   adc(options, decoder);

    // To fix the race condition in case "requestCancelDecode"
    // happens earlier than AutoDecoderCancel object is added
    // to the gAutoDecoderCancelMutex linked list.
    if (NULL != options && env->GetBooleanField(options, gOptions_mCancelID)) {
        return nullObjectReturn("gOptions_mCancelID");;
    }

    SkIRect region;
    region.fLeft = start_x;
    region.fTop = start_y;
    region.fRight = start_x + width;
    region.fBottom = start_y + height;

    if (!bm->decodeRegion(bitmap, region, prefConfig, sampleSize)) {
        return nullObjectReturn("decoder->decodeRegion returned false");
    }

    // update options (if any)
    if (NULL != options) {
        env->SetIntField(options, gOptions_widthFieldID, bitmap->width());
        env->SetIntField(options, gOptions_heightFieldID, bitmap->height());
        // TODO: set the mimeType field with the data from the codec.
        // but how to reuse a set of strings, rather than allocating new one
        // each time?
        env->SetObjectField(options, gOptions_mimeFieldID,
                            getMimeTypeString(env, decoder->getFormat()));
    }

    // detach bitmap from its autotdeleter, since we want to own it now
    adb.detach();

    SkPixelRef* pr;
    pr = bitmap->pixelRef();
    // promise we will never change our pixels (great for sharing and pictures)
    pr->setImmutable();
    // now create the java bitmap
    return GraphicsJNI::createBitmap(env, bitmap, false, NULL);
}

static int nativeGetHeight(JNIEnv* env, jobject, SkLargeBitmap *bm) {
    return bm->getHeight();
}

static int nativeGetWidth(JNIEnv* env, jobject, SkLargeBitmap *bm) {
    return bm->getWidth();
}

static void nativeClean(JNIEnv* env, jobject, SkLargeBitmap *bm) {
    delete bm;
}

///////////////////////////////////////////////////////////////////////////////

#include <android_runtime/AndroidRuntime.h>

static JNINativeMethod gLargeBitmapMethods[] = {
    {   "nativeDecodeRegion",
        "(IIIIILandroid/graphics/BitmapFactory$Options;)Landroid/graphics/Bitmap;",
        (void*)nativeDecodeRegion},
    {   "nativeGetHeight", "(I)I", (void*)nativeGetHeight},
    {   "nativeGetWidth", "(I)I", (void*)nativeGetWidth},
    {   "nativeClean", "(I)V", (void*)nativeClean},
};

#define kClassPathName  "android/graphics/LargeBitmap"

int register_android_graphics_LargeBitmap(JNIEnv* env);
int register_android_graphics_LargeBitmap(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env, kClassPathName,
                                gLargeBitmapMethods, SK_ARRAY_COUNT(gLargeBitmapMethods));
}

