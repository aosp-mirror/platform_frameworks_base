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

#include <cmath>
#include <assert.h>
#include <utils/Log.h>
#include <utils/threads.h>
#include <SkBitmap.h>
#include <media/IMediaHTTPService.h>
#include <media/mediametadataretriever.h>
#include <media/mediascanner.h>
#include <nativehelper/ScopedLocalRef.h>
#include <private/media/VideoFrame.h>

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include "android_media_MediaDataSource.h"
#include "android_media_Streams.h"
#include "android_util_Binder.h"

#include "android/graphics/GraphicsJNI.h"

using namespace android;

struct fields_t {
    jfieldID context;
    jclass bitmapClazz;  // Must be a global ref
    jmethodID createBitmapMethod;
    jmethodID createScaledBitmapMethod;
    jclass configClazz;  // Must be a global ref
    jmethodID createConfigMethod;
    jclass bitmapParamsClazz; // Must be a global ref
    jfieldID inPreferredConfig;
    jfieldID outActualConfig;
    jclass arrayListClazz; // Must be a global ref
    jmethodID arrayListInit;
    jmethodID arrayListAdd;
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

static sp<MediaMetadataRetriever> getRetriever(JNIEnv* env, jobject thiz)
{
    // No lock is needed, since it is called internally by other methods that are protected
    MediaMetadataRetriever* retriever = (MediaMetadataRetriever*) env->GetLongField(thiz, fields.context);
    return retriever;
}

static void setRetriever(JNIEnv* env, jobject thiz, const sp<MediaMetadataRetriever> &retriever)
{
    // No lock is needed, since it is called internally by other methods that are protected

    if (retriever != NULL) {
        retriever->incStrong(thiz);
    }
    sp<MediaMetadataRetriever> old = getRetriever(env, thiz);
    if (old != NULL) {
        old->decStrong(thiz);
    }

    env->SetLongField(thiz, fields.context, (jlong) retriever.get());
}

static void
android_media_MediaMetadataRetriever_setDataSourceAndHeaders(
        JNIEnv *env, jobject thiz, jobject httpServiceBinderObj, jstring path,
        jobjectArray keys, jobjectArray values) {

    ALOGV("setDataSource");
    sp<MediaMetadataRetriever> retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(
                env,
                "java/lang/IllegalStateException", "No retriever available");

        return;
    }

    if (!path) {
        jniThrowException(
                env, "java/lang/IllegalArgumentException", "Null pointer");

        return;
    }

    const char *tmp = env->GetStringUTFChars(path, NULL);
    if (!tmp) {  // OutOfMemoryError exception already thrown
        return;
    }

    String8 pathStr(tmp);
    env->ReleaseStringUTFChars(path, tmp);
    tmp = NULL;

    // Don't let somebody trick us in to reading some random block of memory
    if (strncmp("mem://", pathStr.string(), 6) == 0) {
        jniThrowException(
                env, "java/lang/IllegalArgumentException", "Invalid pathname");
        return;
    }

    // We build a similar KeyedVector out of it.
    KeyedVector<String8, String8> headersVector;
    if (!ConvertKeyValueArraysToKeyedVector(
            env, keys, values, &headersVector)) {
        return;
    }

    sp<IMediaHTTPService> httpService;
    if (httpServiceBinderObj != NULL) {
        sp<IBinder> binder = ibinderForJavaObject(env, httpServiceBinderObj);
        httpService = interface_cast<IMediaHTTPService>(binder);
    }

    process_media_retriever_call(
            env,
            retriever->setDataSource(
                httpService,
                pathStr.string(),
                headersVector.size() > 0 ? &headersVector : NULL),

            "java/lang/RuntimeException",
            "setDataSource failed");
}

static void android_media_MediaMetadataRetriever_setDataSourceFD(JNIEnv *env, jobject thiz, jobject fileDescriptor, jlong offset, jlong length)
{
    ALOGV("setDataSource");
    sp<MediaMetadataRetriever> retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "No retriever available");
        return;
    }
    if (!fileDescriptor) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    int fd = jniGetFDFromFileDescriptor(env, fileDescriptor);
    if (offset < 0 || length < 0 || fd < 0) {
        if (offset < 0) {
            ALOGE("negative offset (%lld)", (long long)offset);
        }
        if (length < 0) {
            ALOGE("negative length (%lld)", (long long)length);
        }
        if (fd < 0) {
            ALOGE("invalid file descriptor");
        }
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
    process_media_retriever_call(env, retriever->setDataSource(fd, offset, length), "java/lang/RuntimeException", "setDataSource failed");
}

