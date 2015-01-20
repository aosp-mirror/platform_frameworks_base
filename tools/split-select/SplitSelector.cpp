/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include <utils/KeyedVector.h>
#include <utils/SortedVector.h>
#include <utils/Vector.h>

#include "Grouper.h"
#include "Rule.h"
#include "RuleGenerator.h"
#include "SplitSelector.h"

namespace split {

using namespace android;

SplitSelector::SplitSelector() {
}

SplitSelector::SplitSelector(const Vector<SplitDescription>& splits)
    : mGroups(groupByMutualExclusivity(splits)) {
}

static void selectBestFromGroup(const SortedVector<SplitDescription>& splits,
        const SplitDescription& target, Vector<SplitDescription>& splitsOut) {
    SplitDescription bestSplit;
    bool isSet = false;
    const size_t splitCount = splits.size();
    for (size_t j = 0; j < splitCount; j++) {
        const SplitDescription& thisSplit = splits[j];
        if (!thisSplit.match(target)) {
            continue;
        }

        if (!isSet || thisSplit.isBetterThan(bestSplit, target)) {
            isSet = true;
            bestSplit = thisSplit;
        }
    }

    if (isSet) {
        splitsOut.add(bestSplit);
    }
}

Vector<SplitDescription> SplitSelector::getBestSplits(const SplitDescription& target) const {
    Vector<SplitDescription> bestSplits;
    const size_t groupCount = mGroups.size();
    for (size_t i = 0; i < groupCount; i++) {
        selectBestFromGroup(mGroups[i], target, bestSplits);
    }
    return bestSplits;
}

KeyedVector<SplitDescription, sp<Rule> > SplitSelector::getRules() const {
    KeyedVector<SplitDescription, sp<Rule> > rules;

    const size_t groupCount = mGroups.size();
    for (size_t i = 0; i < groupCount; i++) {
        const SortedVector<SplitDescription>& splits = mGroups[i];
        const size_t splitCount = splits.size();
        for (size_t j = 0; j < splitCount; j++) {
            sp<Rule> rule = Rule::simplify(RuleGenerator::generate(splits, j));
            if (rule != NULL) {
                rules.add(splits[j], rule);
            }
        }
    }
    return rules;
}

} // namespace split
