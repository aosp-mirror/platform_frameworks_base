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
#include "util/Util.h"

#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>

namespace aapt {

void SymbolTable::appendSource(std::unique_ptr<ISymbolSource> source) {
    mSources.push_back(std::move(source));

    // We do not clear the cache, because sources earlier in the list take precedent.
}

void SymbolTable::prependSource(std::unique_ptr<ISymbolSource> source) {
    mSources.insert(mSources.begin(), std::move(source));

    // We must clear the cache in case we did a lookup before adding this resource.
    mCache.clear();
}

const SymbolTable::Symbol* SymbolTable::findByName(const ResourceName& name) {
    if (const std::shared_ptr<Symbol>& s = mCache.get(name)) {
        return s.get();
    }

    // We did not find it in the cache, so look through the sources.
    for (auto& symbolSource : mSources) {
        std::unique_ptr<Symbol> symbol = symbolSource->findByName(name);
        if (symbol) {
            // Take ownership of the symbol into a shared_ptr. We do this because LruCache
            // doesn't support unique_ptr.
            std::shared_ptr<Symbol> sharedSymbol = std::shared_ptr<Symbol>(symbol.release());
            mCache.put(name, sharedSymbol);

            if (sharedSymbol->id) {
                // The symbol has an ID, so we can also cache this!
                mIdCache.put(sharedSymbol->id.value(), sharedSymbol);
            }
            return sharedSymbol.get();
        }
    }
    return nullptr;
}

const SymbolTable::Symbol* SymbolTable::findById(ResourceId id) {
    if (const std::shared_ptr<Symbol>& s = mIdCache.get(id)) {
        return s.get();
    }

    // We did not find it in the cache, so look through the sources.
    for (auto& symbolSource : mSources) {
        std::unique_ptr<Symbol> symbol = symbolSource->findById(id);
        if (symbol) {
            // Take ownership of the symbol into a shared_ptr. We do this because LruCache
            // doesn't support unique_ptr.
            std::shared_ptr<Symbol> sharedSymbol = std::shared_ptr<Symbol>(symbol.release());
            mIdCache.put(id, sharedSymbol);
            return sharedSymbol.get();
        }
    }
    return nullptr;
}

const SymbolTable::Symbol* SymbolTable::findByReference(const Reference& ref) {
    // First try the ID. This is because when we lookup by ID, we only fill in the ID cache.
    // Looking up by name fills in the name and ID cache. So a cache miss will cause a failed
    // ID lookup, then a successfull name lookup. Subsequent look ups will hit immediately
    // because the ID is cached too.
    //
    // If we looked up by name first, a cache miss would mean we failed to lookup by name, then
    // succeeded to lookup by ID. Subsequent lookups will miss then hit.
    const SymbolTable::Symbol* symbol = nullptr;
    if (ref.id) {
        symbol = findById(ref.id.value());
    }

    if (ref.name && !symbol) {
        symbol = findByName(ref.name.value());
    }
    return symbol;
}

std::unique_ptr<SymbolTable::Symbol> ResourceTableSymbolSource::findByName(
        const ResourceName& name) {
    Maybe<ResourceTable::SearchResult> result = mTable->findResource(name);
    if (!result) {
        if (name.type == ResourceType::kAttr) {
            // Recurse and try looking up a private attribute.
            return findByName(ResourceName(name.package, ResourceType::kAttrPrivate, name.entry));
        }
        return {};
    }

    ResourceTable::SearchResult sr = result.value();

    std::unique_ptr<SymbolTable::Symbol> symbol = util::make_unique<SymbolTable::Symbol>();
    symbol->isPublic = (sr.entry->symbolStatus.state == SymbolState::kPublic);

    if (sr.package->id && sr.type->id && sr.entry->id) {
        symbol->id = ResourceId(sr.package->id.value(), sr.type->id.value(), sr.entry->id.value());
    }

    if (name.type == ResourceType::kAttr || name.type == ResourceType::kAttrPrivate) {
        const ConfigDescription kDefaultConfig;
        ResourceConfigValue* configValue = sr.entry->findValue(kDefaultConfig);
        if (configValue) {
            // This resource has an Attribute.
            if (Attribute* attr = valueCast<Attribute>(configValue->value.get())) {
                symbol->attribute = std::make_shared<Attribute>(*attr);
            } else {
                return {};
            }
        }
    }
    return symbol;
}

bool AssetManagerSymbolSource::addAssetPath(const StringPiece& path) {
    int32_t cookie = 0;
    return mAssets.addAssetPath(android::String8(path.data(), path.size()), &cookie);
}

static std::unique_ptr<SymbolTable::Symbol> lookupAttributeInTable(const android::ResTable& table,
                                                                   ResourceId id) {
    // Try as a bag.
    const android::ResTable::bag_entry* entry;
    ssize_t count = table.lockBag(id.id, &entry);
    if (count < 0) {
        table.unlockBag(entry);
        return nullptr;
    }

    // We found a resource.
    std::unique_ptr<SymbolTable::Symbol> s = util::make_unique<SymbolTable::Symbol>();
    s->id = id;

    // Check to see if it is an attribute.
    for (size_t i = 0; i < (size_t) count; i++) {
        if (entry[i].map.name.ident == android::ResTable_map::ATTR_TYPE) {
            s->attribute = std::make_shared<Attribute>(false);
            s->attribute->typeMask = entry[i].map.value.data;
            break;
        }
    }

    if (s->attribute) {
        for (size_t i = 0; i < (size_t) count; i++) {
            const android::ResTable_map& mapEntry = entry[i].map;
            if (Res_INTERNALID(mapEntry.name.ident)) {
                switch (mapEntry.name.ident) {
                case android::ResTable_map::ATTR_MIN:
                    s->attribute->minInt = static_cast<int32_t>(mapEntry.value.data);
                    break;
                case android::ResTable_map::ATTR_MAX:
                    s->attribute->maxInt = static_cast<int32_t>(mapEntry.value.data);
                    break;
                }
                continue;
            }

            android::ResTable::resource_name entryName;
            if (!table.getResourceName(mapEntry.name.ident, false, &entryName)) {
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
            symbol.symbol.name = ResourceName(
                    StringPiece16(entryName.package, entryName.packageLen),
                    *parsedType,
                    StringPiece16(entryName.name, entryName.nameLen));
            symbol.symbol.id = ResourceId(mapEntry.name.ident);
            symbol.value = mapEntry.value.data;
            s->attribute->symbols.push_back(std::move(symbol));
        }
    }
    table.unlockBag(entry);
    return s;
}

std::unique_ptr<SymbolTable::Symbol> AssetManagerSymbolSource::findByName(
        const ResourceName& name) {
    const android::ResTable& table = mAssets.getResources(false);
    StringPiece16 typeStr = toString(name.type);
    uint32_t typeSpecFlags = 0;
    ResourceId resId = table.identifierForName(name.entry.data(), name.entry.size(),
                                               typeStr.data(), typeStr.size(),
                                               name.package.data(), name.package.size(),
                                               &typeSpecFlags);
    if (!resId.isValid()) {
        return {};
    }

    std::unique_ptr<SymbolTable::Symbol> s;
    if (name.type == ResourceType::kAttr) {
        s = lookupAttributeInTable(table, resId);
    } else {
        s = util::make_unique<SymbolTable::Symbol>();
        s->id = resId;
    }

    if (s) {
        s->isPublic = (typeSpecFlags & android::ResTable_typeSpec::SPEC_PUBLIC) != 0;
        return s;
    }
    return {};
}

static Maybe<ResourceName> getResourceName(const android::ResTable& table, ResourceId id) {
    android::ResTable::resource_name resName = {};
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

std::unique_ptr<SymbolTable::Symbol> AssetManagerSymbolSource::findById(ResourceId id) {
    const android::ResTable& table = mAssets.getResources(false);
    Maybe<ResourceName> maybeName = getResourceName(table, id);
    if (!maybeName) {
        return {};
    }

    uint32_t typeSpecFlags = 0;
    table.getResourceFlags(id.id, &typeSpecFlags);

    std::unique_ptr<SymbolTable::Symbol> s;
    if (maybeName.value().type == ResourceType::kAttr) {
        s = lookupAttributeInTable(table, id);
    } else {
        s = util::make_unique<SymbolTable::Symbol>();
        s->id = id;
    }

    if (s) {
        s->isPublic = (typeSpecFlags & android::ResTable_typeSpec::SPEC_PUBLIC) != 0;
        return s;
    }
    return {};
}

std::unique_ptr<SymbolTable::Symbol> AssetManagerSymbolSource::findByReference(
        const Reference& ref) {
    // AssetManager always prefers IDs.
    if (ref.id) {
        return findById(ref.id.value());
    } else if (ref.name) {
        return findByName(ref.name.value());
    }
    return {};
}

} // namespace aapt
