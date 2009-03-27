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

#include <stdio.h>

#include "android_util_Binder.h"

#include <ui/SurfaceComposerClient.h>
#include <ui/Region.h>
#include <ui/Rect.h>

#include <SkCanvas.h>
#include <SkBitmap.h>

#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>


// ----------------------------------------------------------------------------

namespace android {

// ----------------------------------------------------------------------------

static const char* const OutOfResourcesException =
    "android/view/Surface$OutOfResourcesException";

struct sso_t {
    jfieldID client;
};
static sso_t sso;

struct so_t {
    jfieldID surface;
    jfieldID saveCount;
    jfieldID canvas;
};
static so_t so;

struct ro_t {
    jfieldID l;
    jfieldID t;
    jfieldID r;
    jfieldID b;
};
static ro_t ro;

struct po_t {
    jfieldID x;
    jfieldID y;
};
static po_t po;

struct co_t {
    jfieldID surfaceFormat;
};
static co_t co;

struct no_t {
    jfieldID native_canvas;
    jfieldID native_region;
    jfieldID native_parcel;
};
static no_t no;


static __attribute__((noinline))
void doThrow(JNIEnv* env, const char* exc, const char* msg = NULL)
{
    if (!env->ExceptionOccurred()) {
        jclass npeClazz = env->FindClass(exc);
        env->ThrowNew(npeClazz, msg);
    }
}

// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------

static void SurfaceSession_init(JNIEnv* env, jobject clazz)
{
    sp<SurfaceComposerClient> client = new SurfaceComposerClient;
    client->incStrong(clazz);
    env->SetIntField(clazz, sso.client, (int)client.get());
}

static void SurfaceSession_destroy(JNIEnv* env, jobject clazz)
{
    SurfaceComposerClient* client =
            (SurfaceComposerClient*)env->GetIntField(clazz, sso.client);
    if (client != 0) {
        client->decStrong(clazz);
        env->SetIntField(clazz, sso.client, 0);
    }
}

static void SurfaceSession_kill(JNIEnv* env, jobject clazz)
{
    SurfaceComposerClient* client =
            (SurfaceComposerClient*)env->GetIntField(clazz, sso.client);
    if (client != 0) {
        client->dispose();
        client->decStrong(clazz);
        env->SetIntField(clazz, sso.client, 0);
    }
}

// ----------------------------------------------------------------------------

static sp<Surface> getSurface(JNIEnv* env, jobject clazz)
{
    Surface* const p = (Surface*)env->GetIntField(clazz, so.surface);
    return sp<Surface>(p);
}

static void setSurface(JNIEnv* env, jobject clazz, const sp<Surface>& surface)
{
    Surface* const p = (Surface*)env->GetIntField(clazz, so.surface);
    if (surface.get()) {
        surface->incStrong(clazz);
    }
    if (p) {
        p->decStrong(clazz);
    }
    env->SetIntField(clazz, so.surface, (int)surface.get());
}

// ----------------------------------------------------------------------------

static void Surface_init(
        JNIEnv* env, jobject clazz, 
        jobject session, jint pid, jint dpy, jint w, jint h, jint format, jint flags)
{
    if (session == NULL) {
        doThrow(env, "java/lang/NullPointerException");
        return;
    }
    
    SurfaceComposerClient* client =
            (SurfaceComposerClient*)env->GetIntField(session, sso.client);

    sp<Surface> surface(client->createSurface(pid, dpy, w, h, format, flags));
    if (surface == 0) {
        doThrow(env, OutOfResourcesException);
        return;
    }
    setSurface(env, clazz, surface);
}

static void Surface_initParcel(JNIEnv* env, jobject clazz, jobject argParcel)
{
    Parcel* parcel = (Parcel*)env->GetIntField(argParcel, no.native_parcel);
    if (parcel == NULL) {
        doThrow(env, "java/lang/NullPointerException", NULL);
        return;
    }
    const sp<Surface>& rhs = Surface::readFromParcel(parcel);
    setSurface(env, clazz, rhs);
}

static void Surface_clear(JNIEnv* env, jobject clazz, uintptr_t *ostack)
{
    setSurface(env, clazz, 0);
}

static jboolean Surface_isValid(JNIEnv* env, jobject clazz)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    return surface->isValid() ? JNI_TRUE : JNI_FALSE;
}

