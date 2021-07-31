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

#ifndef _ANDROID_MEDIA_TV_TUNER_H_
#define _ANDROID_MEDIA_TV_TUNER_H_

#include <android/hardware/tv/tuner/1.1/types.h>

#include <C2BlockInternal.h>
#include <C2HandleIonInternal.h>
#include <C2ParamDef.h>
#include <fmq/MessageQueue.h>
#include <fstream>
#include <string>
#include <unordered_map>
#include <utils/Mutex.h>
#include <utils/RefBase.h>

#include "tuner/DemuxClient.h"
#include "tuner/DescramblerClient.h"
#include "tuner/FilterClient.h"
#include "tuner/FilterClientCallback.h"
#include "tuner/FrontendClient.h"
#include "tuner/FrontendClientCallback.h"
#include "tuner/LnbClient.h"
#include "tuner/LnbClientCallback.h"
#include "tuner/TimeFilterClient.h"
#include "tuner/TunerClient.h"
#include "jni.h"

using ::android::hardware::EventFlag;
using ::android::hardware::MQDescriptorSync;
using ::android::hardware::MessageQueue;
using ::android::hardware::Return;
using ::android::hardware::hidl_handle;
using ::android::hardware::hidl_vec;
using ::android::hardware::kSynchronizedReadWrite;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterEvent;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterStatus;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterType;
using ::android::hardware::tv::tuner::V1_0::DemuxPid;
using ::android::hardware::tv::tuner::V1_0::DvrType;
using ::android::hardware::tv::tuner::V1_0::FrontendEventType;
using ::android::hardware::tv::tuner::V1_0::FrontendId;
using ::android::hardware::tv::tuner::V1_0::FrontendInfo;
using ::android::hardware::tv::tuner::V1_0::FrontendScanMessage;
using ::android::hardware::tv::tuner::V1_0::FrontendScanMessageType;
using ::android::hardware::tv::tuner::V1_0::FrontendScanType;
using ::android::hardware::tv::tuner::V1_0::FrontendSettings;
using ::android::hardware::tv::tuner::V1_1::FrontendSettingsExt1_1;
using ::android::hardware::tv::tuner::V1_0::LnbEventType;
using ::android::hardware::tv::tuner::V1_0::LnbId;
using ::android::hardware::tv::tuner::V1_0::PlaybackStatus;
using ::android::hardware::tv::tuner::V1_0::RecordStatus;
using ::android::hardware::tv::tuner::V1_0::Result;
using ::android::hardware::tv::tuner::V1_1::DemuxFilterEventExt;
using ::android::hardware::tv::tuner::V1_1::DemuxFilterMonitorEvent;
using ::android::hardware::tv::tuner::V1_1::FrontendScanMessageExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendScanMessageTypeExt1_1;

using MQ = MessageQueue<uint8_t, kSynchronizedReadWrite>;

const static int TUNER_VERSION_1_1 = ((1 << 16) | 1);

namespace android {

struct LnbClientCallbackImpl : public LnbClientCallback {
    ~LnbClientCallbackImpl();
    virtual void onEvent(LnbEventType lnbEventType);
    virtual void onDiseqcMessage(const hidl_vec<uint8_t>& diseqcMessage);

    void setLnb(jweak lnbObj);
private:
    jweak mLnbObj;
};

struct DvrClientCallbackImpl : public DvrClientCallback {
    ~DvrClientCallbackImpl();
    virtual void onRecordStatus(RecordStatus status);
    virtual void onPlaybackStatus(PlaybackStatus status);

    void setDvr(jweak dvrObj);
private:
    jweak mDvrObj;
};

struct MediaEvent : public RefBase {
    MediaEvent(sp<FilterClient> filterClient, hidl_handle avHandle, uint64_t dataId,
        uint64_t dataSize, jobject obj);
    ~MediaEvent();
    jobject getLinearBlock();
    uint64_t getAudioHandle();
    void finalize();

