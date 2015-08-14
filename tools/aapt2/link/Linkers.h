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

#ifndef AAPT_LINKER_LINKERS_H
#define AAPT_LINKER_LINKERS_H

#include "process/IResourceTableConsumer.h"

#include <set>

namespace aapt {

class ResourceTable;
struct ResourceEntry;
struct ConfigDescription;

/**
 * Determines whether a versioned resource should be created. If a versioned resource already
 * exists, it takes precedence.
 */
bool shouldGenerateVersionedResource(const ResourceEntry* entry, const ConfigDescription& config,
                                     const int sdkVersionToGenerate);

struct AutoVersioner : public IResourceTableConsumer {
    bool consume(IAaptContext* context, ResourceTable* table) override;
};

struct PrivateAttributeMover : public IResourceTableConsumer {
    bool consume(IAaptContext* context, ResourceTable* table) override;
};

struct XmlAutoVersioner : public IXmlResourceConsumer {
    bool consume(IAaptContext* context, XmlResource* resource) override;
};

struct ReferenceLinker : public IResourceTableConsumer {
    bool consume(IAaptContext* context, ResourceTable* table) override;
};

class XmlReferenceLinker : IXmlResourceConsumer {
private:
    std::set<int> mSdkLevelsFound;

public:
    bool consume(IAaptContext* context, XmlResource* resource) override;

    const std::set<int>& getSdkLevels() const {
        return mSdkLevelsFound;
    }
};

} // namespace aapt

#endif /* AAPT_LINKER_LINKERS_H */
