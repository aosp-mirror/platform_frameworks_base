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
#define LOG_TAG "MediaCodec-JNI"
#include <utils/Log.h>

#include "android_media_MediaCodec.h"

#include "android_media_MediaCrypto.h"
#include "android_media_MediaDescrambler.h"
#include "android_media_MediaMetricsJNI.h"
#include "android_media_Utils.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"
#include "android_util_Binder.h"
#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

#include <android/hardware/cas/native/1.0/IDescrambler.h>

#include <cutils/compiler.h>

#include <gui/Surface.h>

#include <media/MediaCodecBuffer.h>
#include <media/stagefright/MediaCodec.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/AString.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/PersistentSurface.h>
#include <mediadrm/ICrypto.h>
#include <nativehelper/ScopedLocalRef.h>

#include <system/window.h>

namespace android {

// Keep these in sync with their equivalents in MediaCodec.java !!!
enum {
    DEQUEUE_INFO_TRY_AGAIN_LATER            = -1,
    DEQUEUE_INFO_OUTPUT_FORMAT_CHANGED      = -2,
    DEQUEUE_INFO_OUTPUT_BUFFERS_CHANGED     = -3,
};

enum {
    EVENT_CALLBACK = 1,
    EVENT_SET_CALLBACK = 2,
    EVENT_FRAME_RENDERED = 3,
};

static struct CryptoErrorCodes {
    jint cryptoErrorNoKey;
    jint cryptoErrorKeyExpired;
    jint cryptoErrorResourceBusy;
    jint cryptoErrorInsufficientOutputProtection;
    jint cryptoErrorSessionNotOpened;
    jint cryptoErrorUnsupportedOperation;
} gCryptoErrorCodes;

static struct CodecActionCodes {
    jint codecActionTransient;
    jint codecActionRecoverable;
} gCodecActionCodes;

static struct CodecErrorCodes {
    jint errorInsufficientResource;
    jint errorReclaimed;
} gCodecErrorCodes;

static struct {
    jclass clazz;
    jfieldID mLock;
    jfieldID mPersistentObject;
    jmethodID ctor;
    jmethodID setNativeObjectLocked;
} gPersistentSurfaceClassInfo;

static struct {
    jint Unencrypted;
    jint AesCtr;
    jint AesCbc;
} gCryptoModes;

static struct {
    jclass capsClazz;
    jmethodID capsCtorId;
    jclass profileLevelClazz;
    jfieldID profileField;
    jfieldID levelField;
} gCodecInfo;

struct fields_t {
    jfieldID context;
    jmethodID postEventFromNativeID;
    jfieldID cryptoInfoNumSubSamplesID;
    jfieldID cryptoInfoNumBytesOfClearDataID;
    jfieldID cryptoInfoNumBytesOfEncryptedDataID;
    jfieldID cryptoInfoKeyID;
    jfieldID cryptoInfoIVID;
    jfieldID cryptoInfoModeID;
    jfieldID cryptoInfoPatternID;
    jfieldID patternEncryptBlocksID;
    jfieldID patternSkipBlocksID;
};

static fields_t gFields;
static const void *sRefBaseOwner;

////////////////////////////////////////////////////////////////////////////////

JMediaCodec::JMediaCodec(
        JNIEnv *env, jobject thiz,
        const char *name, bool nameIsType, bool encoder)
    : mClass(NULL),
      mObject(NULL) {
    jclass clazz = env->GetObjectClass(thiz);
    CHECK(clazz != NULL);

    mClass = (jclass)env->NewGlobalRef(clazz);
    mObject = env->NewWeakGlobalRef(thiz);

    cacheJavaObjects(env);

    mLooper = new ALooper;
    mLooper->setName("MediaCodec_looper");

    mLooper->start(
            false,      // runOnCallingThread
            true,       // canCallJava
            ANDROID_PRIORITY_VIDEO);

    if (nameIsType) {
        mCodec = MediaCodec::CreateByType(mLooper, name, encoder, &mInitStatus);
    } else {
        mCodec = MediaCodec::CreateByComponentName(mLooper, name, &mInitStatus);
    }
    CHECK((mCodec != NULL) != (mInitStatus != OK));
}

void JMediaCodec::cacheJavaObjects(JNIEnv *env) {
    jclass clazz = (jclass)env->FindClass("java/nio/ByteBuffer");
    mByteBufferClass = (jclass)env->NewGlobalRef(clazz);
    CHECK(mByteBufferClass != NULL);

    ScopedLocalRef<jclass> byteOrderClass(
            env, env->FindClass("java/nio/ByteOrder"));
    CHECK(byteOrderClass.get() != NULL);

    jmethodID nativeOrderID = env->GetStaticMethodID(
            byteOrderClass.get(), "nativeOrder", "()Ljava/nio/ByteOrder;");
    CHECK(nativeOrderID != NULL);

    jobject nativeByteOrderObj =
        env->CallStaticObjectMethod(byteOrderClass.get(), nativeOrderID);
    mNativeByteOrderObj = env->NewGlobalRef(nativeByteOrderObj);
    CHECK(mNativeByteOrderObj != NULL);
    env->DeleteLocalRef(nativeByteOrderObj);
    nativeByteOrderObj = NULL;

    mByteBufferOrderMethodID = env->GetMethodID(
            mByteBufferClass,
            "order",
            "(Ljava/nio/ByteOrder;)Ljava/nio/ByteBuffer;");
    CHECK(mByteBufferOrderMethodID != NULL);

    mByteBufferAsReadOnlyBufferMethodID = env->GetMethodID(
            mByteBufferClass, "asReadOnlyBuffer", "()Ljava/nio/ByteBuffer;");
    CHECK(mByteBufferAsReadOnlyBufferMethodID != NULL);

    mByteBufferPositionMethodID = env->GetMethodID(
            mByteBufferClass, "position", "(I)Ljava/nio/Buffer;");
    CHECK(mByteBufferPositionMethodID != NULL);

    mByteBufferLimitMethodID = env->GetMethodID(
            mByteBufferClass, "limit", "(I)Ljava/nio/Buffer;");
    CHECK(mByteBufferLimitMethodID != NULL);
}

status_t JMediaCodec::initCheck() const {
    return mInitStatus;
}

void JMediaCodec::registerSelf() {
    mLooper->registerHandler(this);
}

void JMediaCodec::release() {
    if (mCodec != NULL) {
        mCodec->release();
        mCodec.clear();
        mInitStatus = NO_INIT;
    }

    if (mLooper != NULL) {
        mLooper->unregisterHandler(id());
        mLooper->stop();
        mLooper.clear();
    }
}

JMediaCodec::~JMediaCodec() {
    if (mCodec != NULL || mLooper != NULL) {
        /* MediaCodec and looper should have been released explicitly already
         * in setMediaCodec() (see comments in setMediaCodec()).
         *
         * Otherwise JMediaCodec::~JMediaCodec() might be called from within the
         * message handler, doing release() there risks deadlock as MediaCodec::
         * release() post synchronous message to the same looper.
         *
         * Print a warning and try to proceed with releasing.
         */
        ALOGW("try to release MediaCodec from JMediaCodec::~JMediaCodec()...");
        release();
        ALOGW("done releasing MediaCodec from JMediaCodec::~JMediaCodec().");
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteWeakGlobalRef(mObject);
    mObject = NULL;
    env->DeleteGlobalRef(mClass);
    mClass = NULL;
    deleteJavaObjects(env);
}

void JMediaCodec::deleteJavaObjects(JNIEnv *env) {
    env->DeleteGlobalRef(mByteBufferClass);
    mByteBufferClass = NULL;
    env->DeleteGlobalRef(mNativeByteOrderObj);
    mNativeByteOrderObj = NULL;

    mByteBufferOrderMethodID = NULL;
    mByteBufferAsReadOnlyBufferMethodID = NULL;
    mByteBufferPositionMethodID = NULL;
    mByteBufferLimitMethodID = NULL;
}

status_t JMediaCodec::enableOnFrameRenderedListener(jboolean enable) {
    if (enable) {
        if (mOnFrameRenderedNotification == NULL) {
            mOnFrameRenderedNotification = new AMessage(kWhatFrameRendered, this);
        }
    } else {
        mOnFrameRenderedNotification.clear();
    }

    return mCodec->setOnFrameRenderedNotification(mOnFrameRenderedNotification);
}

status_t JMediaCodec::setCallback(jobject cb) {
    if (cb != NULL) {
        if (mCallbackNotification == NULL) {
            mCallbackNotification = new AMessage(kWhatCallbackNotify, this);
        }
    } else {
        mCallbackNotification.clear();
    }

    return mCodec->setCallback(mCallbackNotification);
}

status_t JMediaCodec::configure(
        const sp<AMessage> &format,
        const sp<IGraphicBufferProducer> &bufferProducer,
        const sp<ICrypto> &crypto,
        const sp<IDescrambler> &descrambler,
        int flags) {
    sp<Surface> client;
    if (bufferProducer != NULL) {
        mSurfaceTextureClient =
            new Surface(bufferProducer, true /* controlledByApp */);
    } else {
        mSurfaceTextureClient.clear();
    }

    return mCodec->configure(
            format, mSurfaceTextureClient, crypto, descrambler, flags);
}

status_t JMediaCodec::setSurface(
        const sp<IGraphicBufferProducer> &bufferProducer) {
    sp<Surface> client;
    if (bufferProducer != NULL) {
        client = new Surface(bufferProducer, true /* controlledByApp */);
    }
    status_t err = mCodec->setSurface(client);
    if (err == OK) {
        mSurfaceTextureClient = client;
    }
    return err;
}

status_t JMediaCodec::createInputSurface(
        sp<IGraphicBufferProducer>* bufferProducer) {
    return mCodec->createInputSurface(bufferProducer);
}

status_t JMediaCodec::setInputSurface(
        const sp<PersistentSurface> &surface) {
    return mCodec->setInputSurface(surface);
}

status_t JMediaCodec::start() {
    return mCodec->start();
}

status_t JMediaCodec::stop() {
    mSurfaceTextureClient.clear();

    return mCodec->stop();
}

status_t JMediaCodec::flush() {
    return mCodec->flush();
}

status_t JMediaCodec::reset() {
    return mCodec->reset();
}

status_t JMediaCodec::queueInputBuffer(
        size_t index,
        size_t offset, size_t size, int64_t timeUs, uint32_t flags,
        AString *errorDetailMsg) {
    return mCodec->queueInputBuffer(
            index, offset, size, timeUs, flags, errorDetailMsg);
}

status_t JMediaCodec::queueSecureInputBuffer(
        size_t index,
        size_t offset,
        const CryptoPlugin::SubSample *subSamples,
        size_t numSubSamples,
        const uint8_t key[16],
        const uint8_t iv[16],
        CryptoPlugin::Mode mode,
        const CryptoPlugin::Pattern &pattern,
        int64_t presentationTimeUs,
        uint32_t flags,
        AString *errorDetailMsg) {
    return mCodec->queueSecureInputBuffer(
            index, offset, subSamples, numSubSamples, key, iv, mode, pattern,
            presentationTimeUs, flags, errorDetailMsg);
}

status_t JMediaCodec::dequeueInputBuffer(size_t *index, int64_t timeoutUs) {
    return mCodec->dequeueInputBuffer(index, timeoutUs);
}

status_t JMediaCodec::dequeueOutputBuffer(
        JNIEnv *env, jobject bufferInfo, size_t *index, int64_t timeoutUs) {
    size_t size, offset;
    int64_t timeUs;
    uint32_t flags;
    status_t err = mCodec->dequeueOutputBuffer(
            index, &offset, &size, &timeUs, &flags, timeoutUs);

    if (err != OK) {
        return err;
    }

    ScopedLocalRef<jclass> clazz(
            env, env->FindClass("android/media/MediaCodec$BufferInfo"));

    jmethodID method = env->GetMethodID(clazz.get(), "set", "(IIJI)V");
    env->CallVoidMethod(bufferInfo, method, (jint)offset, (jint)size, timeUs, flags);

    return OK;
}

status_t JMediaCodec::releaseOutputBuffer(
        size_t index, bool render, bool updatePTS, int64_t timestampNs) {
    if (updatePTS) {
        return mCodec->renderOutputBufferAndRelease(index, timestampNs);
    }
    return render
        ? mCodec->renderOutputBufferAndRelease(index)
        : mCodec->releaseOutputBuffer(index);
}

status_t JMediaCodec::signalEndOfInputStream() {
    return mCodec->signalEndOfInputStream();
}

status_t JMediaCodec::getFormat(JNIEnv *env, bool input, jobject *format) const {
    sp<AMessage> msg;
    status_t err;
    err = input ? mCodec->getInputFormat(&msg) : mCodec->getOutputFormat(&msg);
    if (err != OK) {
        return err;
    }

    return ConvertMessageToMap(env, msg, format);
}

status_t JMediaCodec::getOutputFormat(JNIEnv *env, size_t index, jobject *format) const {
    sp<AMessage> msg;
    status_t err;
    if ((err = mCodec->getOutputFormat(index, &msg)) != OK) {
        return err;
    }

    return ConvertMessageToMap(env, msg, format);
}

status_t JMediaCodec::getBuffers(
        JNIEnv *env, bool input, jobjectArray *bufArray) const {
    Vector<sp<MediaCodecBuffer> > buffers;

    status_t err =
        input
            ? mCodec->getInputBuffers(&buffers)
            : mCodec->getOutputBuffers(&buffers);

    if (err != OK) {
        return err;
    }

    *bufArray = (jobjectArray)env->NewObjectArray(
            buffers.size(), mByteBufferClass, NULL);
    if (*bufArray == NULL) {
        return NO_MEMORY;
    }

    for (size_t i = 0; i < buffers.size(); ++i) {
        const sp<MediaCodecBuffer> &buffer = buffers.itemAt(i);

        jobject byteBuffer = NULL;
        err = createByteBufferFromABuffer(
                env, !input /* readOnly */, true /* clearBuffer */, buffer, &byteBuffer);
        if (err != OK) {
            return err;
        }
        if (byteBuffer != NULL) {
            env->SetObjectArrayElement(
                    *bufArray, i, byteBuffer);

            env->DeleteLocalRef(byteBuffer);
            byteBuffer = NULL;
        }
    }

    return OK;
}

// static
template <typename T>
status_t JMediaCodec::createByteBufferFromABuffer(
        JNIEnv *env, bool readOnly, bool clearBuffer, const sp<T> &buffer,
        jobject *buf) const {
    // if this is an ABuffer that doesn't actually hold any accessible memory,
    // use a null ByteBuffer
    *buf = NULL;

    if (buffer == NULL) {
        ALOGV("createByteBufferFromABuffer - given NULL, returning NULL");
        return OK;
    }

    if (buffer->base() == NULL) {
        return OK;
    }

    jobject byteBuffer =
        env->NewDirectByteBuffer(buffer->base(), buffer->capacity());
    if (readOnly && byteBuffer != NULL) {
        jobject readOnlyBuffer = env->CallObjectMethod(
                byteBuffer, mByteBufferAsReadOnlyBufferMethodID);
        env->DeleteLocalRef(byteBuffer);
        byteBuffer = readOnlyBuffer;
    }
    if (byteBuffer == NULL) {
        return NO_MEMORY;
    }
    jobject me = env->CallObjectMethod(
            byteBuffer, mByteBufferOrderMethodID, mNativeByteOrderObj);
    env->DeleteLocalRef(me);
    me = env->CallObjectMethod(
            byteBuffer, mByteBufferLimitMethodID,
            clearBuffer ? buffer->capacity() : (buffer->offset() + buffer->size()));
    env->DeleteLocalRef(me);
    me = env->CallObjectMethod(
            byteBuffer, mByteBufferPositionMethodID,
            clearBuffer ? 0 : buffer->offset());
    env->DeleteLocalRef(me);
    me = NULL;

    *buf = byteBuffer;
    return OK;
}

status_t JMediaCodec::getBuffer(
        JNIEnv *env, bool input, size_t index, jobject *buf) const {
    sp<MediaCodecBuffer> buffer;

    status_t err =
        input
            ? mCodec->getInputBuffer(index, &buffer)
            : mCodec->getOutputBuffer(index, &buffer);

    if (err != OK) {
        return err;
    }

    return createByteBufferFromABuffer(
            env, !input /* readOnly */, input /* clearBuffer */, buffer, buf);
}

status_t JMediaCodec::getImage(
        JNIEnv *env, bool input, size_t index, jobject *buf) const {
    sp<MediaCodecBuffer> buffer;

    status_t err =
        input
            ? mCodec->getInputBuffer(index, &buffer)
            : mCodec->getOutputBuffer(index, &buffer);

    if (err != OK) {
        return err;
    }

    // if this is an ABuffer that doesn't actually hold any accessible memory,
    // use a null ByteBuffer
    *buf = NULL;
    if (buffer->base() == NULL) {
        return OK;
    }

    // check if buffer is an image
    sp<ABuffer> imageData;
    if (!buffer->meta()->findBuffer("image-data", &imageData)) {
        return OK;
    }

    int64_t timestamp = 0;
    if (!input && buffer->meta()->findInt64("timeUs", &timestamp)) {
        timestamp *= 1000; // adjust to ns
    }

    jobject byteBuffer = NULL;
    err = createByteBufferFromABuffer(
            env, !input /* readOnly */, input /* clearBuffer */, buffer, &byteBuffer);
    if (err != OK) {
        return OK;
    }

    jobject infoBuffer = NULL;
    err = createByteBufferFromABuffer(
            env, true /* readOnly */, true /* clearBuffer */, imageData, &infoBuffer);
    if (err != OK) {
        env->DeleteLocalRef(byteBuffer);
        byteBuffer = NULL;
        return OK;
    }

    jobject cropRect = NULL;
    int32_t left, top, right, bottom;
    if (buffer->meta()->findRect("crop-rect", &left, &top, &right, &bottom)) {
        ScopedLocalRef<jclass> rectClazz(
                env, env->FindClass("android/graphics/Rect"));
        CHECK(rectClazz.get() != NULL);

        jmethodID rectConstructID = env->GetMethodID(
                rectClazz.get(), "<init>", "(IIII)V");

        cropRect = env->NewObject(
                rectClazz.get(), rectConstructID, left, top, right + 1, bottom + 1);
    }

    ScopedLocalRef<jclass> imageClazz(
            env, env->FindClass("android/media/MediaCodec$MediaImage"));
    CHECK(imageClazz.get() != NULL);

    jmethodID imageConstructID = env->GetMethodID(imageClazz.get(), "<init>",
            "(Ljava/nio/ByteBuffer;Ljava/nio/ByteBuffer;ZJIILandroid/graphics/Rect;)V");

    *buf = env->NewObject(imageClazz.get(), imageConstructID,
            byteBuffer, infoBuffer,
            (jboolean)!input /* readOnly */,
            (jlong)timestamp,
            (jint)0 /* xOffset */, (jint)0 /* yOffset */, cropRect);

    // if MediaImage creation fails, return null
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        *buf = NULL;
    }

    if (cropRect != NULL) {
        env->DeleteLocalRef(cropRect);
        cropRect = NULL;
    }

    env->DeleteLocalRef(byteBuffer);
    byteBuffer = NULL;

    env->DeleteLocalRef(infoBuffer);
    infoBuffer = NULL;

    return OK;
}

status_t JMediaCodec::getName(JNIEnv *env, jstring *nameStr) const {
    AString name;

    status_t err = mCodec->getName(&name);

    if (err != OK) {
        return err;
    }

    *nameStr = env->NewStringUTF(name.c_str());

    return OK;
}

static jobject getCodecCapabilitiesObject(
        JNIEnv *env, const char *mime, bool isEncoder,
        const sp<MediaCodecInfo::Capabilities> &capabilities) {
    Vector<MediaCodecInfo::ProfileLevel> profileLevels;
    Vector<uint32_t> colorFormats;

    sp<AMessage> defaultFormat = new AMessage();
    defaultFormat->setString("mime", mime);

    capabilities->getSupportedColorFormats(&colorFormats);
    capabilities->getSupportedProfileLevels(&profileLevels);
    uint32_t flags = capabilities->getFlags();
    sp<AMessage> details = capabilities->getDetails();

    jobject defaultFormatObj = NULL;
    if (ConvertMessageToMap(env, defaultFormat, &defaultFormatObj)) {
        return NULL;
    }
    ScopedLocalRef<jobject> defaultFormatRef(env, defaultFormatObj);

    jobject detailsObj = NULL;
    if (ConvertMessageToMap(env, details, &detailsObj)) {
        return NULL;
    }
    ScopedLocalRef<jobject> detailsRef(env, detailsObj);

    ScopedLocalRef<jobjectArray> profileLevelArray(env, env->NewObjectArray(
            profileLevels.size(), gCodecInfo.profileLevelClazz, NULL));

    for (size_t i = 0; i < profileLevels.size(); ++i) {
        const MediaCodecInfo::ProfileLevel &src = profileLevels.itemAt(i);

        ScopedLocalRef<jobject> srcRef(env, env->AllocObject(
                gCodecInfo.profileLevelClazz));

        env->SetIntField(srcRef.get(), gCodecInfo.profileField, src.mProfile);
        env->SetIntField(srcRef.get(), gCodecInfo.levelField, src.mLevel);

        env->SetObjectArrayElement(profileLevelArray.get(), i, srcRef.get());
    }

    ScopedLocalRef<jintArray> colorFormatsArray(
            env, env->NewIntArray(colorFormats.size()));
    for (size_t i = 0; i < colorFormats.size(); ++i) {
        jint val = colorFormats.itemAt(i);
        env->SetIntArrayRegion(colorFormatsArray.get(), i, 1, &val);
    }

    return env->NewObject(
            gCodecInfo.capsClazz, gCodecInfo.capsCtorId,
            profileLevelArray.get(), colorFormatsArray.get(), isEncoder, flags,
            defaultFormatRef.get(), detailsRef.get());
}

status_t JMediaCodec::getCodecInfo(JNIEnv *env, jobject *codecInfoObject) const {
    sp<MediaCodecInfo> codecInfo;

    status_t err = mCodec->getCodecInfo(&codecInfo);

    if (err != OK) {
        return err;
    }

    ScopedLocalRef<jstring> nameObject(env,
            env->NewStringUTF(codecInfo->getCodecName()));

    bool isEncoder = codecInfo->isEncoder();

    Vector<AString> mimes;
    codecInfo->getSupportedMimes(&mimes);

    ScopedLocalRef<jobjectArray> capsArrayObj(env,
        env->NewObjectArray(mimes.size(), gCodecInfo.capsClazz, NULL));

    for (size_t i = 0; i < mimes.size(); i++) {
        const sp<MediaCodecInfo::Capabilities> caps =
                codecInfo->getCapabilitiesFor(mimes[i].c_str());

        ScopedLocalRef<jobject> capsObj(env, getCodecCapabilitiesObject(
                env, mimes[i].c_str(), isEncoder, caps));

        env->SetObjectArrayElement(capsArrayObj.get(), i, capsObj.get());
    }

    ScopedLocalRef<jclass> codecInfoClazz(env,
            env->FindClass("android/media/MediaCodecInfo"));
    CHECK(codecInfoClazz.get() != NULL);

    jmethodID codecInfoCtorID = env->GetMethodID(codecInfoClazz.get(), "<init>",
            "(Ljava/lang/String;Z[Landroid/media/MediaCodecInfo$CodecCapabilities;)V");

    *codecInfoObject = env->NewObject(codecInfoClazz.get(), codecInfoCtorID,
            nameObject.get(), isEncoder, capsArrayObj.get());

    return OK;
}

status_t JMediaCodec::getMetrics(JNIEnv *, MediaAnalyticsItem * &reply) const {

    status_t status = mCodec->getMetrics(reply);
    return status;
}

status_t JMediaCodec::setParameters(const sp<AMessage> &msg) {
    return mCodec->setParameters(msg);
}

void JMediaCodec::setVideoScalingMode(int mode) {
    if (mSurfaceTextureClient != NULL) {
        native_window_set_scaling_mode(mSurfaceTextureClient.get(), mode);
    }
}

static jthrowable createCodecException(
        JNIEnv *env, status_t err, int32_t actionCode, const char *msg = NULL) {
    ScopedLocalRef<jclass> clazz(
            env, env->FindClass("android/media/MediaCodec$CodecException"));
    CHECK(clazz.get() != NULL);

    const jmethodID ctor = env->GetMethodID(clazz.get(), "<init>", "(IILjava/lang/String;)V");
    CHECK(ctor != NULL);

    ScopedLocalRef<jstring> msgObj(
            env, env->NewStringUTF(msg != NULL ? msg : String8::format("Error %#x", err)));

    // translate action code to Java equivalent
    switch (actionCode) {
    case ACTION_CODE_TRANSIENT:
        actionCode = gCodecActionCodes.codecActionTransient;
        break;
    case ACTION_CODE_RECOVERABLE:
        actionCode = gCodecActionCodes.codecActionRecoverable;
        break;
    default:
        actionCode = 0;  // everything else is fatal
        break;
    }

    /* translate OS errors to Java API CodecException errorCodes */
    switch (err) {
        case NO_MEMORY:
            err = gCodecErrorCodes.errorInsufficientResource;
            break;
        case DEAD_OBJECT:
            err = gCodecErrorCodes.errorReclaimed;
            break;
        default:  /* Other error codes go out as is. */
            break;
    }

    return (jthrowable)env->NewObject(clazz.get(), ctor, err, actionCode, msgObj.get());
}

void JMediaCodec::handleCallback(const sp<AMessage> &msg) {
    int32_t arg1, arg2 = 0;
    jobject obj = NULL;
    CHECK(msg->findInt32("callbackID", &arg1));
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    switch (arg1) {
        case MediaCodec::CB_INPUT_AVAILABLE:
        {
            CHECK(msg->findInt32("index", &arg2));
            break;
        }

        case MediaCodec::CB_OUTPUT_AVAILABLE:
        {
            CHECK(msg->findInt32("index", &arg2));

            size_t size, offset;
            int64_t timeUs;
            uint32_t flags;
            CHECK(msg->findSize("size", &size));
            CHECK(msg->findSize("offset", &offset));
            CHECK(msg->findInt64("timeUs", &timeUs));
            CHECK(msg->findInt32("flags", (int32_t *)&flags));

            ScopedLocalRef<jclass> clazz(
                    env, env->FindClass("android/media/MediaCodec$BufferInfo"));
            jmethodID ctor = env->GetMethodID(clazz.get(), "<init>", "()V");
            jmethodID method = env->GetMethodID(clazz.get(), "set", "(IIJI)V");

            obj = env->NewObject(clazz.get(), ctor);

            if (obj == NULL) {
                if (env->ExceptionCheck()) {
                    ALOGE("Could not create MediaCodec.BufferInfo.");
                    env->ExceptionClear();
                }
                jniThrowException(env, "java/lang/IllegalStateException", NULL);
                return;
            }

            env->CallVoidMethod(obj, method, (jint)offset, (jint)size, timeUs, flags);
            break;
        }

        case MediaCodec::CB_ERROR:
        {
            int32_t err, actionCode;
            CHECK(msg->findInt32("err", &err));
            CHECK(msg->findInt32("actionCode", &actionCode));

            // note that DRM errors could conceivably alias into a CodecException
            obj = (jobject)createCodecException(env, err, actionCode);

            if (obj == NULL) {
                if (env->ExceptionCheck()) {
                    ALOGE("Could not create CodecException object.");
                    env->ExceptionClear();
                }
                jniThrowException(env, "java/lang/IllegalStateException", NULL);
                return;
            }

            break;
        }

        case MediaCodec::CB_OUTPUT_FORMAT_CHANGED:
        {
            sp<AMessage> format;
            CHECK(msg->findMessage("format", &format));

            if (OK != ConvertMessageToMap(env, format, &obj)) {
                jniThrowException(env, "java/lang/IllegalStateException", NULL);
                return;
            }

            break;
        }

        default:
            TRESPASS();
    }

    env->CallVoidMethod(
            mObject,
            gFields.postEventFromNativeID,
            EVENT_CALLBACK,
            arg1,
            arg2,
            obj);

    env->DeleteLocalRef(obj);
}

void JMediaCodec::handleFrameRenderedNotification(const sp<AMessage> &msg) {
    int32_t arg1 = 0, arg2 = 0;
    jobject obj = NULL;
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    sp<AMessage> data;
    CHECK(msg->findMessage("data", &data));

    status_t err = ConvertMessageToMap(env, data, &obj);
    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return;
    }

