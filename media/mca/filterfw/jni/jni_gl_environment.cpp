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

#include <stdint.h>
#include <android/native_window_jni.h>

#include "jni/jni_gl_environment.h"
#include "jni/jni_util.h"
#include "native/core/gl_env.h"
#include <media/mediarecorder.h>

#include <gui/IGraphicBufferProducer.h>
#include <gui/Surface.h>
#include <utils/Errors.h>
#include <system/window.h>


using android::filterfw::GLEnv;
using android::filterfw::WindowHandle;
using android::MediaRecorder;
using android::sp;
using android::IGraphicBufferProducer;
using android::Surface;


class NativeWindowHandle : public WindowHandle {
  public:
    NativeWindowHandle(ANativeWindow* window) : window_(window) {
    }

    virtual ~NativeWindowHandle() {
    }

    virtual void Destroy() {
      ALOGI("Releasing ANativeWindow!");
      ANativeWindow_release(window_);
    }

    virtual const void* InternalHandle() const {
      return window_;
    }

    virtual void* InternalHandle() {
      return window_;
    }

  private:
    ANativeWindow* window_;
};

jboolean Java_android_filterfw_core_GLEnvironment_nativeAllocate(JNIEnv* env, jobject thiz) {
  return ToJBool(WrapObjectInJava(new GLEnv(), env, thiz, true));
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeDeallocate(JNIEnv* env, jobject thiz) {
  return ToJBool(DeleteNativeObject<GLEnv>(env, thiz));
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeInitWithNewContext(JNIEnv* env,
                                                                           jobject thiz) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  return gl_env ? ToJBool(gl_env->InitWithNewContext()) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeInitWithCurrentContext(JNIEnv* env,
                                                                               jobject thiz) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  return gl_env ? ToJBool(gl_env->InitWithCurrentContext()) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeIsActive(JNIEnv* env, jobject thiz) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  return gl_env ? ToJBool(gl_env->IsActive()) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeIsContextActive(JNIEnv* env, jobject thiz) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  return gl_env ? ToJBool(gl_env->IsContextActive()) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeIsAnyContextActive(JNIEnv* /* env */,
                                                                           jclass /* clazz */) {
  return ToJBool(GLEnv::IsAnyContextActive());
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeActivate(JNIEnv* env, jobject thiz) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  return gl_env ? ToJBool(gl_env->Activate()) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeDeactivate(JNIEnv* env, jobject thiz) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  return gl_env ? ToJBool(gl_env->Deactivate()) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeSwapBuffers(JNIEnv* env, jobject thiz) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  return gl_env ? ToJBool(gl_env->SwapBuffers()) : JNI_FALSE;
}

// Get the native mediarecorder object corresponding to the java object
static sp<MediaRecorder> getMediaRecorder(JNIEnv* env, jobject jmediarecorder) {
    jclass clazz = env->FindClass("android/media/MediaRecorder");
    if (clazz == NULL) {
        return NULL;
    }

    jfieldID context = env->GetFieldID(clazz, "mNativeContext", "J");
    if (context == NULL) {
        return NULL;
    }

    MediaRecorder* const p = (MediaRecorder*)env->GetLongField(jmediarecorder, context);
    env->DeleteLocalRef(clazz);
    return sp<MediaRecorder>(p);
}


jint Java_android_filterfw_core_GLEnvironment_nativeAddSurface(JNIEnv* env,
                                                               jobject thiz,
                                                               jobject surface) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  if (!surface) {
    ALOGE("GLEnvironment: Null Surface passed!");
    return -1;
  } else if (gl_env) {
    // Get the ANativeWindow
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
      ALOGE("GLEnvironment: Error creating window!");
      return -1;
    }

    NativeWindowHandle* winHandle = new NativeWindowHandle(window);
    int result = gl_env->FindSurfaceIdForWindow(winHandle);
    if (result == -1) {
      // Configure surface
      EGLConfig config;
      EGLint numConfigs = -1;
      EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_RECORDABLE_ANDROID, EGL_TRUE,
        EGL_NONE
      };



      eglChooseConfig(gl_env->display(), configAttribs, &config, 1, &numConfigs);
      if (numConfigs < 1) {
        ALOGE("GLEnvironment: No suitable EGL configuration found for surface!");
        return -1;
      }

      // Create the EGL surface
      EGLSurface egl_surface = eglCreateWindowSurface(gl_env->display(),
                                                      config,
                                                      window,
                                                      NULL);

      if (GLEnv::CheckEGLError("eglCreateWindowSurface")) {
        ALOGE("GLEnvironment: Error creating window surface!");
        return -1;
      }

      // Add it to GL Env and assign ID
      result = gl_env->AddWindowSurface(egl_surface, winHandle);
    } else {
      delete winHandle;
    }
    return result;
  }
  return -1;
}

