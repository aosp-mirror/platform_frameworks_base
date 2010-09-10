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

#include <surfaceflinger/SurfaceComposerClient.h>
#include <camera/ICameraService.h>
#include <camera/Camera.h>
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
extern sp<Camera> get_native_camera(JNIEnv *env, jobject thiz, struct JNICameraContext** context);

struct fields_t {
    jfieldID    context;
    jfieldID    surface;
    /* actually in android.view.Surface XXX */
    jfieldID    surface_native;

    jmethodID   post_event;
};
static fields_t fields;

static Mutex sLock;

// ----------------------------------------------------------------------------
// ref-counted object for callbacks
class JNIMediaRecorderListener: public MediaRecorderListener
{
public:
    JNIMediaRecorderListener(JNIEnv* env, jobject thiz, jobject weak_thiz);
    ~JNIMediaRecorderListener();
    void notify(int msg, int ext1, int ext2);
private:
    JNIMediaRecorderListener();
    jclass      mClass;     // Reference to MediaRecorder class
    jobject     mObject;    // Weak ref to MediaRecorder Java object to call on
};

JNIMediaRecorderListener::JNIMediaRecorderListener(JNIEnv* env, jobject thiz, jobject weak_thiz)
{

    // Hold onto the MediaRecorder class for use in calling the static method
    // that posts events to the application thread.
    jclass clazz = env->GetObjectClass(thiz);
    if (clazz == NULL) {
        LOGE("Can't find android/media/MediaRecorder");
        jniThrowException(env, "java/lang/Exception", NULL);
        return;
    }
    mClass = (jclass)env->NewGlobalRef(clazz);

    // We use a weak reference so the MediaRecorder object can be garbage collected.
    // The reference is only used as a proxy for callbacks.
    mObject  = env->NewGlobalRef(weak_thiz);
}

JNIMediaRecorderListener::~JNIMediaRecorderListener()
{
    // remove global references
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->DeleteGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
}

void JNIMediaRecorderListener::notify(int msg, int ext1, int ext2)
{
    LOGV("JNIMediaRecorderListener::notify");

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->CallStaticVoidMethod(mClass, fields.post_event, mObject, msg, ext1, ext2, 0);
}

// ----------------------------------------------------------------------------

static sp<Surface> get_surface(JNIEnv* env, jobject clazz)
{
    LOGV("get_surface");
    Surface* const p = (Surface*)env->GetIntField(clazz, fields.surface_native);
    return sp<Surface>(p);
}

// Returns true if it throws an exception.
static bool process_media_recorder_call(JNIEnv *env, status_t opStatus, const char* exception, const char* message)
{
    LOGV("process_media_recorder_call");
    if (opStatus == (status_t)INVALID_OPERATION) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return true;
    } else if (opStatus != (status_t)OK) {
        jniThrowException(env, exception, message);
        return true;
    }
    return false;
}

static sp<MediaRecorder> getMediaRecorder(JNIEnv* env, jobject thiz)
{
    Mutex::Autolock l(sLock);
    MediaRecorder* const p = (MediaRecorder*)env->GetIntField(thiz, fields.context);
    return sp<MediaRecorder>(p);
}

static sp<MediaRecorder> setMediaRecorder(JNIEnv* env, jobject thiz, const sp<MediaRecorder>& recorder)
{
    Mutex::Autolock l(sLock);
    sp<MediaRecorder> old = (MediaRecorder*)env->GetIntField(thiz, fields.context);
    if (recorder.get()) {
        recorder->incStrong(thiz);
    }
    if (old != 0) {
        old->decStrong(thiz);
    }
    env->SetIntField(thiz, fields.context, (int)recorder.get());
    return old;
}


