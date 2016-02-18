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
#include "util/Util.h"
#include "ValueVisitor.h"

#include <algorithm>
#include <iostream>
#include <map>
#include <memory>
#include <queue>
#include <set>
#include <vector>

namespace aapt {

class PrintVisitor : public ValueVisitor {
public:
    using ValueVisitor::visit;

    void visit(Attribute* attr) override {
        std::cout << "(attr) type=";
        attr->printMask(&std::cout);
        static constexpr uint32_t kMask = android::ResTable_map::TYPE_ENUM |
            android::ResTable_map::TYPE_FLAGS;
        if (attr->typeMask & kMask) {
            for (const auto& symbol : attr->symbols) {
                std::cout << "\n        " << symbol.symbol.name.value().entry;
                if (symbol.symbol.id) {
                    std::cout << " (" << symbol.symbol.id.value() << ")";
                }
                std::cout << " = " << symbol.value;
            }
        }
    }

    void visit(Style* style) override {
        std::cout << "(style)";
        if (style->parent) {
            const Reference& parentRef = style->parent.value();
            std::cout << " parent=";
            if (parentRef.name) {
                if (parentRef.privateReference) {
                    std::cout << "*";
                }
                std::cout << parentRef.name.value() << " ";
            }

            if (parentRef.id) {
                std::cout << parentRef.id.value();
            }
        }

        for (const auto& entry : style->entries) {
            std::cout << "\n        ";
            if (entry.key.name) {
                const ResourceName& name = entry.key.name.value();
                if (!name.package.empty()) {
                    std::cout << name.package << ":";
                }
                std::cout << name.entry;
            }

            if (entry.key.id) {
                std::cout << "(" << entry.key.id.value() << ")";
            }

            std::cout << "=" << *entry.value;
        }
    }

    void visit(Array* array) override {
        array->print(&std::cout);
    }

    void visit(Plural* plural) override {
        plural->print(&std::cout);
    }

    void visit(Styleable* styleable) override {
        std::cout << "(styleable)";
        for (const auto& attr : styleable->entries) {
            std::cout << "\n        ";
            if (attr.name) {
                const ResourceName& name = attr.name.value();
                if (!name.package.empty()) {
                    std::cout << name.package << ":";
                }
                std::cout << name.entry;
            }

            if (attr.id) {
                std::cout << "(" << attr.id.value() << ")";
            }
        }
    }

    void visitItem(Item* item) override {
        item->print(&std::cout);
    }
};

void Debug::printTable(ResourceTable* table, const DebugPrintTableOptions& options) {
    PrintVisitor visitor;

    for (auto& package : table->packages) {
        std::cout << "Package name=" << package->name;
        if (package->id) {
            std::cout << " id=" << std::hex << (int) package->id.value() << std::dec;
        }
        std::cout << std::endl;

        for (const auto& type : package->types) {
            std::cout << "\n  type " << type->type;
            if (type->id) {
                std::cout << " id=" << std::hex << (int) type->id.value() << std::dec;
            }
            std::cout << " entryCount=" << type->entries.size() << std::endl;

            std::vector<const ResourceEntry*> sortedEntries;
            for (const auto& entry : type->entries) {
                auto iter = std::lower_bound(sortedEntries.begin(), sortedEntries.end(), entry.get(),
                        [](const ResourceEntry* a, const ResourceEntry* b) -> bool {
                            if (a->id && b->id) {
                                return a->id.value() < b->id.value();
                            } else if (a->id) {
                                return true;
                            } else {
                                return false;
                            }
                        });
                sortedEntries.insert(iter, entry.get());
            }

            for (const ResourceEntry* entry : sortedEntries) {
                ResourceId id(package->id ? package->id.value() : uint8_t(0),
                              type->id ? type->id.value() : uint8_t(0),
                              entry->id ? entry->id.value() : uint16_t(0));
                ResourceName name(package->name, type->type, entry->name);

                std::cout << "    spec resource " << id << " " << name;
                switch (entry->symbolStatus.state) {
                case SymbolState::kPublic: std::cout << " PUBLIC"; break;
                case SymbolState::kPrivate: std::cout << " _PRIVATE_"; break;
                default: break;
                }

                std::cout << std::endl;

                for (const auto& value : entry->values) {
                    std::cout << "      (" << value->config << ") ";
                    value->value->accept(&visitor);
                    if (options.showSources && !value->value->getSource().path.empty()) {
                        std::cout << " src=" << value->value->getSource();
                    }
                    std::cout << std::endl;
                }
            }
        }
    }
}

static size_t getNodeIndex(const std::vector<ResourceName>& names, const ResourceName& name) {
    auto iter = std::lower_bound(names.begin(), names.end(), name);
    assert(iter != names.end() && *iter == name);
    return std::distance(names.begin(), iter);
}

void Debug::printStyleGraph(ResourceTable* table, const ResourceName& targetStyle) {
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

        Maybe<ResourceTable::SearchResult> result = table->findResource(styleName);
        if (result) {
            ResourceEntry* entry = result.value().entry;
            for (const auto& value : entry->values) {
                if (Style* style = valueCast<Style>(value->value.get())) {
                    if (style->parent && style->parent.value().name) {
                        parents.insert(style->parent.value().name.value());
                        stylesToVisit.push(style->parent.value().name.value());
                    }
                }
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

void Debug::dumpHex(const void* data, size_t len) {
    const uint8_t* d = (const uint8_t*) data;
    for (size_t i = 0; i < len; i++) {
        std::cerr << std::hex << std::setfill('0') << std::setw(2) << (uint32_t) d[i] << " ";
        if (i % 8 == 7) {
            std::cerr << "\n";
        }
    }

    if (len - 1 % 8 != 7) {
        std::cerr << std::endl;
    }
}


} // namespace aapt