    env->CallVoidMethod(
            mObject, gFields.postEventFromNativeID,
            EVENT_FRAME_RENDERED, arg1, arg2, obj);

    env->DeleteLocalRef(obj);
}

void JMediaCodec::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatCallbackNotify:
        {
            handleCallback(msg);
            break;
        }
        case kWhatFrameRendered:
        {
            handleFrameRenderedNotification(msg);
            break;
        }
        default:
            TRESPASS();
    }
}

}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static sp<JMediaCodec> setMediaCodec(
        JNIEnv *env, jobject thiz, const sp<JMediaCodec> &codec) {
    sp<JMediaCodec> old = (JMediaCodec *)env->GetLongField(thiz, gFields.context);
    if (codec != NULL) {
        codec->incStrong(thiz);
    }
    if (old != NULL) {
        /* release MediaCodec and stop the looper now before decStrong.
         * otherwise JMediaCodec::~JMediaCodec() could be called from within
         * its message handler, doing release() from there will deadlock
         * (as MediaCodec::release() post synchronous message to the same looper)
         */
        old->release();
        old->decStrong(thiz);
    }
    env->SetLongField(thiz, gFields.context, (jlong)codec.get());

    return old;
}

static sp<JMediaCodec> getMediaCodec(JNIEnv *env, jobject thiz) {
    return (JMediaCodec *)env->GetLongField(thiz, gFields.context);
}

