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

#ifndef H_ANDROID_SPLIT_RULE_GENERATOR
#define H_ANDROID_SPLIT_RULE_GENERATOR

#include "Abi.h"
#include "Rule.h"
#include "SplitDescription.h"

#include <utils/SortedVector.h>
#include <utils/Vector.h>

namespace split {

struct RuleGenerator {
    // Generate rules for a Split given the group of mutually exclusive splits it belongs to
    static android::sp<Rule> generate(const android::SortedVector<SplitDescription>& group, size_t index);

    static android::sp<Rule> generateAbi(const android::Vector<abi::Variant>& allVariants, size_t index);
    static android::sp<Rule> generateDensity(const android::Vector<int>& allDensities, size_t index);
};

} // namespace split

#endif // H_ANDROID_SPLIT_RULE_GENERATOR
