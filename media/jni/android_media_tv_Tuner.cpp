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

//#define LOG_NDEBUG 0
#define LOG_TAG "TvTuner-JNI"

#include "android_media_tv_Tuner.h"

#include <aidl/android/hardware/tv/tuner/AudioExtraMetaData.h>
#include <aidl/android/hardware/tv/tuner/AudioPresentation.h>
#include <aidl/android/hardware/tv/tuner/AudioStreamType.h>
#include <aidl/android/hardware/tv/tuner/AvStreamType.h>
#include <aidl/android/hardware/tv/tuner/Constant.h>
#include <aidl/android/hardware/tv/tuner/Constant64Bit.h>
#include <aidl/android/hardware/tv/tuner/DataFormat.h>
#include <aidl/android/hardware/tv/tuner/DemuxAlpFilterSettings.h>
#include <aidl/android/hardware/tv/tuner/DemuxAlpFilterType.h>
#include <aidl/android/hardware/tv/tuner/DemuxAlpLengthType.h>
#include <aidl/android/hardware/tv/tuner/DemuxCapabilities.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterAvSettings.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterDownloadEvent.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterDownloadSettings.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterIpPayloadEvent.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterMainType.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterMediaEvent.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterPesDataSettings.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterPesEvent.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterRecordSettings.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterScIndexMask.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterSectionBits.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterSectionEvent.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterSectionSettings.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterSectionSettingsCondition.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterSettings.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterSubType.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterTemiEvent.h>
#include <aidl/android/hardware/tv/tuner/DemuxFilterTsRecordEvent.h>
#include <aidl/android/hardware/tv/tuner/DemuxInfo.h>
#include <aidl/android/hardware/tv/tuner/DemuxIpAddress.h>
#include <aidl/android/hardware/tv/tuner/DemuxIpFilterSettings.h>
#include <aidl/android/hardware/tv/tuner/DemuxIpFilterType.h>
#include <aidl/android/hardware/tv/tuner/DemuxMmtpFilterSettings.h>
#include <aidl/android/hardware/tv/tuner/DemuxMmtpFilterType.h>
#include <aidl/android/hardware/tv/tuner/DemuxQueueNotifyBits.h>
#include <aidl/android/hardware/tv/tuner/DemuxRecordScIndexType.h>
#include <aidl/android/hardware/tv/tuner/DemuxScHevcIndex.h>
#include <aidl/android/hardware/tv/tuner/DemuxScIndex.h>
#include <aidl/android/hardware/tv/tuner/DemuxTlvFilterSettings.h>
#include <aidl/android/hardware/tv/tuner/DemuxTlvFilterType.h>
#include <aidl/android/hardware/tv/tuner/DemuxTsFilterSettings.h>
#include <aidl/android/hardware/tv/tuner/DemuxTsFilterType.h>
#include <aidl/android/hardware/tv/tuner/DemuxTsIndex.h>
#include <aidl/android/hardware/tv/tuner/DvrSettings.h>
#include <aidl/android/hardware/tv/tuner/FilterDelayHint.h>
#include <aidl/android/hardware/tv/tuner/FilterDelayHintType.h>
#include <aidl/android/hardware/tv/tuner/FrontendAnalogAftFlag.h>
#include <aidl/android/hardware/tv/tuner/FrontendAnalogSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendAnalogSifStandard.h>
#include <aidl/android/hardware/tv/tuner/FrontendAnalogType.h>
#include <aidl/android/hardware/tv/tuner/FrontendAtsc3Bandwidth.h>
#include <aidl/android/hardware/tv/tuner/FrontendAtsc3CodeRate.h>
#include <aidl/android/hardware/tv/tuner/FrontendAtsc3DemodOutputFormat.h>
#include <aidl/android/hardware/tv/tuner/FrontendAtsc3Fec.h>
#include <aidl/android/hardware/tv/tuner/FrontendAtsc3Modulation.h>
#include <aidl/android/hardware/tv/tuner/FrontendAtsc3PlpSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendAtsc3Settings.h>
#include <aidl/android/hardware/tv/tuner/FrontendAtsc3TimeInterleaveMode.h>
#include <aidl/android/hardware/tv/tuner/FrontendAtscModulation.h>
#include <aidl/android/hardware/tv/tuner/FrontendAtscSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendBandwidth.h>
#include <aidl/android/hardware/tv/tuner/FrontendCableTimeInterleaveMode.h>
#include <aidl/android/hardware/tv/tuner/FrontendDtmbBandwidth.h>
#include <aidl/android/hardware/tv/tuner/FrontendDtmbCapabilities.h>
#include <aidl/android/hardware/tv/tuner/FrontendDtmbCodeRate.h>
#include <aidl/android/hardware/tv/tuner/FrontendDtmbGuardInterval.h>
#include <aidl/android/hardware/tv/tuner/FrontendDtmbModulation.h>
#include <aidl/android/hardware/tv/tuner/FrontendDtmbSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendDtmbTimeInterleaveMode.h>
#include <aidl/android/hardware/tv/tuner/FrontendDtmbTransmissionMode.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbcAnnex.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbcBandwidth.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbcModulation.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbcOuterFec.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbcSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbsCodeRate.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbsModulation.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbsPilot.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbsRolloff.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbsScanType.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbsSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbsStandard.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbsVcmMode.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbtBandwidth.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbtCoderate.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbtConstellation.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbtGuardInterval.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbtHierarchy.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbtPlpMode.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbtSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbtStandard.h>
#include <aidl/android/hardware/tv/tuner/FrontendDvbtTransmissionMode.h>
#include <aidl/android/hardware/tv/tuner/FrontendGuardInterval.h>
#include <aidl/android/hardware/tv/tuner/FrontendInnerFec.h>
#include <aidl/android/hardware/tv/tuner/FrontendInterleaveMode.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbs3Coderate.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbs3Modulation.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbs3Rolloff.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbs3Settings.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbsCoderate.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbsModulation.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbsRolloff.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbsSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbsStreamIdType.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbtBandwidth.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbtCoderate.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbtGuardInterval.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbtLayerSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbtMode.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbtModulation.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbtPartialReceptionFlag.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbtSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendIsdbtTimeInterleaveMode.h>
#include <aidl/android/hardware/tv/tuner/FrontendIptvSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendIptvSettingsFec.h>
#include <aidl/android/hardware/tv/tuner/FrontendIptvSettingsProtocol.h>
#include <aidl/android/hardware/tv/tuner/FrontendIptvSettingsIgmp.h>
#include <aidl/android/hardware/tv/tuner/FrontendIptvSettingsFecType.h>
#include <aidl/android/hardware/tv/tuner/FrontendModulation.h>
#include <aidl/android/hardware/tv/tuner/FrontendModulationStatus.h>
#include <aidl/android/hardware/tv/tuner/FrontendRollOff.h>
#include <aidl/android/hardware/tv/tuner/FrontendScanAtsc3PlpInfo.h>
#include <aidl/android/hardware/tv/tuner/FrontendScanMessageStandard.h>
#include <aidl/android/hardware/tv/tuner/FrontendSpectralInversion.h>
#include <aidl/android/hardware/tv/tuner/FrontendStatus.h>
#include <aidl/android/hardware/tv/tuner/FrontendStatusAtsc3PlpInfo.h>
#include <aidl/android/hardware/tv/tuner/FrontendStatusType.h>
#include <aidl/android/hardware/tv/tuner/FrontendTransmissionMode.h>
#include <aidl/android/hardware/tv/tuner/FrontendType.h>
#include <aidl/android/hardware/tv/tuner/LnbPosition.h>
#include <aidl/android/hardware/tv/tuner/LnbTone.h>
#include <aidl/android/hardware/tv/tuner/LnbVoltage.h>
#include <aidl/android/hardware/tv/tuner/PlaybackSettings.h>
#include <aidl/android/hardware/tv/tuner/RecordSettings.h>
#include <aidl/android/hardware/tv/tuner/VideoStreamType.h>
#include <aidlcommonsupport/NativeHandle.h>
#include <android-base/logging.h>
#include <media/stagefright/foundation/ADebug.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/ScopedLocalRef.h>
#include <utils/Log.h>

#include "android_media_AudioPresentation.h"
#include "android_media_MediaCodecLinearBlock.h"
#include "android_runtime/AndroidRuntime.h"

#pragma GCC diagnostic ignored "-Wunused-function"

using ::aidl::android::hardware::tv::tuner::AudioExtraMetaData;
using ::aidl::android::hardware::tv::tuner::AudioPreselection;
using ::aidl::android::hardware::tv::tuner::AudioStreamType;
using ::aidl::android::hardware::tv::tuner::AvStreamType;
using ::aidl::android::hardware::tv::tuner::Constant;
using ::aidl::android::hardware::tv::tuner::Constant64Bit;
using ::aidl::android::hardware::tv::tuner::DataFormat;
using ::aidl::android::hardware::tv::tuner::DemuxAlpFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxAlpFilterSettingsFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxAlpFilterType;
using ::aidl::android::hardware::tv::tuner::DemuxAlpLengthType;
using ::aidl::android::hardware::tv::tuner::DemuxCapabilities;
using ::aidl::android::hardware::tv::tuner::DemuxFilterAvSettings;
using ::aidl::android::hardware::tv::tuner::DemuxFilterDownloadEvent;
using ::aidl::android::hardware::tv::tuner::DemuxFilterDownloadSettings;
using ::aidl::android::hardware::tv::tuner::DemuxFilterIpPayloadEvent;
using ::aidl::android::hardware::tv::tuner::DemuxFilterMainType;
using ::aidl::android::hardware::tv::tuner::DemuxFilterMediaEvent;
using ::aidl::android::hardware::tv::tuner::DemuxFilterMediaEventExtraMetaData;
using ::aidl::android::hardware::tv::tuner::DemuxFilterMmtpRecordEvent;
using ::aidl::android::hardware::tv::tuner::DemuxFilterPesDataSettings;
using ::aidl::android::hardware::tv::tuner::DemuxFilterPesEvent;
using ::aidl::android::hardware::tv::tuner::DemuxFilterRecordSettings;
using ::aidl::android::hardware::tv::tuner::DemuxFilterScIndexMask;
using ::aidl::android::hardware::tv::tuner::DemuxFilterSectionBits;
using ::aidl::android::hardware::tv::tuner::DemuxFilterSectionEvent;
using ::aidl::android::hardware::tv::tuner::DemuxFilterSectionSettings;
using ::aidl::android::hardware::tv::tuner::DemuxFilterSectionSettingsCondition;
using ::aidl::android::hardware::tv::tuner::DemuxFilterSectionSettingsConditionTableInfo;
using ::aidl::android::hardware::tv::tuner::DemuxFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxFilterSubType;
using ::aidl::android::hardware::tv::tuner::DemuxFilterTemiEvent;
using ::aidl::android::hardware::tv::tuner::DemuxFilterTsRecordEvent;
using ::aidl::android::hardware::tv::tuner::DemuxInfo;
using ::aidl::android::hardware::tv::tuner::DemuxIpAddress;
using ::aidl::android::hardware::tv::tuner::DemuxIpAddressIpAddress;
using ::aidl::android::hardware::tv::tuner::DemuxIpFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxIpFilterSettingsFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxIpFilterType;
using ::aidl::android::hardware::tv::tuner::DemuxMmtpFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxMmtpFilterSettingsFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxMmtpFilterType;
using ::aidl::android::hardware::tv::tuner::DemuxQueueNotifyBits;
using ::aidl::android::hardware::tv::tuner::DemuxRecordScIndexType;
using ::aidl::android::hardware::tv::tuner::DemuxScHevcIndex;
using ::aidl::android::hardware::tv::tuner::DemuxScIndex;
using ::aidl::android::hardware::tv::tuner::DemuxTlvFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxTlvFilterSettingsFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxTlvFilterType;
using ::aidl::android::hardware::tv::tuner::DemuxTsFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxTsFilterSettingsFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxTsFilterType;
using ::aidl::android::hardware::tv::tuner::DemuxTsIndex;
using ::aidl::android::hardware::tv::tuner::DvrSettings;
using ::aidl::android::hardware::tv::tuner::FilterDelayHint;
using ::aidl::android::hardware::tv::tuner::FilterDelayHintType;
using ::aidl::android::hardware::tv::tuner::FrontendAnalogAftFlag;
using ::aidl::android::hardware::tv::tuner::FrontendAnalogSettings;
using ::aidl::android::hardware::tv::tuner::FrontendAnalogSifStandard;
using ::aidl::android::hardware::tv::tuner::FrontendAnalogType;
using ::aidl::android::hardware::tv::tuner::FrontendAtsc3Bandwidth;
using ::aidl::android::hardware::tv::tuner::FrontendAtsc3CodeRate;
using ::aidl::android::hardware::tv::tuner::FrontendAtsc3DemodOutputFormat;
using ::aidl::android::hardware::tv::tuner::FrontendAtsc3Fec;
using ::aidl::android::hardware::tv::tuner::FrontendAtsc3Modulation;
using ::aidl::android::hardware::tv::tuner::FrontendAtsc3PlpSettings;
using ::aidl::android::hardware::tv::tuner::FrontendAtsc3Settings;
using ::aidl::android::hardware::tv::tuner::FrontendAtsc3TimeInterleaveMode;
using ::aidl::android::hardware::tv::tuner::FrontendAtscModulation;
using ::aidl::android::hardware::tv::tuner::FrontendAtscSettings;
using ::aidl::android::hardware::tv::tuner::FrontendBandwidth;
using ::aidl::android::hardware::tv::tuner::FrontendCableTimeInterleaveMode;
using ::aidl::android::hardware::tv::tuner::FrontendDtmbBandwidth;
using ::aidl::android::hardware::tv::tuner::FrontendDtmbCapabilities;
using ::aidl::android::hardware::tv::tuner::FrontendDtmbCodeRate;
using ::aidl::android::hardware::tv::tuner::FrontendDtmbGuardInterval;
using ::aidl::android::hardware::tv::tuner::FrontendDtmbModulation;
using ::aidl::android::hardware::tv::tuner::FrontendDtmbSettings;
using ::aidl::android::hardware::tv::tuner::FrontendDtmbTimeInterleaveMode;
using ::aidl::android::hardware::tv::tuner::FrontendDtmbTransmissionMode;
using ::aidl::android::hardware::tv::tuner::FrontendDvbcAnnex;
using ::aidl::android::hardware::tv::tuner::FrontendDvbcBandwidth;
using ::aidl::android::hardware::tv::tuner::FrontendDvbcModulation;
using ::aidl::android::hardware::tv::tuner::FrontendDvbcOuterFec;
using ::aidl::android::hardware::tv::tuner::FrontendDvbcSettings;
using ::aidl::android::hardware::tv::tuner::FrontendDvbsCodeRate;
using ::aidl::android::hardware::tv::tuner::FrontendDvbsModulation;
using ::aidl::android::hardware::tv::tuner::FrontendDvbsPilot;
using ::aidl::android::hardware::tv::tuner::FrontendDvbsRolloff;
using ::aidl::android::hardware::tv::tuner::FrontendDvbsScanType;
using ::aidl::android::hardware::tv::tuner::FrontendDvbsSettings;
using ::aidl::android::hardware::tv::tuner::FrontendDvbsStandard;
using ::aidl::android::hardware::tv::tuner::FrontendDvbsVcmMode;
using ::aidl::android::hardware::tv::tuner::FrontendDvbtBandwidth;
using ::aidl::android::hardware::tv::tuner::FrontendDvbtCoderate;
using ::aidl::android::hardware::tv::tuner::FrontendDvbtConstellation;
using ::aidl::android::hardware::tv::tuner::FrontendDvbtGuardInterval;
using ::aidl::android::hardware::tv::tuner::FrontendDvbtHierarchy;
using ::aidl::android::hardware::tv::tuner::FrontendDvbtPlpMode;
using ::aidl::android::hardware::tv::tuner::FrontendDvbtSettings;
using ::aidl::android::hardware::tv::tuner::FrontendDvbtStandard;
using ::aidl::android::hardware::tv::tuner::FrontendDvbtTransmissionMode;
using ::aidl::android::hardware::tv::tuner::FrontendGuardInterval;
using ::aidl::android::hardware::tv::tuner::FrontendInnerFec;
using ::aidl::android::hardware::tv::tuner::FrontendInterleaveMode;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbs3Coderate;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbs3Modulation;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbs3Rolloff;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbs3Settings;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbsCoderate;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbsModulation;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbsRolloff;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbsSettings;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbsStreamIdType;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbtBandwidth;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbtCoderate;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbtGuardInterval;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbtLayerSettings;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbtMode;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbtModulation;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbtPartialReceptionFlag;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbtSettings;
using ::aidl::android::hardware::tv::tuner::FrontendIsdbtTimeInterleaveMode;
using ::aidl::android::hardware::tv::tuner::FrontendIptvSettings;
using ::aidl::android::hardware::tv::tuner::FrontendIptvSettingsFec;
using ::aidl::android::hardware::tv::tuner::FrontendIptvSettingsProtocol;
using ::aidl::android::hardware::tv::tuner::FrontendIptvSettingsIgmp;
using ::aidl::android::hardware::tv::tuner::FrontendIptvSettingsFecType;
using ::aidl::android::hardware::tv::tuner::FrontendModulation;
using ::aidl::android::hardware::tv::tuner::FrontendModulationStatus;
using ::aidl::android::hardware::tv::tuner::FrontendRollOff;
using ::aidl::android::hardware::tv::tuner::FrontendScanAtsc3PlpInfo;
using ::aidl::android::hardware::tv::tuner::FrontendScanMessageStandard;
using ::aidl::android::hardware::tv::tuner::FrontendSpectralInversion;
using ::aidl::android::hardware::tv::tuner::FrontendStatus;
using ::aidl::android::hardware::tv::tuner::FrontendStatusAtsc3PlpInfo;
using ::aidl::android::hardware::tv::tuner::FrontendStatusType;
using ::aidl::android::hardware::tv::tuner::FrontendTransmissionMode;
using ::aidl::android::hardware::tv::tuner::FrontendType;
using ::aidl::android::hardware::tv::tuner::LnbPosition;
using ::aidl::android::hardware::tv::tuner::LnbTone;
using ::aidl::android::hardware::tv::tuner::LnbVoltage;
using ::aidl::android::hardware::tv::tuner::PlaybackSettings;
using ::aidl::android::hardware::tv::tuner::RecordSettings;
using ::aidl::android::hardware::tv::tuner::VideoStreamType;

struct fields_t {
    jfieldID tunerContext;
    jfieldID lnbContext;
    jfieldID filterContext;
    jfieldID timeFilterContext;
    jfieldID descramblerContext;
    jfieldID dvrRecorderContext;
    jfieldID dvrPlaybackContext;
    jfieldID mediaEventContext;
    jfieldID sharedFilterContext;
    jmethodID frontendInitID;
    jmethodID filterInitID;
    jmethodID timeFilterInitID;
    jmethodID dvrRecorderInitID;
    jmethodID dvrPlaybackInitID;
    jmethodID onFrontendEventID;
    jmethodID onFilterStatusID;
    jmethodID onFilterEventID;
    jmethodID lnbInitID;
    jmethodID onLnbEventID;
    jmethodID onLnbDiseqcMessageID;
    jmethodID onDvrRecordStatusID;
    jmethodID onDvrPlaybackStatusID;
    jmethodID descramblerInitID;
    jmethodID linearBlockInitID;
    jmethodID linearBlockSetInternalStateID;
    jmethodID sharedFilterInitID;
    jmethodID onSharedFilterStatusID;
    jmethodID onSharedFilterEventID;
};

static fields_t gFields;

static int IP_V4_LENGTH = 4;
static int IP_V6_LENGTH = 16;

void DestroyCallback(const C2Buffer * buf, void *arg) {
    android::sp<android::MediaEvent> event = (android::MediaEvent *)arg;
    android::Mutex::Autolock autoLock(event->mLock);
    if (event->mLinearBlockObj != nullptr) {
        JNIEnv *env = android::AndroidRuntime::getJNIEnv();
        env->DeleteWeakGlobalRef(event->mLinearBlockObj);
        event->mLinearBlockObj = nullptr;
    }

    event->mAvHandleRefCnt--;
    event->finalize();
    event->decStrong(buf);
}

namespace android {

static JAudioPresentationInfo::fields_t gAudioPresentationFields;

/////////////// LnbClientCallbackImpl ///////////////////////
void LnbClientCallbackImpl::onEvent(const LnbEventType lnbEventType) {
    ALOGV("LnbClientCallbackImpl::onEvent, type=%d", lnbEventType);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    ScopedLocalRef lnb(env, env->NewLocalRef(mLnbObj));
    if (!env->IsSameObject(lnb.get(), nullptr)) {
        env->CallVoidMethod(
                lnb.get(),
                gFields.onLnbEventID,
                (jint)lnbEventType);
    } else {
        ALOGE("LnbClientCallbackImpl::onEvent:"
                "Lnb object has been freed. Ignoring callback.");
    }
}

void LnbClientCallbackImpl::onDiseqcMessage(const vector<uint8_t> &diseqcMessage) {
    ALOGV("LnbClientCallbackImpl::onDiseqcMessage");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    ScopedLocalRef lnb(env, env->NewLocalRef(mLnbObj));
    if (!env->IsSameObject(lnb.get(), nullptr)) {
        ScopedLocalRef array(env, env->NewByteArray(diseqcMessage.size()));
        env->SetByteArrayRegion(array.get(), 0, diseqcMessage.size(),
                                reinterpret_cast<const jbyte *>(&diseqcMessage[0]));
        env->CallVoidMethod(
                lnb.get(),
                gFields.onLnbDiseqcMessageID,
                array.get());
    } else {
        ALOGE("LnbClientCallbackImpl::onDiseqcMessage:"
                "Lnb object has been freed. Ignoring callback.");
    }
}

void LnbClientCallbackImpl::setLnb(jweak lnbObj) {
    ALOGV("LnbClientCallbackImpl::setLnb");
    mLnbObj = lnbObj;
}

LnbClientCallbackImpl::~LnbClientCallbackImpl() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (mLnbObj != nullptr) {
        env->DeleteWeakGlobalRef(mLnbObj);
        mLnbObj = nullptr;
    }
}

/////////////// DvrClientCallbackImpl ///////////////////////
void DvrClientCallbackImpl::onRecordStatus(RecordStatus status) {
    ALOGV("DvrClientCallbackImpl::onRecordStatus");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    ScopedLocalRef dvr(env, env->NewLocalRef(mDvrObj));
    if (!env->IsSameObject(dvr.get(), nullptr)) {
        env->CallVoidMethod(dvr.get(), gFields.onDvrRecordStatusID, (jint)status);
    } else {
        ALOGE("DvrClientCallbackImpl::onRecordStatus:"
                "Dvr object has been freed. Ignoring callback.");
    }
}

void DvrClientCallbackImpl::onPlaybackStatus(PlaybackStatus status) {
    ALOGV("DvrClientCallbackImpl::onPlaybackStatus");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    ScopedLocalRef dvr(env, env->NewLocalRef(mDvrObj));
    if (!env->IsSameObject(dvr.get(), nullptr)) {
        env->CallVoidMethod(dvr.get(), gFields.onDvrPlaybackStatusID, (jint)status);
    } else {
        ALOGE("DvrClientCallbackImpl::onPlaybackStatus:"
                "Dvr object has been freed. Ignoring callback.");
    }
}

void DvrClientCallbackImpl::setDvr(jweak dvrObj) {
    ALOGV("DvrClientCallbackImpl::setDvr");
    mDvrObj = dvrObj;
}

DvrClientCallbackImpl::~DvrClientCallbackImpl() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    if (mDvrObj != nullptr) {
        env->DeleteWeakGlobalRef(mDvrObj);
        mDvrObj = nullptr;
    }
}

/////////////// C2DataIdInfo ///////////////////////
C2DataIdInfo::C2DataIdInfo(uint32_t index, uint64_t value) : C2Param(kParamSize, index) {
    CHECK(isGlobal());
    CHECK_EQ(C2Param::INFO, kind());
    mInfo = StubInfo(value);
    memcpy(static_cast<C2Param *>(this) + 1, static_cast<C2Param *>(&mInfo) + 1,
            kParamSize - sizeof(C2Param));
}

/////////////// MediaEvent ///////////////////////
MediaEvent::MediaEvent(sp<FilterClient> filterClient, native_handle_t *avHandle, int64_t dataId,
                       int64_t dataSize, jobject obj)
      : mFilterClient(filterClient),
        mDataId(dataId),
        mDataSize(dataSize),
        mBuffer(nullptr),
        mDataIdRefCnt(0),
        mAvHandleRefCnt(0),
        mIonHandle(nullptr) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    mMediaEventObj = env->NewWeakGlobalRef(obj);
    mAvHandle = avHandle;
    mLinearBlockObj = nullptr;
}

MediaEvent::~MediaEvent() {
    android::Mutex::Autolock autoLock(mLock);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    env->DeleteWeakGlobalRef(mMediaEventObj);
    mMediaEventObj = nullptr;
    native_handle_delete(mAvHandle);
    if (mIonHandle != nullptr) {
        delete mIonHandle;
    }
    std::shared_ptr<C2Buffer> pC2Buffer = mC2Buffer.lock();
    if (pC2Buffer != nullptr) {
        pC2Buffer->unregisterOnDestroyNotify(&DestroyCallback, this);
    }

    if (mLinearBlockObj != nullptr) {
        env->DeleteWeakGlobalRef(mLinearBlockObj);
        mLinearBlockObj = nullptr;
    }

    mFilterClient = nullptr;
}

void MediaEvent::finalize() {
    if (mAvHandleRefCnt == 0) {
        if (mFilterClient != nullptr) {
            mFilterClient->releaseAvHandle(
                    mAvHandle, mDataIdRefCnt == 0 ? mDataId : 0);
        }
        native_handle_close(mAvHandle);
    }
}

jobject MediaEvent::getLinearBlock() {
    ALOGV("MediaEvent::getLinearBlock");
    if (mAvHandle == nullptr) {
        return nullptr;
    }
    if (mLinearBlockObj != nullptr) {
        return mLinearBlockObj;
    }

    int fd;
    int numInts = 0;
    int memIndex;
    int dataSize;
    SharedHandleInfo info = mFilterClient->getAvSharedHandleInfo();
    native_handle_t* avSharedHandle = info.sharedHandle;
    uint64_t avSharedMemSize = info.size;

    if (mAvHandle->numFds == 0) {
        if (avSharedHandle == nullptr) {
            ALOGE("Shared AV memory handle is not initialized.");
            return nullptr;
        }
        if (avSharedHandle->numFds == 0) {
            ALOGE("Shared AV memory handle is empty.");
            return nullptr;
        }
        fd = avSharedHandle->data[0];
        dataSize = avSharedMemSize;
        numInts = avSharedHandle->numInts;
        if (numInts > 0) {
            // If the first int in the shared native handle has value, use it as the index
            memIndex = avSharedHandle->data[avSharedHandle->numFds];
        }
    } else {
        fd = mAvHandle->data[0];
        dataSize = mDataSize;
        numInts = mAvHandle->numInts;
        if (numInts > 0) {
            // Otherwise if the first int in the av native handle returned from the filter
            // event has value, use it as the index
            memIndex = mAvHandle->data[mAvHandle->numFds];
        } else {
            if (avSharedHandle != nullptr) {
                numInts = avSharedHandle->numInts;
                if (numInts > 0) {
                    // If the first int in the shared native handle has value, use it as the index
                    memIndex = avSharedHandle->data[avSharedHandle->numFds];
                }
            }
        }
    }

    mIonHandle = new C2HandleIon(dup(fd), dataSize);
    std::shared_ptr<C2LinearBlock> block = _C2BlockFactory::CreateLinearBlock(mIonHandle);
    if (block != nullptr) {
        // CreateLinearBlock delete mIonHandle after it create block successfully.
        // ToDo: coordinate who is response to delete mIonHandle
        mIonHandle = nullptr;
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        std::unique_ptr<JMediaCodecLinearBlock> context{new JMediaCodecLinearBlock};
        context->mBlock = block;
        std::shared_ptr<C2Buffer> pC2Buffer = context->toC2Buffer(0, dataSize);
        context->mBuffer = pC2Buffer;
        mC2Buffer = pC2Buffer;
        if (numInts > 0) {
            std::shared_ptr<C2Param> c2param = std::make_shared<C2DataIdInfo>(memIndex, mDataId);
            std::shared_ptr<C2Info> info(std::static_pointer_cast<C2Info>(c2param));
            pC2Buffer->setInfo(info);
        }
        pC2Buffer->registerOnDestroyNotify(&DestroyCallback, this);
        incStrong(pC2Buffer.get());
        jobject linearBlock =
                env->NewObject(
                        env->FindClass("android/media/MediaCodec$LinearBlock"),
                        gFields.linearBlockInitID);
        env->CallVoidMethod(
                linearBlock,
                gFields.linearBlockSetInternalStateID,
                (jlong)context.release(),
                true);
        mLinearBlockObj = env->NewWeakGlobalRef(linearBlock);
        mAvHandleRefCnt++;
        return linearBlock;
    } else {
        native_handle_close(const_cast<native_handle_t *>(
                reinterpret_cast<const native_handle_t *>(mIonHandle)));
        native_handle_delete(const_cast<native_handle_t *>(
                reinterpret_cast<const native_handle_t *>(mIonHandle)));
        mIonHandle = nullptr;
        return nullptr;
    }
}

