/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "SurfaceTexture"

#include <stdio.h>

#include <gui/SurfaceTexture.h>

#include <android_runtime/AndroidRuntime.h>

#include <utils/Log.h>
#include <utils/misc.h>

#include "android/graphics/GraphicsJNI.h"
#include "jni.h"

// ----------------------------------------------------------------------------

namespace android {

static const char* const OutOfResourcesException =
    "android/graphics/SurfaceTexture$OutOfResourcesException";

struct st_t {
    jfieldID surfaceTexture;
};
static st_t st;

// ----------------------------------------------------------------------------

static void setSurfaceTexture(JNIEnv* env, jobject clazz,
        const sp<SurfaceTexture>& surfaceTexture)
{
    SurfaceTexture* const p =
        (SurfaceTexture*)env->GetIntField(clazz, st.surfaceTexture);
    if (surfaceTexture.get()) {
        surfaceTexture->incStrong(clazz);
    }
    if (p) {
        p->decStrong(clazz);
    }
    env->SetIntField(clazz, st.surfaceTexture, (int)surfaceTexture.get());
}

sp<SurfaceTexture> getSurfaceTexture(JNIEnv* env, jobject clazz)
{
    sp<SurfaceTexture> surfaceTexture(
        (SurfaceTexture*)env->GetIntField(clazz, st.surfaceTexture));
    return surfaceTexture;
}

// ----------------------------------------------------------------------------

static void SurfaceTexture_init(JNIEnv* env, jobject clazz, jint texName)
{
    sp<SurfaceTexture> surfaceTexture(new SurfaceTexture(texName));

    if (surfaceTexture == 0) {
        doThrow(env, OutOfResourcesException);
        return;
    }
    setSurfaceTexture(env, clazz, surfaceTexture);
}

static void SurfaceTexture_updateTexImage(JNIEnv* env, jobject clazz)
{
    sp<SurfaceTexture> surfaceTexture(getSurfaceTexture(env, clazz));
    surfaceTexture->updateTexImage();
}

static void SurfaceTexture_getTransformMatrix(JNIEnv* env, jobject clazz,
        jfloatArray jmtx)
{
    sp<SurfaceTexture> surfaceTexture(getSurfaceTexture(env, clazz));
    float* mtx = env->GetFloatArrayElements(jmtx, NULL);
    surfaceTexture->getTransformMatrix(mtx);
    env->ReleaseFloatArrayElements(jmtx, mtx, 0);
}

// ----------------------------------------------------------------------------

const char* const kSurfaceTextureClassPathName = "android/graphics/SurfaceTexture";
static void nativeClassInit(JNIEnv* env, jclass clazz);

static JNINativeMethod gSurfaceTextureMethods[] = {
    {"nativeClassInit",     "()V",  (void*)nativeClassInit },
    {"init",                "(I)V", (void*)SurfaceTexture_init },
    {"updateTexImage",      "()V",  (void*)SurfaceTexture_updateTexImage },
    {"getTransformMatrixImpl", "([F)V",  (void*)SurfaceTexture_getTransformMatrix },
};

static void nativeClassInit(JNIEnv* env, jclass clazz)
{
    st.surfaceTexture = env->GetFieldID(clazz,
            ANDROID_GRAPHICS_SURFACETEXTURE_JNI_ID, "I");
}

int register_android_graphics_SurfaceTexture(JNIEnv* env)
{
    int err = 0;
    err = AndroidRuntime::registerNativeMethods(env, kSurfaceTextureClassPathName,
            gSurfaceTextureMethods, NELEM(gSurfaceTextureMethods));
    return err;
}

} // namespace android
