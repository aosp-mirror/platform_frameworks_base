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

#include <nativehelper/JNIHelp.h>

namespace android {

/**
 * Class providing scoped access to the memory backing a java.nio.Buffer instance.
 *
 * Instances of this class should only be allocated on the stack as heap allocation is not
 * supported.
 *
 * Instances of this class do not create any global references for performance reasons.
 */
class AutoBufferPointer final {
public:
    /** Constructor for an AutoBufferPointer instance.
     *
     * @param env          The current JNI env
     * @param nioBuffer    Instance of a java.nio.Buffer whose memory will be accessed.
     * @param commit       JNI_TRUE if the underlying memory will be updated and should be
     *                     copied back to the managed heap. JNI_FALSE if the data will
     *                     not be modified or the modifications may be discarded.
     *
     * The commit parameter is only applicable if the buffer is backed by a managed heap
     * array and the runtime had to provide a copy of the data rather than the original data.
     */
    AutoBufferPointer(JNIEnv* env, jobject nioBuffer, jboolean commit);

    /** Destructor for an AutoBufferPointer instance.
     *
     * Releases critical managed heap array pointer if acquired.
     */
    ~AutoBufferPointer();

    /**
     * Returns a pointer to the current position of the buffer provided to the constructor.  This
     * pointer is only valid whilst the AutoBufferPointer instance remains in scope.
     */
    void* pointer() const { return fPointer; }

private:
    JNIEnv* const fEnv;
    void* fPointer;   // Pointer to current buffer position when constructed.
    void* fElements;  // Pointer to array element 0 (null if buffer is direct, may be
                      // within fArray or point to a copy of the array).
    jarray fArray;    // Pointer to array on managed heap.
    const jboolean fCommit;  // Flag to commit data to source (when fElements is a copy of fArray).

    // Unsupported constructors and operators.
    AutoBufferPointer() = delete;
    AutoBufferPointer(AutoBufferPointer&) = delete;
    AutoBufferPointer& operator=(AutoBufferPointer&) = delete;
    static void* operator new(size_t);
    static void* operator new[](size_t);
    static void* operator new(size_t, void*);
    static void* operator new[](size_t, void*);
    static void operator delete(void*, size_t);
    static void operator delete[](void*, size_t);
};

}   /* namespace android */

#endif  // _ANDROID_NIO_UTILS_H_
