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
#include "core/gl_frame.h"
#include "core/shader_program.h"

#include <vector>

namespace android {
namespace filterfw {

static const int kIdentityShaderKey = 1;

//
// A GLFrame stores pixel data on the GPU. It uses two kinds of GL data
// containers for this: Textures and Frame Buffer Objects (FBOs). Textures are
// used whenever pixel data needs to be read into a shader or the host program,
// and when pixel data is uploaded to a GLFrame. The FBO is used as a rendering
// target for shaders.
//

GLFrame::GLFrame(GLEnv* gl_env)
  : gl_env_(gl_env),
    width_(0),
    height_(0),
    vp_x_(0),
    vp_y_(0),
    vp_width_(0),
    vp_height_(0),
    texture_id_(0),
    fbo_id_(0),
    texture_target_(GL_TEXTURE_2D),
    texture_state_(kStateUninitialized),
    fbo_state_(kStateUninitialized),
    owns_texture_(false),
    owns_fbo_(false) {
  SetDefaultTexParameters();
}

bool GLFrame::Init(int width, int height) {
  // Make sure we haven't been initialized already
  if (width_ == 0 && height_ == 0) {
    InitDimensions(width, height);
    return true;
  }
  return false;
}

bool GLFrame::InitWithTexture(GLint texture_id, int width, int height) {
  texture_id_ = texture_id;
  texture_state_ = glIsTexture(texture_id) ? kStateComplete : kStateGenerated;
  InitDimensions(width, height);
  return true;
}

bool GLFrame::InitWithFbo(GLint fbo_id, int width, int height) {
  fbo_id_ = fbo_id;
  fbo_state_ = glIsFramebuffer(fbo_id) ? kStateComplete : kStateGenerated;
  texture_state_ = kStateUnmanaged;
  InitDimensions(width, height);
  return true;
}

bool GLFrame::InitWithExternalTexture() {
  texture_target_ = GL_TEXTURE_EXTERNAL_OES;
  width_ = 0;
  height_ = 0;
  return GenerateTextureName();
}

void GLFrame::InitDimensions(int width, int height) {
  width_ = width;
  height_ = height;
  vp_width_ = width;
  vp_height_ = height;
}

GLFrame::~GLFrame() {
  // Delete texture
  if (owns_texture_) {
    // Bind FBO so that texture is unbound from it during deletion
    if (fbo_state_ == kStateComplete) {
      glBindFramebuffer(GL_FRAMEBUFFER, fbo_id_);
    }
    glDeleteTextures(1, &texture_id_);
  }

  // Delete FBO
  if (owns_fbo_) {
    glDeleteFramebuffers(1, &fbo_id_);
  }
}

bool GLFrame::GenerateMipMap() {
  if (FocusTexture()) {
    glGenerateMipmap(GL_TEXTURE_2D);
    return !GLEnv::CheckGLError("Generating MipMap!");
  }
  return false;
}

bool GLFrame::SetTextureParameter(GLenum pname, GLint value) {
  if (value != tex_params_[pname]) {
    if (FocusTexture()) {
      glTexParameteri(GL_TEXTURE_2D, pname, value);
      if (!GLEnv::CheckGLError("Setting texture parameter!")) {
        tex_params_[pname] = value;
        return true;
      }
    }
  } else {
    return true;
  }
  return false;
}

bool GLFrame::UpdateTexParameters() {
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, tex_params_[GL_TEXTURE_MAG_FILTER]);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, tex_params_[GL_TEXTURE_MIN_FILTER]);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, tex_params_[GL_TEXTURE_WRAP_S]);
  glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, tex_params_[GL_TEXTURE_WRAP_T]);
  return !GLEnv::CheckGLError("Resetting texture parameters!");
}

bool GLFrame::TexParametersModifed() {
  return tex_params_[GL_TEXTURE_MAG_FILTER] != GL_LINEAR
    ||   tex_params_[GL_TEXTURE_MIN_FILTER] != GL_LINEAR
    ||   tex_params_[GL_TEXTURE_WRAP_S] != GL_CLAMP_TO_EDGE
    ||   tex_params_[GL_TEXTURE_WRAP_T] != GL_CLAMP_TO_EDGE;
}

void GLFrame::SetDefaultTexParameters() {
  tex_params_[GL_TEXTURE_MAG_FILTER] = GL_LINEAR;
  tex_params_[GL_TEXTURE_MIN_FILTER] = GL_LINEAR;
  tex_params_[GL_TEXTURE_WRAP_S] = GL_CLAMP_TO_EDGE;
  tex_params_[GL_TEXTURE_WRAP_T] = GL_CLAMP_TO_EDGE;
}

bool GLFrame::ResetTexParameters() {
  if (TexParametersModifed()) {
    if (BindTexture()) {
      SetDefaultTexParameters();
      return UpdateTexParameters();
    }
    return false;
  }
  return true;
}

