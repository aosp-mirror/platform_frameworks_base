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

#ifndef ANDROID_FILTEFW_JNI_NATIVE_BUFFER_H
#define ANDROID_FILTEFW_JNI_NATIVE_BUFFER_H

#include <jni.h>

// Internal Buffer Unwrapping functions ////////////////////////////////////////////////////////////
/**
 * Given a Java NativeBuffer instance, get access to the underlying C pointer and its size. The
 * size argument may be NULL, in which case the object is not queried for its size.
 **/
char* GetJBufferData(JNIEnv* env, jobject buffer, int* size);

/**
 * Attach a given C data buffer and its size to a given allocated Java NativeBuffer instance. After
 * this call, the java instance will have the given C buffer as its backing. Note, that the Java
 * instance contains the flag on whether or not it owns the buffer or not, so make sure it is what
 * you expect.
 **/
bool AttachDataToJBuffer(JNIEnv* env, jobject buffer, char* data, int size);

#ifdef __cplusplus
extern "C" {
#endif

// JNI Wrappers ////////////////////////////////////////////////////////////////////////////////////
JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeBuffer_allocate(JNIEnv* env, jobject thiz, jint size);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeBuffer_deallocate(JNIEnv* env, jobject thiz, jboolean owns_data);

JNIEXPORT jboolean JNICALL
Java_android_filterfw_core_NativeBuffer_nativeCopyTo(JNIEnv* env, jobject thiz, jobject new_buffer);

#ifdef __cplusplus
}
#endif

#endif // ANDROID_FILTEFW_JNI_NATIVE_BUFFER_H
