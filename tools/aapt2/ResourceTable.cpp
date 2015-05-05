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
#include "Logger.h"
#include "NameMangler.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "Util.h"

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

static bool lessThanEntry(const std::unique_ptr<ResourceEntry>& lhs, const StringPiece16& rhs) {
    return lhs->name.compare(0, lhs->name.size(), rhs.data(), rhs.size()) < 0;
}

ResourceTable::ResourceTable() : mPackageId(kUnsetPackageId) {
    // Make sure attrs always have type ID 1.
    findOrCreateType(ResourceType::kAttr)->typeId = 1;
}

std::unique_ptr<ResourceTableType>& ResourceTable::findOrCreateType(ResourceType type) {
    auto last = mTypes.end();
    auto iter = std::lower_bound(mTypes.begin(), last, type, lessThanType);
    if (iter != last) {
        if ((*iter)->type == type) {
            return *iter;
        }
    }
    return *mTypes.emplace(iter, new ResourceTableType{ type });
}

std::unique_ptr<ResourceEntry>& ResourceTable::findOrCreateEntry(
        std::unique_ptr<ResourceTableType>& type, const StringPiece16& name) {
    auto last = type->entries.end();
    auto iter = std::lower_bound(type->entries.begin(), last, name, lessThanEntry);
    if (iter != last) {
        if (name == (*iter)->name) {
            return *iter;
        }
    }
    return *type->entries.emplace(iter, new ResourceEntry{ name });
}

struct IsAttributeVisitor : ConstValueVisitor {
    bool isAttribute = false;

    void visit(const Attribute&, ValueVisitorArgs&) override {
        isAttribute = true;
    }

    operator bool() {
        return isAttribute;
    }
};

/**
 * The default handler for collisions. A return value of -1 means keep the
 * existing value, 0 means fail, and +1 means take the incoming value.
 */
static int defaultCollisionHandler(const Value& existing, const Value& incoming) {
    IsAttributeVisitor existingIsAttr, incomingIsAttr;
    existing.accept(existingIsAttr, {});
    incoming.accept(incomingIsAttr, {});

    if (!incomingIsAttr) {
        if (incoming.isWeak()) {
            // We're trying to add a weak resource but a resource
            // already exists. Keep the existing.
            return -1;
        } else if (existing.isWeak()) {
            // Override the weak resource with the new strong resource.
            return 1;
        }
        // The existing and incoming values are strong, this is an error
        // if the values are not both attributes.
        return 0;
    }

    if (!existingIsAttr) {
        if (existing.isWeak()) {
            // The existing value is not an attribute and it is weak,
            // so take the incoming attribute value.
            return 1;
        }
        // The existing value is not an attribute and it is strong,
        // so the incoming attribute value is an error.
        return 0;
    }

    //
    // Attribute specific handling. At this point we know both
    // values are attributes. Since we can declare and define
    // attributes all-over, we do special handling to see
    // which definition sticks.
    //
    const Attribute& existingAttr = static_cast<const Attribute&>(existing);
    const Attribute& incomingAttr = static_cast<const Attribute&>(incoming);
    if (existingAttr.typeMask == incomingAttr.typeMask) {
        // The two attributes are both DECLs, but they are plain attributes
        // with the same formats.
        // Keep the strongest one.
        return existingAttr.isWeak() ? 1 : -1;
    }

    if (existingAttr.isWeak() && existingAttr.typeMask == android::ResTable_map::TYPE_ANY) {
        // Any incoming attribute is better than this.
        return 1;
    }

    if (incomingAttr.isWeak() && incomingAttr.typeMask == android::ResTable_map::TYPE_ANY) {
        // The incoming attribute may be a USE instead of a DECL.
        // Keep the existing attribute.
        return -1;
    }
    return 0;
}

static constexpr const char16_t* kValidNameChars = u"._-";
static constexpr const char16_t* kValidNameMangledChars = u"._-$";

bool ResourceTable::addResource(const ResourceNameRef& name, const ConfigDescription& config,
                                const SourceLine& source, std::unique_ptr<Value> value) {
    return addResourceImpl(name, ResourceId{}, config, source, std::move(value), kValidNameChars);
}

bool ResourceTable::addResource(const ResourceNameRef& name, const ResourceId resId,
                                const ConfigDescription& config, const SourceLine& source,
                                std::unique_ptr<Value> value) {
    return addResourceImpl(name, resId, config, source, std::move(value), kValidNameChars);
}

