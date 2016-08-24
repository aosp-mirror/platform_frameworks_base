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

#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "compile/XmlIdCollector.h"
#include "xml/XmlDom.h"

#include <algorithm>
#include <vector>

namespace aapt {

namespace {

static bool cmpName(const SourcedResourceName& a, const ResourceNameRef& b) {
    return a.name < b;
}

struct IdCollector : public xml::Visitor {
    using xml::Visitor::visit;

    std::vector<SourcedResourceName>* mOutSymbols;

    IdCollector(std::vector<SourcedResourceName>* outSymbols) : mOutSymbols(outSymbols) {
    }

    void visit(xml::Element* element) override {
        for (xml::Attribute& attr : element->attributes) {
            ResourceNameRef name;
            bool create = false;
            if (ResourceUtils::tryParseReference(attr.value, &name, &create, nullptr)) {
                if (create && name.type == ResourceType::kId) {
                    auto iter = std::lower_bound(mOutSymbols->begin(), mOutSymbols->end(),
                                                 name, cmpName);
                    if (iter == mOutSymbols->end() || iter->name != name) {
                        mOutSymbols->insert(iter, SourcedResourceName{ name.toResourceName(),
                                                                       element->lineNumber });
                    }
                }
            }
        }

        xml::Visitor::visit(element);
    }
};

} // namespace

bool XmlIdCollector::consume(IAaptContext* context, xml::XmlResource* xmlRes) {
    xmlRes->file.exportedSymbols.clear();
    IdCollector collector(&xmlRes->file.exportedSymbols);
    xmlRes->root->accept(&collector);
    return true;
}

} // namespace aapt