static void android_media_MediaCodec_release(JNIEnv *env, jobject thiz) {
    setMediaCodec(env, thiz, NULL);
}

static void throwCodecException(JNIEnv *env, status_t err, int32_t actionCode, const char *msg) {
    jthrowable exception = createCodecException(env, err, actionCode, msg);
    env->Throw(exception);
}

static void throwCryptoException(JNIEnv *env, status_t err, const char *msg) {
    ScopedLocalRef<jclass> clazz(
            env, env->FindClass("android/media/MediaCodec$CryptoException"));
    CHECK(clazz.get() != NULL);

    jmethodID constructID =
        env->GetMethodID(clazz.get(), "<init>", "(ILjava/lang/String;)V");
    CHECK(constructID != NULL);

    const char *defaultMsg = "Unknown Error";

    /* translate OS errors to Java API CryptoException errorCodes (which are positive) */
    switch (err) {
        case ERROR_DRM_NO_LICENSE:
            err = gCryptoErrorCodes.cryptoErrorNoKey;
            defaultMsg = "Crypto key not available";
            break;
        case ERROR_DRM_LICENSE_EXPIRED:
            err = gCryptoErrorCodes.cryptoErrorKeyExpired;
            defaultMsg = "License expired";
            break;
        case ERROR_DRM_RESOURCE_BUSY:
            err = gCryptoErrorCodes.cryptoErrorResourceBusy;
            defaultMsg = "Resource busy or unavailable";
            break;
        case ERROR_DRM_INSUFFICIENT_OUTPUT_PROTECTION:
            err = gCryptoErrorCodes.cryptoErrorInsufficientOutputProtection;
            defaultMsg = "Required output protections are not active";
            break;
        case ERROR_DRM_SESSION_NOT_OPENED:
            err = gCryptoErrorCodes.cryptoErrorSessionNotOpened;
            defaultMsg = "Attempted to use a closed session";
            break;
        case ERROR_DRM_CANNOT_HANDLE:
            err = gCryptoErrorCodes.cryptoErrorUnsupportedOperation;
            defaultMsg = "Operation not supported in this configuration";
            break;
        default:  /* Other negative DRM error codes go out as is. */
            break;
    }

    jstring msgObj = env->NewStringUTF(msg != NULL ? msg : defaultMsg);

    jthrowable exception =
        (jthrowable)env->NewObject(clazz.get(), constructID, err, msgObj);

    env->Throw(exception);
}

