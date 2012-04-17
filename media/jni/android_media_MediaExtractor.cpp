/*
 * Copyright 2012, The Android Open Source Project
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
#define LOG_TAG "MediaExtractor-JNI"
#include <utils/Log.h>

#include "android_media_MediaExtractor.h"

#include "android_media_Utils.h"
#include "android_runtime/AndroidRuntime.h"
#include "jni.h"
#include "JNIHelp.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/NuMediaExtractor.h>

namespace android {

struct fields_t {
    jfieldID context;
};

static fields_t gFields;

////////////////////////////////////////////////////////////////////////////////

JMediaExtractor::JMediaExtractor(JNIEnv *env, jobject thiz)
    : mClass(NULL),
      mObject(NULL) {
    jclass clazz = env->GetObjectClass(thiz);
    CHECK(clazz != NULL);

    mClass = (jclass)env->NewGlobalRef(clazz);
    mObject = env->NewWeakGlobalRef(thiz);

    mImpl = new NuMediaExtractor;
}

JMediaExtractor::~JMediaExtractor() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteWeakGlobalRef(mObject);
    mObject = NULL;
    env->DeleteGlobalRef(mClass);
    mClass = NULL;
}

status_t JMediaExtractor::setDataSource(
        const char *path, const KeyedVector<String8, String8> *headers) {
    return mImpl->setDataSource(path, headers);
}

status_t JMediaExtractor::setDataSource(int fd, off64_t offset, off64_t size) {
    return mImpl->setDataSource(fd, offset, size);
}

size_t JMediaExtractor::countTracks() const {
    return mImpl->countTracks();
}

status_t JMediaExtractor::getTrackFormat(size_t index, jobject *format) const {
    sp<AMessage> msg;
    status_t err;
    if ((err = mImpl->getTrackFormat(index, &msg)) != OK) {
        return err;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();

    return ConvertMessageToMap(env, msg, format);
}

status_t JMediaExtractor::selectTrack(size_t index) {
    return mImpl->selectTrack(index);
}

status_t JMediaExtractor::seekTo(int64_t timeUs) {
    return mImpl->seekTo(timeUs);
}

status_t JMediaExtractor::advance() {
    return mImpl->advance();
}

status_t JMediaExtractor::readSampleData(
        jobject byteBuf, size_t offset, size_t *sampleSize) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    void *dst = env->GetDirectBufferAddress(byteBuf);

    jlong dstSize;
    jbyteArray byteArray = NULL;

    if (dst == NULL) {
        jclass byteBufClass = env->FindClass("java/nio/ByteBuffer");
        CHECK(byteBufClass != NULL);

        jmethodID arrayID =
            env->GetMethodID(byteBufClass, "array", "()[B");
        CHECK(arrayID != NULL);

        byteArray =
            (jbyteArray)env->CallObjectMethod(byteBuf, arrayID);

        if (byteArray == NULL) {
            return INVALID_OPERATION;
        }

        jboolean isCopy;
        dst = env->GetByteArrayElements(byteArray, &isCopy);

        dstSize = env->GetArrayLength(byteArray);
    } else {
        dstSize = env->GetDirectBufferCapacity(byteBuf);
    }

    if (dstSize < offset) {
        if (byteArray != NULL) {
            env->ReleaseByteArrayElements(byteArray, (jbyte *)dst, 0);
        }

        return -ERANGE;
    }

    sp<ABuffer> buffer = new ABuffer((char *)dst + offset, dstSize - offset);

    status_t err = mImpl->readSampleData(buffer);

    if (byteArray != NULL) {
        env->ReleaseByteArrayElements(byteArray, (jbyte *)dst, 0);
    }

    if (err != OK) {
        return err;
    }

    *sampleSize = buffer->size();

    return OK;
}

status_t JMediaExtractor::getSampleTrackIndex(size_t *trackIndex) {
    return mImpl->getSampleTrackIndex(trackIndex);
}

status_t JMediaExtractor::getSampleTime(int64_t *sampleTimeUs) {
    return mImpl->getSampleTime(sampleTimeUs);
}

status_t JMediaExtractor::getSampleFlags(uint32_t *sampleFlags) {
    return mImpl->getSampleFlags(sampleFlags);
}

}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static sp<JMediaExtractor> setMediaExtractor(
        JNIEnv *env, jobject thiz, const sp<JMediaExtractor> &extractor) {
    sp<JMediaExtractor> old =
        (JMediaExtractor *)env->GetIntField(thiz, gFields.context);

    if (extractor != NULL) {
        extractor->incStrong(thiz);
    }
    if (old != NULL) {
        old->decStrong(thiz);
    }
    env->SetIntField(thiz, gFields.context, (int)extractor.get());

    return old;
}

static sp<JMediaExtractor> getMediaExtractor(JNIEnv *env, jobject thiz) {
    return (JMediaExtractor *)env->GetIntField(thiz, gFields.context);
}

static void android_media_MediaExtractor_release(JNIEnv *env, jobject thiz) {
    setMediaExtractor(env, thiz, NULL);
}

static jint android_media_MediaExtractor_countTracks(
        JNIEnv *env, jobject thiz) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return -1;
    }

    return extractor->countTracks();
}

static jobject android_media_MediaExtractor_getTrackFormat(
        JNIEnv *env, jobject thiz, jint index) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return NULL;
    }

    jobject format;
    status_t err = extractor->getTrackFormat(index, &format);

    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return NULL;
    }

    return format;
}

static void android_media_MediaExtractor_selectTrack(
        JNIEnv *env, jobject thiz, jint index) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return;
    }

    status_t err = extractor->selectTrack(index);

    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
}

static void android_media_MediaExtractor_seekTo(
        JNIEnv *env, jobject thiz, jlong timeUs) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return;
    }

    extractor->seekTo(timeUs);
}

static jboolean android_media_MediaExtractor_advance(
        JNIEnv *env, jobject thiz) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return false;
    }

    status_t err = extractor->advance();

    if (err == ERROR_END_OF_STREAM) {
        return false;
    } else if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return false;
    }

    return true;
}

static jint android_media_MediaExtractor_readSampleData(
        JNIEnv *env, jobject thiz, jobject byteBuf, jint offset) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return -1;
    }

    size_t sampleSize;
    status_t err = extractor->readSampleData(byteBuf, offset, &sampleSize);

    if (err == ERROR_END_OF_STREAM) {
        return -1;
    } else if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return false;
    }

    return sampleSize;
}

static jint android_media_MediaExtractor_getSampleTrackIndex(
        JNIEnv *env, jobject thiz) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return -1;
    }

    size_t trackIndex;
    status_t err = extractor->getSampleTrackIndex(&trackIndex);

    if (err == ERROR_END_OF_STREAM) {
        return -1;
    } else if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return false;
    }

    return trackIndex;
}

static jlong android_media_MediaExtractor_getSampleTime(
        JNIEnv *env, jobject thiz) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return -1ll;
    }

    int64_t sampleTimeUs;
    status_t err = extractor->getSampleTime(&sampleTimeUs);

    if (err == ERROR_END_OF_STREAM) {
        return -1ll;
    } else if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return false;
    }

    return sampleTimeUs;
}

static jint android_media_MediaExtractor_getSampleFlags(
        JNIEnv *env, jobject thiz) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return -1ll;
    }

    uint32_t sampleFlags;
    status_t err = extractor->getSampleFlags(&sampleFlags);

    if (err == ERROR_END_OF_STREAM) {
        return -1ll;
    } else if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return false;
    }

    return sampleFlags;
}

static void android_media_MediaExtractor_native_init(JNIEnv *env) {
    jclass clazz = env->FindClass("android/media/MediaExtractor");
    CHECK(clazz != NULL);

    gFields.context = env->GetFieldID(clazz, "mNativeContext", "I");
    CHECK(gFields.context != NULL);

    DataSource::RegisterDefaultSniffers();
}

static void android_media_MediaExtractor_native_setup(
        JNIEnv *env, jobject thiz) {
    sp<JMediaExtractor> extractor = new JMediaExtractor(env, thiz);
    setMediaExtractor(env,thiz, extractor);
}

static void android_media_MediaExtractor_setDataSource(
        JNIEnv *env, jobject thiz,
        jstring pathObj, jobjectArray keysArray, jobjectArray valuesArray) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return;
    }

    if (pathObj == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    KeyedVector<String8, String8> headers;
    if (!ConvertKeyValueArraysToKeyedVector(
                env, keysArray, valuesArray, &headers)) {
        return;
    }

    const char *path = env->GetStringUTFChars(pathObj, NULL);

    if (path == NULL) {
        return;
    }

    status_t err = extractor->setDataSource(path, &headers);

    env->ReleaseStringUTFChars(pathObj, path);
    path = NULL;

    if (err != OK) {
        jniThrowException(
                env,
                "java/io/IOException",
                "Failed to instantiate extractor.");
        return;
    }
}

static void android_media_MediaExtractor_setDataSourceFd(
        JNIEnv *env, jobject thiz,
        jobject fileDescObj, jlong offset, jlong length) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return;
    }

    if (fileDescObj == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    int fd = jniGetFDFromFileDescriptor(env, fileDescObj);

    status_t err = extractor->setDataSource(fd, offset, length);

    if (err != OK) {
        jniThrowException(
                env,
                "java/io/IOException",
                "Failed to instantiate extractor.");
        return;
    }
}

static void android_media_MediaExtractor_native_finalize(
        JNIEnv *env, jobject thiz) {
    android_media_MediaExtractor_release(env, thiz);
}

static JNINativeMethod gMethods[] = {
    { "release", "()V", (void *)android_media_MediaExtractor_release },

    { "countTracks", "()I", (void *)android_media_MediaExtractor_countTracks },

    { "getTrackFormat", "(I)Ljava/util/Map;",
        (void *)android_media_MediaExtractor_getTrackFormat },

    { "selectTrack", "(I)V", (void *)android_media_MediaExtractor_selectTrack },

    { "seekTo", "(J)V", (void *)android_media_MediaExtractor_seekTo },

    { "advance", "()Z", (void *)android_media_MediaExtractor_advance },

    { "readSampleData", "(Ljava/nio/ByteBuffer;I)I",
        (void *)android_media_MediaExtractor_readSampleData },

    { "getSampleTrackIndex", "()I",
        (void *)android_media_MediaExtractor_getSampleTrackIndex },

    { "getSampleTime", "()J",
        (void *)android_media_MediaExtractor_getSampleTime },

    { "getSampleFlags", "()I",
        (void *)android_media_MediaExtractor_getSampleFlags },

    { "native_init", "()V", (void *)android_media_MediaExtractor_native_init },

    { "native_setup", "()V",
      (void *)android_media_MediaExtractor_native_setup },

    { "native_finalize", "()V",
      (void *)android_media_MediaExtractor_native_finalize },

    { "setDataSource", "(Ljava/lang/String;[Ljava/lang/String;"
                       "[Ljava/lang/String;)V",
      (void *)android_media_MediaExtractor_setDataSource },

    { "setDataSource", "(Ljava/io/FileDescriptor;JJ)V",
      (void *)android_media_MediaExtractor_setDataSourceFd },
};

int register_android_media_MediaExtractor(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaExtractor", gMethods, NELEM(gMethods));
}
