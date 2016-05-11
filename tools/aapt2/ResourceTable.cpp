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
    return types.emplace(iter, new ResourceTableType(type))->get();
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
    return entries.emplace(iter, new ResourceEntry(name))->get();
}

ResourceConfigValue* ResourceEntry::findValue(const ConfigDescription& config) {
    return findValue(config, StringPiece());
}

struct ConfigKey {
    const ConfigDescription* config;
    const StringPiece& product;
};

bool ltConfigKeyRef(const std::unique_ptr<ResourceConfigValue>& lhs, const ConfigKey& rhs) {
    int cmp = lhs->config.compare(*rhs.config);
    if (cmp == 0) {
        cmp = StringPiece(lhs->product).compare(rhs.product);
    }
    return cmp < 0;
}

ResourceConfigValue* ResourceEntry::findValue(const ConfigDescription& config,
                                              const StringPiece& product) {
    auto iter = std::lower_bound(values.begin(), values.end(),
                                 ConfigKey{ &config, product }, ltConfigKeyRef);
    if (iter != values.end()) {
        ResourceConfigValue* value = iter->get();
        if (value->config == config && StringPiece(value->product) == product) {
            return value;
        }
    }
    return nullptr;
}

ResourceConfigValue* ResourceEntry::findOrCreateValue(const ConfigDescription& config,
                                                      const StringPiece& product) {
    auto iter = std::lower_bound(values.begin(), values.end(),
                                 ConfigKey{ &config, product }, ltConfigKeyRef);
    if (iter != values.end()) {
        ResourceConfigValue* value = iter->get();
        if (value->config == config && StringPiece(value->product) == product) {
            return value;
        }
    }
    ResourceConfigValue* newValue = values.insert(
            iter, util::make_unique<ResourceConfigValue>(config, product))->get();
    return newValue;
}

std::vector<ResourceConfigValue*> ResourceEntry::findAllValues(const ConfigDescription& config) {
    std::vector<ResourceConfigValue*> results;

    auto iter = values.begin();
    for (; iter != values.end(); ++iter) {
        ResourceConfigValue* value = iter->get();
        if (value->config == config) {
            results.push_back(value);
            ++iter;
            break;
        }
    }

    for (; iter != values.end(); ++iter) {
        ResourceConfigValue* value = iter->get();
        if (value->config == config) {
            results.push_back(value);
        }
    }
    return results;
}

