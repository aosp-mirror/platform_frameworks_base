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

using std::string;
using std::vector;
using android::base::StringPrintf;

// These constants must be kept in sync with those in StatsDimensionsValue.java
const static int STATS_DIMENSIONS_VALUE_STRING_TYPE = 2;
const static int STATS_DIMENSIONS_VALUE_INT_TYPE = 3;
const static int STATS_DIMENSIONS_VALUE_LONG_TYPE = 4;
// const static int STATS_DIMENSIONS_VALUE_BOOL_TYPE = 5; (commented out because
// unused -- statsd does not correctly support bool types)
const static int STATS_DIMENSIONS_VALUE_FLOAT_TYPE = 6;
const static int STATS_DIMENSIONS_VALUE_TUPLE_TYPE = 7;

/**
 * Recursive helper function that populates a parent StatsDimensionsValueParcel
 * with children StatsDimensionsValueParcels.
 *
 * \param parent parcel that will be populated with children
 * \param childDepth depth of children FieldValues
 * \param childPrefix expected FieldValue prefix of children
 * \param dims vector of FieldValues stored by HashableDimensionKey
 * \param index position in dims to start reading children from
 */
static void populateStatsDimensionsValueParcelChildren(StatsDimensionsValueParcel& parent,
                                                       int childDepth, int childPrefix,
                                                       const vector<FieldValue>& dims,
                                                       size_t& index) {
    if (childDepth > 2) {
        ALOGE("Depth > 2 not supported by StatsDimensionsValueParcel.");
        return;
    }

    while (index < dims.size()) {
        const FieldValue& dim = dims[index];
        int fieldDepth = dim.mField.getDepth();
        int fieldPrefix = dim.mField.getPrefix(childDepth);

        StatsDimensionsValueParcel child;
        child.field = dim.mField.getPosAtDepth(childDepth);

        if (fieldDepth == childDepth && fieldPrefix == childPrefix) {
            switch (dim.mValue.getType()) {
                case INT:
                    child.valueType = STATS_DIMENSIONS_VALUE_INT_TYPE;
                    child.intValue = dim.mValue.int_value;
                    break;
                case LONG:
                    child.valueType = STATS_DIMENSIONS_VALUE_LONG_TYPE;
                    child.longValue = dim.mValue.long_value;
                    break;
                case FLOAT:
                    child.valueType = STATS_DIMENSIONS_VALUE_FLOAT_TYPE;
                    child.floatValue = dim.mValue.float_value;
                    break;
                case STRING:
                    child.valueType = STATS_DIMENSIONS_VALUE_STRING_TYPE;
                    child.stringValue = dim.mValue.str_value;
                    break;
                default:
                    ALOGE("Encountered FieldValue with unsupported value type.");
                    break;
            }
            index++;
            parent.tupleValue.push_back(child);
        } else if (fieldDepth > childDepth && fieldPrefix == childPrefix) {
            // This FieldValue is not a child of the current parent, but it is
            // an indirect descendant. Thus, create a direct child of TUPLE_TYPE
            // and recurse to parcel the indirect descendants.
            child.valueType = STATS_DIMENSIONS_VALUE_TUPLE_TYPE;
            populateStatsDimensionsValueParcelChildren(child, childDepth + 1,
                                                       dim.mField.getPrefix(childDepth + 1), dims,
                                                       index);
            parent.tupleValue.push_back(child);
        } else {
            return;
        }
    }
}

StatsDimensionsValueParcel HashableDimensionKey::toStatsDimensionsValueParcel() const {
    StatsDimensionsValueParcel root;
    if (mValues.size() == 0) {
        return root;
    }

    root.field = mValues[0].mField.getTag();
    root.valueType = STATS_DIMENSIONS_VALUE_TUPLE_TYPE;

    // Children of the root correspond to top-level (depth = 0) FieldValues.
    int childDepth = 0;
    int childPrefix = 0;
    size_t index = 0;
    populateStatsDimensionsValueParcelChildren(root, childDepth, childPrefix, mValues, index);

    return root;
}

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

bool filterValues(const Matcher& matcherField, const vector<FieldValue>& values,
                  FieldValue* output) {
    for (const auto& value : values) {
        if (value.mField.matches(matcherField)) {
            (*output) = value;
            return true;
        }
    }
    return false;
}

bool filterValues(const vector<Matcher>& matcherFields, const vector<FieldValue>& values,
                  HashableDimensionKey* output) {
    size_t num_matches = 0;
    for (const auto& value : values) {
        for (size_t i = 0; i < matcherFields.size(); ++i) {
            const auto& matcher = matcherFields[i];
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
        return;
    }

    for (size_t i = 0; i < count; i++) {
        conditionDimension->mutableValue(i)->mField.setField(
                links.conditionFields[i].mMatcher.getField());
        conditionDimension->mutableValue(i)->mField.setTag(
                links.conditionFields[i].mMatcher.getTag());
    }
}

void getDimensionForState(const std::vector<FieldValue>& eventValues, const Metric2State& link,
                          HashableDimensionKey* statePrimaryKey) {
    // First, get the dimension from the event using the "what" fields from the
    // MetricStateLinks.
    filterValues(link.metricFields, eventValues, statePrimaryKey);

    // Then check that the statePrimaryKey size equals the number of state fields
    size_t count = statePrimaryKey->getValues().size();
    if (count != link.stateFields.size()) {
        return;
    }

    // For each dimension Value in the statePrimaryKey, set the field and tag
    // using the state atom fields from MetricStateLinks.
    for (size_t i = 0; i < count; i++) {
        statePrimaryKey->mutableValue(i)->mField.setField(link.stateFields[i].mMatcher.getField());
        statePrimaryKey->mutableValue(i)->mField.setTag(link.stateFields[i].mMatcher.getTag());
    }
}

bool containsLinkedStateValues(const HashableDimensionKey& whatKey,
                               const HashableDimensionKey& primaryKey,
                               const vector<Metric2State>& stateLinks, const int32_t stateAtomId) {
    if (whatKey.getValues().size() < primaryKey.getValues().size()) {
        ALOGE("Contains linked values false: whatKey is too small");
        return false;
    }

    for (const auto& primaryValue : primaryKey.getValues()) {
        bool found = false;
        for (const auto& whatValue : whatKey.getValues()) {
            if (linked(stateLinks, stateAtomId, primaryValue.mField, whatValue.mField) &&
                primaryValue.mValue == whatValue.mValue) {
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

bool linked(const vector<Metric2State>& stateLinks, const int32_t stateAtomId,
            const Field& stateField, const Field& metricField) {
    for (auto stateLink : stateLinks) {
        if (stateLink.stateAtomId != stateAtomId) {
            continue;
        }

        for (size_t i = 0; i < stateLink.stateFields.size(); i++) {
            if (stateLink.stateFields[i].mMatcher == stateField &&
                stateLink.metricFields[i].mMatcher == metricField) {
                return true;
            }
        }
    }
    return false;
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

bool HashableDimensionKey::operator!=(const HashableDimensionKey& that) const {
    return !((*this) == that);
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
           mStateValuesKey == that.getStateValuesKey();
};

string MetricDimensionKey::toString() const {
    return mDimensionKeyInWhat.toString() + mStateValuesKey.toString();
}

bool MetricDimensionKey::operator<(const MetricDimensionKey& that) const {
    if (mDimensionKeyInWhat < that.getDimensionKeyInWhat()) {
        return true;
    } else if (that.getDimensionKeyInWhat() < mDimensionKeyInWhat) {
        return false;
    }

    return mStateValuesKey < that.getStateValuesKey();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