int64_t MediaEvent::getAudioHandle() {
    mDataIdRefCnt++;
    return mDataId;
}

/////////////// FilterClientCallbackImpl ///////////////////////
void FilterClientCallbackImpl::getSectionEvent(const jobjectArray& arr, const int size,
                                               const DemuxFilterEvent &event) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    const DemuxFilterSectionEvent &sectionEvent = event.get<DemuxFilterEvent::Tag::section>();
    jint tableId = sectionEvent.tableId;
    jint version = sectionEvent.version;
    jint sectionNum = sectionEvent.sectionNum;
    jlong dataLength = sectionEvent.dataLength;

    ScopedLocalRef obj(env, env->NewObject(mSectionEventClass, mSectionEventInitID, tableId,
                                           version, sectionNum, dataLength));
    env->SetObjectArrayElement(arr, size, obj.get());
}

void FilterClientCallbackImpl::getMediaEvent(const jobjectArray& arr, const int size,
                                             const DemuxFilterEvent &event) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    const DemuxFilterMediaEvent &mediaEvent = event.get<DemuxFilterEvent::Tag::media>();
    ScopedLocalRef<jobject> audioDescriptor(env);
    ScopedLocalRef presentationsJObj(env, JAudioPresentationInfo::asJobject(
        env, gAudioPresentationFields));
    switch (mediaEvent.extraMetaData.getTag()) {
        case DemuxFilterMediaEventExtraMetaData::Tag::audio: {

            const AudioExtraMetaData &ad =
                    mediaEvent.extraMetaData.get<DemuxFilterMediaEventExtraMetaData::Tag::audio>();
            jbyte adFade = ad.adFade;
            jbyte adPan = ad.adPan;
            jchar versionTextTag = ad.versionTextTag;
            jbyte adGainCenter = ad.adGainCenter;
            jbyte adGainFront = ad.adGainFront;
            jbyte adGainSurround = ad.adGainSurround;

            audioDescriptor.reset(env->NewObject(mAudioDescriptorClass, mAudioDescriptorInitID,
                                                 adFade, adPan, versionTextTag, adGainCenter,
                                                 adGainFront, adGainSurround));
            break;
        }
        case DemuxFilterMediaEventExtraMetaData::Tag::audioPresentations: {
            JAudioPresentationInfo::addPresentations(
                    env, gAudioPresentationFields,
                    mediaEvent.extraMetaData
                            .get<DemuxFilterMediaEventExtraMetaData::Tag::audioPresentations>(),
                    presentationsJObj.get());
            break;
        }
        default: {
            ALOGE("FilterClientCallbackImpl::getMediaEvent: unknown extraMetaData");
            break;
        }
    }

    jlong dataLength = mediaEvent.dataLength;
    jint streamId = mediaEvent.streamId;
    jboolean isPtsPresent = mediaEvent.isPtsPresent;
    jlong pts = mediaEvent.pts;
    jboolean isDtsPresent = mediaEvent.isDtsPresent;
    jlong dts = mediaEvent.dts;
    jlong offset = mediaEvent.offset;
    jboolean isSecureMemory = mediaEvent.isSecureMemory;
    jlong avDataId = mediaEvent.avDataId;
    jint mpuSequenceNumber = mediaEvent.mpuSequenceNumber;
    jboolean isPesPrivateData = mediaEvent.isPesPrivateData;
    jint sc = 0;
    if (mediaEvent.scIndexMask.getTag() == DemuxFilterScIndexMask::Tag::scIndex) {
        sc = mediaEvent.scIndexMask.get<DemuxFilterScIndexMask::Tag::scIndex>();
    } else if (mediaEvent.scIndexMask.getTag() == DemuxFilterScIndexMask::Tag::scHevc) {
        sc = mediaEvent.scIndexMask.get<DemuxFilterScIndexMask::Tag::scHevc>();
    } else if (mediaEvent.scIndexMask.getTag() == DemuxFilterScIndexMask::Tag::scAvc) {
        sc = mediaEvent.scIndexMask.get<DemuxFilterScIndexMask::Tag::scAvc>();
        // Java uses the values defined by HIDL HAL. Left shift 4 bits.
        sc = sc << 4;
    } else if (mediaEvent.scIndexMask.getTag() == DemuxFilterScIndexMask::Tag::scVvc) {
        sc = mediaEvent.scIndexMask.get<DemuxFilterScIndexMask::Tag::scVvc>();
    }

    ScopedLocalRef obj(env, env->NewObject(mMediaEventClass, mMediaEventInitID, streamId,
                                           isPtsPresent, pts, isDtsPresent, dts, dataLength,
                                           offset, nullptr, isSecureMemory, avDataId,
                                           mpuSequenceNumber, isPesPrivateData, sc,
                                           audioDescriptor.get(), presentationsJObj.get()));

    // Protect mFilterClient from being set to null.
    android::Mutex::Autolock autoLock(mLock);
    uint64_t avSharedMemSize = mFilterClient->getAvSharedHandleInfo().size;
    if (mediaEvent.avMemory.fds.size() > 0 || mediaEvent.avDataId != 0 ||
        (dataLength > 0 && (dataLength + offset) < avSharedMemSize)) {
        sp<MediaEvent> mediaEventSp =
                new MediaEvent(mFilterClient, dupFromAidl(mediaEvent.avMemory),
                               mediaEvent.avDataId, dataLength + offset, obj.get());
        mediaEventSp->mAvHandleRefCnt++;
        env->SetLongField(obj.get(), mMediaEventFieldContextID, (jlong)mediaEventSp.get());
        mediaEventSp->incStrong(obj.get());
    }

    env->SetObjectArrayElement(arr, size, obj.get());
}

void FilterClientCallbackImpl::getPesEvent(const jobjectArray& arr, const int size,
                                           const DemuxFilterEvent &event) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    const DemuxFilterPesEvent &pesEvent = event.get<DemuxFilterEvent::Tag::pes>();
    jint streamId = pesEvent.streamId;
    jint dataLength = pesEvent.dataLength;
    jint mpuSequenceNumber = pesEvent.mpuSequenceNumber;

    ScopedLocalRef obj(env, env->NewObject(mPesEventClass, mPesEventInitID, streamId, dataLength,
                                 mpuSequenceNumber));
    env->SetObjectArrayElement(arr, size, obj.get());
}

void FilterClientCallbackImpl::getTsRecordEvent(const jobjectArray& arr, const int size,
                                                const DemuxFilterEvent &event) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    const DemuxFilterTsRecordEvent &tsRecordEvent = event.get<DemuxFilterEvent::Tag::tsRecord>();
    DemuxPid pid = tsRecordEvent.pid;

    jint jpid = static_cast<jint>(Constant::INVALID_TS_PID);
    if (pid.getTag() == DemuxPid::Tag::tPid) {
        jpid = pid.get<DemuxPid::Tag::tPid>();
    } else if (pid.getTag() == DemuxPid::Tag::mmtpPid) {
        jpid = pid.get<DemuxPid::Tag::mmtpPid>();
    }

    jint sc = 0;
    if (tsRecordEvent.scIndexMask.getTag() == DemuxFilterScIndexMask::Tag::scIndex) {
        sc = tsRecordEvent.scIndexMask.get<DemuxFilterScIndexMask::Tag::scIndex>();
    } else if (tsRecordEvent.scIndexMask.getTag() == DemuxFilterScIndexMask::Tag::scHevc) {
        sc = tsRecordEvent.scIndexMask.get<DemuxFilterScIndexMask::Tag::scHevc>();
    } else if (tsRecordEvent.scIndexMask.getTag() == DemuxFilterScIndexMask::Tag::scAvc) {
        sc = tsRecordEvent.scIndexMask.get<DemuxFilterScIndexMask::Tag::scAvc>();
        // Java uses the values defined by HIDL HAL. Left shift 4 bits.
        sc = sc << 4;
    } else if (tsRecordEvent.scIndexMask.getTag() == DemuxFilterScIndexMask::Tag::scVvc) {
        sc = tsRecordEvent.scIndexMask.get<DemuxFilterScIndexMask::Tag::scVvc>();
    }

    jint ts = tsRecordEvent.tsIndexMask;
    jlong byteNumber = tsRecordEvent.byteNumber;
    jlong pts = tsRecordEvent.pts;
    jint firstMbInSlice = tsRecordEvent.firstMbInSlice;

    ScopedLocalRef obj(env, env->NewObject(mTsRecordEventClass, mTsRecordEventInitID, jpid, ts, sc,
                                 byteNumber, pts, firstMbInSlice));
    env->SetObjectArrayElement(arr, size, obj.get());
}

void FilterClientCallbackImpl::getMmtpRecordEvent(const jobjectArray& arr, const int size,
                                                  const DemuxFilterEvent &event) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    const DemuxFilterMmtpRecordEvent &mmtpRecordEvent =
            event.get<DemuxFilterEvent::Tag::mmtpRecord>();
    jint scHevcIndexMask = mmtpRecordEvent.scHevcIndexMask;
    jlong byteNumber = mmtpRecordEvent.byteNumber;
    jint mpuSequenceNumber = mmtpRecordEvent.mpuSequenceNumber;
    jlong pts = mmtpRecordEvent.pts;
    jint firstMbInSlice = mmtpRecordEvent.firstMbInSlice;
    jlong tsIndexMask = mmtpRecordEvent.tsIndexMask;

    ScopedLocalRef obj(env, env->NewObject(mMmtpRecordEventClass, mMmtpRecordEventInitID,
                                           scHevcIndexMask, byteNumber, mpuSequenceNumber, pts,
                                           firstMbInSlice, tsIndexMask));
    env->SetObjectArrayElement(arr, size, obj.get());
}

void FilterClientCallbackImpl::getDownloadEvent(const jobjectArray& arr, const int size,
                                                const DemuxFilterEvent &event) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    const DemuxFilterDownloadEvent &downloadEvent = event.get<DemuxFilterEvent::Tag::download>();
    jint itemId = downloadEvent.itemId;
    jint downloadId = downloadEvent.downloadId;
    jint mpuSequenceNumber = downloadEvent.mpuSequenceNumber;
    jint itemFragmentIndex = downloadEvent.itemFragmentIndex;
    jint lastItemFragmentIndex = downloadEvent.lastItemFragmentIndex;
    jint dataLength = downloadEvent.dataLength;

    ScopedLocalRef obj(env, env->NewObject(mDownloadEventClass, mDownloadEventInitID, itemId,
                                           downloadId, mpuSequenceNumber, itemFragmentIndex,
                                           lastItemFragmentIndex, dataLength));
    env->SetObjectArrayElement(arr, size, obj.get());
}

void FilterClientCallbackImpl::getIpPayloadEvent(const jobjectArray& arr, const int size,
                                                 const DemuxFilterEvent &event) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    const DemuxFilterIpPayloadEvent &ipPayloadEvent =
        event.get<DemuxFilterEvent::Tag::ipPayload>();
    jint dataLength = ipPayloadEvent.dataLength;
    ScopedLocalRef obj(env, env->NewObject(mIpPayloadEventClass, mIpPayloadEventInitID,
                                           dataLength));
    env->SetObjectArrayElement(arr, size, obj.get());
}

void FilterClientCallbackImpl::getTemiEvent(const jobjectArray& arr, const int size,
                                            const DemuxFilterEvent &event) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    const DemuxFilterTemiEvent &temiEvent = event.get<DemuxFilterEvent::Tag::temi>();
    jlong pts = temiEvent.pts;
    jbyte descrTag = temiEvent.descrTag;
    std::vector<uint8_t> descrData = temiEvent.descrData;

    ScopedLocalRef array(env, env->NewByteArray(descrData.size()));
    env->SetByteArrayRegion(array.get(), 0, descrData.size(),
                            reinterpret_cast<jbyte *>(&descrData[0]));

    ScopedLocalRef obj(env, env->NewObject(mTemiEventClass, mTemiEventInitID, pts, descrTag,
                                           array.get()));
    env->SetObjectArrayElement(arr, size, obj.get());
}

void FilterClientCallbackImpl::getScramblingStatusEvent(const jobjectArray& arr, const int size,
                                                        const DemuxFilterEvent &event) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    const DemuxFilterMonitorEvent &scramblingStatus =
            event.get<DemuxFilterEvent::Tag::monitorEvent>()
                    .get<DemuxFilterMonitorEvent::Tag::scramblingStatus>();
    ScopedLocalRef obj(env, env->NewObject(mScramblingStatusEventClass,
                                           mScramblingStatusEventInitID,
                                           scramblingStatus));
    env->SetObjectArrayElement(arr, size, obj.get());
}

void FilterClientCallbackImpl::getIpCidChangeEvent(const jobjectArray& arr, const int size,
                                                   const DemuxFilterEvent &event) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    const DemuxFilterMonitorEvent &cid = event.get<DemuxFilterEvent::Tag::monitorEvent>()
                                                 .get<DemuxFilterMonitorEvent::Tag::cid>();
    ScopedLocalRef obj(env, env->NewObject(mIpCidChangeEventClass, mIpCidChangeEventInitID, cid));
    env->SetObjectArrayElement(arr, size, obj.get());
}

void FilterClientCallbackImpl::getRestartEvent(const jobjectArray& arr, const int size,
                                               const DemuxFilterEvent &event) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    const int32_t &startId = event.get<DemuxFilterEvent::Tag::startId>();
    ScopedLocalRef obj(env, env->NewObject(mRestartEventClass, mRestartEventInitID, startId));
    env->SetObjectArrayElement(arr, size, obj.get());
}

void FilterClientCallbackImpl::onFilterEvent(const vector<DemuxFilterEvent> &events) {
    ALOGV("FilterClientCallbackImpl::onFilterEvent");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    ScopedLocalRef<jobjectArray> array(env);

    if (!events.empty()) {
        array.reset(env->NewObjectArray(events.size(), mEventClass, nullptr));
    }

    for (int i = 0, arraySize = 0; i < events.size(); i++) {
        const DemuxFilterEvent &event = events[i];
        switch (event.getTag()) {
            case DemuxFilterEvent::Tag::media: {
                getMediaEvent(array.get(), arraySize, event);
                arraySize++;
                break;
            }
            case DemuxFilterEvent::Tag::section: {
                getSectionEvent(array.get(), arraySize, event);
                arraySize++;
                break;
            }
            case DemuxFilterEvent::Tag::pes: {
                getPesEvent(array.get(), arraySize, event);
                arraySize++;
                break;
            }
            case DemuxFilterEvent::Tag::tsRecord: {
                getTsRecordEvent(array.get(), arraySize, event);
                arraySize++;
                break;
            }
            case DemuxFilterEvent::Tag::mmtpRecord: {
                getMmtpRecordEvent(array.get(), arraySize, event);
                arraySize++;
                break;
            }
            case DemuxFilterEvent::Tag::download: {
                getDownloadEvent(array.get(), arraySize, event);
                arraySize++;
                break;
            }
            case DemuxFilterEvent::Tag::ipPayload: {
                getIpPayloadEvent(array.get(), arraySize, event);
                arraySize++;
                break;
            }
            case DemuxFilterEvent::Tag::temi: {
                getTemiEvent(array.get(), arraySize, event);
                arraySize++;
                break;
            }
            case DemuxFilterEvent::Tag::monitorEvent: {
                switch (event.get<DemuxFilterEvent::Tag::monitorEvent>().getTag()) {
                    case DemuxFilterMonitorEvent::Tag::scramblingStatus: {
                        getScramblingStatusEvent(array.get(), arraySize, event);
                        arraySize++;
                        break;
                    }
                    case DemuxFilterMonitorEvent::Tag::cid: {
                        getIpCidChangeEvent(array.get(), arraySize, event);
                        arraySize++;
                        break;
                    }
                    default: {
                        ALOGE("FilterClientCallbackImpl::onFilterEvent: unknown MonitorEvent");
                        break;
                    }
                }
                break;
            }
            case DemuxFilterEvent::Tag::startId: {
                getRestartEvent(array.get(), arraySize, event);
                arraySize++;
                break;
            }
            default: {
                ALOGE("FilterClientCallbackImpl::onFilterEvent: unknown DemuxFilterEvent");
                break;
            }
        }
    }

    ScopedLocalRef<jobject> filter(env);
    {
        android::Mutex::Autolock autoLock(mLock);
        if (env->IsSameObject(mFilterObj, nullptr)) {
            ALOGE("FilterClientCallbackImpl::onFilterEvent:"
                  "Filter object has been freed. Ignoring callback.");
            return;
        } else {
            filter.reset(env->NewLocalRef(mFilterObj));
        }
    }

    jmethodID methodID = gFields.onFilterEventID;
    if (mSharedFilter) {
        methodID = gFields.onSharedFilterEventID;
    }
    env->CallVoidMethod(filter.get(), methodID, array.get());
}

void FilterClientCallbackImpl::onFilterStatus(const DemuxFilterStatus status) {
    ALOGV("FilterClientCallbackImpl::onFilterStatus");
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    ScopedLocalRef<jobject> filter(env);
    {
        android::Mutex::Autolock autoLock(mLock);
        if (env->IsSameObject(mFilterObj, nullptr)) {
            ALOGE("FilterClientCallbackImpl::onFilterStatus:"
                  "Filter object has been freed. Ignoring callback.");
            return;
        } else {
            filter.reset(env->NewLocalRef(mFilterObj));
        }
    }

    jmethodID methodID = gFields.onFilterStatusID;
    if (mSharedFilter) {
        methodID = gFields.onSharedFilterStatusID;
    }
    env->CallVoidMethod(filter.get(), methodID, (jint)static_cast<uint8_t>(status));
}

void FilterClientCallbackImpl::setFilter(jweak filterObj, sp<FilterClient> filterClient) {
    ALOGV("FilterClientCallbackImpl::setFilter");
    // Java Object
    android::Mutex::Autolock autoLock(mLock);
    mFilterObj = filterObj;
    mFilterClient = filterClient;
    mSharedFilter = false;
}

void FilterClientCallbackImpl::setSharedFilter(jweak filterObj, sp<FilterClient> filterClient) {
    ALOGV("FilterClientCallbackImpl::setFilter");
    // Java Object
    android::Mutex::Autolock autoLock(mLock);
    mFilterObj = filterObj;
    mFilterClient = filterClient;
    mSharedFilter = true;
}

FilterClientCallbackImpl::FilterClientCallbackImpl() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    ScopedLocalRef eventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/FilterEvent"));
    ScopedLocalRef sectionEventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/SectionEvent"));
    ScopedLocalRef mediaEventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/MediaEvent"));
    ScopedLocalRef audioDescriptorClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/AudioDescriptor"));
    ScopedLocalRef pesEventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/PesEvent"));
    ScopedLocalRef tsRecordEventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/TsRecordEvent"));
    ScopedLocalRef mmtpRecordEventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/MmtpRecordEvent"));
    ScopedLocalRef downloadEventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/DownloadEvent"));
    ScopedLocalRef ipPayloadEventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/IpPayloadEvent"));
    ScopedLocalRef temiEventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/TemiEvent"));
    ScopedLocalRef scramblingStatusEventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/ScramblingStatusEvent"));
    ScopedLocalRef ipCidChangeEventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/IpCidChangeEvent"));
    ScopedLocalRef restartEventClass =
        ScopedLocalRef(env, env->FindClass("android/media/tv/tuner/filter/RestartEvent"));
    mEventClass = (jclass) env->NewGlobalRef(eventClass.get());
    mSectionEventClass = (jclass) env->NewGlobalRef(sectionEventClass.get());
    mMediaEventClass = (jclass) env->NewGlobalRef(mediaEventClass.get());
    mAudioDescriptorClass = (jclass) env->NewGlobalRef(audioDescriptorClass.get());
    mPesEventClass = (jclass) env->NewGlobalRef(pesEventClass.get());
    mTsRecordEventClass = (jclass) env->NewGlobalRef(tsRecordEventClass.get());
    mMmtpRecordEventClass = (jclass) env->NewGlobalRef(mmtpRecordEventClass.get());
    mDownloadEventClass = (jclass) env->NewGlobalRef(downloadEventClass.get());
    mIpPayloadEventClass = (jclass) env->NewGlobalRef(ipPayloadEventClass.get());
    mTemiEventClass = (jclass) env->NewGlobalRef(temiEventClass.get());
    mScramblingStatusEventClass = (jclass) env->NewGlobalRef(scramblingStatusEventClass.get());
    mIpCidChangeEventClass = (jclass) env->NewGlobalRef(ipCidChangeEventClass.get());
    mRestartEventClass = (jclass) env->NewGlobalRef(restartEventClass.get());
    mSectionEventInitID = env->GetMethodID(mSectionEventClass, "<init>", "(IIIJ)V");
    mMediaEventInitID = env->GetMethodID(
            mMediaEventClass,
            "<init>",
            "(IZJZJJJLandroid/media/MediaCodec$LinearBlock;"
            "ZJIZILandroid/media/tv/tuner/filter/AudioDescriptor;"
            "Ljava/util/List;)V");
    mAudioDescriptorInitID = env->GetMethodID(mAudioDescriptorClass, "<init>", "(BBCBBB)V");
    mPesEventInitID = env->GetMethodID(mPesEventClass, "<init>", "(III)V");
    mTsRecordEventInitID = env->GetMethodID(mTsRecordEventClass, "<init>", "(IIIJJI)V");
    mMmtpRecordEventInitID = env->GetMethodID(mMmtpRecordEventClass, "<init>", "(IJIJII)V");
    mDownloadEventInitID = env->GetMethodID(mDownloadEventClass, "<init>", "(IIIIII)V");
    mIpPayloadEventInitID = env->GetMethodID(mIpPayloadEventClass, "<init>", "(I)V");
    mTemiEventInitID = env->GetMethodID(mTemiEventClass, "<init>", "(JB[B)V");
    mScramblingStatusEventInitID = env->GetMethodID(mScramblingStatusEventClass, "<init>", "(I)V");
    mIpCidChangeEventInitID = env->GetMethodID(mIpCidChangeEventClass, "<init>", "(I)V");
    mRestartEventInitID = env->GetMethodID(mRestartEventClass, "<init>", "(I)V");
    mMediaEventFieldContextID = env->GetFieldID(mMediaEventClass, "mNativeContext", "J");
}

FilterClientCallbackImpl::~FilterClientCallbackImpl() {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    {
        android::Mutex::Autolock autoLock(mLock);
        if (mFilterObj != nullptr) {
            env->DeleteWeakGlobalRef(mFilterObj);
            mFilterObj = nullptr;
        }
        mFilterClient = nullptr;
    }
    env->DeleteGlobalRef(mEventClass);
    env->DeleteGlobalRef(mSectionEventClass);
    env->DeleteGlobalRef(mMediaEventClass);
    env->DeleteGlobalRef(mAudioDescriptorClass);
    env->DeleteGlobalRef(mPesEventClass);
    env->DeleteGlobalRef(mTsRecordEventClass);
    env->DeleteGlobalRef(mMmtpRecordEventClass);
    env->DeleteGlobalRef(mDownloadEventClass);
    env->DeleteGlobalRef(mIpPayloadEventClass);
    env->DeleteGlobalRef(mTemiEventClass);
    env->DeleteGlobalRef(mScramblingStatusEventClass);
    env->DeleteGlobalRef(mIpCidChangeEventClass);
    env->DeleteGlobalRef(mRestartEventClass);
}

/////////////// FrontendClientCallbackImpl ///////////////////////
FrontendClientCallbackImpl::FrontendClientCallbackImpl(JTuner* jtuner, jweak listener) {
    ALOGV("FrontendClientCallbackImpl() with listener:%p", listener);
    addCallbackListener(jtuner, listener);
}

void FrontendClientCallbackImpl::addCallbackListener(JTuner* jtuner, jweak listener) {
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jweak listenerRef = env->NewWeakGlobalRef(listener);
    ALOGV("addCallbackListener() with listener:%p and ref:%p @%p",
              listener, listenerRef, this);
    std::scoped_lock<std::mutex> lock(mMutex);
    mListenersMap[jtuner] = listenerRef;
}

void FrontendClientCallbackImpl::removeCallbackListener(JTuner* listener) {
    ALOGV("removeCallbackListener for listener:%p", listener);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    std::scoped_lock<std::mutex> lock(mMutex);
    if (mListenersMap.find(listener) != mListenersMap.end() && mListenersMap[listener]) {
        env->DeleteWeakGlobalRef(mListenersMap[listener]);
        mListenersMap.erase(listener);
    }
}

void FrontendClientCallbackImpl::onEvent(FrontendEventType frontendEventType) {
    ALOGV("FrontendClientCallbackImpl::onEvent, type=%d", frontendEventType);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    std::scoped_lock<std::mutex> lock(mMutex);
    for (const auto& mapEntry : mListenersMap) {
        ALOGV("JTuner:%p, jweak:%p", mapEntry.first, mapEntry.second);
        ScopedLocalRef frontend(env, env->NewLocalRef(mapEntry.second));
        if (!env->IsSameObject(frontend.get(), nullptr)) {
            env->CallVoidMethod(
                    frontend.get(),
                    gFields.onFrontendEventID,
                    (jint)frontendEventType);
        } else {
            ALOGW("FrontendClientCallbackImpl::onEvent:"
                    "Frontend object has been freed. Ignoring callback.");
        }
    }
}

void FrontendClientCallbackImpl::onScanMessage(
        FrontendScanMessageType type, const FrontendScanMessage& message) {
    ALOGV("FrontendClientCallbackImpl::onScanMessage, type=%d", type);
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    ScopedLocalRef clazz(env, env->FindClass("android/media/tv/tuner/Tuner"));

    std::scoped_lock<std::mutex> lock(mMutex);
    for (const auto& mapEntry : mListenersMap) {
        ScopedLocalRef frontend(env, env->NewLocalRef(mapEntry.second));
        if (env->IsSameObject(frontend.get(), nullptr)) {
            ALOGE("FrontendClientCallbackImpl::onScanMessage:"
                    "Tuner object has been freed. Ignoring callback.");
            continue;
        }
        executeOnScanMessage(env, clazz.get(), frontend.get(), type, message);
    }
}

