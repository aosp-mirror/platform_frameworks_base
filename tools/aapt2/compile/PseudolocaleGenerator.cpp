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

#include "compile/PseudolocaleGenerator.h"

#include <algorithm>

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "compile/Pseudolocalizer.h"
#include "util/Util.h"

using ::android::ConfigDescription;
using ::android::StringPiece;
using ::android::StringPiece16;

namespace aapt {

// The struct that represents both Span objects and UntranslatableSections.
struct UnifiedSpan {
  // Only present for Span objects. If not present, this was an UntranslatableSection.
  Maybe<std::string> tag;

  // The UTF-16 index into the string where this span starts.
  uint32_t first_char;

  // The UTF-16 index into the string where this span ends, inclusive.
  uint32_t last_char;
};

inline static bool operator<(const UnifiedSpan& left, const UnifiedSpan& right) {
  if (left.first_char < right.first_char) {
    return true;
  } else if (left.first_char > right.first_char) {
    return false;
  } else if (left.last_char < right.last_char) {
    return true;
  }
  return false;
}

inline static UnifiedSpan SpanToUnifiedSpan(const StringPool::Span& span) {
  return UnifiedSpan{*span.name, span.first_char, span.last_char};
}

inline static UnifiedSpan UntranslatableSectionToUnifiedSpan(const UntranslatableSection& section) {
  return UnifiedSpan{
      {}, static_cast<uint32_t>(section.start), static_cast<uint32_t>(section.end) - 1};
}

// Merges the Span and UntranslatableSections of this StyledString into a single vector of
// UnifiedSpans. This will first check that the Spans are sorted in ascending order.
static std::vector<UnifiedSpan> MergeSpans(const StyledString& string) {
  // Ensure the Spans are sorted and converted.
  std::vector<UnifiedSpan> sorted_spans;
  sorted_spans.reserve(string.value->spans.size());
  std::transform(string.value->spans.begin(), string.value->spans.end(),
                 std::back_inserter(sorted_spans), SpanToUnifiedSpan);

  // Stable sort to ensure tag sequences like "<b><i>" are preserved.
  std::stable_sort(sorted_spans.begin(), sorted_spans.end());

  // Ensure the UntranslatableSections are sorted and converted.
  std::vector<UnifiedSpan> sorted_untranslatable_sections;
  sorted_untranslatable_sections.reserve(string.untranslatable_sections.size());
  std::transform(string.untranslatable_sections.begin(), string.untranslatable_sections.end(),
                 std::back_inserter(sorted_untranslatable_sections),
                 UntranslatableSectionToUnifiedSpan);
  std::sort(sorted_untranslatable_sections.begin(), sorted_untranslatable_sections.end());

  std::vector<UnifiedSpan> merged_spans;
  merged_spans.reserve(sorted_spans.size() + sorted_untranslatable_sections.size());
  auto span_iter = sorted_spans.begin();
  auto untranslatable_iter = sorted_untranslatable_sections.begin();
  while (span_iter != sorted_spans.end() &&
         untranslatable_iter != sorted_untranslatable_sections.end()) {
    if (*span_iter < *untranslatable_iter) {
      merged_spans.push_back(std::move(*span_iter));
      ++span_iter;
    } else {
      merged_spans.push_back(std::move(*untranslatable_iter));
      ++untranslatable_iter;
    }
  }

  while (span_iter != sorted_spans.end()) {
    merged_spans.push_back(std::move(*span_iter));
    ++span_iter;
  }

  while (untranslatable_iter != sorted_untranslatable_sections.end()) {
    merged_spans.push_back(std::move(*untranslatable_iter));
    ++untranslatable_iter;
  }
  return merged_spans;
}

std::unique_ptr<StyledString> PseudolocalizeStyledString(StyledString* string,
                                                         Pseudolocalizer::Method method,
                                                         StringPool* pool) {
  Pseudolocalizer localizer(method);

  // Collect the spans and untranslatable sections into one set of spans, sorted by first_char.
  // This will effectively subdivide the string into multiple sections that can be individually
  // pseudolocalized, while keeping the span indices synchronized.
  std::vector<UnifiedSpan> merged_spans = MergeSpans(*string);

  // All Span indices are UTF-16 based, according to the resources.arsc format expected by the
  // runtime. So we will do all our processing in UTF-16, then convert back.
  const std::u16string text16 = util::Utf8ToUtf16(string->value->value);

  // Convenient wrapper around the text that allows us to work with StringPieces.
  const StringPiece16 text(text16);

  // The new string.
  std::string new_string = localizer.Start();

  // The stack that keeps track of what nested Span we're in.
  std::vector<size_t> span_stack;

  // The current position in the original text.
  uint32_t cursor = 0u;

  // The current position in the new text.
  uint32_t new_cursor = utf8_to_utf16_length(reinterpret_cast<const uint8_t*>(new_string.data()),
                                             new_string.size(), false);

  // We assume no nesting of untranslatable sections, since XLIFF doesn't allow it.
  bool translatable = true;
  size_t span_idx = 0u;
  while (span_idx < merged_spans.size() || !span_stack.empty()) {
    UnifiedSpan* span = span_idx >= merged_spans.size() ? nullptr : &merged_spans[span_idx];
    UnifiedSpan* parent_span = span_stack.empty() ? nullptr : &merged_spans[span_stack.back()];

    if (span != nullptr) {
      if (parent_span == nullptr || parent_span->last_char > span->first_char) {
        // There is no parent, or this span is the child of the parent.
        // Pseudolocalize all the text until this span.
        const StringPiece16 substr = text.substr(cursor, span->first_char - cursor);
        cursor += substr.size();

        // Pseudolocalize the substring.
        std::string new_substr = util::Utf16ToUtf8(substr);
        if (translatable) {
          new_substr = localizer.Text(new_substr);
        }
        new_cursor += utf8_to_utf16_length(reinterpret_cast<const uint8_t*>(new_substr.data()),
                                           new_substr.size(), false);
        new_string += new_substr;

        // Rewrite the first_char.
        span->first_char = new_cursor;
        if (!span->tag) {
          // An untranslatable section has begun!
          translatable = false;
        }
        span_stack.push_back(span_idx);
        ++span_idx;
        continue;
      }
    }

    if (parent_span != nullptr) {
      // There is a parent, and either this span is not a child of it, or there are no more spans.
      // Pop this off the stack.
      const StringPiece16 substr = text.substr(cursor, parent_span->last_char - cursor + 1);
      cursor += substr.size();

      // Pseudolocalize the substring.
      std::string new_substr = util::Utf16ToUtf8(substr);
      if (translatable) {
        new_substr = localizer.Text(new_substr);
      }
      new_cursor += utf8_to_utf16_length(reinterpret_cast<const uint8_t*>(new_substr.data()),
                                         new_substr.size(), false);
      new_string += new_substr;

      parent_span->last_char = new_cursor - 1;
      if (parent_span->tag) {
        // An end to an untranslatable section.
        translatable = true;
      }
      span_stack.pop_back();
    }
  }

  // Finish the pseudolocalization at the end of the string.
  new_string += localizer.Text(util::Utf16ToUtf8(text.substr(cursor, text.size() - cursor)));
  new_string += localizer.End();

  StyleString localized;
  localized.str = std::move(new_string);

  // Convert the UnifiedSpans into regular Spans, skipping the UntranslatableSections.
  for (UnifiedSpan& span : merged_spans) {
    if (span.tag) {
      localized.spans.push_back(Span{std::move(span.tag.value()), span.first_char, span.last_char});
    }
  }
  return util::make_unique<StyledString>(pool->MakeRef(localized));
}

namespace {

class Visitor : public ValueVisitor {
 public:
  // Either value or item will be populated upon visiting the value.
  std::unique_ptr<Value> value;
  std::unique_ptr<Item> item;

