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

#include <androidfw/TypeWrappers.h>
#include <format/binary/ResChunkPullParser.h>

#include <algorithm>
#include <map>
#include <memory>
#include <queue>
#include <set>
#include <vector>

#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "idmap2/Policies.h"
#include "text/Printer.h"
#include "util/Util.h"

using ::aapt::text::Printer;
using ::android::StringPiece;
using ::android::base::StringPrintf;

using android::idmap2::policy::kPolicyStringToFlag;

using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace aapt {

namespace {

class ValueHeadlinePrinter : public ConstValueVisitor {
 public:
  using ConstValueVisitor::Visit;

  explicit ValueHeadlinePrinter(const std::string& package, Printer* printer)
      : package_(package), printer_(printer) {
  }

  void Visit(const Attribute* attr) override {
    printer_->Print("(attr) type=");
    printer_->Print(attr->MaskString());
    if (!attr->symbols.empty()) {
      printer_->Print(StringPrintf(" size=%zd", attr->symbols.size()));
    }
  }

  void Visit(const Style* style) override {
    printer_->Print(StringPrintf("(style) size=%zd", style->entries.size()));
    if (style->parent) {
      printer_->Print(" parent=");

      const Reference& parent_ref = style->parent.value();
      if (parent_ref.name) {
        if (parent_ref.private_reference) {
          printer_->Print("*");
        }

        const ResourceName& parent_name = parent_ref.name.value();
        if (package_ != parent_name.package) {
          printer_->Print(parent_name.package);
          printer_->Print(":");
        }
        printer_->Print(parent_name.type.to_string());
        printer_->Print("/");
        printer_->Print(parent_name.entry);
        if (parent_ref.id) {
          printer_->Print(" (");
          printer_->Print(parent_ref.id.value().to_string());
          printer_->Print(")");
        }
      } else if (parent_ref.id) {
        printer_->Print(parent_ref.id.value().to_string());
      } else {
        printer_->Print("???");
      }
    }
  }

  void Visit(const Array* array) override {
    printer_->Print(StringPrintf("(array) size=%zd", array->elements.size()));
  }

  void Visit(const Plural* plural) override {
    size_t count = std::count_if(plural->values.begin(), plural->values.end(),
                                 [](const std::unique_ptr<Item>& v) { return v != nullptr; });
    printer_->Print(StringPrintf("(plurals) size=%zd", count));
  }

  void Visit(const Styleable* styleable) override {
    printer_->Println(StringPrintf("(styleable) size=%zd", styleable->entries.size()));
  }

  void VisitItem(const Item* item) override {
    // Pretty much guaranteed to be one line.
    if (const Reference* ref = ValueCast<Reference>(item)) {
      // Special case Reference so that we can print local resources without a package name.
      ref->PrettyPrint(package_, printer_);
    } else {
      item->PrettyPrint(printer_);
    }
  }

 private:
  std::string package_;
  Printer* printer_;
};

class ValueBodyPrinter : public ConstValueVisitor {
 public:
  using ConstValueVisitor::Visit;

  explicit ValueBodyPrinter(const std::string& package, Printer* printer)
      : package_(package), printer_(printer) {
  }

  void Visit(const Attribute* attr) override {
    constexpr uint32_t kMask = android::ResTable_map::TYPE_ENUM | android::ResTable_map::TYPE_FLAGS;
    if (attr->type_mask & kMask) {
      for (const auto& symbol : attr->symbols) {
        if (symbol.symbol.name) {
          printer_->Print(symbol.symbol.name.value().entry);

          if (symbol.symbol.id) {
            printer_->Print("(");
            printer_->Print(symbol.symbol.id.value().to_string());
            printer_->Print(")");
          }
        } else if (symbol.symbol.id) {
          printer_->Print(symbol.symbol.id.value().to_string());
        } else {
          printer_->Print("???");
        }

        printer_->Println(StringPrintf("=0x%08x", symbol.value));
      }
    }
  }

  void Visit(const Style* style) override {
    for (const auto& entry : style->entries) {
      if (entry.key.name) {
        const ResourceName& name = entry.key.name.value();
        if (!name.package.empty() && name.package != package_) {
          printer_->Print(name.package);
          printer_->Print(":");
        }
        printer_->Print(name.entry);

        if (entry.key.id) {
          printer_->Print("(");
          printer_->Print(entry.key.id.value().to_string());
          printer_->Print(")");
        }
      } else if (entry.key.id) {
        printer_->Print(entry.key.id.value().to_string());
      } else {
        printer_->Print("???");
      }

      printer_->Print("=");
      PrintItem(*entry.value);
      printer_->Println();
    }
  }

  void Visit(const Array* array) override {
    const size_t count = array->elements.size();
    printer_->Print("[");
    for (size_t i = 0u; i < count; i++) {
      if (i != 0u && i % 4u == 0u) {
        printer_->Println();
        printer_->Print(" ");
      }
      PrintItem(*array->elements[i]);
      if (i != count - 1) {
        printer_->Print(", ");
      }
    }
    printer_->Println("]");
  }

  void Visit(const Plural* plural) override {
    constexpr std::array<const char*, Plural::Count> kPluralNames = {
        {"zero", "one", "two", "few", "many", "other"}};

    for (size_t i = 0; i < Plural::Count; i++) {
      if (plural->values[i] != nullptr) {
        printer_->Print(StringPrintf("%s=", kPluralNames[i]));
        PrintItem(*plural->values[i]);
        printer_->Println();
      }
    }
  }

  void Visit(const Styleable* styleable) override {
    for (const auto& attr : styleable->entries) {
      if (attr.name) {
        const ResourceName& name = attr.name.value();
        if (!name.package.empty() && name.package != package_) {
          printer_->Print(name.package);
          printer_->Print(":");
        }
        printer_->Print(name.entry);

        if (attr.id) {
          printer_->Print("(");
          printer_->Print(attr.id.value().to_string());
          printer_->Print(")");
        }
      }

      if (attr.id) {
        printer_->Print(attr.id.value().to_string());
      }
      printer_->Println();
    }
  }

  void VisitItem(const Item* item) override {
    // Intentionally left empty, we already printed the Items.
  }

 private:
  void PrintItem(const Item& item) {
    if (const Reference* ref = ValueCast<Reference>(&item)) {
      // Special case Reference so that we can print local resources without a package name.
      ref->PrettyPrint(package_, printer_);
    } else {
      item.PrettyPrint(printer_);
    }
  }

  std::string package_;
  Printer* printer_;
};

}  // namespace

void Debug::PrintTable(const ResourceTable& table, const DebugPrintTableOptions& options,
                       Printer* printer) {
  const auto table_view = table.GetPartitionedView();
  for (const auto& package : table_view.packages) {
    ValueHeadlinePrinter headline_printer(package.name, printer);
    ValueBodyPrinter body_printer(package.name, printer);

    printer->Print("Package name=");
    printer->Print(package.name);
    if (package.id) {
      printer->Print(StringPrintf(" id=%02x", package.id.value()));
    }
    printer->Println();

    printer->Indent();
    for (const auto& type : package.types) {
      printer->Print("type ");
      printer->Print(to_string(type.type));
      if (type.id) {
        printer->Print(StringPrintf(" id=%02x", type.id.value()));
      }
      printer->Println(StringPrintf(" entryCount=%zd", type.entries.size()));

      printer->Indent();
      for (const ResourceTableEntryView& entry : type.entries) {
        printer->Print("resource ");
        printer->Print(ResourceId(package.id.value_or(0), type.id.value_or(0), entry.id.value_or(0))
                           .to_string());
        printer->Print(" ");

        // Write the name without the package (this is obvious and too verbose).
        printer->Print(to_string(type.type));
        printer->Print("/");
        printer->Print(entry.name);

        switch (entry.visibility.level) {
          case Visibility::Level::kPublic:
            printer->Print(" PUBLIC");
            break;
          case Visibility::Level::kPrivate:
            printer->Print(" _PRIVATE_");
            break;
          case Visibility::Level::kUndefined:
            // Print nothing.
            break;
        }

        if (entry.visibility.staged_api) {
          printer->Print(" STAGED");
        }

        if (entry.overlayable_item) {
          printer->Print(" OVERLAYABLE");
        }

        if (entry.staged_id) {
          printer->Print(" STAGED_ID=");
          printer->Print(entry.staged_id.value().id.to_string());
        }

        printer->Println();

        if (options.show_values) {
          printer->Indent();
          for (const auto& value : entry.values) {
            printer->Print("(");
            printer->Print(value->config.to_string());
            printer->Print(") ");
            value->value->Accept(&headline_printer);
            if (options.show_sources && !value->value->GetSource().path.empty()) {
              printer->Print(" src=");
              printer->Print(value->value->GetSource().to_string());
            }
            printer->Println();
            printer->Indent();
            value->value->Accept(&body_printer);
            printer->Undent();
          }
          printer->Undent();
        }
      }
      printer->Undent();
    }
    printer->Undent();
  }
}

static size_t GetNodeIndex(const std::vector<ResourceName>& names, const ResourceName& name) {
  auto iter = std::lower_bound(names.begin(), names.end(), name);
  CHECK(iter != names.end());
  CHECK(*iter == name);
  return std::distance(names.begin(), iter);
}

void Debug::PrintStyleGraph(ResourceTable* table, const ResourceName& target_style) {
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

    std::optional<ResourceTable::SearchResult> result = table->FindResource(style_name);
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
    std::cout << "  node_" << GetNodeIndex(names, name) << " [label=\"" << name << "\"];\n";
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
    std::cerr << std::hex << std::setfill('0') << std::setw(2) << (uint32_t)d[i] << " ";
    if (i % 8 == 7) {
      std::cerr << "\n";
    }
  }

  if (len - 1 % 8 != 7) {
    std::cerr << std::endl;
  }
}

