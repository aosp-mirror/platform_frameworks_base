/*
 * Copyright 2020 The Android Open Source Project
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

#define LOG_TAG "TunerClient"

#include <android/binder_manager.h>
#include <android-base/logging.h>
#include <utils/Log.h>

#include "TunerClient.h"

using ::aidl::android::media::tv::tuner::TunerFrontendCapabilities;
using ::aidl::android::media::tv::tuner::TunerFrontendDtmbCapabilities;
using ::android::hardware::tv::tuner::V1_0::FrontendId;
using ::android::hardware::tv::tuner::V1_0::FrontendStatusType;
using ::android::hardware::tv::tuner::V1_0::FrontendType;

namespace android {

sp<ITuner> TunerClient::mTuner;
sp<::android::hardware::tv::tuner::V1_1::ITuner> TunerClient::mTuner_1_1;
shared_ptr<ITunerService> TunerClient::mTunerService;
int TunerClient::mTunerVersion;

/////////////// TunerClient ///////////////////////

TunerClient::TunerClient() {
    // Get HIDL Tuner in migration stage.
    getHidlTuner();
    if (mTuner != NULL) {
        updateTunerResources();
    }
    // Connect with Tuner Service.
    ::ndk::SpAIBinder binder(AServiceManager_getService("media.tuner"));
    mTunerService = ITunerService::fromBinder(binder);
    if (mTunerService == NULL) {
        ALOGE("Failed to get tuner service");
    } else {
        mTunerService->getTunerHalVersion(&mTunerVersion);
    }
}

TunerClient::~TunerClient() {
    mTuner = NULL;
    mTuner_1_1 = NULL;
    mTunerVersion = 0;
    mTunerService = NULL;
}

vector<FrontendId> TunerClient::getFrontendIds() {
    vector<FrontendId> ids;

    if (mTunerService != NULL) {
        vector<int32_t> v;
        Status s = mTunerService->getFrontendIds(&v);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS || v.size() == 0) {
            ids.clear();
            return ids;
        }
        for (int32_t id : v) {
            ids.push_back(static_cast<FrontendId>(id));
        }
        return ids;
    }

    if (mTuner != NULL) {
        Result res;
        mTuner->getFrontendIds([&](Result r, const hardware::hidl_vec<FrontendId>& frontendIds) {
            res = r;
            ids = frontendIds;
        });
        if (res != Result::SUCCESS || ids.size() == 0) {
            ALOGW("Frontend ids not available");
            ids.clear();
            return ids;
        }
        return ids;
    }

    return ids;
}


sp<FrontendClient> TunerClient::openFrontend(int frontendHandle) {
    if (mTunerService != NULL) {
        shared_ptr<ITunerFrontend> tunerFrontend;
        Status s = mTunerService->openFrontend(frontendHandle, &tunerFrontend);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS
                || tunerFrontend == NULL) {
            return NULL;
        }
        int id;
        s = tunerFrontend->getFrontendId(&id);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        TunerFrontendInfo aidlFrontendInfo;
        s = mTunerService->getFrontendInfo(id, &aidlFrontendInfo);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        return new FrontendClient(tunerFrontend, aidlFrontendInfo.type);
    }

    if (mTuner != NULL) {
        int id = getResourceIdFromHandle(frontendHandle, FRONTEND);
        sp<IFrontend> hidlFrontend = openHidlFrontendById(id);
        if (hidlFrontend != NULL) {
            FrontendInfo hidlInfo;
            Result res = getHidlFrontendInfo(id, hidlInfo);
            if (res != Result::SUCCESS) {
                return NULL;
            }
            sp<FrontendClient> frontendClient = new FrontendClient(
                    NULL, (int)hidlInfo.type);
            frontendClient->setHidlFrontend(hidlFrontend);
            frontendClient->setId(id);
            return frontendClient;
        }
    }

    return NULL;
}

shared_ptr<FrontendInfo> TunerClient::getFrontendInfo(int id) {
    if (mTunerService != NULL) {
        TunerFrontendInfo aidlFrontendInfo;
        Status s = mTunerService->getFrontendInfo(id, &aidlFrontendInfo);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        return make_shared<FrontendInfo>(frontendInfoAidlToHidl(aidlFrontendInfo));
    }

    if (mTuner != NULL) {
        FrontendInfo hidlInfo;
        Result res = getHidlFrontendInfo(id, hidlInfo);
        if (res != Result::SUCCESS) {
            return NULL;
        }
        return make_shared<FrontendInfo>(hidlInfo);
    }

    return NULL;
}

shared_ptr<FrontendDtmbCapabilities> TunerClient::getFrontendDtmbCapabilities(int id) {
    if (mTunerService != NULL) {
        TunerFrontendDtmbCapabilities dtmbCaps;
        Status s = mTunerService->getFrontendDtmbCapabilities(id, &dtmbCaps);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        FrontendDtmbCapabilities hidlCaps{
            .transmissionModeCap = static_cast<uint32_t>(dtmbCaps.transmissionModeCap),
            .bandwidthCap = static_cast<uint32_t>(dtmbCaps.bandwidthCap),
            .modulationCap = static_cast<uint32_t>(dtmbCaps.modulationCap),
            .codeRateCap = static_cast<uint32_t>(dtmbCaps.codeRateCap),
            .guardIntervalCap = static_cast<uint32_t>(dtmbCaps.guardIntervalCap),
            .interleaveModeCap = static_cast<uint32_t>(dtmbCaps.interleaveModeCap),
        };
        return make_shared<FrontendDtmbCapabilities>(hidlCaps);
    }

    if (mTuner_1_1 != NULL) {
        Result result;
        FrontendDtmbCapabilities dtmbCaps;
        mTuner_1_1->getFrontendDtmbCapabilities(id,
                [&](Result r, const FrontendDtmbCapabilities& caps) {
            dtmbCaps = caps;
            result = r;
        });
        if (result == Result::SUCCESS) {
            return make_shared<FrontendDtmbCapabilities>(dtmbCaps);
        }
    }

    return NULL;
}

sp<DemuxClient> TunerClient::openDemux(int demuxHandle) {
    if (mTunerService != NULL) {
        shared_ptr<ITunerDemux> tunerDemux;
        Status s = mTunerService->openDemux(demuxHandle, &tunerDemux);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        return new DemuxClient(tunerDemux);
    }

    if (mTuner != NULL) {
        sp<DemuxClient> demuxClient = new DemuxClient(NULL);
        int demuxId;
        sp<IDemux> hidlDemux = openHidlDemux(demuxId);
        if (hidlDemux != NULL) {
            demuxClient->setHidlDemux(hidlDemux);
            demuxClient->setId(demuxId);
            return demuxClient;
        }
    }

    return NULL;
}

shared_ptr<DemuxCapabilities> TunerClient::getDemuxCaps() {
    if (mTunerService != NULL) {
        TunerDemuxCapabilities aidlCaps;
        Status s = mTunerService->getDemuxCaps(&aidlCaps);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        return make_shared<DemuxCapabilities>(getHidlDemuxCaps(aidlCaps));
    }

    if (mTuner != NULL) {
        Result res;
        DemuxCapabilities caps;
        mTuner->getDemuxCaps([&](Result r, const DemuxCapabilities& demuxCaps) {
            caps = demuxCaps;
            res = r;
        });
        if (res == Result::SUCCESS) {
            return make_shared<DemuxCapabilities>(caps);
        }
    }

    return NULL;
}

sp<DescramblerClient> TunerClient::openDescrambler(int descramblerHandle) {
    if (mTunerService != NULL) {
        shared_ptr<ITunerDescrambler> tunerDescrambler;
        Status s = mTunerService->openDescrambler(descramblerHandle, &tunerDescrambler);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        return new DescramblerClient(tunerDescrambler);
    }

    if (mTuner != NULL) {
        sp<DescramblerClient> descramblerClient = new DescramblerClient(NULL);
        sp<IDescrambler> hidlDescrambler = openHidlDescrambler();
        if (hidlDescrambler != NULL) {
            descramblerClient->setHidlDescrambler(hidlDescrambler);
            return descramblerClient;
        }
    }

    return NULL;
}

sp<LnbClient> TunerClient::openLnb(int lnbHandle) {
    if (mTunerService != NULL) {
        shared_ptr<ITunerLnb> tunerLnb;
        Status s = mTunerService->openLnb(lnbHandle, &tunerLnb);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        return new LnbClient(tunerLnb);
    }

    if (mTuner != NULL) {
        int id = getResourceIdFromHandle(lnbHandle, LNB);
        sp<LnbClient> lnbClient = new LnbClient(NULL);
        sp<ILnb> hidlLnb = openHidlLnbById(id);
        if (hidlLnb != NULL) {
            lnbClient->setHidlLnb(hidlLnb);
            lnbClient->setId(id);
            return lnbClient;
        }
    }

    return NULL;
}

sp<LnbClient> TunerClient::openLnbByName(string lnbName) {
    if (mTunerService != NULL) {
        shared_ptr<ITunerLnb> tunerLnb;
        Status s = mTunerService->openLnbByName(lnbName, &tunerLnb);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return NULL;
        }
        return new LnbClient(tunerLnb);
    }

    if (mTuner != NULL) {
        sp<LnbClient> lnbClient = new LnbClient(NULL);
        LnbId id;
        sp<ILnb> hidlLnb = openHidlLnbByName(lnbName, id);
        if (hidlLnb != NULL) {
            lnbClient->setHidlLnb(hidlLnb);
            lnbClient->setId(id);
            return lnbClient;
        }
    }

    return NULL;
}

/////////////// TunerClient Helper Methods ///////////////////////

void TunerClient::updateTunerResources() {
    if (mTuner == NULL) {
        return;
    }

    // Connect with Tuner Resource Manager.
    ::ndk::SpAIBinder binder(AServiceManager_getService("tv_tuner_resource_mgr"));
    mTunerResourceManager = ITunerResourceManager::fromBinder(binder);

    updateFrontendResources();
    updateLnbResources();
    // TODO: update Demux, Descrambler.
}

// TODO: remove after migration to Tuner Service is done.
void TunerClient::updateFrontendResources() {
    vector<FrontendId> ids = getFrontendIds();
    if (ids.size() == 0) {
        return;
    }
    vector<TunerFrontendInfo> infos;
    for (int i = 0; i < ids.size(); i++) {
        shared_ptr<FrontendInfo> frontendInfo = getFrontendInfo((int)ids[i]);
        if (frontendInfo == NULL) {
            continue;
        }
        TunerFrontendInfo tunerFrontendInfo{
            .handle = getResourceHandleFromId((int)ids[i], FRONTEND),
            .type = static_cast<int>(frontendInfo->type),
            .exclusiveGroupId = static_cast<int>(frontendInfo->exclusiveGroupId),
        };
        infos.push_back(tunerFrontendInfo);
    }
    mTunerResourceManager->setFrontendInfoList(infos);
}

void TunerClient::updateLnbResources() {
    vector<int> handles = getLnbHandles();
    if (handles.size() == 0) {
        return;
    }
    mTunerResourceManager->setLnbInfoList(handles);
}

sp<ITuner> TunerClient::getHidlTuner() {
    if (mTuner == NULL) {
        mTunerVersion = TUNER_HAL_VERSION_UNKNOWN;
        mTuner_1_1 = ::android::hardware::tv::tuner::V1_1::ITuner::getService();

        if (mTuner_1_1 == NULL) {
            ALOGW("Failed to get tuner 1.1 service.");
            mTuner = ITuner::getService();
            if (mTuner == NULL) {
                ALOGW("Failed to get tuner 1.0 service.");
            } else {
                mTunerVersion = TUNER_HAL_VERSION_1_0;
            }
        } else {
            mTuner = static_cast<sp<ITuner>>(mTuner_1_1);
            mTunerVersion = TUNER_HAL_VERSION_1_1;
         }
     }
     return mTuner;
}

sp<IFrontend> TunerClient::openHidlFrontendById(int id) {
    sp<IFrontend> fe;
    Result res;
    mTuner->openFrontendById(id, [&](Result r, const sp<IFrontend>& frontend) {
        fe = frontend;
        res = r;
    });
    if (res != Result::SUCCESS || fe == nullptr) {
        ALOGE("Failed to open frontend");
        return NULL;
    }
    return fe;
}

Result TunerClient::getHidlFrontendInfo(int id, FrontendInfo& feInfo) {
    Result res;
    mTuner->getFrontendInfo(id, [&](Result r, const FrontendInfo& info) {
        feInfo = info;
        res = r;
    });
    return res;
}

sp<IDemux> TunerClient::openHidlDemux(int& demuxId) {
    sp<IDemux> demux;
    Result res;

    mTuner->openDemux([&](Result result, uint32_t id, const sp<IDemux>& demuxSp) {
        demux = demuxSp;
        demuxId = id;
        res = result;
    });
    if (res != Result::SUCCESS || demux == nullptr) {
        ALOGE("Failed to open demux");
        return NULL;
    }
    return demux;
}

sp<ILnb> TunerClient::openHidlLnbById(int id) {
    sp<ILnb> lnb;
    Result res;

    mTuner->openLnbById(id, [&](Result r, const sp<ILnb>& lnbSp) {
        res = r;
        lnb = lnbSp;
    });
    if (res != Result::SUCCESS || lnb == nullptr) {
        ALOGE("Failed to open lnb by id");
        return NULL;
    }
    return lnb;
}

sp<ILnb> TunerClient::openHidlLnbByName(string name, LnbId& lnbId) {
    sp<ILnb> lnb;
    Result res;

    mTuner->openLnbByName(name, [&](Result r, LnbId id, const sp<ILnb>& lnbSp) {
        res = r;
        lnb = lnbSp;
        lnbId = id;
    });
    if (res != Result::SUCCESS || lnb == nullptr) {
        ALOGE("Failed to open lnb by name");
        return NULL;
    }
    return lnb;
}

// TODO: remove after migration to Tuner Service is done.
vector<int> TunerClient::getLnbHandles() {
    vector<int> lnbHandles;
    if (mTuner != NULL) {
        Result res;
        vector<LnbId> lnbIds;
        mTuner->getLnbIds([&](Result r, const hardware::hidl_vec<LnbId>& ids) {
            lnbIds = ids;
            res = r;
        });
        if (res != Result::SUCCESS || lnbIds.size() == 0) {
            ALOGW("Lnb isn't available");
        } else {
            for (int i = 0; i < lnbIds.size(); i++) {
                lnbHandles.push_back(getResourceHandleFromId((int)lnbIds[i], LNB));
            }
        }
    }

    return lnbHandles;
}

sp<IDescrambler> TunerClient::openHidlDescrambler() {
    sp<IDescrambler> descrambler;
    Result res;

    mTuner->openDescrambler([&](Result r, const sp<IDescrambler>& descramblerSp) {
        res = r;
        descrambler = descramblerSp;
    });

    if (res != Result::SUCCESS || descrambler == NULL) {
        return NULL;
    }

    return descrambler;
}

DemuxCapabilities TunerClient::getHidlDemuxCaps(TunerDemuxCapabilities& aidlCaps) {
    DemuxCapabilities caps{
        .numDemux = (uint32_t)aidlCaps.numDemux,
        .numRecord = (uint32_t)aidlCaps.numRecord,
        .numPlayback = (uint32_t)aidlCaps.numPlayback,
        .numTsFilter = (uint32_t)aidlCaps.numTsFilter,
        .numSectionFilter = (uint32_t)aidlCaps.numSectionFilter,
        .numAudioFilter = (uint32_t)aidlCaps.numAudioFilter,
        .numVideoFilter = (uint32_t)aidlCaps.numVideoFilter,
        .numPesFilter = (uint32_t)aidlCaps.numPesFilter,
        .numPcrFilter = (uint32_t)aidlCaps.numPcrFilter,
        .numBytesInSectionFilter = (uint32_t)aidlCaps.numBytesInSectionFilter,
        .filterCaps = (uint32_t)aidlCaps.filterCaps,
        .bTimeFilter = aidlCaps.bTimeFilter,
    };
    caps.linkCaps.resize(aidlCaps.linkCaps.size());
    copy(aidlCaps.linkCaps.begin(), aidlCaps.linkCaps.end(), caps.linkCaps.begin());
    return caps;
}

FrontendInfo TunerClient::frontendInfoAidlToHidl(TunerFrontendInfo aidlFrontendInfo) {
    FrontendInfo hidlFrontendInfo {
        .type = static_cast<FrontendType>(aidlFrontendInfo.type),
        .minFrequency = static_cast<uint32_t>(aidlFrontendInfo.minFrequency),
        .maxFrequency = static_cast<uint32_t>(aidlFrontendInfo.maxFrequency),
        .minSymbolRate = static_cast<uint32_t>(aidlFrontendInfo.minSymbolRate),
        .maxSymbolRate = static_cast<uint32_t>(aidlFrontendInfo.maxSymbolRate),
        .acquireRange = static_cast<uint32_t>(aidlFrontendInfo.acquireRange),
        .exclusiveGroupId = static_cast<uint32_t>(aidlFrontendInfo.exclusiveGroupId),
    };

    int size = aidlFrontendInfo.statusCaps.size();
    hidlFrontendInfo.statusCaps.resize(size);
    for (int i = 0; i < size; i++) {
        hidlFrontendInfo.statusCaps[i] =
                static_cast<FrontendStatusType>(aidlFrontendInfo.statusCaps[i]);
    }

    switch (aidlFrontendInfo.caps.getTag()) {
        case TunerFrontendCapabilities::analogCaps: {
            auto analog = aidlFrontendInfo.caps.get<TunerFrontendCapabilities::analogCaps>();
            hidlFrontendInfo.frontendCaps.analogCaps({
                .typeCap = static_cast<uint32_t>(analog.typeCap),
                .sifStandardCap = static_cast<uint32_t>(analog.sifStandardCap),
            });
            break;
        }
        case TunerFrontendCapabilities::atscCaps: {
            auto atsc = aidlFrontendInfo.caps.get<TunerFrontendCapabilities::atscCaps>();
            hidlFrontendInfo.frontendCaps.atscCaps({
                .modulationCap = static_cast<uint32_t>(atsc.modulationCap),
            });
            break;
        }
        case TunerFrontendCapabilities::atsc3Caps: {
            auto atsc3 = aidlFrontendInfo.caps.get<TunerFrontendCapabilities::atsc3Caps>();
            hidlFrontendInfo.frontendCaps.atsc3Caps({
                .bandwidthCap = static_cast<uint32_t>(atsc3.bandwidthCap),
                .modulationCap = static_cast<uint32_t>(atsc3.modulationCap),
                .timeInterleaveModeCap = static_cast<uint32_t>(atsc3.timeInterleaveModeCap),
                .codeRateCap = static_cast<uint32_t>(atsc3.codeRateCap),
                .fecCap = static_cast<uint32_t>(atsc3.fecCap),
                .demodOutputFormatCap = static_cast<uint8_t>(atsc3.demodOutputFormatCap),
            });
            break;
        }
        case TunerFrontendCapabilities::cableCaps: {
            auto cable = aidlFrontendInfo.caps.get<TunerFrontendCapabilities::cableCaps>();
            hidlFrontendInfo.frontendCaps.dvbcCaps({
                .modulationCap = static_cast<uint32_t>(cable.modulationCap),
                .fecCap = static_cast<uint64_t>(cable.codeRateCap),
                .annexCap = static_cast<uint8_t>(cable.annexCap),
            });
            break;
        }
        case TunerFrontendCapabilities::dvbsCaps: {
            auto dvbs = aidlFrontendInfo.caps.get<TunerFrontendCapabilities::dvbsCaps>();
            hidlFrontendInfo.frontendCaps.dvbsCaps({
                .modulationCap = static_cast<int32_t>(dvbs.modulationCap),
                .innerfecCap = static_cast<uint64_t>(dvbs.codeRateCap),
                .standard = static_cast<uint8_t>(dvbs.standard),
            });
            break;
        }
        case TunerFrontendCapabilities::dvbtCaps: {
            auto dvbt = aidlFrontendInfo.caps.get<TunerFrontendCapabilities::dvbtCaps>();
            hidlFrontendInfo.frontendCaps.dvbtCaps({
                .transmissionModeCap = static_cast<uint32_t>(dvbt.transmissionModeCap),
                .bandwidthCap = static_cast<uint32_t>(dvbt.bandwidthCap),
                .constellationCap = static_cast<uint32_t>(dvbt.constellationCap),
                .coderateCap = static_cast<uint32_t>(dvbt.codeRateCap),
                .hierarchyCap = static_cast<uint32_t>(dvbt.hierarchyCap),
                .guardIntervalCap = static_cast<uint32_t>(dvbt.guardIntervalCap),
                .isT2Supported = dvbt.isT2Supported,
                .isMisoSupported = dvbt.isMisoSupported,
            });
            break;
        }
        case TunerFrontendCapabilities::isdbsCaps: {
            auto isdbs = aidlFrontendInfo.caps.get<TunerFrontendCapabilities::isdbsCaps>();
            hidlFrontendInfo.frontendCaps.isdbsCaps({
                .modulationCap = static_cast<uint32_t>(isdbs.modulationCap),
                .coderateCap = static_cast<uint32_t>(isdbs.codeRateCap),
            });
            break;
        }
        case TunerFrontendCapabilities::isdbs3Caps: {
            auto isdbs3 = aidlFrontendInfo.caps.get<TunerFrontendCapabilities::isdbs3Caps>();
            hidlFrontendInfo.frontendCaps.isdbs3Caps({
                .modulationCap = static_cast<uint32_t>(isdbs3.modulationCap),
                .coderateCap = static_cast<uint32_t>(isdbs3.codeRateCap),
            });
            break;
        }
        case TunerFrontendCapabilities::isdbtCaps: {
            auto isdbt = aidlFrontendInfo.caps.get<TunerFrontendCapabilities::isdbtCaps>();
            hidlFrontendInfo.frontendCaps.isdbtCaps({
                .modeCap = static_cast<uint32_t>(isdbt.modeCap),
                .bandwidthCap = static_cast<uint32_t>(isdbt.bandwidthCap),
                .modulationCap = static_cast<uint32_t>(isdbt.modulationCap),
                .coderateCap = static_cast<uint32_t>(isdbt.codeRateCap),
                .guardIntervalCap = static_cast<uint32_t>(isdbt.guardIntervalCap),
            });
            break;
        }
    }
    return hidlFrontendInfo;
}

// TODO: remove after migration to Tuner Service is done.
int TunerClient::getResourceIdFromHandle(int handle, int /*resourceType*/) {
    return (handle & 0x00ff0000) >> 16;
}

// TODO: remove after migration to Tuner Service is done.
int TunerClient::getResourceHandleFromId(int id, int resourceType) {
    return (resourceType & 0x000000ff) << 24
            | (id << 16)
            | (mResourceRequestCount++ & 0xffff);
}
}  // namespace android
