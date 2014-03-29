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

#define LOG_TAG "ThreadedRenderer"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <utils/StrongPointer.h>
#include <android_runtime/android_view_Surface.h>
#include <system/window.h>

#include <renderthread/RenderProxy.h>
#include <renderthread/RenderTask.h>
#include <renderthread/RenderThread.h>

namespace android {

#ifdef USE_OPENGL_RENDERER

using namespace android::uirenderer;
using namespace android::uirenderer::renderthread;

static jmethodID gRunnableMethod;

class JavaTask : public RenderTask {
public:
    JavaTask(JNIEnv* env, jobject jrunnable) {
        env->GetJavaVM(&mVm);
        mRunnable = env->NewGlobalRef(jrunnable);
    }

    virtual void run() {
        env()->CallVoidMethod(mRunnable, gRunnableMethod);
        env()->DeleteGlobalRef(mRunnable);
        delete this;
    };

private:
    JNIEnv* env() {
        JNIEnv* env;
        if (mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            return 0;
        }
        return env;
    }

    JavaVM* mVm;
    jobject mRunnable;
};

static void android_view_ThreadedRenderer_postToRenderThread(JNIEnv* env, jobject clazz,
        jobject jrunnable) {
    RenderTask* task = new JavaTask(env, jrunnable);
    RenderThread::getInstance().queue(task);
}

static jlong android_view_ThreadedRenderer_createProxy(JNIEnv* env, jobject clazz,
        jboolean translucent) {
    return (jlong) new RenderProxy(translucent);
}

static void android_view_ThreadedRenderer_deleteProxy(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    delete proxy;
}

static jboolean android_view_ThreadedRenderer_initialize(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jobject jsurface) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    sp<ANativeWindow> window = android_view_Surface_getNativeWindow(env, jsurface);
    return proxy->initialize(window.get());
}

static void android_view_ThreadedRenderer_updateSurface(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jobject jsurface) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    sp<ANativeWindow> window;
    if (jsurface) {
        window = android_view_Surface_getNativeWindow(env, jsurface);
    }
    proxy->updateSurface(window.get());
}

static void android_view_ThreadedRenderer_setup(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jint width, jint height) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->setup(width, height);
}

static void android_view_ThreadedRenderer_setDisplayListData(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong displayListPtr, jlong newDataPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    DisplayListData* newData = reinterpret_cast<DisplayListData*>(newDataPtr);
    proxy->setDisplayListData(displayList, newData);
}

static void android_view_ThreadedRenderer_drawDisplayList(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong displayListPtr, jint dirtyLeft, jint dirtyTop,
        jint dirtyRight, jint dirtyBottom) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    proxy->drawDisplayList(displayList, dirtyLeft, dirtyTop, dirtyRight, dirtyBottom);
}

static void android_view_ThreadedRenderer_destroyCanvas(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    proxy->destroyCanvas();
}

static void android_view_ThreadedRenderer_attachFunctor(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong functorPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    Functor* functor = reinterpret_cast<Functor*>(functorPtr);
    proxy->attachFunctor(functor);
}

static void android_view_ThreadedRenderer_detachFunctor(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong functorPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    Functor* functor = reinterpret_cast<Functor*>(functorPtr);
    proxy->detachFunctor(functor);
}

static void android_view_ThreadedRenderer_invokeFunctor(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong functorPtr, jboolean waitForCompletion) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    Functor* functor = reinterpret_cast<Functor*>(functorPtr);
    proxy->invokeFunctor(functor, waitForCompletion);
}

static void android_view_ThreadedRenderer_runWithGlContext(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jobject jrunnable) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    RenderTask* task = new JavaTask(env, jrunnable);
    proxy->runWithGlContext(task);
}

static jlong android_view_ThreadedRenderer_createDisplayListLayer(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jint width, jint height) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    DeferredLayerUpdater* layer = proxy->createDisplayListLayer(width, height);
    return reinterpret_cast<jlong>(layer);
}

static jlong android_view_ThreadedRenderer_createTextureLayer(JNIEnv* env, jobject clazz,
        jlong proxyPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    DeferredLayerUpdater* layer = proxy->createTextureLayer();
    return reinterpret_cast<jlong>(layer);
}

static jboolean android_view_ThreadedRenderer_copyLayerInto(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong layerPtr, jlong bitmapPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerPtr);
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapPtr);
    return proxy->copyLayerInto(layer, bitmap);
}

static void android_view_ThreadedRenderer_destroyLayer(JNIEnv* env, jobject clazz,
        jlong proxyPtr, jlong layerPtr) {
    RenderProxy* proxy = reinterpret_cast<RenderProxy*>(proxyPtr);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerPtr);
    proxy->destroyLayer(layer);
}

#endif

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/ThreadedRenderer";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "postToRenderThread", "(Ljava/lang/Runnable;)V",   (void*) android_view_ThreadedRenderer_postToRenderThread },
    { "nCreateProxy", "(Z)J", (void*) android_view_ThreadedRenderer_createProxy },
    { "nDeleteProxy", "(J)V", (void*) android_view_ThreadedRenderer_deleteProxy },
    { "nInitialize", "(JLandroid/view/Surface;)Z", (void*) android_view_ThreadedRenderer_initialize },
    { "nUpdateSurface", "(JLandroid/view/Surface;)V", (void*) android_view_ThreadedRenderer_updateSurface },
    { "nSetup", "(JII)V", (void*) android_view_ThreadedRenderer_setup },
    { "nSetDisplayListData", "(JJJ)V", (void*) android_view_ThreadedRenderer_setDisplayListData },
    { "nDrawDisplayList", "(JJIIII)V", (void*) android_view_ThreadedRenderer_drawDisplayList },
    { "nDestroyCanvas", "(J)V", (void*) android_view_ThreadedRenderer_destroyCanvas },
    { "nAttachFunctor", "(JJ)V", (void*) android_view_ThreadedRenderer_attachFunctor },
    { "nDetachFunctor", "(JJ)V", (void*) android_view_ThreadedRenderer_detachFunctor },
    { "nInvokeFunctor", "(JJZ)V", (void*) android_view_ThreadedRenderer_invokeFunctor },
    { "nRunWithGlContext", "(JLjava/lang/Runnable;)V", (void*) android_view_ThreadedRenderer_runWithGlContext },
    { "nCreateDisplayListLayer", "(JII)J", (void*) android_view_ThreadedRenderer_createDisplayListLayer },
    { "nCreateTextureLayer", "(J)J", (void*) android_view_ThreadedRenderer_createTextureLayer },
    { "nCopyLayerInto", "(JJJ)Z", (void*) android_view_ThreadedRenderer_copyLayerInto },
    { "nDestroyLayer", "(JJ)V", (void*) android_view_ThreadedRenderer_destroyLayer },
#endif
};

int register_android_view_ThreadedRenderer(JNIEnv* env) {
#ifdef USE_OPENGL_RENDERER
    jclass cls = env->FindClass("java/lang/Runnable");
    gRunnableMethod = env->GetMethodID(cls, "run", "()V");
    env->DeleteLocalRef(cls);
#endif
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

}; // namespace android
