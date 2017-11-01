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

#include <algorithm>
#include <iostream>
#include <map>
#include <memory>
#include <queue>
#include <set>
#include <vector>

#include "android-base/logging.h"

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "util/Util.h"

namespace aapt {

class PrintVisitor : public ValueVisitor {
 public:
  using ValueVisitor::Visit;

  void Visit(Attribute* attr) override {
    std::cout << "(attr) type=";
    attr->PrintMask(&std::cout);
    static constexpr uint32_t kMask =
        android::ResTable_map::TYPE_ENUM | android::ResTable_map::TYPE_FLAGS;
    if (attr->type_mask & kMask) {
      for (const auto& symbol : attr->symbols) {
        std::cout << "\n        " << symbol.symbol.name.value().entry;
        if (symbol.symbol.id) {
          std::cout << " (" << symbol.symbol.id.value() << ")";
        }
        std::cout << " = " << symbol.value;
      }
    }
  }

  void Visit(Style* style) override {
    std::cout << "(style)";
    if (style->parent) {
      const Reference& parent_ref = style->parent.value();
      std::cout << " parent=";
      if (parent_ref.name) {
        if (parent_ref.private_reference) {
          std::cout << "*";
        }
        std::cout << parent_ref.name.value() << " ";
      }

      if (parent_ref.id) {
        std::cout << parent_ref.id.value();
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

  void Visit(Array* array) override { array->Print(&std::cout); }

  void Visit(Plural* plural) override { plural->Print(&std::cout); }

  void Visit(Styleable* styleable) override {
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

  void VisitItem(Item* item) override { item->Print(&std::cout); }
};

void Debug::PrintTable(ResourceTable* table,
                       const DebugPrintTableOptions& options) {
  PrintVisitor visitor;

  for (auto& package : table->packages) {
    std::cout << "Package name=" << package->name;
    if (package->id) {
      std::cout << " id=" << std::hex << (int)package->id.value() << std::dec;
    }
    std::cout << std::endl;

    for (const auto& type : package->types) {
      std::cout << "\n  type " << type->type;
      if (type->id) {
        std::cout << " id=" << std::hex << (int)type->id.value() << std::dec;
      }
      std::cout << " entryCount=" << type->entries.size() << std::endl;

      std::vector<const ResourceEntry*> sorted_entries;
      for (const auto& entry : type->entries) {
        auto iter = std::lower_bound(
            sorted_entries.begin(), sorted_entries.end(), entry.get(),
            [](const ResourceEntry* a, const ResourceEntry* b) -> bool {
              if (a->id && b->id) {
                return a->id.value() < b->id.value();
              } else if (a->id) {
                return true;
              } else {
                return false;
              }
            });
        sorted_entries.insert(iter, entry.get());
      }

      for (const ResourceEntry* entry : sorted_entries) {
        ResourceId id(package->id ? package->id.value() : uint8_t(0),
                      type->id ? type->id.value() : uint8_t(0),
                      entry->id ? entry->id.value() : uint16_t(0));
        ResourceName name(package->name, type->type, entry->name);

        std::cout << "    spec resource " << id << " " << name;
        switch (entry->symbol_status.state) {
          case SymbolState::kPublic:
            std::cout << " PUBLIC";
            break;
          case SymbolState::kPrivate:
            std::cout << " _PRIVATE_";
            break;
          default:
            break;
        }

        std::cout << std::endl;

        for (const auto& value : entry->values) {
          std::cout << "      (" << value->config << ") ";
          value->value->Accept(&visitor);
          if (options.show_sources && !value->value->GetSource().path.empty()) {
            std::cout << " src=" << value->value->GetSource();
          }
          std::cout << std::endl;
        }
      }
    }
  }
}

static size_t GetNodeIndex(const std::vector<ResourceName>& names,
                           const ResourceName& name) {
  auto iter = std::lower_bound(names.begin(), names.end(), name);
  CHECK(iter != names.end());
  CHECK(*iter == name);
  return std::distance(names.begin(), iter);
}

void Debug::PrintStyleGraph(ResourceTable* table,
                            const ResourceName& target_style) {
  std::map<ResourceName, std::set<ResourceName>> graph;

  std::queue<ResourceName> styles_to_visit;
  styles_to_visit.push(target_style);
  for (; !styles_to_visit.empty(); styles_to_visit.pop()) {
    const ResourceName& style_name = styles_to_visit.front();
    std::set<ResourceName>& parents = graph[style_name];
    if (!parents.empty()) {
      // We've already visited this style.
      continue;
    }

    Maybe<ResourceTable::SearchResult> result = table->FindResource(style_name);
    if (result) {
      ResourceEntry* entry = result.value().entry;
      for (const auto& value : entry->values) {
        if (Style* style = ValueCast<Style>(value->value.get())) {
          if (style->parent && style->parent.value().name) {
            parents.insert(style->parent.value().name.value());
            styles_to_visit.push(style->parent.value().name.value());
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
    std::cout << "  node_" << GetNodeIndex(names, name) << " [label=\"" << name
              << "\"];\n";
  }

  for (const auto& entry : graph) {
    const ResourceName& style_name = entry.first;
    size_t style_node_index = GetNodeIndex(names, style_name);

    for (const auto& parent_name : entry.second) {
      std::cout << "  node_" << style_node_index << " -> "
                << "node_" << GetNodeIndex(names, parent_name) << ";\n";
    }
  }

  std::cout << "}" << std::endl;
}

void Debug::DumpHex(const void* data, size_t len) {
  const uint8_t* d = (const uint8_t*)data;
  for (size_t i = 0; i < len; i++) {
    std::cerr << std::hex << std::setfill('0') << std::setw(2) << (uint32_t)d[i]
              << " ";
    if (i % 8 == 7) {
      std::cerr << "\n";
    }
  }

  if (len - 1 % 8 != 7) {
    std::cerr << std::endl;
  }
}

namespace {

class XmlPrinter : public xml::Visitor {
 public:
  using xml::Visitor::Visit;

  void Visit(xml::Element* el) override {
    std::cerr << prefix_;
    std::cerr << "E: ";
    if (!el->namespace_uri.empty()) {
      std::cerr << el->namespace_uri << ":";
    }
    std::cerr << el->name << " (line=" << el->line_number << ")\n";

    for (const xml::Attribute& attr : el->attributes) {
      std::cerr << prefix_ << "  A: ";
      if (!attr.namespace_uri.empty()) {
        std::cerr << attr.namespace_uri << ":";
      }
      std::cerr << attr.name;

      if (attr.compiled_attribute) {
        std::cerr << "(" << attr.compiled_attribute.value().id.value_or_default(ResourceId(0x0))
                  << ")";
      }
      std::cerr << "=" << attr.value << "\n";
    }

    const size_t previous_size = prefix_.size();
    prefix_ += "  ";
    xml::Visitor::Visit(el);
    prefix_.resize(previous_size);
  }

  void Visit(xml::Namespace* ns) override {
    std::cerr << prefix_;
    std::cerr << "N: " << ns->namespace_prefix << "=" << ns->namespace_uri
              << " (line=" << ns->line_number << ")\n";

    const size_t previous_size = prefix_.size();
    prefix_ += "  ";
    xml::Visitor::Visit(ns);
    prefix_.resize(previous_size);
  }

  void Visit(xml::Text* text) override {
    std::cerr << prefix_;
    std::cerr << "T: '" << text->text << "'\n";
  }

 private:
  std::string prefix_;
};

}  // namespace

void Debug::DumpXml(xml::XmlResource* doc) {
  XmlPrinter printer;
  doc->root->Accept(&printer);
}

}  // namespace aapt
