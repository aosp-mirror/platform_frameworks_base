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

#include <androidfw/ResourceTypes.h>

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

static struct {
    jmethodID set;
} gRectClassInfo;

// ----------------------------------------------------------------------------
// Setup
// ----------------------------------------------------------------------------

static void android_view_DisplayListCanvas_setViewport(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jint width, jint height) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    renderer->setViewport(width, height);
}

static void android_view_DisplayListCanvas_setHighContrastText(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jboolean highContrastText) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    renderer->setHighContrastText(highContrastText);
}

static void android_view_DisplayListCanvas_insertReorderBarrier(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jboolean reorderEnable) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    renderer->insertReorderBarrier(reorderEnable);
}

static void android_view_DisplayListCanvas_prepare(JNIEnv* env, jobject clazz,
        jlong rendererPtr) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    renderer->prepare();
}

static void android_view_DisplayListCanvas_prepareDirty(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jint left, jint top, jint right, jint bottom) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    renderer->prepareDirty(left, top, right, bottom);
}

static void android_view_DisplayListCanvas_finish(JNIEnv* env, jobject clazz,
        jlong rendererPtr) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    renderer->finish();
}

// ----------------------------------------------------------------------------
// Functor
// ----------------------------------------------------------------------------

static void android_view_DisplayListCanvas_callDrawGLFunction(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong functorPtr) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    Functor* functor = reinterpret_cast<Functor*>(functorPtr);
    renderer->callDrawGLFunction(functor);
}

// ----------------------------------------------------------------------------
// Misc
// ----------------------------------------------------------------------------

static jint android_view_DisplayListCanvas_getMaxTextureWidth(JNIEnv* env, jobject clazz) {
    if (!Caches::hasInstance()) {
        android::uirenderer::renderthread::RenderProxy::staticFence();
    }
    return Caches::getInstance().maxTextureSize;
}

static jint android_view_DisplayListCanvas_getMaxTextureHeight(JNIEnv* env, jobject clazz) {
    if (!Caches::hasInstance()) {
        android::uirenderer::renderthread::RenderProxy::staticFence();
    }
    return Caches::getInstance().maxTextureSize;
}

// ----------------------------------------------------------------------------
// Drawing
// ----------------------------------------------------------------------------

static void android_view_DisplayListCanvas_drawPatch(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jobject jbitmap, jlong patchPtr,
        float left, float top, float right, float bottom, jlong paintPtr) {
    SkBitmap bitmap;
    GraphicsJNI::getSkBitmap(env, jbitmap, &bitmap);
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    Res_png_9patch* patch = reinterpret_cast<Res_png_9patch*>(patchPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawPatch(bitmap, patch, left, top, right, bottom, paint);
}

static void android_view_DisplayListCanvas_drawRoundRectProps(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong leftPropPtr, jlong topPropPtr, jlong rightPropPtr,
        jlong bottomPropPtr, jlong rxPropPtr, jlong ryPropPtr, jlong paintPropPtr) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    CanvasPropertyPrimitive* leftProp = reinterpret_cast<CanvasPropertyPrimitive*>(leftPropPtr);
    CanvasPropertyPrimitive* topProp = reinterpret_cast<CanvasPropertyPrimitive*>(topPropPtr);
    CanvasPropertyPrimitive* rightProp = reinterpret_cast<CanvasPropertyPrimitive*>(rightPropPtr);
    CanvasPropertyPrimitive* bottomProp = reinterpret_cast<CanvasPropertyPrimitive*>(bottomPropPtr);
    CanvasPropertyPrimitive* rxProp = reinterpret_cast<CanvasPropertyPrimitive*>(rxPropPtr);
    CanvasPropertyPrimitive* ryProp = reinterpret_cast<CanvasPropertyPrimitive*>(ryPropPtr);
    CanvasPropertyPaint* paintProp = reinterpret_cast<CanvasPropertyPaint*>(paintPropPtr);
    renderer->drawRoundRect(leftProp, topProp, rightProp, bottomProp, rxProp, ryProp, paintProp);
}

static void android_view_DisplayListCanvas_drawCircleProps(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong xPropPtr, jlong yPropPtr, jlong radiusPropPtr, jlong paintPropPtr) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    CanvasPropertyPrimitive* xProp = reinterpret_cast<CanvasPropertyPrimitive*>(xPropPtr);
    CanvasPropertyPrimitive* yProp = reinterpret_cast<CanvasPropertyPrimitive*>(yPropPtr);
    CanvasPropertyPrimitive* radiusProp = reinterpret_cast<CanvasPropertyPrimitive*>(radiusPropPtr);
    CanvasPropertyPaint* paintProp = reinterpret_cast<CanvasPropertyPaint*>(paintPropPtr);
    renderer->drawCircle(xProp, yProp, radiusProp, paintProp);
}

