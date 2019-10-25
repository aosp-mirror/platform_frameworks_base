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

#include "GraphicsJNI.h"

#ifdef __ANDROID__ // Layoutlib does not support Looper and device properties
#include <utils/Looper.h>
#endif

#include <SkBitmap.h>
#include <SkRegion.h>

#include <Rect.h>
#include <RenderNode.h>
#include <CanvasProperty.h>
#include <hwui/Canvas.h>
#include <hwui/Paint.h>
#include <minikin/Layout.h>
#ifdef __ANDROID__ // Layoutlib does not support RenderThread
#include <renderthread/RenderProxy.h>
#endif

namespace android {

using namespace uirenderer;

jmethodID gRunnableMethodId;

static JNIEnv* jnienv(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOG_ALWAYS_FATAL("Failed to get JNIEnv for JavaVM: %p", vm);
    }
    return env;
}

#ifdef __ANDROID__ // Layoutlib does not support GL, Looper
class InvokeRunnableMessage : public MessageHandler {
public:
    InvokeRunnableMessage(JNIEnv* env, jobject runnable) {
        mRunnable = env->NewGlobalRef(runnable);
        env->GetJavaVM(&mVm);
    }

    virtual ~InvokeRunnableMessage() {
        jnienv(mVm)->DeleteGlobalRef(mRunnable);
    }

    virtual void handleMessage(const Message&) {
        jnienv(mVm)->CallVoidMethod(mRunnable, gRunnableMethodId);
    }

private:
    JavaVM* mVm;
    jobject mRunnable;
};

class GlFunctorReleasedCallbackBridge : public GlFunctorLifecycleListener {
public:
    GlFunctorReleasedCallbackBridge(JNIEnv* env, jobject javaCallback) {
        mLooper = Looper::getForThread();
        mMessage = new InvokeRunnableMessage(env, javaCallback);
    }

    virtual void onGlFunctorReleased(Functor* functor) override {
        mLooper->sendMessage(mMessage, 0);
    }

private:
    sp<Looper> mLooper;
    sp<InvokeRunnableMessage> mMessage;
};
#endif

// ---------------- @FastNative -----------------------------

static void android_view_DisplayListCanvas_callDrawGLFunction(JNIEnv* env, jobject clazz,
        jlong canvasPtr, jlong functorPtr, jobject releasedCallback) {
#ifdef __ANDROID__ // Layoutlib does not support GL
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    Functor* functor = reinterpret_cast<Functor*>(functorPtr);
    sp<GlFunctorReleasedCallbackBridge> bridge;
    if (releasedCallback) {
        bridge = new GlFunctorReleasedCallbackBridge(env, releasedCallback);
    }
    canvas->callDrawGLFunction(functor, bridge.get());
#endif
}


// ---------------- @CriticalNative -------------------------

static jlong android_view_DisplayListCanvas_createDisplayListCanvas(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        jint width, jint height) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return reinterpret_cast<jlong>(Canvas::create_recording_canvas(width, height, renderNode));
}

static void android_view_DisplayListCanvas_resetDisplayListCanvas(CRITICAL_JNI_PARAMS_COMMA jlong canvasPtr,
        jlong renderNodePtr, jint width, jint height) {
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    canvas->resetRecording(width, height, renderNode);
}

static jint android_view_DisplayListCanvas_getMaxTextureSize(CRITICAL_JNI_PARAMS) {
#ifdef __ANDROID__ // Layoutlib does not support RenderProxy (RenderThread)
    return android::uirenderer::renderthread::RenderProxy::maxTextureSize();
#else
    return 4096;
#endif
}

static void android_view_DisplayListCanvas_insertReorderBarrier(CRITICAL_JNI_PARAMS_COMMA jlong canvasPtr,
        jboolean reorderEnable) {
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    canvas->insertReorderBarrier(reorderEnable);
}

static jlong android_view_DisplayListCanvas_finishRecording(CRITICAL_JNI_PARAMS_COMMA jlong canvasPtr) {
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    return reinterpret_cast<jlong>(canvas->finishRecording());
}

static void android_view_DisplayListCanvas_drawRenderNode(CRITICAL_JNI_PARAMS_COMMA jlong canvasPtr, jlong renderNodePtr) {
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    canvas->drawRenderNode(renderNode);
}

static void android_view_DisplayListCanvas_drawTextureLayer(CRITICAL_JNI_PARAMS_COMMA jlong canvasPtr, jlong layerPtr) {
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerPtr);
    canvas->drawLayer(layer);
}