void Debug::DumpResStringPool(const android::ResStringPool* pool, text::Printer* printer) {
  using namespace android;

  if (pool->getError() == NO_INIT) {
    printer->Print("String pool is unitialized.\n");
    return;
  } else if (pool->getError() != NO_ERROR) {
    printer->Print("String pool is corrupt/invalid.\n");
    return;
  }

  SortedVector<const void*> uniqueStrings;
  const size_t N = pool->size();
  for (size_t i=0; i<N; i++) {
    size_t len;
    if (pool->isUTF8()) {
      uniqueStrings.add(UnpackOptionalString(pool->string8At(i), &len));
    } else {
      uniqueStrings.add(UnpackOptionalString(pool->stringAt(i), &len));
    }
  }

  printer->Print(StringPrintf("String pool of %zd unique %s %s strings, %zd entries and %zd styles "
                              "using %zd bytes:\n", uniqueStrings.size(),
                              pool->isUTF8() ? "UTF-8" : "UTF-16",
                              pool->isSorted() ? "sorted" : "non-sorted", N, pool->styleCount(),
                              pool->bytes()));

  const size_t NS = pool->size();
  for (size_t s=0; s<NS; s++) {
    auto str = pool->string8ObjectAt(s);
    printer->Print(StringPrintf("String #%zd : %s\n", s, str.has_value() ? str->string() : ""));
  }
}

