/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <array>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unordered_map>
#include <unordered_set>

#include <androidfw/LocaleData.h>

namespace android {

#include "LocaleDataTables.cpp"

inline uint32_t packLocale(const char* language, const char* region) {
    return (((uint8_t) language[0]) << 24u) | (((uint8_t) language[1]) << 16u) |
           (((uint8_t) region[0]) << 8u) | ((uint8_t) region[1]);
}

inline uint32_t dropRegion(uint32_t packed_locale) {
    return packed_locale & 0xFFFF0000lu;
}

inline bool hasRegion(uint32_t packed_locale) {
    return (packed_locale & 0x0000FFFFlu) != 0;
}

const size_t SCRIPT_LENGTH = 4;
const size_t SCRIPT_PARENTS_COUNT = sizeof(SCRIPT_PARENTS)/sizeof(SCRIPT_PARENTS[0]);
const uint32_t PACKED_ROOT = 0; // to represent the root locale

uint32_t findParent(uint32_t packed_locale, const char* script) {
    if (hasRegion(packed_locale)) {
        for (size_t i = 0; i < SCRIPT_PARENTS_COUNT; i++) {
            if (memcmp(script, SCRIPT_PARENTS[i].script, SCRIPT_LENGTH) == 0) {
                auto map = SCRIPT_PARENTS[i].map;
                auto lookup_result = map->find(packed_locale);
                if (lookup_result != map->end()) {
                    return lookup_result->second;
                }
                break;
            }
        }
        return dropRegion(packed_locale);
    }
    return PACKED_ROOT;
}

// Find the ancestors of a locale, and fill 'out' with it (assumes out has enough
// space). If any of the members of stop_list was seen, write it in the
// output but stop afterwards.
//
// This also outputs the index of the last written ancestor in the stop_list
// to stop_list_index, which will be -1 if it is not found in the stop_list.
//
// Returns the number of ancestors written in the output, which is always
// at least one.
//
// (If 'out' is nullptr, we do everything the same way but we simply don't write
// any results in 'out'.)
size_t findAncestors(uint32_t* out, ssize_t* stop_list_index,
                     uint32_t packed_locale, const char* script,
                     const uint32_t* stop_list, size_t stop_set_length) {
    uint32_t ancestor = packed_locale;
    size_t count = 0;
    do {
        if (out != nullptr) out[count] = ancestor;
        count++;
        for (size_t i = 0; i < stop_set_length; i++) {
            if (stop_list[i] == ancestor) {
                *stop_list_index = (ssize_t) i;
                return count;
            }
        }
        ancestor = findParent(ancestor, script);
    } while (ancestor != PACKED_ROOT);
    *stop_list_index = (ssize_t) -1;
    return count;
}

size_t findDistance(uint32_t supported,
                    const char* script,
                    const uint32_t* request_ancestors,
                    size_t request_ancestors_count) {
    ssize_t request_ancestors_index;
    const size_t supported_ancestor_count = findAncestors(
            nullptr, &request_ancestors_index,
            supported, script,
            request_ancestors, request_ancestors_count);
    // Since both locales share the same root, there will always be a shared
    // ancestor, so the distance in the parent tree is the sum of the distance
    // of 'supported' to the lowest common ancestor (number of ancestors
    // written for 'supported' minus 1) plus the distance of 'request' to the
    // lowest common ancestor (the index of the ancestor in request_ancestors).
    return supported_ancestor_count + request_ancestors_index - 1;
}

inline bool isRepresentative(uint32_t language_and_region, const char* script) {
    const uint64_t packed_locale = (
            (((uint64_t) language_and_region) << 32u) |
            (((uint64_t) script[0]) << 24u) |
            (((uint64_t) script[1]) << 16u) |
            (((uint64_t) script[2]) <<  8u) |
            ((uint64_t) script[3]));

    return (REPRESENTATIVE_LOCALES.count(packed_locale) != 0);
}

const uint32_t US_SPANISH = 0x65735553lu; // es-US
const uint32_t MEXICAN_SPANISH = 0x65734D58lu; // es-MX
const uint32_t LATIN_AMERICAN_SPANISH = 0x6573A424lu; // es-419

// The two locales es-US and es-MX are treated as special fallbacks for es-419.
// If there is no es-419, they are considered its equivalent.
inline bool isSpecialSpanish(uint32_t language_and_region) {
    return (language_and_region == US_SPANISH || language_and_region == MEXICAN_SPANISH);
}

int localeDataCompareRegions(
        const char* left_region, const char* right_region,
        const char* requested_language, const char* requested_script,
        const char* requested_region) {

    if (left_region[0] == right_region[0] && left_region[1] == right_region[1]) {
        return 0;
    }
    uint32_t left = packLocale(requested_language, left_region);
    uint32_t right = packLocale(requested_language, right_region);
    const uint32_t request = packLocale(requested_language, requested_region);

    // If one and only one of the two locales is a special Spanish locale, we
    // replace it with es-419. We don't do the replacement if the other locale
    // is already es-419, or both locales are special Spanish locales (when
    // es-US is being compared to es-MX).
    const bool leftIsSpecialSpanish = isSpecialSpanish(left);
    const bool rightIsSpecialSpanish = isSpecialSpanish(right);
    if (leftIsSpecialSpanish && !rightIsSpecialSpanish && right != LATIN_AMERICAN_SPANISH) {
        left = LATIN_AMERICAN_SPANISH;
    } else if (rightIsSpecialSpanish && !leftIsSpecialSpanish && left != LATIN_AMERICAN_SPANISH) {
        right = LATIN_AMERICAN_SPANISH;
    }

    uint32_t request_ancestors[MAX_PARENT_DEPTH+1];
    ssize_t left_right_index;
    // Find the parents of the request, but stop as soon as we saw left or right
    const std::array<uint32_t, 2> left_and_right = {{left, right}};
    const size_t ancestor_count = findAncestors(
            request_ancestors, &left_right_index,
            request, requested_script,
            left_and_right.data(), left_and_right.size());
    if (left_right_index == 0) { // We saw left earlier
        return 1;
    }
    if (left_right_index == 1) { // We saw right earlier
        return -1;
    }

    // If we are here, neither left nor right are an ancestor of the
    // request. This means that all the ancestors have been computed and
    // the last ancestor is just the language by itself. We will use the
    // distance in the parent tree for determining the better match.
    const size_t left_distance = findDistance(
            left, requested_script, request_ancestors, ancestor_count);
    const size_t right_distance = findDistance(
            right, requested_script, request_ancestors, ancestor_count);
    if (left_distance != right_distance) {
        return (int) right_distance - (int) left_distance; // smaller distance is better
    }

    // If we are here, left and right are equidistant from the request. We will
    // try and see if any of them is a representative locale.
    const bool left_is_representative = isRepresentative(left, requested_script);
    const bool right_is_representative = isRepresentative(right, requested_script);
    if (left_is_representative != right_is_representative) {
        return (int) left_is_representative - (int) right_is_representative;
    }

    // We have no way of figuring out which locale is a better match. For
    // the sake of stability, we consider the locale with the lower region
    // code (in dictionary order) better, with two-letter codes before
    // three-digit codes (since two-letter codes are more specific).
    return (int64_t) right - (int64_t) left;
}

void localeDataComputeScript(char out[4], const char* language, const char* region) {
    if (language[0] == '\0') {
        memset(out, '\0', SCRIPT_LENGTH);
        return;
    }
    uint32_t lookup_key = packLocale(language, region);
    auto lookup_result = LIKELY_SCRIPTS.find(lookup_key);
    if (lookup_result == LIKELY_SCRIPTS.end()) {
        // We couldn't find the locale. Let's try without the region
        if (region[0] != '\0') {
            lookup_key = dropRegion(lookup_key);
            lookup_result = LIKELY_SCRIPTS.find(lookup_key);
            if (lookup_result != LIKELY_SCRIPTS.end()) {
                memcpy(out, SCRIPT_CODES[lookup_result->second], SCRIPT_LENGTH);
                return;
            }
        }
        // We don't know anything about the locale
        memset(out, '\0', SCRIPT_LENGTH);
        return;
    } else {
        // We found the locale.
        memcpy(out, SCRIPT_CODES[lookup_result->second], SCRIPT_LENGTH);
    }
}

const uint32_t ENGLISH_STOP_LIST[2] = {
    0x656E0000lu, // en
    0x656E8400lu, // en-001
};
const char ENGLISH_CHARS[2] = {'e', 'n'};
const char LATIN_CHARS[4] = {'L', 'a', 't', 'n'};

bool localeDataIsCloseToUsEnglish(const char* region) {
    const uint32_t locale = packLocale(ENGLISH_CHARS, region);
    ssize_t stop_list_index;
    findAncestors(nullptr, &stop_list_index, locale, LATIN_CHARS, ENGLISH_STOP_LIST, 2);
    // A locale is like US English if we see "en" before "en-001" in its ancestor list.
    return stop_list_index == 0; // 'en' is first in ENGLISH_STOP_LIST
}

} // namespace android
