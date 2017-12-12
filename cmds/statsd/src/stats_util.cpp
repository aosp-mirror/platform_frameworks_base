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

#include "stats_util.h"

namespace android {
namespace os {
namespace statsd {

// There is no existing hash function for the dimension key ("repeated KeyValuePair").
// Temporarily use a string concatenation as the hashable key.
// TODO: Find a better hash function for std::vector<KeyValuePair>.
HashableDimensionKey getHashableKey(std::vector<KeyValuePair> keys) {
    std::string flattened;
    for (const KeyValuePair& pair : keys) {
        flattened += std::to_string(pair.key());
        flattened += ":";
        switch (pair.value_case()) {
            case KeyValuePair::ValueCase::kValueStr:
                flattened += pair.value_str();
                break;
            case KeyValuePair::ValueCase::kValueInt:
                flattened += std::to_string(pair.value_int());
                break;
            case KeyValuePair::ValueCase::kValueLong:
                flattened += std::to_string(pair.value_long());
                break;
            case KeyValuePair::ValueCase::kValueBool:
                flattened += std::to_string(pair.value_bool());
                break;
            case KeyValuePair::ValueCase::kValueFloat:
                flattened += std::to_string(pair.value_float());
                break;
            default:
                break;
        }
        flattened += "|";
    }
    return flattened;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
