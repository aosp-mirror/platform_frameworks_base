/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_FILTERFW_CORE_GL_BUFFER_INTERFACE_H
#define ANDROID_FILTERFW_CORE_GL_BUFFER_INTERFACE_H

#include <GLES2/gl2.h>

namespace android {
namespace filterfw {

class GLTextureHandle {
  public:
    virtual ~GLTextureHandle() { }

    // Returns the held texture id.
    virtual GLuint GetTextureId() const = 0;

    // Binds the held texture. This may result in creating the texture if it
    // is not yet available.
    virtual bool FocusTexture() = 0;

    // Generates the mipmap chain of the held texture. Returns true, iff
    // generating was successful.
    virtual bool GenerateMipMap() = 0;

    // Set a texture parameter (see glTextureParameter documentation). Returns
    // true iff the parameter was set successfully.
    virtual bool SetTextureParameter(GLenum pname, GLint value) = 0;

    // Returns the texture target used.
    // Texture Target should be: GL_TEXTURE_2D, GL_TEXTURE_EXTERNAL_OES.
    virtual GLuint GetTextureTarget() const = 0;
};

class GLFrameBufferHandle {
  public:
    virtual ~GLFrameBufferHandle() { }

    // Returns the held FBO id.
    virtual GLuint GetFboId() const = 0;

    // Binds the held FBO. This may result in creating the FBO if it
    // is not yet available.
    virtual bool FocusFrameBuffer() = 0;
};

// Interface to instances that hold GL textures and frame-buffer-objects.
// The GLFrame class implements this interface.
class GLBufferHandle : public GLTextureHandle, public GLFrameBufferHandle {
  public:
    virtual ~GLBufferHandle() { }
};

} // namespace filterfw
} // namespace android

#endif  // ANDROID_FILTERFW_CORE_GL_BUFFER_INTERFACE_H
