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

TypeVariant::TypeVariant(const ResTable_type* data)
    : data(data)
    , mLength(dtohl(data->entryCount))
    , mSparse(data->flags & ResTable_type::FLAG_SPARSE) {
    if (mSparse) {
        const uint32_t entryCount = dtohl(data->entryCount);
        const uintptr_t containerEnd = reinterpret_cast<uintptr_t>(data) + dtohl(data->header.size);
        const uint32_t* const entryIndices = reinterpret_cast<const uint32_t*>(
                reinterpret_cast<uintptr_t>(data) + dtohs(data->header.headerSize));
        if (reinterpret_cast<uintptr_t>(entryIndices) + (sizeof(uint32_t) * entryCount)
                > containerEnd) {
            ALOGE("Type's entry indices extend beyond its boundaries");
            mLength = 0;
        } else {
          mLength = dtohs(ResTable_sparseTypeEntry{entryIndices[entryCount - 1]}.idx) + 1;
        }
    }
}

TypeVariant::iterator& TypeVariant::iterator::operator++() {
    ++mIndex;
    if (mIndex > mTypeVariant->mLength) {
        mIndex = mTypeVariant->mLength;
    }

    if (!mTypeVariant->mSparse) {
      return *this;
    }

    // Need to adjust |mSparseIndex| as well if we've passed its current element.
    const ResTable_type* type = mTypeVariant->data;
    const uint32_t entryCount = dtohl(type->entryCount);
    if (mSparseIndex >= entryCount) {
      return *this; // done
    }
    const auto entryIndices = reinterpret_cast<const uint32_t*>(
        reinterpret_cast<uintptr_t>(type) + dtohs(type->header.headerSize));
    const auto element = (const ResTable_sparseTypeEntry*)(entryIndices + mSparseIndex);
    if (mIndex > dtohs(element->idx)) {
      ++mSparseIndex;
    }

    return *this;
}

const ResTable_entry* TypeVariant::iterator::operator*() const {
    if (mIndex >= mTypeVariant->mLength) {
        return nullptr;
    }

    const ResTable_type* type = mTypeVariant->data;
    const uint32_t entryCount = dtohl(type->entryCount);
    const uintptr_t containerEnd = reinterpret_cast<uintptr_t>(type)
            + dtohl(type->header.size);
    const uint32_t* const entryIndices = reinterpret_cast<const uint32_t*>(
            reinterpret_cast<uintptr_t>(type) + dtohs(type->header.headerSize));
    const size_t indexSize = type->flags & ResTable_type::FLAG_OFFSET16 ?
                                    sizeof(uint16_t) : sizeof(uint32_t);
    if (reinterpret_cast<uintptr_t>(entryIndices) + (indexSize * entryCount) > containerEnd) {
        ALOGE("Type's entry indices extend beyond its boundaries");
        return nullptr;
    }

    uint32_t entryOffset;
    if (mTypeVariant->mSparse) {
      if (mSparseIndex >= entryCount) {
        return nullptr;
      }
      const auto element = (const ResTable_sparseTypeEntry*)(entryIndices + mSparseIndex);
      if (dtohs(element->idx) != mIndex) {
        return nullptr;
      }
      entryOffset = static_cast<uint32_t>(dtohs(element->offset)) * 4u;
    } else if (type->flags & ResTable_type::FLAG_OFFSET16) {
      auto entryIndices16 = reinterpret_cast<const uint16_t*>(entryIndices);
      entryOffset = offset_from16(entryIndices16[mIndex]);
    } else {
      entryOffset = dtohl(entryIndices[mIndex]);
    }

    if (entryOffset == ResTable_type::NO_ENTRY) {
        return nullptr;
    }

    if ((entryOffset & 0x3) != 0) {
        ALOGE("Index %u points to entry with unaligned offset 0x%08x", mIndex, entryOffset);
        return nullptr;
    }

    const ResTable_entry* entry = reinterpret_cast<const ResTable_entry*>(
            reinterpret_cast<uintptr_t>(type) + dtohl(type->entriesStart) + entryOffset);
    if (reinterpret_cast<uintptr_t>(entry) > containerEnd - sizeof(*entry)) {
        ALOGE("Entry offset at index %u points outside the Type's boundaries", mIndex);
        return nullptr;
    } else if (reinterpret_cast<uintptr_t>(entry) + entry->size() > containerEnd) {
        ALOGE("Entry at index %u extends beyond Type's boundaries", mIndex);
        return nullptr;
    } else if (entry->size() < sizeof(*entry)) {
        ALOGE("Entry at index %u is too small (%zu)", mIndex, entry->size());
        return nullptr;
    }
    return entry;
}

} // namespace android
