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

#include "ResourceParser.h"

#include <functional>
#include <limits>
#include <sstream>

#include <android-base/logging.h>
#include <idmap2/Policies.h>

#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "text/Utf8Iterator.h"
#include "util/ImmutableMap.h"

#include "util/Util.h"
#include "xml/XmlPullParser.h"

using ::aapt::ResourceUtils::StringBuilder;
using ::aapt::text::Utf8Iterator;
using ::android::ConfigDescription;
using ::android::StringPiece;

using android::idmap2::policy::kPolicyStringToFlag;

namespace aapt {
namespace {
constexpr const char* kPublicGroupTag = "public-group";
constexpr const char* kStagingPublicGroupTag = "staging-public-group";
constexpr const char* kStagingPublicGroupFinalTag = "staging-public-group-final";
}  // namespace

constexpr const char* sXliffNamespaceUri = "urn:oasis:names:tc:xliff:document:1.2";

// Returns true if the element is <skip> or <eat-comment> and can be safely ignored.
static bool ShouldIgnoreElement(const StringPiece& ns, const StringPiece& name) {
  return ns.empty() && (name == "skip" || name == "eat-comment");
}

static uint32_t ParseFormatTypeNoEnumsOrFlags(const StringPiece& piece) {
  if (piece == "reference") {
    return android::ResTable_map::TYPE_REFERENCE;
  } else if (piece == "string") {
    return android::ResTable_map::TYPE_STRING;
  } else if (piece == "integer") {
    return android::ResTable_map::TYPE_INTEGER;
  } else if (piece == "boolean") {
    return android::ResTable_map::TYPE_BOOLEAN;
  } else if (piece == "color") {
    return android::ResTable_map::TYPE_COLOR;
  } else if (piece == "float") {
    return android::ResTable_map::TYPE_FLOAT;
  } else if (piece == "dimension") {
    return android::ResTable_map::TYPE_DIMENSION;
  } else if (piece == "fraction") {
    return android::ResTable_map::TYPE_FRACTION;
  }
  return 0;
}

static uint32_t ParseFormatType(const StringPiece& piece) {
  if (piece == "enum") {
    return android::ResTable_map::TYPE_ENUM;
  } else if (piece == "flags") {
    return android::ResTable_map::TYPE_FLAGS;
  }
  return ParseFormatTypeNoEnumsOrFlags(piece);
}

static uint32_t ParseFormatAttribute(const StringPiece& str) {
  uint32_t mask = 0;
  for (const StringPiece& part : util::Tokenize(str, '|')) {
    StringPiece trimmed_part = util::TrimWhitespace(part);
    uint32_t type = ParseFormatType(trimmed_part);
    if (type == 0) {
      return 0;
    }
    mask |= type;
  }
  return mask;
}

// A parsed resource ready to be added to the ResourceTable.
struct ParsedResource {
  ResourceName name;
  ConfigDescription config;
  std::string product;
  Source source;

  ResourceId id;
  Visibility::Level visibility_level = Visibility::Level::kUndefined;
  bool staged_api = false;
  bool allow_new = false;
  std::optional<OverlayableItem> overlayable_item;
  std::optional<StagedId> staged_alias;

  std::string comment;
  std::unique_ptr<Value> value;
  std::list<ParsedResource> child_resources;
};

// Recursively adds resources to the ResourceTable.
static bool AddResourcesToTable(ResourceTable* table, IDiagnostics* diag, ParsedResource* res) {
  StringPiece trimmed_comment = util::TrimWhitespace(res->comment);
  if (trimmed_comment.size() != res->comment.size()) {
    // Only if there was a change do we re-assign.
    res->comment = trimmed_comment.to_string();
  }

  NewResourceBuilder res_builder(res->name);
  if (res->visibility_level != Visibility::Level::kUndefined) {
    Visibility visibility;
    visibility.level = res->visibility_level;
    visibility.staged_api = res->staged_api;
    visibility.source = res->source;
    visibility.comment = res->comment;
    res_builder.SetVisibility(visibility);
  }

  if (res->id.is_valid()) {
    res_builder.SetId(res->id);
  }

  if (res->allow_new) {
    AllowNew allow_new;
    allow_new.source = res->source;
    allow_new.comment = res->comment;
    res_builder.SetAllowNew(allow_new);
  }

  if (res->overlayable_item) {
    res_builder.SetOverlayable(res->overlayable_item.value());
  }

  if (res->value != nullptr) {
    // Attach the comment, source and config to the value.
    res->value->SetComment(std::move(res->comment));
    res->value->SetSource(std::move(res->source));
    res_builder.SetValue(std::move(res->value), res->config, res->product);
  }

  if (res->staged_alias) {
    res_builder.SetStagedId(res->staged_alias.value());
  }

  bool error = false;
  if (!res->name.entry.empty()) {
    if (!table->AddResource(res_builder.Build(), diag)) {
      return false;
    }
  }
  for (ParsedResource& child : res->child_resources) {
    error |= !AddResourcesToTable(table, diag, &child);
  }
  return !error;
}

// Convenient aliases for more readable function calls.
enum { kAllowRawString = true, kNoRawString = false };

ResourceParser::ResourceParser(IDiagnostics* diag, ResourceTable* table,
                               const Source& source,
                               const ConfigDescription& config,
                               const ResourceParserOptions& options)
    : diag_(diag),
      table_(table),
      source_(source),
      config_(config),
      options_(options) {}

// Base class Node for representing the various Spans and UntranslatableSections of an XML string.
// This will be used to traverse and flatten the XML string into a single std::string, with all
// Span and Untranslatable data maintained in parallel, as indices into the string.
class Node {
 public:
  virtual ~Node() = default;

  // Adds the given child node to this parent node's set of child nodes, moving ownership to the
  // parent node as well.
  // Returns a pointer to the child node that was added as a convenience.
  template <typename T>
  T* AddChild(std::unique_ptr<T> node) {
    T* raw_ptr = node.get();
    children.push_back(std::move(node));
    return raw_ptr;
  }

  virtual void Build(StringBuilder* builder) const {
    for (const auto& child : children) {
      child->Build(builder);
    }
  }

  std::vector<std::unique_ptr<Node>> children;
};

// A chunk of text in the XML string. This lives between other tags, such as XLIFF tags and Spans.
class SegmentNode : public Node {
 public:
  std::string data;

  void Build(StringBuilder* builder) const override {
    builder->AppendText(data);
  }
};

// A tag that will be encoded into the final flattened string. Tags like <b> or <i>.
class SpanNode : public Node {
 public:
  std::string name;

