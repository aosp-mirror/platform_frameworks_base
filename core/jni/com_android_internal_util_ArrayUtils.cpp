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
    return size;
}

#ifdef __aarch64__
static void CleanDataCache(const uint8_t* p, size_t size, size_t cache_line_size) {
    // Execute 'dc cvac' at least once on each cache line in the memory region.
    //
    // 'dc cvac' stands for "Data Cache line Clean by Virtual Address to point-of-Coherency".
    // It writes the cache line back to the "point-of-coherency", i.e. main memory.
    //
    // Since the memory region is not guaranteed to be cache-line-aligned, we use an "extra"
    // instruction after the loop to make sure the last cache line gets covered.
    for (size_t i = 0; i < size; i += cache_line_size) {
        asm volatile("dc cvac, %0" ::"r"(p + i));
    }
    asm volatile("dc cvac, %0" ::"r"(p + size - 1));
}
#elif defined(__i386__) || defined(__x86_64__)
static void CleanDataCache(const uint8_t* p, size_t size, size_t cache_line_size) {
    for (size_t i = 0; i < size; i += cache_line_size) {
        asm volatile("clflush (%0)" ::"r"(p + i));
    }
    asm volatile("clflush (%0)" ::"r"(p + size - 1));
}
#elif defined(__riscv)
static void CleanDataCache(const uint8_t* p, size_t size, size_t cache_line_size) {
    // This should eventually work, but it is not ready to be enabled yet:
    //  1.) The Android emulator needs to add support for zicbom.
    //  2.) Kernel needs to enable zicbom in usermode.
    //  3.) Android clang needs to add zicbom to the target.
#if 0
    for (size_t i = 0; i < size; i += cache_line_size) {
        asm volatile("cbo.clean (%0)" ::"r"(p + i));
    }
    asm volatile("cbo.clean (%0)" ::"r"(p + size - 1));
#endif
}
#elif defined(__arm__)
// arm32 has a cacheflush() syscall, but it is undocumented and only flushes the icache.
// It is not the same as cacheflush(2) as documented in the Linux man-pages project.
static void CleanDataCache(const uint8_t* p, size_t size, size_t cache_line_size) {}
#else
#error "Unknown architecture"
#endif

static void ZeroizePrimitiveArray(JNIEnv* env, jclass clazz, jarray array, size_t component_len) {
    static const size_t cache_line_size = GetCacheLineSize();

    size_t size = env->GetArrayLength(array) * component_len;
    if (size == 0) {
        return;
    }

    // ART guarantees that GetPrimitiveArrayCritical never copies.
    jboolean isCopy;
    void* elems = env->GetPrimitiveArrayCritical(array, &isCopy);
    CHECK(!isCopy);

#ifdef __BIONIC__
    memset_explicit(elems, 0, size);
#else
    memset(elems, 0, size);
#endif
    // Clean the data cache so that the data gets zeroized in main memory right away.  Without this,
    // it might not be written to main memory until the cache line happens to be evicted.
    CleanDataCache(static_cast<const uint8_t*>(elems), size, cache_line_size);

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
