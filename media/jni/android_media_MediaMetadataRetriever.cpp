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

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaMetadataRetrieverJNI"

#include <assert.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include <core/SkBitmap.h>
#include <media/mediametadataretriever.h>
#include <private/media/VideoFrame.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"


using namespace android;

struct fields_t {
    jfieldID context;
    jclass bitmapClazz;
    jmethodID bitmapConstructor;
    jmethodID createBitmapMethod;
};

static fields_t fields;
static Mutex sLock;
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

static MediaMetadataRetriever* getRetriever(JNIEnv* env, jobject thiz)
{
    // No lock is needed, since it is called internally by other methods that are protected
    MediaMetadataRetriever* retriever = (MediaMetadataRetriever*) env->GetIntField(thiz, fields.context);
    return retriever;
}

static void setRetriever(JNIEnv* env, jobject thiz, int retriever)
{
    // No lock is needed, since it is called internally by other methods that are protected
    MediaMetadataRetriever *old = (MediaMetadataRetriever*) env->GetIntField(thiz, fields.context);
    env->SetIntField(thiz, fields.context, retriever);
}

static void android_media_MediaMetadataRetriever_setDataSource(JNIEnv *env, jobject thiz, jstring path)
{
    LOGV("setDataSource");
    MediaMetadataRetriever* retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "No retriever available");
        return;
    }
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

    process_media_retriever_call(env, retriever->setDataSource(pathStr), "java/lang/RuntimeException", "setDataSource failed");
    env->ReleaseStringUTFChars(path, pathStr);
}

static void android_media_MediaMetadataRetriever_setDataSourceFD(JNIEnv *env, jobject thiz, jobject fileDescriptor, jlong offset, jlong length)
{
    LOGV("setDataSource");
    MediaMetadataRetriever* retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "No retriever available");
        return;
    }
    if (!fileDescriptor) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    int fd = getParcelFileDescriptorFD(env, fileDescriptor);
    if (offset < 0 || length < 0 || fd < 0) {
        if (offset < 0) {
            LOGE("negative offset (%lld)", offset);
        }
        if (length < 0) {
            LOGE("negative length (%lld)", length);
        }
        if (fd < 0) {
            LOGE("invalid file descriptor");
        }
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    process_media_retriever_call(env, retriever->setDataSource(fd, offset, length), "java/lang/RuntimeException", "setDataSource failed");
}