static void android_media_MediaMetadataRetriever_setDataSourceCallback(JNIEnv *env, jobject thiz, jobject dataSource)
{
    ALOGV("setDataSourceCallback");
    sp<MediaMetadataRetriever> retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "No retriever available");
        return;
    }
    if (dataSource == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    sp<IDataSource> callbackDataSource = new JMediaDataSource(env, dataSource);
    process_media_retriever_call(env, retriever->setDataSource(callbackDataSource), "java/lang/RuntimeException", "setDataSourceCallback failed");
}

template<typename T>
static void rotate0(T* dst, const T* src, size_t width, size_t height)
{
    memcpy(dst, src, width * height * sizeof(T));
}

template<typename T>
static void rotate90(T* dst, const T* src, size_t width, size_t height)
{
    for (size_t i = 0; i < height; ++i) {
        for (size_t j = 0; j < width; ++j) {
            dst[j * height + height - 1 - i] = src[i * width + j];
        }
    }
}

template<typename T>
static void rotate180(T* dst, const T* src, size_t width, size_t height)
{
    for (size_t i = 0; i < height; ++i) {
        for (size_t j = 0; j < width; ++j) {
            dst[(height - 1 - i) * width + width - 1 - j] = src[i * width + j];
        }
    }
}

template<typename T>
static void rotate270(T* dst, const T* src, size_t width, size_t height)
{
    for (size_t i = 0; i < height; ++i) {
        for (size_t j = 0; j < width; ++j) {
            dst[(width - 1 - j) * height + i] = src[i * width + j];
        }
    }
}

template<typename T>
static void rotate(T *dst, const T *src, size_t width, size_t height, int angle)
{
    switch (angle) {
        case 0:
            rotate0(dst, src, width, height);
            break;
        case 90:
            rotate90(dst, src, width, height);
            break;
        case 180:
            rotate180(dst, src, width, height);
            break;
        case 270:
            rotate270(dst, src, width, height);
            break;
    }
}

static jobject getBitmapFromVideoFrame(
        JNIEnv *env, VideoFrame *videoFrame, jint dst_width, jint dst_height,
        SkColorType outColorType) {
    ALOGV("getBitmapFromVideoFrame: dimension = %dx%d, displaySize = %dx%d, bytes = %d",
            videoFrame->mWidth,
            videoFrame->mHeight,
            videoFrame->mDisplayWidth,
            videoFrame->mDisplayHeight,
            videoFrame->mSize);

    ScopedLocalRef<jobject> config(env,
            env->CallStaticObjectMethod(
                    fields.configClazz,
                    fields.createConfigMethod,
                    GraphicsJNI::colorTypeToLegacyBitmapConfig(outColorType)));

    uint32_t width, height, displayWidth, displayHeight;
    bool swapWidthAndHeight = false;
    if (videoFrame->mRotationAngle == 90 || videoFrame->mRotationAngle == 270) {
        width = videoFrame->mHeight;
        height = videoFrame->mWidth;
        swapWidthAndHeight = true;
        displayWidth = videoFrame->mDisplayHeight;
        displayHeight = videoFrame->mDisplayWidth;
    } else {
        width = videoFrame->mWidth;
        height = videoFrame->mHeight;
        displayWidth = videoFrame->mDisplayWidth;
        displayHeight = videoFrame->mDisplayHeight;
    }

    jobject jBitmap = env->CallStaticObjectMethod(
                            fields.bitmapClazz,
                            fields.createBitmapMethod,
                            width,
                            height,
                            config.get());
    if (jBitmap == NULL) {
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        ALOGE("getBitmapFromVideoFrame: create Bitmap failed!");
        return NULL;
    }

    SkBitmap bitmap;
    GraphicsJNI::getSkBitmap(env, jBitmap, &bitmap);

    if (outColorType == kRGB_565_SkColorType) {
        rotate((uint16_t*)bitmap.getPixels(),
               (uint16_t*)((char*)videoFrame + sizeof(VideoFrame)),
               videoFrame->mWidth,
               videoFrame->mHeight,
               videoFrame->mRotationAngle);
    } else {
        rotate((uint32_t*)bitmap.getPixels(),
               (uint32_t*)((char*)videoFrame + sizeof(VideoFrame)),
               videoFrame->mWidth,
               videoFrame->mHeight,
               videoFrame->mRotationAngle);
    }

    if (dst_width <= 0 || dst_height <= 0) {
        dst_width = displayWidth;
        dst_height = displayHeight;
    } else {
        float factor = std::min((float)dst_width / (float)displayWidth,
                (float)dst_height / (float)displayHeight);
        dst_width = std::round(displayWidth * factor);
        dst_height = std::round(displayHeight * factor);
    }

    if ((uint32_t)dst_width != width || (uint32_t)dst_height != height) {
        ALOGV("Bitmap dimension is scaled from %dx%d to %dx%d",
                width, height, dst_width, dst_height);
        jobject scaledBitmap = env->CallStaticObjectMethod(fields.bitmapClazz,
                                fields.createScaledBitmapMethod,
                                jBitmap,
                                dst_width,
                                dst_height,
                                true);

        env->DeleteLocalRef(jBitmap);
        return scaledBitmap;
    }

    return jBitmap;
}