bool GLFrame::CopyDataTo(uint8_t* buffer, int size) {
  return (size >= Size())
    ? CopyPixelsTo(buffer)
    : false;
}

bool GLFrame::CopyPixelsTo(uint8_t* buffer) {
  // Use one of the pixel reading methods below, ordered from most
  // efficient to least efficient.
  if (fbo_state_ == kStateComplete)
    return ReadFboPixels(buffer);
  else if (texture_state_ == kStateComplete)
    return ReadTexturePixels(buffer);
  else
    return false;
}

bool GLFrame::WriteData(const uint8_t* data, int data_size) {
  return (data_size == Size()) ? UploadTexturePixels(data) : false;
}

bool GLFrame::SetViewport(int x, int y, int width, int height) {
  vp_x_ = x;
  vp_y_ = y;
  vp_width_ = width;
  vp_height_ = height;
  return true;
}

GLFrame* GLFrame::Clone() const {
  GLFrame* target = new GLFrame(gl_env_);
  target->Init(width_, height_);
  target->CopyPixelsFrom(this);
  return target;
}

bool GLFrame::CopyPixelsFrom(const GLFrame* frame) {
  if (frame == this) {
    return true;
  } else if (frame && frame->width_ == width_ && frame->height_ == height_) {
    std::vector<const GLFrame*> sources;
    sources.push_back(frame);
    GetIdentity()->Process(sources, this);
    return true;
  }
  return false;
}

int GLFrame::Size() const {
  return width_ * height_ * 4;
}

ShaderProgram* GLFrame::GetIdentity() const {
  ShaderProgram* stored_shader = gl_env_->ShaderWithKey(kIdentityShaderKey);
  if (!stored_shader) {
    stored_shader = ShaderProgram::CreateIdentity(gl_env_);
    gl_env_->AttachShader(kIdentityShaderKey, stored_shader);
  }
  return stored_shader;
}

bool GLFrame::BindFrameBuffer() const {
  // Bind the FBO
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_id_);
  if (GLEnv::CheckGLError("FBO Binding")) return false;

  // Set viewport
  glViewport(vp_x_, vp_y_, vp_width_, vp_height_);
  if (GLEnv::CheckGLError("ViewPort Setup")) return false;

  return true;
}

bool GLFrame::FocusFrameBuffer() {
  // Create texture backing if necessary
  if (texture_state_ == kStateUninitialized) {
    if (!GenerateTextureName())
      return false;
  }

  // Create and bind FBO to texture if necessary
  if (fbo_state_ != kStateComplete) {
    if (!GenerateFboName() || !AttachTextureToFbo())
      return false;
  }

  // And bind it.
  return BindFrameBuffer();
}

bool GLFrame::BindTexture() const {
  glBindTexture(GL_TEXTURE_2D, texture_id_);
  return !GLEnv::CheckGLError("Texture Binding");
}

GLuint GLFrame::GetTextureId() const {
  return texture_id_;
}

// Returns the held FBO id. Only call this if the GLFrame holds an FBO. You
// can check this by calling HoldsFbo().
GLuint GLFrame::GetFboId() const {
  return fbo_id_;
}

bool GLFrame::FocusTexture() {
  // Make sure we have a texture
  if (!GenerateTextureName())
    return false;

  // Bind the texture
  if (!BindTexture())
    return false;

  return !GLEnv::CheckGLError("Texture Binding");
}

bool GLFrame::GenerateTextureName() {
  if (texture_state_ == kStateUninitialized) {
    // Make sure texture not in use already
    if (glIsTexture(texture_id_)) {
      ALOGE("GLFrame: Cannot generate texture id %d, as it is in use already!", texture_id_);
      return false;
    }

    // Generate the texture
    glGenTextures (1, &texture_id_);
    if (GLEnv::CheckGLError("Texture Generation"))
      return false;
    texture_state_ = kStateGenerated;
    owns_texture_ = true;
  }

  return true;
}

bool GLFrame::AllocateTexture() {
  // Allocate or re-allocate (if texture was deleted externally).
  if (texture_state_ == kStateGenerated || TextureWasDeleted()) {
    LOG_FRAME("GLFrame: Allocating texture: %d", texture_id_);
    glBindTexture(GL_TEXTURE_2D, texture_id_);
    glTexImage2D(GL_TEXTURE_2D,
               0,
               GL_RGBA,
               width_,
               height_,
               0,
               GL_RGBA,
               GL_UNSIGNED_BYTE,
               NULL);
    if (!GLEnv::CheckGLError("Texture Allocation")) {
      UpdateTexParameters();
      texture_state_ = kStateComplete;
    }
  }
  return texture_state_ == kStateComplete;
}