void FrontendClientCallbackImpl::executeOnScanMessage(
         JNIEnv *env, const jclass& clazz, const jobject& frontend,
         FrontendScanMessageType type,
         const FrontendScanMessage& message) {
    ALOGV("FrontendClientCallbackImpl::executeOnScanMessage, type=%d", type);

    switch(type) {
        case FrontendScanMessageType::LOCKED: {
            if (message.get<FrontendScanMessage::Tag::isLocked>()) {
                env->CallVoidMethod(
                        frontend,
                        env->GetMethodID(clazz, "onLocked", "()V"));
            } else {
                env->CallVoidMethod(
                        frontend,
                        env->GetMethodID(clazz, "onUnlocked", "()V"));
            }
            break;
        }
        case FrontendScanMessageType::END: {
            if (message.get<FrontendScanMessage::Tag::isEnd>()) {
                env->CallVoidMethod(
                        frontend,
                        env->GetMethodID(clazz, "onScanStopped", "()V"));
            }
            break;
        }
        case FrontendScanMessageType::PROGRESS_PERCENT: {
            env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onProgress", "(I)V"),
                                message.get<FrontendScanMessage::Tag::progressPercent>());
            break;
        }
        case FrontendScanMessageType::FREQUENCY: {
            std::vector<int64_t> v = message.get<FrontendScanMessage::Tag::frequencies>();
            ScopedLocalRef freqs(env, env->NewLongArray(v.size()));
            env->SetLongArrayRegion(freqs.get(), 0, v.size(), reinterpret_cast<jlong *>(&v[0]));
            env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onFrequenciesReport", "([J)V"),
                                freqs.get());
            break;
        }
        case FrontendScanMessageType::SYMBOL_RATE: {
            std::vector<int32_t> v = message.get<FrontendScanMessage::Tag::symbolRates>();
            ScopedLocalRef symbolRates(env, env->NewIntArray(v.size()));
            env->SetIntArrayRegion(symbolRates.get(), 0, v.size(),
                                   reinterpret_cast<jint *>(&v[0]));
            env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onSymbolRates", "([I)V"),
                                symbolRates.get());
            break;
        }
        case FrontendScanMessageType::HIERARCHY: {
            env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onHierarchy", "(I)V"),
                                (jint)message.get<FrontendScanMessage::Tag::hierarchy>());
            break;
        }
        case FrontendScanMessageType::ANALOG_TYPE: {
            env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onSignalType", "(I)V"),
                                (jint)message.get<FrontendScanMessage::Tag::analogType>());
            break;
        }
        case FrontendScanMessageType::PLP_IDS: {
            std::vector<int32_t> jintV = message.get<FrontendScanMessage::Tag::plpIds>();
            ScopedLocalRef plpIds(env, env->NewIntArray(jintV.size()));
            env->SetIntArrayRegion(plpIds.get(), 0, jintV.size(),
                                   reinterpret_cast<jint *>(&jintV[0]));
            env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onPlpIds", "([I)V"),
                                plpIds.get());
            break;
        }
        case FrontendScanMessageType::GROUP_IDS: {
            std::vector<int32_t> jintV = message.get<FrontendScanMessage::groupIds>();
            ScopedLocalRef groupIds(env, env->NewIntArray(jintV.size()));
            env->SetIntArrayRegion(groupIds.get(), 0, jintV.size(),
                                   reinterpret_cast<jint *>(&jintV[0]));
            env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onGroupIds", "([I)V"),
                                groupIds.get());
            break;
        }
        case FrontendScanMessageType::INPUT_STREAM_IDS: {
            std::vector<int32_t> jintV = message.get<FrontendScanMessage::inputStreamIds>();
            ScopedLocalRef streamIds(env, env->NewIntArray(jintV.size()));
            env->SetIntArrayRegion(streamIds.get(), 0, jintV.size(),
                                   reinterpret_cast<jint *>(&jintV[0]));
            env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onInputStreamIds", "([I)V"),
                                streamIds.get());
            break;
        }
        case FrontendScanMessageType::STANDARD: {
            FrontendScanMessageStandard std = message.get<FrontendScanMessage::std>();
            jint standard;
            if (std.getTag() == FrontendScanMessageStandard::Tag::sStd) {
                standard = (jint)std.get<FrontendScanMessageStandard::Tag::sStd>();
                env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onDvbsStandard", "(I)V"),
                                    standard);
            } else if (std.getTag() == FrontendScanMessageStandard::Tag::tStd) {
                standard = (jint)std.get<FrontendScanMessageStandard::Tag::tStd>();
                env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onDvbtStandard", "(I)V"),
                                    standard);
            } else if (std.getTag() == FrontendScanMessageStandard::Tag::sifStd) {
                standard = (jint)std.get<FrontendScanMessageStandard::Tag::sifStd>();
                env->CallVoidMethod(frontend,
                                    env->GetMethodID(clazz, "onAnalogSifStandard", "(I)V"),
                                    standard);
            }
            break;
        }
        case FrontendScanMessageType::ATSC3_PLP_INFO: {
            ScopedLocalRef plpClazz(env,
                    env->FindClass("android/media/tv/tuner/frontend/Atsc3PlpInfo"));
            jmethodID init = env->GetMethodID(plpClazz.get(), "<init>", "(IZ)V");
            std::vector<FrontendScanAtsc3PlpInfo> plpInfos =
                    message.get<FrontendScanMessage::atsc3PlpInfos>();
            ScopedLocalRef array(env,
                                 env->NewObjectArray(plpInfos.size(), plpClazz.get(), nullptr));
            for (int i = 0; i < plpInfos.size(); i++) {
                const FrontendScanAtsc3PlpInfo &info = plpInfos[i];
                jint plpId = info.plpId;
                jboolean lls = info.bLlsFlag;
                ScopedLocalRef obj(env, env->NewObject(plpClazz.get(), init, plpId, lls));
                env->SetObjectArrayElement(array.get(), i, obj.get());
            }
            env->CallVoidMethod(frontend,
                                env->GetMethodID(clazz, "onAtsc3PlpInfos",
                                                 "([Landroid/media/tv/tuner/frontend/"
                                                 "Atsc3PlpInfo;)V"),
                                array.get());
            break;
        }
        case FrontendScanMessageType::MODULATION: {
            jint modulationType = -1;
            FrontendModulation modulation = message.get<FrontendScanMessage::modulation>();
            switch (modulation.getTag()) {
                case FrontendModulation::Tag::dvbc: {
                    modulationType = (jint)modulation.get<FrontendModulation::Tag::dvbc>();
                    break;
                }
                case FrontendModulation::Tag::dvbt: {
                    modulationType = (jint)modulation.get<FrontendModulation::Tag::dvbt>();
                    break;
                }
                case FrontendModulation::Tag::dvbs: {
                    modulationType = (jint)modulation.get<FrontendModulation::Tag::dvbs>();
                    break;
                }
                case FrontendModulation::Tag::isdbs: {
                    modulationType = (jint)modulation.get<FrontendModulation::Tag::isdbs>();
                    break;
                }
                case FrontendModulation::Tag::isdbs3: {
                    modulationType = (jint)modulation.get<FrontendModulation::Tag::isdbs3>();
                    break;
                }
                case FrontendModulation::Tag::isdbt: {
                    modulationType = (jint)modulation.get<FrontendModulation::Tag::isdbt>();
                    break;
                }
                case FrontendModulation::Tag::atsc: {
                    modulationType = (jint)modulation.get<FrontendModulation::Tag::atsc>();
                    break;
                }
                case FrontendModulation::Tag::atsc3: {
                    modulationType = (jint)modulation.get<FrontendModulation::Tag::atsc3>();
                    break;
                }
                case FrontendModulation::Tag::dtmb: {
                    modulationType = (jint)modulation.get<FrontendModulation::Tag::dtmb>();
                    break;
                }
                default: {
                    break;
                }
            }
            if (modulationType > 0) {
                env->CallVoidMethod(frontend,
                                    env->GetMethodID(clazz, "onModulationReported", "(I)V"),
                                    modulationType);
            }
            break;
        }
        case FrontendScanMessageType::HIGH_PRIORITY: {
            bool isHighPriority = message.get<FrontendScanMessage::Tag::isHighPriority>();
            env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onPriorityReported", "(Z)V"),
                                isHighPriority);
            break;
        }
        case FrontendScanMessageType::DVBC_ANNEX: {
            jint dvbcAnnex = (jint)message.get<FrontendScanMessage::Tag::annex>();
            env->CallVoidMethod(frontend, env->GetMethodID(clazz, "onDvbcAnnexReported", "(I)V"),
                                dvbcAnnex);
            break;
        }
        case FrontendScanMessageType::DVBT_CELL_IDS: {
            std::vector<int32_t> jintV = message.get<FrontendScanMessage::dvbtCellIds>();
            ScopedLocalRef cellIds(env, env->NewIntArray(jintV.size()));
            env->SetIntArrayRegion(cellIds.get(), 0, jintV.size(),
                                   reinterpret_cast<jint *>(&jintV[0]));
            env->CallVoidMethod(frontend,
                                env->GetMethodID(clazz, "onDvbtCellIdsReported", "([I)V"),
                                cellIds.get());
            break;
        }
        default:
            break;
    }
}

FrontendClientCallbackImpl::~FrontendClientCallbackImpl() {
    JNIEnv *env = android::AndroidRuntime::getJNIEnv();
    ALOGV("~FrontendClientCallbackImpl()");
    std::scoped_lock<std::mutex> lock(mMutex);
    for (const auto& mapEntry : mListenersMap) {
        ALOGV("deleteRef :%p at @ %p", mapEntry.second, this);
        env->DeleteWeakGlobalRef(mapEntry.second);
    }
    mListenersMap.clear();
}

/////////////// Tuner ///////////////////////
sp<TunerClient> JTuner::sTunerClient = nullptr;
std::mutex JTuner::sTunerClientMutex;

JTuner::JTuner(JNIEnv *env, jobject thiz) : mClass(nullptr) {
    jclass clazz = env->GetObjectClass(thiz);
    CHECK(clazz != nullptr);

    mClass = (jclass)env->NewGlobalRef(clazz);
    mObject = env->NewWeakGlobalRef(thiz);
    {
        std::scoped_lock<std::mutex> lock(sTunerClientMutex);
        if (sTunerClient == nullptr) {
            sTunerClient = new TunerClient();
        } else {
            sTunerClient->incStrong(this);
        }
        ALOGV("JTuner refs count: %d", sTunerClient->getStrongCount());
    }
    mSharedFeId = (int)Constant::INVALID_FRONTEND_ID;
}

jweak JTuner::getObject() {
    return mObject;
}

JTuner::~JTuner() {
    if (mFeClient != nullptr) {
        mFeClient->close();
    }
    if (mDemuxClient != nullptr) {
        mDemuxClient->close();
    }
    JNIEnv *env = AndroidRuntime::getJNIEnv();

    env->DeleteWeakGlobalRef(mObject);
    env->DeleteGlobalRef(mClass);
    mFeClient = nullptr;
    mFeClientCb = nullptr;
    mDemuxClient = nullptr;
    {
        std::scoped_lock<std::mutex> lock(sTunerClientMutex);
        int32_t refCnt = sTunerClient->getStrongCount();
        ALOGV("~JTuner refs count: %d", refCnt);
        if (refCnt == 1) {
            sTunerClient = nullptr;
        } else {
            sTunerClient->decStrong(this);
        }
    }
    mClass = nullptr;
    mObject = nullptr;
}

jint JTuner::getTunerVersion() {
    ALOGV("JTuner::getTunerVersion()");
    return (jint)sTunerClient->getHalTunerVersion();
}

jobject JTuner::getFrontendIds() {
    ALOGV("JTuner::getFrontendIds()");
    vector<int32_t> ids = sTunerClient->getFrontendIds();
    if (ids.size() == 0) {
        ALOGW("Frontend isn't available");
        return nullptr;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass arrayListClazz = env->FindClass("java/util/ArrayList");
    jmethodID arrayListAdd = env->GetMethodID(arrayListClazz, "add", "(Ljava/lang/Object;)Z");
    jobject obj = env->NewObject(arrayListClazz,
                                 env->GetMethodID(arrayListClazz, "<init>", "()V"));

    jclass integerClazz = env->FindClass("java/lang/Integer");
    jmethodID intInit = env->GetMethodID(integerClazz, "<init>", "(I)V");

    for (int i = 0; i < ids.size(); i++) {
        jobject idObj = env->NewObject(integerClazz, intInit, ids[i]);
        env->CallBooleanMethod(obj, arrayListAdd, idObj);
    }
    return obj;
}

jobject JTuner::openFrontendByHandle(int feHandle) {
    // TODO: Handle reopening frontend with different handle
    sp<FrontendClient> feClient = sTunerClient->openFrontend(feHandle);
    if (feClient == nullptr) {
        ALOGE("Failed to open frontend");
        return nullptr;
    }
    mFeClient = feClient;

    mFeId = mFeClient->getId();
    if (mDemuxClient != nullptr) {
        mDemuxClient->setFrontendDataSource(mFeClient);
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject tuner(env->NewLocalRef(mObject));
    if (env->IsSameObject(tuner, nullptr)) {
        ALOGE("openFrontendByHandle"
                "Tuner object has been freed. Failed to open frontend.");
        return nullptr;
    }

    mFeClientCb = new FrontendClientCallbackImpl(this, mObject);
    mFeClient->setCallback(mFeClientCb);
    // TODO: add more fields to frontend
    return env->NewObject(
            env->FindClass("android/media/tv/tuner/Tuner$Frontend"),
            gFields.frontendInitID,
            tuner,
            (jint) mFeId);
}

int JTuner::shareFrontend(int feId) {
    if (mFeClient != nullptr) {
        ALOGE("Cannot share frontend:%d because this session is already holding %d",
              feId, mFeClient->getId());
        return (int)Result::INVALID_STATE;
    }

    if (mDemuxClient != NULL) {
        mDemuxClient->setFrontendDataSourceById(feId);
    }

    mSharedFeId = feId;
    return (int)Result::SUCCESS;
}

int JTuner::unshareFrontend() {
    if (mFeClient != nullptr) {
        ALOGE("Cannot unshare frontend because this session is already holding %d"
              " as an owner instead of as a sharee", mFeClient->getId());
        return (int)Result::INVALID_STATE;
    }

    mSharedFeId = (int)Constant::INVALID_FRONTEND_ID;
    return (int)Result::SUCCESS;
}

void JTuner::registerFeCbListener(JTuner* jtuner) {
    ALOGV("registerFeCbListener: %p", jtuner);
    if (mFeClientCb != nullptr && jtuner != nullptr) {
        mFeClientCb->addCallbackListener(jtuner, jtuner->getObject());
    }
}

void JTuner::unregisterFeCbListener(JTuner* jtuner) {
    ALOGV("unregisterFeCbListener: %p", jtuner);
    if (mFeClientCb != nullptr && jtuner != nullptr) {
        mFeClientCb->removeCallbackListener(jtuner);
    }
}

void JTuner::updateFrontend(JTuner* jtuner) {
    if (jtuner == nullptr) {
        ALOGV("JTuner::updateFrontend(null) called for previous owner: %p", this);
        mFeClient = nullptr;
        mFeClientCb = nullptr;
    } else {
        ALOGV("JTuner::updateFrontend(%p) called for new owner: %p", jtuner, this);
        mFeClient = jtuner->mFeClient;
        mFeClientCb = jtuner->mFeClientCb;
    }
}

jobject JTuner::getAnalogFrontendCaps(JNIEnv *env, FrontendCapabilities &caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/AnalogFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(II)V");

    jint typeCap = caps.get<FrontendCapabilities::Tag::analogCaps>().typeCap;
    jint sifStandardCap = caps.get<FrontendCapabilities::Tag::analogCaps>().sifStandardCap;
    return env->NewObject(clazz, capsInit, typeCap, sifStandardCap);
}

jobject JTuner::getAtsc3FrontendCaps(JNIEnv *env, FrontendCapabilities &caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/Atsc3FrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IIIIII)V");

    jint bandwidthCap = caps.get<FrontendCapabilities::Tag::atsc3Caps>().bandwidthCap;
    jint modulationCap = caps.get<FrontendCapabilities::Tag::atsc3Caps>().modulationCap;
    jint timeInterleaveModeCap =
            caps.get<FrontendCapabilities::Tag::atsc3Caps>().timeInterleaveModeCap;
    jint codeRateCap = caps.get<FrontendCapabilities::Tag::atsc3Caps>().codeRateCap;
    jint fecCap = caps.get<FrontendCapabilities::Tag::atsc3Caps>().fecCap;
    jint demodOutputFormatCap =
            caps.get<FrontendCapabilities::Tag::atsc3Caps>().demodOutputFormatCap;

    return env->NewObject(clazz, capsInit, bandwidthCap, modulationCap, timeInterleaveModeCap,
            codeRateCap, fecCap, demodOutputFormatCap);
}

jobject JTuner::getAtscFrontendCaps(JNIEnv *env, FrontendCapabilities &caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/AtscFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(I)V");

    jint modulationCap = caps.get<FrontendCapabilities::Tag::atscCaps>().modulationCap;

    return env->NewObject(clazz, capsInit, modulationCap);
}

jobject JTuner::getDvbcFrontendCaps(JNIEnv *env, FrontendCapabilities &caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbcFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IJI)V");

    jint modulationCap = caps.get<FrontendCapabilities::Tag::dvbcCaps>().modulationCap;
    jlong fecCap = caps.get<FrontendCapabilities::Tag::dvbcCaps>().fecCap;
    jint annexCap = caps.get<FrontendCapabilities::Tag::dvbcCaps>().annexCap;

    return env->NewObject(clazz, capsInit, modulationCap, fecCap, annexCap);
}

jobject JTuner::getDvbsFrontendCaps(JNIEnv *env, FrontendCapabilities &caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbsFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IJI)V");

    jint modulationCap = caps.get<FrontendCapabilities::Tag::dvbsCaps>().modulationCap;
    jlong innerfecCap = caps.get<FrontendCapabilities::Tag::dvbsCaps>().innerfecCap;
    jint standard = caps.get<FrontendCapabilities::Tag::dvbsCaps>().standard;

    return env->NewObject(clazz, capsInit, modulationCap, innerfecCap, standard);
}

jobject JTuner::getDvbtFrontendCaps(JNIEnv *env, FrontendCapabilities &caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbtFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IIIIIIZZ)V");

    jint transmissionModeCap = caps.get<FrontendCapabilities::Tag::dvbtCaps>().transmissionModeCap;
    jint bandwidthCap = caps.get<FrontendCapabilities::Tag::dvbtCaps>().bandwidthCap;
    jint constellationCap = caps.get<FrontendCapabilities::Tag::dvbtCaps>().constellationCap;
    jint coderateCap = caps.get<FrontendCapabilities::Tag::dvbtCaps>().coderateCap;
    jint hierarchyCap = caps.get<FrontendCapabilities::Tag::dvbtCaps>().hierarchyCap;
    jint guardIntervalCap = caps.get<FrontendCapabilities::Tag::dvbtCaps>().guardIntervalCap;
    jboolean isT2Supported = caps.get<FrontendCapabilities::Tag::dvbtCaps>().isT2Supported;
    jboolean isMisoSupported = caps.get<FrontendCapabilities::Tag::dvbtCaps>().isMisoSupported;

    return env->NewObject(clazz, capsInit, transmissionModeCap, bandwidthCap, constellationCap,
            coderateCap, hierarchyCap, guardIntervalCap, isT2Supported, isMisoSupported);
}

jobject JTuner::getIsdbs3FrontendCaps(JNIEnv *env, FrontendCapabilities &caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/Isdbs3FrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(II)V");

    jint modulationCap = caps.get<FrontendCapabilities::Tag::isdbs3Caps>().modulationCap;
    jint coderateCap = caps.get<FrontendCapabilities::Tag::isdbs3Caps>().coderateCap;

    return env->NewObject(clazz, capsInit, modulationCap, coderateCap);
}

jobject JTuner::getIsdbsFrontendCaps(JNIEnv *env, FrontendCapabilities &caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/IsdbsFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(II)V");

    jint modulationCap = caps.get<FrontendCapabilities::Tag::isdbsCaps>().modulationCap;
    jint coderateCap = caps.get<FrontendCapabilities::Tag::isdbsCaps>().coderateCap;

    return env->NewObject(clazz, capsInit, modulationCap, coderateCap);
}

jobject JTuner::getIsdbtFrontendCaps(JNIEnv *env, FrontendCapabilities &caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/IsdbtFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IIIIIIZZ)V");

    jint modeCap = caps.get<FrontendCapabilities::Tag::isdbtCaps>().modeCap;
    jint bandwidthCap = caps.get<FrontendCapabilities::Tag::isdbtCaps>().bandwidthCap;
    jint modulationCap = caps.get<FrontendCapabilities::Tag::isdbtCaps>().modulationCap;
    jint coderateCap = caps.get<FrontendCapabilities::Tag::isdbtCaps>().coderateCap;
    jint guardIntervalCap = caps.get<FrontendCapabilities::Tag::isdbtCaps>().guardIntervalCap;
    jint timeInterleaveCap = caps.get<FrontendCapabilities::Tag::isdbtCaps>().timeInterleaveCap;
    jboolean isSegmentAuto = caps.get<FrontendCapabilities::Tag::isdbtCaps>().isSegmentAuto;
    jboolean isFullSegment = caps.get<FrontendCapabilities::Tag::isdbtCaps>().isFullSegment;

    return env->NewObject(clazz, capsInit, modeCap, bandwidthCap, modulationCap, coderateCap,
                          guardIntervalCap, timeInterleaveCap, isSegmentAuto, isFullSegment);
}

jobject JTuner::getDtmbFrontendCaps(JNIEnv *env, FrontendCapabilities &caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DtmbFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IIIIII)V");

    jint modulationCap = caps.get<FrontendCapabilities::Tag::dtmbCaps>().modulationCap;
    jint transmissionModeCap = caps.get<FrontendCapabilities::Tag::dtmbCaps>().transmissionModeCap;
    jint guardIntervalCap = caps.get<FrontendCapabilities::Tag::dtmbCaps>().guardIntervalCap;
    jint interleaveModeCap = caps.get<FrontendCapabilities::Tag::dtmbCaps>().interleaveModeCap;
    jint codeRateCap = caps.get<FrontendCapabilities::Tag::dtmbCaps>().codeRateCap;
    jint bandwidthCap = caps.get<FrontendCapabilities::Tag::dtmbCaps>().bandwidthCap;

    return env->NewObject(clazz, capsInit, modulationCap, transmissionModeCap, guardIntervalCap,
            interleaveModeCap, codeRateCap, bandwidthCap);
}

jobject JTuner::getIptvFrontendCaps(JNIEnv *env, FrontendCapabilities &caps) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/IptvFrontendCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(I)V");

    jint protocolCap = caps.get<FrontendCapabilities::Tag::iptvCaps>()->protocolCap;

    return env->NewObject(clazz, capsInit, protocolCap);
}

