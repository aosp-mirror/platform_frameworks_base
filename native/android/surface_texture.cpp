/*
 * Copyright 2020 The Android Open Source Project
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
#include <surfacetexture/surface_texture_platform.h>

using namespace android;

ANativeWindow* ASurfaceTexture_acquireANativeWindow(ASurfaceTexture* st) {
    return ASurfaceTexture_routeAcquireANativeWindow(st);
}

int ASurfaceTexture_attachToGLContext(ASurfaceTexture* st, uint32_t texName) {
    return ASurfaceTexture_routeAttachToGLContext(st, texName);
}

int ASurfaceTexture_detachFromGLContext(ASurfaceTexture* st) {
    return ASurfaceTexture_routeDetachFromGLContext(st);
}

void ASurfaceTexture_release(ASurfaceTexture* st) {
    return ASurfaceTexture_routeRelease(st);
}

int ASurfaceTexture_updateTexImage(ASurfaceTexture* st) {
    return ASurfaceTexture_routeUpdateTexImage(st);
}

void ASurfaceTexture_getTransformMatrix(ASurfaceTexture* st, float mtx[16]) {
    return ASurfaceTexture_routeGetTransformMatrix(st, mtx);
}

int64_t ASurfaceTexture_getTimestamp(ASurfaceTexture* st) {
    return ASurfaceTexture_routeGetTimestamp(st);
}

ASurfaceTexture* ASurfaceTexture_fromSurfaceTexture(JNIEnv* env, jobject surfacetexture) {
    return ASurfaceTexture_routeFromSurfaceTexture(env, surfacetexture);
}
