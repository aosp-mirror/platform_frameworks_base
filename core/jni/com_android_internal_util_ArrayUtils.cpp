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

#define LOG_TAG "ArrayUtils"

#include <android-base/logging.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <string.h>
#include <unistd.h>
#include <utils/Log.h>

namespace android {

static size_t GetCacheLineSize() {
    long size = sysconf(_SC_LEVEL1_DCACHE_LINESIZE);
    if (size <= 0) {
        ALOGE("Unable to determine L1 data cache line size. Assuming 32 bytes");
        return 32;
    }
    // The cache line size should always be a power of 2.
    CHECK((size & (size - 1)) == 0);

    return size;
}

static void CleanCacheLineContainingAddress(const uint8_t* p) {
#if defined(__aarch64__)
    // 'dc cvac' stands for "Data Cache line Clean by Virtual Address to point-of-Coherency".
    // It writes the cache line back to the "point-of-coherency", i.e. main memory.
    asm volatile("dc cvac, %0" ::"r"(p));
#elif defined(__i386__) || defined(__x86_64__)
    asm volatile("clflush (%0)" ::"r"(p));
#elif defined(__riscv)
    // This should eventually work, but it is not ready to be enabled yet:
    //  1.) The Android emulator needs to add support for zicbom.
    //  2.) Kernel needs to enable zicbom in usermode.
    //  3.) Android clang needs to add zicbom to the target.
    // asm volatile("cbo.clean (%0)" ::"r"(p));
#elif defined(__arm__)
    // arm32 has a cacheflush() syscall, but it is undocumented and only flushes the icache.
    // It is not the same as cacheflush(2) as documented in the Linux man-pages project.
#else
#error "Unknown architecture"
#endif
}

static void CleanDataCache(const uint8_t* p, size_t buffer_size, size_t cache_line_size) {
    // Clean the first line that overlaps the buffer.
    CleanCacheLineContainingAddress(p);
    // Clean any additional lines that overlap the buffer.  Use cache-line-aligned addresses to
    // ensure that (a) the last cache line gets flushed, and (b) no cache line is flushed twice.
    for (size_t i = cache_line_size - ((uintptr_t)p & (cache_line_size - 1)); i < buffer_size;
         i += cache_line_size) {
        CleanCacheLineContainingAddress(p + i);
    }
}

static void ZeroizePrimitiveArray(JNIEnv* env, jclass clazz, jarray array, size_t component_len) {
    static const size_t cache_line_size = GetCacheLineSize();

    if (array == nullptr) {
        return;
    }

    size_t buffer_size = env->GetArrayLength(array) * component_len;
    if (buffer_size == 0) {
        return;
    }

    // ART guarantees that GetPrimitiveArrayCritical never copies.
    jboolean isCopy;
    void* elems = env->GetPrimitiveArrayCritical(array, &isCopy);
    CHECK(!isCopy);

#ifdef __BIONIC__
    memset_explicit(elems, 0, buffer_size);
#else
    memset(elems, 0, buffer_size);
#endif
    // Clean the data cache so that the data gets zeroized in main memory right away.  Without this,
    // it might not be written to main memory until the cache line happens to be evicted.
    CleanDataCache(static_cast<const uint8_t*>(elems), buffer_size, cache_line_size);

    env->ReleasePrimitiveArrayCritical(array, elems, /* mode= */ 0);
}

static void ZeroizeByteArray(JNIEnv* env, jclass clazz, jbyteArray array) {
    ZeroizePrimitiveArray(env, clazz, array, sizeof(jbyte));
}

static void ZeroizeCharArray(JNIEnv* env, jclass clazz, jcharArray array) {
    ZeroizePrimitiveArray(env, clazz, array, sizeof(jchar));
}

static const JNINativeMethod sMethods[] = {
        {"zeroize", "([B)V", (void*)ZeroizeByteArray},
        {"zeroize", "([C)V", (void*)ZeroizeCharArray},
};

int register_com_android_internal_util_ArrayUtils(JNIEnv* env) {
    return jniRegisterNativeMethods(env, "com/android/internal/util/ArrayUtils", sMethods,
                                    NELEM(sMethods));
}

} // namespace android