namespace {

class XmlPrinter : public xml::ConstVisitor {
 public:
  using xml::ConstVisitor::Visit;

  explicit XmlPrinter(Printer* printer) : printer_(printer) {
  }

  void Visit(const xml::Element* el) override {
    for (const xml::NamespaceDecl& decl : el->namespace_decls) {
      printer_->Println(StringPrintf("N: %s=%s (line=%zu)", decl.prefix.c_str(), decl.uri.c_str(),
                                     decl.line_number));
      printer_->Indent();
    }

    printer_->Print("E: ");
    if (!el->namespace_uri.empty()) {
      printer_->Print(el->namespace_uri);
      printer_->Print(":");
    }
    printer_->Println(StringPrintf("%s (line=%zu)", el->name.c_str(), el->line_number));
    printer_->Indent();

    for (const xml::Attribute& attr : el->attributes) {
      printer_->Print("A: ");
      if (!attr.namespace_uri.empty()) {
        printer_->Print(attr.namespace_uri);
        printer_->Print(":");
      }
      printer_->Print(attr.name);

      if (attr.compiled_attribute) {
        printer_->Print("(");
        printer_->Print(attr.compiled_attribute.value().id.value_or(ResourceId(0)).to_string());
        printer_->Print(")");
      }
      printer_->Print("=");
      if (attr.compiled_value != nullptr) {
        attr.compiled_value->PrettyPrint(printer_);
      } else {
        printer_->Print("\"");
        printer_->Print(attr.value);
        printer_->Print("\"");
      }

      if (!attr.value.empty()) {
        printer_->Print(" (Raw: \"");
        printer_->Print(attr.value);
        printer_->Print("\")");
      }
      printer_->Println();
    }

    printer_->Indent();
    xml::ConstVisitor::Visit(el);
    printer_->Undent();
    printer_->Undent();

    for (size_t i = 0; i < el->namespace_decls.size(); i++) {
      printer_->Undent();
    }
  }