static void android_media_MediaRecorder_setCamera(JNIEnv* env, jobject thiz, jobject camera)
{
    // we should not pass a null camera to get_native_camera() call.
    if (camera == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", "camera object is a NULL pointer");
        return;
    }
    sp<Camera> c = get_native_camera(env, camera, NULL);
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
    process_media_recorder_call(env, mr->setCamera(c->remote()),
            "java/lang/RuntimeException", "setCamera failed.");
}

static void
android_media_MediaRecorder_setVideoSource(JNIEnv *env, jobject thiz, jint vs)
{
    LOGV("setVideoSource(%d)", vs);
    if (vs < VIDEO_SOURCE_DEFAULT || vs >= VIDEO_SOURCE_LIST_END) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid video source");
        return;
    }
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
    process_media_recorder_call(env, mr->setVideoSource(vs), "java/lang/RuntimeException", "setVideoSource failed.");
}

static void
android_media_MediaRecorder_setAudioSource(JNIEnv *env, jobject thiz, jint as)
{
    LOGV("setAudioSource(%d)", as);
    if (as < AUDIO_SOURCE_DEFAULT || as >= AUDIO_SOURCE_LIST_END) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid audio source");
        return;
    }

    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
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
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
    process_media_recorder_call(env, mr->setOutputFormat(of), "java/lang/RuntimeException", "setOutputFormat failed.");
}

static void
android_media_MediaRecorder_setVideoEncoder(JNIEnv *env, jobject thiz, jint ve)
{
    LOGV("setVideoEncoder(%d)", ve);
    if (ve < VIDEO_ENCODER_DEFAULT || ve >= VIDEO_ENCODER_LIST_END) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid video encoder");
        return;
    }
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
    process_media_recorder_call(env, mr->setVideoEncoder(ve), "java/lang/RuntimeException", "setVideoEncoder failed.");
}

static void
android_media_MediaRecorder_setAudioEncoder(JNIEnv *env, jobject thiz, jint ae)
{
    LOGV("setAudioEncoder(%d)", ae);
    if (ae < AUDIO_ENCODER_DEFAULT || ae >= AUDIO_ENCODER_LIST_END) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid audio encoder");
        return;
    }
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
    process_media_recorder_call(env, mr->setAudioEncoder(ae), "java/lang/RuntimeException", "setAudioEncoder failed.");
}

static void
android_media_MediaRecorder_setParameter(JNIEnv *env, jobject thiz, jstring params)
{
    LOGV("setParameter()");
    if (params == NULL)
    {
        LOGE("Invalid or empty params string.  This parameter will be ignored.");
        return;
    }

    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);

    const char* params8 = env->GetStringUTFChars(params, NULL);
    if (params8 == NULL)
    {
        LOGE("Failed to covert jstring to String8.  This parameter will be ignored.");
        return;
    }

    process_media_recorder_call(env, mr->setParameters(String8(params8)), "java/lang/RuntimeException", "setParameter failed.");
    env->ReleaseStringUTFChars(params,params8);
}

static void
android_media_MediaRecorder_setOutputFileFD(JNIEnv *env, jobject thiz, jobject fileDescriptor, jlong offset, jlong length)
{
    LOGV("setOutputFile");
    if (fileDescriptor == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    int fd = getParcelFileDescriptorFD(env, fileDescriptor);
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
    status_t opStatus = mr->setOutputFile(fd, offset, length);
    process_media_recorder_call(env, opStatus, "java/io/IOException", "setOutputFile failed.");
}

static void
android_media_MediaRecorder_setVideoSize(JNIEnv *env, jobject thiz, jint width, jint height)
{
    LOGV("setVideoSize(%d, %d)", width, height);
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);

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
    if (rate <= 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "invalid frame rate");
        return;
    }
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
    process_media_recorder_call(env, mr->setVideoFrameRate(rate), "java/lang/RuntimeException", "setVideoFrameRate failed.");
}

