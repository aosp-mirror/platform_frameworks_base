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

#include <DisplayListRenderer.h>
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
// Defines
// ----------------------------------------------------------------------------

static const bool kDebugRenderer = false;

// ----------------------------------------------------------------------------
// Setup
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_setViewport(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jint width, jint height) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->setViewport(width, height);
}

static void android_view_GLES20Canvas_setHighContrastText(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jboolean highContrastText) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->setHighContrastText(highContrastText);
}

static void android_view_GLES20Canvas_insertReorderBarrier(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jboolean reorderEnable) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->insertReorderBarrier(reorderEnable);
}

static void android_view_GLES20Canvas_prepare(JNIEnv* env, jobject clazz,
        jlong rendererPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->prepare();
}

static void android_view_GLES20Canvas_prepareDirty(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jint left, jint top, jint right, jint bottom) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->prepareDirty(left, top, right, bottom);
}

static void android_view_GLES20Canvas_finish(JNIEnv* env, jobject clazz,
        jlong rendererPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->finish();
}

static void android_view_GLES20Canvas_setProperty(JNIEnv* env,
        jobject clazz, jstring name, jstring value) {
    if (!Caches::hasInstance()) {
        ALOGW("can't set property, no Caches instance");
        return;
    }

    if (name == NULL || value == NULL) {
        ALOGW("can't set prop, null passed");
    }

    const char* nameCharArray = env->GetStringUTFChars(name, NULL);
    const char* valueCharArray = env->GetStringUTFChars(value, NULL);
    Caches::getInstance().setTempProperty(nameCharArray, valueCharArray);
    env->ReleaseStringUTFChars(name, nameCharArray);
    env->ReleaseStringUTFChars(name, valueCharArray);
}

// ----------------------------------------------------------------------------
// Functor
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_callDrawGLFunction(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong functorPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Functor* functor = reinterpret_cast<Functor*>(functorPtr);
    android::uirenderer::Rect dirty;
    renderer->callDrawGLFunction(functor, dirty);
}

// ----------------------------------------------------------------------------
// Misc
// ----------------------------------------------------------------------------

static jint android_view_GLES20Canvas_getMaxTextureWidth(JNIEnv* env, jobject clazz) {
    return Caches::getInstance().maxTextureSize;
}

static jint android_view_GLES20Canvas_getMaxTextureHeight(JNIEnv* env, jobject clazz) {
    return Caches::getInstance().maxTextureSize;
}

// ----------------------------------------------------------------------------
// Drawing
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_drawPatch(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong bitmapPtr, jlong patchPtr,
        float left, float top, float right, float bottom, jlong paintPtr) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapPtr);

    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Res_png_9patch* patch = reinterpret_cast<Res_png_9patch*>(patchPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawPatch(bitmap, patch, left, top, right, bottom, paint);
}

static void android_view_GLES20Canvas_drawRoundRectProps(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong leftPropPtr, jlong topPropPtr, jlong rightPropPtr,
        jlong bottomPropPtr, jlong rxPropPtr, jlong ryPropPtr, jlong paintPropPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    CanvasPropertyPrimitive* leftProp = reinterpret_cast<CanvasPropertyPrimitive*>(leftPropPtr);
    CanvasPropertyPrimitive* topProp = reinterpret_cast<CanvasPropertyPrimitive*>(topPropPtr);
    CanvasPropertyPrimitive* rightProp = reinterpret_cast<CanvasPropertyPrimitive*>(rightPropPtr);
    CanvasPropertyPrimitive* bottomProp = reinterpret_cast<CanvasPropertyPrimitive*>(bottomPropPtr);
    CanvasPropertyPrimitive* rxProp = reinterpret_cast<CanvasPropertyPrimitive*>(rxPropPtr);
    CanvasPropertyPrimitive* ryProp = reinterpret_cast<CanvasPropertyPrimitive*>(ryPropPtr);
    CanvasPropertyPaint* paintProp = reinterpret_cast<CanvasPropertyPaint*>(paintPropPtr);
    renderer->drawRoundRect(leftProp, topProp, rightProp, bottomProp, rxProp, ryProp, paintProp);
}

static void android_view_GLES20Canvas_drawCircleProps(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong xPropPtr, jlong yPropPtr, jlong radiusPropPtr, jlong paintPropPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    CanvasPropertyPrimitive* xProp = reinterpret_cast<CanvasPropertyPrimitive*>(xPropPtr);
    CanvasPropertyPrimitive* yProp = reinterpret_cast<CanvasPropertyPrimitive*>(yPropPtr);
    CanvasPropertyPrimitive* radiusProp = reinterpret_cast<CanvasPropertyPrimitive*>(radiusPropPtr);
    CanvasPropertyPaint* paintProp = reinterpret_cast<CanvasPropertyPaint*>(paintPropPtr);
    renderer->drawCircle(xProp, yProp, radiusProp, paintProp);
}

