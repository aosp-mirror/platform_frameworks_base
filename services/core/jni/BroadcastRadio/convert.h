/**
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef _ANDROID_SERVER_BROADCASTRADIO_CONVERT_H
#define _ANDROID_SERVER_BROADCASTRADIO_CONVERT_H

#include "types.h"

#include "JavaRef.h"

#include <android/hardware/broadcastradio/1.1/types.h>
#include <jni.h>

namespace android {

void register_android_server_broadcastradio_convert(JNIEnv *env);

namespace server {
namespace BroadcastRadio {
namespace convert {

namespace V1_0 = hardware::broadcastradio::V1_0;
namespace V1_1 = hardware::broadcastradio::V1_1;

hardware::hidl_vec<hardware::hidl_string> StringListToHal(JNIEnv *env, jobject jList);

JavaRef<jobject> VendorInfoFromHal(JNIEnv *env, const hardware::hidl_vec<V1_1::VendorKeyValue> &info);
hardware::hidl_vec<V1_1::VendorKeyValue> VendorInfoToHal(JNIEnv *env, jobject jInfo);

JavaRef<jobject> ModulePropertiesFromHal(JNIEnv *env, const V1_0::Properties &properties,
        jint moduleId, const std::string& serviceName);
JavaRef<jobject> ModulePropertiesFromHal(JNIEnv *env, const V1_1::Properties &properties,
        jint moduleId, const std::string& serviceName);

JavaRef<jobject> BandConfigFromHal(JNIEnv *env, const V1_0::BandConfig &config, Region region);
V1_0::BandConfig BandConfigToHal(JNIEnv *env, jobject jConfig, Region &region);

V1_0::Direction DirectionToHal(bool directionDown);

JavaRef<jobject> MetadataFromHal(JNIEnv *env, const hardware::hidl_vec<V1_0::MetaData> &metadata);
JavaRef<jobject> ProgramInfoFromHal(JNIEnv *env, const V1_0::ProgramInfo &info, V1_0::Band band);
JavaRef<jobject> ProgramInfoFromHal(JNIEnv *env, const V1_1::ProgramInfo &info);

V1_1::ProgramSelector ProgramSelectorToHal(JNIEnv *env, jobject jSelector);

void ThrowParcelableRuntimeException(JNIEnv *env, const std::string& msg);

// These three are only for internal use by template functions below.
bool __ThrowIfFailedHidl(JNIEnv *env,
        const hardware::details::return_status &hidlResult);
bool __ThrowIfFailed(JNIEnv *env, const V1_0::Result halResult);
bool __ThrowIfFailed(JNIEnv *env, const V1_1::ProgramListResult halResult);

template <typename T>
bool ThrowIfFailed(JNIEnv *env, const hardware::Return<void> &hidlResult, const T halResult) {
    return __ThrowIfFailedHidl(env, hidlResult) || __ThrowIfFailed(env, halResult);
}

template <typename T>
bool ThrowIfFailed(JNIEnv *env, const hardware::Return<T> &hidlResult) {
    return __ThrowIfFailedHidl(env, hidlResult) || __ThrowIfFailed(env, static_cast<T>(hidlResult));
}

template <>
bool ThrowIfFailed(JNIEnv *env, const hardware::Return<void> &hidlResult);

} // namespace convert
} // namespace BroadcastRadio
} // namespace server
} // namespace android

#endif // _ANDROID_SERVER_BROADCASTRADIO_CONVERT_H
