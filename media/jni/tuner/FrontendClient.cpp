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

#define LOG_TAG "FrontendClient"

#include <android-base/logging.h>
#include <utils/Log.h>

#include "FrontendClient.h"

using ::aidl::android::media::tv::tuner::TunerFrontendScanAtsc3PlpInfo;
using ::aidl::android::media::tv::tuner::TunerFrontendUnionSettings;

using ::android::hardware::tv::tuner::V1_0::FrontendAnalogSifStandard;
using ::android::hardware::tv::tuner::V1_0::FrontendAnalogType;
using ::android::hardware::tv::tuner::V1_0::FrontendAtscModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3Bandwidth;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3Modulation;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3TimeInterleaveMode;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbcAnnex;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbcModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbcSpectralInversion;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsStandard;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsRolloff;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtBandwidth;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtGuardInterval;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtHierarchy;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtStandard;
using ::android::hardware::tv::tuner::V1_0::FrontendInnerFec;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbsModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbsRolloff;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbs3Modulation;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbs3Rolloff;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbtBandwidth;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbtGuardInterval;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbtMode;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbtModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendModulationStatus;
using ::android::hardware::tv::tuner::V1_0::FrontendScanAtsc3PlpInfo;
using ::android::hardware::tv::tuner::V1_0::FrontendStatusAtsc3PlpInfo;
using ::android::hardware::tv::tuner::V1_0::LnbVoltage;
using ::android::hardware::tv::tuner::V1_1::Constant;
using ::android::hardware::tv::tuner::V1_1::FrontendBandwidth;
using ::android::hardware::tv::tuner::V1_1::FrontendCableTimeInterleaveMode;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbBandwidth;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbGuardInterval;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbModulation;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbTimeInterleaveMode;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbTransmissionMode;
using ::android::hardware::tv::tuner::V1_1::FrontendDvbcBandwidth;
using ::android::hardware::tv::tuner::V1_1::FrontendDvbtConstellation;
using ::android::hardware::tv::tuner::V1_1::FrontendDvbtTransmissionMode;
using ::android::hardware::tv::tuner::V1_1::FrontendGuardInterval;
using ::android::hardware::tv::tuner::V1_1::FrontendInterleaveMode;
using ::android::hardware::tv::tuner::V1_1::FrontendModulation;
using ::android::hardware::tv::tuner::V1_1::FrontendRollOff;
using ::android::hardware::tv::tuner::V1_1::FrontendSpectralInversion;
using ::android::hardware::tv::tuner::V1_1::FrontendTransmissionMode;
using ::android::hardware::tv::tuner::V1_1::FrontendType;

