/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include <android/surface_texture_jni.h>
#include "graphics_jni_helpers.h"

#include <hwui/Paint.h>
#include <SkMatrix.h>
#include <DeferredLayerUpdater.h>

namespace android {

using namespace uirenderer;

static jboolean TextureLayer_prepare(JNIEnv* env, jobject clazz,
        jlong layerUpdaterPtr, jint width, jint height, jboolean isOpaque) {
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerUpdaterPtr);
    bool changed = false;
    changed |= layer->setSize(width, height);
    changed |= layer->setBlend(!isOpaque);
    return changed;
}

static void TextureLayer_setLayerPaint(JNIEnv* env, jobject clazz,
        jlong layerUpdaterPtr, jlong paintPtr) {
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerUpdaterPtr);
    if (layer) {
        Paint* paint = reinterpret_cast<Paint*>(paintPtr);
        layer->setPaint(paint);
    }
}

static void TextureLayer_setTransform(JNIEnv* env, jobject clazz,
        jlong layerUpdaterPtr, jlong matrixPtr) {
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerUpdaterPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    layer->setTransform(matrix);
}

static void TextureLayer_setSurfaceTexture(JNIEnv* env, jobject clazz,
        jlong layerUpdaterPtr, jobject surface) {
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerUpdaterPtr);
    ASurfaceTexture* surfaceTexture = ASurfaceTexture_fromSurfaceTexture(env, surface);
    layer->setSurfaceTexture(AutoTextureRelease(surfaceTexture, &ASurfaceTexture_release));
}

static void TextureLayer_updateSurfaceTexture(JNIEnv* env, jobject clazz,
        jlong layerUpdaterPtr) {
    DeferredLayerUpdater* layer = reinterpret_cast<DeferredLayerUpdater*>(layerUpdaterPtr);
    layer->updateTexImage();
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/TextureLayer";

static const JNINativeMethod gMethods[] = {
    { "nPrepare",                "(JIIZ)Z",    (void*) TextureLayer_prepare },
    { "nSetLayerPaint",          "(JJ)V",      (void*) TextureLayer_setLayerPaint },
    { "nSetTransform",           "(JJ)V",      (void*) TextureLayer_setTransform },
    { "nSetSurfaceTexture",      "(JLandroid/graphics/SurfaceTexture;)V",
            (void*) TextureLayer_setSurfaceTexture },
    { "nUpdateSurfaceTexture",   "(J)V",       (void*) TextureLayer_updateSurfaceTexture },
};

int register_android_view_TextureLayer(JNIEnv* env) {
    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