  void Visit(const xml::Text* text) override {
    printer_->Println(StringPrintf("T: '%s'", text->text.c_str()));
  }

 private:
  Printer* printer_;
};

}  // namespace

void Debug::DumpXml(const xml::XmlResource& doc, Printer* printer) {
  XmlPrinter xml_visitor(printer);
  doc.root->Accept(&xml_visitor);
}

struct DumpOverlayableEntry {
  std::string overlayable_section;
  std::string policy_subsection;
  std::string resource_name;
};

void Debug::DumpOverlayable(const ResourceTable& table, text::Printer* printer) {
  std::vector<DumpOverlayableEntry> items;
  for (const auto& package : table.packages) {
    for (const auto& type : package->types) {
      for (const auto& entry : type->entries) {
        if (entry->overlayable_item) {
          const auto& overlayable_item = entry->overlayable_item.value();
          const auto overlayable_section = StringPrintf(R"(name="%s" actor="%s")",
              overlayable_item.overlayable->name.c_str(),
              overlayable_item.overlayable->actor.c_str());
          const auto policy_subsection = StringPrintf(R"(policies="%s")",
              android::idmap2::policy::PoliciesToDebugString(overlayable_item.policies).c_str());
          const auto value =
            StringPrintf("%s/%s", to_string(type->type).data(), entry->name.c_str());
          items.push_back(DumpOverlayableEntry{overlayable_section, policy_subsection, value});
        }
      }
    }
  }

  std::sort(items.begin(), items.end(),
      [](const DumpOverlayableEntry& a, const DumpOverlayableEntry& b) {
        if (a.overlayable_section != b.overlayable_section) {
          return a.overlayable_section < b.overlayable_section;
        }
        if (a.policy_subsection != b.policy_subsection) {
          return a.policy_subsection < b.policy_subsection;
        }
        return a.resource_name < b.resource_name;
      });

  std::string last_overlayable_section;
  std::string last_policy_subsection;
  for (const auto& item : items) {
    if (last_overlayable_section != item.overlayable_section) {
      printer->Println(item.overlayable_section);
      last_overlayable_section = item.overlayable_section;
    }
    if (last_policy_subsection != item.policy_subsection) {
      printer->Indent();
      printer->Println(item.policy_subsection);
      last_policy_subsection = item.policy_subsection;
      printer->Undent();
    }
    printer->Indent();
    printer->Indent();
    printer->Println(item.resource_name);
    printer->Undent();
    printer->Undent();
  }
}

namespace {

using namespace android;

class ChunkPrinter {
 public:
  ChunkPrinter(const void* data, size_t len, Printer* printer, IDiagnostics* diag)
      : data_(data), data_len_(len), printer_(printer), diag_(diag) {
  }

  void PrintChunkHeader(const ResChunk_header* chunk) {
    switch (util::DeviceToHost16(chunk->type)) {
      case RES_STRING_POOL_TYPE:
        printer_->Print("[RES_STRING_POOL_TYPE]");
        break;
      case RES_TABLE_LIBRARY_TYPE:
        printer_->Print("[RES_TABLE_LIBRARY_TYPE]");
        break;
      case RES_TABLE_TYPE:
        printer_->Print("[ResTable_header]");
        break;
      case RES_TABLE_PACKAGE_TYPE:
        printer_->Print("[ResTable_package]");
        break;
      case RES_TABLE_TYPE_TYPE:
        printer_->Print("[ResTable_type]");
        break;
      case RES_TABLE_TYPE_SPEC_TYPE:
        printer_->Print("[RES_TABLE_TYPE_SPEC_TYPE]");
        break;
      default:
        break;
    }

    printer_->Print(StringPrintf(" chunkSize: %u", util::DeviceToHost32(chunk->size)));
    printer_->Print(StringPrintf(" headerSize: %u", util::DeviceToHost32(chunk->headerSize)));
  }