namespace android {

/////////////// FrontendClient ///////////////////////

FrontendClient::FrontendClient(shared_ptr<ITunerFrontend> tunerFrontend, int type) {
    mTunerFrontend = tunerFrontend;
    mType = type;
}

FrontendClient::~FrontendClient() {
    mTunerFrontend = NULL;
    mFrontend = NULL;
    mFrontend_1_1 = NULL;
    mId = -1;
    mType = -1;
}

Result FrontendClient::setCallback(sp<FrontendClientCallback> frontendClientCallback) {
    if (mTunerFrontend != NULL) {
        shared_ptr<TunerFrontendCallback> aidlCallback =
                ::ndk::SharedRefBase::make<TunerFrontendCallback>(frontendClientCallback);
        aidlCallback->setFrontendType(mType);
        Status s = mTunerFrontend->setCallback(aidlCallback);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    sp<HidlFrontendCallback> hidlCallback = new HidlFrontendCallback(frontendClientCallback);
    return mFrontend->setCallback(hidlCallback);
}

void FrontendClient::setHidlFrontend(sp<IFrontend> frontend) {
    mFrontend = frontend;
    mFrontend_1_1 = ::android::hardware::tv::tuner::V1_1::IFrontend::castFrom(mFrontend);
}

// TODO: move after migration is done
void FrontendClient::setId(int id) {
    mId = id;
}

Result FrontendClient::tune(const FrontendSettings& settings,
        const FrontendSettingsExt1_1& settingsExt1_1) {
    if (mTunerFrontend != NULL) {
        TunerFrontendSettings tunerFeSettings = getAidlFrontendSettings(settings, settingsExt1_1);
        Status s = mTunerFrontend->tune(tunerFeSettings);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    Result result;
    if (mFrontend_1_1 != NULL && validateExtendedSettings(settingsExt1_1)) {
        result = mFrontend_1_1->tune_1_1(settings, settingsExt1_1);
        return result;
    }

    if (mFrontend != NULL) {
        result = mFrontend->tune(settings);
        return result;
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::stopTune() {
    if (mTunerFrontend != NULL) {
        Status s = mTunerFrontend->stopTune();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFrontend != NULL) {
        Result result = mFrontend->stopTune();
        return result;
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::scan(const FrontendSettings& settings, FrontendScanType type,
        const FrontendSettingsExt1_1& settingsExt1_1) {
    if (mTunerFrontend != NULL) {
        TunerFrontendSettings tunerFeSettings = getAidlFrontendSettings(settings, settingsExt1_1);
        Status s = mTunerFrontend->scan(tunerFeSettings, (int)type);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    Result result;
    if (mFrontend_1_1 != NULL && validateExtendedSettings(settingsExt1_1)) {
        result = mFrontend_1_1->scan_1_1(settings, type, settingsExt1_1);
        return result;
    }

    if (mFrontend != NULL) {
        result = mFrontend->scan(settings, type);
        return result;
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::stopScan() {
    if (mTunerFrontend != NULL) {
        Status s = mTunerFrontend->stopScan();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFrontend != NULL) {
        Result result = mFrontend->stopScan();
        return result;
    }

    return Result::INVALID_STATE;
}

vector<FrontendStatus> FrontendClient::getStatus(vector<FrontendStatusType> statusTypes) {
    vector<FrontendStatus> status;

    if (mTunerFrontend != NULL) {
        vector<TunerFrontendStatus> aidlStatus;
        vector<int> types;
        for (auto t : statusTypes) {
            types.push_back((int)t);
        }
        Status s = mTunerFrontend->getStatus(types, &aidlStatus);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return status;
        }
        return getHidlStatus(aidlStatus);
    }

    if (mFrontend != NULL && statusTypes.size() > 0) {
        Result res;
        mFrontend->getStatus(statusTypes,
                [&](Result r, const hidl_vec<FrontendStatus>& s) {
                    res = r;
                    status = s;
                });
        if (res != Result::SUCCESS) {
            status.clear();
            return status;
        }
    }

    return status;
}

vector<FrontendStatusExt1_1> FrontendClient::getStatusExtended_1_1(
        vector<FrontendStatusTypeExt1_1> statusTypes) {
    vector<FrontendStatusExt1_1> status;

    if (mTunerFrontend != NULL) {
        vector<TunerFrontendStatus> aidlStatus;
        vector<int> types;
        for (auto t : statusTypes) {
            types.push_back((int)t);
        }
        Status s = mTunerFrontend->getStatusExtended_1_1(types, &aidlStatus);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return status;
        }
        return getHidlStatusExt(aidlStatus);
    }

    if (mFrontend_1_1 != NULL && statusTypes.size() > 0) {
        Result res;
        mFrontend_1_1->getStatusExt1_1(statusTypes,
            [&](Result r, const hidl_vec<FrontendStatusExt1_1>& s) {
                res = r;
                status = s;
            });
        if (res != Result::SUCCESS) {
            status.clear();
            return status;
        }
    }

    return status;
}

Result FrontendClient::setLnb(sp<LnbClient> lnbClient) {
    if (mTunerFrontend != NULL) {
        Status s = mTunerFrontend->setLnb(lnbClient->getAidlLnb());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFrontend != NULL) {
        Result result = mFrontend->setLnb(lnbClient->getId());
        return result;
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::setLna(bool bEnable) {
    if (mTunerFrontend != NULL) {
        Status s = mTunerFrontend->setLna(bEnable);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFrontend != NULL) {
        Result result = mFrontend->setLna(bEnable);
        return result;
    }

    return Result::INVALID_STATE;
}

int FrontendClient::linkCiCamToFrontend(int ciCamId) {
    int ltsId = (int)Constant::INVALID_LTS_ID;

    if (mTunerFrontend != NULL) {
        Status s = mTunerFrontend->linkCiCamToFrontend(ciCamId, &ltsId);
        if (ClientHelper::getServiceSpecificErrorCode(s) == Result::SUCCESS) {
            return ltsId;
        }
        return (int)Constant::INVALID_LTS_ID;
    }

    if (mFrontend_1_1 != NULL) {
        Result res;
        mFrontend_1_1->linkCiCam(static_cast<uint32_t>(ciCamId),
            [&](Result r, uint32_t id) {
                res = r;
                ltsId = id;
            });
        if (res != Result::SUCCESS) {
            return (int)Constant::INVALID_LTS_ID;
        }
    }

    return ltsId;
}

Result FrontendClient::unlinkCiCamToFrontend(int ciCamId) {
    if (mTunerFrontend != NULL) {
        Status s = mTunerFrontend->unlinkCiCamToFrontend(ciCamId);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFrontend_1_1 != NULL) {
        return mFrontend_1_1->unlinkCiCam(static_cast<uint32_t>(ciCamId));
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::close() {
    if (mTunerFrontend != NULL) {
        Status s = mTunerFrontend->close();
        mTunerFrontend = NULL;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFrontend != NULL) {
        Result result = mFrontend->close();
        mFrontend = NULL;
        mFrontend_1_1 = NULL;
        return result;
    }

    return Result::INVALID_STATE;
}

/////////////// TunerFrontend Helper Methods ///////////////////////

shared_ptr<ITunerFrontend> FrontendClient::getAidlFrontend() {
    return mTunerFrontend;
}

int FrontendClient::getId() {
    if (mTunerFrontend != NULL) {
        Status s = mTunerFrontend->getFrontendId(&mId);
        if (ClientHelper::getServiceSpecificErrorCode(s) == Result::SUCCESS) {
            return mId;
        }
        ALOGE("Failed to getFrontendId from Tuner Frontend");
        return -1;
    }

    if (mFrontend != NULL) {
        return mId;
    }

    return -1;
}

vector<FrontendStatus> FrontendClient::getHidlStatus(vector<TunerFrontendStatus>& aidlStatus) {
    vector<FrontendStatus> hidlStatus;
    for (TunerFrontendStatus s : aidlStatus) {
        FrontendStatus status = FrontendStatus();
        switch (s.getTag()) {
            case TunerFrontendStatus::isDemodLocked: {
                status.isDemodLocked(s.get<TunerFrontendStatus::isDemodLocked>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::snr: {
                status.snr(s.get<TunerFrontendStatus::snr>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::ber: {
                status.ber((uint32_t)s.get<TunerFrontendStatus::ber>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::per: {
                status.per((uint32_t)s.get<TunerFrontendStatus::per>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::preBer: {
                status.preBer((uint32_t)s.get<TunerFrontendStatus::preBer>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::signalQuality: {
                status.signalQuality((uint32_t)s.get<TunerFrontendStatus::signalQuality>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::signalStrength: {
                status.signalStrength(s.get<TunerFrontendStatus::signalStrength>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::symbolRate: {
                status.symbolRate((uint32_t)s.get<TunerFrontendStatus::symbolRate>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::innerFec: {
                status.innerFec(static_cast<FrontendInnerFec>(
                        s.get<TunerFrontendStatus::innerFec>()));
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::modulation: {
                auto aidlMod = s.get<TunerFrontendStatus::modulation>();
                FrontendModulationStatus modulation;
                switch (mType) {
                    case (int)FrontendType::DVBC:
                        modulation.dvbc(static_cast<FrontendDvbcModulation>(aidlMod));
                        status.modulation(modulation);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::DVBS:
                        modulation.dvbs(static_cast<FrontendDvbsModulation>(aidlMod));
                        status.modulation(modulation);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::ISDBS:
                        modulation.isdbs(static_cast<FrontendIsdbsModulation>(aidlMod));
                        status.modulation(modulation);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::ISDBS3:
                        modulation.isdbs3(static_cast<FrontendIsdbs3Modulation>(aidlMod));
                        status.modulation(modulation);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::ISDBT:
                        modulation.isdbt(static_cast<FrontendIsdbtModulation>(aidlMod));
                        status.modulation(modulation);
                        hidlStatus.push_back(status);
                        break;
                    default:
                        break;
                }
                break;
            }
            case TunerFrontendStatus::inversion: {
                status.inversion(static_cast<FrontendDvbcSpectralInversion>(
                        s.get<TunerFrontendStatus::inversion>()));
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::lnbVoltage: {
                status.lnbVoltage(static_cast<LnbVoltage>(
                        s.get<TunerFrontendStatus::lnbVoltage>()));
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::plpId: {
                status.plpId((uint8_t)s.get<TunerFrontendStatus::plpId>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::isEWBS: {
                status.isEWBS(s.get<TunerFrontendStatus::isEWBS>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::agc: {
                status.agc((uint8_t)s.get<TunerFrontendStatus::agc>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::isLnaOn: {
                status.isLnaOn(s.get<TunerFrontendStatus::isLnaOn>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::isLayerError: {
                auto aidlE = s.get<TunerFrontendStatus::isLayerError>();
                hidl_vec<bool> e(aidlE.begin(), aidlE.end());
                status.isLayerError(e);
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::mer: {
                status.mer(s.get<TunerFrontendStatus::mer>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::freqOffset: {
                status.freqOffset(s.get<TunerFrontendStatus::freqOffset>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::hierarchy: {
                status.hierarchy(static_cast<FrontendDvbtHierarchy>(
                        s.get<TunerFrontendStatus::hierarchy>()));
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::isRfLocked: {
                status.isRfLocked(s.get<TunerFrontendStatus::isRfLocked>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::plpInfo: {
                int size = s.get<TunerFrontendStatus::plpInfo>().size();
                hidl_vec<FrontendStatusAtsc3PlpInfo> info(size);
                for (int i = 0; i < size; i++) {
                    auto aidlInfo = s.get<TunerFrontendStatus::plpInfo>()[i];
                    info[i] = {
                        .plpId = (uint8_t)aidlInfo.plpId,
                        .isLocked = aidlInfo.isLocked,
                        .uec = (uint32_t)aidlInfo.uec,
                    };
                }
                status.plpInfo(info);
                hidlStatus.push_back(status);
                break;
            }
            default:
                break;
        }
    }
    return hidlStatus;
}

vector<FrontendStatusExt1_1> FrontendClient::getHidlStatusExt(
        vector<TunerFrontendStatus>& aidlStatus) {
    vector<FrontendStatusExt1_1> hidlStatus;
    for (TunerFrontendStatus s : aidlStatus) {
        FrontendStatusExt1_1 status;
        switch (s.getTag()) {
            case TunerFrontendStatus::modulations: {
                vector<FrontendModulation> ms;
                for (auto aidlMod : s.get<TunerFrontendStatus::modulations>()) {
                    FrontendModulation m;
                    switch (mType) {
                        case (int)FrontendType::DVBC:
                            m.dvbc(static_cast<FrontendDvbcModulation>(aidlMod));
                            ms.push_back(m);
                            break;
                        case (int)FrontendType::DVBS:
                            m.dvbs(static_cast<FrontendDvbsModulation>(aidlMod));
                            ms.push_back(m);
                            break;
                        case (int)FrontendType::DVBT:
                            m.dvbt(static_cast<FrontendDvbtConstellation>(aidlMod));
                            ms.push_back(m);
                            break;
                        case (int)FrontendType::ISDBS:
                            m.isdbs(static_cast<FrontendIsdbsModulation>(aidlMod));
                            ms.push_back(m);
                            break;
                        case (int)FrontendType::ISDBS3:
                            m.isdbs3(static_cast<FrontendIsdbs3Modulation>(aidlMod));
                            ms.push_back(m);
                            break;
                        case (int)FrontendType::ISDBT:
                            m.isdbt(static_cast<FrontendIsdbtModulation>(aidlMod));
                            ms.push_back(m);
                            break;
                        case (int)FrontendType::ATSC:
                            m.atsc(static_cast<FrontendAtscModulation>(aidlMod));
                            ms.push_back(m);
                            break;
                        case (int)FrontendType::ATSC3:
                            m.atsc3(static_cast<FrontendAtsc3Modulation>(aidlMod));
                            ms.push_back(m);
                            break;
                        case (int)FrontendType::DTMB:
                            m.dtmb(static_cast<FrontendDtmbModulation>(aidlMod));
                            ms.push_back(m);
                            break;
                        default:
                            break;
                    }
                }
                if (ms.size() > 0) {
                    status.modulations(ms);
                    hidlStatus.push_back(status);
                }
                break;
            }
            case TunerFrontendStatus::bers: {
                auto aidlB = s.get<TunerFrontendStatus::bers>();
                hidl_vec<uint32_t> b(aidlB.begin(), aidlB.end());
                status.bers(b);
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::codeRates: {
                vector<hardware::tv::tuner::V1_1::FrontendInnerFec> codeRates;
                for (auto aidlCodeRate : s.get<TunerFrontendStatus::codeRates>()) {
                    codeRates.push_back(
                            static_cast<hardware::tv::tuner::V1_1::FrontendInnerFec>(aidlCodeRate));
                }
                if (codeRates.size() > 0) {
                    status.codeRates(codeRates);
                    hidlStatus.push_back(status);
                }
                break;
            }
            case TunerFrontendStatus::bandwidth: {
                auto aidlBand = s.get<TunerFrontendStatus::bandwidth>();
                FrontendBandwidth band;
                switch (mType) {
                    case (int)FrontendType::ATSC3:
                        band.atsc3(static_cast<FrontendAtsc3Bandwidth>(aidlBand));
                        status.bandwidth(band);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::DVBC:
                        band.dvbc(static_cast<FrontendDvbcBandwidth>(aidlBand));
                        status.bandwidth(band);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::DVBT:
                        band.dvbt(static_cast<FrontendDvbtBandwidth>(aidlBand));
                        status.bandwidth(band);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::ISDBT:
                        band.isdbt(static_cast<FrontendIsdbtBandwidth>(aidlBand));
                        status.bandwidth(band);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::DTMB:
                        band.dtmb(static_cast<FrontendDtmbBandwidth>(aidlBand));
                        status.bandwidth(band);
                        hidlStatus.push_back(status);
                        break;
                    default:
                        break;
                }
                break;
            }
            case TunerFrontendStatus::interval: {
                auto aidlInter = s.get<TunerFrontendStatus::interval>();
                FrontendGuardInterval inter;
                switch (mType) {
                    case (int)FrontendType::DVBT:
                        inter.dvbt(static_cast<FrontendDvbtGuardInterval>(aidlInter));
                        status.interval(inter);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::ISDBT:
                        inter.isdbt(static_cast<FrontendIsdbtGuardInterval>(aidlInter));
                        status.interval(inter);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::DTMB:
                        inter.dtmb(static_cast<FrontendDtmbGuardInterval>(aidlInter));
                        status.interval(inter);
                        hidlStatus.push_back(status);
                        break;
                    default:
                        break;
                }
                break;
            }
            case TunerFrontendStatus::transmissionMode: {
                auto aidlTran = s.get<TunerFrontendStatus::transmissionMode>();
                FrontendTransmissionMode trans;
                switch (mType) {
                    case (int)FrontendType::DVBT:
                        trans.dvbt(static_cast<FrontendDvbtTransmissionMode>(aidlTran));
                        status.transmissionMode(trans);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::ISDBT:
                        trans.isdbt(static_cast<FrontendIsdbtMode>(aidlTran));
                        status.transmissionMode(trans);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::DTMB:
                        trans.dtmb(static_cast<FrontendDtmbTransmissionMode>(aidlTran));
                        status.transmissionMode(trans);
                        hidlStatus.push_back(status);
                        break;
                    default:
                        break;
                }
                break;
            }
            case TunerFrontendStatus::uec: {
                status.uec((uint32_t)s.get<TunerFrontendStatus::uec>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::systemId: {
                status.systemId((uint16_t)s.get<TunerFrontendStatus::systemId>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::interleaving: {
                vector<FrontendInterleaveMode> modes;
                for (auto aidlInter : s.get<TunerFrontendStatus::interleaving>()) {
                    FrontendInterleaveMode mode;
                    switch (mType) {
                        case (int)FrontendType::DVBC:
                            mode.dvbc(static_cast<FrontendCableTimeInterleaveMode>(aidlInter));
                            modes.push_back(mode);
                            break;
                        case (int)FrontendType::ATSC3:
                            mode.atsc3(static_cast<FrontendAtsc3TimeInterleaveMode>(aidlInter));
                            modes.push_back(mode);
                            break;
                        case (int)FrontendType::DTMB:
                            mode.dtmb(static_cast<FrontendDtmbTimeInterleaveMode>(aidlInter));
                            modes.push_back(mode);
                            break;
                        default:
                            break;
                    }
                }
                if (modes.size() > 0) {
                    status.interleaving(modes);
                    hidlStatus.push_back(status);
                }
                break;
            }
            case TunerFrontendStatus::isdbtSegment: {
                auto aidlSeg = s.get<TunerFrontendStatus::isdbtSegment>();
                hidl_vec<uint8_t> s(aidlSeg.begin(), aidlSeg.end());
                status.isdbtSegment(s);
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::tsDataRate: {
                auto aidlTs = s.get<TunerFrontendStatus::tsDataRate>();
                hidl_vec<uint32_t> ts(aidlTs.begin(), aidlTs.end());
                status.tsDataRate(ts);
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::rollOff: {
                auto aidlRoll = s.get<TunerFrontendStatus::rollOff>();
                FrontendRollOff roll;
                switch (mType) {
                    case (int)FrontendType::DVBS:
                        roll.dvbs(static_cast<FrontendDvbsRolloff>(aidlRoll));
                        status.rollOff(roll);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::ISDBS:
                        roll.isdbs(static_cast<FrontendIsdbsRolloff>(aidlRoll));
                        status.rollOff(roll);
                        hidlStatus.push_back(status);
                        break;
                    case (int)FrontendType::ISDBS3:
                        roll.isdbs3(static_cast<FrontendIsdbs3Rolloff>(aidlRoll));
                        status.rollOff(roll);
                        hidlStatus.push_back(status);
                        break;
                    default:
                        break;
                }
                break;
            }
            case TunerFrontendStatus::isMiso: {
                status.isMiso(s.get<TunerFrontendStatus::isMiso>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::isLinear: {
                status.isLinear(s.get<TunerFrontendStatus::isLinear>());
                hidlStatus.push_back(status);
                break;
            }
            case TunerFrontendStatus::isShortFrames: {
                status.isShortFrames(s.get<TunerFrontendStatus::isShortFrames>());
                hidlStatus.push_back(status);
                break;
            }
            default:
                break;
        }
    }
    return hidlStatus;
}

TunerFrontendSettings FrontendClient::getAidlFrontendSettings(const FrontendSettings& settings,
        const FrontendSettingsExt1_1& settingsExt1_1) {
    bool isExtended = validateExtendedSettings(settingsExt1_1);
    TunerFrontendSettings s{
        .isExtended = isExtended,
        .endFrequency = (int) settingsExt1_1.endFrequency,
        .inversion = (int) settingsExt1_1.inversion,
    };

    if (settingsExt1_1.settingExt.getDiscriminator()
            == FrontendSettingsExt1_1::SettingsExt::hidl_discriminator::dtmb) {
        s.settings.set<TunerFrontendUnionSettings::dtmb>(getAidlDtmbSettings(settingsExt1_1));
        return s;
    }

    switch (settings.getDiscriminator()) {
        case FrontendSettings::hidl_discriminator::analog: {
            s.settings.set<TunerFrontendUnionSettings::analog>(
                    getAidlAnalogSettings(settings, settingsExt1_1));
            break;
        }
        case FrontendSettings::hidl_discriminator::atsc: {
            s.settings.set<TunerFrontendUnionSettings::atsc>(getAidlAtscSettings(settings));
            break;
        }
        case FrontendSettings::hidl_discriminator::atsc3: {
            s.settings.set<TunerFrontendUnionSettings::atsc3>(getAidlAtsc3Settings(settings));
            break;
        }
        case FrontendSettings::hidl_discriminator::dvbs: {
            s.settings.set<TunerFrontendUnionSettings::dvbs>(
                    getAidlDvbsSettings(settings, settingsExt1_1));
            break;
        }
        case FrontendSettings::hidl_discriminator::dvbc: {
            s.settings.set<TunerFrontendUnionSettings::cable>(
                    getAidlCableSettings(settings, settingsExt1_1));
            break;
        }
        case FrontendSettings::hidl_discriminator::dvbt: {
            s.settings.set<TunerFrontendUnionSettings::dvbt>(
                    getAidlDvbtSettings(settings, settingsExt1_1));
            break;
        }
        case FrontendSettings::hidl_discriminator::isdbs: {
            s.settings.set<TunerFrontendUnionSettings::isdbs>(getAidlIsdbsSettings(settings));
            break;
        }
        case FrontendSettings::hidl_discriminator::isdbs3: {
            s.settings.set<TunerFrontendUnionSettings::isdbs3>(getAidlIsdbs3Settings(settings));
            break;
        }
        case FrontendSettings::hidl_discriminator::isdbt: {
            s.settings.set<TunerFrontendUnionSettings::isdbt>(getAidlIsdbtSettings(settings));
            break;
        }
        default:
            break;
    }
    return s;
}

TunerFrontendAnalogSettings FrontendClient::getAidlAnalogSettings(const FrontendSettings& settings,
        const FrontendSettingsExt1_1& settingsExt1_1) {
    TunerFrontendAnalogSettings analogSettings{
        .frequency = (int)settings.analog().frequency,
        .signalType = (int)settings.analog().type,
        .sifStandard = (int)settings.analog().sifStandard,
    };
    if (settingsExt1_1.settingExt.getDiscriminator()
            == FrontendSettingsExt1_1::SettingsExt::hidl_discriminator::analog) {
        analogSettings.isExtended = true;
        analogSettings.aftFlag = (int)settingsExt1_1.settingExt.analog().aftFlag;
    } else {
        analogSettings.isExtended = false;
    }
    return analogSettings;
}

TunerFrontendDvbsSettings FrontendClient::getAidlDvbsSettings(const FrontendSettings& settings,
        const FrontendSettingsExt1_1& settingsExt1_1) {
    TunerFrontendDvbsSettings dvbsSettings{
        .frequency = (int)settings.dvbs().frequency,
        .modulation = (int)settings.dvbs().modulation,
        .codeRate = {
            .fec = (long)settings.dvbs().coderate.fec,
            .isLinear = settings.dvbs().coderate.isLinear,
            .isShortFrames = settings.dvbs().coderate.isShortFrames,
            .bitsPer1000Symbol = (int)settings.dvbs().coderate.bitsPer1000Symbol,
        },
        .symbolRate = (int)settings.dvbs().symbolRate,
        .rolloff = (int)settings.dvbs().rolloff,
        .pilot = (int)settings.dvbs().pilot,
        .inputStreamId = (int)settings.dvbs().inputStreamId,
        .standard = (int)settings.dvbs().standard,
        .vcm = (int)settings.dvbs().vcmMode,
    };
    if (settingsExt1_1.settingExt.getDiscriminator()
            == FrontendSettingsExt1_1::SettingsExt::hidl_discriminator::dvbs) {
        dvbsSettings.isExtended = true;
        dvbsSettings.scanType = (int)settingsExt1_1.settingExt.dvbs().scanType;
        dvbsSettings.isDiseqcRxMessage = settingsExt1_1.settingExt.dvbs().isDiseqcRxMessage;
    } else {
        dvbsSettings.isExtended = false;
    }
    return dvbsSettings;
}

TunerFrontendCableSettings FrontendClient::getAidlCableSettings(const FrontendSettings& settings,
        const FrontendSettingsExt1_1& settingsExt1_1) {
    TunerFrontendCableSettings cableSettings{
        .frequency = (int)settings.dvbc().frequency,
        .modulation = (int)settings.dvbc().modulation,
        .innerFec = (long)settings.dvbc().fec,
        .symbolRate = (int)settings.dvbc().symbolRate,
        .outerFec = (int)settings.dvbc().outerFec,
        .annex = (int)settings.dvbc().annex,
        .spectralInversion = (int)settings.dvbc().spectralInversion,
    };
    if (settingsExt1_1.settingExt.getDiscriminator()
            == FrontendSettingsExt1_1::SettingsExt::hidl_discriminator::dvbc) {
        cableSettings.isExtended = true;
        cableSettings.interleaveMode = (int)settingsExt1_1.settingExt.dvbc().interleaveMode;
        cableSettings.bandwidth = (int)settingsExt1_1.settingExt.dvbc().bandwidth;
    } else {
        cableSettings.isExtended = false;
    }
    return cableSettings;
}

TunerFrontendDvbtSettings FrontendClient::getAidlDvbtSettings(const FrontendSettings& settings,
        const FrontendSettingsExt1_1& settingsExt1_1) {
    TunerFrontendDvbtSettings dvbtSettings{
        .frequency = (int)settings.dvbt().frequency,
        .transmissionMode = (int)settings.dvbt().transmissionMode,
        .bandwidth = (int)settings.dvbt().bandwidth,
        .constellation = (int)settings.dvbt().constellation,
        .hierarchy = (int)settings.dvbt().hierarchy,
        .hpCodeRate = (int)settings.dvbt().hpCoderate,
        .lpCodeRate = (int)settings.dvbt().lpCoderate,
        .guardInterval = (int)settings.dvbt().guardInterval,
        .isHighPriority = settings.dvbt().isHighPriority,
        .standard = (int)settings.dvbt().standard,
        .isMiso = settings.dvbt().isMiso,
        .plpMode = (int)settings.dvbt().plpMode,
        .plpId = (int)settings.dvbt().plpId,
        .plpGroupId = (int)settings.dvbt().plpGroupId,
    };
    if (settingsExt1_1.settingExt.getDiscriminator()
            == FrontendSettingsExt1_1::SettingsExt::hidl_discriminator::dvbt) {
        dvbtSettings.isExtended = true;
        dvbtSettings.constellation = (int)settingsExt1_1.settingExt.dvbt().constellation;
        dvbtSettings.transmissionMode =
                (int)settingsExt1_1.settingExt.dvbt().transmissionMode;
    } else {
        dvbtSettings.isExtended = false;
    }
    return dvbtSettings;
}

TunerFrontendDtmbSettings FrontendClient::getAidlDtmbSettings(
        const FrontendSettingsExt1_1& settingsExt1_1) {
    TunerFrontendDtmbSettings dtmbSettings{
        .frequency = (int)settingsExt1_1.settingExt.dtmb().frequency,
        .transmissionMode = (int)settingsExt1_1.settingExt.dtmb().transmissionMode,
        .bandwidth = (int)settingsExt1_1.settingExt.dtmb().bandwidth,
        .modulation = (int)settingsExt1_1.settingExt.dtmb().modulation,
        .codeRate = (int)settingsExt1_1.settingExt.dtmb().codeRate,
        .guardInterval = (int)settingsExt1_1.settingExt.dtmb().guardInterval,
        .interleaveMode = (int)settingsExt1_1.settingExt.dtmb().interleaveMode,
    };
    return dtmbSettings;
}

TunerFrontendAtscSettings FrontendClient::getAidlAtscSettings(const FrontendSettings& settings) {
    TunerFrontendAtscSettings atscSettings{
        .frequency = (int)settings.atsc().frequency,
        .modulation = (int)settings.atsc().modulation,
    };
    return atscSettings;
}

TunerFrontendAtsc3Settings FrontendClient::getAidlAtsc3Settings(const FrontendSettings& settings) {
    TunerFrontendAtsc3Settings atsc3Settings{
        .frequency = (int)settings.atsc3().frequency,
        .bandwidth = (int)settings.atsc3().bandwidth,
        .demodOutputFormat = (int)settings.atsc3().demodOutputFormat,
    };
    atsc3Settings.plpSettings.resize(settings.atsc3().plpSettings.size());
    for (auto plpSetting : settings.atsc3().plpSettings) {
        atsc3Settings.plpSettings.push_back({
            .plpId = (int)plpSetting.plpId,
            .modulation = (int)plpSetting.modulation,
            .interleaveMode = (int)plpSetting.interleaveMode,
            .codeRate = (int)plpSetting.codeRate,
            .fec = (int)plpSetting.fec,
        });
    }
    return atsc3Settings;
}

TunerFrontendIsdbsSettings FrontendClient::getAidlIsdbsSettings(const FrontendSettings& settings) {
    TunerFrontendIsdbsSettings isdbsSettings{
        .frequency = (int)settings.isdbs().frequency,
        .streamId = (char16_t)settings.isdbs().streamId,
        .streamIdType = (int)settings.isdbs().streamIdType,
        .modulation = (int)settings.isdbs().modulation,
        .codeRate = (int)settings.isdbs().coderate,
        .symbolRate = (int)settings.isdbs().symbolRate,
        .rolloff = (int)settings.isdbs().rolloff,
    };
    return isdbsSettings;
}

TunerFrontendIsdbs3Settings FrontendClient::getAidlIsdbs3Settings(
        const FrontendSettings& settings) {
    TunerFrontendIsdbs3Settings isdbs3Settings{
        .frequency = (int)settings.isdbs3().frequency,
        .streamId = (char16_t)settings.isdbs3().streamId,
        .streamIdType = (int)settings.isdbs3().streamIdType,
        .modulation = (int)settings.isdbs3().modulation,
        .codeRate = (int)settings.isdbs3().coderate,
        .symbolRate = (int)settings.isdbs3().symbolRate,
        .rolloff = (int)settings.isdbs3().rolloff,
    };
    return isdbs3Settings;
}

TunerFrontendIsdbtSettings FrontendClient::getAidlIsdbtSettings(const FrontendSettings& settings) {
    TunerFrontendIsdbtSettings isdbtSettings{
        .frequency = (int)settings.isdbt().frequency,
        .modulation = (int)settings.isdbt().modulation,
        .bandwidth = (int)settings.isdbt().bandwidth,
        .mode = (int)settings.isdbt().mode,
        .codeRate = (int)settings.isdbt().coderate,
        .guardInterval = (int)settings.isdbt().guardInterval,
        .serviceAreaId = (int)settings.isdbt().serviceAreaId,
    };
    return isdbtSettings;
}

bool FrontendClient::validateExtendedSettings(const FrontendSettingsExt1_1& settingsExt1_1) {
    return settingsExt1_1.endFrequency != (uint32_t)Constant::INVALID_FRONTEND_SETTING_FREQUENCY
            || settingsExt1_1.inversion != FrontendSpectralInversion::UNDEFINED
            || settingsExt1_1.settingExt.getDiscriminator()
                    != FrontendSettingsExt1_1::SettingsExt::hidl_discriminator::noinit;
}

/////////////// TunerFrontendCallback ///////////////////////

TunerFrontendCallback::TunerFrontendCallback(sp<FrontendClientCallback> frontendClientCallback)
        : mFrontendClientCallback(frontendClientCallback) {}

Status TunerFrontendCallback::onEvent(int frontendEventType) {
    if (mFrontendClientCallback != NULL) {
        mFrontendClientCallback->onEvent(static_cast<FrontendEventType>(frontendEventType));
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

Status TunerFrontendCallback::onScanMessage(int messageType,
        const TunerFrontendScanMessage& message) {
    if (mFrontendClientCallback != NULL) {
        if (!is1_1ExtendedScanMessage(messageType)) {
            mFrontendClientCallback->onScanMessage(
                    static_cast<FrontendScanMessageType>(messageType),
                    getHalScanMessage(messageType, message));
        } else {
            mFrontendClientCallback->onScanMessageExt1_1(
                    static_cast<FrontendScanMessageTypeExt1_1>(messageType),
                    getHalScanMessageExt1_1(messageType, message));
        }
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

/////////////// IFrontendCallback ///////////////////////

HidlFrontendCallback::HidlFrontendCallback(sp<FrontendClientCallback> frontendClientCallback)
        : mFrontendClientCallback(frontendClientCallback) {}

Return<void> HidlFrontendCallback::onEvent(FrontendEventType frontendEventType) {
    if (mFrontendClientCallback != NULL) {
        mFrontendClientCallback->onEvent(frontendEventType);
    }
    return Void();
}

Return<void> HidlFrontendCallback::onScanMessage(FrontendScanMessageType type,
        const FrontendScanMessage& message) {
    if (mFrontendClientCallback != NULL) {
        mFrontendClientCallback->onScanMessage(type, message);
    }
    return Void();
}

Return<void> HidlFrontendCallback::onScanMessageExt1_1(FrontendScanMessageTypeExt1_1 type,
        const FrontendScanMessageExt1_1& message) {
    if (mFrontendClientCallback != NULL) {
        mFrontendClientCallback->onScanMessageExt1_1(type, message);
    }
    return Void();
}

/////////////// FrontendClientCallback Helper Methods ///////////////////////

FrontendScanMessage TunerFrontendCallback::getHalScanMessage(
        int messageType, const TunerFrontendScanMessage& message) {
    FrontendScanMessage scanMessage;
    switch (messageType) {
        case (int) FrontendScanMessageType::LOCKED:
            scanMessage.isLocked(message.get<TunerFrontendScanMessage::isLocked>());
            break;
        case (int) FrontendScanMessageType::END:
            scanMessage.isEnd(message.get<TunerFrontendScanMessage::isEnd>());
            break;
        case (int) FrontendScanMessageType::PROGRESS_PERCENT:
            scanMessage.progressPercent(message.get<TunerFrontendScanMessage::progressPercent>());
            break;
        case (int) FrontendScanMessageType::FREQUENCY: {
            vector<int> f = message.get<TunerFrontendScanMessage::frequencies>();
            hidl_vec<uint32_t> frequencies(begin(f), end(f));
            scanMessage.frequencies(frequencies);
            break;
        }
        case (int) FrontendScanMessageType::SYMBOL_RATE: {
            vector<int> s = message.get<TunerFrontendScanMessage::symbolRates>();
            hidl_vec<uint32_t> symbolRates(begin(s), end(s));
            scanMessage.symbolRates(symbolRates);
            break;
        }
        case (int) FrontendScanMessageType::HIERARCHY:
            scanMessage.hierarchy(static_cast<FrontendDvbtHierarchy>(
                    message.get<TunerFrontendScanMessage::hierarchy>()));
            break;
        case (int) FrontendScanMessageType::ANALOG_TYPE:
            scanMessage.analogType(static_cast<FrontendAnalogType>(
                    message.get<TunerFrontendScanMessage::analogType>()));
            break;
        case (int) FrontendScanMessageType::PLP_IDS: {
            vector<uint8_t> p = message.get<TunerFrontendScanMessage::plpIds>();
            hidl_vec<uint8_t> plpIds(begin(p), end(p));
            scanMessage.plpIds(plpIds);
            break;
        }
        case (int) FrontendScanMessageType::GROUP_IDS: {
            vector<uint8_t> g = message.get<TunerFrontendScanMessage::groupIds>();
            hidl_vec<uint8_t> groupIds(begin(g), end(g));
            scanMessage.groupIds(groupIds);
            break;
        }
        case (int) FrontendScanMessageType::INPUT_STREAM_IDS: {
            vector<char16_t> i = message.get<TunerFrontendScanMessage::inputStreamIds>();
            hidl_vec<uint16_t> inputStreamIds(begin(i), end(i));
            scanMessage.inputStreamIds(inputStreamIds);
            break;
        }
        case (int) FrontendScanMessageType::STANDARD: {
            FrontendScanMessage::Standard std;
            int standard = message.get<TunerFrontendScanMessage::std>();
            switch (mType) {
                case (int) FrontendType::DVBS:
                    std.sStd(static_cast<FrontendDvbsStandard>(standard));
                    scanMessage.std(std);
                    break;
                case (int) FrontendType::DVBT:
                    std.tStd(static_cast<FrontendDvbtStandard>(standard));
                    scanMessage.std(std);
                    break;
                case (int) FrontendType::ANALOG:
                    std.sifStd(static_cast<FrontendAnalogSifStandard>(standard));
                    scanMessage.std(std);
                    break;
                default:
                    break;
            }
            break;
        }
        case (int) FrontendScanMessageType::ATSC3_PLP_INFO: {
            vector<TunerFrontendScanAtsc3PlpInfo> plp =
                    message.get<TunerFrontendScanMessage::atsc3PlpInfos>();
            hidl_vec<FrontendScanAtsc3PlpInfo> plpInfo;
            int size = plp.size();
            plpInfo.resize(size);
            for (int i = 0; i < size; i++) {
                auto info = message.get<TunerFrontendScanMessage::atsc3PlpInfos>()[i];
                FrontendScanAtsc3PlpInfo p{
                    .plpId = static_cast<uint8_t>(info.plpId),
                    .bLlsFlag = info.llsFlag,
                };
                plpInfo[i] = p;
            }
            scanMessage.atsc3PlpInfos(plpInfo);
            break;
        }
        default:
            break;
    }
    return scanMessage;
}

FrontendScanMessageExt1_1 TunerFrontendCallback::getHalScanMessageExt1_1(
        int messageType, const TunerFrontendScanMessage& message) {
    FrontendScanMessageExt1_1 scanMessage;
    switch (messageType) {
        case (int) FrontendScanMessageTypeExt1_1::HIGH_PRIORITY:
            scanMessage.isHighPriority(message.get<TunerFrontendScanMessage::isHighPriority>());
            break;
        case (int) FrontendScanMessageTypeExt1_1::DVBC_ANNEX:
            scanMessage.annex(static_cast<FrontendDvbcAnnex>(
                    message.get<TunerFrontendScanMessage::annex>()));
            break;
        case (int) FrontendScanMessageTypeExt1_1::MODULATION: {
            FrontendModulation m;
            int modulation = message.get<TunerFrontendScanMessage::modulation>();
            switch (mType) {
                case (int) FrontendType::DVBC:
                    m.dvbc(static_cast<FrontendDvbcModulation>(modulation));
                    scanMessage.modulation(m);
                    break;
                case (int) FrontendType::DVBS:
                    m.dvbs(static_cast<FrontendDvbsModulation>(modulation));
                    scanMessage.modulation(m);
                    break;
                case (int) FrontendType::DVBT:
                    m.dvbt(static_cast<FrontendDvbtConstellation>(modulation));
                    scanMessage.modulation(m);
                    break;
                case (int) FrontendType::ISDBS:
                    m.isdbs(static_cast<FrontendIsdbsModulation>(modulation));
                    scanMessage.modulation(m);
                    break;
                case (int) FrontendType::ISDBS3:
                    m.isdbs3(static_cast<FrontendIsdbs3Modulation>(modulation));
                    scanMessage.modulation(m);
                    break;
                case (int) FrontendType::ISDBT:
                    m.isdbt(static_cast<FrontendIsdbtModulation>(modulation));
                    scanMessage.modulation(m);
                    break;
                case (int) FrontendType::ATSC:
                    m.atsc(static_cast<FrontendAtscModulation>(modulation));
                    scanMessage.modulation(m);
                    break;
                case (int) FrontendType::ATSC3:
                    m.atsc3(static_cast<FrontendAtsc3Modulation>(modulation));
                    scanMessage.modulation(m);
                    break;
                case (int) hardware::tv::tuner::V1_1::FrontendType::DTMB:
                    m.dtmb(static_cast<FrontendDtmbModulation>(modulation));
                    scanMessage.modulation(m);
                    break;
                default:
                    break;
            }
            break;
        }
        default:
            break;
    }
    return scanMessage;
}

bool TunerFrontendCallback::is1_1ExtendedScanMessage(int messageType) {
    return messageType >= (int)FrontendScanMessageTypeExt1_1::MODULATION
            && messageType <= (int)FrontendScanMessageTypeExt1_1::HIGH_PRIORITY;
}
}  // namespace android
