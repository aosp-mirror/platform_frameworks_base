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

#include "base/logging.h"

#include "core/gl_env.h"
#include "core/vertex_frame.h"

#include <GLES2/gl2ext.h>
#include <EGL/egl.h>

namespace android {
namespace filterfw {

VertexFrame::VertexFrame(int size)
  : vbo_(0),
    size_(size) {
}

VertexFrame::~VertexFrame() {
  glDeleteBuffers(1, &vbo_);
}

bool VertexFrame::CreateBuffer() {
  glGenBuffers(1, &vbo_);
  return !GLEnv::CheckGLError("Generating VBO");
}

bool VertexFrame::WriteData(const uint8_t* data, int size) {
  // Create buffer if not created already
  const bool first_upload = !HasVBO();
  if (first_upload && !CreateBuffer()) {
    ALOGE("VertexFrame: Could not create vertex buffer!");
    return false;
  }

  // Upload the data
  glBindBuffer(GL_ARRAY_BUFFER, vbo_);
  if (GLEnv::CheckGLError("VBO Bind Buffer"))
    return false;

  if (first_upload && size == size_)
    glBufferData(GL_ARRAY_BUFFER, size, data, GL_STATIC_DRAW);
  else if (!first_upload && size <= size_)
    glBufferSubData(GL_ARRAY_BUFFER, 0, size, data);
  else {
    ALOGE("VertexFrame: Attempting to upload more data (%d bytes) than fits "
         "inside the vertex frame (%d bytes)!", size, size_);
    return false;
  }

  // Make sure it worked
  if (GLEnv::CheckGLError("VBO Data Upload"))
    return false;

  // Subsequent uploads are now bound to the size given here
  size_ = size;

  return true;
}

int VertexFrame::Size() const {
  return size_;
}

} // namespace filterfw
} // namespace android
