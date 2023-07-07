/*
 * Copyright 2022 The Android Open Source Project
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

#pragma once

// Include all HIDL related files/names here.

#include <android/hardware/tv/input/1.0/ITvInput.h>
#include <android/hardware/tv/input/1.0/ITvInputCallback.h>
#include <android/hardware/tv/input/1.0/types.h>

using ::android::hardware::hidl_vec;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::tv::input::V1_0::Result;

using HidlAudioDevice = ::android::hardware::audio::common::V2_0::AudioDevice;
using HidlITvInput = ::android::hardware::tv::input::V1_0::ITvInput;
using HidlITvInputCallback = ::android::hardware::tv::input::V1_0::ITvInputCallback;
using HidlTvInputDeviceInfo = ::android::hardware::tv::input::V1_0::TvInputDeviceInfo;
using HidlTvInputEvent = ::android::hardware::tv::input::V1_0::TvInputEvent;
using HidlTvStreamConfig = ::android::hardware::tv::input::V1_0::TvStreamConfig;
