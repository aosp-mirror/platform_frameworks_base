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

#include "Debug.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "Util.h"

#include <algorithm>
#include <iostream>
#include <map>
#include <memory>
#include <queue>
#include <set>
#include <vector>

namespace aapt {

struct PrintVisitor : ConstValueVisitor {
    void visit(const Attribute& attr, ValueVisitorArgs&) override {
        std::cout << "(attr) type=";
        attr.printMask(std::cout);
        static constexpr uint32_t kMask = android::ResTable_map::TYPE_ENUM |
            android::ResTable_map::TYPE_FLAGS;
        if (attr.typeMask & kMask) {
            for (const auto& symbol : attr.symbols) {
                std::cout << "\n        "
                          << symbol.symbol.name.entry << " (" << symbol.symbol.id << ") = "
                          << symbol.value;
            }
        }
    }

    void visit(const Style& style, ValueVisitorArgs&) override {
        std::cout << "(style)";
        if (style.parent.name.isValid() || style.parent.id.isValid()) {
            std::cout << " parent=";
            if (style.parent.name.isValid()) {
                std::cout << style.parent.name << " ";
            }

            if (style.parent.id.isValid()) {
                std::cout << style.parent.id;
            }
        }

        for (const auto& entry : style.entries) {
            std::cout << "\n        ";
            if (entry.key.name.isValid()) {
                std::cout << entry.key.name.package << ":" << entry.key.name.entry;
            }

            if (entry.key.id.isValid()) {
                std::cout << "(" << entry.key.id << ")";
            }

            std::cout << "=" << *entry.value;
        }
    }

    void visit(const Array& array, ValueVisitorArgs&) override {
        array.print(std::cout);
    }

    void visit(const Plural& plural, ValueVisitorArgs&) override {
        plural.print(std::cout);
    }

    void visit(const Styleable& styleable, ValueVisitorArgs&) override {
        styleable.print(std::cout);
    }

    void visitItem(const Item& item, ValueVisitorArgs& args) override {
        item.print(std::cout);
    }
};

void Debug::printTable(const std::shared_ptr<ResourceTable>& table) {
    std::cout << "Package name=" << table->getPackage();
    if (table->getPackageId() != ResourceTable::kUnsetPackageId) {
        std::cout << " id=" << std::hex << table->getPackageId() << std::dec;
    }
    std::cout << std::endl;

    for (const auto& type : *table) {
        std::cout << "  type " << type->type;
        if (type->typeId != ResourceTableType::kUnsetTypeId) {
            std::cout << " id=" << std::hex << type->typeId << std::dec;
        }
        std::cout << " entryCount=" << type->entries.size() << std::endl;

        std::vector<const ResourceEntry*> sortedEntries;
        for (const auto& entry : type->entries) {
            auto iter = std::lower_bound(sortedEntries.begin(), sortedEntries.end(), entry.get(),
                    [](const ResourceEntry* a, const ResourceEntry* b) -> bool {
                        return a->entryId < b->entryId;
                    });
            sortedEntries.insert(iter, entry.get());
        }

        for (const ResourceEntry* entry : sortedEntries) {
            ResourceId id = { table->getPackageId(), type->typeId, entry->entryId };
            ResourceName name = { table->getPackage(), type->type, entry->name };
            std::cout << "    spec resource " << id << " " << name;
            if (entry->publicStatus.isPublic) {
                std::cout << " PUBLIC";
            }
            std::cout << std::endl;

            PrintVisitor visitor;
            for (const auto& value : entry->values) {
                std::cout << "      (" << value.config << ") ";
                value.value->accept(visitor, {});
                std::cout << std::endl;
            }
        }
    }
}

static size_t getNodeIndex(const std::vector<ResourceName>& names, const ResourceName& name) {
    auto iter = std::lower_bound(names.begin(), names.end(), name);
    assert(iter != names.end() && *iter == name);
    return std::distance(names.begin(), iter);
}

void Debug::printStyleGraph(const std::shared_ptr<ResourceTable>& table,
                            const ResourceName& targetStyle) {
    std::map<ResourceName, std::set<ResourceName>> graph;

    std::queue<ResourceName> stylesToVisit;
    stylesToVisit.push(targetStyle);
    for (; !stylesToVisit.empty(); stylesToVisit.pop()) {
        const ResourceName& styleName = stylesToVisit.front();
        std::set<ResourceName>& parents = graph[styleName];
        if (!parents.empty()) {
            // We've already visited this style.
            continue;
        }

        const ResourceTableType* type;
        const ResourceEntry* entry;
        std::tie(type, entry) = table->findResource(styleName);
        if (entry) {
            for (const auto& value : entry->values) {
                visitFunc<Style>(*value.value, [&](const Style& style) {
                    if (style.parent.name.isValid()) {
                        parents.insert(style.parent.name);
                        stylesToVisit.push(style.parent.name);
                    }
                });
            }
        }
    }

    std::vector<ResourceName> names;
    for (const auto& entry : graph) {
        names.push_back(entry.first);
    }

    std::cout << "digraph styles {\n";
    for (const auto& name : names) {
        std::cout << "  node_" << getNodeIndex(names, name)
                  << " [label=\"" << name << "\"];\n";
    }

    for (const auto& entry : graph) {
        const ResourceName& styleName = entry.first;
        size_t styleNodeIndex = getNodeIndex(names, styleName);

        for (const auto& parentName : entry.second) {
            std::cout << "  node_" << styleNodeIndex << " -> "
                      << "node_" << getNodeIndex(names, parentName) << ";\n";
        }
    }

    std::cout << "}" << std::endl;
}

} // namespace aapt
