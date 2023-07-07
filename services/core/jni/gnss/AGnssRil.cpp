/*
 * Copyright (C) 2022 The Android Open Source Project
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

// Define LOG_TAG before <log/log.h> to overwrite the default value.
#define LOG_TAG "AGnssRilJni"

#include "AGnssRil.h"

#include "Utils.h"

using android::hardware::gnss::IAGnssRil;
using IAGnssRil_V1_0 = android::hardware::gnss::V1_0::IAGnssRil;
using IAGnssRil_V2_0 = android::hardware::gnss::V2_0::IAGnssRil;

namespace android::gnss {

// Implementation of AGnssRil (AIDL HAL)

AGnssRil::AGnssRil(const sp<IAGnssRil>& iAGnssRil) : mIAGnssRil(iAGnssRil) {
    assert(mIAGnssRil != nullptr);
}

jboolean AGnssRil::setCallback(const std::unique_ptr<AGnssRilCallback>& callback) {
    auto status = mIAGnssRil->setCallback(callback->getAidl());
    return checkAidlStatus(status, "IAGnssRilAidl setCallback() failed.");
}

jboolean AGnssRil::setSetId(jint type, const jstring& setid_string) {
    JNIEnv* env = getJniEnv();
    ScopedJniString jniSetId{env, setid_string};
    auto status = mIAGnssRil->setSetId((IAGnssRil::SetIdType)type, jniSetId.c_str());
    return checkAidlStatus(status, "IAGnssRilAidl setSetId() failed.");
}

jboolean AGnssRil::setRefLocation(jint type, jint mcc, jint mnc, jint lac, jlong cid, jint tac,
                                  jint pcid, jint arfcn) {
    IAGnssRil::AGnssRefLocation location;
    location.type = static_cast<IAGnssRil::AGnssRefLocationType>(type);

    switch (location.type) {
        case IAGnssRil::AGnssRefLocationType::GSM_CELLID:
        case IAGnssRil::AGnssRefLocationType::UMTS_CELLID:
        case IAGnssRil::AGnssRefLocationType::LTE_CELLID:
        case IAGnssRil::AGnssRefLocationType::NR_CELLID:
            location.cellID.mcc = static_cast<int>(mcc);
            location.cellID.mnc = static_cast<int>(mnc);
            location.cellID.lac = static_cast<int>(lac);
            location.cellID.cid = static_cast<long>(cid);
            location.cellID.tac = static_cast<int>(tac);
            location.cellID.pcid = static_cast<int>(pcid);
            location.cellID.arfcn = static_cast<int>(arfcn);
            break;
        default:
            ALOGE("Unknown cellid (%s:%d).", __FUNCTION__, __LINE__);
            return JNI_FALSE;
            break;
    }

    auto status = mIAGnssRil->setRefLocation(location);
    return checkAidlStatus(status, "IAGnssRilAidl dataConnClosed() failed.");
}

jboolean AGnssRil::updateNetworkState(jboolean connected, jint type, jboolean roaming,
                                      jboolean available, const jstring& apn, jlong networkHandle,
                                      jshort capabilities) {
    JNIEnv* env = getJniEnv();
    ScopedJniString jniApn{env, apn};
    IAGnssRil::NetworkAttributes networkAttributes;
    networkAttributes.networkHandle = static_cast<int64_t>(networkHandle),
    networkAttributes.isConnected = static_cast<bool>(connected),
    networkAttributes.capabilities = static_cast<int32_t>(capabilities),
    networkAttributes.apn = jniApn.c_str();

    auto status = mIAGnssRil->updateNetworkState(networkAttributes);
    return checkAidlStatus(status, "IAGnssRilAidl updateNetworkState() failed.");
}

jboolean AGnssRil::injectNiSuplMessageData(const jbyteArray& msgData, jint length, jint slotIndex) {
    if (mIAGnssRil->getInterfaceVersion() <= 2) {
        ALOGE("IAGnssRil does not support injectNiSuplMessageData().");
        return JNI_FALSE;
    }
    JNIEnv* env = getJniEnv();
    jbyte* bytes = reinterpret_cast<jbyte*>(env->GetPrimitiveArrayCritical(msgData, 0));
    auto status = mIAGnssRil->injectNiSuplMessageData(std::vector<uint8_t>((const uint8_t*)bytes,
                                                                           (const uint8_t*)bytes +
                                                                                   length),
                                                      static_cast<int>(slotIndex));
    env->ReleasePrimitiveArrayCritical(msgData, bytes, JNI_ABORT);
    return checkAidlStatus(status, "IAGnssRil injectNiSuplMessageData() failed.");
}

// Implementation of AGnssRil_V1_0

AGnssRil_V1_0::AGnssRil_V1_0(const sp<IAGnssRil_V1_0>& iAGnssRil) : mAGnssRil_V1_0(iAGnssRil) {
    assert(mIAGnssRil_V1_0 != nullptr);
}

jboolean AGnssRil_V1_0::setCallback(const std::unique_ptr<AGnssRilCallback>& callback) {
    auto result = mAGnssRil_V1_0->setCallback(callback->getV1_0());
    return checkHidlReturn(result, "IAGnssRil_V1_0 setCallback() failed.");
}

jboolean AGnssRil_V1_0::setSetId(jint type, const jstring& setid_string) {
    JNIEnv* env = getJniEnv();
    ScopedJniString jniSetId{env, setid_string};
    auto result = mAGnssRil_V1_0->setSetId((IAGnssRil_V1_0::SetIDType)type, jniSetId);
    return checkHidlReturn(result, "IAGnssRil_V1_0 setSetId() failed.");
}

jboolean AGnssRil_V1_0::setRefLocation(jint type, jint mcc, jint mnc, jint lac, jlong cid, jint tac,
                                       jint pcid, jint) {
    IAGnssRil_V1_0::AGnssRefLocation location;
    location.type = static_cast<IAGnssRil_V1_0::AGnssRefLocationType>(type);

    switch (location.type) {
        case IAGnssRil_V1_0::AGnssRefLocationType::GSM_CELLID:
        case IAGnssRil_V1_0::AGnssRefLocationType::UMTS_CELLID:
        case IAGnssRil_V1_0::AGnssRefLocationType::LTE_CELLID:
            location.cellID.mcc = static_cast<uint16_t>(mcc);
            location.cellID.mnc = static_cast<uint16_t>(mnc);
            location.cellID.lac = static_cast<uint16_t>(lac);
            location.cellID.cid = static_cast<uint32_t>(cid);
            location.cellID.tac = static_cast<uint16_t>(tac);
            location.cellID.pcid = static_cast<uint16_t>(pcid);
            break;
        default:
            ALOGE("Unknown cellid (%s:%d).", __FUNCTION__, __LINE__);
            return JNI_FALSE;
            break;
    }

    auto result = mAGnssRil_V1_0->setRefLocation(location);
    return checkHidlReturn(result, "IAGnssRil_V1_0 setRefLocation() failed.");
}

jboolean AGnssRil_V1_0::updateNetworkState(jboolean connected, jint type, jboolean roaming,
                                           jboolean available, const jstring& apn,
                                           jlong networkHandle, jshort capabilities) {
    JNIEnv* env = getJniEnv();
    ScopedJniString jniApn{env, apn};
    hardware::hidl_string hidlApn{jniApn};
    hardware::Return<bool> result(false);

    if (!hidlApn.empty()) {
        result = mAGnssRil_V1_0->updateNetworkAvailability(available, hidlApn);
        checkHidlReturn(result, "IAGnssRil_V1_0 updateNetworkAvailability() failed.");
    }

    result = mAGnssRil_V1_0->updateNetworkState(connected,
                                                static_cast<IAGnssRil_V1_0::NetworkType>(type),
                                                roaming);
    return checkHidlReturn(result, "IAGnssRil_V1_0 updateNetworkState() failed.");
}

jboolean AGnssRil_V1_0::injectNiSuplMessageData(const jbyteArray&, jint, jint) {
    ALOGI("IAGnssRil_V1_0 interface does not support injectNiSuplMessageData.");
    return JNI_FALSE;
}

// Implementation of AGnssRil_V2_0

AGnssRil_V2_0::AGnssRil_V2_0(const sp<IAGnssRil_V2_0>& iAGnssRil)
      : AGnssRil_V1_0{iAGnssRil}, mAGnssRil_V2_0(iAGnssRil) {
    assert(mIAGnssRil_V2_0 != nullptr);
}

jboolean AGnssRil_V2_0::updateNetworkState(jboolean connected, jint type, jboolean roaming,
                                           jboolean available, const jstring& apn,
                                           jlong networkHandle, jshort capabilities) {
    JNIEnv* env = getJniEnv();
    ScopedJniString jniApn{env, apn};
    IAGnssRil_V2_0::NetworkAttributes networkAttributes =
            {.networkHandle = static_cast<uint64_t>(networkHandle),
             .isConnected = static_cast<bool>(connected),
             .capabilities = static_cast<uint16_t>(capabilities),
             .apn = jniApn.c_str()};

    auto result = mAGnssRil_V2_0->updateNetworkState_2_0(networkAttributes);
    return checkHidlReturn(result, "AGnssRil_V2_0 updateNetworkState_2_0() failed.");
}

} // namespace android::gnss
