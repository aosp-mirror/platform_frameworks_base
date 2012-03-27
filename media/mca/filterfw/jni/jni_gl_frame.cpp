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

#include "android/bitmap.h"

#include "jni/jni_gl_frame.h"
#include "jni/jni_util.h"

#include "native/core/gl_env.h"
#include "native/core/gl_frame.h"
#include "native/core/native_frame.h"

using android::filterfw::GLEnv;
using android::filterfw::GLFrame;
using android::filterfw::NativeFrame;

// Helper functions ////////////////////////////////////////////////////////////////////////////////
void ConvertFloatsToRGBA(const float* floats, int length, uint8_t* result) {
  for (int i = 0; i < length; ++i) {
    result[i] = static_cast<uint8_t>(floats[i] * 255.0);
  }
}

void ConvertRGBAToFloats(const uint8_t* rgba, int length, float* result) {
  for (int i = 0; i < length; ++i) {
    result[i] = rgba[i] / 255.0;
  }
}

// GLFrame JNI implementation //////////////////////////////////////////////////////////////////////
jboolean Java_android_filterfw_core_GLFrame_nativeAllocate(JNIEnv* env,
                                                           jobject thiz,
                                                           jobject gl_env,
                                                           jint width,
                                                           jint height) {
  GLEnv* gl_env_ptr = ConvertFromJava<GLEnv>(env, gl_env);
  if (!gl_env_ptr) return JNI_FALSE;
  GLFrame* frame = new GLFrame(gl_env_ptr);
  if (frame->Init(width, height)) {
    return ToJBool(WrapObjectInJava(frame, env, thiz, true));
  } else {
    delete frame;
    return JNI_FALSE;
  }
}

jboolean Java_android_filterfw_core_GLFrame_nativeAllocateWithTexture(JNIEnv* env,
                                                                      jobject thiz,
                                                                      jobject gl_env,
                                                                      jint tex_id,
                                                                      jint width,
                                                                      jint height) {
  GLEnv* gl_env_ptr = ConvertFromJava<GLEnv>(env, gl_env);
  if (!gl_env_ptr) return JNI_FALSE;
  GLFrame* frame = new GLFrame(gl_env_ptr);
  if (frame->InitWithTexture(tex_id, width, height)) {
    return ToJBool(WrapObjectInJava(frame, env, thiz, true));
  } else {
    delete frame;
    return JNI_FALSE;
  }
}

jboolean Java_android_filterfw_core_GLFrame_nativeAllocateWithFbo(JNIEnv* env,
                                                                  jobject thiz,
                                                                  jobject gl_env,
                                                                  jint fbo_id,
                                                                  jint width,
                                                                  jint height) {
  GLEnv* gl_env_ptr = ConvertFromJava<GLEnv>(env, gl_env);
  if (!gl_env_ptr) return JNI_FALSE;
  GLFrame* frame = new GLFrame(gl_env_ptr);
  if (frame->InitWithFbo(fbo_id, width, height)) {
    return ToJBool(WrapObjectInJava(frame, env, thiz, true));
  } else {
    delete frame;
    return JNI_FALSE;
  }
}

jboolean Java_android_filterfw_core_GLFrame_nativeAllocateExternal(JNIEnv* env,
                                                                   jobject thiz,
                                                                   jobject gl_env) {
  GLEnv* gl_env_ptr = ConvertFromJava<GLEnv>(env, gl_env);
  if (!gl_env_ptr) return JNI_FALSE;
  GLFrame* frame = new GLFrame(gl_env_ptr);
  if (frame->InitWithExternalTexture()) {
    return ToJBool(WrapObjectInJava(frame, env, thiz, true));
  } else {
    delete frame;
    return JNI_FALSE;
  }
}

jboolean Java_android_filterfw_core_GLFrame_nativeDeallocate(JNIEnv* env, jobject thiz) {
  return ToJBool(DeleteNativeObject<GLFrame>(env, thiz));
}

