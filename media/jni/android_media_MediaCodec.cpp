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

#include <type_traits>

#include "android_media_MediaCodec.h"

#include "android_media_MediaCodecLinearBlock.h"
#include "android_media_MediaCrypto.h"
#include "android_media_MediaDescrambler.h"
#include "android_media_MediaMetricsJNI.h"
#include "android_media_Streams.h"
#include "android_runtime/AndroidRuntime.h"
#include "android_runtime/android_view_Surface.h"
#include "android_util_Binder.h"
#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>

#include <C2AllocatorGralloc.h>
#include <C2BlockInternal.h>
#include <C2Buffer.h>
#include <C2PlatformSupport.h>

#include <android/hardware/cas/native/1.0/IDescrambler.h>

#include <android_runtime/android_hardware_HardwareBuffer.h>

#include <binder/MemoryDealer.h>

#include <cutils/compiler.h>

#include <gui/Surface.h>

#include <hidlmemory/FrameworkUtils.h>

#include <media/MediaCodecBuffer.h>
#include <media/hardware/VideoAPI.h>
#include <media/stagefright/MediaCodec.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/AString.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/PersistentSurface.h>
#include <mediadrm/DrmUtils.h>
#include <mediadrm/ICrypto.h>

#include <private/android/AHardwareBufferHelpers.h>

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
    EVENT_FIRST_TUNNEL_FRAME_READY = 4,
};

static struct CryptoErrorCodes {
    jint cryptoErrorNoKey;
    jint cryptoErrorKeyExpired;
    jint cryptoErrorResourceBusy;
    jint cryptoErrorInsufficientOutputProtection;
    jint cryptoErrorSessionNotOpened;
    jint cryptoErrorInsufficientSecurity;
    jint cryptoErrorUnsupportedOperation;
    jint cryptoErrorFrameTooLarge;
    jint cryptoErrorLostState;
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

static struct {
    jclass clazz;
    jobject nativeByteOrder;
    jmethodID orderId;
    jmethodID asReadOnlyBufferId;
    jmethodID positionId;
    jmethodID limitId;
    jmethodID getPositionId;
    jmethodID getLimitId;
} gByteBufferInfo;

static struct {
    jmethodID sizeId;
    jmethodID getId;
    jmethodID addId;
} gArrayListInfo;

static struct {
    jclass clazz;
    jmethodID ctorId;
    jmethodID setInternalStateId;
    jfieldID contextId;
    jfieldID validId;
    jfieldID lockId;
} gLinearBlockInfo;

struct fields_t {
    jmethodID postEventFromNativeID;
    jmethodID lockAndGetContextID;
    jmethodID setAndUnlockContextID;
    jfieldID cryptoInfoNumSubSamplesID;
    jfieldID cryptoInfoNumBytesOfClearDataID;
    jfieldID cryptoInfoNumBytesOfEncryptedDataID;
    jfieldID cryptoInfoKeyID;
    jfieldID cryptoInfoIVID;
    jfieldID cryptoInfoModeID;
    jfieldID cryptoInfoPatternID;
    jfieldID patternEncryptBlocksID;
    jfieldID patternSkipBlocksID;
    jfieldID queueRequestIndexID;
    jfieldID outputFrameLinearBlockID;
    jfieldID outputFrameHardwareBufferID;
    jfieldID outputFrameChangedKeysID;
    jfieldID outputFrameFormatID;
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

    mLooper = new ALooper;
    mLooper->setName("MediaCodec_looper");

    mLooper->start(
            false,      // runOnCallingThread
            true,       // canCallJava
            ANDROID_PRIORITY_VIDEO);