static jint throwExceptionAsNecessary(
        JNIEnv *env, status_t err, int32_t actionCode = ACTION_CODE_FATAL,
        const char *msg = NULL) {
    switch (err) {
        case OK:
            return 0;

        case -EAGAIN:
            return DEQUEUE_INFO_TRY_AGAIN_LATER;

        case INFO_FORMAT_CHANGED:
            return DEQUEUE_INFO_OUTPUT_FORMAT_CHANGED;

        case INFO_OUTPUT_BUFFERS_CHANGED:
            return DEQUEUE_INFO_OUTPUT_BUFFERS_CHANGED;

        case INVALID_OPERATION:
            jniThrowException(env, "java/lang/IllegalStateException", msg);
            return 0;

        case BAD_VALUE:
            jniThrowException(env, "java/lang/IllegalArgumentException", msg);
            return 0;

        default:
            if (isCryptoError(err)) {
                throwCryptoException(env, err, msg);
                return 0;
            }
            throwCodecException(env, err, actionCode, msg);
            return 0;
    }
}

static void android_media_MediaCodec_native_enableOnFrameRenderedListener(
        JNIEnv *env,
        jobject thiz,
        jboolean enabled) {
    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->enableOnFrameRenderedListener(enabled);

    throwExceptionAsNecessary(env, err);
}

