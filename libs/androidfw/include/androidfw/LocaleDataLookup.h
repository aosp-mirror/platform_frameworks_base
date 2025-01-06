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

#pragma once

#include <stddef.h>
#include <stdint.h>


namespace android {

constexpr size_t SCRIPT_LENGTH = 4;

constexpr inline uint32_t packLocale(const char* language, const char* region) {
    const unsigned char* lang = reinterpret_cast<const unsigned char*>(language);
    const unsigned char* reg = reinterpret_cast<const unsigned char*>(region);
    return (static_cast<uint32_t>(lang[0]) << 24u) |
            (static_cast<uint32_t>(lang[1]) << 16u) |
            (static_cast<uint32_t>(reg[0]) << 8u) |
            static_cast<uint32_t>(reg[1]);
}

constexpr inline uint32_t dropRegion(uint32_t packed_locale) {
    return packed_locale & 0xFFFF0000LU;
}

constexpr inline bool hasRegion(uint32_t packed_locale) {
    return (packed_locale & 0x0000FFFFLU) != 0;
}

constexpr inline uint32_t packScript(const char* script) {
    const unsigned char* s = reinterpret_cast<const unsigned char*>(script);
    return ((static_cast<uint32_t>(s[0]) << 24u) |
            (static_cast<uint32_t>(s[1]) << 16u) |
            (static_cast<uint32_t>(s[2]) <<  8u) |
            static_cast<uint32_t>(s[3]));
}

/**
 * Return nullptr if the key isn't found. The input packed_lang_region can be computed
 * by android::packLocale.
 * Note that the returned char* is either nullptr or 4-byte char seqeuence, but isn't
 * a null-terminated string.
 */
const char* lookupLikelyScript(uint32_t packed_lang_region);
/**
 * Return false if the key isn't representative. The input lookup key can be computed
 * by android::packLocale.
 */
bool isLocaleRepresentative(uint32_t language_and_region, const char* script);

/**
 * Return a parent packed key for a given script and child packed key. Return 0 if
 * no parent is found.
 */
uint32_t findParentLocalePackedKey(const char* script, uint32_t packed_lang_region);

uint32_t getMaxAncestorTreeDepth();

} // namespace android