    if (nameIsType) {
        mCodec = MediaCodec::CreateByType(mLooper, name, encoder, &mInitStatus);
        if (mCodec == nullptr || mCodec->getName(&mNameAtCreation) != OK) {
            mNameAtCreation = "(null)";
        }
    } else {
        mCodec = MediaCodec::CreateByComponentName(mLooper, name, &mInitStatus);
        mNameAtCreation = name;
    }
    CHECK((mCodec != NULL) != (mInitStatus != OK));
}

status_t JMediaCodec::initCheck() const {
    return mInitStatus;
}

void JMediaCodec::registerSelf() {
    mLooper->registerHandler(this);
}

void JMediaCodec::release() {
    std::call_once(mReleaseFlag, [this] {
        if (mCodec != NULL) {
            mCodec->release();
            mInitStatus = NO_INIT;
        }

        if (mLooper != NULL) {
            mLooper->unregisterHandler(id());
            mLooper->stop();
            mLooper.clear();
        }
    });
}

void JMediaCodec::releaseAsync() {
    std::call_once(mAsyncReleaseFlag, [this] {
        if (mCodec != NULL) {
            sp<AMessage> notify = new AMessage(kWhatAsyncReleaseComplete, this);
            // Hold strong reference to this until async release is complete
            notify->setObject("this", this);
            mCodec->releaseAsync(notify);
        }
        mInitStatus = NO_INIT;
    });
}

JMediaCodec::~JMediaCodec() {
    if (mLooper != NULL) {
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
}

status_t JMediaCodec::enableOnFirstTunnelFrameReadyListener(jboolean enable) {
    if (enable) {
        if (mOnFirstTunnelFrameReadyNotification == NULL) {
            mOnFirstTunnelFrameReadyNotification = new AMessage(kWhatFirstTunnelFrameReady, this);
        }
    } else {
        mOnFirstTunnelFrameReadyNotification.clear();
    }

    return mCodec->setOnFirstTunnelFrameReadyNotification(mOnFirstTunnelFrameReadyNotification);
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

    constexpr int32_t CONFIGURE_FLAG_ENCODE = 1;
    AString mime;
    CHECK(format->findString("mime", &mime));
    mGraphicOutput = (mime.startsWithIgnoreCase("video/") || mime.startsWithIgnoreCase("image/"))
            && !(flags & CONFIGURE_FLAG_ENCODE);
    mHasCryptoOrDescrambler = (crypto != nullptr) || (descrambler != nullptr);
    mCrypto = crypto;

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

status_t JMediaCodec::queueBuffer(
        size_t index, const std::shared_ptr<C2Buffer> &buffer, int64_t timeUs,
        uint32_t flags, const sp<AMessage> &tunings, AString *errorDetailMsg) {
    return mCodec->queueBuffer(
            index, buffer, timeUs, flags, tunings, errorDetailMsg);
}

status_t JMediaCodec::queueEncryptedLinearBlock(
        size_t index,
        const sp<hardware::HidlMemory> &buffer,
        size_t offset,
        const CryptoPlugin::SubSample *subSamples,
        size_t numSubSamples,
        const uint8_t key[16],
        const uint8_t iv[16],
        CryptoPlugin::Mode mode,
        const CryptoPlugin::Pattern &pattern,
        int64_t presentationTimeUs,
        uint32_t flags,
        const sp<AMessage> &tunings,
        AString *errorDetailMsg) {
    return mCodec->queueEncryptedBuffer(
            index, buffer, offset, subSamples, numSubSamples, key, iv, mode, pattern,
            presentationTimeUs, flags, tunings, errorDetailMsg);
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
            buffers.size(), gByteBufferInfo.clazz, NULL);
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

template <typename T>
static jobject CreateByteBuffer(
        JNIEnv *env, T *base, size_t capacity, size_t offset, size_t size,
        bool readOnly, bool clearBuffer) {
    jobject byteBuffer =
        env->NewDirectByteBuffer(
                const_cast<typename std::remove_const<T>::type *>(base),
                capacity);
    if (readOnly && byteBuffer != NULL) {
        jobject readOnlyBuffer = env->CallObjectMethod(
                byteBuffer, gByteBufferInfo.asReadOnlyBufferId);
        env->DeleteLocalRef(byteBuffer);
        byteBuffer = readOnlyBuffer;
    }
    if (byteBuffer == NULL) {
        return nullptr;
    }
    jobject me = env->CallObjectMethod(
            byteBuffer, gByteBufferInfo.orderId, gByteBufferInfo.nativeByteOrder);
    env->DeleteLocalRef(me);
    me = env->CallObjectMethod(
            byteBuffer, gByteBufferInfo.limitId,
            clearBuffer ? capacity : offset + size);
    env->DeleteLocalRef(me);
    me = env->CallObjectMethod(
            byteBuffer, gByteBufferInfo.positionId,
            clearBuffer ? 0 : offset);
    env->DeleteLocalRef(me);
    me = NULL;
    return byteBuffer;
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

    jobject byteBuffer = CreateByteBuffer(
            env, buffer->base(), buffer->capacity(), buffer->offset(), buffer->size(),
            readOnly, clearBuffer);

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

status_t JMediaCodec::getOutputFrame(
        JNIEnv *env, jobject frame, size_t index) const {
    sp<MediaCodecBuffer> buffer;

    status_t err = mCodec->getOutputBuffer(index, &buffer);
    if (err != OK) {
        return err;
    }

    if (buffer->size() > 0) {
        std::shared_ptr<C2Buffer> c2Buffer = buffer->asC2Buffer();
        if (c2Buffer) {
            switch (c2Buffer->data().type()) {
                case C2BufferData::LINEAR: {
                    std::unique_ptr<JMediaCodecLinearBlock> context{new JMediaCodecLinearBlock};
                    context->mBuffer = c2Buffer;
                    ScopedLocalRef<jobject> linearBlock{env, env->NewObject(
                            gLinearBlockInfo.clazz, gLinearBlockInfo.ctorId)};
                    env->CallVoidMethod(
                            linearBlock.get(),
                            gLinearBlockInfo.setInternalStateId,
                            (jlong)context.release(),
                            true);
                    env->SetObjectField(frame, gFields.outputFrameLinearBlockID, linearBlock.get());
                    break;
                }
                case C2BufferData::GRAPHIC: {
                    const C2Handle *c2Handle = c2Buffer->data().graphicBlocks().front().handle();
                    uint32_t width, height, format, stride, igbp_slot, generation;
                    uint64_t usage, igbp_id;
                    _UnwrapNativeCodec2GrallocMetadata(
                            c2Handle, &width, &height, &format, &usage, &stride, &generation,
                            &igbp_id, &igbp_slot);
                    native_handle_t *grallocHandle = UnwrapNativeCodec2GrallocHandle(c2Handle);
                    GraphicBuffer* graphicBuffer = new GraphicBuffer(
                            grallocHandle, GraphicBuffer::CLONE_HANDLE,
                            width, height, format, 1, usage, stride);
                    ScopedLocalRef<jobject> hardwareBuffer{
                        env,
                        android_hardware_HardwareBuffer_createFromAHardwareBuffer(
                                env, AHardwareBuffer_from_GraphicBuffer(graphicBuffer))};
                    env->SetObjectField(
                            frame, gFields.outputFrameHardwareBufferID, hardwareBuffer.get());
                    break;
                }
                case C2BufferData::LINEAR_CHUNKS:  [[fallthrough]];
                case C2BufferData::GRAPHIC_CHUNKS: [[fallthrough]];
                case C2BufferData::INVALID:        [[fallthrough]];
                default:
                    return INVALID_OPERATION;
            }
        } else {
            if (!mGraphicOutput) {
                std::unique_ptr<JMediaCodecLinearBlock> context{new JMediaCodecLinearBlock};
                context->mLegacyBuffer = buffer;
                ScopedLocalRef<jobject> linearBlock{env, env->NewObject(
                        gLinearBlockInfo.clazz, gLinearBlockInfo.ctorId)};
                env->CallVoidMethod(
                        linearBlock.get(),
                        gLinearBlockInfo.setInternalStateId,
                        (jlong)context.release(),
                        true);
                env->SetObjectField(frame, gFields.outputFrameLinearBlockID, linearBlock.get());
            } else {
                // No-op.
            }
        }
    }

    jobject formatMap;
    err = getOutputFormat(env, index, &formatMap);
    if (err != OK) {
        return err;
    }
    ScopedLocalRef<jclass> mediaFormatClass{env, env->FindClass("android/media/MediaFormat")};
    ScopedLocalRef<jobject> format{env, env->NewObject(
            mediaFormatClass.get(),
            env->GetMethodID(mediaFormatClass.get(), "<init>", "(Ljava/util/Map;)V"),
            formatMap)};
    env->SetObjectField(frame, gFields.outputFrameFormatID, format.get());
    env->DeleteLocalRef(formatMap);
    formatMap = nullptr;

    sp<RefBase> obj;
    if (buffer->meta()->findObject("changedKeys", &obj) && obj) {
        sp<MediaCodec::WrapperObject<std::set<std::string>>> changedKeys{
            (decltype(changedKeys.get()))obj.get()};
        ScopedLocalRef<jobject> changedKeysObj{env, env->GetObjectField(
                frame, gFields.outputFrameChangedKeysID)};
        for (const std::string &key : changedKeys->value) {
            ScopedLocalRef<jstring> keyStr{env, env->NewStringUTF(key.c_str())};
            (void)env->CallBooleanMethod(changedKeysObj.get(), gArrayListInfo.addId, keyStr.get());
        }
    }
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
            profileLevelArray.get(), colorFormatsArray.get(), isEncoder,
            defaultFormatRef.get(), detailsRef.get());
}

status_t JMediaCodec::getCodecInfo(JNIEnv *env, jobject *codecInfoObject) const {
    sp<MediaCodecInfo> codecInfo;

    status_t err = mCodec->getCodecInfo(&codecInfo);

    if (err != OK) {
        return err;
    }

    ScopedLocalRef<jstring> nameObject(env,
            env->NewStringUTF(mNameAtCreation.c_str()));

    ScopedLocalRef<jstring> canonicalNameObject(env,
            env->NewStringUTF(codecInfo->getCodecName()));

    MediaCodecInfo::Attributes attributes = codecInfo->getAttributes();
    bool isEncoder = codecInfo->isEncoder();

    Vector<AString> mediaTypes;
    codecInfo->getSupportedMediaTypes(&mediaTypes);

    ScopedLocalRef<jobjectArray> capsArrayObj(env,
        env->NewObjectArray(mediaTypes.size(), gCodecInfo.capsClazz, NULL));

    for (size_t i = 0; i < mediaTypes.size(); i++) {
        const sp<MediaCodecInfo::Capabilities> caps =
                codecInfo->getCapabilitiesFor(mediaTypes[i].c_str());

        ScopedLocalRef<jobject> capsObj(env, getCodecCapabilitiesObject(
                env, mediaTypes[i].c_str(), isEncoder, caps));

        env->SetObjectArrayElement(capsArrayObj.get(), i, capsObj.get());
    }

    ScopedLocalRef<jclass> codecInfoClazz(env,
            env->FindClass("android/media/MediaCodecInfo"));
    CHECK(codecInfoClazz.get() != NULL);

    jmethodID codecInfoCtorID = env->GetMethodID(codecInfoClazz.get(), "<init>",
            "(Ljava/lang/String;Ljava/lang/String;I[Landroid/media/MediaCodecInfo$CodecCapabilities;)V");

    *codecInfoObject = env->NewObject(codecInfoClazz.get(), codecInfoCtorID,
            nameObject.get(), canonicalNameObject.get(), attributes, capsArrayObj.get());

    return OK;
}

status_t JMediaCodec::getMetrics(JNIEnv *, mediametrics::Item * &reply) const {
    mediametrics_handle_t reply2 = mediametrics::Item::convert(reply);
    status_t status = mCodec->getMetrics(reply2);
    // getMetrics() updates reply2, pass the converted update along to our caller.
    reply = mediametrics::Item::convert(reply2);
    return status;
}

status_t JMediaCodec::setParameters(const sp<AMessage> &msg) {
    return mCodec->setParameters(msg);
}

void JMediaCodec::setVideoScalingMode(int mode) {
    if (mSurfaceTextureClient != NULL) {
        // this works for components that queue to surface
        native_window_set_scaling_mode(mSurfaceTextureClient.get(), mode);
        // also signal via param for components that queue to IGBP
        sp<AMessage> msg = new AMessage;
        msg->setInt32("android._video-scaling", mode);
        (void)mCodec->setParameters(msg);
    }
}

void JMediaCodec::selectAudioPresentation(const int32_t presentationId, const int32_t programId) {
    sp<AMessage> msg = new AMessage;
    msg->setInt32("audio-presentation-presentation-id", presentationId);
    msg->setInt32("audio-presentation-program-id", programId);
    (void)mCodec->setParameters(msg);
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

void JMediaCodec::handleFirstTunnelFrameReadyNotification(const sp<AMessage> &msg) {
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
            EVENT_FIRST_TUNNEL_FRAME_READY, arg1, arg2, obj);

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
        case kWhatAsyncReleaseComplete:
        {
            if (mLooper != NULL) {
                mLooper->unregisterHandler(id());
                mLooper->stop();
                mLooper.clear();
            }
            break;
        }
        case kWhatFirstTunnelFrameReady:
        {
            handleFirstTunnelFrameReadyNotification(msg);
            break;
        }
        default:
            TRESPASS();
    }
}

jint MediaErrorToJavaError(status_t err);

}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static sp<JMediaCodec> setMediaCodec(
        JNIEnv *env, jobject thiz, const sp<JMediaCodec> &codec, bool release = true) {
    sp<JMediaCodec> old = (JMediaCodec *)env->CallLongMethod(thiz, gFields.lockAndGetContextID);
    if (codec != NULL) {
        codec->incStrong(thiz);
    }
    if (old != NULL) {
        /* release MediaCodec and stop the looper now before decStrong.
         * otherwise JMediaCodec::~JMediaCodec() could be called from within
         * its message handler, doing release() from there will deadlock
         * (as MediaCodec::release() post synchronous message to the same looper)
         */
        if (release) {
            old->release();
        }
        old->decStrong(thiz);
    }
    env->CallVoidMethod(thiz, gFields.setAndUnlockContextID, (jlong)codec.get());

    return old;
}

static sp<JMediaCodec> getMediaCodec(JNIEnv *env, jobject thiz) {
    sp<JMediaCodec> codec = (JMediaCodec *)env->CallLongMethod(thiz, gFields.lockAndGetContextID);
    env->CallVoidMethod(thiz, gFields.setAndUnlockContextID, (jlong)codec.get());
    return codec;
}

static void android_media_MediaCodec_release(JNIEnv *env, jobject thiz) {
    // Clear Java native reference.
    sp<JMediaCodec> codec = setMediaCodec(env, thiz, nullptr, false /* release */);
    if (codec != NULL) {
        codec->releaseAsync();
    }
}

static void throwCodecException(JNIEnv *env, status_t err, int32_t actionCode, const char *msg) {
    jthrowable exception = createCodecException(env, err, actionCode, msg);
    env->Throw(exception);
}

static void throwCryptoException(JNIEnv *env, status_t err, const char *msg,
        const sp<ICrypto> &crypto) {
    ScopedLocalRef<jclass> clazz(
            env, env->FindClass("android/media/MediaCodec$CryptoException"));
    CHECK(clazz.get() != NULL);

    jmethodID constructID =
        env->GetMethodID(clazz.get(), "<init>", "(ILjava/lang/String;)V");
    CHECK(constructID != NULL);

    std::string defaultMsg = "Unknown Error";

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
        case ERROR_DRM_INSUFFICIENT_SECURITY:
            err = gCryptoErrorCodes.cryptoErrorInsufficientSecurity;
            defaultMsg = "Required security level is not met";
            break;
        case ERROR_DRM_CANNOT_HANDLE:
            err = gCryptoErrorCodes.cryptoErrorUnsupportedOperation;
            defaultMsg = "Operation not supported in this configuration";
            break;
        case ERROR_DRM_FRAME_TOO_LARGE:
            err = gCryptoErrorCodes.cryptoErrorFrameTooLarge;
            defaultMsg = "Decrytped frame exceeds size of output buffer";
            break;
        case ERROR_DRM_SESSION_LOST_STATE:
            err = gCryptoErrorCodes.cryptoErrorLostState;
            defaultMsg = "Session state was lost, open a new session and retry";
            break;
        default:  /* Other negative DRM error codes go out best-effort. */
            err = MediaErrorToJavaError(err);
            defaultMsg = StrCryptoError(err);
            break;
    }

