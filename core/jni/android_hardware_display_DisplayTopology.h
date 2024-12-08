/*
 * Copyright 2024 The Android Open Source Project
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

#include <input/DisplayTopologyGraph.h>

#include "jni.h"

namespace android {

/**
 * Copies the contents of a DVM DisplayTopology object to a new native DisplayTopology instance.
 * Returns DisplayTopology.
 */
extern DisplayTopologyGraph android_hardware_display_DisplayTopologyGraph_toNative(
        JNIEnv* env, jobject eventObj);

} // namespace android