static jobject android_media_MediaMetadataRetriever_getFrameAtTime(JNIEnv *env, jobject thiz, jlong timeUs, jint option)
{
    LOGV("getFrameAtTime: %lld us option: %d", timeUs, option);
    MediaMetadataRetriever* retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "No retriever available");
        return NULL;
    }

    // Call native method to retrieve a video frame
    VideoFrame *videoFrame = NULL;
    sp<IMemory> frameMemory = retriever->getFrameAtTime(timeUs, option);
    if (frameMemory != 0) {  // cast the shared structure to a VideoFrame object
        videoFrame = static_cast<VideoFrame *>(frameMemory->pointer());
    }
    if (videoFrame == NULL) {
        LOGE("getFrameAtTime: videoFrame is a NULL pointer");
        return NULL;
    }

    jobject matrix = NULL;
    if (videoFrame->mRotationAngle != 0) {
        LOGD("Create a rotation matrix: %d degrees", videoFrame->mRotationAngle);
        jclass matrixClazz = env->FindClass("android/graphics/Matrix");
        if (matrixClazz == NULL) {
            jniThrowException(env, "java/lang/RuntimeException",
                "Can't find android/graphics/Matrix");
            return NULL;
        }
        jmethodID matrixConstructor =
            env->GetMethodID(matrixClazz, "<init>", "()V");
        if (matrixConstructor == NULL) {
            jniThrowException(env, "java/lang/RuntimeException",
                "Can't find Matrix constructor");
            return NULL;
        }
        matrix =
            env->NewObject(matrixClazz, matrixConstructor);
        if (matrix == NULL) {
            LOGE("Could not create a Matrix object");
            return NULL;
        }

        LOGV("Rotate the matrix: %d degrees", videoFrame->mRotationAngle);
        jmethodID setRotateMethod =
                env->GetMethodID(matrixClazz, "setRotate", "(F)V");
        if (setRotateMethod == NULL) {
            jniThrowException(env, "java/lang/RuntimeException",
                "Can't find Matrix setRotate method");
            return NULL;
        }
        env->CallVoidMethod(matrix, setRotateMethod, 1.0 * videoFrame->mRotationAngle);
        env->DeleteLocalRef(matrixClazz);
    }

    // Create a SkBitmap to hold the pixels
    SkBitmap *bitmap = new SkBitmap();
    if (bitmap == NULL) {
        LOGE("getFrameAtTime: cannot instantiate a SkBitmap object.");
        return NULL;
    }
    bitmap->setConfig(SkBitmap::kRGB_565_Config, videoFrame->mDisplayWidth, videoFrame->mDisplayHeight);
    if (!bitmap->allocPixels()) {
        delete bitmap;
        LOGE("failed to allocate pixel buffer");
        return NULL;
    }
    memcpy((uint8_t*)bitmap->getPixels(), (uint8_t*)videoFrame + sizeof(VideoFrame), videoFrame->mSize);

    // Since internally SkBitmap uses reference count to manage the reference to
    // its pixels, it is important that the pixels (along with SkBitmap) be
    // available after creating the Bitmap is returned to Java app.
    jobject jSrcBitmap = env->NewObject(fields.bitmapClazz,
            fields.bitmapConstructor, (int) bitmap, true, NULL, -1);

    LOGV("Return a new bitmap constructed with the rotation matrix");
    return env->CallStaticObjectMethod(
                fields.bitmapClazz, fields.createBitmapMethod,
                jSrcBitmap,                     // source Bitmap
                0,                              // x
                0,                              // y
                videoFrame->mDisplayWidth,      // width
                videoFrame->mDisplayHeight,     // height
                matrix,                         // transform matrix
                false);                         // filter
}

static jbyteArray android_media_MediaMetadataRetriever_getEmbeddedPicture(
        JNIEnv *env, jobject thiz, jint pictureType)
{
    LOGV("getEmbeddedPicture: %d", pictureType);
    MediaMetadataRetriever* retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "No retriever available");
        return NULL;
    }
    MediaAlbumArt* mediaAlbumArt = NULL;

    // FIXME:
    // Use pictureType to retrieve the intended embedded picture and also change
    // the method name to getEmbeddedPicture().
    sp<IMemory> albumArtMemory = retriever->extractAlbumArt();
    if (albumArtMemory != 0) {  // cast the shared structure to a MediaAlbumArt object
        mediaAlbumArt = static_cast<MediaAlbumArt *>(albumArtMemory->pointer());
    }
    if (mediaAlbumArt == NULL) {
        LOGE("getEmbeddedPicture: Call to getEmbeddedPicture failed.");
        return NULL;
    }

    unsigned int len = mediaAlbumArt->mSize;
    char* data = (char*) mediaAlbumArt + sizeof(MediaAlbumArt);
    jbyteArray array = env->NewByteArray(len);
    if (!array) {  // OutOfMemoryError exception has already been thrown.
        LOGE("getEmbeddedPicture: OutOfMemoryError is thrown.");
    } else {
        jbyte* bytes = env->GetByteArrayElements(array, NULL);
        if (bytes != NULL) {
            memcpy(bytes, data, len);
            env->ReleaseByteArrayElements(array, bytes, 0);
        }
    }

    // No need to delete mediaAlbumArt here
    return array;
}

static jobject android_media_MediaMetadataRetriever_extractMetadata(JNIEnv *env, jobject thiz, jint keyCode)
{
    LOGV("extractMetadata");
    MediaMetadataRetriever* retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "No retriever available");
        return NULL;
    }
    const char* value = retriever->extractMetadata(keyCode);
    if (!value) {
        LOGV("extractMetadata: Metadata is not found");
        return NULL;
    }
    LOGV("extractMetadata: value (%s) for keyCode(%d)", value, keyCode);
    return env->NewStringUTF(value);
}

