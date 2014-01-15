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
#include "JNIHelp.h"
#include "android_os_Parcel.h"
#include "android/graphics/GraphicsJNI.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_view_Surface.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>
#include <android_runtime/Log.h>

#include <binder/Parcel.h>

#include <gui/Surface.h>
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

#include <ScopedUtfChars.h>

// ----------------------------------------------------------------------------

namespace android {

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

static struct {
    jfieldID mFinalizer;
    jfieldID mNativeCanvas;
    jfieldID mSurfaceFormat;
} gCanvasClassInfo;

static struct {
    jfieldID mNativeCanvas;
} gCanvasFinalizerClassInfo;

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
                env->GetIntField(surfaceObj, gSurfaceClassInfo.mNativeObject));
        env->MonitorExit(lock);
    }
    return sur;
}

jobject android_view_Surface_createFromIGraphicBufferProducer(JNIEnv* env,
        const sp<IGraphicBufferProducer>& bufferProducer) {
    if (bufferProducer == NULL) {
        return NULL;
    }

    sp<Surface> surface(new Surface(bufferProducer, true));
    if (surface == NULL) {
        return NULL;
    }

    jobject surfaceObj = env->NewObject(gSurfaceClassInfo.clazz, gSurfaceClassInfo.ctor, surface.get());
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

// ----------------------------------------------------------------------------

static inline bool isSurfaceValid(const sp<Surface>& sur) {
    return Surface::isValid(sur);
}

// ----------------------------------------------------------------------------

static jint nativeCreateFromSurfaceTexture(JNIEnv* env, jclass clazz,
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
    return int(surface.get());
}

static void nativeRelease(JNIEnv* env, jclass clazz, jint nativeObject) {
    sp<Surface> sur(reinterpret_cast<Surface *>(nativeObject));
    sur->decStrong(&sRefBaseOwner);
}

static jboolean nativeIsValid(JNIEnv* env, jclass clazz, jint nativeObject) {
    sp<Surface> sur(reinterpret_cast<Surface *>(nativeObject));
    return isSurfaceValid(sur) ? JNI_TRUE : JNI_FALSE;
}

static jboolean nativeIsConsumerRunningBehind(JNIEnv* env, jclass clazz, jint nativeObject) {
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

static inline SkBitmap::Config convertPixelFormat(PixelFormat format) {
    /* note: if PIXEL_FORMAT_RGBX_8888 means that all alpha bytes are 0xFF, then
        we can map to SkBitmap::kARGB_8888_Config, and optionally call
        bitmap.setAlphaType(kOpaque_SkAlphaType) on the resulting SkBitmap
        (as an accelerator)
    */
    switch (format) {
    case PIXEL_FORMAT_RGBX_8888:    return SkBitmap::kARGB_8888_Config;
    case PIXEL_FORMAT_RGBA_8888:    return SkBitmap::kARGB_8888_Config;
    case PIXEL_FORMAT_RGB_565:      return SkBitmap::kRGB_565_Config;
    default:                        return SkBitmap::kNo_Config;
    }
}

static inline void swapCanvasPtr(JNIEnv* env, jobject canvasObj, SkCanvas* newCanvas) {
  jobject canvasFinalizerObj = env->GetObjectField(canvasObj, gCanvasClassInfo.mFinalizer);
  SkCanvas* previousCanvas = reinterpret_cast<SkCanvas*>(
          env->GetIntField(canvasObj, gCanvasClassInfo.mNativeCanvas));
  env->SetIntField(canvasObj, gCanvasClassInfo.mNativeCanvas, (int)newCanvas);
  env->SetIntField(canvasFinalizerObj, gCanvasFinalizerClassInfo.mNativeCanvas, (int)newCanvas);
  SkSafeUnref(previousCanvas);
}

static jint nativeLockCanvas(JNIEnv* env, jclass clazz,
        jint nativeObject, jobject canvasObj, jobject dirtyRectObj) {
    sp<Surface> surface(reinterpret_cast<Surface *>(nativeObject));

    if (!isSurfaceValid(surface)) {
        doThrowIAE(env);
        return 0;
    }

    Rect dirtyRect;
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

    // Associate a SkCanvas object to this surface
    env->SetIntField(canvasObj, gCanvasClassInfo.mSurfaceFormat, outBuffer.format);

    SkBitmap bitmap;
    ssize_t bpr = outBuffer.stride * bytesPerPixel(outBuffer.format);
    bitmap.setConfig(convertPixelFormat(outBuffer.format), outBuffer.width, outBuffer.height, bpr);
    if (outBuffer.format == PIXEL_FORMAT_RGBX_8888) {
        bitmap.setAlphaType(kOpaque_SkAlphaType);
    }
    if (outBuffer.width > 0 && outBuffer.height > 0) {
        bitmap.setPixels(outBuffer.bits);
    } else {
        // be safe with an empty bitmap.
        bitmap.setPixels(NULL);
    }

    SkCanvas* nativeCanvas = SkNEW_ARGS(SkCanvas, (bitmap));
    swapCanvasPtr(env, canvasObj, nativeCanvas);

    if (dirtyRectPtr) {
        nativeCanvas->clipRect( SkRect::Make(reinterpret_cast<const SkIRect&>(dirtyRect)) );
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
    return (int) lockedSurface.get();
}

static void nativeUnlockCanvasAndPost(JNIEnv* env, jclass clazz,
        jint nativeObject, jobject canvasObj) {
    sp<Surface> surface(reinterpret_cast<Surface *>(nativeObject));
    if (!isSurfaceValid(surface)) {
        return;
    }

    // detach the canvas from the surface
    SkCanvas* nativeCanvas = SkNEW(SkCanvas);
    swapCanvasPtr(env, canvasObj, nativeCanvas);

    // unlock surface
    status_t err = surface->unlockAndPost();
    if (err < 0) {
        doThrowIAE(env);
    }
}

// ----------------------------------------------------------------------------

static jint nativeCreateFromSurfaceControl(JNIEnv* env, jclass clazz,
        jint surfaceControlNativeObj) {
    /*
     * This is used by the WindowManagerService just after constructing
     * a Surface and is necessary for returning the Surface reference to
     * the caller. At this point, we should only have a SurfaceControl.
     */

    sp<SurfaceControl> ctrl(reinterpret_cast<SurfaceControl *>(surfaceControlNativeObj));
    sp<Surface> surface(ctrl->getSurface());
    if (surface != NULL) {
        surface->incStrong(&sRefBaseOwner);
    }
    return reinterpret_cast<jint>(surface.get());
}

static jint nativeReadFromParcel(JNIEnv* env, jclass clazz,
        jint nativeObject, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return 0;
    }

    sp<Surface> self(reinterpret_cast<Surface *>(nativeObject));
    sp<IBinder> binder(parcel->readStrongBinder());

    // update the Surface only if the underlying IGraphicBufferProducer
    // has changed.
    if (self != NULL
            && (self->getIGraphicBufferProducer()->asBinder() == binder)) {
        // same IGraphicBufferProducer, return ourselves
        return int(self.get());
    }

    sp<Surface> sur;
    sp<IGraphicBufferProducer> gbp(interface_cast<IGraphicBufferProducer>(binder));
    if (gbp != NULL) {
        // we have a new IGraphicBufferProducer, create a new Surface for it
        sur = new Surface(gbp, true);
        // and keep a reference before passing to java
        sur->incStrong(&sRefBaseOwner);
    }

    if (self != NULL) {
        // and loose the java reference to ourselves
        self->decStrong(&sRefBaseOwner);
    }

    return int(sur.get());
}

static void nativeWriteToParcel(JNIEnv* env, jclass clazz,
        jint nativeObject, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel == NULL) {
        doThrowNPE(env);
        return;
    }
    sp<Surface> self(reinterpret_cast<Surface *>(nativeObject));
    parcel->writeStrongBinder( self != 0 ? self->getIGraphicBufferProducer()->asBinder() : NULL);
}

// ----------------------------------------------------------------------------

static JNINativeMethod gSurfaceMethods[] = {
    {"nativeCreateFromSurfaceTexture", "(Landroid/graphics/SurfaceTexture;)I",
            (void*)nativeCreateFromSurfaceTexture },
    {"nativeRelease", "(I)V",
            (void*)nativeRelease },
    {"nativeIsValid", "(I)Z",
            (void*)nativeIsValid },
    {"nativeIsConsumerRunningBehind", "(I)Z",
            (void*)nativeIsConsumerRunningBehind },
    {"nativeLockCanvas", "(ILandroid/graphics/Canvas;Landroid/graphics/Rect;)I",
            (void*)nativeLockCanvas },
    {"nativeUnlockCanvasAndPost", "(ILandroid/graphics/Canvas;)V",
            (void*)nativeUnlockCanvasAndPost },
    {"nativeCreateFromSurfaceControl", "(I)I",
            (void*)nativeCreateFromSurfaceControl },
    {"nativeReadFromParcel", "(ILandroid/os/Parcel;)I",
            (void*)nativeReadFromParcel },
    {"nativeWriteToParcel", "(ILandroid/os/Parcel;)V",
            (void*)nativeWriteToParcel },
};

int register_android_view_Surface(JNIEnv* env)
{
    int err = AndroidRuntime::registerNativeMethods(env, "android/view/Surface",
            gSurfaceMethods, NELEM(gSurfaceMethods));

    jclass clazz = env->FindClass("android/view/Surface");
    gSurfaceClassInfo.clazz = jclass(env->NewGlobalRef(clazz));
    gSurfaceClassInfo.mNativeObject =
            env->GetFieldID(gSurfaceClassInfo.clazz, "mNativeObject", "I");
    gSurfaceClassInfo.mLock =
            env->GetFieldID(gSurfaceClassInfo.clazz, "mLock", "Ljava/lang/Object;");
    gSurfaceClassInfo.ctor = env->GetMethodID(gSurfaceClassInfo.clazz, "<init>", "(I)V");

    clazz = env->FindClass("android/graphics/Canvas");
    gCanvasClassInfo.mFinalizer = env->GetFieldID(clazz, "mFinalizer", "Landroid/graphics/Canvas$CanvasFinalizer;");
    gCanvasClassInfo.mNativeCanvas = env->GetFieldID(clazz, "mNativeCanvas", "I");
    gCanvasClassInfo.mSurfaceFormat = env->GetFieldID(clazz, "mSurfaceFormat", "I");

    clazz = env->FindClass("android/graphics/Canvas$CanvasFinalizer");
    gCanvasFinalizerClassInfo.mNativeCanvas = env->GetFieldID(clazz, "mNativeCanvas", "I");

    clazz = env->FindClass("android/graphics/Rect");
    gRectClassInfo.left = env->GetFieldID(clazz, "left", "I");
    gRectClassInfo.top = env->GetFieldID(clazz, "top", "I");
    gRectClassInfo.right = env->GetFieldID(clazz, "right", "I");
    gRectClassInfo.bottom = env->GetFieldID(clazz, "bottom", "I");

    return err;
}

};
