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

#include "Grouper.h"

#include "aapt/AaptUtil.h"
#include "SplitDescription.h"

#include <utils/KeyedVector.h>
#include <utils/Vector.h>

using namespace android;
using AaptUtil::appendValue;

namespace split {

Vector<SortedVector<SplitDescription> >
groupByMutualExclusivity(const Vector<SplitDescription>& splits) {
    Vector<SortedVector<SplitDescription> > groups;

    // Find mutually exclusive splits and group them.
    KeyedVector<SplitDescription, SortedVector<SplitDescription> > densityGroups;
    KeyedVector<SplitDescription, SortedVector<SplitDescription> > abiGroups;
    KeyedVector<SplitDescription, SortedVector<SplitDescription> > localeGroups;
    const size_t splitCount = splits.size();
    for (size_t i = 0; i < splitCount; i++) {
        const SplitDescription& split = splits[i];
        if (split.config.density != 0) {
            SplitDescription key(split);
            key.config.density = 0;
            key.config.sdkVersion = 0; // Ignore density so we can support anydpi.
            appendValue(densityGroups, key, split);
        } else if (split.abi != abi::Variant_none) {
            SplitDescription key(split);
            key.abi = abi::Variant_none;
            appendValue(abiGroups, key, split);
        } else if (split.config.locale != 0) {
            SplitDescription key(split);
            key.config.clearLocale();
            appendValue(localeGroups, key, split);
        } else {
            groups.add();
            groups.editTop().add(split);
        }
    }

    const size_t densityCount = densityGroups.size();
    for (size_t i = 0; i < densityCount; i++) {
        groups.add(densityGroups[i]);
    }

    const size_t abiCount = abiGroups.size();
    for (size_t i = 0; i < abiCount; i++) {
        groups.add(abiGroups[i]);
    }

    const size_t localeCount = localeGroups.size();
    for (size_t i = 0; i < localeCount; i++) {
        groups.add(localeGroups[i]);
    }
    return groups;
}

} // namespace split
