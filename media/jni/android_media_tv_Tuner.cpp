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

#include "android_media_tv_Tuner.h"
#include "android_runtime/AndroidRuntime.h"

#include <android/hardware/tv/tuner/1.0/ITuner.h>
#include <media/stagefright/foundation/ADebug.h>

#pragma GCC diagnostic ignored "-Wunused-function"

using ::android::hardware::Void;
using ::android::hardware::hidl_vec;
using ::android::hardware::tv::tuner::V1_0::DataFormat;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterMainType;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterPesDataSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxMmtpPid;
using ::android::hardware::tv::tuner::V1_0::DemuxQueueNotifyBits;
using ::android::hardware::tv::tuner::V1_0::DemuxTpid;
using ::android::hardware::tv::tuner::V1_0::DemuxTsFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxTsFilterType;
using ::android::hardware::tv::tuner::V1_0::DvrSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendAnalogSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendAnalogSifStandard;
using ::android::hardware::tv::tuner::V1_0::FrontendAnalogType;
using ::android::hardware::tv::tuner::V1_0::ITuner;
using ::android::hardware::tv::tuner::V1_0::PlaybackSettings;
using ::android::hardware::tv::tuner::V1_0::RecordSettings;
using ::android::hardware::tv::tuner::V1_0::Result;

struct fields_t {
    jfieldID tunerContext;
    jfieldID filterContext;
    jfieldID descramblerContext;
    jfieldID dvrContext;
    jmethodID frontendInitID;
    jmethodID filterInitID;
    jmethodID dvrInitID;
    jmethodID onFrontendEventID;
    jmethodID onFilterStatusID;
    jmethodID lnbInitID;
    jmethodID onLnbEventID;
    jmethodID descramblerInitID;
};

static fields_t gFields;

namespace android {
/////////////// LnbCallback ///////////////////////
LnbCallback::LnbCallback(jweak tunerObj, LnbId id) : mObject(tunerObj), mId(id) {}

Return<void> LnbCallback::onEvent(LnbEventType lnbEventType) {
    ALOGD("LnbCallback::onEvent, type=%d", lnbEventType);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(
            mObject,
            gFields.onLnbEventID,
            (jint)lnbEventType);
    return Void();
}
Return<void> LnbCallback::onDiseqcMessage(const hidl_vec<uint8_t>& /*diseqcMessage*/) {
    ALOGD("LnbCallback::onDiseqcMessage");
    return Void();
}

/////////////// DvrCallback ///////////////////////
Return<void> DvrCallback::onRecordStatus(RecordStatus /*status*/) {
    ALOGD("DvrCallback::onRecordStatus");
    return Void();
}

Return<void> DvrCallback::onPlaybackStatus(PlaybackStatus /*status*/) {
    ALOGD("DvrCallback::onPlaybackStatus");
    return Void();
}

void DvrCallback::setDvr(const jobject dvr) {
    ALOGD("FilterCallback::setDvr");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    mDvr = env->NewWeakGlobalRef(dvr);
}

/////////////// Dvr ///////////////////////

Dvr::Dvr(sp<IDvr> sp, jweak obj) : mDvrSp(sp), mDvrObj(obj) {}

sp<IDvr> Dvr::getIDvr() {
    return mDvrSp;
}

/////////////// FilterCallback ///////////////////////
//TODO: implement filter callback
Return<void> FilterCallback::onFilterEvent(const DemuxFilterEvent& /*filterEvent*/) {
    ALOGD("FilterCallback::onFilterEvent");
    return Void();
}

Return<void> FilterCallback::onFilterStatus(const DemuxFilterStatus status) {
    ALOGD("FilterCallback::onFilterStatus");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(
            mFilter,
            gFields.onFilterStatusID,
            (jint)status);
    return Void();
}

void FilterCallback::setFilter(const jobject filter) {
    ALOGD("FilterCallback::setFilter");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    mFilter = env->NewWeakGlobalRef(filter);
}

/////////////// Filter ///////////////////////

Filter::Filter(sp<IFilter> sp, jweak obj) : mFilterSp(sp), mFilterObj(obj) {}

Filter::~Filter() {
    EventFlag::deleteEventFlag(&mFilterMQEventFlag);
}

int Filter::close() {
    Result r = mFilterSp->close();
    if (r == Result::SUCCESS) {
        EventFlag::deleteEventFlag(&mFilterMQEventFlag);
    }
    return (int)r;
}

sp<IFilter> Filter::getIFilter() {
    return mFilterSp;
}

/////////////// FrontendCallback ///////////////////////

FrontendCallback::FrontendCallback(jweak tunerObj, FrontendId id) : mObject(tunerObj), mId(id) {}

Return<void> FrontendCallback::onEvent(FrontendEventType frontendEventType) {
    ALOGD("FrontendCallback::onEvent, type=%d", frontendEventType);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->CallVoidMethod(
            mObject,
            gFields.onFrontendEventID,
            (jint)frontendEventType);
    return Void();
}
Return<void> FrontendCallback::onDiseqcMessage(const hidl_vec<uint8_t>& /*diseqcMessage*/) {
    ALOGD("FrontendCallback::onDiseqcMessage");
    return Void();
}

Return<void> FrontendCallback::onScanMessage(
        FrontendScanMessageType type, const FrontendScanMessage& /*message*/) {
    ALOGD("FrontendCallback::onScanMessage, type=%d", type);
    return Void();
}

/////////////// Tuner ///////////////////////

sp<ITuner> JTuner::mTuner;

JTuner::JTuner(JNIEnv *env, jobject thiz)
    : mClass(NULL) {
    jclass clazz = env->GetObjectClass(thiz);
    CHECK(clazz != NULL);

    mClass = (jclass)env->NewGlobalRef(clazz);
    mObject = env->NewWeakGlobalRef(thiz);
    if (mTuner == NULL) {
        mTuner = getTunerService();
    }
}

JTuner::~JTuner() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteGlobalRef(mClass);
    mTuner = NULL;
    mClass = NULL;
    mObject = NULL;
}

