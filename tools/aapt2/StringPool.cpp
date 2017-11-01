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

#include <algorithm>
#include <memory>
#include <string>

#include "android-base/logging.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"

#include "util/BigBuffer.h"
#include "util/Util.h"

using android::StringPiece;

namespace aapt {

StringPool::Ref::Ref() : entry_(nullptr) {}

StringPool::Ref::Ref(const StringPool::Ref& rhs) : entry_(rhs.entry_) {
  if (entry_ != nullptr) {
    entry_->ref_++;
  }
}

StringPool::Ref::Ref(StringPool::Entry* entry) : entry_(entry) {
  if (entry_ != nullptr) {
    entry_->ref_++;
  }
}

StringPool::Ref::~Ref() {
  if (entry_ != nullptr) {
    entry_->ref_--;
  }
}

StringPool::Ref& StringPool::Ref::operator=(const StringPool::Ref& rhs) {
  if (rhs.entry_ != nullptr) {
    rhs.entry_->ref_++;
  }

  if (entry_ != nullptr) {
    entry_->ref_--;
  }
  entry_ = rhs.entry_;
  return *this;
}

bool StringPool::Ref::operator==(const Ref& rhs) const {
  return entry_->value == rhs.entry_->value;
}

bool StringPool::Ref::operator!=(const Ref& rhs) const {
  return entry_->value != rhs.entry_->value;
}

const std::string* StringPool::Ref::operator->() const {
  return &entry_->value;
}

const std::string& StringPool::Ref::operator*() const { return entry_->value; }

size_t StringPool::Ref::index() const { return entry_->index; }

const StringPool::Context& StringPool::Ref::GetContext() const {
  return entry_->context;
}

StringPool::StyleRef::StyleRef() : entry_(nullptr) {}

StringPool::StyleRef::StyleRef(const StringPool::StyleRef& rhs)
    : entry_(rhs.entry_) {
  if (entry_ != nullptr) {
    entry_->ref_++;
  }
}

StringPool::StyleRef::StyleRef(StringPool::StyleEntry* entry) : entry_(entry) {
  if (entry_ != nullptr) {
    entry_->ref_++;
  }
}

StringPool::StyleRef::~StyleRef() {
  if (entry_ != nullptr) {
    entry_->ref_--;
  }
}

StringPool::StyleRef& StringPool::StyleRef::operator=(
    const StringPool::StyleRef& rhs) {
  if (rhs.entry_ != nullptr) {
    rhs.entry_->ref_++;
  }

  if (entry_ != nullptr) {
    entry_->ref_--;
  }
  entry_ = rhs.entry_;
  return *this;
}

bool StringPool::StyleRef::operator==(const StyleRef& rhs) const {
  if (entry_->str != rhs.entry_->str) {
    return false;
  }

  if (entry_->spans.size() != rhs.entry_->spans.size()) {
    return false;
  }

  auto rhs_iter = rhs.entry_->spans.begin();
  for (const Span& span : entry_->spans) {
    const Span& rhs_span = *rhs_iter;
    if (span.first_char != rhs_span.first_char || span.last_char != rhs_span.last_char ||
        span.name != rhs_span.name) {
      return false;
    }
  }
  return true;
}

bool StringPool::StyleRef::operator!=(const StyleRef& rhs) const { return !operator==(rhs); }

const StringPool::StyleEntry* StringPool::StyleRef::operator->() const {
  return entry_;
}

const StringPool::StyleEntry& StringPool::StyleRef::operator*() const {
  return *entry_;
}

size_t StringPool::StyleRef::index() const { return entry_->str.index(); }

const StringPool::Context& StringPool::StyleRef::GetContext() const {
  return entry_->str.GetContext();
}

StringPool::Ref StringPool::MakeRef(const StringPiece& str) {
  return MakeRefImpl(str, Context{}, true);
}

StringPool::Ref StringPool::MakeRef(const StringPiece& str,
                                    const Context& context) {
  return MakeRefImpl(str, context, true);
}

StringPool::Ref StringPool::MakeRefImpl(const StringPiece& str,
                                        const Context& context, bool unique) {
  if (unique) {
    auto iter = indexed_strings_.find(str);
    if (iter != std::end(indexed_strings_)) {
      return Ref(iter->second);
    }
  }

  Entry* entry = new Entry();
  entry->value = str.to_string();
  entry->context = context;
  entry->index = strings_.size();
  entry->ref_ = 0;
  strings_.emplace_back(entry);
  indexed_strings_.insert(std::make_pair(StringPiece(entry->value), entry));
  return Ref(entry);
}

StringPool::StyleRef StringPool::MakeRef(const StyleString& str) {
  return MakeRef(str, Context{});
}

StringPool::StyleRef StringPool::MakeRef(const StyleString& str,
                                         const Context& context) {
  Entry* entry = new Entry();
  entry->value = str.str;
  entry->context = context;
  entry->index = strings_.size();
  entry->ref_ = 0;
  strings_.emplace_back(entry);
  indexed_strings_.insert(std::make_pair(StringPiece(entry->value), entry));

  StyleEntry* style_entry = new StyleEntry();
  style_entry->str = Ref(entry);
  for (const aapt::Span& span : str.spans) {
    style_entry->spans.emplace_back(
        Span{MakeRef(span.name), span.first_char, span.last_char});
  }
  style_entry->ref_ = 0;
  styles_.emplace_back(style_entry);
  return StyleRef(style_entry);
}

StringPool::StyleRef StringPool::MakeRef(const StyleRef& ref) {
  Entry* entry = new Entry();
  entry->value = *ref.entry_->str;
  entry->context = ref.entry_->str.entry_->context;
  entry->index = strings_.size();
  entry->ref_ = 0;
  strings_.emplace_back(entry);
  indexed_strings_.insert(std::make_pair(StringPiece(entry->value), entry));

  StyleEntry* style_entry = new StyleEntry();
  style_entry->str = Ref(entry);
  for (const Span& span : ref.entry_->spans) {
    style_entry->spans.emplace_back(
        Span{MakeRef(*span.name), span.first_char, span.last_char});
  }
  style_entry->ref_ = 0;
  styles_.emplace_back(style_entry);
  return StyleRef(style_entry);
}

void StringPool::Merge(StringPool&& pool) {
  indexed_strings_.insert(pool.indexed_strings_.begin(),
                          pool.indexed_strings_.end());
  pool.indexed_strings_.clear();
  std::move(pool.strings_.begin(), pool.strings_.end(),
            std::back_inserter(strings_));
  pool.strings_.clear();
  std::move(pool.styles_.begin(), pool.styles_.end(),
            std::back_inserter(styles_));
  pool.styles_.clear();

  // Assign the indices.
  const size_t len = strings_.size();
  for (size_t index = 0; index < len; index++) {
    strings_[index]->index = index;
  }
}

void StringPool::HintWillAdd(size_t stringCount, size_t styleCount) {
  strings_.reserve(strings_.size() + stringCount);
  styles_.reserve(styles_.size() + styleCount);
}

void StringPool::Prune() {
  const auto iter_end = indexed_strings_.end();
  auto index_iter = indexed_strings_.begin();
  while (index_iter != iter_end) {
    if (index_iter->second->ref_ <= 0) {
      index_iter = indexed_strings_.erase(index_iter);
    } else {
      ++index_iter;
    }
  }

  auto end_iter2 =
      std::remove_if(strings_.begin(), strings_.end(),
                     [](const std::unique_ptr<Entry>& entry) -> bool {
                       return entry->ref_ <= 0;
                     });

  auto end_iter3 =
      std::remove_if(styles_.begin(), styles_.end(),
                     [](const std::unique_ptr<StyleEntry>& entry) -> bool {
                       return entry->ref_ <= 0;
                     });

  // Remove the entries at the end or else we'll be accessing
  // a deleted string from the StyleEntry.
  strings_.erase(end_iter2, strings_.end());
  styles_.erase(end_iter3, styles_.end());

  // Reassign the indices.
  const size_t len = strings_.size();
  for (size_t index = 0; index < len; index++) {
    strings_[index]->index = index;
  }
}

void StringPool::Sort(
    const std::function<bool(const Entry&, const Entry&)>& cmp) {
  std::sort(
      strings_.begin(), strings_.end(),
      [&cmp](const std::unique_ptr<Entry>& a,
             const std::unique_ptr<Entry>& b) -> bool { return cmp(*a, *b); });

  // Assign the indices.
  const size_t len = strings_.size();
  for (size_t index = 0; index < len; index++) {
    strings_[index]->index = index;
  }

  // Reorder the styles.
  std::sort(styles_.begin(), styles_.end(),
            [](const std::unique_ptr<StyleEntry>& lhs,
               const std::unique_ptr<StyleEntry>& rhs) -> bool {
              return lhs->str.index() < rhs->str.index();
            });
}

template <typename T>
static T* EncodeLength(T* data, size_t length) {
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
static size_t EncodedLengthUnits(size_t length) {
  static_assert(std::is_integral<T>::value, "wat.");

  constexpr size_t kMask = 1 << ((sizeof(T) * 8) - 1);
  constexpr size_t kMaxSize = kMask - 1;
  return length > kMaxSize ? 2 : 1;
}

bool StringPool::Flatten(BigBuffer* out, const StringPool& pool, bool utf8) {
  const size_t start_index = out->size();
  android::ResStringPool_header* header =
      out->NextBlock<android::ResStringPool_header>();
  header->header.type = android::RES_STRING_POOL_TYPE;
  header->header.headerSize = sizeof(*header);
  header->stringCount = pool.size();
  if (utf8) {
    header->flags |= android::ResStringPool_header::UTF8_FLAG;
  }

  uint32_t* indices =
      pool.size() != 0 ? out->NextBlock<uint32_t>(pool.size()) : nullptr;

  uint32_t* style_indices = nullptr;
  if (!pool.styles_.empty()) {
    header->styleCount = pool.styles_.back()->str.index() + 1;
    style_indices = out->NextBlock<uint32_t>(header->styleCount);
  }

  const size_t before_strings_index = out->size();
  header->stringsStart = before_strings_index - start_index;

  for (const auto& entry : pool) {
    *indices = out->size() - before_strings_index;
    indices++;

    if (utf8) {
      const std::string& encoded = entry->value;
      const ssize_t utf16_length = utf8_to_utf16_length(
          reinterpret_cast<const uint8_t*>(entry->value.data()),
          entry->value.size());
      CHECK(utf16_length >= 0);

      const size_t total_size = EncodedLengthUnits<char>(utf16_length) +
                                EncodedLengthUnits<char>(encoded.length()) +
                                encoded.size() + 1;

      char* data = out->NextBlock<char>(total_size);

      // First encode the UTF16 string length.
      data = EncodeLength(data, utf16_length);

      // Now encode the size of the real UTF8 string.
      data = EncodeLength(data, encoded.length());
      strncpy(data, encoded.data(), encoded.size());

    } else {
      const std::u16string encoded = util::Utf8ToUtf16(entry->value);
      const ssize_t utf16_length = encoded.size();

      // Total number of 16-bit words to write.
      const size_t total_size =
          EncodedLengthUnits<char16_t>(utf16_length) + encoded.size() + 1;

      char16_t* data = out->NextBlock<char16_t>(total_size);

      // Encode the actual UTF16 string length.
      data = EncodeLength(data, utf16_length);
      const size_t byte_length = encoded.size() * sizeof(char16_t);

      // NOTE: For some reason, strncpy16(data, entry->value.data(),
      // entry->value.size()) truncates the string.
      memcpy(data, encoded.data(), byte_length);

      // The null-terminating character is already here due to the block of data
      // being set to 0s on allocation.
    }
  }

  out->Align4();

  if (!pool.styles_.empty()) {
    const size_t before_styles_index = out->size();
    header->stylesStart = before_styles_index - start_index;

    size_t current_index = 0;
    for (const auto& entry : pool.styles_) {
      while (entry->str.index() > current_index) {
        style_indices[current_index++] = out->size() - before_styles_index;

        uint32_t* span_offset = out->NextBlock<uint32_t>();
        *span_offset = android::ResStringPool_span::END;
      }
      style_indices[current_index++] = out->size() - before_styles_index;

      android::ResStringPool_span* span =
          out->NextBlock<android::ResStringPool_span>(entry->spans.size());
      for (const auto& s : entry->spans) {
        span->name.index = s.name.index();
        span->firstChar = s.first_char;
        span->lastChar = s.last_char;
        span++;
      }

      uint32_t* spanEnd = out->NextBlock<uint32_t>();
      *spanEnd = android::ResStringPool_span::END;
    }

    // The error checking code in the platform looks for an entire
    // ResStringPool_span structure worth of 0xFFFFFFFF at the end
    // of the style block, so fill in the remaining 2 32bit words
    // with 0xFFFFFFFF.
    const size_t padding_length = sizeof(android::ResStringPool_span) -
                                  sizeof(android::ResStringPool_span::name);
    uint8_t* padding = out->NextBlock<uint8_t>(padding_length);
    memset(padding, 0xff, padding_length);
    out->Align4();
  }
  header->header.size = out->size() - start_index;
  return true;
}

bool StringPool::FlattenUtf8(BigBuffer* out, const StringPool& pool) {
  return Flatten(out, pool, true);
}

bool StringPool::FlattenUtf16(BigBuffer* out, const StringPool& pool) {
  return Flatten(out, pool, false);
}

}  // namespace aapt
