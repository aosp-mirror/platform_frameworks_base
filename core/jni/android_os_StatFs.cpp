/*
 * Copyright 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#if INCLUDE_SYS_MOUNT_FOR_STATFS
#include <sys/mount.h>
#else
#include <sys/statfs.h>
#endif

#include <errno.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"


namespace android
{

// ----------------------------------------------------------------------------

struct fields_t {
    jfieldID    context;
};
static fields_t fields;

// ----------------------------------------------------------------------------

static jint
android_os_StatFs_getBlockSize(JNIEnv *env, jobject thiz)
{
    struct statfs *stat = (struct statfs *)env->GetIntField(thiz, fields.context);
    return stat->f_bsize;
}

static jint
android_os_StatFs_getBlockCount(JNIEnv *env, jobject thiz)
{
    struct statfs *stat = (struct statfs *)env->GetIntField(thiz, fields.context);
    return stat->f_blocks;
}

static jint
android_os_StatFs_getFreeBlocks(JNIEnv *env, jobject thiz)
{
    struct statfs *stat = (struct statfs *)env->GetIntField(thiz, fields.context);
    return stat->f_bfree;
}

static jint
android_os_StatFs_getAvailableBlocks(JNIEnv *env, jobject thiz)
{
    struct statfs *stat = (struct statfs *)env->GetIntField(thiz, fields.context);
    return stat->f_bavail;
}

static void
android_os_StatFs_native_restat(JNIEnv *env, jobject thiz, jstring path)
{
    if (path == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    // get the object handle
    struct statfs *stat = (struct statfs *)env->GetIntField(thiz, fields.context);
    if (stat == NULL) {
        jniThrowException(env, "java/lang/NoSuchFieldException", NULL);
        return;
    }

    const char* pathstr = env->GetStringUTFChars(path, NULL);
    if (pathstr == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }

    // note that stat will contain the new file data corresponding to
    // pathstr
    if (statfs(pathstr, stat) != 0) {
        LOGE("statfs %s failed, errno: %d", pathstr, errno);
        delete stat;
        env->SetIntField(thiz, fields.context, 0);
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
    }
    // Release pathstr
    env->ReleaseStringUTFChars(path, pathstr);
}

static void
android_os_StatFs_native_setup(JNIEnv *env, jobject thiz, jstring path)
{
    if (path == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    struct statfs* stat = new struct statfs;
    if (stat == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }
    env->SetIntField(thiz, fields.context, (int)stat);
    android_os_StatFs_native_restat(env, thiz, path);
}

static void
android_os_StatFs_native_finalize(JNIEnv *env, jobject thiz)
{
    struct statfs *stat = (struct statfs *)env->GetIntField(thiz, fields.context);
    if (stat != NULL) {
        delete stat;
        env->SetIntField(thiz, fields.context, 0);
    }
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"getBlockSize",       "()I",                       (void *)android_os_StatFs_getBlockSize},
    {"getBlockCount",      "()I",                       (void *)android_os_StatFs_getBlockCount},
    {"getFreeBlocks",      "()I",                       (void *)android_os_StatFs_getFreeBlocks},
    {"getAvailableBlocks", "()I",                       (void *)android_os_StatFs_getAvailableBlocks},
    {"native_setup",       "(Ljava/lang/String;)V",     (void *)android_os_StatFs_native_setup},
    {"native_finalize",    "()V",                       (void *)android_os_StatFs_native_finalize},
    {"native_restat",      "(Ljava/lang/String;)V",     (void *)android_os_StatFs_native_restat},
};


int register_android_os_StatFs(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/os/StatFs");
    if (clazz == NULL) {
        LOGE("Can't find android/os/StatFs");
        return -1;
    }

    fields.context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (fields.context == NULL) {
        LOGE("Can't find StatFs.mNativeContext");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
            "android/os/StatFs", gMethods, NELEM(gMethods));
}

}   // namespace android