static int getColorFormat(JNIEnv *env, jobject options) {
    if (options == NULL) {
        return HAL_PIXEL_FORMAT_RGBA_8888;
    }

    ScopedLocalRef<jobject> inConfig(env, env->GetObjectField(options, fields.inPreferredConfig));
    SkColorType prefColorType = GraphicsJNI::getNativeBitmapColorType(env, inConfig.get());

    if (prefColorType == kRGB_565_SkColorType) {
        return HAL_PIXEL_FORMAT_RGB_565;
    }
    return HAL_PIXEL_FORMAT_RGBA_8888;
}

static SkColorType setOutColorType(JNIEnv *env, int colorFormat, jobject options) {
    SkColorType outColorType = kN32_SkColorType;
    if (colorFormat == HAL_PIXEL_FORMAT_RGB_565) {
        outColorType = kRGB_565_SkColorType;
    }

    if (options != NULL) {
        ScopedLocalRef<jobject> config(env,
                env->CallStaticObjectMethod(
                        fields.configClazz,
                        fields.createConfigMethod,
                        GraphicsJNI::colorTypeToLegacyBitmapConfig(outColorType)));

        env->SetObjectField(options, fields.outActualConfig, config.get());
    }
    return outColorType;
}

static jobject android_media_MediaMetadataRetriever_getFrameAtTime(
        JNIEnv *env, jobject thiz, jlong timeUs, jint option, jint dst_width, jint dst_height)
{
    ALOGV("getFrameAtTime: %lld us option: %d dst width: %d heigh: %d",
            (long long)timeUs, option, dst_width, dst_height);
    sp<MediaMetadataRetriever> retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "No retriever available");
        return NULL;
    }

    // Call native method to retrieve a video frame
    VideoFrame *videoFrame = NULL;
    sp<IMemory> frameMemory = retriever->getFrameAtTime(timeUs, option);
    // TODO: Using unsecurePointer() has some associated security pitfalls
    //       (see declaration for details).
    //       Either document why it is safe in this case or address the
    //       issue (e.g. by copying).
    if (frameMemory != 0) {  // cast the shared structure to a VideoFrame object
        videoFrame = static_cast<VideoFrame *>(frameMemory->unsecurePointer());
    }
    if (videoFrame == NULL) {
        ALOGE("getFrameAtTime: videoFrame is a NULL pointer");
        return NULL;
    }

    return getBitmapFromVideoFrame(env, videoFrame, dst_width, dst_height, kRGB_565_SkColorType);
}

static jobject android_media_MediaMetadataRetriever_getImageAtIndex(
        JNIEnv *env, jobject thiz, jint index, jobject params)
{
    ALOGV("getImageAtIndex: index %d", index);
    sp<MediaMetadataRetriever> retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "No retriever available");
        return NULL;
    }

    int colorFormat = getColorFormat(env, params);

    // Call native method to retrieve an image
    VideoFrame *videoFrame = NULL;
    sp<IMemory> frameMemory = retriever->getImageAtIndex(index, colorFormat);
    if (frameMemory != 0) {  // cast the shared structure to a VideoFrame object
        // TODO: Using unsecurePointer() has some associated security pitfalls
        //       (see declaration for details).
        //       Either document why it is safe in this case or address the
        //       issue (e.g. by copying).
        videoFrame = static_cast<VideoFrame *>(frameMemory->unsecurePointer());
    }
    if (videoFrame == NULL) {
        ALOGE("getImageAtIndex: videoFrame is a NULL pointer");
        return NULL;
    }

    SkColorType outColorType = setOutColorType(env, colorFormat, params);

    return getBitmapFromVideoFrame(env, videoFrame, -1, -1, outColorType);
}

