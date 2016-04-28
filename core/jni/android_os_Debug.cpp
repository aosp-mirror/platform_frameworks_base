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
#include "JNIHelp.h"
#include "jni.h"
#include <utils/String8.h>
#include "utils/misc.h"
#include "cutils/debugger.h"
#include <memtrack/memtrack.h>
#include <memunreachable/memunreachable.h>

#include <cutils/log.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <sys/time.h>
#include <errno.h>
#include <assert.h>
#include <ctype.h>
#include <malloc.h>

#include <iomanip>
#include <string>

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

    HEAP_DALVIK_NORMAL,
    HEAP_DALVIK_LARGE,
    HEAP_DALVIK_LINEARALLOC,
    HEAP_DALVIK_ACCOUNTING,
    HEAP_DALVIK_CODE_CACHE,
    HEAP_DALVIK_ZYGOTE,
    HEAP_DALVIK_NON_MOVING,
    HEAP_DALVIK_INDIRECT_REFERENCE_TABLE,

    _NUM_HEAP,
    _NUM_EXCLUSIVE_HEAP = HEAP_OTHER_MEMTRACK+1,
    _NUM_CORE_HEAP = HEAP_NATIVE+1
};

struct stat_fields {
    jfieldID pss_field;
    jfieldID pssSwappable_field;
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
    const char* privateDirty_name;
    const char* sharedDirty_name;
    const char* privateClean_name;
    const char* sharedClean_name;
    const char* swappedOut_name;
    const char* swappedOutPss_name;
};

static stat_fields stat_fields[_NUM_CORE_HEAP];

static stat_field_names stat_field_names[_NUM_CORE_HEAP] = {
    { "otherPss", "otherSwappablePss", "otherPrivateDirty", "otherSharedDirty",
        "otherPrivateClean", "otherSharedClean", "otherSwappedOut", "otherSwappedOutPss" },
    { "dalvikPss", "dalvikSwappablePss", "dalvikPrivateDirty", "dalvikSharedDirty",
        "dalvikPrivateClean", "dalvikSharedClean", "dalvikSwappedOut", "dalvikSwappedOutPss" },
    { "nativePss", "nativeSwappablePss", "nativePrivateDirty", "nativeSharedDirty",
        "nativePrivateClean", "nativeSharedClean", "nativeSwappedOut", "nativeSwappedOutPss" }
};

jfieldID otherStats_field;
jfieldID hasSwappedOutPss_field;

static bool memtrackLoaded;

struct stats_t {
    int pss;
    int swappablePss;
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
        ALOGW("failed to get memory consumption info: %d", err);
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
    if (!memtrackLoaded) {
        return -1;
    }

    struct memtrack_proc* p = memtrack_proc_new();
    if (p == NULL) {
        ALOGW("failed to create memtrack_proc");
        return -1;
    }

    int err = read_memtrack_memory(p, pid, graphics_mem);
    memtrack_proc_destroy(p);
    return err;
}