    std::string msgStr(msg != NULL ? msg : defaultMsg.c_str());
    if (crypto != NULL) {
        msgStr = DrmUtils::GetExceptionMessage(err, msgStr.c_str(), crypto);
    }
    jstring msgObj = env->NewStringUTF(msgStr.c_str());

    jthrowable exception =
        (jthrowable)env->NewObject(clazz.get(), constructID, err, msgObj);

    env->Throw(exception);
}

static jint throwExceptionAsNecessary(
        JNIEnv *env, status_t err, int32_t actionCode = ACTION_CODE_FATAL,
        const char *msg = NULL, const sp<ICrypto>& crypto = NULL) {
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
                throwCryptoException(env, err, msg, crypto);
                return 0;
            }
            throwCodecException(env, err, actionCode, msg);
            return 0;
    }
}

static void android_media_MediaCodec_native_enableOnFirstTunnelFrameReadyListener(
        JNIEnv *env,
        jobject thiz,
        jboolean enabled) {
    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL || codec->initCheck() != OK) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->enableOnFirstTunnelFrameReadyListener(enabled);

    throwExceptionAsNecessary(env, err);
}

static void android_media_MediaCodec_native_enableOnFrameRenderedListener(
        JNIEnv *env,
        jobject thiz,
        jboolean enabled) {
    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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
    if (codec == NULL || codec->initCheck() != OK) {
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
    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->start();

    throwExceptionAsNecessary(env, err, ACTION_CODE_FATAL, "start failed");
}

static void android_media_MediaCodec_stop(JNIEnv *env, jobject thiz) {
    ALOGV("android_media_MediaCodec_stop");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL || codec->initCheck() != OK) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->stop();

    throwExceptionAsNecessary(env, err);
}

static void android_media_MediaCodec_reset(JNIEnv *env, jobject thiz) {
    ALOGV("android_media_MediaCodec_reset");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    AString errorDetailMsg;

    status_t err = codec->queueInputBuffer(
            index, offset, size, timestampUs, flags, &errorDetailMsg);

    throwExceptionAsNecessary(
            env, err, ACTION_CODE_FATAL, errorDetailMsg.empty() ? NULL : errorDetailMsg.c_str());
}