static inline SkBitmap::Config convertPixelFormat(PixelFormat format)
{
    /* note: if PIXEL_FORMAT_XRGB_8888 means that all alpha bytes are 0xFF, then
        we can map to SkBitmap::kARGB_8888_Config, and optionally call
        bitmap.setIsOpaque(true) on the resulting SkBitmap (as an accelerator)
    */
	switch (format) {
    case PIXEL_FORMAT_RGBA_8888:    return SkBitmap::kARGB_8888_Config;
    case PIXEL_FORMAT_RGBA_4444:    return SkBitmap::kARGB_4444_Config;
	case PIXEL_FORMAT_RGB_565:		return SkBitmap::kRGB_565_Config;
	case PIXEL_FORMAT_A_8:          return SkBitmap::kA8_Config;
	default:                        return SkBitmap::kNo_Config;
	}
}

static jobject Surface_lockCanvas(JNIEnv* env, jobject clazz, jobject dirtyRect)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (!surface->isValid())
        return 0;

    // get dirty region
    Region dirtyRegion;
    if (dirtyRect) {
        Rect dirty;
        dirty.left  = env->GetIntField(dirtyRect, ro.l);
        dirty.top   = env->GetIntField(dirtyRect, ro.t);
        dirty.right = env->GetIntField(dirtyRect, ro.r);
        dirty.bottom= env->GetIntField(dirtyRect, ro.b);
        if (dirty.left < dirty.right && dirty.top < dirty.bottom) {
            dirtyRegion.set(dirty);    
        }
    } else {
        dirtyRegion.set(Rect(0x3FFF,0x3FFF));
    }

    Surface::SurfaceInfo info;
    status_t err = surface->lock(&info, &dirtyRegion);
    if (err < 0) {
        const char* const exception = (err == NO_MEMORY) ?
            OutOfResourcesException :
            "java/lang/IllegalArgumentException";
        doThrow(env, exception, NULL);
        return 0;
    }

    // Associate a SkCanvas object to this surface
    jobject canvas = env->GetObjectField(clazz, so.canvas);
    env->SetIntField(canvas, co.surfaceFormat, info.format);

    SkCanvas* nativeCanvas = (SkCanvas*)env->GetIntField(canvas, no.native_canvas);
    SkBitmap bitmap;
    bitmap.setConfig(convertPixelFormat(info.format), info.w, info.h, info.bpr);
    if (info.w > 0 && info.h > 0) {
        bitmap.setPixels(info.bits);
    } else {
        // be safe with an empty bitmap.
        bitmap.setPixels(NULL);
    }
    nativeCanvas->setBitmapDevice(bitmap);
    nativeCanvas->clipRegion(dirtyRegion.toSkRegion());
    
    int saveCount = nativeCanvas->save();
    env->SetIntField(clazz, so.saveCount, saveCount);

    if (dirtyRect) {
        Rect bounds(dirtyRegion.bounds());
        env->SetIntField(dirtyRect, ro.l, bounds.left);
        env->SetIntField(dirtyRect, ro.t, bounds.top);
        env->SetIntField(dirtyRect, ro.r, bounds.right);
        env->SetIntField(dirtyRect, ro.b, bounds.bottom);
    }
    
	return canvas;
}

