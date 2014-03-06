/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "SurfaceControl"

#include <stdio.h>

#include "jni.h"
#include "JNIHelp.h"

#include "android_os_Parcel.h"
#include "android_util_Binder.h"
#include "android/graphics/GraphicsJNI.h"
#include "android/graphics/Region.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_view_SurfaceSession.h>

#include <gui/Surface.h>
#include <gui/SurfaceComposerClient.h>

#include <ui/DisplayInfo.h>
#include <ui/Rect.h>
#include <ui/Region.h>

#include <utils/Log.h>

#include <ScopedUtfChars.h>

// ----------------------------------------------------------------------------

namespace android {

static const char* const OutOfResourcesException =
    "android/view/Surface$OutOfResourcesException";

static struct {
    jfieldID width;
    jfieldID height;
    jfieldID refreshRate;
    jfieldID density;
    jfieldID xDpi;
    jfieldID yDpi;
    jfieldID secure;
} gPhysicalDisplayInfoClassInfo;


class ScreenshotPixelRef : public SkPixelRef {
public:
    ScreenshotPixelRef(const SkImageInfo& info, ScreenshotClient* screenshot) :
      SkPixelRef(info),
      mScreenshot(screenshot) {
        setImmutable();
    }

    virtual ~ScreenshotPixelRef() {
        delete mScreenshot;
    }

protected:
    // overrides from SkPixelRef
    virtual void* onLockPixels(SkColorTable** ct) {
        *ct = NULL;
        return (void*)mScreenshot->getPixels();
    }

    virtual void onUnlockPixels() {
    }

    SK_DECLARE_UNFLATTENABLE_OBJECT()
private:
    ScreenshotClient* mScreenshot;

