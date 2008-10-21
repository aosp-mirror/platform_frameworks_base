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

#include "JNIHelp.h"
#include "jni.h"
#include "utils/misc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <time.h>
#include <sys/time.h>

#ifdef HAVE_MALLOC_H
#include <malloc.h>
#endif

namespace android
{

static jfieldID dalvikPss_field;
static jfieldID dalvikPrivateDirty_field;
static jfieldID dalvikSharedDirty_field;
static jfieldID nativePss_field;
static jfieldID nativePrivateDirty_field;
static jfieldID nativeSharedDirty_field;
static jfieldID otherPss_field;
static jfieldID otherPrivateDirty_field;
static jfieldID otherSharedDirty_field;

struct stats_t {
    int dalvikPss;
    int dalvikPrivateDirty;
    int dalvikSharedDirty;
    
    int nativePss;
    int nativePrivateDirty;
    int nativeSharedDirty;
    
    int otherPss;
    int otherPrivateDirty;
    int otherSharedDirty;
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

static int read_mapinfo(FILE *fp, stats_t* stats)
{
    char line[1024];
    int len;
    int skip;

    unsigned start = 0, size = 0, resident = 0, pss = 0;
    unsigned shared_clean = 0, shared_dirty = 0;
    unsigned private_clean = 0, private_dirty = 0;
    unsigned referenced = 0;

    int isNativeHeap;
    int isDalvikHeap;
    int isSqliteHeap;

again:
    isNativeHeap = 0;
    isDalvikHeap = 0;
    isSqliteHeap = 0;
    skip = 0;
    
    if(fgets(line, 1024, fp) == 0) return 0;

    len = strlen(line);
    if (len < 1) return 0;
    line[--len] = 0;

    /* ignore guard pages */
    if (line[18] == '-') skip = 1;

    start = strtoul(line, 0, 16);

    if (len >= 50) {
        if (!strcmp(line + 49, "[heap]")) {
            isNativeHeap = 1;
        } else if (!strncmp(line + 49, "/dalvik-LinearAlloc", strlen("/dalvik-LinearAlloc"))) {
            isDalvikHeap = 1;
        } else if (!strncmp(line + 49, "/mspace/dalvik-heap", strlen("/mspace/dalvik-heap"))) {
            isDalvikHeap = 1;
        } else if (!strncmp(line + 49, "/dalvik-heap-bitmap/", strlen("/dalvik-heap-bitmap/"))) {
            isDalvikHeap = 1;    
        } else if (!strncmp(line + 49, "/tmp/sqlite-heap", strlen("/tmp/sqlite-heap"))) {
            isSqliteHeap = 1;
        }
    }

    // TODO: This needs to be fixed to be less fragile. If the order of this file changes or a new
    // line is add, this method will return without filling out any of the information.

    if (fgets(line, 1024, fp) == 0) return 0;
    if (sscanf(line, "Size: %d kB", &size) != 1) return 0;
    if (fgets(line, 1024, fp) == 0) return 0;
    if (sscanf(line, "Rss: %d kB", &resident) != 1) return 0;
    if (fgets(line, 1024, fp) == 0) return 0;
    if (sscanf(line, "Pss: %d kB", &pss) != 1) return 0;
    if (fgets(line, 1024, fp) == 0) return 0;
    if (sscanf(line, "Shared_Clean: %d kB", &shared_clean) != 1) return 0;
    if (fgets(line, 1024, fp) == 0) return 0;
    if (sscanf(line, "Shared_Dirty: %d kB", &shared_dirty) != 1) return 0;
    if (fgets(line, 1024, fp) == 0) return 0;
    if (sscanf(line, "Private_Clean: %d kB", &private_clean) != 1) return 0;
    if (fgets(line, 1024, fp) == 0) return 0;
    if (sscanf(line, "Private_Dirty: %d kB", &private_dirty) != 1) return 0;
    if (fgets(line, 1024, fp) == 0) return 0;
    if (sscanf(line, "Referenced: %d kB", &referenced) != 1) return 0;
    
    if (skip) {
        goto again;
    }

    if (isNativeHeap) {
        stats->nativePss += pss;
        stats->nativePrivateDirty += private_dirty;
        stats->nativeSharedDirty += shared_dirty;
    } else if (isDalvikHeap) {
        stats->dalvikPss += pss;
        stats->dalvikPrivateDirty += private_dirty;
        stats->dalvikSharedDirty += shared_dirty;
    } else if (isSqliteHeap) {
        // ignore
    } else {
        stats->otherPss += pss;
        stats->otherPrivateDirty += shared_dirty;
        stats->otherSharedDirty += private_dirty;
    }
    
    return 1;
}

static void load_maps(int pid, stats_t* stats)
{
    char tmp[128];
    FILE *fp;
    
    sprintf(tmp, "/proc/%d/smaps", pid);
    fp = fopen(tmp, "r");
    if (fp == 0) return;
    
    while (read_mapinfo(fp, stats) != 0) {
        // Do nothing
    }
    fclose(fp);
}

static void android_os_Debug_getDirtyPages(JNIEnv *env, jobject clazz, jobject object)
{
    stats_t stats;
    memset(&stats, 0, sizeof(stats_t));
    
    load_maps(getpid(), &stats);

    env->SetIntField(object, dalvikPss_field, stats.dalvikPss);
    env->SetIntField(object, dalvikPrivateDirty_field, stats.dalvikPrivateDirty);
    env->SetIntField(object, dalvikSharedDirty_field, stats.dalvikSharedDirty);
    
    env->SetIntField(object, nativePss_field, stats.nativePss);
    env->SetIntField(object, nativePrivateDirty_field, stats.nativePrivateDirty);
    env->SetIntField(object, nativeSharedDirty_field, stats.nativeSharedDirty);
    
    env->SetIntField(object, otherPss_field, stats.otherPss);
    env->SetIntField(object, otherPrivateDirty_field, stats.otherPrivateDirty);
    env->SetIntField(object, otherSharedDirty_field, stats.otherSharedDirty);
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
};

int register_android_os_Debug(JNIEnv *env)
{
    jclass clazz = env->FindClass("android/os/Debug$MemoryInfo");
    
    dalvikPss_field = env->GetFieldID(clazz, "dalvikPss", "I");
    dalvikPrivateDirty_field = env->GetFieldID(clazz, "dalvikPrivateDirty", "I");
    dalvikSharedDirty_field = env->GetFieldID(clazz, "dalvikSharedDirty", "I");

    nativePss_field = env->GetFieldID(clazz, "nativePss", "I");
    nativePrivateDirty_field = env->GetFieldID(clazz, "nativePrivateDirty", "I");
    nativeSharedDirty_field = env->GetFieldID(clazz, "nativeSharedDirty", "I");
    
    otherPss_field = env->GetFieldID(clazz, "otherPss", "I");
    otherPrivateDirty_field = env->GetFieldID(clazz, "otherPrivateDirty", "I");
    otherSharedDirty_field = env->GetFieldID(clazz, "otherSharedDirty", "I");
    
    return jniRegisterNativeMethods(env, "android/os/Debug", gMethods, NELEM(gMethods));
}

};