struct NativeCryptoInfo {
    NativeCryptoInfo(JNIEnv *env, jobject cryptoInfoObj)
        : mEnv{env},
          mIvObj{env, (jbyteArray)env->GetObjectField(cryptoInfoObj, gFields.cryptoInfoIVID)},
          mKeyObj{env, (jbyteArray)env->GetObjectField(cryptoInfoObj, gFields.cryptoInfoKeyID)} {
        mNumSubSamples = env->GetIntField(cryptoInfoObj, gFields.cryptoInfoNumSubSamplesID);

        ScopedLocalRef<jintArray> numBytesOfClearDataObj{env, (jintArray)env->GetObjectField(
                cryptoInfoObj, gFields.cryptoInfoNumBytesOfClearDataID)};

        ScopedLocalRef<jintArray> numBytesOfEncryptedDataObj{env, (jintArray)env->GetObjectField(
                cryptoInfoObj, gFields.cryptoInfoNumBytesOfEncryptedDataID)};

        jint jmode = env->GetIntField(cryptoInfoObj, gFields.cryptoInfoModeID);
        if (jmode == gCryptoModes.Unencrypted) {
            mMode = CryptoPlugin::kMode_Unencrypted;
        } else if (jmode == gCryptoModes.AesCtr) {
            mMode = CryptoPlugin::kMode_AES_CTR;
        } else if (jmode == gCryptoModes.AesCbc) {
            mMode = CryptoPlugin::kMode_AES_CBC;
        }  else {
            throwExceptionAsNecessary(env, INVALID_OPERATION);
            return;
        }

        ScopedLocalRef<jobject> patternObj{
            env, env->GetObjectField(cryptoInfoObj, gFields.cryptoInfoPatternID)};

        if (patternObj.get() == nullptr) {
            mPattern.mEncryptBlocks = 0;
            mPattern.mSkipBlocks = 0;
        } else {
            mPattern.mEncryptBlocks = env->GetIntField(
                    patternObj.get(), gFields.patternEncryptBlocksID);
            mPattern.mSkipBlocks = env->GetIntField(
                    patternObj.get(), gFields.patternSkipBlocksID);
        }

        mErr = OK;
        if (mNumSubSamples <= 0) {
            mErr = -EINVAL;
        } else if (numBytesOfClearDataObj == nullptr
                && numBytesOfEncryptedDataObj == nullptr) {
            mErr = -EINVAL;
        } else if (numBytesOfEncryptedDataObj != nullptr
                && env->GetArrayLength(numBytesOfEncryptedDataObj.get()) < mNumSubSamples) {
            mErr = -ERANGE;
        } else if (numBytesOfClearDataObj != nullptr
                && env->GetArrayLength(numBytesOfClearDataObj.get()) < mNumSubSamples) {
            mErr = -ERANGE;
        // subSamples array may silently overflow if number of samples are too large.  Use
        // INT32_MAX as maximum allocation size may be less than SIZE_MAX on some platforms
        } else if (CC_UNLIKELY(mNumSubSamples >= (signed)(INT32_MAX / sizeof(*mSubSamples))) ) {
            mErr = -EINVAL;
        } else {
            jint *numBytesOfClearData =
                (numBytesOfClearDataObj == nullptr)
                    ? nullptr
                    : env->GetIntArrayElements(numBytesOfClearDataObj.get(), nullptr);

            jint *numBytesOfEncryptedData =
                (numBytesOfEncryptedDataObj == nullptr)
                    ? nullptr
                    : env->GetIntArrayElements(numBytesOfEncryptedDataObj.get(), nullptr);

            mSubSamples = new CryptoPlugin::SubSample[mNumSubSamples];

            for (jint i = 0; i < mNumSubSamples; ++i) {
                mSubSamples[i].mNumBytesOfClearData =
                    (numBytesOfClearData == nullptr) ? 0 : numBytesOfClearData[i];

                mSubSamples[i].mNumBytesOfEncryptedData =
                    (numBytesOfEncryptedData == nullptr) ? 0 : numBytesOfEncryptedData[i];
            }

            if (numBytesOfEncryptedData != nullptr) {
                env->ReleaseIntArrayElements(
                        numBytesOfEncryptedDataObj.get(), numBytesOfEncryptedData, 0);
                numBytesOfEncryptedData = nullptr;
            }

            if (numBytesOfClearData != nullptr) {
                env->ReleaseIntArrayElements(
                        numBytesOfClearDataObj.get(), numBytesOfClearData, 0);
                numBytesOfClearData = nullptr;
            }
        }

        if (mErr == OK && mKeyObj.get() != nullptr) {
            if (env->GetArrayLength(mKeyObj.get()) != 16) {
                mErr = -EINVAL;
            } else {
                mKey = env->GetByteArrayElements(mKeyObj.get(), nullptr);
            }
        }

        if (mErr == OK && mIvObj.get() != nullptr) {
            if (env->GetArrayLength(mIvObj.get()) != 16) {
                mErr = -EINVAL;
            } else {
                mIv = env->GetByteArrayElements(mIvObj.get(), nullptr);
            }
        }

    }

    explicit NativeCryptoInfo(jint size)
        : mIvObj{nullptr, nullptr},
          mKeyObj{nullptr, nullptr},
          mMode{CryptoPlugin::kMode_Unencrypted},
          mPattern{0, 0} {
        mSubSamples = new CryptoPlugin::SubSample[1];
        mNumSubSamples = 1;
        mSubSamples[0].mNumBytesOfClearData = size;
        mSubSamples[0].mNumBytesOfEncryptedData = 0;
    }

    ~NativeCryptoInfo() {
        if (mIv != nullptr) {
            mEnv->ReleaseByteArrayElements(mIvObj.get(), mIv, 0);
        }

        if (mKey != nullptr) {
            mEnv->ReleaseByteArrayElements(mKeyObj.get(), mKey, 0);
        }

        if (mSubSamples != nullptr) {
            delete[] mSubSamples;
        }
    }

    JNIEnv *mEnv{nullptr};
    ScopedLocalRef<jbyteArray> mIvObj;
    ScopedLocalRef<jbyteArray> mKeyObj;
    status_t mErr{OK};

    CryptoPlugin::SubSample *mSubSamples{nullptr};
    int32_t mNumSubSamples{0};
    jbyte *mIv{nullptr};
    jbyte *mKey{nullptr};
    enum CryptoPlugin::Mode mMode;
    CryptoPlugin::Pattern mPattern;
};

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

    if (codec == NULL || codec->initCheck() != OK) {
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
            env, err, ACTION_CODE_FATAL, errorDetailMsg.empty() ? NULL : errorDetailMsg.c_str(),
            codec->getCrypto());
}

static jobject android_media_MediaCodec_mapHardwareBuffer(JNIEnv *env, jclass, jobject bufferObj) {
    ALOGV("android_media_MediaCodec_mapHardwareBuffer");
    AHardwareBuffer *hardwareBuffer = android_hardware_HardwareBuffer_getNativeHardwareBuffer(
            env, bufferObj);
    AHardwareBuffer_Desc desc;
    AHardwareBuffer_describe(hardwareBuffer, &desc);
    if (desc.format != AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420) {
        ALOGI("mapHardwareBuffer: unmappable format: %d", desc.format);
        return nullptr;
    }
    if ((desc.usage & AHARDWAREBUFFER_USAGE_CPU_READ_MASK) == 0) {
        ALOGI("mapHardwareBuffer: buffer not CPU readable");
        return nullptr;
    }
    bool readOnly = ((desc.usage & AHARDWAREBUFFER_USAGE_CPU_WRITE_MASK) == 0);

    uint64_t cpuUsage = 0;
    cpuUsage |= (desc.usage & AHARDWAREBUFFER_USAGE_CPU_READ_MASK);
    cpuUsage |= (desc.usage & AHARDWAREBUFFER_USAGE_CPU_WRITE_MASK);

    AHardwareBuffer_Planes planes;
    int err = AHardwareBuffer_lockPlanes(
            hardwareBuffer, cpuUsage, -1 /* fence */, nullptr /* rect */, &planes);
    if (err != 0) {
        ALOGI("mapHardwareBuffer: Failed to lock planes (err=%d)", err);
        return nullptr;
    }

    if (planes.planeCount != 3) {
        ALOGI("mapHardwareBuffer: planeCount expected 3, actual %u", planes.planeCount);
        return nullptr;
    }

    ScopedLocalRef<jobjectArray> buffersArray{
            env, env->NewObjectArray(3, gByteBufferInfo.clazz, NULL)};
    ScopedLocalRef<jintArray> rowStridesArray{env, env->NewIntArray(3)};
    ScopedLocalRef<jintArray> pixelStridesArray{env, env->NewIntArray(3)};

    jboolean isCopy = JNI_FALSE;
    jint *rowStrides = env->GetIntArrayElements(rowStridesArray.get(), &isCopy);
    jint *pixelStrides = env->GetIntArrayElements(rowStridesArray.get(), &isCopy);

    // For Y plane
    int rowSampling = 1;
    int colSampling = 1;
    // plane indices are Y-U-V.
    for (uint32_t i = 0; i < 3; ++i) {
        const AHardwareBuffer_Plane &plane = planes.planes[i];
        int maxRowOffset = plane.rowStride * (desc.height / rowSampling - 1);
        int maxColOffset = plane.pixelStride * (desc.width / colSampling - 1);
        int maxOffset = maxRowOffset + maxColOffset;
        ScopedLocalRef<jobject> byteBuffer{env, CreateByteBuffer(
                env,
                plane.data,
                maxOffset + 1,
                0,
                maxOffset + 1,
                readOnly,
                true)};

        env->SetObjectArrayElement(buffersArray.get(), i, byteBuffer.get());
        rowStrides[i] = plane.rowStride;
        pixelStrides[i] = plane.pixelStride;
        // For U-V planes
        rowSampling = 2;
        colSampling = 2;
    }

    env->ReleaseIntArrayElements(rowStridesArray.get(), rowStrides, 0);
    env->ReleaseIntArrayElements(pixelStridesArray.get(), pixelStrides, 0);
    rowStrides = pixelStrides = nullptr;

    ScopedLocalRef<jclass> imageClazz(
            env, env->FindClass("android/media/MediaCodec$MediaImage"));
    CHECK(imageClazz.get() != NULL);

    jmethodID imageConstructID = env->GetMethodID(imageClazz.get(), "<init>",
            "([Ljava/nio/ByteBuffer;[I[IIIIZJIILandroid/graphics/Rect;J)V");

    jobject img = env->NewObject(imageClazz.get(), imageConstructID,
            buffersArray.get(),
            rowStridesArray.get(),
            pixelStridesArray.get(),
            desc.width,
            desc.height,
            desc.format, // ???
            (jboolean)readOnly /* readOnly */,
            (jlong)0 /* timestamp */,
            (jint)0 /* xOffset */, (jint)0 /* yOffset */, nullptr /* cropRect */,
            (jlong)hardwareBuffer);

    // if MediaImage creation fails, return null
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return nullptr;
    }

    AHardwareBuffer_acquire(hardwareBuffer);

    return img;
}

