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

#include "format/binary/XmlFlattener.h"

#include <algorithm>
#include <map>
#include <vector>

#include "android-base/logging.h"
#include "android-base/macros.h"
#include "androidfw/ResourceTypes.h"
#include "utils/misc.h"

#include "ResourceUtils.h"
#include "SdkConstants.h"
#include "ValueVisitor.h"
#include "format/binary/ChunkWriter.h"
#include "format/binary/ResourceTypeExtensions.h"
#include "xml/XmlDom.h"

using namespace android;

using ::aapt::ResourceUtils::StringBuilder;

namespace aapt {

namespace {

constexpr uint32_t kLowPriority = 0xffffffffu;

static bool cmp_xml_attribute_by_id(const xml::Attribute* a, const xml::Attribute* b) {
  if (a->compiled_attribute && a->compiled_attribute.value().id) {
    if (b->compiled_attribute && b->compiled_attribute.value().id) {
      return a->compiled_attribute.value().id.value() < b->compiled_attribute.value().id.value();
    }
    return true;
  } else if (!b->compiled_attribute) {
    int diff = a->namespace_uri.compare(b->namespace_uri);
    if (diff < 0) {
      return true;
    } else if (diff > 0) {
      return false;
    }
    return a->name < b->name;
  }
  return false;
}

class XmlFlattenerVisitor : public xml::ConstVisitor {
 public:
  using xml::ConstVisitor::Visit;

  StringPool pool;
  std::map<uint8_t, StringPool> package_pools;

  struct StringFlattenDest {
    StringPool::Ref ref;
    ResStringPool_ref* dest;
  };

  std::vector<StringFlattenDest> string_refs;

  XmlFlattenerVisitor(BigBuffer* buffer, XmlFlattenerOptions options)
      : buffer_(buffer), options_(options) {
  }

  void Visit(const xml::Text* node) override {
    std::string text = util::TrimWhitespace(node->text).to_string();

    // Skip whitespace only text nodes.
    if (text.empty()) {
      return;
    }

    // Compact leading and trailing whitespace into a single space
    if (isspace(node->text[0])) {
      text = ' ' + text;
    }
    if (isspace(node->text[node->text.length() - 1])) {
      text = text + ' ';
    }

    ChunkWriter writer(buffer_);
    ResXMLTree_node* flat_node = writer.StartChunk<ResXMLTree_node>(RES_XML_CDATA_TYPE);
    flat_node->lineNumber = util::HostToDevice32(node->line_number);
    flat_node->comment.index = util::HostToDevice32(-1);

    // Process plain strings to make sure they get properly escaped.
    text = StringBuilder(true /*preserve_spaces*/).AppendText(text).to_string();

    ResXMLTree_cdataExt* flat_text = writer.NextBlock<ResXMLTree_cdataExt>();
    AddString(text, kLowPriority, &flat_text->data);
    writer.Finish();
  }

  void Visit(const xml::Element* node) override {
    for (const xml::NamespaceDecl& decl : node->namespace_decls) {
      // Skip dedicated tools namespace.
      if (decl.uri != xml::kSchemaTools) {
        WriteNamespace(decl, android::RES_XML_START_NAMESPACE_TYPE);
      }
    }

    {
      ChunkWriter start_writer(buffer_);
      ResXMLTree_node* flat_node =
          start_writer.StartChunk<ResXMLTree_node>(RES_XML_START_ELEMENT_TYPE);
      flat_node->lineNumber = util::HostToDevice32(node->line_number);
      flat_node->comment.index = util::HostToDevice32(-1);

      ResXMLTree_attrExt* flat_elem = start_writer.NextBlock<ResXMLTree_attrExt>();

      // A missing namespace must be null, not an empty string. Otherwise the runtime complains.
      AddString(node->namespace_uri, kLowPriority, &flat_elem->ns,
                true /* treat_empty_string_as_null */);
      AddString(node->name, kLowPriority, &flat_elem->name, true /* treat_empty_string_as_null */);

      flat_elem->attributeStart = util::HostToDevice16(sizeof(*flat_elem));
      flat_elem->attributeSize = util::HostToDevice16(sizeof(ResXMLTree_attribute));

      WriteAttributes(node, flat_elem, &start_writer);

      start_writer.Finish();
    }

    xml::ConstVisitor::Visit(node);

    {
      ChunkWriter end_writer(buffer_);
      ResXMLTree_node* flat_end_node =
          end_writer.StartChunk<ResXMLTree_node>(RES_XML_END_ELEMENT_TYPE);
      flat_end_node->lineNumber = util::HostToDevice32(node->line_number);
      flat_end_node->comment.index = util::HostToDevice32(-1);

      ResXMLTree_endElementExt* flat_end_elem = end_writer.NextBlock<ResXMLTree_endElementExt>();
      AddString(node->namespace_uri, kLowPriority, &flat_end_elem->ns,
                true /* treat_empty_string_as_null */);
      AddString(node->name, kLowPriority, &flat_end_elem->name);

      end_writer.Finish();
    }

    for (auto iter = node->namespace_decls.rbegin(); iter != node->namespace_decls.rend(); ++iter) {
      // Skip dedicated tools namespace.
      if (iter->uri != xml::kSchemaTools) {
        WriteNamespace(*iter, android::RES_XML_END_NAMESPACE_TYPE);
      }
    }
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlFlattenerVisitor);

