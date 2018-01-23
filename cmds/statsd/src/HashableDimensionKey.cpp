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
#include "dimension.h"

namespace android {
namespace os {
namespace statsd {

android::hash_t hashDimensionsValue(int64_t seed, const DimensionsValue& value) {
    android::hash_t hash = seed;
    hash = android::JenkinsHashMix(hash, android::hash_type(value.field()));

    hash = android::JenkinsHashMix(hash, android::hash_type((int)value.value_case()));
    switch (value.value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
            hash = android::JenkinsHashMix(
                    hash,
                    static_cast<uint32_t>(std::hash<std::string>()(value.value_str())));
            break;
        case DimensionsValue::ValueCase::kValueInt:
            hash = android::JenkinsHashMix(hash, android::hash_type(value.value_int()));
            break;
        case DimensionsValue::ValueCase::kValueLong:
            hash = android::JenkinsHashMix(
                    hash, android::hash_type(static_cast<int64_t>(value.value_long())));
            break;
        case DimensionsValue::ValueCase::kValueBool:
            hash = android::JenkinsHashMix(hash, android::hash_type(value.value_bool()));
            break;
        case DimensionsValue::ValueCase::kValueFloat: {
            float floatVal = value.value_float();
            hash = android::JenkinsHashMixBytes(hash, (uint8_t*)&floatVal, sizeof(float));
            break;
        }
        case DimensionsValue::ValueCase::kValueTuple: {
            hash = android::JenkinsHashMix(hash, android::hash_type(
                value.value_tuple().dimensions_value_size()));
            for (int i = 0; i < value.value_tuple().dimensions_value_size(); ++i) {
                hash = android::JenkinsHashMix(
                    hash,
                    hashDimensionsValue(value.value_tuple().dimensions_value(i)));
            }
            break;
        }
        case DimensionsValue::ValueCase::VALUE_NOT_SET:
            break;
    }
    return JenkinsHashWhiten(hash);
}

android::hash_t hashDimensionsValue(const DimensionsValue& value) {
    return hashDimensionsValue(0, value);
}

android::hash_t hashMetricDimensionKey(int64_t seed, const MetricDimensionKey& dimensionKey) {
    android::hash_t hash = seed;
    hash = android::JenkinsHashMix(hash, std::hash<MetricDimensionKey>{}(dimensionKey));
    return JenkinsHashWhiten(hash);
}

using std::string;

string HashableDimensionKey::toString() const {
    return DimensionsValueToString(getDimensionsValue());
}

bool EqualsTo(const DimensionsValue& s1, const DimensionsValue& s2) {
    if (s1.field() != s2.field()) {
        return false;
    }
    if (s1.value_case() != s2.value_case()) {
        return false;
    }
    switch (s1.value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
            return (s1.value_str() == s2.value_str());
        case DimensionsValue::ValueCase::kValueInt:
            return s1.value_int() == s2.value_int();
        case DimensionsValue::ValueCase::kValueLong:
            return s1.value_long() == s2.value_long();
        case DimensionsValue::ValueCase::kValueBool:
            return s1.value_bool() == s2.value_bool();
        case DimensionsValue::ValueCase::kValueFloat:
            return s1.value_float() == s2.value_float();
        case DimensionsValue::ValueCase::kValueTuple:
            {
                if (s1.value_tuple().dimensions_value_size() !=
                        s2.value_tuple().dimensions_value_size()) {
                    return false;
                }
                bool allMatched = true;
                for (int i = 0; allMatched && i < s1.value_tuple().dimensions_value_size(); ++i) {
                    allMatched &= EqualsTo(s1.value_tuple().dimensions_value(i),
                                           s2.value_tuple().dimensions_value(i));
                }
                return allMatched;
            }
        case DimensionsValue::ValueCase::VALUE_NOT_SET:
        default:
            return true;
    }
}

bool LessThan(const DimensionsValue& s1, const DimensionsValue& s2) {
    if (s1.field() != s2.field()) {
        return s1.field() < s2.field();
    }
    if (s1.value_case() != s2.value_case()) {
        return s1.value_case() < s2.value_case();
    }
    switch (s1.value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
            return s1.value_str() < s2.value_str();
        case DimensionsValue::ValueCase::kValueInt:
            return s1.value_int() < s2.value_int();
        case DimensionsValue::ValueCase::kValueLong:
            return s1.value_long() < s2.value_long();
        case DimensionsValue::ValueCase::kValueBool:
            return (int)s1.value_bool() < (int)s2.value_bool();
        case DimensionsValue::ValueCase::kValueFloat:
            return s1.value_float() < s2.value_float();
        case DimensionsValue::ValueCase::kValueTuple:
            {
                if (s1.value_tuple().dimensions_value_size() !=
                        s2.value_tuple().dimensions_value_size()) {
                    return s1.value_tuple().dimensions_value_size() <
                        s2.value_tuple().dimensions_value_size();
                }
                for (int i = 0;  i < s1.value_tuple().dimensions_value_size(); ++i) {
                    if (EqualsTo(s1.value_tuple().dimensions_value(i),
                                 s2.value_tuple().dimensions_value(i))) {
                        continue;
                    } else {
                        return LessThan(s1.value_tuple().dimensions_value(i),
                                        s2.value_tuple().dimensions_value(i));
                    }
                }
                return false;
            }
        case DimensionsValue::ValueCase::VALUE_NOT_SET:
        default:
            return false;
    }
}

bool HashableDimensionKey::operator==(const HashableDimensionKey& that) const {
    return EqualsTo(getDimensionsValue(), that.getDimensionsValue());
};

bool HashableDimensionKey::operator<(const HashableDimensionKey& that) const {
    return LessThan(getDimensionsValue(), that.getDimensionsValue());
};

string MetricDimensionKey::toString() const {
    string flattened = mDimensionKeyInWhat.toString();
    flattened += mDimensionKeyInCondition.toString();
    return flattened;
}

bool MetricDimensionKey::operator==(const MetricDimensionKey& that) const {
    return mDimensionKeyInWhat == that.getDimensionKeyInWhat() &&
        mDimensionKeyInCondition == that.getDimensionKeyInCondition();
};

bool MetricDimensionKey::operator<(const MetricDimensionKey& that) const {
    return toString().compare(that.toString()) < 0;
};


}  // namespace statsd
}  // namespace os
}  // namespace android