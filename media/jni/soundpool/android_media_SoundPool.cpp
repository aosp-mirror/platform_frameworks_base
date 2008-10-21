/*
 * Copyright (C) 2008 The Android Open Source Project
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

#include <stdio.h>

//#define LOG_NDEBUG 0
#define LOG_TAG "SoundPool"

#include <utils/Log.h>
#include <nativehelper/jni.h>
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include "SoundPool.h"

using namespace android;

static struct fields_t {
    jfieldID    mNativeContext;
    jclass      mSoundPoolClass;
} fields;

static inline SoundPool* MusterSoundPool(JNIEnv *env, jobject thiz) {
    return (SoundPool*)env->GetIntField(thiz, fields.mNativeContext);
}

// ----------------------------------------------------------------------------
static int
android_media_SoundPool_load_URL(JNIEnv *env, jobject thiz, jstring path, jint priority)
{
    LOGV("android_media_SoundPool_load_URL");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (path == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return 0;
    }
    const char* s = env->GetStringUTFChars(path, NULL);
    int id = ap->load(s, priority);
    env->ReleaseStringUTFChars(path, s);
    return id;
}

static int
android_media_SoundPool_load_FD(JNIEnv *env, jobject thiz, jobject fileDescriptor,
        jlong offset, jlong length, jint priority)
{
    LOGV("android_media_SoundPool_load_FD");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (ap == NULL) return 0;
    return ap->load(getParcelFileDescriptorFD(env, fileDescriptor),
            int64_t(offset), int64_t(length), int(priority));
}

static bool
android_media_SoundPool_unload(JNIEnv *env, jobject thiz, jint sampleID) {
    LOGV("android_media_SoundPool_unload\n");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (ap == NULL) return 0;
    return ap->unload(sampleID);
}

static int
android_media_SoundPool_play(JNIEnv *env, jobject thiz, jint sampleID,
        jfloat leftVolume, jfloat rightVolume, jint priority, jint loop,
        jfloat rate)
{
    LOGV("android_media_SoundPool_play\n");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (ap == NULL) return 0;
    return ap->play(sampleID, leftVolume, rightVolume, priority, loop, rate);
}

static void
android_media_SoundPool_pause(JNIEnv *env, jobject thiz, jint channelID)
{
    LOGV("android_media_SoundPool_pause");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (ap == NULL) return;
    ap->pause(channelID);
}

static void
android_media_SoundPool_resume(JNIEnv *env, jobject thiz, jint channelID)
{
    LOGV("android_media_SoundPool_resume");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (ap == NULL) return;
    ap->resume(channelID);
}

static void
android_media_SoundPool_stop(JNIEnv *env, jobject thiz, jint channelID)
{
    LOGV("android_media_SoundPool_stop");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (ap == NULL) return;
    ap->stop(channelID);
}

static void
android_media_SoundPool_setVolume(JNIEnv *env, jobject thiz, jint channelID,
        float leftVolume, float rightVolume)
{
    LOGV("android_media_SoundPool_setVolume");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (ap == NULL) return;
    ap->setVolume(channelID, leftVolume, rightVolume);
}

static void
android_media_SoundPool_setPriority(JNIEnv *env, jobject thiz, jint channelID,
        int priority)
{
    LOGV("android_media_SoundPool_setPriority");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (ap == NULL) return;
    ap->setPriority(channelID, priority);
}

static void
android_media_SoundPool_setLoop(JNIEnv *env, jobject thiz, jint channelID,
        int loop)
{
    LOGV("android_media_SoundPool_setLoop");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (ap == NULL) return;
    ap->setLoop(channelID, loop);
}

static void
android_media_SoundPool_setRate(JNIEnv *env, jobject thiz, jint channelID,
        float rate)
{
    LOGV("android_media_SoundPool_setRate");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (ap == NULL) return;
    ap->setRate(channelID, rate);
}

static void
android_media_SoundPool_native_setup(JNIEnv *env, jobject thiz,
        jobject weak_this, jint maxChannels, jint streamType, jint srcQuality)
{
    LOGV("android_media_SoundPool_native_setup");
    SoundPool *ap = new SoundPool(weak_this, maxChannels, streamType, srcQuality);
    if (ap == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }

    // save pointer to SoundPool C++ object in opaque field in Java object
    env->SetIntField(thiz, fields.mNativeContext, (int)ap);
}

static void
android_media_SoundPool_release(JNIEnv *env, jobject thiz)
{
    LOGV("android_media_SoundPool_release");
    SoundPool *ap = MusterSoundPool(env, thiz);
    if (ap != NULL) {
        env->SetIntField(thiz, fields.mNativeContext, 0);
        delete ap;
    }
}

// ----------------------------------------------------------------------------

// Dalvik VM type signatures
static JNINativeMethod gMethods[] = {
    {   "_load",
        "(Ljava/lang/String;I)I",
        (void *)android_media_SoundPool_load_URL
    },
    {   "_load",
        "(Ljava/io/FileDescriptor;JJI)I",
        (void *)android_media_SoundPool_load_FD
    },
    {   "unload",
        "(I)Z",
        (void *)android_media_SoundPool_unload
    },
    {   "play",
        "(IFFIIF)I",
        (void *)android_media_SoundPool_play
    },
    {   "pause",
        "(I)V",
        (void *)android_media_SoundPool_pause
    },
    {   "resume",
        "(I)V",
        (void *)android_media_SoundPool_resume
    },
    {   "stop",
        "(I)V",
        (void *)android_media_SoundPool_stop
    },
    {   "setVolume",
        "(IFF)V",
        (void *)android_media_SoundPool_setVolume
    },
    {   "setPriority",
        "(II)V",
        (void *)android_media_SoundPool_setPriority
    },
    {   "setLoop",
        "(II)V",
        (void *)android_media_SoundPool_setLoop
    },
    {   "setRate",
        "(IF)V",
        (void *)android_media_SoundPool_setRate
    },
    {   "native_setup",
        "(Ljava/lang/Object;III)V",
        (void*)android_media_SoundPool_native_setup
    },
    {   "release",
        "()V",
        (void*)android_media_SoundPool_release
    }
};

static const char* const kClassPathName = "android/media/SoundPool";

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    JNIEnv* env = NULL;
    jint result = -1;
    jclass clazz;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        LOGE("ERROR: GetEnv failed\n");
        goto bail;
    }
    assert(env != NULL);

    clazz = env->FindClass(kClassPathName);
    if (clazz == NULL) {
        LOGE("Can't find %s", kClassPathName);
        goto bail;
    }

    fields.mNativeContext = env->GetFieldID(clazz, "mNativeContext", "I");
    if (fields.mNativeContext == NULL) {
        LOGE("Can't find SoundPool.mNativeContext");
        goto bail;
    }

    if (AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods)) < 0)
        goto bail;

    /* success -- return valid version number */
    result = JNI_VERSION_1_4;

bail:
    return result;
}
