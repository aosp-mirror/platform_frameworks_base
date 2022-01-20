/*
 * Copyright (C) 2021 The Android Open Source Project
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
#define LOG_TAG "GnssBatchingJni"

#include "GnssBatching.h"

#include "Utils.h"

using android::hardware::gnss::IGnssBatching;
using IGnssBatching_V1_0 = android::hardware::gnss::V1_0::IGnssBatching;
using IGnssBatching_V2_0 = android::hardware::gnss::V2_0::IGnssBatching;

namespace android::gnss {

// Implementation of GnssBatching (AIDL HAL)

GnssBatching::GnssBatching(const sp<IGnssBatching>& iGnssBatching) : mIGnssBatching(iGnssBatching) {
    assert(mIGnssBatching != nullptr);
}

jboolean GnssBatching::init(const std::unique_ptr<GnssBatchingCallback>& callback) {
    auto status = mIGnssBatching->init(callback->getAidl());
    return checkAidlStatus(status, "IGnssBatchingAidl init() failed.");
}

jint GnssBatching::getBatchSize() {
    int size = 0;
    auto status = mIGnssBatching->getBatchSize(&size);
    if (!checkAidlStatus(status, "IGnssBatchingAidl getBatchSize() failed")) {
        return 0;
    }
    return size;
}

jboolean GnssBatching::start(long periodNanos, float minUpdateDistanceMeters, bool wakeOnFifoFull) {
    IGnssBatching::Options options;
    options.flags = (wakeOnFifoFull) ? IGnssBatching::WAKEUP_ON_FIFO_FULL : 0;
    options.periodNanos = periodNanos;
    options.minDistanceMeters = minUpdateDistanceMeters;
    auto status = mIGnssBatching->start(options);
    return checkAidlStatus(status, "IGnssBatchingAidl start() failed.");
}

jboolean GnssBatching::stop() {
    auto status = mIGnssBatching->stop();
    return checkAidlStatus(status, "IGnssBatchingAidl stop() failed.");
}

jboolean GnssBatching::flush() {
    auto status = mIGnssBatching->flush();
    return checkAidlStatus(status, "IGnssBatchingAidl flush() failed.");
}

jboolean GnssBatching::cleanup() {
    auto status = mIGnssBatching->cleanup();
    return checkAidlStatus(status, "IGnssBatchingAidl cleanup() failed");
}

// Implementation of GnssBatching_V1_0

GnssBatching_V1_0::GnssBatching_V1_0(const sp<IGnssBatching_V1_0>& iGnssBatching)
      : mIGnssBatching_V1_0(iGnssBatching) {
    assert(mIGnssBatching_V1_0 != nullptr);
}

jboolean GnssBatching_V1_0::init(const std::unique_ptr<GnssBatchingCallback>& callback) {
    auto result = mIGnssBatching_V1_0->init(callback->getV1_0());
    return checkHidlReturn(result, "IGnssBatching_V1_0 init() failed.");
}

jint GnssBatching_V1_0::getBatchSize() {
    auto result = mIGnssBatching_V1_0->getBatchSize();
    if (!checkHidlReturn(result, "IGnssBatching getBatchSize() failed.")) {
        return 0; // failure in binder, don't support batching
    }
    return static_cast<jint>(result);
}

jboolean GnssBatching_V1_0::start(long periodNanos, float minUpdateDistanceMeters,
                                  bool wakeOnFifoFull) {
    IGnssBatching_V1_0::Options options;
    options.periodNanos = periodNanos;
    if (minUpdateDistanceMeters > 0) {
        ALOGW("minUpdateDistanceMeters is not supported in 1.0 GNSS HAL.");
    }
    if (wakeOnFifoFull) {
        options.flags = static_cast<uint8_t>(IGnssBatching_V1_0::Flag::WAKEUP_ON_FIFO_FULL);
    } else {
        options.flags = 0;
    }

    auto result = mIGnssBatching_V1_0->start(options);
    return checkHidlReturn(result, "IGnssBatching start() failed.");
}

jboolean GnssBatching_V1_0::stop() {
    auto result = mIGnssBatching_V1_0->stop();
    return checkHidlReturn(result, "IGnssBatching stop() failed.");
}

jboolean GnssBatching_V1_0::flush() {
    auto result = mIGnssBatching_V1_0->flush();
    return checkHidlReturn(result, "IGnssBatching flush() failed.");
}

jboolean GnssBatching_V1_0::cleanup() {
    auto result = mIGnssBatching_V1_0->cleanup();
    return checkHidlReturn(result, "IGnssBatching cleanup() failed.");
}

// Implementation of GnssBatching_V2_0

GnssBatching_V2_0::GnssBatching_V2_0(const sp<IGnssBatching_V2_0>& iGnssBatching)
      : GnssBatching_V1_0{iGnssBatching}, mIGnssBatching_V2_0(iGnssBatching) {
    assert(mIGnssBatching_V2_0 != nullptr);
}

jboolean GnssBatching_V2_0::init(const std::unique_ptr<GnssBatchingCallback>& callback) {
    auto result = mIGnssBatching_V2_0->init_2_0(callback->getV2_0());
    return checkHidlReturn(result, "IGnssBatching_V2_0 init() failed.");
}

} // namespace android::gnss