jobject JTuner::getFrontendInfo(int id) {
    shared_ptr<FrontendInfo> feInfo;
    feInfo = sTunerClient->getFrontendInfo(id);
    if (feInfo == nullptr) {
        return nullptr;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/FrontendInfo");
    jmethodID infoInit =
            env->GetMethodID(clazz, "<init>",
                    "(IIJJIIJI[ILandroid/media/tv/tuner/frontend/FrontendCapabilities;)V");

    jint type = (jint)feInfo->type;
    jlong minFrequency = feInfo->minFrequency;
    jlong maxFrequency = feInfo->maxFrequency;
    jint minSymbolRate = feInfo->minSymbolRate;
    jint maxSymbolRate = feInfo->maxSymbolRate;
    jlong acquireRange = feInfo->acquireRange;
    jint exclusiveGroupId = feInfo->exclusiveGroupId;
    jintArray statusCaps = env->NewIntArray(feInfo->statusCaps.size());
    env->SetIntArrayRegion(
            statusCaps, 0, feInfo->statusCaps.size(),
            reinterpret_cast<jint*>(&feInfo->statusCaps[0]));
    FrontendCapabilities caps = feInfo->frontendCaps;

    jobject jcaps = nullptr;
    switch(feInfo->type) {
        case FrontendType::ANALOG:
            if (FrontendCapabilities::Tag::analogCaps == caps.getTag()) {
                jcaps = getAnalogFrontendCaps(env, caps);
            }
            break;
        case FrontendType::ATSC3:
            if (FrontendCapabilities::Tag::atsc3Caps == caps.getTag()) {
                jcaps = getAtsc3FrontendCaps(env, caps);
            }
            break;
        case FrontendType::ATSC:
            if (FrontendCapabilities::Tag::atscCaps == caps.getTag()) {
                jcaps = getAtscFrontendCaps(env, caps);
            }
            break;
        case FrontendType::DVBC:
            if (FrontendCapabilities::Tag::dvbcCaps == caps.getTag()) {
                jcaps = getDvbcFrontendCaps(env, caps);
            }
            break;
        case FrontendType::DVBS:
            if (FrontendCapabilities::Tag::dvbsCaps == caps.getTag()) {
                jcaps = getDvbsFrontendCaps(env, caps);
            }
            break;
        case FrontendType::DVBT:
            if (FrontendCapabilities::Tag::dvbtCaps == caps.getTag()) {
                jcaps = getDvbtFrontendCaps(env, caps);
            }
            break;
        case FrontendType::ISDBS:
            if (FrontendCapabilities::Tag::isdbsCaps == caps.getTag()) {
                jcaps = getIsdbsFrontendCaps(env, caps);
            }
            break;
        case FrontendType::ISDBS3:
            if (FrontendCapabilities::Tag::isdbs3Caps == caps.getTag()) {
                jcaps = getIsdbs3FrontendCaps(env, caps);
            }
            break;
        case FrontendType::ISDBT:
            if (FrontendCapabilities::Tag::isdbtCaps == caps.getTag()) {
                jcaps = getIsdbtFrontendCaps(env, caps);
            }
            break;
        case FrontendType::DTMB:
            if (FrontendCapabilities::Tag::dtmbCaps == caps.getTag()) {
                jcaps = getDtmbFrontendCaps(env, caps);
            }
            break;
        case FrontendType::IPTV:
            if (FrontendCapabilities::Tag::iptvCaps == caps.getTag()) {
                jcaps = getIptvFrontendCaps(env, caps);
            }
            break;
        default:
            break;
    }

    return env->NewObject(clazz, infoInit, id, type, minFrequency, maxFrequency, minSymbolRate,
                          maxSymbolRate, acquireRange, exclusiveGroupId, statusCaps, jcaps);
}

Result JTuner::getFrontendHardwareInfo(string &info) {
    if (mFeClient == nullptr) {
        ALOGE("frontend is not initialized");
        return Result::INVALID_STATE;
    }

    return mFeClient->getHardwareInfo(info);
}

jint JTuner::setMaxNumberOfFrontends(int32_t type, int32_t maxNumber) {
    if (sTunerClient == nullptr) {
        ALOGE("tuner is not initialized");
        return (jint)Result::INVALID_STATE;
    }

    return (jint)sTunerClient->setMaxNumberOfFrontends(static_cast<FrontendType>(type), maxNumber);
}

int32_t JTuner::getMaxNumberOfFrontends(int32_t type) {
    if (sTunerClient == nullptr) {
        ALOGE("tuner is not initialized");
        return -1;
    }

    return sTunerClient->getMaxNumberOfFrontends(static_cast<FrontendType>(type));
}

jint JTuner::removeOutputPid(int32_t pid) {
    if (mFeClient == nullptr) {
        ALOGE("frontend is not initialized");
        return (jint)Result::INVALID_STATE;
    }

    return (jint)mFeClient->removeOutputPid(pid);
}

jobjectArray JTuner::getFrontendStatusReadiness(jintArray types) {
    if (mFeClient == nullptr) {
        ALOGE("frontend is not initialized");
        return nullptr;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jsize size = env->GetArrayLength(types);
    jint intTypes[size];
    env->GetIntArrayRegion(types, 0, size, intTypes);
    std::vector<FrontendStatusType> v;
    for (int i = 0; i < size; i++) {
        v.push_back(static_cast<FrontendStatusType>(intTypes[i]));
    }

    vector<FrontendStatusReadiness> readiness = mFeClient->getStatusReadiness(v);
    if (readiness.size() < size) {
        return nullptr;
    }

    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/FrontendStatusReadiness");
    jmethodID init = env->GetMethodID(clazz, "<init>", "(II)V");
    jobjectArray valObj = env->NewObjectArray(size, clazz, nullptr);
    for (int i = 0; i < size; i++) {
        ScopedLocalRef readinessObj(env, env->NewObject(clazz, init, intTypes[i], readiness[i]));
        env->SetObjectArrayElement(valObj, i, readinessObj.get());
    }
    return valObj;
}

jobject JTuner::openLnbByHandle(int handle) {
    if (sTunerClient == nullptr) {
        return nullptr;
    }

    sp<LnbClient> lnbClient;
    sp<LnbClientCallbackImpl> callback = new LnbClientCallbackImpl();
    lnbClient = sTunerClient->openLnb(handle);
    if (lnbClient == nullptr) {
        ALOGD("Failed to open lnb, handle = %d", handle);
        return nullptr;
    }

    if (lnbClient->setCallback(callback) != Result::SUCCESS) {
        ALOGD("Failed to set lnb callback");
        return nullptr;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject lnbObj = env->NewObject(
            env->FindClass("android/media/tv/tuner/Lnb"),
            gFields.lnbInitID);

    lnbClient->incStrong(lnbObj);
    env->SetLongField(lnbObj, gFields.lnbContext, (jlong)lnbClient.get());
    callback->setLnb(env->NewWeakGlobalRef(lnbObj));

    return lnbObj;
}

jobject JTuner::openLnbByName(jstring name) {
    if (sTunerClient == nullptr) {
        return nullptr;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    std::string lnbName(env->GetStringUTFChars(name, nullptr));
    sp<LnbClient> lnbClient;
    sp<LnbClientCallbackImpl> callback = new LnbClientCallbackImpl();
    lnbClient = sTunerClient->openLnbByName(lnbName);
    if (lnbClient == nullptr) {
        ALOGD("Failed to open lnb by name, name = %s", lnbName.c_str());
        return nullptr;
    }

    if (lnbClient->setCallback(callback) != Result::SUCCESS) {
        ALOGD("Failed to set lnb callback");
        return nullptr;
    }

    jobject lnbObj = env->NewObject(
            env->FindClass("android/media/tv/tuner/Lnb"),
            gFields.lnbInitID);

    lnbClient->incStrong(lnbObj);
    env->SetLongField(lnbObj, gFields.lnbContext, (jlong)lnbClient.get());
    callback->setLnb(env->NewWeakGlobalRef(lnbObj));

    return lnbObj;
}

int JTuner::tune(const FrontendSettings &settings) {
    if (mFeClient == nullptr) {
        ALOGE("frontend is not initialized");
        return (int)Result::INVALID_STATE;
    }
    return (int)mFeClient->tune(settings);
}

int JTuner::stopTune() {
    if (mFeClient == nullptr) {
        ALOGE("frontend is not initialized");
        return (int)Result::INVALID_STATE;
    }
    return (int) mFeClient->stopTune();
}

int JTuner::scan(const FrontendSettings &settings, FrontendScanType scanType) {
    if (mFeClient == nullptr) {
        ALOGE("frontend client is not initialized");
        return (int)Result::INVALID_STATE;
    }
    Result result = mFeClient->scan(settings, scanType);
    return (int)result;
}

int JTuner::stopScan() {
    if (mFeClient == nullptr) {
        ALOGE("frontend client is not initialized");
        return (int)Result::INVALID_STATE;
    }
    Result result = mFeClient->stopScan();
    return (int)result;
}

int JTuner::setLnb(sp<LnbClient> lnbClient) {
    if (mFeClient == nullptr) {
        ALOGE("frontend client is not initialized");
        return (int)Result::INVALID_STATE;
    }
    if (lnbClient == nullptr) {
        ALOGE("lnb is not initialized");
        return (int)Result::INVALID_STATE;
    }
    Result result = mFeClient->setLnb(lnbClient);
    return (int)result;
}

bool JTuner::isLnaSupported() {
    if (sTunerClient == nullptr) {
        return (int)Result::NOT_INITIALIZED;
    }
    return sTunerClient->isLnaSupported();
}

int JTuner::setLna(bool enable) {
    if (sTunerClient == nullptr) {
        return (int)Result::NOT_INITIALIZED;
    }
    Result result = sTunerClient->setLna(enable);
    return (int)result;
}

Result JTuner::openDemux(int handle) {
    if (sTunerClient == nullptr) {
        return Result::NOT_INITIALIZED;
    }

    if (mDemuxClient == nullptr) {
        mDemuxClient = sTunerClient->openDemux(handle);
        if (mDemuxClient == nullptr) {
            ALOGE("Failed to open demux");
            return Result::UNKNOWN_ERROR;
        }
        if (mFeClient != nullptr) {
            return mDemuxClient->setFrontendDataSource(mFeClient);
        } else if (mSharedFeId != (int)Constant::INVALID_FRONTEND_ID) {
            return mDemuxClient->setFrontendDataSourceById(mSharedFeId);
        }
    }

    return Result::SUCCESS;
}

jint JTuner::close() {
    Result res = Result::SUCCESS;

    if (mFeClient != nullptr) {
        res = mFeClient->close();
        if (res != Result::SUCCESS) {
            return (jint)res;
        }
        mFeClient = nullptr;
    }
    if (mDemuxClient != nullptr) {
        res = mDemuxClient->close();
        if (res != Result::SUCCESS) {
            return (jint)res;
        }
        mDemuxClient = nullptr;
    }

    mSharedFeId = (int)Constant::INVALID_FRONTEND_ID;
    return (jint)res;
}

jobject JTuner::getAvSyncHwId(sp<FilterClient> filterClient) {
    if (mDemuxClient == nullptr) {
        return nullptr;
    }

    int avSyncHwId = mDemuxClient->getAvSyncHwId(filterClient);
    if (avSyncHwId >= 0) {
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        jclass integerClazz = env->FindClass("java/lang/Integer");
        jmethodID intInit = env->GetMethodID(integerClazz, "<init>", "(I)V");
        return env->NewObject(integerClazz, intInit, avSyncHwId);
    }
    return nullptr;
}

jobject JTuner::getAvSyncTime(jint id) {
    if (mDemuxClient == nullptr) {
        return nullptr;
    }
    int64_t time = mDemuxClient->getAvSyncTime((int)id);
    if (time >= 0) {
        JNIEnv *env = AndroidRuntime::getJNIEnv();
        jclass longClazz = env->FindClass("java/lang/Long");
        jmethodID longInit = env->GetMethodID(longClazz, "<init>", "(J)V");
        return env->NewObject(longClazz, longInit, time);
    }
    return nullptr;
}

int JTuner::connectCiCam(jint id) {
    if (mDemuxClient == nullptr) {
        return (int)Result::NOT_INITIALIZED;
    }
    return (int)mDemuxClient->connectCiCam((int)id);
}

int JTuner::linkCiCam(int id) {
    if (mFeClient == nullptr) {
        ALOGE("frontend client is not initialized");
        return (int)Constant::INVALID_LTS_ID;
    }
    return mFeClient->linkCiCamToFrontend(id);
}

int JTuner::disconnectCiCam() {
    if (mDemuxClient == nullptr) {
        return (int)Result::NOT_INITIALIZED;
    }
    return (int)mDemuxClient->disconnectCiCam();
}

int JTuner::unlinkCiCam(int id) {
    if (mFeClient == nullptr) {
        ALOGE("frontend client is not initialized");
        return (int)Result::INVALID_STATE;
    }
    return (int)mFeClient->unlinkCiCamToFrontend(id);
}

jobject JTuner::openDescrambler() {
    ALOGV("JTuner::openDescrambler");
    if (sTunerClient == nullptr || mDemuxClient == nullptr) {
        return nullptr;
    }
    sp<DescramblerClient> descramblerClient = sTunerClient->openDescrambler(0 /*unused*/);

    if (descramblerClient == nullptr) {
        ALOGD("Failed to open descrambler");
        return nullptr;
    }

    descramblerClient->setDemuxSource(mDemuxClient);

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject descramblerObj =
            env->NewObject(
                    env->FindClass("android/media/tv/tuner/Descrambler"),
                    gFields.descramblerInitID);

    descramblerClient->incStrong(descramblerObj);
    env->SetLongField(descramblerObj, gFields.descramblerContext, (jlong)descramblerClient.get());

    return descramblerObj;
}

jobject JTuner::openFilter(DemuxFilterType type, int bufferSize) {
    if (mDemuxClient == nullptr) {
        return nullptr;
    }

    sp<FilterClient> filterClient;
    sp<FilterClientCallbackImpl> callback = new FilterClientCallbackImpl();
    filterClient = mDemuxClient->openFilter(type, bufferSize, callback);
    if (filterClient == nullptr) {
        ALOGD("Failed to open filter, type = %d", type.mainType);
        return nullptr;
    }
    int64_t fId;
    Result res = filterClient->getId64Bit(fId);
    if (res != Result::SUCCESS) {
        int32_t id;
        filterClient->getId(id);
        fId = static_cast<int64_t>(id);
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject filterObj = env->NewObject(env->FindClass("android/media/tv/tuner/filter/Filter"),
                                       gFields.filterInitID, fId);

    filterClient->incStrong(filterObj);
    env->SetLongField(filterObj, gFields.filterContext, (jlong)filterClient.get());
    callback->setFilter(env->NewWeakGlobalRef(filterObj), filterClient);

    return filterObj;
}

jobject JTuner::openTimeFilter() {
    if (mDemuxClient == nullptr) {
        return nullptr;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject timeFilterObj =
            env->NewObject(
                    env->FindClass("android/media/tv/tuner/filter/TimeFilter"),
                    gFields.timeFilterInitID);
    sp<TimeFilterClient> timeFilterClient = mDemuxClient->openTimeFilter();
    if (timeFilterClient == nullptr) {
        ALOGD("Failed to open time filter.");
        return nullptr;
    }
    timeFilterClient->incStrong(timeFilterObj);
    env->SetLongField(timeFilterObj, gFields.timeFilterContext, (jlong)timeFilterClient.get());

    return timeFilterObj;
}

jobject JTuner::openDvr(DvrType type, jlong bufferSize) {
    ALOGV("JTuner::openDvr");
    if (mDemuxClient == nullptr) {
        return nullptr;
    }

    sp<DvrClient> dvrClient;
    sp<DvrClientCallbackImpl> callback = new DvrClientCallbackImpl();
    dvrClient = mDemuxClient->openDvr(type, (int) bufferSize, callback);
    if (dvrClient == nullptr) {
        ALOGD("Failed to open Dvr");
        return nullptr;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jobject dvrObj;
    if (type == DvrType::RECORD) {
        dvrObj =
                env->NewObject(
                        env->FindClass("android/media/tv/tuner/dvr/DvrRecorder"),
                        gFields.dvrRecorderInitID);
        dvrClient->incStrong(dvrObj);
        env->SetLongField(dvrObj, gFields.dvrRecorderContext, (jlong)dvrClient.get());
    } else {
        dvrObj =
                env->NewObject(
                        env->FindClass("android/media/tv/tuner/dvr/DvrPlayback"),
                        gFields.dvrPlaybackInitID);
        dvrClient->incStrong(dvrObj);
        env->SetLongField(dvrObj, gFields.dvrPlaybackContext, (jlong)dvrClient.get());
    }

    callback->setDvr(env->NewWeakGlobalRef(dvrObj));

    return dvrObj;
}

jobject JTuner::getDemuxCaps() {
    if (sTunerClient == nullptr) {
        return nullptr;
    }

    shared_ptr<DemuxCapabilities> caps;
    caps = sTunerClient->getDemuxCaps();
    if (caps == nullptr) {
        return nullptr;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass clazz = env->FindClass("android/media/tv/tuner/DemuxCapabilities");
    jmethodID capsInit = env->GetMethodID(clazz, "<init>", "(IIIIIIIIIJI[I[IZ)V");

    jint numDemux = caps->numDemux;
    jint numRecord = caps->numRecord;
    jint numPlayback = caps->numPlayback;
    jint numTsFilter = caps->numTsFilter;
    jint numSectionFilter = caps->numSectionFilter;
    jint numAudioFilter = caps->numAudioFilter;
    jint numVideoFilter = caps->numVideoFilter;
    jint numPesFilter = caps->numPesFilter;
    jint numPcrFilter = caps->numPcrFilter;
    jlong numBytesInSectionFilter = caps->numBytesInSectionFilter;
    jboolean bTimeFilter = caps->bTimeFilter;

    jint filterCaps = caps->filterCaps;
    jintArray filterCapsList = nullptr;
    vector<DemuxInfo> demuxInfoList;
    sTunerClient->getDemuxInfoList(&demuxInfoList);
    if (demuxInfoList.size() > 0) {
        vector<int32_t> demuxFilterTypesList;
        for (int i = 0; i < demuxInfoList.size(); i++) {
            demuxFilterTypesList.push_back(demuxInfoList[i].filterTypes);
        }
        filterCapsList = env->NewIntArray(demuxFilterTypesList.size());
        env->SetIntArrayRegion(filterCapsList, 0, demuxFilterTypesList.size(),
                               reinterpret_cast<jint *>(&demuxFilterTypesList[0]));
    } else {
        filterCapsList = env->NewIntArray(0);
    }
    jintArray linkCaps = env->NewIntArray(caps->linkCaps.size());
    env->SetIntArrayRegion(linkCaps, 0, caps->linkCaps.size(),
                           reinterpret_cast<jint *>(&caps->linkCaps[0]));

    return env->NewObject(clazz, capsInit, numDemux, numRecord, numPlayback, numTsFilter,
            numSectionFilter, numAudioFilter, numVideoFilter, numPesFilter, numPcrFilter,
            numBytesInSectionFilter, filterCaps, filterCapsList, linkCaps, bTimeFilter);
}

jobject JTuner::getDemuxInfo(int handle) {
    if (sTunerClient == nullptr) {
        ALOGE("tuner is not initialized");
        return nullptr;
    }
    shared_ptr<DemuxInfo> demuxInfo = sTunerClient->getDemuxInfo(handle);
    if (demuxInfo == nullptr) {
        return nullptr;
    }

    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jclass clazz = env->FindClass("android/media/tv/tuner/DemuxInfo");
    jmethodID infoInit = env->GetMethodID(clazz, "<init>", "(I)V");

    jint filterTypes = demuxInfo->filterTypes;

    return env->NewObject(clazz, infoInit, filterTypes);
}

jobject JTuner::getFrontendStatus(jintArray types) {
    if (mFeClient == nullptr) {
        return nullptr;
    }
    JNIEnv *env = AndroidRuntime::getJNIEnv();
    jsize size = env->GetArrayLength(types);
    jint intTypes[size];
    env->GetIntArrayRegion(types, 0, size, intTypes);
    std::vector<FrontendStatusType> v;
    for (int i = 0; i < size; i++) {
        v.push_back(static_cast<FrontendStatusType>(intTypes[i]));
    }

    vector<FrontendStatus> status = mFeClient->getStatus(v);

    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/FrontendStatus");
    jmethodID init = env->GetMethodID(clazz, "<init>", "()V");
    jobject statusObj = env->NewObject(clazz, init);

    jclass intClazz = env->FindClass("java/lang/Integer");
    jmethodID initInt = env->GetMethodID(intClazz, "<init>", "(I)V");
    jclass booleanClazz = env->FindClass("java/lang/Boolean");
    jmethodID initBoolean = env->GetMethodID(booleanClazz, "<init>", "(Z)V");
    jclass longClazz = env->FindClass("java/lang/Long");
    jmethodID initLong = env->GetMethodID(longClazz, "<init>", "(J)V");

    for (int i = 0; i < status.size(); i++) {
        const FrontendStatus &s = status[i];
        switch (s.getTag()) {
            case FrontendStatus::Tag::isDemodLocked: {
                jfieldID field = env->GetFieldID(clazz, "mIsDemodLocked", "Ljava/lang/Boolean;");
                ScopedLocalRef newBooleanObj(env,
                        env->NewObject(booleanClazz, initBoolean,
                                       s.get<FrontendStatus::Tag::isDemodLocked>()));
                env->SetObjectField(statusObj, field, newBooleanObj.get());
                break;
            }
            case FrontendStatus::Tag::snr: {
                jfieldID field = env->GetFieldID(clazz, "mSnr", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt, s.get<FrontendStatus::Tag::snr>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::ber: {
                jfieldID field = env->GetFieldID(clazz, "mBer", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt, s.get<FrontendStatus::Tag::ber>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::per: {
                jfieldID field = env->GetFieldID(clazz, "mPer", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt, s.get<FrontendStatus::Tag::per>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::preBer: {
                jfieldID field = env->GetFieldID(clazz, "mPerBer", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt, s.get<FrontendStatus::Tag::preBer>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::signalQuality: {
                jfieldID field = env->GetFieldID(clazz, "mSignalQuality", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt,
                                       s.get<FrontendStatus::Tag::signalQuality>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::signalStrength: {
                jfieldID field = env->GetFieldID(clazz, "mSignalStrength", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt,
                                       s.get<FrontendStatus::Tag::signalStrength>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::symbolRate: {
                jfieldID field = env->GetFieldID(clazz, "mSymbolRate", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt,
                                       s.get<FrontendStatus::Tag::symbolRate>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::innerFec: {
                jfieldID field = env->GetFieldID(clazz, "mInnerFec", "Ljava/lang/Long;");
                ScopedLocalRef longClazz(env, env->FindClass("java/lang/Long"));
                jmethodID initLong = env->GetMethodID(longClazz.get(), "<init>", "(J)V");
                ScopedLocalRef newLongObj(env,
                        env->NewObject(longClazz.get(), initLong,
                                       static_cast<long>(s.get<FrontendStatus::Tag::innerFec>())));
                env->SetObjectField(statusObj, field, newLongObj.get());
                break;
            }
            case FrontendStatus::Tag::modulationStatus: {
                jfieldID field = env->GetFieldID(clazz, "mModulation", "Ljava/lang/Integer;");
                FrontendModulationStatus modulation =
                        s.get<FrontendStatus::Tag::modulationStatus>();
                jint intModulation;
                bool valid = true;
                switch (modulation.getTag()) {
                    case FrontendModulationStatus::Tag::dvbc: {
                        intModulation = static_cast<jint>(
                                modulation.get<FrontendModulationStatus::Tag::dvbc>());
                        break;
                    }
                    case FrontendModulationStatus::Tag::dvbs: {
                        intModulation = static_cast<jint>(
                                modulation.get<FrontendModulationStatus::Tag::dvbs>());
                        break;
                    }
                    case FrontendModulationStatus::Tag::isdbs: {
                        intModulation = static_cast<jint>(
                                modulation.get<FrontendModulationStatus::Tag::isdbs>());
                        break;
                    }
                    case FrontendModulationStatus::Tag::isdbs3: {
                        intModulation = static_cast<jint>(
                                modulation.get<FrontendModulationStatus::Tag::isdbs3>());
                        break;
                    }
                    case FrontendModulationStatus::Tag::isdbt: {
                        intModulation = static_cast<jint>(
                                modulation.get<FrontendModulationStatus::Tag::isdbt>());
                        break;
                    }
                    default: {
                        valid = false;
                        break;
                    }
                }
                if (valid) {
                    ScopedLocalRef newIntegerObj(env,
                            env->NewObject(intClazz, initInt, intModulation));
                    env->SetObjectField(statusObj, field, newIntegerObj.get());
                }
                break;
            }
            case FrontendStatus::Tag::inversion: {
                jfieldID field = env->GetFieldID(clazz, "mInversion", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt,
                                static_cast<jint>(s.get<FrontendStatus::Tag::inversion>())));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::lnbVoltage: {
                jfieldID field = env->GetFieldID(clazz, "mLnbVoltage", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt,
                                static_cast<jint>(s.get<FrontendStatus::Tag::lnbVoltage>())));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::plpId: {
                jfieldID field = env->GetFieldID(clazz, "mPlpId", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt, s.get<FrontendStatus::Tag::plpId>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::isEWBS: {
                jfieldID field = env->GetFieldID(clazz, "mIsEwbs", "Ljava/lang/Boolean;");
                ScopedLocalRef newBooleanObj(env, env->NewObject(booleanClazz, initBoolean,
                                                       s.get<FrontendStatus::Tag::isEWBS>()));
                env->SetObjectField(statusObj, field, newBooleanObj.get());
                break;
            }
            case FrontendStatus::Tag::agc: {
                jfieldID field = env->GetFieldID(clazz, "mAgc", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt, s.get<FrontendStatus::Tag::agc>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::isLnaOn: {
                jfieldID field = env->GetFieldID(clazz, "mIsLnaOn", "Ljava/lang/Boolean;");
                ScopedLocalRef newBooleanObj(env, env->NewObject(booleanClazz, initBoolean,
                                                       s.get<FrontendStatus::Tag::isLnaOn>()));
                env->SetObjectField(statusObj, field, newBooleanObj.get());
                break;
            }
            case FrontendStatus::Tag::isLayerError: {
                jfieldID field = env->GetFieldID(clazz, "mIsLayerErrors", "[Z");
                vector<bool> layerErr = s.get<FrontendStatus::Tag::isLayerError>();

                ScopedLocalRef valObj(env, env->NewBooleanArray(layerErr.size()));

                for (size_t i = 0; i < layerErr.size(); i++) {
                    jboolean x = layerErr[i];
                    env->SetBooleanArrayRegion(valObj.get(), i, 1, &x);
                }
                env->SetObjectField(statusObj, field, valObj.get());
                break;
            }
            case FrontendStatus::Tag::mer: {
                jfieldID field = env->GetFieldID(clazz, "mMer", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt, s.get<FrontendStatus::Tag::mer>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::freqOffset: {
                jfieldID field = env->GetFieldID(clazz, "mFreqOffset", "Ljava/lang/Long;");
                ScopedLocalRef newLongObj(env, env->NewObject(longClazz, initLong,
                                                    s.get<FrontendStatus::Tag::freqOffset>()));
                env->SetObjectField(statusObj, field, newLongObj.get());
                break;
            }
            case FrontendStatus::Tag::hierarchy: {
                jfieldID field = env->GetFieldID(clazz, "mHierarchy", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt,
                                static_cast<jint>(s.get<FrontendStatus::Tag::hierarchy>())));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::isRfLocked: {
                jfieldID field = env->GetFieldID(clazz, "mIsRfLocked", "Ljava/lang/Boolean;");
                ScopedLocalRef newBooleanObj(env, env->NewObject(booleanClazz, initBoolean,
                                                       s.get<FrontendStatus::Tag::isRfLocked>()));
                env->SetObjectField(statusObj, field, newBooleanObj.get());
                break;
            }
            case FrontendStatus::Tag::plpInfo: {
                jfieldID field = env->GetFieldID(clazz, "mPlpInfo",
                        "[Landroid/media/tv/tuner/frontend/FrontendStatus$Atsc3PlpTuningInfo;");
                ScopedLocalRef plpClazz(env, env->FindClass(
                        "android/media/tv/tuner/frontend/FrontendStatus$Atsc3PlpTuningInfo"));
                jmethodID initPlp = env->GetMethodID(plpClazz.get(), "<init>", "(IZI)V");

                vector<FrontendStatusAtsc3PlpInfo> plpInfos =
                        s.get<FrontendStatus::Tag::plpInfo>();
                ScopedLocalRef valObj(env, env->NewObjectArray(plpInfos.size(), plpClazz.get(),
                                                               nullptr));
                for (int i = 0; i < plpInfos.size(); i++) {
                    const FrontendStatusAtsc3PlpInfo &info = plpInfos[i];
                    jint plpId = info.plpId;
                    jboolean isLocked = info.isLocked;
                    jint uec = info.uec;

                    ScopedLocalRef plpObj(env, env->NewObject(plpClazz.get(), initPlp, plpId,
                                                              isLocked, uec));
                    env->SetObjectArrayElement(valObj.get(), i, plpObj.get());
                }

                env->SetObjectField(statusObj, field, valObj.get());
                break;
            }
            case FrontendStatus::Tag::modulations: {
                jfieldID field = env->GetFieldID(clazz, "mModulationsExt", "[I");
                std::vector<FrontendModulation> v = s.get<FrontendStatus::Tag::modulations>();

                ScopedLocalRef valObj(env, env->NewIntArray(v.size()));
                bool valid = false;
                jint m[1];
                for (int i = 0; i < v.size(); i++) {
                    const FrontendModulation &modulation = v[i];
                    switch (modulation.getTag()) {
                        case FrontendModulation::Tag::dvbc: {
                            m[0] = static_cast<jint>(
                                    modulation.get<FrontendModulation::Tag::dvbc>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::Tag::dvbs: {
                            m[0] = static_cast<jint>(
                                    modulation.get<FrontendModulation::Tag::dvbs>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, m);
                            valid = true;
                           break;
                        }
                        case FrontendModulation::Tag::dvbt: {
                            m[0] = static_cast<jint>(
                                    modulation.get<FrontendModulation::Tag::dvbt>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::Tag::isdbs: {
                            m[0] = static_cast<jint>(
                                    modulation.get<FrontendModulation::Tag::isdbs>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::Tag::isdbs3: {
                            m[0] = static_cast<jint>(
                                    modulation.get<FrontendModulation::Tag::isdbs3>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::Tag::isdbt: {
                            m[0] = static_cast<jint>(
                                    modulation.get<FrontendModulation::Tag::isdbt>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::Tag::atsc: {
                            m[0] = static_cast<jint>(
                                    modulation.get<FrontendModulation::Tag::atsc>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::Tag::atsc3: {
                            m[0] = static_cast<jint>(
                                    modulation.get<FrontendModulation::Tag::atsc3>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, m);
                            valid = true;
                            break;
                        }
                        case FrontendModulation::Tag::dtmb: {
                            m[0] = static_cast<jint>(
                                    modulation.get<FrontendModulation::Tag::dtmb>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, m);
                            valid = true;
                            break;
                        }
                        default:
                            break;
                    }
                }
                if (valid) {
                    env->SetObjectField(statusObj, field, valObj.get());
                }
                break;
            }
            case FrontendStatus::Tag::bers: {
                jfieldID field = env->GetFieldID(clazz, "mBers", "[I");
                std::vector<int32_t> v = s.get<FrontendStatus::Tag::bers>();

                ScopedLocalRef valObj(env, env->NewIntArray(v.size()));
                env->SetIntArrayRegion(valObj.get(), 0, v.size(), reinterpret_cast<jint *>(&v[0]));

                env->SetObjectField(statusObj, field, valObj.get());
                break;
            }
            case FrontendStatus::Tag::codeRates: {
                jfieldID field = env->GetFieldID(clazz, "mCodeRates", "[I");
                std::vector<FrontendInnerFec> v = s.get<FrontendStatus::Tag::codeRates>();

                ScopedLocalRef valObj(env, env->NewIntArray(v.size()));
                env->SetIntArrayRegion(valObj.get(), 0, v.size(), reinterpret_cast<jint *>(&v[0]));

                env->SetObjectField(statusObj, field, valObj.get());
                break;
            }
            case FrontendStatus::Tag::bandwidth: {
                jfieldID field = env->GetFieldID(clazz, "mBandwidth", "Ljava/lang/Integer;");
                const FrontendBandwidth &bandwidth = s.get<FrontendStatus::Tag::bandwidth>();
                jint intBandwidth;
                bool valid = true;
                switch (bandwidth.getTag()) {
                    case FrontendBandwidth::Tag::atsc3: {
                        intBandwidth =
                                static_cast<jint>(bandwidth.get<FrontendBandwidth::Tag::atsc3>());
                        break;
                    }
                    case FrontendBandwidth::Tag::dvbt: {
                        intBandwidth =
                                static_cast<jint>(bandwidth.get<FrontendBandwidth::Tag::dvbt>());
                        break;
                    }
                    case FrontendBandwidth::Tag::dvbc: {
                        intBandwidth =
                                static_cast<jint>(bandwidth.get<FrontendBandwidth::Tag::dvbc>());
                        break;
                    }
                    case FrontendBandwidth::Tag::isdbt: {
                        intBandwidth =
                                static_cast<jint>(bandwidth.get<FrontendBandwidth::Tag::isdbt>());
                        break;
                    }
                    case FrontendBandwidth::Tag::dtmb: {
                        intBandwidth =
                                static_cast<jint>(bandwidth.get<FrontendBandwidth::Tag::dtmb>());
                        break;
                    }
                    default:
                        valid = false;
                        break;
                }
                if (valid) {
                    ScopedLocalRef newIntegerObj(env, env->NewObject(intClazz, initInt,
                                                                     intBandwidth));
                    env->SetObjectField(statusObj, field, newIntegerObj.get());
                }
                break;
            }
            case FrontendStatus::Tag::interval: {
                jfieldID field = env->GetFieldID(clazz, "mGuardInterval", "Ljava/lang/Integer;");
                const FrontendGuardInterval &interval = s.get<FrontendStatus::Tag::interval>();
                jint intInterval;
                bool valid = true;
                switch (interval.getTag()) {
                    case FrontendGuardInterval::Tag::dvbt: {
                        intInterval = static_cast<jint>(
                                interval.get<FrontendGuardInterval::Tag::dvbt>());
                        break;
                    }
                    case FrontendGuardInterval::Tag::isdbt: {
                        intInterval = static_cast<jint>(
                                interval.get<FrontendGuardInterval::Tag::isdbt>());
                        break;
                    }
                    case FrontendGuardInterval::Tag::dtmb: {
                        intInterval = static_cast<jint>(
                                interval.get<FrontendGuardInterval::Tag::dtmb>());
                        break;
                    }
                    default:
                        valid = false;
                        break;
                }
                if (valid) {
                    ScopedLocalRef newIntegerObj(env, env->NewObject(intClazz, initInt,
                                                                     intInterval));
                    env->SetObjectField(statusObj, field, newIntegerObj.get());
                }
                break;
            }
            case FrontendStatus::Tag::transmissionMode: {
                jfieldID field = env->GetFieldID(clazz, "mTransmissionMode",
                                                 "Ljava/lang/Integer;");
                const FrontendTransmissionMode &transmissionMode =
                        s.get<FrontendStatus::Tag::transmissionMode>();
                jint intTransmissionMode;
                bool valid = true;
                switch (transmissionMode.getTag()) {
                    case FrontendTransmissionMode::Tag::dvbt: {
                        intTransmissionMode = static_cast<jint>(
                                transmissionMode.get<FrontendTransmissionMode::Tag::dvbt>());
                        break;
                    }
                    case FrontendTransmissionMode::Tag::isdbt: {
                        intTransmissionMode = static_cast<jint>(
                                transmissionMode.get<FrontendTransmissionMode::Tag::isdbt>());
                        break;
                    }
                    case FrontendTransmissionMode::Tag::dtmb: {
                        intTransmissionMode = static_cast<jint>(
                                transmissionMode.get<FrontendTransmissionMode::Tag::dtmb>());
                        break;
                    }
                    default:
                        valid = false;
                        break;
                }
                if (valid) {
                    ScopedLocalRef newIntegerObj(env, env->NewObject(intClazz, initInt,
                                                                     intTransmissionMode));
                    env->SetObjectField(statusObj, field, newIntegerObj.get());
                }
                break;
            }
            case FrontendStatus::Tag::uec: {
                jfieldID field = env->GetFieldID(clazz, "mUec", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt, s.get<FrontendStatus::Tag::uec>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::systemId: {
                jfieldID field = env->GetFieldID(clazz, "mSystemId", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt, s.get<FrontendStatus::Tag::systemId>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::interleaving: {
                jfieldID field = env->GetFieldID(clazz, "mInterleaving", "[I");
                std::vector<FrontendInterleaveMode> v = s.get<FrontendStatus::Tag::interleaving>();
                ScopedLocalRef valObj(env, env->NewIntArray(v.size()));
                bool valid = false;
                jint in[1];
                for (int i = 0; i < v.size(); i++) {
                    const FrontendInterleaveMode &interleaving = v[i];
                    switch (interleaving.getTag()) {
                        case FrontendInterleaveMode::Tag::atsc3: {
                            in[0] = static_cast<jint>(
                                    interleaving.get<FrontendInterleaveMode::Tag::atsc3>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, in);
                            valid = true;
                            break;
                        }
                        case FrontendInterleaveMode::Tag::dvbc: {
                            in[0] = static_cast<jint>(
                                    interleaving.get<FrontendInterleaveMode::Tag::dvbc>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, in);
                            valid = true;
                           break;
                        }
                        case FrontendInterleaveMode::Tag::dtmb: {
                            in[0] = static_cast<jint>(
                                    interleaving.get<FrontendInterleaveMode::Tag::dtmb>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, in);
                            valid = true;
                           break;
                        }
                        case FrontendInterleaveMode::Tag::isdbt: {
                            in[0] = static_cast<jint>(
                                    interleaving.get<FrontendInterleaveMode::Tag::isdbt>());
                            env->SetIntArrayRegion(valObj.get(), i, 1, in);
                            valid = true;
                            break;
                        }
                        default:
                            break;
                    }
                }
                if (valid) {
                    env->SetObjectField(statusObj, field, valObj.get());
                }
                break;
            }
            case FrontendStatus::Tag::isdbtSegment: {
                jfieldID field = env->GetFieldID(clazz, "mIsdbtSegment", "[I");
                std::vector<int32_t> v = s.get<FrontendStatus::Tag::isdbtSegment>();

                ScopedLocalRef valObj(env, env->NewIntArray(v.size()));
                env->SetIntArrayRegion(valObj.get(), 0, v.size(), reinterpret_cast<jint*>(&v[0]));

                env->SetObjectField(statusObj, field, valObj.get());
                break;
            }
            case FrontendStatus::Tag::tsDataRate: {
                jfieldID field = env->GetFieldID(clazz, "mTsDataRate", "[I");
                std::vector<int32_t> v = s.get<FrontendStatus::Tag::tsDataRate>();

                ScopedLocalRef valObj(env, env->NewIntArray(v.size()));
                env->SetIntArrayRegion(valObj.get(), 0, v.size(), reinterpret_cast<jint *>(&v[0]));

                env->SetObjectField(statusObj, field, valObj.get());
                break;
            }
            case FrontendStatus::Tag::rollOff: {
                jfieldID field = env->GetFieldID(clazz, "mRollOff", "Ljava/lang/Integer;");
                const FrontendRollOff &rollOff = s.get<FrontendStatus::Tag::rollOff>();
                jint intRollOff;
                bool valid = true;
                switch (rollOff.getTag()) {
                    case FrontendRollOff::Tag::dvbs: {
                        intRollOff = static_cast<jint>(rollOff.get<FrontendRollOff::Tag::dvbs>());
                        break;
                    }
                    case FrontendRollOff::Tag::isdbs: {
                        intRollOff = static_cast<jint>(rollOff.get<FrontendRollOff::Tag::isdbs>());
                        break;
                    }
                    case FrontendRollOff::Tag::isdbs3: {
                        intRollOff = static_cast<jint>(
                                rollOff.get<FrontendRollOff::Tag::isdbs3>());
                        break;
                    }
                    default:
                        valid = false;
                        break;
                }
                if (valid) {
                    ScopedLocalRef newIntegerObj(env, env->NewObject(intClazz, initInt,
                                                                     intRollOff));
                    env->SetObjectField(statusObj, field, newIntegerObj.get());
                }
                break;
            }
            case FrontendStatus::Tag::isMiso: {
                jfieldID field = env->GetFieldID(clazz, "mIsMisoEnabled", "Ljava/lang/Boolean;");
                ScopedLocalRef newBooleanObj(env, env->NewObject(booleanClazz, initBoolean,
                                                       s.get<FrontendStatus::Tag::isMiso>()));
                env->SetObjectField(statusObj, field, newBooleanObj.get());
                break;
            }
            case FrontendStatus::Tag::isLinear: {
                jfieldID field = env->GetFieldID(clazz, "mIsLinear", "Ljava/lang/Boolean;");
                ScopedLocalRef newBooleanObj(env, env->NewObject(booleanClazz, initBoolean,
                                                       s.get<FrontendStatus::Tag::isLinear>()));
                env->SetObjectField(statusObj, field, newBooleanObj.get());
                break;
            }
            case FrontendStatus::Tag::isShortFrames: {
                jfieldID field = env->GetFieldID(clazz, "mIsShortFrames", "Ljava/lang/Boolean;");
                ScopedLocalRef newBooleanObj(env,
                        env->NewObject(booleanClazz, initBoolean,
                                s.get<FrontendStatus::Tag::isShortFrames>()));
                env->SetObjectField(statusObj, field, newBooleanObj.get());
                break;
            }
            case FrontendStatus::Tag::isdbtMode: {
                jfieldID field = env->GetFieldID(clazz, "mIsdbtMode", "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt,
                                s.get<FrontendStatus::Tag::isdbtMode>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::partialReceptionFlag: {
                jfieldID field =
                        env->GetFieldID(clazz, "mIsdbtPartialReceptionFlag",
                                        "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt,
                                       s.get<FrontendStatus::Tag::partialReceptionFlag>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::streamIdList: {
                jfieldID field = env->GetFieldID(clazz, "mStreamIds", "[I");
                std::vector<int32_t> ids = s.get<FrontendStatus::Tag::streamIdList>();

                ScopedLocalRef valObj(env, env->NewIntArray(v.size()));
                env->SetIntArrayRegion(valObj.get(), 0, v.size(),
                                       reinterpret_cast<jint *>(&ids[0]));

                env->SetObjectField(statusObj, field, valObj.get());
                break;
            }
            case FrontendStatus::Tag::dvbtCellIds: {
                jfieldID field = env->GetFieldID(clazz, "mDvbtCellIds", "[I");
                std::vector<int32_t> ids = s.get<FrontendStatus::Tag::dvbtCellIds>();

                ScopedLocalRef valObj(env, env->NewIntArray(v.size()));
                env->SetIntArrayRegion(valObj.get(), 0, v.size(),
                                       reinterpret_cast<jint *>(&ids[0]));

                env->SetObjectField(statusObj, field, valObj.get());
                break;
            }
            case FrontendStatus::Tag::allPlpInfo: {
                jfieldID field = env->GetFieldID(clazz, "mAllPlpInfo",
                        "[Landroid/media/tv/tuner/frontend/Atsc3PlpInfo;");
                ScopedLocalRef plpClazz(env,
                        env->FindClass("android/media/tv/tuner/frontend/Atsc3PlpInfo"));
                jmethodID initPlp = env->GetMethodID(plpClazz.get(), "<init>", "(IZ)V");

                vector<FrontendScanAtsc3PlpInfo> plpInfos =
                        s.get<FrontendStatus::Tag::allPlpInfo>();
                ScopedLocalRef valObj(env, env->NewObjectArray(plpInfos.size(), plpClazz.get(),
                                                               nullptr));
                for (int i = 0; i < plpInfos.size(); i++) {
                    ScopedLocalRef plpObj(env, env->NewObject(plpClazz.get(), initPlp,
                                                              plpInfos[i].plpId,
                                                              plpInfos[i].bLlsFlag));
                    env->SetObjectArrayElement(valObj.get(), i, plpObj.get());
                }

                env->SetObjectField(statusObj, field, valObj.get());
                break;
            }
            case FrontendStatus::Tag::iptvContentUrl: {
                jfieldID field = env->GetFieldID(clazz, "mIptvContentUrl", "Ljava/lang/String;");
                std::string iptvContentUrl = s.get<FrontendStatus::Tag::iptvContentUrl>();
                ScopedLocalRef iptvContentUrlUtf8(env, env->NewStringUTF(iptvContentUrl.c_str()));
                env->SetObjectField(statusObj, field, iptvContentUrlUtf8.get());
                break;
            }
            case FrontendStatus::Tag::iptvPacketsLost: {
                jfieldID field = env->GetFieldID(clazz, "mIptvPacketsLost", "Ljava/lang/Long;");
                ScopedLocalRef newLongObj(env,
                        env->NewObject(longClazz, initLong,
                                       s.get<FrontendStatus::Tag::iptvPacketsLost>()));
                env->SetObjectField(statusObj, field, newLongObj.get());
                break;
            }
            case FrontendStatus::Tag::iptvPacketsReceived: {
                jfieldID field = env->GetFieldID(clazz, "mIptvPacketsReceived",
                                                 "Ljava/lang/Long;");
                ScopedLocalRef newLongObj(env,
                        env->NewObject(longClazz, initLong,
                                       s.get<FrontendStatus::Tag::iptvPacketsReceived>()));
                env->SetObjectField(statusObj, field, newLongObj.get());
                break;
            }
            case FrontendStatus::Tag::iptvWorstJitterMs: {
                jfieldID field = env->GetFieldID(clazz, "mIptvWorstJitterMs",
                                                 "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt,
                                       s.get<FrontendStatus::Tag::iptvWorstJitterMs>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
            case FrontendStatus::Tag::iptvAverageJitterMs: {
                jfieldID field = env->GetFieldID(clazz, "mIptvAverageJitterMs",
                                                 "Ljava/lang/Integer;");
                ScopedLocalRef newIntegerObj(env,
                        env->NewObject(intClazz, initInt,
                                       s.get<FrontendStatus::Tag::iptvAverageJitterMs>()));
                env->SetObjectField(statusObj, field, newIntegerObj.get());
                break;
            }
        }
    }
    return statusObj;
}

jint JTuner::closeFrontend() {
    Result r = Result::SUCCESS;

    if (mFeClient != nullptr) {
        r = mFeClient->close();
    }
    if (r == Result::SUCCESS) {
        mFeClient = nullptr;
    }
    return (jint)r;
}

jint JTuner::closeDemux() {
    Result r = Result::SUCCESS;

    if (mDemuxClient != nullptr) {
        r = mDemuxClient->close();
    }
    if (r == Result::SUCCESS) {
        mDemuxClient = nullptr;
    }
    return (jint)r;
}
}  // namespace android

////////////////////////////////////////////////////////////////////////////////

using namespace android;

static sp<JTuner> setTuner(JNIEnv *env, jobject thiz, const sp<JTuner> &tuner) {
    sp<JTuner> old = (JTuner *)env->GetLongField(thiz, gFields.tunerContext);

    if (tuner != nullptr) {
        tuner->incStrong(thiz);
    }
    if (old != nullptr) {
        old->decStrong(thiz);
    }

    if (tuner != nullptr) {
        env->SetLongField(thiz, gFields.tunerContext, (jlong)tuner.get());
    }

    return old;
}

static sp<JTuner> getTuner(JNIEnv *env, jobject thiz) {
    return (JTuner *)env->GetLongField(thiz, gFields.tunerContext);
}

static sp<DescramblerClient> getDescramblerClient(JNIEnv *env, jobject descrambler) {
    return (DescramblerClient *)env->GetLongField(descrambler, gFields.descramblerContext);
}

static DemuxPid getDemuxPid(int pidType, int pid) {
    DemuxPid demuxPid;
    if (pidType == 1) {
        demuxPid.set<DemuxPid::tPid>(pid);
    } else if (pidType == 2) {
        demuxPid.set<DemuxPid::mmtpPid>(pid);
    }
    return demuxPid;
}

static int64_t getFrontendSettingsFreq(JNIEnv *env, const jobject &settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/FrontendSettings");
    jfieldID freqField = env->GetFieldID(clazz, "mFrequency", "J");
    return env->GetLongField(settings, freqField);
}

static int64_t getFrontendSettingsEndFreq(JNIEnv *env, const jobject &settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/FrontendSettings");
    jfieldID endFreqField = env->GetFieldID(clazz, "mEndFrequency", "J");
    return env->GetLongField(settings, endFreqField);
}

static FrontendSpectralInversion getFrontendSettingsSpectralInversion(
        JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/FrontendSettings");
    jfieldID inversionField = env->GetFieldID(clazz, "mSpectralInversion", "I");
    FrontendSpectralInversion inversion =
            static_cast<FrontendSpectralInversion>(env->GetIntField(settings, inversionField));
    return inversion;
}

static FrontendSettings getAnalogFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    int64_t freq = getFrontendSettingsFreq(env, settings);
    int64_t endFreq = getFrontendSettingsEndFreq(env, settings);
    FrontendSpectralInversion inversion = getFrontendSettingsSpectralInversion(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/AnalogFrontendSettings");
    FrontendAnalogType analogType =
            static_cast<FrontendAnalogType>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mSignalType", "I")));
    FrontendAnalogSifStandard sifStandard =
            static_cast<FrontendAnalogSifStandard>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mSifStandard", "I")));
    FrontendAnalogAftFlag aftFlag = static_cast<FrontendAnalogAftFlag>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mAftFlag", "I")));
    FrontendAnalogSettings frontendAnalogSettings{
            .frequency = freq,
            .endFrequency = endFreq,
            .type = analogType,
            .sifStandard = sifStandard,
            .aftFlag = aftFlag,
            .inversion = inversion,
    };
    frontendSettings.set<FrontendSettings::Tag::analog>(frontendAnalogSettings);
    return frontendSettings;
}

static vector<FrontendAtsc3PlpSettings> getAtsc3PlpSettings(JNIEnv *env, const jobject &settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/Atsc3FrontendSettings");
    jobjectArray plpSettings =
            reinterpret_cast<jobjectArray>(
                    env->GetObjectField(settings,
                            env->GetFieldID(
                                    clazz,
                                    "mPlpSettings",
                                    "[Landroid/media/tv/tuner/frontend/Atsc3PlpSettings;")));
    int len = env->GetArrayLength(plpSettings);

    jclass plpClazz = env->FindClass("android/media/tv/tuner/frontend/Atsc3PlpSettings");
    vector<FrontendAtsc3PlpSettings> plps = vector<FrontendAtsc3PlpSettings>(len);
    // parse PLP settings
    for (int i = 0; i < len; i++) {
        ScopedLocalRef plp(env, env->GetObjectArrayElement(plpSettings, i));
        int32_t plpId = env->GetIntField(plp.get(), env->GetFieldID(plpClazz, "mPlpId", "I"));
        FrontendAtsc3Modulation modulation =
                static_cast<FrontendAtsc3Modulation>(
                        env->GetIntField(plp.get(), env->GetFieldID(plpClazz, "mModulation",
                                                                    "I")));
        FrontendAtsc3TimeInterleaveMode interleaveMode =
                static_cast<FrontendAtsc3TimeInterleaveMode>(
                        env->GetIntField(
                                plp.get(), env->GetFieldID(plpClazz, "mInterleaveMode", "I")));
        FrontendAtsc3CodeRate codeRate =
                static_cast<FrontendAtsc3CodeRate>(
                        env->GetIntField(plp.get(), env->GetFieldID(plpClazz, "mCodeRate", "I")));
        FrontendAtsc3Fec fec =
                static_cast<FrontendAtsc3Fec>(
                        env->GetIntField(plp.get(), env->GetFieldID(plpClazz, "mFec", "I")));
        FrontendAtsc3PlpSettings frontendAtsc3PlpSettings {
                .plpId = plpId,
                .modulation = modulation,
                .interleaveMode = interleaveMode,
                .codeRate = codeRate,
                .fec = fec,
        };
        plps[i] = frontendAtsc3PlpSettings;
    }
    return plps;
}

static FrontendSettings getAtsc3FrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    int64_t freq = getFrontendSettingsFreq(env, settings);
    int64_t endFreq = getFrontendSettingsEndFreq(env, settings);
    FrontendSpectralInversion inversion = getFrontendSettingsSpectralInversion(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/Atsc3FrontendSettings");
    FrontendAtsc3Bandwidth bandwidth =
            static_cast<FrontendAtsc3Bandwidth>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mBandwidth", "I")));
    FrontendAtsc3DemodOutputFormat demod =
            static_cast<FrontendAtsc3DemodOutputFormat>(
                    env->GetIntField(
                            settings, env->GetFieldID(clazz, "mDemodOutputFormat", "I")));
    vector<FrontendAtsc3PlpSettings> plps = getAtsc3PlpSettings(env, settings);
    FrontendAtsc3Settings frontendAtsc3Settings{
            .frequency = freq,
            .endFrequency = endFreq,
            .bandwidth = bandwidth,
            .demodOutputFormat = demod,
            .plpSettings = plps,
            .inversion = inversion,
    };
    frontendSettings.set<FrontendSettings::Tag::atsc3>(frontendAtsc3Settings);
    return frontendSettings;
}

static FrontendSettings getAtscFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    int64_t freq = getFrontendSettingsFreq(env, settings);
    int64_t endFreq = getFrontendSettingsEndFreq(env, settings);
    FrontendSpectralInversion inversion = getFrontendSettingsSpectralInversion(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/AtscFrontendSettings");
    FrontendAtscModulation modulation =
            static_cast<FrontendAtscModulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    FrontendAtscSettings frontendAtscSettings{
            .frequency = freq,
            .endFrequency = endFreq,
            .modulation = modulation,
            .inversion = inversion,
    };
    frontendSettings.set<FrontendSettings::Tag::atsc>(frontendAtscSettings);
    return frontendSettings;
}

static FrontendSettings getDvbcFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    int64_t freq = getFrontendSettingsFreq(env, settings);
    int64_t endFreq = getFrontendSettingsEndFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbcFrontendSettings");
    FrontendDvbcModulation modulation =
            static_cast<FrontendDvbcModulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    FrontendInnerFec innerFec =
            static_cast<FrontendInnerFec>(
                    env->GetLongField(settings, env->GetFieldID(clazz, "mInnerFec", "J")));
    int32_t symbolRate = env->GetIntField(settings, env->GetFieldID(clazz, "mSymbolRate", "I"));
    FrontendDvbcOuterFec outerFec =
            static_cast<FrontendDvbcOuterFec>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mOuterFec", "I")));
    FrontendDvbcAnnex annex =
            static_cast<FrontendDvbcAnnex>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mAnnex", "I")));
    FrontendSpectralInversion spectralInversion = static_cast<FrontendSpectralInversion>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mSpectralInversion", "I")));
    FrontendCableTimeInterleaveMode interleaveMode = static_cast<FrontendCableTimeInterleaveMode>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mInterleaveMode", "I")));
    FrontendDvbcBandwidth bandwidth = static_cast<FrontendDvbcBandwidth>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mBandwidth", "I")));
    FrontendDvbcSettings frontendDvbcSettings{
            .frequency = freq,
            .endFrequency = endFreq,
            .modulation = modulation,
            .fec = innerFec,
            .symbolRate = symbolRate,
            .outerFec = outerFec,
            .annex = annex,
            .inversion = spectralInversion,
            .interleaveMode = interleaveMode,
            .bandwidth = bandwidth,
    };
    frontendSettings.set<FrontendSettings::Tag::dvbc>(frontendDvbcSettings);
    return frontendSettings;
}

static FrontendDvbsCodeRate getDvbsCodeRate(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbsFrontendSettings");
    jobject jcodeRate =
            env->GetObjectField(settings,
                    env->GetFieldID(
                            clazz,
                            "mCodeRate",
                            "Landroid/media/tv/tuner/frontend/DvbsCodeRate;"));

    jclass codeRateClazz = env->FindClass("android/media/tv/tuner/frontend/DvbsCodeRate");
    FrontendInnerFec innerFec =
            static_cast<FrontendInnerFec>(
                    env->GetLongField(
                            jcodeRate, env->GetFieldID(codeRateClazz, "mInnerFec", "J")));
    bool isLinear =
            env->GetBooleanField(jcodeRate, env->GetFieldID(codeRateClazz, "mIsLinear", "Z"));
    bool isShortFrames =
            env->GetBooleanField(jcodeRate, env->GetFieldID(codeRateClazz, "mIsShortFrames", "Z"));
    int32_t bitsPer1000Symbol =
            env->GetIntField(jcodeRate, env->GetFieldID(codeRateClazz, "mBitsPer1000Symbol", "I"));
    FrontendDvbsCodeRate coderate {
            .fec = innerFec,
            .isLinear = isLinear,
            .isShortFrames = isShortFrames,
            .bitsPer1000Symbol = bitsPer1000Symbol,
    };
    return coderate;
}

static FrontendSettings getDvbsFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    int64_t freq = getFrontendSettingsFreq(env, settings);
    int64_t endFreq = getFrontendSettingsEndFreq(env, settings);
    FrontendSpectralInversion inversion = getFrontendSettingsSpectralInversion(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbsFrontendSettings");
    FrontendDvbsModulation modulation =
            static_cast<FrontendDvbsModulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    int32_t symbolRate = env->GetIntField(settings, env->GetFieldID(clazz, "mSymbolRate", "I"));
    FrontendDvbsRolloff rolloff =
            static_cast<FrontendDvbsRolloff>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mRolloff", "I")));
    FrontendDvbsPilot pilot =
            static_cast<FrontendDvbsPilot>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mPilot", "I")));
    int32_t inputStreamId =
            env->GetIntField(settings, env->GetFieldID(clazz, "mInputStreamId", "I"));
    FrontendDvbsStandard standard =
            static_cast<FrontendDvbsStandard>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mStandard", "I")));
    FrontendDvbsVcmMode vcmMode =
            static_cast<FrontendDvbsVcmMode>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mVcmMode", "I")));
    FrontendDvbsScanType scanType = static_cast<FrontendDvbsScanType>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mScanType", "I")));
    bool isDiseqcRxMessage =
            env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsDiseqcRxMessage", "Z"));

    FrontendDvbsSettings frontendDvbsSettings{
            .frequency = freq,
            .endFrequency = endFreq,
            .modulation = modulation,
            .symbolRate = symbolRate,
            .rolloff = rolloff,
            .pilot = pilot,
            .inputStreamId = inputStreamId,
            .standard = standard,
            .vcmMode = vcmMode,
            .scanType = scanType,
            .isDiseqcRxMessage = isDiseqcRxMessage,
            .inversion = inversion,
    };

    jobject jcodeRate = env->GetObjectField(settings, env->GetFieldID(clazz, "mCodeRate",
            "Landroid/media/tv/tuner/frontend/DvbsCodeRate;"));
    if (jcodeRate != nullptr) {
        frontendDvbsSettings.coderate = getDvbsCodeRate(env, settings);
    }

    frontendSettings.set<FrontendSettings::Tag::dvbs>(frontendDvbsSettings);
    return frontendSettings;
}

static FrontendSettings getDvbtFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    int64_t freq = getFrontendSettingsFreq(env, settings);
    int64_t endFreq = getFrontendSettingsEndFreq(env, settings);
    FrontendSpectralInversion inversion = getFrontendSettingsSpectralInversion(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DvbtFrontendSettings");
    FrontendDvbtTransmissionMode transmissionMode =
            static_cast<FrontendDvbtTransmissionMode>(
                    env->GetIntField(
                            settings, env->GetFieldID(clazz, "mTransmissionMode", "I")));
    FrontendDvbtBandwidth bandwidth =
            static_cast<FrontendDvbtBandwidth>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mBandwidth", "I")));
    FrontendDvbtConstellation constellation =
            static_cast<FrontendDvbtConstellation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mConstellation", "I")));
    FrontendDvbtHierarchy hierarchy =
            static_cast<FrontendDvbtHierarchy>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mHierarchy", "I")));
    FrontendDvbtCoderate hpCoderate =
            static_cast<FrontendDvbtCoderate>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mHpCodeRate", "I")));
    FrontendDvbtCoderate lpCoderate =
            static_cast<FrontendDvbtCoderate>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mLpCodeRate", "I")));
    FrontendDvbtGuardInterval guardInterval =
            static_cast<FrontendDvbtGuardInterval>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mGuardInterval", "I")));
    bool isHighPriority =
            env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsHighPriority", "Z"));
    FrontendDvbtStandard standard =
            static_cast<FrontendDvbtStandard>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mStandard", "I")));
    bool isMiso = env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsMiso", "Z"));
    FrontendDvbtPlpMode plpMode =
            static_cast<FrontendDvbtPlpMode>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mPlpMode", "I")));
    int32_t plpId = env->GetIntField(settings, env->GetFieldID(clazz, "mPlpId", "I"));
    int32_t plpGroupId = env->GetIntField(settings, env->GetFieldID(clazz, "mPlpGroupId", "I"));

    FrontendDvbtSettings frontendDvbtSettings{
            .frequency = freq,
            .endFrequency = endFreq,
            .transmissionMode = transmissionMode,
            .bandwidth = bandwidth,
            .constellation = constellation,
            .hierarchy = hierarchy,
            .hpCoderate = hpCoderate,
            .lpCoderate = lpCoderate,
            .guardInterval = guardInterval,
            .isHighPriority = isHighPriority,
            .standard = standard,
            .isMiso = isMiso,
            .plpMode = plpMode,
            .plpId = plpId,
            .plpGroupId = plpGroupId,
            .inversion = inversion,
    };
    frontendSettings.set<FrontendSettings::Tag::dvbt>(frontendDvbtSettings);
    return frontendSettings;
}

static FrontendSettings getIsdbsFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    int64_t freq = getFrontendSettingsFreq(env, settings);
    int64_t endFreq = getFrontendSettingsEndFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/IsdbsFrontendSettings");
    int32_t streamId = env->GetIntField(settings, env->GetFieldID(clazz, "mStreamId", "I"));
    FrontendIsdbsStreamIdType streamIdType =
            static_cast<FrontendIsdbsStreamIdType>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mStreamIdType", "I")));
    FrontendIsdbsModulation modulation =
            static_cast<FrontendIsdbsModulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    FrontendIsdbsCoderate coderate =
            static_cast<FrontendIsdbsCoderate>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mCodeRate", "I")));
    int32_t symbolRate = env->GetIntField(settings, env->GetFieldID(clazz, "mSymbolRate", "I"));
    FrontendIsdbsRolloff rolloff =
            static_cast<FrontendIsdbsRolloff>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mRolloff", "I")));

    FrontendIsdbsSettings frontendIsdbsSettings{
            .frequency = freq,
            .endFrequency = endFreq,
            .streamId = streamId,
            .streamIdType = streamIdType,
            .modulation = modulation,
            .coderate = coderate,
            .symbolRate = symbolRate,
            .rolloff = rolloff,
    };
    frontendSettings.set<FrontendSettings::Tag::isdbs>(frontendIsdbsSettings);
    return frontendSettings;
}

static FrontendSettings getIsdbs3FrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    int64_t freq = getFrontendSettingsFreq(env, settings);
    int64_t endFreq = getFrontendSettingsEndFreq(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/Isdbs3FrontendSettings");
    int32_t streamId = env->GetIntField(settings, env->GetFieldID(clazz, "mStreamId", "I"));
    FrontendIsdbsStreamIdType streamIdType =
            static_cast<FrontendIsdbsStreamIdType>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mStreamIdType", "I")));
    FrontendIsdbs3Modulation modulation =
            static_cast<FrontendIsdbs3Modulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    FrontendIsdbs3Coderate coderate =
            static_cast<FrontendIsdbs3Coderate>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mCodeRate", "I")));
    int32_t symbolRate = env->GetIntField(settings, env->GetFieldID(clazz, "mSymbolRate", "I"));
    FrontendIsdbs3Rolloff rolloff =
            static_cast<FrontendIsdbs3Rolloff>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mRolloff", "I")));

    FrontendIsdbs3Settings frontendIsdbs3Settings{
            .frequency = freq,
            .endFrequency = endFreq,
            .streamId = streamId,
            .streamIdType = streamIdType,
            .modulation = modulation,
            .coderate = coderate,
            .symbolRate = symbolRate,
            .rolloff = rolloff,
    };
    frontendSettings.set<FrontendSettings::Tag::isdbs3>(frontendIsdbs3Settings);
    return frontendSettings;
}

static FrontendSettings getIsdbtFrontendSettings(JNIEnv *env, const jobject& settings) {
    FrontendSettings frontendSettings;
    int64_t freq = getFrontendSettingsFreq(env, settings);
    int64_t endFreq = getFrontendSettingsEndFreq(env, settings);
    FrontendSpectralInversion inversion = getFrontendSettingsSpectralInversion(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/IsdbtFrontendSettings");
    FrontendIsdbtBandwidth bandwidth =
            static_cast<FrontendIsdbtBandwidth>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mBandwidth", "I")));
    FrontendIsdbtMode mode = static_cast<FrontendIsdbtMode>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mMode", "I")));
    FrontendIsdbtGuardInterval guardInterval =
            static_cast<FrontendIsdbtGuardInterval>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mGuardInterval", "I")));
    int32_t serviceAreaId =
            env->GetIntField(settings, env->GetFieldID(clazz, "mServiceAreaId", "I"));
    FrontendIsdbtPartialReceptionFlag partialReceptionFlag =
            static_cast<FrontendIsdbtPartialReceptionFlag>(
                    env->GetIntField(settings,
                                     env->GetFieldID(clazz, "mPartialReceptionFlag", "I")));

    FrontendIsdbtSettings frontendIsdbtSettings{
            .frequency = freq,
            .endFrequency = endFreq,
            .bandwidth = bandwidth,
            .mode = mode,
            .guardInterval = guardInterval,
            .serviceAreaId = serviceAreaId,
            .inversion = inversion,
            .partialReceptionFlag = partialReceptionFlag,
    };

    jobjectArray layerSettings = reinterpret_cast<jobjectArray>(
            env->GetObjectField(settings,
                                env->GetFieldID(clazz, "mLayerSettings",
                                                "[Landroid/media/tv/tuner/frontend/"
                                                "IsdbtFrontendSettings$IsdbtLayerSettings;")));
    int len = env->GetArrayLength(layerSettings);
    jclass layerClazz = env->FindClass(
            "android/media/tv/tuner/frontend/IsdbtFrontendSettings$IsdbtLayerSettings");
    frontendIsdbtSettings.layerSettings.resize(len);
    for (int i = 0; i < len; i++) {
        ScopedLocalRef layer(env, env->GetObjectArrayElement(layerSettings, i));
        frontendIsdbtSettings.layerSettings[i].modulation = static_cast<FrontendIsdbtModulation>(
                env->GetIntField(layer.get(), env->GetFieldID(layerClazz, "mModulation", "I")));
        frontendIsdbtSettings.layerSettings[i].timeInterleave =
                static_cast<FrontendIsdbtTimeInterleaveMode>(
                        env->GetIntField(layer.get(),
                                         env->GetFieldID(layerClazz, "mTimeInterleaveMode", "I")));
        frontendIsdbtSettings.layerSettings[i].coderate = static_cast<FrontendIsdbtCoderate>(
                env->GetIntField(layer.get(), env->GetFieldID(layerClazz, "mCodeRate", "I")));
        frontendIsdbtSettings.layerSettings[i].numOfSegment =
                env->GetIntField(layer.get(), env->GetFieldID(layerClazz, "mNumOfSegments", "I"));
    }

    frontendSettings.set<FrontendSettings::Tag::isdbt>(frontendIsdbtSettings);
    return frontendSettings;
}

static FrontendSettings getDtmbFrontendSettings(JNIEnv *env, const jobject &settings) {
    FrontendSettings frontendSettings;
    int64_t freq = getFrontendSettingsFreq(env, settings);
    int64_t endFreq = getFrontendSettingsEndFreq(env, settings);
    FrontendSpectralInversion inversion = getFrontendSettingsSpectralInversion(env, settings);
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/DtmbFrontendSettings");
    FrontendDtmbModulation modulation =
            static_cast<FrontendDtmbModulation>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mModulation", "I")));
    FrontendDtmbBandwidth bandwidth =
            static_cast<FrontendDtmbBandwidth>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mBandwidth", "I")));
    FrontendDtmbTransmissionMode transmissionMode =
            static_cast<FrontendDtmbTransmissionMode>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mTransmissionMode", "I")));
    FrontendDtmbCodeRate codeRate =
            static_cast<FrontendDtmbCodeRate>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mCodeRate", "I")));
    FrontendDtmbGuardInterval guardInterval =
            static_cast<FrontendDtmbGuardInterval>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mGuardInterval", "I")));
    FrontendDtmbTimeInterleaveMode interleaveMode =
            static_cast<FrontendDtmbTimeInterleaveMode>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mTimeInterleaveMode",
                                                               "I")));

    FrontendDtmbSettings frontendDtmbSettings{
            .frequency = freq,
            .endFrequency = endFreq,
            .modulation = modulation,
            .bandwidth = bandwidth,
            .transmissionMode = transmissionMode,
            .codeRate = codeRate,
            .guardInterval = guardInterval,
            .interleaveMode = interleaveMode,
            .inversion = inversion,
    };
    frontendSettings.set<FrontendSettings::Tag::dtmb>(frontendDtmbSettings);
    return frontendSettings;
}

static DemuxIpAddress getDemuxIpAddress(JNIEnv *env, const jobject& config,
                                        const char* className) {
    jclass clazz = env->FindClass(className);

    jbyteArray jsrcIpAddress = static_cast<jbyteArray>(
            env->GetObjectField(config, env->GetFieldID(clazz, "mSrcIpAddress", "[B")));
    jsize srcSize = env->GetArrayLength(jsrcIpAddress);
    jbyteArray jdstIpAddress = static_cast<jbyteArray>(
            env->GetObjectField(config, env->GetFieldID(clazz, "mDstIpAddress", "[B")));
    jsize dstSize = env->GetArrayLength(jdstIpAddress);

    DemuxIpAddress res;

    if (srcSize != dstSize) {
        // should never happen. Validated on Java size.
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
            "IP address lengths don't match. srcLength=%d, dstLength=%d", srcSize, dstSize);
        return res;
    }

    if (srcSize == IP_V4_LENGTH) {
        vector<uint8_t> srcAddr;
        vector<uint8_t> dstAddr;
        srcAddr.resize(IP_V4_LENGTH);
        dstAddr.resize(IP_V4_LENGTH);
        env->GetByteArrayRegion(jsrcIpAddress, 0, srcSize, reinterpret_cast<jbyte *>(&srcAddr[0]));
        env->GetByteArrayRegion(jdstIpAddress, 0, dstSize, reinterpret_cast<jbyte *>(&dstAddr[0]));
        res.srcIpAddress.set<DemuxIpAddressIpAddress::Tag::v4>(srcAddr);
        res.dstIpAddress.set<DemuxIpAddressIpAddress::Tag::v4>(dstAddr);
    } else if (srcSize == IP_V6_LENGTH) {
        vector<uint8_t> srcAddr;
        vector<uint8_t> dstAddr;
        srcAddr.resize(IP_V6_LENGTH);
        dstAddr.resize(IP_V6_LENGTH);
        env->GetByteArrayRegion(jsrcIpAddress, 0, srcSize, reinterpret_cast<jbyte *>(&srcAddr[0]));
        env->GetByteArrayRegion(jdstIpAddress, 0, dstSize, reinterpret_cast<jbyte *>(&dstAddr[0]));
        res.srcIpAddress.set<DemuxIpAddressIpAddress::Tag::v6>(srcAddr);
        res.dstIpAddress.set<DemuxIpAddressIpAddress::Tag::v6>(dstAddr);
    } else {
        // should never happen. Validated on Java size.
        jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
            "Invalid IP address length %d", srcSize);
        return res;
    }

    res.srcPort = env->GetIntField(config, env->GetFieldID(clazz, "mSrcPort", "I"));
    res.dstPort = env->GetIntField(config, env->GetFieldID(clazz, "mDstPort", "I"));

    return res;
}

static FrontendIptvSettingsFec getIptvFrontendSettingsFec(JNIEnv *env, const jobject &settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/frontend/IptvFrontendSettings");
    jclass fecClazz = env->FindClass("android/media/tv/tuner/frontend/IptvFrontendSettingsFec");
    jobject fec = env->GetObjectField(settings, env->GetFieldID(clazz, "mFec",
            "Landroid/media/tv/tuner/frontend/IptvFrontendSettingsFec;"));

    FrontendIptvSettingsFecType type =
            static_cast<FrontendIptvSettingsFecType>(
                    env->GetIntField(fec, env->GetFieldID(fecClazz, "mFecType", "I")));
    int32_t fecColNum = env->GetIntField(fec, env->GetFieldID(fecClazz, "mFecColNum", "I"));
    int32_t fecRowNum = env->GetIntField(fec, env->GetFieldID(fecClazz, "mFecRowNum", "I"));

    FrontendIptvSettingsFec frontendIptvSettingsFec = {
        .type = type,
        .fecColNum = fecColNum,
        .fecRowNum = fecRowNum,
    };

    return frontendIptvSettingsFec;
}

static FrontendSettings getIptvFrontendSettings(JNIEnv *env, const jobject &settings) {
    FrontendSettings frontendSettings;
    const char *className = "android/media/tv/tuner/frontend/IptvFrontendSettings";
    jclass clazz = env->FindClass(className);
    FrontendIptvSettingsProtocol protocol =
            static_cast<FrontendIptvSettingsProtocol>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mProtocol", "I")));
    FrontendIptvSettingsIgmp igmp =
            static_cast<FrontendIptvSettingsIgmp>(
                    env->GetIntField(settings, env->GetFieldID(clazz, "mIgmp", "I")));
    int64_t bitrate = env->GetIntField(settings, env->GetFieldID(clazz, "mBitrate", "J"));
    jstring jContentUrl = (jstring) env->GetObjectField(settings, env->GetFieldID(
                                clazz, "mContentUrl", "Ljava/lang/String;"));
    const char *contentUrl = env->GetStringUTFChars(jContentUrl, 0);
    DemuxIpAddress ipAddr = getDemuxIpAddress(env, settings, className);

    FrontendIptvSettings frontendIptvSettings{
            .protocol = protocol,
            .igmp = igmp,
            .bitrate = bitrate,
            .ipAddr = ipAddr,
            .contentUrl = contentUrl,
    };

    jobject jFec = env->GetObjectField(settings, env->GetFieldID(clazz, "mFec",
            "Landroid/media/tv/tuner/frontend/IptvFrontendSettingsFec;"));
    if (jFec != nullptr) {
        frontendIptvSettings.fec = getIptvFrontendSettingsFec(env, settings);
    }

    frontendSettings.set<FrontendSettings::Tag::iptv>(frontendIptvSettings);
    env->ReleaseStringUTFChars(jContentUrl, contentUrl);
    return frontendSettings;
}

static FrontendSettings getFrontendSettings(JNIEnv *env, int type, jobject settings) {
    ALOGV("getFrontendSettings %d", type);
    FrontendType feType = static_cast<FrontendType>(type);
    switch(feType) {
        case FrontendType::ANALOG:
            return getAnalogFrontendSettings(env, settings);
        case FrontendType::ATSC3:
            return getAtsc3FrontendSettings(env, settings);
        case FrontendType::ATSC:
            return getAtscFrontendSettings(env, settings);
        case FrontendType::DVBC:
            return getDvbcFrontendSettings(env, settings);
        case FrontendType::DVBS:
            return getDvbsFrontendSettings(env, settings);
        case FrontendType::DVBT:
            return getDvbtFrontendSettings(env, settings);
        case FrontendType::ISDBS:
            return getIsdbsFrontendSettings(env, settings);
        case FrontendType::ISDBS3:
            return getIsdbs3FrontendSettings(env, settings);
        case FrontendType::ISDBT:
            return getIsdbtFrontendSettings(env, settings);
        case FrontendType::DTMB:
            return getDtmbFrontendSettings(env, settings);
        case FrontendType::IPTV:
            return getIptvFrontendSettings(env, settings);
        default:
            // should never happen because a type is associated with a subclass of
            // FrontendSettings and not set by users
            jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException",
                "Unsupported frontend type %d", type);
            return FrontendSettings();
    }
}

static sp<FilterClient> getFilterClient(JNIEnv *env, jobject filter) {
    return (FilterClient *)env->GetLongField(filter, gFields.filterContext);
}

static sp<FilterClient> getSharedFilterClient(JNIEnv *env, jobject filter) {
    return (FilterClient *)env->GetLongField(filter, gFields.sharedFilterContext);
}

static sp<LnbClient> getLnbClient(JNIEnv *env, jobject lnb) {
    return (LnbClient *)env->GetLongField(lnb, gFields.lnbContext);
}

static DvrSettings getDvrSettings(JNIEnv *env, jobject settings, bool isRecorder) {
    DvrSettings dvrSettings;
    jclass clazz = env->FindClass("android/media/tv/tuner/dvr/DvrSettings");
    int32_t statusMask = env->GetIntField(settings, env->GetFieldID(clazz, "mStatusMask", "I"));
    int64_t lowThreshold =
            env->GetLongField(settings, env->GetFieldID(clazz, "mLowThreshold", "J"));
    int64_t highThreshold =
            env->GetLongField(settings, env->GetFieldID(clazz, "mHighThreshold", "J"));
    int64_t packetSize = env->GetLongField(settings, env->GetFieldID(clazz, "mPacketSize", "J"));
    DataFormat dataFormat =
            static_cast<DataFormat>(env->GetIntField(
                    settings, env->GetFieldID(clazz, "mDataFormat", "I")));
    if (isRecorder) {
        RecordSettings recordSettings{
                .statusMask = statusMask,
                .lowThreshold = lowThreshold,
                .highThreshold = highThreshold,
                .dataFormat = dataFormat,
                .packetSize = packetSize,
        };
        dvrSettings.set<DvrSettings::Tag::record>(recordSettings);
    } else {
        PlaybackSettings PlaybackSettings {
                .statusMask = statusMask,
                .lowThreshold = lowThreshold,
                .highThreshold = highThreshold,
                .dataFormat = dataFormat,
                .packetSize = packetSize,
        };
        dvrSettings.set<DvrSettings::Tag::playback>(PlaybackSettings);
    }
    return dvrSettings;
}

static sp<DvrClient> getDvrClient(JNIEnv *env, jobject dvr) {
    bool isRecorder =
            env->IsInstanceOf(dvr, env->FindClass("android/media/tv/tuner/dvr/DvrRecorder"));
    jfieldID fieldId =
            isRecorder ? gFields.dvrRecorderContext : gFields.dvrPlaybackContext;
    return (DvrClient *)env->GetLongField(dvr, fieldId);
}

static void android_media_tv_Tuner_native_init(JNIEnv *env) {
    jclass clazz = env->FindClass("android/media/tv/tuner/Tuner");
    CHECK(clazz != nullptr);

    gFields.tunerContext = env->GetFieldID(clazz, "mNativeContext", "J");
    CHECK(gFields.tunerContext != nullptr);

    gFields.onFrontendEventID = env->GetMethodID(clazz, "onFrontendEvent", "(I)V");

    jclass frontendClazz = env->FindClass("android/media/tv/tuner/Tuner$Frontend");
    gFields.frontendInitID =
            env->GetMethodID(frontendClazz, "<init>", "(Landroid/media/tv/tuner/Tuner;I)V");

    jclass lnbClazz = env->FindClass("android/media/tv/tuner/Lnb");
    gFields.lnbContext = env->GetFieldID(lnbClazz, "mNativeContext", "J");
    gFields.lnbInitID = env->GetMethodID(lnbClazz, "<init>", "()V");
    gFields.onLnbEventID = env->GetMethodID(lnbClazz, "onEvent", "(I)V");
    gFields.onLnbDiseqcMessageID = env->GetMethodID(lnbClazz, "onDiseqcMessage", "([B)V");

    jclass filterClazz = env->FindClass("android/media/tv/tuner/filter/Filter");
    gFields.filterContext = env->GetFieldID(filterClazz, "mNativeContext", "J");
    gFields.filterInitID =
            env->GetMethodID(filterClazz, "<init>", "(J)V");
    gFields.onFilterStatusID =
            env->GetMethodID(filterClazz, "onFilterStatus", "(I)V");
    gFields.onFilterEventID =
            env->GetMethodID(filterClazz, "onFilterEvent",
                    "([Landroid/media/tv/tuner/filter/FilterEvent;)V");

    jclass sharedFilterClazz = env->FindClass("android/media/tv/tuner/filter/SharedFilter");
    gFields.sharedFilterContext = env->GetFieldID(sharedFilterClazz, "mNativeContext", "J");
    gFields.sharedFilterInitID = env->GetMethodID(sharedFilterClazz, "<init>", "()V");
    gFields.onSharedFilterStatusID = env->GetMethodID(sharedFilterClazz, "onFilterStatus", "(I)V");
    gFields.onSharedFilterEventID =
            env->GetMethodID(sharedFilterClazz, "onFilterEvent",
                             "([Landroid/media/tv/tuner/filter/FilterEvent;)V");

    jclass timeFilterClazz = env->FindClass("android/media/tv/tuner/filter/TimeFilter");
    gFields.timeFilterContext = env->GetFieldID(timeFilterClazz, "mNativeContext", "J");
    gFields.timeFilterInitID = env->GetMethodID(timeFilterClazz, "<init>", "()V");

    jclass descramblerClazz = env->FindClass("android/media/tv/tuner/Descrambler");
    gFields.descramblerContext = env->GetFieldID(descramblerClazz, "mNativeContext", "J");
    gFields.descramblerInitID = env->GetMethodID(descramblerClazz, "<init>", "()V");

    jclass dvrRecorderClazz = env->FindClass("android/media/tv/tuner/dvr/DvrRecorder");
    gFields.dvrRecorderContext = env->GetFieldID(dvrRecorderClazz, "mNativeContext", "J");
    gFields.dvrRecorderInitID = env->GetMethodID(dvrRecorderClazz, "<init>", "()V");
    gFields.onDvrRecordStatusID =
            env->GetMethodID(dvrRecorderClazz, "onRecordStatusChanged", "(I)V");

    jclass dvrPlaybackClazz = env->FindClass("android/media/tv/tuner/dvr/DvrPlayback");
    gFields.dvrPlaybackContext = env->GetFieldID(dvrPlaybackClazz, "mNativeContext", "J");
    gFields.dvrPlaybackInitID = env->GetMethodID(dvrPlaybackClazz, "<init>", "()V");
    gFields.onDvrPlaybackStatusID =
            env->GetMethodID(dvrPlaybackClazz, "onPlaybackStatusChanged", "(I)V");

    jclass mediaEventClazz = env->FindClass("android/media/tv/tuner/filter/MediaEvent");
    gFields.mediaEventContext = env->GetFieldID(mediaEventClazz, "mNativeContext", "J");

    jclass linearBlockClazz = env->FindClass("android/media/MediaCodec$LinearBlock");
    gFields.linearBlockInitID = env->GetMethodID(linearBlockClazz, "<init>", "()V");
    gFields.linearBlockSetInternalStateID =
            env->GetMethodID(linearBlockClazz, "setInternalStateLocked", "(JZ)V");
    gAudioPresentationFields.init(env);
}

static void android_media_tv_Tuner_native_setup(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = new JTuner(env, thiz);
    setTuner(env, thiz, tuner);
}

static jint android_media_tv_Tuner_native_get_tuner_version(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getTunerVersion();
}

static jobject android_media_tv_Tuner_get_frontend_ids(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getFrontendIds();
}

static jobject android_media_tv_Tuner_open_frontend_by_handle(
        JNIEnv *env, jobject thiz, jint handle) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openFrontendByHandle(handle);
}

static int android_media_tv_Tuner_share_frontend(
        JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->shareFrontend(id);
}

static int android_media_tv_Tuner_unshare_frontend(
        JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->unshareFrontend();
}

static void android_media_tv_Tuner_register_fe_cb_listener(
        JNIEnv *env, jobject thiz, jlong shareeJTuner) {
    sp<JTuner> tuner = getTuner(env, thiz);
    JTuner *jtuner = (JTuner *)shareeJTuner;
    tuner->registerFeCbListener(jtuner);
}

static void android_media_tv_Tuner_unregister_fe_cb_listener(
        JNIEnv *env, jobject thiz, jlong shareeJTuner) {
    sp<JTuner> tuner = getTuner(env, thiz);
    JTuner *jtuner = (JTuner *)shareeJTuner;
    tuner->unregisterFeCbListener(jtuner);
}

static void android_media_tv_Tuner_update_frontend(JNIEnv *env, jobject thiz, jlong jtunerPtr) {
    sp<JTuner> tuner = getTuner(env, thiz);
    JTuner *jtuner;
    if (jtunerPtr == 0) {
        jtuner = nullptr;
    } else {
        jtuner = (JTuner *) jtunerPtr;
    }
    tuner->updateFrontend(jtuner);
}

static int android_media_tv_Tuner_tune(JNIEnv *env, jobject thiz, jint type, jobject settings) {
    sp<JTuner> tuner = getTuner(env, thiz);
    FrontendSettings setting = getFrontendSettings(env, type, settings);
    return tuner->tune(setting);
}

static int android_media_tv_Tuner_stop_tune(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->stopTune();
}

static int android_media_tv_Tuner_scan(
        JNIEnv *env, jobject thiz, jint settingsType, jobject settings, jint scanType) {
    sp<JTuner> tuner = getTuner(env, thiz);
    FrontendSettings setting = getFrontendSettings(env, settingsType, settings);
    return tuner->scan(setting, static_cast<FrontendScanType>(scanType));
}

static int android_media_tv_Tuner_stop_scan(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->stopScan();
}

static int android_media_tv_Tuner_set_lnb(JNIEnv *env, jobject thiz, jobject lnb) {
    sp<JTuner> tuner = getTuner(env, thiz);
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    if (lnbClient == nullptr) {
        ALOGE("lnb is not initialized");
        return (int)Result::INVALID_STATE;
    }
    return tuner->setLnb(lnbClient);
}

static bool android_media_tv_Tuner_is_lna_supported(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->isLnaSupported();
}

static int android_media_tv_Tuner_set_lna(JNIEnv *env, jobject thiz, jboolean enable) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->setLna(enable);
}

static jobject android_media_tv_Tuner_get_frontend_status(
        JNIEnv* env, jobject thiz, jintArray types) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getFrontendStatus(types);
}

static jobject android_media_tv_Tuner_get_av_sync_hw_id(
        JNIEnv *env, jobject thiz, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        ALOGD("Failed to get sync ID. Filter client not found");
        return nullptr;
    }
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getAvSyncHwId(filterClient);
}

static jobject android_media_tv_Tuner_get_av_sync_time(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getAvSyncTime(id);
}

static int android_media_tv_Tuner_connect_cicam(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->connectCiCam(id);
}

static int android_media_tv_Tuner_link_cicam(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->linkCiCam(id);
}

static int android_media_tv_Tuner_disconnect_cicam(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->disconnectCiCam();
}

static int android_media_tv_Tuner_unlink_cicam(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->unlinkCiCam(id);
}

static jobject android_media_tv_Tuner_get_frontend_info(JNIEnv *env, jobject thiz, jint id) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getFrontendInfo(id);
}

static jobject android_media_tv_Tuner_open_lnb_by_handle(JNIEnv *env, jobject thiz, jint handle) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openLnbByHandle(handle);
}

static jobject android_media_tv_Tuner_open_lnb_by_name(JNIEnv *env, jobject thiz, jstring name) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openLnbByName(name);
}

static jobject android_media_tv_Tuner_open_filter(
        JNIEnv *env, jobject thiz, jint type, jint subType, jlong bufferSize) {
    sp<JTuner> tuner = getTuner(env, thiz);
    DemuxFilterMainType mainType = static_cast<DemuxFilterMainType>(type);
    DemuxFilterType filterType {
        .mainType = mainType,
    };

    switch (mainType) {
        case DemuxFilterMainType::TS:
            filterType.subType.set<DemuxFilterSubType::Tag::tsFilterType>(
                    static_cast<DemuxTsFilterType>(subType));
            break;
        case DemuxFilterMainType::MMTP:
            filterType.subType.set<DemuxFilterSubType::Tag::mmtpFilterType>(
                    static_cast<DemuxMmtpFilterType>(subType));
            break;
        case DemuxFilterMainType::IP:
            filterType.subType.set<DemuxFilterSubType::Tag::ipFilterType>(
                    static_cast<DemuxIpFilterType>(subType));
            break;
        case DemuxFilterMainType::TLV:
            filterType.subType.set<DemuxFilterSubType::Tag::tlvFilterType>(
                    static_cast<DemuxTlvFilterType>(subType));
            break;
        case DemuxFilterMainType::ALP:
            filterType.subType.set<DemuxFilterSubType::Tag::alpFilterType>(
                    static_cast<DemuxAlpFilterType>(subType));
            break;
        default:
            ALOGD("Demux Filter Main Type is undefined.");
            return nullptr;
    }

    return tuner->openFilter(filterType, bufferSize);
}

static jobject android_media_tv_Tuner_open_time_filter(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openTimeFilter();
}

static DemuxFilterSectionBits getFilterSectionBits(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/SectionSettingsWithSectionBits");
    jbyteArray jfilterBytes = static_cast<jbyteArray>(
            env->GetObjectField(settings, env->GetFieldID(clazz, "mFilter", "[B")));
    jsize size = env->GetArrayLength(jfilterBytes);
    std::vector<uint8_t> filterBytes(size);
    env->GetByteArrayRegion(jfilterBytes, 0, size, reinterpret_cast<jbyte *>(&filterBytes[0]));

    jbyteArray jmask = static_cast<jbyteArray>(
            env->GetObjectField(settings, env->GetFieldID(clazz, "mMask", "[B")));
    size = env->GetArrayLength(jmask);
    std::vector<uint8_t> mask(size);
    env->GetByteArrayRegion(jmask, 0, size, reinterpret_cast<jbyte *>(&mask[0]));

    jbyteArray jmode = static_cast<jbyteArray>(
            env->GetObjectField(settings, env->GetFieldID(clazz, "mMode", "[B")));
    size = env->GetArrayLength(jmode);
    std::vector<uint8_t> mode(size);
    env->GetByteArrayRegion(jmode, 0, size, reinterpret_cast<jbyte *>(&mode[0]));

    DemuxFilterSectionBits filterSectionBits {
        .filter = filterBytes,
        .mask = mask,
        .mode = mode,
    };
    return filterSectionBits;
}

static DemuxFilterSectionSettingsConditionTableInfo getFilterTableInfo(JNIEnv *env,
                                                                       const jobject &settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/SectionSettingsWithTableInfo");
    int32_t tableId = env->GetIntField(settings, env->GetFieldID(clazz, "mTableId", "I"));
    int32_t version = env->GetIntField(settings, env->GetFieldID(clazz, "mVersion", "I"));
    DemuxFilterSectionSettingsConditionTableInfo tableInfo{
            .tableId = tableId,
            .version = version,
    };
    return tableInfo;
}

static DemuxFilterSectionSettings getFilterSectionSettings(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/SectionSettings");
    bool isCheckCrc = env->GetBooleanField(settings, env->GetFieldID(clazz, "mCrcEnabled", "Z"));
    bool isRepeat = env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsRepeat", "Z"));
    bool isRaw = env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsRaw", "Z"));
    int32_t bitWidthOfLengthField =
            env->GetIntField(settings, env->GetFieldID(clazz, "mBitWidthOfLengthField", "I"));

    DemuxFilterSectionSettings filterSectionSettings {
        .isCheckCrc = isCheckCrc,
        .isRepeat = isRepeat,
        .isRaw = isRaw,
        .bitWidthOfLengthField = bitWidthOfLengthField,
    };
    if (env->IsInstanceOf(
            settings,
            env->FindClass("android/media/tv/tuner/filter/SectionSettingsWithSectionBits"))) {
        filterSectionSettings.condition.set<DemuxFilterSectionSettingsCondition::Tag::sectionBits>(
                getFilterSectionBits(env, settings));
    } else if (env->IsInstanceOf(
            settings,
            env->FindClass("android/media/tv/tuner/filter/SectionSettingsWithTableInfo"))) {
        filterSectionSettings.condition.set<DemuxFilterSectionSettingsCondition::Tag::tableInfo>(
                getFilterTableInfo(env, settings));
    }
    return filterSectionSettings;
}

static DemuxFilterAvSettings getFilterAvSettings(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/AvSettings");
    bool isPassthrough =
            env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsPassthrough", "Z"));
    bool isSecureMemory =
            env->GetBooleanField(settings, env->GetFieldID(clazz, "mUseSecureMemory", "Z"));
    DemuxFilterAvSettings filterAvSettings{
            .isPassthrough = isPassthrough,
            .isSecureMemory = isSecureMemory,
    };
    return filterAvSettings;
}

static bool getAvStreamType(JNIEnv *env, jobject filterConfigObj, AvStreamType& type) {
    jobject settingsObj =
            env->GetObjectField(
                    filterConfigObj,
                    env->GetFieldID(
                            env->FindClass("android/media/tv/tuner/filter/FilterConfiguration"),
                            "mSettings",
                            "Landroid/media/tv/tuner/filter/Settings;"));
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/AvSettings");
    AvStreamType streamType;
    AudioStreamType audioStreamType = static_cast<AudioStreamType>(
            env->GetIntField(settingsObj, env->GetFieldID(clazz, "mAudioStreamType", "I")));
    if (audioStreamType != AudioStreamType::UNDEFINED) {
        type.set<AvStreamType::Tag::audio>(audioStreamType);
        return true;
    }
    VideoStreamType videoStreamType = static_cast<VideoStreamType>(
            env->GetIntField(settingsObj, env->GetFieldID(clazz, "mVideoStreamType", "I")));
    if (videoStreamType != VideoStreamType::UNDEFINED) {
        type.set<AvStreamType::Tag::video>(videoStreamType);
        return true;
    }
    return false;
}

static DemuxFilterPesDataSettings getFilterPesDataSettings(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/PesSettings");
    int32_t streamId = env->GetIntField(settings, env->GetFieldID(clazz, "mStreamId", "I"));
    bool isRaw = env->GetBooleanField(settings, env->GetFieldID(clazz, "mIsRaw", "Z"));
    DemuxFilterPesDataSettings filterPesDataSettings {
        .streamId = streamId,
        .isRaw = isRaw,
    };
    return filterPesDataSettings;
}

static DemuxFilterRecordSettings getFilterRecordSettings(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/RecordSettings");
    int32_t tsIndexMask = env->GetIntField(settings, env->GetFieldID(clazz, "mTsIndexMask", "I"));
    DemuxRecordScIndexType scIndexType = static_cast<DemuxRecordScIndexType>(
            env->GetIntField(settings, env->GetFieldID(clazz, "mScIndexType", "I")));
    jint scIndexMask = env->GetIntField(settings, env->GetFieldID(clazz, "mScIndexMask", "I"));

    // Backward compatibility for S- apps.
    if (scIndexType == DemuxRecordScIndexType::SC &&
        scIndexMask > static_cast<int32_t>(DemuxScIndex::SEQUENCE)) {
        scIndexType = DemuxRecordScIndexType::SC_AVC;
    }
    DemuxFilterRecordSettings filterRecordSettings {
        .tsIndexMask = tsIndexMask,
        .scIndexType = scIndexType,
    };
    if (scIndexType == DemuxRecordScIndexType::SC) {
        filterRecordSettings.scIndexMask.set<DemuxFilterScIndexMask::Tag::scIndex>(scIndexMask);
    } else if (scIndexType == DemuxRecordScIndexType::SC_HEVC) {
        filterRecordSettings.scIndexMask.set<DemuxFilterScIndexMask::Tag::scHevc>(scIndexMask);
    } else if (scIndexType == DemuxRecordScIndexType::SC_AVC) {
        // Java uses the values defined by HIDL HAL. Right shift 4 bits.
        filterRecordSettings.scIndexMask.set<DemuxFilterScIndexMask::Tag::scAvc>(scIndexMask >> 4);
    } else if (scIndexType == DemuxRecordScIndexType::SC_VVC) {
        filterRecordSettings.scIndexMask.set<DemuxFilterScIndexMask::Tag::scVvc>(scIndexMask);
    }
    return filterRecordSettings;
}

static DemuxFilterDownloadSettings getFilterDownloadSettings(JNIEnv *env, const jobject& settings) {
    jclass clazz = env->FindClass("android/media/tv/tuner/filter/DownloadSettings");
    bool useDownloadId =
            env->GetBooleanField(settings, env->GetFieldID(clazz, "mUseDownloadId", "Z"));
    int32_t downloadId = env->GetIntField(settings, env->GetFieldID(clazz, "mDownloadId", "I"));

    DemuxFilterDownloadSettings filterDownloadSettings{
            .useDownloadId = useDownloadId,
            .downloadId = downloadId,
    };
    return filterDownloadSettings;
}

static DemuxFilterSettings getFilterConfiguration(
        JNIEnv *env, int type, int subtype, jobject filterConfigObj) {
    DemuxFilterSettings filterSettings;
    jobject settingsObj =
            env->GetObjectField(
                    filterConfigObj,
                    env->GetFieldID(
                            env->FindClass("android/media/tv/tuner/filter/FilterConfiguration"),
                            "mSettings",
                            "Landroid/media/tv/tuner/filter/Settings;"));
    DemuxFilterMainType mainType = static_cast<DemuxFilterMainType>(type);
    switch (mainType) {
        case DemuxFilterMainType::TS: {
            jclass clazz = env->FindClass("android/media/tv/tuner/filter/TsFilterConfiguration");
            int32_t tpid = env->GetIntField(filterConfigObj, env->GetFieldID(clazz, "mTpid", "I"));
            DemuxTsFilterSettings tsFilterSettings {
                .tpid = tpid,
            };

            if (settingsObj != nullptr) {
                DemuxTsFilterType tsType = static_cast<DemuxTsFilterType>(subtype);
                switch (tsType) {
                    case DemuxTsFilterType::SECTION:
                        tsFilterSettings.filterSettings
                                .set<DemuxTsFilterSettingsFilterSettings::Tag::section>(
                                        getFilterSectionSettings(env, settingsObj));
                        break;
                    case DemuxTsFilterType::AUDIO:
                    case DemuxTsFilterType::VIDEO:
                        tsFilterSettings.filterSettings
                                .set<DemuxTsFilterSettingsFilterSettings::Tag::av>(
                                        getFilterAvSettings(env, settingsObj));
                        break;
                    case DemuxTsFilterType::PES:
                        tsFilterSettings.filterSettings
                                .set<DemuxTsFilterSettingsFilterSettings::Tag::pesData>(
                                        getFilterPesDataSettings(env, settingsObj));
                        break;
                    case DemuxTsFilterType::RECORD:
                        tsFilterSettings.filterSettings
                                .set<DemuxTsFilterSettingsFilterSettings::Tag::record>(
                                        getFilterRecordSettings(env, settingsObj));
                        break;
                    default:
                        break;
                }
            }
            filterSettings.set<DemuxFilterSettings::Tag::ts>(tsFilterSettings);
            break;
        }
        case DemuxFilterMainType::MMTP: {
            jclass clazz = env->FindClass("android/media/tv/tuner/filter/MmtpFilterConfiguration");
            int32_t mmtpPid =
                    env->GetIntField(filterConfigObj, env->GetFieldID(clazz, "mMmtpPid", "I"));
            DemuxMmtpFilterSettings mmtpFilterSettings {
                .mmtpPid = mmtpPid,
            };

            if (settingsObj != nullptr) {
                DemuxMmtpFilterType mmtpType = static_cast<DemuxMmtpFilterType>(subtype);
                switch (mmtpType) {
                    case DemuxMmtpFilterType::SECTION:
                        mmtpFilterSettings.filterSettings
                                .set<DemuxMmtpFilterSettingsFilterSettings::Tag::section>(
                                        getFilterSectionSettings(env, settingsObj));
                        break;
                    case DemuxMmtpFilterType::AUDIO:
                    case DemuxMmtpFilterType::VIDEO:
                        mmtpFilterSettings.filterSettings
                                .set<DemuxMmtpFilterSettingsFilterSettings::Tag::av>(
                                        getFilterAvSettings(env, settingsObj));
                        break;
                    case DemuxMmtpFilterType::PES:
                        mmtpFilterSettings.filterSettings
                                .set<DemuxMmtpFilterSettingsFilterSettings::Tag::pesData>(
                                        getFilterPesDataSettings(env, settingsObj));
                        break;
                    case DemuxMmtpFilterType::RECORD:
                        mmtpFilterSettings.filterSettings
                                .set<DemuxMmtpFilterSettingsFilterSettings::Tag::record>(
                                        getFilterRecordSettings(env, settingsObj));
                        break;
                    case DemuxMmtpFilterType::DOWNLOAD:
                        mmtpFilterSettings.filterSettings
                                .set<DemuxMmtpFilterSettingsFilterSettings::Tag::download>(
                                        getFilterDownloadSettings(env, settingsObj));
                        break;
                    default:
                        break;
                }
            }
            filterSettings.set<DemuxFilterSettings::Tag::mmtp>(mmtpFilterSettings);
            break;
        }
        case DemuxFilterMainType::IP: {
            DemuxIpAddress ipAddr = getDemuxIpAddress(env, filterConfigObj,
                                    "android/media/tv/tuner/filter/IpFilterConfiguration");
            DemuxIpFilterSettings ipFilterSettings {
                .ipAddr = ipAddr,
            };

            DemuxIpFilterType ipType = static_cast<DemuxIpFilterType>(subtype);
            if (ipType == DemuxIpFilterType::SECTION && settingsObj != nullptr) {
                ipFilterSettings.filterSettings
                        .set<DemuxIpFilterSettingsFilterSettings::Tag::section>(
                                getFilterSectionSettings(env, settingsObj));
            } else if (ipType == DemuxIpFilterType::IP) {
                jclass clazz = env->FindClass(
                        "android/media/tv/tuner/filter/IpFilterConfiguration");
                bool bPassthrough =
                        env->GetBooleanField(filterConfigObj,
                                             env->GetFieldID(clazz, "mPassthrough", "Z"));
                ipFilterSettings.filterSettings
                        .set<DemuxIpFilterSettingsFilterSettings::Tag::bPassthrough>(bPassthrough);
            }
            filterSettings.set<DemuxFilterSettings::Tag::ip>(ipFilterSettings);
            break;
        }
        case DemuxFilterMainType::TLV: {
            jclass clazz = env->FindClass("android/media/tv/tuner/filter/TlvFilterConfiguration");
            int32_t packetType =
                    env->GetIntField(filterConfigObj, env->GetFieldID(clazz, "mPacketType", "I"));
            bool isCompressedIpPacket =
                    env->GetBooleanField(filterConfigObj,
                                         env->GetFieldID(clazz, "mIsCompressedIpPacket", "Z"));

            DemuxTlvFilterSettings tlvFilterSettings {
                .packetType = packetType,
                .isCompressedIpPacket = isCompressedIpPacket,
            };

            DemuxTlvFilterType tlvType = static_cast<DemuxTlvFilterType>(subtype);
            if (tlvType == DemuxTlvFilterType::SECTION && settingsObj != nullptr) {
                tlvFilterSettings.filterSettings
                        .set<DemuxTlvFilterSettingsFilterSettings::Tag::section>(
                                getFilterSectionSettings(env, settingsObj));
            } else if (tlvType == DemuxTlvFilterType::TLV) {
                bool bPassthrough =
                        env->GetBooleanField(filterConfigObj,
                                             env->GetFieldID(clazz, "mPassthrough", "Z"));
                tlvFilterSettings.filterSettings
                        .set<DemuxTlvFilterSettingsFilterSettings::Tag::bPassthrough>(bPassthrough);
            }
            filterSettings.set<DemuxFilterSettings::Tag::tlv>(tlvFilterSettings);
            break;
        }
        case DemuxFilterMainType::ALP: {
            jclass clazz = env->FindClass("android/media/tv/tuner/filter/AlpFilterConfiguration");
            int32_t packetType =
                    env->GetIntField(filterConfigObj, env->GetFieldID(clazz, "mPacketType", "I"));
            DemuxAlpLengthType lengthType = static_cast<DemuxAlpLengthType>(
                    env->GetIntField(filterConfigObj, env->GetFieldID(clazz, "mLengthType", "I")));
            DemuxAlpFilterSettings alpFilterSettings {
                .packetType = packetType,
                .lengthType = lengthType,
            };

            if (settingsObj != nullptr) {
                DemuxAlpFilterType alpType = static_cast<DemuxAlpFilterType>(subtype);
                switch (alpType) {
                    case DemuxAlpFilterType::SECTION:
                        alpFilterSettings.filterSettings
                                .set<DemuxAlpFilterSettingsFilterSettings::Tag::section>(
                                        getFilterSectionSettings(env, settingsObj));
                        break;
                    default:
                        break;
                }
            }
            filterSettings.set<DemuxFilterSettings::Tag::alp>(alpFilterSettings);
            break;
        }
        default: {
            break;
        }
    }
    return filterSettings;
}

static Result configureIpFilterContextId(
        JNIEnv *env, sp<FilterClient> filterClient, jobject ipFilterConfigObj) {
    jclass clazz = env->FindClass(
            "android/media/tv/tuner/filter/IpFilterConfiguration");
    uint32_t cid = env->GetIntField(ipFilterConfigObj, env->GetFieldID(
            clazz, "mIpFilterContextId", "I"));

    return filterClient->configureIpFilterContextId(cid);
}

static bool isAvFilterSettings(DemuxFilterSettings filterSettings) {
    return (filterSettings.getTag() == DemuxFilterSettings::Tag::ts &&
            filterSettings.get<DemuxFilterSettings::Tag::ts>().filterSettings.getTag() ==
                    DemuxTsFilterSettingsFilterSettings::Tag::av) ||
            (filterSettings.getTag() == DemuxFilterSettings::Tag::mmtp &&
             filterSettings.get<DemuxFilterSettings::Tag::mmtp>().filterSettings.getTag() ==
                     DemuxMmtpFilterSettingsFilterSettings::Tag::av);
}

static jint android_media_tv_Tuner_configure_filter(
        JNIEnv *env, jobject filter, int type, int subtype, jobject settings) {
    ALOGV("configure filter type=%d, subtype=%d", type, subtype);
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        ALOGD("Failed to configure filter: filter not found");
        return (jint)Result::NOT_INITIALIZED;
    }
    DemuxFilterSettings filterSettings = getFilterConfiguration(env, type, subtype, settings);
    Result res = filterClient->configure(filterSettings);

    if (res != Result::SUCCESS) {
        return (jint)res;
    }

    if (static_cast<DemuxFilterMainType>(type) == DemuxFilterMainType::IP) {
        res = configureIpFilterContextId(env, filterClient, settings);
        if (res != Result::SUCCESS) {
            return (jint)res;
        }
    }

    AvStreamType streamType;
    if (isAvFilterSettings(filterSettings) && getAvStreamType(env, settings, streamType)) {
        res = filterClient->configureAvStreamType(streamType);
    }
    return (jint)res;
}

static jint android_media_tv_Tuner_get_filter_id(JNIEnv* env, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        ALOGD("Failed to get filter ID: filter client not found");
        return (int) Result::NOT_INITIALIZED;
    }
    int32_t id;
    Result res = filterClient->getId(id);
    if (res != Result::SUCCESS) {
        return (jint)Constant::INVALID_FILTER_ID;
    }
    return (jint)id;
}

static jlong android_media_tv_Tuner_get_filter_64bit_id(JNIEnv* env, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        ALOGD("Failed to get filter ID 64 bit: filter client not found");
        return (int)Result::NOT_INITIALIZED;
    }
    int64_t id;
    Result res = filterClient->getId64Bit(id);
    return (res == Result::SUCCESS) ? id
                                    : static_cast<jlong>(Constant64Bit::INVALID_FILTER_ID_64BIT);
}

static jint android_media_tv_Tuner_configure_monitor_event(
        JNIEnv* env, jobject filter, int monitorEventType) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        ALOGD("Failed to configure scrambling event: filter client not found");
        return (int)Result::NOT_INITIALIZED;
    }
    Result res = filterClient->configureMonitorEvent(monitorEventType);
    return (jint)res;
}

static jint android_media_tv_Tuner_set_filter_data_source(
        JNIEnv* env, jobject filter, jobject srcFilter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        ALOGD("Failed to set filter data source: filter client not found");
        return (int)Result::NOT_INITIALIZED;
    }
    Result res;
    if (srcFilter == nullptr) {
        res = filterClient->setDataSource(nullptr);
    } else {
        sp<FilterClient> srcClient = getFilterClient(env, srcFilter);
        if (srcClient == nullptr) {
            ALOGD("Failed to set filter data source: src filter not found");
            return (jint)Result::INVALID_ARGUMENT;
        }
        res = filterClient->setDataSource(srcClient);
    }
    return (jint)res;
}

static jint android_media_tv_Tuner_start_filter(JNIEnv *env, jobject filter) {
    sp<FilterClient> filterClient = nullptr;
    if (env->IsInstanceOf(filter, env->FindClass("android/media/tv/tuner/filter/SharedFilter"))) {
        filterClient = getSharedFilterClient(env, filter);
    } else {
        filterClient = getFilterClient(env, filter);
    }
    if (filterClient == nullptr) {
        ALOGD("Failed to start filter: filter client not found");
        return (int)Result::NOT_INITIALIZED;
    }
    return (jint)filterClient->start();
}

static jint android_media_tv_Tuner_stop_filter(JNIEnv *env, jobject filter) {
    sp<FilterClient> filterClient = nullptr;
    if (env->IsInstanceOf(filter, env->FindClass("android/media/tv/tuner/filter/SharedFilter"))) {
        filterClient = getSharedFilterClient(env, filter);
    } else {
        filterClient = getFilterClient(env, filter);
    }
    if (filterClient == nullptr) {
        ALOGD("Failed to stop filter: filter client not found");
        return (int)Result::NOT_INITIALIZED;
    }
    return (jint)filterClient->stop();
}

static jint android_media_tv_Tuner_flush_filter(JNIEnv *env, jobject filter) {
    sp<FilterClient> filterClient = nullptr;
    if (env->IsInstanceOf(filter, env->FindClass("android/media/tv/tuner/filter/SharedFilter"))) {
        filterClient = getSharedFilterClient(env, filter);
    } else {
        filterClient = getFilterClient(env, filter);
    }
    if (filterClient == nullptr) {
        ALOGD("Failed to flush filter: filter client not found");
        return (jint)Result::NOT_INITIALIZED;
    }
    return (jint)filterClient->flush();
}

static jint android_media_tv_Tuner_read_filter_fmq(
        JNIEnv *env, jobject filter, jbyteArray buffer, jlong offset, jlong size) {
    sp<FilterClient> filterClient = nullptr;
    if (env->IsInstanceOf(filter, env->FindClass("android/media/tv/tuner/filter/SharedFilter"))) {
        filterClient = getSharedFilterClient(env, filter);
    } else {
        filterClient = getFilterClient(env, filter);
    }
    if (filterClient == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Failed to read filter FMQ: filter client not found");
        return -1;
    }

    jboolean isCopy;
    jbyte *dst = env->GetByteArrayElements(buffer, &isCopy);
    ALOGV("copyData, isCopy=%d", isCopy);
    if (dst == nullptr) {
        jniThrowRuntimeException(env, "Failed to GetByteArrayElements");
        return -1;
    }
    int realReadSize = filterClient->read(reinterpret_cast<int8_t *>(dst) + offset, size);
    env->ReleaseByteArrayElements(buffer, dst, 0);
    return (jint)realReadSize;
}

static jint android_media_tv_Tuner_close_filter(JNIEnv *env, jobject filter) {
    sp<FilterClient> filterClient = nullptr;
    bool shared = env->IsInstanceOf(
        filter, env->FindClass("android/media/tv/tuner/filter/SharedFilter"));
    if (shared) {
        filterClient = getSharedFilterClient(env, filter);
    } else {
        filterClient = getFilterClient(env, filter);
    }
    if (filterClient == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Failed to close filter: filter client not found");
        return 0;
    }

    Result r = filterClient->close();
    filterClient->decStrong(filter);
    filterClient = nullptr;
    if (shared) {
        env->SetLongField(filter, gFields.sharedFilterContext, 0);
    } else {
        env->SetLongField(filter, gFields.filterContext, 0);
    }

    return (jint)r;
}

static jstring android_media_tv_Tuner_acquire_shared_filter_token(JNIEnv *env, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Failed to acquire shared filter token: filter client not found");
        return nullptr;
    }

    string token = filterClient->acquireSharedFilterToken();
    if (token.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(token.data());
}

static void android_media_tv_Tuner_free_shared_filter_token(
        JNIEnv *env, jobject filter, jstring token) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Failed to release shared filter: filter client not found");
        return;
    }

    std::string filterToken(env->GetStringUTFChars(token, nullptr));
    filterClient->freeSharedFilterToken(filterToken);
}

static jint android_media_tv_Tuner_set_filter_time_delay_hint(
        JNIEnv *env, jobject filter, int timeDelayInMs) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Failed to set filter delay: filter client not found");
    }

    FilterDelayHint delayHint {
        .hintType = FilterDelayHintType::TIME_DELAY_IN_MS,
        .hintValue = timeDelayInMs,
    };
    return static_cast<jint>(filterClient->setDelayHint(delayHint));
}

static jint android_media_tv_Tuner_set_filter_data_size_delay_hint(
        JNIEnv *env, jobject filter, int dataSizeDelayInBytes) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Failed to set filter delay: filter client not found");
    }

    FilterDelayHint delayHint {
        .hintType = FilterDelayHintType::DATA_SIZE_DELAY_IN_BYTES,
        .hintValue = dataSizeDelayInBytes,
    };
    return static_cast<jint>(filterClient->setDelayHint(delayHint));
}

static sp<TimeFilterClient> getTimeFilterClient(JNIEnv *env, jobject filter) {
    return (TimeFilterClient *)env->GetLongField(filter, gFields.timeFilterContext);
}

static int android_media_tv_Tuner_time_filter_set_timestamp(
        JNIEnv *env, jobject filter, jlong timestamp) {
    sp<TimeFilterClient> timeFilterClient = getTimeFilterClient(env, filter);
    if (timeFilterClient == nullptr) {
        ALOGD("Failed set timestamp: time filter client not found");
        return (int) Result::INVALID_STATE;
    }
    return (int)timeFilterClient->setTimeStamp(timestamp);
}

static int android_media_tv_Tuner_time_filter_clear_timestamp(JNIEnv *env, jobject filter) {
    sp<TimeFilterClient> timeFilterClient = getTimeFilterClient(env, filter);
    if (timeFilterClient == nullptr) {
        ALOGD("Failed clear timestamp: time filter client not found");
        return (int) Result::INVALID_STATE;
    }
    return (int)timeFilterClient->clearTimeStamp();
}

static jobject android_media_tv_Tuner_time_filter_get_timestamp(JNIEnv *env, jobject filter) {
    sp<TimeFilterClient> timeFilterClient = getTimeFilterClient(env, filter);
    if (timeFilterClient == nullptr) {
        ALOGD("Failed get timestamp: time filter client not found");
        return nullptr;
    }
    int64_t timestamp = timeFilterClient->getTimeStamp();
    if (timestamp == (long)Constant64Bit::INVALID_PRESENTATION_TIME_STAMP) {
        return nullptr;
    }

    jclass longClazz = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClazz, "<init>", "(J)V");

    jobject longObj = env->NewObject(longClazz, longInit, timestamp);
    return longObj;
}

static jobject android_media_tv_Tuner_time_filter_get_source_time(JNIEnv *env, jobject filter) {
    sp<TimeFilterClient> timeFilterClient = getTimeFilterClient(env, filter);
    if (timeFilterClient == nullptr) {
        ALOGD("Failed get source time: time filter client not found");
        return nullptr;
    }
    int64_t timestamp = timeFilterClient->getSourceTime();
    if (timestamp == (long)Constant64Bit::INVALID_PRESENTATION_TIME_STAMP) {
        return nullptr;
    }

    jclass longClazz = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClazz, "<init>", "(J)V");

    jobject longObj = env->NewObject(longClazz, longInit, timestamp);
    return longObj;
}

static int android_media_tv_Tuner_time_filter_close(JNIEnv *env, jobject filter) {
    sp<TimeFilterClient> timeFilterClient = getTimeFilterClient(env, filter);
    if (timeFilterClient == nullptr) {
        ALOGD("Failed close time filter: time filter client not found");
        return (int) Result::INVALID_STATE;
    }

    Result r = timeFilterClient->close();
    timeFilterClient->decStrong(filter);
    env->SetLongField(filter, gFields.timeFilterContext, 0);
    return (int)r;
}

static jobject android_media_tv_Tuner_open_descrambler(JNIEnv *env, jobject thiz, jint) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openDescrambler();
}

static jint android_media_tv_Tuner_descrambler_add_pid(
        JNIEnv *env, jobject descrambler, jint pidType, jint pid, jobject filter) {
    sp<DescramblerClient> descramblerClient = getDescramblerClient(env, descrambler);
    if (descramblerClient == nullptr) {
        return (jint)Result::NOT_INITIALIZED;
    }
    sp<FilterClient> filterClient = (filter == nullptr) ? nullptr : getFilterClient(env, filter);
    Result result = descramblerClient->addPid(getDemuxPid((int)pidType, (int)pid), filterClient);
    return (jint)result;
}

static jint android_media_tv_Tuner_descrambler_remove_pid(
        JNIEnv *env, jobject descrambler, jint pidType, jint pid, jobject filter) {
    sp<DescramblerClient> descramblerClient = getDescramblerClient(env, descrambler);
    if (descramblerClient == nullptr) {
        return (jint)Result::NOT_INITIALIZED;
    }
    sp<FilterClient> filterClient = (filter == nullptr) ? nullptr : getFilterClient(env, filter);
    Result result = descramblerClient->removePid(getDemuxPid((int)pidType, (int)pid), filterClient);
    return (jint)result;
}

static jint android_media_tv_Tuner_descrambler_set_key_token(
        JNIEnv* env, jobject descrambler, jbyteArray keyToken) {
    sp<DescramblerClient> descramblerClient = getDescramblerClient(env, descrambler);
    if (descramblerClient == nullptr) {
        return (jint)Result::NOT_INITIALIZED;
    }
    int size = env->GetArrayLength(keyToken);
    std::vector<uint8_t> v(size);
    env->GetByteArrayRegion(keyToken, 0, size, reinterpret_cast<jbyte *>(&v[0]));
    Result result = descramblerClient->setKeyToken(v);
    return (jint)result;
}

static jint android_media_tv_Tuner_close_descrambler(JNIEnv* env, jobject descrambler) {
    sp<DescramblerClient> descramblerClient = getDescramblerClient(env, descrambler);
    if (descramblerClient == nullptr) {
        return (jint)Result::NOT_INITIALIZED;
    }
    Result r = descramblerClient->close();
    descramblerClient->decStrong(descrambler);
    env->SetLongField(descrambler, gFields.descramblerContext, 0);
    return (jint)r;
}

static jobject android_media_tv_Tuner_open_dvr_recorder(
        JNIEnv* env, jobject thiz, jlong bufferSize) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openDvr(DvrType::RECORD, bufferSize);
}

static jobject android_media_tv_Tuner_open_dvr_playback(
        JNIEnv* env, jobject thiz, jlong bufferSize) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->openDvr(DvrType::PLAYBACK, bufferSize);
}

static jobject android_media_tv_Tuner_get_demux_caps(JNIEnv* env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getDemuxCaps();
}

static jobject android_media_tv_Tuner_get_demux_info(JNIEnv* env, jobject thiz, jint handle) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getDemuxInfo(handle);
}

static jint android_media_tv_Tuner_open_demux(JNIEnv* env, jobject thiz, jint handle) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return (jint)tuner->openDemux(handle);
}

static jint android_media_tv_Tuner_close_tuner(JNIEnv* env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    setTuner(env, thiz, nullptr);
    return (jint)tuner->close();
}

static jint android_media_tv_Tuner_close_demux(JNIEnv* env, jobject thiz, jint /* handle */) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->closeDemux();
}

static jobject android_media_tv_Tuner_open_shared_filter(
        JNIEnv* env, jobject /* thiz */, jstring token) {

    sp<TunerClient> tunerClient = new TunerClient();
    std::string filterToken(env->GetStringUTFChars(token, nullptr));
    sp<FilterClient> filterClient;
    sp<FilterClientCallbackImpl> callback = new FilterClientCallbackImpl();
    filterClient = tunerClient->openSharedFilter(filterToken, callback);
    if (filterClient == nullptr) {
        ALOGD("Failed to open shared filter %s", filterToken.c_str());
        return nullptr;
    }

    jobject filterObj = env->NewObject(env->FindClass("android/media/tv/tuner/filter/SharedFilter"),
                                       gFields.sharedFilterInitID);

    filterClient->incStrong(filterObj);
    env->SetLongField(filterObj, gFields.sharedFilterContext, (jlong)filterClient.get());
    callback->setSharedFilter(env->NewWeakGlobalRef(filterObj), filterClient);

    return filterObj;
}

static jstring android_media_tv_Tuner_get_frontend_hardware_info(JNIEnv *env, jobject thiz) {
    sp<JTuner> tuner = getTuner(env, thiz);
    string info;
    Result r = tuner->getFrontendHardwareInfo(info);
    if (r != Result::SUCCESS) {
        return nullptr;
    }
    return env->NewStringUTF(info.data());
}

static jint android_media_tv_Tuner_set_maximum_frontends(JNIEnv *env, jobject thiz, jint type,
                                                         jint maxNumber) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->setMaxNumberOfFrontends(type, maxNumber);
}

static jint android_media_tv_Tuner_get_maximum_frontends(JNIEnv *env, jobject thiz, jint type) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getMaxNumberOfFrontends(type);
}

static jint android_media_tv_Tuner_remove_output_pid(JNIEnv *env, jobject thiz, jint pid) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->removeOutputPid(pid);
}

static jobjectArray android_media_tv_Tuner_get_frontend_status_readiness(JNIEnv *env, jobject thiz,
                                                                         jintArray types) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->getFrontendStatusReadiness(types);
}

static jint android_media_tv_Tuner_close_frontend(JNIEnv* env, jobject thiz, jint /* handle */) {
    sp<JTuner> tuner = getTuner(env, thiz);
    return tuner->closeFrontend();
}

static jint android_media_tv_Tuner_attach_filter(JNIEnv *env, jobject dvr, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        return (jint)Result::INVALID_ARGUMENT;
    }
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        return (jint)Result::NOT_INITIALIZED;
    }
    Result result = dvrClient->attachFilter(filterClient);
    return (jint)result;
}

static jint android_media_tv_Tuner_detach_filter(JNIEnv *env, jobject dvr, jobject filter) {
    sp<FilterClient> filterClient = getFilterClient(env, filter);
    if (filterClient == nullptr) {
        return (jint)Result::INVALID_ARGUMENT;
    }
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        return (jint)Result::NOT_INITIALIZED;
    }
    Result result = dvrClient->detachFilter(filterClient);
    return (jint)result;
}

static jint android_media_tv_Tuner_configure_dvr(JNIEnv *env, jobject dvr, jobject settings) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        ALOGD("Failed to configure dvr: dvr client not found");
        return (int)Result::NOT_INITIALIZED;
    }
    bool isRecorder =
            env->IsInstanceOf(dvr, env->FindClass("android/media/tv/tuner/dvr/DvrRecorder"));
    Result result = dvrClient->configure(getDvrSettings(env, settings, isRecorder));
    return (jint)result;
}

static jint android_media_tv_Tuner_set_status_check_interval_hint(JNIEnv *env, jobject dvr,
                                                                  jlong durationInMs) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        ALOGD("Failed to set status check interval hint: dvr client not found");
        return (int)Result::NOT_INITIALIZED;
    }
    Result result = dvrClient->setStatusCheckIntervalHint(durationInMs);
    return (jint)result;
}

static jint android_media_tv_Tuner_start_dvr(JNIEnv *env, jobject dvr) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        ALOGD("Failed to start dvr: dvr client not found");
        return (jint)Result::NOT_INITIALIZED;
    }
    Result result = dvrClient->start();
    return (jint)result;
}

static jint android_media_tv_Tuner_stop_dvr(JNIEnv *env, jobject dvr) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        ALOGD("Failed to stop dvr: dvr client not found");
        return (jint)Result::NOT_INITIALIZED;
    }
    Result result = dvrClient->stop();
    return (jint)result;
}

