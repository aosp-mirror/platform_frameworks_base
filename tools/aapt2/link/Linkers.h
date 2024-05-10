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

#include <set>
#include <unordered_set>

#include "Resource.h"
#include "SdkConstants.h"
#include "android-base/macros.h"
#include "android-base/result.h"
#include "androidfw/ConfigDescription.h"
#include "androidfw/StringPiece.h"
#include "process/IResourceTableConsumer.h"
#include "xml/XmlDom.h"

namespace aapt {

class ResourceTable;
class ResourceEntry;

// Defines the context in which a resource value is defined. Most resources are defined with the
// implicit package name of their compilation context. Understanding the package name of a resource
// allows to determine visibility of other symbols which may or may not have their packages defined.
struct CallSite {
  std::string package;
};

// Determines whether a versioned resource should be created. If a versioned resource already
// exists, it takes precedence.
bool ShouldGenerateVersionedResource(const ResourceEntry* entry,
                                     const android::ConfigDescription& config,
                                     const ApiVersion sdk_version_to_generate);

// Finds the next largest ApiVersion of the config which is identical to the given config except
// for sdkVersion.
ApiVersion FindNextApiVersionForConfig(const ResourceEntry* entry,
                                       const android::ConfigDescription& config);

class AutoVersioner : public IResourceTableConsumer {
 public:
  AutoVersioner() = default;

  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(AutoVersioner);
};

// If any attribute resource values are defined as public, this consumer will move all private
// attribute resource values to a private ^private-attr type, avoiding backwards compatibility
// issues with new apps running on old platforms.
//
// The Android platform ignores resource attributes it doesn't recognize, so an app developer can
// use new attributes in their layout XML files without worrying about versioning. This assumption
// actually breaks on older platforms. OEMs may add private attributes that are used internally.
// AAPT originally assigned all private attributes IDs immediately proceeding the public attributes'
// IDs.
//
// This means that on a newer Android platform, an ID previously assigned to a private attribute
// may end up assigned to a public attribute.
//
// App developers assume using the newer attribute is safe on older platforms because it will
// be ignored. Instead, the platform thinks the new attribute is an older, private attribute and
// will interpret it as such. This leads to unintended styling and exceptions thrown due to
// unexpected types.
//
// By moving the private attributes to a completely different type, this ID conflict will never
// occur.
class PrivateAttributeMover : public IResourceTableConsumer {
 public:
  PrivateAttributeMover() = default;

  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(PrivateAttributeMover);
};

// Removes namespace nodes and URI information from the XmlResource.
//
// Once an XmlResource is processed by this consumer, it is no longer able to have its attributes
// parsed. As such, this XmlResource must have already been processed by XmlReferenceLinker.
class XmlNamespaceRemover : public IXmlResourceConsumer {
 public:
  explicit XmlNamespaceRemover(bool keep_uris = false) : keep_uris_(keep_uris){};

  bool Consume(IAaptContext* context, xml::XmlResource* resource) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlNamespaceRemover);

  bool keep_uris_;
};

// Resolves attributes in the XmlResource and compiles string values to resource values.
// Once an XmlResource is processed by this linker, it is ready to be flattened.
class XmlReferenceLinker : public IXmlResourceConsumer {
 public:
  explicit XmlReferenceLinker(ResourceTable* table) : table_(table) {
  }

  bool Consume(IAaptContext* context, xml::XmlResource* resource) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlReferenceLinker);
  ResourceTable* table_;
};

}  // namespace aapt

#endif /* AAPT_LINKER_LINKERS_H */
