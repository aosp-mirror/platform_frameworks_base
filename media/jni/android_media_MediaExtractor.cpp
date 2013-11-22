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
#include "android_runtime/Log.h"
#include "jni.h"
#include "JNIHelp.h"

#include <media/hardware/CryptoAPI.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/NuMediaExtractor.h>

namespace android {

struct fields_t {
    jfieldID context;

    jmethodID cryptoInfoSetID;
};

static fields_t gFields;

class JavaDataSourceBridge : public DataSource {
    jmethodID mReadMethod;
    jmethodID mGetSizeMethod;
    jmethodID mCloseMethod;
    jobject   mDataSource;
 public:
    JavaDataSourceBridge(JNIEnv *env, jobject source) {
        mDataSource = env->NewGlobalRef(source);

        jclass datasourceclass = env->GetObjectClass(mDataSource);
        CHECK(datasourceclass != NULL);

        mReadMethod = env->GetMethodID(datasourceclass, "readAt", "(J[BI)I");
        CHECK(mReadMethod != NULL);

        mGetSizeMethod = env->GetMethodID(datasourceclass, "getSize", "()J");
        CHECK(mGetSizeMethod != NULL);

        mCloseMethod = env->GetMethodID(datasourceclass, "close", "()V");
        CHECK(mCloseMethod != NULL);
    }

    ~JavaDataSourceBridge() {
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        env->CallVoidMethod(mDataSource, mCloseMethod);
        env->DeleteGlobalRef(mDataSource);
    }

    virtual status_t initCheck() const {
        return OK;
    }

    virtual ssize_t readAt(off64_t offset, void* buffer, size_t size) {
        JNIEnv *env = AndroidRuntime::getJNIEnv();

        // XXX could optimize this by reusing the same array
        jbyteArray byteArrayObj = env->NewByteArray(size);
        env->DeleteLocalRef(env->GetObjectClass(mDataSource));
        env->DeleteLocalRef(env->GetObjectClass(byteArrayObj));
        ssize_t numread = env->CallIntMethod(mDataSource, mReadMethod, offset, byteArrayObj, size);
        env->GetByteArrayRegion(byteArrayObj, 0, size, (jbyte*) buffer);
        env->DeleteLocalRef(byteArrayObj);
        if (env->ExceptionCheck()) {
            ALOGW("Exception occurred while reading %d at %lld", size, offset);
            LOGW_EX(env);
            env->ExceptionClear();
            return -1;
        }
        return numread;
    }

    virtual status_t getSize(off64_t *size) {
        JNIEnv *env = AndroidRuntime::getJNIEnv();

        CHECK(size != NULL);

        int64_t len = env->CallLongMethod(mDataSource, mGetSizeMethod);
        if (len < 0) {
            *size = ERROR_UNSUPPORTED;
        } else {
            *size = len;
        }
        return OK;
    }
};

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

status_t JMediaExtractor::setDataSource(const sp<DataSource> &datasource) {
    return mImpl->setDataSource(datasource);
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

status_t JMediaExtractor::getFileFormat(jobject *format) const {
    sp<AMessage> msg;
    status_t err;
    if ((err = mImpl->getFileFormat(&msg)) != OK) {
        return err;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();

    return ConvertMessageToMap(env, msg, format);
}

status_t JMediaExtractor::selectTrack(size_t index) {
    return mImpl->selectTrack(index);
}

status_t JMediaExtractor::unselectTrack(size_t index) {
    return mImpl->unselectTrack(index);
}

status_t JMediaExtractor::seekTo(
        int64_t timeUs, MediaSource::ReadOptions::SeekMode mode) {
    return mImpl->seekTo(timeUs, mode);
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
    *sampleFlags = 0;

    sp<MetaData> meta;
    status_t err = mImpl->getSampleMeta(&meta);

    if (err != OK) {
        return err;
    }

    int32_t val;
    if (meta->findInt32(kKeyIsSyncFrame, &val) && val != 0) {
        (*sampleFlags) |= NuMediaExtractor::SAMPLE_FLAG_SYNC;
    }

    uint32_t type;
    const void *data;
    size_t size;
    if (meta->findData(kKeyEncryptedSizes, &type, &data, &size)) {
        (*sampleFlags) |= NuMediaExtractor::SAMPLE_FLAG_ENCRYPTED;
    }

    return OK;
}

status_t JMediaExtractor::getSampleMeta(sp<MetaData> *sampleMeta) {
    return mImpl->getSampleMeta(sampleMeta);
}

bool JMediaExtractor::getCachedDuration(int64_t *durationUs, bool *eos) const {
    return mImpl->getCachedDuration(durationUs, eos);
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

static jint android_media_MediaExtractor_getTrackCount(
        JNIEnv *env, jobject thiz) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return -1;
    }

    return extractor->countTracks();
}

static jobject android_media_MediaExtractor_getTrackFormatNative(
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

static jobject android_media_MediaExtractor_getFileFormatNative(
        JNIEnv *env, jobject thiz) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return NULL;
    }

    jobject format;
    status_t err = extractor->getFileFormat(&format);

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

static void android_media_MediaExtractor_unselectTrack(
        JNIEnv *env, jobject thiz, jint index) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return;
    }