static jint android_media_tv_Tuner_flush_dvr(JNIEnv *env, jobject dvr) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        ALOGD("Failed to flush dvr: dvr client not found");
        return (jint)Result::NOT_INITIALIZED;
    }
    Result result = dvrClient->flush();
    return (jint)result;
}

static jint android_media_tv_Tuner_close_dvr(JNIEnv* env, jobject dvr) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        ALOGD("Failed to close dvr: dvr client not found");
        return (jint)Result::NOT_INITIALIZED;
    }
    Result r = dvrClient->close();
    bool isRecorder =
            env->IsInstanceOf(dvr, env->FindClass("android/media/tv/tuner/dvr/DvrRecorder"));
    jfieldID fieldId =
            isRecorder ? gFields.dvrRecorderContext : gFields.dvrPlaybackContext;
    dvrClient->decStrong(dvr);
    env->SetLongField(dvr, fieldId, 0);
    return (jint)r;
}

static jint android_media_tv_Tuner_lnb_set_voltage(JNIEnv* env, jobject lnb, jint voltage) {
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    return (jint)lnbClient->setVoltage(static_cast<LnbVoltage>(voltage));
}

static int android_media_tv_Tuner_lnb_set_tone(JNIEnv* env, jobject lnb, jint tone) {
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    return (jint)lnbClient->setTone(static_cast<LnbTone>(tone));
}

