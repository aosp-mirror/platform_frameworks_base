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

namespace android {

static jfieldID gMemoryUsedField;
static jfieldID gPageCacheOverflowField;
static jfieldID gLargestMemAllocField;


#define USE_MSPACE 0

static void getPagerStats(JNIEnv *env, jobject clazz, jobject statsObj)
{
    int memoryUsed;
    int pageCacheOverflow;
    int largestMemAlloc;
    int unused;

    sqlite3_status(SQLITE_STATUS_MEMORY_USED, &memoryUsed, &unused, 0);
    sqlite3_status(SQLITE_STATUS_MALLOC_SIZE, &unused, &largestMemAlloc, 0);
    sqlite3_status(SQLITE_STATUS_PAGECACHE_OVERFLOW, &pageCacheOverflow, &unused, 0);
    env->SetIntField(statsObj, gMemoryUsedField, memoryUsed);
    env->SetIntField(statsObj, gPageCacheOverflowField, pageCacheOverflow);
    env->SetIntField(statsObj, gLargestMemAllocField, largestMemAlloc);
}

/*
 * JNI registration.
 */

static JNINativeMethod gMethods[] =
{
    { "getPagerStats", "(Landroid/database/sqlite/SQLiteDebug$PagerStats;)V",
            (void*) getPagerStats },
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

    gPageCacheOverflowField = env->GetFieldID(clazz, "pageCacheOverflow", "I");
    if (gPageCacheOverflowField == NULL) {
        ALOGE("Can't find pageCacheOverflow");
        return -1;
    }

    return jniRegisterNativeMethods(env, "android/database/sqlite/SQLiteDebug",
            gMethods, NELEM(gMethods));
}

} // namespace android
