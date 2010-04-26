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

#include <surfaceflinger/SurfaceComposerClient.h>
#include <ui/Region.h>
#include <ui/Rect.h>

#include <SkCanvas.h>
#include <SkBitmap.h>
#include <SkRegion.h>

#include "jni.h"
#include <android_runtime/AndroidRuntime.h>
#include <utils/misc.h>


// ----------------------------------------------------------------------------

namespace android {

enum {
    // should match Parcelable.java
    PARCELABLE_WRITE_RETURN_VALUE = 0x0001
};

// ----------------------------------------------------------------------------

static const char* const OutOfResourcesException =
    "android/view/Surface$OutOfResourcesException";

struct sso_t {
    jfieldID client;
};
static sso_t sso;

struct so_t {
    jfieldID surfaceControl;
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

static sp<SurfaceControl> getSurfaceControl(JNIEnv* env, jobject clazz)
{
    SurfaceControl* const p = 
        (SurfaceControl*)env->GetIntField(clazz, so.surfaceControl);
    return sp<SurfaceControl>(p);
}

static void setSurfaceControl(JNIEnv* env, jobject clazz, 
        const sp<SurfaceControl>& surface)
{
    SurfaceControl* const p = 
        (SurfaceControl*)env->GetIntField(clazz, so.surfaceControl);
    if (surface.get()) {
        surface->incStrong(clazz);
    }
    if (p) {
        p->decStrong(clazz);
    }
    env->SetIntField(clazz, so.surfaceControl, (int)surface.get());
}

static sp<Surface> getSurface(JNIEnv* env, jobject clazz)
{
    sp<Surface> result((Surface*)env->GetIntField(clazz, so.surface));
    if (result == 0) {
        /*
         * if this method is called from the WindowManager's process, it means
         * the client is is not remote, and therefore is allowed to have
         * a Surface (data), so we create it here. 
         * If we don't have a SurfaceControl, it means we're in a different
         * process.
         */
        
        SurfaceControl* const control = 
            (SurfaceControl*)env->GetIntField(clazz, so.surfaceControl);
        if (control) {
            result = control->getSurface();
            if (result != 0) {
                result->incStrong(clazz);
                env->SetIntField(clazz, so.surface, (int)result.get());
            }
        }
    }
    return result;
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
        jobject session,
        jint pid, jstring jname, jint dpy, jint w, jint h, jint format, jint flags)
{
    if (session == NULL) {
        doThrow(env, "java/lang/NullPointerException");
        return;
    }
    
    SurfaceComposerClient* client =
            (SurfaceComposerClient*)env->GetIntField(session, sso.client);

    sp<SurfaceControl> surface;
    if (jname == NULL) {
        surface = client->createSurface(pid, dpy, w, h, format, flags);
    } else {
        const jchar* str = env->GetStringCritical(jname, 0);
        const String8 name(str, env->GetStringLength(jname));
        env->ReleaseStringCritical(jname, str);
        surface = client->createSurface(pid, name, dpy, w, h, format, flags);
    }

    if (surface == 0) {
        doThrow(env, OutOfResourcesException);
        return;
    }
    setSurfaceControl(env, clazz, surface);
}

static void Surface_initParcel(JNIEnv* env, jobject clazz, jobject argParcel)
{
    Parcel* parcel = (Parcel*)env->GetIntField(argParcel, no.native_parcel);
    if (parcel == NULL) {
        doThrow(env, "java/lang/NullPointerException", NULL);
        return;
    }
    sp<Surface> rhs = new Surface(*parcel);
    setSurface(env, clazz, rhs);
}

static jint Surface_getIdentity(JNIEnv* env, jobject clazz)
{
    const sp<SurfaceControl>& control(getSurfaceControl(env, clazz));
    if (control != 0) return (jint) control->getIdentity();
    const sp<Surface>& surface(getSurface(env, clazz));
    if (surface != 0) return (jint) surface->getIdentity();
    return -1;
}

static void Surface_destroy(JNIEnv* env, jobject clazz, uintptr_t *ostack)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (SurfaceControl::isValid(surface)) {
        surface->clear();
    }
    setSurfaceControl(env, clazz, 0);
    setSurface(env, clazz, 0);
}

static void Surface_release(JNIEnv* env, jobject clazz, uintptr_t *ostack)
{
    setSurfaceControl(env, clazz, 0);
    setSurface(env, clazz, 0);
}

static jboolean Surface_isValid(JNIEnv* env, jobject clazz)
{
    const sp<SurfaceControl>& surfaceControl(getSurfaceControl(env, clazz));
    if (surfaceControl != 0) {
        return SurfaceControl::isValid(surfaceControl) ? JNI_TRUE : JNI_FALSE;
    }
    const sp<Surface>& surface(getSurface(env, clazz));
    return Surface::isValid(surface) ? JNI_TRUE : JNI_FALSE;
}

static inline SkBitmap::Config convertPixelFormat(PixelFormat format)
{
    /* note: if PIXEL_FORMAT_RGBX_8888 means that all alpha bytes are 0xFF, then
        we can map to SkBitmap::kARGB_8888_Config, and optionally call
        bitmap.setIsOpaque(true) on the resulting SkBitmap (as an accelerator)
    */
    switch (format) {
    case PIXEL_FORMAT_RGBX_8888:    return SkBitmap::kARGB_8888_Config;
    case PIXEL_FORMAT_RGBA_8888:    return SkBitmap::kARGB_8888_Config;
    case PIXEL_FORMAT_RGBA_4444:    return SkBitmap::kARGB_4444_Config;
    case PIXEL_FORMAT_RGB_565:      return SkBitmap::kRGB_565_Config;
    case PIXEL_FORMAT_A_8:          return SkBitmap::kA8_Config;
    default:                        return SkBitmap::kNo_Config;
    }
}

static jobject Surface_lockCanvas(JNIEnv* env, jobject clazz, jobject dirtyRect)
{
    const sp<Surface>& surface(getSurface(env, clazz));
    if (!Surface::isValid(surface))
        return 0;

    // get dirty region
    Region dirtyRegion;
    if (dirtyRect) {
        Rect dirty;
        dirty.left  = env->GetIntField(dirtyRect, ro.l);
        dirty.top   = env->GetIntField(dirtyRect, ro.t);
        dirty.right = env->GetIntField(dirtyRect, ro.r);
        dirty.bottom= env->GetIntField(dirtyRect, ro.b);
        if (!dirty.isEmpty()) {
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
    ssize_t bpr = info.s * bytesPerPixel(info.format);
    bitmap.setConfig(convertPixelFormat(info.format), info.w, info.h, bpr);
    if (info.format == PIXEL_FORMAT_RGBX_8888) {
        bitmap.setIsOpaque(true);
    }
    if (info.w > 0 && info.h > 0) {
        bitmap.setPixels(info.bits);
    } else {
        // be safe with an empty bitmap.
        bitmap.setPixels(NULL);
    }
    nativeCanvas->setBitmapDevice(bitmap);
    
    SkRegion clipReg;
    if (dirtyRegion.isRect()) { // very common case
        const Rect& b(dirtyRegion.getBounds());
        clipReg.setRect(b.left, b.top, b.right, b.bottom);
    } else {
        size_t count;
        Rect const* r = dirtyRegion.getArray(&count);
        while (count) {
            clipReg.op(r->left, r->top, r->right, r->bottom, SkRegion::kUnion_Op);
            r++, count--;
        }
    }

    nativeCanvas->clipRegion(clipReg);
    
    int saveCount = nativeCanvas->save();
    env->SetIntField(clazz, so.saveCount, saveCount);

    if (dirtyRect) {
        const Rect& bounds(dirtyRegion.getBounds());
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
    
    const sp<Surface>& surface(getSurface(env, clazz));
    if (!Surface::isValid(surface))
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
    // XXX: this API has been removed
    doThrow(env, "java/lang/IllegalArgumentException", NULL);
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
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setLayer(zorder);
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

static void Surface_setPosition(
        JNIEnv* env, jobject clazz, jint x, jint y)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setPosition(x, y);
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

static void Surface_setSize(
        JNIEnv* env, jobject clazz, jint w, jint h)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setSize(w, h);
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

static void Surface_hide(
        JNIEnv* env, jobject clazz)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->hide();
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

static void Surface_show(
        JNIEnv* env, jobject clazz)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->show();
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

static void Surface_freeze(
        JNIEnv* env, jobject clazz)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->freeze();
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

static void Surface_unfreeze(
        JNIEnv* env, jobject clazz)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->unfreeze();
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

static void Surface_setFlags(
        JNIEnv* env, jobject clazz, jint flags, jint mask)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setFlags(flags, mask);
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

static void Surface_setTransparentRegion(
        JNIEnv* env, jobject clazz, jobject argRegion)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    SkRegion* nativeRegion = (SkRegion*)env->GetIntField(argRegion, no.native_region);
    
    const SkIRect& b(nativeRegion->getBounds());
    Region reg(Rect(b.fLeft, b.fTop, b.fRight, b.fBottom));
    if (nativeRegion->isComplex()) {
        SkRegion::Iterator it(*nativeRegion);
        while (!it.done()) {
            const SkIRect& r(it.rect());
            reg.addRectUnchecked(r.fLeft, r.fTop, r.fRight, r.fBottom);
            it.next();
        }
    }
    
    status_t err = surface->setTransparentRegionHint(reg);
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

static void Surface_setAlpha(
        JNIEnv* env, jobject clazz, jfloat alpha)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setAlpha(alpha);
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

static void Surface_setMatrix(
        JNIEnv* env, jobject clazz,
        jfloat dsdx, jfloat dtdx, jfloat dsdy, jfloat dtdy)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setMatrix(dsdx, dtdx, dsdy, dtdy);
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

static void Surface_setFreezeTint(
        JNIEnv* env, jobject clazz,
        jint tint)
{
    const sp<SurfaceControl>& surface(getSurfaceControl(env, clazz));
    if (surface == 0) return;
    status_t err = surface->setFreezeTint(tint);
    if (err<0 && err!=NO_INIT)
        doThrow(env, "java/lang/IllegalArgumentException", NULL);
}

// ----------------------------------------------------------------------------

static void Surface_copyFrom(
        JNIEnv* env, jobject clazz, jobject other)
{
    if (clazz == other)
        return;

    if (other == NULL) {
        doThrow(env, "java/lang/NullPointerException", NULL);
        return;
    }

    /*
     * This is used by the WindowManagerService just after constructing
     * a Surface and is necessary for returning the Surface reference to
     * the caller. At this point, we should only have a SurfaceControl.
     */
    
    const sp<SurfaceControl>& surface = getSurfaceControl(env, clazz);
    const sp<SurfaceControl>& rhs = getSurfaceControl(env, other);
    if (!SurfaceControl::isSameSurface(surface, rhs)) {
        // we reassign the surface only if it's a different one
        // otherwise we would loose our client-side state.
        setSurfaceControl(env, clazz, rhs);
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

    const sp<Surface>& control(getSurface(env, clazz));
    sp<Surface> rhs = new Surface(*parcel);
    if (!Surface::isSameSurface(control, rhs)) {
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

    const sp<SurfaceControl>& control(getSurfaceControl(env, clazz));
    SurfaceControl::writeSurfaceToParcel(control, parcel);
    if (flags & PARCELABLE_WRITE_RETURN_VALUE) {
        setSurfaceControl(env, clazz, 0);
    }
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
    {"init",                "(Landroid/view/SurfaceSession;ILjava/lang/String;IIIII)V",  (void*)Surface_init },
    {"init",                "(Landroid/os/Parcel;)V",  (void*)Surface_initParcel },
    {"getIdentity",         "()I",  (void*)Surface_getIdentity },
    {"destroy",             "()V",  (void*)Surface_destroy },
    {"release",             "()V",  (void*)Surface_release },
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
    so.surface = env->GetFieldID(clazz, "mSurface", "I");
    so.surfaceControl = env->GetFieldID(clazz, "mSurfaceControl", "I");
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