static jobject android_media_MediaMetadataRetriever_getThumbnailImageAtIndex(
        JNIEnv *env, jobject thiz, jint index, jobject params, jint targetSize, jint maxPixels)
{
    ALOGV("getThumbnailImageAtIndex: index %d", index);

    sp<MediaMetadataRetriever> retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "No retriever available");
        return NULL;
    }

    int colorFormat = getColorFormat(env, params);
    jint dst_width = -1, dst_height = -1;

    // Call native method to retrieve an image
    VideoFrame *videoFrame = NULL;
    sp<IMemory> frameMemory = retriever->getImageAtIndex(
            index, colorFormat, true /*metaOnly*/, true /*thumbnail*/);
    if (frameMemory != 0) {
        // TODO: Using unsecurePointer() has some associated security pitfalls
        //       (see declaration for details).
        //       Either document why it is safe in this case or address the
        //       issue (e.g. by copying).
        videoFrame = static_cast<VideoFrame *>(frameMemory->unsecurePointer());
        int32_t thumbWidth = videoFrame->mWidth;
        int32_t thumbHeight = videoFrame->mHeight;
        videoFrame = NULL;
        int64_t thumbPixels = thumbWidth * thumbHeight;

        // Here we try to use the included thumbnail if it's not too shabby.
        // If this fails ThumbnailUtils would have to decode the full image and
        // downscale which could take long.
        if (thumbWidth >= targetSize || thumbHeight >= targetSize
                || thumbPixels * 6 >= maxPixels) {
            frameMemory = retriever->getImageAtIndex(
                    index, colorFormat, false /*metaOnly*/, true /*thumbnail*/);
            // TODO: Using unsecurePointer() has some associated security pitfalls
            //       (see declaration for details).
            //       Either document why it is safe in this case or address the
            //       issue (e.g. by copying).
            videoFrame = static_cast<VideoFrame *>(frameMemory->unsecurePointer());

            if (thumbPixels > maxPixels) {
                int downscale = ceil(sqrt(thumbPixels / (float)maxPixels));
                dst_width = thumbWidth / downscale;
                dst_height = thumbHeight /downscale;
            }
        }
    }
    if (videoFrame == NULL) {
        ALOGV("getThumbnailImageAtIndex: no suitable thumbnails available");
        return NULL;
    }

    // Ignore rotation for thumbnail extraction to be consistent with
    // thumbnails extracted by BitmapFactory APIs.
    videoFrame->mRotationAngle = 0;

    SkColorType outColorType = setOutColorType(env, colorFormat, params);

    return getBitmapFromVideoFrame(env, videoFrame, dst_width, dst_height, outColorType);
}

static jobject android_media_MediaMetadataRetriever_getFrameAtIndex(
        JNIEnv *env, jobject thiz, jint frameIndex, jint numFrames, jobject params)
{
    ALOGV("getFrameAtIndex: frameIndex %d, numFrames %d", frameIndex, numFrames);
    sp<MediaMetadataRetriever> retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env,
                "java/lang/IllegalStateException", "No retriever available");
        return NULL;
    }


    jobject arrayList = env->NewObject(fields.arrayListClazz, fields.arrayListInit);
    if (arrayList == NULL) {
        jniThrowException(env,
                "java/lang/IllegalStateException", "Can't create bitmap array");
        return NULL;
    }

    int colorFormat = getColorFormat(env, params);
    SkColorType outColorType = setOutColorType(env, colorFormat, params);
    size_t i = 0;
    for (; i < numFrames; i++) {
        sp<IMemory> frame = retriever->getFrameAtIndex(frameIndex + i, colorFormat);
        if (frame == NULL || frame->unsecurePointer() == NULL) {
            ALOGE("video frame at index %zu is a NULL pointer", frameIndex + i);
            break;
        }
        // TODO: Using unsecurePointer() has some associated security pitfalls
        //       (see declaration for details).
        //       Either document why it is safe in this case or address the
        //       issue (e.g. by copying).
        VideoFrame *videoFrame = static_cast<VideoFrame *>(frame->unsecurePointer());
        jobject bitmapObj = getBitmapFromVideoFrame(env, videoFrame, -1, -1, outColorType);
        env->CallBooleanMethod(arrayList, fields.arrayListAdd, bitmapObj);
        env->DeleteLocalRef(bitmapObj);
    }

    if (i == 0) {
        env->DeleteLocalRef(arrayList);

        jniThrowException(env,
                "java/lang/IllegalStateException", "No frames from retriever");
        return NULL;
    }

    return arrayList;
}

