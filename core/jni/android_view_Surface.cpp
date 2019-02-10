/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "Surface"

#include <stdio.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android_os_Parcel.h"
#include "android/graphics/GraphicBuffer.h"
#include "android/graphics/GraphicsJNI.h"

#include "core_jni_helpers.h"
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>
#include <android_runtime/Log.h>

#include <binder/Parcel.h>

#include <gui/Surface.h>
#include <gui/view/Surface.h>
#include <gui/SurfaceControl.h>
#include <gui/GLConsumer.h>

#include <ui/Rect.h>
#include <ui/Region.h>

#include <SkCanvas.h>
#include <SkBitmap.h>
#include <SkImage.h>
#include <SkRegion.h>

#include <utils/misc.h>
#include <utils/Log.h>

#include <nativehelper/ScopedUtfChars.h>

#include <AnimationContext.h>
#include <FrameInfo.h>
#include <RenderNode.h>
#include <renderthread/RenderProxy.h>

// ----------------------------------------------------------------------------

namespace android {

using ui::Dataspace;

static const char* const OutOfResourcesException =
    "android/view/Surface$OutOfResourcesException";

static struct {
    jclass clazz;
    jfieldID mNativeObject;
    jfieldID mLock;
    jmethodID ctor;
} gSurfaceClassInfo;

static struct {
    jfieldID left;
    jfieldID top;
    jfieldID right;
    jfieldID bottom;
} gRectClassInfo;

// ----------------------------------------------------------------------------

// this is just a pointer we use to pass to inc/decStrong
static const void *sRefBaseOwner;

bool android_view_Surface_isInstanceOf(JNIEnv* env, jobject obj) {
    return env->IsInstanceOf(obj, gSurfaceClassInfo.clazz);
}

sp<ANativeWindow> android_view_Surface_getNativeWindow(JNIEnv* env, jobject surfaceObj) {
    return android_view_Surface_getSurface(env, surfaceObj);
}

sp<Surface> android_view_Surface_getSurface(JNIEnv* env, jobject surfaceObj) {
    sp<Surface> sur;
    jobject lock = env->GetObjectField(surfaceObj,
            gSurfaceClassInfo.mLock);
    if (env->MonitorEnter(lock) == JNI_OK) {
        sur = reinterpret_cast<Surface *>(
                env->GetLongField(surfaceObj, gSurfaceClassInfo.mNativeObject));
        env->MonitorExit(lock);
    }
    env->DeleteLocalRef(lock);
    return sur;
}

jobject android_view_Surface_createFromSurface(JNIEnv* env, const sp<Surface>& surface) {
    jobject surfaceObj = env->NewObject(gSurfaceClassInfo.clazz,
            gSurfaceClassInfo.ctor, (jlong)surface.get());
    if (surfaceObj == NULL) {
        if (env->ExceptionCheck()) {
            ALOGE("Could not create instance of Surface from IGraphicBufferProducer.");
            LOGE_EX(env);
            env->ExceptionClear();
        }
        return NULL;
    }
    surface->incStrong(&sRefBaseOwner);
    return surfaceObj;
}

jobject android_view_Surface_createFromIGraphicBufferProducer(JNIEnv* env,
        const sp<IGraphicBufferProducer>& bufferProducer) {
    if (bufferProducer == NULL) {
        return NULL;
    }

    sp<Surface> surface(new Surface(bufferProducer, true));
    return android_view_Surface_createFromSurface(env, surface);
}

int android_view_Surface_mapPublicFormatToHalFormat(PublicFormat f) {

    switch(f) {
        case PublicFormat::JPEG:
        case PublicFormat::DEPTH_POINT_CLOUD:
        case PublicFormat::DEPTH_JPEG:
        case PublicFormat::HEIC:
            return HAL_PIXEL_FORMAT_BLOB;
        case PublicFormat::DEPTH16:
            return HAL_PIXEL_FORMAT_Y16;
        case PublicFormat::RAW_SENSOR:
        case PublicFormat::RAW_DEPTH:
            return HAL_PIXEL_FORMAT_RAW16;
        default:
            // Most formats map 1:1
            return static_cast<int>(f);
    }
}

android_dataspace android_view_Surface_mapPublicFormatToHalDataspace(
        PublicFormat f) {
    Dataspace dataspace;
    switch(f) {
        case PublicFormat::JPEG:
            dataspace = Dataspace::V0_JFIF;
            break;
        case PublicFormat::DEPTH_POINT_CLOUD:
        case PublicFormat::DEPTH16:
        case PublicFormat::RAW_DEPTH:
            dataspace = Dataspace::DEPTH;
            break;
        case PublicFormat::RAW_SENSOR:
        case PublicFormat::RAW_PRIVATE:
        case PublicFormat::RAW10:
        case PublicFormat::RAW12:
            dataspace = Dataspace::ARBITRARY;
            break;
        case PublicFormat::YUV_420_888:
        case PublicFormat::NV21:
        case PublicFormat::YV12:
            dataspace = Dataspace::V0_JFIF;
            break;
        case PublicFormat::DEPTH_JPEG:
            dataspace = Dataspace::DYNAMIC_DEPTH;
            break;
        case PublicFormat::HEIC:
            dataspace = Dataspace::HEIF;
            break;
        default:
            // Most formats map to UNKNOWN
            dataspace = Dataspace::UNKNOWN;
            break;
    }
    return static_cast<android_dataspace>(dataspace);
}

PublicFormat android_view_Surface_mapHalFormatDataspaceToPublicFormat(
        int format, android_dataspace dataSpace) {
    Dataspace ds = static_cast<Dataspace>(dataSpace);
    switch(format) {
        case HAL_PIXEL_FORMAT_RGBA_8888:
        case HAL_PIXEL_FORMAT_RGBX_8888:
        case HAL_PIXEL_FORMAT_RGBA_FP16:
        case HAL_PIXEL_FORMAT_RGBA_1010102:
        case HAL_PIXEL_FORMAT_RGB_888:
        case HAL_PIXEL_FORMAT_RGB_565:
        case HAL_PIXEL_FORMAT_Y8:
        case HAL_PIXEL_FORMAT_RAW10:
        case HAL_PIXEL_FORMAT_RAW12:
        case HAL_PIXEL_FORMAT_YCbCr_420_888:
        case HAL_PIXEL_FORMAT_YV12:
            // Enums overlap in both name and value
            return static_cast<PublicFormat>(format);
        case HAL_PIXEL_FORMAT_RAW16:
            switch (ds) {
                case Dataspace::DEPTH:
                  return PublicFormat::RAW_DEPTH;
                default:
                  return PublicFormat::RAW_SENSOR;
            }
        case HAL_PIXEL_FORMAT_RAW_OPAQUE:
            // Name differs, though value is the same
            return PublicFormat::RAW_PRIVATE;
        case HAL_PIXEL_FORMAT_YCbCr_422_SP:
            // Name differs, though the value is the same
            return PublicFormat::NV16;
        case HAL_PIXEL_FORMAT_YCrCb_420_SP:
            // Name differs, though the value is the same
            return PublicFormat::NV21;
        case HAL_PIXEL_FORMAT_YCbCr_422_I:
            // Name differs, though the value is the same
            return PublicFormat::YUY2;
        case HAL_PIXEL_FORMAT_IMPLEMENTATION_DEFINED:
            // Name differs, though the value is the same
            return PublicFormat::PRIVATE;
        case HAL_PIXEL_FORMAT_Y16:
            // Dataspace-dependent
            switch (ds) {
                case Dataspace::DEPTH:
                    return PublicFormat::DEPTH16;
                default:
                    // Assume non-depth Y16 is just Y16.
                    return PublicFormat::Y16;
            }
            break;
        case HAL_PIXEL_FORMAT_BLOB:
            // Dataspace-dependent
            switch (ds) {
                case Dataspace::DEPTH:
                    return PublicFormat::DEPTH_POINT_CLOUD;
                case Dataspace::V0_JFIF:
                    return PublicFormat::JPEG;
                case Dataspace::HEIF:
                    return PublicFormat::HEIC;
                default:
                    if (dataSpace == static_cast<android_dataspace>(HAL_DATASPACE_DYNAMIC_DEPTH)) {
                        return PublicFormat::DEPTH_JPEG;
                    } else {
                        // Assume otherwise-marked blobs are also JPEG
                        return PublicFormat::JPEG;
                    }
            }
            break;
        case HAL_PIXEL_FORMAT_BGRA_8888:
            // Not defined in public API
            return PublicFormat::UNKNOWN;

        default:
            return PublicFormat::UNKNOWN;
    }
}
// ----------------------------------------------------------------------------

static inline bool isSurfaceValid(const sp<Surface>& sur) {
    return Surface::isValid(sur);
}

// ----------------------------------------------------------------------------

static jlong nativeCreateFromSurfaceTexture(JNIEnv* env, jclass clazz,
        jobject surfaceTextureObj) {
    sp<IGraphicBufferProducer> producer(SurfaceTexture_getProducer(env, surfaceTextureObj));
    if (producer == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException",
                "SurfaceTexture has already been released");
        return 0;
    }