    status_t err = extractor->unselectTrack(index);

    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }
}

static void android_media_MediaExtractor_seekTo(
        JNIEnv *env, jobject thiz, jlong timeUs, jint mode) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return;
    }

    if (mode < MediaSource::ReadOptions::SEEK_PREVIOUS_SYNC
            || mode >= MediaSource::ReadOptions::SEEK_CLOSEST) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    extractor->seekTo(timeUs, (MediaSource::ReadOptions::SeekMode)mode);
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

static jboolean android_media_MediaExtractor_getSampleCryptoInfo(
        JNIEnv *env, jobject thiz, jobject cryptoInfoObj) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return -1ll;
    }

    sp<MetaData> meta;
    status_t err = extractor->getSampleMeta(&meta);

    if (err != OK) {
        return false;
    }

    uint32_t type;
    const void *data;
    size_t size;
    if (!meta->findData(kKeyEncryptedSizes, &type, &data, &size)) {
        return false;
    }

    size_t numSubSamples = size / sizeof(size_t);

    if (numSubSamples == 0) {
        return false;
    }

    jintArray numBytesOfEncryptedDataObj = env->NewIntArray(numSubSamples);
    jboolean isCopy;
    jint *dst = env->GetIntArrayElements(numBytesOfEncryptedDataObj, &isCopy);
    for (size_t i = 0; i < numSubSamples; ++i) {
        dst[i] = ((const size_t *)data)[i];
    }
    env->ReleaseIntArrayElements(numBytesOfEncryptedDataObj, dst, 0);
    dst = NULL;

    size_t encSize = size;
    jintArray numBytesOfPlainDataObj = NULL;
    if (meta->findData(kKeyPlainSizes, &type, &data, &size)) {
        if (size != encSize) {
            // The two must be of the same length.
            return false;
        }

        numBytesOfPlainDataObj = env->NewIntArray(numSubSamples);
        jboolean isCopy;
        jint *dst = env->GetIntArrayElements(numBytesOfPlainDataObj, &isCopy);
        for (size_t i = 0; i < numSubSamples; ++i) {
            dst[i] = ((const size_t *)data)[i];
        }
        env->ReleaseIntArrayElements(numBytesOfPlainDataObj, dst, 0);
        dst = NULL;
    }

    jbyteArray keyObj = NULL;
    if (meta->findData(kKeyCryptoKey, &type, &data, &size)) {
        if (size != 16) {
            // Keys must be 16 bytes in length.
            return false;
        }

        keyObj = env->NewByteArray(size);
        jboolean isCopy;
        jbyte *dst = env->GetByteArrayElements(keyObj, &isCopy);
        memcpy(dst, data, size);
        env->ReleaseByteArrayElements(keyObj, dst, 0);
        dst = NULL;
    }

    jbyteArray ivObj = NULL;
    if (meta->findData(kKeyCryptoIV, &type, &data, &size)) {
        if (size != 16) {
            // IVs must be 16 bytes in length.
            return false;
        }

        ivObj = env->NewByteArray(size);
        jboolean isCopy;
        jbyte *dst = env->GetByteArrayElements(ivObj, &isCopy);
        memcpy(dst, data, size);
        env->ReleaseByteArrayElements(ivObj, dst, 0);
        dst = NULL;
    }

    int32_t mode;
    if (!meta->findInt32(kKeyCryptoMode, &mode)) {
        mode = CryptoPlugin::kMode_AES_CTR;
    }

    env->CallVoidMethod(
            cryptoInfoObj,
            gFields.cryptoInfoSetID,
            numSubSamples,
            numBytesOfPlainDataObj,
            numBytesOfEncryptedDataObj,
            keyObj,
            ivObj,
            mode);

    return true;
}

