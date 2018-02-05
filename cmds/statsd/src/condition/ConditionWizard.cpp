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

ConditionState ConditionWizard::query(
    const int index, const ConditionKey& parameters,
    const FieldMatcher& dimensionFields,
    std::unordered_set<HashableDimensionKey> *dimensionKeySet) {

    vector<ConditionState> cache(mAllConditions.size(), ConditionState::kNotEvaluated);

    mAllConditions[index]->isConditionMet(
        parameters, mAllConditions, dimensionFields, cache, *dimensionKeySet);
    return cache[index];
}

ConditionState ConditionWizard::getMetConditionDimension(
    const int index, const FieldMatcher& dimensionFields,
    std::unordered_set<HashableDimensionKey> *dimensionsKeySet) const {

    return mAllConditions[index]->getMetConditionDimension(mAllConditions, dimensionFields,
                                 *dimensionsKeySet);
}

}  // namespace statsd
}  // namespace os
}  // namespace android