    typedef SkPixelRef INHERITED;
};


// ----------------------------------------------------------------------------

static jint nativeCreate(JNIEnv* env, jclass clazz, jobject sessionObj,
        jstring nameStr, jint w, jint h, jint format, jint flags) {
    ScopedUtfChars name(env, nameStr);
    sp<SurfaceComposerClient> client(android_view_SurfaceSession_getClient(env, sessionObj));
    sp<SurfaceControl> surface = client->createSurface(
            String8(name.c_str()), w, h, format, flags);
    if (surface == NULL) {
        jniThrowException(env, OutOfResourcesException, NULL);
        return 0;
    }
    surface->incStrong((void *)nativeCreate);
    return int(surface.get());
}

static void nativeRelease(JNIEnv* env, jclass clazz, jint nativeObject) {
    sp<SurfaceControl> ctrl(reinterpret_cast<SurfaceControl *>(nativeObject));
    ctrl->decStrong((void *)nativeCreate);
}

static void nativeDestroy(JNIEnv* env, jclass clazz, jint nativeObject) {
    sp<SurfaceControl> ctrl(reinterpret_cast<SurfaceControl *>(nativeObject));
    ctrl->clear();
    ctrl->decStrong((void *)nativeCreate);
}

static jobject nativeScreenshotBitmap(JNIEnv* env, jclass clazz, jobject displayTokenObj,
        jint width, jint height, jint minLayer, jint maxLayer, bool allLayers) {
    sp<IBinder> displayToken = ibinderForJavaObject(env, displayTokenObj);
    if (displayToken == NULL) {
        return NULL;
    }

    ScreenshotClient* screenshot = new ScreenshotClient();
    status_t res = (width > 0 && height > 0)
            ? (allLayers
                    ? screenshot->update(displayToken, width, height)
                    : screenshot->update(displayToken, width, height, minLayer, maxLayer))
            : screenshot->update(displayToken);
    if (res != NO_ERROR) {
        delete screenshot;
        return NULL;
    }

    SkImageInfo screenshotInfo;
    screenshotInfo.fWidth = screenshot->getWidth();
    screenshotInfo.fHeight = screenshot->getHeight();

    switch (screenshot->getFormat()) {
        case PIXEL_FORMAT_RGBX_8888: {
            screenshotInfo.fColorType = kRGBA_8888_SkColorType;
            screenshotInfo.fAlphaType = kIgnore_SkAlphaType;
            break;
        }
        case PIXEL_FORMAT_RGBA_8888: {
            screenshotInfo.fColorType = kRGBA_8888_SkColorType;
            screenshotInfo.fAlphaType = kPremul_SkAlphaType;
            break;
        }
        case PIXEL_FORMAT_RGB_565: {
            screenshotInfo.fColorType = kRGB_565_SkColorType;
            screenshotInfo.fAlphaType = kIgnore_SkAlphaType;
            break;
        }
        default: {
            delete screenshot;
            return NULL;
        }
    }

    // takes ownership of ScreenshotClient
    ScreenshotPixelRef* pixels = new ScreenshotPixelRef(screenshotInfo, screenshot);
    ssize_t rowBytes = screenshot->getStride() * android::bytesPerPixel(screenshot->getFormat());

    SkBitmap* bitmap = new SkBitmap();
    bitmap->setConfig(screenshotInfo, rowBytes);
    if (screenshotInfo.fWidth > 0 && screenshotInfo.fHeight > 0) {
        bitmap->setPixelRef(pixels)->unref();
        bitmap->lockPixels();
    } else {
        // be safe with an empty bitmap.
        delete pixels;
        bitmap->setPixels(NULL);
    }

    return GraphicsJNI::createBitmap(env, bitmap,
            GraphicsJNI::kBitmapCreateFlag_Premultiplied, NULL);
}

static void nativeScreenshot(JNIEnv* env, jclass clazz,
        jobject displayTokenObj, jobject surfaceObj,
        jint width, jint height, jint minLayer, jint maxLayer, bool allLayers) {
    sp<IBinder> displayToken = ibinderForJavaObject(env, displayTokenObj);
    if (displayToken != NULL) {
        sp<Surface> consumer = android_view_Surface_getSurface(env, surfaceObj);
        if (consumer != NULL) {
            if (allLayers) {
                minLayer = 0;
                maxLayer = -1;
            }
            ScreenshotClient::capture(
                    displayToken, consumer->getIGraphicBufferProducer(),
                    width, height, uint32_t(minLayer), uint32_t(maxLayer));
        }
    }
}

static void nativeOpenTransaction(JNIEnv* env, jclass clazz) {
    SurfaceComposerClient::openGlobalTransaction();
}

static void nativeCloseTransaction(JNIEnv* env, jclass clazz) {
    SurfaceComposerClient::closeGlobalTransaction();
}

static void nativeSetAnimationTransaction(JNIEnv* env, jclass clazz) {
    SurfaceComposerClient::setAnimationTransaction();
}

static void nativeSetLayer(JNIEnv* env, jclass clazz, jint nativeObject, jint zorder) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    status_t err = ctrl->setLayer(zorder);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetPosition(JNIEnv* env, jclass clazz, jint nativeObject, jfloat x, jfloat y) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    status_t err = ctrl->setPosition(x, y);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetSize(JNIEnv* env, jclass clazz, jint nativeObject, jint w, jint h) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    status_t err = ctrl->setSize(w, h);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetFlags(JNIEnv* env, jclass clazz, jint nativeObject, jint flags, jint mask) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    status_t err = ctrl->setFlags(flags, mask);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetTransparentRegionHint(JNIEnv* env, jclass clazz, jint nativeObject, jobject regionObj) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    SkRegion* region = android_graphics_Region_getSkRegion(env, regionObj);
    if (!region) {
        doThrowIAE(env);
        return;
    }

    const SkIRect& b(region->getBounds());
    Region reg(Rect(b.fLeft, b.fTop, b.fRight, b.fBottom));
    if (region->isComplex()) {
        SkRegion::Iterator it(*region);
        while (!it.done()) {
            const SkIRect& r(it.rect());
            reg.addRectUnchecked(r.fLeft, r.fTop, r.fRight, r.fBottom);
            it.next();
        }
    }

