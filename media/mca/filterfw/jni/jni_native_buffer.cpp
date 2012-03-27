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

#include "jni/jni_native_buffer.h"
#include "jni/jni_util.h"

char* GetJBufferData(JNIEnv* env, jobject buffer, int* size) {
  jclass base_class = env->FindClass("android/filterfw/core/NativeBuffer");

  // Get fields
  jfieldID ptr_field = env->GetFieldID(base_class, "mDataPointer", "J");
  jfieldID size_field = env->GetFieldID(base_class, "mSize", "I");

  // Get their values
  char* data = reinterpret_cast<char*>(env->GetLongField(buffer, ptr_field));
  if (size) {
    *size = env->GetIntField(buffer, size_field);
  }

  // Clean-up
  env->DeleteLocalRef(base_class);

  return data;
}

bool AttachDataToJBuffer(JNIEnv* env, jobject buffer, char* data, int size) {
  jclass base_class = env->FindClass("android/filterfw/core/NativeBuffer");

  // Get fields
  jfieldID ptr_field = env->GetFieldID(base_class, "mDataPointer", "J");
  jfieldID size_field = env->GetFieldID(base_class, "mSize", "I");

  // Set their values
  env->SetLongField(buffer, ptr_field, reinterpret_cast<jlong>(data));
  env->SetIntField(buffer, size_field, size);

  return true;
}

jboolean Java_android_filterfw_core_NativeBuffer_allocate(JNIEnv* env, jobject thiz, jint size) {
  char* data = new char[size];
  return ToJBool(AttachDataToJBuffer(env, thiz, data, size));
}

jboolean Java_android_filterfw_core_NativeBuffer_deallocate(JNIEnv* env,
                                                            jobject thiz,
                                                            jboolean owns_data) {
  if (ToCppBool(owns_data)) {
    char* data = GetJBufferData(env, thiz, NULL);
    delete[] data;
  }
  return JNI_TRUE;
}

jboolean Java_android_filterfw_core_NativeBuffer_nativeCopyTo(JNIEnv* env,
                                                              jobject thiz,
                                                              jobject new_buffer) {
  // Get source buffer
  int size;
  char* source_data = GetJBufferData(env, thiz, &size);

  // Make copy
  char* target_data = new char[size];
  memcpy(target_data, source_data, size);

  // Attach it to new buffer
  AttachDataToJBuffer(env, new_buffer, target_data, size);

  return JNI_TRUE;
}

