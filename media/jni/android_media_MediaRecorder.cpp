/*
 * Copyright (C) 2008 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaRecorderJNI"
#include <utils/Log.h>

#include <ui/SurfaceComposerClient.h>
#include <ui/ICameraService.h>
#include <ui/Camera.h>
#include <media/mediarecorder.h>
#include <stdio.h>
#include <assert.h>
#include <limits.h>
#include <unistd.h>
#include <fcntl.h>
#include <utils/threads.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"


// ----------------------------------------------------------------------------

using namespace android;

// ----------------------------------------------------------------------------

// helper function to extract a native Camera object from a Camera Java object
extern sp<Camera> get_native_camera(JNIEnv *env, jobject thiz);

struct fields_t {
    jfieldID    context;
    jfieldID    surface;
    /* actually in android.view.Surface XXX */
    jfieldID    surface_native;
};
static fields_t fields;

// ----------------------------------------------------------------------------

static sp<Surface> get_surface(JNIEnv* env, jobject clazz)
{
    LOGV("get_surface");
    Surface* const p = (Surface*)env->GetIntField(clazz, fields.surface_native);
    return sp<Surface>(p);
}

static void process_media_recorder_call(JNIEnv *env, status_t opStatus, const char* exception, const char* message)
{
    LOGV("process_media_recorder_call");
    if (opStatus == (status_t)INVALID_OPERATION) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
    } else if (opStatus != (status_t)OK) {
        jniThrowException(env, exception, message);
    }
    return;
}

static void android_media_MediaRecorder_setCamera(JNIEnv* env, jobject thiz, jobject camera)
{
    sp<Camera> c = get_native_camera(env, camera);
    MediaRecorder *mr = (MediaRecorder*)env->GetIntField(thiz, fields.context);
    process_media_recorder_call(env, mr->setCamera(c->remote()),
            "java/lang/RuntimeException", "setCamera failed.");
}

static void
android_media_MediaRecorder_setVideoSource(JNIEnv *env, jobject thiz, jint vs)
{
    LOGV("setVideoSource(%d)", vs);
    if (vs < VIDEO_SOURCE_DEFAULT || vs > VIDEO_SOURCE_CAMERA) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid video source");
        return;
    }
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    process_media_recorder_call(env, mr->setVideoSource(vs), "java/lang/RuntimeException", "setVideoSource failed.");
}

static void
android_media_MediaRecorder_setAudioSource(JNIEnv *env, jobject thiz, jint as)
{
    LOGV("setAudioSource(%d)", as);
    if (as < AUDIO_SOURCE_DEFAULT || as > AUDIO_SOURCE_MIC) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid audio source");
        return;
    }
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    process_media_recorder_call(env, mr->setAudioSource(as), "java/lang/RuntimeException", "setAudioSource failed.");
}

static void
android_media_MediaRecorder_setOutputFormat(JNIEnv *env, jobject thiz, jint of)
{
    LOGV("setOutputFormat(%d)", of);
    if (of < OUTPUT_FORMAT_DEFAULT || of >= OUTPUT_FORMAT_LIST_END) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid output format");
        return;
    }
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    process_media_recorder_call(env, mr->setOutputFormat(of), "java/lang/RuntimeException", "setOutputFormat failed.");
}

static void
android_media_MediaRecorder_setVideoEncoder(JNIEnv *env, jobject thiz, jint ve)
{
    LOGV("setVideoEncoder(%d)", ve);
    if (ve < VIDEO_ENCODER_DEFAULT || ve > VIDEO_ENCODER_MPEG_4_SP) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid video encoder");
        return;
    }
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    process_media_recorder_call(env, mr->setVideoEncoder(ve), "java/lang/RuntimeException", "setVideoEncoder failed.");
}

static void
android_media_MediaRecorder_setAudioEncoder(JNIEnv *env, jobject thiz, jint ae)
{
    LOGV("setAudioEncoder(%d)", ae);
    if (ae < AUDIO_ENCODER_DEFAULT || ae > AUDIO_ENCODER_AMR_NB) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid audio encoder");
        return;
    }
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    process_media_recorder_call(env, mr->setAudioEncoder(ae), "java/lang/RuntimeException", "setAudioEncoder failed.");
}

static void
android_media_MediaRecorder_setOutputFile(JNIEnv *env, jobject thiz, jstring path)
{
    LOGV("setOutputFile");
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);

    if (path == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Path is a NULL pointer");
        return;
    }
    const char *pathStr = env->GetStringUTFChars(path, NULL);
    if (pathStr == NULL) {  // Out of memory
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }
    status_t opStatus = mr->setOutputFile(pathStr);

    // Make sure that local ref is released before a potential exception
    env->ReleaseStringUTFChars(path, pathStr);
    process_media_recorder_call(env, opStatus, "java/lang/RuntimeException", "setOutputFile failed.");
}

static void
android_media_MediaRecorder_setVideoSize(JNIEnv *env, jobject thiz, jint width, jint height)
{
    LOGV("setVideoSize(%d, %d)", width, height);
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);

    if (width <= 0 || height <= 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "invalid video size");
        return;
    }
    process_media_recorder_call(env, mr->setVideoSize(width, height), "java/lang/RuntimeException", "setVideoSize failed.");
}

static void
android_media_MediaRecorder_setVideoFrameRate(JNIEnv *env, jobject thiz, jint rate)
{
    LOGV("setVideoFrameRate(%d)", rate);
    if (rate <= 0 || rate > MEDIA_RECORDER_MAX_FRAME_RATE) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "invalid frame rate");
        return;
    }
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    process_media_recorder_call(env, mr->setVideoFrameRate(rate), "java/lang/RuntimeException", "setVideoFrameRate failed.");
}