static int android_media_tv_Tuner_lnb_set_position(JNIEnv* env, jobject lnb, jint position) {
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    return (jint)lnbClient->setSatellitePosition(static_cast<LnbPosition>(position));
}

static int android_media_tv_Tuner_lnb_send_diseqc_msg(JNIEnv* env, jobject lnb, jbyteArray msg) {
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    int size = env->GetArrayLength(msg);
    std::vector<uint8_t> v(size);
    env->GetByteArrayRegion(msg, 0, size, reinterpret_cast<jbyte *>(&v[0]));
    return (jint)lnbClient->sendDiseqcMessage(v);
}

static int android_media_tv_Tuner_close_lnb(JNIEnv* env, jobject lnb) {
    sp<LnbClient> lnbClient = getLnbClient(env, lnb);
    Result r = lnbClient->close();
    lnbClient->decStrong(lnb);
    env->SetLongField(lnb, gFields.lnbContext, 0);
    return (jint)r;
}

static void android_media_tv_Tuner_dvr_set_fd(JNIEnv *env, jobject dvr, jint fd) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        ALOGD("Failed to set FD for dvr: dvr client not found");
        return;
    }
    dvrClient->setFd(fd);
    ALOGV("set fd = %d", fd);
}

static jlong android_media_tv_Tuner_read_dvr(JNIEnv *env, jobject dvr, jlong size) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Failed to read dvr: dvr client not found");
        return -1;
    }

    return (jlong)dvrClient->readFromFile(size);
}

