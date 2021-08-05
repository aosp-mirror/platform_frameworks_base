/*
 * Copyright 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "TvTuner-JNI"
#include <utils/Log.h>

#include "android_media_MediaCodecLinearBlock.h"
#include "android_media_tv_Tuner.h"
#include "android_runtime/AndroidRuntime.h"

#include <android-base/logging.h>
#include <media/stagefright/foundation/ADebug.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <utils/NativeHandle.h>

#pragma GCC diagnostic ignored "-Wunused-function"

using ::android::hardware::Void;
using ::android::hardware::hidl_bitfield;
using ::android::hardware::hidl_vec;
using ::android::hardware::tv::tuner::V1_0::AudioExtraMetaData;
using ::android::hardware::tv::tuner::V1_0::DataFormat;
using ::android::hardware::tv::tuner::V1_0::DemuxAlpFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxAlpFilterType;
using ::android::hardware::tv::tuner::V1_0::DemuxAlpLengthType;
using ::android::hardware::tv::tuner::V1_0::DemuxCapabilities;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterAvSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterDownloadEvent;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterDownloadSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterIpPayloadEvent;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterMainType;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterMediaEvent;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterMmtpRecordEvent;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterPesDataSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterPesEvent;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterRecordSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterSectionBits;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterSectionEvent;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterSectionSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterTemiEvent;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterTsRecordEvent;
using ::android::hardware::tv::tuner::V1_0::DemuxIpAddress;
using ::android::hardware::tv::tuner::V1_0::DemuxIpFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxIpFilterType;
using ::android::hardware::tv::tuner::V1_0::DemuxMmtpFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxMmtpFilterType;
using ::android::hardware::tv::tuner::V1_0::DemuxMmtpPid;
using ::android::hardware::tv::tuner::V1_0::DemuxQueueNotifyBits;
using ::android::hardware::tv::tuner::V1_0::DemuxRecordScIndexType;
using ::android::hardware::tv::tuner::V1_0::DemuxScHevcIndex;
using ::android::hardware::tv::tuner::V1_0::DemuxScIndex;
using ::android::hardware::tv::tuner::V1_0::DemuxTlvFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxTlvFilterType;
using ::android::hardware::tv::tuner::V1_0::DemuxTpid;
using ::android::hardware::tv::tuner::V1_0::DemuxTsFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxTsFilterType;
using ::android::hardware::tv::tuner::V1_0::DemuxTsIndex;
using ::android::hardware::tv::tuner::V1_0::DvrSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendAnalogSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendAnalogSifStandard;
using ::android::hardware::tv::tuner::V1_0::FrontendAnalogType;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3Bandwidth;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3CodeRate;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3DemodOutputFormat;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3Fec;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3Modulation;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3PlpSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3Settings;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3TimeInterleaveMode;
using ::android::hardware::tv::tuner::V1_0::FrontendAtscSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendAtscModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbcAnnex;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbcModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbcOuterFec;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbcSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbcSpectralInversion;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsCodeRate;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsPilot;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsRolloff;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsStandard;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsVcmMode;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtBandwidth;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtCoderate;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtConstellation;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtGuardInterval;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtHierarchy;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtPlpMode;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtStandard;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtTransmissionMode;
using ::android::hardware::tv::tuner::V1_0::FrontendInnerFec;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbs3Coderate;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbs3Modulation;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbs3Rolloff;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbs3Settings;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbsCoderate;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbsModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbsRolloff;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbsSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbsStreamIdType;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbtBandwidth;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbtCoderate;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbtGuardInterval;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbtMode;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbtModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbtSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendModulationStatus;
using ::android::hardware::tv::tuner::V1_0::FrontendScanAtsc3PlpInfo;
using ::android::hardware::tv::tuner::V1_0::FrontendStatus;
using ::android::hardware::tv::tuner::V1_0::FrontendStatusAtsc3PlpInfo;
using ::android::hardware::tv::tuner::V1_0::FrontendStatusType;
using ::android::hardware::tv::tuner::V1_0::FrontendType;
using ::android::hardware::tv::tuner::V1_0::LnbPosition;
using ::android::hardware::tv::tuner::V1_0::LnbTone;
using ::android::hardware::tv::tuner::V1_0::LnbVoltage;
using ::android::hardware::tv::tuner::V1_0::PlaybackSettings;
using ::android::hardware::tv::tuner::V1_0::RecordSettings;
using ::android::hardware::tv::tuner::V1_1::AudioStreamType;
using ::android::hardware::tv::tuner::V1_1::AvStreamType;
using ::android::hardware::tv::tuner::V1_1::Constant;
using ::android::hardware::tv::tuner::V1_1::Constant64Bit;
using ::android::hardware::tv::tuner::V1_1::FrontendAnalogAftFlag;
using ::android::hardware::tv::tuner::V1_1::FrontendAnalogSettingsExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendBandwidth;
using ::android::hardware::tv::tuner::V1_1::FrontendCableTimeInterleaveMode;
using ::android::hardware::tv::tuner::V1_1::FrontendDvbcBandwidth;
using ::android::hardware::tv::tuner::V1_1::FrontendDvbsScanType;
using ::android::hardware::tv::tuner::V1_1::FrontendDvbcSettingsExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendDvbsSettingsExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendDvbtSettingsExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbBandwidth;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbCapabilities;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbCodeRate;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbGuardInterval;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbModulation;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbSettings;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbTimeInterleaveMode;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbTransmissionMode;
using ::android::hardware::tv::tuner::V1_1::FrontendGuardInterval;
using ::android::hardware::tv::tuner::V1_1::FrontendInterleaveMode;
using ::android::hardware::tv::tuner::V1_1::FrontendModulation;
using ::android::hardware::tv::tuner::V1_1::FrontendRollOff;
using ::android::hardware::tv::tuner::V1_1::FrontendSpectralInversion;
using ::android::hardware::tv::tuner::V1_1::FrontendStatusExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendStatusTypeExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendTransmissionMode;
using ::android::hardware::tv::tuner::V1_1::VideoStreamType;

struct fields_t {
    jfieldID tunerContext;
    jfieldID lnbContext;
    jfieldID filterContext;
    jfieldID timeFilterContext;
    jfieldID descramblerContext;
    jfieldID dvrRecorderContext;
    jfieldID dvrPlaybackContext;
    jfieldID mediaEventContext;
    jmethodID frontendInitID;
    jmethodID filterInitID;
    jmethodID timeFilterInitID;
    jmethodID dvrRecorderInitID;
    jmethodID dvrPlaybackInitID;
    jmethodID onFrontendEventID;
    jmethodID onFilterStatusID;
    jmethodID onFilterEventID;
    jmethodID lnbInitID;
    jmethodID onLnbEventID;
    jmethodID onLnbDiseqcMessageID;
    jmethodID onDvrRecordStatusID;
    jmethodID onDvrPlaybackStatusID;
    jmethodID descramblerInitID;
    jmethodID linearBlockInitID;
    jmethodID linearBlockSetInternalStateID;
};

static fields_t gFields;


static int IP_V4_LENGTH = 4;
static int IP_V6_LENGTH = 16;

void DestroyCallback(const C2Buffer * buf, void *arg) {
    android::sp<android::MediaEvent> event = (android::MediaEvent *)arg;
    android::Mutex::Autolock autoLock(event->mLock);
    if (event->mLinearBlockObj != NULL) {
        JNIEnv *env = android::AndroidRuntime::getJNIEnv();
        env->DeleteWeakGlobalRef(event->mLinearBlockObj);
        event->mLinearBlockObj = NULL;
    }

    event->mAvHandleRefCnt--;
    event->finalize();
    event->decStrong(buf);
}

namespace android {

/////////////// LnbClientCallbackImpl ///////////////////////

void LnbClientCallbackImpl::onEvent(const LnbEventType lnbEventType) {
    ALOGD("LnbClientCallbackImpl::onEvent, type=%d", lnbEventType);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject lnb(env->NewLocalRef(mLnbObj));
    if (!env->IsSameObject(lnb, nullptr)) {
        env->CallVoidMethod(
                lnb,
                gFields.onLnbEventID,
                (jint)lnbEventType);
    } else {
        ALOGE("LnbClientCallbackImpl::onEvent:"
                "Lnb object has been freed. Ignoring callback.");
    }
}

void LnbClientCallbackImpl::onDiseqcMessage(const hidl_vec<uint8_t>& diseqcMessage) {
    ALOGD("LnbClientCallbackImpl::onDiseqcMessage");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject lnb(env->NewLocalRef(mLnbObj));
    if (!env->IsSameObject(lnb, nullptr)) {
        jbyteArray array = env->NewByteArray(diseqcMessage.size());
        env->SetByteArrayRegion(
                array, 0, diseqcMessage.size(), reinterpret_cast<jbyte*>(diseqcMessage[0]));
        env->CallVoidMethod(
                lnb,
                gFields.onLnbDiseqcMessageID,
                array);
    } else {
        ALOGE("LnbClientCallbackImpl::onDiseqcMessage:"
                "Lnb object has been freed. Ignoring callback.");
    }
}

void LnbClientCallbackImpl::setLnb(jweak lnbObj) {
    ALOGD("LnbClientCallbackImpl::setLnb");
    mLnbObj = lnbObj;
}

LnbClientCallbackImpl::~LnbClientCallbackImpl() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (mLnbObj != NULL) {
        env->DeleteWeakGlobalRef(mLnbObj);
        mLnbObj = NULL;
    }
}

/////////////// DvrClientCallbackImpl ///////////////////////

void DvrClientCallbackImpl::onRecordStatus(RecordStatus status) {
    ALOGD("DvrClientCallbackImpl::onRecordStatus");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject dvr(env->NewLocalRef(mDvrObj));
    if (!env->IsSameObject(dvr, nullptr)) {
        env->CallVoidMethod(
                dvr,
                gFields.onDvrRecordStatusID,
                (jint) status);
    } else {
        ALOGE("DvrClientCallbackImpl::onRecordStatus:"
                "Dvr object has been freed. Ignoring callback.");
    }
}

void DvrClientCallbackImpl::onPlaybackStatus(PlaybackStatus status) {
    ALOGD("DvrClientCallbackImpl::onPlaybackStatus");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject dvr(env->NewLocalRef(mDvrObj));
    if (!env->IsSameObject(dvr, nullptr)) {
        env->CallVoidMethod(
                dvr,
                gFields.onDvrPlaybackStatusID,
                (jint) status);
    } else {
        ALOGE("DvrClientCallbackImpl::onPlaybackStatus:"
                "Dvr object has been freed. Ignoring callback.");
    }
}

void DvrClientCallbackImpl::setDvr(jweak dvrObj) {
    ALOGD("DvrClientCallbackImpl::setDvr");
    mDvrObj = dvrObj;
}

DvrClientCallbackImpl::~DvrClientCallbackImpl() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (mDvrObj != NULL) {
        env->DeleteWeakGlobalRef(mDvrObj);
        mDvrObj = NULL;
    }
}

/////////////// C2DataIdInfo ///////////////////////

C2DataIdInfo::C2DataIdInfo(uint32_t index, uint64_t value) : C2Param(kParamSize, index) {
    CHECK(isGlobal());
    CHECK_EQ(C2Param::INFO, kind());
    mInfo = StubInfo(value);
    memcpy(static_cast<C2Param *>(this) + 1, static_cast<C2Param *>(&mInfo) + 1,
            kParamSize - sizeof(C2Param));
}

/////////////// MediaEvent ///////////////////////

MediaEvent::MediaEvent(sp<FilterClient> filterClient, hidl_handle avHandle,
        uint64_t dataId, uint64_t dataSize, jobject obj) : mFilterClient(filterClient),
        mDataId(dataId), mDataSize(dataSize), mBuffer(nullptr),
        mDataIdRefCnt(0), mAvHandleRefCnt(0), mIonHandle(nullptr) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    mMediaEventObj = env->NewWeakGlobalRef(obj);
    mAvHandle = native_handle_clone(avHandle.getNativeHandle());
    mLinearBlockObj = NULL;
}

MediaEvent::~MediaEvent() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->DeleteWeakGlobalRef(mMediaEventObj);
    mMediaEventObj = NULL;
    native_handle_delete(mAvHandle);
    if (mIonHandle != NULL) {
        delete mIonHandle;
    }
    std::shared_ptr<C2Buffer> pC2Buffer = mC2Buffer.lock();
    if (pC2Buffer != NULL) {
        pC2Buffer->unregisterOnDestroyNotify(&DestroyCallback, this);
    }

    if (mLinearBlockObj != NULL) {
        env->DeleteWeakGlobalRef(mLinearBlockObj);
        mLinearBlockObj = NULL;
    }

    mFilterClient = NULL;
}

void MediaEvent::finalize() {
    if (mAvHandleRefCnt == 0 && mFilterClient != NULL) {
        mFilterClient->releaseAvHandle(
                mAvHandle, mDataIdRefCnt == 0 ? mDataId : 0);
        native_handle_close(mAvHandle);
    }
}

jobject MediaEvent::getLinearBlock() {
    ALOGD("MediaEvent::getLinearBlock");
    if (mAvHandle == NULL) {
        return NULL;
    }
    if (mLinearBlockObj != NULL) {
        return mLinearBlockObj;
    }

    int fd;
    int numInts = 0;
    int memIndex;
    int dataSize;
    SharedHandleInfo info = mFilterClient->getAvSharedHandleInfo();
    native_handle_t* avSharedHandle = info.sharedHandle;
    uint64_t avSharedMemSize = info.size;

    if (mAvHandle->numFds == 0) {
        if (avSharedHandle == NULL) {
            ALOGE("Shared AV memory handle is not initialized.");
            return NULL;
        }
        if (avSharedHandle->numFds == 0) {
            ALOGE("Shared AV memory handle is empty.");
            return NULL;
        }
        fd = avSharedHandle->data[0];
        dataSize = avSharedMemSize;
        numInts = avSharedHandle->numInts;
        if (numInts > 0) {
            // If the first int in the shared native handle has value, use it as the index
            memIndex = avSharedHandle->data[avSharedHandle->numFds];
        }
    } else {
        fd = mAvHandle->data[0];
        dataSize = mDataSize;
        numInts = mAvHandle->numInts;
        if (numInts > 0) {
            // Otherwise if the first int in the av native handle returned from the filter
            // event has value, use it as the index
            memIndex = mAvHandle->data[mAvHandle->numFds];
        } else {
            if (avSharedHandle != NULL) {
                numInts = avSharedHandle->numInts;
                if (numInts > 0) {
                    // If the first int in the shared native handle has value, use it as the index
                    memIndex = avSharedHandle->data[avSharedHandle->numFds];
                }
            }
        }
    }

    mIonHandle = new C2HandleIon(dup(fd), dataSize);
    std::shared_ptr<C2LinearBlock> block = _C2BlockFactory::CreateLinearBlock(mIonHandle);
    if (block != nullptr) {
        // CreateLinearBlock delete mIonHandle after it create block successfully.
        // ToDo: coordinate who is response to delete mIonHandle
        mIonHandle = NULL;
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        std::unique_ptr<JMediaCodecLinearBlock> context{new JMediaCodecLinearBlock};
        context->mBlock = block;
        std::shared_ptr<C2Buffer> pC2Buffer = context->toC2Buffer(0, dataSize);
        context->mBuffer = pC2Buffer;
        mC2Buffer = pC2Buffer;
        if (numInts > 0) {
            std::shared_ptr<C2Param> c2param = std::make_shared<C2DataIdInfo>(memIndex, mDataId);
            std::shared_ptr<C2Info> info(std::static_pointer_cast<C2Info>(c2param));
            pC2Buffer->setInfo(info);
        }
        pC2Buffer->registerOnDestroyNotify(&DestroyCallback, this);
        incStrong(pC2Buffer.get());
        jobject linearBlock =
                env->NewObject(
                        env->FindClass("android/media/MediaCodec$LinearBlock"),
                        gFields.linearBlockInitID);
        env->CallVoidMethod(
                linearBlock,
                gFields.linearBlockSetInternalStateID,
                (jlong)context.release(),
                true);
        mLinearBlockObj = env->NewWeakGlobalRef(linearBlock);
        mAvHandleRefCnt++;
        return linearBlock;
    } else {
        native_handle_close(const_cast<native_handle_t*>(
                    reinterpret_cast<const native_handle_t*>(mIonHandle)));
        native_handle_delete(const_cast<native_handle_t*>(
                    reinterpret_cast<const native_handle_t*>(mIonHandle)));
        mIonHandle = NULL;
        return NULL;
    }
}

uint64_t MediaEvent::getAudioHandle() {
    mDataIdRefCnt++;
    return mDataId;
}

/////////////// FilterClientCallbackImpl ///////////////////////

jobjectArray FilterClientCallbackImpl::getSectionEvent(
        jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/SectionEvent");
    jmethodID eventInit = env->GetMethodID(eventClazz, "<init>", "(IIII)V");

    for (int i = 0; i < events.size(); i++) {
        auto event = events[i];
        DemuxFilterSectionEvent sectionEvent = event.section();

        jint tableId = static_cast<jint>(sectionEvent.tableId);
        jint version = static_cast<jint>(sectionEvent.version);
        jint sectionNum = static_cast<jint>(sectionEvent.sectionNum);
        jint dataLength = static_cast<jint>(sectionEvent.dataLength);

        jobject obj =
                env->NewObject(eventClazz, eventInit, tableId, version, sectionNum, dataLength);
        env->SetObjectArrayElement(arr, i, obj);
    }
    return arr;
}

jobjectArray FilterClientCallbackImpl::getMediaEvent(
        jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/MediaEvent");
    jmethodID eventInit = env->GetMethodID(eventClazz,
            "<init>",
            "(IZJJJLandroid/media/MediaCodec$LinearBlock;"
            "ZJIZLandroid/media/tv/tuner/filter/AudioDescriptor;)V");
    jfieldID eventContext = env->GetFieldID(eventClazz, "mNativeContext", "J");

    for (int i = 0; i < events.size(); i++) {
        auto event = events[i];
        DemuxFilterMediaEvent mediaEvent = event.media();

        jobject audioDescriptor = NULL;
        if (mediaEvent.extraMetaData.getDiscriminator()
                == DemuxFilterMediaEvent::ExtraMetaData::hidl_discriminator::audio) {
            jclass adClazz = env->FindClass("android/media/tv/tuner/filter/AudioDescriptor");
            jmethodID adInit = env->GetMethodID(adClazz, "<init>", "(BBCBBB)V");

            AudioExtraMetaData ad = mediaEvent.extraMetaData.audio();
            jbyte adFade = static_cast<jbyte>(ad.adFade);
            jbyte adPan = static_cast<jbyte>(ad.adPan);
            jchar versionTextTag = static_cast<jchar>(ad.versionTextTag);
            jbyte adGainCenter = static_cast<jbyte>(ad.adGainCenter);
            jbyte adGainFront = static_cast<jbyte>(ad.adGainFront);
            jbyte adGainSurround = static_cast<jbyte>(ad.adGainSurround);

            audioDescriptor =
                    env->NewObject(adClazz, adInit, adFade, adPan, versionTextTag, adGainCenter,
                            adGainFront, adGainSurround);
        }

        jlong dataLength = static_cast<jlong>(mediaEvent.dataLength);

        jint streamId = static_cast<jint>(mediaEvent.streamId);
        jboolean isPtsPresent = static_cast<jboolean>(mediaEvent.isPtsPresent);
        jlong pts = static_cast<jlong>(mediaEvent.pts);
        jlong offset = static_cast<jlong>(mediaEvent.offset);
        jboolean isSecureMemory = static_cast<jboolean>(mediaEvent.isSecureMemory);
        jlong avDataId = static_cast<jlong>(mediaEvent.avDataId);
        jint mpuSequenceNumber = static_cast<jint>(mediaEvent.mpuSequenceNumber);
        jboolean isPesPrivateData = static_cast<jboolean>(mediaEvent.isPesPrivateData);

        jobject obj =
                env->NewObject(eventClazz, eventInit, streamId, isPtsPresent, pts, dataLength,
                offset, NULL, isSecureMemory, avDataId, mpuSequenceNumber, isPesPrivateData,
                audioDescriptor);

        if (mediaEvent.avMemory.getNativeHandle() != NULL || mediaEvent.avDataId != 0) {
            sp<MediaEvent> mediaEventSp =
                           new MediaEvent(mFilterClient, mediaEvent.avMemory,
                               mediaEvent.avDataId, dataLength + offset, obj);
            mediaEventSp->mAvHandleRefCnt++;
            env->SetLongField(obj, eventContext, (jlong) mediaEventSp.get());
            mediaEventSp->incStrong(obj);
        }

        env->SetObjectArrayElement(arr, i, obj);
    }
    return arr;
}

jobjectArray FilterClientCallbackImpl::getPesEvent(
        jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/PesEvent");
    jmethodID eventInit = env->GetMethodID(eventClazz, "<init>", "(III)V");

    for (int i = 0; i < events.size(); i++) {
        auto event = events[i];
        DemuxFilterPesEvent pesEvent = event.pes();

        jint streamId = static_cast<jint>(pesEvent.streamId);
        jint dataLength = static_cast<jint>(pesEvent.dataLength);
        jint mpuSequenceNumber = static_cast<jint>(pesEvent.mpuSequenceNumber);

        jobject obj =
                env->NewObject(eventClazz, eventInit, streamId, dataLength, mpuSequenceNumber);
        env->SetObjectArrayElement(arr, i, obj);
    }
    return arr;
}

jobjectArray FilterClientCallbackImpl::getTsRecordEvent(
        jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events,
                const std::vector<DemuxFilterEventExt::Event>& eventsExt) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/TsRecordEvent");
    jmethodID eventInit = env->GetMethodID(eventClazz, "<init>", "(IIIJJI)V");

    for (int i = 0; i < events.size(); i++) {
        auto event = events[i];
        DemuxFilterTsRecordEvent tsRecordEvent = event.tsRecord();
        DemuxPid pid = tsRecordEvent.pid;

        jint jpid = static_cast<jint>(Constant::INVALID_TS_PID);

        if (pid.getDiscriminator() == DemuxPid::hidl_discriminator::tPid) {
            jpid = static_cast<jint>(pid.tPid());
        } else if (pid.getDiscriminator() == DemuxPid::hidl_discriminator::mmtpPid) {
            jpid = static_cast<jint>(pid.mmtpPid());
        }

        jint sc = 0;

        if (tsRecordEvent.scIndexMask.getDiscriminator()
                == DemuxFilterTsRecordEvent::ScIndexMask::hidl_discriminator::sc) {
            sc = static_cast<jint>(tsRecordEvent.scIndexMask.sc());
        } else if (tsRecordEvent.scIndexMask.getDiscriminator()
                == DemuxFilterTsRecordEvent::ScIndexMask::hidl_discriminator::scHevc) {
            sc = static_cast<jint>(tsRecordEvent.scIndexMask.scHevc());
        }

        jint ts = static_cast<jint>(tsRecordEvent.tsIndexMask);

        jlong byteNumber = static_cast<jlong>(tsRecordEvent.byteNumber);

        jlong pts;
        jlong firstMbInSlice;
        if (eventsExt.size() > i && eventsExt[i].getDiscriminator() ==
                    DemuxFilterEventExt::Event::hidl_discriminator::tsRecord) {
            pts = static_cast<jlong>(eventsExt[i].tsRecord().pts);
            firstMbInSlice = static_cast<jint>(eventsExt[i].tsRecord().firstMbInSlice);
        } else {
            pts = static_cast<jlong>(Constant64Bit::INVALID_PRESENTATION_TIME_STAMP);
            firstMbInSlice = static_cast<jint>(Constant::INVALID_FIRST_MACROBLOCK_IN_SLICE);
        }

        jobject obj =
                env->NewObject(eventClazz, eventInit, jpid, ts, sc, byteNumber,
                        pts, firstMbInSlice);
        env->SetObjectArrayElement(arr, i, obj);
    }
    return arr;
}

jobjectArray FilterClientCallbackImpl::getMmtpRecordEvent(
        jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events,
                const std::vector<DemuxFilterEventExt::Event>& eventsExt) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/MmtpRecordEvent");
    jmethodID eventInit = env->GetMethodID(eventClazz, "<init>", "(IJIJII)V");

    for (int i = 0; i < events.size(); i++) {
        auto event = events[i];

        DemuxFilterMmtpRecordEvent mmtpRecordEvent = event.mmtpRecord();

        jint scHevcIndexMask = static_cast<jint>(mmtpRecordEvent.scHevcIndexMask);
        jlong byteNumber = static_cast<jlong>(mmtpRecordEvent.byteNumber);

        jint mpuSequenceNumber;
        jlong pts;
        jlong firstMbInSlice;
        jlong tsIndexMask;

        if (eventsExt.size() > i && eventsExt[i].getDiscriminator() ==
                    DemuxFilterEventExt::Event::hidl_discriminator::mmtpRecord) {
            mpuSequenceNumber = static_cast<jint>(eventsExt[i].mmtpRecord().mpuSequenceNumber);
            pts = static_cast<jlong>(eventsExt[i].mmtpRecord().pts);
            firstMbInSlice = static_cast<jint>(eventsExt[i].mmtpRecord().firstMbInSlice);
            tsIndexMask = static_cast<jint>(eventsExt[i].mmtpRecord().tsIndexMask);
        } else {
            mpuSequenceNumber =
                    static_cast<jint>(Constant::INVALID_MMTP_RECORD_EVENT_MPT_SEQUENCE_NUM);
            pts = static_cast<jlong>(Constant64Bit::INVALID_PRESENTATION_TIME_STAMP);
            firstMbInSlice = static_cast<jint>(Constant::INVALID_FIRST_MACROBLOCK_IN_SLICE);
            tsIndexMask = 0;
        }

        jobject obj =
                env->NewObject(eventClazz, eventInit, scHevcIndexMask, byteNumber,
                        mpuSequenceNumber, pts, firstMbInSlice, tsIndexMask);
        env->SetObjectArrayElement(arr, i, obj);
    }
    return arr;
}

jobjectArray FilterClientCallbackImpl::getDownloadEvent(
        jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/DownloadEvent");
    jmethodID eventInit = env->GetMethodID(eventClazz, "<init>", "(IIIII)V");

    for (int i = 0; i < events.size(); i++) {
        auto event = events[i];
        DemuxFilterDownloadEvent downloadEvent = event.download();

        jint itemId = static_cast<jint>(downloadEvent.itemId);
        jint mpuSequenceNumber = static_cast<jint>(downloadEvent.mpuSequenceNumber);
        jint itemFragmentIndex = static_cast<jint>(downloadEvent.itemFragmentIndex);
        jint lastItemFragmentIndex = static_cast<jint>(downloadEvent.lastItemFragmentIndex);
        jint dataLength = static_cast<jint>(downloadEvent.dataLength);

        jobject obj =
                env->NewObject(eventClazz, eventInit, itemId, mpuSequenceNumber, itemFragmentIndex,
                        lastItemFragmentIndex, dataLength);
        env->SetObjectArrayElement(arr, i, obj);
    }
    return arr;
}

jobjectArray FilterClientCallbackImpl::getIpPayloadEvent(
        jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/IpPayloadEvent");
    jmethodID eventInit = env->GetMethodID(eventClazz, "<init>", "(I)V");

    for (int i = 0; i < events.size(); i++) {
        auto event = events[i];
        DemuxFilterIpPayloadEvent ipPayloadEvent = event.ipPayload();
        jint dataLength = static_cast<jint>(ipPayloadEvent.dataLength);
        jobject obj = env->NewObject(eventClazz, eventInit, dataLength);
        env->SetObjectArrayElement(arr, i, obj);
    }
    return arr;
}

jobjectArray FilterClientCallbackImpl::getTemiEvent(
        jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/TemiEvent");
    jmethodID eventInit = env->GetMethodID(eventClazz, "<init>", "(JB[B)V");

    for (int i = 0; i < events.size(); i++) {
        auto event = events[i];
        DemuxFilterTemiEvent temiEvent = event.temi();
        jlong pts = static_cast<jlong>(temiEvent.pts);
        jbyte descrTag = static_cast<jbyte>(temiEvent.descrTag);
        std::vector<uint8_t> descrData = temiEvent.descrData;

        jbyteArray array = env->NewByteArray(descrData.size());
        env->SetByteArrayRegion(
                array, 0, descrData.size(), reinterpret_cast<jbyte*>(&descrData[0]));

        jobject obj = env->NewObject(eventClazz, eventInit, pts, descrTag, array);
        env->SetObjectArrayElement(arr, i, obj);
    }
    return arr;
}

jobjectArray FilterClientCallbackImpl::getScramblingStatusEvent(
        jobjectArray& arr, const std::vector<DemuxFilterEventExt::Event>& eventsExt) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/ScramblingStatusEvent");
    jmethodID eventInit = env->GetMethodID(eventClazz, "<init>", "(I)V");

    auto scramblingStatus = eventsExt[0].monitorEvent().scramblingStatus();
    jobject obj = env->NewObject(eventClazz, eventInit, static_cast<jint>(scramblingStatus));
    env->SetObjectArrayElement(arr, 0, obj);
    return arr;
}

jobjectArray FilterClientCallbackImpl::getIpCidChangeEvent(
        jobjectArray& arr, const std::vector<DemuxFilterEventExt::Event>& eventsExt) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/IpCidChangeEvent");
    jmethodID eventInit = env->GetMethodID(eventClazz, "<init>", "(I)V");

    auto cid = eventsExt[0].monitorEvent().cid();
    jobject obj = env->NewObject(eventClazz, eventInit, static_cast<jint>(cid));
    env->SetObjectArrayElement(arr, 0, obj);
    return arr;
}

jobjectArray FilterClientCallbackImpl::getRestartEvent(
        jobjectArray& arr, const std::vector<DemuxFilterEventExt::Event>& eventsExt) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/RestartEvent");
    jmethodID eventInit = env->GetMethodID(eventClazz, "<init>", "(I)V");

    auto startId = eventsExt[0].startId();
    jobject obj = env->NewObject(eventClazz, eventInit, static_cast<jint>(startId));
    env->SetObjectArrayElement(arr, 0, obj);
    return arr;
}

void FilterClientCallbackImpl::onFilterEvent_1_1(const DemuxFilterEvent& filterEvent,
        const DemuxFilterEventExt& filterEventExt) {
    ALOGD("FilterClientCallbackImpl::onFilterEvent_1_1");

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobjectArray array;

    std::vector<DemuxFilterEvent::Event> events = filterEvent.events;
    std::vector<DemuxFilterEventExt::Event> eventsExt = filterEventExt.events;
    jclass eventClazz = env->FindClass("android/media/tv/tuner/filter/FilterEvent");

    if (events.empty() && !eventsExt.empty()) {
        // Monitor event should be sent with one DemuxFilterMonitorEvent in DemuxFilterEventExt.
        array = env->NewObjectArray(1, eventClazz, NULL);
        auto eventExt = eventsExt[0];
        switch (eventExt.getDiscriminator()) {
            case DemuxFilterEventExt::Event::hidl_discriminator::monitorEvent: {
                switch (eventExt.monitorEvent().getDiscriminator()) {
                    case DemuxFilterMonitorEvent::hidl_discriminator::scramblingStatus: {
                        array = getScramblingStatusEvent(array, eventsExt);
                        break;
                    }
                    case DemuxFilterMonitorEvent::hidl_discriminator::cid: {
                        array = getIpCidChangeEvent(array, eventsExt);
                        break;
                    }
                    default: {
                        break;
                    }
                }
                break;
            }
            case DemuxFilterEventExt::Event::hidl_discriminator::startId: {
                array = getRestartEvent(array, eventsExt);
                break;
            }
            default: {
                break;
            }
        }
    }

    if (!events.empty()) {
        array = env->NewObjectArray(events.size(), eventClazz, NULL);
        auto event = events[0];
        switch (event.getDiscriminator()) {
            case DemuxFilterEvent::Event::hidl_discriminator::media: {
                array = getMediaEvent(array, events);
                break;
            }
            case DemuxFilterEvent::Event::hidl_discriminator::section: {
                array = getSectionEvent(array, events);
                break;
            }
            case DemuxFilterEvent::Event::hidl_discriminator::pes: {
                array = getPesEvent(array, events);
                break;
            }
            case DemuxFilterEvent::Event::hidl_discriminator::tsRecord: {
                array = getTsRecordEvent(array, events, eventsExt);
                break;
            }
            case DemuxFilterEvent::Event::hidl_discriminator::mmtpRecord: {
                array = getMmtpRecordEvent(array, events, eventsExt);
                break;
            }
            case DemuxFilterEvent::Event::hidl_discriminator::download: {
                array = getDownloadEvent(array, events);
                break;
            }
            case DemuxFilterEvent::Event::hidl_discriminator::ipPayload: {
                array = getIpPayloadEvent(array, events);
                break;
            }
            case DemuxFilterEvent::Event::hidl_discriminator::temi: {
                array = getTemiEvent(array, events);
                break;
            }
            default: {
                break;
            }
        }
    }
    jobject filter(env->NewLocalRef(mFilterObj));
    if (!env->IsSameObject(filter, nullptr)) {
        env->CallVoidMethod(
                filter,
                gFields.onFilterEventID,
                array);
    } else {
        ALOGE("FilterClientCallbackImpl::onFilterEvent_1_1:"
                "Filter object has been freed. Ignoring callback.");
    }
}

void FilterClientCallbackImpl::onFilterEvent(const DemuxFilterEvent& filterEvent) {
    ALOGD("FilterClientCallbackImpl::onFilterEvent");
    std::vector<DemuxFilterEventExt::Event> emptyEventsExt;
    DemuxFilterEventExt emptyFilterEventExt {
            .events = emptyEventsExt,
    };
    return onFilterEvent_1_1(filterEvent, emptyFilterEventExt);
}

void FilterClientCallbackImpl::onFilterStatus(const DemuxFilterStatus status) {
    ALOGD("FilterClientCallbackImpl::onFilterStatus");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject filter(env->NewLocalRef(mFilterObj));
    if (!env->IsSameObject(filter, nullptr)) {
        env->CallVoidMethod(
                filter,
                gFields.onFilterStatusID,
                (jint)status);
    } else {
        ALOGE("FilterClientCallbackImpl::onFilterStatus:"
                "Filter object has been freed. Ignoring callback.");
    }
}

void FilterClientCallbackImpl::setFilter(jweak filterObj, sp<FilterClient> filterClient) {
    ALOGD("FilterClientCallbackImpl::setFilter");
    // Java Object
    mFilterObj = filterObj;
    mFilterClient = filterClient;
}

FilterClientCallbackImpl::~FilterClientCallbackImpl() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (mFilterObj != NULL) {
        env->DeleteWeakGlobalRef(mFilterObj);
        mFilterObj = NULL;
    }
    mFilterClient = NULL;
}

/////////////// FrontendClientCallbackImpl ///////////////////////

FrontendClientCallbackImpl::FrontendClientCallbackImpl(jweak tunerObj) : mObject(tunerObj) {}

void FrontendClientCallbackImpl::onEvent(FrontendEventType frontendEventType) {
    ALOGD("FrontendClientCallbackImpl::onEvent, type=%d", frontendEventType);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject frontend(env->NewLocalRef(mObject));
    if (!env->IsSameObject(frontend, nullptr)) {
        env->CallVoidMethod(
                frontend,
                gFields.onFrontendEventID,
                (jint)frontendEventType);
    } else {
        ALOGE("FrontendClientCallbackImpl::onEvent:"
                "Frontend object has been freed. Ignoring callback.");
    }
}

void FrontendClientCallbackImpl::onScanMessage(
        FrontendScanMessageType type, const FrontendScanMessage& message) {
    ALOGD("FrontendClientCallbackImpl::onScanMessage, type=%d", type);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass clazz = env->FindClass("android/media/tv/tuner/Tuner");
    jobject frontend(env->NewLocalRef(mObject));
    if (env->IsSameObject(frontend, nullptr)) {
        ALOGE("FrontendClientCallbackImpl::onScanMessage:"
                "Frontend object has been freed. Ignoring callback.");
        return;
    }
    switch(type) {
        case FrontendScanMessageType::LOCKED: {
            if (message.isLocked()) {
                env->CallVoidMethod(
                        frontend,
                        env->GetMethodID(clazz, "onLocked", "()V"));
            }
            break;
        }
        case FrontendScanMessageType::END: {
            if (message.isEnd()) {
                env->CallVoidMethod(
                        frontend,
                        env->GetMethodID(clazz, "onScanStopped", "()V"));
            }
            break;
        }
        case FrontendScanMessageType::PROGRESS_PERCENT: {
            env->CallVoidMethod(
                    frontend,
                    env->GetMethodID(clazz, "onProgress", "(I)V"),
                    (jint) message.progressPercent());
            break;
        }
        case FrontendScanMessageType::FREQUENCY: {
            std::vector<uint32_t> v = message.frequencies();
            jintArray freqs = env->NewIntArray(v.size());
            env->SetIntArrayRegion(freqs, 0, v.size(), reinterpret_cast<jint*>(&v[0]));

            env->CallVoidMethod(
                    frontend,
                    env->GetMethodID(clazz, "onFrequenciesReport", "([I)V"),
                    freqs);
            break;
        }
        case FrontendScanMessageType::SYMBOL_RATE: {
            std::vector<uint32_t> v = message.symbolRates();
            jintArray symbolRates = env->NewIntArray(v.size());
            env->SetIntArrayRegion(symbolRates, 0, v.size(), reinterpret_cast<jint*>(&v[0]));

            env->CallVoidMethod(
                    frontend,
                    env->GetMethodID(clazz, "onSymbolRates", "([I)V"),
                    symbolRates);
            break;
        }
        case FrontendScanMessageType::HIERARCHY: {
            env->CallVoidMethod(
                    frontend,
                    env->GetMethodID(clazz, "onHierarchy", "(I)V"),
                    (jint) message.hierarchy());
            break;
        }
        case FrontendScanMessageType::ANALOG_TYPE: {
            env->CallVoidMethod(
                    frontend,
                    env->GetMethodID(clazz, "onSignalType", "(I)V"),
                    (jint) message.analogType());
            break;
        }
        case FrontendScanMessageType::PLP_IDS: {
            std::vector<uint8_t> v = message.plpIds();
            std::vector<jint> jintV(v.begin(), v.end());
            jintArray plpIds = env->NewIntArray(v.size());
            env->SetIntArrayRegion(plpIds, 0, jintV.size(), &jintV[0]);

            env->CallVoidMethod(
                    frontend,
                    env->GetMethodID(clazz, "onPlpIds", "([I)V"),
                    plpIds);
            break;
        }
        case FrontendScanMessageType::GROUP_IDS: {
            std::vector<uint8_t> v = message.groupIds();
            std::vector<jint> jintV(v.begin(), v.end());
            jintArray groupIds = env->NewIntArray(v.size());
            env->SetIntArrayRegion(groupIds, 0, jintV.size(), &jintV[0]);

            env->CallVoidMethod(
                    frontend,
                    env->GetMethodID(clazz, "onGroupIds", "([I)V"),
                    groupIds);
            break;
        }
        case FrontendScanMessageType::INPUT_STREAM_IDS: {
            std::vector<uint16_t> v = message.inputStreamIds();
            std::vector<jint> jintV(v.begin(), v.end());
            jintArray streamIds = env->NewIntArray(v.size());
            env->SetIntArrayRegion(streamIds, 0, jintV.size(), &jintV[0]);

            env->CallVoidMethod(
                    frontend,
                    env->GetMethodID(clazz, "onInputStreamIds", "([I)V"),
                    streamIds);
            break;
        }
        case FrontendScanMessageType::STANDARD: {
            FrontendScanMessage::Standard std = message.std();
            jint standard;
            if (std.getDiscriminator() == FrontendScanMessage::Standard::hidl_discriminator::sStd) {
                standard = (jint) std.sStd();
                env->CallVoidMethod(
                        frontend,
                        env->GetMethodID(clazz, "onDvbsStandard", "(I)V"),
                        standard);
            } else if (std.getDiscriminator() ==
                    FrontendScanMessage::Standard::hidl_discriminator::tStd) {
                standard = (jint) std.tStd();
                env->CallVoidMethod(
                        frontend,
                        env->GetMethodID(clazz, "onDvbtStandard", "(I)V"),
                        standard);
            } else if (std.getDiscriminator() ==
                    FrontendScanMessage::Standard::hidl_discriminator::sifStd) {
                standard = (jint) std.sifStd();
                env->CallVoidMethod(
                        frontend,
                        env->GetMethodID(clazz, "onAnalogSifStandard", "(I)V"),
                        standard);
            }
            break;
        }
        case FrontendScanMessageType::ATSC3_PLP_INFO: {
            jclass plpClazz = env->FindClass("android/media/tv/tuner/frontend/Atsc3PlpInfo");
            jmethodID init = env->GetMethodID(plpClazz, "<init>", "(IZ)V");
            std::vector<FrontendScanAtsc3PlpInfo> plpInfos = message.atsc3PlpInfos();
            jobjectArray array = env->NewObjectArray(plpInfos.size(), plpClazz, NULL);

            for (int i = 0; i < plpInfos.size(); i++) {
                auto info = plpInfos[i];
                jint plpId = (jint) info.plpId;
                jboolean lls = (jboolean) info.bLlsFlag;

                jobject obj = env->NewObject(plpClazz, init, plpId, lls);
                env->SetObjectArrayElement(array, i, obj);
            }
            env->CallVoidMethod(
                    frontend,
                    env->GetMethodID(clazz, "onAtsc3PlpInfos",
                            "([Landroid/media/tv/tuner/frontend/Atsc3PlpInfo;)V"),
                    array);
            break;
        }
    }
}

void FrontendClientCallbackImpl::onScanMessageExt1_1(FrontendScanMessageTypeExt1_1 type,
        const FrontendScanMessageExt1_1& message) {
    ALOGD("FrontendClientCallbackImpl::onScanMessageExt1_1, type=%d", type);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass clazz = env->FindClass("android/media/tv/tuner/Tuner");
    jobject frontend(env->NewLocalRef(mObject));
    if (env->IsSameObject(frontend, nullptr)) {
        ALOGE("FrontendClientCallbackImpl::onScanMessageExt1_1:"
                "Frontend object has been freed. Ignoring callback.");
        return;
    }
    switch(type) {
        case FrontendScanMessageTypeExt1_1::MODULATION: {
            jint modulation = -1;
            switch (message.modulation().getDiscriminator()) {
                case FrontendModulation::hidl_discriminator::dvbc: {
                    modulation = (jint) message.modulation().dvbc();
                    break;
                }
                case FrontendModulation::hidl_discriminator::dvbt: {
                    modulation = (jint) message.modulation().dvbt();
                    break;
                }
                case FrontendModulation::hidl_discriminator::dvbs: {
                    modulation = (jint) message.modulation().dvbs();
                    break;
                }
                case FrontendModulation::hidl_discriminator::isdbs: {
                    modulation = (jint) message.modulation().isdbs();
                    break;
                }
                case FrontendModulation::hidl_discriminator::isdbs3: {
                    modulation = (jint) message.modulation().isdbs3();
                    break;
                }
                case FrontendModulation::hidl_discriminator::isdbt: {
                    modulation = (jint) message.modulation().isdbt();
                    break;
                }
                case FrontendModulation::hidl_discriminator::atsc: {
                    modulation = (jint) message.modulation().atsc();
                    break;
                }
                case FrontendModulation::hidl_discriminator::atsc3: {
                    modulation = (jint) message.modulation().atsc3();
                    break;
                }
                case FrontendModulation::hidl_discriminator::dtmb: {
                    modulation = (jint) message.modulation().dtmb();
                    break;
                }
                default: {
                    break;
                }
            }
            if (modulation > 0) {
                env->CallVoidMethod(
                        frontend,
                        env->GetMethodID(clazz, "onModulationReported", "(I)V"),
                        modulation);
            }
            break;
        }
        case FrontendScanMessageTypeExt1_1::HIGH_PRIORITY: {
            bool isHighPriority = message.isHighPriority();
            env->CallVoidMethod(
                    frontend,
                    env->GetMethodID(clazz, "onPriorityReported", "(Z)V"),
                    isHighPriority);
            break;
        }
        case FrontendScanMessageTypeExt1_1::DVBC_ANNEX: {
            jint dvbcAnnex = (jint) message.annex();
            env->CallVoidMethod(
                    frontend,
                    env->GetMethodID(clazz, "onDvbcAnnexReported", "(I)V"),
                    dvbcAnnex);
            break;
        }
        default:
            break;
    }
}

FrontendClientCallbackImpl::~FrontendClientCallbackImpl() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (mObject != NULL) {
        env->DeleteWeakGlobalRef(mObject);
        mObject = NULL;
    }
}

/////////////// Tuner ///////////////////////

sp<TunerClient> JTuner::mTunerClient;

JTuner::JTuner(JNIEnv *env, jobject thiz)
    : mClass(NULL) {
    jclass clazz = env->GetObjectClass(thiz);
    CHECK(clazz != NULL);

    mClass = (jclass)env->NewGlobalRef(clazz);
    mObject = env->NewWeakGlobalRef(thiz);
    if (mTunerClient == NULL) {
        mTunerClient = new TunerClient();
    }

    mSharedFeId = (int) Constant::INVALID_FRONTEND_ID;
}

JTuner::~JTuner() {
    if (mFeClient != NULL) {
        mFeClient->close();
    }
    if (mDemuxClient != NULL) {
        mDemuxClient->close();
    }
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteWeakGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
    mTunerClient = NULL;
    mFeClient = NULL;
    mDemuxClient = NULL;
    mClass = NULL;
    mObject = NULL;
}

jint JTuner::getTunerVersion() {
    ALOGD("JTuner::getTunerVersion()");
    return (jint) mTunerClient->getHalTunerVersion();
}

jobject JTuner::getFrontendIds() {
    ALOGD("JTuner::getFrontendIds()");
    vector<FrontendId> ids = mTunerClient->getFrontendIds();
    if (ids.size() == 0) {
        ALOGW("Frontend isn't available");
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass arrayListClazz = env->FindClass("java/util/ArrayList");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClazz, "add", "(Ljava/lang/Object;)Z");
    jobject obj = env->NewObject(arrayListClazz, env->GetMethodID(arrayListClazz, "<init>", "()V"));

    jclass integerClazz = env->FindClass("java/lang/Integer");
    jmethodID intInit = env->GetMethodID(integerClazz, "<init>", "(I)V");

    for (int i=0; i < ids.size(); i++) {
       jobject idObj = env->NewObject(integerClazz, intInit, ids[i]);
       env->CallBooleanMethod(obj, arrayListAdd, idObj);
    }
    return obj;
}

jobject JTuner::openFrontendByHandle(int feHandle) {
    // TODO: Handle reopening frontend with different handle
    sp<FrontendClient> feClient = mTunerClient->openFrontend(feHandle);
    if (feClient == NULL) {
        ALOGE("Failed to open frontend");
        return NULL;
    }
    mFeClient = feClient;

    mFeId = mFeClient->getId();
    if (mDemuxClient != NULL) {
        mDemuxClient->setFrontendDataSource(mFeClient);
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject tuner(env->NewLocalRef(mObject));
    if (env->IsSameObject(tuner, nullptr)) {
        ALOGE("openFrontendByHandle"
                "Tuner object has been freed. Failed to open frontend.");
        return NULL;
    }

    sp<FrontendClientCallbackImpl> feClientCb = new FrontendClientCallbackImpl(mObject);
    mFeClient->setCallback(feClientCb);
    // TODO: add more fields to frontend
    return env->NewObject(
            env->FindClass("android/media/tv/tuner/Tuner$Frontend"),
            gFields.frontendInitID,
            tuner,
            (jint) mFeId);
}

int JTuner::shareFrontend(int feId) {
    if (mFeClient != NULL) {
        ALOGE("Cannot share frontend:%d because this session is already holding %d",
              feId, mFeClient->getId());
        return (int)Result::INVALID_STATE;
    }

    mSharedFeId = feId;
    return (int)Result::SUCCESS;
}

jobject JTuner::getAnalogFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/AnalogFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(II)V");

    jint typeCap = caps.analogCaps().typeCap;
    jint sifStandardCap = caps.analogCaps().sifStandardCap;
    return env->NewObject(clazz, capsInit, typeCap, sifStandardCap);
}

jobject JTuner::getAtsc3FrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/Atsc3FrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IIIIII)V");

    jint bandwidthCap = caps.atsc3Caps().bandwidthCap;
    jint modulationCap = caps.atsc3Caps().modulationCap;
    jint timeInterleaveModeCap = caps.atsc3Caps().timeInterleaveModeCap;
    jint codeRateCap = caps.atsc3Caps().codeRateCap;
    jint fecCap = caps.atsc3Caps().fecCap;
    jint demodOutputFormatCap = caps.atsc3Caps().demodOutputFormatCap;

    return env->NewObject(clazz, capsInit, bandwidthCap, modulationCap, timeInterleaveModeCap,
            codeRateCap, fecCap, demodOutputFormatCap);
}

jobject JTuner::getAtscFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/AtscFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(I)V");

    jint modulationCap = caps.atscCaps().modulationCap;

    return env->NewObject(clazz, capsInit, modulationCap);
}

jobject JTuner::getDvbcFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbcFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IJI)V");

    jint modulationCap = caps.dvbcCaps().modulationCap;
    jlong fecCap = caps.dvbcCaps().fecCap;
    jint annexCap = caps.dvbcCaps().annexCap;

    return env->NewObject(clazz, capsInit, modulationCap, fecCap, annexCap);
}

jobject JTuner::getDvbsFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbsFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IJI)V");

    jint modulationCap = caps.dvbsCaps().modulationCap;
    jlong innerfecCap = caps.dvbsCaps().innerfecCap;
    jint standard = caps.dvbsCaps().standard;

    return env->NewObject(clazz, capsInit, modulationCap, innerfecCap, standard);
}

jobject JTuner::getDvbtFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbtFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IIIIIIZZ)V");

    jint transmissionModeCap = caps.dvbtCaps().transmissionModeCap;
    jint bandwidthCap = caps.dvbtCaps().bandwidthCap;
    jint constellationCap = caps.dvbtCaps().constellationCap;
    jint coderateCap = caps.dvbtCaps().coderateCap;
    jint hierarchyCap = caps.dvbtCaps().hierarchyCap;
    jint guardIntervalCap = caps.dvbtCaps().guardIntervalCap;
    jboolean isT2Supported = caps.dvbtCaps().isT2Supported;
    jboolean isMisoSupported = caps.dvbtCaps().isMisoSupported;

    return env->NewObject(clazz, capsInit, transmissionModeCap, bandwidthCap, constellationCap,
            coderateCap, hierarchyCap, guardIntervalCap, isT2Supported, isMisoSupported);
}

jobject JTuner::getIsdbs3FrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/Isdbs3FrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(II)V");

    jint modulationCap = caps.isdbs3Caps().modulationCap;
    jint coderateCap = caps.isdbs3Caps().coderateCap;

    return env->NewObject(clazz, capsInit, modulationCap, coderateCap);
}

jobject JTuner::getIsdbsFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/IsdbsFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(II)V");

    jint modulationCap = caps.isdbsCaps().modulationCap;
    jint coderateCap = caps.isdbsCaps().coderateCap;

    return env->NewObject(clazz, capsInit, modulationCap, coderateCap);
}

jobject JTuner::getIsdbtFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/IsdbtFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IIIII)V");

    jint modeCap = caps.isdbtCaps().modeCap;
    jint bandwidthCap = caps.isdbtCaps().bandwidthCap;
    jint modulationCap = caps.isdbtCaps().modulationCap;
    jint coderateCap = caps.isdbtCaps().coderateCap;
    jint guardIntervalCap = caps.isdbtCaps().guardIntervalCap;

    return env->NewObject(clazz, capsInit, modeCap, bandwidthCap, modulationCap, coderateCap,
            guardIntervalCap);
}

jobject JTuner::getDtmbFrontendCaps(JNIEnv *env, int id) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DtmbFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IIIIII)V");

    shared_ptr<FrontendDtmbCapabilities> dtmbCaps = mTunerClient->getFrontendDtmbCapabilities(id);
    if (dtmbCaps == NULL) {
        return NULL;
    }

    jint modulationCap = dtmbCaps->modulationCap;
    jint transmissionModeCap = dtmbCaps->transmissionModeCap;
    jint guardIntervalCap = dtmbCaps->guardIntervalCap;
    jint interleaveModeCap = dtmbCaps->interleaveModeCap;
    jint codeRateCap = dtmbCaps->codeRateCap;
    jint bandwidthCap = dtmbCaps->bandwidthCap;

    return env->NewObject(clazz, capsInit, modulationCap, transmissionModeCap, guardIntervalCap,
            interleaveModeCap, codeRateCap, bandwidthCap);
}

jobject JTuner::getFrontendInfo(int id) {
    shared_ptr<FrontendInfo> feInfo;
    feInfo = mTunerClient->getFrontendInfo(id);
    if (feInfo == NULL) {
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/FrontendInfo");
    jmethodID infoInit = env->GetMethodID(clazz, "<init>",
            "(IIIIIIII[ILandroid/media/tv/tuner/frontend/FrontendCapabilities;)V");

    jint type = (jint) feInfo->type;
    jint minFrequency = feInfo->minFrequency;
    jint maxFrequency = feInfo->maxFrequency;
    jint minSymbolRate = feInfo->minSymbolRate;
    jint maxSymbolRate = feInfo->maxSymbolRate;
    jint acquireRange = feInfo->acquireRange;
    jint exclusiveGroupId = feInfo->exclusiveGroupId;
    jintArray statusCaps = env->NewIntArray(feInfo->statusCaps.size());
    env->SetIntArrayRegion(
            statusCaps, 0, feInfo->statusCaps.size(),
            reinterpret_cast<jint*>(&feInfo->statusCaps[0]));
    FrontendInfo::FrontendCapabilities caps = feInfo->frontendCaps;

    jobject jcaps = NULL;

    if (feInfo->type == static_cast<FrontendType>(
            ::android::hardware::tv::tuner::V1_1::FrontendType::DTMB)) {
        jcaps = getDtmbFrontendCaps(env, id);
    }

    switch(feInfo->type) {
        case FrontendType::ANALOG:
            if (FrontendInfo::FrontendCapabilities::hidl_discriminator::analogCaps
                    == caps.getDiscriminator()) {
                jcaps = getAnalogFrontendCaps(env, caps);
            }
            break;
        case FrontendType::ATSC3:
            if (FrontendInfo::FrontendCapabilities::hidl_discriminator::atsc3Caps
                    == caps.getDiscriminator()) {
                jcaps = getAtsc3FrontendCaps(env, caps);
            }
            break;
        case FrontendType::ATSC:
            if (FrontendInfo::FrontendCapabilities::hidl_discriminator::atscCaps
                    == caps.getDiscriminator()) {
                jcaps = getAtscFrontendCaps(env, caps);
            }
            break;
        case FrontendType::DVBC:
            if (FrontendInfo::FrontendCapabilities::hidl_discriminator::dvbcCaps
                    == caps.getDiscriminator()) {
                jcaps = getDvbcFrontendCaps(env, caps);
            }
            break;
        case FrontendType::DVBS:
            if (FrontendInfo::FrontendCapabilities::hidl_discriminator::dvbsCaps
                    == caps.getDiscriminator()) {
                jcaps = getDvbsFrontendCaps(env, caps);
            }
            break;
        case FrontendType::DVBT:
            if (FrontendInfo::FrontendCapabilities::hidl_discriminator::dvbtCaps
                    == caps.getDiscriminator()) {
                jcaps = getDvbtFrontendCaps(env, caps);
            }
            break;
        case FrontendType::ISDBS:
            if (FrontendInfo::FrontendCapabilities::hidl_discriminator::isdbsCaps
                    == caps.getDiscriminator()) {
                jcaps = getIsdbsFrontendCaps(env, caps);
            }
            break;
        case FrontendType::ISDBS3:
            if (FrontendInfo::FrontendCapabilities::hidl_discriminator::isdbs3Caps
                    == caps.getDiscriminator()) {
                jcaps = getIsdbs3FrontendCaps(env, caps);
            }
            break;
        case FrontendType::ISDBT:
            if (FrontendInfo::FrontendCapabilities::hidl_discriminator::isdbtCaps
                    == caps.getDiscriminator()) {
                jcaps = getIsdbtFrontendCaps(env, caps);
            }
            break;
        default:
            break;
    }

    return env->NewObject(
            clazz, infoInit, (jint) id, type, minFrequency, maxFrequency, minSymbolRate,
            maxSymbolRate, acquireRange, exclusiveGroupId, statusCaps, jcaps);
}

jobject JTuner::openLnbByHandle(int handle) {
    if (mTunerClient == NULL) {
        return NULL;
    }

    sp<LnbClient> lnbClient;
    sp<LnbClientCallbackImpl> callback = new LnbClientCallbackImpl();
    lnbClient = mTunerClient->openLnb(handle);
    if (lnbClient == NULL) {
        ALOGD("Failed to open lnb, handle = %d", handle);
        return NULL;
    }

    if (lnbClient->setCallback(callback) != Result::SUCCESS) {
        ALOGD("Failed to set lnb callback");
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject lnbObj = env->NewObject(
            env->FindClass("android/media/tv/tuner/Lnb"),
            gFields.lnbInitID);

    lnbClient->incStrong(lnbObj);
    env->SetLongField(lnbObj, gFields.lnbContext, (jlong)lnbClient.get());
    callback->setLnb(env->NewWeakGlobalRef(lnbObj));

    return lnbObj;
}

jobject JTuner::openLnbByName(jstring name) {
    if (mTunerClient == NULL) {
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    std::string lnbName(env->GetStringUTFChars(name, nullptr));
    sp<LnbClient> lnbClient;
    sp<LnbClientCallbackImpl> callback = new LnbClientCallbackImpl();
    lnbClient = mTunerClient->openLnbByName(lnbName);
    if (lnbClient == NULL) {
        ALOGD("Failed to open lnb by name, name = %s", lnbName.c_str());
        return NULL;
    }

    if (lnbClient->setCallback(callback) != Result::SUCCESS) {
        ALOGD("Failed to set lnb callback");
        return NULL;
    }

    jobject lnbObj = env->NewObject(
            env->FindClass("android/media/tv/tuner/Lnb"),
            gFields.lnbInitID);

    lnbClient->incStrong(lnbObj);
    env->SetLongField(lnbObj, gFields.lnbContext, (jlong)lnbClient.get());
    callback->setLnb(env->NewWeakGlobalRef(lnbObj));

    return lnbObj;
}

int JTuner::tune(const FrontendSettings& settings, const FrontendSettingsExt1_1& settingsExt1_1) {
    if (mFeClient == nullptr) {
        ALOGE("frontend is not initialized");
        return (int)Result::INVALID_STATE;
    }
    return (int) mFeClient->tune(settings, settingsExt1_1);
}

int JTuner::stopTune() {
    if (mFeClient == nullptr) {
        ALOGE("frontend is not initialized");
        return (int)Result::INVALID_STATE;
    }
    return (int) mFeClient->stopTune();
}

int JTuner::scan(const FrontendSettings& settings, FrontendScanType scanType,
        const FrontendSettingsExt1_1& settingsExt1_1) {
    if (mFeClient == NULL) {
        ALOGE("frontend client is not initialized");
        return (int)Result::INVALID_STATE;
    }
    Result result = mFeClient->scan(settings, scanType, settingsExt1_1);
    return (int)result;
}

int JTuner::stopScan() {
    if (mFeClient == NULL) {
        ALOGE("frontend client is not initialized");
        return (int)Result::INVALID_STATE;
    }
    Result result = mFeClient->stopScan();
    return (int)result;
}

int JTuner::setLnb(sp<LnbClient> lnbClient) {
    if (mFeClient == NULL) {
        ALOGE("frontend client is not initialized");
        return (int)Result::INVALID_STATE;
    }
    if (lnbClient == NULL) {
        ALOGE("lnb is not initialized");
        return (int)Result::INVALID_STATE;
    }
    Result result = mFeClient->setLnb(lnbClient);
    return (int)result;
}

int JTuner::setLna(bool enable) {
    if (mFeClient == NULL) {
        ALOGE("frontend client is not initialized");
        return (int)Result::INVALID_STATE;
    }
    Result result = mFeClient->setLna(enable);
    return (int)result;
}

Result JTuner::openDemux(int handle) {
    if (mTunerClient == nullptr) {
        return Result::NOT_INITIALIZED;
    }

    if (mDemuxClient == nullptr) {
        mDemuxClient = mTunerClient->openDemux(handle);
        if (mDemuxClient == NULL) {
            ALOGE("Failed to open demux");
            return Result::UNKNOWN_ERROR;
        }
        if (mFeClient != NULL) {
            return mDemuxClient->setFrontendDataSource(mFeClient);
        } else if (mSharedFeId != (int) Constant::INVALID_FRONTEND_ID) {
            return mDemuxClient->setFrontendDataSourceById(mSharedFeId);
        }
    }

    return Result::SUCCESS;
}

jint JTuner::close() {
    Result res = Result::SUCCESS;

    if (mFeClient != NULL) {
        res = mFeClient->close();
        if (res != Result::SUCCESS) {
            return (jint) res;
        }
        mFeClient = NULL;
    }
    if (mDemuxClient != NULL) {
        res = mDemuxClient->close();
        if (res != Result::SUCCESS) {
            return (jint) res;
        }
        mDemuxClient = NULL;
    }

    mSharedFeId = (int) Constant::INVALID_FRONTEND_ID;
    return (jint) res;
}

jobject JTuner::getAvSyncHwId(sp<FilterClient> filterClient) {
    if (mDemuxClient == NULL) {
        return NULL;
    }

    int avSyncHwId = mDemuxClient->getAvSyncHwId(filterClient);
    if (avSyncHwId >= 0) {
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        jclass integerClazz = env->FindClass("java/lang/Integer");
        jmethodID intInit = env->GetMethodID(integerClazz, "<init>", "(I)V");
        return env->NewObject(integerClazz, intInit, avSyncHwId);
    }
    return NULL;
}

jobject JTuner::getAvSyncTime(jint id) {
    if (mDemuxClient == NULL) {
        return NULL;
    }
    long time = mDemuxClient->getAvSyncTime((int)id);
    if (time >= 0) {
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        jclass longClazz = env->FindClass("java/lang/Long");
        jmethodID longInit = env->GetMethodID(longClazz, "<init>", "(J)V");
        return env->NewObject(longClazz, longInit, static_cast<jlong>(time));
    }
    return NULL;
}

int JTuner::connectCiCam(jint id) {
    if (mDemuxClient == NULL) {
        return (int)Result::NOT_INITIALIZED;
    }
    Result r = mDemuxClient->connectCiCam((int)id);
    return (int) r;
}

int JTuner::linkCiCam(int id) {
    if (mFeClient == NULL) {
        ALOGE("frontend client is not initialized");
        return (int)Constant::INVALID_LTS_ID;
    }
    return mFeClient->linkCiCamToFrontend(id);
}

int JTuner::disconnectCiCam() {
    if (mDemuxClient == NULL) {
        return (int)Result::NOT_INITIALIZED;
    }
    Result r = mDemuxClient->disconnectCiCam();
    return (int) r;
}


int JTuner::unlinkCiCam(int id) {
    if (mFeClient == NULL) {
        ALOGE("frontend client is not initialized");
        return (int)Result::INVALID_STATE;
    }

    Result r = mFeClient->unlinkCiCamToFrontend(id);

    return (int) r;
}

jobject JTuner::openDescrambler() {
    ALOGD("JTuner::openDescrambler");
    if (mTunerClient == nullptr || mDemuxClient == nullptr) {
        return NULL;
    }
    sp<DescramblerClient> descramblerClient = mTunerClient->openDescrambler(0/*unused*/);

    if (descramblerClient == NULL) {
        ALOGD("Failed to open descrambler");
        return NULL;
    }

    descramblerClient->setDemuxSource(mDemuxClient);

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject descramblerObj =
            env->NewObject(
                    env->FindClass("android/media/tv/tuner/Descrambler"),
                    gFields.descramblerInitID);

    descramblerClient->incStrong(descramblerObj);
    env->SetLongField(descramblerObj, gFields.descramblerContext, (jlong)descramblerClient.get());

    return descramblerObj;
}

