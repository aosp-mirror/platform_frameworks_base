/* //device/libs/android_runtime/android_ddm_DdmHandleNativeHeap.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#undef LOG_TAG
#define LOG_TAG "DdmHandleNativeHeap"

#include <nativehelper/JNIHelp.h>
#include <jni.h>
#include "core_jni_helpers.h"

#include <utils/Log.h>
#include <utils/String8.h>

#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>

extern "C" void get_malloc_leak_info(uint8_t** info, size_t* overallSize,
                                     size_t* infoSize, size_t* totalMemory, size_t* backtraceSize);

extern "C" void free_malloc_leak_info(uint8_t* info);

#define DDMS_HEADER_SIGNATURE 0x812345dd
#define DDMS_VERSION 2

struct Header {
#if defined(__LP64__)
    uint32_t signature;
    uint16_t version;
    uint16_t pointerSize;
#endif
    size_t mapSize;
    size_t allocSize;
    size_t allocInfoSize;
    size_t totalMemory;
    size_t backtraceSize;
};

namespace android {

static void ReadFile(const char* path, String8& s) {
    int fd = open(path, O_RDONLY);
    if (fd != -1) {
        char bytes[1024];
        ssize_t byteCount;
        while ((byteCount = TEMP_FAILURE_RETRY(read(fd, bytes, sizeof(bytes)))) > 0) {
            s.append(bytes, byteCount);
        }
        close(fd);
    }
}

/*
 * Retrieve the native heap information and the info from /proc/self/maps,
 * copy them into a byte[] with a "struct Header" that holds data offsets,
 * and return the array.
 */
static jbyteArray DdmHandleNativeHeap_getLeakInfo(JNIEnv* env, jobject) {
    Header header;
    memset(&header, 0, sizeof(header));

    String8 maps;
    ReadFile("/proc/self/maps", maps);
    header.mapSize = maps.size();

    uint8_t* allocBytes;
    get_malloc_leak_info(&allocBytes, &header.allocSize, &header.allocInfoSize,
                         &header.totalMemory, &header.backtraceSize);

    ALOGD("*** mapSize: %zu allocSize: %zu allocInfoSize: %zu totalMemory: %zu",
          header.mapSize, header.allocSize, header.allocInfoSize, header.totalMemory);

#if defined(__LP64__)
    header.signature = DDMS_HEADER_SIGNATURE;
    header.version = DDMS_VERSION;
    header.pointerSize = sizeof(void*);
#endif

    jbyteArray array = env->NewByteArray(sizeof(Header) + header.mapSize + header.allocSize);
    if (array != NULL) {
        env->SetByteArrayRegion(array, 0,
                                sizeof(header), reinterpret_cast<jbyte*>(&header));
        env->SetByteArrayRegion(array, sizeof(header),
                                maps.size(), reinterpret_cast<const jbyte*>(maps.string()));
        env->SetByteArrayRegion(array, sizeof(header) + maps.size(),
                                header.allocSize, reinterpret_cast<jbyte*>(allocBytes));
    }

    free_malloc_leak_info(allocBytes);
    return array;
}

static const JNINativeMethod method_table[] = {
    { "getLeakInfo", "()[B", (void*) DdmHandleNativeHeap_getLeakInfo },
};

int register_android_ddm_DdmHandleNativeHeap(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/ddm/DdmHandleNativeHeap", method_table,
                                NELEM(method_table));
}

};
