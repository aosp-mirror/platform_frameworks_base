/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_IMAGE_H
#define ANDROID_HWUI_IMAGE_H

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>

#include <ui/GraphicBuffer.h>

namespace android {
namespace uirenderer {

/**
 * A simple wrapper that creates an EGLImage and a texture for a GraphicBuffer.
 */
class Image {
public:
    /**
     * Creates a new image from the specified graphic buffer. If the image
     * cannot be created, getTexture() will return 0 and getImage() will
     * return EGL_NO_IMAGE_KHR.
     */
    explicit Image(sp<GraphicBuffer> buffer);
    ~Image();

    /**
     * Returns the name of the GL texture that can be used to sample
     * from this image.
     */
    GLuint getTexture() const { return mTexture; }

    /**
     * Returns the name of the EGL image represented by this object.
     */
    EGLImageKHR getImage() const { return mImage; }

private:
    GLuint mTexture;
    EGLImageKHR mImage;
};  // class Image

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_IMAGE_H
