/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "StringPool.h"
#include "util/BigBuffer.h"
#include "util/StringPiece.h"
#include "util/Util.h"

#include <algorithm>
#include <androidfw/ResourceTypes.h>
#include <memory>
#include <string>

namespace aapt {

StringPool::Ref::Ref() : mEntry(nullptr) {
}

StringPool::Ref::Ref(const StringPool::Ref& rhs) : mEntry(rhs.mEntry) {
    if (mEntry != nullptr) {
        mEntry->ref++;
    }
}

StringPool::Ref::Ref(StringPool::Entry* entry) : mEntry(entry) {
    if (mEntry != nullptr) {
        mEntry->ref++;
    }
}

StringPool::Ref::~Ref() {
    if (mEntry != nullptr) {
        mEntry->ref--;
    }
}

StringPool::Ref& StringPool::Ref::operator=(const StringPool::Ref& rhs) {
    if (rhs.mEntry != nullptr) {
        rhs.mEntry->ref++;
    }

    if (mEntry != nullptr) {
        mEntry->ref--;
    }
    mEntry = rhs.mEntry;
    return *this;
}

const std::u16string* StringPool::Ref::operator->() const {
    return &mEntry->value;
}

const std::u16string& StringPool::Ref::operator*() const {
    return mEntry->value;
}

size_t StringPool::Ref::getIndex() const {
    return mEntry->index;
}

const StringPool::Context& StringPool::Ref::getContext() const {
    return mEntry->context;
}

StringPool::StyleRef::StyleRef() : mEntry(nullptr) {
}

StringPool::StyleRef::StyleRef(const StringPool::StyleRef& rhs) : mEntry(rhs.mEntry) {
    if (mEntry != nullptr) {
        mEntry->ref++;
    }
}

StringPool::StyleRef::StyleRef(StringPool::StyleEntry* entry) : mEntry(entry) {
    if (mEntry != nullptr) {
        mEntry->ref++;
    }
}

StringPool::StyleRef::~StyleRef() {
    if (mEntry != nullptr) {
        mEntry->ref--;
    }
}

StringPool::StyleRef& StringPool::StyleRef::operator=(const StringPool::StyleRef& rhs) {
    if (rhs.mEntry != nullptr) {
        rhs.mEntry->ref++;
    }

    if (mEntry != nullptr) {
        mEntry->ref--;
    }
    mEntry = rhs.mEntry;
    return *this;
}

const StringPool::StyleEntry* StringPool::StyleRef::operator->() const {
    return mEntry;
}

const StringPool::StyleEntry& StringPool::StyleRef::operator*() const {
    return *mEntry;
}

size_t StringPool::StyleRef::getIndex() const {
    return mEntry->str.getIndex();
}

const StringPool::Context& StringPool::StyleRef::getContext() const {
    return mEntry->str.getContext();
}

StringPool::Ref StringPool::makeRef(const StringPiece16& str) {
    return makeRefImpl(str, Context{}, true);
}

StringPool::Ref StringPool::makeRef(const StringPiece16& str, const Context& context) {
    return makeRefImpl(str, context, true);
}

StringPool::Ref StringPool::makeRefImpl(const StringPiece16& str, const Context& context,
        bool unique) {
    if (unique) {
        auto iter = mIndexedStrings.find(str);
        if (iter != std::end(mIndexedStrings)) {
            return Ref(iter->second);
        }
    }

    Entry* entry = new Entry();
    entry->value = str.toString();
    entry->context = context;
    entry->index = mStrings.size();
    entry->ref = 0;
    mStrings.emplace_back(entry);
    mIndexedStrings.insert(std::make_pair(StringPiece16(entry->value), entry));
    return Ref(entry);
}

StringPool::StyleRef StringPool::makeRef(const StyleString& str) {
    return makeRef(str, Context{});
}

StringPool::StyleRef StringPool::makeRef(const StyleString& str, const Context& context) {
    Entry* entry = new Entry();
    entry->value = str.str;
    entry->context = context;
    entry->index = mStrings.size();
    entry->ref = 0;
    mStrings.emplace_back(entry);
    mIndexedStrings.insert(std::make_pair(StringPiece16(entry->value), entry));

    StyleEntry* styleEntry = new StyleEntry();
    styleEntry->str = Ref(entry);
    for (const aapt::Span& span : str.spans) {
        styleEntry->spans.emplace_back(Span{makeRef(span.name),
                span.firstChar, span.lastChar});
    }
    styleEntry->ref = 0;
    mStyles.emplace_back(styleEntry);
    return StyleRef(styleEntry);
}

StringPool::StyleRef StringPool::makeRef(const StyleRef& ref) {
    Entry* entry = new Entry();
    entry->value = *ref.mEntry->str;
    entry->context = ref.mEntry->str.mEntry->context;
    entry->index = mStrings.size();
    entry->ref = 0;
    mStrings.emplace_back(entry);
    mIndexedStrings.insert(std::make_pair(StringPiece16(entry->value), entry));

    StyleEntry* styleEntry = new StyleEntry();
    styleEntry->str = Ref(entry);
    for (const Span& span : ref.mEntry->spans) {
        styleEntry->spans.emplace_back(Span{ makeRef(*span.name), span.firstChar, span.lastChar });
    }
    styleEntry->ref = 0;
    mStyles.emplace_back(styleEntry);
    return StyleRef(styleEntry);
}

void StringPool::merge(StringPool&& pool) {
    mIndexedStrings.insert(pool.mIndexedStrings.begin(), pool.mIndexedStrings.end());
    pool.mIndexedStrings.clear();
    std::move(pool.mStrings.begin(), pool.mStrings.end(), std::back_inserter(mStrings));
    pool.mStrings.clear();
    std::move(pool.mStyles.begin(), pool.mStyles.end(), std::back_inserter(mStyles));
    pool.mStyles.clear();

    // Assign the indices.
    const size_t len = mStrings.size();
    for (size_t index = 0; index < len; index++) {
        mStrings[index]->index = index;
    }
}

void StringPool::hintWillAdd(size_t stringCount, size_t styleCount) {
    mStrings.reserve(mStrings.size() + stringCount);
    mStyles.reserve(mStyles.size() + styleCount);
}

void StringPool::prune() {
    const auto iterEnd = std::end(mIndexedStrings);
    auto indexIter = std::begin(mIndexedStrings);
    while (indexIter != iterEnd) {
        if (indexIter->second->ref <= 0) {
            indexIter = mIndexedStrings.erase(indexIter);
        } else {
            ++indexIter;
        }
    }

    auto endIter2 = std::remove_if(std::begin(mStrings), std::end(mStrings),
            [](const std::unique_ptr<Entry>& entry) -> bool {
                return entry->ref <= 0;
            }
    );

    auto endIter3 = std::remove_if(std::begin(mStyles), std::end(mStyles),
            [](const std::unique_ptr<StyleEntry>& entry) -> bool {
                return entry->ref <= 0;
            }
    );

    // Remove the entries at the end or else we'll be accessing
    // a deleted string from the StyleEntry.
    mStrings.erase(endIter2, std::end(mStrings));
    mStyles.erase(endIter3, std::end(mStyles));

    // Reassign the indices.
    const size_t len = mStrings.size();
    for (size_t index = 0; index < len; index++) {
        mStrings[index]->index = index;
    }
}

void StringPool::sort(const std::function<bool(const Entry&, const Entry&)>& cmp) {
    std::sort(std::begin(mStrings), std::end(mStrings),
            [&cmp](const std::unique_ptr<Entry>& a, const std::unique_ptr<Entry>& b) -> bool {
                return cmp(*a, *b);
            }
    );

    // Assign the indices.
    const size_t len = mStrings.size();
    for (size_t index = 0; index < len; index++) {
        mStrings[index]->index = index;
    }

    // Reorder the styles.
    std::sort(std::begin(mStyles), std::end(mStyles),
            [](const std::unique_ptr<StyleEntry>& lhs,
               const std::unique_ptr<StyleEntry>& rhs) -> bool {
                return lhs->str.getIndex() < rhs->str.getIndex();
            }
    );
}

template <typename T>
static T* encodeLength(T* data, size_t length) {
    static_assert(std::is_integral<T>::value, "wat.");

    constexpr size_t kMask = 1 << ((sizeof(T) * 8) - 1);
    constexpr size_t kMaxSize = kMask - 1;
    if (length > kMaxSize) {
        *data++ = kMask | (kMaxSize & (length >> (sizeof(T) * 8)));
    }
    *data++ = length;
    return data;
}

template <typename T>
static size_t encodedLengthUnits(size_t length) {
    static_assert(std::is_integral<T>::value, "wat.");

    constexpr size_t kMask = 1 << ((sizeof(T) * 8) - 1);
    constexpr size_t kMaxSize = kMask - 1;
    return length > kMaxSize ? 2 : 1;
}


bool StringPool::flatten(BigBuffer* out, const StringPool& pool, bool utf8) {
    const size_t startIndex = out->size();
    android::ResStringPool_header* header = out->nextBlock<android::ResStringPool_header>();
    header->header.type = android::RES_STRING_POOL_TYPE;
    header->header.headerSize = sizeof(*header);
    header->stringCount = pool.size();
    if (utf8) {
        header->flags |= android::ResStringPool_header::UTF8_FLAG;
    }

    uint32_t* indices = pool.size() != 0 ? out->nextBlock<uint32_t>(pool.size()) : nullptr;

    uint32_t* styleIndices = nullptr;
    if (!pool.mStyles.empty()) {
        header->styleCount = pool.mStyles.back()->str.getIndex() + 1;
        styleIndices = out->nextBlock<uint32_t>(header->styleCount);
    }

    const size_t beforeStringsIndex = out->size();
    header->stringsStart = beforeStringsIndex - startIndex;

    for (const auto& entry : pool) {
        *indices = out->size() - beforeStringsIndex;
        indices++;

        if (utf8) {
            std::string encoded = util::utf16ToUtf8(entry->value);

            const size_t totalSize = encodedLengthUnits<char>(entry->value.size())
                    + encodedLengthUnits<char>(encoded.length())
                    + encoded.size() + 1;

            char* data = out->nextBlock<char>(totalSize);

            // First encode the actual UTF16 string length.
            data = encodeLength(data, entry->value.size());

            // Now encode the size of the converted UTF8 string.
            data = encodeLength(data, encoded.length());
            strncpy(data, encoded.data(), encoded.size());
        } else {
            const size_t totalSize = encodedLengthUnits<char16_t>(entry->value.size())
                    + entry->value.size() + 1;

            char16_t* data = out->nextBlock<char16_t>(totalSize);

            // Encode the actual UTF16 string length.
            data = encodeLength(data, entry->value.size());
            const size_t byteLength = entry->value.size() * sizeof(char16_t);

            // NOTE: For some reason, strncpy16(data, entry->value.data(), entry->value.size())
            // truncates the string.
            memcpy(data, entry->value.data(), byteLength);

            // The null-terminating character is already here due to the block of data being set
            // to 0s on allocation.
        }
    }

    out->align4();

    if (!pool.mStyles.empty()) {
        const size_t beforeStylesIndex = out->size();
        header->stylesStart = beforeStylesIndex - startIndex;

        size_t currentIndex = 0;
        for (const auto& entry : pool.mStyles) {
            while (entry->str.getIndex() > currentIndex) {
                styleIndices[currentIndex++] = out->size() - beforeStylesIndex;

                uint32_t* spanOffset = out->nextBlock<uint32_t>();
                *spanOffset = android::ResStringPool_span::END;
            }
            styleIndices[currentIndex++] = out->size() - beforeStylesIndex;

            android::ResStringPool_span* span =
                    out->nextBlock<android::ResStringPool_span>(entry->spans.size());
            for (const auto& s : entry->spans) {
                span->name.index = s.name.getIndex();
                span->firstChar = s.firstChar;
                span->lastChar = s.lastChar;
                span++;
            }

            uint32_t* spanEnd = out->nextBlock<uint32_t>();
            *spanEnd = android::ResStringPool_span::END;
        }

        // The error checking code in the platform looks for an entire
        // ResStringPool_span structure worth of 0xFFFFFFFF at the end
        // of the style block, so fill in the remaining 2 32bit words
        // with 0xFFFFFFFF.
        const size_t paddingLength = sizeof(android::ResStringPool_span)
                - sizeof(android::ResStringPool_span::name);
        uint8_t* padding = out->nextBlock<uint8_t>(paddingLength);
        memset(padding, 0xff, paddingLength);
        out->align4();
    }
    header->header.size = out->size() - startIndex;
    return true;
}

bool StringPool::flattenUtf8(BigBuffer* out, const StringPool& pool) {
    return flatten(out, pool, true);
}

bool StringPool::flattenUtf16(BigBuffer* out, const StringPool& pool) {
    return flatten(out, pool, false);
}

} // namespace aapt