static void android_media_MediaCodec_native_setCallback(
        JNIEnv *env,
        jobject thiz,
        jobject cb) {
    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->setCallback(cb);

    throwExceptionAsNecessary(env, err);
}

static void android_media_MediaCodec_native_configure(
        JNIEnv *env,
        jobject thiz,
        jobjectArray keys, jobjectArray values,
        jobject jsurface,
        jobject jcrypto,
        jobject descramblerBinderObj,
        jint flags) {
    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    sp<AMessage> format;
    status_t err = ConvertKeyValueArraysToMessage(env, keys, values, &format);

    if (err != OK) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    sp<IGraphicBufferProducer> bufferProducer;
    if (jsurface != NULL) {
        sp<Surface> surface(android_view_Surface_getSurface(env, jsurface));
        if (surface != NULL) {
            bufferProducer = surface->getIGraphicBufferProducer();
        } else {
            jniThrowException(
                    env,
                    "java/lang/IllegalArgumentException",
                    "The surface has been released");
            return;
        }
    }

    sp<ICrypto> crypto;
    if (jcrypto != NULL) {
        crypto = JCrypto::GetCrypto(env, jcrypto);
    }

    sp<IDescrambler> descrambler;
    if (descramblerBinderObj != NULL) {
        descrambler = GetDescrambler(env, descramblerBinderObj);
    }

    err = codec->configure(format, bufferProducer, crypto, descrambler, flags);

    throwExceptionAsNecessary(env, err);
}

static void android_media_MediaCodec_native_setSurface(
        JNIEnv *env,
        jobject thiz,
        jobject jsurface) {
    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    sp<IGraphicBufferProducer> bufferProducer;
    if (jsurface != NULL) {
        sp<Surface> surface(android_view_Surface_getSurface(env, jsurface));
        if (surface != NULL) {
            bufferProducer = surface->getIGraphicBufferProducer();
        } else {
            jniThrowException(
                    env,
                    "java/lang/IllegalArgumentException",
                    "The surface has been released");
            return;
        }
    }

    status_t err = codec->setSurface(bufferProducer);
    throwExceptionAsNecessary(env, err);
}

sp<PersistentSurface> android_media_MediaCodec_getPersistentInputSurface(
        JNIEnv* env, jobject object) {
    sp<PersistentSurface> persistentSurface;

    jobject lock = env->GetObjectField(
            object, gPersistentSurfaceClassInfo.mLock);
    if (env->MonitorEnter(lock) == JNI_OK) {
        persistentSurface = reinterpret_cast<PersistentSurface *>(
                env->GetLongField(object,
                        gPersistentSurfaceClassInfo.mPersistentObject));
        env->MonitorExit(lock);
    }
    env->DeleteLocalRef(lock);

    return persistentSurface;
}

static jobject android_media_MediaCodec_createPersistentInputSurface(
        JNIEnv* env, jclass /* clazz */) {
    ALOGV("android_media_MediaCodec_createPersistentInputSurface");
    sp<PersistentSurface> persistentSurface =
        MediaCodec::CreatePersistentInputSurface();

    if (persistentSurface == NULL) {
        return NULL;
    }

    sp<Surface> surface = new Surface(
            persistentSurface->getBufferProducer(), true);
    if (surface == NULL) {
        return NULL;
    }

    jobject object = env->NewObject(
            gPersistentSurfaceClassInfo.clazz,
            gPersistentSurfaceClassInfo.ctor);

    if (object == NULL) {
        if (env->ExceptionCheck()) {
            ALOGE("Could not create PersistentSurface.");
            env->ExceptionClear();
        }
        return NULL;
    }

    jobject lock = env->GetObjectField(
            object, gPersistentSurfaceClassInfo.mLock);
    if (env->MonitorEnter(lock) == JNI_OK) {
        env->CallVoidMethod(
                object,
                gPersistentSurfaceClassInfo.setNativeObjectLocked,
                (jlong)surface.get());
        env->SetLongField(
                object,
                gPersistentSurfaceClassInfo.mPersistentObject,
                (jlong)persistentSurface.get());
        env->MonitorExit(lock);
    } else {
        env->DeleteLocalRef(object);
        object = NULL;
    }
    env->DeleteLocalRef(lock);

    if (object != NULL) {
        surface->incStrong(&sRefBaseOwner);
        persistentSurface->incStrong(&sRefBaseOwner);
    }

    return object;
}

static void android_media_MediaCodec_releasePersistentInputSurface(
        JNIEnv* env, jclass /* clazz */, jobject object) {
    sp<PersistentSurface> persistentSurface;

    jobject lock = env->GetObjectField(
            object, gPersistentSurfaceClassInfo.mLock);
    if (env->MonitorEnter(lock) == JNI_OK) {
        persistentSurface = reinterpret_cast<PersistentSurface *>(
            env->GetLongField(
                    object, gPersistentSurfaceClassInfo.mPersistentObject));
        env->SetLongField(
                object,
                gPersistentSurfaceClassInfo.mPersistentObject,
                (jlong)0);
        env->MonitorExit(lock);
    }
    env->DeleteLocalRef(lock);

    if (persistentSurface != NULL) {
        persistentSurface->decStrong(&sRefBaseOwner);
    }
    // no need to release surface as it will be released by Surface's jni
}

static void android_media_MediaCodec_setInputSurface(
        JNIEnv* env, jobject thiz, jobject object) {
    ALOGV("android_media_MediaCodec_setInputSurface");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);
    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    sp<PersistentSurface> persistentSurface =
        android_media_MediaCodec_getPersistentInputSurface(env, object);

    if (persistentSurface == NULL) {
        throwExceptionAsNecessary(
                env, BAD_VALUE, ACTION_CODE_FATAL, "input surface not valid");
        return;
    }
    status_t err = codec->setInputSurface(persistentSurface);
    if (err != NO_ERROR) {
        throwExceptionAsNecessary(env, err);
    }
}

static jobject android_media_MediaCodec_createInputSurface(JNIEnv* env,
        jobject thiz) {
    ALOGV("android_media_MediaCodec_createInputSurface");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);
    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    // Tell the MediaCodec that we want to use a Surface as input.
    sp<IGraphicBufferProducer> bufferProducer;
    status_t err = codec->createInputSurface(&bufferProducer);
    if (err != NO_ERROR) {
        throwExceptionAsNecessary(env, err);
        return NULL;
    }

    // Wrap the IGBP in a Java-language Surface.
    return android_view_Surface_createFromIGraphicBufferProducer(env,
            bufferProducer);
}

static void android_media_MediaCodec_start(JNIEnv *env, jobject thiz) {
    ALOGV("android_media_MediaCodec_start");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->start();

    throwExceptionAsNecessary(env, err, ACTION_CODE_FATAL, "start failed");
}

static void android_media_MediaCodec_stop(JNIEnv *env, jobject thiz) {
    ALOGV("android_media_MediaCodec_stop");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->stop();

    throwExceptionAsNecessary(env, err);
}

static void android_media_MediaCodec_reset(JNIEnv *env, jobject thiz) {
    ALOGV("android_media_MediaCodec_reset");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->reset();
    if (err != OK) {
        // treat all errors as fatal for now, though resource not available
        // errors could be treated as transient.
        // we also should avoid sending INVALID_OPERATION here due to
        // the transitory nature of reset(), it should not inadvertently
        // trigger an IllegalStateException.
        err = UNKNOWN_ERROR;
    }
    throwExceptionAsNecessary(env, err);
}

static void android_media_MediaCodec_flush(JNIEnv *env, jobject thiz) {
    ALOGV("android_media_MediaCodec_flush");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->flush();

    throwExceptionAsNecessary(env, err);
}

static void android_media_MediaCodec_queueInputBuffer(
        JNIEnv *env,
        jobject thiz,
        jint index,
        jint offset,
        jint size,
        jlong timestampUs,
        jint flags) {
    ALOGV("android_media_MediaCodec_queueInputBuffer");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    AString errorDetailMsg;

    status_t err = codec->queueInputBuffer(
            index, offset, size, timestampUs, flags, &errorDetailMsg);

    throwExceptionAsNecessary(
            env, err, ACTION_CODE_FATAL, errorDetailMsg.empty() ? NULL : errorDetailMsg.c_str());
}