sp<ITuner> JTuner::getTunerService() {
    if (mTuner == nullptr) {
        mTuner = ITuner::getService();

        if (mTuner == nullptr) {
            ALOGW("Failed to get tuner service.");
        }
    }
    return mTuner;
}

jobject JTuner::getFrontendIds() {
    ALOGD("JTuner::getFrontendIds()");
    mTuner->getFrontendIds([&](Result, const hidl_vec<FrontendId>& frontendIds) {
        mFeIds = frontendIds;
    });
    if (mFeIds.size() == 0) {
        ALOGW("Frontend isn't available");
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass arrayListClazz = env->FindClass("java/util/ArrayList");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClazz, "add", "(Ljava/lang/Object;)Z");
    jobject obj = env->NewObject(arrayListClazz, env->GetMethodID(arrayListClazz, "<init>", "()V"));

    jclass integerClazz = env->FindClass("java/lang/Integer");
    jmethodID intInit = env->GetMethodID(integerClazz, "<init>", "(I)V");

    for (int i=0; i < mFeIds.size(); i++) {
       jobject idObj = env->NewObject(integerClazz, intInit, mFeIds[i]);
       env->CallBooleanMethod(obj, arrayListAdd, idObj);
    }
    return obj;
}

jobject JTuner::openFrontendById(int id) {
    sp<IFrontend> fe;
    mTuner->openFrontendById(id, [&](Result, const sp<IFrontend>& frontend) {
        fe = frontend;
    });
    if (fe == nullptr) {
        ALOGE("Failed to open frontend");
        return NULL;
    }
    mFe = fe;
    sp<FrontendCallback> feCb = new FrontendCallback(mObject, id);
    fe->setCallback(feCb);

    jint jId = (jint) id;

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    // TODO: add more fields to frontend
    return env->NewObject(
            env->FindClass("android/media/tv/tuner/Tuner$Frontend"),
            gFields.frontendInitID,
            mObject,
            (jint) jId);
}

jobject JTuner::getLnbIds() {
    ALOGD("JTuner::getLnbIds()");
    mTuner->getLnbIds([&](Result, const hidl_vec<FrontendId>& lnbIds) {
        mLnbIds = lnbIds;
    });
    if (mLnbIds.size() == 0) {
        ALOGW("Lnb isn't available");
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass arrayListClazz = env->FindClass("java/util/ArrayList");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClazz, "add", "(Ljava/lang/Object;)Z");
    jobject obj = env->NewObject(arrayListClazz, env->GetMethodID(arrayListClazz, "<init>", "()V"));

    jclass integerClazz = env->FindClass("java/lang/Integer");
    jmethodID intInit = env->GetMethodID(integerClazz, "<init>", "(I)V");

    for (int i=0; i < mLnbIds.size(); i++) {
       jobject idObj = env->NewObject(integerClazz, intInit, mLnbIds[i]);
       env->CallBooleanMethod(obj, arrayListAdd, idObj);
    }
    return obj;
}

jobject JTuner::openLnbById(int id) {
    sp<ILnb> lnbSp;
    mTuner->openLnbById(id, [&](Result, const sp<ILnb>& lnb) {
        lnbSp = lnb;
    });
    if (lnbSp == nullptr) {
        ALOGE("Failed to open lnb");
        return NULL;
    }
    mLnb = lnbSp;
    sp<LnbCallback> lnbCb = new LnbCallback(mObject, id);
    mLnb->setCallback(lnbCb);

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    return env->NewObject(
            env->FindClass("android/media/tv/tuner/Tuner$Lnb"),
            gFields.lnbInitID,
            mObject,
            id);
}

