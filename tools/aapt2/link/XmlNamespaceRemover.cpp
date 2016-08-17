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

#include "ResourceTable.h"
#include "link/Linkers.h"

#include <algorithm>

namespace aapt {

namespace {

/**
 * Visits each xml Node, removing URI references and nested namespaces.
 */
class XmlVisitor : public xml::Visitor {
public:
    XmlVisitor(bool keepUris) : mKeepUris(keepUris) {
    }

    void visit(xml::Element* el) override {
        // Strip namespaces
        for (auto& child : el->children) {
            while (child && xml::nodeCast<xml::Namespace>(child.get())) {
                if (child->children.empty()) {
                    child = {};
                } else {
                    child = std::move(child->children.front());
                    child->parent = el;
                }
            }
        }
        el->children.erase(std::remove_if(el->children.begin(), el->children.end(),
                [](const std::unique_ptr<xml::Node>& child) -> bool {
            return child == nullptr;
        }), el->children.end());

        if (!mKeepUris) {
            for (xml::Attribute& attr : el->attributes) {
                attr.namespaceUri = std::string();
            }
            el->namespaceUri = std::string();
        }
        xml::Visitor::visit(el);
    }

private:
    bool mKeepUris;
};

} // namespace

bool XmlNamespaceRemover::consume(IAaptContext* context, xml::XmlResource* resource) {
    if (!resource->root) {
        return false;
    }
    // Replace any root namespaces until the root is a non-namespace node
    while (xml::nodeCast<xml::Namespace>(resource->root.get())) {
        if (resource->root->children.empty()) {
            break;
        }
        resource->root = std::move(resource->root->children.front());
        resource->root->parent = nullptr;
    }
    XmlVisitor visitor(mKeepUris);
    resource->root->accept(&visitor);
    return true;
}

} // namespace aapt
