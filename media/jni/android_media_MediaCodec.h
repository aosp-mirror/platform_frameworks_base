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

#ifndef _ANDROID_MEDIA_MEDIACODEC_H_
#define _ANDROID_MEDIA_MEDIACODEC_H_

#include <mutex>

#include "jni.h"

#include <C2Buffer.h>
#include <binder/MemoryHeapBase.h>
#include <media/MediaCodecBuffer.h>
#include <media/MediaMetricsItem.h>
#include <media/hardware/CryptoAPI.h>
#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AHandler.h>
#include <utils/Errors.h>

class C2Buffer;

namespace android {

struct ABuffer;
struct AccessUnitInfo;
struct ALooper;
struct AMessage;
struct AString;
struct ICrypto;
class IGraphicBufferProducer;
struct MediaCodec;
struct PersistentSurface;
class Surface;
namespace hardware {
class HidlMemory;
namespace cas {
namespace native {
namespace V1_0 {
struct IDescrambler;
}}}}
using hardware::cas::native::V1_0::IDescrambler;

struct JMediaCodec : public AHandler {
    JMediaCodec(
            JNIEnv *env, jobject thiz,
            const char *name, bool nameIsType, bool encoder, int pid, int uid);

    status_t initCheck() const;

    void registerSelf();
    void release();
    void releaseAsync();

    status_t enableOnFirstTunnelFrameReadyListener(jboolean enable);

    status_t enableOnFrameRenderedListener(jboolean enable);

    status_t setCallback(jobject cb);

    status_t configure(
            const sp<AMessage> &format,
            const sp<IGraphicBufferProducer> &bufferProducer,
            const sp<ICrypto> &crypto,
            const sp<IDescrambler> &descrambler,
            int flags);

    status_t setSurface(
            const sp<IGraphicBufferProducer> &surface);

    status_t createInputSurface(sp<IGraphicBufferProducer>* bufferProducer);
    status_t setInputSurface(const sp<PersistentSurface> &surface);

    status_t start();
    status_t stop();
    status_t reset();

    status_t flush();

    status_t queueInputBuffer(
            size_t index,
            size_t offset, size_t size, int64_t timeUs, uint32_t flags,
            AString *errorDetailMsg);

    status_t queueInputBuffers(
            size_t index,
            size_t offset,
            size_t size,
            const sp<RefBase> &auInfo,
            AString *errorDetailMsg = NULL);

    status_t queueSecureInputBuffer(
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
            AString *errorDetailMsg);

    status_t queueBuffer(
            size_t index, const std::shared_ptr<C2Buffer> &buffer,
            const sp<RefBase> &infos, const sp<AMessage> &tunings,
            AString *errorDetailMsg);

    status_t queueEncryptedLinearBlock(
            size_t index,
            const sp<hardware::HidlMemory> &buffer,
            size_t offset,
            const CryptoPlugin::SubSample *subSamples,
            size_t numSubSamples,
            const uint8_t key[16],
            const uint8_t iv[16],
            CryptoPlugin::Mode mode,
            const CryptoPlugin::Pattern &pattern,
            const sp<RefBase> &infos,
            const sp<AMessage> &tunings,
            AString *errorDetailMsg);

    status_t dequeueInputBuffer(size_t *index, int64_t timeoutUs);

    status_t dequeueOutputBuffer(
            JNIEnv *env, jobject bufferInfo, size_t *index, int64_t timeoutUs);

    status_t releaseOutputBuffer(
            size_t index, bool render, bool updatePTS, int64_t timestampNs);

    status_t signalEndOfInputStream();

    status_t getFormat(JNIEnv *env, bool input, jobject *format) const;

    status_t getOutputFormat(JNIEnv *env, size_t index, jobject *format) const;

    status_t getBuffers(
            JNIEnv *env, bool input, jobjectArray *bufArray) const;

    status_t getBuffer(
            JNIEnv *env, bool input, size_t index, jobject *buf) const;

    status_t getImage(
            JNIEnv *env, bool input, size_t index, jobject *image) const;

    status_t getOutputFrame(
            JNIEnv *env, jobject frame, size_t index) const;

    status_t getName(JNIEnv *env, jstring *name) const;

    status_t getCodecInfo(JNIEnv *env, jobject *codecInfo) const;

    status_t getMetrics(JNIEnv *env, mediametrics::Item * &reply) const;

    status_t setParameters(const sp<AMessage> &params);

    void setVideoScalingMode(int mode);

    void selectAudioPresentation(const int32_t presentationId, const int32_t programId);

    status_t querySupportedVendorParameters(JNIEnv *env, jobject *names);

    status_t describeParameter(JNIEnv *env, jstring name, jobject *desc);

    status_t subscribeToVendorParameters(JNIEnv *env, jobject names);

    status_t unsubscribeFromVendorParameters(JNIEnv *env, jobject names);

    bool hasCryptoOrDescrambler() { return mHasCryptoOrDescrambler; }

    const sp<ICrypto> &getCrypto() { return mCrypto; }

    std::string getExceptionMessage(const char *msg) const;

protected:
    virtual ~JMediaCodec();

    virtual void onMessageReceived(const sp<AMessage> &msg);

private:
    enum {
        kWhatCallbackNotify,
        kWhatFrameRendered,
        kWhatAsyncReleaseComplete,
        kWhatFirstTunnelFrameReady,
    };

    jclass mClass;
    jweak mObject;
    sp<Surface> mSurfaceTextureClient;

    sp<ALooper> mLooper;
    sp<MediaCodec> mCodec;
    AString mNameAtCreation;
    bool mGraphicOutput{false};
    bool mHasCryptoOrDescrambler{false};
    std::once_flag mReleaseFlag;
    std::once_flag mAsyncReleaseFlag;

    sp<AMessage> mCallbackNotification;
    sp<AMessage> mOnFirstTunnelFrameReadyNotification;
    sp<AMessage> mOnFrameRenderedNotification;

    status_t mInitStatus;

    sp<ICrypto> mCrypto;

    template <typename T>
    status_t createByteBufferFromABuffer(
            JNIEnv *env, bool readOnly, bool clearBuffer, const sp<T> &buffer,
            jobject *buf) const;

    void handleCallback(const sp<AMessage> &msg);
    void handleFirstTunnelFrameReadyNotification(const sp<AMessage> &msg);
    void handleFrameRenderedNotification(const sp<AMessage> &msg);

    DISALLOW_EVIL_CONSTRUCTORS(JMediaCodec);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_MEDIACODEC_H_
