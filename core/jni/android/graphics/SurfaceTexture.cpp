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
#include <gui/SurfaceTextureClient.h>

#include <android_runtime/AndroidRuntime.h>

#include <utils/Log.h>
#include <utils/misc.h>

#include "jni.h"
#include "JNIHelp.h"

// ----------------------------------------------------------------------------

namespace android {

static const char* const OutOfResourcesException =
    "android/graphics/SurfaceTexture$OutOfResourcesException";

struct fields_t {
    jfieldID  surfaceTexture;
    jmethodID postEvent;
};
static fields_t fields;

// ----------------------------------------------------------------------------

static void SurfaceTexture_setSurfaceTexture(JNIEnv* env, jobject thiz,
        const sp<SurfaceTexture>& surfaceTexture)
{
    SurfaceTexture* const p =
        (SurfaceTexture*)env->GetIntField(thiz, fields.surfaceTexture);
    if (surfaceTexture.get()) {
        surfaceTexture->incStrong(thiz);
    }
    if (p) {
        p->decStrong(thiz);
    }
    env->SetIntField(thiz, fields.surfaceTexture, (int)surfaceTexture.get());
}

sp<SurfaceTexture> SurfaceTexture_getSurfaceTexture(JNIEnv* env, jobject thiz)
{
    sp<SurfaceTexture> surfaceTexture(
        (SurfaceTexture*)env->GetIntField(thiz, fields.surfaceTexture));
    return surfaceTexture;
}

sp<ANativeWindow> android_SurfaceTexture_getNativeWindow(
        JNIEnv* env, jobject thiz)
{
    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    sp<SurfaceTextureClient> surfaceTextureClient(surfaceTexture != NULL ?
            new SurfaceTextureClient(surfaceTexture) : NULL);
    return surfaceTextureClient;
}

// ----------------------------------------------------------------------------

class JNISurfaceTextureContext : public SurfaceTexture::FrameAvailableListener
{
public:
    JNISurfaceTextureContext(JNIEnv* env, jobject weakThiz, jclass clazz);
    virtual ~JNISurfaceTextureContext();
    virtual void onFrameAvailable();

private:
    jobject mWeakThiz;
    jclass mClazz;
};

JNISurfaceTextureContext::JNISurfaceTextureContext(JNIEnv* env,
        jobject weakThiz, jclass clazz) :
    mWeakThiz(env->NewGlobalRef(weakThiz)),
    mClazz((jclass)env->NewGlobalRef(clazz))
{}

JNISurfaceTextureContext::~JNISurfaceTextureContext()
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mWeakThiz);
    env->DeleteGlobalRef(mClazz);
}

void JNISurfaceTextureContext::onFrameAvailable()
{
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->CallStaticVoidMethod(mClazz, fields.postEvent, mWeakThiz);
}

// ----------------------------------------------------------------------------

static void SurfaceTexture_classInit(JNIEnv* env, jclass clazz)
{
    fields.surfaceTexture = env->GetFieldID(clazz,
            ANDROID_GRAPHICS_SURFACETEXTURE_JNI_ID, "I");
    if (fields.surfaceTexture == NULL) {
        LOGE("can't find android/graphics/SurfaceTexture.%s",
                ANDROID_GRAPHICS_SURFACETEXTURE_JNI_ID);
    }

    fields.postEvent = env->GetStaticMethodID(clazz, "postEventFromNative",
            "(Ljava/lang/Object;)V");
    if (fields.postEvent == NULL) {
        LOGE("can't find android/graphics/SurfaceTexture.postEventFromNative");
    }

}

static void SurfaceTexture_init(JNIEnv* env, jobject thiz, jint texName,
        jobject weakThiz)
{
    sp<SurfaceTexture> surfaceTexture(new SurfaceTexture(texName));
    if (surfaceTexture == 0) {
        jniThrowException(env, OutOfResourcesException,
                "Unable to create native SurfaceTexture");
        return;
    }
    SurfaceTexture_setSurfaceTexture(env, thiz, surfaceTexture);

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        jniThrowRuntimeException(env,
                "Can't find android/graphics/SurfaceTexture");
        return;
    }

    sp<JNISurfaceTextureContext> ctx(new JNISurfaceTextureContext(env, weakThiz,
            clazz));
    surfaceTexture->setFrameAvailableListener(ctx);
}

static void SurfaceTexture_finalize(JNIEnv* env, jobject thiz)
{
    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    surfaceTexture->setFrameAvailableListener(0);
    SurfaceTexture_setSurfaceTexture(env, thiz, 0);
}

static void SurfaceTexture_updateTexImage(JNIEnv* env, jobject thiz)
{
    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    surfaceTexture->updateTexImage();
}

static void SurfaceTexture_getTransformMatrix(JNIEnv* env, jobject thiz,
        jfloatArray jmtx)
{
    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    float* mtx = env->GetFloatArrayElements(jmtx, NULL);
    surfaceTexture->getTransformMatrix(mtx);
    env->ReleaseFloatArrayElements(jmtx, mtx, 0);
}

// ----------------------------------------------------------------------------

const char* const kSurfaceTextureClassPathName = "android/graphics/SurfaceTexture";

static JNINativeMethod gSurfaceTextureMethods[] = {
    {"nativeClassInit",          "()V",   (void*)SurfaceTexture_classInit },
    {"nativeInit",               "(ILjava/lang/Object;)V", (void*)SurfaceTexture_init },
    {"nativeFinalize",            "()V",  (void*)SurfaceTexture_finalize },
    {"nativeUpdateTexImage",     "()V",   (void*)SurfaceTexture_updateTexImage },
    {"nativeGetTransformMatrix", "([F)V", (void*)SurfaceTexture_getTransformMatrix },
};

int register_android_graphics_SurfaceTexture(JNIEnv* env)
{
    int err = 0;
    err = AndroidRuntime::registerNativeMethods(env, kSurfaceTextureClassPathName,
            gSurfaceTextureMethods, NELEM(gSurfaceTextureMethods));
    return err;
}

} // namespace android
