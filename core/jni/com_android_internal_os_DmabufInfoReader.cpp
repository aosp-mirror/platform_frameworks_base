/*
 * Copyright (C) 2021 The Android Open Source Project
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

#include <dmabufinfo/dmabufinfo.h>
#include "core_jni_helpers.h"

namespace android {

static jobject DmabufInfoReader_getProcessStats(JNIEnv *env, jobject, jint pid) {
    std::vector<dmabufinfo::DmaBuffer> buffers;
    if (!dmabufinfo::ReadDmaBufMapRefs(pid, &buffers)) {
        return nullptr;
    }
    jint mappedSize = 0;
    jint mappedCount = buffers.size();
    for (const auto &buffer : buffers) {
        mappedSize += buffer.size();
    }
    mappedSize /= 1024;

    jint retainedSize = -1;
    jint retainedCount = -1;
    if (dmabufinfo::ReadDmaBufFdRefs(pid, &buffers)) {
        retainedCount = buffers.size();
        retainedSize = 0;
        for (const auto &buffer : buffers) {
            retainedSize += buffer.size();
        }
        retainedSize /= 1024;
    }

    jclass clazz = FindClassOrDie(env, "com/android/internal/os/DmabufInfoReader$ProcessDmabuf");
    jmethodID constructID = GetMethodIDOrDie(env, clazz, "<init>", "(IIII)V");
    return env->NewObject(clazz, constructID, retainedSize, retainedCount, mappedSize, mappedCount);
}

static const JNINativeMethod methods[] = {
        {"getProcessStats", "(I)Lcom/android/internal/os/DmabufInfoReader$ProcessDmabuf;",
         (void *)DmabufInfoReader_getProcessStats},
};

int register_com_android_internal_os_DmabufInfoReader(JNIEnv *env) {
    return RegisterMethodsOrDie(env, "com/android/internal/os/DmabufInfoReader", methods,
                                NELEM(methods));
}

} // namespace android