static void android_view_DisplayListCanvas_drawRoundRectProps(CRITICAL_JNI_PARAMS_COMMA jlong canvasPtr,
        jlong leftPropPtr, jlong topPropPtr, jlong rightPropPtr, jlong bottomPropPtr,
        jlong rxPropPtr, jlong ryPropPtr, jlong paintPropPtr) {
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    CanvasPropertyPrimitive* leftProp = reinterpret_cast<CanvasPropertyPrimitive*>(leftPropPtr);
    CanvasPropertyPrimitive* topProp = reinterpret_cast<CanvasPropertyPrimitive*>(topPropPtr);
    CanvasPropertyPrimitive* rightProp = reinterpret_cast<CanvasPropertyPrimitive*>(rightPropPtr);
    CanvasPropertyPrimitive* bottomProp = reinterpret_cast<CanvasPropertyPrimitive*>(bottomPropPtr);
    CanvasPropertyPrimitive* rxProp = reinterpret_cast<CanvasPropertyPrimitive*>(rxPropPtr);
    CanvasPropertyPrimitive* ryProp = reinterpret_cast<CanvasPropertyPrimitive*>(ryPropPtr);
    CanvasPropertyPaint* paintProp = reinterpret_cast<CanvasPropertyPaint*>(paintPropPtr);
    canvas->drawRoundRect(leftProp, topProp, rightProp, bottomProp, rxProp, ryProp, paintProp);
}

static void android_view_DisplayListCanvas_drawCircleProps(CRITICAL_JNI_PARAMS_COMMA jlong canvasPtr,
        jlong xPropPtr, jlong yPropPtr, jlong radiusPropPtr, jlong paintPropPtr) {
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    CanvasPropertyPrimitive* xProp = reinterpret_cast<CanvasPropertyPrimitive*>(xPropPtr);
    CanvasPropertyPrimitive* yProp = reinterpret_cast<CanvasPropertyPrimitive*>(yPropPtr);
    CanvasPropertyPrimitive* radiusProp = reinterpret_cast<CanvasPropertyPrimitive*>(radiusPropPtr);
    CanvasPropertyPaint* paintProp = reinterpret_cast<CanvasPropertyPaint*>(paintPropPtr);
    canvas->drawCircle(xProp, yProp, radiusProp, paintProp);
}

static void android_view_DisplayListCanvas_drawWebViewFunctor(CRITICAL_JNI_PARAMS_COMMA jlong canvasPtr, jint functor) {
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    canvas->drawWebViewFunctor(functor);
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/graphics/RecordingCanvas";

static JNINativeMethod gMethods[] = {

    // ------------ @FastNative ------------------

    { "nCallDrawGLFunction", "(JJLjava/lang/Runnable;)V",
            (void*) android_view_DisplayListCanvas_callDrawGLFunction },

    // ------------ @CriticalNative --------------
    { "nCreateDisplayListCanvas", "(JII)J",     (void*) android_view_DisplayListCanvas_createDisplayListCanvas },
    { "nResetDisplayListCanvas",  "(JJII)V",    (void*) android_view_DisplayListCanvas_resetDisplayListCanvas },
    { "nGetMaximumTextureWidth",  "()I",        (void*) android_view_DisplayListCanvas_getMaxTextureSize },
    { "nGetMaximumTextureHeight", "()I",        (void*) android_view_DisplayListCanvas_getMaxTextureSize },
    { "nInsertReorderBarrier",    "(JZ)V",      (void*) android_view_DisplayListCanvas_insertReorderBarrier },
    { "nFinishRecording",         "(J)J",       (void*) android_view_DisplayListCanvas_finishRecording },
    { "nDrawRenderNode",          "(JJ)V",      (void*) android_view_DisplayListCanvas_drawRenderNode },
    { "nDrawTextureLayer",        "(JJ)V",      (void*) android_view_DisplayListCanvas_drawTextureLayer },
    { "nDrawCircle",              "(JJJJJ)V",   (void*) android_view_DisplayListCanvas_drawCircleProps },
    { "nDrawRoundRect",           "(JJJJJJJJ)V",(void*) android_view_DisplayListCanvas_drawRoundRectProps },
    { "nDrawWebViewFunctor",      "(JI)V",      (void*) android_view_DisplayListCanvas_drawWebViewFunctor },
};

int register_android_view_DisplayListCanvas(JNIEnv* env) {
    jclass runnableClass = FindClassOrDie(env, "java/lang/Runnable");
    gRunnableMethodId = GetMethodIDOrDie(env, runnableClass, "run", "()V");

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
