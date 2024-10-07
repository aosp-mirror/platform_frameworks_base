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

#include <C2BlockInternal.h>
#include <C2HandleIonInternal.h>
#include <C2ParamDef.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterEvent.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterMonitorEvent.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterStatus.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterType.h>
#include <aidl/android/hardware/tv/tuner/DemuxPid.h>
#include <aidl/android/hardware/tv/tuner/DvrType.h>
#include <aidl/android/hardware/tv/tuner/FrontendCapabilities.h>
#include <aidl/android/hardware/tv/tuner/FrontendEventType.h>
#include <aidl/android/hardware/tv/tuner/FrontendInfo.h>
#include <aidl/android/hardware/tv/tuner/FrontendScanMessage.h>
#include <aidl/android/hardware/tv/tuner/FrontendScanMessageType.h>
#include <aidl/android/hardware/tv/tuner/FrontendScanType.h>
#include <aidl/android/hardware/tv/tuner/FrontendSettings.h>
#include <aidl/android/hardware/tv/tuner/LnbEventType.h>
#include <aidl/android/hardware/tv/tuner/PlaybackStatus.h>
#include <aidl/android/hardware/tv/tuner/RecordStatus.h>
#include <aidl/android/hardware/tv/tuner/Result.h>
#include <fmq/AidlMessageQueue.h>
#include <utils/Mutex.h>
#include <utils/RefBase.h>

#include <fstream>
#include <string>
#include <unordered_map>

#include "jni.h"
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

using ::aidl::android::hardware::common::fmq::MQDescriptor;
using ::aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using ::aidl::android::hardware::tv::tuner::DemuxFilterEvent;
using ::aidl::android::hardware::tv::tuner::DemuxFilterMonitorEvent;
using ::aidl::android::hardware::tv::tuner::DemuxFilterStatus;
using ::aidl::android::hardware::tv::tuner::DemuxFilterType;
using ::aidl::android::hardware::tv::tuner::DemuxPid;
using ::aidl::android::hardware::tv::tuner::DvrType;
using ::aidl::android::hardware::tv::tuner::FrontendCapabilities;
using ::aidl::android::hardware::tv::tuner::FrontendEventType;
using ::aidl::android::hardware::tv::tuner::FrontendInfo;
using ::aidl::android::hardware::tv::tuner::FrontendScanMessage;
using ::aidl::android::hardware::tv::tuner::FrontendScanMessageType;
using ::aidl::android::hardware::tv::tuner::FrontendScanType;
using ::aidl::android::hardware::tv::tuner::FrontendSettings;
using ::aidl::android::hardware::tv::tuner::LnbEventType;
using ::aidl::android::hardware::tv::tuner::PlaybackStatus;
using ::aidl::android::hardware::tv::tuner::RecordStatus;
using ::aidl::android::hardware::tv::tuner::Result;
using ::android::hardware::EventFlag;

using MQ = MQDescriptor<int8_t, SynchronizedReadWrite>;

namespace android {

struct LnbClientCallbackImpl : public LnbClientCallback {
    ~LnbClientCallbackImpl();
    virtual void onEvent(LnbEventType lnbEventType);
    virtual void onDiseqcMessage(const vector<uint8_t>& diseqcMessage);

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
    MediaEvent(sp<FilterClient> filterClient, native_handle_t* avHandle, int64_t dataId,
               int64_t dataSize, jobject obj);
    ~MediaEvent();
    jobject getLinearBlock();
    int64_t getAudioHandle();
    void finalize();

    sp<FilterClient> mFilterClient;
    native_handle_t* mAvHandle;
    int64_t mDataId;
    int64_t mDataSize;
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
    FilterClientCallbackImpl();
    ~FilterClientCallbackImpl();
    virtual void onFilterEvent(const vector<DemuxFilterEvent>& events);
    virtual void onFilterStatus(const DemuxFilterStatus status);

