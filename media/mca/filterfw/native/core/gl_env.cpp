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
// #define LOG_NDEBUG 0

#include "base/logging.h"
#include "base/utilities.h"
#include "core/gl_env.h"
#include "core/shader_program.h"
#include "core/vertex_frame.h"
#include "system/window.h"

#include <map>
#include <string>
#include <EGL/eglext.h>

#include <gui/BufferQueue.h>
#include <gui/Surface.h>
#include <gui/GLConsumer.h>
#include <gui/IGraphicBufferProducer.h>

namespace android {
namespace filterfw {

GLEnv::GLEnv()
  : display_(EGL_NO_DISPLAY),
    context_id_(0),
    surface_id_(0),
    max_surface_id_(0),
    created_context_(false),
    created_surface_(false),
    initialized_(false) {
}

GLEnv::~GLEnv() {
  // Destroy surfaces
  for (std::map<int, SurfaceWindowPair>::iterator it = surfaces_.begin();
       it != surfaces_.end();
       ++it) {
    if (it->first != 0 || created_surface_) {
      eglDestroySurface(display(), it->second.first);
      if (it->second.second) {
        it->second.second->Destroy();
        delete it->second.second;
      }
    }
  }

  // Destroy contexts
  for (std::map<int, EGLContext>::iterator it = contexts_.begin();
       it != contexts_.end();
       ++it) {
    if (it->first != 0 || created_context_)
      eglDestroyContext(display(), it->second);
  }

  // Destroy attached shaders and frames
  STLDeleteValues(&attached_shaders_);
  STLDeleteValues(&attached_vframes_);

  // Destroy display
  if (initialized_)
    eglTerminate(display());

  // Log error if this did not work
  if (CheckEGLError("TearDown!"))
    ALOGE("GLEnv: Error tearing down GL Environment!");
}

bool GLEnv::IsInitialized() const {
  return (contexts_.size() > 0 &&
          surfaces_.size() > 0 &&
          display_ != EGL_NO_DISPLAY);
}

bool GLEnv::Deactivate() {
  eglMakeCurrent(display(), EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
  return !CheckEGLError("eglMakeCurrent");
}

bool GLEnv::Activate() {
  ALOGV("Activate()");
  if (display()   != eglGetCurrentDisplay() ||
      context()   != eglGetCurrentContext() ||
      surface()   != eglGetCurrentSurface(EGL_DRAW)) {
    // Make sure we are initialized
    if (context() == EGL_NO_CONTEXT || surface() == EGL_NO_SURFACE)
      return false;

    // Make our context current
    ALOGV("eglMakeCurrent");
    eglMakeCurrent(display(), surface(), surface(), context());

    return !CheckEGLMakeCurrentError();
  }
  return true;
}

bool GLEnv::SwapBuffers() {
  const bool result = eglSwapBuffers(display(), surface()) == EGL_TRUE;
  return !CheckEGLError("eglSwapBuffers") && result;
}

bool GLEnv::InitWithCurrentContext() {
  if (IsInitialized())
    return true;

  display_     = eglGetCurrentDisplay();
  contexts_[0] = eglGetCurrentContext();
  surfaces_[0] = SurfaceWindowPair(eglGetCurrentSurface(EGL_DRAW), NULL);

  return (context() != EGL_NO_CONTEXT) &&
         (display() != EGL_NO_DISPLAY) &&
         (surface() != EGL_NO_SURFACE);
}

bool GLEnv::InitWithNewContext() {
  if (IsInitialized()) {
    ALOGE("GLEnv: Attempting to reinitialize environment!");
    return false;
  }

  display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
  if (CheckEGLError("eglGetDisplay")) return false;

  EGLint majorVersion;
  EGLint minorVersion;
  eglInitialize(display(), &majorVersion, &minorVersion);
  if (CheckEGLError("eglInitialize")) return false;
  initialized_ = true;

  // Configure context/surface
  EGLConfig config;
  EGLint numConfigs = -1;

  // TODO(renn): Do we need the window bit here?
  // TODO: Currently choosing the config that includes all
  // This is not needed if the encoding is not being used
  EGLint configAttribs[] = {
    EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
    EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
    EGL_RED_SIZE, 8,
    EGL_GREEN_SIZE, 8,
    EGL_BLUE_SIZE, 8,
    EGL_RECORDABLE_ANDROID, EGL_TRUE,
    EGL_NONE
  };

  eglChooseConfig(display(), configAttribs, &config, 1, &numConfigs);
  if (numConfigs < 1) {
    ALOGE("GLEnv::Init: No suitable EGL configuration found!");
    return false;
  }

  // Create dummy surface using a GLConsumer
  sp<IGraphicBufferProducer> producer;
  sp<IGraphicBufferConsumer> consumer;
  BufferQueue::createBufferQueue(&producer, &consumer);
  surfaceTexture_ = new GLConsumer(consumer, 0, GLConsumer::TEXTURE_EXTERNAL,
          true, false);
  window_ = new Surface(producer);

  surfaces_[0] = SurfaceWindowPair(eglCreateWindowSurface(display(), config, window_.get(), NULL), NULL);
  if (CheckEGLError("eglCreateWindowSurface")) return false;

  // Create context
  EGLint context_attribs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
  contexts_[0] = eglCreateContext(display(),
                                  config,
                                  EGL_NO_CONTEXT,
                                  context_attribs);
  if (CheckEGLError("eglCreateContext")) return false;

  created_context_ = created_surface_ = true;

  return true;
}

bool GLEnv::IsActive() const {
  ALOGV("IsActive()");
  return context() == eglGetCurrentContext()
    &&   display() == eglGetCurrentDisplay()
    &&   surface() == eglGetCurrentSurface(EGL_DRAW);
}

bool GLEnv::IsContextActive() const {
  return context() == eglGetCurrentContext();
}

bool GLEnv::IsAnyContextActive() {
  return eglGetCurrentContext() != EGL_NO_CONTEXT;
}

int GLEnv::AddWindowSurface(const EGLSurface& surface, WindowHandle* window_handle) {
  const int id = ++max_surface_id_;
  surfaces_[id] = SurfaceWindowPair(surface, window_handle);
  return id;
}

int GLEnv::AddSurface(const EGLSurface& surface) {
  return AddWindowSurface(surface, NULL);
}

bool GLEnv::SwitchToSurfaceId(int surface_id) {
  ALOGV("SwitchToSurfaceId");
  if (surface_id_ != surface_id) {
    const SurfaceWindowPair* surface = FindOrNull(surfaces_, surface_id);
    if (surface) {
      bool wasActive = IsActive();
      surface_id_ = surface_id;
      return wasActive ? Activate() : true;
    }
    return false;
  }
  return true;
}

bool GLEnv::ReleaseSurfaceId(int surface_id) {
  if (surface_id > 0) {
    const SurfaceWindowPair* surface_window_pair = FindOrNull(surfaces_, surface_id);
    if (surface_window_pair) {
      if (surface_id_ == surface_id)
        SwitchToSurfaceId(0);
      eglDestroySurface(display(), surface_window_pair->first);
      if (surface_window_pair->second) {
        surface_window_pair->second->Destroy();
        delete surface_window_pair->second;
      }
      surfaces_.erase(surface_id);
      return true;
    }
  }
  return false;
}

bool GLEnv::SetSurfaceTimestamp(int64_t timestamp) {
  if (surface_id_ > 0) {
    const SurfaceWindowPair* surface_window_pair = FindOrNull(surfaces_,
            surface_id_);
    if (surface_window_pair) {
      ANativeWindow *window = static_cast<ANativeWindow*>(
              surface_window_pair->second->InternalHandle());
      native_window_set_buffers_timestamp(window, timestamp);
      return true;
    }
  }
  return false;
}

int GLEnv::FindSurfaceIdForWindow(const WindowHandle* window_handle) {
  for (std::map<int, SurfaceWindowPair>::iterator it = surfaces_.begin();
       it != surfaces_.end();
       ++it) {
    const WindowHandle* my_handle = it->second.second;
    if (my_handle && my_handle->Equals(window_handle)) {
      return it->first;
    }
  }
  return -1;
}


int GLEnv::AddContext(const EGLContext& context) {
  const int id = contexts_.size();
  contexts_[id] = context;
  return id;
}

bool GLEnv::SwitchToContextId(int context_id) {
  const EGLContext* context = FindOrNull(contexts_, context_id);
  if (context) {
    if (context_id_ != context_id) {
      context_id_ = context_id;
      return Activate();
    }
    return true;
  }
  return false;
}

void GLEnv::ReleaseContextId(int context_id) {
  if (context_id > 0) {
    const EGLContext* context = FindOrNull(contexts_, context_id);
    if (context) {
      contexts_.erase(context_id);
      if (context_id_ == context_id && IsActive())
        SwitchToContextId(0);
      eglDestroyContext(display(), *context);
    }
  }
}

bool GLEnv::CheckGLError(const std::string& op) {
  bool err = false;
  for (GLint error = glGetError(); error; error = glGetError()) {
    ALOGE("GL Error: Operation '%s' caused GL error (0x%x)\n",
         op.c_str(),
         error);
    err = true;
  }
  return err;
}

bool GLEnv::CheckEGLError(const std::string& op) {
  bool err = false;
  for (EGLint error = eglGetError();
       error != EGL_SUCCESS;
       error = eglGetError()) {
    ALOGE("EGL Error: Operation '%s' caused EGL error (0x%x)\n",
         op.c_str(),
         error);
    err = true;
  }
  return err;
}

bool GLEnv::CheckEGLMakeCurrentError() {
  bool err = false;
  for (EGLint error = eglGetError();
       error != EGL_SUCCESS;
       error = eglGetError()) {
    switch (error) {
      case EGL_BAD_DISPLAY:
        ALOGE("EGL Error: Attempting to activate context with bad display!");
        break;
      case EGL_BAD_SURFACE:
        ALOGE("EGL Error: Attempting to activate context with bad surface!");
        break;
      case EGL_BAD_ACCESS:
        ALOGE("EGL Error: Attempting to activate context, which is "
             "already active in another thread!");
        break;
      default:
        ALOGE("EGL Error: Making EGL rendering context current caused "
             "error: 0x%x\n", error);
    }
    err = true;
  }
  return err;
}

GLuint GLEnv::GetCurrentProgram() {
  GLint result;
  glGetIntegerv(GL_CURRENT_PROGRAM, &result);
  ALOG_ASSERT(result >= 0);
  return static_cast<GLuint>(result);
}

EGLDisplay GLEnv::GetCurrentDisplay() {
  return eglGetCurrentDisplay();
}

int GLEnv::NumberOfComponents(GLenum type) {
  switch (type) {
    case GL_BOOL:
    case GL_FLOAT:
    case GL_INT:
      return 1;
    case GL_BOOL_VEC2:
    case GL_FLOAT_VEC2:
    case GL_INT_VEC2:
      return 2;
    case GL_INT_VEC3:
    case GL_FLOAT_VEC3:
    case GL_BOOL_VEC3:
      return 3;
    case GL_BOOL_VEC4:
    case GL_FLOAT_VEC4:
    case GL_INT_VEC4:
    case GL_FLOAT_MAT2:
      return 4;
    case GL_FLOAT_MAT3:
      return 9;
    case GL_FLOAT_MAT4:
      return 16;
    default:
      return 0;
  }
}

void GLEnv::AttachShader(int key, ShaderProgram* shader) {
  ShaderProgram* existingShader = ShaderWithKey(key);
  if (existingShader)
    delete existingShader;
  attached_shaders_[key] = shader;
}

void GLEnv::AttachVertexFrame(int key, VertexFrame* frame) {
  VertexFrame* existingFrame = VertexFrameWithKey(key);
  if (existingFrame)
    delete existingFrame;
  attached_vframes_[key] = frame;
}

ShaderProgram* GLEnv::ShaderWithKey(int key) {
  return FindPtrOrNull(attached_shaders_, key);
}

VertexFrame* GLEnv::VertexFrameWithKey(int key) {
  return FindPtrOrNull(attached_vframes_, key);
}

} // namespace filterfw
} // namespace android
