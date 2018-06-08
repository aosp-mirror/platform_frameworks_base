/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <android/surface_texture.h>
#include <android/surface_texture_jni.h>

#define LOG_TAG "ASurfaceTexture"

#include <utils/Log.h>

#include <gui/GLConsumer.h>
#include <gui/Surface.h>

#include <android_runtime/android_graphics_SurfaceTexture.h>

using namespace android;

struct ASurfaceTexture {
    sp<GLConsumer> consumer;
    sp<IGraphicBufferProducer> producer;
};

ASurfaceTexture* ASurfaceTexture_fromSurfaceTexture(JNIEnv* env, jobject surfacetexture) {
    if (!surfacetexture || !android_SurfaceTexture_isInstanceOf(env, surfacetexture)) {
        return nullptr;
    }
    ASurfaceTexture* ast = new ASurfaceTexture;
    ast->consumer = SurfaceTexture_getSurfaceTexture(env, surfacetexture);
    ast->producer = SurfaceTexture_getProducer(env, surfacetexture);
    return ast;
}

ANativeWindow* ASurfaceTexture_acquireANativeWindow(ASurfaceTexture* st) {
    sp<Surface> surface = new Surface(st->producer);
    ANativeWindow* win(surface.get());
    ANativeWindow_acquire(win);
    return win;
}

void ASurfaceTexture_release(ASurfaceTexture* st) {
    delete st;
}

int ASurfaceTexture_attachToGLContext(ASurfaceTexture* st, uint32_t tex) {
    return st->consumer->attachToContext(tex);
}

int ASurfaceTexture_detachFromGLContext(ASurfaceTexture* st) {
    return st->consumer->detachFromContext();
}

int ASurfaceTexture_updateTexImage(ASurfaceTexture* st) {
    return st->consumer->updateTexImage();
}

void ASurfaceTexture_getTransformMatrix(ASurfaceTexture* st, float mtx[16]) {
    st->consumer->getTransformMatrix(mtx);
}

int64_t ASurfaceTexture_getTimestamp(ASurfaceTexture* st) {
    return st->consumer->getTimestamp();
}