    status_t err = ctrl->setTransparentRegionHint(reg);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetAlpha(JNIEnv* env, jclass clazz, jint nativeObject, jfloat alpha) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    status_t err = ctrl->setAlpha(alpha);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetMatrix(JNIEnv* env, jclass clazz, jint nativeObject,
        jfloat dsdx, jfloat dtdx, jfloat dsdy, jfloat dtdy) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    status_t err = ctrl->setMatrix(dsdx, dtdx, dsdy, dtdy);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetWindowCrop(JNIEnv* env, jclass clazz, jint nativeObject,
        jint l, jint t, jint r, jint b) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    Rect crop(l, t, r, b);
    status_t err = ctrl->setCrop(crop);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static void nativeSetLayerStack(JNIEnv* env, jclass clazz, jint nativeObject, jint layerStack) {
    SurfaceControl* const ctrl = reinterpret_cast<SurfaceControl *>(nativeObject);
    status_t err = ctrl->setLayerStack(layerStack);
    if (err < 0 && err != NO_INIT) {
        doThrowIAE(env);
    }
}

static jobject nativeGetBuiltInDisplay(JNIEnv* env, jclass clazz, jint id) {
    sp<IBinder> token(SurfaceComposerClient::getBuiltInDisplay(id));
    return javaObjectForIBinder(env, token);
}

static jobject nativeCreateDisplay(JNIEnv* env, jclass clazz, jstring nameObj,
        jboolean secure) {
    ScopedUtfChars name(env, nameObj);
    sp<IBinder> token(SurfaceComposerClient::createDisplay(
            String8(name.c_str()), bool(secure)));
    return javaObjectForIBinder(env, token);
}

static void nativeDestroyDisplay(JNIEnv* env, jclass clazz, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;
    SurfaceComposerClient::destroyDisplay(token);
}

static void nativeSetDisplaySurface(JNIEnv* env, jclass clazz,
        jobject tokenObj, jint nativeSurfaceObject) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;
    sp<IGraphicBufferProducer> bufferProducer;
    sp<Surface> sur(reinterpret_cast<Surface *>(nativeSurfaceObject));
    if (sur != NULL) {
        bufferProducer = sur->getIGraphicBufferProducer();
    }
    SurfaceComposerClient::setDisplaySurface(token, bufferProducer);
}

static void nativeSetDisplayLayerStack(JNIEnv* env, jclass clazz,
        jobject tokenObj, jint layerStack) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;

    SurfaceComposerClient::setDisplayLayerStack(token, layerStack);
}

static void nativeSetDisplayProjection(JNIEnv* env, jclass clazz,
        jobject tokenObj, jint orientation,
        jint layerStackRect_left, jint layerStackRect_top, jint layerStackRect_right, jint layerStackRect_bottom,
        jint displayRect_left, jint displayRect_top, jint displayRect_right, jint displayRect_bottom) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;
    Rect layerStackRect(layerStackRect_left, layerStackRect_top, layerStackRect_right, layerStackRect_bottom);
    Rect displayRect(displayRect_left, displayRect_top, displayRect_right, displayRect_bottom);
    SurfaceComposerClient::setDisplayProjection(token, orientation, layerStackRect, displayRect);
}

static jboolean nativeGetDisplayInfo(JNIEnv* env, jclass clazz,
        jobject tokenObj, jobject infoObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return JNI_FALSE;

    DisplayInfo info;
    if (SurfaceComposerClient::getDisplayInfo(token, &info)) {
        return JNI_FALSE;
    }

    env->SetIntField(infoObj, gPhysicalDisplayInfoClassInfo.width, info.w);
    env->SetIntField(infoObj, gPhysicalDisplayInfoClassInfo.height, info.h);
    env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.refreshRate, info.fps);
    env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.density, info.density);
    env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.xDpi, info.xdpi);
    env->SetFloatField(infoObj, gPhysicalDisplayInfoClassInfo.yDpi, info.ydpi);
    env->SetBooleanField(infoObj, gPhysicalDisplayInfoClassInfo.secure, info.secure);
    return JNI_TRUE;
}

static void nativeBlankDisplay(JNIEnv* env, jclass clazz, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;

    ALOGD_IF_SLOW(100, "Excessive delay in blankDisplay() while turning screen off");
    SurfaceComposerClient::blankDisplay(token);
}

static void nativeUnblankDisplay(JNIEnv* env, jclass clazz, jobject tokenObj) {
    sp<IBinder> token(ibinderForJavaObject(env, tokenObj));
    if (token == NULL) return;

    ALOGD_IF_SLOW(100, "Excessive delay in unblankDisplay() while turning screen on");
    SurfaceComposerClient::unblankDisplay(token);
}

// ----------------------------------------------------------------------------