  // We are adding strings to a StringPool whose strings will be sorted and merged with other
  // string pools. That means we can't encode the ID of a string directly. Instead, we defer the
  // writing of the ID here, until after the StringPool is merged and sorted.
  void AddString(const StringPiece& str, uint32_t priority, android::ResStringPool_ref* dest,
                 bool treat_empty_string_as_null = false) {
    if (str.empty() && treat_empty_string_as_null) {
      // Some parts of the runtime treat null differently than empty string.
      dest->index = util::DeviceToHost32(-1);
    } else {
      string_refs.push_back(
          StringFlattenDest{pool.MakeRef(str, StringPool::Context(priority)), dest});
    }
  }

  // We are adding strings to a StringPool whose strings will be sorted and merged with other
  // string pools. That means we can't encode the ID of a string directly. Instead, we defer the
  // writing of the ID here, until after the StringPool is merged and sorted.
  void AddString(const StringPool::Ref& ref, android::ResStringPool_ref* dest) {
    string_refs.push_back(StringFlattenDest{ref, dest});
  }

  void WriteNamespace(const xml::NamespaceDecl& decl, uint16_t type) {
    ChunkWriter writer(buffer_);

    ResXMLTree_node* flatNode = writer.StartChunk<ResXMLTree_node>(type);
    flatNode->lineNumber = util::HostToDevice32(decl.line_number);
    flatNode->comment.index = util::HostToDevice32(-1);

    ResXMLTree_namespaceExt* flat_ns = writer.NextBlock<ResXMLTree_namespaceExt>();
    AddString(decl.prefix, kLowPriority, &flat_ns->prefix);
    AddString(decl.uri, kLowPriority, &flat_ns->uri);

    writer.Finish();
  }

  void WriteAttributes(const xml::Element* node, ResXMLTree_attrExt* flat_elem,
                       ChunkWriter* writer) {
    filtered_attrs_.clear();
    filtered_attrs_.reserve(node->attributes.size());

    // Filter the attributes.
    for (const xml::Attribute& attr : node->attributes) {
      if (attr.namespace_uri != xml::kSchemaTools) {
        filtered_attrs_.push_back(&attr);
      }
    }

    if (filtered_attrs_.empty()) {
      return;
    }

    const ResourceId kIdAttr(0x010100d0);

    std::sort(filtered_attrs_.begin(), filtered_attrs_.end(), cmp_xml_attribute_by_id);

    flat_elem->attributeCount = util::HostToDevice16(filtered_attrs_.size());

    ResXMLTree_attribute* flat_attr =
        writer->NextBlock<ResXMLTree_attribute>(filtered_attrs_.size());
    uint16_t attribute_index = 1;
    for (const xml::Attribute* xml_attr : filtered_attrs_) {
      // Assign the indices for specific attributes.
      if (xml_attr->compiled_attribute && xml_attr->compiled_attribute.value().id &&
          xml_attr->compiled_attribute.value().id.value() == kIdAttr) {
        flat_elem->idIndex = util::HostToDevice16(attribute_index);
      } else if (xml_attr->namespace_uri.empty()) {
        if (xml_attr->name == "class") {
          flat_elem->classIndex = util::HostToDevice16(attribute_index);
        } else if (xml_attr->name == "style") {
          flat_elem->styleIndex = util::HostToDevice16(attribute_index);
        }
      }
      attribute_index++;

      // Add the namespaceUri to the list of StringRefs to encode. Use null if the namespace
      // is empty (doesn't exist).
      AddString(xml_attr->namespace_uri, kLowPriority, &flat_attr->ns,
                true /* treat_empty_string_as_null */);

      flat_attr->rawValue.index = util::HostToDevice32(-1);

      if (!xml_attr->compiled_attribute || !xml_attr->compiled_attribute.value().id) {
        // The attribute has no associated ResourceID, so the string order doesn't matter.
        AddString(xml_attr->name, kLowPriority, &flat_attr->name);
      } else {
        // Attribute names are stored without packages, but we use
        // their StringPool index to lookup their resource IDs.
        // This will cause collisions, so we can't dedupe
        // attribute names from different packages. We use separate
        // pools that we later combine.
        //
        // Lookup the StringPool for this package and make the reference there.
        const xml::AaptAttribute& aapt_attr = xml_attr->compiled_attribute.value();

        StringPool::Ref name_ref = package_pools[aapt_attr.id.value().package_id()].MakeRef(
            xml_attr->name, StringPool::Context(aapt_attr.id.value().id));

        // Add it to the list of strings to flatten.
        AddString(name_ref, &flat_attr->name);
      }

      std::string processed_str;
      Maybe<StringPiece> compiled_text;
      if (xml_attr->compiled_value != nullptr) {
        // Make sure we're not flattening a String. A String can be referencing a string from
        // a different StringPool than we're using here to build the binary XML.
        String* string_value = ValueCast<String>(xml_attr->compiled_value.get());
        if (string_value != nullptr) {
          // Mark the String's text as needing to be serialized.
          compiled_text = StringPiece(*string_value->value);
        } else {
          // Serialize this compiled value safely.
          CHECK(xml_attr->compiled_value->Flatten(&flat_attr->typedValue));
        }
      } else {
        // There is no compiled value, so treat the raw string as compiled, once it is processed to
        // make sure escape sequences are properly interpreted.
        processed_str =
            StringBuilder(true /*preserve_spaces*/).AppendText(xml_attr->value).to_string();
        compiled_text = StringPiece(processed_str);
      }

      if (compiled_text) {
        // Write out the compiled text and raw_text.
        flat_attr->typedValue.dataType = android::Res_value::TYPE_STRING;
        AddString(compiled_text.value(), kLowPriority,
                  reinterpret_cast<ResStringPool_ref*>(&flat_attr->typedValue.data));
        if (options_.keep_raw_values) {
          AddString(xml_attr->value, kLowPriority, &flat_attr->rawValue);
        } else {
          AddString(compiled_text.value(), kLowPriority, &flat_attr->rawValue);
        }
      } else if (options_.keep_raw_values && !xml_attr->value.empty()) {
        AddString(xml_attr->value, kLowPriority, &flat_attr->rawValue);
      }

      flat_attr->typedValue.size = util::HostToDevice16(sizeof(flat_attr->typedValue));
      flat_attr++;
    }
  }