    sp<Surface> surface(new Surface(producer, true));
    if (surface == NULL) {
        jniThrowException(env, OutOfResourcesException, NULL);
        return 0;
    }

    surface->incStrong(&sRefBaseOwner);
    return jlong(surface.get());
}

static void nativeRelease(JNIEnv* env, jclass clazz, jlong nativeObject) {
    sp<Surface> sur(reinterpret_cast<Surface *>(nativeObject));
    sur->decStrong(&sRefBaseOwner);
}

static jboolean nativeIsValid(JNIEnv* env, jclass clazz, jlong nativeObject) {
    sp<Surface> sur(reinterpret_cast<Surface *>(nativeObject));
    return isSurfaceValid(sur) ? JNI_TRUE : JNI_FALSE;
}

static jboolean nativeIsConsumerRunningBehind(JNIEnv* env, jclass clazz, jlong nativeObject) {
    sp<Surface> sur(reinterpret_cast<Surface *>(nativeObject));
    if (!isSurfaceValid(sur)) {
        doThrowIAE(env);
        return JNI_FALSE;
    }
    int value = 0;
    ANativeWindow* anw = static_cast<ANativeWindow*>(sur.get());
    anw->query(anw, NATIVE_WINDOW_CONSUMER_RUNNING_BEHIND, &value);
    return value;
}