  void Build(StringBuilder* builder) const override {
    StringBuilder::SpanHandle span_handle = builder->StartSpan(name);
    Node::Build(builder);
    builder->EndSpan(span_handle);
  }
};

// An XLIFF 'g' tag, which marks a section of the string as untranslatable.
class UntranslatableNode : public Node {
 public:
  void Build(StringBuilder* builder) const override {
    StringBuilder::UntranslatableHandle handle = builder->StartUntranslatable();
    Node::Build(builder);
    builder->EndUntranslatable(handle);
  }
};

// Build a string from XML that converts nested elements into Span objects.
bool ResourceParser::FlattenXmlSubtree(
    xml::XmlPullParser* parser, std::string* out_raw_string, StyleString* out_style_string,
    std::vector<UntranslatableSection>* out_untranslatable_sections) {
  std::string raw_string;
  std::string current_text;

  // The first occurrence of a <xliff:g> tag. Nested <xliff:g> tags are illegal.
  std::optional<size_t> untranslatable_start_depth;

  Node root;
  std::vector<Node*> node_stack;
  node_stack.push_back(&root);

  bool saw_span_node = false;
  SegmentNode* first_segment = nullptr;
  SegmentNode* last_segment = nullptr;

  size_t depth = 1;
  while (depth > 0 && xml::XmlPullParser::IsGoodEvent(parser->Next())) {
    const xml::XmlPullParser::Event event = parser->event();

    // First take care of any SegmentNodes that should be created.
    if (event == xml::XmlPullParser::Event::kStartElement
        || event == xml::XmlPullParser::Event::kEndElement) {
      if (!current_text.empty()) {
        auto segment_node = util::make_unique<SegmentNode>();
        segment_node->data = std::move(current_text);

        last_segment = node_stack.back()->AddChild(std::move(segment_node));
        if (first_segment == nullptr) {
          first_segment = last_segment;
        }
        current_text = {};
      }
    }

    switch (event) {
      case xml::XmlPullParser::Event::kText: {
        current_text += parser->text();
        raw_string += parser->text();
      } break;

      case xml::XmlPullParser::Event::kStartElement: {
        if (parser->element_namespace().empty()) {
          // This is an HTML tag which we encode as a span. Add it to the span stack.
          std::unique_ptr<SpanNode> span_node = util::make_unique<SpanNode>();
          span_node->name = parser->element_name();
          const auto end_attr_iter = parser->end_attributes();
          for (auto attr_iter = parser->begin_attributes(); attr_iter != end_attr_iter;
               ++attr_iter) {
            span_node->name += ";";
            span_node->name += attr_iter->name;
            span_node->name += "=";
            span_node->name += attr_iter->value;
          }

          node_stack.push_back(node_stack.back()->AddChild(std::move(span_node)));
          saw_span_node = true;
        } else if (parser->element_namespace() == sXliffNamespaceUri) {
          // This is an XLIFF tag, which is not encoded as a span.
          if (parser->element_name() == "g") {
            // Check that an 'untranslatable' tag is not already being processed. Nested
            // <xliff:g> tags are illegal.
            if (untranslatable_start_depth) {
              diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                           << "illegal nested XLIFF 'g' tag");
              return false;
            } else {
              // Mark the beginning of an 'untranslatable' section.
              untranslatable_start_depth = depth;
              node_stack.push_back(
                  node_stack.back()->AddChild(util::make_unique<UntranslatableNode>()));
            }
          } else {
            // Ignore unknown XLIFF tags, but don't warn.
            node_stack.push_back(node_stack.back()->AddChild(util::make_unique<Node>()));
          }
        } else {
          // Besides XLIFF, any other namespaced tag is unsupported and ignored.
          diag_->Warn(DiagMessage(source_.WithLine(parser->line_number()))
                      << "ignoring element '" << parser->element_name()
                      << "' with unknown namespace '" << parser->element_namespace() << "'");
          node_stack.push_back(node_stack.back()->AddChild(util::make_unique<Node>()));
        }

        // Enter one level inside the element.
        depth++;
      } break;

      case xml::XmlPullParser::Event::kEndElement: {
        // Return one level from within the element.
        depth--;
        if (depth == 0) {
          break;
        }

        node_stack.pop_back();
        if (untranslatable_start_depth == depth) {
          // This is the end of an untranslatable section.
          untranslatable_start_depth = {};
        }
      } break;

      default:
        // ignore.
        break;
    }
  }

  // Validity check to make sure we processed all the nodes.
  CHECK(node_stack.size() == 1u);
  CHECK(node_stack.back() == &root);

  if (!saw_span_node) {
    // If there were no spans, we must treat this string a little differently (according to AAPT).
    // Find and strip the leading whitespace from the first segment, and the trailing whitespace
    // from the last segment.
    if (first_segment != nullptr) {
      // Trim leading whitespace.
      StringPiece trimmed = util::TrimLeadingWhitespace(first_segment->data);
      if (trimmed.size() != first_segment->data.size()) {
        first_segment->data = trimmed.to_string();
      }
    }

    if (last_segment != nullptr) {
      // Trim trailing whitespace.
      StringPiece trimmed = util::TrimTrailingWhitespace(last_segment->data);
      if (trimmed.size() != last_segment->data.size()) {
        last_segment->data = trimmed.to_string();
      }
    }
  }

  // Have the XML structure flatten itself into the StringBuilder. The StringBuilder will take
  // care of recording the correctly adjusted Spans and UntranslatableSections.
  StringBuilder builder;
  root.Build(&builder);
  if (!builder) {
    diag_->Error(DiagMessage(source_.WithLine(parser->line_number())) << builder.GetError());
    return false;
  }

  ResourceUtils::FlattenedXmlString flattened_string = builder.GetFlattenedString();
  *out_raw_string = std::move(raw_string);
  *out_untranslatable_sections = std::move(flattened_string.untranslatable_sections);
  out_style_string->str = std::move(flattened_string.text);
  out_style_string->spans = std::move(flattened_string.spans);
  return true;
}

bool ResourceParser::Parse(xml::XmlPullParser* parser) {
  bool error = false;
  const size_t depth = parser->depth();
  while (xml::XmlPullParser::NextChildNode(parser, depth)) {
    if (parser->event() != xml::XmlPullParser::Event::kStartElement) {
      // Skip comments and text.
      continue;
    }

    if (!parser->element_namespace().empty() || parser->element_name() != "resources") {
      diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                   << "root element must be <resources>");
      return false;
    }

    error |= !ParseResources(parser);
    break;
  };

  if (parser->event() == xml::XmlPullParser::Event::kBadDocument) {
    diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                 << "xml parser error: " << parser->error());
    return false;
  }
  return !error;
}

bool ResourceParser::ParseResources(xml::XmlPullParser* parser) {
  std::set<ResourceName> stripped_resources;

  bool error = false;
  std::string comment;
  const size_t depth = parser->depth();
  while (xml::XmlPullParser::NextChildNode(parser, depth)) {
    const xml::XmlPullParser::Event event = parser->event();
    if (event == xml::XmlPullParser::Event::kComment) {
      comment = parser->comment();
      continue;
    }

    if (event == xml::XmlPullParser::Event::kText) {
      if (!util::TrimWhitespace(parser->text()).empty()) {
        diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                     << "plain text not allowed here");
        error = true;
      }
      continue;
    }

    CHECK(event == xml::XmlPullParser::Event::kStartElement);

    if (!parser->element_namespace().empty()) {
      // Skip unknown namespace.
      continue;
    }

    std::string element_name = parser->element_name();
    if (element_name == "skip" || element_name == "eat-comment") {
      comment = "";
      continue;
    }

    ParsedResource parsed_resource;
    parsed_resource.config = config_;
    parsed_resource.source = source_.WithLine(parser->line_number());
    parsed_resource.comment = std::move(comment);
    comment.clear();
    if (options_.visibility) {
      parsed_resource.visibility_level = options_.visibility.value();
    }

    // Extract the product name if it exists.
    if (std::optional<StringPiece> maybe_product = xml::FindNonEmptyAttribute(parser, "product")) {
      parsed_resource.product = maybe_product.value().to_string();
    }

    // Parse the resource regardless of product.
    if (!ParseResource(parser, &parsed_resource)) {
      error = true;
      continue;
    }

    if (!AddResourcesToTable(table_, diag_, &parsed_resource)) {
      error = true;
    }
  }

  // Check that we included at least one variant of each stripped resource.
  for (const ResourceName& stripped_resource : stripped_resources) {
    if (!table_->FindResource(stripped_resource)) {
      // Failed to find the resource.
      diag_->Error(DiagMessage(source_) << "resource '" << stripped_resource
                                        << "' was filtered out but no product variant remains");
      error = true;
    }
  }

  return !error;
}

