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

using android::StringPiece;

namespace aapt {

std::unique_ptr<StyledString> PseudolocalizeStyledString(
    StyledString* string, Pseudolocalizer::Method method, StringPool* pool) {
  Pseudolocalizer localizer(method);

  const StringPiece original_text = *string->value->str;

  StyleString localized;

  // Copy the spans. We will update their offsets when we localize.
  localized.spans.reserve(string->value->spans.size());
  for (const StringPool::Span& span : string->value->spans) {
    localized.spans.push_back(
        Span{*span.name, span.first_char, span.last_char});
  }

  // The ranges are all represented with a single value. This is the start of
  // one range and
  // end of another.
  struct Range {
    size_t start;

    // Once the new string is localized, these are the pointers to the spans to
    // adjust.
    // Since this struct represents the start of one range and end of another,
    // we have
    // the two pointers respectively.
    uint32_t* update_start;
    uint32_t* update_end;
  };

  auto cmp = [](const Range& r, size_t index) -> bool {
    return r.start < index;
  };

  // Construct the ranges. The ranges are represented like so: [0, 2, 5, 7]
  // The ranges are the spaces in between. In this example, with a total string
  // length of 9,
  // the vector represents: (0,1], (2,4], (5,6], (7,9]
  //
  std::vector<Range> ranges;
  ranges.push_back(Range{0});
  ranges.push_back(Range{original_text.size() - 1});
  for (size_t i = 0; i < string->value->spans.size(); i++) {
    const StringPool::Span& span = string->value->spans[i];

    // Insert or update the Range marker for the start of this span.
    auto iter =
        std::lower_bound(ranges.begin(), ranges.end(), span.first_char, cmp);
    if (iter != ranges.end() && iter->start == span.first_char) {
      iter->update_start = &localized.spans[i].first_char;
    } else {
      ranges.insert(iter, Range{span.first_char, &localized.spans[i].first_char,
                                nullptr});
    }

    // Insert or update the Range marker for the end of this span.
    iter = std::lower_bound(ranges.begin(), ranges.end(), span.last_char, cmp);
    if (iter != ranges.end() && iter->start == span.last_char) {
      iter->update_end = &localized.spans[i].last_char;
    } else {
      ranges.insert(
          iter, Range{span.last_char, nullptr, &localized.spans[i].last_char});
    }
  }

  localized.str += localizer.Start();

  // Iterate over the ranges and localize each section.
  for (size_t i = 0; i < ranges.size(); i++) {
    const size_t start = ranges[i].start;
    size_t len = original_text.size() - start;
    if (i + 1 < ranges.size()) {
      len = ranges[i + 1].start - start;
    }

    if (ranges[i].update_start) {
      *ranges[i].update_start = localized.str.size();
    }

    if (ranges[i].update_end) {
      *ranges[i].update_end = localized.str.size();
    }

    localized.str += localizer.Text(original_text.substr(start, len));
  }

  localized.str += localizer.End();

  std::unique_ptr<StyledString> localized_string =
      util::make_unique<StyledString>(pool->MakeRef(localized));
  localized_string->SetSource(string->GetSource());
  return localized_string;
}

namespace {

class Visitor : public RawValueVisitor {
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
        if (sub_visitor.value) {
          localized->values[i] = std::move(sub_visitor.item);
        } else {
          localized->values[i] =
              std::unique_ptr<Item>(plural->values[i]->Clone(pool_));
        }
      }
    }
    localized->SetSource(plural->GetSource());
    localized->SetWeak(true);
    value = std::move(localized);
  }

  void Visit(String* string) override {
    std::string result =
        localizer_.Start() + localizer_.Text(*string->value) + localizer_.End();
    std::unique_ptr<String> localized =
        util::make_unique<String>(pool_->MakeRef(result));
    localized->SetSource(string->GetSource());
    localized->SetWeak(true);
    item = std::move(localized);
  }

  void Visit(StyledString* string) override {
    item = PseudolocalizeStyledString(string, method_, pool_);
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

/**
 * A value is pseudolocalizable if it does not define a locale (or is the
 * default locale)
 * and is translateable.
 */
static bool IsPseudolocalizable(ResourceConfigValue* config_value) {
  const int diff =
      config_value->config.diff(ConfigDescription::DefaultConfig());
  if (diff & ConfigDescription::CONFIG_LOCALE) {
    return false;
  }
  return config_value->value->IsTranslateable();
}

}  // namespace

bool PseudolocaleGenerator::Consume(IAaptContext* context,
                                    ResourceTable* table) {
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        std::vector<ResourceConfigValue*> values =
            entry->FindValuesIf(IsPseudolocalizable);

        for (ResourceConfigValue* value : values) {
          PseudolocalizeIfNeeded(Pseudolocalizer::Method::kAccent, value,
                                 &table->string_pool, entry.get());
          PseudolocalizeIfNeeded(Pseudolocalizer::Method::kBidi, value,
                                 &table->string_pool, entry.get());
        }
      }
    }
  }
  return true;
}

}  // namespace aapt
