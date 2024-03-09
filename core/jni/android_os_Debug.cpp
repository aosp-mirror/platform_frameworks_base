/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "android.os.Debug"

#include <assert.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <malloc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

#include <iomanip>
#include <string>
#include <vector>

#include <android-base/logging.h>
#include <android-base/properties.h>
#include <bionic/malloc.h>
#include <debuggerd/client.h>
#include <log/log.h>
#include <utils/misc.h>
#include <utils/String8.h>

#include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/ScopedUtfChars.h>
#include "jni.h"
#include <dmabufinfo/dmabuf_sysfs_stats.h>
#include <dmabufinfo/dmabufinfo.h>
#include <meminfo/procmeminfo.h>
#include <meminfo/sysmeminfo.h>
#include <memtrack/memtrack.h>
#include <memunreachable/memunreachable.h>
#include <android-base/strings.h>
#include "android_os_Debug.h"
#include <vintf/KernelConfigs.h>

namespace android
{

enum {
    HEAP_UNKNOWN,
    HEAP_DALVIK,
    HEAP_NATIVE,

    HEAP_DALVIK_OTHER,
    HEAP_STACK,
    HEAP_CURSOR,
    HEAP_ASHMEM,
    HEAP_GL_DEV,
    HEAP_UNKNOWN_DEV,
    HEAP_SO,
    HEAP_JAR,
    HEAP_APK,
    HEAP_TTF,
    HEAP_DEX,
    HEAP_OAT,
    HEAP_ART,
    HEAP_UNKNOWN_MAP,
    HEAP_GRAPHICS,
    HEAP_GL,
    HEAP_OTHER_MEMTRACK,

    // Dalvik extra sections (heap).
    HEAP_DALVIK_NORMAL,
    HEAP_DALVIK_LARGE,
    HEAP_DALVIK_ZYGOTE,
    HEAP_DALVIK_NON_MOVING,

    // Dalvik other extra sections.
    HEAP_DALVIK_OTHER_LINEARALLOC,
    HEAP_DALVIK_OTHER_ACCOUNTING,
    HEAP_DALVIK_OTHER_ZYGOTE_CODE_CACHE,
    HEAP_DALVIK_OTHER_APP_CODE_CACHE,
    HEAP_DALVIK_OTHER_COMPILER_METADATA,
    HEAP_DALVIK_OTHER_INDIRECT_REFERENCE_TABLE,

    // Boot vdex / app dex / app vdex
    HEAP_DEX_BOOT_VDEX,
    HEAP_DEX_APP_DEX,
    HEAP_DEX_APP_VDEX,

    // App art, boot art.
    HEAP_ART_APP,
    HEAP_ART_BOOT,

    _NUM_HEAP,
    _NUM_EXCLUSIVE_HEAP = HEAP_OTHER_MEMTRACK+1,
    _NUM_CORE_HEAP = HEAP_NATIVE+1
};

struct stat_fields {
    jfieldID pss_field;
    jfieldID pssSwappable_field;
    jfieldID rss_field;
    jfieldID privateDirty_field;
    jfieldID sharedDirty_field;
    jfieldID privateClean_field;
    jfieldID sharedClean_field;
    jfieldID swappedOut_field;
    jfieldID swappedOutPss_field;
};

struct stat_field_names {
    const char* pss_name;
    const char* pssSwappable_name;
    const char* rss_name;
    const char* privateDirty_name;
    const char* sharedDirty_name;
    const char* privateClean_name;
    const char* sharedClean_name;
    const char* swappedOut_name;
    const char* swappedOutPss_name;
};

static stat_fields stat_fields[_NUM_CORE_HEAP];

static stat_field_names stat_field_names[_NUM_CORE_HEAP] = {
    { "otherPss", "otherSwappablePss", "otherRss", "otherPrivateDirty", "otherSharedDirty",
        "otherPrivateClean", "otherSharedClean", "otherSwappedOut", "otherSwappedOutPss" },
    { "dalvikPss", "dalvikSwappablePss", "dalvikRss", "dalvikPrivateDirty", "dalvikSharedDirty",
        "dalvikPrivateClean", "dalvikSharedClean", "dalvikSwappedOut", "dalvikSwappedOutPss" },
    { "nativePss", "nativeSwappablePss", "nativeRss", "nativePrivateDirty", "nativeSharedDirty",
        "nativePrivateClean", "nativeSharedClean", "nativeSwappedOut", "nativeSwappedOutPss" }
};

static jfieldID otherStats_field;
static jfieldID hasSwappedOutPss_field;

struct stats_t {
    int pss;
    int swappablePss;
    int rss;
    int privateDirty;
    int sharedDirty;
    int privateClean;
    int sharedClean;
    int swappedOut;
    int swappedOutPss;
};

#define BINDER_STATS "/proc/binder/stats"

static jlong android_os_Debug_getNativeHeapSize(JNIEnv *env, jobject clazz)
{
    struct mallinfo info = mallinfo();
    return (jlong) info.usmblks;
}

static jlong android_os_Debug_getNativeHeapAllocatedSize(JNIEnv *env, jobject clazz)
{
    struct mallinfo info = mallinfo();
    return (jlong) info.uordblks;
}

static jlong android_os_Debug_getNativeHeapFreeSize(JNIEnv *env, jobject clazz)
{
    struct mallinfo info = mallinfo();
    return (jlong) info.fordblks;
}

// Container used to retrieve graphics memory pss
struct graphics_memory_pss
{
    int graphics;
    int gl;
    int other;
};

/*
 * Uses libmemtrack to retrieve graphics memory that the process is using.
 * Any graphics memory reported in /proc/pid/smaps is not included here.
 */
static int read_memtrack_memory(struct memtrack_proc* p, int pid,
        struct graphics_memory_pss* graphics_mem)
{
    int err = memtrack_proc_get(p, pid);
    if (err != 0) {
        // The memtrack HAL may not be available, do not log to avoid flooding
        // logcat.
        return err;
    }

