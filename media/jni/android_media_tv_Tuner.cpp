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
using ::android::hardware::tv::tuner::V1_0::DemuxFilterMainType;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterPesDataSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxMmtpPid;
using ::android::hardware::tv::tuner::V1_0::DemuxTpid;
using ::android::hardware::tv::tuner::V1_0::DemuxTsFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxTsFilterType;
using ::android::hardware::tv::tuner::V1_0::FrontendAnalogSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendAnalogSifStandard;
using ::android::hardware::tv::tuner::V1_0::FrontendAnalogType;
using ::android::hardware::tv::tuner::V1_0::ITuner;
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

    sp<IFilter> filterSp;
    sp<FilterCallback> callback = new FilterCallback();
    mDemux->openFilter(type, bufferSize, callback,
            [&](Result, const sp<IFilter>& filter) {
                filterSp = filter;
            });
    if (filterSp == NULL) {
        ALOGD("Failed to open filter, type = %d", type.mainType);
        return NULL;
    }
    int fId;
    filterSp->getId([&](Result, uint32_t filterId) {
        fId = filterId;
    });

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject filterObj =
            env->NewObject(
                    env->FindClass("android/media/tv/tuner/Tuner$Filter"),
                    gFields.filterInitID,
                    mObject,
                    (jint) fId);

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
    sp<IDvr> dvrSp;
    sp<DvrCallback> callback = new DvrCallback();
    mDemux->openDvr(type, bufferSize, callback,
            [&](Result, const sp<IDvr>& dvr) {
                dvrSp = dvr;
            });

    if (dvrSp == NULL) {
        return NULL;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject dvrObj =
            env->NewObject(
                    env->FindClass("android/media/tv/tuner/Tuner$Dvr"),
                    gFields.dvrInitID,
                    mObject);

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
    jfieldID freqField = env->GetFieldID(clazz, "frequency", "I");
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

static sp<IFilter> getFilter(JNIEnv *env, jobject filter) {
    return (IFilter *)env->GetLongField(filter, gFields.filterContext);
}

static sp<IDvr> getDvr(JNIEnv *env, jobject dvr) {
    return (IDvr *)env->GetLongField(dvr, gFields.dvrContext);
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

static int android_media_tv_Tuner_configure_filter(
        JNIEnv *env, jobject filter, int type, int subtype, jobject settings) {
    ALOGD("configure filter type=%d, subtype=%d", type, subtype);
    sp<IFilter> filterSp = getFilter(env, filter);
    if (filterSp == NULL) {
        ALOGD("Failed to configure filter: filter not found");
        return (int)Result::INVALID_STATE;
    }
    DemuxFilterSettings filterSettings = getFilterSettings(env, type, subtype, settings);
    Result res = filterSp->configure(filterSettings);
    return (int)res;
}

static bool android_media_tv_Tuner_start_filter(JNIEnv *env, jobject filter) {
    sp<IFilter> filterSp = getFilter(env, filter);
    if (filterSp == NULL) {
        ALOGD("Failed to start filter: filter not found");
        return false;
    }
    return filterSp->start() == Result::SUCCESS;
}

static bool android_media_tv_Tuner_stop_filter(JNIEnv *env, jobject filter) {
    sp<IFilter> filterSp = getFilter(env, filter);
    if (filterSp == NULL) {
        ALOGD("Failed to stop filter: filter not found");
        return false;
    }
    return filterSp->stop() == Result::SUCCESS;
}

static bool android_media_tv_Tuner_flush_filter(JNIEnv *env, jobject filter) {
    sp<IFilter> filterSp = getFilter(env, filter);
    if (filterSp == NULL) {
        ALOGD("Failed to flush filter: filter not found");
        return false;
    }
    return filterSp->flush() == Result::SUCCESS;
}

static jobject android_media_tv_Tuner_open_descrambler(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openDescrambler();
}

static bool android_media_tv_Tuner_add_pid(
        JNIEnv *env, jobject descrambler, jint pidType, jint pid, jobject filter) {
    sp<IDescrambler> descramblerSp = getDescrambler(env, descrambler);
    if (descramblerSp == NULL) {
        return false;
    }
    sp<IFilter> filterSp = getFilter(env, filter);
    Result result = descramblerSp->addPid(getDemuxPid((int)pidType, (int)pid), filterSp);
    return result == Result::SUCCESS;
}

static bool android_media_tv_Tuner_remove_pid(
        JNIEnv *env, jobject descrambler, jint pidType, jint pid, jobject filter) {
    sp<IDescrambler> descramblerSp = getDescrambler(env, descrambler);
    if (descramblerSp == NULL) {
        return false;
    }
    sp<IFilter> filterSp = getFilter(env, filter);
    Result result = descramblerSp->removePid(getDemuxPid((int)pidType, (int)pid), filterSp);
    return result == Result::SUCCESS;
}

static jobject android_media_tv_Tuner_open_dvr(JNIEnv *env, jobject thiz, jint type, jint bufferSize) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openDvr(static_cast<DvrType>(type), bufferSize);
}

static bool android_media_tv_Tuner_attach_filter(JNIEnv *env, jobject dvr, jobject filter) {
    sp<IDvr> dvrSp = getDvr(env, dvr);
    sp<IFilter> filterSp = getFilter(env, filter);
    if (dvrSp == NULL || filterSp == NULL) {
        return false;
    }
    Result result = dvrSp->attachFilter(filterSp);
    return result == Result::SUCCESS;
}

static bool android_media_tv_Tuner_detach_filter(JNIEnv *env, jobject dvr, jobject filter) {
    sp<IDvr> dvrSp = getDvr(env, dvr);
    sp<IFilter> filterSp = getFilter(env, filter);
    if (dvrSp == NULL || filterSp == NULL) {
        return false;
    }
    Result result = dvrSp->detachFilter(filterSp);
    return result == Result::SUCCESS;
}

static bool android_media_tv_Tuner_start_dvr(JNIEnv *env, jobject dvr) {
    sp<IDvr> dvrSp = getDvr(env, dvr);
    if (dvrSp == NULL) {
        ALOGD("Failed to start dvr: dvr not found");
        return false;
    }
    return dvrSp->start() == Result::SUCCESS;
}

static bool android_media_tv_Tuner_stop_dvr(JNIEnv *env, jobject dvr) {
    sp<IDvr> dvrSp = getDvr(env, dvr);
    if (dvrSp == NULL) {
        ALOGD("Failed to stop dvr: dvr not found");
        return false;
    }
    return dvrSp->stop() == Result::SUCCESS;
}

static bool android_media_tv_Tuner_flush_dvr(JNIEnv *env, jobject dvr) {
    sp<IDvr> dvrSp = getDvr(env, dvr);
    if (dvrSp == NULL) {
        ALOGD("Failed to flush dvr: dvr not found");
        return false;
    }
    return dvrSp->flush() == Result::SUCCESS;
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
    { "nativeStartFilter", "()Z", (void *)android_media_tv_Tuner_start_filter },
    { "nativeStopFilter", "()Z", (void *)android_media_tv_Tuner_stop_filter },
    { "nativeFlushFilter", "()Z", (void *)android_media_tv_Tuner_flush_filter },
};

static const JNINativeMethod gDescramblerMethods[] = {
    { "nativeAddPid", "(IILandroid/media/tv/tuner/Tuner$Filter;)Z",
            (void *)android_media_tv_Tuner_add_pid },
    { "nativeRemovePid", "(IILandroid/media/tv/tuner/Tuner$Filter;)Z",
            (void *)android_media_tv_Tuner_remove_pid },
};

static const JNINativeMethod gDvrMethods[] = {
    { "nativeAttachFilter", "(Landroid/media/tv/tuner/Tuner$Filter;)Z",
            (void *)android_media_tv_Tuner_attach_filter },
    { "nativeDetachFilter", "(Landroid/media/tv/tuner/Tuner$Filter;)Z",
            (void *)android_media_tv_Tuner_detach_filter },
    { "nativeStartDvr", "()Z", (void *)android_media_tv_Tuner_start_dvr },
    { "nativeStopDvr", "()Z", (void *)android_media_tv_Tuner_stop_dvr },
    { "nativeFlushDvr", "()Z", (void *)android_media_tv_Tuner_flush_dvr },
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
