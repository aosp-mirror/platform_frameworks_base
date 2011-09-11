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

#ifndef ANDROID_GUI_SURFACETEXTURE_H
#define ANDROID_GUI_SURFACETEXTURE_H

#include <ui/GraphicBuffer.h>

namespace android {

struct SurfaceTexture {
    struct FrameAvailableListener : public virtual RefBase {};

    SurfaceTexture(GLuint, bool allowSynchronousMode = true) {}
    void updateTexImage() {}
    void decStrong(android::sp<android::SurfaceTexture>* const) {}
    void incStrong(android::sp<android::SurfaceTexture>* const) {}
    void getTransformMatrix(float mtx[16]) {}
    void setFrameAvailableListener(const sp<FrameAvailableListener>&) {}
    void setSynchronousMode(bool) {}
    GLenum getCurrentTextureTarget() const { return 0; }
    void setBufferCount(int bufferCount) {}
    sp<GraphicBuffer> getCurrentBuffer() const { return NULL; }
    int64_t getTimestamp() { return 0; }
};

static sp<SurfaceTexture> SurfaceTexture_getSurfaceTexture(JNIEnv* env, jobject thiz)
{
    sp<SurfaceTexture> s;
    return s;
}

}

#endif