static void
android_media_MediaRecorder_setMaxDuration(JNIEnv *env, jobject thiz, jint max_duration_ms)
{
    LOGV("setMaxDuration(%d)", max_duration_ms);
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);

    char params[64];
    sprintf(params, "max-duration=%d", max_duration_ms);

    process_media_recorder_call(env, mr->setParameters(String8(params)), "java/lang/RuntimeException", "setMaxDuration failed.");
}

static void
android_media_MediaRecorder_setMaxFileSize(
        JNIEnv *env, jobject thiz, jlong max_filesize_bytes)
{
    LOGV("setMaxFileSize(%lld)", max_filesize_bytes);
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);

    char params[64];
    sprintf(params, "max-filesize=%lld", max_filesize_bytes);

    process_media_recorder_call(env, mr->setParameters(String8(params)), "java/lang/RuntimeException", "setMaxFileSize failed.");
}

static void
android_media_MediaRecorder_prepare(JNIEnv *env, jobject thiz)
{
    LOGV("prepare");
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);

    jobject surface = env->GetObjectField(thiz, fields.surface);
    if (surface != NULL) {
        const sp<Surface> native_surface = get_surface(env, surface);

        // The application may misbehave and
        // the preview surface becomes unavailable
        if (native_surface.get() == 0) {
            LOGE("Application lost the surface");
            jniThrowException(env, "java/io/IOException", "invalid preview surface");
            return;
        }

        LOGI("prepare: surface=%p (identity=%d)", native_surface.get(), native_surface->getIdentity());
        if (process_media_recorder_call(env, mr->setPreviewSurface(native_surface), "java/lang/RuntimeException", "setPreviewSurface failed.")) {
            return;
        }
    }
    process_media_recorder_call(env, mr->prepare(), "java/io/IOException", "prepare failed.");
}

static int
android_media_MediaRecorder_native_getMaxAmplitude(JNIEnv *env, jobject thiz)
{
    LOGV("getMaxAmplitude");
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
    int result = 0;
    process_media_recorder_call(env, mr->getMaxAmplitude(&result), "java/lang/RuntimeException", "getMaxAmplitude failed.");
    return result;
}

static void
android_media_MediaRecorder_start(JNIEnv *env, jobject thiz)
{
    LOGV("start");
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
    process_media_recorder_call(env, mr->start(), "java/lang/RuntimeException", "start failed.");
}

static void
android_media_MediaRecorder_stop(JNIEnv *env, jobject thiz)
{
    LOGV("stop");
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
    process_media_recorder_call(env, mr->stop(), "java/lang/RuntimeException", "stop failed.");
}

static void
android_media_MediaRecorder_native_reset(JNIEnv *env, jobject thiz)
{
    LOGV("native_reset");
    sp<MediaRecorder> mr = getMediaRecorder(env, thiz);
    process_media_recorder_call(env, mr->reset(), "java/lang/RuntimeException", "native_reset failed.");
}

static void
android_media_MediaRecorder_release(JNIEnv *env, jobject thiz)
{
    LOGV("release");
    sp<MediaRecorder> mr = setMediaRecorder(env, thiz, 0);
    if (mr != NULL) {
        mr->setListener(NULL);
        mr->release();
    }
}

// This function gets some field IDs, which in turn causes class initialization.
// It is called from a static block in MediaRecorder, which won't run until the
// first time an instance of this class is used.
static void
android_media_MediaRecorder_native_init(JNIEnv *env)
{
    jclass clazz;

    clazz = env->FindClass("android/media/MediaRecorder");
    if (clazz == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find android/media/MediaRecorder");
        return;
    }

    fields.context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (fields.context == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find MediaRecorder.mNativeContext");
        return;
    }

    fields.surface = env->GetFieldID(clazz, "mSurface", "Landroid/view/Surface;");
    if (fields.surface == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find MediaRecorder.mSurface");
        return;
    }

    jclass surface = env->FindClass("android/view/Surface");
    if (surface == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find android/view/Surface");
        return;
    }

    fields.surface_native = env->GetFieldID(surface, ANDROID_VIEW_SURFACE_JNI_ID, "I");
    if (fields.surface_native == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find Surface.mSurface");
        return;
    }

    fields.post_event = env->GetStaticMethodID(clazz, "postEventFromNative",
                                               "(Ljava/lang/Object;IIILjava/lang/Object;)V");
    if (fields.post_event == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "MediaRecorder.postEventFromNative");
        return;
    }
}