bool ResourceParser::ParseResource(xml::XmlPullParser* parser,
                                   ParsedResource* out_resource) {
  struct ItemTypeFormat {
    ResourceType type;
    uint32_t format;
  };

  using BagParseFunc = std::function<bool(ResourceParser*, xml::XmlPullParser*,
                                          ParsedResource*)>;

  static const auto elToItemMap = ImmutableMap<std::string, ItemTypeFormat>::CreatePreSorted({
      {"bool", {ResourceType::kBool, android::ResTable_map::TYPE_BOOLEAN}},
      {"color", {ResourceType::kColor, android::ResTable_map::TYPE_COLOR}},
      {"configVarying", {ResourceType::kConfigVarying, android::ResTable_map::TYPE_ANY}},
      {"dimen",
       {ResourceType::kDimen,
        android::ResTable_map::TYPE_FLOAT | android::ResTable_map::TYPE_FRACTION |
            android::ResTable_map::TYPE_DIMENSION}},
      {"drawable", {ResourceType::kDrawable, android::ResTable_map::TYPE_COLOR}},
      {"fraction",
       {ResourceType::kFraction,
        android::ResTable_map::TYPE_FLOAT | android::ResTable_map::TYPE_FRACTION |
            android::ResTable_map::TYPE_DIMENSION}},
      {"integer", {ResourceType::kInteger, android::ResTable_map::TYPE_INTEGER}},
      {"string", {ResourceType::kString, android::ResTable_map::TYPE_STRING}},
  });

  static const auto elToBagMap = ImmutableMap<std::string, BagParseFunc>::CreatePreSorted({
      {"add-resource", std::mem_fn(&ResourceParser::ParseAddResource)},
      {"array", std::mem_fn(&ResourceParser::ParseArray)},
      {"attr", std::mem_fn(&ResourceParser::ParseAttr)},
      {"configVarying",
       std::bind(&ResourceParser::ParseStyle, std::placeholders::_1, ResourceType::kConfigVarying,
                 std::placeholders::_2, std::placeholders::_3)},
      {"declare-styleable", std::mem_fn(&ResourceParser::ParseDeclareStyleable)},
      {"integer-array", std::mem_fn(&ResourceParser::ParseIntegerArray)},
      {"java-symbol", std::mem_fn(&ResourceParser::ParseSymbol)},
      {"overlayable", std::mem_fn(&ResourceParser::ParseOverlayable)},
      {"plurals", std::mem_fn(&ResourceParser::ParsePlural)},
      {"public", std::mem_fn(&ResourceParser::ParsePublic)},
      {"public-group", std::mem_fn(&ResourceParser::ParsePublicGroup)},
      {"staging-public-group", std::mem_fn(&ResourceParser::ParseStagingPublicGroup)},
      {"staging-public-group-final", std::mem_fn(&ResourceParser::ParseStagingPublicGroupFinal)},
      {"string-array", std::mem_fn(&ResourceParser::ParseStringArray)},
      {"style", std::bind(&ResourceParser::ParseStyle, std::placeholders::_1, ResourceType::kStyle,
                          std::placeholders::_2, std::placeholders::_3)},
      {"symbol", std::mem_fn(&ResourceParser::ParseSymbol)},
  });

  std::string resource_type = parser->element_name();

  // The value format accepted for this resource.
  uint32_t resource_format = 0u;

  bool can_be_item = true;
  bool can_be_bag = true;
  if (resource_type == "item") {
    can_be_bag = false;

    // The default format for <item> is any. If a format attribute is present, that one will
    // override the default.
    resource_format = android::ResTable_map::TYPE_ANY;

    // Items have their type encoded in the type attribute.
    if (std::optional<StringPiece> maybe_type = xml::FindNonEmptyAttribute(parser, "type")) {
      resource_type = maybe_type.value().to_string();
    } else {
      diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                   << "<item> must have a 'type' attribute");
      return false;
    }

    if (std::optional<StringPiece> maybe_format = xml::FindNonEmptyAttribute(parser, "format")) {
      // An explicit format for this resource was specified. The resource will
      // retain its type in its name, but the accepted value for this type is
      // overridden.
      resource_format = ParseFormatTypeNoEnumsOrFlags(maybe_format.value());
      if (!resource_format) {
        diag_->Error(DiagMessage(out_resource->source)
                     << "'" << maybe_format.value()
                     << "' is an invalid format");
        return false;
      }
    }
  } else if (resource_type == "bag") {
    can_be_item = false;

    // Bags have their type encoded in the type attribute.
    if (std::optional<StringPiece> maybe_type = xml::FindNonEmptyAttribute(parser, "type")) {
      resource_type = maybe_type.value().to_string();
    } else {
      diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                   << "<bag> must have a 'type' attribute");
      return false;
    }
  }

  // Get the name of the resource. This will be checked later, because not all
  // XML elements require a name.
  std::optional<StringPiece> maybe_name = xml::FindNonEmptyAttribute(parser, "name");

  if (resource_type == "id") {
    if (!maybe_name) {
      diag_->Error(DiagMessage(out_resource->source)
                   << "<" << parser->element_name()
                   << "> missing 'name' attribute");
      return false;
    }

    out_resource->name.type =
        ResourceNamedTypeWithDefaultName(ResourceType::kId).ToResourceNamedType();
    out_resource->name.entry = maybe_name.value().to_string();

    // Ids either represent a unique resource id or reference another resource id
    auto item = ParseItem(parser, out_resource, resource_format);
    if (!item) {
      return false;
    }

    String* empty = ValueCast<String>(out_resource->value.get());
    if (empty && *empty->value == "") {
      // If no inner element exists, represent a unique identifier
      out_resource->value = util::make_unique<Id>();
    } else {
      Reference* ref = ValueCast<Reference>(out_resource->value.get());
      if (ref && !ref->name && !ref->id) {
        // A null reference also means there is no inner element when ids are in the form:
        //    <id name="name"/>
        out_resource->value = util::make_unique<Id>();
      } else if (!ref || ref->name.value().type.type != ResourceType::kId) {
        // If an inner element exists, the inner element must be a reference to another resource id
        diag_->Error(DiagMessage(out_resource->source)
                         << "<" << parser->element_name()
                         << "> inner element must either be a resource reference or empty");
        return false;
      }
    }

    return true;
  } else if (resource_type == "macro") {
    if (!maybe_name) {
      diag_->Error(DiagMessage(out_resource->source)
                   << "<" << parser->element_name() << "> missing 'name' attribute");
      return false;
    }

    out_resource->name.type =
        ResourceNamedTypeWithDefaultName(ResourceType::kMacro).ToResourceNamedType();
    out_resource->name.entry = maybe_name.value().to_string();
    return ParseMacro(parser, out_resource);
  }

  if (can_be_item) {
    const auto item_iter = elToItemMap.find(resource_type);
    if (item_iter != elToItemMap.end()) {
      // This is an item, record its type and format and start parsing.

      if (!maybe_name) {
        diag_->Error(DiagMessage(out_resource->source)
                     << "<" << parser->element_name() << "> missing 'name' attribute");
        return false;
      }

      out_resource->name.type =
          ResourceNamedTypeWithDefaultName(item_iter->second.type).ToResourceNamedType();
      out_resource->name.entry = maybe_name.value().to_string();

      // Only use the implied format of the type when there is no explicit format.
      if (resource_format == 0u) {
        resource_format = item_iter->second.format;
      }

      if (!ParseItem(parser, out_resource, resource_format)) {
        return false;
      }
      return true;
    }
  }

  // This might be a bag or something.
  if (can_be_bag) {
    const auto bag_iter = elToBagMap.find(resource_type);
    if (bag_iter != elToBagMap.end()) {
      // Ensure we have a name (unless this is a <public-group> or <overlayable>).
      if (resource_type != kPublicGroupTag && resource_type != kStagingPublicGroupTag &&
          resource_type != kStagingPublicGroupFinalTag && resource_type != "overlayable") {
        if (!maybe_name) {
          diag_->Error(DiagMessage(out_resource->source)
                       << "<" << parser->element_name() << "> missing 'name' attribute");
          return false;
        }

        out_resource->name.entry = maybe_name.value().to_string();
      }

      // Call the associated parse method. The type will be filled in by the
      // parse func.
      if (!bag_iter->second(this, parser, out_resource)) {
        return false;
      }
      return true;
    }
  }

  if (can_be_item) {
    // Try parsing the elementName (or type) as a resource. These shall only be
    // resources like 'layout' or 'xml' and they can only be references.
    std::optional<ResourceNamedTypeRef> parsed_type = ParseResourceNamedType(resource_type);
    if (parsed_type) {
      if (!maybe_name) {
        diag_->Error(DiagMessage(out_resource->source)
                     << "<" << parser->element_name()
                     << "> missing 'name' attribute");
        return false;
      }

      out_resource->name.type = parsed_type->ToResourceNamedType();
      out_resource->name.entry = maybe_name.value().to_string();
      out_resource->value = ParseXml(parser, android::ResTable_map::TYPE_REFERENCE, kNoRawString);
      if (!out_resource->value) {
        diag_->Error(DiagMessage(out_resource->source)
                     << "invalid value for type '" << *parsed_type << "'. Expected a reference");
        return false;
      }
      return true;
    }
  }

  // If the resource type was not recognized, write the error and return false.
  diag_->Error(DiagMessage(out_resource->source)
              << "unknown resource type '" << resource_type << "'");
  return false;
}

