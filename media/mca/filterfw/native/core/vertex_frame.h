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

#ifndef ANDROID_FILTERFW_CORE_VERTEXFRAME_H
#define ANDROID_FILTERFW_CORE_VERTEXFRAME_H

#include <GLES2/gl2.h>

namespace android {
namespace filterfw {

// A VertexFrame stores vertex attribute data in a VBO. Unlike other frames,
// you often create instances of VertexFrame yourself, to pass vertex data to
// a ShaderProgram. Note, that any kind of reading from VertexFrames is NOT
// supported. Once data is uploaded to a VertexFrame, it cannot be read from
// again.
class VertexFrame {
  public:
    // Create a VertexFrame of the specified size (in bytes).
    explicit VertexFrame(int size);

    ~VertexFrame();

    // Upload the given data to the vertex buffer. The size must match the size
    // passed in the constructor for the first upload. Subsequent uploads must
    // be able to fit within the allocated space (i.e. size must not exceed the
    // frame's size).
    bool WriteData(const uint8_t* data, int size);

    // The size of the vertex buffer in bytes.
    int Size() const;

    // Return the id of the internal VBO. Returns 0 if no VBO has been
    // generated yet. The internal VBO is generated the first time data is
    // uploaded.
    GLuint GetVboId() const {
      return vbo_;
    }

    // Returns true if the frame contains an allocated VBO.
    bool HasBuffer() const {
      return vbo_ != 0;
    }

  private:
    // Create the VBO
    bool CreateBuffer();

    // Returns true if the VBO has been created.
    bool HasVBO() const {
      return vbo_ != 0;
    }

    // The internal VBO handle
    GLuint vbo_;

    // The size of this frame in bytes
    int size_;
};

} // namespace filterfw
} // namespace android

#endif  // ANDROID_FILTERFW_CORE_VERTEXFRAME_H
