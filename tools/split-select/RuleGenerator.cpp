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

#include "RuleGenerator.h"
#include "aapt/SdkConstants.h"

#include <algorithm>
#include <cmath>
#include <vector>
#include <androidfw/ResourceTypes.h>

using namespace android;

namespace split {

// Calculate the point at which the density selection changes between l and h.
static inline int findMid(int l, int h) {
    double root = sqrt((h*h) + (8*l*h));
    return (double(-h) + root) / 2.0;
}

sp<Rule> RuleGenerator::generateDensity(const Vector<int>& allDensities, size_t index) {
    if (allDensities[index] != ResTable_config::DENSITY_ANY) {
        sp<Rule> densityRule = new Rule();
        densityRule->op = Rule::AND_SUBRULES;

        const bool hasAnyDensity = std::find(allDensities.begin(),
                allDensities.end(), (int) ResTable_config::DENSITY_ANY) != allDensities.end();

        if (hasAnyDensity) {
            sp<Rule> version = new Rule();
            version->op = Rule::LESS_THAN;
            version->key = Rule::SDK_VERSION;
            version->longArgs.add((long) SDK_LOLLIPOP);
            densityRule->subrules.add(version);
        }

        if (index > 0) {
            sp<Rule> gt = new Rule();
            gt->op = Rule::GREATER_THAN;
            gt->key = Rule::SCREEN_DENSITY;
            gt->longArgs.add(findMid(allDensities[index - 1], allDensities[index]) - 1);
            densityRule->subrules.add(gt);
        }

        if (index + 1 < allDensities.size() && allDensities[index + 1] != ResTable_config::DENSITY_ANY) {
            sp<Rule> lt = new Rule();
            lt->op = Rule::LESS_THAN;
            lt->key = Rule::SCREEN_DENSITY;
            lt->longArgs.add(findMid(allDensities[index], allDensities[index + 1]));
            densityRule->subrules.add(lt);
        }
        return densityRule;
    } else {
        // SDK_VERSION is handled elsewhere, so we always pick DENSITY_ANY if it's
        // available.
        sp<Rule> always = new Rule();
        always->op = Rule::ALWAYS_TRUE;
        return always;
    }
}

sp<Rule> RuleGenerator::generateAbi(const Vector<abi::Variant>& splitAbis, size_t index) {
    const abi::Variant thisAbi = splitAbis[index];
    const Vector<abi::Variant>& familyVariants = abi::getVariants(abi::getFamily(thisAbi));

    Vector<abi::Variant>::const_iterator start =
            std::find(familyVariants.begin(), familyVariants.end(), thisAbi);

    Vector<abi::Variant>::const_iterator end = familyVariants.end();
    if (index + 1 < splitAbis.size()) {
        end = std::find(start, familyVariants.end(), splitAbis[index + 1]);
    }

    sp<Rule> abiRule = new Rule();
    abiRule->op = Rule::CONTAINS_ANY;
    abiRule->key = Rule::NATIVE_PLATFORM;
    while (start != end) {
        abiRule->stringArgs.add(String8(abi::toString(*start)));
        ++start;
    }
    return abiRule;
}

sp<Rule> RuleGenerator::generate(const SortedVector<SplitDescription>& group, size_t index) {
    sp<Rule> rootRule = new Rule();
    rootRule->op = Rule::AND_SUBRULES;

    if (group[index].config.locale != 0) {
        sp<Rule> locale = new Rule();
        locale->op = Rule::EQUALS;
        locale->key = Rule::LANGUAGE;
        char str[RESTABLE_MAX_LOCALE_LEN];
        group[index].config.getBcp47Locale(str);
        locale->stringArgs.add(String8(str));
        rootRule->subrules.add(locale);
    }

    if (group[index].config.sdkVersion != 0) {
        sp<Rule> sdk = new Rule();
        sdk->op = Rule::GREATER_THAN;
        sdk->key = Rule::SDK_VERSION;
        sdk->longArgs.add(group[index].config.sdkVersion - 1);
        rootRule->subrules.add(sdk);
    }

    if (group[index].config.density != 0) {
        size_t densityIndex = 0;
        Vector<int> allDensities;
        allDensities.add(group[index].config.density);

        const size_t groupSize = group.size();
        for (size_t i = 0; i < groupSize; i++) {
            if (group[i].config.density != group[index].config.density) {
                // This group differs by density.
                allDensities.clear();
                for (size_t j = 0; j < groupSize; j++) {
                    allDensities.add(group[j].config.density);
                }
                densityIndex = index;
                break;
            }
        }
        rootRule->subrules.add(generateDensity(allDensities, densityIndex));
    }

    if (group[index].abi != abi::Variant_none) {
        size_t abiIndex = 0;
        Vector<abi::Variant> allVariants;
        allVariants.add(group[index].abi);

        const size_t groupSize = group.size();
        for (size_t i = 0; i < groupSize; i++) {
            if (group[i].abi != group[index].abi) {
                // This group differs by ABI.
                allVariants.clear();
                for (size_t j = 0; j < groupSize; j++) {
                    allVariants.add(group[j].abi);
                }
                abiIndex = index;
                break;
            }
        }
        rootRule->subrules.add(generateAbi(allVariants, abiIndex));
    }

    return rootRule;
}

} // namespace split