  bool PrintTable(const ResTable_header* chunk) {
    printer_->Print(
        StringPrintf(" Package count: %u\n", util::DeviceToHost32(chunk->packageCount)));

    // Print the chunks contained within the table
    printer_->Indent();
    bool success = PrintChunk(
        ResChunkPullParser(GetChunkData(&chunk->header), GetChunkDataLen(&chunk->header)));
    printer_->Undent();
    return success;
  }

  void PrintResValue(const Res_value* value, const ConfigDescription& config,
                     const ResourceType* type) {
    printer_->Print("[Res_value]");
    printer_->Print(StringPrintf(" size: %u", util::DeviceToHost32(value->size)));
    printer_->Print(StringPrintf(" dataType: 0x%02x", util::DeviceToHost32(value->dataType)));
    printer_->Print(StringPrintf(" data: 0x%08x", util::DeviceToHost32(value->data)));

    if (type) {
      auto item =
          ResourceUtils::ParseBinaryResValue(*type, config, value_pool_, *value, &out_pool_);
      printer_->Print(" (");
      item->PrettyPrint(printer_);
      printer_->Print(")");
    }

    printer_->Print("\n");
  }

  bool PrintTableType(const ResTable_type* chunk) {
    printer_->Print(StringPrintf(" id: 0x%02x", util::DeviceToHost32(chunk->id)));
    printer_->Print(StringPrintf(
        " name: %s", util::GetString(type_pool_, util::DeviceToHost32(chunk->id) - 1).c_str()));
    printer_->Print(StringPrintf(" flags: 0x%02x", util::DeviceToHost32(chunk->flags)));
    printer_->Print(StringPrintf(" entryCount: %u", util::DeviceToHost32(chunk->entryCount)));
    printer_->Print(StringPrintf(" entryStart: %u", util::DeviceToHost32(chunk->entriesStart)));

    ConfigDescription config;
    config.copyFromDtoH(chunk->config);
    printer_->Print(StringPrintf(" config: %s\n", config.to_string().c_str()));

    const ResourceType* type =
        ParseResourceType(util::GetString(type_pool_, util::DeviceToHost32(chunk->id) - 1));

    printer_->Indent();

    TypeVariant tv(chunk);
    for (auto it = tv.beginEntries(); it != tv.endEntries(); ++it) {
      const ResTable_entry* entry = *it;
      if (!entry) {
        continue;
      }

      printer_->Print((entry->flags & ResTable_entry::FLAG_COMPLEX) ? "[ResTable_map_entry]"
                                                                    : "[ResTable_entry]");
      printer_->Print(StringPrintf(" id: 0x%04x", it.index()));
      printer_->Print(StringPrintf(
          " name: %s", util::GetString(key_pool_, util::DeviceToHost32(entry->key.index)).c_str()));
      printer_->Print(StringPrintf(" keyIndex: %u", util::DeviceToHost32(entry->key.index)));
      printer_->Print(StringPrintf(" size: %u", util::DeviceToHost32(entry->size)));
      printer_->Print(StringPrintf(" flags: 0x%04x", util::DeviceToHost32(entry->flags)));

      printer_->Indent();

      if (entry->flags & ResTable_entry::FLAG_COMPLEX) {
        auto map_entry = (const ResTable_map_entry*)entry;
        printer_->Print(StringPrintf(" count: 0x%04x", util::DeviceToHost32(map_entry->count)));
        printer_->Print(
            StringPrintf(" parent: 0x%08x\n", util::DeviceToHost32(map_entry->parent.ident)));

        // Print the name and value mappings
        auto maps =
            (const ResTable_map*)((const uint8_t*)entry + util::DeviceToHost32(entry->size));
        for (size_t i = 0, count = util::DeviceToHost32(map_entry->count); i < count; i++) {
          PrintResValue(&(maps[i].value), config, type);

          printer_->Print(StringPrintf(
              " name: %s name-id:%d\n",
              util::GetString(key_pool_, util::DeviceToHost32(maps[i].name.ident)).c_str(),
              util::DeviceToHost32(maps[i].name.ident)));
        }
      } else {
        printer_->Print("\n");

        // Print the value of the entry
        auto value = (const Res_value*)((const uint8_t*)entry + util::DeviceToHost32(entry->size));
        PrintResValue(value, config, type);
      }

      printer_->Undent();
    }

    printer_->Undent();
    return true;
  }