static void android_media_MediaCodec_closeMediaImage(JNIEnv *, jclass, jlong context) {
    ALOGV("android_media_MediaCodec_closeMediaImage");
    if (context == 0) {
        return;
    }
    AHardwareBuffer *hardwareBuffer = (AHardwareBuffer *)context;

    int err = AHardwareBuffer_unlock(hardwareBuffer, nullptr);
    if (err != 0) {
        ALOGI("closeMediaImage: failed to unlock (err=%d)", err);
        // Continue to release the hardwareBuffer
    }

    AHardwareBuffer_release(hardwareBuffer);
}

static status_t ConvertKeyValueListsToAMessage(
        JNIEnv *env, jobject keys, jobject values, sp<AMessage> *msg) {
    static struct Fields {
        explicit Fields(JNIEnv *env) {
            ScopedLocalRef<jclass> clazz{env, env->FindClass("java/lang/String")};
            CHECK(clazz.get() != NULL);
            mStringClass = (jclass)env->NewGlobalRef(clazz.get());

            clazz.reset(env->FindClass("java/lang/Integer"));
            CHECK(clazz.get() != NULL);
            mIntegerClass = (jclass)env->NewGlobalRef(clazz.get());

            mIntegerValueId = env->GetMethodID(clazz.get(), "intValue", "()I");
            CHECK(mIntegerValueId != NULL);

            clazz.reset(env->FindClass("java/lang/Long"));
            CHECK(clazz.get() != NULL);
            mLongClass = (jclass)env->NewGlobalRef(clazz.get());

            mLongValueId = env->GetMethodID(clazz.get(), "longValue", "()J");
            CHECK(mLongValueId != NULL);

            clazz.reset(env->FindClass("java/lang/Float"));
            CHECK(clazz.get() != NULL);
            mFloatClass = (jclass)env->NewGlobalRef(clazz.get());

            mFloatValueId = env->GetMethodID(clazz.get(), "floatValue", "()F");
            CHECK(mFloatValueId != NULL);

            clazz.reset(env->FindClass("java/util/ArrayList"));
            CHECK(clazz.get() != NULL);

            mByteBufferArrayId = env->GetMethodID(gByteBufferInfo.clazz, "array", "()[B");
            CHECK(mByteBufferArrayId != NULL);
        }

        jclass mStringClass;
        jclass mIntegerClass;
        jmethodID mIntegerValueId;
        jclass mLongClass;
        jmethodID mLongValueId;
        jclass mFloatClass;
        jmethodID mFloatValueId;
        jmethodID mByteBufferArrayId;
    } sFields{env};

    jint size = env->CallIntMethod(keys, gArrayListInfo.sizeId);
    if (size != env->CallIntMethod(values, gArrayListInfo.sizeId)) {
        return BAD_VALUE;
    }

    sp<AMessage> result{new AMessage};
    for (jint i = 0; i < size; ++i) {
        ScopedLocalRef<jstring> jkey{
            env, (jstring)env->CallObjectMethod(keys, gArrayListInfo.getId, i)};
        const char *tmp = env->GetStringUTFChars(jkey.get(), nullptr);
        AString key;
        if (tmp) {
            key.setTo(tmp);
        }
        env->ReleaseStringUTFChars(jkey.get(), tmp);
        if (key.empty()) {
            return NO_MEMORY;
        }

        ScopedLocalRef<jobject> jvalue{
            env, env->CallObjectMethod(values, gArrayListInfo.getId, i)};

        if (env->IsInstanceOf(jvalue.get(), sFields.mStringClass)) {
            const char *tmp = env->GetStringUTFChars((jstring)jvalue.get(), nullptr);
            AString value;
            if (!tmp) {
                return NO_MEMORY;
            }
            value.setTo(tmp);
            env->ReleaseStringUTFChars((jstring)jvalue.get(), tmp);
            result->setString(key.c_str(), value);
        } else if (env->IsInstanceOf(jvalue.get(), sFields.mIntegerClass)) {
            jint value = env->CallIntMethod(jvalue.get(), sFields.mIntegerValueId);
            result->setInt32(key.c_str(), value);
        } else if (env->IsInstanceOf(jvalue.get(), sFields.mLongClass)) {
            jlong value = env->CallLongMethod(jvalue.get(), sFields.mLongValueId);
            result->setInt64(key.c_str(), value);
        } else if (env->IsInstanceOf(jvalue.get(), sFields.mFloatClass)) {
            jfloat value = env->CallFloatMethod(jvalue.get(), sFields.mFloatValueId);
            result->setFloat(key.c_str(), value);
        } else if (env->IsInstanceOf(jvalue.get(), gByteBufferInfo.clazz)) {
            jint position = env->CallIntMethod(jvalue.get(), gByteBufferInfo.getPositionId);
            jint limit = env->CallIntMethod(jvalue.get(), gByteBufferInfo.getLimitId);
            sp<ABuffer> buffer{new ABuffer(limit - position)};
            void *data = env->GetDirectBufferAddress(jvalue.get());
            if (data != nullptr) {
                memcpy(buffer->data(),
                       static_cast<const uint8_t *>(data) + position,
                       buffer->size());
            } else {
                ScopedLocalRef<jbyteArray> byteArray{env, (jbyteArray)env->CallObjectMethod(
                        jvalue.get(), sFields.mByteBufferArrayId)};
                env->GetByteArrayRegion(byteArray.get(), position, buffer->size(),
                                        reinterpret_cast<jbyte *>(buffer->data()));
            }
            result->setBuffer(key.c_str(), buffer);
        }
    }

    *msg = result;
    return OK;
}

