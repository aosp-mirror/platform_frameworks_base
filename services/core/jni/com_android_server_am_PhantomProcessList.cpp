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

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <processgroup/processgroup.h>

namespace android {
namespace {

jstring getCgroupProcsPath(JNIEnv* env, jobject clazz, jint uid, jint pid) {
    if (uid < 0) {
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", "uid is negative: %d", uid);
        return nullptr;
    }

    std::string path;
    if (!CgroupGetAttributePathForProcess("CgroupProcs", uid, pid, path)) {
        path.clear();
    }

    return env->NewStringUTF(path.c_str());
}

const JNINativeMethod sMethods[] = {
        {"nativeGetCgroupProcsPath", "(II)Ljava/lang/String;", (void*)getCgroupProcsPath},
};

} // anonymous namespace

int register_android_server_am_PhantomProcessList(JNIEnv* env) {
    const char* className = "com/android/server/am/PhantomProcessList";
    return jniRegisterNativeMethods(env, className, sMethods, NELEM(sMethods));
}

} // namespace android