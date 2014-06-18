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

#include <androidfw/TypeWrappers.h>

namespace android {

TypeVariant::iterator& TypeVariant::iterator::operator++() {
    mIndex++;
    if (mIndex > dtohl(mTypeVariant->data->entryCount)) {
        mIndex = dtohl(mTypeVariant->data->entryCount);
    }
    return *this;
}

const ResTable_entry* TypeVariant::iterator::operator*() const {
    const ResTable_type* type = mTypeVariant->data;
    const uint32_t entryCount = dtohl(type->entryCount);
    if (mIndex >= entryCount) {
        return NULL;
    }

    const uintptr_t containerEnd = reinterpret_cast<uintptr_t>(type)
            + dtohl(type->header.size);
    const uint32_t* const entryIndices = reinterpret_cast<const uint32_t*>(
            reinterpret_cast<uintptr_t>(type) + dtohs(type->header.headerSize));
    if (reinterpret_cast<uintptr_t>(entryIndices) + (sizeof(uint32_t) * entryCount) > containerEnd) {
        ALOGE("Type's entry indices extend beyond its boundaries");
        return NULL;
    }

    const uint32_t entryOffset = dtohl(entryIndices[mIndex]);
    if (entryOffset == ResTable_type::NO_ENTRY) {
        return NULL;
    }

    if ((entryOffset & 0x3) != 0) {
        ALOGE("Index %u points to entry with unaligned offset 0x%08x", mIndex, entryOffset);
        return NULL;
    }

    const ResTable_entry* entry = reinterpret_cast<const ResTable_entry*>(
            reinterpret_cast<uintptr_t>(type) + dtohl(type->entriesStart) + entryOffset);
    if (reinterpret_cast<uintptr_t>(entry) > containerEnd - sizeof(*entry)) {
        ALOGE("Entry offset at index %u points outside the Type's boundaries", mIndex);
        return NULL;
    } else if (reinterpret_cast<uintptr_t>(entry) + dtohs(entry->size) > containerEnd) {
        ALOGE("Entry at index %u extends beyond Type's boundaries", mIndex);
        return NULL;
    } else if (dtohs(entry->size) < sizeof(*entry)) {
        ALOGE("Entry at index %u is too small (%u)", mIndex, dtohs(entry->size));
        return NULL;
    }
    return entry;
}

} // namespace android
