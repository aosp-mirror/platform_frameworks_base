/*
 * Copyright (C) 2019 The Android Open Source Project
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

#ifndef ANDROID_OS_HIDL_MEMORY_H
#define ANDROID_OS_HIDL_MEMORY_H

#include <jni.h>
#include <hidl/HidlSupport.h>

namespace android {

// A utility class for handling the android.os.HidlMemory class from JNI code.
class JHidlMemory final {
 public:
    // Convert an android.os.HidlMemory object to its C++ counterpart,
    // hardware::hidl_memory.
    // No duplication of file descriptors is performed.
    // The returned reference is owned by the underlying Java object.
    // Returns nullptr if conversion cannot be done.
    static const hardware::hidl_memory* fromJava(JNIEnv* env,
                                                 jobject jobj);

    // Convert a hardware::hidl_memory object to its Java counterpart,
    // android.os.HidlMemory.
    // No duplication of file descriptors is performed.
    // Returns nullptr if conversion cannot be done.
    static jobject toJava(JNIEnv* env,
                          const hardware::hidl_memory& cobj);

    ~JHidlMemory();

 private:
    // We store an instance of type JHidlMemory attached to every Java object
    // of type HidlMemory, for holding any native context we need. This instance
    // will get deleted when finalize() is called on the Java object.
    // This method either extracts the native object from the Java object, or
    // attached a new one if it doesn't yet exist.
    static JHidlMemory* getNativeContext(JNIEnv* env, jobject obj);

    // Convert an android.os.HidlMemory object to its C++ counterpart,
    // hardware::hidl_memory.
    // No duplication of file descriptors is performed.
    // IMPORTANT: caller is responsible to native_handle_delete() the handle of the
    // returned object. This is due to an underlying limitation of the hidl_handle
    // type, where ownership of the handle implies ownership of the fd and we don't
    // want the latter.
    // Returns nullptr if conversion cannot be done.
    static std::unique_ptr<hardware::hidl_memory> javaToNative(JNIEnv* env,
                                                               jobject jobj);

    std::unique_ptr<hardware::hidl_memory> mObj;
};

int register_android_os_HidlMemory(JNIEnv* env);

}  // namespace android

#endif //ANDROID_OS_HIDL_MEMORY_H