    sp<FilterClient> mFilterClient;
    native_handle_t* mAvHandle;
    uint64_t mDataId;
    uint64_t mDataSize;
    uint8_t* mBuffer;
    android::Mutex mLock;
    int mDataIdRefCnt;
    int mAvHandleRefCnt;
    jweak mMediaEventObj;
    jweak mLinearBlockObj;
    C2HandleIon* mIonHandle;
    std::weak_ptr<C2Buffer> mC2Buffer;
};

struct FilterClientCallbackImpl : public FilterClientCallback {
    ~FilterClientCallbackImpl();
    virtual void onFilterEvent_1_1(const DemuxFilterEvent& filterEvent,
            const DemuxFilterEventExt& filterEventExt);
    virtual void onFilterEvent(const DemuxFilterEvent& filterEvent);
    virtual void onFilterStatus(const DemuxFilterStatus status);

    void setFilter(jweak filterObj, sp<FilterClient> filterClient);
private:
    jweak mFilterObj;
    sp<FilterClient> mFilterClient;
    jobjectArray getSectionEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getMediaEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getPesEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getTsRecordEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>&events,
                    const std::vector<DemuxFilterEventExt::Event>& eventsExt);
    jobjectArray getMmtpRecordEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>&events,
                    const std::vector<DemuxFilterEventExt::Event>& eventsExt);
    jobjectArray getDownloadEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getIpPayloadEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getTemiEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getScramblingStatusEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEventExt::Event>& eventsExt);
    jobjectArray getIpCidChangeEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEventExt::Event>& eventsExt);
    jobjectArray getRestartEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEventExt::Event>& eventsExt);
};

struct FrontendClientCallbackImpl : public FrontendClientCallback {
    FrontendClientCallbackImpl(jweak tunerObj);
    ~FrontendClientCallbackImpl();
    virtual void onEvent(FrontendEventType frontendEventType);
    virtual void onScanMessage(
            FrontendScanMessageType type, const FrontendScanMessage& message);
    virtual void onScanMessageExt1_1(
            FrontendScanMessageTypeExt1_1 type, const FrontendScanMessageExt1_1& messageExt);

    jweak mObject;
};

struct JTuner : public RefBase {
    JTuner(JNIEnv *env, jobject thiz);
    int getTunerVersion();
    jobject getAvSyncHwId(sp<FilterClient> filter);
    jobject getAvSyncTime(jint id);
    int connectCiCam(jint id);
    int linkCiCam(jint id);
    int disconnectCiCam();
    int unlinkCiCam(jint id);
    jobject getFrontendIds();
    jobject openFrontendByHandle(int feHandle);
    int shareFrontend(int feId);
    jint closeFrontendById(int id);
    jobject getFrontendInfo(int id);
    int tune(const FrontendSettings& settings, const FrontendSettingsExt1_1& settingsExt1_1);
    int stopTune();
    int scan(const FrontendSettings& settings, FrontendScanType scanType,
            const FrontendSettingsExt1_1& settingsExt1_1);
    int stopScan();
    int setLnb(sp<LnbClient> lnbClient);
    int setLna(bool enable);
    jobject openLnbByHandle(int handle);
    jobject openLnbByName(jstring name);
    jobject openFilter(DemuxFilterType type, int bufferSize);
    jobject openTimeFilter();
    jobject openDescrambler();
    jobject openDvr(DvrType type, jlong bufferSize);
    jobject getDemuxCaps();
    jobject getFrontendStatus(jintArray types);
    Result openDemux(int handle);
    jint close();
    jint closeFrontend();
    jint closeDemux();

protected:
    virtual ~JTuner();

private:
    jclass mClass;
    jweak mObject;
    static sp<TunerClient> mTunerClient;
    sp<FrontendClient> mFeClient;
    int mFeId;
    int mSharedFeId;
    sp<LnbClient> mLnbClient;
    sp<DemuxClient> mDemuxClient;
    static jobject getAnalogFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps);
    static jobject getAtsc3FrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps);
    static jobject getAtscFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps);
    static jobject getDvbcFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps);
    static jobject getDvbsFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps);
    static jobject getDvbtFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps);
    static jobject getIsdbs3FrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps);
    static jobject getIsdbsFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps);
    static jobject getIsdbtFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities& caps);
    static jobject getDtmbFrontendCaps(JNIEnv *env, int id);

    bool isV1_1ExtendedStatusType(jint type);
};

class C2DataIdInfo : public C2Param {
public:
    C2DataIdInfo(uint32_t index, uint64_t value);
private:
    typedef C2GlobalParam<C2Info, C2Int64Value, 0> StubInfo;
    StubInfo mInfo;
    static const size_t kParamSize = sizeof(StubInfo);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_TV_TUNER_H_
