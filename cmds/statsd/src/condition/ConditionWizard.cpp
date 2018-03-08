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
#include "ConditionWizard.h"
#include <unordered_set>

namespace android {
namespace os {
namespace statsd {

using std::map;
using std::string;
using std::vector;

ConditionState ConditionWizard::query(const int index, const ConditionKey& parameters,
                                      const vector<Matcher>& dimensionFields,
                                      const bool isSubOutputDimensionFields,
                                      const bool isPartialLink,
                                      std::unordered_set<HashableDimensionKey>* dimensionKeySet) {
    vector<ConditionState> cache(mAllConditions.size(), ConditionState::kNotEvaluated);

    mAllConditions[index]->isConditionMet(
        parameters, mAllConditions, dimensionFields, isSubOutputDimensionFields, isPartialLink,
        cache, *dimensionKeySet);
    return cache[index];
}

ConditionState ConditionWizard::getMetConditionDimension(
        const int index, const vector<Matcher>& dimensionFields,
        const bool isSubOutputDimensionFields,
        std::unordered_set<HashableDimensionKey>* dimensionsKeySet) const {
    return mAllConditions[index]->getMetConditionDimension(mAllConditions, dimensionFields,
                                                           isSubOutputDimensionFields,
                                                           *dimensionsKeySet);
}

const set<HashableDimensionKey>* ConditionWizard::getChangedToTrueDimensions(
        const int index) const {
    return mAllConditions[index]->getChangedToTrueDimensions(mAllConditions);
}

const set<HashableDimensionKey>* ConditionWizard::getChangedToFalseDimensions(
        const int index) const {
    return mAllConditions[index]->getChangedToFalseDimensions(mAllConditions);
}

bool ConditionWizard::IsChangedDimensionTrackable(const int index) {
    if (index >= 0 && index < (int)mAllConditions.size()) {
        return mAllConditions[index]->IsChangedDimensionTrackable();
    } else {
        return false;
    }
}

bool ConditionWizard::IsSimpleCondition(const int index) {
    if (index >= 0 && index < (int)mAllConditions.size()) {
        return mAllConditions[index]->IsSimpleCondition();
    } else {
        return false;
    }
}

bool ConditionWizard::equalOutputDimensions(const int index, const vector<Matcher>& dimensions) {
    if (index >= 0 && index < (int)mAllConditions.size()) {
        return mAllConditions[index]->equalOutputDimensions(mAllConditions, dimensions);
    } else {
        return false;
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android