jint Java_android_filterfw_core_GLEnvironment_nativeAddSurfaceWidthHeight(JNIEnv* env,
                                                                      jobject thiz,
                                                                      jobject surface,
                                                                      jint width,
                                                                      jint height) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  if (!surface) {
    ALOGE("GLEnvironment: Null SurfaceTexture passed!");
    return -1;
  } else if (gl_env) {
    // Get the ANativeWindow
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
      ALOGE("GLEnvironment: Error creating window!");
      return -1;
    }

    // Don't care about format (will get overridden by SurfaceTexture
    // anyway), but do care about width and height
    // TODO: Probably, this should be just be
    // ANativeWindow_setBuffersDimensions. The pixel format is
    // set during the eglCreateWindowSurface
    ANativeWindow_setBuffersGeometry(window, width, height, 0);

    NativeWindowHandle* winHandle = new NativeWindowHandle(window);
    int result = gl_env->FindSurfaceIdForWindow(winHandle);
    if (result == -1) {
      // Configure surface
      EGLConfig config;
      EGLint numConfigs = -1;
      EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_RECORDABLE_ANDROID, EGL_TRUE,
        EGL_NONE
      };



      eglChooseConfig(gl_env->display(), configAttribs, &config, 1, &numConfigs);
      if (numConfigs < 1) {
        ALOGE("GLEnvironment: No suitable EGL configuration found for surface texture!");
        return -1;
      }

      // Create the EGL surface
      EGLSurface egl_surface = eglCreateWindowSurface(gl_env->display(),
                                                      config,
                                                      window,
                                                      NULL);

      if (GLEnv::CheckEGLError("eglCreateWindowSurface")) {
        ALOGE("GLEnvironment: Error creating window surface!");
        return -1;
      }

      // Add it to GL Env and assign ID
      result = gl_env->AddWindowSurface(egl_surface, winHandle);
    } else {
      delete winHandle;
    }
    return result;
  }
  return -1;
}

// nativeAddSurfaceFromMediaRecorder gets an EGLSurface
// using a MediaRecorder object.
// When Mediarecorder is used for recording GL Frames, it
// will have a reference to a Native Handle (a SurfaceTexureClient)
// which talks to the StageFrightRecorder in mediaserver via
// a binder interface.
jint Java_android_filterfw_core_GLEnvironment_nativeAddSurfaceFromMediaRecorder(
                                                      JNIEnv* env,
                                                      jobject thiz,
                                                      jobject jmediarecorder) {
    ALOGV("GLEnv Jni: nativeAddSurfaceFromMediaRecorder");
    GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
    if (!gl_env) {
        return -1;
    }
    // get a native mediarecorder object from the java object
    sp<MediaRecorder> mr = getMediaRecorder(env, jmediarecorder);
    if (mr == NULL) {
        ALOGE("GLEnvironment: Error- MediaRecorder could not be initialized!");
        return -1;
    }

    // Ask the mediarecorder to return a handle to a surfacemediasource
    // This will talk to the StageFrightRecorder via MediaRecorderClient
    // over binder calls
    sp<IGraphicBufferProducer> surfaceMS = mr->querySurfaceMediaSourceFromMediaServer();
    if (surfaceMS == NULL) {
      ALOGE("GLEnvironment: Error- MediaRecorder returned a null \
              <IGraphicBufferProducer> handle.");
      return -1;
    }
    sp<Surface> surfaceTC = new Surface(surfaceMS);
    // Get the ANativeWindow
    sp<ANativeWindow> window = surfaceTC;


    if (window == NULL) {
      ALOGE("GLEnvironment: Error creating window!");
      return -1;
    }
    window->incStrong((void*)ANativeWindow_acquire);

    // In case of encoding, no need to set the dimensions
    // on the buffers. The dimensions for the final encoding are set by
    // the consumer side.
    // The pixel format is dictated by the GL, and set during the
    // eglCreateWindowSurface

    NativeWindowHandle* winHandle = new NativeWindowHandle(window.get());
    int result = gl_env->FindSurfaceIdForWindow(winHandle);
    // If we find a surface with that window handle, just return that id
    if (result != -1) {
        delete winHandle;
        return result;
    }
    // If we do not find a surface with that window handle, create
    // one and assign to it the handle
    // Configure surface
    EGLConfig config;
    EGLint numConfigs = -1;
    EGLint configAttribs[] = {
          EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
          EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
          EGL_RED_SIZE, 8,
          EGL_GREEN_SIZE, 8,
          EGL_BLUE_SIZE, 8,
          EGL_RECORDABLE_ANDROID, EGL_TRUE,
          EGL_NONE
    };


    eglChooseConfig(gl_env->display(), configAttribs, &config, 1, &numConfigs);
    if (numConfigs < 1) {
      ALOGE("GLEnvironment: No suitable EGL configuration found for surface texture!");
      delete winHandle;
      return -1;
    }

    // Create the EGL surface
    EGLSurface egl_surface = eglCreateWindowSurface(gl_env->display(),
                                                    config,
                                                    window.get(),
                                                    NULL);

    if (GLEnv::CheckEGLError("eglCreateWindowSurface")) {
      ALOGE("GLEnvironment: Error creating window surface!");
      delete winHandle;
      return -1;
    }

    // Add it to GL Env and assign ID
    result = gl_env->AddWindowSurface(egl_surface, winHandle);
    return result;
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeActivateSurfaceId(JNIEnv* env,
                                                                          jobject thiz,
                                                                          jint surfaceId) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  return gl_env ? ToJBool(gl_env->SwitchToSurfaceId(surfaceId) && gl_env->Activate()) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeRemoveSurfaceId(JNIEnv* env,
                                                                        jobject thiz,
                                                                        jint surfaceId) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  return gl_env ? ToJBool(gl_env->ReleaseSurfaceId(surfaceId)) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLEnvironment_nativeSetSurfaceTimestamp(JNIEnv* env,
                                                                            jobject thiz,
                                                                            jlong timestamp) {
  GLEnv* gl_env = ConvertFromJava<GLEnv>(env, thiz);
  int64_t timestamp_native = timestamp;
  return gl_env ? ToJBool(gl_env->SetSurfaceTimestamp(timestamp_native)) : JNI_FALSE;
}