static void android_view_GLES20Canvas_drawRegionAsRects(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong regionPtr, jlong paintPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
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

static jlong android_view_GLES20Canvas_finishRecording(JNIEnv* env,
        jobject clazz, jlong rendererPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    return reinterpret_cast<jlong>(renderer->finishRecording());
}

static jlong android_view_GLES20Canvas_createDisplayListRenderer(JNIEnv* env, jobject clazz) {
    return reinterpret_cast<jlong>(new DisplayListRenderer);
}

static void android_view_GLES20Canvas_drawRenderNode(JNIEnv* env,
        jobject clazz, jlong rendererPtr, jlong renderNodePtr,
        jint flags) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    android::uirenderer::Rect bounds;
    renderer->drawRenderNode(renderNode, bounds, flags);
}

// ----------------------------------------------------------------------------
// Layers
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_drawLayer(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong layerPtr, jfloat x, jfloat y) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerPtr);
    renderer->drawLayer(layer, x, y);
}

// ----------------------------------------------------------------------------
// Common
// ----------------------------------------------------------------------------

static jboolean android_view_GLES20Canvas_isAvailable(JNIEnv* env, jobject clazz) {
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

const char* const kClassPathName = "android/view/GLES20Canvas";

static JNINativeMethod gMethods[] = {
    { "nIsAvailable",       "()Z",             (void*) android_view_GLES20Canvas_isAvailable },
    { "nSetViewport",       "(JII)V",          (void*) android_view_GLES20Canvas_setViewport },
    { "nSetHighContrastText","(JZ)V",          (void*) android_view_GLES20Canvas_setHighContrastText },
    { "nInsertReorderBarrier","(JZ)V",         (void*) android_view_GLES20Canvas_insertReorderBarrier },
    { "nPrepare",           "(J)V",            (void*) android_view_GLES20Canvas_prepare },
    { "nPrepareDirty",      "(JIIII)V",        (void*) android_view_GLES20Canvas_prepareDirty },
    { "nFinish",            "(J)V",            (void*) android_view_GLES20Canvas_finish },
    { "nSetProperty",       "(Ljava/lang/String;Ljava/lang/String;)V",
            (void*) android_view_GLES20Canvas_setProperty },

    { "nCallDrawGLFunction", "(JJ)V",          (void*) android_view_GLES20Canvas_callDrawGLFunction },

    { "nDrawPatch",         "(JJJFFFFJ)V",     (void*) android_view_GLES20Canvas_drawPatch },

    { "nDrawRects",         "(JJJ)V",          (void*) android_view_GLES20Canvas_drawRegionAsRects },
    { "nDrawRoundRect",     "(JJJJJJJJ)V",     (void*) android_view_GLES20Canvas_drawRoundRectProps },
    { "nDrawCircle",        "(JJJJJ)V",        (void*) android_view_GLES20Canvas_drawCircleProps },

    { "nFinishRecording",   "(J)J",            (void*) android_view_GLES20Canvas_finishRecording },
    { "nDrawRenderNode",    "(JJI)V",          (void*) android_view_GLES20Canvas_drawRenderNode },

    { "nCreateDisplayListRenderer", "()J",     (void*) android_view_GLES20Canvas_createDisplayListRenderer },

    { "nDrawLayer",               "(JJFF)V",   (void*) android_view_GLES20Canvas_drawLayer },

    { "nGetMaximumTextureWidth",  "()I",       (void*) android_view_GLES20Canvas_getMaxTextureWidth },
    { "nGetMaximumTextureHeight", "()I",       (void*) android_view_GLES20Canvas_getMaxTextureHeight },
};

static JNINativeMethod gActivityThreadMethods[] = {
    { "dumpGraphicsInfo",        "(Ljava/io/FileDescriptor;)V",
                                               (void*) android_app_ActivityThread_dumpGraphics }
};

int register_android_view_GLES20Canvas(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, "android/graphics/Rect");
    gRectClassInfo.set = GetMethodIDOrDie(env, clazz, "set", "(IIII)V");

    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

int register_android_app_ActivityThread(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/app/ActivityThread",
            gActivityThreadMethods, NELEM(gActivityThreadMethods));
}

};