static jlong android_media_tv_Tuner_seek_dvr(JNIEnv *env, jobject dvr, jlong pos) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                          "Failed to seek dvr: dvr client not found");
        return -1;
    }

    return (jlong)dvrClient->seekFile(pos);
}

static jlong android_media_tv_Tuner_read_dvr_from_array(
        JNIEnv* env, jobject dvr, jbyteArray buffer, jlong offset, jlong size) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        ALOGW("Failed to read dvr: dvr client not found");
        return -1;
    }

    jboolean isCopy;
    jbyte *src = env->GetByteArrayElements(buffer, &isCopy);
    if (src == nullptr) {
        ALOGD("Failed to GetByteArrayElements");
        return -1;
    }
    int64_t realSize =
            dvrClient->readFromBuffer(reinterpret_cast<signed char *>(src) + offset, size);
    env->ReleaseByteArrayElements(buffer, src, 0);
    return (jlong)realSize;
}

static jlong android_media_tv_Tuner_write_dvr(JNIEnv *env, jobject dvr, jlong size) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "Failed to write dvr: dvr client not found");
        return -1;
    }

    return (jlong)dvrClient->writeToFile(size);
}

static jlong android_media_tv_Tuner_write_dvr_to_array(
        JNIEnv *env, jobject dvr, jbyteArray buffer, jlong offset, jlong size) {
    sp<DvrClient> dvrClient = getDvrClient(env, dvr);
    if (dvrClient == nullptr) {
        ALOGW("Failed to read dvr: dvr client not found");
        return -1;
    }

    jboolean isCopy;
    jbyte *dst = env->GetByteArrayElements(buffer, &isCopy);
    ALOGV("copyData, isCopy=%d", isCopy);
    if (dst == nullptr) {
        jniThrowRuntimeException(env, "Failed to GetByteArrayElements");
        return -1;
    }

    int64_t realSize =
            dvrClient->writeToBuffer(reinterpret_cast<signed char *>(dst) + offset, size);
    env->ReleaseByteArrayElements(buffer, dst, 0);
    return (jlong)realSize;
}

