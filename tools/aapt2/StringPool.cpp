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

using ::android::StringPiece;

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

const std::string& StringPool::Ref::operator*() const {
  return entry_->value;
}

size_t StringPool::Ref::index() const {
  // Account for the styles, which *always* come first.
  return entry_->pool_->styles_.size() + entry_->index_;
}

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

StringPool::StyleRef& StringPool::StyleRef::operator=(const StringPool::StyleRef& rhs) {
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
  if (entry_->value != rhs.entry_->value) {
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

bool StringPool::StyleRef::operator!=(const StyleRef& rhs) const {
  return !operator==(rhs);
}

const StringPool::StyleEntry* StringPool::StyleRef::operator->() const {
  return entry_;
}

const StringPool::StyleEntry& StringPool::StyleRef::operator*() const {
  return *entry_;
}

size_t StringPool::StyleRef::index() const {
  return entry_->index_;
}

const StringPool::Context& StringPool::StyleRef::GetContext() const {
  return entry_->context;
}

StringPool::Ref StringPool::MakeRef(const StringPiece& str) {
  return MakeRefImpl(str, Context{}, true);
}

StringPool::Ref StringPool::MakeRef(const StringPiece& str, const Context& context) {
  return MakeRefImpl(str, context, true);
}

StringPool::Ref StringPool::MakeRefImpl(const StringPiece& str, const Context& context,
                                        bool unique) {
  if (unique) {
    auto range = indexed_strings_.equal_range(str);
    for (auto iter = range.first; iter != range.second; ++iter) {
      if (context.priority == iter->second->context.priority) {
        return Ref(iter->second);
      }
    }
  }

  std::unique_ptr<Entry> entry(new Entry());
  entry->value = str.to_string();
  entry->context = context;
  entry->index_ = strings_.size();
  entry->ref_ = 0;
  entry->pool_ = this;

  Entry* borrow = entry.get();
  strings_.emplace_back(std::move(entry));
  indexed_strings_.insert(std::make_pair(StringPiece(borrow->value), borrow));
  return Ref(borrow);
}

StringPool::Ref StringPool::MakeRef(const Ref& ref) {
  if (ref.entry_->pool_ == this) {
    return ref;
  }
  return MakeRef(ref.entry_->value, ref.entry_->context);
}

StringPool::StyleRef StringPool::MakeRef(const StyleString& str) {
  return MakeRef(str, Context{});
}

StringPool::StyleRef StringPool::MakeRef(const StyleString& str, const Context& context) {
  std::unique_ptr<StyleEntry> entry(new StyleEntry());
  entry->value = str.str;
  entry->context = context;
  entry->index_ = styles_.size();
  entry->ref_ = 0;
  for (const aapt::Span& span : str.spans) {
    entry->spans.emplace_back(Span{MakeRef(span.name), span.first_char, span.last_char});
  }

  StyleEntry* borrow = entry.get();
  styles_.emplace_back(std::move(entry));
  return StyleRef(borrow);
}

StringPool::StyleRef StringPool::MakeRef(const StyleRef& ref) {
  std::unique_ptr<StyleEntry> entry(new StyleEntry());
  entry->value = ref.entry_->value;
  entry->context = ref.entry_->context;
  entry->index_ = styles_.size();
  entry->ref_ = 0;
  for (const Span& span : ref.entry_->spans) {
    entry->spans.emplace_back(Span{MakeRef(*span.name), span.first_char, span.last_char});
  }

  StyleEntry* borrow = entry.get();
  styles_.emplace_back(std::move(entry));
  return StyleRef(borrow);
}

void StringPool::ReAssignIndices() {
  // Assign the style indices.
  const size_t style_len = styles_.size();
  for (size_t index = 0; index < style_len; index++) {
    styles_[index]->index_ = index;
  }

  // Assign the string indices.
  const size_t string_len = strings_.size();
  for (size_t index = 0; index < string_len; index++) {
    strings_[index]->index_ = index;
  }
}

void StringPool::Merge(StringPool&& pool) {
  // First, change the owning pool for the incoming strings.
  for (std::unique_ptr<Entry>& entry : pool.strings_) {
    entry->pool_ = this;
  }

  // Now move the styles, strings, and indices over.
  std::move(pool.styles_.begin(), pool.styles_.end(), std::back_inserter(styles_));
  pool.styles_.clear();
  std::move(pool.strings_.begin(), pool.strings_.end(), std::back_inserter(strings_));
  pool.strings_.clear();
  indexed_strings_.insert(pool.indexed_strings_.begin(), pool.indexed_strings_.end());
  pool.indexed_strings_.clear();

  ReAssignIndices();
}

void StringPool::HintWillAdd(size_t string_count, size_t style_count) {
  strings_.reserve(strings_.size() + string_count);
  styles_.reserve(styles_.size() + style_count);
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
                     [](const std::unique_ptr<Entry>& entry) -> bool { return entry->ref_ <= 0; });
  auto end_iter3 = std::remove_if(
      styles_.begin(), styles_.end(),
      [](const std::unique_ptr<StyleEntry>& entry) -> bool { return entry->ref_ <= 0; });

  // Remove the entries at the end or else we'll be accessing a deleted string from the StyleEntry.
  strings_.erase(end_iter2, strings_.end());
  styles_.erase(end_iter3, styles_.end());

  ReAssignIndices();
}

template <typename E>
static void SortEntries(
    std::vector<std::unique_ptr<E>>& entries,
    const std::function<int(const StringPool::Context&, const StringPool::Context&)>& cmp) {
  using UEntry = std::unique_ptr<E>;

  if (cmp != nullptr) {
    std::sort(entries.begin(), entries.end(), [&cmp](const UEntry& a, const UEntry& b) -> bool {
      int r = cmp(a->context, b->context);
      if (r == 0) {
        r = a->value.compare(b->value);
      }
      return r < 0;
    });
  } else {
    std::sort(entries.begin(), entries.end(),
              [](const UEntry& a, const UEntry& b) -> bool { return a->value < b->value; });
  }
}

void StringPool::Sort(const std::function<int(const Context&, const Context&)>& cmp) {
  SortEntries(styles_, cmp);
  SortEntries(strings_, cmp);
  ReAssignIndices();
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

/**
 * Returns the maximum possible string length that can be successfully encoded
 * using 2 units of the specified T.
 *    EncodeLengthMax<char> -> maximum unit length of 0x7FFF
 *    EncodeLengthMax<char16_t> -> maximum unit length of 0x7FFFFFFF
 **/
template <typename T>
static size_t EncodeLengthMax() {
  static_assert(std::is_integral<T>::value, "wat.");

  constexpr size_t kMask = 1 << ((sizeof(T) * 8 * 2) - 1);
  constexpr size_t max = kMask - 1;
  return max;
}

/**
 * Returns the number of units (1 or 2) needed to encode the string length
 * before writing the string.
 */
template <typename T>
static size_t EncodedLengthUnits(size_t length) {
  static_assert(std::is_integral<T>::value, "wat.");

  constexpr size_t kMask = 1 << ((sizeof(T) * 8) - 1);
  constexpr size_t kMaxSize = kMask - 1;
  return length > kMaxSize ? 2 : 1;
}

const std::string kStringTooLarge = "STRING_TOO_LARGE";

static bool EncodeString(const std::string& str, const bool utf8, BigBuffer* out,
                         IDiagnostics* diag) {
  if (utf8) {
    const std::string& encoded = str;
    const ssize_t utf16_length = utf8_to_utf16_length(
        reinterpret_cast<const uint8_t*>(encoded.data()), encoded.size());
    CHECK(utf16_length >= 0);

    // Make sure the lengths to be encoded do not exceed the maximum length that
    // can be encoded using chars
    if ((((size_t)encoded.size()) > EncodeLengthMax<char>())
        || (((size_t)utf16_length) > EncodeLengthMax<char>())) {

      diag->Error(DiagMessage() << "string too large to encode using UTF-8 "
          << "written instead as '" << kStringTooLarge << "'");

      EncodeString(kStringTooLarge, utf8, out, diag);
      return false;
    }

    const size_t total_size = EncodedLengthUnits<char>(utf16_length)
        + EncodedLengthUnits<char>(encoded.size()) + encoded.size() + 1;

    char* data = out->NextBlock<char>(total_size);

    // First encode the UTF16 string length.
    data = EncodeLength(data, utf16_length);

    // Now encode the size of the real UTF8 string.
    data = EncodeLength(data, encoded.size());
    strncpy(data, encoded.data(), encoded.size());

  } else {
    const std::u16string encoded = util::Utf8ToUtf16(str);
    const ssize_t utf16_length = encoded.size();

    // Make sure the length to be encoded does not exceed the maximum possible
    // length that can be encoded
    if (((size_t)utf16_length) > EncodeLengthMax<char16_t>()) {
      diag->Error(DiagMessage() << "string too large to encode using UTF-16 "
          << "written instead as '" << kStringTooLarge << "'");

      EncodeString(kStringTooLarge, utf8, out, diag);
      return false;
    }

    // Total number of 16-bit words to write.
    const size_t total_size = EncodedLengthUnits<char16_t>(utf16_length)
        + encoded.size() + 1;

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

  return true;
}

bool StringPool::Flatten(BigBuffer* out, const StringPool& pool, bool utf8,
                         IDiagnostics* diag) {
  bool no_error = true;
  const size_t start_index = out->size();
  android::ResStringPool_header* header = out->NextBlock<android::ResStringPool_header>();
  header->header.type = util::HostToDevice16(android::RES_STRING_POOL_TYPE);
  header->header.headerSize = util::HostToDevice16(sizeof(*header));
  header->stringCount = util::HostToDevice32(pool.size());
  header->styleCount = util::HostToDevice32(pool.styles_.size());
  if (utf8) {
    header->flags |= android::ResStringPool_header::UTF8_FLAG;
  }

  uint32_t* indices = pool.size() != 0 ? out->NextBlock<uint32_t>(pool.size()) : nullptr;
  uint32_t* style_indices =
      pool.styles_.size() != 0 ? out->NextBlock<uint32_t>(pool.styles_.size()) : nullptr;

  const size_t before_strings_index = out->size();
  header->stringsStart = before_strings_index - start_index;

  // Styles always come first.
  for (const std::unique_ptr<StyleEntry>& entry : pool.styles_) {
    *indices++ = out->size() - before_strings_index;
    no_error = EncodeString(entry->value, utf8, out, diag) && no_error;
  }

  for (const std::unique_ptr<Entry>& entry : pool.strings_) {
    *indices++ = out->size() - before_strings_index;
    no_error = EncodeString(entry->value, utf8, out, diag) && no_error;
  }

  out->Align4();

  if (style_indices != nullptr) {
    const size_t before_styles_index = out->size();
    header->stylesStart = util::HostToDevice32(before_styles_index - start_index);

    for (const std::unique_ptr<StyleEntry>& entry : pool.styles_) {
      *style_indices++ = out->size() - before_styles_index;

      if (!entry->spans.empty()) {
        android::ResStringPool_span* span =
            out->NextBlock<android::ResStringPool_span>(entry->spans.size());
        for (const Span& s : entry->spans) {
          span->name.index = util::HostToDevice32(s.name.index());
          span->firstChar = util::HostToDevice32(s.first_char);
          span->lastChar = util::HostToDevice32(s.last_char);
          span++;
        }
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
  header->header.size = util::HostToDevice32(out->size() - start_index);
  return no_error;
}

bool StringPool::FlattenUtf8(BigBuffer* out, const StringPool& pool, IDiagnostics* diag) {
  return Flatten(out, pool, true, diag);
}

bool StringPool::FlattenUtf16(BigBuffer* out, const StringPool& pool, IDiagnostics* diag) {
  return Flatten(out, pool, false, diag);
}

}  // namespace aapt