bool ResourceParser::ParseItem(xml::XmlPullParser* parser,
                               ParsedResource* out_resource,
                               const uint32_t format) {
  if (format == android::ResTable_map::TYPE_STRING) {
    return ParseString(parser, out_resource);
  }

  out_resource->value = ParseXml(parser, format, kNoRawString);
  if (!out_resource->value) {
    diag_->Error(DiagMessage(out_resource->source) << "invalid "
                                                   << out_resource->name.type);
    return false;
  }
  return true;
}

std::optional<FlattenedXmlSubTree> ResourceParser::CreateFlattenSubTree(
    xml::XmlPullParser* parser) {
  const size_t begin_xml_line = parser->line_number();

  std::string raw_value;
  StyleString style_string;
  std::vector<UntranslatableSection> untranslatable_sections;
  if (!FlattenXmlSubtree(parser, &raw_value, &style_string, &untranslatable_sections)) {
    return {};
  }

  return FlattenedXmlSubTree{.raw_value = raw_value,
                             .style_string = style_string,
                             .untranslatable_sections = untranslatable_sections,
                             .namespace_resolver = parser,
                             .source = source_.WithLine(begin_xml_line)};
}

/**
 * Reads the entire XML subtree and attempts to parse it as some Item,
 * with typeMask denoting which items it can be. If allowRawValue is
 * true, a RawString is returned if the XML couldn't be parsed as
 * an Item. If allowRawValue is false, nullptr is returned in this
 * case.
 */
std::unique_ptr<Item> ResourceParser::ParseXml(xml::XmlPullParser* parser, const uint32_t type_mask,
                                               const bool allow_raw_value) {
  auto sub_tree = CreateFlattenSubTree(parser);
  if (!sub_tree.has_value()) {
    return {};
  }
  return ParseXml(sub_tree.value(), type_mask, allow_raw_value, *table_, config_, *diag_);
}

std::unique_ptr<Item> ResourceParser::ParseXml(const FlattenedXmlSubTree& xmlsub_tree,
                                               const uint32_t type_mask, const bool allow_raw_value,
                                               ResourceTable& table,
                                               const android::ConfigDescription& config,
                                               IDiagnostics& diag) {
  if (!xmlsub_tree.style_string.spans.empty()) {
    // This can only be a StyledString.
    std::unique_ptr<StyledString> styled_string =
        util::make_unique<StyledString>(table.string_pool.MakeRef(
            xmlsub_tree.style_string,
            StringPool::Context(StringPool::Context::kNormalPriority, config)));
    styled_string->untranslatable_sections = xmlsub_tree.untranslatable_sections;
    return std::move(styled_string);
  }

  auto on_create_reference = [&](const ResourceName& name) {
    // name.package can be empty here, as it will assume the package name of the
    // table.
    auto id = util::make_unique<Id>();
    id->SetSource(xmlsub_tree.source);
    return table.AddResource(NewResourceBuilder(name).SetValue(std::move(id)).Build(), &diag);
  };

  // Process the raw value.
  std::unique_ptr<Item> processed_item = ResourceUtils::TryParseItemForAttribute(
      xmlsub_tree.raw_value, type_mask, on_create_reference);
  if (processed_item) {
    // Fix up the reference.
    if (auto ref = ValueCast<Reference>(processed_item.get())) {
      ref->allow_raw = allow_raw_value;
      ResolvePackage(xmlsub_tree.namespace_resolver, ref);
    }
    return processed_item;
  }

  // Try making a regular string.
  if (type_mask & android::ResTable_map::TYPE_STRING) {
    // Use the trimmed, escaped string.
    std::unique_ptr<String> string = util::make_unique<String>(
        table.string_pool.MakeRef(xmlsub_tree.style_string.str, StringPool::Context(config)));
    string->untranslatable_sections = xmlsub_tree.untranslatable_sections;
    return std::move(string);
  }

  if (allow_raw_value) {
    // We can't parse this so return a RawString if we are allowed.
    return util::make_unique<RawString>(table.string_pool.MakeRef(
        util::TrimWhitespace(xmlsub_tree.raw_value), StringPool::Context(config)));
  } else if (util::TrimWhitespace(xmlsub_tree.raw_value).empty()) {
    // If the text is empty, and the value is not allowed to be a string, encode it as a @null.
    return ResourceUtils::MakeNull();
  }
  return {};
}

bool ResourceParser::ParseString(xml::XmlPullParser* parser,
                                 ParsedResource* out_resource) {
  bool formatted = true;
  if (std::optional<StringPiece> formatted_attr = xml::FindAttribute(parser, "formatted")) {
    std::optional<bool> maybe_formatted = ResourceUtils::ParseBool(formatted_attr.value());
    if (!maybe_formatted) {
      diag_->Error(DiagMessage(out_resource->source)
                   << "invalid value for 'formatted'. Must be a boolean");
      return false;
    }
    formatted = maybe_formatted.value();
  }

  bool translatable = options_.translatable;
  if (std::optional<StringPiece> translatable_attr = xml::FindAttribute(parser, "translatable")) {
    std::optional<bool> maybe_translatable = ResourceUtils::ParseBool(translatable_attr.value());
    if (!maybe_translatable) {
      diag_->Error(DiagMessage(out_resource->source)
                   << "invalid value for 'translatable'. Must be a boolean");
      return false;
    }
    translatable = maybe_translatable.value();
  }

  out_resource->value =
      ParseXml(parser, android::ResTable_map::TYPE_STRING, kNoRawString);
  if (!out_resource->value) {
    diag_->Error(DiagMessage(out_resource->source) << "not a valid string");
    return false;
  }

  if (String* string_value = ValueCast<String>(out_resource->value.get())) {
    string_value->SetTranslatable(translatable);

    if (formatted && translatable) {
      if (!util::VerifyJavaStringFormat(*string_value->value)) {
        if (options_.error_on_positional_arguments) {
          DiagMessage msg(out_resource->source);
          msg << "multiple substitutions specified in non-positional format; "
                 "did you mean to add the formatted=\"false\" attribute?";

          diag_->Error(msg);
          return false;
        }
      }
    }

  } else if (StyledString* string_value = ValueCast<StyledString>(out_resource->value.get())) {
    string_value->SetTranslatable(translatable);
  }
  return true;
}

bool ResourceParser::ParseMacro(xml::XmlPullParser* parser, ParsedResource* out_resource) {
  auto sub_tree = CreateFlattenSubTree(parser);
  if (!sub_tree) {
    return false;
  }

  if (out_resource->config != ConfigDescription::DefaultConfig()) {
    diag_->Error(DiagMessage(out_resource->source)
                 << "<macro> tags cannot be declared in configurations other than the default "
                    "configuration'");
    return false;
  }

  auto macro = std::make_unique<Macro>();
  macro->raw_value = std::move(sub_tree->raw_value);
  macro->style_string = std::move(sub_tree->style_string);
  macro->untranslatable_sections = std::move(sub_tree->untranslatable_sections);

  for (const auto& decl : parser->package_decls()) {
    macro->alias_namespaces.emplace_back(
        Macro::Namespace{.alias = decl.prefix,
                         .package_name = decl.package.package,
                         .is_private = decl.package.private_namespace});
  }

  out_resource->value = std::move(macro);
  return true;
}

bool ResourceParser::ParsePublic(xml::XmlPullParser* parser, ParsedResource* out_resource) {
  if (options_.visibility) {
    diag_->Error(DiagMessage(out_resource->source)
                 << "<public> tag not allowed with --visibility flag");
    return false;
  }

  if (out_resource->config != ConfigDescription::DefaultConfig()) {
    diag_->Warn(DiagMessage(out_resource->source)
                << "ignoring configuration '" << out_resource->config << "' for <public> tag");
  }

  std::optional<StringPiece> maybe_type = xml::FindNonEmptyAttribute(parser, "type");
  if (!maybe_type) {
    diag_->Error(DiagMessage(out_resource->source)
                 << "<public> must have a 'type' attribute");
    return false;
  }

  std::optional<ResourceNamedTypeRef> parsed_type = ParseResourceNamedType(maybe_type.value());
  if (!parsed_type) {
    diag_->Error(DiagMessage(out_resource->source) << "invalid resource type '"
                                                   << maybe_type.value()
                                                   << "' in <public>");
    return false;
  }

  out_resource->name.type = parsed_type->ToResourceNamedType();

  if (std::optional<StringPiece> maybe_id_str = xml::FindNonEmptyAttribute(parser, "id")) {
    std::optional<ResourceId> maybe_id = ResourceUtils::ParseResourceId(maybe_id_str.value());
    if (!maybe_id) {
      diag_->Error(DiagMessage(out_resource->source)
                   << "invalid resource ID '" << maybe_id_str.value() << "' in <public>");
      return false;
    }
    out_resource->id = maybe_id.value();
  }

  if (parsed_type->type == ResourceType::kId) {
    // An ID marked as public is also the definition of an ID.
    out_resource->value = util::make_unique<Id>();
  }

  out_resource->visibility_level = Visibility::Level::kPublic;
  return true;
}