static void
android_media_MediaRecorder_native_setup(JNIEnv *env, jobject thiz, jobject weak_this)
{
    LOGV("setup");
    sp<MediaRecorder> mr = new MediaRecorder();
    if (mr == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }
    if (mr->initCheck() != NO_ERROR) {
        jniThrowException(env, "java/lang/RuntimeException", "Unable to initialize media recorder");
        return;
    }

    // create new listener and give it to MediaRecorder
    sp<JNIMediaRecorderListener> listener = new JNIMediaRecorderListener(env, thiz, weak_this);
    mr->setListener(listener);

    setMediaRecorder(env, thiz, mr);
}

static void
android_media_MediaRecorder_native_finalize(JNIEnv *env, jobject thiz)
{
    LOGV("finalize");
    android_media_MediaRecorder_release(env, thiz);
}

// ----------------------------------------------------------------------------

static JNINativeMethod gMethods[] = {
    {"setCamera",            "(Landroid/hardware/Camera;)V",    (void *)android_media_MediaRecorder_setCamera},
    {"setVideoSource",       "(I)V",                            (void *)android_media_MediaRecorder_setVideoSource},
    {"setAudioSource",       "(I)V",                            (void *)android_media_MediaRecorder_setAudioSource},
    {"setOutputFormat",      "(I)V",                            (void *)android_media_MediaRecorder_setOutputFormat},
    {"setVideoEncoder",      "(I)V",                            (void *)android_media_MediaRecorder_setVideoEncoder},
    {"setAudioEncoder",      "(I)V",                            (void *)android_media_MediaRecorder_setAudioEncoder},
    {"setParameter",         "(Ljava/lang/String;)V",           (void *)android_media_MediaRecorder_setParameter},
    {"_setOutputFile",       "(Ljava/io/FileDescriptor;JJ)V",   (void *)android_media_MediaRecorder_setOutputFileFD},
    {"setVideoSize",         "(II)V",                           (void *)android_media_MediaRecorder_setVideoSize},
    {"setVideoFrameRate",    "(I)V",                            (void *)android_media_MediaRecorder_setVideoFrameRate},
    {"setMaxDuration",       "(I)V",                            (void *)android_media_MediaRecorder_setMaxDuration},
    {"setMaxFileSize",       "(J)V",                            (void *)android_media_MediaRecorder_setMaxFileSize},
    {"_prepare",             "()V",                             (void *)android_media_MediaRecorder_prepare},
    {"getMaxAmplitude",      "()I",                             (void *)android_media_MediaRecorder_native_getMaxAmplitude},
    {"start",                "()V",                             (void *)android_media_MediaRecorder_start},
    {"stop",                 "()V",                             (void *)android_media_MediaRecorder_stop},
    {"native_reset",         "()V",                             (void *)android_media_MediaRecorder_native_reset},
    {"release",              "()V",                             (void *)android_media_MediaRecorder_release},
    {"native_init",          "()V",                             (void *)android_media_MediaRecorder_native_init},
    {"native_setup",         "(Ljava/lang/Object;)V",           (void *)android_media_MediaRecorder_native_setup},
    {"native_finalize",      "()V",                             (void *)android_media_MediaRecorder_native_finalize},
};

static const char* const kClassPathName = "android/media/MediaRecorder";

// This function only registers the native methods, and is called from
// JNI_OnLoad in android_media_MediaPlayer.cpp
int register_android_media_MediaRecorder(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaRecorder", gMethods, NELEM(gMethods));
}


