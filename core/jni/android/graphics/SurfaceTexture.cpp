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
const char* const kSurfaceTextureClassPathName = "android/graphics/SurfaceTexture";

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

bool android_SurfaceTexture_isInstanceOf(JNIEnv* env, jobject thiz)
{
    jclass surfaceTextureClass = env->FindClass(kSurfaceTextureClassPathName);
    return env->IsInstanceOf(thiz, surfaceTextureClass);
}

// ----------------------------------------------------------------------------

class JNISurfaceTextureContext : public SurfaceTexture::FrameAvailableListener
{
public:
    JNISurfaceTextureContext(JNIEnv* env, jobject weakThiz, jclass clazz);
    virtual ~JNISurfaceTextureContext();
    virtual void onFrameAvailable();

private:
    static JNIEnv* getJNIEnv(bool* needsDetach);
    static void detachJNI();

    jobject mWeakThiz;
    jclass mClazz;
};

JNISurfaceTextureContext::JNISurfaceTextureContext(JNIEnv* env,
        jobject weakThiz, jclass clazz) :
    mWeakThiz(env->NewGlobalRef(weakThiz)),
    mClazz((jclass)env->NewGlobalRef(clazz))
{}

JNIEnv* JNISurfaceTextureContext::getJNIEnv(bool* needsDetach) {
    *needsDetach = false;
    JNIEnv* env = AndroidRuntime::getJNIEnv();
    if (env == NULL) {
        JavaVMAttachArgs args = {JNI_VERSION_1_4, NULL, NULL};
        JavaVM* vm = AndroidRuntime::getJavaVM();
        int result = vm->AttachCurrentThread(&env, (void*) &args);
        if (result != JNI_OK) {
            ALOGE("thread attach failed: %#x", result);
            return NULL;
        }
        *needsDetach = true;
    }
    return env;
}

void JNISurfaceTextureContext::detachJNI() {
    JavaVM* vm = AndroidRuntime::getJavaVM();
    int result = vm->DetachCurrentThread();
    if (result != JNI_OK) {
        ALOGE("thread detach failed: %#x", result);
    }
}

JNISurfaceTextureContext::~JNISurfaceTextureContext()
{
    bool needsDetach = false;
    JNIEnv* env = getJNIEnv(&needsDetach);
    if (env != NULL) {
        env->DeleteGlobalRef(mWeakThiz);
        env->DeleteGlobalRef(mClazz);
    } else {
        ALOGW("leaking JNI object references");
    }
    if (needsDetach) {
        detachJNI();
    }
}

void JNISurfaceTextureContext::onFrameAvailable()
{
    bool needsDetach = false;
    JNIEnv* env = getJNIEnv(&needsDetach);
    if (env != NULL) {
        env->CallStaticVoidMethod(mClazz, fields.postEvent, mWeakThiz);
    } else {
        ALOGW("onFrameAvailable event will not posted");
    }
    if (needsDetach) {
        detachJNI();
    }
}

// ----------------------------------------------------------------------------

static void SurfaceTexture_classInit(JNIEnv* env, jclass clazz)
{
    fields.surfaceTexture = env->GetFieldID(clazz,
            ANDROID_GRAPHICS_SURFACETEXTURE_JNI_ID, "I");
    if (fields.surfaceTexture == NULL) {
        ALOGE("can't find android/graphics/SurfaceTexture.%s",
                ANDROID_GRAPHICS_SURFACETEXTURE_JNI_ID);
    }

    fields.postEvent = env->GetStaticMethodID(clazz, "postEventFromNative",
            "(Ljava/lang/Object;)V");
    if (fields.postEvent == NULL) {
        ALOGE("can't find android/graphics/SurfaceTexture.postEventFromNative");
    }
}

static void SurfaceTexture_init(JNIEnv* env, jobject thiz, jint texName,
        jobject weakThiz, jboolean allowSynchronous)
{
    sp<SurfaceTexture> surfaceTexture(new SurfaceTexture(texName, allowSynchronous));
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

static void SurfaceTexture_setDefaultBufferSize(
        JNIEnv* env, jobject thiz, jint width, jint height)
{
    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    surfaceTexture->setDefaultBufferSize(width, height);
}

static jint SurfaceTexture_updateTexImage(JNIEnv* env, jobject thiz)
{
    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    return surfaceTexture->updateTexImage();
}

static void SurfaceTexture_getTransformMatrix(JNIEnv* env, jobject thiz,
        jfloatArray jmtx)
{
    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    float* mtx = env->GetFloatArrayElements(jmtx, NULL);
    surfaceTexture->getTransformMatrix(mtx);
    env->ReleaseFloatArrayElements(jmtx, mtx, 0);
}

static jlong SurfaceTexture_getTimestamp(JNIEnv* env, jobject thiz)
{
    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    return surfaceTexture->getTimestamp();
}

static void SurfaceTexture_release(JNIEnv* env, jobject thiz)
{
    sp<SurfaceTexture> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    surfaceTexture->abandon();
}

// ----------------------------------------------------------------------------

static JNINativeMethod gSurfaceTextureMethods[] = {
    {"nativeClassInit",          "()V",   (void*)SurfaceTexture_classInit },
    {"nativeInit",               "(ILjava/lang/Object;Z)V", (void*)SurfaceTexture_init },
    {"nativeFinalize",           "()V",   (void*)SurfaceTexture_finalize },
    {"nativeSetDefaultBufferSize", "(II)V", (void*)SurfaceTexture_setDefaultBufferSize },
    {"nativeUpdateTexImage",     "()I",   (void*)SurfaceTexture_updateTexImage },
    {"nativeGetTransformMatrix", "([F)V", (void*)SurfaceTexture_getTransformMatrix },
    {"nativeGetTimestamp",       "()J",   (void*)SurfaceTexture_getTimestamp },
    {"nativeRelease",            "()V",   (void*)SurfaceTexture_release },
};

int register_android_graphics_SurfaceTexture(JNIEnv* env)
{
    int err = 0;
    err = AndroidRuntime::registerNativeMethods(env, kSurfaceTextureClassPathName,
            gSurfaceTextureMethods, NELEM(gSurfaceTextureMethods));
    return err;
}

} // namespace android