int JTuner::tune(const FrontendSettings& settings) {
    if (mFe == NULL) {
        ALOGE("frontend is not initialized");
        return (int)Result::INVALID_STATE;
    }
    Result result = mFe->tune(settings);
    return (int)result;
}

int JTuner::scan(const FrontendSettings& settings, FrontendScanType scanType) {
    if (mFe == NULL) {
        ALOGE("frontend is not initialized");
        return (int)Result::INVALID_STATE;
    }
    Result result = mFe->scan(settings, scanType);
    return (int)result;
}

bool JTuner::openDemux() {
    if (mTuner == nullptr) {
        return false;
    }
    if (mDemux != nullptr) {
        return true;
    }
    mTuner->openDemux([&](Result, uint32_t demuxId, const sp<IDemux>& demux) {
        mDemux = demux;
        mDemuxId = demuxId;
        ALOGD("open demux, id = %d", demuxId);
    });
    if (mDemux == nullptr) {
        return false;
    }
    return true;
}

jobject JTuner::openDescrambler() {
    ALOGD("JTuner::openDescrambler");
    if (mTuner == nullptr) {
        return NULL;
    }
    sp<IDescrambler> descramblerSp;
    mTuner->openDescrambler([&](Result, const sp<IDescrambler>& descrambler) {
        descramblerSp = descrambler;
    });

    if (descramblerSp == NULL) {
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject descramblerObj =
            env->NewObject(
                    env->FindClass("android/media/tv/tuner/Tuner$Descrambler"),
                    gFields.descramblerInitID,
                    mObject);

    descramblerSp->incStrong(descramblerObj);
    env->SetLongField(descramblerObj, gFields.descramblerContext, (jlong)descramblerSp.get());

    return descramblerObj;
}

jobject JTuner::openFilter(DemuxFilterType type, int bufferSize) {
    if (mDemux == NULL) {
        if (!openDemux()) {
            return NULL;
        }
    }

    sp<IFilter> iFilterSp;
    sp<FilterCallback> callback = new FilterCallback();
    mDemux->openFilter(type, bufferSize, callback,
            [&](Result, const sp<IFilter>& filter) {
                iFilterSp = filter;
            });
    if (iFilterSp == NULL) {
        ALOGD("Failed to open filter, type = %d", type.mainType);
        return NULL;
    }
    int fId;
    iFilterSp->getId([&](Result, uint32_t filterId) {
        fId = filterId;
    });

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject filterObj =
            env->NewObject(
                    env->FindClass("android/media/tv/tuner/Tuner$Filter"),
                    gFields.filterInitID,
                    mObject,
                    (jint) fId);

    sp<Filter> filterSp = new Filter(iFilterSp, filterObj);
    filterSp->incStrong(filterObj);
    env->SetLongField(filterObj, gFields.filterContext, (jlong)filterSp.get());

    callback->setFilter(filterObj);

    return filterObj;
}

jobject JTuner::openDvr(DvrType type, int bufferSize) {
    ALOGD("JTuner::openDvr");
    if (mDemux == NULL) {
        if (!openDemux()) {
            return NULL;
        }
    }
    sp<IDvr> iDvrSp;
    sp<DvrCallback> callback = new DvrCallback();
    mDemux->openDvr(type, bufferSize, callback,
            [&](Result, const sp<IDvr>& dvr) {
                iDvrSp = dvr;
            });

    if (iDvrSp == NULL) {
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject dvrObj =
            env->NewObject(
                    env->FindClass("android/media/tv/tuner/Tuner$Dvr"),
                    gFields.dvrInitID,
                    mObject);
    sp<Dvr> dvrSp = new Dvr(iDvrSp, dvrObj);
    dvrSp->incStrong(dvrObj);
    env->SetLongField(dvrObj, gFields.dvrContext, (jlong)dvrSp.get());

    callback->setDvr(dvrObj);

    return dvrObj;
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
    env->SetLongField(thiz, gFields.tunerContext, (jlong)tuner.get());

    return old;
}

static sp<JTuner> getTuner(JNIEnv *env, jobject thiz) {
    return (JTuner *)env->GetLongField(thiz, gFields.tunerContext);
}

static sp<IDescrambler> getDescrambler(JNIEnv *env, jobject descrambler) {
    return (IDescrambler *)env->GetLongField(descrambler, gFields.descramblerContext);
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

static FrontendSettings getFrontendSettings(JNIEnv *env, int type, jobject settings) {
    FrontendSettings frontendSettings;
    jclass clazz = env->FindClass("android/media/tv/tuner/FrontendSettings");
    jfieldID freqField = env->GetFieldID(clazz, "mFrequency", "I");
    uint32_t freq = static_cast<uint32_t>(env->GetIntField(clazz, freqField));

    // TODO: handle the other 8 types of settings
    if (type == 1) {
        // analog
        clazz = env->FindClass("android/media/tv/tuner/FrontendSettings$FrontendAnalogSettings");
        FrontendAnalogType analogType =
                static_cast<FrontendAnalogType>(
                        env->GetIntField(settings, env->GetFieldID(clazz, "mAnalogType", "I")));
        FrontendAnalogSifStandard sifStandard =
                static_cast<FrontendAnalogSifStandard>(
                        env->GetIntField(settings, env->GetFieldID(clazz, "mSifStandard", "I")));
        FrontendAnalogSettings frontendAnalogSettings {
                .frequency = freq,
                .type = analogType,
                .sifStandard = sifStandard,
        };
        frontendSettings.analog(frontendAnalogSettings);
    }
    return frontendSettings;
}

static sp<Filter> getFilter(JNIEnv *env, jobject filter) {
    return (Filter *)env->GetLongField(filter, gFields.filterContext);
}

static DvrSettings getDvrSettings(JNIEnv *env, jobject settings) {
    DvrSettings dvrSettings;
    jclass clazz = env->FindClass("android/media/tv/tuner/DvrSettings");
    uint32_t statusMask =
            static_cast<uint32_t>(env->GetIntField(
                    settings, env->GetFieldID(clazz, "mStatusMask", "I")));
    uint32_t lowThreshold =
            static_cast<uint32_t>(env->GetIntField(
                    settings, env->GetFieldID(clazz, "mLowThreshold", "I")));
    uint32_t highThreshold =
            static_cast<uint32_t>(env->GetIntField(
                    settings, env->GetFieldID(clazz, "mHighThreshold", "I")));
    uint8_t packetSize =
            static_cast<uint8_t>(env->GetIntField(
                    settings, env->GetFieldID(clazz, "mPacketSize", "I")));
    DataFormat dataFormat =
            static_cast<DataFormat>(env->GetIntField(
                    settings, env->GetFieldID(clazz, "mDataFormat", "I")));
    DvrType type =
            static_cast<DvrType>(env->GetIntField(
                    settings, env->GetFieldID(clazz, "mType", "I")));
    if (type == DvrType::RECORD) {
        RecordSettings recordSettings {
                .statusMask = static_cast<unsigned char>(statusMask),
                .lowThreshold = lowThreshold,
                .highThreshold = highThreshold,
                .dataFormat = dataFormat,
                .packetSize = packetSize,
        };
        dvrSettings.record(recordSettings);
    } else if (type == DvrType::PLAYBACK) {
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

static sp<Dvr> getDvr(JNIEnv *env, jobject dvr) {
    return (Dvr *)env->GetLongField(dvr, gFields.dvrContext);
}

static void android_media_tv_Tuner_native_init(JNIEnv *env) {
    jclass clazz = env->FindClass("android/media/tv/tuner/Tuner");
    CHECK(clazz != NULL);

    gFields.tunerContext = env->GetFieldID(clazz, "mNativeContext", "J");
    CHECK(gFields.tunerContext != NULL);

    gFields.onFrontendEventID = env->GetMethodID(clazz, "onFrontendEvent", "(I)V");

    gFields.onLnbEventID = env->GetMethodID(clazz, "onLnbEvent", "(I)V");

    jclass frontendClazz = env->FindClass("android/media/tv/tuner/Tuner$Frontend");
    gFields.frontendInitID =
            env->GetMethodID(frontendClazz, "<init>", "(Landroid/media/tv/tuner/Tuner;I)V");

    jclass lnbClazz = env->FindClass("android/media/tv/tuner/Tuner$Lnb");
    gFields.lnbInitID =
            env->GetMethodID(lnbClazz, "<init>", "(Landroid/media/tv/tuner/Tuner;I)V");

    jclass filterClazz = env->FindClass("android/media/tv/tuner/Tuner$Filter");
    gFields.filterContext = env->GetFieldID(filterClazz, "mNativeContext", "J");
    gFields.filterInitID =
            env->GetMethodID(filterClazz, "<init>", "(Landroid/media/tv/tuner/Tuner;I)V");
    gFields.onFilterStatusID =
            env->GetMethodID(filterClazz, "onFilterStatus", "(I)V");

    jclass descramblerClazz = env->FindClass("android/media/tv/tuner/Tuner$Descrambler");
    gFields.descramblerContext = env->GetFieldID(descramblerClazz, "mNativeContext", "J");
    gFields.descramblerInitID =
            env->GetMethodID(descramblerClazz, "<init>", "(Landroid/media/tv/tuner/Tuner;)V");

    jclass dvrClazz = env->FindClass("android/media/tv/tuner/Tuner$Dvr");
    gFields.dvrContext = env->GetFieldID(dvrClazz, "mNativeContext", "J");
    gFields.dvrInitID = env->GetMethodID(dvrClazz, "<init>", "(Landroid/media/tv/tuner/Tuner;)V");
}

static void android_media_tv_Tuner_native_setup(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = new JTuner(env, thiz);
    setTuner(env,thiz, tuner);
}

static jobject android_media_tv_Tuner_get_frontend_ids(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getFrontendIds();
}

static jobject android_media_tv_Tuner_open_frontend_by_id(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openFrontendById(id);
}

static int android_media_tv_Tuner_tune(JNIEnv *env, jobject thiz, jint type, jobject settings) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->tune(getFrontendSettings(env, type, settings));
}

static int android_media_tv_Tuner_stop_tune(JNIEnv*, jobject) {
    return 0;
}

static int android_media_tv_Tuner_scan(
        JNIEnv *env, jobject thiz, jint settingsType, jobject settings, jint scanType) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->scan(getFrontendSettings(
            env, settingsType, settings), static_cast<FrontendScanType>(scanType));
}

static int android_media_tv_Tuner_stop_scan(JNIEnv*, jobject) {
    return 0;
}

static int android_media_tv_Tuner_set_lnb(JNIEnv*, jobject, jint) {
    return 0;
}

static int android_media_tv_Tuner_set_lna(JNIEnv*, jobject, jint, jboolean) {
    return 0;
}

static jobjectArray android_media_tv_Tuner_get_frontend_status(JNIEnv, jobject, jintArray) {
    return NULL;
}

static jobject android_media_tv_Tuner_get_lnb_ids(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getLnbIds();
}

static jobject android_media_tv_Tuner_open_lnb_by_id(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openLnbById(id);
}

static jobject android_media_tv_Tuner_open_filter(
        JNIEnv *env, jobject thiz, jint type, jint subType, jint bufferSize) {
    sp<JTuner> tuner = getTuner(env, thiz);
    DemuxFilterType filterType {
        .mainType = static_cast<DemuxFilterMainType>(type),
    };

    // TODO: other sub types
    filterType.subType.tsFilterType(static_cast<DemuxTsFilterType>(subType));

    return tuner->openFilter(filterType, bufferSize);
}

static DemuxFilterSettings getFilterSettings(
        JNIEnv *env, int type, int subtype, jobject filterSettingsObj) {
    DemuxFilterSettings filterSettings;
    // TODO: more setting types
    jobject settingsObj =
            env->GetObjectField(
                    filterSettingsObj,
                    env->GetFieldID(
                            env->FindClass("android/media/tv/tuner/FilterSettings"),
                            "mSettings",
                            "Landroid/media/tv/tuner/FilterSettings$Settings;"));
    if (type == (int)DemuxFilterMainType::TS) {
        // DemuxTsFilterSettings
        jclass clazz = env->FindClass("android/media/tv/tuner/FilterSettings$TsFilterSettings");
        int tpid = env->GetIntField(filterSettingsObj, env->GetFieldID(clazz, "mTpid", "I"));
        if (subtype == (int)DemuxTsFilterType::PES) {
            // DemuxFilterPesDataSettings
            jclass settingClazz =
                    env->FindClass("android/media/tv/tuner/FilterSettings$PesSettings");
            int streamId = env->GetIntField(
                    settingsObj, env->GetFieldID(settingClazz, "mStreamId", "I"));
            bool isRaw = (bool)env->GetBooleanField(
                    settingsObj, env->GetFieldID(settingClazz, "mIsRaw", "Z"));
            DemuxFilterPesDataSettings filterPesDataSettings {
                    .streamId = static_cast<uint16_t>(streamId),
                    .isRaw = isRaw,
            };
            DemuxTsFilterSettings tsFilterSettings {
                    .tpid = static_cast<uint16_t>(tpid),
            };
            tsFilterSettings.filterSettings.pesData(filterPesDataSettings);
            filterSettings.ts(tsFilterSettings);
        }
    }
    return filterSettings;
}

static int copyData(JNIEnv *env, sp<Filter> filter, jbyteArray buffer, jint offset, int size) {
    ALOGD("copyData, size=%d, offset=%d", size, offset);

    int available = filter->mFilterMQ->availableToRead();
    ALOGD("copyData, available=%d", available);
    size = std::min(size, available);

    jboolean isCopy;
    jbyte *dst = env->GetByteArrayElements(buffer, &isCopy);
    ALOGD("copyData, isCopy=%d", isCopy);
    if (dst == nullptr) {
        ALOGD("Failed to GetByteArrayElements");
        return 0;
    }

    if (filter->mFilterMQ->read(reinterpret_cast<unsigned char*>(dst) + offset, size)) {
        env->ReleaseByteArrayElements(buffer, dst, 0);
        filter->mFilterMQEventFlag->wake(static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_CONSUMED));
    } else {
        ALOGD("Failed to read FMQ");
        env->ReleaseByteArrayElements(buffer, dst, 0);
        return 0;
    }
    return size;
}

static int android_media_tv_Tuner_configure_filter(
        JNIEnv *env, jobject filter, int type, int subtype, jobject settings) {
    ALOGD("configure filter type=%d, subtype=%d", type, subtype);
    sp<Filter> filterSp = getFilter(env, filter);
    sp<IFilter> iFilterSp = filterSp->getIFilter();
    if (iFilterSp == NULL) {
        ALOGD("Failed to configure filter: filter not found");
        return (int)Result::INVALID_STATE;
    }
    DemuxFilterSettings filterSettings = getFilterSettings(env, type, subtype, settings);
    Result res = iFilterSp->configure(filterSettings);
    MQDescriptorSync<uint8_t> filterMQDesc;
    if (res == Result::SUCCESS && filterSp->mFilterMQ == NULL) {
        Result getQueueDescResult = Result::UNKNOWN_ERROR;
        iFilterSp->getQueueDesc(
                [&](Result r, const MQDescriptorSync<uint8_t>& desc) {
                    filterMQDesc = desc;
                    getQueueDescResult = r;
                    ALOGD("getFilterQueueDesc");
                });
        if (getQueueDescResult == Result::SUCCESS) {
            filterSp->mFilterMQ = std::make_unique<FilterMQ>(filterMQDesc, true);
            EventFlag::createEventFlag(
                    filterSp->mFilterMQ->getEventFlagWord(), &(filterSp->mFilterMQEventFlag));
        }
    }
    return (int)res;
}

static int android_media_tv_Tuner_get_filter_id(JNIEnv*, jobject) {
    return 0;
}

static int android_media_tv_Tuner_set_filter_data_source(JNIEnv*, jobject, jobject) {
    return 0;
}

static int android_media_tv_Tuner_start_filter(JNIEnv *env, jobject filter) {
    sp<IFilter> filterSp = getFilter(env, filter)->getIFilter();
    if (filterSp == NULL) {
        ALOGD("Failed to start filter: filter not found");
        return false;
    }
    Result r = filterSp->start();
    return (int) r;
}

static int android_media_tv_Tuner_stop_filter(JNIEnv *env, jobject filter) {
    sp<IFilter> filterSp = getFilter(env, filter)->getIFilter();
    if (filterSp == NULL) {
        ALOGD("Failed to stop filter: filter not found");
        return false;
    }
    Result r = filterSp->stop();
    return (int) r;
}

static int android_media_tv_Tuner_flush_filter(JNIEnv *env, jobject filter) {
    sp<IFilter> filterSp = getFilter(env, filter)->getIFilter();
    if (filterSp == NULL) {
        ALOGD("Failed to flush filter: filter not found");
        return false;
    }
    Result r = filterSp->flush();
    return (int) r;
}

static int android_media_tv_Tuner_read_filter_fmq(
        JNIEnv *env, jobject filter, jbyteArray buffer, jint offset, jint size) {
    sp<Filter> filterSp = getFilter(env, filter);
    if (filterSp == NULL) {
        ALOGD("Failed to read filter FMQ: filter not found");
        return 0;
    }
    return copyData(env, filterSp, buffer, offset, size);
}

static int android_media_tv_Tuner_close_filter(JNIEnv*, jobject) {
    return 0;
}

static jobject android_media_tv_Tuner_open_descrambler(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openDescrambler();
}

static int android_media_tv_Tuner_add_pid(
        JNIEnv *env, jobject descrambler, jint pidType, jint pid, jobject filter) {
    sp<IDescrambler> descramblerSp = getDescrambler(env, descrambler);
    if (descramblerSp == NULL) {
        return false;
    }
    sp<IFilter> filterSp = getFilter(env, filter)->getIFilter();
    Result result = descramblerSp->addPid(getDemuxPid((int)pidType, (int)pid), filterSp);
    return (int)result;
}

static int android_media_tv_Tuner_remove_pid(
        JNIEnv *env, jobject descrambler, jint pidType, jint pid, jobject filter) {
    sp<IDescrambler> descramblerSp = getDescrambler(env, descrambler);
    if (descramblerSp == NULL) {
        return false;
    }
    sp<IFilter> filterSp = getFilter(env, filter)->getIFilter();
    Result result = descramblerSp->removePid(getDemuxPid((int)pidType, (int)pid), filterSp);
    return (int)result;
}

static int android_media_tv_Tuner_set_key_token(JNIEnv, jobject, jbyteArray) {
    return 0;
}

static int android_media_tv_Tuner_close_descrambler(JNIEnv, jobject) {
    return 0;
}

static jobject android_media_tv_Tuner_open_dvr(JNIEnv *env, jobject thiz, jint type, jint bufferSize) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openDvr(static_cast<DvrType>(type), bufferSize);
}

static int android_media_tv_Tuner_attach_filter(JNIEnv *env, jobject dvr, jobject filter) {
    sp<IDvr> dvrSp = getDvr(env, dvr)->getIDvr();
    sp<IFilter> filterSp = getFilter(env, filter)->getIFilter();
    if (dvrSp == NULL || filterSp == NULL) {
        return false;
    }
    Result result = dvrSp->attachFilter(filterSp);
    return (int) result;
}

static int android_media_tv_Tuner_detach_filter(JNIEnv *env, jobject dvr, jobject filter) {
    sp<IDvr> dvrSp = getDvr(env, dvr)->getIDvr();
    sp<IFilter> filterSp = getFilter(env, filter)->getIFilter();
    if (dvrSp == NULL || filterSp == NULL) {
        return false;
    }
    Result result = dvrSp->detachFilter(filterSp);
    return (int) result;
}

static int android_media_tv_Tuner_configure_dvr(JNIEnv *env, jobject dvr, jobject settings) {
    sp<IDvr> dvrSp = getDvr(env, dvr)->getIDvr();
    if (dvrSp == NULL) {
        ALOGD("Failed to configure dvr: dvr not found");
        return (int)Result::INVALID_STATE;
    }
    Result result = dvrSp->configure(getDvrSettings(env, settings));
    return (int)result;
}

static int android_media_tv_Tuner_start_dvr(JNIEnv *env, jobject dvr) {
    sp<IDvr> dvrSp = getDvr(env, dvr)->getIDvr();
    if (dvrSp == NULL) {
        ALOGD("Failed to start dvr: dvr not found");
        return false;
    }
    Result result = dvrSp->start();
    return (int) result;
}

static int android_media_tv_Tuner_stop_dvr(JNIEnv *env, jobject dvr) {
    sp<IDvr> dvrSp = getDvr(env, dvr)->getIDvr();
    if (dvrSp == NULL) {
        ALOGD("Failed to stop dvr: dvr not found");
        return false;
    }
    Result result = dvrSp->stop();
    return (int) result;
}

static int android_media_tv_Tuner_flush_dvr(JNIEnv *env, jobject dvr) {
    sp<IDvr> dvrSp = getDvr(env, dvr)->getIDvr();
    if (dvrSp == NULL) {
        ALOGD("Failed to flush dvr: dvr not found");
        return false;
    }
    Result result = dvrSp->flush();
    return (int) result;
}

static int android_media_tv_Tuner_close_dvr(JNIEnv*, jobject) {
    return 0;
}

static int android_media_tv_Tuner_lnb_set_voltage(JNIEnv*, jobject, jint) {
    return 0;
}

static int android_media_tv_Tuner_lnb_set_tone(JNIEnv*, jobject, jint) {
    return 0;
}

static int android_media_tv_Tuner_lnb_set_position(JNIEnv*, jobject, jint) {
    return 0;
}

static int android_media_tv_Tuner_lnb_send_diseqc_msg(JNIEnv*, jobject, jbyteArray) {
    return 0;
}

static int android_media_tv_Tuner_close_lnb(JNIEnv*, jobject) {
    return 0;
}

static const JNINativeMethod gTunerMethods[] = {
    { "nativeInit", "()V", (void *)android_media_tv_Tuner_native_init },
    { "nativeSetup", "()V", (void *)android_media_tv_Tuner_native_setup },
    { "nativeGetFrontendIds", "()Ljava/util/List;",
            (void *)android_media_tv_Tuner_get_frontend_ids },
    { "nativeOpenFrontendById", "(I)Landroid/media/tv/tuner/Tuner$Frontend;",
            (void *)android_media_tv_Tuner_open_frontend_by_id },
    { "nativeTune", "(ILandroid/media/tv/tuner/FrontendSettings;)I",
            (void *)android_media_tv_Tuner_tune },
    { "nativeStopTune", "()I", (void *)android_media_tv_Tuner_stop_tune },
    { "nativeScan", "(ILandroid/media/tv/tuner/FrontendSettings;I)I",
            (void *)android_media_tv_Tuner_scan },
    { "nativeStopScan", "()I", (void *)android_media_tv_Tuner_stop_scan },
    { "nativeSetLnb", "(I)I", (void *)android_media_tv_Tuner_set_lnb },
    { "nativeSetLna", "(Z)I", (void *)android_media_tv_Tuner_set_lna },
    { "nativeGetFrontendStatus", "([I)[Landroid/media/tv/tuner/FrontendStatus;",
            (void *)android_media_tv_Tuner_get_frontend_status },
    { "nativeOpenFilter", "(III)Landroid/media/tv/tuner/Tuner$Filter;",
            (void *)android_media_tv_Tuner_open_filter },
    { "nativeGetLnbIds", "()Ljava/util/List;",
            (void *)android_media_tv_Tuner_get_lnb_ids },
    { "nativeOpenLnbById", "(I)Landroid/media/tv/tuner/Tuner$Lnb;",
            (void *)android_media_tv_Tuner_open_lnb_by_id },
    { "nativeOpenDescrambler", "()Landroid/media/tv/tuner/Tuner$Descrambler;",
            (void *)android_media_tv_Tuner_open_descrambler },
    { "nativeOpenDvr", "(II)Landroid/media/tv/tuner/Tuner$Dvr;",
            (void *)android_media_tv_Tuner_open_dvr },
};

static const JNINativeMethod gFilterMethods[] = {
    { "nativeConfigureFilter", "(IILandroid/media/tv/tuner/FilterSettings;)I",
            (void *)android_media_tv_Tuner_configure_filter },
    { "nativeGetId", "()I", (void *)android_media_tv_Tuner_get_filter_id },
    { "nativeSetDataSource", "(Landroid/media/tv/tuner/Tuner$Filter;)I",
            (void *)android_media_tv_Tuner_set_filter_data_source },
    { "nativeStartFilter", "()I", (void *)android_media_tv_Tuner_start_filter },
    { "nativeStopFilter", "()I", (void *)android_media_tv_Tuner_stop_filter },
    { "nativeFlushFilter", "()I", (void *)android_media_tv_Tuner_flush_filter },
    { "nativeRead", "([BII)I", (void *)android_media_tv_Tuner_read_filter_fmq },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_filter },
};

