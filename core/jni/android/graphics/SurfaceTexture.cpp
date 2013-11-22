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

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <gui/GLConsumer.h>
#include <gui/Surface.h>

#include <android_runtime/AndroidRuntime.h>

#include <utils/Log.h>
#include <utils/misc.h>

#include "jni.h"
#include "JNIHelp.h"

// ----------------------------------------------------------------------------

namespace android {

static const char* const OutOfResourcesException =
    "android/view/Surface$OutOfResourcesException";
static const char* const IllegalStateException = "java/lang/IllegalStateException";
const char* const kSurfaceTextureClassPathName = "android/graphics/SurfaceTexture";

struct fields_t {
    jfieldID  surfaceTexture;
    jfieldID  bufferQueue;
    jfieldID  frameAvailableListener;
    jmethodID postEvent;
};
static fields_t fields;

// ----------------------------------------------------------------------------

static void SurfaceTexture_setSurfaceTexture(JNIEnv* env, jobject thiz,
        const sp<GLConsumer>& surfaceTexture)
{
    GLConsumer* const p =
        (GLConsumer*)env->GetIntField(thiz, fields.surfaceTexture);
    if (surfaceTexture.get()) {
        surfaceTexture->incStrong((void*)SurfaceTexture_setSurfaceTexture);
    }
    if (p) {
        p->decStrong((void*)SurfaceTexture_setSurfaceTexture);
    }
    env->SetIntField(thiz, fields.surfaceTexture, (int)surfaceTexture.get());
}

static void SurfaceTexture_setBufferQueue(JNIEnv* env, jobject thiz,
        const sp<BufferQueue>& bq)
{
    BufferQueue* const p =
        (BufferQueue*)env->GetIntField(thiz, fields.bufferQueue);
    if (bq.get()) {
        bq->incStrong((void*)SurfaceTexture_setBufferQueue);
    }
    if (p) {
        p->decStrong((void*)SurfaceTexture_setBufferQueue);
    }
    env->SetIntField(thiz, fields.bufferQueue, (int)bq.get());
}

static void SurfaceTexture_setFrameAvailableListener(JNIEnv* env,
        jobject thiz, sp<GLConsumer::FrameAvailableListener> listener)
{
    GLConsumer::FrameAvailableListener* const p =
        (GLConsumer::FrameAvailableListener*)
            env->GetIntField(thiz, fields.frameAvailableListener);
    if (listener.get()) {
        listener->incStrong((void*)SurfaceTexture_setSurfaceTexture);
    }
    if (p) {
        p->decStrong((void*)SurfaceTexture_setSurfaceTexture);
    }
    env->SetIntField(thiz, fields.frameAvailableListener, (int)listener.get());
}

sp<GLConsumer> SurfaceTexture_getSurfaceTexture(JNIEnv* env, jobject thiz) {
    return (GLConsumer*)env->GetIntField(thiz, fields.surfaceTexture);
}

sp<IGraphicBufferProducer> SurfaceTexture_getProducer(JNIEnv* env, jobject thiz) {
    return (BufferQueue*)env->GetIntField(thiz, fields.bufferQueue);
}

sp<ANativeWindow> android_SurfaceTexture_getNativeWindow(JNIEnv* env, jobject thiz) {
    sp<GLConsumer> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    sp<IGraphicBufferProducer> producer(SurfaceTexture_getProducer(env, thiz));
    sp<Surface> surfaceTextureClient(surfaceTexture != NULL ? new Surface(producer) : NULL);
    return surfaceTextureClient;
}

bool android_SurfaceTexture_isInstanceOf(JNIEnv* env, jobject thiz) {
    jclass surfaceTextureClass = env->FindClass(kSurfaceTextureClassPathName);
    return env->IsInstanceOf(thiz, surfaceTextureClass);
}

// ----------------------------------------------------------------------------

class JNISurfaceTextureContext : public GLConsumer::FrameAvailableListener
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


#define ANDROID_GRAPHICS_SURFACETEXTURE_JNI_ID "mSurfaceTexture"
#define ANDROID_GRAPHICS_BUFFERQUEUE_JNI_ID "mBufferQueue"
#define ANDROID_GRAPHICS_FRAMEAVAILABLELISTENER_JNI_ID \
                                         "mFrameAvailableListener"

static void SurfaceTexture_classInit(JNIEnv* env, jclass clazz)
{
    fields.surfaceTexture = env->GetFieldID(clazz,
            ANDROID_GRAPHICS_SURFACETEXTURE_JNI_ID, "I");
    if (fields.surfaceTexture == NULL) {
        ALOGE("can't find android/graphics/SurfaceTexture.%s",
                ANDROID_GRAPHICS_SURFACETEXTURE_JNI_ID);
    }
    fields.bufferQueue = env->GetFieldID(clazz,
            ANDROID_GRAPHICS_BUFFERQUEUE_JNI_ID, "I");
    if (fields.bufferQueue == NULL) {
        ALOGE("can't find android/graphics/SurfaceTexture.%s",
                ANDROID_GRAPHICS_BUFFERQUEUE_JNI_ID);
    }
    fields.frameAvailableListener = env->GetFieldID(clazz,
            ANDROID_GRAPHICS_FRAMEAVAILABLELISTENER_JNI_ID, "I");
    if (fields.frameAvailableListener == NULL) {
        ALOGE("can't find android/graphics/SurfaceTexture.%s",
                ANDROID_GRAPHICS_FRAMEAVAILABLELISTENER_JNI_ID);
    }

    fields.postEvent = env->GetStaticMethodID(clazz, "postEventFromNative",
            "(Ljava/lang/Object;)V");
    if (fields.postEvent == NULL) {
        ALOGE("can't find android/graphics/SurfaceTexture.postEventFromNative");
    }
}

static void SurfaceTexture_init(JNIEnv* env, jobject thiz,
        jint texName, jboolean singleBufferMode, jobject weakThiz)
{
    sp<BufferQueue> bq = new BufferQueue();

    if (singleBufferMode) {
        bq->disableAsyncBuffer();
        bq->setDefaultMaxBufferCount(1);
    }

    sp<GLConsumer> surfaceTexture(new GLConsumer(bq, texName, GL_TEXTURE_EXTERNAL_OES, true, true));
    if (surfaceTexture == 0) {
        jniThrowException(env, OutOfResourcesException,
                "Unable to create native SurfaceTexture");
        return;
    }
    SurfaceTexture_setSurfaceTexture(env, thiz, surfaceTexture);
    SurfaceTexture_setBufferQueue(env, thiz, bq);

    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        jniThrowRuntimeException(env,
                "Can't find android/graphics/SurfaceTexture");
        return;
    }