static void read_mapinfo(FILE *fp, stats_t* stats, bool* foundSwapPss)
{
    char line[1024];
    int len, nameLen;
    bool skip, done = false;

    unsigned pss = 0, swappable_pss = 0;
    float sharing_proportion = 0.0;
    unsigned shared_clean = 0, shared_dirty = 0;
    unsigned private_clean = 0, private_dirty = 0;
    unsigned swapped_out = 0, swapped_out_pss = 0;
    bool is_swappable = false;
    unsigned temp;

    uint64_t start;
    uint64_t end = 0;
    uint64_t prevEnd = 0;
    char* name;
    int name_pos;

    int whichHeap = HEAP_UNKNOWN;
    int subHeap = HEAP_UNKNOWN;
    int prevHeap = HEAP_UNKNOWN;

    *foundSwapPss = false;

    if(fgets(line, sizeof(line), fp) == 0) return;

    while (!done) {
        prevHeap = whichHeap;
        prevEnd = end;
        whichHeap = HEAP_UNKNOWN;
        subHeap = HEAP_UNKNOWN;
        skip = false;
        is_swappable = false;

        len = strlen(line);
        if (len < 1) return;
        line[--len] = 0;

        if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %*s %*x %*x:%*x %*d%n", &start, &end, &name_pos) != 2) {
            skip = true;
        } else {
            while (isspace(line[name_pos])) {
                name_pos += 1;
            }
            name = line + name_pos;
            nameLen = strlen(name);
            // Trim the end of the line if it is " (deleted)".
            const char* deleted_str = " (deleted)";
            if (nameLen > (int)strlen(deleted_str) &&
                strcmp(name+nameLen-strlen(deleted_str), deleted_str) == 0) {
                nameLen -= strlen(deleted_str);
                name[nameLen] = '\0';
            }
            if ((strstr(name, "[heap]") == name)) {
                whichHeap = HEAP_NATIVE;
            } else if (strncmp(name, "[anon:libc_malloc]", 18) == 0) {
                whichHeap = HEAP_NATIVE;
            } else if (strncmp(name, "[stack", 6) == 0) {
                whichHeap = HEAP_STACK;
            } else if (nameLen > 3 && strcmp(name+nameLen-3, ".so") == 0) {
                whichHeap = HEAP_SO;
                is_swappable = true;
            } else if (nameLen > 4 && strcmp(name+nameLen-4, ".jar") == 0) {
                whichHeap = HEAP_JAR;
                is_swappable = true;
            } else if (nameLen > 4 && strcmp(name+nameLen-4, ".apk") == 0) {
                whichHeap = HEAP_APK;
                is_swappable = true;
            } else if (nameLen > 4 && strcmp(name+nameLen-4, ".ttf") == 0) {
                whichHeap = HEAP_TTF;
                is_swappable = true;
            } else if ((nameLen > 4 && strstr(name, ".dex") != NULL) ||
                       (nameLen > 5 && strcmp(name+nameLen-5, ".odex") == 0)) {
                whichHeap = HEAP_DEX;
                is_swappable = true;
            } else if (nameLen > 4 && strcmp(name+nameLen-4, ".oat") == 0) {
                whichHeap = HEAP_OAT;
                is_swappable = true;
            } else if (nameLen > 4 && strcmp(name+nameLen-4, ".art") == 0) {
                whichHeap = HEAP_ART;
                is_swappable = true;
            } else if (strncmp(name, "/dev/", 5) == 0) {
                if (strncmp(name, "/dev/kgsl-3d0", 13) == 0) {
                    whichHeap = HEAP_GL_DEV;
                } else if (strncmp(name, "/dev/ashmem", 11) == 0) {
                    if (strncmp(name, "/dev/ashmem/dalvik-", 19) == 0) {
                        whichHeap = HEAP_DALVIK_OTHER;
                        if (strstr(name, "/dev/ashmem/dalvik-LinearAlloc") == name) {
                            subHeap = HEAP_DALVIK_LINEARALLOC;
                        } else if ((strstr(name, "/dev/ashmem/dalvik-alloc space") == name) ||
                                   (strstr(name, "/dev/ashmem/dalvik-main space") == name)) {
                            // This is the regular Dalvik heap.
                            whichHeap = HEAP_DALVIK;
                            subHeap = HEAP_DALVIK_NORMAL;
                        } else if (strstr(name, "/dev/ashmem/dalvik-large object space") == name ||
                                   strstr(name, "/dev/ashmem/dalvik-free list large object space")
                                       == name) {
                            whichHeap = HEAP_DALVIK;
                            subHeap = HEAP_DALVIK_LARGE;
                        } else if (strstr(name, "/dev/ashmem/dalvik-non moving space") == name) {
                            whichHeap = HEAP_DALVIK;
                            subHeap = HEAP_DALVIK_NON_MOVING;
                        } else if (strstr(name, "/dev/ashmem/dalvik-zygote space") == name) {
                            whichHeap = HEAP_DALVIK;
                            subHeap = HEAP_DALVIK_ZYGOTE;
                        } else if (strstr(name, "/dev/ashmem/dalvik-indirect ref") == name) {
                            subHeap = HEAP_DALVIK_INDIRECT_REFERENCE_TABLE;
                        } else if (strstr(name, "/dev/ashmem/dalvik-jit-code-cache") == name) {
                            subHeap = HEAP_DALVIK_CODE_CACHE;
                        } else {
                            subHeap = HEAP_DALVIK_ACCOUNTING;  // Default to accounting.
                        }
                    } else if (strncmp(name, "/dev/ashmem/CursorWindow", 24) == 0) {
                        whichHeap = HEAP_CURSOR;
                    } else if (strncmp(name, "/dev/ashmem/libc malloc", 23) == 0) {
                        whichHeap = HEAP_NATIVE;
                    } else {
                        whichHeap = HEAP_ASHMEM;
                    }
                } else {
                    whichHeap = HEAP_UNKNOWN_DEV;
                }
            } else if (strncmp(name, "[anon:", 6) == 0) {
                whichHeap = HEAP_UNKNOWN;
            } else if (nameLen > 0) {
                whichHeap = HEAP_UNKNOWN_MAP;
            } else if (start == prevEnd && prevHeap == HEAP_SO) {
                // bss section of a shared library.
                whichHeap = HEAP_SO;
            }
        }

        //ALOGI("native=%d dalvik=%d sqlite=%d: %s\n", isNativeHeap, isDalvikHeap,
        //    isSqliteHeap, line);

        shared_clean = 0;
        shared_dirty = 0;
        private_clean = 0;
        private_dirty = 0;
        swapped_out = 0;
        swapped_out_pss = 0;

        while (true) {
            if (fgets(line, 1024, fp) == 0) {
                done = true;
                break;
            }

            if (line[0] == 'S' && sscanf(line, "Size: %d kB", &temp) == 1) {
                /* size = temp; */
            } else if (line[0] == 'R' && sscanf(line, "Rss: %d kB", &temp) == 1) {
                /* resident = temp; */
            } else if (line[0] == 'P' && sscanf(line, "Pss: %d kB", &temp) == 1) {
                pss = temp;
            } else if (line[0] == 'S' && sscanf(line, "Shared_Clean: %d kB", &temp) == 1) {
                shared_clean = temp;
            } else if (line[0] == 'S' && sscanf(line, "Shared_Dirty: %d kB", &temp) == 1) {
                shared_dirty = temp;
            } else if (line[0] == 'P' && sscanf(line, "Private_Clean: %d kB", &temp) == 1) {
                private_clean = temp;
            } else if (line[0] == 'P' && sscanf(line, "Private_Dirty: %d kB", &temp) == 1) {
                private_dirty = temp;
            } else if (line[0] == 'R' && sscanf(line, "Referenced: %d kB", &temp) == 1) {
                /* referenced = temp; */
            } else if (line[0] == 'S' && sscanf(line, "Swap: %d kB", &temp) == 1) {
                swapped_out = temp;
            } else if (line[0] == 'S' && sscanf(line, "SwapPss: %d kB", &temp) == 1) {
                *foundSwapPss = true;
                swapped_out_pss = temp;
            } else if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %*s %*x %*x:%*x %*d", &start, &end) == 2) {
                // looks like a new mapping
                // example: "10000000-10001000 ---p 10000000 00:00 0"
                break;
            }
        }

        if (!skip) {
            if (is_swappable && (pss > 0)) {
                sharing_proportion = 0.0;
                if ((shared_clean > 0) || (shared_dirty > 0)) {
                    sharing_proportion = (pss - private_clean
                            - private_dirty)/(shared_clean+shared_dirty);
                }
                swappable_pss = (sharing_proportion*shared_clean) + private_clean;
            } else
                swappable_pss = 0;

            stats[whichHeap].pss += pss;
            stats[whichHeap].swappablePss += swappable_pss;
            stats[whichHeap].privateDirty += private_dirty;
            stats[whichHeap].sharedDirty += shared_dirty;
            stats[whichHeap].privateClean += private_clean;
            stats[whichHeap].sharedClean += shared_clean;
            stats[whichHeap].swappedOut += swapped_out;
            stats[whichHeap].swappedOutPss += swapped_out_pss;
            if (whichHeap == HEAP_DALVIK || whichHeap == HEAP_DALVIK_OTHER) {
                stats[subHeap].pss += pss;
                stats[subHeap].swappablePss += swappable_pss;
                stats[subHeap].privateDirty += private_dirty;
                stats[subHeap].sharedDirty += shared_dirty;
                stats[subHeap].privateClean += private_clean;
                stats[subHeap].sharedClean += shared_clean;
                stats[subHeap].swappedOut += swapped_out;
                stats[subHeap].swappedOutPss += swapped_out_pss;
            }
        }
    }
}