static inline SkColorType convertPixelFormat(PixelFormat format) {
    /* note: if PIXEL_FORMAT_RGBX_8888 means that all alpha bytes are 0xFF, then
        we can map to kN32_SkColorType, and optionally call
        bitmap.setAlphaType(kOpaque_SkAlphaType) on the resulting SkBitmap
        (as an accelerator)
    */
    switch (format) {
    case PIXEL_FORMAT_RGBX_8888:    return kN32_SkColorType;
    case PIXEL_FORMAT_RGBA_8888:    return kN32_SkColorType;
    case PIXEL_FORMAT_RGBA_FP16:    return kRGBA_F16_SkColorType;
    case PIXEL_FORMAT_RGB_565:      return kRGB_565_SkColorType;
    default:                        return kUnknown_SkColorType;
    }
}

static jlong nativeLockCanvas(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject canvasObj, jobject dirtyRectObj) {
    sp<Surface> surface(reinterpret_cast<Surface *>(nativeObject));

    if (!isSurfaceValid(surface)) {
        doThrowIAE(env);
        return 0;
    }

    Rect dirtyRect(Rect::EMPTY_RECT);
    Rect* dirtyRectPtr = NULL;

    if (dirtyRectObj) {
        dirtyRect.left   = env->GetIntField(dirtyRectObj, gRectClassInfo.left);
        dirtyRect.top    = env->GetIntField(dirtyRectObj, gRectClassInfo.top);
        dirtyRect.right  = env->GetIntField(dirtyRectObj, gRectClassInfo.right);
        dirtyRect.bottom = env->GetIntField(dirtyRectObj, gRectClassInfo.bottom);
        dirtyRectPtr = &dirtyRect;
    }

    ANativeWindow_Buffer outBuffer;
    status_t err = surface->lock(&outBuffer, dirtyRectPtr);
    if (err < 0) {
        const char* const exception = (err == NO_MEMORY) ?
                OutOfResourcesException :
                "java/lang/IllegalArgumentException";
        jniThrowException(env, exception, NULL);
        return 0;
    }

    SkImageInfo info = SkImageInfo::Make(outBuffer.width, outBuffer.height,
                                         convertPixelFormat(outBuffer.format),
                                         outBuffer.format == PIXEL_FORMAT_RGBX_8888
                                                 ? kOpaque_SkAlphaType : kPremul_SkAlphaType);

    SkBitmap bitmap;
    ssize_t bpr = outBuffer.stride * bytesPerPixel(outBuffer.format);
    bitmap.setInfo(info, bpr);
    if (outBuffer.width > 0 && outBuffer.height > 0) {
        bitmap.setPixels(outBuffer.bits);
    } else {
        // be safe with an empty bitmap.
        bitmap.setPixels(NULL);
    }

    Canvas* nativeCanvas = GraphicsJNI::getNativeCanvas(env, canvasObj);
    nativeCanvas->setBitmap(bitmap);

    if (dirtyRectPtr) {
        nativeCanvas->clipRect(dirtyRect.left, dirtyRect.top,
                dirtyRect.right, dirtyRect.bottom, SkClipOp::kIntersect);
    }

    if (dirtyRectObj) {
        env->SetIntField(dirtyRectObj, gRectClassInfo.left,   dirtyRect.left);
        env->SetIntField(dirtyRectObj, gRectClassInfo.top,    dirtyRect.top);
        env->SetIntField(dirtyRectObj, gRectClassInfo.right,  dirtyRect.right);
        env->SetIntField(dirtyRectObj, gRectClassInfo.bottom, dirtyRect.bottom);
    }

    // Create another reference to the surface and return it.  This reference
    // should be passed to nativeUnlockCanvasAndPost in place of mNativeObject,
    // because the latter could be replaced while the surface is locked.
    sp<Surface> lockedSurface(surface);
    lockedSurface->incStrong(&sRefBaseOwner);
    return (jlong) lockedSurface.get();
}