static jbyteArray android_media_MediaMetadataRetriever_getEmbeddedPicture(
        JNIEnv *env, jobject thiz, jint pictureType)
{
    ALOGV("getEmbeddedPicture: %d", pictureType);
    sp<MediaMetadataRetriever> retriever = getRetriever(env, thiz);
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
        // TODO: Using unsecurePointer() has some associated security pitfalls
        //       (see declaration for details).
        //       Either document why it is safe in this case or address the
        //       issue (e.g. by copying).
        mediaAlbumArt = static_cast<MediaAlbumArt *>(albumArtMemory->unsecurePointer());
    }
    if (mediaAlbumArt == NULL) {
        ALOGE("getEmbeddedPicture: Call to getEmbeddedPicture failed.");
        return NULL;
    }

    jbyteArray array = env->NewByteArray(mediaAlbumArt->size());
    if (!array) {  // OutOfMemoryError exception has already been thrown.
        ALOGE("getEmbeddedPicture: OutOfMemoryError is thrown.");
    } else {
        const jbyte* data =
                reinterpret_cast<const jbyte*>(mediaAlbumArt->data());
        env->SetByteArrayRegion(array, 0, mediaAlbumArt->size(), data);
    }

    // No need to delete mediaAlbumArt here
    return array;
}

static jobject android_media_MediaMetadataRetriever_extractMetadata(JNIEnv *env, jobject thiz, jint keyCode)
{
    ALOGV("extractMetadata");
    sp<MediaMetadataRetriever> retriever = getRetriever(env, thiz);
    if (retriever == 0) {
        jniThrowException(env, "java/lang/IllegalStateException", "No retriever available");
        return NULL;
    }
    const char* value = retriever->extractMetadata(keyCode);
    if (!value) {
        ALOGV("extractMetadata: Metadata is not found");
        return NULL;
    }
    ALOGV("extractMetadata: value (%s) for keyCode(%d)", value, keyCode);
    return env->NewStringUTF(value);
}

static void android_media_MediaMetadataRetriever_release(JNIEnv *env, jobject thiz)
{
    ALOGV("release");
    Mutex::Autolock lock(sLock);
    setRetriever(env, thiz, NULL);
}

static void android_media_MediaMetadataRetriever_native_finalize(JNIEnv *env, jobject thiz)
{
    ALOGV("native_finalize");
    // No lock is needed, since android_media_MediaMetadataRetriever_release() is protected
    android_media_MediaMetadataRetriever_release(env, thiz);
}

// This function gets a field ID, which in turn causes class initialization.
// It is called from a static block in MediaMetadataRetriever, which won't run until the
// first time an instance of this class is used.
static void android_media_MediaMetadataRetriever_native_init(JNIEnv *env)
{
    ScopedLocalRef<jclass> clazz(env, env->FindClass(kClassPathName));
    if (clazz.get() == NULL) {
        return;
    }

    fields.context = env->GetFieldID(clazz.get(), "mNativeContext", "J");
    if (fields.context == NULL) {
        return;
    }

    clazz.reset(env->FindClass("android/graphics/Bitmap"));
    if (clazz.get() == NULL) {
        return;
    }
    fields.bitmapClazz = (jclass) env->NewGlobalRef(clazz.get());
    if (fields.bitmapClazz == NULL) {
        return;
    }
    fields.createBitmapMethod =
            env->GetStaticMethodID(fields.bitmapClazz, "createBitmap",
                    "(IILandroid/graphics/Bitmap$Config;)"
                    "Landroid/graphics/Bitmap;");
    if (fields.createBitmapMethod == NULL) {
        return;
    }
    fields.createScaledBitmapMethod =
            env->GetStaticMethodID(fields.bitmapClazz, "createScaledBitmap",
                    "(Landroid/graphics/Bitmap;IIZ)"
                    "Landroid/graphics/Bitmap;");
    if (fields.createScaledBitmapMethod == NULL) {
        return;
    }

    clazz.reset(env->FindClass("android/graphics/Bitmap$Config"));
    if (clazz.get() == NULL) {
        return;
    }
    fields.configClazz = (jclass) env->NewGlobalRef(clazz.get());
    if (fields.configClazz == NULL) {
        return;
    }
    fields.createConfigMethod =
            env->GetStaticMethodID(fields.configClazz, "nativeToConfig",
                    "(I)Landroid/graphics/Bitmap$Config;");
    if (fields.createConfigMethod == NULL) {
        return;
    }

    clazz.reset(env->FindClass("android/media/MediaMetadataRetriever$BitmapParams"));
    if (clazz.get() == NULL) {
        return;
    }
    fields.bitmapParamsClazz = (jclass) env->NewGlobalRef(clazz.get());
    if (fields.bitmapParamsClazz == NULL) {
        return;
    }
    fields.inPreferredConfig = env->GetFieldID(fields.bitmapParamsClazz,
            "inPreferredConfig", "Landroid/graphics/Bitmap$Config;");
    if (fields.inPreferredConfig == NULL) {
        return;
    }
    fields.outActualConfig = env->GetFieldID(fields.bitmapParamsClazz,
            "outActualConfig", "Landroid/graphics/Bitmap$Config;");
    if (fields.outActualConfig == NULL) {
        return;
    }

    clazz.reset(env->FindClass("java/util/ArrayList"));
    if (clazz.get() == NULL) {
        return;
    }
    fields.arrayListClazz = (jclass) env->NewGlobalRef(clazz.get());
    if (fields.arrayListClazz == NULL) {
        return;
    }
    fields.arrayListInit = env->GetMethodID(clazz.get(), "<init>", "()V");
    if (fields.arrayListInit == NULL) {
        return;
    }
    fields.arrayListAdd = env->GetMethodID(clazz.get(), "add", "(Ljava/lang/Object;)Z");
    if (fields.arrayListAdd == NULL) {
        return;
    }
}

