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

#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <sys/time.h>
#include <errno.h>
#include <assert.h>
#include <ctype.h>

#ifdef HAVE_MALLOC_H
#include <malloc.h>
#endif

namespace android
{

enum {
    HEAP_UNKNOWN,
    HEAP_DALVIK,
    HEAP_NATIVE,
    HEAP_STACK,
    HEAP_CURSOR,
    HEAP_ASHMEM,
    HEAP_UNKNOWN_DEV,
    HEAP_SO,
    HEAP_JAR,
    HEAP_APK,
    HEAP_TTF,
    HEAP_DEX,
    HEAP_UNKNOWN_MAP,

    _NUM_HEAP,
    _NUM_CORE_HEAP = HEAP_NATIVE+1
};

struct stat_fields {
    jfieldID pss_field;
    jfieldID privateDirty_field;
    jfieldID sharedDirty_field;
};

struct stat_field_names {
    const char* pss_name;
    const char* privateDirty_name;
    const char* sharedDirty_name;
};

static stat_fields stat_fields[_NUM_CORE_HEAP];

static stat_field_names stat_field_names[_NUM_CORE_HEAP] = {
    { "otherPss", "otherPrivateDirty", "otherSharedDirty" },
    { "dalvikPss", "dalvikPrivateDirty", "dalvikSharedDirty" },
    { "nativePss", "nativePrivateDirty", "nativeSharedDirty" }
};

jfieldID otherStats_field;

struct stats_t {
    int pss;
    int privateDirty;
    int sharedDirty;
};

#define BINDER_STATS "/proc/binder/stats"

static jlong android_os_Debug_getNativeHeapSize(JNIEnv *env, jobject clazz)
{
#ifdef HAVE_MALLOC_H
    struct mallinfo info = mallinfo();
    return (jlong) info.usmblks;
#else
    return -1;
#endif
}

static jlong android_os_Debug_getNativeHeapAllocatedSize(JNIEnv *env, jobject clazz)
{
#ifdef HAVE_MALLOC_H
    struct mallinfo info = mallinfo();
    return (jlong) info.uordblks;
#else
    return -1;
#endif
}

static jlong android_os_Debug_getNativeHeapFreeSize(JNIEnv *env, jobject clazz)
{
#ifdef HAVE_MALLOC_H
    struct mallinfo info = mallinfo();
    return (jlong) info.fordblks;
#else
    return -1;
#endif
}

static void read_mapinfo(FILE *fp, stats_t* stats)
{
    char line[1024];
    int len, nameLen;
    bool skip, done = false;

    unsigned size = 0, resident = 0, pss = 0;
    unsigned shared_clean = 0, shared_dirty = 0;
    unsigned private_clean = 0, private_dirty = 0;
    unsigned referenced = 0;
    unsigned temp;

    unsigned long int start;
    unsigned long int end = 0;
    unsigned long int prevEnd = 0;
    char* name;
    int name_pos;

    int whichHeap = HEAP_UNKNOWN;
    int prevHeap = HEAP_UNKNOWN;

    if(fgets(line, sizeof(line), fp) == 0) return;

    while (!done) {
        prevHeap = whichHeap;
        prevEnd = end;
        whichHeap = HEAP_UNKNOWN;
        skip = false;

        len = strlen(line);
        if (len < 1) return;
        line[--len] = 0;

        if (sscanf(line, "%lx-%lx %*s %*x %*x:%*x %*d%n", &start, &end, &name_pos) != 2) {
            skip = true;
        } else {
            while (isspace(line[name_pos])) {
                name_pos += 1;
            }
            name = line + name_pos;
            nameLen = strlen(name);

            if ((strstr(name, "[heap]") == name) ||
                (strstr(name, "/dev/ashmem/libc malloc") == name)) {
                whichHeap = HEAP_NATIVE;
            } else if (strstr(name, "/dev/ashmem/dalvik-") == name) {
                whichHeap = HEAP_DALVIK;
            } else if (strstr(name, "[stack") == name) {
                whichHeap = HEAP_STACK;
            } else if (strstr(name, "/dev/ashmem/CursorWindow") == name) {
                whichHeap = HEAP_CURSOR;
            } else if (strstr(name, "/dev/ashmem/") == name) {
                whichHeap = HEAP_ASHMEM;
            } else if (strstr(name, "/dev/") == name) {
                whichHeap = HEAP_UNKNOWN_DEV;
            } else if (nameLen > 3 && strcmp(name+nameLen-3, ".so") == 0) {
                whichHeap = HEAP_SO;
            } else if (nameLen > 4 && strcmp(name+nameLen-4, ".jar") == 0) {
                whichHeap = HEAP_JAR;
            } else if (nameLen > 4 && strcmp(name+nameLen-4, ".apk") == 0) {
                whichHeap = HEAP_APK;
            } else if (nameLen > 4 && strcmp(name+nameLen-4, ".ttf") == 0) {
                whichHeap = HEAP_TTF;
            } else if ((nameLen > 4 && strcmp(name+nameLen-4, ".dex") == 0) ||
                       (nameLen > 5 && strcmp(name+nameLen-5, ".odex") == 0)) {
                whichHeap = HEAP_DEX;
            } else if (nameLen > 0) {
                whichHeap = HEAP_UNKNOWN_MAP;
            } else if (start == prevEnd && prevHeap == HEAP_SO) {
                // bss section of a shared library.
                whichHeap = HEAP_SO;
            }
        }

        //ALOGI("native=%d dalvik=%d sqlite=%d: %s\n", isNativeHeap, isDalvikHeap,
        //    isSqliteHeap, line);

        while (true) {
            if (fgets(line, 1024, fp) == 0) {
                done = true;
                break;
            }

            if (sscanf(line, "Size: %d kB", &temp) == 1) {
                size = temp;
            } else if (sscanf(line, "Rss: %d kB", &temp) == 1) {
                resident = temp;
            } else if (sscanf(line, "Pss: %d kB", &temp) == 1) {
                pss = temp;
            } else if (sscanf(line, "Shared_Clean: %d kB", &temp) == 1) {
                shared_clean = temp;
            } else if (sscanf(line, "Shared_Dirty: %d kB", &temp) == 1) {
                shared_dirty = temp;
            } else if (sscanf(line, "Private_Clean: %d kB", &temp) == 1) {
                private_clean = temp;
            } else if (sscanf(line, "Private_Dirty: %d kB", &temp) == 1) {
                private_dirty = temp;
            } else if (sscanf(line, "Referenced: %d kB", &temp) == 1) {
                referenced = temp;
            } else if (strlen(line) > 30 && line[8] == '-' && line[17] == ' ') {
                // looks like a new mapping
                // example: "10000000-10001000 ---p 10000000 00:00 0"
                break;
            }
        }

        if (!skip) {
            stats[whichHeap].pss += pss;
            stats[whichHeap].privateDirty += private_dirty;
            stats[whichHeap].sharedDirty += shared_dirty;
        }
    }
}

static void load_maps(int pid, stats_t* stats)
{
    char tmp[128];
    FILE *fp;

    sprintf(tmp, "/proc/%d/smaps", pid);
    fp = fopen(tmp, "r");
    if (fp == 0) return;

    read_mapinfo(fp, stats);
    fclose(fp);
}

static void android_os_Debug_getDirtyPagesPid(JNIEnv *env, jobject clazz,
        jint pid, jobject object)
{
    stats_t stats[_NUM_HEAP];
    memset(&stats, 0, sizeof(stats));

    load_maps(pid, stats);

    for (int i=_NUM_CORE_HEAP; i<_NUM_HEAP; i++) {
        stats[HEAP_UNKNOWN].pss += stats[i].pss;
        stats[HEAP_UNKNOWN].privateDirty += stats[i].privateDirty;
        stats[HEAP_UNKNOWN].sharedDirty += stats[i].sharedDirty;
    }

    for (int i=0; i<_NUM_CORE_HEAP; i++) {
        env->SetIntField(object, stat_fields[i].pss_field, stats[i].pss);
        env->SetIntField(object, stat_fields[i].privateDirty_field, stats[i].privateDirty);
        env->SetIntField(object, stat_fields[i].sharedDirty_field, stats[i].sharedDirty);
    }

    jintArray otherIntArray = (jintArray)env->GetObjectField(object, otherStats_field);

    jint* otherArray = (jint*)env->GetPrimitiveArrayCritical(otherIntArray, 0);
    if (otherArray == NULL) {
        return;
    }

    int j=0;
    for (int i=_NUM_CORE_HEAP; i<_NUM_HEAP; i++) {
        otherArray[j++] = stats[i].pss;
        otherArray[j++] = stats[i].privateDirty;
        otherArray[j++] = stats[i].sharedDirty;
    }

    env->ReleasePrimitiveArrayCritical(otherIntArray, otherArray, 0);
}

static void android_os_Debug_getDirtyPages(JNIEnv *env, jobject clazz, jobject object)
{
    android_os_Debug_getDirtyPagesPid(env, clazz, getpid(), object);
}

static jlong android_os_Debug_getPssPid(JNIEnv *env, jobject clazz, jint pid)
{
    char line[1024];
    jlong pss = 0;
    unsigned temp;

    char tmp[128];
    FILE *fp;

    sprintf(tmp, "/proc/%d/smaps", pid);
    fp = fopen(tmp, "r");
    if (fp == 0) return 0;

    while (true) {
        if (fgets(line, 1024, fp) == 0) {
            break;
        }

        if (sscanf(line, "Pss: %d kB", &temp) == 1) {
            pss += temp;
        }
    }

    fclose(fp);

    return pss;
}

static jlong android_os_Debug_getPss(JNIEnv *env, jobject clazz)
{
    return android_os_Debug_getPssPid(env, clazz, getpid());
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
            return -1;
        }
    } while (strncmp(compare, line, len));

    // now that we have this process, read until we find the stat that we are looking for
    len = snprintf(compare, 128, "  %s: ", stat);

    do {
        if (fgets(line, 1024, fp) == 0) {
            return -1;
        }
    } while (strncmp(compare, line, len));

    // we have the line, now increment the line ptr to the value
    char* ptr = line + len;
    return atoi(ptr);
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
        fprintf(fp, "WARNING: mismatched backtrace sizes (%d vs. %d)\n",
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
                fprintf(fp, " %08x", backtrace[bt]);
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
        fileName8 = String8(str, env->GetStringLength(fileName));
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

/*
 * JNI registration.
 */

static JNINativeMethod gMethods[] = {
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
    { "getPss",                 "(I)J",
            (void*) android_os_Debug_getPssPid },
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
};

int register_android_os_Debug(JNIEnv *env)
{
    jclass clazz = env->FindClass("android/os/Debug$MemoryInfo");

    // Sanity check the number of other statistics expected in Java matches here.
    jfieldID numOtherStats_field = env->GetStaticFieldID(clazz, "NUM_OTHER_STATS", "I");
    jint numOtherStats = env->GetStaticIntField(clazz, numOtherStats_field);
    int expectedNumOtherStats = _NUM_HEAP - _NUM_CORE_HEAP;
    if (numOtherStats != expectedNumOtherStats) {
        jniThrowExceptionFmt(env, "java/lang/RuntimeException",
                             "android.os.Debug.Meminfo.NUM_OTHER_STATS=%d expected %d",
                             numOtherStats, expectedNumOtherStats);
        return JNI_ERR;
    }

    otherStats_field = env->GetFieldID(clazz, "otherStats", "[I");

    for (int i=0; i<_NUM_CORE_HEAP; i++) {
        stat_fields[i].pss_field =
                env->GetFieldID(clazz, stat_field_names[i].pss_name, "I");
        stat_fields[i].privateDirty_field =
                env->GetFieldID(clazz, stat_field_names[i].privateDirty_name, "I");
        stat_fields[i].sharedDirty_field =
                env->GetFieldID(clazz, stat_field_names[i].sharedDirty_name, "I");
    }

    return jniRegisterNativeMethods(env, "android/os/Debug", gMethods, NELEM(gMethods));
}

}; // namespace android