    sp<JNISurfaceTextureContext> ctx(new JNISurfaceTextureContext(env, weakThiz,
            clazz));
    surfaceTexture->setFrameAvailableListener(ctx);
    SurfaceTexture_setFrameAvailableListener(env, thiz, ctx);
}

static void SurfaceTexture_finalize(JNIEnv* env, jobject thiz)
{
    sp<GLConsumer> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    surfaceTexture->setFrameAvailableListener(0);
    SurfaceTexture_setFrameAvailableListener(env, thiz, 0);
    SurfaceTexture_setSurfaceTexture(env, thiz, 0);
    SurfaceTexture_setBufferQueue(env, thiz, 0);
}

static void SurfaceTexture_setDefaultBufferSize(
        JNIEnv* env, jobject thiz, jint width, jint height) {
    sp<GLConsumer> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    surfaceTexture->setDefaultBufferSize(width, height);
}

static void SurfaceTexture_updateTexImage(JNIEnv* env, jobject thiz)
{
    sp<GLConsumer> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    status_t err = surfaceTexture->updateTexImage();
    if (err == INVALID_OPERATION) {
        jniThrowException(env, IllegalStateException, "Unable to update texture contents (see "
                "logcat for details)");
    } else if (err < 0) {
        jniThrowRuntimeException(env, "Error during updateTexImage (see logcat for details)");
    }
}

static void SurfaceTexture_releaseTexImage(JNIEnv* env, jobject thiz)
{
    sp<GLConsumer> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    status_t err = surfaceTexture->releaseTexImage();
    if (err == INVALID_OPERATION) {
        jniThrowException(env, IllegalStateException, "Unable to release texture contents (see "
                "logcat for details)");
    } else if (err < 0) {
        jniThrowRuntimeException(env, "Error during updateTexImage (see logcat for details)");
    }
}

static jint SurfaceTexture_detachFromGLContext(JNIEnv* env, jobject thiz)
{
    sp<GLConsumer> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    return surfaceTexture->detachFromContext();
}

static jint SurfaceTexture_attachToGLContext(JNIEnv* env, jobject thiz, jint tex)
{
    sp<GLConsumer> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    return surfaceTexture->attachToContext((GLuint)tex);
}

static void SurfaceTexture_getTransformMatrix(JNIEnv* env, jobject thiz,
        jfloatArray jmtx)
{
    sp<GLConsumer> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    float* mtx = env->GetFloatArrayElements(jmtx, NULL);
    surfaceTexture->getTransformMatrix(mtx);
    env->ReleaseFloatArrayElements(jmtx, mtx, 0);
}

static jlong SurfaceTexture_getTimestamp(JNIEnv* env, jobject thiz)
{
    sp<GLConsumer> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    return surfaceTexture->getTimestamp();
}

static void SurfaceTexture_release(JNIEnv* env, jobject thiz)
{
    sp<GLConsumer> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, thiz));
    surfaceTexture->abandon();
}

// ----------------------------------------------------------------------------

static JNINativeMethod gSurfaceTextureMethods[] = {
    {"nativeClassInit",            "()V",   (void*)SurfaceTexture_classInit },
    {"nativeInit",                 "(IZLjava/lang/Object;)V", (void*)SurfaceTexture_init },
    {"nativeFinalize",             "()V",   (void*)SurfaceTexture_finalize },
    {"nativeSetDefaultBufferSize", "(II)V", (void*)SurfaceTexture_setDefaultBufferSize },
    {"nativeUpdateTexImage",       "()V",   (void*)SurfaceTexture_updateTexImage },
    {"nativeReleaseTexImage",      "()V",   (void*)SurfaceTexture_releaseTexImage },
    {"nativeDetachFromGLContext",  "()I",   (void*)SurfaceTexture_detachFromGLContext },
    {"nativeAttachToGLContext",    "(I)I",   (void*)SurfaceTexture_attachToGLContext },
    {"nativeGetTransformMatrix",   "([F)V", (void*)SurfaceTexture_getTransformMatrix },
    {"nativeGetTimestamp",         "()J",   (void*)SurfaceTexture_getTimestamp },
    {"nativeRelease",              "()V",   (void*)SurfaceTexture_release },
};

int register_android_graphics_SurfaceTexture(JNIEnv* env)
{
    int err = 0;
    err = AndroidRuntime::registerNativeMethods(env, kSurfaceTextureClassPathName,
            gSurfaceTextureMethods, NELEM(gSurfaceTextureMethods));
    return err;
}

} // namespace android