static void android_media_MediaCodec_queueSecureInputBuffer(
        JNIEnv *env,
        jobject thiz,
        jint index,
        jint offset,
        jobject cryptoInfoObj,
        jlong timestampUs,
        jint flags) {
    ALOGV("android_media_MediaCodec_queueSecureInputBuffer");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    jint numSubSamples =
        env->GetIntField(cryptoInfoObj, gFields.cryptoInfoNumSubSamplesID);

    jintArray numBytesOfClearDataObj =
        (jintArray)env->GetObjectField(
                cryptoInfoObj, gFields.cryptoInfoNumBytesOfClearDataID);

    jintArray numBytesOfEncryptedDataObj =
        (jintArray)env->GetObjectField(
                cryptoInfoObj, gFields.cryptoInfoNumBytesOfEncryptedDataID);

    jbyteArray keyObj =
        (jbyteArray)env->GetObjectField(cryptoInfoObj, gFields.cryptoInfoKeyID);

    jbyteArray ivObj =
        (jbyteArray)env->GetObjectField(cryptoInfoObj, gFields.cryptoInfoIVID);

    jint jmode = env->GetIntField(cryptoInfoObj, gFields.cryptoInfoModeID);
    enum CryptoPlugin::Mode mode;
    if (jmode == gCryptoModes.Unencrypted) {
        mode = CryptoPlugin::kMode_Unencrypted;
    } else if (jmode == gCryptoModes.AesCtr) {
        mode = CryptoPlugin::kMode_AES_CTR;
    } else if (jmode == gCryptoModes.AesCbc) {
        mode = CryptoPlugin::kMode_AES_CBC;
    }  else {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    jobject patternObj = env->GetObjectField(cryptoInfoObj, gFields.cryptoInfoPatternID);

    CryptoPlugin::Pattern pattern;
    if (patternObj == NULL) {
        pattern.mEncryptBlocks = 0;
        pattern.mSkipBlocks = 0;
    } else {
        pattern.mEncryptBlocks = env->GetIntField(patternObj, gFields.patternEncryptBlocksID);
        pattern.mSkipBlocks = env->GetIntField(patternObj, gFields.patternSkipBlocksID);
    }

    status_t err = OK;

    CryptoPlugin::SubSample *subSamples = NULL;
    jbyte *key = NULL;
    jbyte *iv = NULL;

    if (numSubSamples <= 0) {
        err = -EINVAL;
    } else if (numBytesOfClearDataObj == NULL
            && numBytesOfEncryptedDataObj == NULL) {
        err = -EINVAL;
    } else if (numBytesOfEncryptedDataObj != NULL
            && env->GetArrayLength(numBytesOfEncryptedDataObj) < numSubSamples) {
        err = -ERANGE;
    } else if (numBytesOfClearDataObj != NULL
            && env->GetArrayLength(numBytesOfClearDataObj) < numSubSamples) {
        err = -ERANGE;
    // subSamples array may silently overflow if number of samples are too large.  Use
    // INT32_MAX as maximum allocation size may be less than SIZE_MAX on some platforms
    } else if ( CC_UNLIKELY(numSubSamples >= (signed)(INT32_MAX / sizeof(*subSamples))) ) {
        err = -EINVAL;
    } else {
        jboolean isCopy;

        jint *numBytesOfClearData =
            (numBytesOfClearDataObj == NULL)
                ? NULL
                : env->GetIntArrayElements(numBytesOfClearDataObj, &isCopy);

        jint *numBytesOfEncryptedData =
            (numBytesOfEncryptedDataObj == NULL)
                ? NULL
                : env->GetIntArrayElements(numBytesOfEncryptedDataObj, &isCopy);

        subSamples = new CryptoPlugin::SubSample[numSubSamples];

        for (jint i = 0; i < numSubSamples; ++i) {
            subSamples[i].mNumBytesOfClearData =
                (numBytesOfClearData == NULL) ? 0 : numBytesOfClearData[i];

            subSamples[i].mNumBytesOfEncryptedData =
                (numBytesOfEncryptedData == NULL)
                    ? 0 : numBytesOfEncryptedData[i];
        }

        if (numBytesOfEncryptedData != NULL) {
            env->ReleaseIntArrayElements(
                    numBytesOfEncryptedDataObj, numBytesOfEncryptedData, 0);
            numBytesOfEncryptedData = NULL;
        }

        if (numBytesOfClearData != NULL) {
            env->ReleaseIntArrayElements(
                    numBytesOfClearDataObj, numBytesOfClearData, 0);
            numBytesOfClearData = NULL;
        }
    }

    if (err == OK && keyObj != NULL) {
        if (env->GetArrayLength(keyObj) != 16) {
            err = -EINVAL;
        } else {
            jboolean isCopy;
            key = env->GetByteArrayElements(keyObj, &isCopy);
        }
    }

    if (err == OK && ivObj != NULL) {
        if (env->GetArrayLength(ivObj) != 16) {
            err = -EINVAL;
        } else {
            jboolean isCopy;
            iv = env->GetByteArrayElements(ivObj, &isCopy);
        }
    }

    AString errorDetailMsg;

    if (err == OK) {
        err = codec->queueSecureInputBuffer(
                index, offset,
                subSamples, numSubSamples,
                (const uint8_t *)key, (const uint8_t *)iv,
                mode,
                pattern,
                timestampUs,
                flags,
                &errorDetailMsg);
    }

    if (iv != NULL) {
        env->ReleaseByteArrayElements(ivObj, iv, 0);
        iv = NULL;
    }

    if (key != NULL) {
        env->ReleaseByteArrayElements(keyObj, key, 0);
        key = NULL;
    }

    delete[] subSamples;
    subSamples = NULL;

    throwExceptionAsNecessary(
            env, err, ACTION_CODE_FATAL, errorDetailMsg.empty() ? NULL : errorDetailMsg.c_str());
}

static jint android_media_MediaCodec_dequeueInputBuffer(
        JNIEnv *env, jobject thiz, jlong timeoutUs) {
    ALOGV("android_media_MediaCodec_dequeueInputBuffer");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return -1;
    }

    size_t index;
    status_t err = codec->dequeueInputBuffer(&index, timeoutUs);

    if (err == OK) {
        return (jint) index;
    }

    return throwExceptionAsNecessary(env, err);
}

static jint android_media_MediaCodec_dequeueOutputBuffer(
        JNIEnv *env, jobject thiz, jobject bufferInfo, jlong timeoutUs) {
    ALOGV("android_media_MediaCodec_dequeueOutputBuffer");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return 0;
    }

    size_t index;
    status_t err = codec->dequeueOutputBuffer(
            env, bufferInfo, &index, timeoutUs);

    if (err == OK) {
        return (jint) index;
    }

    return throwExceptionAsNecessary(env, err);
}

static void android_media_MediaCodec_releaseOutputBuffer(
        JNIEnv *env, jobject thiz,
        jint index, jboolean render, jboolean updatePTS, jlong timestampNs) {
    ALOGV("android_media_MediaCodec_renderOutputBufferAndRelease");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->releaseOutputBuffer(index, render, updatePTS, timestampNs);

    throwExceptionAsNecessary(env, err);
}

static void android_media_MediaCodec_signalEndOfInputStream(JNIEnv* env,
        jobject thiz) {
    ALOGV("android_media_MediaCodec_signalEndOfInputStream");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);
    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->signalEndOfInputStream();

    throwExceptionAsNecessary(env, err);
}

static jobject android_media_MediaCodec_getFormatNative(
        JNIEnv *env, jobject thiz, jboolean input) {
    ALOGV("android_media_MediaCodec_getFormatNative");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    jobject format;
    status_t err = codec->getFormat(env, input, &format);

    if (err == OK) {
        return format;
    }

    throwExceptionAsNecessary(env, err);

    return NULL;
}

static jobject android_media_MediaCodec_getOutputFormatForIndexNative(
        JNIEnv *env, jobject thiz, jint index) {
    ALOGV("android_media_MediaCodec_getOutputFormatForIndexNative");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    jobject format;
    status_t err = codec->getOutputFormat(env, index, &format);

    if (err == OK) {
        return format;
    }

    throwExceptionAsNecessary(env, err);

    return NULL;
}

