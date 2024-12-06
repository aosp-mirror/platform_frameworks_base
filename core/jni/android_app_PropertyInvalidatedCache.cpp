/*
 * Copyright (C) 2024 The Android Open Source Project
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

#define LOG_TAG "CacheNonce"

#include <string.h>
#include <memory.h>

#include <atomic>

#include <nativehelper/JNIHelp.h>
#include <nativehelper/scoped_primitive_array.h>
#include <android-base/logging.h>

#include "core_jni_helpers.h"
#include "android_app_PropertyInvalidatedCache.h"

namespace {

using namespace android::app::PropertyInvalidatedCache;

// Convert a jlong to a nonce block.  This is a convenience function that should be inlined by
// the compiler.
inline SystemCacheNonce* sysCache(jlong ptr) {
    return reinterpret_cast<SystemCacheNonce*>(ptr);
}

// Return the number of nonces in the nonce block.
jint getMaxNonce(JNIEnv*, jclass, jlong ptr) {
    return sysCache(ptr)->getMaxNonce();
}

// Return the number of string bytes in the nonce block.
jint getMaxByte(JNIEnv*, jclass, jlong ptr) {
    return sysCache(ptr)->getMaxByte();
}

// Set the byte block.  The first int is the hash to set and the second is the array to copy.
// This should be synchronized in the Java layer.
void setByteBlock(JNIEnv* env, jclass, jlong ptr, jint hash, jbyteArray val) {
    ScopedByteArrayRO value(env, val);
    if (value.get() == nullptr) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", "null byte block");
        return;
    }
    sysCache(ptr)->setByteBlock(hash, value.get(), value.size());
}

// Fetch the byte block.  If the incoming hash is the same as the local hash, the Java layer is
// presumed to have an up-to-date copy of the byte block; do not copy byte array.  The local
// hash is returned.
jint getByteBlock(JNIEnv* env, jclass, jlong ptr, jint hash, jbyteArray val) {
    if (sysCache(ptr)->getHash() == hash) {
        return hash;
    }
    ScopedByteArrayRW value(env, val);
    return sysCache(ptr)->getByteBlock(value.get(), value.size());
}

// Fetch the byte block hash.
//
// This is a CriticalNative method and therefore does not get the JNIEnv or jclass parameters.
jint getByteBlockHash(jlong ptr) {
    return sysCache(ptr)->getHash();
}

// Get a nonce value. So that this method can be CriticalNative, it returns 0 if the value is
// out of range, rather than throwing an exception.  This is a CriticalNative method and
// therefore does not get the JNIEnv or jclass parameters.
//
// This method is @CriticalNative and does not take a JNIEnv* or jclass argument.
jlong getNonce(jlong ptr, jint index) {
    return sysCache(ptr)->getNonce(index);
}

// Set a nonce value. So that this method can be CriticalNative, it returns a boolean: false if
// the index is out of range and true otherwise.  Callers may test the returned boolean and
// generate an exception.
//
// This method is @CriticalNative and does not take a JNIEnv* or jclass argument.
jboolean setNonce(jlong ptr, jint index, jlong value) {
    return sysCache(ptr)->setNonce(index, value);
}

static const JNINativeMethod gMethods[] = {
    {"nativeGetMaxNonce",      "(J)I",    (void*) getMaxNonce },
    {"nativeGetMaxByte",       "(J)I",    (void*) getMaxByte },
    {"nativeSetByteBlock",     "(JI[B)V", (void*) setByteBlock },
    {"nativeGetByteBlock",     "(JI[B)I", (void*) getByteBlock },
    {"nativeGetByteBlockHash", "(J)I",    (void*) getByteBlockHash },
    {"nativeGetNonce",         "(JI)J",   (void*) getNonce },
    {"nativeSetNonce",         "(JIJ)Z",  (void*) setNonce },
};

static const char* kClassName = "android/app/PropertyInvalidatedCache";

} // anonymous namespace

namespace android {

int register_android_app_PropertyInvalidatedCache(JNIEnv* env) {
    RegisterMethodsOrDie(env, kClassName, gMethods, NELEM(gMethods));
    return JNI_OK;
}

} // namespace android
