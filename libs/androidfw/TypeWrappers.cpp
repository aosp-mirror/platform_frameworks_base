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

#include <algorithm>

namespace android {

TypeVariant::TypeVariant(const ResTable_type* data) : data(data), mLength(dtohl(data->entryCount)) {
    if (data->flags & ResTable_type::FLAG_SPARSE) {
        const uint32_t entryCount = dtohl(data->entryCount);
        const uintptr_t containerEnd = reinterpret_cast<uintptr_t>(data) + dtohl(data->header.size);
        const uint32_t* const entryIndices = reinterpret_cast<const uint32_t*>(
                reinterpret_cast<uintptr_t>(data) + dtohs(data->header.headerSize));
        if (reinterpret_cast<uintptr_t>(entryIndices) + (sizeof(uint32_t) * entryCount)
                > containerEnd) {
            ALOGE("Type's entry indices extend beyond its boundaries");
            mLength = 0;
        } else {
          mLength = ResTable_sparseTypeEntry{entryIndices[entryCount - 1]}.idx + 1;
        }
    }
}

TypeVariant::iterator& TypeVariant::iterator::operator++() {
    mIndex++;
    if (mIndex > mTypeVariant->mLength) {
        mIndex = mTypeVariant->mLength;
    }
    return *this;
}

static bool keyCompare(uint32_t entry, uint16_t index) {
  return dtohs(ResTable_sparseTypeEntry{entry}.idx) < index;
}

const ResTable_entry* TypeVariant::iterator::operator*() const {
    const ResTable_type* type = mTypeVariant->data;
    if (mIndex >= mTypeVariant->mLength) {
        return NULL;
    }

    const uint32_t entryCount = dtohl(mTypeVariant->data->entryCount);
    const uintptr_t containerEnd = reinterpret_cast<uintptr_t>(type)
            + dtohl(type->header.size);
    const uint32_t* const entryIndices = reinterpret_cast<const uint32_t*>(
            reinterpret_cast<uintptr_t>(type) + dtohs(type->header.headerSize));
    if (reinterpret_cast<uintptr_t>(entryIndices) + (sizeof(uint32_t) * entryCount) > containerEnd) {
        ALOGE("Type's entry indices extend beyond its boundaries");
        return NULL;
    }

    uint32_t entryOffset;
    if (type->flags & ResTable_type::FLAG_SPARSE) {
      auto iter = std::lower_bound(entryIndices, entryIndices + entryCount, mIndex, keyCompare);
      if (iter == entryIndices + entryCount
              || dtohs(ResTable_sparseTypeEntry{*iter}.idx) != mIndex) {
        return NULL;
      }

      entryOffset = static_cast<uint32_t>(dtohs(ResTable_sparseTypeEntry{*iter}.offset)) * 4u;
    } else {
      entryOffset = dtohl(entryIndices[mIndex]);
    }

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