static void Surface_unlockCanvasAndPost(
        JNIEnv* env, jobject clazz, jobject argCanvas)
{
    jobject canvas = env->GetObjectField(clazz, so.canvas);
    if (canvas != argCanvas) {
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    
    const sp<Surface>& surface = getSurface(env, clazz);
    if (!surface->isValid())
        return;

    // detach the canvas from the surface
    SkCanvas* nativeCanvas = (SkCanvas*)env->GetIntField(canvas, no.native_canvas);
    int saveCount = env->GetIntField(clazz, so.saveCount);
    nativeCanvas->restoreToCount(saveCount);
    nativeCanvas->setBitmapDevice(SkBitmap());
    env->SetIntField(clazz, so.saveCount, 0);

    // unlock surface
    status_t err = surface->unlockAndPost();
    if (err < 0) {
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
    }
}

static void Surface_unlockCanvas(
        JNIEnv* env, jobject clazz, jobject argCanvas)
{
    jobject canvas = env->GetObjectField(clazz, so.canvas);
    if (canvas != argCanvas) {
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    
    const sp<Surface>& surface = getSurface(env, clazz);
    if (!surface->isValid())
        return;
    
    status_t err = surface->unlock();
    if (err < 0) {
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    SkCanvas* nativeCanvas = (SkCanvas*)env->GetIntField(canvas, no.native_canvas);
    int saveCount = env->GetIntField(clazz, so.saveCount);
    nativeCanvas->restoreToCount(saveCount);
    nativeCanvas->setBitmapDevice(SkBitmap());
    env->SetIntField(clazz, so.saveCount, 0);
}

static void Surface_openTransaction(
        JNIEnv* env, jobject clazz)
{
    SurfaceComposerClient::openGlobalTransaction();
}

static void Surface_closeTransaction(
        JNIEnv* env, jobject clazz)
{
    SurfaceComposerClient::closeGlobalTransaction();
}

static void Surface_setOrientation(
        JNIEnv* env, jobject clazz, jint display, jint orientation, jint flags)
{
    int err = SurfaceComposerClient::setOrientation(display, orientation, flags);
    if (err < 0) {
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
    }
}

static void Surface_freezeDisplay(
        JNIEnv* env, jobject clazz, jint display)
{
    int err = SurfaceComposerClient::freezeDisplay(display, 0);
    if (err < 0) {
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
    }
}

static void Surface_unfreezeDisplay(
        JNIEnv* env, jobject clazz, jint display)
{
    int err = SurfaceComposerClient::unfreezeDisplay(display, 0);
    if (err < 0) {
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
    }
}

static void Surface_setLayer(
        JNIEnv* env, jobject clazz, jint zorder)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        if (surface->setLayer(zorder) < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_setPosition(
        JNIEnv* env, jobject clazz, jint x, jint y)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        if (surface->setPosition(x, y) < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_setSize(
        JNIEnv* env, jobject clazz, jint w, jint h)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        if (surface->setSize(w, h) < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_hide(
        JNIEnv* env, jobject clazz)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        if (surface->hide() < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_show(
        JNIEnv* env, jobject clazz)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        if (surface->show() < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_freeze(
        JNIEnv* env, jobject clazz)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        if (surface->freeze() < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_unfreeze(
        JNIEnv* env, jobject clazz)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        if (surface->unfreeze() < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_setFlags(
        JNIEnv* env, jobject clazz, jint flags, jint mask)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        if (surface->setFlags(flags, mask) < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_setTransparentRegion(
        JNIEnv* env, jobject clazz, jobject argRegion)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        SkRegion* nativeRegion = (SkRegion*)env->GetIntField(argRegion, no.native_region);
        if (surface->setTransparentRegionHint(Region(*nativeRegion)) < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_setAlpha(
        JNIEnv* env, jobject clazz, jfloat alpha)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        if (surface->setAlpha(alpha) < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_setMatrix(
        JNIEnv* env, jobject clazz,
        jfloat dsdx, jfloat dtdx, jfloat dsdy, jfloat dtdy)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        if (surface->setMatrix(dsdx, dtdx, dsdy, dtdy) < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_setFreezeTint(
        JNIEnv* env, jobject clazz,
        jint tint)
{
    const sp<Surface>& surface = getSurface(env, clazz);
    if (surface->isValid()) {
        if (surface->setFreezeTint(tint) < 0) {
            doThrow(env, "java/lang/IllegalArgumentException", NULL);
        }
    }
}

static void Surface_copyFrom(
        JNIEnv* env, jobject clazz, jobject other)
{
    if (clazz == other)
        return;

    if (other == NULL) {
        doThrow(env, "java/lang/NullPointerException", NULL);
        return;
    }

    const sp<Surface>& surface = getSurface(env, clazz);
    const sp<Surface>& rhs = getSurface(env, other);
    if (!Surface::isSameSurface(surface, rhs)) {
        // we reassign the surface only if it's a different one
        // otherwise we would loose our client-side state.
        setSurface(env, clazz, rhs->dup());
    }
}


static void Surface_readFromParcel(
        JNIEnv* env, jobject clazz, jobject argParcel)
{
    Parcel* parcel = (Parcel*)env->GetIntField( argParcel, no.native_parcel);
    if (parcel == NULL) {
        doThrow(env, "java/lang/NullPointerException", NULL);
        return;
    }

    const sp<Surface>& surface = getSurface(env, clazz);
    const sp<Surface>& rhs = Surface::readFromParcel(parcel);
    if (!Surface::isSameSurface(surface, rhs)) {
        // we reassign the surface only if it's a different one
        // otherwise we would loose our client-side state.
        setSurface(env, clazz, rhs);
    }
}

static void Surface_writeToParcel(
        JNIEnv* env, jobject clazz, jobject argParcel, jint flags)
{
    Parcel* parcel = (Parcel*)env->GetIntField(
            argParcel, no.native_parcel);

    if (parcel == NULL) {
        doThrow(env, "java/lang/NullPointerException", NULL);
        return;
    }

    const sp<Surface>& surface = getSurface(env, clazz);
    Surface::writeToParcel(surface, parcel);
}

// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------
// ----------------------------------------------------------------------------

const char* const kSurfaceSessionClassPathName = "android/view/SurfaceSession";
const char* const kSurfaceClassPathName = "android/view/Surface";
static void nativeClassInit(JNIEnv* env, jclass clazz);

static JNINativeMethod gSurfaceSessionMethods[] = {
	{"init",     "()V",  (void*)SurfaceSession_init },
	{"destroy",  "()V",  (void*)SurfaceSession_destroy },
    {"kill",     "()V",  (void*)SurfaceSession_kill },
};

static JNINativeMethod gSurfaceMethods[] = {
    {"nativeClassInit",     "()V",  (void*)nativeClassInit },
    {"init",                "(Landroid/view/SurfaceSession;IIIIII)V",  (void*)Surface_init },
    {"init",                "(Landroid/os/Parcel;)V",  (void*)Surface_initParcel },
	{"clear",               "()V",  (void*)Surface_clear },
	{"copyFrom",            "(Landroid/view/Surface;)V",  (void*)Surface_copyFrom },
	{"isValid",             "()Z",  (void*)Surface_isValid },
	{"lockCanvasNative",    "(Landroid/graphics/Rect;)Landroid/graphics/Canvas;",  (void*)Surface_lockCanvas },
	{"unlockCanvasAndPost", "(Landroid/graphics/Canvas;)V", (void*)Surface_unlockCanvasAndPost },
	{"unlockCanvas",        "(Landroid/graphics/Canvas;)V", (void*)Surface_unlockCanvas },
	{"openTransaction",     "()V",  (void*)Surface_openTransaction },
    {"closeTransaction",    "()V",  (void*)Surface_closeTransaction },
    {"setOrientation",      "(III)V", (void*)Surface_setOrientation },
    {"freezeDisplay",       "(I)V", (void*)Surface_freezeDisplay },
    {"unfreezeDisplay",     "(I)V", (void*)Surface_unfreezeDisplay },
    {"setLayer",            "(I)V", (void*)Surface_setLayer },
	{"setPosition",         "(II)V",(void*)Surface_setPosition },
	{"setSize",             "(II)V",(void*)Surface_setSize },
	{"hide",                "()V",  (void*)Surface_hide },
	{"show",                "()V",  (void*)Surface_show },
	{"freeze",              "()V",  (void*)Surface_freeze },
	{"unfreeze",            "()V",  (void*)Surface_unfreeze },
	{"setFlags",            "(II)V",(void*)Surface_setFlags },
	{"setTransparentRegionHint","(Landroid/graphics/Region;)V", (void*)Surface_setTransparentRegion },
	{"setAlpha",            "(F)V", (void*)Surface_setAlpha },
	{"setMatrix",           "(FFFF)V",  (void*)Surface_setMatrix },
	{"setFreezeTint",       "(I)V",  (void*)Surface_setFreezeTint },
	{"readFromParcel",      "(Landroid/os/Parcel;)V", (void*)Surface_readFromParcel },
	{"writeToParcel",       "(Landroid/os/Parcel;I)V", (void*)Surface_writeToParcel },
};

void nativeClassInit(JNIEnv* env, jclass clazz)
{
	so.surface   = env->GetFieldID(clazz, "mSurface", "I");
	so.saveCount = env->GetFieldID(clazz, "mSaveCount", "I");
	so.canvas    = env->GetFieldID(clazz, "mCanvas", "Landroid/graphics/Canvas;");

    jclass surfaceSession = env->FindClass("android/view/SurfaceSession");
 	sso.client = env->GetFieldID(surfaceSession, "mClient", "I");

    jclass canvas = env->FindClass("android/graphics/Canvas");
    no.native_canvas = env->GetFieldID(canvas, "mNativeCanvas", "I");
    co.surfaceFormat = env->GetFieldID(canvas, "mSurfaceFormat", "I");

    jclass region = env->FindClass("android/graphics/Region");
    no.native_region = env->GetFieldID(region, "mNativeRegion", "I");

    jclass parcel = env->FindClass("android/os/Parcel");
    no.native_parcel = env->GetFieldID(parcel, "mObject", "I");

    jclass rect = env->FindClass("android/graphics/Rect");
    ro.l = env->GetFieldID(rect, "left", "I");
    ro.t = env->GetFieldID(rect, "top", "I");
    ro.r = env->GetFieldID(rect, "right", "I");
    ro.b = env->GetFieldID(rect, "bottom", "I");

    jclass point = env->FindClass("android/graphics/Point");
    po.x = env->GetFieldID(point, "x", "I");
    po.y = env->GetFieldID(point, "y", "I");
}

int register_android_view_Surface(JNIEnv* env)
{
    int err;
    err = AndroidRuntime::registerNativeMethods(env, kSurfaceSessionClassPathName,
            gSurfaceSessionMethods, NELEM(gSurfaceSessionMethods));

    err |= AndroidRuntime::registerNativeMethods(env, kSurfaceClassPathName,
            gSurfaceMethods, NELEM(gSurfaceMethods));
    return err;
}

};

