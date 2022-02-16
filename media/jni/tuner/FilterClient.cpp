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

#define LOG_TAG "FilterClient"

#include <aidlcommonsupport/NativeHandle.h>
#include <android-base/logging.h>
#include <fmq/ConvertMQDescriptors.h>
#include <utils/Log.h>

#include "FilterClient.h"

using ::aidl::android::media::tv::tuner::TunerDemuxIpAddressSettings;
using ::aidl::android::media::tv::tuner::TunerFilterAlpConfiguration;
using ::aidl::android::media::tv::tuner::TunerFilterIpConfiguration;
using ::aidl::android::media::tv::tuner::TunerFilterMmtpConfiguration;
using ::aidl::android::media::tv::tuner::TunerFilterMonitorEvent;
using ::aidl::android::media::tv::tuner::TunerFilterScIndexMask;
using ::aidl::android::media::tv::tuner::TunerFilterSectionBits;
using ::aidl::android::media::tv::tuner::TunerFilterSectionCondition;
using ::aidl::android::media::tv::tuner::TunerFilterSectionTableInfo;
using ::aidl::android::media::tv::tuner::TunerFilterSharedHandleInfo;
using ::aidl::android::media::tv::tuner::TunerFilterTlvConfiguration;
using ::aidl::android::media::tv::tuner::TunerFilterTsConfiguration;
using ::android::hardware::hidl_vec;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterMainType;
using ::android::hardware::tv::tuner::V1_0::DemuxMmtpFilterType;
using ::android::hardware::tv::tuner::V1_0::DemuxQueueNotifyBits;
using ::android::hardware::tv::tuner::V1_0::DemuxStreamId;
using ::android::hardware::tv::tuner::V1_0::DemuxTpid;
using ::android::hardware::tv::tuner::V1_0::DemuxTsFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxTsFilterType;
using ::android::hardware::tv::tuner::V1_1::DemuxFilterMonitorEvent;
using ::android::hardware::tv::tuner::V1_1::ScramblingStatus;