template <typename Func>
bool static ParseGroupImpl(xml::XmlPullParser* parser, ParsedResource* out_resource,
                           const char* tag_name, IDiagnostics* diag, Func&& func) {
  if (out_resource->config != ConfigDescription::DefaultConfig()) {
    diag->Warn(DiagMessage(out_resource->source)
               << "ignoring configuration '" << out_resource->config << "' for <" << tag_name
               << "> tag");
  }

  std::optional<StringPiece> maybe_type = xml::FindNonEmptyAttribute(parser, "type");
  if (!maybe_type) {
    diag->Error(DiagMessage(out_resource->source)
                << "<" << tag_name << "> must have a 'type' attribute");
    return false;
  }

  std::optional<ResourceNamedTypeRef> parsed_type = ParseResourceNamedType(maybe_type.value());
  if (!parsed_type) {
    diag->Error(DiagMessage(out_resource->source)
                << "invalid resource type '" << maybe_type.value() << "' in <" << tag_name << ">");
    return false;
  }

  std::optional<StringPiece> maybe_id_str = xml::FindNonEmptyAttribute(parser, "first-id");
  if (!maybe_id_str) {
    diag->Error(DiagMessage(out_resource->source)
                << "<" << tag_name << "> must have a 'first-id' attribute");
    return false;
  }

  std::optional<ResourceId> maybe_id = ResourceUtils::ParseResourceId(maybe_id_str.value());
  if (!maybe_id) {
    diag->Error(DiagMessage(out_resource->source)
                << "invalid resource ID '" << maybe_id_str.value() << "' in <" << tag_name << ">");
    return false;
  }

  std::string comment;
  ResourceId next_id = maybe_id.value();
  bool error = false;
  const size_t depth = parser->depth();
  while (xml::XmlPullParser::NextChildNode(parser, depth)) {
    if (parser->event() == xml::XmlPullParser::Event::kComment) {
      comment = util::TrimWhitespace(parser->comment()).to_string();
      continue;
    } else if (parser->event() != xml::XmlPullParser::Event::kStartElement) {
      // Skip text.
      continue;
    }

    const Source item_source = out_resource->source.WithLine(parser->line_number());
    const std::string& element_namespace = parser->element_namespace();
    const std::string& element_name = parser->element_name();
    if (element_namespace.empty() && element_name == "public") {
      auto maybe_name = xml::FindNonEmptyAttribute(parser, "name");
      if (!maybe_name) {
        diag->Error(DiagMessage(item_source) << "<public> must have a 'name' attribute");
        error = true;
        continue;
      }

      if (xml::FindNonEmptyAttribute(parser, "id")) {
        diag->Error(DiagMessage(item_source) << "'id' is ignored within <" << tag_name << ">");
        error = true;
        continue;
      }

      if (xml::FindNonEmptyAttribute(parser, "type")) {
        diag->Error(DiagMessage(item_source) << "'type' is ignored within <" << tag_name << ">");
        error = true;
        continue;
      }

      if (maybe_name.value().substr(0, std::strlen("removed_")) == "removed_") {
        // Skip resources that have been removed from the framework, but leave a hole so that
        // other staged resources don't shift and break apps previously compiled against them
        next_id.id++;
        continue;
      }

      ParsedResource& entry_res = out_resource->child_resources.emplace_back(ParsedResource{
          .name = ResourceName{{}, *parsed_type, maybe_name.value().to_string()},
          .source = item_source,
          .comment = std::move(comment),
      });
      comment.clear();

      // Execute group specific code.
      func(entry_res, next_id);

      next_id.id++;
    } else if (!ShouldIgnoreElement(element_namespace, element_name)) {
      diag->Error(DiagMessage(item_source) << ":" << element_name << ">");
      error = true;
    }
  }
  return !error;
}

bool ResourceParser::ParseStagingPublicGroup(xml::XmlPullParser* parser,
                                             ParsedResource* out_resource) {
  return ParseGroupImpl(parser, out_resource, kStagingPublicGroupTag, diag_,
                        [](ParsedResource& parsed_entry, ResourceId id) {
                          parsed_entry.id = id;
                          parsed_entry.staged_api = true;
                          parsed_entry.visibility_level = Visibility::Level::kPublic;
                        });
}

bool ResourceParser::ParseStagingPublicGroupFinal(xml::XmlPullParser* parser,
                                                  ParsedResource* out_resource) {
  return ParseGroupImpl(parser, out_resource, kStagingPublicGroupFinalTag, diag_,
                        [](ParsedResource& parsed_entry, ResourceId id) {
                          parsed_entry.staged_alias = StagedId{id, parsed_entry.source};
                        });
}

bool ResourceParser::ParsePublicGroup(xml::XmlPullParser* parser, ParsedResource* out_resource) {
  if (options_.visibility) {
    diag_->Error(DiagMessage(out_resource->source)
                 << "<" << kPublicGroupTag << "> tag not allowed with --visibility flag");
    return false;
  }

  return ParseGroupImpl(parser, out_resource, kPublicGroupTag, diag_,
                        [](ParsedResource& parsed_entry, ResourceId id) {
                          parsed_entry.id = id;
                          parsed_entry.visibility_level = Visibility::Level::kPublic;
                        });
}

bool ResourceParser::ParseSymbolImpl(xml::XmlPullParser* parser,
                                     ParsedResource* out_resource) {
  std::optional<StringPiece> maybe_type = xml::FindNonEmptyAttribute(parser, "type");
  if (!maybe_type) {
    diag_->Error(DiagMessage(out_resource->source)
                 << "<" << parser->element_name()
                 << "> must have a 'type' attribute");
    return false;
  }

  std::optional<ResourceNamedTypeRef> parsed_type = ParseResourceNamedType(maybe_type.value());
  if (!parsed_type) {
    diag_->Error(DiagMessage(out_resource->source)
                 << "invalid resource type '" << maybe_type.value() << "' in <"
                 << parser->element_name() << ">");
    return false;
  }

  out_resource->name.type = parsed_type->ToResourceNamedType();
  return true;
}

bool ResourceParser::ParseSymbol(xml::XmlPullParser* parser, ParsedResource* out_resource) {
  if (options_.visibility) {
    diag_->Error(DiagMessage(out_resource->source)
                 << "<java-symbol> and <symbol> tags not allowed with --visibility flag");
    return false;
  }
  if (out_resource->config != ConfigDescription::DefaultConfig()) {
    diag_->Warn(DiagMessage(out_resource->source)
                << "ignoring configuration '" << out_resource->config << "' for <"
                << parser->element_name() << "> tag");
  }

  if (!ParseSymbolImpl(parser, out_resource)) {
    return false;
  }

  out_resource->visibility_level = Visibility::Level::kPrivate;
  return true;
}

