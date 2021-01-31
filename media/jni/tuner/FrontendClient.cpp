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

using ::aidl::android::media::tv::tuner::TunerFrontendDvbtSettings;
using ::aidl::android::media::tv::tuner::TunerFrontendScanAtsc3PlpInfo;

using ::android::hardware::tv::tuner::V1_0::FrontendAnalogSifStandard;
using ::android::hardware::tv::tuner::V1_0::FrontendAnalogType;
using ::android::hardware::tv::tuner::V1_0::FrontendAtscModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendAtsc3Modulation;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbcAnnex;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbcModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbsStandard;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtHierarchy;
using ::android::hardware::tv::tuner::V1_0::FrontendDvbtStandard;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbsModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbs3Modulation;
using ::android::hardware::tv::tuner::V1_0::FrontendIsdbtModulation;
using ::android::hardware::tv::tuner::V1_0::FrontendScanAtsc3PlpInfo;
using ::android::hardware::tv::tuner::V1_0::FrontendType;
using ::android::hardware::tv::tuner::V1_1::Constant;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbModulation;
using ::android::hardware::tv::tuner::V1_1::FrontendDvbtConstellation;
using ::android::hardware::tv::tuner::V1_1::FrontendModulation;

namespace android {

/////////////// FrontendClient ///////////////////////

FrontendClient::FrontendClient(shared_ptr<ITunerFrontend> tunerFrontend, int id, int type) {
    mTunerFrontend = tunerFrontend;
    mAidlCallback = NULL;
    mHidlCallback = NULL;
    mId = id;
    mType = type;
}

FrontendClient::~FrontendClient() {
    mTunerFrontend = NULL;
    mFrontend = NULL;
    mFrontend_1_1 = NULL;
    mAidlCallback = NULL;
    mHidlCallback = NULL;
    mId = -1;
    mType = -1;
}

Result FrontendClient::setCallback(sp<FrontendClientCallback> frontendClientCallback) {
    if (mTunerFrontend != NULL) {
        mAidlCallback = ::ndk::SharedRefBase::make<TunerFrontendCallback>(frontendClientCallback);
        mAidlCallback->setFrontendType(mType);
        mTunerFrontend->setCallback(mAidlCallback);
        return Result::SUCCESS;
    }

    mHidlCallback = new HidlFrontendCallback(frontendClientCallback);
    return mFrontend->setCallback(mHidlCallback);
}

void FrontendClient::setHidlFrontend(sp<IFrontend> frontend) {
    mFrontend = frontend;
    mFrontend_1_1 = ::android::hardware::tv::tuner::V1_1::IFrontend::castFrom(mFrontend);
}

Result FrontendClient::tune(const FrontendSettings& settings,
        const FrontendSettingsExt1_1& settingsExt1_1) {
    if (mTunerFrontend != NULL) {
        // TODO: aidl frontend settings to include Tuner HAL 1.1 settings
        TunerFrontendSettings tunerFeSettings = getAidlFrontendSettings(settings, settingsExt1_1);
        Status s = mTunerFrontend->tune(tunerFeSettings);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    Result result;
    if (mFrontend_1_1 != NULL) {
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
        // TODO: aidl frontend settings to include Tuner HAL 1.1 settings
        TunerFrontendSettings tunerFeSettings = getAidlFrontendSettings(settings, settingsExt1_1);
        Status s = mTunerFrontend->scan(tunerFeSettings, (int)type);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    Result result;
    if (mFrontend_1_1 != NULL) {
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
        // TODO: handle error message.
        /*status = mTunerFrontend->getStatus(statusTypes);
        return status;*/
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
        // TODO: handle error message.
        /*status = mTunerFrontend->getStatusExtended_1_1(statusTypes);
        return status;*/
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
        // TODO: handle error message.
        /*mTunerFrontend->setLnb(lnbClient->getAidlLnb());
        return Result::SUCCESS;*/
    }

    if (mFrontend != NULL) {
        Result result = mFrontend->setLnb(lnbClient->getId());
        return result;
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::setLna(bool bEnable) {
    if (mTunerFrontend != NULL) {
        // TODO: handle error message.
        /*mTunerFrontend->setLna(bEnable);
        return Result::SUCCESS;*/
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
        // TODO: handle error message.
        /*mTunerFrontend->linkCiCamToFrontend(ciCamId, ltsId);
        return ltsId;*/
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
        // TODO: handle error message.
        /*mTunerFrontend->unlinkCiCamToFrontend(ciCamId);
        return Result::SUCCESS;*/
    }

    if (mFrontend_1_1 != NULL) {
        return mFrontend_1_1->unlinkCiCam(static_cast<uint32_t>(ciCamId));
    }

    return Result::INVALID_STATE;
}

Result FrontendClient::close() {
    if (mTunerFrontend != NULL) {
        Status s = mTunerFrontend->close();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFrontend != NULL) {
        Result result = mFrontend->close();
        if (result == Result::SUCCESS) {
            mFrontend = NULL;
            mFrontend_1_1 = NULL;
        }
        return result;
    }

    return Result::INVALID_STATE;
}

shared_ptr<ITunerFrontend> FrontendClient::getAidlFrontend() {
    return mTunerFrontend;
}

int FrontendClient::getId() {
    return mId;
}

TunerFrontendSettings FrontendClient::getAidlFrontendSettings(const FrontendSettings& settings,
        const FrontendSettingsExt1_1& /*settingsExt1_1*/) {
    // TODO: complete hidl to aidl frontend settings conversion
    TunerFrontendSettings s;
    switch (settings.getDiscriminator()) {
        case FrontendSettings::hidl_discriminator::analog: {
            break;
        }
        case FrontendSettings::hidl_discriminator::atsc: {
            break;
        }
        case FrontendSettings::hidl_discriminator::atsc3: {
            break;
        }
        case FrontendSettings::hidl_discriminator::dvbs: {
            break;
        }
        case FrontendSettings::hidl_discriminator::dvbc: {
            break;
        }
        case FrontendSettings::hidl_discriminator::dvbt: {
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
            s.set<TunerFrontendSettings::dvbt>(dvbtSettings);
            break;
        }
        case FrontendSettings::hidl_discriminator::isdbs: {
            break;
        }
        case FrontendSettings::hidl_discriminator::isdbs3: {
            break;
        }
        case FrontendSettings::hidl_discriminator::isdbt: {
            break;
        }
        default:
            break;
    }
    return s;
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
            for (TunerFrontendScanAtsc3PlpInfo info : plp) {
                FrontendScanAtsc3PlpInfo p{
                    .plpId = static_cast<uint8_t>(info.plpId),
                    .bLlsFlag = info.llsFlag,
                };
                int size = plpInfo.size();
                plpInfo.resize(size + 1);
                plpInfo[size] = p;
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