jboolean Java_android_filterfw_core_GLFrame_setNativeData(JNIEnv* env,
                                                          jobject thiz,
                                                          jbyteArray data,
                                                          jint offset,
                                                          jint length) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  if (frame && data) {
    jbyte* bytes = env->GetByteArrayElements(data, NULL);
    if (bytes) {
      const bool success = frame->WriteData(reinterpret_cast<const uint8_t*>(bytes + offset), length);
      env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
      return ToJBool(success);
    }
  }
  return JNI_FALSE;
}

jbyteArray Java_android_filterfw_core_GLFrame_getNativeData(JNIEnv* env, jobject thiz) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  if (frame && frame->Size() > 0) {
    jbyteArray result = env->NewByteArray(frame->Size());
    jbyte* data = env->GetByteArrayElements(result, NULL);
    frame->CopyDataTo(reinterpret_cast<uint8_t*>(data), frame->Size());
    env->ReleaseByteArrayElements(result, data, 0);
    return result;
  }
  return NULL;
}

jboolean Java_android_filterfw_core_GLFrame_setNativeInts(JNIEnv* env,
                                                          jobject thiz,
                                                          jintArray ints) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  if (frame && ints) {
    jint* int_ptr = env->GetIntArrayElements(ints, NULL);
    const int length = env->GetArrayLength(ints);
    if (int_ptr) {
      const bool success = frame->WriteData(reinterpret_cast<const uint8_t*>(int_ptr),
                                            length * sizeof(jint));
      env->ReleaseIntArrayElements(ints, int_ptr, JNI_ABORT);
      return ToJBool(success);
    }
  }
  return JNI_FALSE;
}

jintArray Java_android_filterfw_core_GLFrame_getNativeInts(JNIEnv* env, jobject thiz) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  if (frame && frame->Size() > 0 && (frame->Size() % sizeof(jint) == 0)) {
    jintArray result = env->NewIntArray(frame->Size() / sizeof(jint));
    jint* data = env->GetIntArrayElements(result, NULL);
    frame->CopyDataTo(reinterpret_cast<uint8_t*>(data), frame->Size());
    env->ReleaseIntArrayElements(result, data, 0);
    return result;
   }
   return NULL;
}

jboolean Java_android_filterfw_core_GLFrame_setNativeFloats(JNIEnv* env,
                                                            jobject thiz,
                                                            jfloatArray floats) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  if (frame && floats) {
    jfloat* float_ptr = env->GetFloatArrayElements(floats, NULL);
    const int length = env->GetArrayLength(floats);
    if (float_ptr) {
      // Convert floats to RGBA buffer
      uint8_t* rgba_buffer = new uint8_t[length];
      ConvertFloatsToRGBA(float_ptr, length, rgba_buffer);
      env->ReleaseFloatArrayElements(floats, float_ptr, JNI_ABORT);

      // Write RGBA buffer to frame
      const bool success = frame->WriteData(rgba_buffer, length);

      // Clean-up
      delete[] rgba_buffer;
      return ToJBool(success);
    }
  }
  return JNI_FALSE;
}

jfloatArray Java_android_filterfw_core_GLFrame_getNativeFloats(JNIEnv* env, jobject thiz) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  if (frame && frame->Size() > 0) {
    // Create the result array
    jfloatArray result = env->NewFloatArray(frame->Size());
    jfloat* float_array = env->GetFloatArrayElements(result, NULL);

    // Read the frame pixels
    uint8_t* pixels = new uint8_t[frame->Size()];
    frame->CopyDataTo(pixels, frame->Size());

    // Convert them to floats
    ConvertRGBAToFloats(pixels, frame->Size(), float_array);

    // Clean-up
    delete[] pixels;
    env->ReleaseFloatArrayElements(result, float_array, 0);
    return result;
  }
  return NULL;
}

