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
#include <jni.h>
#include <meminfo/sysmeminfo.h>

#include "core_jni_helpers.h"

namespace {
static jclass gProcessDmabufClazz;
static jmethodID gProcessDmabufCtor;
static jclass gProcessGpuMemClazz;
static jmethodID gProcessGpuMemCtor;
} // namespace

namespace android {

static jobject KernelAllocationStats_getDmabufAllocations(JNIEnv *env, jobject, jint pid) {
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
    return env->NewObject(gProcessDmabufClazz, gProcessDmabufCtor, retainedSize, retainedCount,
                          mappedSize, mappedCount);
}

static jobject KernelAllocationStats_getGpuAllocations(JNIEnv *env) {
    std::unordered_map<uint32_t, uint64_t> out;
    meminfo::ReadPerProcessGpuMem(&out);
    jobjectArray result = env->NewObjectArray(out.size(), gProcessGpuMemClazz, nullptr);
    if (result == NULL) {
        jniThrowRuntimeException(env, "Cannot create result array");
        return nullptr;
    }
    int idx = 0;
    for (const auto &entry : out) {
        jobject pidStats =
                env->NewObject(gProcessGpuMemClazz, gProcessGpuMemCtor, entry.first, entry.second);
        env->SetObjectArrayElement(result, idx, pidStats);
        env->DeleteLocalRef(pidStats);
        ++idx;
    }
    return result;
}

static const JNINativeMethod methods[] = {
        {"getDmabufAllocations", "(I)Lcom/android/internal/os/KernelAllocationStats$ProcessDmabuf;",
         (void *)KernelAllocationStats_getDmabufAllocations},
        {"getGpuAllocations", "()[Lcom/android/internal/os/KernelAllocationStats$ProcessGpuMem;",
         (void *)KernelAllocationStats_getGpuAllocations},
};

int register_com_android_internal_os_KernelAllocationStats(JNIEnv *env) {
    int res = RegisterMethodsOrDie(env, "com/android/internal/os/KernelAllocationStats", methods,
                                   NELEM(methods));
    jclass clazz =
            FindClassOrDie(env, "com/android/internal/os/KernelAllocationStats$ProcessDmabuf");
    gProcessDmabufClazz = MakeGlobalRefOrDie(env, clazz);
    gProcessDmabufCtor = GetMethodIDOrDie(env, gProcessDmabufClazz, "<init>", "(IIII)V");

    clazz = FindClassOrDie(env, "com/android/internal/os/KernelAllocationStats$ProcessGpuMem");
    gProcessGpuMemClazz = MakeGlobalRefOrDie(env, clazz);
    gProcessGpuMemCtor = GetMethodIDOrDie(env, gProcessGpuMemClazz, "<init>", "(II)V");
    return res;
}

} // namespace android