static void nativeUnlockCanvasAndPost(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject canvasObj) {
    sp<Surface> surface(reinterpret_cast<Surface *>(nativeObject));
    if (!isSurfaceValid(surface)) {
        return;
    }

    // detach the canvas from the surface
    Canvas* nativeCanvas = GraphicsJNI::getNativeCanvas(env, canvasObj);
    nativeCanvas->setBitmap(SkBitmap());

    // unlock surface
    status_t err = surface->unlockAndPost();
    if (err < 0) {
        doThrowIAE(env);
    }
}

static void nativeAllocateBuffers(JNIEnv* /* env */ , jclass /* clazz */,
        jlong nativeObject) {
    sp<Surface> surface(reinterpret_cast<Surface *>(nativeObject));
    if (!isSurfaceValid(surface)) {
        return;
    }

    surface->allocateBuffers();
}

// ----------------------------------------------------------------------------

static jlong nativeCreateFromSurfaceControl(JNIEnv* env, jclass clazz,
        jlong surfaceControlNativeObj) {
    sp<SurfaceControl> ctrl(reinterpret_cast<SurfaceControl *>(surfaceControlNativeObj));
    sp<Surface> surface(ctrl->createSurface());
    if (surface != NULL) {
        surface->incStrong(&sRefBaseOwner);
    }
    return reinterpret_cast<jlong>(surface.get());
}

static jlong nativeGetFromSurfaceControl(JNIEnv* env, jclass clazz,
        jlong nativeObject,
        jlong surfaceControlNativeObj) {
    Surface* self(reinterpret_cast<Surface *>(nativeObject));
    sp<SurfaceControl> ctrl(reinterpret_cast<SurfaceControl *>(surfaceControlNativeObj));

    // If the underlying IGBP's are the same, we don't need to do anything.
    if (self != nullptr &&
            IInterface::asBinder(self->getIGraphicBufferProducer()) ==
            IInterface::asBinder(ctrl->getIGraphicBufferProducer())) {
        return nativeObject;
    }

    sp<Surface> surface(ctrl->getSurface());
    if (surface != NULL) {
        surface->incStrong(&sRefBaseOwner);
    }

    return reinterpret_cast<jlong>(surface.get());
}

static jlong nativeReadFromParcel(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return 0;
    }

    android::view::Surface surfaceShim;

    // Calling code in Surface.java has already read the name of the Surface
    // from the Parcel
    surfaceShim.readFromParcel(parcel, /*nameAlreadyRead*/true);

    sp<Surface> self(reinterpret_cast<Surface *>(nativeObject));

    // update the Surface only if the underlying IGraphicBufferProducer
    // has changed.
    if (self != nullptr
            && (IInterface::asBinder(self->getIGraphicBufferProducer()) ==
                    IInterface::asBinder(surfaceShim.graphicBufferProducer))) {
        // same IGraphicBufferProducer, return ourselves
        return jlong(self.get());
    }

    sp<Surface> sur;
    if (surfaceShim.graphicBufferProducer != nullptr) {
        // we have a new IGraphicBufferProducer, create a new Surface for it
        sur = new Surface(surfaceShim.graphicBufferProducer, true);
        // and keep a reference before passing to java
        sur->incStrong(&sRefBaseOwner);
    }

    if (self != NULL) {
        // and loose the java reference to ourselves
        self->decStrong(&sRefBaseOwner);
    }

    return jlong(sur.get());
}

