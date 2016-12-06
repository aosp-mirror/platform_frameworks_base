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

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "compile/PseudolocaleGenerator.h"
#include "compile/Pseudolocalizer.h"

#include <algorithm>

namespace aapt {

std::unique_ptr<StyledString> pseudolocalizeStyledString(StyledString* string,
                                                         Pseudolocalizer::Method method,
                                                         StringPool* pool) {
    Pseudolocalizer localizer(method);

    const StringPiece16 originalText = *string->value->str;

    StyleString localized;

    // Copy the spans. We will update their offsets when we localize.
    localized.spans.reserve(string->value->spans.size());
    for (const StringPool::Span& span : string->value->spans) {
        localized.spans.push_back(Span{ *span.name, span.firstChar, span.lastChar });
    }

    // The ranges are all represented with a single value. This is the start of one range and
    // end of another.
    struct Range {
        size_t start;

        // Once the new string is localized, these are the pointers to the spans to adjust.
        // Since this struct represents the start of one range and end of another, we have
        // the two pointers respectively.
        uint32_t* updateStart;
        uint32_t* updateEnd;
    };

    auto cmp = [](const Range& r, size_t index) -> bool {
        return r.start < index;
    };

    // Construct the ranges. The ranges are represented like so: [0, 2, 5, 7]
    // The ranges are the spaces in between. In this example, with a total string length of 9,
    // the vector represents: (0,1], (2,4], (5,6], (7,9]
    //
    std::vector<Range> ranges;
    ranges.push_back(Range{ 0 });
    ranges.push_back(Range{ originalText.size() - 1 });
    for (size_t i = 0; i < string->value->spans.size(); i++) {
        const StringPool::Span& span = string->value->spans[i];

        // Insert or update the Range marker for the start of this span.
        auto iter = std::lower_bound(ranges.begin(), ranges.end(), span.firstChar, cmp);
        if (iter != ranges.end() && iter->start == span.firstChar) {
            iter->updateStart = &localized.spans[i].firstChar;
        } else {
            ranges.insert(iter,
                          Range{ span.firstChar, &localized.spans[i].firstChar, nullptr });
        }

        // Insert or update the Range marker for the end of this span.
        iter = std::lower_bound(ranges.begin(), ranges.end(), span.lastChar, cmp);
        if (iter != ranges.end() && iter->start == span.lastChar) {
            iter->updateEnd = &localized.spans[i].lastChar;
        } else {
            ranges.insert(iter,
                          Range{ span.lastChar, nullptr, &localized.spans[i].lastChar });
        }
    }

    localized.str += localizer.start();

    // Iterate over the ranges and localize each section.
    for (size_t i = 0; i < ranges.size(); i++) {
        const size_t start = ranges[i].start;
        size_t len = originalText.size() - start;
        if (i + 1 < ranges.size()) {
            len = ranges[i + 1].start - start;
        }

        if (ranges[i].updateStart) {
            *ranges[i].updateStart = localized.str.size();
        }

        if (ranges[i].updateEnd) {
            *ranges[i].updateEnd = localized.str.size();
        }

        localized.str += localizer.text(originalText.substr(start, len));
    }

    localized.str += localizer.end();

    std::unique_ptr<StyledString> localizedString = util::make_unique<StyledString>(
            pool->makeRef(localized));
    localizedString->setSource(string->getSource());
    return localizedString;
}

namespace {

struct Visitor : public RawValueVisitor {
    StringPool* mPool;
    Pseudolocalizer::Method mMethod;
    Pseudolocalizer mLocalizer;

    // Either value or item will be populated upon visiting the value.
    std::unique_ptr<Value> mValue;
    std::unique_ptr<Item> mItem;

    Visitor(StringPool* pool, Pseudolocalizer::Method method) :
            mPool(pool), mMethod(method), mLocalizer(method) {
    }

    void visit(Plural* plural) override {
        std::unique_ptr<Plural> localized = util::make_unique<Plural>();
        for (size_t i = 0; i < plural->values.size(); i++) {
            Visitor subVisitor(mPool, mMethod);
            if (plural->values[i]) {
                plural->values[i]->accept(&subVisitor);
                if (subVisitor.mValue) {
                    localized->values[i] = std::move(subVisitor.mItem);
                } else {
                    localized->values[i] = std::unique_ptr<Item>(plural->values[i]->clone(mPool));
                }
            }
        }
        localized->setSource(plural->getSource());
        localized->setWeak(true);
        mValue = std::move(localized);
    }

    void visit(String* string) override {
        std::u16string result = mLocalizer.start() + mLocalizer.text(*string->value) +
                mLocalizer.end();
        std::unique_ptr<String> localized = util::make_unique<String>(mPool->makeRef(result));
        localized->setSource(string->getSource());
        localized->setWeak(true);
        mItem = std::move(localized);
    }

    void visit(StyledString* string) override {
        mItem = pseudolocalizeStyledString(string, mMethod, mPool);
        mItem->setWeak(true);
    }
};

ConfigDescription modifyConfigForPseudoLocale(const ConfigDescription& base,
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

void pseudolocalizeIfNeeded(const Pseudolocalizer::Method method,
                            ResourceConfigValue* originalValue,
                            StringPool* pool,
                            ResourceEntry* entry) {
    Visitor visitor(pool, method);
    originalValue->value->accept(&visitor);

    std::unique_ptr<Value> localizedValue;
    if (visitor.mValue) {
        localizedValue = std::move(visitor.mValue);
    } else if (visitor.mItem) {
        localizedValue = std::move(visitor.mItem);
    }

    if (!localizedValue) {
        return;
    }

    ConfigDescription configWithAccent = modifyConfigForPseudoLocale(
            originalValue->config, method);

    ResourceConfigValue* newConfigValue = entry->findOrCreateValue(
            configWithAccent, originalValue->product);
    if (!newConfigValue->value) {
        // Only use auto-generated pseudo-localization if none is defined.
        newConfigValue->value = std::move(localizedValue);
    }
}

/**
 * A value is pseudolocalizable if it does not define a locale (or is the default locale)
 * and is translateable.
 */
static bool isPseudolocalizable(ResourceConfigValue* configValue) {
    const int diff = configValue->config.diff(ConfigDescription::defaultConfig());
    if (diff & ConfigDescription::CONFIG_LOCALE) {
        return false;
    }
    return configValue->value->isTranslateable();
}

} // namespace

bool PseudolocaleGenerator::consume(IAaptContext* context, ResourceTable* table) {
    for (auto& package : table->packages) {
        for (auto& type : package->types) {
            for (auto& entry : type->entries) {
                std::vector<ResourceConfigValue*> values = entry->findValuesIf(isPseudolocalizable);

                for (ResourceConfigValue* value : values) {
                    pseudolocalizeIfNeeded(Pseudolocalizer::Method::kAccent, value,
                                           &table->stringPool, entry.get());
                    pseudolocalizeIfNeeded(Pseudolocalizer::Method::kBidi, value,
                                           &table->stringPool, entry.get());
                }
            }
        }
    }
    return true;
}

} // namespace aapt