static sp<MediaEvent> getMediaEventSp(JNIEnv *env, jobject mediaEventObj) {
    return (MediaEvent *)env->GetLongField(mediaEventObj, gFields.mediaEventContext);
}

static jobject android_media_tv_Tuner_media_event_get_linear_block(
        JNIEnv* env, jobject mediaEventObj) {
    sp<MediaEvent> mediaEventSp = getMediaEventSp(env, mediaEventObj);
    if (mediaEventSp == nullptr) {
        ALOGD("Failed get MediaEvent");
        return nullptr;
    }
    android::Mutex::Autolock autoLock(mediaEventSp->mLock);

    return mediaEventSp->getLinearBlock();
}

static jobject android_media_tv_Tuner_media_event_get_audio_handle(
        JNIEnv* env, jobject mediaEventObj) {
    sp<MediaEvent> mediaEventSp = getMediaEventSp(env, mediaEventObj);
    if (mediaEventSp == nullptr) {
        ALOGD("Failed get MediaEvent");
        return nullptr;
    }

    android::Mutex::Autolock autoLock(mediaEventSp->mLock);
    int64_t audioHandle = mediaEventSp->getAudioHandle();
    jclass longClazz = env->FindClass("java/lang/Long");
    jmethodID longInit = env->GetMethodID(longClazz, "<init>", "(J)V");

    jobject longObj = env->NewObject(longClazz, longInit, audioHandle);
    return longObj;
}

static void android_media_tv_Tuner_media_event_finalize(JNIEnv* env, jobject mediaEventObj) {
    sp<MediaEvent> mediaEventSp = getMediaEventSp(env, mediaEventObj);
    if (mediaEventSp == nullptr) {
        ALOGD("Failed get MediaEvent");
        return;
    }

    android::Mutex::Autolock autoLock(mediaEventSp->mLock);
    mediaEventSp->mAvHandleRefCnt--;
    mediaEventSp->finalize();

    mediaEventSp->decStrong(mediaEventObj);
}

static const JNINativeMethod gTunerMethods[] = {
    { "nativeInit", "()V", (void *)android_media_tv_Tuner_native_init },
    { "nativeSetup", "()V", (void *)android_media_tv_Tuner_native_setup },
    { "nativeGetTunerVersion", "()I", (void *)android_media_tv_Tuner_native_get_tuner_version },
    { "nativeGetFrontendIds", "()Ljava/util/List;",
            (void *)android_media_tv_Tuner_get_frontend_ids },
    { "nativeOpenFrontendByHandle", "(I)Landroid/media/tv/tuner/Tuner$Frontend;",
            (void *)android_media_tv_Tuner_open_frontend_by_handle },
    { "nativeShareFrontend", "(I)I",
            (void *)android_media_tv_Tuner_share_frontend },
    { "nativeUnshareFrontend", "()I",
            (void *)android_media_tv_Tuner_unshare_frontend },
    { "nativeRegisterFeCbListener", "(J)V",
            (void*)android_media_tv_Tuner_register_fe_cb_listener },
    { "nativeUnregisterFeCbListener", "(J)V",
            (void*)android_media_tv_Tuner_unregister_fe_cb_listener },
    { "nativeUpdateFrontend", "(J)V",
            (void*)android_media_tv_Tuner_update_frontend },
    { "nativeTune", "(ILandroid/media/tv/tuner/frontend/FrontendSettings;)I",
            (void *)android_media_tv_Tuner_tune },
    { "nativeStopTune", "()I", (void *)android_media_tv_Tuner_stop_tune },
    { "nativeScan", "(ILandroid/media/tv/tuner/frontend/FrontendSettings;I)I",
            (void *)android_media_tv_Tuner_scan },
    { "nativeStopScan", "()I", (void *)android_media_tv_Tuner_stop_scan },
    { "nativeSetLnb", "(Landroid/media/tv/tuner/Lnb;)I", (void *)android_media_tv_Tuner_set_lnb },
    { "nativeIsLnaSupported", "()Z", (void *)android_media_tv_Tuner_is_lna_supported },
    { "nativeSetLna", "(Z)I", (void *)android_media_tv_Tuner_set_lna },
    { "nativeGetFrontendStatus", "([I)Landroid/media/tv/tuner/frontend/FrontendStatus;",
            (void *)android_media_tv_Tuner_get_frontend_status },
    { "nativeGetAvSyncHwId", "(Landroid/media/tv/tuner/filter/Filter;)Ljava/lang/Integer;",
            (void *)android_media_tv_Tuner_get_av_sync_hw_id },
    { "nativeGetAvSyncTime", "(I)Ljava/lang/Long;",
            (void *)android_media_tv_Tuner_get_av_sync_time },
    { "nativeConnectCiCam", "(I)I", (void *)android_media_tv_Tuner_connect_cicam },
    { "nativeLinkCiCam", "(I)I",
            (void *)android_media_tv_Tuner_link_cicam },
    { "nativeUnlinkCiCam", "(I)I",
            (void *)android_media_tv_Tuner_unlink_cicam },
    { "nativeDisconnectCiCam", "()I", (void *)android_media_tv_Tuner_disconnect_cicam },
    { "nativeGetFrontendInfo", "(I)Landroid/media/tv/tuner/frontend/FrontendInfo;",
            (void *)android_media_tv_Tuner_get_frontend_info },
    { "nativeOpenFilter", "(IIJ)Landroid/media/tv/tuner/filter/Filter;",
            (void *)android_media_tv_Tuner_open_filter },
    { "nativeOpenTimeFilter", "()Landroid/media/tv/tuner/filter/TimeFilter;",
            (void *)android_media_tv_Tuner_open_time_filter },
    { "nativeOpenLnbByHandle", "(I)Landroid/media/tv/tuner/Lnb;",
            (void *)android_media_tv_Tuner_open_lnb_by_handle },
    { "nativeOpenLnbByName", "(Ljava/lang/String;)Landroid/media/tv/tuner/Lnb;",
            (void *)android_media_tv_Tuner_open_lnb_by_name },
    { "nativeOpenDescramblerByHandle", "(I)Landroid/media/tv/tuner/Descrambler;",
            (void *)android_media_tv_Tuner_open_descrambler },
    { "nativeOpenDvrRecorder", "(J)Landroid/media/tv/tuner/dvr/DvrRecorder;",
            (void *)android_media_tv_Tuner_open_dvr_recorder },
    { "nativeOpenDvrPlayback", "(J)Landroid/media/tv/tuner/dvr/DvrPlayback;",
            (void *)android_media_tv_Tuner_open_dvr_playback },
    { "nativeGetDemuxCapabilities", "()Landroid/media/tv/tuner/DemuxCapabilities;",
            (void *)android_media_tv_Tuner_get_demux_caps },
    { "nativeGetDemuxInfo", "(I)Landroid/media/tv/tuner/DemuxInfo;",
            (void *)android_media_tv_Tuner_get_demux_info },
    { "nativeOpenDemuxByhandle", "(I)I", (void *)android_media_tv_Tuner_open_demux },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_tuner },
    { "nativeCloseFrontend", "(I)I", (void *)android_media_tv_Tuner_close_frontend },
    { "nativeCloseDemux", "(I)I", (void *)android_media_tv_Tuner_close_demux },
    { "nativeOpenSharedFilter",
            "(Ljava/lang/String;)Landroid/media/tv/tuner/filter/SharedFilter;",
            (void *)android_media_tv_Tuner_open_shared_filter},
    { "nativeGetFrontendHardwareInfo","()Ljava/lang/String;",
            (void *)android_media_tv_Tuner_get_frontend_hardware_info },
    { "nativeSetMaxNumberOfFrontends", "(II)I",
             (void *)android_media_tv_Tuner_set_maximum_frontends },
    { "nativeGetMaxNumberOfFrontends", "(I)I",
            (void *)android_media_tv_Tuner_get_maximum_frontends },
    { "nativeRemoveOutputPid", "(I)I",
            (void *)android_media_tv_Tuner_remove_output_pid },
    { "nativeGetFrontendStatusReadiness",
            "([I)[Landroid/media/tv/tuner/frontend/FrontendStatusReadiness;",
            (void *)android_media_tv_Tuner_get_frontend_status_readiness },
};

static const JNINativeMethod gFilterMethods[] = {
    { "nativeConfigureFilter", "(IILandroid/media/tv/tuner/filter/FilterConfiguration;)I",
            (void *)android_media_tv_Tuner_configure_filter},
    { "nativeGetId", "()I", (void *)android_media_tv_Tuner_get_filter_id},
    { "nativeGetId64Bit", "()J", (void *)android_media_tv_Tuner_get_filter_64bit_id},
    { "nativeConfigureMonitorEvent", "(I)I",
            (void *)android_media_tv_Tuner_configure_monitor_event},
    { "nativeSetDataSource", "(Landroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_set_filter_data_source},
    { "nativeStartFilter", "()I", (void *)android_media_tv_Tuner_start_filter},
    { "nativeStopFilter", "()I", (void *)android_media_tv_Tuner_stop_filter},
    { "nativeFlushFilter", "()I", (void *)android_media_tv_Tuner_flush_filter},
    { "nativeRead", "([BJJ)I", (void *)android_media_tv_Tuner_read_filter_fmq},
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_filter},
    { "nativeAcquireSharedFilterToken", "()Ljava/lang/String;",
            (void *)android_media_tv_Tuner_acquire_shared_filter_token},
    { "nativeFreeSharedFilterToken", "(Ljava/lang/String;)V",
            (void *)android_media_tv_Tuner_free_shared_filter_token},
    {"nativeSetTimeDelayHint", "(I)I",
            (void *)android_media_tv_Tuner_set_filter_time_delay_hint},
    {"nativeSetDataSizeDelayHint", "(I)I",
            (void *)android_media_tv_Tuner_set_filter_data_size_delay_hint},
};

static const JNINativeMethod gSharedFilterMethods[] = {
    { "nativeStartSharedFilter", "()I", (void *)android_media_tv_Tuner_start_filter},
    { "nativeStopSharedFilter", "()I", (void *)android_media_tv_Tuner_stop_filter},
    { "nativeFlushSharedFilter", "()I", (void *)android_media_tv_Tuner_flush_filter},
    { "nativeSharedRead", "([BJJ)I", (void *)android_media_tv_Tuner_read_filter_fmq},
    { "nativeSharedClose", "()I", (void *)android_media_tv_Tuner_close_filter},
};

static const JNINativeMethod gTimeFilterMethods[] = {
    { "nativeSetTimestamp", "(J)I", (void *)android_media_tv_Tuner_time_filter_set_timestamp },
    { "nativeClearTimestamp", "()I", (void *)android_media_tv_Tuner_time_filter_clear_timestamp },
    { "nativeGetTimestamp", "()Ljava/lang/Long;",
            (void *)android_media_tv_Tuner_time_filter_get_timestamp },
    { "nativeGetSourceTime", "()Ljava/lang/Long;",
            (void *)android_media_tv_Tuner_time_filter_get_source_time },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_time_filter_close },
};

static const JNINativeMethod gDescramblerMethods[] = {
    { "nativeAddPid", "(IILandroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_descrambler_add_pid },
    { "nativeRemovePid", "(IILandroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_descrambler_remove_pid },
    { "nativeSetKeyToken", "([B)I", (void *)android_media_tv_Tuner_descrambler_set_key_token },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_descrambler },
};

static const JNINativeMethod gDvrRecorderMethods[] = {
    { "nativeAttachFilter", "(Landroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_attach_filter },
    { "nativeDetachFilter", "(Landroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_detach_filter },
    { "nativeConfigureDvr", "(Landroid/media/tv/tuner/dvr/DvrSettings;)I",
            (void *)android_media_tv_Tuner_configure_dvr },
    { "nativeSetStatusCheckIntervalHint", "(J)I",
            (void *)android_media_tv_Tuner_set_status_check_interval_hint},
    { "nativeStartDvr", "()I", (void *)android_media_tv_Tuner_start_dvr },
    { "nativeStopDvr", "()I", (void *)android_media_tv_Tuner_stop_dvr },
    { "nativeFlushDvr", "()I", (void *)android_media_tv_Tuner_flush_dvr },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_dvr },
    { "nativeSetFileDescriptor", "(I)V", (void *)android_media_tv_Tuner_dvr_set_fd },
    { "nativeWrite", "(J)J", (void *)android_media_tv_Tuner_write_dvr },
    { "nativeWrite", "([BJJ)J", (void *)android_media_tv_Tuner_write_dvr_to_array },
};

static const JNINativeMethod gDvrPlaybackMethods[] = {
    { "nativeAttachFilter", "(Landroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_attach_filter},
    { "nativeDetachFilter", "(Landroid/media/tv/tuner/filter/Filter;)I",
            (void *)android_media_tv_Tuner_detach_filter},
    { "nativeConfigureDvr", "(Landroid/media/tv/tuner/dvr/DvrSettings;)I",
            (void *)android_media_tv_Tuner_configure_dvr},
    { "nativeSetStatusCheckIntervalHint", "(J)I",
            (void *)android_media_tv_Tuner_set_status_check_interval_hint},
    { "nativeStartDvr", "()I", (void *)android_media_tv_Tuner_start_dvr},
    { "nativeStopDvr", "()I", (void *)android_media_tv_Tuner_stop_dvr},
    { "nativeFlushDvr", "()I", (void *)android_media_tv_Tuner_flush_dvr},
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_dvr},
    { "nativeSetFileDescriptor", "(I)V", (void *)android_media_tv_Tuner_dvr_set_fd},
    { "nativeRead", "(J)J", (void *)android_media_tv_Tuner_read_dvr},
    { "nativeRead", "([BJJ)J", (void *)android_media_tv_Tuner_read_dvr_from_array},
    { "nativeSeek", "(J)J", (void *)android_media_tv_Tuner_seek_dvr},
};

static const JNINativeMethod gLnbMethods[] = {
    { "nativeSetVoltage", "(I)I", (void *)android_media_tv_Tuner_lnb_set_voltage },
    { "nativeSetTone", "(I)I", (void *)android_media_tv_Tuner_lnb_set_tone },
    { "nativeSetSatellitePosition", "(I)I", (void *)android_media_tv_Tuner_lnb_set_position },
    { "nativeSendDiseqcMessage", "([B)I", (void *)android_media_tv_Tuner_lnb_send_diseqc_msg },
    { "nativeClose", "()I", (void *)android_media_tv_Tuner_close_lnb },
};

static const JNINativeMethod gMediaEventMethods[] = {
    { "nativeGetLinearBlock", "()Landroid/media/MediaCodec$LinearBlock;",
            (void *)android_media_tv_Tuner_media_event_get_linear_block },
    { "nativeGetAudioHandle", "()Ljava/lang/Long;",
            (void *)android_media_tv_Tuner_media_event_get_audio_handle },
    { "nativeFinalize", "()V",
            (void *)android_media_tv_Tuner_media_event_finalize },
};

static bool register_android_media_tv_Tuner(JNIEnv *env) {
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Tuner", gTunerMethods, NELEM(gTunerMethods)) != JNI_OK) {
        ALOGE("Failed to register tuner native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/filter/Filter",
            gFilterMethods,
            NELEM(gFilterMethods)) != JNI_OK) {
        ALOGE("Failed to register filter native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/filter/SharedFilter",
            gSharedFilterMethods,
            NELEM(gSharedFilterMethods)) != JNI_OK) {
        ALOGE("Failed to register shared filter native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/filter/TimeFilter",
            gTimeFilterMethods,
            NELEM(gTimeFilterMethods)) != JNI_OK) {
        ALOGE("Failed to register time filter native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Descrambler",
            gDescramblerMethods,
            NELEM(gDescramblerMethods)) != JNI_OK) {
        ALOGE("Failed to register descrambler native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/dvr/DvrRecorder",
            gDvrRecorderMethods,
            NELEM(gDvrRecorderMethods)) != JNI_OK) {
        ALOGE("Failed to register dvr recorder native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/dvr/DvrPlayback",
            gDvrPlaybackMethods,
            NELEM(gDvrPlaybackMethods)) != JNI_OK) {
        ALOGE("Failed to register dvr playback native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/Lnb",
            gLnbMethods,
            NELEM(gLnbMethods)) != JNI_OK) {
        ALOGE("Failed to register lnb native methods");
        return false;
    }
    if (AndroidRuntime::registerNativeMethods(
            env, "android/media/tv/tuner/filter/MediaEvent",
            gMediaEventMethods,
            NELEM(gMediaEventMethods)) != JNI_OK) {
        ALOGE("Failed to register MediaEvent native methods");
        return false;
    }
    return true;
}

jint JNI_OnLoad(JavaVM *vm, void * /* reserved */) {
    JNIEnv *env = nullptr;
    jint result = -1;

    if (vm->GetEnv((void**) &env, JNI_VERSION_1_4) != JNI_OK) {
        ALOGE("ERROR: GetEnv failed");
        return result;
    }
    assert(env != nullptr);

    if (!register_android_media_tv_Tuner(env)) {
        ALOGE("ERROR: Tuner native registration failed");
        return result;
    }
    return JNI_VERSION_1_4;
}
