/*
 * Copyright (C) 2008 The Android Open Source Project
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

#define LOG_TAG "MemoryFile"
#include <utils/Log.h>

#include <cutils/ashmem.h>
#include <android_runtime/AndroidRuntime.h>
#include "JNIHelp.h"
#include <unistd.h>
#include <sys/mman.h>


namespace android {

static jint android_os_MemoryFile_open(JNIEnv* env, jobject clazz, jstring name, jint length)
{
    const char* namestr = (name ? env->GetStringUTFChars(name, NULL) : NULL);

    // round up length to page boundary
    length = (((length - 1) / getpagesize()) + 1) * getpagesize();
    int result = ashmem_create_region(namestr, length);

    if (name)
        env->ReleaseStringUTFChars(name, namestr);

    if (result < 0)
        jniThrowException(env, "java/io/IOException", "ashmem_create_region failed");
    return result;
}

static jint android_os_MemoryFile_mmap(JNIEnv* env, jobject clazz, jint fd, jint length)
{
    jint result = (jint)mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
    if (!result)
        jniThrowException(env, "java/io/IOException", "mmap failed");
    return result;
}

static void android_os_MemoryFile_close(JNIEnv* env, jobject clazz, jint fd)
{
    close(fd);
}

static jint android_os_MemoryFile_read(JNIEnv* env, jobject clazz,
        jint fd, jint address, jbyteArray buffer, jint srcOffset, jint destOffset,
        jint count, jboolean unpinned)
{
    if (unpinned && ashmem_pin_region(fd, 0, 0) == ASHMEM_WAS_PURGED) {
        ashmem_unpin_region(fd, 0, 0);
        jniThrowException(env, "java/io/IOException", "ashmem region was purged");
        return -1;
    }

    jbyte* bytes = env->GetByteArrayElements(buffer, 0);
    memcpy(bytes + destOffset, (const char *)address + srcOffset, count);
    env->ReleaseByteArrayElements(buffer, bytes, 0);

    if (unpinned) {
        ashmem_unpin_region(fd, 0, 0);
    }
    return count;
}

static jint android_os_MemoryFile_write(JNIEnv* env, jobject clazz,
        jint fd, jint address, jbyteArray buffer, jint srcOffset, jint destOffset,
        jint count, jboolean unpinned)
{
    if (unpinned && ashmem_pin_region(fd, 0, 0) == ASHMEM_WAS_PURGED) {
        ashmem_unpin_region(fd, 0, 0);
        jniThrowException(env, "java/io/IOException", "ashmem region was purged");
        return -1;
    }

    jbyte* bytes = env->GetByteArrayElements(buffer, 0);
    memcpy((char *)address + destOffset, bytes + srcOffset, count);
    env->ReleaseByteArrayElements(buffer, bytes, 0);

    if (unpinned) {
        ashmem_unpin_region(fd, 0, 0);
    }
    return count;
}

static void android_os_MemoryFile_pin(JNIEnv* env, jobject clazz, jint fd, jboolean pin)
{
    int result = (pin ? ashmem_pin_region(fd, 0, 0) : ashmem_unpin_region(fd, 0, 0));
    if (result < 0) {
        jniThrowException(env, "java/io/IOException", NULL);
    }
}

static const JNINativeMethod methods[] = {
	{"native_open",  "(Ljava/lang/String;I)I", (void*)android_os_MemoryFile_open},
    {"native_mmap",  "(II)I", (void*)android_os_MemoryFile_mmap},
    {"native_close", "(I)V", (void*)android_os_MemoryFile_close},
    {"native_read",  "(II[BIIIZ)I", (void*)android_os_MemoryFile_read},
    {"native_write", "(II[BIIIZ)V", (void*)android_os_MemoryFile_write},
    {"native_pin",   "(IZ)V", (void*)android_os_MemoryFile_pin},
};

static const char* const kClassPathName = "android/os/MemoryFile";

int register_android_os_MemoryFile(JNIEnv* env)
{
    jclass clazz;

    clazz = env->FindClass(kClassPathName);
    LOG_FATAL_IF(clazz == NULL, "Unable to find class android.os.FileUtils");

    return AndroidRuntime::registerNativeMethods(
        env, kClassPathName,
        methods, NELEM(methods));
}

}