static const JNINativeMethod gDescramblerMethods[] = {
    { "nativeAddPid", "(IILandroid/media/tv/tuner/Tuner$Filter;)I",
            (void *)android_media_tv_Tuner_add_pid },
    { "nativeRemovePid", "(IILandroid/media/tv/tuner/Tuner$Filter;)I",
            (void *)android_media_tv_Tuner_remove_pid },
    { "nativeSetKeyToken", "([B)I", (void *)android_media_tv_Tuner_set_key_token },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_descrambler },
};

static const JNINativeMethod gDvrMethods[] = {
    { "nativeAttachFilter", "(Landroid/media/tv/tuner/Tuner$Filter;)I",
            (void *)android_media_tv_Tuner_attach_filter },
    { "nativeDetachFilter", "(Landroid/media/tv/tuner/Tuner$Filter;)I",
            (void *)android_media_tv_Tuner_detach_filter },
    { "nativeConfigureDvr", "(Landroid/media/tv/tuner/DvrSettings;)I",
            (void *)android_media_tv_Tuner_configure_dvr },
    { "nativeStartDvr", "()I", (void *)android_media_tv_Tuner_start_dvr },
    { "nativeStopDvr", "()I", (void *)android_media_tv_Tuner_stop_dvr },
    { "nativeFlushDvr", "()I", (void *)android_media_tv_Tuner_flush_dvr },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_dvr },
};

