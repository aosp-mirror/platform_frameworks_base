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

#include "jni/jni_vertex_frame.h"
#include "jni/jni_util.h"

#include "native/core/vertex_frame.h"

using android::filterfw::VertexFrame;

jboolean Java_android_filterfw_core_VertexFrame_nativeAllocate(JNIEnv* env,
                                                               jobject thiz,
                                                               jint size) {
  std::unique_ptr<VertexFrame> frame(new VertexFrame(size));
  return ToJBool(WrapOwnedObjectInJava(std::move(frame), env, thiz, true));
}

jboolean Java_android_filterfw_core_VertexFrame_nativeDeallocate(JNIEnv* env, jobject thiz) {
  return ToJBool(DeleteNativeObject<VertexFrame>(env, thiz));
}

jboolean Java_android_filterfw_core_VertexFrame_setNativeInts(JNIEnv* env,
                                                              jobject thiz,
                                                              jintArray ints) {

  VertexFrame* frame = ConvertFromJava<VertexFrame>(env, thiz);
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

jboolean Java_android_filterfw_core_VertexFrame_setNativeFloats(JNIEnv* env,
                                                                jobject thiz,
                                                                jfloatArray floats) {
  VertexFrame* frame = ConvertFromJava<VertexFrame>(env, thiz);
  if (frame && floats) {
    jfloat* float_ptr = env->GetFloatArrayElements(floats, NULL);
    const int length = env->GetArrayLength(floats);
    if (float_ptr) {
      const bool success = frame->WriteData(reinterpret_cast<const uint8_t*>(float_ptr),
                                            length * sizeof(jfloat));
      env->ReleaseFloatArrayElements(floats, float_ptr, JNI_ABORT);
      return ToJBool(success);
    }
  }
  return JNI_FALSE;
}

jboolean Java_android_filterfw_core_VertexFrame_setNativeData(JNIEnv* env,
                                                              jobject thiz,
                                                              jbyteArray data,
                                                              jint offset,
                                                              jint length) {
  VertexFrame* frame = ConvertFromJava<VertexFrame>(env, thiz);
  if (frame && data) {
    jbyte* bytes = env->GetByteArrayElements(data, NULL);
    if (bytes) {
      const bool success = frame->WriteData(reinterpret_cast<const uint8_t*>(bytes + offset),
                                            length);
      env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
      return ToJBool(success);
    }
  }
  return JNI_FALSE;
}

jint Java_android_filterfw_core_VertexFrame_getNativeVboId(JNIEnv* env, jobject thiz) {
  VertexFrame* frame = ConvertFromJava<VertexFrame>(env, thiz);
  return frame ? frame->GetVboId() : -1;
}
