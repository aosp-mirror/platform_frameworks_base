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

#include <debugstore/debugstore_cxx_bridge.rs.h>
#include <log/log.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <nativehelper/ScopedUtfChars.h>

#include <iterator>
#include <sstream>
#include <vector>

#include "core_jni_helpers.h"

namespace android {

static struct {
    jmethodID mGet;
    jmethodID mSize;
} gListClassInfo;

static std::vector<std::string> list_to_vector(JNIEnv* env, jobject jList) {
    std::vector<std::string> vec;
    jint size = env->CallIntMethod(jList, gListClassInfo.mSize);
    if (size % 2 != 0) {
        std::ostringstream oss;

        std::copy(vec.begin(), vec.end(), std::ostream_iterator<std::string>(oss, ", "));
        ALOGW("DebugStore list size is odd: %d, elements: %s", size, oss.str().c_str());

        return vec;
    }

    vec.reserve(size);

    for (jint i = 0; i < size; i++) {
        ScopedLocalRef<jstring> jEntry(env,
                                       reinterpret_cast<jstring>(
                                               env->CallObjectMethod(jList, gListClassInfo.mGet,
                                                                     i)));
        ScopedUtfChars cEntry(env, jEntry.get());
        vec.emplace_back(cEntry.c_str());
    }
    return vec;
}

static void com_android_internal_os_DebugStore_endEvent(JNIEnv* env, jclass clazz, jlong eventId,
                                                        jobject jAttributeList) {
    auto attributes = list_to_vector(env, jAttributeList);
    debugstore::debug_store_end(static_cast<uint64_t>(eventId), attributes);
}

static jlong com_android_internal_os_DebugStore_beginEvent(JNIEnv* env, jclass clazz,
                                                           jstring jeventName,
                                                           jobject jAttributeList) {
    ScopedUtfChars eventName(env, jeventName);
    auto attributes = list_to_vector(env, jAttributeList);
    jlong eventId =
            static_cast<jlong>(debugstore::debug_store_begin(eventName.c_str(), attributes));
    return eventId;
}

static void com_android_internal_os_DebugStore_recordEvent(JNIEnv* env, jclass clazz,
                                                           jstring jeventName,
                                                           jobject jAttributeList) {
    ScopedUtfChars eventName(env, jeventName);
    auto attributes = list_to_vector(env, jAttributeList);
    debugstore::debug_store_record(eventName.c_str(), attributes);
}

static const JNINativeMethod gDebugStoreMethods[] = {
        /* name, signature, funcPtr */
        {"beginEventNative", "(Ljava/lang/String;Ljava/util/List;)J",
         (void*)com_android_internal_os_DebugStore_beginEvent},
        {"endEventNative", "(JLjava/util/List;)V",
         (void*)com_android_internal_os_DebugStore_endEvent},
        {"recordEventNative", "(Ljava/lang/String;Ljava/util/List;)V",
         (void*)com_android_internal_os_DebugStore_recordEvent},
};

int register_com_android_internal_os_DebugStore(JNIEnv* env) {
    int res = RegisterMethodsOrDie(env, "com/android/internal/os/DebugStore", gDebugStoreMethods,
                                   NELEM(gDebugStoreMethods));
    jclass listClass = FindClassOrDie(env, "java/util/List");
    gListClassInfo.mGet = GetMethodIDOrDie(env, listClass, "get", "(I)Ljava/lang/Object;");
    gListClassInfo.mSize = GetMethodIDOrDie(env, listClass, "size", "()I");

    return res;
}

} // namespace android