    void setFilter(jweak filterObj, sp<FilterClient> filterClient);
    void setSharedFilter(jweak filterObj, sp<FilterClient> filterClient);

private:
    jweak mFilterObj;
    sp<FilterClient> mFilterClient;
    android::Mutex mLock;
    jclass mEventClass;
    jclass mSectionEventClass;
    jclass mMediaEventClass;
    jclass mAudioDescriptorClass;
    jclass mPesEventClass;
    jclass mTsRecordEventClass;
    jclass mMmtpRecordEventClass;
    jclass mDownloadEventClass;
    jclass mIpPayloadEventClass;
    jclass mTemiEventClass;
    jclass mScramblingStatusEventClass;
    jclass mIpCidChangeEventClass;
    jclass mRestartEventClass;
    jmethodID mSectionEventInitID;
    jmethodID mMediaEventInitID;
    jmethodID mAudioDescriptorInitID;
    jmethodID mPesEventInitID;
    jmethodID mTsRecordEventInitID;
    jmethodID mMmtpRecordEventInitID;
    jmethodID mDownloadEventInitID;
    jmethodID mIpPayloadEventInitID;
    jmethodID mTemiEventInitID;
    jmethodID mScramblingStatusEventInitID;
    jmethodID mIpCidChangeEventInitID;
    jmethodID mRestartEventInitID;
    jfieldID mMediaEventFieldContextID;
    bool mSharedFilter;
    void getSectionEvent(const jobjectArray& arr, const int size, const DemuxFilterEvent& event);
    void getMediaEvent(const jobjectArray& arr, const int size, const DemuxFilterEvent& event);
    void getPesEvent(const jobjectArray& arr, const int size, const DemuxFilterEvent& event);
    void getTsRecordEvent(const jobjectArray& arr, const int size, const DemuxFilterEvent& event);
    void getMmtpRecordEvent(const jobjectArray& arr, const int size, const DemuxFilterEvent& event);
    void getDownloadEvent(const jobjectArray& arr, const int size, const DemuxFilterEvent& event);
    void getIpPayloadEvent(const jobjectArray& arr, const int size, const DemuxFilterEvent& event);
    void getTemiEvent(const jobjectArray& arr, const int size, const DemuxFilterEvent& event);
    void getScramblingStatusEvent(const jobjectArray& arr, const int size,
                                  const DemuxFilterEvent& event);
    void getIpCidChangeEvent(const jobjectArray& arr, const int size,
                             const DemuxFilterEvent& event);
    void getRestartEvent(const jobjectArray& arr, const int size, const DemuxFilterEvent& event);
};

struct JTuner;
struct FrontendClientCallbackImpl : public FrontendClientCallback {
    FrontendClientCallbackImpl(JTuner*, jweak);
    ~FrontendClientCallbackImpl();
    virtual void onEvent(FrontendEventType frontendEventType);
    virtual void onScanMessage(
            FrontendScanMessageType type, const FrontendScanMessage& message);

    void executeOnScanMessage(JNIEnv *env, const jclass& clazz, const jobject& frontend,
                              FrontendScanMessageType type,
                              const FrontendScanMessage& message);
    void addCallbackListener(JTuner*, jweak obj);
    void removeCallbackListener(JTuner* jtuner);
    std::unordered_map<JTuner*, jweak> mListenersMap;
    std::mutex mMutex;
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
    int unshareFrontend();
    void registerFeCbListener(JTuner* jtuner);
    void unregisterFeCbListener(JTuner* jtuner);
    void updateFrontend(JTuner* jtuner);
    jint closeFrontendById(int id);
    jobject getFrontendInfo(int id);
    int tune(const FrontendSettings& settings);
    int stopTune();
    int scan(const FrontendSettings& settings, FrontendScanType scanType);
    int stopScan();
    int setLnb(sp<LnbClient> lnbClient);
    bool isLnaSupported();
    int setLna(bool enable);
    jobject openLnbByHandle(int handle);
    jobject openLnbByName(jstring name);
    jobject openFilter(DemuxFilterType type, int bufferSize);
    jobject openTimeFilter();
    jobject openDescrambler();
    jobject openDvr(DvrType type, jlong bufferSize);
    jobject getDemuxCaps();
    jobject getDemuxInfo(int handle);
    jobject getFrontendStatus(jintArray types);
    Result openDemux(int handle);
    jint close();
    jint closeFrontend();
    jint closeDemux();
    Result getFrontendHardwareInfo(string& info);
    jint setMaxNumberOfFrontends(int32_t frontendType, int32_t maxNumber);
    int32_t getMaxNumberOfFrontends(int32_t frontendType);
    jint removeOutputPid(int32_t pid);
    jobjectArray getFrontendStatusReadiness(jintArray types);

    jweak getObject();

protected:
    virtual ~JTuner();

private:
    jclass mClass;
    jweak mObject;
    static sp<TunerClient> sTunerClient;
    static std::mutex sTunerClientMutex;
    sp<FrontendClient> mFeClient;
    sp<FrontendClientCallbackImpl> mFeClientCb;
    int mFeId;
    int mSharedFeId;
    sp<LnbClient> mLnbClient;
    sp<DemuxClient> mDemuxClient;
    static jobject getAnalogFrontendCaps(JNIEnv* env, FrontendCapabilities& caps);
    static jobject getAtsc3FrontendCaps(JNIEnv* env, FrontendCapabilities& caps);
    static jobject getAtscFrontendCaps(JNIEnv* env, FrontendCapabilities& caps);
    static jobject getDvbcFrontendCaps(JNIEnv* env, FrontendCapabilities& caps);
    static jobject getDvbsFrontendCaps(JNIEnv* env, FrontendCapabilities& caps);
    static jobject getDvbtFrontendCaps(JNIEnv* env, FrontendCapabilities& caps);
    static jobject getIsdbs3FrontendCaps(JNIEnv* env, FrontendCapabilities& caps);
    static jobject getIsdbsFrontendCaps(JNIEnv* env, FrontendCapabilities& caps);
    static jobject getIsdbtFrontendCaps(JNIEnv* env, FrontendCapabilities& caps);
    static jobject getDtmbFrontendCaps(JNIEnv* env, FrontendCapabilities& caps);
    static jobject getIptvFrontendCaps(JNIEnv* env, FrontendCapabilities& caps);
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