static void android_media_MediaCodec_native_queueLinearBlock(
        JNIEnv *env, jobject thiz, jint index, jobject bufferObj,
        jint offset, jint size, jobject cryptoInfoObj,
        jlong presentationTimeUs, jint flags, jobject keys, jobject values) {
    ALOGV("android_media_MediaCodec_native_queueLinearBlock");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == nullptr || codec->initCheck() != OK) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    sp<AMessage> tunings;
    status_t err = ConvertKeyValueListsToAMessage(env, keys, values, &tunings);
    if (err != OK) {
        throwExceptionAsNecessary(env, err);
        return;
    }

    std::shared_ptr<C2Buffer> buffer;
    sp<hardware::HidlMemory> memory;
    ScopedLocalRef<jobject> lock{env, env->GetObjectField(bufferObj, gLinearBlockInfo.lockId)};
    if (env->MonitorEnter(lock.get()) == JNI_OK) {
        if (env->GetBooleanField(bufferObj, gLinearBlockInfo.validId)) {
            JMediaCodecLinearBlock *context =
                (JMediaCodecLinearBlock *)env->GetLongField(bufferObj, gLinearBlockInfo.contextId);
            if (codec->hasCryptoOrDescrambler()) {
                memory = context->toHidlMemory();
                // TODO: copy if memory is null
                offset += context->mHidlMemoryOffset;
            } else {
                buffer = context->toC2Buffer(offset, size);
                // TODO: copy if buffer is null
            }
        }
        env->MonitorExit(lock.get());
    } else {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    AString errorDetailMsg;
    if (codec->hasCryptoOrDescrambler()) {
        if (!memory) {
            ALOGI("queueLinearBlock: no ashmem memory for encrypted content");
            throwExceptionAsNecessary(env, BAD_VALUE);
            return;
        }
        NativeCryptoInfo cryptoInfo = [env, cryptoInfoObj, size]{
            if (cryptoInfoObj == nullptr) {
                return NativeCryptoInfo{size};
            } else {
                return NativeCryptoInfo{env, cryptoInfoObj};
            }
        }();
        err = codec->queueEncryptedLinearBlock(
                index,
                memory,
                offset,
                cryptoInfo.mSubSamples, cryptoInfo.mNumSubSamples,
                (const uint8_t *)cryptoInfo.mKey, (const uint8_t *)cryptoInfo.mIv,
                cryptoInfo.mMode,
                cryptoInfo.mPattern,
                presentationTimeUs,
                flags,
                tunings,
                &errorDetailMsg);
    } else {
        if (!buffer) {
            ALOGI("queueLinearBlock: no C2Buffer found");
            throwExceptionAsNecessary(env, BAD_VALUE);
            return;
        }
        err = codec->queueBuffer(
                index, buffer, presentationTimeUs, flags, tunings, &errorDetailMsg);
    }
    throwExceptionAsNecessary(env, err, ACTION_CODE_FATAL, errorDetailMsg.c_str());
}

static void android_media_MediaCodec_native_queueHardwareBuffer(
        JNIEnv *env, jobject thiz, jint index, jobject bufferObj,
        jlong presentationTimeUs, jint flags, jobject keys, jobject values) {
    ALOGV("android_media_MediaCodec_native_queueHardwareBuffer");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL || codec->initCheck() != OK) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    sp<AMessage> tunings;
    status_t err = ConvertKeyValueListsToAMessage(env, keys, values, &tunings);
    if (err != OK) {
        throwExceptionAsNecessary(env, err);
        return;
    }

    AHardwareBuffer *hardwareBuffer = android_hardware_HardwareBuffer_getNativeHardwareBuffer(
            env, bufferObj);
    sp<GraphicBuffer> graphicBuffer{AHardwareBuffer_to_GraphicBuffer(hardwareBuffer)};
    C2Handle *handle = WrapNativeCodec2GrallocHandle(
            graphicBuffer->handle, graphicBuffer->width, graphicBuffer->height,
            graphicBuffer->format, graphicBuffer->usage, graphicBuffer->stride);
    static std::shared_ptr<C2Allocator> sGrallocAlloc = []() -> std::shared_ptr<C2Allocator> {
        std::shared_ptr<C2Allocator> alloc;
        c2_status_t err = GetCodec2PlatformAllocatorStore()->fetchAllocator(
                C2PlatformAllocatorStore::GRALLOC, &alloc);
        if (err == C2_OK) {
            return alloc;
        }
        return nullptr;
    }();
    std::shared_ptr<C2GraphicAllocation> alloc;
    c2_status_t c2err = sGrallocAlloc->priorGraphicAllocation(handle, &alloc);
    if (c2err != C2_OK) {
        ALOGW("Failed to wrap AHardwareBuffer into C2GraphicAllocation");
        native_handle_close(handle);
        native_handle_delete(handle);
        throwExceptionAsNecessary(env, BAD_VALUE);
        return;
    }
    std::shared_ptr<C2GraphicBlock> block = _C2BlockFactory::CreateGraphicBlock(alloc);
    std::shared_ptr<C2Buffer> buffer = C2Buffer::CreateGraphicBuffer(block->share(
            block->crop(), C2Fence{}));
    AString errorDetailMsg;
    err = codec->queueBuffer(
            index, buffer, presentationTimeUs, flags, tunings, &errorDetailMsg);
    throwExceptionAsNecessary(env, err, ACTION_CODE_FATAL, errorDetailMsg.c_str());
}

static void android_media_MediaCodec_native_getOutputFrame(
        JNIEnv *env, jobject thiz, jobject frame, jint index) {
    ALOGV("android_media_MediaCodec_native_getOutputFrame");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL || codec->initCheck() != OK) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    status_t err = codec->getOutputFrame(env, frame, index);
    if (err != OK) {
        throwExceptionAsNecessary(env, err);
    }
}

static jint android_media_MediaCodec_dequeueInputBuffer(
        JNIEnv *env, jobject thiz, jlong timeoutUs) {
    ALOGV("android_media_MediaCodec_dequeueInputBuffer");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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
    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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
    if (codec == NULL || codec->initCheck() != OK) {
        jniThrowException(env, "java/lang/IllegalStateException", NULL);
        return 0;
    }

    // get what we have for the metrics from the codec
    mediametrics::Item *item = 0;

    status_t err = codec->getMetrics(env, item);
    if (err != OK) {
        ALOGE("getMetrics failed");
        return (jobject) NULL;
    }

    jobject mybundle = MediaMetricsJNI::writeMetricsToBundle(env, item, NULL);

    // housekeeping
    delete item;
    item = 0;

    return mybundle;
}

