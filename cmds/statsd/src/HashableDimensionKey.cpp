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

#include "HashableDimensionKey.h"
#include "FieldValue.h"

namespace android {
namespace os {
namespace statsd {
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
                float floatVal = fieldValue.mValue.float_value;
                hash = android::JenkinsHashMixBytes(hash, (uint8_t*)&floatVal, sizeof(float));
                break;
            }
        }
    }
    return JenkinsHashWhiten(hash);
}

// Filter fields using the matchers and output the results as a HashableDimensionKey.
// Note: HashableDimensionKey is just a wrapper for vector<FieldValue>
bool filterValues(const vector<Matcher>& matcherFields, const vector<FieldValue>& values,
                  vector<HashableDimensionKey>* output) {
    output->push_back(HashableDimensionKey());
    // Top level is only tag id. Now take the real child matchers
    int prevAnyMatcherPrefix = 0;
    size_t prevPrevFanout = 0;
    size_t prevFanout = 0;
    // For each matcher get matched results.
    for (const auto& matcher : matcherFields) {
        vector<FieldValue> matchedResults;
        for (const auto& value : values) {
            // TODO: potential optimization here to break early because all fields are naturally
            // sorted.
            if (value.mField.matches(matcher)) {
                matchedResults.push_back(FieldValue(
                        Field(value.mField.getTag(), (value.mField.getField() & matcher.mMask)),
                        value.mValue));
            }
        }

        if (matchedResults.size() == 0) {
            VLOG("We can't find a dimension value for matcher (%d)%#x.", matcher.mMatcher.getTag(),
                   matcher.mMatcher.getField());
            continue;
        }

        if (matchedResults.size() == 1) {
            for (auto& dimension : *output) {
                dimension.addValue(matchedResults[0]);
            }
            prevAnyMatcherPrefix = 0;
            prevFanout = 0;
            continue;
        }

        // All the complexity below is because we support ANY in dimension.
        bool createFanout = true;
        // createFanout is true when the matcher doesn't need to follow the prev matcher's
        // order.
        // e.g., get (uid, tag) from any position in attribution. because we have translated
        // it as 2 matchers, they need to follow the same ordering, we can't create a cross
        // product of all uid and tags.
        // However, if the 2 matchers have different prefix, they will create a cross product
        // e.g., [any uid] [any some other repeated field], we will create a cross product for them
        if (prevAnyMatcherPrefix != 0) {
            int anyMatcherPrefix = 0;
            bool isAnyMatcher = matcher.hasAnyPositionMatcher(&anyMatcherPrefix);
            if (isAnyMatcher && anyMatcherPrefix == prevAnyMatcherPrefix) {
                createFanout = false;
            } else {
                prevAnyMatcherPrefix = anyMatcherPrefix;
            }
        }

        // Each matcher should match exact one field, unless position is ANY
        // When x number of fields matches a matcher, the returned dimension
        // size is multiplied by x.
        int oldSize;
        if (createFanout) {
            // First create fanout (fanout size is matchedResults.Size which could be one,
            // which means we do nothing here)
            oldSize = output->size();
            for (size_t i = 1; i < matchedResults.size(); i++) {
                output->insert(output->end(), output->begin(), output->begin() + oldSize);
            }
            prevPrevFanout = oldSize;
            prevFanout = matchedResults.size();
        } else {
            // If we should not create fanout, e.g., uid tag from same position should be remain
            // together.
            oldSize = prevPrevFanout;
            if (prevFanout != matchedResults.size()) {
                // sanity check.
                ALOGE("2 Any matcher result in different output");
                return false;
            }
        }
        // now add the matched field value to output
        for (size_t i = 0; i < matchedResults.size(); i++) {
            for (int j = 0; j < oldSize; j++) {
                (*output)[i * oldSize + j].addValue(matchedResults[i]);
            }
        }
    }

    return output->size() > 0 && (*output)[0].getValues().size() > 0;
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

void getDimensionForCondition(const LogEvent& event, Metric2Condition links,
                              vector<HashableDimensionKey>* conditionDimension) {
    // Get the dimension first by using dimension from what.
    filterValues(links.metricFields, event.getValues(), conditionDimension);

    // Then replace the field with the dimension from condition.
    for (auto& dim : *conditionDimension) {
        size_t count = dim.getValues().size();
        if (count != links.conditionFields.size()) {
            // ALOGE("WTF condition link is bad");
            return;
        }

        for (size_t i = 0; i < count; i++) {
            dim.mutableValue(i)->mField.setField(links.conditionFields[i].mMatcher.getField());
            dim.mutableValue(i)->mField.setTag(links.conditionFields[i].mMatcher.getTag());
        }
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