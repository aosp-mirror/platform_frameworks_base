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

#include <JNIHelp.h>
#include <jni.h>
#include <android_runtime/AndroidRuntime.h>

#include <utils/Log.h>

#include <fcntl.h>
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>

#if defined(__arm__)
extern "C" void get_malloc_leak_info(uint8_t** info, size_t* overallSize, 
        size_t* infoSize, size_t* totalMemory, size_t* backtraceSize);
        
extern "C" void free_malloc_leak_info(uint8_t* info);
#endif

#define MAPS_FILE_SIZE 65 * 1024

struct Header {
    size_t mapSize;
    size_t allocSize;
    size_t allocInfoSize;
    size_t totalMemory;
    size_t backtraceSize;
};

namespace android {

/*
 * Retrieve the native heap information and the info from /proc/<self>/maps,
 * copy them into a byte[] with a "struct Header" that holds data offsets,
 * and return the array.
 */
static jbyteArray getLeakInfo(JNIEnv *env, jobject clazz)
{
#if defined(__arm__)
    // get the info in /proc/[pid]/map
    Header header;
    memset(&header, 0, sizeof(header));

    pid_t pid = getpid();

    char path[FILENAME_MAX];
    sprintf(path, "/proc/%d/maps", pid);

    struct stat sb;
    int ret = stat(path, &sb);

    uint8_t* mapsFile = NULL;
    if (ret == 0) {
        mapsFile = (uint8_t*)malloc(MAPS_FILE_SIZE);
        int fd = open(path, O_RDONLY);
    
        if (mapsFile != NULL && fd != -1) {
            int amount = 0;
            do {
                uint8_t* ptr = mapsFile + header.mapSize;
                amount = read(fd, ptr, MAPS_FILE_SIZE);
                if (amount <= 0) {
                    if (errno != EINTR)
                        break; 
                    else
                        continue;
                }
                header.mapSize += amount;
            } while (header.mapSize < MAPS_FILE_SIZE);
            
            ALOGD("**** read %d bytes from '%s'", (int) header.mapSize, path);
        }
    }

    uint8_t* allocBytes;
    get_malloc_leak_info(&allocBytes, &header.allocSize, &header.allocInfoSize, 
            &header.totalMemory, &header.backtraceSize);

    jbyte* bytes = NULL;
    jbyte* ptr = NULL;
    jbyteArray array = env->NewByteArray(sizeof(Header) + header.mapSize + header.allocSize);
    if (array == NULL) {
        goto done;
    }

    bytes = env->GetByteArrayElements(array, NULL);
    ptr = bytes;

//    ALOGD("*** mapSize: %d allocSize: %d allocInfoSize: %d totalMemory: %d", 
//            header.mapSize, header.allocSize, header.allocInfoSize, header.totalMemory);

    memcpy(ptr, &header, sizeof(header));
    ptr += sizeof(header);
    
    if (header.mapSize > 0 && mapsFile != NULL) {
        memcpy(ptr, mapsFile, header.mapSize);
        ptr += header.mapSize;
    }
    
    memcpy(ptr, allocBytes, header.allocSize);
    env->ReleaseByteArrayElements(array, bytes, 0);

done:
    if (mapsFile != NULL) {
        free(mapsFile);
    }
    // free the info up!
    free_malloc_leak_info(allocBytes);

    return array;
#else
    return NULL;
#endif
}

static JNINativeMethod method_table[] = {
    { "getLeakInfo", "()[B", (void*)getLeakInfo },
};

int register_android_ddm_DdmHandleNativeHeap(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env, "android/ddm/DdmHandleNativeHeap", method_table, NELEM(method_table));
}

};