std::vector<ResourceConfigValue*> ResourceEntry::findValuesIf(
        const std::function<bool(ResourceConfigValue*)>& f) {
    std::vector<ResourceConfigValue*> results;
    for (auto& configValue : values) {
        if (f(configValue.get())) {
            results.push_back(configValue.get());
        }
    }
    return results;
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

bool ResourceTable::addResource(const ResourceNameRef& name,
                                const ConfigDescription& config,
                                const StringPiece& product,
                                std::unique_ptr<Value> value,
                                IDiagnostics* diag) {
    return addResourceImpl(name, {}, config, product, std::move(value), kValidNameChars,
                           resolveValueCollision, diag);
}

bool ResourceTable::addResource(const ResourceNameRef& name,
                                const ResourceId resId,
                                const ConfigDescription& config,
                                const StringPiece& product,
                                std::unique_ptr<Value> value,
                                IDiagnostics* diag) {
    return addResourceImpl(name, resId, config, product, std::move(value), kValidNameChars,
                           resolveValueCollision, diag);
}

bool ResourceTable::addFileReference(const ResourceNameRef& name,
                                     const ConfigDescription& config,
                                     const Source& source,
                                     const StringPiece16& path,
                                     IDiagnostics* diag) {
    return addFileReferenceImpl(name, config, source, path, nullptr, kValidNameChars, diag);
}

bool ResourceTable::addFileReferenceAllowMangled(const ResourceNameRef& name,
                                                 const ConfigDescription& config,
                                                 const Source& source,
                                                 const StringPiece16& path,
                                                 io::IFile* file,
                                                 IDiagnostics* diag) {
    return addFileReferenceImpl(name, config, source, path, file, kValidNameMangledChars, diag);
}

bool ResourceTable::addFileReferenceImpl(const ResourceNameRef& name,
                                         const ConfigDescription& config,
                                         const Source& source,
                                         const StringPiece16& path,
                                         io::IFile* file,
                                         const char16_t* validChars,
                                         IDiagnostics* diag) {
    std::unique_ptr<FileReference> fileRef = util::make_unique<FileReference>(
            stringPool.makeRef(path));
    fileRef->setSource(source);
    fileRef->file = file;
    return addResourceImpl(name, ResourceId{}, config, StringPiece{}, std::move(fileRef),
                           kValidNameChars, resolveValueCollision, diag);
}

bool ResourceTable::addResourceAllowMangled(const ResourceNameRef& name,
                                            const ConfigDescription& config,
                                            const StringPiece& product,
                                            std::unique_ptr<Value> value,
                                            IDiagnostics* diag) {
    return addResourceImpl(name, ResourceId{}, config, product, std::move(value),
                           kValidNameMangledChars, resolveValueCollision, diag);
}

bool ResourceTable::addResourceAllowMangled(const ResourceNameRef& name,
                                            const ResourceId id,
                                            const ConfigDescription& config,
                                            const StringPiece& product,
                                            std::unique_ptr<Value> value,
                                            IDiagnostics* diag) {
    return addResourceImpl(name, id, config, product, std::move(value), kValidNameMangledChars,
                           resolveValueCollision, diag);
}

bool ResourceTable::addResourceImpl(const ResourceNameRef& name,
                                    const ResourceId resId,
                                    const ConfigDescription& config,
                                    const StringPiece& product,
                                    std::unique_ptr<Value> value,
                                    const char16_t* validChars,
                                    std::function<int(Value*,Value*)> conflictResolver,
                                    IDiagnostics* diag) {
    assert(value && "value can't be nullptr");
    assert(diag && "diagnostics can't be nullptr");

    auto badCharIter = util::findNonAlphaNumericAndNotInSet(name.entry, validChars);
    if (badCharIter != name.entry.end()) {
        diag->error(DiagMessage(value->getSource())
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
        diag->error(DiagMessage(value->getSource())
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
        diag->error(DiagMessage(value->getSource())
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
        diag->error(DiagMessage(value->getSource())
                    << "trying to add resource '"
                    << name
                    << "' with ID "
                    << resId
                    << " but resource already has ID "
                    << ResourceId(package->id.value(), type->id.value(), entry->id.value()));
        return false;
    }

    ResourceConfigValue* configValue = entry->findOrCreateValue(config, product);
    if (!configValue->value) {
        // Resource does not exist, add it now.
        configValue->value = std::move(value);

    } else {
        int collisionResult = conflictResolver(configValue->value.get(), value.get());
        if (collisionResult > 0) {
            // Take the incoming value.
            configValue->value = std::move(value);
        } else if (collisionResult == 0) {
            diag->error(DiagMessage(value->getSource())
                        << "duplicate value for resource '" << name << "' "
                        << "with config '" << config << "'");
            diag->error(DiagMessage(configValue->value->getSource())
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
                                   const Symbol& symbol, IDiagnostics* diag) {
    return setSymbolStateImpl(name, resId, symbol, kValidNameChars, diag);
}

bool ResourceTable::setSymbolStateAllowMangled(const ResourceNameRef& name,
                                               const ResourceId resId,
                                               const Symbol& symbol, IDiagnostics* diag) {
    return setSymbolStateImpl(name, resId, symbol, kValidNameMangledChars, diag);
}

bool ResourceTable::setSymbolStateImpl(const ResourceNameRef& name, const ResourceId resId,
                                       const Symbol& symbol, const char16_t* validChars,
                                       IDiagnostics* diag) {
    assert(diag && "diagnostics can't be nullptr");

    auto badCharIter = util::findNonAlphaNumericAndNotInSet(name.entry, validChars);
    if (badCharIter != name.entry.end()) {
        diag->error(DiagMessage(symbol.source)
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
        diag->error(DiagMessage(symbol.source)
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
        diag->error(DiagMessage(symbol.source)
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
        diag->error(DiagMessage(symbol.source)
                    << "trying to add resource '"
                    << name
                    << "' with ID "
                    << resId
                    << " but resource already has ID "
                    << ResourceId(package->id.value(), type->id.value(), entry->id.value()));
        return false;
    }

    if (resId.isValid()) {
        package->id = resId.packageId();
        type->id = resId.typeId();
        entry->id = resId.entryId();
    }

    // Only mark the type state as public, it doesn't care about being private.
    if (symbol.state == SymbolState::kPublic) {
        type->symbolStatus.state = SymbolState::kPublic;
    }

    if (symbol.state == SymbolState::kUndefined &&
            entry->symbolStatus.state != SymbolState::kUndefined) {
        // We can't undefine a symbol (remove its visibility). Ignore.
        return true;
    }

    if (symbol.state == SymbolState::kPrivate &&
            entry->symbolStatus.state == SymbolState::kPublic) {
        // We can't downgrade public to private. Ignore.
        return true;
    }

    entry->symbolStatus = std::move(symbol);
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
