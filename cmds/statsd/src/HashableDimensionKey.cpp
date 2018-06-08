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
#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include <mutex>

#include "HashableDimensionKey.h"
#include "FieldValue.h"

namespace android {
namespace os {
namespace statsd {

using std::string;
using std::vector;

android::hash_t hashDimension(const HashableDimensionKey& value) {
    android::hash_t hash = 0;
    for (const auto& fieldValue : value.getValues()) {
        hash = android::JenkinsHashMix(hash, android::hash_type((int)fieldValue.mField.getField()));
        hash = android::JenkinsHashMix(hash, android::hash_type((int)fieldValue.mField.getTag()));
        hash = android::JenkinsHashMix(hash, android::hash_type((int)fieldValue.mValue.getType()));
        switch (fieldValue.mValue.getType()) {
            case INT:
                hash = android::JenkinsHashMix(hash,
                                               android::hash_type(fieldValue.mValue.int_value));
                break;
            case LONG:
                hash = android::JenkinsHashMix(hash,
                                               android::hash_type(fieldValue.mValue.long_value));
                break;
            case STRING:
                hash = android::JenkinsHashMix(hash, static_cast<uint32_t>(std::hash<std::string>()(
                                                             fieldValue.mValue.str_value)));
                break;
            case FLOAT: {
                hash = android::JenkinsHashMix(hash,
                                               android::hash_type(fieldValue.mValue.float_value));
                break;
            }
            default:
                break;
        }
    }
    return JenkinsHashWhiten(hash);
}

bool filterValues(const vector<Matcher>& matcherFields, const vector<FieldValue>& values,
                  HashableDimensionKey* output) {
    size_t num_matches = 0;
    for (const auto& value : values) {
        for (size_t i = 0; i < matcherFields.size(); ++i) {
            const auto& matcher = matcherFields[i];
            // TODO: potential optimization here to break early because all fields are naturally
            // sorted.
            if (value.mField.matches(matcher)) {
                output->addValue(value);
                output->mutableValue(num_matches)->mField.setTag(value.mField.getTag());
                output->mutableValue(num_matches)->mField.setField(
                    value.mField.getField() & matcher.mMask);
                num_matches++;
            }
        }
    }
    return num_matches > 0;
}

void filterGaugeValues(const std::vector<Matcher>& matcherFields,
                       const std::vector<FieldValue>& values, std::vector<FieldValue>* output) {
    for (const auto& field : matcherFields) {
        for (const auto& value : values) {
            if (value.mField.matches(field)) {
                output->push_back(value);
            }
        }
    }
}

void getDimensionForCondition(const std::vector<FieldValue>& eventValues,
                              const Metric2Condition& links,
                              HashableDimensionKey* conditionDimension) {
    // Get the dimension first by using dimension from what.
    filterValues(links.metricFields, eventValues, conditionDimension);

    size_t count = conditionDimension->getValues().size();
    if (count != links.conditionFields.size()) {
        // ALOGE("WTF condition link is bad");
        return;
    }

    for (size_t i = 0; i < count; i++) {
        conditionDimension->mutableValue(i)->mField.setField(
            links.conditionFields[i].mMatcher.getField());
        conditionDimension->mutableValue(i)->mField.setTag(
            links.conditionFields[i].mMatcher.getTag());
    }
}

bool LessThan(const vector<FieldValue>& s1, const vector<FieldValue>& s2) {
    if (s1.size() != s2.size()) {
        return s1.size() < s2.size();
    }

    size_t count = s1.size();
    for (size_t i = 0; i < count; i++) {
        if (s1[i] != s2[i]) {
            return s1[i] < s2[i];
        }
    }
    return false;
}

bool HashableDimensionKey::operator==(const HashableDimensionKey& that) const {
    if (mValues.size() != that.getValues().size()) {
        return false;
    }
    size_t count = mValues.size();
    for (size_t i = 0; i < count; i++) {
        if (mValues[i] != (that.getValues())[i]) {
            return false;
        }
    }
    return true;
};

bool HashableDimensionKey::operator<(const HashableDimensionKey& that) const {
    return LessThan(getValues(), that.getValues());
};

bool HashableDimensionKey::contains(const HashableDimensionKey& that) const {
    if (mValues.size() < that.getValues().size()) {
        return false;
    }

    if (mValues.size() == that.getValues().size()) {
        return (*this) == that;
    }

    for (const auto& value : that.getValues()) {
        bool found = false;
        for (const auto& myValue : mValues) {
            if (value.mField == myValue.mField && value.mValue == myValue.mValue) {
                found = true;
                break;
            }
        }
        if (!found) {
            return false;
        }
    }

    return true;
}

string HashableDimensionKey::toString() const {
    std::string output;
    for (const auto& value : mValues) {
        output += StringPrintf("(%d)%#x->%s ", value.mField.getTag(), value.mField.getField(),
                               value.mValue.toString().c_str());
    }
    return output;
}

bool MetricDimensionKey::operator==(const MetricDimensionKey& that) const {
    return mDimensionKeyInWhat == that.getDimensionKeyInWhat() &&
           mDimensionKeyInCondition == that.getDimensionKeyInCondition();
};

string MetricDimensionKey::toString() const {
    return mDimensionKeyInWhat.toString() + mDimensionKeyInCondition.toString();
}

bool MetricDimensionKey::operator<(const MetricDimensionKey& that) const {
    if (mDimensionKeyInWhat < that.getDimensionKeyInWhat()) {
        return true;
    } else if (that.getDimensionKeyInWhat() < mDimensionKeyInWhat) {
        return false;
    }

    return mDimensionKeyInCondition < that.getDimensionKeyInCondition();
}

}  // namespace statsd
}  // namespace os
}  // namespace android