static void load_maps(int pid, stats_t* stats, bool* foundSwapPss)
{
    char tmp[128];
    FILE *fp;

    sprintf(tmp, "/proc/%d/smaps", pid);
    fp = fopen(tmp, "r");
    if (fp == 0) return;

    read_mapinfo(fp, stats, foundSwapPss);
    fclose(fp);
}

static void android_os_Debug_getDirtyPagesPid(JNIEnv *env, jobject clazz,
        jint pid, jobject object)
{
    bool foundSwapPss;
    stats_t stats[_NUM_HEAP];
    memset(&stats, 0, sizeof(stats));

    load_maps(pid, stats, &foundSwapPss);

    struct graphics_memory_pss graphics_mem;
    if (read_memtrack_memory(pid, &graphics_mem) == 0) {
        stats[HEAP_GRAPHICS].pss = graphics_mem.graphics;
        stats[HEAP_GRAPHICS].privateDirty = graphics_mem.graphics;
        stats[HEAP_GL].pss = graphics_mem.gl;
        stats[HEAP_GL].privateDirty = graphics_mem.gl;
        stats[HEAP_OTHER_MEMTRACK].pss = graphics_mem.other;
        stats[HEAP_OTHER_MEMTRACK].privateDirty = graphics_mem.other;
    }

    for (int i=_NUM_CORE_HEAP; i<_NUM_EXCLUSIVE_HEAP; i++) {
        stats[HEAP_UNKNOWN].pss += stats[i].pss;
        stats[HEAP_UNKNOWN].swappablePss += stats[i].swappablePss;
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
        return;
    }

    int j=0;
    for (int i=_NUM_CORE_HEAP; i<_NUM_HEAP; i++) {
        otherArray[j++] = stats[i].pss;
        otherArray[j++] = stats[i].swappablePss;
        otherArray[j++] = stats[i].privateDirty;
        otherArray[j++] = stats[i].sharedDirty;
        otherArray[j++] = stats[i].privateClean;
        otherArray[j++] = stats[i].sharedClean;
        otherArray[j++] = stats[i].swappedOut;
        otherArray[j++] = stats[i].swappedOutPss;
    }

    env->ReleasePrimitiveArrayCritical(otherIntArray, otherArray, 0);
}

