/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <unordered_map>
#include <unordered_set>

#include <androidfw/LocaleDataLookup.h>

namespace android {

#include "LocaleDataTables.cpp"

const size_t SCRIPT_PARENTS_COUNT = sizeof(SCRIPT_PARENTS)/sizeof(SCRIPT_PARENTS[0]);

const char* lookupLikelyScript(uint32_t packed_lang_region) {

    auto lookup_result = LIKELY_SCRIPTS.find(packed_lang_region);
    if (lookup_result == LIKELY_SCRIPTS.end()) {
        return nullptr;
    } else {
        return SCRIPT_CODES[lookup_result->second];
    }
}

uint32_t findParentLocalePackedKey(const char* script, uint32_t packed_lang_region) {
    for (size_t i = 0; i < SCRIPT_PARENTS_COUNT; i++) {
        if (memcmp(script, SCRIPT_PARENTS[i].script, SCRIPT_LENGTH) == 0) {
            auto map = SCRIPT_PARENTS[i].map;
            auto lookup_result = map->find(packed_lang_region);
            if (lookup_result != map->end()) {
                return lookup_result->second;
            }
            break;
        }
    }
    return 0;
}

uint32_t getMaxAncestorTreeDepth() {
    return MAX_PARENT_DEPTH;
}

namespace hidden {

bool isRepresentative(uint64_t packed_locale) {
    return (REPRESENTATIVE_LOCALES.count(packed_locale) != 0);
}

} // namespace hidden

} // namespace android
