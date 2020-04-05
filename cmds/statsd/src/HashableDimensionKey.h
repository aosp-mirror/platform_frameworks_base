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

#pragma once

#include <aidl/android/os/StatsDimensionsValueParcel.h>
#include <utils/JenkinsHash.h>
#include <vector>
#include "android-base/stringprintf.h"
#include "FieldValue.h"
#include "logd/LogEvent.h"

namespace android {
namespace os {
namespace statsd {

using ::aidl::android::os::StatsDimensionsValueParcel;

struct Metric2Condition {
    int64_t conditionId;
    std::vector<Matcher> metricFields;
    std::vector<Matcher> conditionFields;
};

struct Metric2State {
    int32_t stateAtomId;
    std::vector<Matcher> metricFields;
    std::vector<Matcher> stateFields;
};

class HashableDimensionKey {
public:
    explicit HashableDimensionKey(const std::vector<FieldValue>& values) {
        mValues = values;
    }

    HashableDimensionKey() {};

    HashableDimensionKey(const HashableDimensionKey& that) : mValues(that.getValues()){};

    inline void addValue(const FieldValue& value) {
        mValues.push_back(value);
    }

    inline const std::vector<FieldValue>& getValues() const {
        return mValues;
    }

    inline std::vector<FieldValue>* mutableValues() {
        return &mValues;
    }

    inline FieldValue* mutableValue(size_t i) {
        if (i >= 0 && i < mValues.size()) {
            return &(mValues[i]);
        }
        return nullptr;
    }

    StatsDimensionsValueParcel toStatsDimensionsValueParcel() const;

    std::string toString() const;

    bool operator!=(const HashableDimensionKey& that) const;

    bool operator==(const HashableDimensionKey& that) const;

    bool operator<(const HashableDimensionKey& that) const;

    bool contains(const HashableDimensionKey& that) const;

private:
    std::vector<FieldValue> mValues;
};

class MetricDimensionKey {
public:
    explicit MetricDimensionKey(const HashableDimensionKey& dimensionKeyInWhat,
                                const HashableDimensionKey& stateValuesKey)
        : mDimensionKeyInWhat(dimensionKeyInWhat), mStateValuesKey(stateValuesKey){};

    MetricDimensionKey(){};

    MetricDimensionKey(const MetricDimensionKey& that)
        : mDimensionKeyInWhat(that.getDimensionKeyInWhat()),
          mStateValuesKey(that.getStateValuesKey()){};

    MetricDimensionKey& operator=(const MetricDimensionKey& from) = default;

    std::string toString() const;

    inline const HashableDimensionKey& getDimensionKeyInWhat() const {
        return mDimensionKeyInWhat;
    }

    inline const HashableDimensionKey& getStateValuesKey() const {
        return mStateValuesKey;
    }

    inline HashableDimensionKey* getMutableStateValuesKey() {
        return &mStateValuesKey;
    }

    inline void setStateValuesKey(const HashableDimensionKey& key) {
        mStateValuesKey = key;
    }

    bool hasStateValuesKey() const {
        return mStateValuesKey.getValues().size() > 0;
    }

    bool operator==(const MetricDimensionKey& that) const;

    bool operator<(const MetricDimensionKey& that) const;

private:
    HashableDimensionKey mDimensionKeyInWhat;
    HashableDimensionKey mStateValuesKey;
};

android::hash_t hashDimension(const HashableDimensionKey& key);

/**
 * Returns true if a FieldValue field matches the matcher field.
 * The value of the FieldValue is output.
 */
bool filterValues(const Matcher& matcherField, const std::vector<FieldValue>& values,
                  FieldValue* output);

/**
 * Creating HashableDimensionKeys from FieldValues using matcher.
 *
 * This function may make modifications to the Field if the matcher has Position=FIRST,LAST or ALL
 * in it. This is because: for example, when we create dimension from last uid in attribution chain,
 * In one event, uid 1000 is at position 5 and it's the last
 * In another event, uid 1000 is at position 6, and it's the last
 * these 2 events should be mapped to the same dimension.  So we will remove the original position
 * from the dimension key for the uid field (by applying 0x80 bit mask).
 */
bool filterValues(const std::vector<Matcher>& matcherFields, const std::vector<FieldValue>& values,
                  HashableDimensionKey* output);

/**
 * Filter the values from FieldValues using the matchers.
 *
 * In contrast to the above function, this function will not do any modification to the original
 * data. Considering it as taking a snapshot on the atom event.
 */
void filterGaugeValues(const std::vector<Matcher>& matchers, const std::vector<FieldValue>& values,
                       std::vector<FieldValue>* output);

void getDimensionForCondition(const std::vector<FieldValue>& eventValues,
                              const Metric2Condition& links,
                              HashableDimensionKey* conditionDimension);

/**
 * Get dimension values using metric's "what" fields and fill statePrimaryKey's
 * mField information using "state" fields.
 */
void getDimensionForState(const std::vector<FieldValue>& eventValues, const Metric2State& link,
                          HashableDimensionKey* statePrimaryKey);

/**
 * Returns true if the primaryKey values are a subset of the whatKey values.
 * The values from the primaryKey come from the state atom, so we need to
 * check that a link exists between the state atom field and what atom field.
 *
 * Example:
 * whatKey = [Atom: 10, {uid: 1005, wakelock_name: "compose"}]
 * statePrimaryKey = [Atom: 27, {uid: 1005}]
 * Returns true IF one of the Metric2State links Atom 10's uid to Atom 27's uid
 *
 * Example:
 * whatKey = [Atom: 10, {uid: 1005, wakelock_name: "compose"}]
 * statePrimaryKey = [Atom: 59, {uid: 1005, package_name: "system"}]
 * Returns false
 */
bool containsLinkedStateValues(const HashableDimensionKey& whatKey,
                               const HashableDimensionKey& primaryKey,
                               const std::vector<Metric2State>& stateLinks,
                               const int32_t stateAtomId);

/**
 * Returns true if there is a Metric2State link that links the stateField and
 * the metricField (they are equal fields from different atoms).
 */
bool linked(const std::vector<Metric2State>& stateLinks, const int32_t stateAtomId,
            const Field& stateField, const Field& metricField);
}  // namespace statsd
}  // namespace os
}  // namespace android

namespace std {

using android::os::statsd::HashableDimensionKey;
using android::os::statsd::MetricDimensionKey;

template <>
struct hash<HashableDimensionKey> {
    std::size_t operator()(const HashableDimensionKey& key) const {
        return hashDimension(key);
    }
};

template <>
struct hash<MetricDimensionKey> {
    std::size_t operator()(const MetricDimensionKey& key) const {
        android::hash_t hash = hashDimension(key.getDimensionKeyInWhat());
        hash = android::JenkinsHashMix(hash, hashDimension(key.getStateValuesKey()));
        return android::JenkinsHashWhiten(hash);
    }
};
}  // namespace std
