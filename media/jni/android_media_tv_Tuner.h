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
using ::android::hardware::tv::tuner::V1_0::ITuner;
using ::android::hardware::tv::tuner::V1_0::LnbEventType;
using ::android::hardware::tv::tuner::V1_0::LnbId;
using ::android::hardware::tv::tuner::V1_0::PlaybackStatus;
using ::android::hardware::tv::tuner::V1_0::RecordStatus;

using FilterMQ = MessageQueue<uint8_t, kSynchronizedReadWrite>;

namespace android {

struct LnbCallback : public ILnbCallback {
    LnbCallback(jweak tunerObj, LnbId id);
    virtual Return<void> onEvent(LnbEventType lnbEventType);
    virtual Return<void> onDiseqcMessage(const hidl_vec<uint8_t>& diseqcMessage);
    jweak mObject;
    LnbId mId;
};

struct DvrCallback : public IDvrCallback {
    virtual Return<void> onRecordStatus(RecordStatus status);
    virtual Return<void> onPlaybackStatus(PlaybackStatus status);

    void setDvr(const jobject dvr);
private:
    jweak mDvr;
};

struct Dvr : public RefBase {
    Dvr(sp<IDvr> sp, jweak obj);
    sp<IDvr> getIDvr();
    sp<IDvr> mDvrSp;
    jweak mDvrObj;
};

struct FilterCallback : public IFilterCallback {
    virtual Return<void> onFilterEvent(const DemuxFilterEvent& filterEvent);
    virtual Return<void> onFilterStatus(const DemuxFilterStatus status);

    void setFilter(const jobject filter);
private:
    jweak mFilter;
};

struct FrontendCallback : public IFrontendCallback {
    FrontendCallback(jweak tunerObj, FrontendId id);

    virtual Return<void> onEvent(FrontendEventType frontendEventType);
    virtual Return<void> onDiseqcMessage(const hidl_vec<uint8_t>& diseqcMessage);
    virtual Return<void> onScanMessage(
            FrontendScanMessageType type, const FrontendScanMessage& message);

    jweak mObject;
    FrontendId mId;
};

struct Filter : public RefBase {
    Filter(sp<IFilter> sp, jweak obj);
    ~Filter();
    int close();
    sp<IFilter> getIFilter();
    sp<IFilter> mFilterSp;
    std::unique_ptr<FilterMQ> mFilterMQ;
    EventFlag* mFilterMQEventFlag;
    jweak mFilterObj;
};

struct JTuner : public RefBase {
    JTuner(JNIEnv *env, jobject thiz);
    sp<ITuner> getTunerService();
    jobject getFrontendIds();
    jobject openFrontendById(int id);
    int tune(const FrontendSettings& settings);
    int scan(const FrontendSettings& settings, FrontendScanType scanType);
    jobject getLnbIds();
    jobject openLnbById(int id);
    jobject openFilter(DemuxFilterType type, int bufferSize);
    jobject openDescrambler();
    jobject openDvr(DvrType type, int bufferSize);

protected:
    bool openDemux();
    virtual ~JTuner();

private:
    jclass mClass;
    jweak mObject;
    static sp<ITuner> mTuner;
    hidl_vec<FrontendId> mFeIds;
    sp<IFrontend> mFe;
    hidl_vec<LnbId> mLnbIds;
    sp<ILnb> mLnb;
    sp<IDemux> mDemux;
    int mDemuxId;
};

}  // namespace android

#endif  // _ANDROID_MEDIA_TV_TUNER_H_