  BigBuffer* buffer_;
  XmlFlattenerOptions options_;

  // Scratch vector to filter attributes. We avoid allocations making this a member.
  std::vector<const xml::Attribute*> filtered_attrs_;
};

}  // namespace

bool XmlFlattener::Flatten(IAaptContext* context, const xml::Node* node) {
  BigBuffer node_buffer(1024);
  XmlFlattenerVisitor visitor(&node_buffer, options_);
  node->Accept(&visitor);

  // Merge the package pools into the main pool.
  for (auto& package_pool_entry : visitor.package_pools) {
    visitor.pool.Merge(std::move(package_pool_entry.second));
  }

  // Sort the string pool so that attribute resource IDs show up first.
  visitor.pool.Sort([](const StringPool::Context& a, const StringPool::Context& b) -> int {
    return util::compare(a.priority, b.priority);
  });

  // Now we flatten the string pool references into the correct places.
  for (const auto& ref_entry : visitor.string_refs) {
    ref_entry.dest->index = util::HostToDevice32(ref_entry.ref.index());
  }

  // Write the XML header.
  ChunkWriter xml_header_writer(buffer_);
  xml_header_writer.StartChunk<ResXMLTree_header>(RES_XML_TYPE);

  // Flatten the StringPool.
  if (options_.use_utf16) {
    StringPool::FlattenUtf16(buffer_, visitor.pool, context->GetDiagnostics());
  } else {
    StringPool::FlattenUtf8(buffer_, visitor.pool, context->GetDiagnostics());
  }

  {
    // Write the array of resource IDs, indexed by StringPool order.
    ChunkWriter res_id_map_writer(buffer_);
    res_id_map_writer.StartChunk<ResChunk_header>(RES_XML_RESOURCE_MAP_TYPE);
    for (const auto& str : visitor.pool.strings()) {
      ResourceId id(str->context.priority);
      if (str->context.priority == kLowPriority || !id.is_valid()) {
        // When we see the first non-resource ID, we're done.
        break;
      }
      *res_id_map_writer.NextBlock<uint32_t>() = util::HostToDevice32(id.id);
    }
    res_id_map_writer.Finish();
  }

  // Move the nodeBuffer and append it to the out buffer.
  buffer_->AppendBuffer(std::move(node_buffer));

  // Finish the xml header.
  xml_header_writer.Finish();
  return true;
}

bool XmlFlattener::Consume(IAaptContext* context, const xml::XmlResource* resource) {
  if (!resource->root) {
    return false;
  }
  return Flatten(context, resource->root.get());
}

}  // namespace aapt