bool ResourceParser::ParseOverlayable(xml::XmlPullParser* parser, ParsedResource* out_resource) {
  if (out_resource->config != ConfigDescription::DefaultConfig()) {
    diag_->Warn(DiagMessage(out_resource->source)
                << "ignoring configuration '" << out_resource->config
                << "' for <overlayable> tag");
  }

  std::optional<StringPiece> overlayable_name = xml::FindNonEmptyAttribute(parser, "name");
  if (!overlayable_name) {
    diag_->Error(DiagMessage(out_resource->source)
                  << "<overlayable> tag must have a 'name' attribute");
    return false;
  }

  const std::string kActorUriScheme =
      android::base::StringPrintf("%s://", Overlayable::kActorScheme);
  std::optional<StringPiece> overlayable_actor = xml::FindNonEmptyAttribute(parser, "actor");
  if (overlayable_actor && !util::StartsWith(overlayable_actor.value(), kActorUriScheme)) {
    diag_->Error(DiagMessage(out_resource->source)
                 << "specified <overlayable> tag 'actor' attribute must use the scheme '"
                 << Overlayable::kActorScheme << "'");
    return false;
  }

  // Create a overlayable entry grouping that represents this <overlayable>
  auto overlayable = std::make_shared<Overlayable>(
      overlayable_name.value(), (overlayable_actor) ? overlayable_actor.value() : "",
      source_);

  bool error = false;
  std::string comment;
  PolicyFlags current_policies = PolicyFlags::NONE;
  const size_t start_depth = parser->depth();
  while (xml::XmlPullParser::IsGoodEvent(parser->Next())) {
    xml::XmlPullParser::Event event = parser->event();
    if (event == xml::XmlPullParser::Event::kEndElement && parser->depth() == start_depth) {
      // Break the loop when exiting the <overlayable>
      break;
    } else if (event == xml::XmlPullParser::Event::kEndElement
               && parser->depth() == start_depth + 1) {
      // Clear the current policies when exiting the <policy> tags
      current_policies = PolicyFlags::NONE;
      continue;
    } else if (event == xml::XmlPullParser::Event::kComment) {
      // Retrieve the comment of individual <item> tags
      comment = parser->comment();
      continue;
    } else if (event != xml::XmlPullParser::Event::kStartElement) {
      // Skip to the start of the next element
      continue;
    }

    const Source element_source = source_.WithLine(parser->line_number());
    const std::string& element_name = parser->element_name();
    const std::string& element_namespace = parser->element_namespace();
    if (element_namespace.empty() && element_name == "item") {
      if (current_policies == PolicyFlags::NONE) {
        diag_->Error(DiagMessage(element_source)
                         << "<item> within an <overlayable> must be inside a <policy> block");
        error = true;
        continue;
      }

      // Items specify the name and type of resource that should be overlayable
      std::optional<StringPiece> item_name = xml::FindNonEmptyAttribute(parser, "name");
      if (!item_name) {
        diag_->Error(DiagMessage(element_source)
                     << "<item> within an <overlayable> must have a 'name' attribute");
        error = true;
        continue;
      }

      std::optional<StringPiece> item_type = xml::FindNonEmptyAttribute(parser, "type");
      if (!item_type) {
        diag_->Error(DiagMessage(element_source)
                     << "<item> within an <overlayable> must have a 'type' attribute");
        error = true;
        continue;
      }

      std::optional<ResourceNamedTypeRef> type = ParseResourceNamedType(item_type.value());
      if (!type) {
        diag_->Error(DiagMessage(element_source)
                     << "invalid resource type '" << item_type.value()
                     << "' in <item> within an <overlayable>");
        error = true;
        continue;
      }

      OverlayableItem overlayable_item(overlayable);
      overlayable_item.policies = current_policies;
      overlayable_item.comment = comment;
      overlayable_item.source = element_source;

      ParsedResource child_resource{};
      child_resource.name.type = type->ToResourceNamedType();
      child_resource.name.entry = item_name.value().to_string();
      child_resource.overlayable_item = overlayable_item;
      out_resource->child_resources.push_back(std::move(child_resource));

    } else if (element_namespace.empty() && element_name == "policy") {
      if (current_policies != PolicyFlags::NONE) {
        // If the policy list is not empty, then we are currently inside a policy element
        diag_->Error(DiagMessage(element_source) << "<policy> blocks cannot be recursively nested");
        error = true;
        break;
      } else if (std::optional<StringPiece> maybe_type =
                     xml::FindNonEmptyAttribute(parser, "type")) {
        // Parse the polices separated by vertical bar characters to allow for specifying multiple
        // policies. Items within the policy tag will have the specified policy.
        for (const StringPiece& part : util::Tokenize(maybe_type.value(), '|')) {
          StringPiece trimmed_part = util::TrimWhitespace(part);
          const auto policy = std::find_if(kPolicyStringToFlag.begin(),
                                           kPolicyStringToFlag.end(),
                                           [trimmed_part](const auto& it) {
                                             return trimmed_part == it.first;
                                           });
          if (policy == kPolicyStringToFlag.end()) {
            diag_->Error(DiagMessage(element_source)
                         << "<policy> has unsupported type '" << trimmed_part << "'");
            error = true;
            continue;
          }

          current_policies |= policy->second;
        }
      } else {
        diag_->Error(DiagMessage(element_source)
                     << "<policy> must have a 'type' attribute");
        error = true;
        continue;
      }
    } else if (!ShouldIgnoreElement(element_namespace, element_name)) {
      diag_->Error(DiagMessage(element_source) << "invalid element <" << element_name << "> "
                                               << " in <overlayable>");
      error = true;
      break;
    }

    comment.clear();
  }

  return !error;
}

bool ResourceParser::ParseAddResource(xml::XmlPullParser* parser, ParsedResource* out_resource) {
  if (ParseSymbolImpl(parser, out_resource)) {
    out_resource->visibility_level = Visibility::Level::kUndefined;
    out_resource->allow_new = true;
    return true;
  }
  return false;
}

bool ResourceParser::ParseAttr(xml::XmlPullParser* parser,
                               ParsedResource* out_resource) {
  return ParseAttrImpl(parser, out_resource, false);
}

bool ResourceParser::ParseAttrImpl(xml::XmlPullParser* parser,
                                   ParsedResource* out_resource, bool weak) {
  out_resource->name.type =
      ResourceNamedTypeWithDefaultName(ResourceType::kAttr).ToResourceNamedType();

  // Attributes only end up in default configuration.
  if (out_resource->config != ConfigDescription::DefaultConfig()) {
    diag_->Warn(DiagMessage(out_resource->source)
                << "ignoring configuration '" << out_resource->config
                << "' for attribute " << out_resource->name);
    out_resource->config = ConfigDescription::DefaultConfig();
  }

  uint32_t type_mask = 0;

  std::optional<StringPiece> maybe_format = xml::FindAttribute(parser, "format");
  if (maybe_format) {
    type_mask = ParseFormatAttribute(maybe_format.value());
    if (type_mask == 0) {
      diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                   << "invalid attribute format '" << maybe_format.value() << "'");
      return false;
    }
  }

  std::optional<int32_t> maybe_min, maybe_max;

  if (std::optional<StringPiece> maybe_min_str = xml::FindAttribute(parser, "min")) {
    StringPiece min_str = util::TrimWhitespace(maybe_min_str.value());
    if (!min_str.empty()) {
      std::u16string min_str16 = util::Utf8ToUtf16(min_str);
      android::Res_value value;
      if (android::ResTable::stringToInt(min_str16.data(), min_str16.size(), &value)) {
        maybe_min = static_cast<int32_t>(value.data);
      }
    }

    if (!maybe_min) {
      diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                   << "invalid 'min' value '" << min_str << "'");
      return false;
    }
  }

  if (std::optional<StringPiece> maybe_max_str = xml::FindAttribute(parser, "max")) {
    StringPiece max_str = util::TrimWhitespace(maybe_max_str.value());
    if (!max_str.empty()) {
      std::u16string max_str16 = util::Utf8ToUtf16(max_str);
      android::Res_value value;
      if (android::ResTable::stringToInt(max_str16.data(), max_str16.size(), &value)) {
        maybe_max = static_cast<int32_t>(value.data);
      }
    }

    if (!maybe_max) {
      diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                   << "invalid 'max' value '" << max_str << "'");
      return false;
    }
  }

  if ((maybe_min || maybe_max) &&
      (type_mask & android::ResTable_map::TYPE_INTEGER) == 0) {
    diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                 << "'min' and 'max' can only be used when format='integer'");
    return false;
  }

  struct SymbolComparator {
    bool operator()(const Attribute::Symbol& a, const Attribute::Symbol& b) const {
      return a.symbol.name.value() < b.symbol.name.value();
    }
  };

  std::set<Attribute::Symbol, SymbolComparator> items;

  std::string comment;
  bool error = false;
  const size_t depth = parser->depth();
  while (xml::XmlPullParser::NextChildNode(parser, depth)) {
    if (parser->event() == xml::XmlPullParser::Event::kComment) {
      comment = util::TrimWhitespace(parser->comment()).to_string();
      continue;
    } else if (parser->event() != xml::XmlPullParser::Event::kStartElement) {
      // Skip text.
      continue;
    }

    const Source item_source = source_.WithLine(parser->line_number());
    const std::string& element_namespace = parser->element_namespace();
    const std::string& element_name = parser->element_name();
    if (element_namespace.empty() && (element_name == "flag" || element_name == "enum")) {
      if (element_name == "enum") {
        if (type_mask & android::ResTable_map::TYPE_FLAGS) {
          diag_->Error(DiagMessage(item_source)
                       << "can not define an <enum>; already defined a <flag>");
          error = true;
          continue;
        }
        type_mask |= android::ResTable_map::TYPE_ENUM;

      } else if (element_name == "flag") {
        if (type_mask & android::ResTable_map::TYPE_ENUM) {
          diag_->Error(DiagMessage(item_source)
                       << "can not define a <flag>; already defined an <enum>");
          error = true;
          continue;
        }
        type_mask |= android::ResTable_map::TYPE_FLAGS;
      }

      if (std::optional<Attribute::Symbol> s = ParseEnumOrFlagItem(parser, element_name)) {
        Attribute::Symbol& symbol = s.value();
        ParsedResource child_resource;
        child_resource.name = symbol.symbol.name.value();
        child_resource.source = item_source;
        child_resource.value = util::make_unique<Id>();
        if (options_.visibility) {
          child_resource.visibility_level = options_.visibility.value();
        }
        out_resource->child_resources.push_back(std::move(child_resource));

        symbol.symbol.SetComment(std::move(comment));
        symbol.symbol.SetSource(item_source);

        auto insert_result = items.insert(std::move(symbol));
        if (!insert_result.second) {
          const Attribute::Symbol& existing_symbol = *insert_result.first;
          diag_->Error(DiagMessage(item_source)
                       << "duplicate symbol '"
                       << existing_symbol.symbol.name.value().entry << "'");

          diag_->Note(DiagMessage(existing_symbol.symbol.GetSource())
                      << "first defined here");
          error = true;
        }
      } else {
        error = true;
      }
    } else if (!ShouldIgnoreElement(element_namespace, element_name)) {
      diag_->Error(DiagMessage(item_source) << ":" << element_name << ">");
      error = true;
    }

    comment = {};
  }

  if (error) {
    return false;
  }

  std::unique_ptr<Attribute> attr = util::make_unique<Attribute>(
      type_mask ? type_mask : uint32_t{android::ResTable_map::TYPE_ANY});
  attr->SetWeak(weak);
  attr->symbols = std::vector<Attribute::Symbol>(items.begin(), items.end());
  attr->min_int = maybe_min.value_or(std::numeric_limits<int32_t>::min());
  attr->max_int = maybe_max.value_or(std::numeric_limits<int32_t>::max());
  out_resource->value = std::move(attr);
  return true;
}