static void
android_media_MediaRecorder_prepare(JNIEnv *env, jobject thiz)
{
    LOGV("prepare");
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);

    jobject surface = env->GetObjectField(thiz, fields.surface);
    if (surface != NULL) {
        const sp<Surface>& native_surface = get_surface(env, surface);
        LOGI("prepare: surface=%p (id=%d)", native_surface.get(), native_surface->ID());
        process_media_recorder_call(env, mr->setPreviewSurface(native_surface), "java/lang/RuntimeException", "setPreviewSurface failed.");
    }
    process_media_recorder_call(env, mr->prepare(), "java/io/IOException", "prepare failed.");
}

static int
android_media_MediaRecorder_native_getMaxAmplitude(JNIEnv *env, jobject thiz)
{
    LOGV("getMaxAmplitude");
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    int result = 0;
    process_media_recorder_call(env, mr->getMaxAmplitude(&result), "java/lang/RuntimeException", "getMaxAmplitude failed.");
    return result;
}

static void
android_media_MediaRecorder_start(JNIEnv *env, jobject thiz)
{
    LOGV("start");
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    process_media_recorder_call(env, mr->start(), "java/lang/RuntimeException", "start failed.");
}

static void
android_media_MediaRecorder_stop(JNIEnv *env, jobject thiz)
{
    LOGV("stop");
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    process_media_recorder_call(env, mr->stop(), "java/lang/RuntimeException", "stop failed.");
}

static void
android_media_MediaRecorder_reset(JNIEnv *env, jobject thiz)
{
    LOGV("reset");
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    process_media_recorder_call(env, mr->reset(), "java/lang/RuntimeException", "reset failed.");
}

static void
android_media_MediaRecorder_release(JNIEnv *env, jobject thiz)
{
    LOGV("release");
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    env->SetIntField(thiz, fields.context, 0);
    delete mr;
}

static void
android_media_MediaRecorder_native_setup(JNIEnv *env, jobject thiz)
{
    LOGV("setup");
    MediaRecorder *mr = new MediaRecorder();
    if (mr->initCheck() == NO_ERROR) {
        env->SetIntField(thiz, fields.context, (int)mr);
    } else {
        delete mr;
        jniThrowException(env, "java/lang/IOException", "Unable to initialize camera");
    }
}

static void
android_media_MediaRecorder_native_finalize(JNIEnv *env, jobject thiz)
{
    LOGV("finalize");
    MediaRecorder *mr = (MediaRecorder *)env->GetIntField(thiz, fields.context);
    delete mr;
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"setCamera",            "(Landroid/hardware/Camera;)V",(void *)android_media_MediaRecorder_setCamera},
    {"setVideoSource",       "(I)V",                    (void *)android_media_MediaRecorder_setVideoSource},
    {"setAudioSource",       "(I)V",                    (void *)android_media_MediaRecorder_setAudioSource},
    {"setOutputFormat",      "(I)V",                    (void *)android_media_MediaRecorder_setOutputFormat},
    {"setVideoEncoder",      "(I)V",                    (void *)android_media_MediaRecorder_setVideoEncoder},
    {"setAudioEncoder",      "(I)V",                    (void *)android_media_MediaRecorder_setAudioEncoder},
    {"setOutputFile",        "(Ljava/lang/String;)V",   (void *)android_media_MediaRecorder_setOutputFile},
    {"setVideoSize",         "(II)V",                   (void *)android_media_MediaRecorder_setVideoSize},
    {"setVideoFrameRate",    "(I)V",                    (void *)android_media_MediaRecorder_setVideoFrameRate},
    {"prepare",              "()V",                     (void *)android_media_MediaRecorder_prepare},
    {"getMaxAmplitude",      "()I",                     (void *)android_media_MediaRecorder_native_getMaxAmplitude},
    {"start",                "()V",                     (void *)android_media_MediaRecorder_start},
    {"stop",                 "()V",                     (void *)android_media_MediaRecorder_stop},
    {"reset",                "()V",                     (void *)android_media_MediaRecorder_reset},
    {"release",              "()V",                     (void *)android_media_MediaRecorder_release},
    {"native_setup",         "()V",                     (void *)android_media_MediaRecorder_native_setup},
    {"native_finalize",      "()V",                     (void *)android_media_MediaRecorder_native_finalize},
};

static const char* const kClassPathName = "android/media/MediaRecorder";

int register_android_media_MediaRecorder(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/media/MediaRecorder");
    if (clazz == NULL) {
        LOGE("Can't find android/media/MediaRecorder");
        return -1;
    }

    fields.context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (fields.context == NULL) {
        LOGE("Can't find MediaRecorder.mNativeContext");
        return -1;
    }

    fields.surface = env->GetFieldID(clazz, "mSurface", "Landroid/view/Surface;");
    if (fields.surface == NULL) {
        LOGE("Can't find MediaRecorder.mSurface");
        return -1;
    }

    jclass surface = env->FindClass("android/view/Surface");
    if (surface == NULL) {
        LOGE("Can't find android/view/Surface");
        return -1;
    }

    fields.surface_native = env->GetFieldID(surface, "mSurface", "I");
    if (fields.surface_native == NULL) {
        LOGE("Can't find Surface fields");
        return -1;
    }

    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaRecorder", gMethods, NELEM(gMethods));
}


