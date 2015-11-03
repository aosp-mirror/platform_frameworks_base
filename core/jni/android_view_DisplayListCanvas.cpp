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

#define LOG_TAG "OpenGLRenderer"

#include "jni.h"
#include "GraphicsJNI.h"
#include <nativehelper/JNIHelp.h>

#include <android_runtime/AndroidRuntime.h>

#include <cutils/properties.h>

#include <SkBitmap.h>
#include <SkRegion.h>

#include <DisplayListCanvas.h>
#include <Rect.h>
#include <RenderNode.h>
#include <CanvasProperty.h>
#include <Paint.h>
#include <renderthread/RenderProxy.h>

#include "core_jni_helpers.h"

namespace android {

using namespace uirenderer;

// ----------------------------------------------------------------------------
// Setup
// ----------------------------------------------------------------------------

static void android_view_DisplayListCanvas_insertReorderBarrier(JNIEnv* env, jobject clazz,
        jlong canvasPtr, jboolean reorderEnable) {
    DisplayListCanvas* canvas = reinterpret_cast<DisplayListCanvas*>(canvasPtr);
    canvas->insertReorderBarrier(reorderEnable);
}

// ----------------------------------------------------------------------------
// Functor
// ----------------------------------------------------------------------------

static void android_view_DisplayListCanvas_callDrawGLFunction(JNIEnv* env, jobject clazz,
        jlong canvasPtr, jlong functorPtr) {
    DisplayListCanvas* canvas = reinterpret_cast<DisplayListCanvas*>(canvasPtr);
    Functor* functor = reinterpret_cast<Functor*>(functorPtr);
    canvas->callDrawGLFunction(functor);
}

// ----------------------------------------------------------------------------
// Misc
// ----------------------------------------------------------------------------

static jint android_view_DisplayListCanvas_getMaxTextureWidth(JNIEnv* env, jobject clazz) {
    return Caches::getInstance().maxTextureSize;
}

static jint android_view_DisplayListCanvas_getMaxTextureHeight(JNIEnv* env, jobject clazz) {
    return Caches::getInstance().maxTextureSize;
}

// ----------------------------------------------------------------------------
// Drawing
// ----------------------------------------------------------------------------

static void android_view_DisplayListCanvas_drawRoundRectProps(JNIEnv* env, jobject clazz,
        jlong canvasPtr, jlong leftPropPtr, jlong topPropPtr, jlong rightPropPtr,
        jlong bottomPropPtr, jlong rxPropPtr, jlong ryPropPtr, jlong paintPropPtr) {
    DisplayListCanvas* canvas = reinterpret_cast<DisplayListCanvas*>(canvasPtr);
    CanvasPropertyPrimitive* leftProp = reinterpret_cast<CanvasPropertyPrimitive*>(leftPropPtr);
    CanvasPropertyPrimitive* topProp = reinterpret_cast<CanvasPropertyPrimitive*>(topPropPtr);
    CanvasPropertyPrimitive* rightProp = reinterpret_cast<CanvasPropertyPrimitive*>(rightPropPtr);
    CanvasPropertyPrimitive* bottomProp = reinterpret_cast<CanvasPropertyPrimitive*>(bottomPropPtr);
    CanvasPropertyPrimitive* rxProp = reinterpret_cast<CanvasPropertyPrimitive*>(rxPropPtr);
    CanvasPropertyPrimitive* ryProp = reinterpret_cast<CanvasPropertyPrimitive*>(ryPropPtr);
    CanvasPropertyPaint* paintProp = reinterpret_cast<CanvasPropertyPaint*>(paintPropPtr);
    canvas->drawRoundRect(leftProp, topProp, rightProp, bottomProp, rxProp, ryProp, paintProp);
}

static void android_view_DisplayListCanvas_drawCircleProps(JNIEnv* env, jobject clazz,
        jlong canvasPtr, jlong xPropPtr, jlong yPropPtr, jlong radiusPropPtr, jlong paintPropPtr) {
    DisplayListCanvas* canvas = reinterpret_cast<DisplayListCanvas*>(canvasPtr);
    CanvasPropertyPrimitive* xProp = reinterpret_cast<CanvasPropertyPrimitive*>(xPropPtr);
    CanvasPropertyPrimitive* yProp = reinterpret_cast<CanvasPropertyPrimitive*>(yPropPtr);
    CanvasPropertyPrimitive* radiusProp = reinterpret_cast<CanvasPropertyPrimitive*>(radiusPropPtr);
    CanvasPropertyPaint* paintProp = reinterpret_cast<CanvasPropertyPaint*>(paintPropPtr);
    canvas->drawCircle(xProp, yProp, radiusProp, paintProp);
}

// ----------------------------------------------------------------------------
// Display lists
// ----------------------------------------------------------------------------

static jlong android_view_DisplayListCanvas_finishRecording(JNIEnv* env,
        jobject clazz, jlong canvasPtr) {
    DisplayListCanvas* canvas = reinterpret_cast<DisplayListCanvas*>(canvasPtr);
    return reinterpret_cast<jlong>(canvas->finishRecording());
}

static jlong android_view_DisplayListCanvas_createDisplayListCanvas(JNIEnv* env, jobject clazz,
        jint width, jint height) {
    return reinterpret_cast<jlong>(new DisplayListCanvas(width, height));
}

static void android_view_DisplayListCanvas_resetDisplayListCanvas(JNIEnv* env, jobject clazz,
        jlong canvasPtr, jint width, jint height) {
    DisplayListCanvas* canvas = reinterpret_cast<DisplayListCanvas*>(canvasPtr);
    canvas->reset(width, height);
}


static void android_view_DisplayListCanvas_drawRenderNode(JNIEnv* env,
        jobject clazz, jlong canvasPtr, jlong renderNodePtr) {
    DisplayListCanvas* canvas = reinterpret_cast<DisplayListCanvas*>(canvasPtr);
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    canvas->drawRenderNode(renderNode);
}

// ----------------------------------------------------------------------------
// Layers
// ----------------------------------------------------------------------------

static void android_view_DisplayListCanvas_drawLayer(JNIEnv* env, jobject clazz,
        jlong canvasPtr, jlong layerPtr) {
    DisplayListCanvas* canvas = reinterpret_cast<DisplayListCanvas*>(canvasPtr);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerPtr);
    canvas->drawLayer(layer);
}