namespace android {

/////////////// FilterClient ///////////////////////

FilterClient::FilterClient(DemuxFilterType type, shared_ptr<ITunerFilter> tunerFilter) {
    mTunerFilter = tunerFilter;
    mAvSharedHandle = NULL;
    checkIsMediaFilter(type);
}

FilterClient::~FilterClient() {
    mTunerFilter = NULL;
    mFilter = NULL;
    mFilter_1_1 = NULL;
    mAvSharedHandle = NULL;
    mAvSharedMemSize = 0;
    mIsMediaFilter = false;
    mIsPassthroughFilter = false;
    mFilterMQ = NULL;
    mFilterMQEventFlag = NULL;
}

// TODO: remove after migration to Tuner Service is done.
void FilterClient::setHidlFilter(sp<IFilter> filter) {
    mFilter = filter;
    mFilter_1_1 = ::android::hardware::tv::tuner::V1_1::IFilter::castFrom(mFilter);
}

int FilterClient::read(int8_t* buffer, int size) {
    Result res = getFilterMq();
    if (res != Result::SUCCESS) {
        return -1;
    }
    return copyData(buffer, size);
}

SharedHandleInfo FilterClient::getAvSharedHandleInfo() {
    handleAvShareMemory();
    SharedHandleInfo info{
        .sharedHandle = (mIsMediaFilter && !mIsPassthroughFilter) ? mAvSharedHandle : NULL,
        .size = mAvSharedMemSize,
    };

    return info;
}

Result FilterClient::configure(DemuxFilterSettings configure) {
    Result res;
    checkIsPassthroughFilter(configure);

    if (mTunerFilter != NULL) {
        Status s = mTunerFilter->configure(getAidlFilterSettings(configure));
        res = ClientHelper::getServiceSpecificErrorCode(s);
        if (res == Result::SUCCESS) {
            getAvSharedHandleInfo();
        }
        return res;
    }

    if (mFilter != NULL) {
        res = mFilter->configure(configure);
        if (res == Result::SUCCESS) {
            getAvSharedHandleInfo();
        }
        return res;
    }

    return Result::INVALID_STATE;
}

Result FilterClient::configureMonitorEvent(int monitorEventType) {
    if (mTunerFilter != NULL) {
        Status s = mTunerFilter->configureMonitorEvent(monitorEventType);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter_1_1 != NULL) {
        return mFilter_1_1->configureMonitorEvent(monitorEventType);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::configureIpFilterContextId(int cid) {
    if (mTunerFilter != NULL) {
        Status s = mTunerFilter->configureIpFilterContextId(cid);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter_1_1 != NULL) {
        return mFilter_1_1->configureIpCid(cid);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::configureAvStreamType(AvStreamType avStreamType) {
    if (mTunerFilter != NULL) {
        int type;
        switch (avStreamType.getDiscriminator()) {
            case AvStreamType::hidl_discriminator::audio:
                type = (int)avStreamType.audio();
                break;
            case AvStreamType::hidl_discriminator::video:
                type = (int)avStreamType.video();
                break;
        }
        Status s = mTunerFilter->configureAvStreamType(type);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter_1_1 != NULL) {
        return mFilter_1_1->configureAvStreamType(avStreamType);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::start() {
    if (mTunerFilter != NULL) {
        Status s = mTunerFilter->start();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter != NULL) {
        return mFilter->start();
    }

    return Result::INVALID_STATE;
}

Result FilterClient::stop() {
    if (mTunerFilter != NULL) {
        Status s = mTunerFilter->stop();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter != NULL) {
        return mFilter->stop();
    }

    return Result::INVALID_STATE;
}

Result FilterClient::flush() {
    if (mTunerFilter != NULL) {
        Status s = mTunerFilter->flush();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter != NULL) {
        return mFilter->flush();
    }

    return Result::INVALID_STATE;
}

Result FilterClient::getId(uint32_t& id) {
    if (mTunerFilter != NULL) {
        int32_t id32Bit;
        Status s = mTunerFilter->getId(&id32Bit);
        id = static_cast<uint32_t>(id32Bit);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter != NULL) {
        Result res;
        mFilter->getId([&](Result r, uint32_t filterId) {
            res = r;
            id = filterId;
        });
        return res;
    }

    return Result::INVALID_STATE;
}

Result FilterClient::getId64Bit(uint64_t& id) {
    if (mTunerFilter != NULL) {
        int64_t id64Bit;
        Status s = mTunerFilter->getId64Bit(&id64Bit);
        id = static_cast<uint64_t>(id64Bit);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter_1_1 != NULL) {
        Result res;
        mFilter_1_1->getId64Bit([&](Result r, uint64_t filterId) {
            res = r;
            id = filterId;
        });
        return res;
    }

    return Result::INVALID_STATE;
}

Result FilterClient::releaseAvHandle(native_handle_t* handle, uint64_t avDataId) {
    if (mTunerFilter != NULL) {
        Status s = mTunerFilter->releaseAvHandle(makeToAidl(handle), avDataId);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter != NULL) {
        return mFilter->releaseAvHandle(hidl_handle(handle), avDataId);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::setDataSource(sp<FilterClient> filterClient){
    if (mTunerFilter != NULL) {
        Status s = mTunerFilter->setDataSource(filterClient->getAidlFilter());
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter != NULL) {
        sp<IFilter> sourceFilter = filterClient->getHalFilter();
        if (sourceFilter == NULL) {
            return Result::INVALID_ARGUMENT;
        }
        return mFilter->setDataSource(sourceFilter);
    }

    return Result::INVALID_STATE;
}

Result FilterClient::close() {
    if (mFilterMQEventFlag) {
        EventFlag::deleteEventFlag(&mFilterMQEventFlag);
    }
    mFilterMQEventFlag = NULL;
    mFilterMQ = NULL;

    if (mTunerFilter != NULL) {
        Status s = mTunerFilter->close();
        closeAvSharedMemory();
        mTunerFilter = NULL;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mFilter != NULL) {
        Result res = mFilter->close();
        mFilter = NULL;
        mFilter_1_1 = NULL;
        closeAvSharedMemory();
        return res;
    }

    return Result::INVALID_STATE;
}

/////////////// IFilterCallback ///////////////////////

HidlFilterCallback::HidlFilterCallback(sp<FilterClientCallback> filterClientCallback)
        : mFilterClientCallback(filterClientCallback) {}

Return<void> HidlFilterCallback::onFilterStatus(const DemuxFilterStatus status) {
    if (mFilterClientCallback != NULL) {
        mFilterClientCallback->onFilterStatus(status);
    }
    return Void();
}

Return<void> HidlFilterCallback::onFilterEvent(const DemuxFilterEvent& filterEvent) {
    if (mFilterClientCallback != NULL) {
        mFilterClientCallback->onFilterEvent(filterEvent);
    }
    return Void();
}

Return<void> HidlFilterCallback::onFilterEvent_1_1(const DemuxFilterEvent& filterEvent,
        const DemuxFilterEventExt& filterEventExt) {
    if (mFilterClientCallback != NULL) {
        mFilterClientCallback->onFilterEvent_1_1(filterEvent, filterEventExt);
    }
    return Void();
}

/////////////// TunerFilterCallback ///////////////////////

TunerFilterCallback::TunerFilterCallback(sp<FilterClientCallback> filterClientCallback)
        : mFilterClientCallback(filterClientCallback) {}

Status TunerFilterCallback::onFilterStatus(int status) {
    if (mFilterClientCallback != NULL) {
        mFilterClientCallback->onFilterStatus(static_cast<DemuxFilterStatus>(status));
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

Status TunerFilterCallback::onFilterEvent(const vector<TunerFilterEvent>& filterEvents) {
    if (mFilterClientCallback == NULL) {
        return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
    }

    if (filterEvents.size() == 0) {
        return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_ARGUMENT));
    }

    DemuxFilterEvent event;
    DemuxFilterEventExt eventExt;
    getHidlFilterEvent(filterEvents, event, eventExt);
    if (eventExt.events.size() > 0) {
        mFilterClientCallback->onFilterEvent_1_1(event, eventExt);
    } else {
        mFilterClientCallback->onFilterEvent(event);
    }

    return Status::ok();
}

/////////////// FilterClient Helper Methods ///////////////////////

TunerFilterConfiguration FilterClient::getAidlFilterSettings(DemuxFilterSettings configure) {
    TunerFilterConfiguration config;
    switch (configure.getDiscriminator()) {
        case DemuxFilterSettings::hidl_discriminator::ts:
            return getAidlTsSettings(configure.ts());
        case DemuxFilterSettings::hidl_discriminator::mmtp:
            return getAidlMmtpSettings(configure.mmtp());
        case DemuxFilterSettings::hidl_discriminator::ip:
            return getAidlIpSettings(configure.ip());
        case DemuxFilterSettings::hidl_discriminator::tlv:
            return getAidlTlvSettings(configure.tlv());
        case DemuxFilterSettings::hidl_discriminator::alp:
            return getAidlAlpSettings(configure.alp());
        default:
            break;
    }
    ALOGE("Wrong DemuxFilterSettings union.");
    return config;
}

TunerFilterConfiguration FilterClient::getAidlTsSettings(DemuxTsFilterSettings ts) {
    TunerFilterConfiguration config;
    TunerFilterSettings filterSettings;
    switch (ts.filterSettings.getDiscriminator()) {
        case DemuxTsFilterSettings::FilterSettings::hidl_discriminator::av: {
            filterSettings.set<TunerFilterSettings::av>(
                    getAidlAvSettings(ts.filterSettings.av()));
            break;
        }
        case DemuxTsFilterSettings::FilterSettings::hidl_discriminator::section: {
            filterSettings.set<TunerFilterSettings::section>(
                    getAidlSectionSettings(ts.filterSettings.section()));
            break;
        }
        case DemuxTsFilterSettings::FilterSettings::hidl_discriminator::pesData: {
            filterSettings.set<TunerFilterSettings::pesData>(
                    getAidlPesDataSettings(ts.filterSettings.pesData()));
            break;
        }
        case DemuxTsFilterSettings::FilterSettings::hidl_discriminator::record: {
            filterSettings.set<TunerFilterSettings::record>(
                    getAidlRecordSettings(ts.filterSettings.record()));
            break;
        }
        default:
            filterSettings.set<TunerFilterSettings::nothing>(true);
            break;
    }

    TunerFilterTsConfiguration aidlTs{
        .tpid = static_cast<char16_t>(ts.tpid),
        .filterSettings = filterSettings,
    };
    config.set<TunerFilterConfiguration::ts>(aidlTs);

    return config;
}

TunerFilterConfiguration FilterClient::getAidlMmtpSettings(DemuxMmtpFilterSettings mmtp) {
    TunerFilterConfiguration config;
    TunerFilterSettings filterSettings;
    switch (mmtp.filterSettings.getDiscriminator()) {
        case DemuxMmtpFilterSettings::FilterSettings::hidl_discriminator::av: {
            filterSettings.set<TunerFilterSettings::av>(
                    getAidlAvSettings(mmtp.filterSettings.av()));
            break;
        }
        case DemuxMmtpFilterSettings::FilterSettings::hidl_discriminator::section: {
            filterSettings.set<TunerFilterSettings::section>(
                    getAidlSectionSettings(mmtp.filterSettings.section()));
            break;
        }
        case DemuxMmtpFilterSettings::FilterSettings::hidl_discriminator::pesData: {
            filterSettings.set<TunerFilterSettings::pesData>(
                    getAidlPesDataSettings(mmtp.filterSettings.pesData()));
            break;
        }
        case DemuxMmtpFilterSettings::FilterSettings::hidl_discriminator::record: {
            filterSettings.set<TunerFilterSettings::record>(
                    getAidlRecordSettings(mmtp.filterSettings.record()));
            break;
        }
        case DemuxMmtpFilterSettings::FilterSettings::hidl_discriminator::download: {
            filterSettings.set<TunerFilterSettings::download>(
                    getAidlDownloadSettings(mmtp.filterSettings.download()));
            break;
        }
        default:
            filterSettings.set<TunerFilterSettings::nothing>(true);
            break;
    }

    TunerFilterMmtpConfiguration aidlMmtp{
        .mmtpPid = static_cast<char16_t>(mmtp.mmtpPid),
        .filterSettings = filterSettings,
    };
    config.set<TunerFilterConfiguration::mmtp>(aidlMmtp);

    return config;
}

TunerFilterConfiguration FilterClient::getAidlIpSettings(DemuxIpFilterSettings ip) {
    TunerFilterConfiguration config;
    TunerFilterSettings filterSettings;
    switch (ip.filterSettings.getDiscriminator()) {
        case DemuxIpFilterSettings::FilterSettings::hidl_discriminator::section: {
            filterSettings.set<TunerFilterSettings::section>(
                    getAidlSectionSettings(ip.filterSettings.section()));
            break;
        }
        case DemuxIpFilterSettings::FilterSettings::hidl_discriminator::bPassthrough: {
            filterSettings.set<TunerFilterSettings::isPassthrough>(
                    ip.filterSettings.bPassthrough());
            break;
        }
        default:
            filterSettings.set<TunerFilterSettings::nothing>(true);
            break;
    }

    TunerDemuxIpAddressSettings ipAddr{
        .srcPort = static_cast<char16_t>(ip.ipAddr.srcPort),
        .dstPort = static_cast<char16_t>(ip.ipAddr.dstPort),
    };
    getAidlIpAddress(ip.ipAddr, ipAddr.srcIpAddress, ipAddr.dstIpAddress);

    TunerFilterIpConfiguration aidlIp{
        .ipAddr = ipAddr,
        .filterSettings = filterSettings,
    };
    config.set<TunerFilterConfiguration::ip>(aidlIp);

    return config;
}

void FilterClient::getAidlIpAddress(DemuxIpAddress ipAddr,
        TunerDemuxIpAddress& srcIpAddress, TunerDemuxIpAddress& dstIpAddress) {
    switch (ipAddr.srcIpAddress.getDiscriminator()) {
        case DemuxIpAddress::SrcIpAddress::hidl_discriminator::v4: {
            int size = ipAddr.srcIpAddress.v4().size();
            srcIpAddress.isIpV6 = false;
            srcIpAddress.addr.resize(size);
            copy(&ipAddr.srcIpAddress.v4()[0], &ipAddr.srcIpAddress.v4()[size],
                    srcIpAddress.addr.begin());
            break;
        }
        case DemuxIpAddress::SrcIpAddress::hidl_discriminator::v6: {
            int size = ipAddr.srcIpAddress.v6().size();
            srcIpAddress.isIpV6 = true;
            srcIpAddress.addr.resize(size);
            copy(&ipAddr.srcIpAddress.v6()[0], &ipAddr.srcIpAddress.v6()[size],
                    srcIpAddress.addr.begin());
            break;
        }
    }
    switch (ipAddr.dstIpAddress.getDiscriminator()) {
        case DemuxIpAddress::DstIpAddress::hidl_discriminator::v4: {
            int size = ipAddr.dstIpAddress.v4().size();
            dstIpAddress.isIpV6 = false;
            dstIpAddress.addr.resize(size);
            copy(&ipAddr.dstIpAddress.v4()[0], &ipAddr.dstIpAddress.v4()[size],
                    dstIpAddress.addr.begin());
            break;
        }
        case DemuxIpAddress::DstIpAddress::hidl_discriminator::v6: {
            int size = ipAddr.dstIpAddress.v6().size();
            dstIpAddress.isIpV6 = true;
            dstIpAddress.addr.resize(size);
            copy(&ipAddr.dstIpAddress.v6()[0], &ipAddr.dstIpAddress.v6()[size],
                    dstIpAddress.addr.begin());
            break;
        }
    }
}

TunerFilterConfiguration FilterClient::getAidlTlvSettings(DemuxTlvFilterSettings tlv) {
    TunerFilterConfiguration config;
    TunerFilterSettings filterSettings;
    switch (tlv.filterSettings.getDiscriminator()) {
        case DemuxTlvFilterSettings::FilterSettings::hidl_discriminator::section: {
            filterSettings.set<TunerFilterSettings::section>(
                    getAidlSectionSettings(tlv.filterSettings.section()));
            break;
        }
        case DemuxTlvFilterSettings::FilterSettings::hidl_discriminator::bPassthrough: {
            filterSettings.set<TunerFilterSettings::isPassthrough>(
                    tlv.filterSettings.bPassthrough());
            break;
        }
        default:
            filterSettings.set<TunerFilterSettings::nothing>(true);
            break;
    }

    TunerFilterTlvConfiguration aidlTlv{
        .packetType = static_cast<int8_t>(tlv.packetType),
        .isCompressedIpPacket = tlv.isCompressedIpPacket,
        .filterSettings = filterSettings,
    };
    config.set<TunerFilterConfiguration::tlv>(aidlTlv);

    return config;
}

TunerFilterConfiguration FilterClient::getAidlAlpSettings(DemuxAlpFilterSettings alp) {
    TunerFilterConfiguration config;
    TunerFilterSettings filterSettings;
    switch (alp.filterSettings.getDiscriminator()) {
        case DemuxAlpFilterSettings::FilterSettings::hidl_discriminator::section: {
            filterSettings.set<TunerFilterSettings::section>(
                    getAidlSectionSettings(alp.filterSettings.section()));
            break;
        }
        default:
            filterSettings.set<TunerFilterSettings::nothing>(true);
            break;
    }

    TunerFilterAlpConfiguration aidlAlp{
        .packetType = static_cast<int8_t>(alp.packetType),
        .lengthType = static_cast<int8_t>(alp.lengthType),
        .filterSettings = filterSettings,
    };
    config.set<TunerFilterConfiguration::alp>(aidlAlp);

    return config;
}

TunerFilterAvSettings FilterClient::getAidlAvSettings(DemuxFilterAvSettings hidlAv) {
    TunerFilterAvSettings aidlAv{
        .isPassthrough = hidlAv.isPassthrough,
    };
    return aidlAv;
}

TunerFilterSectionSettings FilterClient::getAidlSectionSettings(
        DemuxFilterSectionSettings hidlSection) {
    TunerFilterSectionSettings aidlSection;

    switch (hidlSection.condition.getDiscriminator()) {
        case DemuxFilterSectionSettings::Condition::hidl_discriminator::sectionBits: {
            TunerFilterSectionBits sectionBits;
            auto hidlSectionBits = hidlSection.condition.sectionBits();
            sectionBits.filter.resize(hidlSectionBits.filter.size());
            sectionBits.mask.resize(hidlSectionBits.mask.size());
            sectionBits.mode.resize(hidlSectionBits.mode.size());
            copy(hidlSectionBits.filter.begin(), hidlSectionBits.filter.end(),
                    sectionBits.filter.begin());
            copy(hidlSectionBits.mask.begin(), hidlSectionBits.mask.end(),
                    sectionBits.mask.begin());
            copy(hidlSectionBits.mode.begin(), hidlSectionBits.mode.end(),
                    sectionBits.mode.begin());
            aidlSection.condition.set<TunerFilterSectionCondition::sectionBits>(sectionBits);
            break;
        }
        case DemuxFilterSectionSettings::Condition::hidl_discriminator::tableInfo: {
            TunerFilterSectionTableInfo tableInfo{
                .tableId = static_cast<char16_t>(hidlSection.condition.tableInfo().tableId),
                .version = static_cast<char16_t>(hidlSection.condition.tableInfo().version),
            };
            aidlSection.condition.set<TunerFilterSectionCondition::tableInfo>(tableInfo);
            break;
        }
    }
    aidlSection.isCheckCrc = hidlSection.isCheckCrc;
    aidlSection.isRepeat = hidlSection.isRepeat;
    aidlSection.isRaw = hidlSection.isRaw;
    return aidlSection;
}

TunerFilterPesDataSettings FilterClient::getAidlPesDataSettings(
        DemuxFilterPesDataSettings hidlPesData) {
    TunerFilterPesDataSettings aidlPesData{
        .streamId = static_cast<char16_t>(hidlPesData.streamId),
        .isRaw = hidlPesData.isRaw,
    };
    return aidlPesData;
}

TunerFilterRecordSettings FilterClient::getAidlRecordSettings(
        DemuxFilterRecordSettings hidlRecord) {
    TunerFilterScIndexMask mask;
    switch (hidlRecord.scIndexMask.getDiscriminator()) {
        case DemuxFilterRecordSettings::ScIndexMask::hidl_discriminator::sc: {
            mask.set<TunerFilterScIndexMask::sc>(hidlRecord.scIndexMask.sc());
            break;
        }
        case DemuxFilterRecordSettings::ScIndexMask::hidl_discriminator::scHevc: {
            mask.set<TunerFilterScIndexMask::scHevc>(hidlRecord.scIndexMask.scHevc());
            break;
        }
        default:
            break;
    }
    TunerFilterRecordSettings aidlRecord{
        .tsIndexMask = static_cast<int32_t>(hidlRecord.tsIndexMask),
        .scIndexType = static_cast<int32_t>(hidlRecord.scIndexType),
        .scIndexMask = mask,
    };
    return aidlRecord;
}

TunerFilterDownloadSettings FilterClient::getAidlDownloadSettings(
        DemuxFilterDownloadSettings hidlDownload) {
    TunerFilterDownloadSettings aidlDownload{
        .downloadId = static_cast<int32_t>(hidlDownload.downloadId),
    };
    return aidlDownload;
}

void TunerFilterCallback::getHidlFilterEvent(const vector<TunerFilterEvent>& filterEvents,
        DemuxFilterEvent& event, DemuxFilterEventExt& eventExt) {
    switch (filterEvents[0].getTag()) {
        case  TunerFilterEvent::media: {
            getHidlMediaEvent(filterEvents, event);
            break;
        }
        case  TunerFilterEvent::section: {
            getHidlSectionEvent(filterEvents, event);
            break;
        }
        case  TunerFilterEvent::pes: {
            getHidlPesEvent(filterEvents, event);
            break;
        }
        case  TunerFilterEvent::tsRecord: {
            getHidlTsRecordEvent(filterEvents, event, eventExt);
            break;
        }
        case  TunerFilterEvent::mmtpRecord: {
            getHidlMmtpRecordEvent(filterEvents, event, eventExt);
            break;
        }
        case  TunerFilterEvent::download: {
            getHidlDownloadEvent(filterEvents, event);
            break;
        }
        case  TunerFilterEvent::ipPayload: {
            getHidlIpPayloadEvent(filterEvents, event);
            break;
        }
        case  TunerFilterEvent::temi: {
            getHidlTemiEvent(filterEvents, event);
            break;
        }
        case  TunerFilterEvent::monitor: {
            getHidlMonitorEvent(filterEvents, eventExt);
            break;
        }
        case  TunerFilterEvent::startId: {
            getHidlRestartEvent(filterEvents, eventExt);
            break;
        }
    }
}

void TunerFilterCallback::getHidlMediaEvent(
        const vector<TunerFilterEvent>& filterEvents, DemuxFilterEvent& event) {
    event.events.resize(filterEvents.size());
    for (int i = 0; i < filterEvents.size(); i++) {
        hidl_handle handle = hidl_handle(makeFromAidl(filterEvents[i]
                .get<TunerFilterEvent::media>().avMemory));
        event.events[i].media({
            .avMemory = handle,
            .streamId = static_cast<DemuxStreamId>(filterEvents[i]
                    .get<TunerFilterEvent::media>().streamId),
            .isPtsPresent = filterEvents[i]
                    .get<TunerFilterEvent::media>().isPtsPresent,
            .pts = static_cast<uint64_t>(filterEvents[i]
                    .get<TunerFilterEvent::media>().pts),
            .dataLength = static_cast<uint32_t>(filterEvents[i]
                    .get<TunerFilterEvent::media>().dataLength),
            .offset = static_cast<uint32_t>(filterEvents[i]
                    .get<TunerFilterEvent::media>().offset),
            .isSecureMemory = filterEvents[i]
                    .get<TunerFilterEvent::media>().isSecureMemory,
            .avDataId = static_cast<uint64_t>(filterEvents[i]
                    .get<TunerFilterEvent::media>().avDataId),
            .mpuSequenceNumber = static_cast<uint32_t>(filterEvents[i]
                    .get<TunerFilterEvent::media>().offset),
            .isPesPrivateData = filterEvents[i]
                    .get<TunerFilterEvent::media>().isPesPrivateData,
        });

        if (filterEvents[i].get<TunerFilterEvent::media>().isAudioExtraMetaData) {
            event.events[i].media().extraMetaData.audio({
                .adFade = static_cast<uint8_t>(filterEvents[i]
                        .get<TunerFilterEvent::media>().audio.adFade),
                .adPan = static_cast<uint8_t>(filterEvents[i]
                        .get<TunerFilterEvent::media>().audio.adPan),
                .versionTextTag = static_cast<uint8_t>(filterEvents[i]
                        .get<TunerFilterEvent::media>().audio.versionTextTag),
                .adGainCenter = static_cast<uint8_t>(filterEvents[i]
                        .get<TunerFilterEvent::media>().audio.adGainCenter),
                .adGainFront = static_cast<uint8_t>(filterEvents[i]
                        .get<TunerFilterEvent::media>().audio.adGainFront),
                .adGainSurround = static_cast<uint8_t>(filterEvents[i]
                        .get<TunerFilterEvent::media>().audio.adGainSurround),
            });
        } else {
            event.events[i].media().extraMetaData.noinit();
        }
    }
}

void TunerFilterCallback::getHidlSectionEvent(
        const vector<TunerFilterEvent>& filterEvents, DemuxFilterEvent& event) {
    event.events.resize(filterEvents.size());
    for (int i = 0; i < filterEvents.size(); i++) {
        auto section = filterEvents[i].get<TunerFilterEvent::section>();
        event.events[i].section({
            .tableId = static_cast<uint16_t>(section.tableId),
            .version = static_cast<uint16_t>(section.version),
            .sectionNum = static_cast<uint16_t>(section.sectionNum),
            .dataLength = static_cast<uint16_t>(section.dataLength),
        });
    }
}

void TunerFilterCallback::getHidlPesEvent(
        const vector<TunerFilterEvent>& filterEvents, DemuxFilterEvent& event) {
    event.events.resize(filterEvents.size());
    for (int i = 0; i < filterEvents.size(); i++) {
        auto pes = filterEvents[i].get<TunerFilterEvent::pes>();
        event.events[i].pes({
            .streamId = static_cast<DemuxStreamId>(pes.streamId),
            .dataLength = static_cast<uint16_t>(pes.dataLength),
            .mpuSequenceNumber = static_cast<uint32_t>(pes.mpuSequenceNumber),
        });
    }
}

void TunerFilterCallback::getHidlTsRecordEvent(const vector<TunerFilterEvent>& filterEvents,
        DemuxFilterEvent& event, DemuxFilterEventExt& eventExt) {
    event.events.resize(filterEvents.size());
    eventExt.events.resize(filterEvents.size());
    for (int i = 0; i < filterEvents.size(); i++) {
        auto ts = filterEvents[i].get<TunerFilterEvent::tsRecord>();
        event.events[i].tsRecord({
            .tsIndexMask = static_cast<uint32_t>(ts.tsIndexMask),
            .byteNumber = static_cast<uint64_t>(ts.byteNumber),
        });
        event.events[i].tsRecord().pid.tPid(static_cast<DemuxTpid>(ts.pid));

        switch (ts.scIndexMask.getTag()) {
            case TunerFilterScIndexMask::sc: {
                event.events[i].tsRecord().scIndexMask.sc(
                        ts.scIndexMask.get<TunerFilterScIndexMask::sc>());
                break;
            }
            case TunerFilterScIndexMask::scHevc: {
                event.events[i].tsRecord().scIndexMask.scHevc(
                        ts.scIndexMask.get<TunerFilterScIndexMask::scHevc>());
                break;
            }
            default:
                break;
        }

        if (ts.isExtended) {
            eventExt.events[i].tsRecord({
                .pts = static_cast<uint64_t>(ts.pts),
                .firstMbInSlice = static_cast<uint32_t>(ts.firstMbInSlice),
            });
        } else {
            eventExt.events[i].noinit();
        }
    }
}

void TunerFilterCallback::getHidlMmtpRecordEvent(const vector<TunerFilterEvent>& filterEvents,
        DemuxFilterEvent& event, DemuxFilterEventExt& eventExt) {
    event.events.resize(filterEvents.size());
    eventExt.events.resize(filterEvents.size());
    for (int i = 0; i < filterEvents.size(); i++) {
        auto mmtp = filterEvents[i].get<TunerFilterEvent::mmtpRecord>();
        event.events[i].mmtpRecord({
            .scHevcIndexMask = static_cast<uint32_t>(mmtp.scHevcIndexMask),
            .byteNumber = static_cast<uint64_t>(mmtp.byteNumber),
        });

        if (mmtp.isExtended) {
            eventExt.events[i].mmtpRecord({
                .pts = static_cast<uint64_t>(mmtp.pts),
                .mpuSequenceNumber = static_cast<uint32_t>(mmtp.mpuSequenceNumber),
                .firstMbInSlice = static_cast<uint32_t>(mmtp.firstMbInSlice),
                .tsIndexMask = static_cast<uint32_t>(mmtp.tsIndexMask),
            });
        } else {
            eventExt.events[i].noinit();
        }
    }
}

void TunerFilterCallback::getHidlDownloadEvent(const vector<TunerFilterEvent>& filterEvents,
        DemuxFilterEvent& event) {
    event.events.resize(filterEvents.size());
    for (int i = 0; i < filterEvents.size(); i++) {
        auto download = filterEvents[i].get<TunerFilterEvent::download>();
        event.events[i].download({
            .itemId = static_cast<uint32_t>(download.itemId),
            .mpuSequenceNumber = static_cast<uint32_t>(download.mpuSequenceNumber),
            .itemFragmentIndex = static_cast<uint32_t>(download.itemFragmentIndex),
            .lastItemFragmentIndex = static_cast<uint32_t>(download.lastItemFragmentIndex),
            .dataLength = static_cast<uint16_t>(download.dataLength),
        });
    }
}

void TunerFilterCallback::getHidlIpPayloadEvent(const vector<TunerFilterEvent>& filterEvents,
        DemuxFilterEvent& event) {
    event.events.resize(filterEvents.size());
    for (int i = 0; i < filterEvents.size(); i++) {
        auto ip = filterEvents[i].get<TunerFilterEvent::ipPayload>();
        event.events[i].ipPayload({
            .dataLength = static_cast<uint16_t>(ip.dataLength),
        });
    }
}

void TunerFilterCallback::getHidlTemiEvent(const vector<TunerFilterEvent>& filterEvents,
        DemuxFilterEvent& event) {
    event.events.resize(filterEvents.size());
    for (int i = 0; i < filterEvents.size(); i++) {
        auto temi = filterEvents[i].get<TunerFilterEvent::temi>();
        event.events[i].temi({
            .pts = static_cast<uint64_t>(temi.pts),
            .descrTag = static_cast<uint8_t>(temi.descrTag),
        });
        hidl_vec<uint8_t> descrData(temi.descrData.begin(), temi.descrData.end());
        event.events[i].temi().descrData = descrData;
    }
}

void TunerFilterCallback::getHidlMonitorEvent(const vector<TunerFilterEvent>& filterEvents,
        DemuxFilterEventExt& eventExt) {
    auto monitor = filterEvents[0].get<TunerFilterEvent::monitor>();
    eventExt.events.resize(1);
    DemuxFilterMonitorEvent monitorEvent;
    switch (monitor.getTag()) {
        case TunerFilterMonitorEvent::scramblingStatus: {
            monitorEvent.scramblingStatus(static_cast<ScramblingStatus>(monitor.scramblingStatus));
            eventExt.events[0].monitorEvent(monitorEvent);
            break;
        }
        case TunerFilterMonitorEvent::cid: {
            monitorEvent.cid(static_cast<uint32_t>(monitor.cid));
            eventExt.events[0].monitorEvent(monitorEvent);
            break;
        }
    }
}

void TunerFilterCallback::getHidlRestartEvent(const vector<TunerFilterEvent>& filterEvents,
        DemuxFilterEventExt& eventExt) {
    uint32_t startId = filterEvents[0].get<TunerFilterEvent::startId>();
    eventExt.events.resize(1);
    eventExt.events[0].startId(static_cast<uint32_t>(startId));
}

Result FilterClient::getFilterMq() {
    if (mFilterMQ != NULL) {
        return Result::SUCCESS;
    }

    AidlMQDesc aidlMqDesc;
    Result res = Result::UNAVAILABLE;

    if (mTunerFilter != NULL) {
        Status s = mTunerFilter->getQueueDesc(&aidlMqDesc);
        res = ClientHelper::getServiceSpecificErrorCode(s);
        if (res == Result::SUCCESS) {
            mFilterMQ = new (nothrow) AidlMQ(aidlMqDesc, false/*resetPointer*/);
            EventFlag::createEventFlag(mFilterMQ->getEventFlagWord(), &mFilterMQEventFlag);
        }
        return res;
    }

    if (mFilter != NULL) {
        MQDescriptorSync<uint8_t> filterMQDesc;
        mFilter->getQueueDesc(
                [&](Result r, const MQDescriptorSync<uint8_t>& desc) {
                    filterMQDesc = desc;
                    res = r;
                });
        if (res == Result::SUCCESS) {
            AidlMQDesc aidlMQDesc;
            unsafeHidlToAidlMQDescriptor<uint8_t, int8_t, SynchronizedReadWrite>(
                    filterMQDesc,  &aidlMQDesc);
            mFilterMQ = new (nothrow) AidlMessageQueue(aidlMQDesc, false/*resetPointer*/);
            EventFlag::createEventFlag(mFilterMQ->getEventFlagWord(), &mFilterMQEventFlag);
        }
    }

    return res;
}

int FilterClient::copyData(int8_t* buffer, int size) {
    if (mFilterMQ == NULL || mFilterMQEventFlag == NULL) {
        return -1;
    }

    int available = mFilterMQ->availableToRead();
    size = min(size, available);

    if (mFilterMQ->read(buffer, size)) {
        mFilterMQEventFlag->wake(static_cast<uint32_t>(DemuxQueueNotifyBits::DATA_CONSUMED));
    } else {
        return -1;
    }

    return size;
}

void FilterClient::checkIsMediaFilter(DemuxFilterType type) {
    if (type.mainType == DemuxFilterMainType::MMTP) {
        if (type.subType.mmtpFilterType() == DemuxMmtpFilterType::AUDIO ||
                type.subType.mmtpFilterType() == DemuxMmtpFilterType::VIDEO) {
            mIsMediaFilter = true;
            return;
        }
    }

    if (type.mainType == DemuxFilterMainType::TS) {
        if (type.subType.tsFilterType() == DemuxTsFilterType::AUDIO ||
                type.subType.tsFilterType() == DemuxTsFilterType::VIDEO) {
            mIsMediaFilter = true;
            return;
        }
    }

    mIsMediaFilter = false;
}

void FilterClient::checkIsPassthroughFilter(DemuxFilterSettings configure) {
    if (!mIsMediaFilter) {
        mIsPassthroughFilter = false;
        return;
    }

    if (configure.getDiscriminator() == DemuxFilterSettings::hidl_discriminator::ts) {
        if (configure.ts().filterSettings.av().isPassthrough) {
            mIsPassthroughFilter = true;
            return;
        }
    }

    if (configure.getDiscriminator() == DemuxFilterSettings::hidl_discriminator::mmtp) {
        if (configure.mmtp().filterSettings.av().isPassthrough) {
            mIsPassthroughFilter = true;
            return;
        }
    }

    mIsPassthroughFilter = false;
}

void FilterClient::handleAvShareMemory() {
    if (mAvSharedHandle != NULL) {
        return;
    }
    if (mTunerFilter != NULL && mIsMediaFilter && !mIsPassthroughFilter) {
        TunerFilterSharedHandleInfo aidlHandleInfo;
        Status s = mTunerFilter->getAvSharedHandleInfo(&aidlHandleInfo);
        if (ClientHelper::getServiceSpecificErrorCode(s) == Result::SUCCESS) {
            mAvSharedHandle = native_handle_clone(makeFromAidl(aidlHandleInfo.handle));
            mAvSharedMemSize = aidlHandleInfo.size;
        }
        return;
    }

    if (mFilter_1_1 != NULL && mIsMediaFilter && !mIsPassthroughFilter) {
        mFilter_1_1->getAvSharedHandle([&](Result r, hidl_handle avMemory, uint64_t avMemSize) {
            if (r == Result::SUCCESS) {
                mAvSharedHandle = native_handle_clone(avMemory.getNativeHandle());
                mAvSharedMemSize = avMemSize;
            }
        });
    }
}

void FilterClient::closeAvSharedMemory() {
    if (mAvSharedHandle == NULL) {
        mAvSharedMemSize = 0;
        return;
    }
    native_handle_close(mAvSharedHandle);
    native_handle_delete(mAvSharedHandle);
    mAvSharedMemSize = 0;
    mAvSharedHandle = NULL;
}
}  // namespace android