static JNINativeMethod sSurfaceControlMethods[] = {
    {"nativeCreate", "(Landroid/view/SurfaceSession;Ljava/lang/String;IIII)I",
            (void*)nativeCreate },
    {"nativeRelease", "(I)V",
            (void*)nativeRelease },
    {"nativeDestroy", "(I)V",
            (void*)nativeDestroy },
    {"nativeScreenshot", "(Landroid/os/IBinder;IIIIZ)Landroid/graphics/Bitmap;",
            (void*)nativeScreenshotBitmap },
    {"nativeScreenshot", "(Landroid/os/IBinder;Landroid/view/Surface;IIIIZ)V",
            (void*)nativeScreenshot },
    {"nativeOpenTransaction", "()V",
            (void*)nativeOpenTransaction },
    {"nativeCloseTransaction", "()V",
            (void*)nativeCloseTransaction },
    {"nativeSetAnimationTransaction", "()V",
            (void*)nativeSetAnimationTransaction },
    {"nativeSetLayer", "(II)V",
            (void*)nativeSetLayer },
    {"nativeSetPosition", "(IFF)V",
            (void*)nativeSetPosition },
    {"nativeSetSize", "(III)V",
            (void*)nativeSetSize },
    {"nativeSetTransparentRegionHint", "(ILandroid/graphics/Region;)V",
            (void*)nativeSetTransparentRegionHint },
    {"nativeSetAlpha", "(IF)V",
            (void*)nativeSetAlpha },
    {"nativeSetMatrix", "(IFFFF)V",
            (void*)nativeSetMatrix },
    {"nativeSetFlags", "(III)V",
            (void*)nativeSetFlags },
    {"nativeSetWindowCrop", "(IIIII)V",
            (void*)nativeSetWindowCrop },
    {"nativeSetLayerStack", "(II)V",
            (void*)nativeSetLayerStack },
    {"nativeGetBuiltInDisplay", "(I)Landroid/os/IBinder;",
            (void*)nativeGetBuiltInDisplay },
    {"nativeCreateDisplay", "(Ljava/lang/String;Z)Landroid/os/IBinder;",
            (void*)nativeCreateDisplay },
    {"nativeDestroyDisplay", "(Landroid/os/IBinder;)V",
            (void*)nativeDestroyDisplay },
    {"nativeSetDisplaySurface", "(Landroid/os/IBinder;I)V",
            (void*)nativeSetDisplaySurface },
    {"nativeSetDisplayLayerStack", "(Landroid/os/IBinder;I)V",
            (void*)nativeSetDisplayLayerStack },
    {"nativeSetDisplayProjection", "(Landroid/os/IBinder;IIIIIIIII)V",
            (void*)nativeSetDisplayProjection },
    {"nativeGetDisplayInfo", "(Landroid/os/IBinder;Landroid/view/SurfaceControl$PhysicalDisplayInfo;)Z",
            (void*)nativeGetDisplayInfo },
    {"nativeBlankDisplay", "(Landroid/os/IBinder;)V",
            (void*)nativeBlankDisplay },
    {"nativeUnblankDisplay", "(Landroid/os/IBinder;)V",
            (void*)nativeUnblankDisplay },
};

int register_android_view_SurfaceControl(JNIEnv* env)
{
    int err = AndroidRuntime::registerNativeMethods(env, "android/view/SurfaceControl",
            sSurfaceControlMethods, NELEM(sSurfaceControlMethods));

    jclass clazz = env->FindClass("android/view/SurfaceControl$PhysicalDisplayInfo");
    gPhysicalDisplayInfoClassInfo.width = env->GetFieldID(clazz, "width", "I");
    gPhysicalDisplayInfoClassInfo.height = env->GetFieldID(clazz, "height", "I");
    gPhysicalDisplayInfoClassInfo.refreshRate = env->GetFieldID(clazz, "refreshRate", "F");
    gPhysicalDisplayInfoClassInfo.density = env->GetFieldID(clazz, "density", "F");
    gPhysicalDisplayInfoClassInfo.xDpi = env->GetFieldID(clazz, "xDpi", "F");
    gPhysicalDisplayInfoClassInfo.yDpi = env->GetFieldID(clazz, "yDpi", "F");
    gPhysicalDisplayInfoClassInfo.secure = env->GetFieldID(clazz, "secure", "Z");
    return err;
}

};