bool ResourceTable::addResourceAllowMangled(const ResourceNameRef& name,
                                            const ConfigDescription& config,
                                            const SourceLine& source,
                                            std::unique_ptr<Value> value) {
    return addResourceImpl(name, ResourceId{}, config, source, std::move(value),
                           kValidNameMangledChars);
}

bool ResourceTable::addResourceImpl(const ResourceNameRef& name, const ResourceId resId,
                                    const ConfigDescription& config, const SourceLine& source,
                                    std::unique_ptr<Value> value, const char16_t* validChars) {
    if (!name.package.empty() && name.package != mPackage) {
        Logger::error(source)
                << "resource '"
                << name
                << "' has incompatible package. Must be '"
                << mPackage
                << "'."
                << std::endl;
        return false;
    }

    auto badCharIter = util::findNonAlphaNumericAndNotInSet(name.entry, validChars);
    if (badCharIter != name.entry.end()) {
        Logger::error(source)
                << "resource '"
                << name
                << "' has invalid entry name '"
                << name.entry
                << "'. Invalid character '"
                << StringPiece16(badCharIter, 1)
                << "'."
                << std::endl;
        return false;
    }

    std::unique_ptr<ResourceTableType>& type = findOrCreateType(name.type);
    if (resId.isValid() && type->typeId != ResourceTableType::kUnsetTypeId &&
            type->typeId != resId.typeId()) {
        Logger::error(source)
                << "trying to add resource '"
                << name
                << "' with ID "
                << resId
                << " but type '"
                << type->type
                << "' already has ID "
                << std::hex << type->typeId << std::dec
                << "."
                << std::endl;
        return false;
    }

    std::unique_ptr<ResourceEntry>& entry = findOrCreateEntry(type, name.entry);
    if (resId.isValid() && entry->entryId != ResourceEntry::kUnsetEntryId &&
            entry->entryId != resId.entryId()) {
        Logger::error(source)
                << "trying to add resource '"
                << name
                << "' with ID "
                << resId
                << " but resource already has ID "
                << ResourceId(mPackageId, type->typeId, entry->entryId)
                << "."
                << std::endl;
        return false;
    }

    const auto endIter = std::end(entry->values);
    auto iter = std::lower_bound(std::begin(entry->values), endIter, config, compareConfigs);
    if (iter == endIter || iter->config != config) {
        // This resource did not exist before, add it.
        entry->values.insert(iter, ResourceConfigValue{ config, source, {}, std::move(value) });
    } else {
        int collisionResult = defaultCollisionHandler(*iter->value, *value);
        if (collisionResult > 0) {
            // Take the incoming value.
            *iter = ResourceConfigValue{ config, source, {}, std::move(value) };
        } else if (collisionResult == 0) {
            Logger::error(source)
                    << "duplicate value for resource '" << name << "' "
                    << "with config '" << iter->config << "'."
                    << std::endl;

            Logger::error(iter->source)
                    << "resource previously defined here."
                    << std::endl;
            return false;
        }
    }

    if (resId.isValid()) {
        type->typeId = resId.typeId();
        entry->entryId = resId.entryId();
    }
    return true;
}

bool ResourceTable::markPublic(const ResourceNameRef& name, const ResourceId resId,
                               const SourceLine& source) {
    return markPublicImpl(name, resId, source, kValidNameChars);
}

bool ResourceTable::markPublicAllowMangled(const ResourceNameRef& name, const ResourceId resId,
                                           const SourceLine& source) {
    return markPublicImpl(name, resId, source, kValidNameMangledChars);
}

bool ResourceTable::markPublicImpl(const ResourceNameRef& name, const ResourceId resId,
                                   const SourceLine& source, const char16_t* validChars) {
    if (!name.package.empty() && name.package != mPackage) {
        Logger::error(source)
                << "resource '"
                << name
                << "' has incompatible package. Must be '"
                << mPackage
                << "'."
            << std::endl;
        return false;
    }

    auto badCharIter = util::findNonAlphaNumericAndNotInSet(name.entry, validChars);
    if (badCharIter != name.entry.end()) {
        Logger::error(source)
                << "resource '"
                << name
                << "' has invalid entry name '"
                << name.entry
                << "'. Invalid character '"
                << StringPiece16(badCharIter, 1)
                << "'."
                << std::endl;
        return false;
    }

    std::unique_ptr<ResourceTableType>& type = findOrCreateType(name.type);
    if (resId.isValid() && type->typeId != ResourceTableType::kUnsetTypeId &&
            type->typeId != resId.typeId()) {
        Logger::error(source)
                << "trying to make resource '"
                << name
                << "' public with ID "
                << resId
                << " but type '"
                << type->type
                << "' already has ID "
                << std::hex << type->typeId << std::dec
                << "."
                << std::endl;
        return false;
    }

    std::unique_ptr<ResourceEntry>& entry = findOrCreateEntry(type, name.entry);
    if (resId.isValid() && entry->entryId != ResourceEntry::kUnsetEntryId &&
            entry->entryId != resId.entryId()) {
        Logger::error(source)
                << "trying to make resource '"
                << name
                << "' public with ID "
                << resId
                << " but resource already has ID "
                << ResourceId(mPackageId, type->typeId, entry->entryId)
                << "."
                << std::endl;
        return false;
    }

    type->publicStatus.isPublic = true;
    entry->publicStatus.isPublic = true;
    entry->publicStatus.source = source;

    if (resId.isValid()) {
        type->typeId = resId.typeId();
        entry->entryId = resId.entryId();
    }
    return true;
}

