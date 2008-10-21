/*
**
** Copyright 2008, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#ifdef LOG_TAG
#undef LOG_TAG
#define LOG_TAG "MediaMetadataRetriever"
#endif

#include <assert.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include <graphics/SkBitmap.h>
#include <media/mediametadataretriever.h>
#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"

using namespace android;

static const char* const kClassPathName = "android/media/MediaMetadataRetriever";

static void process_media_retriever_call(JNIEnv *env, status_t opStatus, const char* exception, const char *message)
{
    if (opStatus == (status_t) INVALID_OPERATION) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
    } else if (opStatus != (status_t) OK) {
        if (strlen(message) > 230) {
            // If the message is too long, don't bother displaying the status code.
            jniThrowException( env, exception, message);
        } else {
            char msg[256];
            // Append the status code to the message.
            sprintf(msg, "%s: status = 0x%X", message, opStatus);
            jniThrowException( env, exception, msg);
        }
    }
}

static void android_media_MediaMetadataRetriever_setMode(JNIEnv *env, jobject thiz, jint mode)
{
    MediaMetadataRetriever::setMode(mode);
}

static void android_media_MediaMetadataRetriever_setDataSource(JNIEnv *env, jobject thiz, jstring path)
{
    if (!path) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Null pointer");
        return;
    }

    const char *pathStr = env->GetStringUTFChars(path, NULL);
    if (!pathStr) {  // OutOfMemoryError exception already thrown
        return;
    }

    // Don't let somebody trick us in to reading some random block of memory
    if (strncmp("mem://", pathStr, 6) == 0) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "Invalid pathname");
        return;
    }

    process_media_retriever_call(env, MediaMetadataRetriever::setDataSource(pathStr), "java/lang/RuntimeException", "setDataSource failed");
    env->ReleaseStringUTFChars(path, pathStr);
}

static jobject android_media_MediaMetadataRetriever_extractMetadata(JNIEnv *env, jobject thiz, jint keyCode)
{
    const char* value = MediaMetadataRetriever::extractMetadata(keyCode);
    if (!value) {
        LOGV("extractMetadata: Metadata is not found");
        return NULL;
    }
    LOGV("extractMetadata: value (%s) for keyCode(%d)", value, keyCode);
    return env->NewStringUTF(value);
}

static jobject android_media_MediaMetadataRetriever_captureFrame(JNIEnv *env, jobject thiz)
{
    // Call native MediaMetadataRetriever::captureFrame method
    SkBitmap *bitmap = MediaMetadataRetriever::captureFrame();
    if (!bitmap) {
        return NULL;
    }

    // Create the bitmap by calling into Java!
    jclass bitmapClazz = env->FindClass("android/graphics/Bitmap");
    if (!bitmapClazz) {
        LOGE("captureFrame: Bitmap class is not found");
        return NULL;
    }
    jmethodID constructor = env->GetMethodID(bitmapClazz, "<init>", "(IZ[B)V");
    if (!constructor) {
        LOGE("captureFrame: Bitmap constructor is not found");
        return NULL;
    }
    return env->NewObject(bitmapClazz, constructor, (int) bitmap, true, NULL);
}

static jbyteArray android_media_MediaMetadataRetriever_extractAlbumArt(JNIEnv *env, jobject thiz)
{
    MediaAlbumArt* mediaAlbumArt = MediaMetadataRetriever::extractAlbumArt();
    if (!mediaAlbumArt) {
        LOGE("extractAlbumArt: Call to extractAlbumArt failed.");
        return NULL;
    }

    unsigned int len = mediaAlbumArt->getLength();
    char* data = mediaAlbumArt->getData();
    jbyteArray array = env->NewByteArray(len);
    if (!array) {  // OutOfMemoryError exception has already been thrown.
        LOGE("extractAlbumArt: OutOfMemoryError is thrown.");
    } else {
        jbyte* bytes = env->GetByteArrayElements(array, NULL);
        memcpy(bytes, data, len);
        env->ReleaseByteArrayElements(array, bytes, 0);
    }
    delete []data;
    delete mediaAlbumArt;
    return array;
}

static void android_media_MediaMetadataRetriever_release(JNIEnv *env, jobject thiz)
{
    MediaMetadataRetriever::release();
}

static void android_media_MediaMetadataRetriever_native_finalize(JNIEnv *env, jobject thiz)
{
    MediaMetadataRetriever::release();
}

static void android_media_MediaMetadataRetriever_native_setup(JNIEnv *env, jobject thiz)
{
    MediaMetadataRetriever::create();
}

// JNI mapping between Java methods and native methods
static JNINativeMethod nativeMethods[] = {
        {"setMode",         "(I)V", (void *)android_media_MediaMetadataRetriever_setMode},
        {"setDataSource",   "(Ljava/lang/String;)V", (void *)android_media_MediaMetadataRetriever_setDataSource},
        {"captureFrame",    "()Landroid/graphics/Bitmap;", (void *)android_media_MediaMetadataRetriever_captureFrame},
        {"extractMetadata", "(I)Ljava/lang/String;", (void *)android_media_MediaMetadataRetriever_extractMetadata},
        {"extractAlbumArt", "()[B", (void *)android_media_MediaMetadataRetriever_extractAlbumArt},
        {"release",         "()V", (void *)android_media_MediaMetadataRetriever_release},
        {"native_finalize", "()V", (void *)android_media_MediaMetadataRetriever_native_finalize},
        {"native_setup",    "()V", (void *)android_media_MediaMetadataRetriever_native_setup},
};

// Register native mehtods with Android runtime environment
int register_android_media_MediaMetadataRetriever(JNIEnv *env)
{
    jclass clazz = env->FindClass(kClassPathName);
    if (clazz == NULL) {
        LOGE("Can't find class: %s", kClassPathName);
        return -1;
    }

    return AndroidRuntime::registerNativeMethods
    (env, kClassPathName, nativeMethods, NELEM(nativeMethods));
}
