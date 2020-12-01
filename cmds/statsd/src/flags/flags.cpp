/*
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

#include "flags.h"

#include <server_configurable_flags/get_flags.h>

using server_configurable_flags::GetServerConfigurableFlag;
using std::string;

namespace android {
namespace os {
namespace statsd {

string getFlagString(const string& flagName, const string& defaultValue) {
    return GetServerConfigurableFlag(STATSD_NATIVE_NAMESPACE, flagName, defaultValue);
}

bool getFlagBool(const string& flagName, const string& defaultValue) {
    return getFlagString(flagName, defaultValue) == "true";
}
}  // namespace statsd
}  // namespace os
}  // namespace android
