/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef EPHEMERAL_STORAGE_H_

#define EPHEMERAL_STORAGE_H_

#include <android-base/macros.h>
#include <hidl/HidlSupport.h>
#include <jni.h>
#include <utils/Vector.h>

namespace android {

#define DECLARE_ALLOC_METHODS(Suffix,Type)                          \
    const ::android::hardware::hidl_vec<Type> *                     \
    allocTemporary ## Suffix ## Vector(                             \
            JNIEnv *env, Type ## Array arrayObj);

struct EphemeralStorage {
    EphemeralStorage();
    ~EphemeralStorage();

    void release(JNIEnv *env);

    hardware::hidl_string *allocStringArray(size_t size);

    void *allocTemporaryStorage(size_t size);

    const ::android::hardware::hidl_string *allocTemporaryString(
            JNIEnv *env, jstring stringObj);

    native_handle_t *allocTemporaryNativeHandle(int numFds, int numInts);

    DECLARE_ALLOC_METHODS(Int8,jbyte)
    DECLARE_ALLOC_METHODS(Int16,jshort)
    DECLARE_ALLOC_METHODS(Int32,jint)
    DECLARE_ALLOC_METHODS(Int64,jlong)
    DECLARE_ALLOC_METHODS(Float,jfloat)
    DECLARE_ALLOC_METHODS(Double,jdouble)

private:
    enum Type {
        TYPE_STRING_ARRAY,
        TYPE_STORAGE,
        TYPE_STRING,
        TYPE_Int8_ARRAY,
        TYPE_Int16_ARRAY,
        TYPE_Int32_ARRAY,
        TYPE_Int64_ARRAY,
        TYPE_Float_ARRAY,
        TYPE_Double_ARRAY,
        TYPE_NATIVE_HANDLE,
    };

    struct Item {
        Type mType;
        jobject mObj;
        void *mPtr;
    };

    Vector<Item> mItems;

    DISALLOW_COPY_AND_ASSIGN(EphemeralStorage);
};

#undef DECLARE_ALLOC_METHODS

}  // namespace android

#endif  // EPHEMERAL_STORAGE_H_
