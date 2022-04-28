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
#define LOG_TAG "GnssPsdsJni"

#include "GnssPsds.h"

#include "Utils.h"

using android::hardware::hidl_bitfield;
using android::hardware::gnss::PsdsType;
using IGnssPsdsAidl = android::hardware::gnss::IGnssPsds;
using IGnssPsdsHidl = android::hardware::gnss::V1_0::IGnssXtra;

namespace android::gnss {

// Implementation of GnssPsds (AIDL HAL)

GnssPsdsAidl::GnssPsdsAidl(const sp<IGnssPsdsAidl>& iGnssPsds) : mIGnssPsds(iGnssPsds) {
    assert(mIGnssPsds != nullptr);
}

jboolean GnssPsdsAidl::setCallback(const std::unique_ptr<GnssPsdsCallback>& callback) {
    auto status = mIGnssPsds->setCallback(callback->getAidl());
    return checkAidlStatus(status, "IGnssPsdsAidl setCallback() failed.");
}

void GnssPsdsAidl::injectPsdsData(const jbyteArray& data, const jint& length,
                                  const jint& psdsType) {
    JNIEnv* env = getJniEnv();
    jbyte* bytes = reinterpret_cast<jbyte*>(env->GetPrimitiveArrayCritical(data, 0));
    auto status = mIGnssPsds->injectPsdsData(static_cast<PsdsType>(psdsType),
                                             std::vector<uint8_t>((const uint8_t*)bytes,
                                                                  (const uint8_t*)bytes + length));
    checkAidlStatus(status, "IGnssPsdsAidl injectPsdsData() failed.");
    env->ReleasePrimitiveArrayCritical(data, bytes, JNI_ABORT);
}

// Implementation of GnssPsdsHidl

GnssPsdsHidl::GnssPsdsHidl(const sp<android::hardware::gnss::V1_0::IGnssXtra>& iGnssXtra)
      : mIGnssXtra(iGnssXtra) {
    assert(mIGnssXtra != nullptr);
}

jboolean GnssPsdsHidl::setCallback(const std::unique_ptr<GnssPsdsCallback>& callback) {
    auto result = mIGnssXtra->setCallback(callback->getHidl());
    return checkHidlReturn(result, "IGnssPsdsHidl setCallback() failed.");
}

void GnssPsdsHidl::injectPsdsData(const jbyteArray& data, const jint& length, const jint&) {
    JNIEnv* env = getJniEnv();
    jbyte* bytes = reinterpret_cast<jbyte*>(env->GetPrimitiveArrayCritical(data, 0));
    auto result = mIGnssXtra->injectXtraData(std::string((const char*)bytes, length));
    checkHidlReturn(result, "IGnssXtra injectXtraData() failed.");
    env->ReleasePrimitiveArrayCritical(data, bytes, JNI_ABORT);
}

} // namespace android::gnss