    ssize_t pss = memtrack_proc_graphics_pss(p);
    if (pss < 0) {
        ALOGW("failed to get graphics pss: %zd", pss);
        return pss;
    }
    graphics_mem->graphics = pss / 1024;

    pss = memtrack_proc_gl_pss(p);
    if (pss < 0) {
        ALOGW("failed to get gl pss: %zd", pss);
        return pss;
    }
    graphics_mem->gl = pss / 1024;

    pss = memtrack_proc_other_pss(p);
    if (pss < 0) {
        ALOGW("failed to get other pss: %zd", pss);
        return pss;
    }
    graphics_mem->other = pss / 1024;

    return 0;
}

/*
 * Retrieves the graphics memory that is unaccounted for in /proc/pid/smaps.
 */
static int read_memtrack_memory(int pid, struct graphics_memory_pss* graphics_mem)
{
    struct memtrack_proc* p = memtrack_proc_new();
    if (p == NULL) {
        ALOGW("failed to create memtrack_proc");
        return -1;
    }

    int err = read_memtrack_memory(p, pid, graphics_mem);
    memtrack_proc_destroy(p);
    return err;
}

static bool load_maps(int pid, stats_t* stats, bool* foundSwapPss)
{
    *foundSwapPss = false;
    uint64_t prev_end = 0;
    int prev_heap = HEAP_UNKNOWN;

    std::string smaps_path = base::StringPrintf("/proc/%d/smaps", pid);
    auto vma_scan = [&](const meminfo::Vma& vma) {
        int which_heap = HEAP_UNKNOWN;
        int sub_heap = HEAP_UNKNOWN;
        bool is_swappable = false;
        std::string name;
        if (base::EndsWith(vma.name, " (deleted)")) {
            name = vma.name.substr(0, vma.name.size() - strlen(" (deleted)"));
        } else {
            name = vma.name;
        }

        uint32_t namesz = name.size();
        if (base::StartsWith(name, "[heap]")) {
            which_heap = HEAP_NATIVE;
        } else if (base::StartsWith(name, "[anon:libc_malloc]")) {
            which_heap = HEAP_NATIVE;
        } else if (base::StartsWith(name, "[anon:scudo:")) {
            which_heap = HEAP_NATIVE;
        } else if (base::StartsWith(name, "[anon:GWP-ASan")) {
            which_heap = HEAP_NATIVE;
        } else if (base::StartsWith(name, "[stack")) {
            which_heap = HEAP_STACK;
        } else if (base::StartsWith(name, "[anon:stack_and_tls:")) {
            which_heap = HEAP_STACK;
        } else if (base::EndsWith(name, ".so")) {
            which_heap = HEAP_SO;
            is_swappable = true;
        } else if (base::EndsWith(name, ".jar")) {
            which_heap = HEAP_JAR;
            is_swappable = true;
        } else if (base::EndsWith(name, ".apk")) {
            which_heap = HEAP_APK;
            is_swappable = true;
        } else if (base::EndsWith(name, ".ttf")) {
            which_heap = HEAP_TTF;
            is_swappable = true;
        } else if ((base::EndsWith(name, ".odex")) ||
                (namesz > 4 && strstr(name.c_str(), ".dex") != nullptr)) {
            which_heap = HEAP_DEX;
            sub_heap = HEAP_DEX_APP_DEX;
            is_swappable = true;
        } else if (base::EndsWith(name, ".vdex")) {
            which_heap = HEAP_DEX;
            // Handle system@framework@boot and system/framework/boot|apex
            if ((strstr(name.c_str(), "@boot") != nullptr) ||
                    (strstr(name.c_str(), "/boot") != nullptr) ||
                    (strstr(name.c_str(), "/apex") != nullptr)) {
                sub_heap = HEAP_DEX_BOOT_VDEX;
            } else {
                sub_heap = HEAP_DEX_APP_VDEX;
            }
            is_swappable = true;
        } else if (base::EndsWith(name, ".oat")) {
            which_heap = HEAP_OAT;
            is_swappable = true;
        } else if (base::EndsWith(name, ".art") || base::EndsWith(name, ".art]")) {
            which_heap = HEAP_ART;
            // Handle system@framework@boot* and system/framework/boot|apex*
            if ((strstr(name.c_str(), "@boot") != nullptr) ||
                    (strstr(name.c_str(), "/boot") != nullptr) ||
                    (strstr(name.c_str(), "/apex") != nullptr)) {
                sub_heap = HEAP_ART_BOOT;
            } else {
                sub_heap = HEAP_ART_APP;
            }
            is_swappable = true;
        } else if (base::StartsWith(name, "/dev/")) {
            which_heap = HEAP_UNKNOWN_DEV;
            if (base::StartsWith(name, "/dev/kgsl-3d0")) {
                which_heap = HEAP_GL_DEV;
            } else if (base::StartsWith(name, "/dev/ashmem/CursorWindow")) {
                which_heap = HEAP_CURSOR;
            } else if (base::StartsWith(name, "/dev/ashmem/jit-zygote-cache")) {
                which_heap = HEAP_DALVIK_OTHER;
                sub_heap = HEAP_DALVIK_OTHER_ZYGOTE_CODE_CACHE;
            } else if (base::StartsWith(name, "/dev/ashmem")) {
                which_heap = HEAP_ASHMEM;
            }
        } else if (base::StartsWith(name, "/memfd:jit-cache")) {
          which_heap = HEAP_DALVIK_OTHER;
          sub_heap = HEAP_DALVIK_OTHER_APP_CODE_CACHE;
        } else if (base::StartsWith(name, "/memfd:jit-zygote-cache")) {
          which_heap = HEAP_DALVIK_OTHER;
          sub_heap = HEAP_DALVIK_OTHER_ZYGOTE_CODE_CACHE;
        } else if (base::StartsWith(name, "[anon:")) {
            which_heap = HEAP_UNKNOWN;
            if (base::StartsWith(name, "[anon:dalvik-")) {
                which_heap = HEAP_DALVIK_OTHER;
                if (base::StartsWith(name, "[anon:dalvik-LinearAlloc")) {
                    sub_heap = HEAP_DALVIK_OTHER_LINEARALLOC;
                } else if (base::StartsWith(name, "[anon:dalvik-alloc space") ||
                        base::StartsWith(name, "[anon:dalvik-main space")) {
                    // This is the regular Dalvik heap.
                    which_heap = HEAP_DALVIK;
                    sub_heap = HEAP_DALVIK_NORMAL;
                } else if (base::StartsWith(name,
                            "[anon:dalvik-large object space") ||
                        base::StartsWith(
                            name, "[anon:dalvik-free list large object space")) {
                    which_heap = HEAP_DALVIK;
                    sub_heap = HEAP_DALVIK_LARGE;
                } else if (base::StartsWith(name, "[anon:dalvik-non moving space")) {
                    which_heap = HEAP_DALVIK;
                    sub_heap = HEAP_DALVIK_NON_MOVING;
                } else if (base::StartsWith(name, "[anon:dalvik-zygote space")) {
                    which_heap = HEAP_DALVIK;
                    sub_heap = HEAP_DALVIK_ZYGOTE;
                } else if (base::StartsWith(name, "[anon:dalvik-indirect ref")) {
                    sub_heap = HEAP_DALVIK_OTHER_INDIRECT_REFERENCE_TABLE;
                } else if (base::StartsWith(name, "[anon:dalvik-jit-code-cache") ||
                        base::StartsWith(name, "[anon:dalvik-data-code-cache")) {
                    sub_heap = HEAP_DALVIK_OTHER_APP_CODE_CACHE;
                } else if (base::StartsWith(name, "[anon:dalvik-CompilerMetadata")) {
                    sub_heap = HEAP_DALVIK_OTHER_COMPILER_METADATA;
                } else {
                    sub_heap = HEAP_DALVIK_OTHER_ACCOUNTING;  // Default to accounting.
                }
            }
        } else if (namesz > 0) {
            which_heap = HEAP_UNKNOWN_MAP;
        } else if (vma.start == prev_end && prev_heap == HEAP_SO) {
            // bss section of a shared library
            which_heap = HEAP_SO;
        }

        prev_end = vma.end;
        prev_heap = which_heap;

        const meminfo::MemUsage& usage = vma.usage;
        if (usage.swap_pss > 0 && *foundSwapPss != true) {
            *foundSwapPss = true;
        }

        uint64_t swapable_pss = 0;
        if (is_swappable && (usage.pss > 0)) {
            float sharing_proportion = 0.0;
            if ((usage.shared_clean > 0) || (usage.shared_dirty > 0)) {
                sharing_proportion = (usage.pss - usage.uss) / (usage.shared_clean + usage.shared_dirty);
            }
            swapable_pss = (sharing_proportion * usage.shared_clean) + usage.private_clean;
        }

        stats[which_heap].pss += usage.pss;
        stats[which_heap].swappablePss += swapable_pss;
        stats[which_heap].rss += usage.rss;
        stats[which_heap].privateDirty += usage.private_dirty;
        stats[which_heap].sharedDirty += usage.shared_dirty;
        stats[which_heap].privateClean += usage.private_clean;
        stats[which_heap].sharedClean += usage.shared_clean;
        stats[which_heap].swappedOut += usage.swap;
        stats[which_heap].swappedOutPss += usage.swap_pss;
        if (which_heap == HEAP_DALVIK || which_heap == HEAP_DALVIK_OTHER ||
                which_heap == HEAP_DEX || which_heap == HEAP_ART) {
            stats[sub_heap].pss += usage.pss;
            stats[sub_heap].swappablePss += swapable_pss;
            stats[sub_heap].rss += usage.rss;
            stats[sub_heap].privateDirty += usage.private_dirty;
            stats[sub_heap].sharedDirty += usage.shared_dirty;
            stats[sub_heap].privateClean += usage.private_clean;
            stats[sub_heap].sharedClean += usage.shared_clean;
            stats[sub_heap].swappedOut += usage.swap;
            stats[sub_heap].swappedOutPss += usage.swap_pss;
        }
        return true;
    };

    return meminfo::ForEachVmaFromFile(smaps_path, vma_scan);
}

static jboolean android_os_Debug_getDirtyPagesPid(JNIEnv *env, jobject clazz,
        jint pid, jobject object)
{
    bool foundSwapPss;
    stats_t stats[_NUM_HEAP];
    memset(&stats, 0, sizeof(stats));

    if (!load_maps(pid, stats, &foundSwapPss)) {
        return JNI_FALSE;
    }

    struct graphics_memory_pss graphics_mem;
    if (read_memtrack_memory(pid, &graphics_mem) == 0) {
        stats[HEAP_GRAPHICS].pss = graphics_mem.graphics;
        stats[HEAP_GRAPHICS].privateDirty = graphics_mem.graphics;
        stats[HEAP_GRAPHICS].rss = graphics_mem.graphics;
        stats[HEAP_GL].pss = graphics_mem.gl;
        stats[HEAP_GL].privateDirty = graphics_mem.gl;
        stats[HEAP_GL].rss = graphics_mem.gl;
        stats[HEAP_OTHER_MEMTRACK].pss = graphics_mem.other;
        stats[HEAP_OTHER_MEMTRACK].privateDirty = graphics_mem.other;
        stats[HEAP_OTHER_MEMTRACK].rss = graphics_mem.other;
    }

    for (int i=_NUM_CORE_HEAP; i<_NUM_EXCLUSIVE_HEAP; i++) {
        stats[HEAP_UNKNOWN].pss += stats[i].pss;
        stats[HEAP_UNKNOWN].swappablePss += stats[i].swappablePss;
        stats[HEAP_UNKNOWN].rss += stats[i].rss;
        stats[HEAP_UNKNOWN].privateDirty += stats[i].privateDirty;
        stats[HEAP_UNKNOWN].sharedDirty += stats[i].sharedDirty;
        stats[HEAP_UNKNOWN].privateClean += stats[i].privateClean;
        stats[HEAP_UNKNOWN].sharedClean += stats[i].sharedClean;
        stats[HEAP_UNKNOWN].swappedOut += stats[i].swappedOut;
        stats[HEAP_UNKNOWN].swappedOutPss += stats[i].swappedOutPss;
    }

    for (int i=0; i<_NUM_CORE_HEAP; i++) {
        env->SetIntField(object, stat_fields[i].pss_field, stats[i].pss);
        env->SetIntField(object, stat_fields[i].pssSwappable_field, stats[i].swappablePss);
        env->SetIntField(object, stat_fields[i].rss_field, stats[i].rss);
        env->SetIntField(object, stat_fields[i].privateDirty_field, stats[i].privateDirty);
        env->SetIntField(object, stat_fields[i].sharedDirty_field, stats[i].sharedDirty);
        env->SetIntField(object, stat_fields[i].privateClean_field, stats[i].privateClean);
        env->SetIntField(object, stat_fields[i].sharedClean_field, stats[i].sharedClean);
        env->SetIntField(object, stat_fields[i].swappedOut_field, stats[i].swappedOut);
        env->SetIntField(object, stat_fields[i].swappedOutPss_field, stats[i].swappedOutPss);
    }


    env->SetBooleanField(object, hasSwappedOutPss_field, foundSwapPss);
    jintArray otherIntArray = (jintArray)env->GetObjectField(object, otherStats_field);

    jint* otherArray = (jint*)env->GetPrimitiveArrayCritical(otherIntArray, 0);
    if (otherArray == NULL) {
        return JNI_FALSE;
    }

    int j=0;
    for (int i=_NUM_CORE_HEAP; i<_NUM_HEAP; i++) {
        otherArray[j++] = stats[i].pss;
        otherArray[j++] = stats[i].swappablePss;
        otherArray[j++] = stats[i].rss;
        otherArray[j++] = stats[i].privateDirty;
        otherArray[j++] = stats[i].sharedDirty;
        otherArray[j++] = stats[i].privateClean;
        otherArray[j++] = stats[i].sharedClean;
        otherArray[j++] = stats[i].swappedOut;
        otherArray[j++] = stats[i].swappedOutPss;
    }

    env->ReleasePrimitiveArrayCritical(otherIntArray, otherArray, 0);
    return JNI_TRUE;
}

static void android_os_Debug_getDirtyPages(JNIEnv *env, jobject clazz, jobject object)
{
    android_os_Debug_getDirtyPagesPid(env, clazz, getpid(), object);
}

static jlong android_os_Debug_getPssPid(JNIEnv *env, jobject clazz, jint pid,
        jlongArray outUssSwapPssRss, jlongArray outMemtrack)
{
    jlong pss = 0;
    jlong rss = 0;
    jlong swapPss = 0;
    jlong uss = 0;
    jlong memtrack = 0;

    struct graphics_memory_pss graphics_mem;
    if (read_memtrack_memory(pid, &graphics_mem) == 0) {
        pss = uss = rss = memtrack = graphics_mem.graphics + graphics_mem.gl + graphics_mem.other;
    }

    ::android::meminfo::ProcMemInfo proc_mem(pid);
    ::android::meminfo::MemUsage stats;
    if (proc_mem.SmapsOrRollup(&stats)) {
        pss += stats.pss;
        uss += stats.uss;
        rss += stats.rss;
        swapPss = stats.swap_pss;
        pss += swapPss; // Also in swap, those pages would be accounted as Pss without SWAP
    } else {
        return 0;
    }

    if (outUssSwapPssRss != NULL) {
        int outLen = env->GetArrayLength(outUssSwapPssRss);
        if (outLen >= 1) {
            jlong* outUssSwapPssRssArray = env->GetLongArrayElements(outUssSwapPssRss, 0);
            if (outUssSwapPssRssArray != NULL) {
                outUssSwapPssRssArray[0] = uss;
                if (outLen >= 2) {
                    outUssSwapPssRssArray[1] = swapPss;
                }
                if (outLen >= 3) {
                    outUssSwapPssRssArray[2] = rss;
                }
            }
            env->ReleaseLongArrayElements(outUssSwapPssRss, outUssSwapPssRssArray, 0);
        }
    }

    if (outMemtrack != NULL) {
        int outLen = env->GetArrayLength(outMemtrack);
        if (outLen >= 1) {
            jlong* outMemtrackArray = env->GetLongArrayElements(outMemtrack, 0);
            if (outMemtrackArray != NULL) {
                outMemtrackArray[0] = memtrack;
                if (outLen >= 2) {
                    outMemtrackArray[1] = graphics_mem.graphics;
                }
                if (outLen >= 3) {
                    outMemtrackArray[2] = graphics_mem.gl;
                }
                if (outLen >= 4) {
                    outMemtrackArray[3] = graphics_mem.other;
                }
            }
            env->ReleaseLongArrayElements(outMemtrack, outMemtrackArray, 0);
        }
    }

    return pss;
}

static jlong android_os_Debug_getPss(JNIEnv *env, jobject clazz)
{
    return android_os_Debug_getPssPid(env, clazz, getpid(), NULL, NULL);
}

static jlong android_os_Debug_getRssPid(JNIEnv* env, jobject clazz, jint pid,
                                        jlongArray outMemtrack) {
    jlong rss = 0;
    jlong memtrack = 0;

    struct graphics_memory_pss graphics_mem;
    if (read_memtrack_memory(pid, &graphics_mem) == 0) {
        rss = memtrack = graphics_mem.graphics + graphics_mem.gl + graphics_mem.other;
    }

    ::android::meminfo::ProcMemInfo proc_mem(pid);
    uint64_t status_rss;
    if (proc_mem.StatusVmRSS(&status_rss)) {
        rss += status_rss;
    } else {
        return 0;
    }

    if (outMemtrack != NULL) {
        int outLen = env->GetArrayLength(outMemtrack);
        if (outLen >= 1) {
            jlong* outMemtrackArray = env->GetLongArrayElements(outMemtrack, 0);
            if (outMemtrackArray != NULL) {
                outMemtrackArray[0] = memtrack;
                if (outLen >= 2) {
                    outMemtrackArray[1] = graphics_mem.graphics;
                }
                if (outLen >= 3) {
                    outMemtrackArray[2] = graphics_mem.gl;
                }
                if (outLen >= 4) {
                    outMemtrackArray[3] = graphics_mem.other;
                }
            }
            env->ReleaseLongArrayElements(outMemtrack, outMemtrackArray, 0);
        }
    }

    return rss;
}

static jlong android_os_Debug_getRss(JNIEnv* env, jobject clazz) {
    return android_os_Debug_getRssPid(env, clazz, getpid(), NULL);
}

// The 1:1 mapping of MEMINFO_* enums here must match with the constants from
// Debug.java.
enum {
    MEMINFO_TOTAL,
    MEMINFO_FREE,
    MEMINFO_BUFFERS,
    MEMINFO_CACHED,
    MEMINFO_SHMEM,
    MEMINFO_SLAB,
    MEMINFO_SLAB_RECLAIMABLE,
    MEMINFO_SLAB_UNRECLAIMABLE,
    MEMINFO_SWAP_TOTAL,
    MEMINFO_SWAP_FREE,
    MEMINFO_ZRAM_TOTAL,
    MEMINFO_MAPPED,
    MEMINFO_VMALLOC_USED,
    MEMINFO_PAGE_TABLES,
    MEMINFO_KERNEL_STACK,
    MEMINFO_KERNEL_RECLAIMABLE,
    MEMINFO_ACTIVE,
    MEMINFO_INACTIVE,
    MEMINFO_UNEVICTABLE,
    MEMINFO_AVAILABLE,
    MEMINFO_ACTIVE_ANON,
    MEMINFO_INACTIVE_ANON,
    MEMINFO_ACTIVE_FILE,
    MEMINFO_INACTIVE_FILE,
    MEMINFO_CMA_TOTAL,
    MEMINFO_CMA_FREE,
    MEMINFO_COUNT
};

static void android_os_Debug_getMemInfo(JNIEnv *env, jobject clazz, jlongArray out)
{
    if (out == NULL) {
        jniThrowNullPointerException(env, "out == null");
        return;
    }

    int outLen = env->GetArrayLength(out);
    if (outLen < MEMINFO_COUNT) {
        jniThrowRuntimeException(env, "outLen < MEMINFO_COUNT");
        return;
    }

    // Read system memory info including ZRAM. The values are stored in the vector
    // in the same order as MEMINFO_* enum
    std::vector<std::string_view> tags(
        ::android::meminfo::SysMemInfo::kDefaultSysMemInfoTags.begin(),
        ::android::meminfo::SysMemInfo::kDefaultSysMemInfoTags.end());
    tags.insert(tags.begin() + MEMINFO_ZRAM_TOTAL, "Zram:");
    std::vector<uint64_t> mem(tags.size());
    ::android::meminfo::SysMemInfo smi;
    if (!smi.ReadMemInfo(tags.size(), tags.data(), mem.data())) {
        jniThrowRuntimeException(env, "SysMemInfo read failed");
        return;
    }

    jlong* outArray = env->GetLongArrayElements(out, 0);
    if (outArray != NULL) {
        outLen = MEMINFO_COUNT;
        for (int i = 0; i < outLen; i++) {
            if (i == MEMINFO_VMALLOC_USED && mem[i] == 0) {
                outArray[i] = smi.ReadVmallocInfo() / 1024;
                continue;
            }
            outArray[i] = mem[i];
        }
    }

    env->ReleaseLongArrayElements(out, outArray, 0);
}

static jint read_binder_stat(const char* stat)
{
    UniqueFile fp = MakeUniqueFile(BINDER_STATS, "re");
    if (fp == nullptr) {
        return -1;
    }

    char line[1024];

    char compare[128];
    int len = snprintf(compare, 128, "proc %d", getpid());

    // loop until we have the block that represents this process
    do {
        if (fgets(line, 1024, fp.get()) == 0) {
            return -1;
        }
    } while (strncmp(compare, line, len));

    // now that we have this process, read until we find the stat that we are looking for
    len = snprintf(compare, 128, "  %s: ", stat);

    do {
        if (fgets(line, 1024, fp.get()) == 0) {
            return -1;
        }
    } while (strncmp(compare, line, len));

    // we have the line, now increment the line ptr to the value
    char* ptr = line + len;
    jint result = atoi(ptr);
    return result;
}

static jint android_os_Debug_getBinderSentTransactions(JNIEnv *env, jobject clazz)
{
    return read_binder_stat("bcTRANSACTION");
}

static jint android_os_getBinderReceivedTransactions(JNIEnv *env, jobject clazz)
{
    return read_binder_stat("brTRANSACTION");
}

// these are implemented in android_util_Binder.cpp
jint android_os_Debug_getLocalObjectCount(JNIEnv* env, jobject clazz);
jint android_os_Debug_getProxyObjectCount(JNIEnv* env, jobject clazz);
jint android_os_Debug_getDeathObjectCount(JNIEnv* env, jobject clazz);

static bool openFile(JNIEnv* env, jobject fileDescriptor, UniqueFile& fp)
{
    if (fileDescriptor == NULL) {
        jniThrowNullPointerException(env, "fd == null");
        return false;
    }
    int origFd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (origFd < 0) {
        jniThrowRuntimeException(env, "Invalid file descriptor");
        return false;
    }

    /* dup() the descriptor so we don't close the original with fclose() */
    int fd = fcntl(origFd, F_DUPFD_CLOEXEC, 0);
    if (fd < 0) {
        ALOGW("dup(%d) failed: %s\n", origFd, strerror(errno));
        jniThrowRuntimeException(env, "dup() failed");
        return false;
    }

    fp.reset(fdopen(fd, "w"));
    if (fp == nullptr) {
        ALOGW("fdopen(%d) failed: %s\n", fd, strerror(errno));
        close(fd);
        jniThrowRuntimeException(env, "fdopen() failed");
        return false;
    }
    return true;
}

/*
 * Dump the native heap, writing human-readable output to the specified
 * file descriptor.
 */
static void android_os_Debug_dumpNativeHeap(JNIEnv* env, jobject,
    jobject fileDescriptor)
{
    UniqueFile fp(nullptr, safeFclose);
    if (!openFile(env, fileDescriptor, fp)) {
        return;
    }

    ALOGD("Native heap dump starting...\n");
    // Formatting of the native heap dump is handled by malloc debug itself.
    // See https://android.googlesource.com/platform/bionic/+/master/libc/malloc_debug/README.md#backtrace-heap-dump-format
    if (android_mallopt(M_WRITE_MALLOC_LEAK_INFO_TO_FILE, fp.get(), sizeof(FILE*))) {
      ALOGD("Native heap dump complete.\n");
    } else {
      PLOG(ERROR) << "Failed to write native heap dump to file";
    }
}

/*
 * Dump the native malloc info, writing xml output to the specified
 * file descriptor.
 */
static void android_os_Debug_dumpNativeMallocInfo(JNIEnv* env, jobject,
    jobject fileDescriptor)
{
    UniqueFile fp(nullptr, safeFclose);
    if (!openFile(env, fileDescriptor, fp)) {
        return;
    }

    malloc_info(0, fp.get());
}

static bool dumpTraces(JNIEnv* env, jint pid, jstring fileName, jint timeoutSecs,
                       DebuggerdDumpType dumpType) {
    const ScopedUtfChars fileNameChars(env, fileName);
    if (fileNameChars.c_str() == nullptr) {
        return false;
    }

    android::base::unique_fd fd(open(fileNameChars.c_str(),
                                     O_CREAT | O_WRONLY | O_NOFOLLOW | O_CLOEXEC | O_APPEND,
                                     0666));
    if (fd < 0) {
        PLOG(ERROR) << "Can't open " << fileNameChars.c_str();
        return false;
    }

    int res = dump_backtrace_to_file_timeout(pid, dumpType, timeoutSecs, fd);
    if (fdatasync(fd.get()) != 0) {
        PLOG(ERROR) << "Failed flushing trace.";
    }
    return res == 0;
}

static jboolean android_os_Debug_dumpJavaBacktraceToFileTimeout(JNIEnv* env, jobject clazz,
        jint pid, jstring fileName, jint timeoutSecs) {
    const bool ret = dumpTraces(env, pid, fileName, timeoutSecs, kDebuggerdJavaBacktrace);
    return ret ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_os_Debug_dumpNativeBacktraceToFileTimeout(JNIEnv* env, jobject clazz,
        jint pid, jstring fileName, jint timeoutSecs) {
    const bool ret = dumpTraces(env, pid, fileName, timeoutSecs, kDebuggerdNativeBacktrace);
    return ret ? JNI_TRUE : JNI_FALSE;
}

static jstring android_os_Debug_getUnreachableMemory(JNIEnv* env, jobject clazz,
    jint limit, jboolean contents)
{
    std::string s = GetUnreachableMemoryString(contents, limit);
    return env->NewStringUTF(s.c_str());
}

static jlong android_os_Debug_getFreeZramKb(JNIEnv* env, jobject clazz) {

    jlong zramFreeKb = 0;

    std::string status_path = android::base::StringPrintf("/proc/meminfo");
    UniqueFile file = MakeUniqueFile(status_path.c_str(), "re");

    char line[256];
    while (file != nullptr && fgets(line, sizeof(line), file.get())) {
        jlong v;
        if (sscanf(line, "SwapFree: %" SCNd64 " kB", &v) == 1) {
            zramFreeKb = v;
            break;
        }
    }

    return zramFreeKb;
}

static jlong android_os_Debug_getIonHeapsSizeKb(JNIEnv* env, jobject clazz) {
    jlong heapsSizeKb = -1;
    uint64_t size;

    if (meminfo::ReadIonHeapsSizeKb(&size)) {
        heapsSizeKb = size;
    }

    return heapsSizeKb;
}

static jlong android_os_Debug_getDmabufTotalExportedKb(JNIEnv* env, jobject clazz) {
    jlong dmabufTotalSizeKb = -1;
    uint64_t size;

    if (dmabufinfo::GetDmabufTotalExportedKb(&size)) {
        dmabufTotalSizeKb = size;
    }
    return dmabufTotalSizeKb;
}

static jlong android_os_Debug_getDmabufHeapTotalExportedKb(JNIEnv* env, jobject clazz) {
    jlong dmabufHeapTotalSizeKb = -1;
    uint64_t size;

    if (meminfo::ReadDmabufHeapTotalExportedKb(&size)) {
        dmabufHeapTotalSizeKb = size;
    }
    return dmabufHeapTotalSizeKb;
}

static jlong android_os_Debug_getIonPoolsSizeKb(JNIEnv* env, jobject clazz) {
    jlong poolsSizeKb = -1;
    uint64_t size;

    if (meminfo::ReadIonPoolsSizeKb(&size)) {
        poolsSizeKb = size;
    }

    return poolsSizeKb;
}

static jlong android_os_Debug_getDmabufHeapPoolsSizeKb(JNIEnv* env, jobject clazz) {
    jlong poolsSizeKb = -1;
    uint64_t size;

    if (meminfo::ReadDmabufHeapPoolsSizeKb(&size)) {
        poolsSizeKb = size;
    }

    return poolsSizeKb;
}

static bool halSupportsGpuPrivateMemory() {
    int productApiLevel =
            android::base::GetIntProperty("ro.product.first_api_level",
                                          android::base::GetIntProperty("ro.build.version.sdk",
                                                                         __ANDROID_API_FUTURE__));
    int boardApiLevel =
            android::base::GetIntProperty("ro.board.api_level",
                                          android::base::GetIntProperty("ro.board.first_api_level",
                                                                         __ANDROID_API_FUTURE__));

    return std::min(productApiLevel, boardApiLevel) >= __ANDROID_API_S__;
}

static jlong android_os_Debug_getGpuPrivateMemoryKb(JNIEnv* env, jobject clazz) {
    static bool gpuPrivateMemorySupported = halSupportsGpuPrivateMemory();

    struct memtrack_proc* p = memtrack_proc_new();
    if (p == nullptr) {
        LOG(ERROR) << "getGpuPrivateMemoryKb: Failed to create memtrack_proc";
        return -1;
    }

    // Memtrack hal defines PID 0 as global total for GPU-private (GL) memory.
    if (memtrack_proc_get(p, 0) != 0) {
        // The memtrack HAL may not be available, avoid flooding the log.
        memtrack_proc_destroy(p);
        return -1;
    }

    ssize_t gpuPrivateMem = memtrack_proc_gl_pss(p);

    memtrack_proc_destroy(p);

    // Old HAL implementations may return 0 for GPU private memory if not supported
    if (gpuPrivateMem == 0 && !gpuPrivateMemorySupported) {
        return -1;
    }

    return gpuPrivateMem / 1024;
}

static jlong android_os_Debug_getDmabufMappedSizeKb(JNIEnv* env, jobject clazz) {
    jlong dmabufPss = 0;
    std::vector<dmabufinfo::DmaBuffer> dmabufs;

    std::unique_ptr<DIR, int (*)(DIR*)> dir(opendir("/proc"), closedir);
    if (!dir) {
        LOG(ERROR) << "Failed to open /proc directory";
        return false;
    }

    struct dirent* dent;
    while ((dent = readdir(dir.get()))) {
        if (dent->d_type != DT_DIR) continue;

        int pid = atoi(dent->d_name);
        if (pid == 0) {
            continue;
        }

        if (!ReadDmaBufMapRefs(pid, &dmabufs)) {
            LOG(ERROR) << "Failed to read maps for pid " << pid;
        }
    }

    for (const dmabufinfo::DmaBuffer& buf : dmabufs) {
        dmabufPss += buf.size() / 1024;
    }

    return dmabufPss;
}

static jlong android_os_Debug_getGpuTotalUsageKb(JNIEnv* env, jobject clazz) {
    jlong sizeKb = -1;
    uint64_t size;

    if (meminfo::ReadGpuTotalUsageKb(&size)) {
        sizeKb = size;
    }

    return sizeKb;
}

static jboolean android_os_Debug_isVmapStack(JNIEnv *env, jobject clazz)
{
    static enum {
        CONFIG_UNKNOWN,
        CONFIG_SET,
        CONFIG_UNSET,
    } cfg_state = CONFIG_UNKNOWN;

    if (cfg_state == CONFIG_UNKNOWN) {
        std::map<std::string, std::string> configs;
        const status_t result = android::kernelconfigs::LoadKernelConfigs(&configs);
        CHECK(result == OK) << "Kernel configs could not be fetched. b/151092221";
        std::map<std::string, std::string>::const_iterator it = configs.find("CONFIG_VMAP_STACK");
        cfg_state = (it != configs.end() && it->second == "y") ? CONFIG_SET : CONFIG_UNSET;
    }
    return cfg_state == CONFIG_SET;
}

static jboolean android_os_Debug_logAllocatorStats(JNIEnv*, jobject) {
    return mallopt(M_LOG_STATS, 0) == 1 ? JNI_TRUE : JNI_FALSE;
}

/*
 * JNI registration.
 */

static const JNINativeMethod gMethods[] = {
        {"getNativeHeapSize", "()J", (void*)android_os_Debug_getNativeHeapSize},
        {"getNativeHeapAllocatedSize", "()J", (void*)android_os_Debug_getNativeHeapAllocatedSize},
        {"getNativeHeapFreeSize", "()J", (void*)android_os_Debug_getNativeHeapFreeSize},
        {"getMemoryInfo", "(Landroid/os/Debug$MemoryInfo;)V",
         (void*)android_os_Debug_getDirtyPages},
        {"getMemoryInfo", "(ILandroid/os/Debug$MemoryInfo;)Z",
         (void*)android_os_Debug_getDirtyPagesPid},
        {"getPss", "()J", (void*)android_os_Debug_getPss},
        {"getPss", "(I[J[J)J", (void*)android_os_Debug_getPssPid},
        {"getRss", "()J", (void*)android_os_Debug_getRss},
        {"getRss", "(I[J)J", (void*)android_os_Debug_getRssPid},
        {"getMemInfo", "([J)V", (void*)android_os_Debug_getMemInfo},
        {"dumpNativeHeap", "(Ljava/io/FileDescriptor;)V", (void*)android_os_Debug_dumpNativeHeap},
        {"dumpNativeMallocInfo", "(Ljava/io/FileDescriptor;)V",
         (void*)android_os_Debug_dumpNativeMallocInfo},
        {"getBinderSentTransactions", "()I", (void*)android_os_Debug_getBinderSentTransactions},
        {"getBinderReceivedTransactions", "()I", (void*)android_os_getBinderReceivedTransactions},
        {"getBinderLocalObjectCount", "()I", (void*)android_os_Debug_getLocalObjectCount},
        {"getBinderProxyObjectCount", "()I", (void*)android_os_Debug_getProxyObjectCount},
        {"getBinderDeathObjectCount", "()I", (void*)android_os_Debug_getDeathObjectCount},
        {"dumpJavaBacktraceToFileTimeout", "(ILjava/lang/String;I)Z",
         (void*)android_os_Debug_dumpJavaBacktraceToFileTimeout},
        {"dumpNativeBacktraceToFileTimeout", "(ILjava/lang/String;I)Z",
         (void*)android_os_Debug_dumpNativeBacktraceToFileTimeout},
        {"getUnreachableMemory", "(IZ)Ljava/lang/String;",
         (void*)android_os_Debug_getUnreachableMemory},
        {"getZramFreeKb", "()J", (void*)android_os_Debug_getFreeZramKb},
        {"getIonHeapsSizeKb", "()J", (void*)android_os_Debug_getIonHeapsSizeKb},
        {"getDmabufTotalExportedKb", "()J", (void*)android_os_Debug_getDmabufTotalExportedKb},
        {"getGpuPrivateMemoryKb", "()J", (void*)android_os_Debug_getGpuPrivateMemoryKb},
        {"getDmabufHeapTotalExportedKb", "()J",
         (void*)android_os_Debug_getDmabufHeapTotalExportedKb},
        {"getIonPoolsSizeKb", "()J", (void*)android_os_Debug_getIonPoolsSizeKb},
        {"getDmabufMappedSizeKb", "()J", (void*)android_os_Debug_getDmabufMappedSizeKb},
        {"getDmabufHeapPoolsSizeKb", "()J", (void*)android_os_Debug_getDmabufHeapPoolsSizeKb},
        {"getGpuTotalUsageKb", "()J", (void*)android_os_Debug_getGpuTotalUsageKb},
        {"isVmapStack", "()Z", (void*)android_os_Debug_isVmapStack},
        {"logAllocatorStats", "()Z", (void*)android_os_Debug_logAllocatorStats},
};

int register_android_os_Debug(JNIEnv *env)
{
    jclass clazz = env->FindClass("android/os/Debug$MemoryInfo");

    // Check the number of other statistics expected in Java matches here.
    jfieldID numOtherStats_field = env->GetStaticFieldID(clazz, "NUM_OTHER_STATS", "I");
    jint numOtherStats = env->GetStaticIntField(clazz, numOtherStats_field);
    jfieldID numDvkStats_field = env->GetStaticFieldID(clazz, "NUM_DVK_STATS", "I");
    jint numDvkStats = env->GetStaticIntField(clazz, numDvkStats_field);
    int expectedNumOtherStats = _NUM_HEAP - _NUM_CORE_HEAP;
    if ((numOtherStats + numDvkStats) != expectedNumOtherStats) {
        jniThrowExceptionFmt(env, "java/lang/RuntimeException",
                             "android.os.Debug.Meminfo.NUM_OTHER_STATS+android.os.Debug.Meminfo.NUM_DVK_STATS=%d expected %d",
                             numOtherStats+numDvkStats, expectedNumOtherStats);
        return JNI_ERR;
    }

    otherStats_field = env->GetFieldID(clazz, "otherStats", "[I");
    hasSwappedOutPss_field = env->GetFieldID(clazz, "hasSwappedOutPss", "Z");

    for (int i=0; i<_NUM_CORE_HEAP; i++) {
        stat_fields[i].pss_field =
                env->GetFieldID(clazz, stat_field_names[i].pss_name, "I");
        stat_fields[i].pssSwappable_field =
                env->GetFieldID(clazz, stat_field_names[i].pssSwappable_name, "I");
        stat_fields[i].rss_field =
                env->GetFieldID(clazz, stat_field_names[i].rss_name, "I");
        stat_fields[i].privateDirty_field =
                env->GetFieldID(clazz, stat_field_names[i].privateDirty_name, "I");
        stat_fields[i].sharedDirty_field =
                env->GetFieldID(clazz, stat_field_names[i].sharedDirty_name, "I");
        stat_fields[i].privateClean_field =
                env->GetFieldID(clazz, stat_field_names[i].privateClean_name, "I");
        stat_fields[i].sharedClean_field =
                env->GetFieldID(clazz, stat_field_names[i].sharedClean_name, "I");
        stat_fields[i].swappedOut_field =
                env->GetFieldID(clazz, stat_field_names[i].swappedOut_name, "I");
        stat_fields[i].swappedOutPss_field =
                env->GetFieldID(clazz, stat_field_names[i].swappedOutPss_name, "I");
    }

    return jniRegisterNativeMethods(env, "android/os/Debug", gMethods, NELEM(gMethods));
}

}; // namespace android
