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

static void read_mapinfo(FILE *fp, stats_t* stats)
{
    char line[1024];
    int len;
    bool skip, done = false;

    unsigned start = 0, size = 0, resident = 0, pss = 0;
    unsigned shared_clean = 0, shared_dirty = 0;
    unsigned private_clean = 0, private_dirty = 0;
    unsigned referenced = 0;
    unsigned temp;

    int isNativeHeap;
    int isDalvikHeap;
    int isSqliteHeap;

    if(fgets(line, 1024, fp) == 0) return;

    while (!done) {
        isNativeHeap = 0;
        isDalvikHeap = 0;
        isSqliteHeap = 0;
        skip = false;

        len = strlen(line);
        if (len < 1) return;
        line[--len] = 0;

        /* ignore guard pages */
        if (len > 18 && line[17] == '-') skip = true;

        start = strtoul(line, 0, 16);

        if (strstr(line, "[heap]")) {
            isNativeHeap = 1;
        } else if (strstr(line, "/dalvik-LinearAlloc")) {
            isDalvikHeap = 1;
        } else if (strstr(line, "/mspace/dalvik-heap")) {
            isDalvikHeap = 1;
        } else if (strstr(line, "/dalvik-heap-bitmap/")) {
            isDalvikHeap = 1;    
        } else if (strstr(line, "/data/dalvik-cache/")) {
            isDalvikHeap = 1;
        } else if (strstr(line, "/tmp/sqlite-heap")) {
            isSqliteHeap = 1;
        }

        //LOGI("native=%d dalvik=%d sqlite=%d: %s\n", isNativeHeap, isDalvikHeap,
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
            if (isNativeHeap) {
                stats->nativePss += pss;
                stats->nativePrivateDirty += private_dirty;
                stats->nativeSharedDirty += shared_dirty;
            } else if (isDalvikHeap) {
                stats->dalvikPss += pss;
                stats->dalvikPrivateDirty += private_dirty;
                stats->dalvikSharedDirty += shared_dirty;
            } else if ( isSqliteHeap) {
                // ignore
            } else {
                stats->otherPss += pss;
                stats->otherPrivateDirty += private_dirty;
                stats->otherSharedDirty += shared_dirty;
            }
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
    stats_t stats;
    memset(&stats, 0, sizeof(stats_t));
    
    load_maps(pid, &stats);

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

static void android_os_Debug_getDirtyPages(JNIEnv *env, jobject clazz, jobject object)
{
    android_os_Debug_getDirtyPagesPid(env, clazz, getpid(), object);
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
    { "getMemoryInfo",          "(ILandroid/os/Debug$MemoryInfo;)V",
            (void*) android_os_Debug_getDirtyPagesPid },
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