bool ResourceTable::merge(ResourceTable&& other) {
    const bool mangleNames = mPackage != other.getPackage();
    std::u16string mangledName;

    for (auto& otherType : other) {
        std::unique_ptr<ResourceTableType>& type = findOrCreateType(otherType->type);
        if (otherType->publicStatus.isPublic) {
            if (type->publicStatus.isPublic && type->typeId != otherType->typeId) {
                Logger::error() << "can not merge type '" << type->type
                                << "': conflicting public IDs "
                                << "(" << type->typeId << " vs " << otherType->typeId << ")."
                                << std::endl;
                return false;
            }
            type->publicStatus = std::move(otherType->publicStatus);
            type->typeId = otherType->typeId;
        }

        for (auto& otherEntry : otherType->entries) {
            const std::u16string* nameToAdd = &otherEntry->name;
            if (mangleNames) {
                mangledName = otherEntry->name;
                NameMangler::mangle(other.getPackage(), &mangledName);
                nameToAdd = &mangledName;
            }

            std::unique_ptr<ResourceEntry>& entry = findOrCreateEntry(type, *nameToAdd);
            if (otherEntry->publicStatus.isPublic) {
                if (entry->publicStatus.isPublic && entry->entryId != otherEntry->entryId) {
                    Logger::error() << "can not merge entry '" << type->type << "/" << entry->name
                                    << "': conflicting public IDs "
                                    << "(" << entry->entryId << " vs " << entry->entryId << ")."
                                    << std::endl;
                    return false;
                }
                entry->publicStatus = std::move(otherEntry->publicStatus);
                entry->entryId = otherEntry->entryId;
            }

            for (ResourceConfigValue& otherValue : otherEntry->values) {
                auto iter = std::lower_bound(entry->values.begin(), entry->values.end(),
                                             otherValue.config, compareConfigs);
                if (iter != entry->values.end() && iter->config == otherValue.config) {
                    int collisionResult = defaultCollisionHandler(*iter->value, *otherValue.value);
                    if (collisionResult > 0) {
                        // Take the incoming value.
                        iter->source = std::move(otherValue.source);
                        iter->comment = std::move(otherValue.comment);
                        iter->value = std::unique_ptr<Value>(otherValue.value->clone(&mValuePool));
                    } else if (collisionResult == 0) {
                        ResourceNameRef resourceName = { mPackage, type->type, entry->name };
                        Logger::error(otherValue.source)
                                << "resource '" << resourceName << "' has a conflicting value for "
                                << "configuration (" << otherValue.config << ")."
                                << std::endl;
                        Logger::note(iter->source) << "originally defined here." << std::endl;
                        return false;
                    }
                } else {
                    entry->values.insert(iter, ResourceConfigValue{
                            otherValue.config,
                            std::move(otherValue.source),
                            std::move(otherValue.comment),
                            std::unique_ptr<Value>(otherValue.value->clone(&mValuePool)),
                    });
                }
            }
        }
    }
    return true;
}

std::tuple<const ResourceTableType*, const ResourceEntry*>
ResourceTable::findResource(const ResourceNameRef& name) const {
    if (name.package != mPackage) {
        return {};
    }

    auto iter = std::lower_bound(mTypes.begin(), mTypes.end(), name.type, lessThanType);
    if (iter == mTypes.end() || (*iter)->type != name.type) {
        return {};
    }

    const std::unique_ptr<ResourceTableType>& type = *iter;
    auto iter2 = std::lower_bound(type->entries.begin(), type->entries.end(), name.entry,
                                  lessThanEntry);
    if (iter2 == type->entries.end() || name.entry != (*iter2)->name) {
        return {};
    }
    return std::make_tuple(iter->get(), iter2->get());
}

} // namespace aapt
