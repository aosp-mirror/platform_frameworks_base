/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _ANDROID_MEDIA_DESCRAMBLER_H_
#define _ANDROID_MEDIA_DESCRAMBLER_H_

#include "jni.h"

#include <utils/RefBase.h>

namespace android {

namespace hardware {
namespace cas {
namespace native {
namespace V1_0 {
struct IDescrambler;
}}}}
using hardware::cas::native::V1_0::IDescrambler;

sp<IDescrambler> GetDescrambler(JNIEnv *env, jobject obj);

}  // namespace android

#endif  // _ANDROID_MEDIA_DESCRAMBLER_H_
