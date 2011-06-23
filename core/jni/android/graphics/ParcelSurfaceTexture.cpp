/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "ParcelSurfaceTexture"

#include <gui/SurfaceTextureClient.h>
#include <surfaceflinger/Surface.h>

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>
#include <android_runtime/android_view_Surface.h>

#include <utils/Log.h>

#include <binder/Parcel.h>

#include "android_util_Binder.h"
#include "jni.h"
#include "JNIHelp.h"

// ----------------------------------------------------------------------------

namespace android {

const char* const kParcelSurfaceTextureClassPathName =
    "android/graphics/ParcelSurfaceTexture";

struct fields_t {
    jfieldID iSurfaceTexture;
};
static fields_t fields;

#define ANDROID_GRAPHICS_ISURFACETEXTURE_JNI_ID "mISurfaceTexture"

// ----------------------------------------------------------------------------

static void ParcelSurfaceTexture_setISurfaceTexture(
        JNIEnv* env, jobject thiz, const sp<ISurfaceTexture>& iSurfaceTexture)
{
    ISurfaceTexture* const p =
            (ISurfaceTexture*)env->GetIntField(thiz, fields.iSurfaceTexture);
    if (iSurfaceTexture.get()) {
        iSurfaceTexture->incStrong(thiz);
    }
    if (p) {
        p->decStrong(thiz);
    }
    env->SetIntField(thiz, fields.iSurfaceTexture, (int)iSurfaceTexture.get());
}

sp<ISurfaceTexture> ParcelSurfaceTexture_getISurfaceTexture(
        JNIEnv* env, jobject thiz)
{
    sp<ISurfaceTexture> iSurfaceTexture(
        (ISurfaceTexture*)env->GetIntField(thiz, fields.iSurfaceTexture));
    return iSurfaceTexture;
}

sp<ANativeWindow> android_ParcelSurfaceTexture_getNativeWindow(
        JNIEnv* env, jobject thiz)
{
    sp<ISurfaceTexture> iSurfaceTexture(
            ParcelSurfaceTexture_getISurfaceTexture(env, thiz));
    sp<SurfaceTextureClient> surfaceTextureClient(iSurfaceTexture != NULL ?
            new SurfaceTextureClient(iSurfaceTexture) : NULL);
    return surfaceTextureClient;
}

bool android_ParcelSurfaceTexture_isInstanceOf(JNIEnv* env, jobject thiz)
{
    jclass parcelSurfaceTextureClass = env->FindClass(
            kParcelSurfaceTextureClassPathName);
    return env->IsInstanceOf(thiz, parcelSurfaceTextureClass);
}

// ----------------------------------------------------------------------------

static void ParcelSurfaceTexture_classInit(JNIEnv* env, jclass clazz)
{
    fields.iSurfaceTexture =
            env->GetFieldID(clazz, ANDROID_GRAPHICS_ISURFACETEXTURE_JNI_ID, "I");
    if (fields.iSurfaceTexture == NULL) {
        LOGE("can't find android/graphics/ParcelSurfaceTexture.%s",
             ANDROID_GRAPHICS_ISURFACETEXTURE_JNI_ID);
    }
}

static void ParcelSurfaceTexture_initFromSurface(
        JNIEnv* env, jobject thiz, jobject jSurface)
{
    sp<Surface> surface(Surface_getSurface(env, jSurface));
    sp<ISurfaceTexture> iSurfaceTexture(surface->getSurfaceTexture());
    ParcelSurfaceTexture_setISurfaceTexture(env, thiz, iSurfaceTexture);
}

static void ParcelSurfaceTexture_initFromSurfaceTexture(
        JNIEnv* env, jobject thiz, jobject jSurfaceTexture)
{
    sp<ISurfaceTexture> iSurfaceTexture(
            SurfaceTexture_getSurfaceTexture(env, jSurfaceTexture));
    ParcelSurfaceTexture_setISurfaceTexture(env, thiz, iSurfaceTexture);
}

static void ParcelSurfaceTexture_finalize(JNIEnv* env, jobject thiz)
{
    ParcelSurfaceTexture_setISurfaceTexture(env, thiz, 0);
}

static void ParcelSurfaceTexture_writeToParcel(
        JNIEnv* env, jobject thiz, jobject jParcel, jint flags)
{
    Parcel* parcel = parcelForJavaObject(env, jParcel);
    sp<ISurfaceTexture> iSurfaceTexture(
            ParcelSurfaceTexture_getISurfaceTexture(env, thiz));
    sp<IBinder> b(iSurfaceTexture->asBinder());
    parcel->writeStrongBinder(b);
}

static void ParcelSurfaceTexture_readFromParcel(
        JNIEnv* env, jobject thiz, jobject jParcel)
{
    Parcel* parcel = parcelForJavaObject(env, jParcel);
    sp<ISurfaceTexture> iSurfaceTexture(
            interface_cast<ISurfaceTexture>(parcel->readStrongBinder()));
    ParcelSurfaceTexture_setISurfaceTexture(env, thiz, iSurfaceTexture);
}

// ----------------------------------------------------------------------------

static JNINativeMethod gParcelSurfaceTextureMethods[] = {
    {"nativeClassInit", "()V",   (void*)ParcelSurfaceTexture_classInit },
    {"nativeInitFromSurface", "(Landroid/view/Surface;)V",
      (void *)ParcelSurfaceTexture_initFromSurface },
    {"nativeInitFromSurfaceTexture", "(Landroid/graphics/SurfaceTexture;)V",
      (void *)ParcelSurfaceTexture_initFromSurfaceTexture },
    { "nativeFinalize", "()V", (void *)ParcelSurfaceTexture_finalize },
    { "nativeWriteToParcel", "(Landroid/os/Parcel;I)V",
      (void *)ParcelSurfaceTexture_writeToParcel },
    { "nativeReadFromParcel", "(Landroid/os/Parcel;)V",
      (void *)ParcelSurfaceTexture_readFromParcel },
};


int register_android_graphics_ParcelSurfaceTexture(JNIEnv* env)
{
    int err = 0;
    err = AndroidRuntime::registerNativeMethods(env, kParcelSurfaceTextureClassPathName,
            gParcelSurfaceTextureMethods, NELEM(gParcelSurfaceTextureMethods));
    return err;
}

} // namespace android