std::optional<Attribute::Symbol> ResourceParser::ParseEnumOrFlagItem(xml::XmlPullParser* parser,
                                                                     const StringPiece& tag) {
  const Source source = source_.WithLine(parser->line_number());

  std::optional<StringPiece> maybe_name = xml::FindNonEmptyAttribute(parser, "name");
  if (!maybe_name) {
    diag_->Error(DiagMessage(source) << "no attribute 'name' found for tag <"
                                     << tag << ">");
    return {};
  }

  std::optional<StringPiece> maybe_value = xml::FindNonEmptyAttribute(parser, "value");
  if (!maybe_value) {
    diag_->Error(DiagMessage(source) << "no attribute 'value' found for tag <"
                                     << tag << ">");
    return {};
  }

  std::u16string value16 = util::Utf8ToUtf16(maybe_value.value());
  android::Res_value val;
  if (!android::ResTable::stringToInt(value16.data(), value16.size(), &val)) {
    diag_->Error(DiagMessage(source) << "invalid value '" << maybe_value.value()
                                     << "' for <" << tag
                                     << ">; must be an integer");
    return {};
  }

  return Attribute::Symbol{
      Reference(ResourceNameRef({}, ResourceNamedTypeWithDefaultName(ResourceType::kId),
                                maybe_name.value())),
      val.data, val.dataType};
}

bool ResourceParser::ParseStyleItem(xml::XmlPullParser* parser, Style* style) {
  const Source source = source_.WithLine(parser->line_number());

  std::optional<StringPiece> maybe_name = xml::FindNonEmptyAttribute(parser, "name");
  if (!maybe_name) {
    diag_->Error(DiagMessage(source) << "<item> must have a 'name' attribute");
    return false;
  }

  std::optional<Reference> maybe_key = ResourceUtils::ParseXmlAttributeName(maybe_name.value());
  if (!maybe_key) {
    diag_->Error(DiagMessage(source) << "invalid attribute name '" << maybe_name.value() << "'");
    return false;
  }

  ResolvePackage(parser, &maybe_key.value());
  maybe_key.value().SetSource(source);

  std::unique_ptr<Item> value = ParseXml(parser, 0, kAllowRawString);
  if (!value) {
    diag_->Error(DiagMessage(source) << "could not parse style item");
    return false;
  }

  style->entries.push_back(Style::Entry{std::move(maybe_key.value()), std::move(value)});
  return true;
}

bool ResourceParser::ParseStyle(const ResourceType type, xml::XmlPullParser* parser,
                                ParsedResource* out_resource) {
  out_resource->name.type = ResourceNamedTypeWithDefaultName(type).ToResourceNamedType();

  std::unique_ptr<Style> style = util::make_unique<Style>();

  std::optional<StringPiece> maybe_parent = xml::FindAttribute(parser, "parent");
  if (maybe_parent) {
    // If the parent is empty, we don't have a parent, but we also don't infer either.
    if (!maybe_parent.value().empty()) {
      std::string err_str;
      style->parent = ResourceUtils::ParseStyleParentReference(maybe_parent.value(), &err_str);
      if (!style->parent) {
        diag_->Error(DiagMessage(out_resource->source) << err_str);
        return false;
      }

      // Transform the namespace prefix to the actual package name, and mark the reference as
      // private if appropriate.
      ResolvePackage(parser, &style->parent.value());
    }

  } else {
    // No parent was specified, so try inferring it from the style name.
    std::string style_name = out_resource->name.entry;
    size_t pos = style_name.find_last_of(u'.');
    if (pos != std::string::npos) {
      style->parent_inferred = true;
      style->parent = Reference(ResourceName(
          {}, ResourceNamedTypeWithDefaultName(ResourceType::kStyle), style_name.substr(0, pos)));
    }
  }

  bool error = false;
  const size_t depth = parser->depth();
  while (xml::XmlPullParser::NextChildNode(parser, depth)) {
    if (parser->event() != xml::XmlPullParser::Event::kStartElement) {
      // Skip text and comments.
      continue;
    }

    const std::string& element_namespace = parser->element_namespace();
    const std::string& element_name = parser->element_name();
    if (element_namespace == "" && element_name == "item") {
      error |= !ParseStyleItem(parser, style.get());

    } else if (!ShouldIgnoreElement(element_namespace, element_name)) {
      diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                   << ":" << element_name << ">");
      error = true;
    }
  }

  if (error) {
    return false;
  }

  out_resource->value = std::move(style);
  return true;
}

bool ResourceParser::ParseArray(xml::XmlPullParser* parser, ParsedResource* out_resource) {
  uint32_t resource_format = android::ResTable_map::TYPE_ANY;
  if (std::optional<StringPiece> format_attr = xml::FindNonEmptyAttribute(parser, "format")) {
    resource_format = ParseFormatTypeNoEnumsOrFlags(format_attr.value());
    if (resource_format == 0u) {
      diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                   << "'" << format_attr.value() << "' is an invalid format");
      return false;
    }
  }
  return ParseArrayImpl(parser, out_resource, resource_format);
}

bool ResourceParser::ParseIntegerArray(xml::XmlPullParser* parser, ParsedResource* out_resource) {
  return ParseArrayImpl(parser, out_resource, android::ResTable_map::TYPE_INTEGER);
}

bool ResourceParser::ParseStringArray(xml::XmlPullParser* parser, ParsedResource* out_resource) {
  return ParseArrayImpl(parser, out_resource, android::ResTable_map::TYPE_STRING);
}