static const JNINativeMethod gLnbMethods[] = {
    { "nativeSetVoltage", "(I)I", (void *)android_media_tv_Tuner_lnb_set_voltage },
    { "nativeSetTone", "(I)I", (void *)android_media_tv_Tuner_lnb_set_tone },
    { "nativeSetSatellitePosition", "(I)I", (void *)android_media_tv_Tuner_lnb_set_position },
    { "nativeSendDiseqcMessage", "([B)I", (void *)android_media_tv_Tuner_lnb_send_diseqc_msg },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_lnb },
};

static bool register_android_media_tv_Tuner(JNIEnv *env) {
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Tuner", gTunerMethods, NELEM(gTunerMethods)) != JNI_OK) {
        ALOGE("Failed to register tuner native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Tuner$Filter",
            gFilterMethods,
            NELEM(gFilterMethods)) != JNI_OK) {
        ALOGE("Failed to register filter native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Tuner$Descrambler",
            gDescramblerMethods,
            NELEM(gDescramblerMethods)) != JNI_OK) {
        ALOGE("Failed to register descrambler native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Tuner$Dvr",
            gDvrMethods,
            NELEM(gDvrMethods)) != JNI_OK) {
        ALOGE("Failed to register dvr native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Tuner$Lnb",
            gLnbMethods,
            NELEM(gLnbMethods)) != JNI_OK) {
        ALOGE("Failed to register lnb native methods");
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