static void nativeWriteToParcel(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return;
    }
    sp<Surface> self(reinterpret_cast<Surface *>(nativeObject));
    android::view::Surface surfaceShim;
    if (self != nullptr) {
        surfaceShim.graphicBufferProducer = self->getIGraphicBufferProducer();
    }
    // Calling code in Surface.java has already written the name of the Surface
    // to the Parcel
    surfaceShim.writeToParcel(parcel, /*nameAlreadyWritten*/true);
}

static jint nativeGetWidth(JNIEnv* env, jclass clazz, jlong nativeObject) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    ANativeWindow* anw = static_cast<ANativeWindow*>(surface);
    int value = 0;
    anw->query(anw, NATIVE_WINDOW_WIDTH, &value);
    return value;
}

static jint nativeGetHeight(JNIEnv* env, jclass clazz, jlong nativeObject) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    ANativeWindow* anw = static_cast<ANativeWindow*>(surface);
    int value = 0;
    anw->query(anw, NATIVE_WINDOW_HEIGHT, &value);
    return value;
}

static jlong nativeGetNextFrameNumber(JNIEnv *env, jclass clazz, jlong nativeObject) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    return surface->getNextFrameNumber();
}

static jint nativeSetScalingMode(JNIEnv *env, jclass clazz, jlong nativeObject, jint scalingMode) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    return surface->setScalingMode(scalingMode);
}

static jint nativeForceScopedDisconnect(JNIEnv *env, jclass clazz, jlong nativeObject) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    return surface->disconnect(-1, IGraphicBufferProducer::DisconnectMode::AllLocal);
}

static jint nativeAttachAndQueueBuffer(JNIEnv *env, jclass clazz, jlong nativeObject,
        jobject graphicBuffer) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    sp<GraphicBuffer> bp = graphicBufferForJavaObject(env, graphicBuffer);
    int err = Surface::attachAndQueueBuffer(surface, bp);
    return err;
}

static jint nativeSetSharedBufferModeEnabled(JNIEnv* env, jclass clazz, jlong nativeObject,
        jboolean enabled) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    ANativeWindow* anw = static_cast<ANativeWindow*>(surface);
    return anw->perform(surface, NATIVE_WINDOW_SET_SHARED_BUFFER_MODE, int(enabled));
}

static jint nativeSetAutoRefreshEnabled(JNIEnv* env, jclass clazz, jlong nativeObject,
        jboolean enabled) {
    Surface* surface = reinterpret_cast<Surface*>(nativeObject);
    ANativeWindow* anw = static_cast<ANativeWindow*>(surface);
    return anw->perform(surface, NATIVE_WINDOW_SET_AUTO_REFRESH, int(enabled));
}

namespace uirenderer {

using namespace android::uirenderer::renderthread;

class ContextFactory : public IContextFactory {
public:
    virtual AnimationContext* createAnimationContext(renderthread::TimeLord& clock) {
        return new AnimationContext(clock);
    }
};

static jlong create(JNIEnv* env, jclass clazz, jlong rootNodePtr, jlong surfacePtr,
        jboolean isWideColorGamut) {
    RenderNode* rootNode = reinterpret_cast<RenderNode*>(rootNodePtr);
    sp<Surface> surface(reinterpret_cast<Surface*>(surfacePtr));
    ContextFactory factory;
    RenderProxy* proxy = new RenderProxy(false, rootNode, &factory);
    proxy->loadSystemProperties();
    if (isWideColorGamut) {
        proxy->setWideGamut(true);
    }
    proxy->setSwapBehavior(SwapBehavior::kSwap_discardBuffer);
    proxy->setSurface(surface);
    // Shadows can't be used via this interface, so just set the light source
    // to all 0s.
    proxy->setLightAlpha(0, 0);
    proxy->setLightGeometry((Vector3){0, 0, 0}, 0);
    return (jlong) proxy;
}

static void setSurface(JNIEnv* env, jclass clazz, jlong rendererPtr, jlong surfacePtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(rendererPtr);
    sp<Surface> surface(reinterpret_cast<Surface*>(surfacePtr));
    proxy->setSurface(surface);
}

static void draw(JNIEnv* env, jclass clazz, jlong rendererPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(rendererPtr);
    nsecs_t vsync = systemTime(CLOCK_MONOTONIC);
    UiFrameInfoBuilder(proxy->frameInfo())
            .setVsync(vsync, vsync)
            .addFlag(FrameInfoFlags::SurfaceCanvas);
    proxy->syncAndDrawFrame();
}

static void destroy(JNIEnv* env, jclass clazz, jlong rendererPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(rendererPtr);
    delete proxy;
}

} // uirenderer

