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

#include "Linker.h"
#include "Logger.h"
#include "NameMangler.h"
#include "Resolver.h"
#include "ResourceParser.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "StringPiece.h"
#include "Util.h"

#include <androidfw/AssetManager.h>
#include <array>
#include <bitset>
#include <iostream>
#include <map>
#include <ostream>
#include <set>
#include <sstream>
#include <tuple>
#include <vector>

namespace aapt {

Linker::Args::Args(const ResourceNameRef& r, const SourceLine& s) : referrer(r), source(s) {
}

Linker::Linker(const std::shared_ptr<ResourceTable>& table,
               const std::shared_ptr<IResolver>& resolver, const Options& options) :
        mResolver(resolver), mTable(table), mOptions(options), mError(false) {
}

bool Linker::linkAndValidate() {
    std::bitset<256> usedTypeIds;
    std::array<std::set<uint16_t>, 256> usedIds;
    usedTypeIds.set(0);

    // Collect which resource IDs are already taken.
    for (auto& type : *mTable) {
        if (type->typeId != ResourceTableType::kUnsetTypeId) {
            // The ID for this type has already been set. We
            // mark this ID as taken so we don't re-assign it
            // later.
            usedTypeIds.set(type->typeId);
        }

        for (auto& entry : type->entries) {
            if (type->typeId != ResourceTableType::kUnsetTypeId &&
                    entry->entryId != ResourceEntry::kUnsetEntryId) {
                // The ID for this entry has already been set. We
                // mark this ID as taken so we don't re-assign it
                // later.
                usedIds[type->typeId].insert(entry->entryId);
            }
        }
    }

    // Assign resource IDs that are available.
    size_t nextTypeIndex = 0;
    for (auto& type : *mTable) {
        if (type->typeId == ResourceTableType::kUnsetTypeId) {
            while (nextTypeIndex < usedTypeIds.size() && usedTypeIds[nextTypeIndex]) {
                nextTypeIndex++;
            }
            type->typeId = nextTypeIndex++;
        }

        const auto endEntryIter = std::end(usedIds[type->typeId]);
        auto nextEntryIter = std::begin(usedIds[type->typeId]);
        size_t nextIndex = 0;
        for (auto& entry : type->entries) {
            if (entry->entryId == ResourceTableType::kUnsetTypeId) {
                while (nextEntryIter != endEntryIter &&
                        nextIndex == *nextEntryIter) {
                    nextIndex++;
                    ++nextEntryIter;
                }
                entry->entryId = nextIndex++;
            }
        }
    }

    // Now do reference linking.
    for (auto& type : *mTable) {
        for (auto& entry : type->entries) {
            if (entry->publicStatus.isPublic && entry->values.empty()) {
                // A public resource has no values. It will not be encoded
                // properly without a symbol table. This is a unresolved symbol.
                addUnresolvedSymbol(ResourceNameRef{
                        mTable->getPackage(), type->type, entry->name },
                        entry->publicStatus.source);
                continue;
            }

            for (auto& valueConfig : entry->values) {
                // Dispatch to the right method of this linker
                // based on the value's type.
                valueConfig.value->accept(*this, Args{
                        ResourceNameRef{ mTable->getPackage(), type->type, entry->name },
                        valueConfig.source
                });
            }
        }
    }
    return !mError;
}

const Linker::ResourceNameToSourceMap& Linker::getUnresolvedReferences() const {
    return mUnresolvedSymbols;
}

void Linker::doResolveReference(Reference& reference, const SourceLine& source) {
    Maybe<ResourceId> result = mResolver->findId(reference.name);
    if (!result) {
        addUnresolvedSymbol(reference.name, source);
        return;
    }
    assert(result.value().isValid());

    if (mOptions.linkResourceIds) {
        reference.id = result.value();
    } else {
        reference.id = 0;
    }
}

const Attribute* Linker::doResolveAttribute(Reference& attribute, const SourceLine& source) {
    Maybe<IResolver::Entry> result = mResolver->findAttribute(attribute.name);
    if (!result || !result.value().attr) {
        addUnresolvedSymbol(attribute.name, source);
        return nullptr;
    }

    const IResolver::Entry& entry = result.value();
    assert(entry.id.isValid());

    if (mOptions.linkResourceIds) {
        attribute.id = entry.id;
    } else {
        attribute.id = 0;
    }
    return entry.attr;
}

void Linker::visit(Reference& reference, ValueVisitorArgs& a) {
    Args& args = static_cast<Args&>(a);

    if (reference.name.entry.empty()) {
        // We can't have a completely bad reference.
        if (!reference.id.isValid()) {
            Logger::error() << "srsly? " << args.referrer << std::endl;
            assert(reference.id.isValid());
        }

        // This reference has no name but has an ID.
        // It is a really bad error to have no name and have the same
        // package ID.
        assert(reference.id.packageId() != mTable->getPackageId());

        // The reference goes outside this package, let it stay as a
        // resource ID because it will not change.
        return;
    }

    doResolveReference(reference, args.source);

    // TODO(adamlesinski): Verify the referencedType is another reference
    // or a compatible primitive.
}

void Linker::processAttributeValue(const ResourceNameRef& name, const SourceLine& source,
        const Attribute& attr, std::unique_ptr<Item>& value) {
    std::unique_ptr<Item> convertedValue;
    visitFunc<RawString>(*value, [&](RawString& str) {
        // This is a raw string, so check if it can be converted to anything.
        // We can NOT swap value with the converted value in here, since
        // we called through the original value.

        auto onCreateReference = [&](const ResourceName& name) {
            // We should never get here. All references would have been
            // parsed in the parser phase.
            assert(false);
        };

        convertedValue = ResourceParser::parseItemForAttribute(*str.value, attr,
                                                               onCreateReference);
        if (!convertedValue && attr.typeMask & android::ResTable_map::TYPE_STRING) {
            // Last effort is to parse as a string.
            util::StringBuilder builder;
            builder.append(*str.value);
            if (builder) {
                convertedValue = util::make_unique<String>(
                        mTable->getValueStringPool().makeRef(builder.str()));
            }
        }
    });

    if (convertedValue) {
        value = std::move(convertedValue);
    }

    // Process this new or old value (it can be a reference!).
    value->accept(*this, Args{ name, source });

    // Flatten the value to see what resource type it is.
    android::Res_value resValue;
    value->flatten(resValue);

    // Always allow references.
    const uint32_t typeMask = attr.typeMask | android::ResTable_map::TYPE_REFERENCE;
    if (!(typeMask & ResourceParser::androidTypeToAttributeTypeMask(resValue.dataType))) {
        Logger::error(source)
                << *value
                << " is not compatible with attribute "
                << attr
                << "."
                << std::endl;
        mError = true;
    }
}

void Linker::visit(Style& style, ValueVisitorArgs& a) {
    Args& args = static_cast<Args&>(a);

    if (style.parent.name.isValid() || style.parent.id.isValid()) {
        visit(style.parent, a);
    }

    for (Style::Entry& styleEntry : style.entries) {
        const Attribute* attr = doResolveAttribute(styleEntry.key, args.source);
        if (attr) {
            processAttributeValue(args.referrer, args.source, *attr, styleEntry.value);
        }
    }
}

void Linker::visit(Attribute& attr, ValueVisitorArgs& a) {
    static constexpr uint32_t kMask = android::ResTable_map::TYPE_ENUM |
            android::ResTable_map::TYPE_FLAGS;
    if (attr.typeMask & kMask) {
        for (auto& symbol : attr.symbols) {
            visit(symbol.symbol, a);
        }
    }
}

void Linker::visit(Styleable& styleable, ValueVisitorArgs& a) {
    for (auto& attrRef : styleable.entries) {
        visit(attrRef, a);
    }
}

void Linker::visit(Array& array, ValueVisitorArgs& a) {
    Args& args = static_cast<Args&>(a);

    for (auto& item : array.items) {
        item->accept(*this, Args{ args.referrer, args.source });
    }
}

void Linker::visit(Plural& plural, ValueVisitorArgs& a) {
    Args& args = static_cast<Args&>(a);

    for (auto& item : plural.values) {
        if (item) {
            item->accept(*this, Args{ args.referrer, args.source });
        }
    }
}

void Linker::addUnresolvedSymbol(const ResourceNameRef& name, const SourceLine& source) {
    mUnresolvedSymbols[name.toResourceName()].push_back(source);
}

} // namespace aapt