static void android_view_DisplayListCanvas_drawRegionAsRects(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong regionPtr, jlong paintPtr) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    SkRegion* region = reinterpret_cast<SkRegion*>(regionPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    if (paint->getStyle() != Paint::kFill_Style ||
            (paint->isAntiAlias() && !renderer->isCurrentTransformSimple())) {
        SkRegion::Iterator it(*region);
        while (!it.done()) {
            const SkIRect& r = it.rect();
            renderer->drawRect(r.fLeft, r.fTop, r.fRight, r.fBottom, *paint);
            it.next();
        }
    } else {
        int count = 0;
        Vector<float> rects;
        SkRegion::Iterator it(*region);
        while (!it.done()) {
            const SkIRect& r = it.rect();
            rects.push(r.fLeft);
            rects.push(r.fTop);
            rects.push(r.fRight);
            rects.push(r.fBottom);
            count += 4;
            it.next();
        }
        renderer->drawRects(rects.array(), count, paint);
    }
}

// ----------------------------------------------------------------------------
// Display lists
// ----------------------------------------------------------------------------

static jlong android_view_DisplayListCanvas_finishRecording(JNIEnv* env,
        jobject clazz, jlong rendererPtr) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    return reinterpret_cast<jlong>(renderer->finishRecording());
}

static jlong android_view_DisplayListCanvas_createDisplayListCanvas(JNIEnv* env, jobject clazz) {
    return reinterpret_cast<jlong>(new DisplayListCanvas);
}

static void android_view_DisplayListCanvas_drawRenderNode(JNIEnv* env,
        jobject clazz, jlong rendererPtr, jlong renderNodePtr) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderer->drawRenderNode(renderNode);
}

// ----------------------------------------------------------------------------
// Layers
// ----------------------------------------------------------------------------

static void android_view_DisplayListCanvas_drawLayer(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong layerPtr, jfloat x, jfloat y) {
    DisplayListCanvas* renderer = reinterpret_cast<DisplayListCanvas*>(rendererPtr);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerPtr);
    renderer->drawLayer(layer, x, y);
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
    { "nIsAvailable",       "()Z",             (void*) android_view_DisplayListCanvas_isAvailable },
    { "nSetViewport",       "(JII)V",          (void*) android_view_DisplayListCanvas_setViewport },
    { "nSetHighContrastText","(JZ)V",          (void*) android_view_DisplayListCanvas_setHighContrastText },
    { "nInsertReorderBarrier","(JZ)V",         (void*) android_view_DisplayListCanvas_insertReorderBarrier },
    { "nPrepare",           "(J)V",            (void*) android_view_DisplayListCanvas_prepare },
    { "nPrepareDirty",      "(JIIII)V",        (void*) android_view_DisplayListCanvas_prepareDirty },
    { "nFinish",            "(J)V",            (void*) android_view_DisplayListCanvas_finish },

    { "nCallDrawGLFunction", "(JJ)V",          (void*) android_view_DisplayListCanvas_callDrawGLFunction },

    { "nDrawPatch",         "(JLandroid/graphics/Bitmap;JFFFFJ)V",     (void*) android_view_DisplayListCanvas_drawPatch },

    { "nDrawRects",         "(JJJ)V",          (void*) android_view_DisplayListCanvas_drawRegionAsRects },
    { "nDrawRoundRect",     "(JJJJJJJJ)V",     (void*) android_view_DisplayListCanvas_drawRoundRectProps },
    { "nDrawCircle",        "(JJJJJ)V",        (void*) android_view_DisplayListCanvas_drawCircleProps },

    { "nFinishRecording",   "(J)J",            (void*) android_view_DisplayListCanvas_finishRecording },
    { "nDrawRenderNode",    "(JJ)V",           (void*) android_view_DisplayListCanvas_drawRenderNode },

    { "nCreateDisplayListCanvas", "()J",     (void*) android_view_DisplayListCanvas_createDisplayListCanvas },

    { "nDrawLayer",               "(JJFF)V",   (void*) android_view_DisplayListCanvas_drawLayer },

    { "nGetMaximumTextureWidth",  "()I",       (void*) android_view_DisplayListCanvas_getMaxTextureWidth },
    { "nGetMaximumTextureHeight", "()I",       (void*) android_view_DisplayListCanvas_getMaxTextureHeight },
};

static JNINativeMethod gActivityThreadMethods[] = {
    { "dumpGraphicsInfo",        "(Ljava/io/FileDescriptor;)V",
                                               (void*) android_app_ActivityThread_dumpGraphics }
};

int register_android_view_DisplayListCanvas(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, "android/graphics/Rect");
    gRectClassInfo.set = GetMethodIDOrDie(env, clazz, "set", "(IIII)V");

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

int register_android_app_ActivityThread(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/app/ActivityThread",
            gActivityThreadMethods, NELEM(gActivityThreadMethods));
}

};