bool GLFrame::TextureWasDeleted() const {
  return texture_state_ == kStateComplete && !glIsTexture(texture_id_);
}

bool GLFrame::GenerateFboName() {
  if (fbo_state_ == kStateUninitialized) {
    // Make sure FBO not in use already
    if (glIsFramebuffer(fbo_id_)) {
      ALOGE("GLFrame: Cannot generate FBO id %d, as it is in use already!", fbo_id_);
      return false;
    }

    // Create FBO
    glGenFramebuffers(1, &fbo_id_);
    if (GLEnv::CheckGLError("FBO Generation"))
      return false;
    fbo_state_ = kStateGenerated;
    owns_fbo_ = true;
  }

  return true;
}

bool GLFrame::ReadFboPixels(uint8_t* pixels) const {
  if (fbo_state_ == kStateComplete) {
    BindFrameBuffer();
    glReadPixels(0,
                 0,
                 width_,
                 height_,
                 GL_RGBA,
                 GL_UNSIGNED_BYTE,
                 pixels);
    return !GLEnv::CheckGLError("FBO Pixel Readout");
  }
  return false;
}

bool GLFrame::ReadTexturePixels(uint8_t* pixels) const {
  // Read pixels from texture if we do not have an FBO
  // NOTE: OpenGL ES does NOT support glGetTexImage() for reading out texture
  // data. The only way for us to get texture data is to create a new FBO and
  // render the current texture frame into it. As this is quite inefficient,
  // and unnecessary (this can only happen if the user is reading out data
  // that was just set, and not run through a filter), we warn the user about
  // this here.
  ALOGW("Warning: Reading pixel data from unfiltered GL frame. This is highly "
        "inefficient. Please consider using your original pixel buffer "
        "instead!");

  // Create source frame set (unfortunately this requires an ugly const-cast,
  // as we need to wrap ourselves in a frame-set. Still, as this set is used
  // as input only, we are certain we will not be modified).
  std::vector<const GLFrame*> sources;
  sources.push_back(this);

  // Create target frame
  GLFrame target(gl_env_);
  target.Init(width_, height_);

  // Render the texture to the target
  GetIdentity()->Process(sources, &target);

  // Get the pixel data
  return target.ReadFboPixels(pixels);
}

bool GLFrame::AttachTextureToFbo() {
  // Check FBO and texture state. We do not do anything if we are not managing the texture.
  if (fbo_state_ == kStateComplete || texture_state_ == kStateUnmanaged) {
    return true;
  } else if (fbo_state_ != kStateGenerated) {
    ALOGE("Attempting to attach texture to FBO with no FBO in place!");
    return false;
  }

  // If texture has been generated, make sure it is allocated.
  if (!AllocateTexture())
    return false;

  // Bind the frame buffer, and check if we it is already bound
  glBindFramebuffer(GL_FRAMEBUFFER, fbo_id_);

  // Bind the texture to the frame buffer
  LOG_FRAME("Attaching tex %d w %d h %d to fbo %d", texture_id_, width_, height_, fbo_id_);
  glFramebufferTexture2D(GL_FRAMEBUFFER,
                         GL_COLOR_ATTACHMENT0,
                         GL_TEXTURE_2D,
                         texture_id_,
                         0);

  // Cleanup
  glBindTexture(GL_TEXTURE_2D, 0);
  glBindFramebuffer(GL_FRAMEBUFFER, 0);

  if (GLEnv::CheckGLError("Texture Binding to FBO"))
    return false;
  else
    fbo_state_ = kStateComplete;

  return true;
}

bool GLFrame::ReattachTextureToFbo() {
  return (fbo_state_ == kStateGenerated) ? AttachTextureToFbo() : true;
}

bool GLFrame::DetachTextureFromFbo() {
  if (fbo_state_ == kStateComplete && texture_state_ == kStateComplete) {
    LOG_FRAME("Detaching tex %d w %d h %d from fbo %d", texture_id_, width_, height_, fbo_id_);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo_id_);
    glFramebufferTexture2D(GL_FRAMEBUFFER,
                           GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D,
                           0,
                           0);
    if (GLEnv::CheckGLError("Detaching texture to FBO"))
      return false;
    else
      fbo_state_ = kStateGenerated;
  }
  return true;
}

bool GLFrame::UploadTexturePixels(const uint8_t* pixels) {
  // Bind the texture object
  FocusTexture();

  // Load mipmap level 0
  glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width_, height_,
               0, GL_RGBA, GL_UNSIGNED_BYTE, pixels);

  // Set the user specified texture parameters
  UpdateTexParameters();

  if (GLEnv::CheckGLError("Texture Pixel Upload"))
    return false;

  texture_state_ = kStateComplete;
  return true;
}

} // namespace filterfw
} // namespace android