static void android_media_MediaCodec_setParameters(
        JNIEnv *env, jobject thiz, jobjectArray keys, jobjectArray vals) {
    ALOGV("android_media_MediaCodec_setParameters");

    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL || codec->initCheck() != OK) {
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

    if (codec == NULL || codec->initCheck() != OK) {
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

static void android_media_MediaCodec_setAudioPresentation(
        JNIEnv *env, jobject thiz, jint presentationId, jint programId) {
    sp<JMediaCodec> codec = getMediaCodec(env, thiz);

    if (codec == NULL || codec->initCheck() != OK) {
        throwExceptionAsNecessary(env, INVALID_OPERATION);
        return;
    }

    codec->selectAudioPresentation((int32_t)presentationId, (int32_t)programId);
}

static void android_media_MediaCodec_native_init(JNIEnv *env, jclass) {
    ScopedLocalRef<jclass> clazz(
            env, env->FindClass("android/media/MediaCodec"));
    CHECK(clazz.get() != NULL);

    gFields.postEventFromNativeID =
        env->GetMethodID(
                clazz.get(), "postEventFromNative", "(IIILjava/lang/Object;)V");
    CHECK(gFields.postEventFromNativeID != NULL);

    gFields.lockAndGetContextID =
        env->GetMethodID(
                clazz.get(), "lockAndGetContext", "()J");
    CHECK(gFields.lockAndGetContextID != NULL);

    gFields.setAndUnlockContextID =
        env->GetMethodID(
                clazz.get(), "setAndUnlockContext", "(J)V");
    CHECK(gFields.setAndUnlockContextID != NULL);

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

    gFields.cryptoInfoPatternID = env->GetFieldID(clazz.get(), "mPattern",
        "Landroid/media/MediaCodec$CryptoInfo$Pattern;");
    CHECK(gFields.cryptoInfoPatternID != NULL);

    clazz.reset(env->FindClass("android/media/MediaCodec$CryptoInfo$Pattern"));
    CHECK(clazz.get() != NULL);

    gFields.patternEncryptBlocksID = env->GetFieldID(clazz.get(), "mEncryptBlocks", "I");
    CHECK(gFields.patternEncryptBlocksID != NULL);

    gFields.patternSkipBlocksID = env->GetFieldID(clazz.get(), "mSkipBlocks", "I");
    CHECK(gFields.patternSkipBlocksID != NULL);

    clazz.reset(env->FindClass("android/media/MediaCodec$QueueRequest"));
    CHECK(clazz.get() != NULL);

    gFields.queueRequestIndexID = env->GetFieldID(clazz.get(), "mIndex", "I");
    CHECK(gFields.queueRequestIndexID != NULL);

    clazz.reset(env->FindClass("android/media/MediaCodec$OutputFrame"));
    CHECK(clazz.get() != NULL);

    gFields.outputFrameLinearBlockID =
        env->GetFieldID(clazz.get(), "mLinearBlock", "Landroid/media/MediaCodec$LinearBlock;");
    CHECK(gFields.outputFrameLinearBlockID != NULL);

    gFields.outputFrameHardwareBufferID =
        env->GetFieldID(clazz.get(), "mHardwareBuffer", "Landroid/hardware/HardwareBuffer;");
    CHECK(gFields.outputFrameHardwareBufferID != NULL);

    gFields.outputFrameChangedKeysID =
        env->GetFieldID(clazz.get(), "mChangedKeys", "Ljava/util/ArrayList;");
    CHECK(gFields.outputFrameChangedKeysID != NULL);

    gFields.outputFrameFormatID =
        env->GetFieldID(clazz.get(), "mFormat", "Landroid/media/MediaFormat;");
    CHECK(gFields.outputFrameFormatID != NULL);

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

    field = env->GetStaticFieldID(clazz.get(), "ERROR_INSUFFICIENT_SECURITY", "I");
    CHECK(field != NULL);
    gCryptoErrorCodes.cryptoErrorInsufficientSecurity =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "ERROR_UNSUPPORTED_OPERATION", "I");
    CHECK(field != NULL);
    gCryptoErrorCodes.cryptoErrorUnsupportedOperation =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "ERROR_FRAME_TOO_LARGE", "I");
    CHECK(field != NULL);
    gCryptoErrorCodes.cryptoErrorFrameTooLarge =
        env->GetStaticIntField(clazz.get(), field);

    field = env->GetStaticFieldID(clazz.get(), "ERROR_LOST_STATE", "I");
    CHECK(field != NULL);
    gCryptoErrorCodes.cryptoErrorLostState =
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
            "([Landroid/media/MediaCodecInfo$CodecProfileLevel;[IZ"
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

    clazz.reset(env->FindClass("java/nio/ByteBuffer"));
    CHECK(clazz.get() != NULL);
    gByteBufferInfo.clazz = (jclass)env->NewGlobalRef(clazz.get());

    ScopedLocalRef<jclass> byteOrderClass(
            env, env->FindClass("java/nio/ByteOrder"));
    CHECK(byteOrderClass.get() != NULL);

    jmethodID nativeOrderID = env->GetStaticMethodID(
            byteOrderClass.get(), "nativeOrder", "()Ljava/nio/ByteOrder;");
    CHECK(nativeOrderID != NULL);

    ScopedLocalRef<jobject> nativeByteOrderObj{
        env, env->CallStaticObjectMethod(byteOrderClass.get(), nativeOrderID)};
    gByteBufferInfo.nativeByteOrder = env->NewGlobalRef(nativeByteOrderObj.get());
    CHECK(gByteBufferInfo.nativeByteOrder != NULL);
    nativeByteOrderObj.reset();

    gByteBufferInfo.orderId = env->GetMethodID(
            clazz.get(),
            "order",
            "(Ljava/nio/ByteOrder;)Ljava/nio/ByteBuffer;");
    CHECK(gByteBufferInfo.orderId != NULL);

    gByteBufferInfo.asReadOnlyBufferId = env->GetMethodID(
            clazz.get(), "asReadOnlyBuffer", "()Ljava/nio/ByteBuffer;");
    CHECK(gByteBufferInfo.asReadOnlyBufferId != NULL);

    gByteBufferInfo.positionId = env->GetMethodID(
            clazz.get(), "position", "(I)Ljava/nio/Buffer;");
    CHECK(gByteBufferInfo.positionId != NULL);

    gByteBufferInfo.limitId = env->GetMethodID(
            clazz.get(), "limit", "(I)Ljava/nio/Buffer;");
    CHECK(gByteBufferInfo.limitId != NULL);

    gByteBufferInfo.getPositionId = env->GetMethodID(
            clazz.get(), "position", "()I");
    CHECK(gByteBufferInfo.getPositionId != NULL);

    gByteBufferInfo.getLimitId = env->GetMethodID(
            clazz.get(), "limit", "()I");
    CHECK(gByteBufferInfo.getLimitId != NULL);

    clazz.reset(env->FindClass("java/util/ArrayList"));
    CHECK(clazz.get() != NULL);

    gArrayListInfo.sizeId = env->GetMethodID(clazz.get(), "size", "()I");
    CHECK(gArrayListInfo.sizeId != NULL);

    gArrayListInfo.getId = env->GetMethodID(clazz.get(), "get", "(I)Ljava/lang/Object;");
    CHECK(gArrayListInfo.getId != NULL);

    gArrayListInfo.addId = env->GetMethodID(clazz.get(), "add", "(Ljava/lang/Object;)Z");
    CHECK(gArrayListInfo.addId != NULL);

    clazz.reset(env->FindClass("android/media/MediaCodec$LinearBlock"));
    CHECK(clazz.get() != NULL);

    gLinearBlockInfo.clazz = (jclass)env->NewGlobalRef(clazz.get());

    gLinearBlockInfo.ctorId = env->GetMethodID(clazz.get(), "<init>", "()V");
    CHECK(gLinearBlockInfo.ctorId != NULL);

    gLinearBlockInfo.setInternalStateId = env->GetMethodID(
            clazz.get(), "setInternalStateLocked", "(JZ)V");
    CHECK(gLinearBlockInfo.setInternalStateId != NULL);

    gLinearBlockInfo.contextId = env->GetFieldID(clazz.get(), "mNativeContext", "J");
    CHECK(gLinearBlockInfo.contextId != NULL);

    gLinearBlockInfo.validId = env->GetFieldID(clazz.get(), "mValid", "Z");
    CHECK(gLinearBlockInfo.validId != NULL);

    gLinearBlockInfo.lockId = env->GetFieldID(clazz.get(), "mLock", "Ljava/lang/Object;");
    CHECK(gLinearBlockInfo.lockId != NULL);
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
    setMediaCodec(env, thiz, NULL);
}

// MediaCodec.LinearBlock

static jobject android_media_MediaCodec_LinearBlock_native_map(
        JNIEnv *env, jobject thiz) {
    JMediaCodecLinearBlock *context =
        (JMediaCodecLinearBlock *)env->GetLongField(thiz, gLinearBlockInfo.contextId);
    if (context->mBuffer) {
        std::shared_ptr<C2Buffer> buffer = context->mBuffer;
        if (!context->mReadonlyMapping) {
            const C2BufferData data = buffer->data();
            if (data.type() != C2BufferData::LINEAR) {
                throwExceptionAsNecessary(env, INVALID_OPERATION);
                return nullptr;
            }
            if (data.linearBlocks().size() != 1u) {
                throwExceptionAsNecessary(env, INVALID_OPERATION);
                return nullptr;
            }
            C2ConstLinearBlock block = data.linearBlocks().front();
            context->mReadonlyMapping =
                std::make_shared<C2ReadView>(block.map().get());
        }
        return CreateByteBuffer(
                env,
                context->mReadonlyMapping->data(),  // base
                context->mReadonlyMapping->capacity(),  // capacity
                0u,  // offset
                context->mReadonlyMapping->capacity(),  // size
                true,  // readOnly
                true /* clearBuffer */);
    } else if (context->mBlock) {
        std::shared_ptr<C2LinearBlock> block = context->mBlock;
        if (!context->mReadWriteMapping) {
            context->mReadWriteMapping =
                std::make_shared<C2WriteView>(block->map().get());
        }
        return CreateByteBuffer(
                env,
                context->mReadWriteMapping->base(),
                context->mReadWriteMapping->capacity(),
                context->mReadWriteMapping->offset(),
                context->mReadWriteMapping->size(),
                false,  // readOnly
                true /* clearBuffer */);
    } else if (context->mLegacyBuffer) {
        return CreateByteBuffer(
                env,
                context->mLegacyBuffer->base(),
                context->mLegacyBuffer->capacity(),
                context->mLegacyBuffer->offset(),
                context->mLegacyBuffer->size(),
                true,  // readOnly
                true /* clearBuffer */);
    } else if (context->mMemory) {
        return CreateByteBuffer(
                env,
                context->mMemory->unsecurePointer(),
                context->mMemory->size(),
                0,
                context->mMemory->size(),
                false,  // readOnly
                true /* clearBuffer */);
    }
    throwExceptionAsNecessary(env, INVALID_OPERATION);
    return nullptr;
}

