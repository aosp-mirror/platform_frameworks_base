/*
 * Copyright (C) 2008 The Android Open Source Project
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

#ifndef _ANDROID_NIO_UTILS_H_
#define _ANDROID_NIO_UTILS_H_

#include <android_runtime/AndroidRuntime.h>

namespace android {
    
/**
 * Given an nio.Buffer, return a pointer to it, beginning at its current
 * position. The returned pointer is only valid for the current JNI stack-frame.
 * For performance, it does not create any global references, so the getPointer
 * (and releasePointer if array is returned non-null) must be done in the
 * same JNI stack-frame.
 *
 * @param env   The current JNI env
 * @param buffer    The nio.Buffer object
 * @param array     REQUIRED. Output. If on return it is set to non-null, then
 *                  nio_releasePointer must be called with the array
 *                  and the returned pointer when the caller is through with it.
 *                  If on return it is set to null, do not call
 *                  nio_releasePointer.
 * @return The pointer to the memory in the buffer object
 */
void* nio_getPointer(JNIEnv *env, jobject buffer, jarray *array);

/**
 * Call this if android_nio_getPointer returned non-null in its array parameter.
 * Pass that array and the returned pointer when you are done accessing the
 * pointer. If called (i.e. array is non-null), it must be called in the same
 * JNI stack-frame as getPointer
 *
 * @param env   The current JNI env
 * @param buffer    The array returned from android_nio_getPointer (!= null)
 * @param pointer   The pointer returned by android_nio_getPointer
 * @param commit    JNI_FALSE if the pointer was just read, and JNI_TRUE if
 *                  the pointer was written to.
 */
void nio_releasePointer(JNIEnv *env, jarray array, void *pointer,
                                jboolean commit);

class AutoBufferPointer {
public:
    AutoBufferPointer(JNIEnv* env, jobject nioBuffer, jboolean commit);
    ~AutoBufferPointer();

    void* pointer() const { return fPointer; }

private:
    JNIEnv* fEnv;
    void*   fPointer;
    jarray  fArray;
    jboolean fCommit;
};

}   /* namespace android */

#endif  // _ANDROID_NIO_UTILS_H_