jobject JTuner::openFilter(DemuxFilterType type, int bufferSize) {
    if (mDemuxClient == NULL) {
        return NULL;
    }

    sp<FilterClient> filterClient;
    sp<FilterClientCallbackImpl> callback = new FilterClientCallbackImpl();
    filterClient = mDemuxClient->openFilter(type, bufferSize, callback);
    if (filterClient == NULL) {
        ALOGD("Failed to open filter, type = %d", type.mainType);
        return NULL;
    }
    uint64_t fId;
    Result res = filterClient->getId64Bit(fId);
    if (res != Result::SUCCESS) {
        uint32_t id;
        filterClient->getId(id);
        fId = static_cast<uint64_t>(id);
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject filterObj =
            env->NewObject(
                    env->FindClass("android/media/tv/tuner/filter/Filter"),
                    gFields.filterInitID,
                    (jlong) fId);

    filterClient->incStrong(filterObj);
    env->SetLongField(filterObj, gFields.filterContext, (jlong)filterClient.get());
    callback->setFilter(env->NewWeakGlobalRef(filterObj), filterClient);

    return filterObj;
}

jobject JTuner::openTimeFilter() {
    if (mDemuxClient == NULL) {
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject timeFilterObj =
            env->NewObject(
                    env->FindClass("android/media/tv/tuner/filter/TimeFilter"),
                    gFields.timeFilterInitID);
    sp<TimeFilterClient> timeFilterClient = mDemuxClient->openTimeFilter();
    if (timeFilterClient == NULL) {
        ALOGD("Failed to open time filter.");
        return NULL;
    }
    timeFilterClient->incStrong(timeFilterObj);
    env->SetLongField(timeFilterObj, gFields.timeFilterContext, (jlong)timeFilterClient.get());

    return timeFilterObj;
}

jobject JTuner::openDvr(DvrType type, jlong bufferSize) {
    ALOGD("JTuner::openDvr");
    if (mDemuxClient == NULL) {
        return NULL;
    }
    sp<DvrClient> dvrClient;
    sp<DvrClientCallbackImpl> callback = new DvrClientCallbackImpl();
    dvrClient = mDemuxClient->openDvr(type, (int) bufferSize, callback);

    if (dvrClient == NULL) {
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject dvrObj;
    if (type == DvrType::RECORD) {
        dvrObj =
                env->NewObject(
                        env->FindClass("android/media/tv/tuner/dvr/DvrRecorder"),
                        gFields.dvrRecorderInitID);
        dvrClient->incStrong(dvrObj);
        env->SetLongField(dvrObj, gFields.dvrRecorderContext, (jlong)dvrClient.get());
    } else {
        dvrObj =
                env->NewObject(
                        env->FindClass("android/media/tv/tuner/dvr/DvrPlayback"),
                        gFields.dvrPlaybackInitID);
        dvrClient->incStrong(dvrObj);
        env->SetLongField(dvrObj, gFields.dvrPlaybackContext, (jlong)dvrClient.get());
    }

    callback->setDvr(env->NewWeakGlobalRef(dvrObj));

    return dvrObj;
}

jobject JTuner::getDemuxCaps() {
    if (mTunerClient == NULL) {
        return NULL;
    }

    shared_ptr<DemuxCapabilities> caps;
    caps = mTunerClient->getDemuxCaps();
    if (caps == NULL) {
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass clazz = env->FindClass("android/media/tv/tuner/DemuxCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IIIIIIIIIJI[IZ)V");

    jint numDemux = caps->numDemux;
    jint numRecord = caps->numRecord;
    jint numPlayback = caps->numPlayback;
    jint numTsFilter = caps->numTsFilter;
    jint numSectionFilter = caps->numSectionFilter;
    jint numAudioFilter = caps->numAudioFilter;
    jint numVideoFilter = caps->numVideoFilter;
    jint numPesFilter = caps->numPesFilter;
    jint numPcrFilter = caps->numPcrFilter;
    jlong numBytesInSectionFilter = caps->numBytesInSectionFilter;
    jint filterCaps = static_cast<jint>(caps->filterCaps);
    jboolean bTimeFilter = caps->bTimeFilter;

    jintArray linkCaps = env->NewIntArray(caps->linkCaps.size());
    env->SetIntArrayRegion(
            linkCaps, 0, caps->linkCaps.size(), reinterpret_cast<jint*>(&caps->linkCaps[0]));

    return env->NewObject(clazz, capsInit, numDemux, numRecord, numPlayback, numTsFilter,
            numSectionFilter, numAudioFilter, numVideoFilter, numPesFilter, numPcrFilter,
            numBytesInSectionFilter, filterCaps, linkCaps, bTimeFilter);
}

jobject JTuner::getFrontendStatus(jintArray types) {
    if (mFeClient == NULL) {
        return NULL;
    }
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jsize size = env->GetArrayLength(types);
    jint intTypes[size];
    env->GetIntArrayRegion(types, 0, size, intTypes);
    std::vector<FrontendStatusType> v;
    std::vector<FrontendStatusTypeExt1_1> v_1_1;
    for (int i = 0; i < size; i++) {
        if (isV1_1ExtendedStatusType(intTypes[i])) {
            v_1_1.push_back(static_cast<FrontendStatusTypeExt1_1>(intTypes[i]));
        } else {
            v.push_back(static_cast<FrontendStatusType>(intTypes[i]));
        }
    }

    hidl_vec<FrontendStatus> status = mFeClient->getStatus(v);
    hidl_vec<FrontendStatusExt1_1> status_1_1 = mFeClient->getStatusExtended_1_1(v_1_1);

    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/FrontendStatus");
    jmethodID init = env->GetMethodID(clazz, "<init>", "()V");
    jobject statusObj = env->NewObject(clazz, init);

    jclass intClazz = env->FindClass("java/lang/Integer");
    jmethodID initInt = env->GetMethodID(intClazz, "<init>", "(I)V");
    jclass booleanClazz = env->FindClass("java/lang/Boolean");
    jmethodID initBoolean = env->GetMethodID(booleanClazz, "<init>", "(Z)V");

    for (auto s : status) {
        switch(s.getDiscriminator()) {
            case FrontendStatus::hidl_discriminator::isDemodLocked: {
                jfieldID field = env->GetFieldID(clazz, "mIsDemodLocked", "Ljava/lang/Boolean;");
                jobject newBooleanObj = env->NewObject(
                        booleanClazz, initBoolean, static_cast<jboolean>(s.isDemodLocked()));
                env->SetObjectField(statusObj, field, newBooleanObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::snr: {
                jfieldID field = env->GetFieldID(clazz, "mSnr", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.snr()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::ber: {
                jfieldID field = env->GetFieldID(clazz, "mBer", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.ber()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::per: {
                jfieldID field = env->GetFieldID(clazz, "mPer", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.per()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::preBer: {
                jfieldID field = env->GetFieldID(clazz, "mPerBer", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.preBer()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::signalQuality: {
                jfieldID field = env->GetFieldID(clazz, "mSignalQuality", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.signalQuality()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::signalStrength: {
                jfieldID field = env->GetFieldID(clazz, "mSignalStrength", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.signalStrength()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::symbolRate: {
                jfieldID field = env->GetFieldID(clazz, "mSymbolRate", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.symbolRate()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::innerFec: {
                jfieldID field = env->GetFieldID(clazz, "mInnerFec", "Ljava/lang/Long;");
                jclass longClazz = env->FindClass("java/lang/Long");
                jmethodID initLong = env->GetMethodID(longClazz, "<init>", "(J)V");
                jobject newLongObj = env->NewObject(
                        longClazz, initLong, static_cast<jlong>(s.innerFec()));
                env->SetObjectField(statusObj, field, newLongObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::modulation: {
                jfieldID field = env->GetFieldID(clazz, "mModulation", "Ljava/lang/Integer;");
                FrontendModulationStatus modulation = s.modulation();
                jint intModulation;
                bool valid = true;
                switch(modulation.getDiscriminator()) {
                    case FrontendModulationStatus::hidl_discriminator::dvbc: {
                        intModulation = static_cast<jint>(modulation.dvbc());
                        break;
                    }
                    case FrontendModulationStatus::hidl_discriminator::dvbs: {
                        intModulation = static_cast<jint>(modulation.dvbs());
                        break;
                    }
                    case FrontendModulationStatus::hidl_discriminator::isdbs: {
                        intModulation = static_cast<jint>(modulation.isdbs());
                        break;
                    }
                    case FrontendModulationStatus::hidl_discriminator::isdbs3: {
                        intModulation = static_cast<jint>(modulation.isdbs3());
                        break;
                    }
                    case FrontendModulationStatus::hidl_discriminator::isdbt: {
                        intModulation = static_cast<jint>(modulation.isdbt());
                        break;
                    }
                    default: {
                        valid = false;
                        break;
                    }
                }
                if (valid) {
                    jobject newIntegerObj = env->NewObject(intClazz, initInt, intModulation);
                    env->SetObjectField(statusObj, field, newIntegerObj);
                }
                break;
            }
            case FrontendStatus::hidl_discriminator::inversion: {
                jfieldID field = env->GetFieldID(clazz, "mInversion", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.inversion()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::lnbVoltage: {
                jfieldID field = env->GetFieldID(clazz, "mLnbVoltage", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.lnbVoltage()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::plpId: {
                jfieldID field = env->GetFieldID(clazz, "mPlpId", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.plpId()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::isEWBS: {
                jfieldID field = env->GetFieldID(clazz, "mIsEwbs", "Ljava/lang/Boolean;");
                jobject newBooleanObj = env->NewObject(
                        booleanClazz, initBoolean, static_cast<jboolean>(s.isEWBS()));
                env->SetObjectField(statusObj, field, newBooleanObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::agc: {
                jfieldID field = env->GetFieldID(clazz, "mAgc", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.agc()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::isLnaOn: {
                jfieldID field = env->GetFieldID(clazz, "mIsLnaOn", "Ljava/lang/Boolean;");
                jobject newBooleanObj = env->NewObject(
                        booleanClazz, initBoolean, static_cast<jboolean>(s.isLnaOn()));
                env->SetObjectField(statusObj, field, newBooleanObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::isLayerError: {
                jfieldID field = env->GetFieldID(clazz, "mIsLayerErrors", "[Z");
                hidl_vec<bool> layerErr = s.isLayerError();

                jbooleanArray valObj = env->NewBooleanArray(layerErr.size());

                for (size_t i = 0; i < layerErr.size(); i++) {
                    jboolean x = layerErr[i];
                    env->SetBooleanArrayRegion(valObj, i, 1, &x);
                }
                env->SetObjectField(statusObj, field, valObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::mer: {
                jfieldID field = env->GetFieldID(clazz, "mMer", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.mer()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::freqOffset: {
                jfieldID field = env->GetFieldID(clazz, "mFreqOffset", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.freqOffset()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::hierarchy: {
                jfieldID field = env->GetFieldID(clazz, "mHierarchy", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.hierarchy()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::isRfLocked: {
                jfieldID field = env->GetFieldID(clazz, "mIsRfLocked", "Ljava/lang/Boolean;");
                jobject newBooleanObj = env->NewObject(
                        booleanClazz, initBoolean, static_cast<jboolean>(s.isRfLocked()));
                env->SetObjectField(statusObj, field, newBooleanObj);
                break;
            }
            case FrontendStatus::hidl_discriminator::plpInfo: {
                jfieldID field = env->GetFieldID(clazz, "mPlpInfo",
                        "[Landroid/media/tv/tuner/frontend/FrontendStatus$Atsc3PlpTuningInfo;");
                jclass plpClazz = env->FindClass(
                        "android/media/tv/tuner/frontend/FrontendStatus$Atsc3PlpTuningInfo");
                jmethodID initPlp = env->GetMethodID(plpClazz, "<init>", "(IZI)V");

                hidl_vec<FrontendStatusAtsc3PlpInfo> plpInfos = s.plpInfo();

                jobjectArray valObj = env->NewObjectArray(plpInfos.size(), plpClazz, NULL);
                for (int i = 0; i < plpInfos.size(); i++) {
                    auto info = plpInfos[i];
                    jint plpId = (jint) info.plpId;
                    jboolean isLocked = (jboolean) info.isLocked;
                    jint uec = (jint) info.uec;

                    jobject plpObj = env->NewObject(plpClazz, initPlp, plpId, isLocked, uec);
                    env->SetObjectArrayElement(valObj, i, plpObj);
                }

                env->SetObjectField(statusObj, field, valObj);
                break;
            }
            default: {
                break;
            }
        }
    }

    for (auto s : status_1_1) {
        switch(s.getDiscriminator()) {
            case FrontendStatusExt1_1::hidl_discriminator::modulations: {
                jfieldID field = env->GetFieldID(clazz, "mModulationsExt", "[I");
                std::vector<FrontendModulation> v = s.modulations();

                jintArray valObj = env->NewIntArray(v.size());
                bool valid = false;
                jint m[1];
                for (int i = 0; i < v.size(); i++) {
                    auto modulation = v[i];
                    switch(modulation.getDiscriminator()) {
                        case FrontendModulation::hidl_discriminator::dvbc: {
                            m[0] = static_cast<jint>(modulation.dvbc());
                            env->SetIntArrayRegion(valObj, i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::hidl_discriminator::dvbs: {
                            m[0] = static_cast<jint>(modulation.dvbs());
                            env->SetIntArrayRegion(valObj, i, 1, m);
                            valid = true;
                           break;
                        }
                        case FrontendModulation::hidl_discriminator::dvbt: {
                            m[0] = static_cast<jint>(modulation.dvbt());
                            env->SetIntArrayRegion(valObj, i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::hidl_discriminator::isdbs: {
                            m[0] = static_cast<jint>(modulation.isdbs());
                            env->SetIntArrayRegion(valObj, i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::hidl_discriminator::isdbs3: {
                            m[0] = static_cast<jint>(modulation.isdbs3());
                            env->SetIntArrayRegion(valObj, i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::hidl_discriminator::isdbt: {
                            m[0] = static_cast<jint>(modulation.isdbt());
                            env->SetIntArrayRegion(valObj, i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::hidl_discriminator::atsc: {
                            m[0] = static_cast<jint>(modulation.atsc());
                            env->SetIntArrayRegion(valObj, i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::hidl_discriminator::atsc3: {
                            m[0] = static_cast<jint>(modulation.atsc3());
                            env->SetIntArrayRegion(valObj, i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::hidl_discriminator::dtmb: {
                            m[0] = static_cast<jint>(modulation.dtmb());
                            env->SetIntArrayRegion(valObj, i, 1, m);
                            valid = true;
                            break;
                        }
                        default:
                            break;
                    }
                }
                if (valid) {
                    env->SetObjectField(statusObj, field, valObj);
                }
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::bers: {
                jfieldID field = env->GetFieldID(clazz, "mBers", "[I");
                std::vector<uint32_t> v = s.bers();

                jintArray valObj = env->NewIntArray(v.size());
                env->SetIntArrayRegion(valObj, 0, v.size(), reinterpret_cast<jint*>(&v[0]));

                env->SetObjectField(statusObj, field, valObj);
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::codeRates: {
                jfieldID field = env->GetFieldID(clazz, "mCodeRates", "[I");
                std::vector<::android::hardware::tv::tuner::V1_1::FrontendInnerFec> v
                        = s.codeRates();

                jintArray valObj = env->NewIntArray(v.size());
                env->SetIntArrayRegion(valObj, 0, v.size(), reinterpret_cast<jint*>(&v[0]));

                env->SetObjectField(statusObj, field, valObj);
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::bandwidth: {
                jfieldID field = env->GetFieldID(clazz, "mBandwidth", "Ljava/lang/Integer;");
                auto bandwidth = s.bandwidth();
                jint intBandwidth;
                bool valid = true;
                switch(bandwidth.getDiscriminator()) {
                    case FrontendBandwidth::hidl_discriminator::atsc3: {
                        intBandwidth = static_cast<jint>(bandwidth.atsc3());
                        break;
                    }
                    case FrontendBandwidth::hidl_discriminator::dvbt: {
                        intBandwidth = static_cast<jint>(bandwidth.dvbt());
                        break;
                    }
                    case FrontendBandwidth::hidl_discriminator::dvbc: {
                        intBandwidth = static_cast<jint>(bandwidth.dvbc());
                        break;
                    }
                    case FrontendBandwidth::hidl_discriminator::isdbt: {
                        intBandwidth = static_cast<jint>(bandwidth.isdbt());
                        break;
                    }
                    case FrontendBandwidth::hidl_discriminator::dtmb: {
                        intBandwidth = static_cast<jint>(bandwidth.dtmb());
                        break;
                    }
                    default:
                        valid = false;
                        break;
                }
                if (valid) {
                    jobject newIntegerObj = env->NewObject(intClazz, initInt, intBandwidth);
                    env->SetObjectField(statusObj, field, newIntegerObj);
                }
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::interval: {
                jfieldID field = env->GetFieldID(clazz, "mGuardInterval", "Ljava/lang/Integer;");
                auto interval = s.interval();
                jint intInterval;
                bool valid = true;
                switch(interval.getDiscriminator()) {
                    case FrontendGuardInterval::hidl_discriminator::dvbt: {
                        intInterval = static_cast<jint>(interval.dvbt());
                        break;
                    }
                    case FrontendGuardInterval::hidl_discriminator::isdbt: {
                        intInterval = static_cast<jint>(interval.isdbt());
                        break;
                    }
                    case FrontendGuardInterval::hidl_discriminator::dtmb: {
                        intInterval = static_cast<jint>(interval.dtmb());
                        break;
                    }
                    default:
                        valid = false;
                        break;
                }
                if (valid) {
                    jobject newIntegerObj = env->NewObject(intClazz, initInt, intInterval);
                    env->SetObjectField(statusObj, field, newIntegerObj);
                }
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::transmissionMode: {
                jfieldID field = env->GetFieldID(clazz, "mTransmissionMode", "Ljava/lang/Integer;");
                auto transmissionMode = s.transmissionMode();
                jint intTransmissionMode;
                bool valid = true;
                switch(transmissionMode.getDiscriminator()) {
                    case FrontendTransmissionMode::hidl_discriminator::dvbt: {
                        intTransmissionMode = static_cast<jint>(transmissionMode.dvbt());
                        break;
                    }
                    case FrontendTransmissionMode::hidl_discriminator::isdbt: {
                        intTransmissionMode = static_cast<jint>(transmissionMode.isdbt());
                        break;
                    }
                    case FrontendTransmissionMode::hidl_discriminator::dtmb: {
                        intTransmissionMode = static_cast<jint>(transmissionMode.dtmb());
                        break;
                    }
                    default:
                        valid = false;
                        break;
                }
                if (valid) {
                    jobject newIntegerObj = env->NewObject(intClazz, initInt, intTransmissionMode);
                    env->SetObjectField(statusObj, field, newIntegerObj);
                }
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::uec: {
                jfieldID field = env->GetFieldID(clazz, "mUec", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.uec()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::systemId: {
                jfieldID field = env->GetFieldID(clazz, "mSystemId", "Ljava/lang/Integer;");
                jobject newIntegerObj = env->NewObject(
                        intClazz, initInt, static_cast<jint>(s.systemId()));
                env->SetObjectField(statusObj, field, newIntegerObj);
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::interleaving: {
                jfieldID field = env->GetFieldID(clazz, "mInterleaving", "[I");

                std::vector<FrontendInterleaveMode> v = s.interleaving();
                jintArray valObj = env->NewIntArray(v.size());
                bool valid = false;
                jint in[1];
                for (int i = 0; i < v.size(); i++) {
                    auto interleaving = v[i];
                    switch(interleaving.getDiscriminator()) {
                        case FrontendInterleaveMode::hidl_discriminator::atsc3: {
                            in[0] = static_cast<jint>(interleaving.atsc3());
                            env->SetIntArrayRegion(valObj, i, 1, in);
                            valid = true;
                            break;
                        }
                        case FrontendInterleaveMode::hidl_discriminator::dvbc: {
                            in[0] = static_cast<jint>(interleaving.dvbc());
                            env->SetIntArrayRegion(valObj, i, 1, in);
                            valid = true;
                           break;
                        }
                        case FrontendInterleaveMode::hidl_discriminator::dtmb: {
                            in[0] = static_cast<jint>(interleaving.dtmb());
                            env->SetIntArrayRegion(valObj, i, 1, in);
                            valid = true;
                           break;
                        }
                        default:
                            break;
                    }
                }
                if (valid) {
                    env->SetObjectField(statusObj, field, valObj);
                }
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::isdbtSegment: {
                jfieldID field = env->GetFieldID(clazz, "mIsdbtSegment", "[I");
                std::vector<uint8_t> v = s.isdbtSegment();

                jintArray valObj = env->NewIntArray(v.size());
                env->SetIntArrayRegion(valObj, 0, v.size(), reinterpret_cast<jint*>(&v[0]));

                env->SetObjectField(statusObj, field, valObj);
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::tsDataRate: {
                jfieldID field = env->GetFieldID(clazz, "mTsDataRate", "[I");
                std::vector<uint32_t> v = s.tsDataRate();

                jintArray valObj = env->NewIntArray(v.size());
                env->SetIntArrayRegion(valObj, 0, v.size(), reinterpret_cast<jint*>(&v[0]));

                env->SetObjectField(statusObj, field, valObj);
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::rollOff: {
                jfieldID field = env->GetFieldID(clazz, "mRollOff", "Ljava/lang/Integer;");
                auto rollOff = s.rollOff();
                jint intRollOff;
                bool valid = true;
                switch(rollOff.getDiscriminator()) {
                    case FrontendRollOff::hidl_discriminator::dvbs: {
                        intRollOff = static_cast<jint>(rollOff.dvbs());
                        break;
                    }
                    case FrontendRollOff::hidl_discriminator::isdbs: {
                        intRollOff = static_cast<jint>(rollOff.isdbs());
                        break;
                    }
                    case FrontendRollOff::hidl_discriminator::isdbs3: {
                        intRollOff = static_cast<jint>(rollOff.isdbs3());
                        break;
                    }
                    default:
                        valid = false;
                        break;
                }
                if (valid) {
                    jobject newIntegerObj = env->NewObject(intClazz, initInt, intRollOff);
                    env->SetObjectField(statusObj, field, newIntegerObj);
                }
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::isMiso: {
                jfieldID field = env->GetFieldID(clazz, "mIsMisoEnabled", "Ljava/lang/Boolean;");
                jobject newBooleanObj = env->NewObject(
                        booleanClazz, initBoolean, static_cast<jboolean>(s.isMiso()));
                env->SetObjectField(statusObj, field, newBooleanObj);
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::isLinear: {
                jfieldID field = env->GetFieldID(clazz, "mIsLinear", "Ljava/lang/Boolean;");
                jobject newBooleanObj = env->NewObject(
                        booleanClazz, initBoolean, static_cast<jboolean>(s.isLinear()));
                env->SetObjectField(statusObj, field, newBooleanObj);
                break;
            }
            case FrontendStatusExt1_1::hidl_discriminator::isShortFrames: {
                jfieldID field = env->GetFieldID(clazz, "mIsShortFrames", "Ljava/lang/Boolean;");
                jobject newBooleanObj = env->NewObject(
                        booleanClazz, initBoolean, static_cast<jboolean>(s.isShortFrames()));
                env->SetObjectField(statusObj, field, newBooleanObj);
                break;
            }
            default: {
                break;
            }
        }
    }
    return statusObj;
}

bool JTuner::isV1_1ExtendedStatusType(int type) {
    return (type > static_cast<int>(FrontendStatusType::ATSC3_PLP_INFO)
                && type <= static_cast<int>(FrontendStatusTypeExt1_1::IS_SHORT_FRAMES));
}

jint JTuner::closeFrontend() {
    Result r = Result::SUCCESS;

    if (mFeClient != NULL) {
        r = mFeClient->close();
    }
    if (r == Result::SUCCESS) {
        mFeClient = NULL;
    }
    return (jint) r;
}

jint JTuner::closeDemux() {
    Result r = Result::SUCCESS;

    if (mDemuxClient != NULL) {
        r = mDemuxClient->close();
    }
    if (r == Result::SUCCESS) {
        mDemuxClient = NULL;
    }
    return (jint) r;
}
}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static sp<JTuner> setTuner(JNIEnv *env, jobject thiz, const sp<JTuner> &tuner) {
    sp<JTuner> old = (JTuner *)env->GetLongField(thiz, gFields.tunerContext);

    if (tuner != NULL) {
        tuner->incStrong(thiz);
    }
    if (old != NULL) {
        old->decStrong(thiz);
    }

    if (tuner != NULL) {
        env->SetLongField(thiz, gFields.tunerContext, (jlong)tuner.get());
    }

    return old;
}

static sp<JTuner> getTuner(JNIEnv *env, jobject thiz) {
    return (JTuner *)env->GetLongField(thiz, gFields.tunerContext);
}

static sp<DescramblerClient> getDescramblerClient(JNIEnv *env, jobject descrambler) {
    return (DescramblerClient *)env->GetLongField(descrambler, gFields.descramblerContext);
}

static DemuxPid getDemuxPid(int pidType, int pid) {
    DemuxPid demuxPid;
    if ((int)pidType == 1) {
        demuxPid.tPid(static_cast<DemuxTpid>(pid));
    } else if ((int)pidType == 2) {
        demuxPid.mmtpPid(static_cast<DemuxMmtpPid>(pid));
    }
    return demuxPid;
}

static uint32_t getFrontendSettingsFreq(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/FrontendSettings");
    jfieldID freqField = env->GetFieldID(clazz, "mFrequency", "I");
    uint32_t freq = static_cast<uint32_t>(env->GetIntField(settings, freqField));
    return freq;
}

static uint32_t getFrontendSettingsEndFreq(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/FrontendSettings");
    jfieldID endFreqField = env->GetFieldID(clazz, "mEndFrequency", "I");
    uint32_t endFreq = static_cast<uint32_t>(env->GetIntField(settings, endFreqField));
    return endFreq;
}

static FrontendSpectralInversion getFrontendSettingsSpectralInversion(
        JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/FrontendSettings");
    jfieldID inversionField = env->GetFieldID(clazz, "mSpectralInversion", "I");
    FrontendSpectralInversion inversion =
            static_cast<FrontendSpectralInversion>(env->GetIntField(settings, inversionField));
    return inversion;
}

static FrontendSettings getAnalogFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    uint32_t freq = getFrontendSettingsFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/AnalogFrontendSettings");
    FrontendAnalogType analogType =
            static_cast<FrontendAnalogType>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mSignalType", "I")));
    FrontendAnalogSifStandard sifStandard =
            static_cast<FrontendAnalogSifStandard>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mSifStandard", "I")));
    FrontendAnalogSettings frontendAnalogSettings {
            .frequency = freq,
            .type = analogType,
            .sifStandard = sifStandard,
    };
    frontendSettings.analog(frontendAnalogSettings);
    return frontendSettings;
}

static void getAnalogFrontendSettingsExt1_1(JNIEnv *env, const jobject& settings,
        FrontendSettingsExt1_1& settingsExt1_1) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/AnalogFrontendSettings");
    FrontendAnalogAftFlag aftFlag =
            static_cast<FrontendAnalogAftFlag>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mAftFlag", "I")));
    FrontendAnalogSettingsExt1_1 analogExt1_1 {
        .aftFlag = aftFlag,
    };
    settingsExt1_1.settingExt.analog(analogExt1_1);
}

static hidl_vec<FrontendAtsc3PlpSettings> getAtsc3PlpSettings(
        JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/Atsc3FrontendSettings");
    jobjectArray plpSettings =
            reinterpret_cast<jobjectArray>(
                    env->GetObjectField(settings,
                            env->GetFieldID(
                                    clazz,
                                    "mPlpSettings",
                                    "[Landroid/media/tv/tuner/frontend/Atsc3PlpSettings;")));
    int len = env->GetArrayLength(plpSettings);

    jclass plpClazz = env->FindClass("android/media/tv/tuner/frontend/Atsc3PlpSettings");
    hidl_vec<FrontendAtsc3PlpSettings> plps = hidl_vec<FrontendAtsc3PlpSettings>(len);
    // parse PLP settings
    for (int i = 0; i < len; i++) {
        jobject plp = env->GetObjectArrayElement(plpSettings, i);
        uint8_t plpId =
                static_cast<uint8_t>(
                        env->GetIntField(plp, env->GetFieldID(plpClazz, "mPlpId", "I")));
        FrontendAtsc3Modulation modulation =
                static_cast<FrontendAtsc3Modulation>(
                        env->GetIntField(plp, env->GetFieldID(plpClazz, "mModulation", "I")));
        FrontendAtsc3TimeInterleaveMode interleaveMode =
                static_cast<FrontendAtsc3TimeInterleaveMode>(
                        env->GetIntField(
                                plp, env->GetFieldID(plpClazz, "mInterleaveMode", "I")));
        FrontendAtsc3CodeRate codeRate =
                static_cast<FrontendAtsc3CodeRate>(
                        env->GetIntField(plp, env->GetFieldID(plpClazz, "mCodeRate", "I")));
        FrontendAtsc3Fec fec =
                static_cast<FrontendAtsc3Fec>(
                        env->GetIntField(plp, env->GetFieldID(plpClazz, "mFec", "I")));
        FrontendAtsc3PlpSettings frontendAtsc3PlpSettings {
                .plpId = plpId,
                .modulation = modulation,
                .interleaveMode = interleaveMode,
                .codeRate = codeRate,
                .fec = fec,
        };
        plps[i] = frontendAtsc3PlpSettings;
    }
    return plps;
}

static FrontendSettings getAtsc3FrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    uint32_t freq = getFrontendSettingsFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/Atsc3FrontendSettings");

    FrontendAtsc3Bandwidth bandwidth =
            static_cast<FrontendAtsc3Bandwidth>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mBandwidth", "I")));
    FrontendAtsc3DemodOutputFormat demod =
            static_cast<FrontendAtsc3DemodOutputFormat>(
                    env->GetIntField(
                            settings, env->GetFieldID(clazz, "mDemodOutputFormat", "I")));
    hidl_vec<FrontendAtsc3PlpSettings> plps = getAtsc3PlpSettings(env, settings);
    FrontendAtsc3Settings frontendAtsc3Settings {
            .frequency = freq,
            .bandwidth = bandwidth,
            .demodOutputFormat = demod,
            .plpSettings = plps,
    };
    frontendSettings.atsc3(frontendAtsc3Settings);
    return frontendSettings;
}

static FrontendSettings getAtscFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    uint32_t freq = getFrontendSettingsFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/AtscFrontendSettings");
    FrontendAtscModulation modulation =
            static_cast<FrontendAtscModulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    FrontendAtscSettings frontendAtscSettings {
            .frequency = freq,
            .modulation = modulation,
    };
    frontendSettings.atsc(frontendAtscSettings);
    return frontendSettings;
}

static FrontendSettings getDvbcFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    uint32_t freq = getFrontendSettingsFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbcFrontendSettings");
    FrontendDvbcModulation modulation =
            static_cast<FrontendDvbcModulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    FrontendInnerFec innerFec =
            static_cast<FrontendInnerFec>(
                    env->GetLongField(settings, env->GetFieldID(clazz, "mInnerFec", "J")));
    uint32_t symbolRate =
            static_cast<uint32_t>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mSymbolRate", "I")));
    FrontendDvbcOuterFec outerFec =
            static_cast<FrontendDvbcOuterFec>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mOuterFec", "I")));
    FrontendDvbcAnnex annex =
            static_cast<FrontendDvbcAnnex>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mAnnex", "I")));
    FrontendDvbcSpectralInversion spectralInversion =
            static_cast<FrontendDvbcSpectralInversion>(
                    env->GetIntField(
                            settings, env->GetFieldID(clazz, "mSpectralInversion", "I")));
    FrontendDvbcSettings frontendDvbcSettings {
            .frequency = freq,
            .modulation = modulation,
            .fec = innerFec,
            .symbolRate = symbolRate,
            .outerFec = outerFec,
            .annex = annex,
            .spectralInversion = spectralInversion,
    };
    frontendSettings.dvbc(frontendDvbcSettings);
    return frontendSettings;
}

static void getDvbcFrontendSettingsExt1_1(JNIEnv *env, const jobject& settings,
        FrontendSettingsExt1_1& settingsExt1_1) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbcFrontendSettings");
    FrontendCableTimeInterleaveMode interleaveMode =
            static_cast<FrontendCableTimeInterleaveMode>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mInterleaveMode", "I")));
    FrontendDvbcBandwidth bandwidth =
            static_cast<FrontendDvbcBandwidth>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mBandwidth", "I")));

    FrontendDvbcSettingsExt1_1 dvbcExt1_1 {
        .interleaveMode = interleaveMode,
        .bandwidth = bandwidth,
    };
    settingsExt1_1.settingExt.dvbc(dvbcExt1_1);
}

static FrontendDvbsCodeRate getDvbsCodeRate(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbsFrontendSettings");
    jobject jcodeRate =
            env->GetObjectField(settings,
                    env->GetFieldID(
                            clazz,
                            "mCodeRate",
                            "Landroid/media/tv/tuner/frontend/DvbsCodeRate;"));

    jclass codeRateClazz = env->FindClass("android/media/tv/tuner/frontend/DvbsCodeRate");
    FrontendInnerFec innerFec =
            static_cast<FrontendInnerFec>(
                    env->GetLongField(
                            jcodeRate, env->GetFieldID(codeRateClazz, "mInnerFec", "J")));
    bool isLinear =
            static_cast<bool>(
                    env->GetBooleanField(
                            jcodeRate, env->GetFieldID(codeRateClazz, "mIsLinear", "Z")));
    bool isShortFrames =
            static_cast<bool>(
                    env->GetBooleanField(
                            jcodeRate, env->GetFieldID(codeRateClazz, "mIsShortFrames", "Z")));
    uint32_t bitsPer1000Symbol =
            static_cast<uint32_t>(
                    env->GetIntField(
                            jcodeRate, env->GetFieldID(
                                    codeRateClazz, "mBitsPer1000Symbol", "I")));
    FrontendDvbsCodeRate coderate {
            .fec = innerFec,
            .isLinear = isLinear,
            .isShortFrames = isShortFrames,
            .bitsPer1000Symbol = bitsPer1000Symbol,
    };
    return coderate;
}

static FrontendSettings getDvbsFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    uint32_t freq = getFrontendSettingsFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbsFrontendSettings");

    FrontendDvbsModulation modulation =
            static_cast<FrontendDvbsModulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    uint32_t symbolRate =
            static_cast<uint32_t>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mSymbolRate", "I")));
    FrontendDvbsRolloff rolloff =
            static_cast<FrontendDvbsRolloff>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mRolloff", "I")));
    FrontendDvbsPilot pilot =
            static_cast<FrontendDvbsPilot>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mPilot", "I")));
    uint32_t inputStreamId =
            static_cast<uint32_t>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mInputStreamId", "I")));
    FrontendDvbsStandard standard =
            static_cast<FrontendDvbsStandard>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mStandard", "I")));
    FrontendDvbsVcmMode vcmMode =
            static_cast<FrontendDvbsVcmMode>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mVcmMode", "I")));

    FrontendDvbsSettings frontendDvbsSettings {
            .frequency = freq,
            .modulation = modulation,
            .symbolRate = symbolRate,
            .rolloff = rolloff,
            .pilot = pilot,
            .inputStreamId = inputStreamId,
            .standard = standard,
            .vcmMode = vcmMode,
    };

    jobject jcodeRate = env->GetObjectField(settings, env->GetFieldID(clazz, "mCodeRate",
            "Landroid/media/tv/tuner/frontend/DvbsCodeRate;"));
    if (jcodeRate != NULL) {
        frontendDvbsSettings.coderate = getDvbsCodeRate(env, settings);
    }

    frontendSettings.dvbs(frontendDvbsSettings);
    return frontendSettings;
}

static void getDvbsFrontendSettingsExt1_1(JNIEnv *env, const jobject& settings,
        FrontendSettingsExt1_1& settingsExt1_1) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbsFrontendSettings");
    FrontendDvbsScanType scanType =
            static_cast<FrontendDvbsScanType>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mScanType", "I")));
    bool isDiseqcRxMessage = static_cast<bool>(env->GetBooleanField(
            settings, env->GetFieldID(clazz, "mIsDiseqcRxMessage", "Z")));

    FrontendDvbsSettingsExt1_1 dvbsExt1_1 {
        .scanType = scanType,
        .isDiseqcRxMessage = isDiseqcRxMessage,
    };
    settingsExt1_1.settingExt.dvbs(dvbsExt1_1);
}

static FrontendSettings getDvbtFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    uint32_t freq = getFrontendSettingsFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbtFrontendSettings");
    FrontendDvbtTransmissionMode transmissionMode =
            static_cast<FrontendDvbtTransmissionMode>(
                    env->GetIntField(
                            settings, env->GetFieldID(clazz, "mTransmissionMode", "I")));
    FrontendDvbtBandwidth bandwidth =
            static_cast<FrontendDvbtBandwidth>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mBandwidth", "I")));
    FrontendDvbtConstellation constellation =
            static_cast<FrontendDvbtConstellation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mConstellation", "I")));
    FrontendDvbtHierarchy hierarchy =
            static_cast<FrontendDvbtHierarchy>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mHierarchy", "I")));
    FrontendDvbtCoderate hpCoderate =
            static_cast<FrontendDvbtCoderate>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mHpCodeRate", "I")));
    FrontendDvbtCoderate lpCoderate =
            static_cast<FrontendDvbtCoderate>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mLpCodeRate", "I")));
    FrontendDvbtGuardInterval guardInterval =
            static_cast<FrontendDvbtGuardInterval>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mGuardInterval", "I")));
    bool isHighPriority =
            static_cast<bool>(
                    env->GetBooleanField(
                            settings, env->GetFieldID(clazz, "mIsHighPriority", "Z")));
    FrontendDvbtStandard standard =
            static_cast<FrontendDvbtStandard>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mStandard", "I")));
    bool isMiso =
            static_cast<bool>(
                    env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsMiso", "Z")));
    FrontendDvbtPlpMode plpMode =
            static_cast<FrontendDvbtPlpMode>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mPlpMode", "I")));
    uint8_t plpId =
            static_cast<uint8_t>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mPlpId", "I")));
    uint8_t plpGroupId =
            static_cast<uint8_t>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mPlpGroupId", "I")));

    FrontendDvbtSettings frontendDvbtSettings {
            .frequency = freq,
            .transmissionMode = transmissionMode,
            .bandwidth = bandwidth,
            .constellation = constellation,
            .hierarchy = hierarchy,
            .hpCoderate = hpCoderate,
            .lpCoderate = lpCoderate,
            .guardInterval = guardInterval,
            .isHighPriority = isHighPriority,
            .standard = standard,
            .isMiso = isMiso,
            .plpMode = plpMode,
            .plpId = plpId,
            .plpGroupId = plpGroupId,
    };
    frontendSettings.dvbt(frontendDvbtSettings);
    return frontendSettings;
}

static void getDvbtFrontendSettingsExt1_1(JNIEnv *env, const jobject& settings,
        FrontendSettingsExt1_1& settingsExt1_1) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbtFrontendSettings");

    FrontendDvbtSettingsExt1_1 dvbtExt1_1;
    int transmissionMode =
            env->GetIntField(settings, env->GetFieldID(clazz, "mTransmissionMode", "I"));
    dvbtExt1_1.transmissionMode = static_cast<
            ::android::hardware::tv::tuner::V1_1::FrontendDvbtTransmissionMode>(
                    transmissionMode);

    int constellation =
            env->GetIntField(settings, env->GetFieldID(clazz, "mConstellation", "I"));
    dvbtExt1_1.constellation = static_cast<
            ::android::hardware::tv::tuner::V1_1::FrontendDvbtConstellation>(constellation);

    settingsExt1_1.settingExt.dvbt(dvbtExt1_1);
}

static FrontendSettings getIsdbsFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    uint32_t freq = getFrontendSettingsFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/IsdbsFrontendSettings");
    uint16_t streamId =
            static_cast<uint16_t>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mStreamId", "I")));
    FrontendIsdbsStreamIdType streamIdType =
            static_cast<FrontendIsdbsStreamIdType>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mStreamIdType", "I")));
    FrontendIsdbsModulation modulation =
            static_cast<FrontendIsdbsModulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    FrontendIsdbsCoderate coderate =
            static_cast<FrontendIsdbsCoderate>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mCodeRate", "I")));
    uint32_t symbolRate =
            static_cast<uint32_t>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mSymbolRate", "I")));
    FrontendIsdbsRolloff rolloff =
            static_cast<FrontendIsdbsRolloff>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mRolloff", "I")));

    FrontendIsdbsSettings frontendIsdbsSettings {
            .frequency = freq,
            .streamId = streamId,
            .streamIdType = streamIdType,
            .modulation = modulation,
            .coderate = coderate,
            .symbolRate = symbolRate,
            .rolloff = rolloff,
    };
    frontendSettings.isdbs(frontendIsdbsSettings);
    return frontendSettings;
}

static FrontendSettings getIsdbs3FrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    uint32_t freq = getFrontendSettingsFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/Isdbs3FrontendSettings");
    uint16_t streamId =
            static_cast<uint16_t>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mStreamId", "I")));
    FrontendIsdbsStreamIdType streamIdType =
            static_cast<FrontendIsdbsStreamIdType>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mStreamIdType", "I")));
    FrontendIsdbs3Modulation modulation =
            static_cast<FrontendIsdbs3Modulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    FrontendIsdbs3Coderate coderate =
            static_cast<FrontendIsdbs3Coderate>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mCodeRate", "I")));
    uint32_t symbolRate =
            static_cast<uint32_t>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mSymbolRate", "I")));
    FrontendIsdbs3Rolloff rolloff =
            static_cast<FrontendIsdbs3Rolloff>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mRolloff", "I")));

    FrontendIsdbs3Settings frontendIsdbs3Settings {
            .frequency = freq,
            .streamId = streamId,
            .streamIdType = streamIdType,
            .modulation = modulation,
            .coderate = coderate,
            .symbolRate = symbolRate,
            .rolloff = rolloff,
    };
    frontendSettings.isdbs3(frontendIsdbs3Settings);
    return frontendSettings;
}

static FrontendSettings getIsdbtFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    uint32_t freq = getFrontendSettingsFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/IsdbtFrontendSettings");
    FrontendIsdbtModulation modulation =
            static_cast<FrontendIsdbtModulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    FrontendIsdbtBandwidth bandwidth =
            static_cast<FrontendIsdbtBandwidth>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mBandwidth", "I")));
    FrontendIsdbtMode mode =
            static_cast<FrontendIsdbtMode>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mMode", "I")));
    FrontendIsdbtCoderate coderate =
            static_cast<FrontendIsdbtCoderate>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mCodeRate", "I")));
    FrontendIsdbtGuardInterval guardInterval =
            static_cast<FrontendIsdbtGuardInterval>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mGuardInterval", "I")));
    uint32_t serviceAreaId =
            static_cast<uint32_t>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mServiceAreaId", "I")));

    FrontendIsdbtSettings frontendIsdbtSettings {
            .frequency = freq,
            .modulation = modulation,
            .bandwidth = bandwidth,
            .mode = mode,
            .coderate = coderate,
            .guardInterval = guardInterval,
            .serviceAreaId = serviceAreaId,
    };
    frontendSettings.isdbt(frontendIsdbtSettings);
    return frontendSettings;
}

static void getDtmbFrontendSettings(JNIEnv *env, const jobject& settings,
        FrontendSettingsExt1_1& settingsExt1_1) {
    uint32_t freq = getFrontendSettingsFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DtmbFrontendSettings");
    FrontendDtmbModulation modulation =
            static_cast<FrontendDtmbModulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    FrontendDtmbBandwidth bandwidth =
            static_cast<FrontendDtmbBandwidth>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mBandwidth", "I")));
    FrontendDtmbTransmissionMode transmissionMode =
            static_cast<FrontendDtmbTransmissionMode>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mTransmissionMode", "I")));
    FrontendDtmbCodeRate codeRate =
            static_cast<FrontendDtmbCodeRate>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mCodeRate", "I")));
    FrontendDtmbGuardInterval guardInterval =
            static_cast<FrontendDtmbGuardInterval>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mGuardInterval", "I")));
    FrontendDtmbTimeInterleaveMode interleaveMode =
            static_cast<FrontendDtmbTimeInterleaveMode>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mTimeInterleaveMode", "I")));

    FrontendDtmbSettings frontendDtmbSettings {
            .frequency = freq,
            .modulation = modulation,
            .bandwidth = bandwidth,
            .transmissionMode = transmissionMode,
            .codeRate = codeRate,
            .guardInterval = guardInterval,
            .interleaveMode = interleaveMode,
    };
    settingsExt1_1.settingExt.dtmb(frontendDtmbSettings);
}

static FrontendSettings getFrontendSettings(JNIEnv *env, int type, jobject settings) {
    ALOGD("getFrontendSettings %d", type);

    if (type == static_cast<int>(::android::hardware::tv::tuner::V1_1::FrontendType::DTMB)) {
        return FrontendSettings();
    }

    FrontendType feType = static_cast<FrontendType>(type);
    switch(feType) {
        case FrontendType::ANALOG:
            return getAnalogFrontendSettings(env, settings);
        case FrontendType::ATSC3:
            return getAtsc3FrontendSettings(env, settings);
        case FrontendType::ATSC:
            return getAtscFrontendSettings(env, settings);
        case FrontendType::DVBC:
            return getDvbcFrontendSettings(env, settings);
        case FrontendType::DVBS:
            return getDvbsFrontendSettings(env, settings);
        case FrontendType::DVBT:
            return getDvbtFrontendSettings(env, settings);
        case FrontendType::ISDBS:
            return getIsdbsFrontendSettings(env, settings);
        case FrontendType::ISDBS3:
            return getIsdbs3FrontendSettings(env, settings);
        case FrontendType::ISDBT:
            return getIsdbtFrontendSettings(env, settings);
        default:
            // should never happen because a type is associated with a subclass of
            // FrontendSettings and not set by users
            jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                "Unsupported frontend type %d", type);
            return FrontendSettings();
    }
}

static FrontendSettingsExt1_1 getFrontendSettingsExt1_1(
        JNIEnv *env, int type, jobject settings, int tunerVersion) {
    ALOGD("getFrontendSettingsExt1_1 %d", type);

    FrontendSettingsExt1_1 settingsExt1_1 {
        .endFrequency = static_cast<uint32_t>(Constant::INVALID_FRONTEND_SETTING_FREQUENCY),
        .inversion = FrontendSpectralInversion::UNDEFINED,
    };
    settingsExt1_1.settingExt.noinit();

    if (tunerVersion < TUNER_VERSION_1_1) {
        return settingsExt1_1;
    }

    if (type == static_cast<int>(::android::hardware::tv::tuner::V1_1::FrontendType::DTMB)) {
        getDtmbFrontendSettings(env, settings, settingsExt1_1);
    } else {
        FrontendType feType = static_cast<FrontendType>(type);
        switch(feType) {
            case FrontendType::DVBS:
                getDvbsFrontendSettingsExt1_1(env, settings, settingsExt1_1);
                break;
            case FrontendType::DVBT:
                getDvbtFrontendSettingsExt1_1(env, settings, settingsExt1_1);
                break;
            case FrontendType::ANALOG:
                getAnalogFrontendSettingsExt1_1(env, settings, settingsExt1_1);
                break;
            case FrontendType::ATSC3:
                break;
            case FrontendType::ATSC:
                break;
            case FrontendType::DVBC:
                getDvbcFrontendSettingsExt1_1(env, settings, settingsExt1_1);
                break;
            case FrontendType::ISDBS:
                break;
            case FrontendType::ISDBS3:
                break;
            case FrontendType::ISDBT:
                break;
            default:
                // should never happen because a type is associated with a subclass of
                // FrontendSettings and not set by users
                jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                    "Unsupported frontend type %d", type);
                return FrontendSettingsExt1_1();
        }
    }

    uint32_t endFreq = getFrontendSettingsEndFreq(env, settings);
    FrontendSpectralInversion inversion = getFrontendSettingsSpectralInversion(env, settings);
    settingsExt1_1.endFrequency = endFreq;
    settingsExt1_1.inversion = inversion;

    return settingsExt1_1;
}

static sp<FilterClient> getFilterClient(JNIEnv *env, jobject filter) {
    return (FilterClient *)env->GetLongField(filter, gFields.filterContext);
}

static sp<LnbClient> getLnbClient(JNIEnv *env, jobject lnb) {
    return (LnbClient *)env->GetLongField(lnb, gFields.lnbContext);
}

static DvrSettings getDvrSettings(JNIEnv *env, jobject settings, bool isRecorder) {
    DvrSettings dvrSettings;
    jclass clazz = env->FindClass("android/media/tv/tuner/dvr/DvrSettings");
    uint32_t statusMask =
            static_cast<uint32_t>(env->GetIntField(
                    settings, env->GetFieldID(clazz, "mStatusMask", "I")));
    uint32_t lowThreshold =
            static_cast<uint32_t>(env->GetLongField(
                    settings, env->GetFieldID(clazz, "mLowThreshold", "J")));
    uint32_t highThreshold =
            static_cast<uint32_t>(env->GetLongField(
                    settings, env->GetFieldID(clazz, "mHighThreshold", "J")));
    uint8_t packetSize =
            static_cast<uint8_t>(env->GetLongField(
                    settings, env->GetFieldID(clazz, "mPacketSize", "J")));
    DataFormat dataFormat =
            static_cast<DataFormat>(env->GetIntField(
                    settings, env->GetFieldID(clazz, "mDataFormat", "I")));
    if (isRecorder) {
        RecordSettings recordSettings {
                .statusMask = static_cast<unsigned char>(statusMask),
                .lowThreshold = lowThreshold,
                .highThreshold = highThreshold,
                .dataFormat = dataFormat,
                .packetSize = packetSize,
        };
        dvrSettings.record(recordSettings);
    } else {
        PlaybackSettings PlaybackSettings {
                .statusMask = statusMask,
                .lowThreshold = lowThreshold,
                .highThreshold = highThreshold,
                .dataFormat = dataFormat,
                .packetSize = packetSize,
        };
        dvrSettings.playback(PlaybackSettings);
    }
    return dvrSettings;
}

static sp<DvrClient> getDvrClient(JNIEnv *env, jobject dvr) {
    bool isRecorder =
            env->IsInstanceOf(dvr, env->FindClass("android/media/tv/tuner/dvr/DvrRecorder"));
    jfieldID fieldId =
            isRecorder ? gFields.dvrRecorderContext : gFields.dvrPlaybackContext;
    return (DvrClient *)env->GetLongField(dvr, fieldId);
}

static void android_media_tv_Tuner_native_init(JNIEnv *env) {
    jclass clazz = env->FindClass("android/media/tv/tuner/Tuner");
    CHECK(clazz != NULL);

    gFields.tunerContext = env->GetFieldID(clazz, "mNativeContext", "J");
    CHECK(gFields.tunerContext != NULL);

    gFields.onFrontendEventID = env->GetMethodID(clazz, "onFrontendEvent", "(I)V");

    jclass frontendClazz = env->FindClass("android/media/tv/tuner/Tuner$Frontend");
    gFields.frontendInitID =
            env->GetMethodID(frontendClazz, "<init>", "(Landroid/media/tv/tuner/Tuner;I)V");

    jclass lnbClazz = env->FindClass("android/media/tv/tuner/Lnb");
    gFields.lnbContext = env->GetFieldID(lnbClazz, "mNativeContext", "J");
    gFields.lnbInitID = env->GetMethodID(lnbClazz, "<init>", "()V");
    gFields.onLnbEventID = env->GetMethodID(lnbClazz, "onEvent", "(I)V");
    gFields.onLnbDiseqcMessageID = env->GetMethodID(lnbClazz, "onDiseqcMessage", "([B)V");

    jclass filterClazz = env->FindClass("android/media/tv/tuner/filter/Filter");
    gFields.filterContext = env->GetFieldID(filterClazz, "mNativeContext", "J");
    gFields.filterInitID =
            env->GetMethodID(filterClazz, "<init>", "(J)V");
    gFields.onFilterStatusID =
            env->GetMethodID(filterClazz, "onFilterStatus", "(I)V");
    gFields.onFilterEventID =
            env->GetMethodID(filterClazz, "onFilterEvent",
                    "([Landroid/media/tv/tuner/filter/FilterEvent;)V");

    jclass timeFilterClazz = env->FindClass("android/media/tv/tuner/filter/TimeFilter");
    gFields.timeFilterContext = env->GetFieldID(timeFilterClazz, "mNativeContext", "J");
    gFields.timeFilterInitID = env->GetMethodID(timeFilterClazz, "<init>", "()V");

    jclass descramblerClazz = env->FindClass("android/media/tv/tuner/Descrambler");
    gFields.descramblerContext = env->GetFieldID(descramblerClazz, "mNativeContext", "J");
    gFields.descramblerInitID = env->GetMethodID(descramblerClazz, "<init>", "()V");

    jclass dvrRecorderClazz = env->FindClass("android/media/tv/tuner/dvr/DvrRecorder");
    gFields.dvrRecorderContext = env->GetFieldID(dvrRecorderClazz, "mNativeContext", "J");
    gFields.dvrRecorderInitID = env->GetMethodID(dvrRecorderClazz, "<init>", "()V");
    gFields.onDvrRecordStatusID =
            env->GetMethodID(dvrRecorderClazz, "onRecordStatusChanged", "(I)V");

    jclass dvrPlaybackClazz = env->FindClass("android/media/tv/tuner/dvr/DvrPlayback");
    gFields.dvrPlaybackContext = env->GetFieldID(dvrPlaybackClazz, "mNativeContext", "J");
    gFields.dvrPlaybackInitID = env->GetMethodID(dvrPlaybackClazz, "<init>", "()V");
    gFields.onDvrPlaybackStatusID =
            env->GetMethodID(dvrPlaybackClazz, "onPlaybackStatusChanged", "(I)V");

    jclass mediaEventClazz = env->FindClass("android/media/tv/tuner/filter/MediaEvent");
    gFields.mediaEventContext = env->GetFieldID(mediaEventClazz, "mNativeContext", "J");

    jclass linearBlockClazz = env->FindClass("android/media/MediaCodec$LinearBlock");
    gFields.linearBlockInitID = env->GetMethodID(linearBlockClazz, "<init>", "()V");
    gFields.linearBlockSetInternalStateID =
            env->GetMethodID(linearBlockClazz, "setInternalStateLocked", "(JZ)V");
}

static void android_media_tv_Tuner_native_setup(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = new JTuner(env, thiz);
    setTuner(env, thiz, tuner);
}

static jint android_media_tv_Tuner_native_get_tuner_version(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getTunerVersion();
}

static jobject android_media_tv_Tuner_get_frontend_ids(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getFrontendIds();
}

static jobject android_media_tv_Tuner_open_frontend_by_handle(
        JNIEnv *env, jobject thiz, jint handle) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openFrontendByHandle(handle);
}

static int android_media_tv_Tuner_share_frontend(
        JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->shareFrontend(id);
}

static int android_media_tv_Tuner_tune(JNIEnv *env, jobject thiz, jint type, jobject settings) {
    sp<JTuner> tuner = getTuner(env, thiz);
    FrontendSettings setting = getFrontendSettings(env, type, settings);
    FrontendSettingsExt1_1 settingExt = getFrontendSettingsExt1_1(
            env, type, settings, tuner->getTunerVersion());
    return tuner->tune(setting, settingExt);
}

static int android_media_tv_Tuner_stop_tune(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->stopTune();
}

static int android_media_tv_Tuner_scan(
        JNIEnv *env, jobject thiz, jint settingsType, jobject settings, jint scanType) {
    sp<JTuner> tuner = getTuner(env, thiz);
    FrontendSettings setting = getFrontendSettings(env, settingsType, settings);
    FrontendSettingsExt1_1 settingExt = getFrontendSettingsExt1_1(
            env, settingsType, settings, tuner->getTunerVersion());
    return tuner->scan(setting, static_cast<FrontendScanType>(scanType), settingExt);
}

static int android_media_tv_Tuner_stop_scan(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->stopScan();
}

static int android_media_tv_Tuner_set_lnb(JNIEnv *env, jobject thiz, jobject lnb) {
    sp<JTuner> tuner = getTuner(env, thiz);
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    if (lnbClient == NULL) {
        ALOGE("lnb is not initialized");
        return (int)Result::INVALID_STATE;
    }
    return tuner->setLnb(lnbClient);
}

static int android_media_tv_Tuner_set_lna(JNIEnv *env, jobject thiz, jboolean enable) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->setLna(enable);
}

static jobject android_media_tv_Tuner_get_frontend_status(
        JNIEnv* env, jobject thiz, jintArray types) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getFrontendStatus(types);
}

static jobject android_media_tv_Tuner_get_av_sync_hw_id(
        JNIEnv *env, jobject thiz, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        ALOGD("Failed to get sync ID. Filter client not found");
        return NULL;
    }
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getAvSyncHwId(filterClient);
}

static jobject android_media_tv_Tuner_get_av_sync_time(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getAvSyncTime(id);
}

static int android_media_tv_Tuner_connect_cicam(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->connectCiCam(id);
}

static int android_media_tv_Tuner_link_cicam(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->linkCiCam(id);
}

static int android_media_tv_Tuner_disconnect_cicam(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->disconnectCiCam();
}

static int android_media_tv_Tuner_unlink_cicam(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->unlinkCiCam(id);
}

static jobject android_media_tv_Tuner_get_frontend_info(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getFrontendInfo(id);
}

static jobject android_media_tv_Tuner_open_lnb_by_handle(JNIEnv *env, jobject thiz, jint handle) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openLnbByHandle(handle);
}

static jobject android_media_tv_Tuner_open_lnb_by_name(JNIEnv *env, jobject thiz, jstring name) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openLnbByName(name);
}


static jobject android_media_tv_Tuner_open_filter(
        JNIEnv *env, jobject thiz, jint type, jint subType, jlong bufferSize) {
    sp<JTuner> tuner = getTuner(env, thiz);
    DemuxFilterMainType mainType = static_cast<DemuxFilterMainType>(type);
    DemuxFilterType filterType {
        .mainType = mainType,
    };

    switch(mainType) {
        case DemuxFilterMainType::TS:
            filterType.subType.tsFilterType(static_cast<DemuxTsFilterType>(subType));
            break;
        case DemuxFilterMainType::MMTP:
            filterType.subType.mmtpFilterType(static_cast<DemuxMmtpFilterType>(subType));
            break;
        case DemuxFilterMainType::IP:
            filterType.subType.ipFilterType(static_cast<DemuxIpFilterType>(subType));
            break;
        case DemuxFilterMainType::TLV:
            filterType.subType.tlvFilterType(static_cast<DemuxTlvFilterType>(subType));
            break;
        case DemuxFilterMainType::ALP:
            filterType.subType.alpFilterType(static_cast<DemuxAlpFilterType>(subType));
            break;
    }

    return tuner->openFilter(filterType, bufferSize);
}

static jobject android_media_tv_Tuner_open_time_filter(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openTimeFilter();
}

static DemuxFilterSectionBits getFilterSectionBits(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/SectionSettingsWithSectionBits");
    jbyteArray jfilterBytes = static_cast<jbyteArray>(
            env->GetObjectField(settings, env->GetFieldID(clazz, "mFilter", "[B")));
    jsize size = env->GetArrayLength(jfilterBytes);
    std::vector<uint8_t> filterBytes(size);
    env->GetByteArrayRegion(
            jfilterBytes, 0, size, reinterpret_cast<jbyte*>(&filterBytes[0]));

    jbyteArray jmask = static_cast<jbyteArray>(
            env->GetObjectField(settings, env->GetFieldID(clazz, "mMask", "[B")));
    size = env->GetArrayLength(jmask);
    std::vector<uint8_t> mask(size);
    env->GetByteArrayRegion(jmask, 0, size, reinterpret_cast<jbyte*>(&mask[0]));

    jbyteArray jmode = static_cast<jbyteArray>(
            env->GetObjectField(settings, env->GetFieldID(clazz, "mMode", "[B")));
    size = env->GetArrayLength(jmode);
    std::vector<uint8_t> mode(size);
    env->GetByteArrayRegion(jmode, 0, size, reinterpret_cast<jbyte*>(&mode[0]));

    DemuxFilterSectionBits filterSectionBits {
        .filter = filterBytes,
        .mask = mask,
        .mode = mode,
    };
    return filterSectionBits;
}

static DemuxFilterSectionSettings::Condition::TableInfo getFilterTableInfo(
        JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/SectionSettingsWithTableInfo");
    uint16_t tableId = static_cast<uint16_t>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mTableId", "I")));
    uint16_t version = static_cast<uint16_t>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mVersion", "I")));
    DemuxFilterSectionSettings::Condition::TableInfo tableInfo {
        .tableId = tableId,
        .version = version,
    };
    return tableInfo;
}

static DemuxFilterSectionSettings getFilterSectionSettings(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/SectionSettings");
    bool isCheckCrc = static_cast<bool>(
            env->GetBooleanField(settings, env->GetFieldID(clazz, "mCrcEnabled", "Z")));
    bool isRepeat = static_cast<bool>(
            env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsRepeat", "Z")));
    bool isRaw = static_cast<bool>(
            env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsRaw", "Z")));

    DemuxFilterSectionSettings filterSectionSettings {
        .isCheckCrc = isCheckCrc,
        .isRepeat = isRepeat,
        .isRaw = isRaw,
    };
    if (env->IsInstanceOf(
            settings,
            env->FindClass("android/media/tv/tuner/filter/SectionSettingsWithSectionBits"))) {
        filterSectionSettings.condition.sectionBits(getFilterSectionBits(env, settings));
    } else if (env->IsInstanceOf(
            settings,
            env->FindClass("android/media/tv/tuner/filter/SectionSettingsWithTableInfo"))) {
        filterSectionSettings.condition.tableInfo(getFilterTableInfo(env, settings));
    }
    return filterSectionSettings;
}

static DemuxFilterAvSettings getFilterAvSettings(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/AvSettings");
    bool isPassthrough = static_cast<bool>(
            env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsPassthrough", "Z")));
    DemuxFilterAvSettings filterAvSettings {
        .isPassthrough = isPassthrough,
    };
    return filterAvSettings;
}

static bool getAvStreamType(JNIEnv *env, jobject filterConfigObj, AvStreamType& type) {
    jobject settingsObj =
            env->GetObjectField(
                    filterConfigObj,
                    env->GetFieldID(
                            env->FindClass("android/media/tv/tuner/filter/FilterConfiguration"),
                            "mSettings",
                            "Landroid/media/tv/tuner/filter/Settings;"));
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/AvSettings");
    AvStreamType streamType;
    AudioStreamType audioStreamType = static_cast<AudioStreamType>(
            env->GetIntField(settingsObj, env->GetFieldID(clazz, "mAudioStreamType", "I")));
    if (audioStreamType != AudioStreamType::UNDEFINED) {
        type.audio(audioStreamType);
        return true;
    }
    VideoStreamType videoStreamType = static_cast<VideoStreamType>(
            env->GetIntField(settingsObj, env->GetFieldID(clazz, "mVideoStreamType", "I")));
    if (videoStreamType != VideoStreamType::UNDEFINED) {
        type.video(videoStreamType);
        return true;
    }
    return false;
}

static DemuxFilterPesDataSettings getFilterPesDataSettings(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/PesSettings");
    uint16_t streamId = static_cast<uint16_t>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mStreamId", "I")));
    bool isRaw = static_cast<bool>(
            env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsRaw", "Z")));
    DemuxFilterPesDataSettings filterPesDataSettings {
        .streamId = streamId,
        .isRaw = isRaw,
    };
    return filterPesDataSettings;
}

static DemuxFilterRecordSettings getFilterRecordSettings(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/RecordSettings");
    hidl_bitfield<DemuxTsIndex> tsIndexMask = static_cast<hidl_bitfield<DemuxTsIndex>>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mTsIndexMask", "I")));
    DemuxRecordScIndexType scIndexType = static_cast<DemuxRecordScIndexType>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mScIndexType", "I")));
    jint scIndexMask = env->GetIntField(settings, env->GetFieldID(clazz, "mScIndexMask", "I"));

    DemuxFilterRecordSettings filterRecordSettings {
        .tsIndexMask = tsIndexMask,
        .scIndexType = scIndexType,
    };
    if (scIndexType == DemuxRecordScIndexType::SC) {
        filterRecordSettings.scIndexMask.sc(static_cast<hidl_bitfield<DemuxScIndex>>(scIndexMask));
    } else if (scIndexType == DemuxRecordScIndexType::SC_HEVC) {
        filterRecordSettings.scIndexMask.scHevc(
                static_cast<hidl_bitfield<DemuxScHevcIndex>>(scIndexMask));
    }
    return filterRecordSettings;
}

static DemuxFilterDownloadSettings getFilterDownloadSettings(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/DownloadSettings");
    uint32_t downloadId = static_cast<uint32_t>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mDownloadId", "I")));

    DemuxFilterDownloadSettings filterDownloadSettings {
        .downloadId = downloadId,
    };
    return filterDownloadSettings;
}

static DemuxIpAddress getDemuxIpAddress(JNIEnv *env, const jobject& config) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/IpFilterConfiguration");

    jbyteArray jsrcIpAddress = static_cast<jbyteArray>(
            env->GetObjectField(config, env->GetFieldID(clazz, "mSrcIpAddress", "[B")));
    jsize srcSize = env->GetArrayLength(jsrcIpAddress);
    jbyteArray jdstIpAddress = static_cast<jbyteArray>(
            env->GetObjectField(config, env->GetFieldID(clazz, "mDstIpAddress", "[B")));
    jsize dstSize = env->GetArrayLength(jdstIpAddress);

    DemuxIpAddress res;

    if (srcSize != dstSize) {
        // should never happen. Validated on Java size.
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
            "IP address lengths don't match. srcLength=%d, dstLength=%d", srcSize, dstSize);
        return res;
    }

    if (srcSize == IP_V4_LENGTH) {
        uint8_t srcAddr[IP_V4_LENGTH];
        uint8_t dstAddr[IP_V4_LENGTH];
        env->GetByteArrayRegion(
                jsrcIpAddress, 0, srcSize, reinterpret_cast<jbyte*>(srcAddr));
        env->GetByteArrayRegion(
                jdstIpAddress, 0, dstSize, reinterpret_cast<jbyte*>(dstAddr));
        res.srcIpAddress.v4(srcAddr);
        res.dstIpAddress.v4(dstAddr);
    } else if (srcSize == IP_V6_LENGTH) {
        uint8_t srcAddr[IP_V6_LENGTH];
        uint8_t dstAddr[IP_V6_LENGTH];
        env->GetByteArrayRegion(
                jsrcIpAddress, 0, srcSize, reinterpret_cast<jbyte*>(srcAddr));
        env->GetByteArrayRegion(
                jdstIpAddress, 0, dstSize, reinterpret_cast<jbyte*>(dstAddr));
        res.srcIpAddress.v6(srcAddr);
        res.dstIpAddress.v6(dstAddr);
    } else {
        // should never happen. Validated on Java size.
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
            "Invalid IP address length %d", srcSize);
        return res;
    }

    uint16_t srcPort = static_cast<uint16_t>(
            env->GetIntField(config, env->GetFieldID(clazz, "mSrcPort", "I")));
    uint16_t dstPort = static_cast<uint16_t>(
            env->GetIntField(config, env->GetFieldID(clazz, "mDstPort", "I")));

    res.srcPort = srcPort;
    res.dstPort = dstPort;

    return res;
}

static DemuxFilterSettings getFilterConfiguration(
        JNIEnv *env, int type, int subtype, jobject filterConfigObj) {
    DemuxFilterSettings filterSettings;
    jobject settingsObj =
            env->GetObjectField(
                    filterConfigObj,
                    env->GetFieldID(
                            env->FindClass("android/media/tv/tuner/filter/FilterConfiguration"),
                            "mSettings",
                            "Landroid/media/tv/tuner/filter/Settings;"));
    DemuxFilterMainType mainType = static_cast<DemuxFilterMainType>(type);
    switch (mainType) {
        case DemuxFilterMainType::TS: {
            jclass clazz = env->FindClass("android/media/tv/tuner/filter/TsFilterConfiguration");
            uint16_t tpid = static_cast<uint16_t>(
                    env->GetIntField(filterConfigObj, env->GetFieldID(clazz, "mTpid", "I")));
            DemuxTsFilterSettings tsFilterSettings {
                .tpid = tpid,
            };

            if (settingsObj != NULL) {
                DemuxTsFilterType tsType = static_cast<DemuxTsFilterType>(subtype);
                switch (tsType) {
                    case DemuxTsFilterType::SECTION:
                        tsFilterSettings.filterSettings.section(
                                getFilterSectionSettings(env, settingsObj));
                        break;
                    case DemuxTsFilterType::AUDIO:
                    case DemuxTsFilterType::VIDEO:
                        tsFilterSettings.filterSettings.av(getFilterAvSettings(env, settingsObj));
                        break;
                    case DemuxTsFilterType::PES:
                        tsFilterSettings.filterSettings.pesData(
                                getFilterPesDataSettings(env, settingsObj));
                        break;
                    case DemuxTsFilterType::RECORD:
                        tsFilterSettings.filterSettings.record(
                                getFilterRecordSettings(env, settingsObj));
                        break;
                    default:
                        break;
                }
            }
            filterSettings.ts(tsFilterSettings);
            break;
        }
        case DemuxFilterMainType::MMTP: {
            jclass clazz = env->FindClass("android/media/tv/tuner/filter/MmtpFilterConfiguration");
            uint16_t mmtpPid = static_cast<uint16_t>(
                    env->GetIntField(filterConfigObj, env->GetFieldID(clazz, "mMmtpPid", "I")));
            DemuxMmtpFilterSettings mmtpFilterSettings {
                .mmtpPid = mmtpPid,
            };

            if (settingsObj != NULL) {
                DemuxMmtpFilterType mmtpType = static_cast<DemuxMmtpFilterType>(subtype);
                switch (mmtpType) {
                    case DemuxMmtpFilterType::SECTION:
                        mmtpFilterSettings.filterSettings.section(
                                getFilterSectionSettings(env, settingsObj));
                        break;
                    case DemuxMmtpFilterType::AUDIO:
                    case DemuxMmtpFilterType::VIDEO:
                        mmtpFilterSettings.filterSettings.av(getFilterAvSettings(env, settingsObj));
                        break;
                    case DemuxMmtpFilterType::PES:
                        mmtpFilterSettings.filterSettings.pesData(
                                getFilterPesDataSettings(env, settingsObj));
                        break;
                    case DemuxMmtpFilterType::RECORD:
                        mmtpFilterSettings.filterSettings.record(
                                getFilterRecordSettings(env, settingsObj));
                        break;
                    case DemuxMmtpFilterType::DOWNLOAD:
                        mmtpFilterSettings.filterSettings.download(
                                getFilterDownloadSettings(env, settingsObj));
                        break;
                    default:
                        break;
                }
            }
            filterSettings.mmtp(mmtpFilterSettings);
            break;
        }
        case DemuxFilterMainType::IP: {
            DemuxIpAddress ipAddr = getDemuxIpAddress(env, filterConfigObj);
            DemuxIpFilterSettings ipFilterSettings {
                .ipAddr = ipAddr,
            };

            DemuxIpFilterType ipType = static_cast<DemuxIpFilterType>(subtype);
            if (ipType == DemuxIpFilterType::SECTION && settingsObj != NULL) {
                ipFilterSettings.filterSettings.section(
                                getFilterSectionSettings(env, settingsObj));
            } else if (ipType == DemuxIpFilterType::IP) {
                jclass clazz = env->FindClass(
                        "android/media/tv/tuner/filter/IpFilterConfiguration");
                bool bPassthrough = static_cast<bool>(
                        env->GetBooleanField(
                                filterConfigObj, env->GetFieldID(
                                        clazz, "mPassthrough", "Z")));
                ipFilterSettings.filterSettings.bPassthrough(bPassthrough);
            }
            filterSettings.ip(ipFilterSettings);
            break;
        }
        case DemuxFilterMainType::TLV: {
            jclass clazz = env->FindClass("android/media/tv/tuner/filter/TlvFilterConfiguration");
            uint8_t packetType = static_cast<uint8_t>(
                    env->GetIntField(filterConfigObj, env->GetFieldID(clazz, "mPacketType", "I")));
            bool isCompressedIpPacket = static_cast<bool>(
                    env->GetBooleanField(
                            filterConfigObj, env->GetFieldID(clazz, "mIsCompressedIpPacket", "Z")));

            DemuxTlvFilterSettings tlvFilterSettings {
                .packetType = packetType,
                .isCompressedIpPacket = isCompressedIpPacket,
            };

            DemuxTlvFilterType tlvType = static_cast<DemuxTlvFilterType>(subtype);
            if (tlvType == DemuxTlvFilterType::SECTION && settingsObj != NULL) {
                tlvFilterSettings.filterSettings.section(
                        getFilterSectionSettings(env, settingsObj));
            } else if (tlvType == DemuxTlvFilterType::TLV) {
                bool bPassthrough = static_cast<bool>(
                env->GetBooleanField(
                        filterConfigObj, env->GetFieldID(
                                clazz, "mPassthrough", "Z")));
                tlvFilterSettings.filterSettings.bPassthrough(bPassthrough);
            }
            filterSettings.tlv(tlvFilterSettings);
            break;
        }
        case DemuxFilterMainType::ALP: {
            jclass clazz = env->FindClass("android/media/tv/tuner/filter/AlpFilterConfiguration");
            uint8_t packetType = static_cast<uint8_t>(
                    env->GetIntField(filterConfigObj, env->GetFieldID(clazz, "mPacketType", "I")));
            DemuxAlpLengthType lengthType = static_cast<DemuxAlpLengthType>(
                    env->GetIntField(filterConfigObj, env->GetFieldID(clazz, "mLengthType", "I")));
            DemuxAlpFilterSettings alpFilterSettings {
                .packetType = packetType,
                .lengthType = lengthType,
            };

            if (settingsObj != NULL) {
                DemuxAlpFilterType alpType = static_cast<DemuxAlpFilterType>(subtype);
                switch (alpType) {
                    case DemuxAlpFilterType::SECTION:
                        alpFilterSettings.filterSettings.section(
                                getFilterSectionSettings(env, settingsObj));
                        break;
                    default:
                        break;
                }
            }
            filterSettings.alp(alpFilterSettings);
            break;
        }
        default: {
            break;
        }
    }
    return filterSettings;
}

static Result configureIpFilterContextId(
        JNIEnv *env, sp<FilterClient> filterClient, jobject ipFilterConfigObj) {
    jclass clazz = env->FindClass(
            "android/media/tv/tuner/filter/IpFilterConfiguration");
    uint32_t cid = env->GetIntField(ipFilterConfigObj, env->GetFieldID(
            clazz, "mIpFilterContextId", "I"));

    return filterClient->configureIpFilterContextId(cid);
}

static bool isAvFilterSettings(DemuxFilterSettings filterSettings) {
    return (filterSettings.getDiscriminator() == DemuxFilterSettings::hidl_discriminator::ts
            && filterSettings.ts().filterSettings.getDiscriminator()
                    == DemuxTsFilterSettings::FilterSettings::hidl_discriminator::av)
            ||
            (filterSettings.getDiscriminator() == DemuxFilterSettings::hidl_discriminator::mmtp
            && filterSettings.mmtp().filterSettings.getDiscriminator()
                    == DemuxMmtpFilterSettings::FilterSettings::hidl_discriminator::av);
}

static jint android_media_tv_Tuner_configure_filter(
        JNIEnv *env, jobject filter, int type, int subtype, jobject settings) {
    ALOGD("configure filter type=%d, subtype=%d", type, subtype);
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        ALOGD("Failed to configure filter: filter not found");
        return (jint) Result::NOT_INITIALIZED;
    }
    DemuxFilterSettings filterSettings = getFilterConfiguration(env, type, subtype, settings);
    Result res = filterClient->configure(filterSettings);

    if (res != Result::SUCCESS) {
        return (jint) res;
    }

    if (static_cast<DemuxFilterMainType>(type) == DemuxFilterMainType::IP) {
        res = configureIpFilterContextId(env, filterClient, settings);
        if (res != Result::SUCCESS) {
            return (jint) res;
        }
    }

    AvStreamType streamType;
    if (isAvFilterSettings(filterSettings) && getAvStreamType(env, settings, streamType)) {
        res = filterClient->configureAvStreamType(streamType);
    }
    return (jint) res;
}

static jint android_media_tv_Tuner_get_filter_id(JNIEnv* env, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        ALOGD("Failed to get filter ID: filter client not found");
        return (int) Result::NOT_INITIALIZED;
    }
    uint32_t id;
    Result res = filterClient->getId(id);
    if (res != Result::SUCCESS) {
        return (jint) Constant::INVALID_FILTER_ID;
    }
    return (jint) id;
}

static jlong android_media_tv_Tuner_get_filter_64bit_id(JNIEnv* env, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        ALOGD("Failed to get filter ID 64 bit: filter client not found");
        return (int) Result::NOT_INITIALIZED;
    }
    uint64_t id;
    Result res = filterClient->getId64Bit(id);
    return (res == Result::SUCCESS) ?
            static_cast<jlong>(id) : static_cast<jlong>(
                    ::android::hardware::tv::tuner::V1_1::Constant64Bit::INVALID_FILTER_ID_64BIT);
}

static jint android_media_tv_Tuner_configure_monitor_event(
        JNIEnv* env, jobject filter, int monitorEventType) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        ALOGD("Failed to configure scrambling event: filter client not found");
        return (int) Result::NOT_INITIALIZED;
    }
    Result res = filterClient->configureMonitorEvent(monitorEventType);
    return (jint) res;
}

static jint android_media_tv_Tuner_set_filter_data_source(
        JNIEnv* env, jobject filter, jobject srcFilter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        ALOGD("Failed to set filter data source: filter client not found");
        return (int) Result::NOT_INITIALIZED;
    }
    Result res;
    if (srcFilter == NULL) {
        res = filterClient->setDataSource(NULL);
    } else {
        sp<FilterClient> srcClient = getFilterClient(env, srcFilter);
        if (srcClient == NULL) {
            ALOGD("Failed to set filter data source: src filter not found");
            return (jint) Result::INVALID_ARGUMENT;
        }
        res = filterClient->setDataSource(srcClient);
    }
    return (jint) res;
}

static jint android_media_tv_Tuner_start_filter(JNIEnv *env, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        ALOGD("Failed to start filter: filter client not found");
        return (int) Result::NOT_INITIALIZED;
    }
    return (jint) filterClient->start();
}

static jint android_media_tv_Tuner_stop_filter(JNIEnv *env, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        ALOGD("Failed to stop filter: filter client not found");
        return (int) Result::NOT_INITIALIZED;
    }
    return (jint) filterClient->stop();
}

static jint android_media_tv_Tuner_flush_filter(JNIEnv *env, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        ALOGD("Failed to flush filter: filter client not found");
        return (int) Result::NOT_INITIALIZED;
    }
    return (jint) filterClient->flush();
}

static jint android_media_tv_Tuner_read_filter_fmq(
        JNIEnv *env, jobject filter, jbyteArray buffer, jlong offset, jlong size) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Failed to read filter FMQ: filter client not found");
        return -1;
    }

    jboolean isCopy;
    jbyte *dst = env->GetByteArrayElements(buffer, &isCopy);
    ALOGD("copyData, isCopy=%d", isCopy);
    if (dst == nullptr) {
        jniThrowRuntimeException(env, "Failed to GetByteArrayElements");
        return -1;
    }
    int realReadSize = filterClient->read(reinterpret_cast<int8_t*>(dst) + offset, size);
    env->ReleaseByteArrayElements(buffer, dst, 0);
    return (jint) realReadSize;
}

static jint android_media_tv_Tuner_close_filter(JNIEnv *env, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Failed to close filter: filter client not found");
        return 0;
    }

    return (jint) filterClient->close();
}

static sp<TimeFilterClient> getTimeFilterClient(JNIEnv *env, jobject filter) {
    return (TimeFilterClient *)env->GetLongField(filter, gFields.timeFilterContext);
}

static int android_media_tv_Tuner_time_filter_set_timestamp(
        JNIEnv *env, jobject filter, jlong timestamp) {
    sp<TimeFilterClient> timeFilterClient = getTimeFilterClient(env, filter);
    if (timeFilterClient == NULL) {
        ALOGD("Failed set timestamp: time filter client not found");
        return (int) Result::INVALID_STATE;
    }
    Result r = timeFilterClient->setTimeStamp(static_cast<uint64_t>(timestamp));
    return (int) r;
}

static int android_media_tv_Tuner_time_filter_clear_timestamp(JNIEnv *env, jobject filter) {
    sp<TimeFilterClient> timeFilterClient = getTimeFilterClient(env, filter);
    if (timeFilterClient == NULL) {
        ALOGD("Failed clear timestamp: time filter client not found");
        return (int) Result::INVALID_STATE;
    }
    Result r = timeFilterClient->clearTimeStamp();
    return (int) r;
}

static jobject android_media_tv_Tuner_time_filter_get_timestamp(JNIEnv *env, jobject filter) {
    sp<TimeFilterClient> timeFilterClient = getTimeFilterClient(env, filter);
    if (timeFilterClient == NULL) {
        ALOGD("Failed get timestamp: time filter client not found");
        return NULL;
    }
    uint64_t timestamp = timeFilterClient->getTimeStamp();
    if (timestamp == (long)Constant64Bit::INVALID_PRESENTATION_TIME_STAMP) {
        return NULL;
    }

    jclass longClazz = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClazz, "<init>", "(J)V");

    jobject longObj = env->NewObject(longClazz, longInit, static_cast<jlong>(timestamp));
    return longObj;
}

static jobject android_media_tv_Tuner_time_filter_get_source_time(JNIEnv *env, jobject filter) {
    sp<TimeFilterClient> timeFilterClient = getTimeFilterClient(env, filter);
    if (timeFilterClient == NULL) {
        ALOGD("Failed get source time: time filter client not found");
        return NULL;
    }
    uint64_t timestamp = timeFilterClient->getSourceTime();
    if (timestamp == (long)Constant64Bit::INVALID_PRESENTATION_TIME_STAMP) {
        return NULL;
    }

    jclass longClazz = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClazz, "<init>", "(J)V");

    jobject longObj = env->NewObject(longClazz, longInit, static_cast<jlong>(timestamp));
    return longObj;
}

static int android_media_tv_Tuner_time_filter_close(JNIEnv *env, jobject filter) {
    sp<TimeFilterClient> timeFilterClient = getTimeFilterClient(env, filter);
    if (timeFilterClient == NULL) {
        ALOGD("Failed close time filter: time filter client not found");
        return (int) Result::INVALID_STATE;
    }

    Result r = timeFilterClient->close();
    if (r == Result::SUCCESS) {
        timeFilterClient->decStrong(filter);
        env->SetLongField(filter, gFields.timeFilterContext, 0);
    }
    return (int) r;
}

static jobject android_media_tv_Tuner_open_descrambler(JNIEnv *env, jobject thiz, jint) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openDescrambler();
}

static jint android_media_tv_Tuner_descrambler_add_pid(
        JNIEnv *env, jobject descrambler, jint pidType, jint pid, jobject filter) {
    sp<DescramblerClient> descramblerClient = getDescramblerClient(env, descrambler);
    if (descramblerClient == NULL) {
        return (jint) Result::NOT_INITIALIZED;
    }
    sp<FilterClient> filterClient = (filter == NULL) ? NULL : getFilterClient(env, filter);
    Result result = descramblerClient->addPid(getDemuxPid((int)pidType, (int)pid), filterClient);
    return (jint) result;
}

static jint android_media_tv_Tuner_descrambler_remove_pid(
        JNIEnv *env, jobject descrambler, jint pidType, jint pid, jobject filter) {
    sp<DescramblerClient> descramblerClient = getDescramblerClient(env, descrambler);
    if (descramblerClient == NULL) {
        return (jint) Result::NOT_INITIALIZED;
    }
    sp<FilterClient> filterClient = (filter == NULL) ? NULL : getFilterClient(env, filter);
    Result result = descramblerClient->removePid(getDemuxPid((int)pidType, (int)pid), filterClient);
    return (jint) result;
}

static jint android_media_tv_Tuner_descrambler_set_key_token(
        JNIEnv* env, jobject descrambler, jbyteArray keyToken) {
    sp<DescramblerClient> descramblerClient = getDescramblerClient(env, descrambler);
    if (descramblerClient == NULL) {
        return (jint) Result::NOT_INITIALIZED;
    }
    int size = env->GetArrayLength(keyToken);
    std::vector<uint8_t> v(size);
    env->GetByteArrayRegion(keyToken, 0, size, reinterpret_cast<jbyte*>(&v[0]));
    Result result = descramblerClient->setKeyToken(v);
    return (jint) result;
}

static jint android_media_tv_Tuner_close_descrambler(JNIEnv* env, jobject descrambler) {
    sp<DescramblerClient> descramblerClient = getDescramblerClient(env, descrambler);
    if (descramblerClient == NULL) {
        return (jint) Result::NOT_INITIALIZED;
    }
    Result r = descramblerClient->close();
    if (r == Result::SUCCESS) {
        descramblerClient->decStrong(descrambler);
    }
    return (jint) r;
}

static jobject android_media_tv_Tuner_open_dvr_recorder(
        JNIEnv* env, jobject thiz, jlong bufferSize) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openDvr(DvrType::RECORD, bufferSize);
}

static jobject android_media_tv_Tuner_open_dvr_playback(
        JNIEnv* env, jobject thiz, jlong bufferSize) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openDvr(DvrType::PLAYBACK, bufferSize);
}

static jobject android_media_tv_Tuner_get_demux_caps(JNIEnv* env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getDemuxCaps();
}

static jint android_media_tv_Tuner_open_demux(JNIEnv* env, jobject thiz, jint handle) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return (jint) tuner->openDemux(handle);
}

static jint android_media_tv_Tuner_close_tuner(JNIEnv* env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    setTuner(env, thiz, NULL);
    return (jint) tuner->close();
}

static jint android_media_tv_Tuner_close_demux(JNIEnv* env, jobject thiz, jint /* handle */) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->closeDemux();
}

static jint android_media_tv_Tuner_close_frontend(JNIEnv* env, jobject thiz, jint /* handle */) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->closeFrontend();
}

static jint android_media_tv_Tuner_attach_filter(JNIEnv *env, jobject dvr, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        return (jint) Result::INVALID_ARGUMENT;
    }
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        return (jint) Result::NOT_INITIALIZED;
    }
    Result result = dvrClient->attachFilter(filterClient);
    return (jint) result;
}

static jint android_media_tv_Tuner_detach_filter(JNIEnv *env, jobject dvr, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == NULL) {
        return (jint) Result::INVALID_ARGUMENT;
    }
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        return (jint) Result::NOT_INITIALIZED;
    }
    Result result = dvrClient->detachFilter(filterClient);
    return (jint) result;
}

static jint android_media_tv_Tuner_configure_dvr(JNIEnv *env, jobject dvr, jobject settings) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        ALOGD("Failed to configure dvr: dvr client not found");
        return (int)Result::NOT_INITIALIZED;
    }
    bool isRecorder =
            env->IsInstanceOf(dvr, env->FindClass("android/media/tv/tuner/dvr/DvrRecorder"));
    Result result = dvrClient->configure(getDvrSettings(env, settings, isRecorder));
    return (jint) result;
}

static jint android_media_tv_Tuner_start_dvr(JNIEnv *env, jobject dvr) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        ALOGD("Failed to start dvr: dvr client not found");
        return (jint) Result::NOT_INITIALIZED;
    }
    Result result = dvrClient->start();
    return (jint) result;
}

static jint android_media_tv_Tuner_stop_dvr(JNIEnv *env, jobject dvr) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        ALOGD("Failed to stop dvr: dvr client not found");
        return (jint) Result::NOT_INITIALIZED;
    }
    Result result = dvrClient->stop();
    return (jint) result;
}

static jint android_media_tv_Tuner_flush_dvr(JNIEnv *env, jobject dvr) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        ALOGD("Failed to flush dvr: dvr client not found");
        return (jint) Result::NOT_INITIALIZED;
    }
    Result result = dvrClient->flush();
    return (jint) result;
}

static jint android_media_tv_Tuner_close_dvr(JNIEnv* env, jobject dvr) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        ALOGD("Failed to close dvr: dvr client not found");
        return (jint) Result::NOT_INITIALIZED;
    }
    return (jint) dvrClient->close();
}

static jint android_media_tv_Tuner_lnb_set_voltage(JNIEnv* env, jobject lnb, jint voltage) {
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    Result r = lnbClient->setVoltage(static_cast<LnbVoltage>(voltage));
    return (jint) r;
}

static int android_media_tv_Tuner_lnb_set_tone(JNIEnv* env, jobject lnb, jint tone) {
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    Result r = lnbClient->setTone(static_cast<LnbTone>(tone));
    return (jint) r;
}

static int android_media_tv_Tuner_lnb_set_position(JNIEnv* env, jobject lnb, jint position) {
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    Result r = lnbClient->setSatellitePosition(static_cast<LnbPosition>(position));
    return (jint) r;
}

static int android_media_tv_Tuner_lnb_send_diseqc_msg(JNIEnv* env, jobject lnb, jbyteArray msg) {
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    int size = env->GetArrayLength(msg);
    std::vector<uint8_t> v(size);
    env->GetByteArrayRegion(msg, 0, size, reinterpret_cast<jbyte*>(&v[0]));
    Result r = lnbClient->sendDiseqcMessage(v);
    return (jint) r;
}

static int android_media_tv_Tuner_close_lnb(JNIEnv* env, jobject lnb) {
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    Result r = lnbClient->close();
    if (r == Result::SUCCESS) {
        lnbClient->decStrong(lnb);
        env->SetLongField(lnb, gFields.lnbContext, 0);
    }
    return (jint) r;
}

static void android_media_tv_Tuner_dvr_set_fd(JNIEnv *env, jobject dvr, jint fd) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        ALOGD("Failed to set FD for dvr: dvr client not found");
        return;
    }
    dvrClient->setFd((int)fd);
    ALOGD("set fd = %d", fd);
}

static jlong android_media_tv_Tuner_read_dvr(JNIEnv *env, jobject dvr, jlong size) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Failed to read dvr: dvr client not found");
        return -1;
    }

    return (jlong) dvrClient->readFromFile(size);
}

static jlong android_media_tv_Tuner_read_dvr_from_array(
        JNIEnv* env, jobject dvr, jbyteArray buffer, jlong offset, jlong size) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        ALOGW("Failed to read dvr: dvr client not found");
        return -1;
    }

    jboolean isCopy;
    jbyte *src = env->GetByteArrayElements(buffer, &isCopy);
    if (src == nullptr) {
        ALOGD("Failed to GetByteArrayElements");
        return -1;
    }
    long realSize = dvrClient->readFromBuffer(reinterpret_cast<signed char*>(src) + offset, size);
    env->ReleaseByteArrayElements(buffer, src, 0);
    return (jlong) realSize;

}

static jlong android_media_tv_Tuner_write_dvr(JNIEnv *env, jobject dvr, jlong size) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Failed to write dvr: dvr client not found");
        return -1;
    }

    return (jlong) dvrClient->writeToFile(size);
}

static jlong android_media_tv_Tuner_write_dvr_to_array(
        JNIEnv *env, jobject dvr, jbyteArray buffer, jlong offset, jlong size) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == NULL) {
        ALOGW("Failed to read dvr: dvr client not found");
        return -1;
    }

    jboolean isCopy;
    jbyte *dst = env->GetByteArrayElements(buffer, &isCopy);
    ALOGD("copyData, isCopy=%d", isCopy);
    if (dst == nullptr) {
        jniThrowRuntimeException(env, "Failed to GetByteArrayElements");
        return -1;
    }

    long realSize = dvrClient->writeToBuffer(reinterpret_cast<signed char*>(dst) + offset, size);
    env->ReleaseByteArrayElements(buffer, dst, 0);
    return (jlong) realSize;
}

static sp<MediaEvent> getMediaEventSp(JNIEnv *env, jobject mediaEventObj) {
    return (MediaEvent *)env->GetLongField(mediaEventObj, gFields.mediaEventContext);
}

static jobject android_media_tv_Tuner_media_event_get_linear_block(
        JNIEnv* env, jobject mediaEventObj) {
    sp<MediaEvent> mediaEventSp = getMediaEventSp(env, mediaEventObj);
    if (mediaEventSp == NULL) {
        ALOGD("Failed get MediaEvent");
        return NULL;
    }
    android::Mutex::Autolock autoLock(mediaEventSp->mLock);

    return mediaEventSp->getLinearBlock();
}

static jobject android_media_tv_Tuner_media_event_get_audio_handle(
        JNIEnv* env, jobject mediaEventObj) {
    sp<MediaEvent> mediaEventSp = getMediaEventSp(env, mediaEventObj);
    if (mediaEventSp == NULL) {
        ALOGD("Failed get MediaEvent");
        return NULL;
    }

    android::Mutex::Autolock autoLock(mediaEventSp->mLock);
    uint64_t audioHandle = mediaEventSp->getAudioHandle();
    jclass longClazz = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClazz, "<init>", "(J)V");

    jobject longObj = env->NewObject(longClazz, longInit, static_cast<jlong>(audioHandle));
    return longObj;
}

static void android_media_tv_Tuner_media_event_finalize(JNIEnv* env, jobject mediaEventObj) {
    sp<MediaEvent> mediaEventSp = getMediaEventSp(env, mediaEventObj);
    if (mediaEventSp == NULL) {
        ALOGD("Failed get MediaEvent");
        return;
    }

    android::Mutex::Autolock autoLock(mediaEventSp->mLock);
    mediaEventSp->mAvHandleRefCnt--;
    mediaEventSp->finalize();

    mediaEventSp->decStrong(mediaEventObj);
}

static const JNINativeMethod gTunerMethods[] = {
    { "nativeInit", "()V", (void *)android_media_tv_Tuner_native_init },
    { "nativeSetup", "()V", (void *)android_media_tv_Tuner_native_setup },
    { "nativeGetTunerVersion", "()I", (void *)android_media_tv_Tuner_native_get_tuner_version },
    { "nativeGetFrontendIds", "()Ljava/util/List;",
            (void *)android_media_tv_Tuner_get_frontend_ids },
    { "nativeOpenFrontendByHandle", "(I)Landroid/media/tv/tuner/Tuner$Frontend;",
            (void *)android_media_tv_Tuner_open_frontend_by_handle },
    { "nativeShareFrontend", "(I)I",
            (void *)android_media_tv_Tuner_share_frontend },
    { "nativeTune", "(ILandroid/media/tv/tuner/frontend/FrontendSettings;)I",
            (void *)android_media_tv_Tuner_tune },
    { "nativeStopTune", "()I", (void *)android_media_tv_Tuner_stop_tune },
    { "nativeScan", "(ILandroid/media/tv/tuner/frontend/FrontendSettings;I)I",
            (void *)android_media_tv_Tuner_scan },
    { "nativeStopScan", "()I", (void *)android_media_tv_Tuner_stop_scan },
    { "nativeSetLnb", "(Landroid/media/tv/tuner/Lnb;)I", (void *)android_media_tv_Tuner_set_lnb },
    { "nativeSetLna", "(Z)I", (void *)android_media_tv_Tuner_set_lna },
    { "nativeGetFrontendStatus", "([I)Landroid/media/tv/tuner/frontend/FrontendStatus;",
            (void *)android_media_tv_Tuner_get_frontend_status },
    { "nativeGetAvSyncHwId", "(Landroid/media/tv/tuner/filter/Filter;)Ljava/lang/Integer;",
            (void *)android_media_tv_Tuner_get_av_sync_hw_id },
    { "nativeGetAvSyncTime", "(I)Ljava/lang/Long;",
            (void *)android_media_tv_Tuner_get_av_sync_time },
    { "nativeConnectCiCam", "(I)I", (void *)android_media_tv_Tuner_connect_cicam },
    { "nativeLinkCiCam", "(I)I",
            (void *)android_media_tv_Tuner_link_cicam },
    { "nativeUnlinkCiCam", "(I)I",
            (void *)android_media_tv_Tuner_unlink_cicam },
    { "nativeDisconnectCiCam", "()I", (void *)android_media_tv_Tuner_disconnect_cicam },
    { "nativeGetFrontendInfo", "(I)Landroid/media/tv/tuner/frontend/FrontendInfo;",
            (void *)android_media_tv_Tuner_get_frontend_info },
    { "nativeOpenFilter", "(IIJ)Landroid/media/tv/tuner/filter/Filter;",
            (void *)android_media_tv_Tuner_open_filter },
    { "nativeOpenTimeFilter", "()Landroid/media/tv/tuner/filter/TimeFilter;",
            (void *)android_media_tv_Tuner_open_time_filter },
    { "nativeOpenLnbByHandle", "(I)Landroid/media/tv/tuner/Lnb;",
            (void *)android_media_tv_Tuner_open_lnb_by_handle },
    { "nativeOpenLnbByName", "(Ljava/lang/String;)Landroid/media/tv/tuner/Lnb;",
            (void *)android_media_tv_Tuner_open_lnb_by_name },
    { "nativeOpenDescramblerByHandle", "(I)Landroid/media/tv/tuner/Descrambler;",
            (void *)android_media_tv_Tuner_open_descrambler },
    { "nativeOpenDvrRecorder", "(J)Landroid/media/tv/tuner/dvr/DvrRecorder;",
            (void *)android_media_tv_Tuner_open_dvr_recorder },
    { "nativeOpenDvrPlayback", "(J)Landroid/media/tv/tuner/dvr/DvrPlayback;",
            (void *)android_media_tv_Tuner_open_dvr_playback },
    { "nativeGetDemuxCapabilities", "()Landroid/media/tv/tuner/DemuxCapabilities;",
            (void *)android_media_tv_Tuner_get_demux_caps },
    { "nativeOpenDemuxByhandle", "(I)I", (void *)android_media_tv_Tuner_open_demux },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_tuner },
    { "nativeCloseFrontend", "(I)I", (void *)android_media_tv_Tuner_close_frontend },
    { "nativeCloseDemux", "(I)I", (void *)android_media_tv_Tuner_close_demux },
};

static const JNINativeMethod gFilterMethods[] = {
    { "nativeConfigureFilter", "(IILandroid/media/tv/tuner/filter/FilterConfiguration;)I",
            (void *)android_media_tv_Tuner_configure_filter },
    { "nativeGetId", "()I", (void *)android_media_tv_Tuner_get_filter_id },
    { "nativeGetId64Bit", "()J",
            (void *)android_media_tv_Tuner_get_filter_64bit_id },
    { "nativeConfigureMonitorEvent", "(I)I",
            (void *)android_media_tv_Tuner_configure_monitor_event },
    { "nativeSetDataSource", "(Landroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_set_filter_data_source },
    { "nativeStartFilter", "()I", (void *)android_media_tv_Tuner_start_filter },
    { "nativeStopFilter", "()I", (void *)android_media_tv_Tuner_stop_filter },
    { "nativeFlushFilter", "()I", (void *)android_media_tv_Tuner_flush_filter },
    { "nativeRead", "([BJJ)I", (void *)android_media_tv_Tuner_read_filter_fmq },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_filter },
};

static const JNINativeMethod gTimeFilterMethods[] = {
    { "nativeSetTimestamp", "(J)I", (void *)android_media_tv_Tuner_time_filter_set_timestamp },
    { "nativeClearTimestamp", "()I", (void *)android_media_tv_Tuner_time_filter_clear_timestamp },
    { "nativeGetTimestamp", "()Ljava/lang/Long;",
            (void *)android_media_tv_Tuner_time_filter_get_timestamp },
    { "nativeGetSourceTime", "()Ljava/lang/Long;",
            (void *)android_media_tv_Tuner_time_filter_get_source_time },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_time_filter_close },
};

static const JNINativeMethod gDescramblerMethods[] = {
    { "nativeAddPid", "(IILandroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_descrambler_add_pid },
    { "nativeRemovePid", "(IILandroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_descrambler_remove_pid },
    { "nativeSetKeyToken", "([B)I", (void *)android_media_tv_Tuner_descrambler_set_key_token },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_descrambler },
};

static const JNINativeMethod gDvrRecorderMethods[] = {
    { "nativeAttachFilter", "(Landroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_attach_filter },
    { "nativeDetachFilter", "(Landroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_detach_filter },
    { "nativeConfigureDvr", "(Landroid/media/tv/tuner/dvr/DvrSettings;)I",
            (void *)android_media_tv_Tuner_configure_dvr },
    { "nativeStartDvr", "()I", (void *)android_media_tv_Tuner_start_dvr },
    { "nativeStopDvr", "()I", (void *)android_media_tv_Tuner_stop_dvr },
    { "nativeFlushDvr", "()I", (void *)android_media_tv_Tuner_flush_dvr },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_dvr },
    { "nativeSetFileDescriptor", "(I)V", (void *)android_media_tv_Tuner_dvr_set_fd },
    { "nativeWrite", "(J)J", (void *)android_media_tv_Tuner_write_dvr },
    { "nativeWrite", "([BJJ)J", (void *)android_media_tv_Tuner_write_dvr_to_array },
};

static const JNINativeMethod gDvrPlaybackMethods[] = {
    { "nativeAttachFilter", "(Landroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_attach_filter },
    { "nativeDetachFilter", "(Landroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_detach_filter },
    { "nativeConfigureDvr", "(Landroid/media/tv/tuner/dvr/DvrSettings;)I",
            (void *)android_media_tv_Tuner_configure_dvr },
    { "nativeStartDvr", "()I", (void *)android_media_tv_Tuner_start_dvr },
    { "nativeStopDvr", "()I", (void *)android_media_tv_Tuner_stop_dvr },
    { "nativeFlushDvr", "()I", (void *)android_media_tv_Tuner_flush_dvr },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_dvr },
    { "nativeSetFileDescriptor", "(I)V", (void *)android_media_tv_Tuner_dvr_set_fd },
    { "nativeRead", "(J)J", (void *)android_media_tv_Tuner_read_dvr },
    { "nativeRead", "([BJJ)J", (void *)android_media_tv_Tuner_read_dvr_from_array },
};

static const JNINativeMethod gLnbMethods[] = {
    { "nativeSetVoltage", "(I)I", (void *)android_media_tv_Tuner_lnb_set_voltage },
    { "nativeSetTone", "(I)I", (void *)android_media_tv_Tuner_lnb_set_tone },
    { "nativeSetSatellitePosition", "(I)I", (void *)android_media_tv_Tuner_lnb_set_position },
    { "nativeSendDiseqcMessage", "([B)I", (void *)android_media_tv_Tuner_lnb_send_diseqc_msg },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_lnb },
};

static const JNINativeMethod gMediaEventMethods[] = {
    { "nativeGetLinearBlock", "()Landroid/media/MediaCodec$LinearBlock;",
            (void *)android_media_tv_Tuner_media_event_get_linear_block },
    { "nativeGetAudioHandle", "()Ljava/lang/Long;",
            (void *)android_media_tv_Tuner_media_event_get_audio_handle },
    { "nativeFinalize", "()V",
            (void *)android_media_tv_Tuner_media_event_finalize },
};

static bool register_android_media_tv_Tuner(JNIEnv *env) {
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Tuner", gTunerMethods, NELEM(gTunerMethods)) != JNI_OK) {
        ALOGE("Failed to register tuner native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/filter/Filter",
            gFilterMethods,
            NELEM(gFilterMethods)) != JNI_OK) {
        ALOGE("Failed to register filter native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/filter/TimeFilter",
            gTimeFilterMethods,
            NELEM(gTimeFilterMethods)) != JNI_OK) {
        ALOGE("Failed to register time filter native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Descrambler",
            gDescramblerMethods,
            NELEM(gDescramblerMethods)) != JNI_OK) {
        ALOGE("Failed to register descrambler native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/dvr/DvrRecorder",
            gDvrRecorderMethods,
            NELEM(gDvrRecorderMethods)) != JNI_OK) {
        ALOGE("Failed to register dvr recorder native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/dvr/DvrPlayback",
            gDvrPlaybackMethods,
            NELEM(gDvrPlaybackMethods)) != JNI_OK) {
        ALOGE("Failed to register dvr playback native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Lnb",
            gLnbMethods,
            NELEM(gLnbMethods)) != JNI_OK) {
        ALOGE("Failed to register lnb native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/filter/MediaEvent",
            gMediaEventMethods,
            NELEM(gMediaEventMethods)) != JNI_OK) {
        ALOGE("Failed to register MediaEvent native methods");
        return false;
    }
    return true;
}

jint JNI_OnLoad(JavaVM* vm, void* /* reserved */)
{
    JNIEnv* env = NULL;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed\n");
        return result;
    }
    assert(env != NULL);

    if (!register_android_media_tv_Tuner(env)) {
        ALOGE("ERROR: Tuner native registration failed\n");
        return result;
    }
    return JNI_VERSION_1_4;
}