// ----------------------------------------------------------------------------
// Common
// ----------------------------------------------------------------------------

static jboolean android_view_DisplayListCanvas_isAvailable(JNIEnv* env, jobject clazz) {
    char prop[PROPERTY_VALUE_MAX];
    if (property_get("ro.kernel.qemu", prop, NULL) == 0) {
        // not in the emulator
        return JNI_TRUE;
    }
    // In the emulator this property will be set to 1 when hardware GLES is
    // enabled, 0 otherwise. On old emulator versions it will be undefined.
    property_get("ro.kernel.qemu.gles", prop, "0");
    return atoi(prop) == 1 ? JNI_TRUE : JNI_FALSE;
}

// ----------------------------------------------------------------------------
// Logging
// ----------------------------------------------------------------------------

static void
android_app_ActivityThread_dumpGraphics(JNIEnv* env, jobject clazz, jobject javaFileDescriptor) {
    int fd = jniGetFDFromFileDescriptor(env, javaFileDescriptor);
    android::uirenderer::renderthread::RenderProxy::dumpGraphicsMemory(fd);
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/DisplayListCanvas";

static JNINativeMethod gMethods[] = {
    { "nIsAvailable",       "!()Z",             (void*) android_view_DisplayListCanvas_isAvailable },
    { "nInsertReorderBarrier","!(JZ)V",         (void*) android_view_DisplayListCanvas_insertReorderBarrier },

    { "nCallDrawGLFunction", "!(JJ)V",          (void*) android_view_DisplayListCanvas_callDrawGLFunction },

    { "nDrawRoundRect",     "!(JJJJJJJJ)V",     (void*) android_view_DisplayListCanvas_drawRoundRectProps },
    { "nDrawCircle",        "!(JJJJJ)V",        (void*) android_view_DisplayListCanvas_drawCircleProps },

    { "nFinishRecording",   "!(J)J",            (void*) android_view_DisplayListCanvas_finishRecording },
    { "nDrawRenderNode",    "!(JJ)V",           (void*) android_view_DisplayListCanvas_drawRenderNode },

    { "nCreateDisplayListCanvas", "!(II)J",     (void*) android_view_DisplayListCanvas_createDisplayListCanvas },
    { "nResetDisplayListCanvas", "!(JII)V",     (void*) android_view_DisplayListCanvas_resetDisplayListCanvas },

    { "nDrawLayer",               "!(JJ)V",     (void*) android_view_DisplayListCanvas_drawLayer },

    { "nGetMaximumTextureWidth",  "!()I",       (void*) android_view_DisplayListCanvas_getMaxTextureWidth },
    { "nGetMaximumTextureHeight", "!()I",       (void*) android_view_DisplayListCanvas_getMaxTextureHeight },
};

static JNINativeMethod gActivityThreadMethods[] = {
    { "dumpGraphicsInfo",        "(Ljava/io/FileDescriptor;)V",
                                               (void*) android_app_ActivityThread_dumpGraphics }
};

int register_android_view_DisplayListCanvas(JNIEnv* env) {
    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

int register_android_app_ActivityThread(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/app/ActivityThread",
            gActivityThreadMethods, NELEM(gActivityThreadMethods));
}

};
