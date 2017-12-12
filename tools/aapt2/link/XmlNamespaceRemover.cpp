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

#include "link/Linkers.h"

#include <algorithm>

#include "ResourceTable.h"

namespace aapt {

namespace {

// Visits each xml Node, removing URI references and nested namespaces.
class XmlVisitor : public xml::Visitor {
 public:
  explicit XmlVisitor(bool keep_uris) : keep_uris_(keep_uris) {}

  void Visit(xml::Element* el) override {
    el->namespace_decls.clear();

    if (!keep_uris_) {
      for (xml::Attribute& attr : el->attributes) {
        attr.namespace_uri.clear();
      }
      el->namespace_uri.clear();
    }
    xml::Visitor::Visit(el);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlVisitor);

  bool keep_uris_;
};

}  // namespace

bool XmlNamespaceRemover::Consume(IAaptContext* context, xml::XmlResource* resource) {
  if (!resource->root) {
    return false;
  }

  XmlVisitor visitor(keep_uris_);
  resource->root->Accept(&visitor);
  return true;
}

}  // namespace aapt
