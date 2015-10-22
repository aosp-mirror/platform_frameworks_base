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
#include "NameMangler.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "util/Util.h"

#include <algorithm>
#include <androidfw/ResourceTypes.h>
#include <memory>
#include <string>
#include <tuple>

namespace aapt {

static bool compareConfigs(const ResourceConfigValue& lhs, const ConfigDescription& rhs) {
    return lhs.config < rhs;
}

static bool lessThanType(const std::unique_ptr<ResourceTableType>& lhs, ResourceType rhs) {
    return lhs->type < rhs;
}

template <typename T>
static bool lessThanStructWithName(const std::unique_ptr<T>& lhs,
                                   const StringPiece16& rhs) {
    return lhs->name.compare(0, lhs->name.size(), rhs.data(), rhs.size()) < 0;
}

ResourceTablePackage* ResourceTable::findPackage(const StringPiece16& name) {
    const auto last = packages.end();
    auto iter = std::lower_bound(packages.begin(), last, name,
                                 lessThanStructWithName<ResourceTablePackage>);
    if (iter != last && name == (*iter)->name) {
        return iter->get();
    }
    return nullptr;
}

ResourceTablePackage* ResourceTable::findPackageById(uint8_t id) {
    for (auto& package : packages) {
        if (package->id && package->id.value() == id) {
            return package.get();
        }
    }
    return nullptr;
}

ResourceTablePackage* ResourceTable::createPackage(const StringPiece16& name, Maybe<uint8_t> id) {
    ResourceTablePackage* package = findOrCreatePackage(name);
    if (id && !package->id) {
        package->id = id;
        return package;
    }

    if (id && package->id && package->id.value() != id.value()) {
        return nullptr;
    }
    return package;
}

ResourceTablePackage* ResourceTable::findOrCreatePackage(const StringPiece16& name) {
    const auto last = packages.end();
    auto iter = std::lower_bound(packages.begin(), last, name,
                                 lessThanStructWithName<ResourceTablePackage>);
    if (iter != last && name == (*iter)->name) {
        return iter->get();
    }

    std::unique_ptr<ResourceTablePackage> newPackage = util::make_unique<ResourceTablePackage>();
    newPackage->name = name.toString();
    return packages.emplace(iter, std::move(newPackage))->get();
}

ResourceTableType* ResourceTablePackage::findType(ResourceType type) {
    const auto last = types.end();
    auto iter = std::lower_bound(types.begin(), last, type, lessThanType);
    if (iter != last && (*iter)->type == type) {
        return iter->get();
    }
    return nullptr;
}

ResourceTableType* ResourceTablePackage::findOrCreateType(ResourceType type) {
    const auto last = types.end();
    auto iter = std::lower_bound(types.begin(), last, type, lessThanType);
    if (iter != last && (*iter)->type == type) {
        return iter->get();
    }
    return types.emplace(iter, new ResourceTableType{ type })->get();
}

ResourceEntry* ResourceTableType::findEntry(const StringPiece16& name) {
    const auto last = entries.end();
    auto iter = std::lower_bound(entries.begin(), last, name,
                                 lessThanStructWithName<ResourceEntry>);
    if (iter != last && name == (*iter)->name) {
        return iter->get();
    }
    return nullptr;
}

ResourceEntry* ResourceTableType::findOrCreateEntry(const StringPiece16& name) {
    auto last = entries.end();
    auto iter = std::lower_bound(entries.begin(), last, name,
                                 lessThanStructWithName<ResourceEntry>);
    if (iter != last && name == (*iter)->name) {
        return iter->get();
    }
    return entries.emplace(iter, new ResourceEntry{ name })->get();
}

/**
 * The default handler for collisions. A return value of -1 means keep the
 * existing value, 0 means fail, and +1 means take the incoming value.
 */
int ResourceTable::resolveValueCollision(Value* existing, Value* incoming) {
    Attribute* existingAttr = valueCast<Attribute>(existing);
    Attribute* incomingAttr = valueCast<Attribute>(incoming);

    if (!incomingAttr) {
        if (incoming->isWeak()) {
            // We're trying to add a weak resource but a resource
            // already exists. Keep the existing.
            return -1;
        } else if (existing->isWeak()) {
            // Override the weak resource with the new strong resource.
            return 1;
        }
        // The existing and incoming values are strong, this is an error
        // if the values are not both attributes.
        return 0;
    }

    if (!existingAttr) {
        if (existing->isWeak()) {
            // The existing value is not an attribute and it is weak,
            // so take the incoming attribute value.
            return 1;
        }
        // The existing value is not an attribute and it is strong,
        // so the incoming attribute value is an error.
        return 0;
    }

    assert(incomingAttr && existingAttr);

    //
    // Attribute specific handling. At this point we know both
    // values are attributes. Since we can declare and define
    // attributes all-over, we do special handling to see
    // which definition sticks.
    //
    if (existingAttr->typeMask == incomingAttr->typeMask) {
        // The two attributes are both DECLs, but they are plain attributes
        // with the same formats.
        // Keep the strongest one.
        return existingAttr->isWeak() ? 1 : -1;
    }

    if (existingAttr->isWeak() && existingAttr->typeMask == android::ResTable_map::TYPE_ANY) {
        // Any incoming attribute is better than this.
        return 1;
    }

    if (incomingAttr->isWeak() && incomingAttr->typeMask == android::ResTable_map::TYPE_ANY) {
        // The incoming attribute may be a USE instead of a DECL.
        // Keep the existing attribute.
        return -1;
    }
    return 0;
}

static constexpr const char16_t* kValidNameChars = u"._-";
static constexpr const char16_t* kValidNameMangledChars = u"._-$";

bool ResourceTable::addResource(const ResourceNameRef& name, const ConfigDescription& config,
                                const Source& source, std::unique_ptr<Value> value,
                                IDiagnostics* diag) {
    return addResourceImpl(name, ResourceId{}, config, source, std::move(value), kValidNameChars,
                           diag);
}

bool ResourceTable::addResource(const ResourceNameRef& name, const ResourceId resId,
                                const ConfigDescription& config, const Source& source,
                                std::unique_ptr<Value> value, IDiagnostics* diag) {
    return addResourceImpl(name, resId, config, source, std::move(value), kValidNameChars, diag);
}

bool ResourceTable::addFileReference(const ResourceNameRef& name, const ConfigDescription& config,
                                     const Source& source, const StringPiece16& path,
                                     IDiagnostics* diag) {
    return addResourceImpl(name, ResourceId{}, config, source,
                           util::make_unique<FileReference>(stringPool.makeRef(path)),
                           kValidNameChars, diag);
}

bool ResourceTable::addResourceAllowMangled(const ResourceNameRef& name,
                                            const ConfigDescription& config,
                                            const Source& source,
                                            std::unique_ptr<Value> value,
                                            IDiagnostics* diag) {
    return addResourceImpl(name, ResourceId{}, config, source, std::move(value),
                           kValidNameMangledChars, diag);
}

bool ResourceTable::addResourceAllowMangled(const ResourceNameRef& name,
                                            const ResourceId id,
                                            const ConfigDescription& config,
                                            const Source& source,
                                            std::unique_ptr<Value> value,
                                            IDiagnostics* diag) {
    return addResourceImpl(name, id, config, source, std::move(value),
                           kValidNameMangledChars, diag);
}

bool ResourceTable::addResourceImpl(const ResourceNameRef& name, const ResourceId resId,
                                    const ConfigDescription& config, const Source& source,
                                    std::unique_ptr<Value> value, const char16_t* validChars,
                                    IDiagnostics* diag) {
    auto badCharIter = util::findNonAlphaNumericAndNotInSet(name.entry, validChars);
    if (badCharIter != name.entry.end()) {
        diag->error(DiagMessage(source)
                    << "resource '"
                    << name
                    << "' has invalid entry name '"
                    << name.entry
                    << "'. Invalid character '"
                    << StringPiece16(badCharIter, 1)
                    << "'");
        return false;
    }

    ResourceTablePackage* package = findOrCreatePackage(name.package);
    if (resId.isValid() && package->id && package->id.value() != resId.packageId()) {
        diag->error(DiagMessage(source)
                    << "trying to add resource '"
                    << name
                    << "' with ID "
                    << resId
                    << " but package '"
                    << package->name
                    << "' already has ID "
                    << std::hex << (int) package->id.value() << std::dec);
        return false;
    }

    ResourceTableType* type = package->findOrCreateType(name.type);
    if (resId.isValid() && type->id && type->id.value() != resId.typeId()) {
        diag->error(DiagMessage(source)
                    << "trying to add resource '"
                    << name
                    << "' with ID "
                    << resId
                    << " but type '"
                    << type->type
                    << "' already has ID "
                    << std::hex << (int) type->id.value() << std::dec);
        return false;
    }

    ResourceEntry* entry = type->findOrCreateEntry(name.entry);
    if (resId.isValid() && entry->id && entry->id.value() != resId.entryId()) {
        diag->error(DiagMessage(source)
                    << "trying to add resource '"
                    << name
                    << "' with ID "
                    << resId
                    << " but resource already has ID "
                    << ResourceId(package->id.value(), type->id.value(), entry->id.value()));
        return false;
    }

    const auto endIter = entry->values.end();
    auto iter = std::lower_bound(entry->values.begin(), endIter, config, compareConfigs);
    if (iter == endIter || iter->config != config) {
        // This resource did not exist before, add it.
        entry->values.insert(iter, ResourceConfigValue{ config, source, {}, std::move(value) });
    } else {
        int collisionResult = resolveValueCollision(iter->value.get(), value.get());
        if (collisionResult > 0) {
            // Take the incoming value.
            *iter = ResourceConfigValue{ config, source, {}, std::move(value) };
        } else if (collisionResult == 0) {
            diag->error(DiagMessage(source)
                        << "duplicate value for resource '" << name << "' "
                        << "with config '" << iter->config << "'");
            diag->error(DiagMessage(iter->source)
                        << "resource previously defined here");
            return false;
        }
    }

    if (resId.isValid()) {
        package->id = resId.packageId();
        type->id = resId.typeId();
        entry->id = resId.entryId();
    }
    return true;
}

bool ResourceTable::setSymbolState(const ResourceNameRef& name, const ResourceId resId,
                                   const Source& source, SymbolState state, IDiagnostics* diag) {
    return setSymbolStateImpl(name, resId, source, state, kValidNameChars, diag);
}

bool ResourceTable::setSymbolStateAllowMangled(const ResourceNameRef& name, const ResourceId resId,
                                               const Source& source, SymbolState state,
                                               IDiagnostics* diag) {
    return setSymbolStateImpl(name, resId, source, state, kValidNameMangledChars, diag);
}

bool ResourceTable::setSymbolStateImpl(const ResourceNameRef& name, const ResourceId resId,
                                       const Source& source, SymbolState state,
                                       const char16_t* validChars, IDiagnostics* diag) {
    if (state == SymbolState::kUndefined) {
        // Nothing to do.
        return true;
    }

    auto badCharIter = util::findNonAlphaNumericAndNotInSet(name.entry, validChars);
    if (badCharIter != name.entry.end()) {
        diag->error(DiagMessage(source)
                    << "resource '"
                    << name
                    << "' has invalid entry name '"
                    << name.entry
                    << "'. Invalid character '"
                    << StringPiece16(badCharIter, 1)
                    << "'");
        return false;
    }

    ResourceTablePackage* package = findOrCreatePackage(name.package);
    if (resId.isValid() && package->id && package->id.value() != resId.packageId()) {
        diag->error(DiagMessage(source)
                    << "trying to add resource '"
                    << name
                    << "' with ID "
                    << resId
                    << " but package '"
                    << package->name
                    << "' already has ID "
                    << std::hex << (int) package->id.value() << std::dec);
        return false;
    }

    ResourceTableType* type = package->findOrCreateType(name.type);
    if (resId.isValid() && type->id && type->id.value() != resId.typeId()) {
        diag->error(DiagMessage(source)
                    << "trying to add resource '"
                    << name
                    << "' with ID "
                    << resId
                    << " but type '"
                    << type->type
                    << "' already has ID "
                    << std::hex << (int) type->id.value() << std::dec);
        return false;
    }

    ResourceEntry* entry = type->findOrCreateEntry(name.entry);
    if (resId.isValid() && entry->id && entry->id.value() != resId.entryId()) {
        diag->error(DiagMessage(source)
                    << "trying to add resource '"
                    << name
                    << "' with ID "
                    << resId
                    << " but resource already has ID "
                    << ResourceId(package->id.value(), type->id.value(), entry->id.value()));
        return false;
    }

    // Only mark the type state as public, it doesn't care about being private.
    if (state == SymbolState::kPublic) {
        type->symbolStatus.state = SymbolState::kPublic;
    }

    // Downgrading to a private symbol from a public one is not allowed.
    if (entry->symbolStatus.state != SymbolState::kPublic) {
        if (entry->symbolStatus.state != state) {
            entry->symbolStatus.state = state;
            entry->symbolStatus.source = source;
        }
    }

    if (resId.isValid()) {
        package->id = resId.packageId();
        type->id = resId.typeId();
        entry->id = resId.entryId();
    }
    return true;
}

Maybe<ResourceTable::SearchResult>
ResourceTable::findResource(const ResourceNameRef& name) {
    ResourceTablePackage* package = findPackage(name.package);
    if (!package) {
        return {};
    }

    ResourceTableType* type = package->findType(name.type);
    if (!type) {
        return {};
    }

    ResourceEntry* entry = type->findEntry(name.entry);
    if (!entry) {
        return {};
    }
    return SearchResult{ package, type, entry };
}

} // namespace aapt