static void android_media_MediaExtractor_native_init(JNIEnv *env) {
    jclass clazz = env->FindClass("android/media/MediaExtractor");
    CHECK(clazz != NULL);

    gFields.context = env->GetFieldID(clazz, "mNativeContext", "I");
    CHECK(gFields.context != NULL);

    clazz = env->FindClass("android/media/MediaCodec$CryptoInfo");
    CHECK(clazz != NULL);

    gFields.cryptoInfoSetID =
        env->GetMethodID(clazz, "set", "(I[I[I[B[BI)V");

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

static void android_media_MediaExtractor_setDataSourceCallback(
        JNIEnv *env, jobject thiz,
        jobject callbackObj) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return;
    }

    if (callbackObj == NULL) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    sp<JavaDataSourceBridge> bridge = new JavaDataSourceBridge(env, callbackObj);
    status_t err = extractor->setDataSource(bridge);

    if (err != OK) {
        jniThrowException(
                env,
                "java/io/IOException",
                "Failed to instantiate extractor.");
        return;
    }
}

static jlong android_media_MediaExtractor_getCachedDurationUs(
        JNIEnv *env, jobject thiz) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return -1ll;
    }

    int64_t cachedDurationUs;
    bool eos;
    if (!extractor->getCachedDuration(&cachedDurationUs, &eos)) {
        return -1ll;
    }

    return cachedDurationUs;
}

static jboolean android_media_MediaExtractor_hasCacheReachedEOS(
        JNIEnv *env, jobject thiz) {
    sp<JMediaExtractor> extractor = getMediaExtractor(env, thiz);

    if (extractor == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return true;
    }

    int64_t cachedDurationUs;
    bool eos;
    if (!extractor->getCachedDuration(&cachedDurationUs, &eos)) {
        return true;
    }

    return eos;
}

static void android_media_MediaExtractor_native_finalize(
        JNIEnv *env, jobject thiz) {
    android_media_MediaExtractor_release(env, thiz);
}

static JNINativeMethod gMethods[] = {
    { "release", "()V", (void *)android_media_MediaExtractor_release },

    { "getTrackCount", "()I", (void *)android_media_MediaExtractor_getTrackCount },

    { "getFileFormatNative", "()Ljava/util/Map;",
        (void *)android_media_MediaExtractor_getFileFormatNative },

    { "getTrackFormatNative", "(I)Ljava/util/Map;",
        (void *)android_media_MediaExtractor_getTrackFormatNative },

    { "selectTrack", "(I)V", (void *)android_media_MediaExtractor_selectTrack },

    { "unselectTrack", "(I)V",
        (void *)android_media_MediaExtractor_unselectTrack },

    { "seekTo", "(JI)V", (void *)android_media_MediaExtractor_seekTo },

    { "advance", "()Z", (void *)android_media_MediaExtractor_advance },

    { "readSampleData", "(Ljava/nio/ByteBuffer;I)I",
        (void *)android_media_MediaExtractor_readSampleData },

    { "getSampleTrackIndex", "()I",
        (void *)android_media_MediaExtractor_getSampleTrackIndex },

    { "getSampleTime", "()J",
        (void *)android_media_MediaExtractor_getSampleTime },

    { "getSampleFlags", "()I",
        (void *)android_media_MediaExtractor_getSampleFlags },

    { "getSampleCryptoInfo", "(Landroid/media/MediaCodec$CryptoInfo;)Z",
        (void *)android_media_MediaExtractor_getSampleCryptoInfo },

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

    { "setDataSource", "(Landroid/media/DataSource;)V",
      (void *)android_media_MediaExtractor_setDataSourceCallback },

    { "getCachedDuration", "()J",
      (void *)android_media_MediaExtractor_getCachedDurationUs },

    { "hasCacheReachedEndOfStream", "()Z",
      (void *)android_media_MediaExtractor_hasCacheReachedEOS },
};

int register_android_media_MediaExtractor(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaExtractor", gMethods, NELEM(gMethods));
}