jboolean Java_android_filterfw_core_GLFrame_setNativeBitmap(JNIEnv* env,
                                                            jobject thiz,
                                                            jobject bitmap,
                                                            jint size) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  if (frame && bitmap) {
    uint8_t* pixels;
    const int result = AndroidBitmap_lockPixels(env, bitmap, reinterpret_cast<void**>(&pixels));
    if (result == ANDROID_BITMAP_RESUT_SUCCESS) {
      const bool success = frame->WriteData(pixels, size);
      return ToJBool(success &&
                     AndroidBitmap_unlockPixels(env, bitmap) == ANDROID_BITMAP_RESUT_SUCCESS);
    }
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLFrame_getNativeBitmap(JNIEnv* env,
                                                            jobject thiz,
                                                            jobject bitmap) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  if (frame && bitmap) {
    uint8_t* pixels;
    const int result = AndroidBitmap_lockPixels(env, bitmap, reinterpret_cast<void**>(&pixels));
    if (result == ANDROID_BITMAP_RESUT_SUCCESS) {
      frame->CopyDataTo(pixels, frame->Size());
      return (AndroidBitmap_unlockPixels(env, bitmap) == ANDROID_BITMAP_RESUT_SUCCESS);
    }
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLFrame_setNativeViewport(JNIEnv* env,
                                                              jobject thiz,
                                                              jint x,
                                                              jint y,
                                                              jint width,
                                                              jint height) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  return frame ? ToJBool(frame->SetViewport(x, y, width, height)) : JNI_FALSE;
}

jint Java_android_filterfw_core_GLFrame_getNativeTextureId(JNIEnv* env, jobject thiz) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  return frame ? frame->GetTextureId() : -1;
}

jint Java_android_filterfw_core_GLFrame_getNativeFboId(JNIEnv* env, jobject thiz) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  return frame ? frame->GetFboId() : -1;
}

jboolean Java_android_filterfw_core_GLFrame_generateNativeMipMap(JNIEnv* env, jobject thiz) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  return frame ? ToJBool(frame->GenerateMipMap()) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLFrame_setNativeTextureParam(JNIEnv* env,
                                                                  jobject thiz,
                                                                  jint param,
                                                                  jint value) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  return frame ? ToJBool(frame->SetTextureParameter(param, value)) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLFrame_nativeResetParams(JNIEnv* env, jobject thiz) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  return frame ? ToJBool(frame->ResetTexParameters()) : JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLFrame_nativeCopyFromNative(JNIEnv* env,
                                                                 jobject thiz,
                                                                 jobject frame) {
  GLFrame* this_frame = ConvertFromJava<GLFrame>(env, thiz);
  NativeFrame* other_frame = ConvertFromJava<NativeFrame>(env, frame);
  if (this_frame && other_frame) {
    return ToJBool(this_frame->WriteData(other_frame->Data(), other_frame->Size()));
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLFrame_nativeCopyFromGL(JNIEnv* env,
                                                             jobject thiz,
                                                             jobject frame) {
  GLFrame* this_frame = ConvertFromJava<GLFrame>(env, thiz);
  GLFrame* other_frame = ConvertFromJava<GLFrame>(env, frame);
  if (this_frame && other_frame) {
    return ToJBool(this_frame->CopyPixelsFrom(other_frame));
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_GLFrame_nativeFocus(JNIEnv* env, jobject thiz) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  return ToJBool(frame && frame->FocusFrameBuffer());
}

jboolean Java_android_filterfw_core_GLFrame_nativeReattachTexToFbo(JNIEnv* env, jobject thiz) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  return ToJBool(frame && frame->ReattachTextureToFbo());
}

jboolean Java_android_filterfw_core_GLFrame_nativeDetachTexFromFbo(JNIEnv* env, jobject thiz) {
  GLFrame* frame = ConvertFromJava<GLFrame>(env, thiz);
  return ToJBool(frame && frame->DetachTextureFromFbo());
}