  Visitor(StringPool* pool, Pseudolocalizer::Method method)
      : pool_(pool), method_(method), localizer_(method) {}

  void Visit(Plural* plural) override {
    std::unique_ptr<Plural> localized = util::make_unique<Plural>();
    for (size_t i = 0; i < plural->values.size(); i++) {
      Visitor sub_visitor(pool_, method_);
      if (plural->values[i]) {
        plural->values[i]->Accept(&sub_visitor);
        if (sub_visitor.item) {
          localized->values[i] = std::move(sub_visitor.item);
        } else {
          localized->values[i] = std::unique_ptr<Item>(plural->values[i]->Clone(pool_));
        }
      }
    }
    localized->SetSource(plural->GetSource());
    localized->SetWeak(true);
    value = std::move(localized);
  }

  void Visit(String* string) override {
    const StringPiece original_string = *string->value;
    std::string result = localizer_.Start();

    // Pseudolocalize only the translatable sections.
    size_t start = 0u;
    for (const UntranslatableSection& section : string->untranslatable_sections) {
      // Pseudolocalize the content before the untranslatable section.
      const size_t len = section.start - start;
      if (len > 0u) {
        result += localizer_.Text(original_string.substr(start, len));
      }

      // Copy the untranslatable content.
      result += original_string.substr(section.start, section.end - section.start);
      start = section.end;
    }

    // Pseudolocalize the content after the last untranslatable section.
    if (start != original_string.size()) {
      const size_t len = original_string.size() - start;
      result += localizer_.Text(original_string.substr(start, len));
    }
    result += localizer_.End();

    std::unique_ptr<String> localized = util::make_unique<String>(pool_->MakeRef(result));
    localized->SetSource(string->GetSource());
    localized->SetWeak(true);
    item = std::move(localized);
  }