static jobjectArray android_media_MediaCodec_getBuffers(
        JNIEnv *env, jobject thiz, jboolean input) {
    ALOGV("android_media_MediaCodec_getBuffers");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    jobjectArray buffers;
    status_t err = codec->getBuffers(env, input, &buffers);

    if (err == OK) {
        return buffers;
    }

    // if we're out of memory, an exception was already thrown
    if (err != NO_MEMORY) {
        throwExceptionAsNecessary(env, err);
    }

    return NULL;
}

static jobject android_media_MediaCodec_getBuffer(
        JNIEnv *env, jobject thiz, jboolean input, jint index) {
    ALOGV("android_media_MediaCodec_getBuffer");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    jobject buffer;
    status_t err = codec->getBuffer(env, input, index, &buffer);

    if (err == OK) {
        return buffer;
    }

    // if we're out of memory, an exception was already thrown
    if (err != NO_MEMORY) {
        throwExceptionAsNecessary(env, err);
    }

    return NULL;
}

static jobject android_media_MediaCodec_getImage(
        JNIEnv *env, jobject thiz, jboolean input, jint index) {
    ALOGV("android_media_MediaCodec_getImage");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    jobject image;
    status_t err = codec->getImage(env, input, index, &image);

    if (err == OK) {
        return image;
    }

    // if we're out of memory, an exception was already thrown
    if (err != NO_MEMORY) {
        throwExceptionAsNecessary(env, err);
    }

    return NULL;
}

static jobject android_media_MediaCodec_getName(
        JNIEnv *env, jobject thiz) {
    ALOGV("android_media_MediaCodec_getName");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    jstring name;
    status_t err = codec->getName(env, &name);

    if (err == OK) {
        return name;
    }

    throwExceptionAsNecessary(env, err);

    return NULL;
}

static jobject android_media_MediaCodec_getOwnCodecInfo(
        JNIEnv *env, jobject thiz) {
    ALOGV("android_media_MediaCodec_getOwnCodecInfo");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return NULL;
    }

    jobject codecInfoObj;
    status_t err = codec->getCodecInfo(env, &codecInfoObj);

    if (err == OK) {
        return codecInfoObj;
    }

    throwExceptionAsNecessary(env, err);

    return NULL;
}

static jobject
android_media_MediaCodec_native_getMetrics(JNIEnv *env, jobject thiz)
{
    ALOGV("android_media_MediaCodec_native_getMetrics");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);
    if (codec == NULL ) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    // get what we have for the metrics from the codec
    MediaAnalyticsItem *item = NULL;

    status_t err = codec->getMetrics(env, item);
    if (err != OK) {
        ALOGE("getMetrics failed");
        return (jobject) NULL;
    }

    jobject mybundle = MediaMetricsJNI::writeMetricsToBundle(env, item, NULL);

    // housekeeping
    delete item;
    item = NULL;

    return mybundle;
}

static void android_media_MediaCodec_setParameters(
        JNIEnv *env, jobject thiz, jobjectArray keys, jobjectArray vals) {
    ALOGV("android_media_MediaCodec_setParameters");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    sp<AMessage> params;
    status_t err = ConvertKeyValueArraysToMessage(env, keys, vals, &params);

    if (err == OK) {
        err = codec->setParameters(params);
    }

    throwExceptionAsNecessary(env, err);
}

static void android_media_MediaCodec_setVideoScalingMode(
        JNIEnv *env, jobject thiz, jint mode) {
    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    if (mode != NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW
            && mode != NATIVE_WINDOW_SCALING_MODE_SCALE_CROP) {
        jniThrowException(env, "java/lang/IllegalArgumentException", NULL);
        return;
    }

    codec->setVideoScalingMode(mode);
}

static void android_media_MediaCodec_native_init(JNIEnv *env) {
    ScopedLocalRef<jclass> clazz(
            env, env->FindClass("android/media/MediaCodec"));
    CHECK(clazz.get() != NULL);

    gFields.context = env->GetFieldID(clazz.get(), "mNativeContext", "J");
    CHECK(gFields.context != NULL);

    gFields.postEventFromNativeID =
        env->GetMethodID(
                clazz.get(), "postEventFromNative", "(IIILjava/lang/Object;)V");

    CHECK(gFields.postEventFromNativeID != NULL);

    jfieldID field;
    field = env->GetStaticFieldID(clazz.get(), "CRYPTO_MODE_UNENCRYPTED", "I");
    CHECK(field != NULL);
    gCryptoModes.Unencrypted =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "CRYPTO_MODE_AES_CTR", "I");
    CHECK(field != NULL);
    gCryptoModes.AesCtr =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "CRYPTO_MODE_AES_CBC", "I");
    CHECK(field != NULL);
    gCryptoModes.AesCbc =
        env->GetStaticIntField(clazz.get(), field);

    clazz.reset(env->FindClass("android/media/MediaCodec$CryptoInfo"));
    CHECK(clazz.get() != NULL);

    gFields.cryptoInfoNumSubSamplesID =
        env->GetFieldID(clazz.get(), "numSubSamples", "I");
    CHECK(gFields.cryptoInfoNumSubSamplesID != NULL);

    gFields.cryptoInfoNumBytesOfClearDataID =
        env->GetFieldID(clazz.get(), "numBytesOfClearData", "[I");
    CHECK(gFields.cryptoInfoNumBytesOfClearDataID != NULL);

    gFields.cryptoInfoNumBytesOfEncryptedDataID =
        env->GetFieldID(clazz.get(), "numBytesOfEncryptedData", "[I");
    CHECK(gFields.cryptoInfoNumBytesOfEncryptedDataID != NULL);

    gFields.cryptoInfoKeyID = env->GetFieldID(clazz.get(), "key", "[B");
    CHECK(gFields.cryptoInfoKeyID != NULL);

    gFields.cryptoInfoIVID = env->GetFieldID(clazz.get(), "iv", "[B");
    CHECK(gFields.cryptoInfoIVID != NULL);

    gFields.cryptoInfoModeID = env->GetFieldID(clazz.get(), "mode", "I");
    CHECK(gFields.cryptoInfoModeID != NULL);

    gFields.cryptoInfoPatternID = env->GetFieldID(clazz.get(), "pattern",
        "Landroid/media/MediaCodec$CryptoInfo$Pattern;");
    CHECK(gFields.cryptoInfoPatternID != NULL);

    clazz.reset(env->FindClass("android/media/MediaCodec$CryptoInfo$Pattern"));
    CHECK(clazz.get() != NULL);

    gFields.patternEncryptBlocksID = env->GetFieldID(clazz.get(), "mEncryptBlocks", "I");
    CHECK(gFields.patternEncryptBlocksID != NULL);

    gFields.patternSkipBlocksID = env->GetFieldID(clazz.get(), "mSkipBlocks", "I");
    CHECK(gFields.patternSkipBlocksID != NULL);

    clazz.reset(env->FindClass("android/media/MediaCodec$CryptoException"));
    CHECK(clazz.get() != NULL);

    field = env->GetStaticFieldID(clazz.get(), "ERROR_NO_KEY", "I");
    CHECK(field != NULL);
    gCryptoErrorCodes.cryptoErrorNoKey =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "ERROR_KEY_EXPIRED", "I");
    CHECK(field != NULL);
    gCryptoErrorCodes.cryptoErrorKeyExpired =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "ERROR_RESOURCE_BUSY", "I");
    CHECK(field != NULL);
    gCryptoErrorCodes.cryptoErrorResourceBusy =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "ERROR_INSUFFICIENT_OUTPUT_PROTECTION", "I");
    CHECK(field != NULL);
    gCryptoErrorCodes.cryptoErrorInsufficientOutputProtection =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "ERROR_SESSION_NOT_OPENED", "I");
    CHECK(field != NULL);
    gCryptoErrorCodes.cryptoErrorSessionNotOpened =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "ERROR_UNSUPPORTED_OPERATION", "I");
    CHECK(field != NULL);
    gCryptoErrorCodes.cryptoErrorUnsupportedOperation =
        env->GetStaticIntField(clazz.get(), field);

    clazz.reset(env->FindClass("android/media/MediaCodec$CodecException"));
    CHECK(clazz.get() != NULL);
    field = env->GetStaticFieldID(clazz.get(), "ACTION_TRANSIENT", "I");
    CHECK(field != NULL);
    gCodecActionCodes.codecActionTransient =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "ACTION_RECOVERABLE", "I");
    CHECK(field != NULL);
    gCodecActionCodes.codecActionRecoverable =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "ERROR_INSUFFICIENT_RESOURCE", "I");
    CHECK(field != NULL);
    gCodecErrorCodes.errorInsufficientResource =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "ERROR_RECLAIMED", "I");
    CHECK(field != NULL);
    gCodecErrorCodes.errorReclaimed =
        env->GetStaticIntField(clazz.get(), field);

    clazz.reset(env->FindClass("android/view/Surface"));
    CHECK(clazz.get() != NULL);

    field = env->GetFieldID(clazz.get(), "mLock", "Ljava/lang/Object;");
    CHECK(field != NULL);
    gPersistentSurfaceClassInfo.mLock = field;

    jmethodID method = env->GetMethodID(clazz.get(), "setNativeObjectLocked", "(J)V");
    CHECK(method != NULL);
    gPersistentSurfaceClassInfo.setNativeObjectLocked = method;

    clazz.reset(env->FindClass("android/media/MediaCodec$PersistentSurface"));
    CHECK(clazz.get() != NULL);
    gPersistentSurfaceClassInfo.clazz = (jclass)env->NewGlobalRef(clazz.get());

    method = env->GetMethodID(clazz.get(), "<init>", "()V");
    CHECK(method != NULL);
    gPersistentSurfaceClassInfo.ctor = method;

    field = env->GetFieldID(clazz.get(), "mPersistentObject", "J");
    CHECK(field != NULL);
    gPersistentSurfaceClassInfo.mPersistentObject = field;

    clazz.reset(env->FindClass("android/media/MediaCodecInfo$CodecCapabilities"));
    CHECK(clazz.get() != NULL);
    gCodecInfo.capsClazz = (jclass)env->NewGlobalRef(clazz.get());

    method = env->GetMethodID(clazz.get(), "<init>",
            "([Landroid/media/MediaCodecInfo$CodecProfileLevel;[IZI"
            "Ljava/util/Map;Ljava/util/Map;)V");
    CHECK(method != NULL);
    gCodecInfo.capsCtorId = method;

    clazz.reset(env->FindClass("android/media/MediaCodecInfo$CodecProfileLevel"));
    CHECK(clazz.get() != NULL);
    gCodecInfo.profileLevelClazz = (jclass)env->NewGlobalRef(clazz.get());

    field = env->GetFieldID(clazz.get(), "profile", "I");
    CHECK(field != NULL);
    gCodecInfo.profileField = field;

    field = env->GetFieldID(clazz.get(), "level", "I");
    CHECK(field != NULL);
    gCodecInfo.levelField = field;
}