static void android_media_MediaMetadataRetriever_release(JNIEnv *env, jobject thiz)
{
    LOGV("release");
    Mutex::Autolock lock(sLock);
    MediaMetadataRetriever* retriever = getRetriever(env, thiz);
    delete retriever;
    setRetriever(env, thiz, 0);
}

static void android_media_MediaMetadataRetriever_native_finalize(JNIEnv *env, jobject thiz)
{
    LOGV("native_finalize");
    
    // No lock is needed, since android_media_MediaMetadataRetriever_release() is protected
    android_media_MediaMetadataRetriever_release(env, thiz);
}

// This function gets a field ID, which in turn causes class initialization.
// It is called from a static block in MediaMetadataRetriever, which won't run until the
// first time an instance of this class is used.
static void android_media_MediaMetadataRetriever_native_init(JNIEnv *env)
{
    jclass clazz = env->FindClass(kClassPathName);
    if (clazz == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find android/media/MediaMetadataRetriever");
        return;
    }

    fields.context = env->GetFieldID(clazz, "mNativeContext", "I");
    if (fields.context == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find MediaMetadataRetriever.mNativeContext");
        return;
    }

    fields.bitmapClazz = env->FindClass("android/graphics/Bitmap");
    if (fields.bitmapClazz == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find android/graphics/Bitmap");
        return;
    }

    fields.bitmapConstructor = env->GetMethodID(fields.bitmapClazz, "<init>", "(IZ[BI)V");
    if (fields.bitmapConstructor == NULL) {
        jniThrowException(env, "java/lang/RuntimeException", "Can't find Bitmap constructor");
        return;
    }
    fields.createBitmapMethod =
            env->GetStaticMethodID(fields.bitmapClazz, "createBitmap",
                    "(Landroid/graphics/Bitmap;IIIILandroid/graphics/Matrix;Z)"
                    "Landroid/graphics/Bitmap;");
    if (fields.createBitmapMethod == NULL) {
        jniThrowException(env, "java/lang/RuntimeException",
                "Can't find Bitmap.createBitmap method");
        return;
    }
}

static void android_media_MediaMetadataRetriever_native_setup(JNIEnv *env, jobject thiz)
{
    LOGV("native_setup");
    MediaMetadataRetriever* retriever = new MediaMetadataRetriever();
    if (retriever == 0) {
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }
    setRetriever(env, thiz, (int)retriever);
}

// JNI mapping between Java methods and native methods
static JNINativeMethod nativeMethods[] = {
        {"setDataSource",   "(Ljava/lang/String;)V", (void *)android_media_MediaMetadataRetriever_setDataSource},
        {"setDataSource",   "(Ljava/io/FileDescriptor;JJ)V", (void *)android_media_MediaMetadataRetriever_setDataSourceFD},
        {"_getFrameAtTime", "(JI)Landroid/graphics/Bitmap;", (void *)android_media_MediaMetadataRetriever_getFrameAtTime},
        {"extractMetadata", "(I)Ljava/lang/String;", (void *)android_media_MediaMetadataRetriever_extractMetadata},
        {"getEmbeddedPicture", "(I)[B", (void *)android_media_MediaMetadataRetriever_getEmbeddedPicture},
        {"release",         "()V", (void *)android_media_MediaMetadataRetriever_release},
        {"native_finalize", "()V", (void *)android_media_MediaMetadataRetriever_native_finalize},
        {"native_setup",    "()V", (void *)android_media_MediaMetadataRetriever_native_setup},
        {"native_init",     "()V", (void *)android_media_MediaMetadataRetriever_native_init},
};

// This function only registers the native methods, and is called from
// JNI_OnLoad in android_media_MediaPlayer.cpp
int register_android_media_MediaMetadataRetriever(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods
        (env, kClassPathName, nativeMethods, NELEM(nativeMethods));
}
