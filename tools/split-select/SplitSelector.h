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

#ifndef H_ANDROID_SPLIT_SPLIT_SELECTOR
#define H_ANDROID_SPLIT_SPLIT_SELECTOR

#include <utils/KeyedVector.h>
#include <utils/SortedVector.h>
#include <utils/Vector.h>

#include "Rule.h"
#include "SplitDescription.h"

namespace split {

class SplitSelector {
public:
    SplitSelector();
    SplitSelector(const android::Vector<SplitDescription>& splits);

    android::Vector<SplitDescription> getBestSplits(const SplitDescription& target) const;

    android::KeyedVector<SplitDescription, android::sp<Rule> > getRules() const;

private:
    android::Vector<android::SortedVector<SplitDescription> > mGroups;
};

} // namespace split

#endif // H_ANDROID_SPLIT_SPLIT_SELECTOR
