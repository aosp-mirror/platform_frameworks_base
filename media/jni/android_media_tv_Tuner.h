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

#include <android/hardware/tv/tuner/1.0/ITuner.h>
#include <fmq/MessageQueue.h>
#include <fstream>
#include <string>
#include <unordered_map>
#include <utils/RefBase.h>

#include "jni.h"

using ::android::hardware::EventFlag;
using ::android::hardware::MQDescriptorSync;
using ::android::hardware::MessageQueue;
using ::android::hardware::Return;
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
using ::android::hardware::tv::tuner::V1_0::IDemux;
using ::android::hardware::tv::tuner::V1_0::IDescrambler;
using ::android::hardware::tv::tuner::V1_0::IDvr;
using ::android::hardware::tv::tuner::V1_0::IDvrCallback;
using ::android::hardware::tv::tuner::V1_0::IFilter;
using ::android::hardware::tv::tuner::V1_0::IFilterCallback;
using ::android::hardware::tv::tuner::V1_0::IFrontend;
using ::android::hardware::tv::tuner::V1_0::IFrontendCallback;
using ::android::hardware::tv::tuner::V1_0::ILnb;
using ::android::hardware::tv::tuner::V1_0::ILnbCallback;
using ::android::hardware::tv::tuner::V1_0::ITimeFilter;
using ::android::hardware::tv::tuner::V1_0::ITuner;
using ::android::hardware::tv::tuner::V1_0::LnbEventType;
using ::android::hardware::tv::tuner::V1_0::LnbId;
using ::android::hardware::tv::tuner::V1_0::PlaybackStatus;
using ::android::hardware::tv::tuner::V1_0::RecordStatus;
using ::android::hardware::tv::tuner::V1_0::Result;

using MQ = MessageQueue<uint8_t, kSynchronizedReadWrite>;

namespace android {

struct LnbCallback : public ILnbCallback {
    LnbCallback(jweak tunerObj, LnbId id);
    virtual Return<void> onEvent(LnbEventType lnbEventType);
    virtual Return<void> onDiseqcMessage(const hidl_vec<uint8_t>& diseqcMessage);
    jweak mLnb;
    LnbId mId;
};

struct Lnb : public RefBase {
    Lnb(sp<ILnb> sp, jobject obj);
    ~Lnb();
    sp<ILnb> getILnb();
    sp<ILnb> mLnbSp;
    jweak mLnbObj;
};

struct DvrCallback : public IDvrCallback {
    ~DvrCallback();
    virtual Return<void> onRecordStatus(RecordStatus status);
    virtual Return<void> onPlaybackStatus(PlaybackStatus status);

    void setDvr(const jobject dvr);
private:
    jweak mDvr;
};

struct Dvr : public RefBase {
    Dvr(sp<IDvr> sp, jweak obj);
    ~Dvr();
    jint close();
    MQ& getDvrMQ();
    sp<IDvr> getIDvr();
    sp<IDvr> mDvrSp;
    jweak mDvrObj;
    std::unique_ptr<MQ> mDvrMQ;
    EventFlag* mDvrMQEventFlag;
    std::string mFilePath;
    int mFd;
};

struct FilterCallback : public IFilterCallback {
    ~FilterCallback();
    virtual Return<void> onFilterEvent(const DemuxFilterEvent& filterEvent);
    virtual Return<void> onFilterStatus(const DemuxFilterStatus status);

    void setFilter(const jobject filter);
    jobject handleToLinearBlock(const native_handle_t* handle, uint32_t size);
private:
    jweak mFilter;
    jobjectArray getSectionEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getMediaEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getPesEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getTsRecordEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getMmtpRecordEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getDownloadEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getIpPayloadEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
    jobjectArray getTemiEvent(
            jobjectArray& arr, const std::vector<DemuxFilterEvent::Event>& events);
};

struct FrontendCallback : public IFrontendCallback {
    FrontendCallback(jweak tunerObj, FrontendId id);

    virtual Return<void> onEvent(FrontendEventType frontendEventType);
    virtual Return<void> onScanMessage(
            FrontendScanMessageType type, const FrontendScanMessage& message);

    jweak mObject;
    FrontendId mId;
};

struct Filter : public RefBase {
    Filter(sp<IFilter> sp, jobject obj);
    ~Filter();
    int close();
    sp<IFilter> getIFilter();
    sp<IFilter> mFilterSp;
    std::unique_ptr<MQ> mFilterMQ;
    EventFlag* mFilterMQEventFlag;
    jweak mFilterObj;
};

struct TimeFilter : public RefBase {
    TimeFilter(sp<ITimeFilter> sp, jweak obj);
    ~TimeFilter();
    sp<ITimeFilter> getITimeFilter();
    sp<ITimeFilter> mTimeFilterSp;
    jweak mTimeFilterObj;
};

struct JTuner : public RefBase {
    JTuner(JNIEnv *env, jobject thiz);
    sp<ITuner> getTunerService();
    jobject getAvSyncHwId(sp<Filter> filter);
    jobject getAvSyncTime(jint id);
    int connectCiCam(jint id);
    int disconnectCiCam();
    jobject getFrontendIds();
    jobject openFrontendById(int id);
    jobject getFrontendInfo(int id);
    int tune(const FrontendSettings& settings);
    int stopTune();
    int scan(const FrontendSettings& settings, FrontendScanType scanType);
    int stopScan();
    int setLnb(int id);
    int setLna(bool enable);
    jobject getLnbIds();
    jobject openLnbById(int id);
    jobject openLnbByName(jstring name);
    jobject openFilter(DemuxFilterType type, int bufferSize);
    jobject openTimeFilter();
    jobject openDescrambler();
    jobject openDvr(DvrType type, jlong bufferSize);
    jobject getDemuxCaps();
    jobject getFrontendStatus(jintArray types);

protected:
    Result openDemux();
    virtual ~JTuner();

private:
    jclass mClass;
    jweak mObject;
    static sp<ITuner> mTuner;
    hidl_vec<FrontendId> mFeIds;
    sp<IFrontend> mFe;
    int mFeId;
    hidl_vec<LnbId> mLnbIds;
    sp<ILnb> mLnb;
    sp<IDemux> mDemux;
    uint32_t mDemuxId;
    static jobject getAnalogFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities caps);
    static jobject getAtsc3FrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities caps);
    static jobject getAtscFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities caps);
    static jobject getDvbcFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities caps);
    static jobject getDvbsFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities caps);
    static jobject getDvbtFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities caps);
    static jobject getIsdbs3FrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities caps);
    static jobject getIsdbsFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities caps);
    static jobject getIsdbtFrontendCaps(JNIEnv *env, FrontendInfo::FrontendCapabilities caps);
};

}  // namespace android

#endif  // _ANDROID_MEDIA_TV_TUNER_H_
