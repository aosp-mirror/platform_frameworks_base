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

#define LOG_TAG "EphemeralStorage"
//#define LOG_NDEBUG 0

#include <android-base/logging.h>

#include "EphemeralStorage.h"

using ::android::hardware::hidl_string;
using ::android::hardware::hidl_vec;

namespace android {

EphemeralStorage::EphemeralStorage() {
}

EphemeralStorage::~EphemeralStorage() {
    CHECK(mItems.empty())
        << "All item storage should have been released by now.";
}

hidl_string *EphemeralStorage::allocStringArray(size_t size) {
    Item item;
    item.mType = TYPE_STRING_ARRAY;
    item.mObj = NULL;
    item.mPtr = new hidl_string[size];
    mItems.push_back(item);

    return static_cast<hidl_string *>(item.mPtr);
}

void *EphemeralStorage::allocTemporaryStorage(size_t size) {
    Item item;
    item.mType = TYPE_STORAGE;
    item.mObj = NULL;
    item.mPtr = malloc(size);
    mItems.push_back(item);

    return item.mPtr;
}

const hidl_string *EphemeralStorage::allocTemporaryString(
        JNIEnv *env, jstring stringObj) {
    jstring obj = (jstring)env->NewGlobalRef(stringObj);
    const char *val = env->GetStringUTFChars(obj, NULL);

    Item item;
    item.mType = TYPE_STRING;
    item.mObj = obj;
    item.mPtr = (void *)val;
    mItems.push_back(item);

    hidl_string *s = allocStringArray(1 /* size */);
    s->setToExternal((char *)val, strlen(val));

    return s;
}

native_handle_t *EphemeralStorage::allocTemporaryNativeHandle(
        int numFds, int numInts) {
    Item item;
    item.mType = TYPE_NATIVE_HANDLE;
    item.mObj = nullptr;
    item.mPtr = native_handle_create(numFds, numInts);
    mItems.push_back(item);

    return static_cast<native_handle_t*>(item.mPtr);
}

#define DEFINE_ALLOC_VECTOR_METHODS(Suffix,Type,NewType)                       \
const hidl_vec<Type> *EphemeralStorage::allocTemporary ## Suffix ## Vector(    \
        JNIEnv *env, Type ## Array arrayObj) {                                 \
    Type ## Array obj = (Type ## Array)env->NewGlobalRef(arrayObj);            \
    jsize len = env->GetArrayLength(obj);                                      \
    const Type *val = env->Get ## NewType ## ArrayElements(obj, NULL);         \
                                                                               \
    Item item;                                                                 \
    item.mType = TYPE_ ## Suffix ## _ARRAY;                                    \
    item.mObj = obj;                                                           \
    item.mPtr = (void *)val;                                                   \
    mItems.push_back(item);                                                    \
                                                                               \
    void *vecPtr = allocTemporaryStorage(sizeof(hidl_vec<Type>));              \
                                                                               \
    hidl_vec<Type> *vec = new (vecPtr) hidl_vec<Type>;                         \
    vec->setToExternal(const_cast<Type *>(val), len);                          \
                                                                               \
    return vec;                                                                \
}

DEFINE_ALLOC_VECTOR_METHODS(Int8,jbyte,Byte)
DEFINE_ALLOC_VECTOR_METHODS(Int16,jshort,Short)
DEFINE_ALLOC_VECTOR_METHODS(Int32,jint,Int)
DEFINE_ALLOC_VECTOR_METHODS(Int64,jlong,Long)
DEFINE_ALLOC_VECTOR_METHODS(Float,jfloat,Float)
DEFINE_ALLOC_VECTOR_METHODS(Double,jdouble,Double)

#define DEFINE_RELEASE_ARRAY_CASE(Suffix,Type,NewType)                         \
            case TYPE_ ## Suffix ## _ARRAY:                                    \
            {                                                                  \
                env->Release ## NewType ## ArrayElements(                      \
                        (Type ## Array)item.mObj,                              \
                        (Type *)item.mPtr,                                     \
                        0 /* mode */);                                         \
                                                                               \
                env->DeleteGlobalRef(item.mObj);                               \
                break;                                                         \
            }

__attribute__((no_sanitize("unsigned-integer-overflow")))
void EphemeralStorage::release(JNIEnv *env) {
    for (size_t i = mItems.size(); i--;) {
        const Item &item = mItems[i];

        switch (item.mType) {
            case TYPE_STRING_ARRAY:
            {
                delete[] static_cast<hidl_string *>(item.mPtr);
                break;
            }

            case TYPE_STORAGE:
            {
                free(item.mPtr);
                break;
            }

            case TYPE_STRING:
            {
                env->ReleaseStringUTFChars(
                        (jstring)item.mObj, (const char *)item.mPtr);

                env->DeleteGlobalRef(item.mObj);
                break;
            }

            DEFINE_RELEASE_ARRAY_CASE(Int8,jbyte,Byte)
            DEFINE_RELEASE_ARRAY_CASE(Int16,jshort,Short)
            DEFINE_RELEASE_ARRAY_CASE(Int32,jint,Int)
            DEFINE_RELEASE_ARRAY_CASE(Int64,jlong,Long)
            DEFINE_RELEASE_ARRAY_CASE(Float,jfloat,Float)
            DEFINE_RELEASE_ARRAY_CASE(Double,jdouble,Double)

            case TYPE_NATIVE_HANDLE:
            {
                int err = native_handle_delete(static_cast<native_handle_t *>(item.mPtr));
                CHECK(err == 0);
                break;
            }

            default:
                CHECK(!"Should not be here") << "Item type: " << item.mType;
        }
    }

    mItems.clear();
}

}  // namespace android

