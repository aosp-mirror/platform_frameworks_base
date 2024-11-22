/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#define LOG_TAG "DisplayLuts"

#include <android/display_luts.h>
#include <display_luts_private.h>
#include <utils/Log.h>

#include <cmath>

#define ADISPLAYLUTS_BUFFER_LENGTH_LIMIT (100000)

#define CHECK_NOT_NULL(name) \
    LOG_ALWAYS_FATAL_IF(name == nullptr, "nullptr passed as " #name " argument");

ADisplayLutsEntry* ADisplayLutsEntry_createEntry(float* buffer, int32_t length,
                                                 ADisplayLuts_Dimension dimension,
                                                 ADisplayLuts_SamplingKey key) {
    CHECK_NOT_NULL(buffer);
    LOG_ALWAYS_FATAL_IF(length >= ADISPLAYLUTS_BUFFER_LENGTH_LIMIT,
                        "the lut raw buffer length is too big to handle");
    if (dimension != ADISPLAYLUTS_ONE_DIMENSION && dimension != ADISPLAYLUTS_THREE_DIMENSION) {
        LOG_ALWAYS_FATAL("the lut dimension is be either 1 or 3");
    }
    int32_t size = 0;
    if (dimension == ADISPLAYLUTS_THREE_DIMENSION) {
        LOG_ALWAYS_FATAL_IF(length % 3 != 0, "the 3d lut raw buffer is not divisible by 3");
        int32_t lengthPerChannel = length / 3;
        float sizeForDim = std::cbrt(static_cast<float>(lengthPerChannel));
        LOG_ALWAYS_FATAL_IF(sizeForDim != (int)(sizeForDim),
                            "the 3d lut buffer length is incorrect");
        size = (int)sizeForDim;
    } else {
        size = length;
    }
    LOG_ALWAYS_FATAL_IF(size < 2, "the lut size for each dimension is too small");

    ADisplayLutsEntry* entry = new ADisplayLutsEntry();
    entry->buffer.data.resize(length);
    std::copy(buffer, buffer + length, entry->buffer.data.begin());
    entry->properties = {dimension, size, key};

    entry->incStrong((void*)ADisplayLutsEntry_createEntry);
    return static_cast<ADisplayLutsEntry*>(entry);
}

void ADisplayLutsEntry_destroy(ADisplayLutsEntry* entry) {
    if (entry != NULL) {
        entry->decStrong((void*)ADisplayLutsEntry_createEntry);
    }
}

ADisplayLuts_Dimension ADisplayLutsEntry_getDimension(const ADisplayLutsEntry* entry) {
    CHECK_NOT_NULL(entry);
    return entry->properties.dimension;
}

int32_t ADisplayLutsEntry_getSize(const ADisplayLutsEntry* entry) {
    CHECK_NOT_NULL(entry);
    return entry->properties.size;
}

ADisplayLuts_SamplingKey ADisplayLutsEntry_getSamplingKey(const ADisplayLutsEntry* entry) {
    CHECK_NOT_NULL(entry);
    return entry->properties.samplingKey;
}

const float* ADisplayLutsEntry_getBuffer(const ADisplayLutsEntry* _Nonnull entry) {
    CHECK_NOT_NULL(entry);
    return entry->buffer.data.data();
}

ADisplayLuts* ADisplayLuts_create() {
    ADisplayLuts* luts = new ADisplayLuts();
    if (luts == NULL) {
        delete luts;
        return NULL;
    }
    luts->incStrong((void*)ADisplayLuts_create);
    return static_cast<ADisplayLuts*>(luts);
}

void ADisplayLuts_clearLuts(ADisplayLuts* luts) {
    for (auto& entry : luts->entries) {
        entry->decStrong((void*)ADisplayLuts_setEntries); // Decrement ref count
    }
    luts->entries.clear();
    luts->offsets.clear();
    luts->totalBufferSize = 0;
}

void ADisplayLuts_destroy(ADisplayLuts* luts) {
    if (luts != NULL) {
        ADisplayLuts_clearLuts(luts);
        luts->decStrong((void*)ADisplayLuts_create);
    }
}

void ADisplayLuts_setEntries(ADisplayLuts* luts, ADisplayLutsEntry** entries, int32_t numEntries) {
    CHECK_NOT_NULL(luts);
    // always clear the previously set lut(s)
    ADisplayLuts_clearLuts(luts);

    // do nothing
    if (!entries || numEntries == 0) {
        return;
    }

    LOG_ALWAYS_FATAL_IF(numEntries > 2, "The number of entries should be not over 2!");
    if (numEntries == 2 && entries[0]->properties.dimension != ADISPLAYLUTS_ONE_DIMENSION &&
        entries[1]->properties.dimension != ADISPLAYLUTS_THREE_DIMENSION) {
        LOG_ALWAYS_FATAL("The entries should be 1D and 3D in order!");
    }

    luts->offsets.reserve(numEntries);
    luts->entries.reserve(numEntries);
    for (int32_t i = 0; i < numEntries; i++) {
        luts->offsets.emplace_back(luts->totalBufferSize);
        luts->totalBufferSize += entries[i]->buffer.data.size();
        luts->entries.emplace_back(entries[i]);
        luts->entries.back()->incStrong((void*)ADisplayLuts_setEntries);
    }
}