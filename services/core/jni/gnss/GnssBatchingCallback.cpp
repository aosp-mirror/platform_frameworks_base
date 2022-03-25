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

#define LOG_TAG "GnssBatchingCbJni"

#include "GnssBatchingCallback.h"

namespace android::gnss {

namespace {

jmethodID method_reportLocationBatch;

} // anonymous namespace

using android::hardware::hidl_vec;
using binder::Status;
using hardware::Return;

using GnssLocationAidl = android::hardware::gnss::GnssLocation;
using GnssLocation_V1_0 = android::hardware::gnss::V1_0::GnssLocation;
using GnssLocation_V2_0 = android::hardware::gnss::V2_0::GnssLocation;

void GnssBatching_class_init_once(JNIEnv* env, jclass clazz) {
    method_reportLocationBatch =
            env->GetMethodID(clazz, "reportLocationBatch", "([Landroid/location/Location;)V");
}

Status GnssBatchingCallbackAidl::gnssLocationBatchCb(
        const std::vector<android::hardware::gnss::GnssLocation>& locations) {
    GnssBatchingCallbackUtil::gnssLocationBatchCbImpl(hidl_vec<GnssLocationAidl>(locations));
    return Status::ok();
}

Return<void> GnssBatchingCallback_V1_0::gnssLocationBatchCb(
        const hidl_vec<GnssLocation_V1_0>& locations) {
    return GnssBatchingCallbackUtil::gnssLocationBatchCbImpl(locations);
}

Return<void> GnssBatchingCallback_V2_0::gnssLocationBatchCb(
        const hidl_vec<GnssLocation_V2_0>& locations) {
    return GnssBatchingCallbackUtil::gnssLocationBatchCbImpl(locations);
}

} // namespace android::gnss