static void android_media_MediaCodec_LinearBlock_native_recycle(
        JNIEnv *env, jobject thiz) {
    JMediaCodecLinearBlock *context =
        (JMediaCodecLinearBlock *)env->GetLongField(thiz, gLinearBlockInfo.contextId);
    env->CallVoidMethod(thiz, gLinearBlockInfo.setInternalStateId, jlong(0), false);
    delete context;
}

static void PopulateNamesVector(
        JNIEnv *env, jobjectArray codecNames, std::vector<std::string> *names) {
    jsize length = env->GetArrayLength(codecNames);
    for (jsize i = 0; i < length; ++i) {
        jstring jstr = static_cast<jstring>(env->GetObjectArrayElement(codecNames, i));
        if (jstr == nullptr) {
            // null entries are ignored
            continue;
        }
        const char *cstr = env->GetStringUTFChars(jstr, nullptr);
        if (cstr == nullptr) {
            throwExceptionAsNecessary(env, BAD_VALUE);
            return;
        }
        names->emplace_back(cstr);
        env->ReleaseStringUTFChars(jstr, cstr);
    }
}

static void android_media_MediaCodec_LinearBlock_native_obtain(
        JNIEnv *env, jobject thiz, jint capacity, jobjectArray codecNames) {
    std::unique_ptr<JMediaCodecLinearBlock> context{new JMediaCodecLinearBlock};
    std::vector<std::string> names;
    PopulateNamesVector(env, codecNames, &names);
    bool hasSecure = false;
    bool hasNonSecure = false;
    for (const std::string &name : names) {
        if (name.length() >= 7 && name.substr(name.length() - 7) == ".secure") {
            hasSecure = true;
        } else {
            hasNonSecure = true;
        }
    }
    if (hasSecure && !hasNonSecure) {
        constexpr size_t kInitialDealerCapacity = 1048576;  // 1MB
        thread_local sp<MemoryDealer> sDealer = new MemoryDealer(
                kInitialDealerCapacity, "JNI(1MB)");
        context->mMemory = sDealer->allocate(capacity);
        if (context->mMemory == nullptr) {
            size_t newDealerCapacity = sDealer->getMemoryHeap()->getSize() * 2;
            while (capacity * 2 > newDealerCapacity) {
                newDealerCapacity *= 2;
            }
            ALOGI("LinearBlock.native_obtain: "
                  "Dealer capacity increasing from %zuMB to %zuMB",
                  sDealer->getMemoryHeap()->getSize() / 1048576,
                  newDealerCapacity / 1048576);
            sDealer = new MemoryDealer(
                    newDealerCapacity,
                    AStringPrintf("JNI(%zuMB)", newDealerCapacity).c_str());
            context->mMemory = sDealer->allocate(capacity);
        }
        context->mHidlMemory = hardware::fromHeap(context->mMemory->getMemory(
                    &context->mHidlMemoryOffset, &context->mHidlMemorySize));
    } else {
        context->mBlock = MediaCodec::FetchLinearBlock(capacity, names);
        if (!context->mBlock) {
            jniThrowException(env, "java/io/IOException", nullptr);
            return;
        }
    }
    env->CallVoidMethod(
            thiz,
            gLinearBlockInfo.setInternalStateId,
            (jlong)context.release(),
            true /* isMappable */);
}

static jboolean android_media_MediaCodec_LinearBlock_checkCompatible(
        JNIEnv *env, jclass, jobjectArray codecNames) {
    std::vector<std::string> names;
    PopulateNamesVector(env, codecNames, &names);
    bool isCompatible = false;
    bool hasSecure = false;
    bool hasNonSecure = false;
    for (const std::string &name : names) {
        if (name.length() >= 7 && name.substr(name.length() - 7) == ".secure") {
            hasSecure = true;
        } else {
            hasNonSecure = true;
        }
    }
    if (hasSecure && hasNonSecure) {
        return false;
    }
    status_t err = MediaCodec::CanFetchLinearBlock(names, &isCompatible);
    if (err != OK) {
        throwExceptionAsNecessary(env, err);
    }
    return isCompatible;
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

    { "native_enableOnFirstTunnelFrameReadyListener", "(Z)V",
      (void *)android_media_MediaCodec_native_enableOnFirstTunnelFrameReadyListener },

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

    { "native_mapHardwareBuffer",
      "(Landroid/hardware/HardwareBuffer;)Landroid/media/Image;",
      (void *)android_media_MediaCodec_mapHardwareBuffer },

    { "native_closeMediaImage", "(J)V", (void *)android_media_MediaCodec_closeMediaImage },

    { "native_queueLinearBlock",
      "(ILandroid/media/MediaCodec$LinearBlock;IILandroid/media/MediaCodec$CryptoInfo;JI"
      "Ljava/util/ArrayList;Ljava/util/ArrayList;)V",
      (void *)android_media_MediaCodec_native_queueLinearBlock },

    { "native_queueHardwareBuffer",
      "(ILandroid/hardware/HardwareBuffer;JILjava/util/ArrayList;Ljava/util/ArrayList;)V",
      (void *)android_media_MediaCodec_native_queueHardwareBuffer },

    { "native_getOutputFrame",
      "(Landroid/media/MediaCodec$OutputFrame;I)V",
      (void *)android_media_MediaCodec_native_getOutputFrame },

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

    { "getCanonicalName", "()Ljava/lang/String;",
      (void *)android_media_MediaCodec_getName },

    { "getOwnCodecInfo", "()Landroid/media/MediaCodecInfo;",
        (void *)android_media_MediaCodec_getOwnCodecInfo },

    { "native_getMetrics", "()Landroid/os/PersistableBundle;",
      (void *)android_media_MediaCodec_native_getMetrics},

    { "setParameters", "([Ljava/lang/String;[Ljava/lang/Object;)V",
      (void *)android_media_MediaCodec_setParameters },

    { "setVideoScalingMode", "(I)V",
      (void *)android_media_MediaCodec_setVideoScalingMode },

    { "native_setAudioPresentation", "(II)V",
      (void *)android_media_MediaCodec_setAudioPresentation },

    { "native_init", "()V", (void *)android_media_MediaCodec_native_init },

    { "native_setup", "(Ljava/lang/String;ZZ)V",
      (void *)android_media_MediaCodec_native_setup },

    { "native_finalize", "()V",
      (void *)android_media_MediaCodec_native_finalize },
};

static const JNINativeMethod gLinearBlockMethods[] = {
    { "native_map", "()Ljava/nio/ByteBuffer;",
      (void *)android_media_MediaCodec_LinearBlock_native_map },

    { "native_recycle", "()V",
      (void *)android_media_MediaCodec_LinearBlock_native_recycle },

    { "native_obtain", "(I[Ljava/lang/String;)V",
      (void *)android_media_MediaCodec_LinearBlock_native_obtain },

    { "native_checkCompatible", "([Ljava/lang/String;)Z",
      (void *)android_media_MediaCodec_LinearBlock_checkCompatible },
};

int register_android_media_MediaCodec(JNIEnv *env) {
    int result = AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaCodec", gMethods, NELEM(gMethods));
    if (result != JNI_OK) {
        return result;
    }
    result = AndroidRuntime::registerNativeMethods(env,
                "android/media/MediaCodec$LinearBlock",
                gLinearBlockMethods,
                NELEM(gLinearBlockMethods));
    return result;
}