  void Visit(StyledString* string) override {
    item = PseudolocalizeStyledString(string, method_, pool_);
    item->SetSource(string->GetSource());
    item->SetWeak(true);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(Visitor);

  StringPool* pool_;
  Pseudolocalizer::Method method_;
  Pseudolocalizer localizer_;
};

ConfigDescription ModifyConfigForPseudoLocale(const ConfigDescription& base,
                                              Pseudolocalizer::Method m) {
  ConfigDescription modified = base;
  switch (m) {
    case Pseudolocalizer::Method::kAccent:
      modified.language[0] = 'e';
      modified.language[1] = 'n';
      modified.country[0] = 'X';
      modified.country[1] = 'A';
      break;

    case Pseudolocalizer::Method::kBidi:
      modified.language[0] = 'a';
      modified.language[1] = 'r';
      modified.country[0] = 'X';
      modified.country[1] = 'B';
      break;
    default:
      break;
  }
  return modified;
}

void PseudolocalizeIfNeeded(const Pseudolocalizer::Method method,
                            ResourceConfigValue* original_value,
                            StringPool* pool, ResourceEntry* entry) {
  Visitor visitor(pool, method);
  original_value->value->Accept(&visitor);

  std::unique_ptr<Value> localized_value;
  if (visitor.value) {
    localized_value = std::move(visitor.value);
  } else if (visitor.item) {
    localized_value = std::move(visitor.item);
  }

  if (!localized_value) {
    return;
  }

  ConfigDescription config_with_accent =
      ModifyConfigForPseudoLocale(original_value->config, method);

  ResourceConfigValue* new_config_value =
      entry->FindOrCreateValue(config_with_accent, original_value->product);
  if (!new_config_value->value) {
    // Only use auto-generated pseudo-localization if none is defined.
    new_config_value->value = std::move(localized_value);
  }
}

// A value is pseudolocalizable if it does not define a locale (or is the default locale) and is
// translatable.
static bool IsPseudolocalizable(ResourceConfigValue* config_value) {
  const int diff = config_value->config.diff(ConfigDescription::DefaultConfig());
  if (diff & ConfigDescription::CONFIG_LOCALE) {
    return false;
  }
  return config_value->value->IsTranslatable();
}

}  // namespace

bool PseudolocaleGenerator::Consume(IAaptContext* context, ResourceTable* table) {
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        std::vector<ResourceConfigValue*> values = entry->FindValuesIf(IsPseudolocalizable);
        for (ResourceConfigValue* value : values) {
          PseudolocalizeIfNeeded(Pseudolocalizer::Method::kAccent, value, &table->string_pool,
                                 entry.get());
          PseudolocalizeIfNeeded(Pseudolocalizer::Method::kBidi, value, &table->string_pool,
                                 entry.get());
        }
      }
    }
  }
  return true;
}

}  // namespace aapt