static void android_media_MediaCodec_native_setup(
        JNIEnv *env, jobject thiz,
        jstring name, jboolean nameIsType, jboolean encoder) {
    if (name == NULL) {
        jniThrowException(env, "java/lang/NullPointerException", NULL);
        return;
    }

    const char *tmp = env->GetStringUTFChars(name, NULL);

    if (tmp == NULL) {
        return;
    }

    sp<JMediaCodec> codec = new JMediaCodec(env, thiz, tmp, nameIsType, encoder);

    const status_t err = codec->initCheck();
    if (err == NAME_NOT_FOUND) {
        // fail and do not try again.
        jniThrowException(env, "java/lang/IllegalArgumentException",
                String8::format("Failed to initialize %s, error %#x", tmp, err));
        env->ReleaseStringUTFChars(name, tmp);
        return;
    } if (err == NO_MEMORY) {
        throwCodecException(env, err, ACTION_CODE_TRANSIENT,
                String8::format("Failed to initialize %s, error %#x", tmp, err));
        env->ReleaseStringUTFChars(name, tmp);
        return;
    } else if (err != OK) {
        // believed possible to try again
        jniThrowException(env, "java/io/IOException",
                String8::format("Failed to find matching codec %s, error %#x", tmp, err));
        env->ReleaseStringUTFChars(name, tmp);
        return;
    }

    env->ReleaseStringUTFChars(name, tmp);

    codec->registerSelf();

    setMediaCodec(env,thiz, codec);
}

static void android_media_MediaCodec_native_finalize(
        JNIEnv *env, jobject thiz) {
    android_media_MediaCodec_release(env, thiz);
}

static const JNINativeMethod gMethods[] = {
    { "native_release", "()V", (void *)android_media_MediaCodec_release },

    { "native_reset", "()V", (void *)android_media_MediaCodec_reset },

    { "native_releasePersistentInputSurface",
      "(Landroid/view/Surface;)V",
       (void *)android_media_MediaCodec_releasePersistentInputSurface},

    { "native_createPersistentInputSurface",
      "()Landroid/media/MediaCodec$PersistentSurface;",
      (void *)android_media_MediaCodec_createPersistentInputSurface },

    { "native_setInputSurface", "(Landroid/view/Surface;)V",
      (void *)android_media_MediaCodec_setInputSurface },

    { "native_enableOnFrameRenderedListener", "(Z)V",
      (void *)android_media_MediaCodec_native_enableOnFrameRenderedListener },

    { "native_setCallback",
      "(Landroid/media/MediaCodec$Callback;)V",
      (void *)android_media_MediaCodec_native_setCallback },

    { "native_configure",
      "([Ljava/lang/String;[Ljava/lang/Object;Landroid/view/Surface;"
      "Landroid/media/MediaCrypto;Landroid/os/IHwBinder;I)V",
      (void *)android_media_MediaCodec_native_configure },

    { "native_setSurface",
      "(Landroid/view/Surface;)V",
      (void *)android_media_MediaCodec_native_setSurface },

    { "createInputSurface", "()Landroid/view/Surface;",
      (void *)android_media_MediaCodec_createInputSurface },

    { "native_start", "()V", (void *)android_media_MediaCodec_start },
    { "native_stop", "()V", (void *)android_media_MediaCodec_stop },
    { "native_flush", "()V", (void *)android_media_MediaCodec_flush },

    { "native_queueInputBuffer", "(IIIJI)V",
      (void *)android_media_MediaCodec_queueInputBuffer },

    { "native_queueSecureInputBuffer", "(IILandroid/media/MediaCodec$CryptoInfo;JI)V",
      (void *)android_media_MediaCodec_queueSecureInputBuffer },

    { "native_dequeueInputBuffer", "(J)I",
      (void *)android_media_MediaCodec_dequeueInputBuffer },

    { "native_dequeueOutputBuffer", "(Landroid/media/MediaCodec$BufferInfo;J)I",
      (void *)android_media_MediaCodec_dequeueOutputBuffer },

    { "releaseOutputBuffer", "(IZZJ)V",
      (void *)android_media_MediaCodec_releaseOutputBuffer },

    { "signalEndOfInputStream", "()V",
      (void *)android_media_MediaCodec_signalEndOfInputStream },

    { "getFormatNative", "(Z)Ljava/util/Map;",
      (void *)android_media_MediaCodec_getFormatNative },

    { "getOutputFormatNative", "(I)Ljava/util/Map;",
      (void *)android_media_MediaCodec_getOutputFormatForIndexNative },

    { "getBuffers", "(Z)[Ljava/nio/ByteBuffer;",
      (void *)android_media_MediaCodec_getBuffers },

    { "getBuffer", "(ZI)Ljava/nio/ByteBuffer;",
      (void *)android_media_MediaCodec_getBuffer },

    { "getImage", "(ZI)Landroid/media/Image;",
      (void *)android_media_MediaCodec_getImage },

    { "getName", "()Ljava/lang/String;",
      (void *)android_media_MediaCodec_getName },

    { "getOwnCodecInfo", "()Landroid/media/MediaCodecInfo;",
        (void *)android_media_MediaCodec_getOwnCodecInfo },

    { "native_getMetrics", "()Landroid/os/PersistableBundle;",
      (void *)android_media_MediaCodec_native_getMetrics},

    { "setParameters", "([Ljava/lang/String;[Ljava/lang/Object;)V",
      (void *)android_media_MediaCodec_setParameters },

    { "setVideoScalingMode", "(I)V",
      (void *)android_media_MediaCodec_setVideoScalingMode },

    { "native_init", "()V", (void *)android_media_MediaCodec_native_init },

    { "native_setup", "(Ljava/lang/String;ZZ)V",
      (void *)android_media_MediaCodec_native_setup },

    { "native_finalize", "()V",
      (void *)android_media_MediaCodec_native_finalize },
};

int register_android_media_MediaCodec(JNIEnv *env) {
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaCodec", gMethods, NELEM(gMethods));
}