static void android_os_Debug_getDirtyPages(JNIEnv *env, jobject clazz, jobject object)
{
    android_os_Debug_getDirtyPagesPid(env, clazz, getpid(), object);
}

static jlong android_os_Debug_getPssPid(JNIEnv *env, jobject clazz, jint pid,
        jlongArray outUssSwapPss, jlongArray outMemtrack)
{
    char line[1024];
    jlong pss = 0;
    jlong swapPss = 0;
    jlong uss = 0;
    jlong memtrack = 0;

    char tmp[128];
    FILE *fp;

    struct graphics_memory_pss graphics_mem;
    if (read_memtrack_memory(pid, &graphics_mem) == 0) {
        pss = uss = memtrack = graphics_mem.graphics + graphics_mem.gl + graphics_mem.other;
    }

    sprintf(tmp, "/proc/%d/smaps", pid);
    fp = fopen(tmp, "r");

    if (fp != 0) {
        while (true) {
            if (fgets(line, 1024, fp) == NULL) {
                break;
            }

            if (line[0] == 'P') {
                if (strncmp(line, "Pss:", 4) == 0) {
                    char* c = line + 4;
                    while (*c != 0 && (*c < '0' || *c > '9')) {
                        c++;
                    }
                    pss += atoi(c);
                } else if (strncmp(line, "Private_Clean:", 14) == 0
                        || strncmp(line, "Private_Dirty:", 14) == 0) {
                    char* c = line + 14;
                    while (*c != 0 && (*c < '0' || *c > '9')) {
                        c++;
                    }
                    uss += atoi(c);
                }
            } else if (line[0] == 'S' && strncmp(line, "SwapPss:", 8) == 0) {
                char* c = line + 8;
                jlong lSwapPss;
                while (*c != 0 && (*c < '0' || *c > '9')) {
                    c++;
                }
                lSwapPss = atoi(c);
                swapPss += lSwapPss;
                pss += lSwapPss; // Also in swap, those pages would be accounted as Pss without SWAP
            }
        }

        fclose(fp);
    }

    if (outUssSwapPss != NULL) {
        if (env->GetArrayLength(outUssSwapPss) >= 1) {
            jlong* outUssSwapPssArray = env->GetLongArrayElements(outUssSwapPss, 0);
            if (outUssSwapPssArray != NULL) {
                outUssSwapPssArray[0] = uss;
                if (env->GetArrayLength(outUssSwapPss) >= 2) {
                    outUssSwapPssArray[1] = swapPss;
                }
            }
            env->ReleaseLongArrayElements(outUssSwapPss, outUssSwapPssArray, 0);
        }
    }

    if (outMemtrack != NULL) {
        if (env->GetArrayLength(outMemtrack) >= 1) {
            jlong* outMemtrackArray = env->GetLongArrayElements(outMemtrack, 0);
            if (outMemtrackArray != NULL) {
                outMemtrackArray[0] = memtrack;
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

static long get_allocated_vmalloc_memory() {
    char line[1024];
    // Ignored tags that don't actually consume memory (ie remappings)
    static const char* const ignored_tags[] = {
            "ioremap",
            "map_lowmem",
            "vm_map_ram",
            NULL
    };
    long size, vmalloc_allocated_size = 0;
    FILE* fp = fopen("/proc/vmallocinfo", "r");
    if (fp == NULL) {
        return 0;
    }
    while (true) {
        if (fgets(line, 1024, fp) == NULL) {
            break;
        }
        bool valid_line = true;
        int i = 0;
        while (ignored_tags[i]) {
            if (strstr(line, ignored_tags[i]) != NULL) {
                valid_line = false;
                break;
            }
            i++;
        }
        if (valid_line && (sscanf(line, "%*x-%*x %ld", &size) == 1)) {
            vmalloc_allocated_size += size;
        }
    }
    fclose(fp);
    return vmalloc_allocated_size;
}

enum {
    MEMINFO_TOTAL,
    MEMINFO_FREE,
    MEMINFO_BUFFERS,
    MEMINFO_CACHED,
    MEMINFO_SHMEM,
    MEMINFO_SLAB,
    MEMINFO_SWAP_TOTAL,
    MEMINFO_SWAP_FREE,
    MEMINFO_ZRAM_TOTAL,
    MEMINFO_MAPPED,
    MEMINFO_VMALLOC_USED,
    MEMINFO_PAGE_TABLES,
    MEMINFO_KERNEL_STACK,
    MEMINFO_COUNT
};

static long long get_zram_mem_used()
{
#define ZRAM_SYSFS "/sys/block/zram0/"
    FILE *f = fopen(ZRAM_SYSFS "mm_stat", "r");
    if (f) {
        long long mem_used_total = 0;

        int matched = fscanf(f, "%*d %*d %lld %*d %*d %*d %*d", &mem_used_total);
        if (matched != 1)
            ALOGW("failed to parse " ZRAM_SYSFS "mm_stat");

        fclose(f);
        return mem_used_total;
    }

    f = fopen(ZRAM_SYSFS "mem_used_total", "r");
    if (f) {
        long long mem_used_total = 0;

        int matched = fscanf(f, "%lld", &mem_used_total);
        if (matched != 1)
            ALOGW("failed to parse " ZRAM_SYSFS "mem_used_total");

        fclose(f);
        return mem_used_total;
    }

    return 0;
}

static void android_os_Debug_getMemInfo(JNIEnv *env, jobject clazz, jlongArray out)
{
    char buffer[1024];
    size_t numFound = 0;

    if (out == NULL) {
        jniThrowNullPointerException(env, "out == null");
        return;
    }

    int fd = open("/proc/meminfo", O_RDONLY);

    if (fd < 0) {
        ALOGW("Unable to open /proc/meminfo: %s\n", strerror(errno));
        return;
    }

    int len = read(fd, buffer, sizeof(buffer)-1);
    close(fd);

    if (len < 0) {
        ALOGW("Empty /proc/meminfo");
        return;
    }
    buffer[len] = 0;

    static const char* const tags[] = {
            "MemTotal:",
            "MemFree:",
            "Buffers:",
            "Cached:",
            "Shmem:",
            "Slab:",
            "SwapTotal:",
            "SwapFree:",
            "ZRam:",
            "Mapped:",
            "VmallocUsed:",
            "PageTables:",
            "KernelStack:",
            NULL
    };
    static const int tagsLen[] = {
            9,
            8,
            8,
            7,
            6,
            5,
            10,
            9,
            5,
            7,
            12,
            11,
            12,
            0
    };
    long mem[] = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

    char* p = buffer;
    while (*p && numFound < (sizeof(tagsLen) / sizeof(tagsLen[0]))) {
        int i = 0;
        while (tags[i]) {
            if (strncmp(p, tags[i], tagsLen[i]) == 0) {
                p += tagsLen[i];
                while (*p == ' ') p++;
                char* num = p;
                while (*p >= '0' && *p <= '9') p++;
                if (*p != 0) {
                    *p = 0;
                    p++;
                }
                mem[i] = atoll(num);
                numFound++;
                break;
            }
            i++;
        }
        while (*p && *p != '\n') {
            p++;
        }
        if (*p) p++;
    }

    mem[MEMINFO_ZRAM_TOTAL] = get_zram_mem_used() / 1024;
    // Recompute Vmalloc Used since the value in meminfo
    // doesn't account for I/O remapping which doesn't use RAM.
    mem[MEMINFO_VMALLOC_USED] = get_allocated_vmalloc_memory() / 1024;

    int maxNum = env->GetArrayLength(out);
    if (maxNum > MEMINFO_COUNT) {
        maxNum = MEMINFO_COUNT;
    }
    jlong* outArray = env->GetLongArrayElements(out, 0);
    if (outArray != NULL) {
        for (int i=0; i<maxNum; i++) {
            outArray[i] = mem[i];
        }
    }
    env->ReleaseLongArrayElements(out, outArray, 0);
}


static jint read_binder_stat(const char* stat)
{
    FILE* fp = fopen(BINDER_STATS, "r");
    if (fp == NULL) {
        return -1;
    }

    char line[1024];

    char compare[128];
    int len = snprintf(compare, 128, "proc %d", getpid());

    // loop until we have the block that represents this process
    do {
        if (fgets(line, 1024, fp) == 0) {
            fclose(fp);
            return -1;
        }
    } while (strncmp(compare, line, len));

    // now that we have this process, read until we find the stat that we are looking for
    len = snprintf(compare, 128, "  %s: ", stat);

    do {
        if (fgets(line, 1024, fp) == 0) {
            fclose(fp);
            return -1;
        }
    } while (strncmp(compare, line, len));

    // we have the line, now increment the line ptr to the value
    char* ptr = line + len;
    jint result = atoi(ptr);
    fclose(fp);
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


/* pulled out of bionic */
extern "C" void get_malloc_leak_info(uint8_t** info, size_t* overallSize,
    size_t* infoSize, size_t* totalMemory, size_t* backtraceSize);
extern "C" void free_malloc_leak_info(uint8_t* info);
#define SIZE_FLAG_ZYGOTE_CHILD  (1<<31)
#define BACKTRACE_SIZE          32

/*
 * This is a qsort() callback.
 *
 * See dumpNativeHeap() for comments about the data format and sort order.
 */
static int compareHeapRecords(const void* vrec1, const void* vrec2)
{
    const size_t* rec1 = (const size_t*) vrec1;
    const size_t* rec2 = (const size_t*) vrec2;
    size_t size1 = *rec1;
    size_t size2 = *rec2;

    if (size1 < size2) {
        return 1;
    } else if (size1 > size2) {
        return -1;
    }

    intptr_t* bt1 = (intptr_t*)(rec1 + 2);
    intptr_t* bt2 = (intptr_t*)(rec2 + 2);
    for (size_t idx = 0; idx < BACKTRACE_SIZE; idx++) {
        intptr_t addr1 = bt1[idx];
        intptr_t addr2 = bt2[idx];
        if (addr1 == addr2) {
            if (addr1 == 0)
                break;
            continue;
        }
        if (addr1 < addr2) {
            return -1;
        } else if (addr1 > addr2) {
            return 1;
        }
    }

    return 0;
}

/*
 * The get_malloc_leak_info() call returns an array of structs that
 * look like this:
 *
 *   size_t size
 *   size_t allocations
 *   intptr_t backtrace[32]
 *
 * "size" is the size of the allocation, "backtrace" is a fixed-size
 * array of function pointers, and "allocations" is the number of
 * allocations with the exact same size and backtrace.
 *
 * The entries are sorted by descending total size (i.e. size*allocations)
 * then allocation count.  For best results with "diff" we'd like to sort
 * primarily by individual size then stack trace.  Since the entries are
 * fixed-size, and we're allowed (by the current implementation) to mangle
 * them, we can do this in place.
 */
static void dumpNativeHeap(FILE* fp)
{
    uint8_t* info = NULL;
    size_t overallSize, infoSize, totalMemory, backtraceSize;

    get_malloc_leak_info(&info, &overallSize, &infoSize, &totalMemory,
        &backtraceSize);
    if (info == NULL) {
        fprintf(fp, "Native heap dump not available. To enable, run these"
                    " commands (requires root):\n");
        fprintf(fp, "$ adb shell setprop libc.debug.malloc 1\n");
        fprintf(fp, "$ adb shell stop\n");
        fprintf(fp, "$ adb shell start\n");
        return;
    }
    assert(infoSize != 0);
    assert(overallSize % infoSize == 0);

    fprintf(fp, "Android Native Heap Dump v1.0\n\n");

    size_t recordCount = overallSize / infoSize;
    fprintf(fp, "Total memory: %zu\n", totalMemory);
    fprintf(fp, "Allocation records: %zd\n", recordCount);
    if (backtraceSize != BACKTRACE_SIZE) {
        fprintf(fp, "WARNING: mismatched backtrace sizes (%zu vs. %d)\n",
            backtraceSize, BACKTRACE_SIZE);
    }
    fprintf(fp, "\n");

    /* re-sort the entries */
    qsort(info, recordCount, infoSize, compareHeapRecords);

    /* dump the entries to the file */
    const uint8_t* ptr = info;
    for (size_t idx = 0; idx < recordCount; idx++) {
        size_t size = *(size_t*) ptr;
        size_t allocations = *(size_t*) (ptr + sizeof(size_t));
        intptr_t* backtrace = (intptr_t*) (ptr + sizeof(size_t) * 2);

        fprintf(fp, "z %d  sz %8zu  num %4zu  bt",
                (size & SIZE_FLAG_ZYGOTE_CHILD) != 0,
                size & ~SIZE_FLAG_ZYGOTE_CHILD,
                allocations);
        for (size_t bt = 0; bt < backtraceSize; bt++) {
            if (backtrace[bt] == 0) {
                break;
            } else {
#ifdef __LP64__
                fprintf(fp, " %016" PRIxPTR, backtrace[bt]);
#else
                fprintf(fp, " %08" PRIxPTR, backtrace[bt]);
#endif
            }
        }
        fprintf(fp, "\n");

        ptr += infoSize;
    }

    free_malloc_leak_info(info);

    fprintf(fp, "MAPS\n");
    const char* maps = "/proc/self/maps";
    FILE* in = fopen(maps, "r");
    if (in == NULL) {
        fprintf(fp, "Could not open %s\n", maps);
        return;
    }
    char buf[BUFSIZ];
    while (size_t n = fread(buf, sizeof(char), BUFSIZ, in)) {
        fwrite(buf, sizeof(char), n, fp);
    }
    fclose(in);

    fprintf(fp, "END\n");
}

/*
 * Dump the native heap, writing human-readable output to the specified
 * file descriptor.
 */
static void android_os_Debug_dumpNativeHeap(JNIEnv* env, jobject clazz,
    jobject fileDescriptor)
{
    if (fileDescriptor == NULL) {
        jniThrowNullPointerException(env, "fd == null");
        return;
    }
    int origFd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (origFd < 0) {
        jniThrowRuntimeException(env, "Invalid file descriptor");
        return;
    }

    /* dup() the descriptor so we don't close the original with fclose() */
    int fd = dup(origFd);
    if (fd < 0) {
        ALOGW("dup(%d) failed: %s\n", origFd, strerror(errno));
        jniThrowRuntimeException(env, "dup() failed");
        return;
    }

    FILE* fp = fdopen(fd, "w");
    if (fp == NULL) {
        ALOGW("fdopen(%d) failed: %s\n", fd, strerror(errno));
        close(fd);
        jniThrowRuntimeException(env, "fdopen() failed");
        return;
    }

    ALOGD("Native heap dump starting...\n");
    dumpNativeHeap(fp);
    ALOGD("Native heap dump complete.\n");

    fclose(fp);
}


static void android_os_Debug_dumpNativeBacktraceToFile(JNIEnv* env, jobject clazz,
    jint pid, jstring fileName)
{
    if (fileName == NULL) {
        jniThrowNullPointerException(env, "file == null");
        return;
    }
    const jchar* str = env->GetStringCritical(fileName, 0);
    String8 fileName8;
    if (str) {
        fileName8 = String8(reinterpret_cast<const char16_t*>(str),
                            env->GetStringLength(fileName));
        env->ReleaseStringCritical(fileName, str);
    }

    int fd = open(fileName8.string(), O_CREAT | O_WRONLY | O_NOFOLLOW, 0666);  /* -rw-rw-rw- */
    if (fd < 0) {
        fprintf(stderr, "Can't open %s: %s\n", fileName8.string(), strerror(errno));
        return;
    }

    if (lseek(fd, 0, SEEK_END) < 0) {
        fprintf(stderr, "lseek: %s\n", strerror(errno));
    } else {
        dump_backtrace_to_file(pid, fd);
    }

    close(fd);
}

static jstring android_os_Debug_getUnreachableMemory(JNIEnv* env, jobject clazz,
    jint limit, jboolean contents)
{
    std::string s = GetUnreachableMemoryString(contents, limit);
    return env->NewStringUTF(s.c_str());
}

/*
 * JNI registration.
 */

static const JNINativeMethod gMethods[] = {
    { "getNativeHeapSize",      "()J",
            (void*) android_os_Debug_getNativeHeapSize },
    { "getNativeHeapAllocatedSize", "()J",
            (void*) android_os_Debug_getNativeHeapAllocatedSize },
    { "getNativeHeapFreeSize",  "()J",
            (void*) android_os_Debug_getNativeHeapFreeSize },
    { "getMemoryInfo",          "(Landroid/os/Debug$MemoryInfo;)V",
            (void*) android_os_Debug_getDirtyPages },
    { "getMemoryInfo",          "(ILandroid/os/Debug$MemoryInfo;)V",
            (void*) android_os_Debug_getDirtyPagesPid },
    { "getPss",                 "()J",
            (void*) android_os_Debug_getPss },
    { "getPss",                 "(I[J[J)J",
            (void*) android_os_Debug_getPssPid },
    { "getMemInfo",             "([J)V",
            (void*) android_os_Debug_getMemInfo },
    { "dumpNativeHeap",         "(Ljava/io/FileDescriptor;)V",
            (void*) android_os_Debug_dumpNativeHeap },
    { "getBinderSentTransactions", "()I",
            (void*) android_os_Debug_getBinderSentTransactions },
    { "getBinderReceivedTransactions", "()I",
            (void*) android_os_getBinderReceivedTransactions },
    { "getBinderLocalObjectCount", "()I",
            (void*)android_os_Debug_getLocalObjectCount },
    { "getBinderProxyObjectCount", "()I",
            (void*)android_os_Debug_getProxyObjectCount },
    { "getBinderDeathObjectCount", "()I",
            (void*)android_os_Debug_getDeathObjectCount },
    { "dumpNativeBacktraceToFile", "(ILjava/lang/String;)V",
            (void*)android_os_Debug_dumpNativeBacktraceToFile },
    { "getUnreachableMemory", "(IZ)Ljava/lang/String;",
            (void*)android_os_Debug_getUnreachableMemory },
};

int register_android_os_Debug(JNIEnv *env)
{
    int err = memtrack_init();
    if (err != 0) {
        memtrackLoaded = false;
        ALOGE("failed to load memtrack module: %d", err);
    } else {
        memtrackLoaded = true;
    }

    jclass clazz = env->FindClass("android/os/Debug$MemoryInfo");

    // Sanity check the number of other statistics expected in Java matches here.
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