// ----------------------------------------------------------------------------

namespace hwui = android::uirenderer;

static const JNINativeMethod gSurfaceMethods[] = {
    {"nativeCreateFromSurfaceTexture", "(Landroid/graphics/SurfaceTexture;)J",
            (void*)nativeCreateFromSurfaceTexture },
    {"nativeRelease", "(J)V",
            (void*)nativeRelease },
    {"nativeIsValid", "(J)Z",
            (void*)nativeIsValid },
    {"nativeIsConsumerRunningBehind", "(J)Z",
            (void*)nativeIsConsumerRunningBehind },
    {"nativeLockCanvas", "(JLandroid/graphics/Canvas;Landroid/graphics/Rect;)J",
            (void*)nativeLockCanvas },
    {"nativeUnlockCanvasAndPost", "(JLandroid/graphics/Canvas;)V",
            (void*)nativeUnlockCanvasAndPost },
    {"nativeAllocateBuffers", "(J)V",
            (void*)nativeAllocateBuffers },
    {"nativeCreateFromSurfaceControl", "(J)J",
            (void*)nativeCreateFromSurfaceControl },
    {"nativeGetFromSurfaceControl", "(JJ)J",
            (void*)nativeGetFromSurfaceControl },
    {"nativeReadFromParcel", "(JLandroid/os/Parcel;)J",
            (void*)nativeReadFromParcel },
    {"nativeWriteToParcel", "(JLandroid/os/Parcel;)V",
            (void*)nativeWriteToParcel },
    {"nativeGetWidth", "(J)I", (void*)nativeGetWidth },
    {"nativeGetHeight", "(J)I", (void*)nativeGetHeight },
    {"nativeGetNextFrameNumber", "(J)J", (void*)nativeGetNextFrameNumber },
    {"nativeSetScalingMode", "(JI)I", (void*)nativeSetScalingMode },
    {"nativeForceScopedDisconnect", "(J)I", (void*)nativeForceScopedDisconnect},
    {"nativeAttachAndQueueBuffer", "(JLandroid/graphics/GraphicBuffer;)I", (void*)nativeAttachAndQueueBuffer},
    {"nativeSetSharedBufferModeEnabled", "(JZ)I", (void*)nativeSetSharedBufferModeEnabled},
    {"nativeSetAutoRefreshEnabled", "(JZ)I", (void*)nativeSetAutoRefreshEnabled},

    // HWUI context
    {"nHwuiCreate", "(JJZ)J", (void*) hwui::create },
    {"nHwuiSetSurface", "(JJ)V", (void*) hwui::setSurface },
    {"nHwuiDraw", "(J)V", (void*) hwui::draw },
    {"nHwuiDestroy", "(J)V", (void*) hwui::destroy },
};

int register_android_view_Surface(JNIEnv* env)
{
    int err = RegisterMethodsOrDie(env, "android/view/Surface",
            gSurfaceMethods, NELEM(gSurfaceMethods));

    jclass clazz = FindClassOrDie(env, "android/view/Surface");
    gSurfaceClassInfo.clazz = MakeGlobalRefOrDie(env, clazz);
    gSurfaceClassInfo.mNativeObject = GetFieldIDOrDie(env,
            gSurfaceClassInfo.clazz, "mNativeObject", "J");
    gSurfaceClassInfo.mLock = GetFieldIDOrDie(env,
            gSurfaceClassInfo.clazz, "mLock", "Ljava/lang/Object;");
    gSurfaceClassInfo.ctor = GetMethodIDOrDie(env, gSurfaceClassInfo.clazz, "<init>", "(J)V");

    clazz = FindClassOrDie(env, "android/graphics/Rect");
    gRectClassInfo.left = GetFieldIDOrDie(env, clazz, "left", "I");
    gRectClassInfo.top = GetFieldIDOrDie(env, clazz, "top", "I");
    gRectClassInfo.right = GetFieldIDOrDie(env, clazz, "right", "I");
    gRectClassInfo.bottom = GetFieldIDOrDie(env, clazz, "bottom", "I");

    return err;
}

};