bool ResourceParser::ParseArrayImpl(xml::XmlPullParser* parser,
                                    ParsedResource* out_resource,
                                    const uint32_t typeMask) {
  out_resource->name.type =
      ResourceNamedTypeWithDefaultName(ResourceType::kArray).ToResourceNamedType();

  std::unique_ptr<Array> array = util::make_unique<Array>();

  bool translatable = options_.translatable;
  if (std::optional<StringPiece> translatable_attr = xml::FindAttribute(parser, "translatable")) {
    std::optional<bool> maybe_translatable = ResourceUtils::ParseBool(translatable_attr.value());
    if (!maybe_translatable) {
      diag_->Error(DiagMessage(out_resource->source)
                   << "invalid value for 'translatable'. Must be a boolean");
      return false;
    }
    translatable = maybe_translatable.value();
  }
  array->SetTranslatable(translatable);

  bool error = false;
  const size_t depth = parser->depth();
  while (xml::XmlPullParser::NextChildNode(parser, depth)) {
    if (parser->event() != xml::XmlPullParser::Event::kStartElement) {
      // Skip text and comments.
      continue;
    }

    const Source item_source = source_.WithLine(parser->line_number());
    const std::string& element_namespace = parser->element_namespace();
    const std::string& element_name = parser->element_name();
    if (element_namespace.empty() && element_name == "item") {
      std::unique_ptr<Item> item = ParseXml(parser, typeMask, kNoRawString);
      if (!item) {
        diag_->Error(DiagMessage(item_source) << "could not parse array item");
        error = true;
        continue;
      }
      item->SetSource(item_source);
      array->elements.emplace_back(std::move(item));

    } else if (!ShouldIgnoreElement(element_namespace, element_name)) {
      diag_->Error(DiagMessage(source_.WithLine(parser->line_number()))
                   << "unknown tag <" << element_namespace << ":"
                   << element_name << ">");
      error = true;
    }
  }

  if (error) {
    return false;
  }

  out_resource->value = std::move(array);
  return true;
}

bool ResourceParser::ParsePlural(xml::XmlPullParser* parser,
                                 ParsedResource* out_resource) {
  out_resource->name.type =
      ResourceNamedTypeWithDefaultName(ResourceType::kPlurals).ToResourceNamedType();

  std::unique_ptr<Plural> plural = util::make_unique<Plural>();

  bool error = false;
  const size_t depth = parser->depth();
  while (xml::XmlPullParser::NextChildNode(parser, depth)) {
    if (parser->event() != xml::XmlPullParser::Event::kStartElement) {
      // Skip text and comments.
      continue;
    }

    const Source item_source = source_.WithLine(parser->line_number());
    const std::string& element_namespace = parser->element_namespace();
    const std::string& element_name = parser->element_name();
    if (element_namespace.empty() && element_name == "item") {
      std::optional<StringPiece> maybe_quantity = xml::FindNonEmptyAttribute(parser, "quantity");
      if (!maybe_quantity) {
        diag_->Error(DiagMessage(item_source)
                     << "<item> in <plurals> requires attribute "
                     << "'quantity'");
        error = true;
        continue;
      }

      StringPiece trimmed_quantity =
          util::TrimWhitespace(maybe_quantity.value());
      size_t index = 0;
      if (trimmed_quantity == "zero") {
        index = Plural::Zero;
      } else if (trimmed_quantity == "one") {
        index = Plural::One;
      } else if (trimmed_quantity == "two") {
        index = Plural::Two;
      } else if (trimmed_quantity == "few") {
        index = Plural::Few;
      } else if (trimmed_quantity == "many") {
        index = Plural::Many;
      } else if (trimmed_quantity == "other") {
        index = Plural::Other;
      } else {
        diag_->Error(DiagMessage(item_source)
                     << "<item> in <plural> has invalid value '"
                     << trimmed_quantity << "' for attribute 'quantity'");
        error = true;
        continue;
      }

      if (plural->values[index]) {
        diag_->Error(DiagMessage(item_source) << "duplicate quantity '"
                                              << trimmed_quantity << "'");
        error = true;
        continue;
      }

      if (!(plural->values[index] = ParseXml(
                parser, android::ResTable_map::TYPE_STRING, kNoRawString))) {
        error = true;
        continue;
      }

      plural->values[index]->SetSource(item_source);

    } else if (!ShouldIgnoreElement(element_namespace, element_name)) {
      diag_->Error(DiagMessage(item_source) << "unknown tag <"
                                            << element_namespace << ":"
                                            << element_name << ">");
      error = true;
    }
  }

  if (error) {
    return false;
  }

  out_resource->value = std::move(plural);
  return true;
}

bool ResourceParser::ParseDeclareStyleable(xml::XmlPullParser* parser,
                                           ParsedResource* out_resource) {
  out_resource->name.type =
      ResourceNamedTypeWithDefaultName(ResourceType::kStyleable).ToResourceNamedType();

  if (!options_.preserve_visibility_of_styleables) {
    // This was added in change Idd21b5de4d20be06c6f8c8eb5a22ccd68afc4927 to mimic aapt1, but no one
    // knows exactly what for.
    //
    // FWIW, styleables only appear in generated R classes.  For custom views these should always be
    // package-private (to be used only by the view class); themes are a different story.
    out_resource->visibility_level = Visibility::Level::kPublic;
  }

  // Declare-styleable only ends up in default config;
  if (out_resource->config != ConfigDescription::DefaultConfig()) {
    diag_->Warn(DiagMessage(out_resource->source)
                << "ignoring configuration '" << out_resource->config
                << "' for styleable " << out_resource->name.entry);
    out_resource->config = ConfigDescription::DefaultConfig();
  }

  std::unique_ptr<Styleable> styleable = util::make_unique<Styleable>();

  std::string comment;
  bool error = false;
  const size_t depth = parser->depth();
  while (xml::XmlPullParser::NextChildNode(parser, depth)) {
    if (parser->event() == xml::XmlPullParser::Event::kComment) {
      comment = util::TrimWhitespace(parser->comment()).to_string();
      continue;
    } else if (parser->event() != xml::XmlPullParser::Event::kStartElement) {
      // Ignore text.
      continue;
    }

    const Source item_source = source_.WithLine(parser->line_number());
    const std::string& element_namespace = parser->element_namespace();
    const std::string& element_name = parser->element_name();
    if (element_namespace.empty() && element_name == "attr") {
      std::optional<StringPiece> maybe_name = xml::FindNonEmptyAttribute(parser, "name");
      if (!maybe_name) {
        diag_->Error(DiagMessage(item_source) << "<attr> tag must have a 'name' attribute");
        error = true;
        continue;
      }

      // If this is a declaration, the package name may be in the name. Separate
      // these out.
      // Eg. <attr name="android:text" />
      std::optional<Reference> maybe_ref = ResourceUtils::ParseXmlAttributeName(maybe_name.value());
      if (!maybe_ref) {
        diag_->Error(DiagMessage(item_source) << "<attr> tag has invalid name '"
                                              << maybe_name.value() << "'");
        error = true;
        continue;
      }

      Reference& child_ref = maybe_ref.value();
      xml::ResolvePackage(parser, &child_ref);

      // Create the ParsedResource that will add the attribute to the table.
      ParsedResource child_resource;
      child_resource.name = child_ref.name.value();
      child_resource.source = item_source;
      child_resource.comment = std::move(comment);
      comment.clear();
      if (options_.visibility) {
        child_resource.visibility_level = options_.visibility.value();
      }

      if (!ParseAttrImpl(parser, &child_resource, true)) {
        error = true;
        continue;
      }

      // Create the reference to this attribute.
      child_ref.SetComment(child_resource.comment);
      child_ref.SetSource(item_source);
      styleable->entries.push_back(std::move(child_ref));

      // Do not add referenced attributes that do not define a format to the table.
      CHECK(child_resource.value != nullptr);
      Attribute* attr = ValueCast<Attribute>(child_resource.value.get());

      CHECK(attr != nullptr);
      if (attr->type_mask != android::ResTable_map::TYPE_ANY) {
        out_resource->child_resources.push_back(std::move(child_resource));
      }

    } else if (!ShouldIgnoreElement(element_namespace, element_name)) {
      diag_->Error(DiagMessage(item_source) << "unknown tag <"
                                            << element_namespace << ":"
                                            << element_name << ">");
      error = true;
    }

    comment = {};
  }

  if (error) {
    return false;
  }

  out_resource->value = std::move(styleable);
  return true;
}

}  // namespace aapt
