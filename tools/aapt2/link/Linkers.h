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

#include "Resource.h"
#include "process/IResourceTableConsumer.h"
#include "xml/XmlDom.h"

#include <set>

namespace aapt {

class ResourceTable;
class ResourceEntry;
struct ConfigDescription;

/**
 * Defines the location in which a value exists. This determines visibility of other
 * package's private symbols.
 */
struct CallSite {
    ResourceNameRef resource;
};

/**
 * Determines whether a versioned resource should be created. If a versioned resource already
 * exists, it takes precedence.
 */
bool shouldGenerateVersionedResource(const ResourceEntry* entry, const ConfigDescription& config,
                                     const int sdkVersionToGenerate);

struct AutoVersioner : public IResourceTableConsumer {
    bool consume(IAaptContext* context, ResourceTable* table) override;
};

struct XmlAutoVersioner : public IXmlResourceConsumer {
    bool consume(IAaptContext* context, xml::XmlResource* resource) override;
};

/**
 * If any attribute resource values are defined as public, this consumer will move all private
 * attribute resource values to a private ^private-attr type, avoiding backwards compatibility
 * issues with new apps running on old platforms.
 *
 * The Android platform ignores resource attributes it doesn't recognize, so an app developer can
 * use new attributes in their layout XML files without worrying about versioning. This assumption
 * actually breaks on older platforms. OEMs may add private attributes that are used internally.
 * AAPT originally assigned all private attributes IDs immediately proceeding the public attributes'
 * IDs.
 *
 * This means that on a newer Android platform, an ID previously assigned to a private attribute
 * may end up assigned to a public attribute.
 *
 * App developers assume using the newer attribute is safe on older platforms because it will
 * be ignored. Instead, the platform thinks the new attribute is an older, private attribute and
 * will interpret it as such. This leads to unintended styling and exceptions thrown due to
 * unexpected types.
 *
 * By moving the private attributes to a completely different type, this ID conflict will never
 * occur.
 */
struct PrivateAttributeMover : public IResourceTableConsumer {
    bool consume(IAaptContext* context, ResourceTable* table) override;
};

/**
 * Resolves attributes in the XmlResource and compiles string values to resource values.
 * Once an XmlResource is processed by this linker, it is ready to be flattened.
 */
class XmlReferenceLinker : public IXmlResourceConsumer {
private:
    std::set<int> mSdkLevelsFound;

public:
    bool consume(IAaptContext* context, xml::XmlResource* resource) override;

    /**
     * Once the XmlResource has been consumed, this returns the various SDK levels in which
     * framework attributes used within the XML document were defined.
     */
    inline const std::set<int>& getSdkLevels() const {
        return mSdkLevelsFound;
    }
};

} // namespace aapt

#endif /* AAPT_LINKER_LINKERS_H */