static void android_media_MediaMetadataRetriever_native_setup(JNIEnv *env, jobject thiz)
{
    ALOGV("native_setup");
    sp<MediaMetadataRetriever> retriever = new MediaMetadataRetriever();
    if (retriever == 0) {
        jniThrowException(env, "java/lang/RuntimeException", "Out of memory");
        return;
    }
    setRetriever(env, thiz, retriever);
}

// JNI mapping between Java methods and native methods
static const JNINativeMethod nativeMethods[] = {
        {
            "_setDataSource",
            "(Landroid/os/IBinder;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V",
            (void *)android_media_MediaMetadataRetriever_setDataSourceAndHeaders
        },

        {"setDataSource",   "(Ljava/io/FileDescriptor;JJ)V",
                (void *)android_media_MediaMetadataRetriever_setDataSourceFD},
        {"_setDataSource",   "(Landroid/media/MediaDataSource;)V",
                (void *)android_media_MediaMetadataRetriever_setDataSourceCallback},
        {"_getFrameAtTime", "(JIII)Landroid/graphics/Bitmap;",
                (void *)android_media_MediaMetadataRetriever_getFrameAtTime},
        {
            "_getImageAtIndex",
            "(ILandroid/media/MediaMetadataRetriever$BitmapParams;)Landroid/graphics/Bitmap;",
            (void *)android_media_MediaMetadataRetriever_getImageAtIndex
        },

        {
            "getThumbnailImageAtIndex",
            "(ILandroid/media/MediaMetadataRetriever$BitmapParams;II)Landroid/graphics/Bitmap;",
            (void *)android_media_MediaMetadataRetriever_getThumbnailImageAtIndex
        },

        {
            "_getFrameAtIndex",
            "(IILandroid/media/MediaMetadataRetriever$BitmapParams;)Ljava/util/List;",
            (void *)android_media_MediaMetadataRetriever_getFrameAtIndex
        },

        {"extractMetadata", "(I)Ljava/lang/String;",
                (void *)android_media_MediaMetadataRetriever_extractMetadata},
        {"getEmbeddedPicture", "(I)[B",
                (void *)android_media_MediaMetadataRetriever_getEmbeddedPicture},
        {"release",         "()V",
                (void *)android_media_MediaMetadataRetriever_release},
        {"native_finalize", "()V",
                (void *)android_media_MediaMetadataRetriever_native_finalize},
        {"native_setup",    "()V",
                (void *)android_media_MediaMetadataRetriever_native_setup},
        {"native_init",     "()V",
                (void *)android_media_MediaMetadataRetriever_native_init},
};

// This function only registers the native methods, and is called from
// JNI_OnLoad in android_media_MediaPlayer.cpp
int register_android_media_MediaMetadataRetriever(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods
        (env, kClassPathName, nativeMethods, NELEM(nativeMethods));
}
