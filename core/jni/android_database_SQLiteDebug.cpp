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

#include <JNIHelp.h>
#include <jni.h>
#include <utils/misc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <cutils/mspace.h>
#include <utils/Log.h>

#include <sqlite3.h>

// From mem_mspace.c in libsqlite
extern "C" mspace sqlite3_get_mspace();

namespace android {

static jfieldID gMemoryUsedField;
static jfieldID gPageCacheOverfloField;
static jfieldID gLargestMemAllocField;


#define USE_MSPACE 0

static void getPagerStats(JNIEnv *env, jobject clazz, jobject statsObj)
{
    int memoryUsed;
    int pageCacheOverflo;
    int largestMemAlloc;
    int unused;

    sqlite3_status(SQLITE_STATUS_MEMORY_USED, &memoryUsed, &unused, 0);
    sqlite3_status(SQLITE_STATUS_MALLOC_SIZE, &unused, &largestMemAlloc, 0);
    sqlite3_status(SQLITE_STATUS_PAGECACHE_OVERFLOW, &pageCacheOverflo, &unused, 0);
    env->SetIntField(statsObj, gMemoryUsedField, memoryUsed);
    env->SetIntField(statsObj, gPageCacheOverfloField, pageCacheOverflo);
    env->SetIntField(statsObj, gLargestMemAllocField, largestMemAlloc);
}

static jlong getHeapSize(JNIEnv *env, jobject clazz)
{
#if !NO_MALLINFO
    struct mallinfo info = mspace_mallinfo(sqlite3_get_mspace());
    struct mallinfo info = dlmallinfo();
    return (jlong) info.usmblks;
#elif USE_MSPACE
    mspace space = sqlite3_get_mspace();
    if (space != 0) {
        return mspace_footprint(space);
    } else {
        return 0;
    }
#else
    return 0;
#endif
}

static jlong getHeapAllocatedSize(JNIEnv *env, jobject clazz)
{
#if !NO_MALLINFO
    struct mallinfo info = mspace_mallinfo(sqlite3_get_mspace());
    return (jlong) info.uordblks;
#else
    return sqlite3_memory_used();
#endif
}

static jlong getHeapFreeSize(JNIEnv *env, jobject clazz)
{
#if !NO_MALLINFO
    struct mallinfo info = mspace_mallinfo(sqlite3_get_mspace());
    return (jlong) info.fordblks;
#else
    return getHeapSize(env, clazz) - sqlite3_memory_used();
#endif
}

static int read_mapinfo(FILE *fp,
        int *sharedPages, int *privatePages)
{
    char line[1024];
    int len;
    int skip;

    unsigned start = 0, size = 0, resident = 0;
    unsigned shared_clean = 0, shared_dirty = 0;
    unsigned private_clean = 0, private_dirty = 0;
    unsigned referenced = 0;

    int isAnon = 0;
    int isHeap = 0;

again:
    skip = 0;
    
    if(fgets(line, 1024, fp) == 0) return 0;

    len = strlen(line);
    if (len < 1) return 0;
    line[--len] = 0;

    /* ignore guard pages */
    if (line[18] == '-') skip = 1;

    start = strtoul(line, 0, 16);

    if (len > 50 && !strncmp(line + 49, "/tmp/sqlite-heap", strlen("/tmp/sqlite-heap"))) {
        isHeap = 1;
    }

    if (fgets(line, 1024, fp) == 0) return 0;
    if (sscanf(line, "Size: %d kB", &size) != 1) return 0;
    if (fgets(line, 1024, fp) == 0) return 0;
    if (sscanf(line, "Rss: %d kB", &resident) != 1) return 0;
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

    if (isHeap) {
        *sharedPages += shared_dirty;
        *privatePages += private_dirty;
    }
    return 1;
}

static void load_maps(int pid, int *sharedPages, int *privatePages)
{
    char tmp[128];
    FILE *fp;
    
    sprintf(tmp, "/proc/%d/smaps", pid);
    fp = fopen(tmp, "r");
    if (fp == 0) return;
    
    while (read_mapinfo(fp, sharedPages, privatePages) != 0) {
        // Do nothing
    }
    fclose(fp);
}

static void getHeapDirtyPages(JNIEnv *env, jobject clazz, jintArray pages)
{
    int _pages[2];

    _pages[0] = 0;
    _pages[1] = 0;

    load_maps(getpid(), &_pages[0], &_pages[1]);

    // Convert from kbytes to 4K pages
    _pages[0] /= 4;
    _pages[1] /= 4;

    env->SetIntArrayRegion(pages, 0, 2, _pages);
}

/*
 * JNI registration.
 */

static JNINativeMethod gMethods[] =
{
    { "getPagerStats", "(Landroid/database/sqlite/SQLiteDebug$PagerStats;)V",
            (void*) getPagerStats },
    { "getHeapSize", "()J", (void*) getHeapSize },
    { "getHeapAllocatedSize", "()J", (void*) getHeapAllocatedSize },
    { "getHeapFreeSize", "()J", (void*) getHeapFreeSize },
    { "getHeapDirtyPages", "([I)V", (void*) getHeapDirtyPages },
};

int register_android_database_SQLiteDebug(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/database/sqlite/SQLiteDebug$PagerStats");
    if (clazz == NULL) {
        ALOGE("Can't find android/database/sqlite/SQLiteDebug$PagerStats");
        return -1;
    }

    gMemoryUsedField = env->GetFieldID(clazz, "memoryUsed", "I");
    if (gMemoryUsedField == NULL) {
        ALOGE("Can't find memoryUsed");
        return -1;
    }

    gLargestMemAllocField = env->GetFieldID(clazz, "largestMemAlloc", "I");
    if (gLargestMemAllocField == NULL) {
        ALOGE("Can't find largestMemAlloc");
        return -1;
    }

    gPageCacheOverfloField = env->GetFieldID(clazz, "pageCacheOverflo", "I");
    if (gPageCacheOverfloField == NULL) {
        ALOGE("Can't find pageCacheOverflo");
        return -1;
    }

    return jniRegisterNativeMethods(env, "android/database/sqlite/SQLiteDebug",
            gMethods, NELEM(gMethods));
}

} // namespace android
