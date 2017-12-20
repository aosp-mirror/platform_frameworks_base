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
#include "HashableDimensionKey.h"

namespace android {
namespace os {
namespace statsd {

using std::string;

string HashableDimensionKey::toString() const {
    string flattened;
    for (const auto& pair : mKeyValuePairs) {
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

bool HashableDimensionKey::operator==(const HashableDimensionKey& that) const {
    const auto& keyValue2 = that.getKeyValuePairs();
    if (mKeyValuePairs.size() != keyValue2.size()) {
        return false;
    }

    for (size_t i = 0; i < keyValue2.size(); i++) {
        const auto& kv1 = mKeyValuePairs[i];
        const auto& kv2 = keyValue2[i];
        if (kv1.key() != kv2.key()) {
            return false;
        }

        if (kv1.value_case() != kv2.value_case()) {
            return false;
        }

        switch (kv1.value_case()) {
            case KeyValuePair::ValueCase::kValueStr:
                if (kv1.value_str() != kv2.value_str()) {
                    return false;
                }
                break;
            case KeyValuePair::ValueCase::kValueInt:
                if (kv1.value_int() != kv2.value_int()) {
                    return false;
                }
                break;
            case KeyValuePair::ValueCase::kValueLong:
                if (kv1.value_long() != kv2.value_long()) {
                    return false;
                }
                break;
            case KeyValuePair::ValueCase::kValueBool:
                if (kv1.value_bool() != kv2.value_bool()) {
                    return false;
                }
                break;
            case KeyValuePair::ValueCase::kValueFloat: {
                if (kv1.value_float() != kv2.value_float()) {
                    return false;
                }
                break;
            }
            case KeyValuePair::ValueCase::VALUE_NOT_SET:
                break;
        }
    }
    return true;
};

bool HashableDimensionKey::operator<(const HashableDimensionKey& that) const {
    return toString().compare(that.toString()) < 0;
};

}  // namespace statsd
}  // namespace os
}  // namespace android