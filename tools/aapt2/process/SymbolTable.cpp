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

#include "ConfigDescription.h"
#include "Resource.h"
#include "ValueVisitor.h"

#include "process/SymbolTable.h"
#include "util/Comparators.h"
#include "util/Util.h"

#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>

namespace aapt {

const ISymbolTable::Symbol* SymbolTableWrapper::findByName(const ResourceName& name) {
    if (const std::shared_ptr<Symbol>& s = mCache.get(name)) {
        return s.get();
    }

    Maybe<ResourceTable::SearchResult> result = mTable->findResource(name);
    if (!result) {
        if (name.type == ResourceType::kAttr) {
            // Recurse and try looking up a private attribute.
            return findByName(ResourceName(name.package, ResourceType::kAttrPrivate, name.entry));
        }
        return {};
    }

    ResourceTable::SearchResult sr = result.value();

    // If no ID exists, we treat the symbol as missing. SymbolTables are used to
    // find symbols to link.
    if (!sr.package->id || !sr.type->id || !sr.entry->id) {
        return {};
    }

    std::shared_ptr<Symbol> symbol = std::make_shared<Symbol>();
    symbol->id = ResourceId(sr.package->id.value(), sr.type->id.value(), sr.entry->id.value());
    symbol->isPublic = (sr.entry->symbolStatus.state == SymbolState::kPublic);

    if (name.type == ResourceType::kAttr || name.type == ResourceType::kAttrPrivate) {
        const ConfigDescription kDefaultConfig;
        auto iter = std::lower_bound(sr.entry->values.begin(), sr.entry->values.end(),
                                     kDefaultConfig, cmp::lessThanConfig);

        if (iter != sr.entry->values.end() && iter->config == kDefaultConfig) {
            // This resource has an Attribute.
            if (Attribute* attr = valueCast<Attribute>(iter->value.get())) {
                symbol->attribute = std::unique_ptr<Attribute>(attr->clone(nullptr));
            } else {
                return {};
            }
        }
    }

    if (name.type == ResourceType::kAttrPrivate) {
        // Masquerade this entry as kAttr.
        mCache.put(ResourceName(name.package, ResourceType::kAttr, name.entry), symbol);
    } else {
        mCache.put(name, symbol);
    }
    return symbol.get();
}


static std::shared_ptr<ISymbolTable::Symbol> lookupAttributeInTable(const android::ResTable& table,
                                                                    ResourceId id) {
    // Try as a bag.
    const android::ResTable::bag_entry* entry;
    ssize_t count = table.lockBag(id.id, &entry);
    if (count < 0) {
        table.unlockBag(entry);
        return nullptr;
    }

    // We found a resource.
    std::shared_ptr<ISymbolTable::Symbol> s = std::make_shared<ISymbolTable::Symbol>();
    s->id = id;

    // Check to see if it is an attribute.
    for (size_t i = 0; i < (size_t) count; i++) {
        if (entry[i].map.name.ident == android::ResTable_map::ATTR_TYPE) {
            s->attribute = util::make_unique<Attribute>(false);
            s->attribute->typeMask = entry[i].map.value.data;
            break;
        }
    }

    if (s->attribute) {
        for (size_t i = 0; i < (size_t) count; i++) {
            if (!Res_INTERNALID(entry[i].map.name.ident)) {
                android::ResTable::resource_name entryName;
                if (!table.getResourceName(entry[i].map.name.ident, false, &entryName)) {
                    table.unlockBag(entry);
                    return nullptr;
                }

                const ResourceType* parsedType = parseResourceType(
                        StringPiece16(entryName.type, entryName.typeLen));
                if (!parsedType) {
                    table.unlockBag(entry);
                    return nullptr;
                }

                Attribute::Symbol symbol;
                symbol.symbol.name = ResourceNameRef(
                        StringPiece16(entryName.package, entryName.packageLen),
                        *parsedType,
                        StringPiece16(entryName.name, entryName.nameLen)).toResourceName();
                symbol.symbol.id = ResourceId(entry[i].map.name.ident);
                symbol.value = entry[i].map.value.data;
                s->attribute->symbols.push_back(std::move(symbol));
            }
        }
    }
    table.unlockBag(entry);
    return s;
}

const ISymbolTable::Symbol* AssetManagerSymbolTableBuilder::AssetManagerSymbolTable::findByName(
        const ResourceName& name) {
    if (const std::shared_ptr<Symbol>& s = mCache.get(name)) {
        return s.get();
    }

    for (const auto& asset : mAssets) {
        const android::ResTable& table = asset->getResources(false);
        StringPiece16 typeStr = toString(name.type);
        uint32_t typeSpecFlags = 0;
        ResourceId resId = table.identifierForName(name.entry.data(), name.entry.size(),
                                                   typeStr.data(), typeStr.size(),
                                                   name.package.data(), name.package.size(),
                                                   &typeSpecFlags);
        if (!resId.isValid()) {
            continue;
        }

        std::shared_ptr<Symbol> s;
        if (name.type == ResourceType::kAttr) {
            s = lookupAttributeInTable(table, resId);
        } else {
            s = std::make_shared<Symbol>();
            s->id = resId;
        }

        if (s) {
            s->isPublic = (typeSpecFlags & android::ResTable_typeSpec::SPEC_PUBLIC) != 0;
            mCache.put(name, s);
            return s.get();
        }
    }
    return nullptr;
}

static Maybe<ResourceName> getResourceName(const android::ResTable& table, ResourceId id) {
    android::ResTable::resource_name resName;
    if (!table.getResourceName(id.id, true, &resName)) {
        return {};
    }

    ResourceName name;
    if (resName.package) {
        name.package = StringPiece16(resName.package, resName.packageLen).toString();
    }

    const ResourceType* type;
    if (resName.type) {
        type = parseResourceType(StringPiece16(resName.type, resName.typeLen));

    } else if (resName.type8) {
        type = parseResourceType(util::utf8ToUtf16(StringPiece(resName.type8, resName.typeLen)));
    } else {
        return {};
    }

    if (!type) {
        return {};
    }

    name.type = *type;

    if (resName.name) {
        name.entry = StringPiece16(resName.name, resName.nameLen).toString();
    } else if (resName.name8) {
        name.entry = util::utf8ToUtf16(StringPiece(resName.name8, resName.nameLen));
    } else {
        return {};
    }

    return name;
}

const ISymbolTable::Symbol* AssetManagerSymbolTableBuilder::AssetManagerSymbolTable::findById(
        ResourceId id) {
    if (const std::shared_ptr<Symbol>& s = mIdCache.get(id)) {
        return s.get();
    }

    for (const auto& asset : mAssets) {
        const android::ResTable& table = asset->getResources(false);

        Maybe<ResourceName> maybeName = getResourceName(table, id);
        if (!maybeName) {
            continue;
        }

        uint32_t typeSpecFlags = 0;
        table.getResourceFlags(id.id, &typeSpecFlags);

        std::shared_ptr<Symbol> s;
        if (maybeName.value().type == ResourceType::kAttr) {
            s = lookupAttributeInTable(table, id);
        } else {
            s = std::make_shared<Symbol>();
            s->id = id;
        }

        if (s) {
            s->isPublic = (typeSpecFlags & android::ResTable_typeSpec::SPEC_PUBLIC) != 0;
            mIdCache.put(id, s);
            return s.get();
        }
    }
    return nullptr;
}

const ISymbolTable::Symbol* JoinedSymbolTableBuilder::JoinedSymbolTable::findByName(
        const ResourceName& name) {
    for (auto& symbolTable : mSymbolTables) {
        if (const Symbol* s = symbolTable->findByName(name)) {
            return s;
        }
    }
    return {};
}

const ISymbolTable::Symbol* JoinedSymbolTableBuilder::JoinedSymbolTable::findById(ResourceId id) {
    for (auto& symbolTable : mSymbolTables) {
        if (const Symbol* s = symbolTable->findById(id)) {
            return s;
        }
    }
    return {};
}

} // namespace aapt