  void PrintStringPool(const ResStringPool_header* chunk) {
    // Initialize the string pools

    ResStringPool* pool;
    if (value_pool_.getError() == NO_INIT) {
      pool = &value_pool_;
    } else if (type_pool_.getError() == NO_INIT) {
      pool = &type_pool_;
    } else if (key_pool_.getError() == NO_INIT) {
      pool = &key_pool_;
    } else {
      return;
    }

    pool->setTo(chunk,
                util::DeviceToHost32((reinterpret_cast<const ResChunk_header*>(chunk))->size));

    printer_->Print("\n");

    for (size_t i = 0; i < pool->size(); i++) {
      printer_->Print(StringPrintf("#%zd : %s\n", i, util::GetString(*pool, i).c_str()));
    }
  }

  bool PrintPackage(const ResTable_package* chunk) {
    printer_->Print(StringPrintf(" id: 0x%02x", util::DeviceToHost32(chunk->id)));

    size_t len = strnlen16((const char16_t*)chunk->name, std::size(chunk->name));
    std::u16string package_name(len, u'\0');
    package_name.resize(len);
    for (size_t i = 0; i < len; i++) {
      package_name[i] = util::DeviceToHost16(chunk->name[i]);
    }

    printer_->Print(StringPrintf("name: %s", String8(package_name.c_str()).c_str()));
    printer_->Print(StringPrintf(" typeStrings: %u", util::DeviceToHost32(chunk->typeStrings)));
    printer_->Print(
        StringPrintf(" lastPublicType: %u", util::DeviceToHost32(chunk->lastPublicType)));
    printer_->Print(StringPrintf(" keyStrings: %u", util::DeviceToHost32(chunk->keyStrings)));
    printer_->Print(StringPrintf(" lastPublicKey: %u", util::DeviceToHost32(chunk->lastPublicKey)));
    printer_->Print(StringPrintf(" typeIdOffset: %u\n", util::DeviceToHost32(chunk->typeIdOffset)));

    // Print the chunks contained within the table
    printer_->Indent();
    bool success = PrintChunk(
        ResChunkPullParser(GetChunkData(&chunk->header), GetChunkDataLen(&chunk->header)));
    printer_->Undent();
    return success;
  }

  bool PrintChunk(ResChunkPullParser&& parser) {
    while (ResChunkPullParser::IsGoodEvent(parser.Next())) {
      auto chunk = parser.chunk();
      PrintChunkHeader(chunk);

      switch (util::DeviceToHost16(chunk->type)) {
        case RES_STRING_POOL_TYPE:
          PrintStringPool(reinterpret_cast<const ResStringPool_header*>(chunk));
          break;

        case RES_TABLE_TYPE:
          PrintTable(reinterpret_cast<const ResTable_header*>(chunk));
          break;

        case RES_TABLE_PACKAGE_TYPE:
          type_pool_.uninit();
          key_pool_.uninit();
          PrintPackage(reinterpret_cast<const ResTable_package*>(chunk));
          break;

        case RES_TABLE_TYPE_TYPE:
          PrintTableType(reinterpret_cast<const ResTable_type*>(chunk));
          break;

        default:
          printer_->Print("\n");
          break;
      }
    }

    if (parser.event() == ResChunkPullParser::Event::kBadDocument) {
      diag_->Error(DiagMessage(source_) << "corrupt resource table: " << parser.error());
      return false;
    }

    return true;
  }

  void Print() {
    PrintChunk(ResChunkPullParser(data_, data_len_));
    printer_->Print("[End]\n");
  }

 private:
  const Source source_;
  const void* data_;
  const size_t data_len_;
  Printer* printer_;
  IDiagnostics* diag_;

  // The standard value string pool for resource values.
  ResStringPool value_pool_;

  // The string pool that holds the names of the types defined
  // in this table.
  ResStringPool type_pool_;

  // The string pool that holds the names of the entries defined
  // in this table.
  ResStringPool key_pool_;

  StringPool out_pool_;
};

}  // namespace

void Debug::DumpChunks(const void* data, size_t len, Printer* printer, IDiagnostics* diag) {
  ChunkPrinter chunk_printer(data, len, printer, diag);
  chunk_printer.Print();
}

}  // namespace aapt
